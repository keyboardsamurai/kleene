package kleene

import kotlin.time.Duration

/**
 * A judgment that could not be made: the Judge did not answer. Always thrown, never turned into an Unknown
 * [Verdict] (ADR-0002). Coroutine cancellation is never wrapped in one.
 */
sealed class KleeneException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    /** The judge refused the credentials (HTTP 401 or 403). */
    class Authentication(message: String, val status: Int) : KleeneException(message)

    /** The request is invalid: client-side validation, HTTP 400 or 422. */
    class InvalidRequest(message: String, cause: Throwable? = null) : KleeneException(message, cause)

    /** HTTP 429 after retries were exhausted. [retryAfter] is the last delay the judge asked for, if any. */
    class RateLimited(message: String, val retryAfter: Duration?) : KleeneException(message)

    /** The judge is overloaded (HTTP 5xx, e.g. 529) after retries were exhausted. */
    class Overloaded(message: String, val status: Int?, cause: Throwable? = null) : KleeneException(message, cause)

    /**
     * The judge can't be reached (connection refused, DNS, reset) after retries were exhausted. Usually configuration:
     * a wrong base URL, or a local judge that is not running (ADR-0004).
     */
    class Unavailable(message: String, cause: Throwable? = null) : KleeneException(message, cause)

    /** Every attempt timed out. */
    class Timeout(message: String, cause: Throwable? = null) : KleeneException(message, cause)

    /** The response can't be used as is: unparsable, wrong ids or kinds, bad numbers or sums, missing required metric. */
    class Malformed(message: String, cause: Throwable? = null) : KleeneException(message, cause)

    /** The adapter refuses the request before sending it (limits). */
    class Unsupported(message: String) : KleeneException(message)
}
