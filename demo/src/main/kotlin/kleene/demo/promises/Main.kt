package kleene.demo.promises

import kleene.Kleene
import kleene.Policy
import kleene.SystemOneJudge
import kleene.demo.UsageError
import kleene.demo.splitArgs
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

fun main(args: Array<String>) {
    exitProcess(run(args))
}

private const val USAGE = """Usage:
  log <repoDir> <path> [--out promises.jsonl] [--chunk <chars>] [--timeout <seconds>]
  grid <jsonl>... [--accept-at 0.85]
  html <jsonl>... [--out grid.html] [--repo-url URL]"""

/**
 * Runs one subcommand of the promise-tests CLI (`log`, `grid`, `html`). Returns 0 on success, 2 for missing or
 * unknown arguments (after printing [USAGE] to stderr). Never catches [kleene.KleeneException]: a judge failure
 * during `log` propagates (ADR-0002).
 */
fun run(args: Array<String>): Int {
    if (args.isEmpty()) return usage()
    return try {
        when (args[0]) {
            "log" -> runLog(args.drop(1))
            "grid" -> runGrid(args.drop(1))
            "html" -> runHtml(args.drop(1))
            else -> usage()
        }
    } catch (_: UsageError) {
        usage()
    }
}

private fun usage(): Int {
    System.err.println(USAGE)
    return 2
}

private fun runLog(args: List<String>): Int {
    val (positional, options) = splitArgs(args, setOf("--out", "--chunk", "--timeout"))
    if (positional.size != 2) throw UsageError("log needs <repoDir> <path>")
    val (repoDir, path) = positional
    val out = File(options["--out"] ?: "promises.jsonl")
    val chunk = options["--chunk"]?.let { it.toIntOrNull() ?: throw UsageError("--chunk must be a number") } ?: 0
    val timeout = options["--timeout"]?.let { it.toIntOrNull() ?: throw UsageError("--timeout must be a number") } ?: 120

    val ai = Kleene(judgeFromEnv(timeout.seconds))
    val versions = history(File(repoDir), path)
    runBlocking { log(ai, versions, path, out, chunk) }
    return 0
}

private fun runGrid(args: List<String>): Int {
    val (files, options) = splitArgs(args, setOf("--accept-at"))
    if (files.isEmpty()) throw UsageError("grid needs at least one jsonl file")
    val acceptAt = options["--accept-at"]?.let { it.toDoubleOrNull() ?: throw UsageError("--accept-at must be a number") } ?: 0.85

    println(textGrid(readRecords(files.map(::File)), Policy(acceptAt)))
    return 0
}

private fun runHtml(args: List<String>): Int {
    val (files, options) = splitArgs(args, setOf("--out", "--repo-url"))
    if (files.isEmpty()) throw UsageError("html needs at least one jsonl file")
    val out = File(options["--out"] ?: "grid.html")

    out.writeText(html(readRecords(files.map(::File)), options["--repo-url"]))
    return 0
}

/**
 * The judge named by `KLEENE_BASE_URL`, `KLEENE_MODEL` and `KLEENE_API_KEY`, with a per-attempt [timeout] longer
 * than the library default: one terms-of-service version is a large [kleene.State] and a local judge is slow.
 */
private fun judgeFromEnv(timeout: Duration): SystemOneJudge {
    val env = SystemOneJudge.fromEnv()
    return SystemOneJudge(env.baseUrl, env.model, env.apiKey, timeout)
}
