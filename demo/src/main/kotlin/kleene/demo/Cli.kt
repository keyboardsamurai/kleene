package kleene.demo

/** Raised for any usage mistake in a demo CLI; each `run` catches it, prints its usage and returns 2. */
internal class UsageError(message: String) : Exception(message)

/** Splits [args] into positional arguments and the [known] `--option value` pairs. */
internal fun splitArgs(args: List<String>, known: Set<String>): Pair<List<String>, Map<String, String>> {
    val positional = mutableListOf<String>()
    val options = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        when {
            arg in known -> {
                i++
                options[arg] = args.getOrNull(i) ?: throw UsageError("$arg needs a value")
            }
            arg.startsWith("--") -> throw UsageError("unknown option $arg")
            else -> positional += arg
        }
        i++
    }
    return positional to options
}
