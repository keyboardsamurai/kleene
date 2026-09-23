package kleene.demo.kalah

import kleene.Kleene
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
  log [--out out/kalah/kalah.jsonl] [--count 200] [--seed 1] [--timeout 60] [--hints]
  rank <jsonl>... [--accept-at 0.95,0.85,0.75,0.60]
  html <jsonl>... [--out out/kalah/kalah.html]"""

/**
 * Runs one subcommand of the Kalah bench CLI (`log`, `rank`, `html`). Returns 0 on success, 2 for missing or
 * unknown arguments (after printing [USAGE] to stderr). Never catches [kleene.KleeneException]: a judge failure
 * during `log` propagates (ADR-0002).
 */
fun run(args: Array<String>): Int {
    if (args.isEmpty()) return usage()
    return try {
        when (args[0]) {
            "log" -> runLog(args.drop(1))
            "rank" -> runRank(args.drop(1))
            "html" -> runHtml(args.drop(1))
            else -> usage()
        }
    } catch (e: UsageError) {
        System.err.println(e.message)
        usage()
    }
}

private fun usage(): Int {
    System.err.println(USAGE)
    return 2
}

private fun runLog(args: List<String>): Int {
    // ponytail: the only flag without a value, so it is taken out before splitArgs instead of teaching it flags.
    val hints = "--hints" in args
    val (positional, options) = splitArgs(args - "--hints", setOf("--out", "--count", "--seed", "--timeout"))
    if (positional.isNotEmpty()) throw UsageError("log takes no positional arguments")
    val out = File(options["--out"] ?: "out/kalah/kalah.jsonl")
    val count = options["--count"]?.let { it.toIntOrNull() ?: throw UsageError("--count must be a number") } ?: 200
    val seed = options["--seed"]?.let { it.toIntOrNull() ?: throw UsageError("--seed must be a number") } ?: 1
    val timeout = options["--timeout"]?.let { it.toIntOrNull() ?: throw UsageError("--timeout must be a number") } ?: 60

    val ai = Kleene(judgeFromEnv(timeout.seconds))
    println("computing $count positions for seed $seed")
    val positions = positions(seed, count)
    out.absoluteFile.parentFile.mkdirs()
    runBlocking { log(ai, positions, out, hints) }
    return 0
}

private fun runRank(args: List<String>): Int {
    val (files, options) = splitArgs(args, setOf("--accept-at"))
    if (files.isEmpty()) throw UsageError("rank needs at least one jsonl file")
    val acceptAts = options["--accept-at"]?.let(::acceptAts) ?: ACCEPT_ATS

    println(leaderboard(runsOf(files), acceptAts))
    return 0
}

private fun runHtml(args: List<String>): Int {
    val (files, options) = splitArgs(args, setOf("--out"))
    if (files.isEmpty()) throw UsageError("html needs at least one jsonl file")
    val out = File(options["--out"] ?: "out/kalah/kalah.html")

    out.absoluteFile.parentFile.mkdirs()
    out.writeText(html(runsOf(files)))
    return 0
}

/** One run per file, labelled by the file name without its extension: two Laya checkpoints share one judge id. */
private fun runsOf(files: List<String>): Map<String, List<Record>> {
    val runs = files.map(::File).associate { it.nameWithoutExtension to readRecords(it) }
    runs.filterValues { it.isEmpty() }.keys.firstOrNull()?.let { throw UsageError("$it has no records") }
    if (runs.size != files.size) throw UsageError("two files have the same name; a run is labelled by its file name")
    return runs
}

/** Parses a comma list such as `0.95,0.85`; each must be a valid [kleene.Policy.acceptAt], in (0.5, 1]. */
private fun acceptAts(list: String): List<Double> = list.split(",").map { item ->
    item.trim().toDoubleOrNull()?.takeIf { it > 0.5 && it <= 1.0 } ?: throw UsageError("--accept-at needs numbers in (0.5, 1], got $item")
}

/**
 * The judge named by `KLEENE_BASE_URL`, `KLEENE_MODEL` and `KLEENE_API_KEY`, with a per-attempt [timeout] longer
 * than the library default: 14 questions per position are slow on a local judge.
 */
private fun judgeFromEnv(timeout: Duration): SystemOneJudge {
    val env = SystemOneJudge.fromEnv()
    return SystemOneJudge(env.baseUrl, env.model, env.apiKey, timeout)
}
