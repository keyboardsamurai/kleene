package kleene

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * The [Judge] for any System One server: cloud TypeSafe (`https://api.typesafe.ai`) or a local Kev/openjev
 * (`http://127.0.0.1:8009`). One ask is one `POST {baseUrl}/v1/systemone`. It never falls back from one server
 * to another.
 *
 * It refuses, before sending, what TypeSafe's documented shape does not allow: a choice with fewer than 2 or
 * more than 255 options, a score with fewer than 2 or more than 10 levels ([KleeneException.Unsupported]).
 * [timeout] applies to each attempt. [id] tags the [Evidence] and [Rating] it produces.
 */
class SystemOneJudge(
    val baseUrl: String,
    val model: String,
    val apiKey: String? = null,
    val timeout: Duration = 10.seconds,
    val maxRetries: Int = 2,
    val httpClient: HttpClient = HttpClient.newHttpClient(),
    override val id: String = "${URI(baseUrl).host}/$model",
) : Judge {

    private val endpoint = URI.create(baseUrl.trimEnd('/') + "/v1/systemone")

    override suspend fun evaluate(request: Request): Response {
        request.questions.forEach(::refuseUnsupported)
        return attempt(encode(request))
    }

    /**
     * One HTTP exchange, no retry. Throws [KleeneException.Timeout] (per-attempt timeout or HTTP 408),
     * [KleeneException.RateLimited] (429), [KleeneException.Overloaded] (5xx or an I/O error) and the
     * non-retryable [KleeneException.Authentication], [KleeneException.InvalidRequest] and [KleeneException.Malformed].
     * Cancelling the caller cancels the exchange.
     */
    private suspend fun attempt(body: String): Response {
        val http = HttpRequest.newBuilder(endpoint)
            .timeout(timeout.toJavaDuration())
            .header("Content-Type", "application/json")
            .apply { if (apiKey != null) header("Authorization", "Bearer $apiKey") }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val reply = try {
            httpClient.sendAsync(http, HttpResponse.BodyHandlers.ofString()).awaitCancelling()
        } catch (e: HttpTimeoutException) {
            throw KleeneException.Timeout("$baseUrl did not answer within $timeout", e)
        } catch (e: IOException) {
            throw KleeneException.Overloaded("$baseUrl could not be reached: $e", status = null, cause = e)
        }
        if (reply.statusCode() !in 200..299) throw failure(reply)
        return decode(reply.body(), reply.headers().firstValue("x-typesafe-request-id").orElse(null))
    }

    private fun failure(reply: HttpResponse<String>): KleeneException {
        val status = reply.statusCode()
        val message = "$baseUrl answered HTTP $status: ${serverMessage(reply.body())}"
        return when (status) {
            401, 403 -> KleeneException.Authentication(message, status)
            408 -> KleeneException.Timeout(message)
            429 -> KleeneException.RateLimited(message, retryAfter = null)
            in 500..599 -> KleeneException.Overloaded(message, status)
            else -> KleeneException.InvalidRequest(message)
        }
    }

    private fun encode(request: Request): String = buildJsonObject {
        put("model", model)
        put("state", request.state.toJson())
        putJsonObject("questions") { request.questions.forEach { put(it.id, it.toJson()) } }
    }.toString()

    companion object {
        /**
         * A judge configured by `KLEENE_BASE_URL` (default `https://api.typesafe.ai`), `KLEENE_API_KEY` (optional)
         * and `KLEENE_MODEL` (required).
         *
         * @throws IllegalStateException if `KLEENE_MODEL` is not set.
         */
        fun fromEnv(): SystemOneJudge = fromEnv(System::getenv)

        internal fun fromEnv(env: (String) -> String?): SystemOneJudge {
            fun value(name: String) = env(name)?.takeIf { it.isNotBlank() }
            return SystemOneJudge(
                baseUrl = value("KLEENE_BASE_URL") ?: "https://api.typesafe.ai",
                model = checkNotNull(value("KLEENE_MODEL")) { "KLEENE_MODEL is not set: name the System One model to use" },
                apiKey = value("KLEENE_API_KEY"),
            )
        }
    }
}

private fun refuseUnsupported(question: WireQuestion) {
    val (allowed, what) = when (question.kind) {
        Kind.FEELS -> return
        Kind.CHOOSE -> 2..255 to "options"
        Kind.SCORE -> 2..10 to "levels"
    }
    if (question.labels.size !in allowed) {
        throw KleeneException.Unsupported(
            "question \"${question.name}\" (${question.id}) has ${question.labels.size} $what; System One allows $allowed",
        )
    }
}

private fun State.toJson(): JsonElement = when (this) {
    is State.Text -> JsonPrimitive(value)
    is State.Json -> value
}

