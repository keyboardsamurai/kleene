package kleene.demo.icd

import kleene.Kleene
import kleene.SystemOneJudge
import kleene.demo.UsageError
import kleene.demo.splitArgs
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

fun main(args: Array<String>) {
    exitProcess(run(args))
}

private const val USAGE = """Usage:
  log [--out out/icd/icd.jsonl] [--timeout 120] [--cut <file of document ids, one per line>]
  rank <jsonl>... [--accept-at 0.5,0.6,0.7,0.8,0.85,0.9,0.95]"""

/**
 * Runs one subcommand of the ICD bench CLI (`log`, `rank`). Returns 0 on success, 2 for missing or unknown
 * arguments (after printing [USAGE] to stderr). Never catches [kleene.KleeneException]: a judge failure during
 * `log` propagates (ADR-0002).
 */
fun run(args: Array<String>): Int {
    if (args.isEmpty()) return usage()
    return try {
        when (args[0]) {
            "log" -> runLog(args.drop(1))
            "rank" -> runRank(args.drop(1))
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
    val (positional, options) = splitArgs(args, setOf("--out", "--timeout", "--cut"))
    if (positional.isNotEmpty()) throw UsageError("log takes no positional arguments")
    val out = File(options["--out"] ?: "out/icd/icd.jsonl")
    val timeout = options["--timeout"]?.let { it.toIntOrNull() ?: throw UsageError("--timeout must be a number") } ?: 120
    val docs = fixture()
    val cut = options["--cut"]?.let(::cutOf) ?: emptySet()
    (cut - docs.map { it.id }.toSet()).firstOrNull()?.let { throw UsageError("--cut lists $it, which is not in the fixture") }

    // A per-attempt timeout longer than the library default: 52 questions per document are slow on a local judge.
    val env = SystemOneJudge.fromEnv()
    val ai = Kleene(SystemOneJudge(env.baseUrl, env.model, env.apiKey, timeout.seconds))
    out.absoluteFile.parentFile.mkdirs()
    runBlocking { log(ai, labels().codes, docs, out, fixtureVersion(), cut) }
    return 0
}

private fun runRank(args: List<String>): Int {
    val (files, options) = splitArgs(args, setOf("--accept-at"))
    if (files.isEmpty()) throw UsageError("rank needs at least one jsonl file")
    val acceptAts = options["--accept-at"]?.let(::acceptAts) ?: ACCEPT_ATS

    println(leaderboard(runsOf(files), fixture(), labels().codes, acceptAts))
    return 0
}

/**
 * One run per file, labelled by the file name without its extension: the two Laya checkpoints share one judge id.
 * Every record must be from the current [fixtureVersion], so a run is never scored against edited gold labels.
 */
private fun runsOf(files: List<String>): Map<String, List<Record>> {
    files.firstOrNull { !File(it).isFile }?.let { throw UsageError("$it is not a file") }
    val runs = files.map(::File).associate { it.nameWithoutExtension to readRecords(it) }
    runs.filterValues { it.isEmpty() }.keys.firstOrNull()?.let { throw UsageError("$it has no records") }
    if (runs.size != files.size) throw UsageError("two files have the same name; a run is labelled by its file name")
    val version = fixtureVersion()
    runs.forEach { (label, records) ->
        records.firstOrNull { it.version != version }?.let {
            throw UsageError("$label was logged against fixture version ${it.version}, not $version: log it again")
        }
    }
    return runs
}

/** The document ids in [path], one per line. */
private fun cutOf(path: String): Set<String> {
    val file = File(path)
    if (!file.isFile) throw UsageError("--cut $path is not a file")
    return file.readLines().map(String::trim).filter(String::isNotEmpty).toSet()
}

/**
 * Parses a comma list such as `0.5,0.85`; each must be in [0.5, 1]. 0.5 removes the UNKNOWN band for `feels` only
 * (see [policyAt]): the principal `choose` is still UNKNOWN when its top p is 0.5 or less.
 */
private fun acceptAts(list: String): List<Double> = list.split(",").map { item ->
    item.trim().toDoubleOrNull()?.takeIf { it >= 0.5 && it <= 1.0 } ?: throw UsageError("--accept-at needs numbers in [0.5, 1], got $item")
}
