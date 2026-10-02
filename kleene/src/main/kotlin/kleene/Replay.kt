package kleene

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE

/**
 * Wraps this Judge so that each answered [Request] and its [Response] are appended to [path] as one JSONL
 * line, together with this Judge's [Judge.id]. The Response is recorded as received, before core validates
 * it. A call that throws records nothing. The returned Judge has the same id, so its Evidence is unchanged.
 * The parent directories of [path] are created now, so a parent that cannot be created fails here, before any
 * model call. Each line is written after this Judge answers, so a line that cannot be written (for example,
 * [path] is a directory or read-only, or a State text or a label is not valid Unicode) throws an `IOException`
 * from `ask` after the model answered: that model call is spent.
 *
 * @throws java.io.IOException if the parent directories of [path] cannot be created.
 */
fun Judge.recordingTo(path: Path): Judge = RecordingJudge(this, path)

private class RecordingJudge(private val judge: Judge, private val path: Path) : Judge {
    init {
        // Files.createDirectories throws on a symlinked parent on some JDK 17 builds (JDK-8294193).
        path.toAbsolutePath().parent?.takeUnless { Files.isDirectory(it) }?.let { Files.createDirectories(it) }
    }

    override val id: String get() = judge.id

    override suspend fun evaluate(request: Request): Response {
        val response = judge.evaluate(request)
        val line = buildJsonObject {
            put("judge", id)
            put("request", request.toRecord())
            put("response", response.toRecord())
        }
        // ponytail: the lock is per wrapper; two wrappers on one path are not excluded.
        synchronized(this) { Files.writeString(path, "$line\n", CREATE, APPEND) }
        return response
    }
}

/**
 * A Judge that returns the Responses recorded by [recordingTo] in [path] and never calls a model. A Request
 * matches a recording when its [State] (text or JSON) and its [WireQuestion]s, in order, are equal. Replay is
 * strict: a Request with no recording throws [IllegalStateException]; it is a test or setup error, not a
 * judge that did not answer, so it is not a [KleeneException]. Core validates each replayed Response as for
 * any Judge. [id] is the recorded judge id, so replayed Evidence equals recorded Evidence.
 *
 * One Request recorded more than once replays when every recording has the same model and answers; usage and
 * request id may differ, and the first recording is replayed. An unknown field outside `request` is ignored.
 *
 * @throws IllegalArgumentException if the recording is missing, unreadable, empty, holds a line that is not a
 *   recording, holds more than one judge id, or holds one Request with two different models or answers.
 */
class ReplayJudge(path: Path) : Judge {
    private val responses: Map<JsonElement, Response>

    override val id: String

    init {
        val lines = try {
            Files.readAllLines(path).withIndex().filter { it.value.isNotBlank() }
        } catch (e: IOException) {
            throw IllegalArgumentException("recording $path cannot be read: $e", e)
        }
        require(lines.isNotEmpty()) { "recording $path is empty" }
        val records = lines.map { (index, line) ->
            try {
                val record = Json.parseToJsonElement(line).jsonObject
                Record(record.getValue("judge").string(), record.getValue("request").jsonObject, record.getValue("response").toResponse())
            } catch (e: RuntimeException) {
                throw IllegalArgumentException("recording $path line ${index + 1} is not a recording: ${e.message}", e)
            }
        }
        val judges = records.map { it.judge }.distinct()
        require(judges.size == 1) { "recording $path holds the judges $judges, not exactly one" }
        id = judges.single()
        responses = records.groupBy({ it.request }, { it.response }).mapValues { (request, recorded) ->
            require(recorded.distinctBy { it.model to it.answers }.size == 1) { "recording $path holds different responses for the request $request" }
            recorded.first()
        }
    }

    private class Record(val judge: String, val request: JsonElement, val response: Response)

    /** @throws IllegalStateException if [request] was not recorded. */
    override suspend fun evaluate(request: Request): Response =
        responses[request.toRecord()] ?: error("no recording for the request ${request.toRecord()}")
}