private fun WireQuestion.toJson(): JsonObject = buildJsonObject {
    when (kind) {
        Kind.FEELS -> {
            put("type", "noul")
            put("instructions", instructions)
        }
        Kind.CHOOSE -> {
            put("type", "choice")
            put("instructions", instructions)
            putJsonObject("criteria") { labels.forEach { put(it, JsonNull) } }
        }
        Kind.SCORE -> {
            put("type", "score")
            put("instructions", instructions)
            putJsonArray("criteria") { labels.forEach { add(it) } }
        }
    }
}

/** Reads a 2xx body into a [Response]. Unknown keys are ignored; any other shape is [KleeneException.Malformed]. */
private fun decode(body: String, requestId: String?): Response {
    val root = try {
        Json.parseToJsonElement(body)
    } catch (e: SerializationException) {
        throw KleeneException.Malformed("System One response is not JSON: ${e.message}", e)
    }.obj("response")
    return Response(
        model = root["model"].text("model"),
        answers = root["answers"].obj("answers").mapValues { (id, answer) -> answer.toRaw("answers.$id") },
        usage = root["usage"]?.takeUnless { it is JsonNull }?.obj("usage")?.let {
            Usage(it["input_tokens"].count("usage.input_tokens"), it["output_tokens"].count("usage.output_tokens"))
        },
        requestId = requestId,
    )
}

private fun JsonElement.toRaw(path: String): Raw {
    val answer = obj(path)
    return when (val type = answer["type"].text("$path.type")) {
        "noul" -> Raw.Noul(answer["noul"].number("$path.noul"))
        "choice" -> Raw.Choice(
            answer["probabilities"].obj("$path.probabilities").mapValues { (label, p) -> p.number("$path.probabilities.$label") },
            answer.confidence(path),
        )
        "score" -> Raw.Score(answer["score"].number("$path.score"), answer["probabilities"].levels("$path.probabilities"), answer.confidence(path))
        else -> malformed("$path.type is \"$type\", not noul, choice or score")
    }
}

/** Score probabilities arrive keyed "0".."K-1"; they become a list indexed by level. */
private fun JsonElement?.levels(path: String): List<Double> {
    val byLevel = obj(path)
    val keys = List(byLevel.size) { it.toString() }
    if (byLevel.keys != keys.toSet()) malformed("$path keys ${byLevel.keys} are not \"0\"..\"${byLevel.size - 1}\"")
    return keys.map { byLevel[it].number("$path.$it") }
}

private fun JsonObject.confidence(path: String): Double? =
    get("confidence")?.takeUnless { it is JsonNull }?.number("$path.confidence")

private fun JsonElement?.obj(path: String): JsonObject = this as? JsonObject ?: malformed("$path is not an object")

private fun JsonElement?.text(path: String): String = stringOrNull() ?: malformed("$path is not a string")

private fun JsonElement?.number(path: String): Double =
    (this as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull ?: malformed("$path is not a number")

private fun JsonElement?.count(path: String): Long =
    (this as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: malformed("$path is not an integer")

private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun malformed(problem: String): Nothing = throw KleeneException.Malformed("System One response: $problem")

/**
 * The server's own words for an error, in the TypeSafe SDK's order: `error` (string), `error.message`, `message`,
 * `detail` (string), `detail.message`, `detail[]` as `loc: msg` joined by "; ", else the raw body.
 */
private fun serverMessage(body: String): String {
    val json = try {
        Json.parseToJsonElement(body) as? JsonObject
    } catch (e: SerializationException) {
        null
    } ?: return body
    val error = json["error"]
    val detail = json["detail"]
    return error.stringOrNull() ?: (error as? JsonObject)?.get("message").stringOrNull() ?: json["message"].stringOrNull()
        ?: detail.stringOrNull() ?: (detail as? JsonObject)?.get("message").stringOrNull()
        ?: (detail as? JsonArray)?.joinToString("; ", transform = ::validationError)
        ?: body
}

/** One FastAPI validation error: `{"loc":["body","questions",...],"msg":...}` becomes `questions...: msg`. */
private fun validationError(error: JsonElement): String {
    val fields = error as? JsonObject ?: return error.toString()
    val loc = (fields["loc"] as? JsonArray)
        ?.map { (it as? JsonPrimitive)?.content ?: it.toString() }
        ?.dropWhile { it == "body" }
        ?.joinToString(".")
    val msg = fields["msg"].stringOrNull() ?: fields.toString()
    return if (loc.isNullOrEmpty()) msg else "$loc: $msg"
}

/** Awaits the future; cancelling the coroutine cancels the future with `cancel(true)`, which aborts the exchange. */
private suspend fun <T> CompletableFuture<T>.awaitCancelling(): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel(true) }
    whenComplete { value, error ->
        if (error == null) continuation.resume(value)
        else continuation.resumeWithException((error as? CompletionException)?.cause ?: error)
    }
}
