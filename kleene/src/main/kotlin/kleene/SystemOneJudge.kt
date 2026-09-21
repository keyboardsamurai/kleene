package kleene

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

/**
 * The [Judge] for any System One server: cloud TypeSafe (`https://api.typesafe.ai`) or a local Kev/openjev
 * (`http://127.0.0.1:8009`). One ask is one `POST {baseUrl}/v1/systemone`. It never falls back from one server
 * to another.
 *
 * It refuses, before sending, what TypeSafe's documented shape does not allow: a choice with fewer than 2 or
 * more than 255 options, a score with fewer than 2 or more than 10 levels ([KleeneException.Unsupported]).
 * [timeout] bounds each attempt, headers and body, in real time. [id] tags the [Evidence] and [Rating] it produces.
 *
 * It repeats an attempt up to [maxRetries] times on HTTP 408, 429 and 5xx, a timeout or an I/O error; never on
 * 400, 401, 403, 422 or a malformed response. Before each retry it waits what the server asks for (`retry-after-ms`,
 * else `Retry-After` in seconds or as an HTTP date), else 0.5 s × 2ⁿ capped at 5 s, ± 25% jitter. When no retry is left it throws
 * the last failure: [KleeneException.RateLimited], [KleeneException.Overloaded] or [KleeneException.Timeout].
 * Cancelling the caller cancels the attempt or the wait.
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
        val body = encode(request)
        var retry = 0
        while (true) {
            try {
                return attempt(body)
            } catch (failure: Retryable) {
                if (retry >= maxRetries) throw failure.error
                delay(failure.wait ?: backoff(retry))
                retry++
            }
        }
    }

    /**
     * One HTTP exchange. Throws [Retryable] for a per-attempt timeout, an I/O error, HTTP 408, 429 or 5xx, and
     * [KleeneException.Authentication], [KleeneException.InvalidRequest] or [KleeneException.Malformed] otherwise.
     * Cancelling the caller cancels the exchange. The timeout runs on [Dispatchers.IO] so that it counts real time
     * even when the caller's dispatcher counts virtual time (`runTest`).
     */
    private suspend fun attempt(body: String): Response {
        val http = HttpRequest.newBuilder(endpoint)
            .header("Content-Type", "application/json")
            .apply { if (apiKey != null) header("Authorization", "Bearer $apiKey") }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val reply = try {
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(timeout) { httpClient.sendAsync(http, HttpResponse.BodyHandlers.ofString()).awaitCancelling() }
            }
        } catch (e: HttpTimeoutException) {
            throw Retryable(timedOut(e))
        } catch (e: IOException) {
            throw Retryable(KleeneException.Overloaded("$baseUrl is unreachable: $e", status = null, cause = e))
        } ?: throw Retryable(timedOut(cause = null))
        if (reply.statusCode() !in 200..299) throw failure(reply)
        return decode(reply.body(), reply.headers().firstValue("x-typesafe-request-id").orElse(null))
    }

    private fun timedOut(cause: HttpTimeoutException?) =
        KleeneException.Timeout("$baseUrl did not answer within $timeout", cause)

    private fun failure(reply: HttpResponse<String>): Exception {
        val status = reply.statusCode()
        val message = "$baseUrl answered HTTP $status: ${serverMessage(reply.body())}"
        val wait = retryAfter(reply)
        return when (status) {
            401, 403 -> KleeneException.Authentication(message, status)
            408 -> Retryable(KleeneException.Timeout(message), wait)
            429 -> Retryable(KleeneException.RateLimited(message, retryAfter = wait), wait)
            in 500..599 -> Retryable(KleeneException.Overloaded(message, status), wait)
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
        Kind.CHOOSE -> CHOOSE_OPTIONS to "options"
        Kind.SCORE -> SCORE_LEVELS to "levels"
    }
    if (question.labels.size !in allowed) {
        throw KleeneException.Unsupported(
            "question \"${question.name}\" (${question.id}) has ${question.labels.size} $what; System One allows $allowed",
        )
    }
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
            Usage(it["input_tokens"].integer("usage.input_tokens"), it["output_tokens"].integer("usage.output_tokens"))
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

private fun JsonElement?.integer(path: String): Long =
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

/** A failed attempt worth repeating. [wait] is the delay the server asked for, if any. Never leaves [SystemOneJudge]. */
private class Retryable(val error: KleeneException, val wait: Duration? = null) : Exception(error.message, error)

/**
 * The delay the server asks for: `retry-after-ms`, else `Retry-After` in seconds or as an HTTP date
 * (`Wed, 21 Oct 2026 07:28:00 GMT`, zero once passed). Anything else is no hint.
 */
private fun retryAfter(reply: HttpResponse<*>): Duration? {
    fun header(name: String) = reply.headers().firstValue(name).orElse(null)?.trim()
    return header("retry-after-ms")?.nonNegative()?.milliseconds
        ?: header("retry-after")?.let { it.nonNegative()?.seconds ?: timeUntil(it) }
}

private fun String.nonNegative(): Double? = toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }

private fun timeUntil(httpDate: String): Duration? = try {
    val date = ZonedDateTime.parse(httpDate, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
    java.time.Duration.between(Instant.now(), date).toKotlinDuration().coerceAtLeast(Duration.ZERO)
} catch (e: DateTimeParseException) {
    null
}

/** The wait before retry number [retry] (from 0): 0.5 s × 2^[retry], capped at 5 s, scaled by 1 + [jitter]. */
internal fun backoff(retry: Int, jitter: Double = Random.nextDouble(-0.25, 0.25)): Duration =
    minOf(0.5 * 2.0.pow(retry), 5.0).seconds * (1 + jitter)