private fun Request.toRecord(): JsonObject = buildJsonObject {
    putJsonObject("state") {
        when (state) {
            is State.Text -> put("text", state.value)
            is State.Json -> put("json", state.value)
        }
    }
    putJsonArray("questions") {
        questions.forEach { question ->
            add(buildJsonObject {
                put("id", question.id)
                put("name", question.name)
                put("kind", question.kind.name)
                put("instructions", question.instructions)
                putJsonArray("labels") { question.labels.forEach { add(it) } }
            })
        }
    }
}

private fun Response.toRecord(): JsonObject = buildJsonObject {
    put("model", model)
    putJsonObject("answers") { answers.forEach { (id, raw) -> put(id, raw.toRecord()) } }
    put("usage", usage?.let { buildJsonObject { put("inputTokens", it.inputTokens); put("outputTokens", it.outputTokens) } } ?: JsonNull)
    put("requestId", requestId)
}

private fun Raw.toRecord(): JsonObject = buildJsonObject {
    when (this@toRecord) {
        is Raw.Noul -> {
            put("kind", Kind.FEELS.name)
            put("p", p.toRecord())
        }
        is Raw.Choice -> {
            put("kind", Kind.CHOOSE.name)
            putJsonObject("probabilities") { probabilities.forEach { (label, p) -> put(label, p.toRecord()) } }
            put("confidence", confidence?.toRecord() ?: JsonNull)
        }
        is Raw.Score -> {
            put("kind", Kind.SCORE.name)
            put("expected", expected.toRecord())
            putJsonArray("probabilities") { probabilities.forEach { add(it.toRecord()) } }
            put("confidence", confidence?.toRecord() ?: JsonNull)
        }
    }
}

/** A finite double as a JSON number; NaN and the infinities as the strings "NaN", "Infinity", "-Infinity". */
private fun Double.toRecord(): JsonPrimitive = if (isFinite()) JsonPrimitive(this) else JsonPrimitive(toString())

private val NON_FINITE = mapOf("NaN" to Double.NaN, "Infinity" to Double.POSITIVE_INFINITY, "-Infinity" to Double.NEGATIVE_INFINITY)

// The JSON number grammar: the parser also passes tokens such as 0.5f, .5 and a bare NaN, and toDouble reads them.
private val JSON_INTEGER = Regex("""-?(0|[1-9]\d*)""")
private val JSON_NUMBER = Regex("""$JSON_INTEGER(\.\d+)?([eE][+-]?\d+)?""")

private fun JsonElement.string(): String =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("$this is not a JSON string")

private fun JsonElement.number(): Double {
    val primitive = this as? JsonPrimitive ?: throw IllegalArgumentException("$this is not a JSON number")
    val number = if (primitive.isString) NON_FINITE[primitive.content] else primitive.takeIf { JSON_NUMBER.matches(it.content) }?.doubleOrNull
    return number ?: throw IllegalArgumentException("$this is not a JSON number")
}

private fun JsonElement.tokens(): Long =
    (this as? JsonPrimitive)?.takeIf { !it.isString && JSON_INTEGER.matches(it.content) }?.longOrNull
        ?: throw IllegalArgumentException("$this is not a token count")

/** The value under [key], or null when it is absent or JSON null. */
private fun JsonObject.optional(key: String): JsonElement? = get(key)?.takeUnless { it is JsonNull }

private fun JsonElement.toResponse(): Response {
    val record = jsonObject
    return Response(
        model = record.getValue("model").string(),
        answers = record.getValue("answers").jsonObject.mapValues { (_, raw) -> raw.toRaw() },
        usage = record.optional("usage")?.jsonObject?.let { Usage(it.getValue("inputTokens").tokens(), it.getValue("outputTokens").tokens()) },
        requestId = record.optional("requestId")?.string(),
    )
}

private fun JsonElement.toRaw(): Raw {
    val record = jsonObject
    val confidence = record.optional("confidence")?.number()
    return when (Kind.valueOf(record.getValue("kind").string())) {
        Kind.FEELS -> Raw.Noul(record.getValue("p").number())
        Kind.CHOOSE -> Raw.Choice(record.getValue("probabilities").jsonObject.mapValues { it.value.number() }, confidence)
        Kind.SCORE -> Raw.Score(
            record.getValue("expected").number(),
            record.getValue("probabilities").jsonArray.map { it.number() },
            confidence,
        )
    }
}
