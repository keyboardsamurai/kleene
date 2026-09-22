package kleene.demo.promises

import kleene.Evidence
import kleene.Kind
import kleene.Kleene
import kleene.Policy
import kleene.Truth
import kleene.check
import kleene.contract
import kleene.truth
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** The promises a terms-of-service document is expected to keep; requirements are judged, [Rule]s are code. */
val userPromises by contract {
    +"Commits to not selling personal data to third parties"
    +"Lets the user delete their account and their data"
    +"Commits to notifying users before the terms change"
    +"Commits to not using user content to train AI models"
    rule("Is not an empty scrape") { it.length > 2_000 }
}

/** One [kleene.Report] for one version of a tracked file, serialized. */
@Serializable
data class Record(
    val path: String,
    val commit: String,
    val date: String,
    val judge: String,
    val model: String,
    val requirements: List<Requirement>,
    val rules: List<RuleResult>,
)

/** [pTrue] has one entry per chunk of the version's text; size 1 when the whole text was judged in one ask. */
@Serializable
data class Requirement(val label: String, val pTrue: List<Double>)

/** A [kleene.Rule]'s deterministic outcome. */
@Serializable
data class RuleResult(val label: String, val outcome: String)

/**
 * Splits [text] into pieces no longer than [maxChars], breaking at blank lines (`"\n\n"`); a paragraph longer
 * than [maxChars] is hard-cut. [maxChars] `<= 0`, or a [text] that already fits, returns [text] unsplit. The
 * blank-line separators themselves may be dropped, but every other character survives in one of the pieces, in order.
 */
fun chunk(text: String, maxChars: Int): List<String> {
    if (maxChars <= 0 || text.length <= maxChars) return listOf(text)
    val pieces = mutableListOf<String>()
    var current = StringBuilder()
    for (paragraph in text.split("\n\n")) {
        if (paragraph.length > maxChars) {
            if (current.isNotEmpty()) {
                pieces += current.toString()
                current = StringBuilder()
            }
            pieces += paragraph.chunked(maxChars)
            continue
        }
        if (current.length + paragraph.length > maxChars) {
            pieces += current.toString()
            current = StringBuilder()
        }
        current.append(paragraph)
    }
    if (current.isNotEmpty()) pieces += current.toString()
    return pieces
}

/**
 * Appends one [Record] per version in [versions] to [out], skipping commits already recorded there so a rerun
 * after a crash resumes; that is the whole error strategy. Per version, [userPromises] rules run once on the
 * full text; requirements run once per chunk of at most [maxChars] characters (`<= 0`: the whole text in one
 * chunk), each chunk being exactly one [kleene.check] call, so exactly one [kleene.Judge] call.
 *
 * @throws kleene.KleeneException if the judge fails: a failure is never turned into UNKNOWN or a missing cell.
 */
suspend fun log(ai: Kleene, versions: List<Version>, path: String, out: File, maxChars: Int = 0) {
    val alreadyLogged = if (out.exists()) readRecords(listOf(out)).map { it.commit }.toSet() else emptySet()
    for (version in versions) {
        if (version.commit in alreadyLogged) continue
        out.appendText(Json.encodeToString(recordFor(ai, version, path, maxChars)) + "\n")
        println("logged ${version.commit} ${version.date}")
    }
}

private suspend fun recordFor(ai: Kleene, version: Version, path: String, maxChars: Int): Record {
    val ruleResults = userPromises.rules.map { rule -> RuleResult(rule.label, if (rule.test(version.text)) "PASS" else "FAIL") }
    val reports = chunk(version.text, maxChars).map { piece -> ai.check(piece, userPromises) }
    val requirements = userPromises.requirements.map { label ->
        Requirement(label, reports.map { report -> report.results.first { it.label == label }.evidence!!.probabilityOf(true) })
    }
    return Record(path, version.commit, version.date.toString(), reports.first().judge, reports.first().model, requirements, ruleResults)
}

/**
 * The K3 [Truth] of [pTrue] (one entry per chunk) under [policy]: TRUE if any chunk commits, FALSE if every
 * chunk is FALSE, else UNKNOWN. Reapply: zero model calls.
 */
fun decide(pTrue: List<Double>, policy: Policy, judge: String, model: String): Truth =
    // ponytail: p(false) reconstructed as 1-p; decideFeels reads p(true) only, so the verdict is exact.
    pTrue
        .map { p -> Evidence(Kind.FEELS, listOf(true, false), listOf(p, 1 - p), null, judge, model).decide(policy).truth }
        .reduce(Truth::or)

/** Reads every [Record] from [files], one JSONL line each. */
fun readRecords(files: List<File>): List<Record> =
    files.flatMap { file -> file.readLines().filter { it.isNotBlank() }.map { line -> Json.decodeFromString<Record>(line) } }

/**
 * One text grid per distinct [Record.judge]: rows are rule labels then requirement labels (in the declaration
 * order of that judge's first record), columns are versions sorted by date. A `*` marks a cell whose [Truth]
 * differs from the previous column. Reapplies [policy] to every requirement with zero model calls.
 */
fun textGrid(records: List<Record>, policy: Policy): String =
    records.groupBy { it.judge }.entries.joinToString("\n\n") { (judge, forJudge) -> gridFor(judge, forJudge.sortedBy { it.date }, policy) }

private fun gridFor(judge: String, records: List<Record>, policy: Policy): String {
    val header = "date\t" + records.joinToString("\t") { it.date.take(10) }
    val ruleRows = records.first().rules.map { it.label }.map { label ->
        row(label, records.map { record -> if (record.rules.first { it.label == label }.outcome == "PASS") Truth.TRUE else Truth.FALSE })
    }
    val requirementRows = records.first().requirements.map { it.label }.map { label ->
        row(
            label,
            records.map { record ->
                decide(record.requirements.first { it.label == label }.pTrue, policy, record.judge, record.model)
            },
        )
    }
    return (listOf("judge: $judge", header) + ruleRows + requirementRows + "model calls: 0").joinToString("\n")
}

private fun row(label: String, truths: List<Truth>): String {
    val cells = truths.mapIndexed { i, truth -> symbol(truth) + if (i > 0 && truths[i - 1] != truth) "*" else "" }
    return "$label\t${cells.joinToString("\t")}"
}

private fun symbol(truth: Truth): String = when (truth) {
    Truth.TRUE -> "T"
    Truth.FALSE -> "F"
    Truth.UNKNOWN -> "?"
}
