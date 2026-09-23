package kleene.demo.icd

import kleene.Evidence
import kleene.Kind
import kleene.Policy
import kleene.Truth
import kleene.Verdict
import kleene.demo.feelsEvidence
import kleene.truth
import java.util.Locale

/** The acceptAts `rank` reapplies by default. */
val ACCEPT_ATS = listOf(0.5, 0.6, 0.7, 0.8, 0.85, 0.9, 0.95)

/** The acceptAt of the main table: the library default. */
private val HEADLINE = Policy().acceptAt

/**
 * The [Policy] at [acceptAt]. 0.5 is outside [Policy]'s (0.5, 1], so it becomes the next double up: every p but
 * exactly 0.5 is decided, still through [Evidence.decide].
 */
fun policyAt(acceptAt: Double): Policy = Policy(if (acceptAt == 0.5) Math.nextUp(0.5) else acceptAt)

/**
 * The scores of one run on its documents at one [Policy]. A `feels` cell is [tp], [fp], [fn] or [tn] on the decided
 * [Truth], or [unknown]. Precision, recall and F1 treat UNKNOWN as not predicted. [macroF1] averages only the codes
 * with at least one gold cell in the scored documents, so a false positive on a code without gold does not lower it.
 * [coverage] is decided over all cells, [accuracy] is on decided cells, [auc] is on raw p. The principal:
 * [top1Decided] on accepted answers, [unknownRate], and [top1All] with UNKNOWN counted as wrong.
 * A ratio with a zero denominator is NaN. [cut] counts records whose request did not fit the judge's context.
 */
data class Metrics(
    val n: Int,
    val tp: Int,
    val fp: Int,
    val fn: Int,
    val tn: Int,
    val unknown: Int,
    val precision: Double,
    val recall: Double,
    val microF1: Double,
    val macroF1: Double,
    val coverage: Double,
    val accuracy: Double,
    val auc: Double,
    val top1Decided: Double,
    val unknownRate: Double,
    val top1All: Double,
    val cut: Int,
)

/** One cell: the decided truth, the raw p, and whether the code is gold. */
private class Cell(val code: String, val truth: Truth, val p: Double, val gold: Boolean)

/** Scores [scored] (each record with its document) over [codes] at [policy]. Zero model calls. */
fun metrics(scored: List<Pair<Record, Doc>>, codes: List<String>, policy: Policy): Metrics {
    val cells = scored.flatMap { (record, doc) ->
        codes.map { code ->
            val p = record.feels.getValue(code)
            Cell(code, feelsEvidence(p, record.judge, record.model).decide(policy).truth, p, code in doc.categories)
        }
    }
    val tp = cells.count { it.gold && it.truth == Truth.TRUE }
    val fp = cells.count { !it.gold && it.truth == Truth.TRUE }
    val fn = cells.count { it.gold && it.truth == Truth.FALSE }
    val tn = cells.count { !it.gold && it.truth == Truth.FALSE }
    val unknown = cells.count { it.truth == Truth.UNKNOWN }
    val positives = cells.count { it.gold }
    val perCode = cells.groupBy { it.code }.values.filter { code -> code.any { it.gold } }.map { code ->
        f1(code.count { it.gold && it.truth == Truth.TRUE }, code.count { !it.gold && it.truth == Truth.TRUE }, code.count { it.gold })
    }
    val principals = scored.map { (record, doc) -> principalEvidence(record).decide(policy) to (doc.principal ?: NONE) }
    val accepted = principals.mapNotNull { (verdict, gold) -> (verdict as? Verdict.Accepted)?.let { it.value == gold } }
    return Metrics(
        n = scored.size,
        tp = tp, fp = fp, fn = fn, tn = tn, unknown = unknown,
        precision = ratio(tp, tp + fp),
        recall = ratio(tp, positives),
        microF1 = f1(tp, fp, positives),
        macroF1 = if (perCode.isEmpty()) Double.NaN else perCode.average(),
        coverage = ratio(cells.size - unknown, cells.size),
        accuracy = ratio(tp + tn, cells.size - unknown),
        auc = auc(cells),
        top1Decided = ratio(accepted.count { it }, accepted.size),
        unknownRate = ratio(principals.size - accepted.size, principals.size),
        top1All = ratio(accepted.count { it }, principals.size),
        cut = scored.count { (record, _) -> !record.fits },
    )
}

private fun f1(tp: Int, fp: Int, positives: Int): Double = ratio(2 * tp, tp + fp + positives)

private fun ratio(a: Int, b: Int): Double = if (b == 0) Double.NaN else a.toDouble() / b

/** The share of (gold, other) cell pairs where the gold cell has the higher p; a tie counts half. */
private fun auc(cells: List<Cell>): Double {
    // ponytail: O(gold x other) pairs, about 1M per run on the fixture; sort by rank if the fixture grows 10x.
    val gold = cells.filter { it.gold }.map { it.p }
    val other = cells.filter { !it.gold }.map { it.p }
    if (gold.isEmpty() || other.isEmpty()) return Double.NaN
    val wins = gold.sumOf { g -> other.sumOf { o -> if (g > o) 1.0 else if (g == o) 0.5 else 0.0 } }
    return wins / (gold.size.toDouble() * other.size)
}

private fun principalEvidence(record: Record) =
    Evidence(Kind.CHOOSE, record.principal.keys.toList(), record.principal.values.toList(), record.confidence, record.judge, record.model)

/**
 * The three baselines, one record per document of [docs], each playing no judge: `always empty` (every code FALSE,
 * principal none), `most frequent (X)` (the most frequent gold category X as the only code and the principal; absent
 * when no document has a code) and
 * `keyword` (a code is TRUE if any of its synonyms, in any language, is in the text as a case-insensitive whole
 * word or phrase; the principal is the first matched code in label set order, else none). The keyword baseline has
 * no negation handling, on purpose: it is the bar a judge must clear to be worth a model call.
 */
fun baselines(docs: List<Doc>, categories: List<Category>): Map<String, List<Record>> {
    val codes = categories.map { it.code }
    val frequent = docs.flatMap { it.categories }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    val patterns = categories.associate { category -> category.code to category.synonyms.values.flatten().map(::wholeWord) }
    fun sure(id: String, trueCodes: List<String>, principal: String) = Record(
        id, "baseline", "baseline",
        codes.associateWith { if (it in trueCodes) 1.0 else 0.0 },
        (codes + NONE).associateWith { if (it == principal) 1.0 else 0.0 },
        version = "baseline",
    )
    return listOfNotNull(
        "baseline: always empty" to docs.map { sure(it.id, emptyList(), NONE) },
        frequent?.let { "baseline: most frequent ($it)" to docs.map { doc -> sure(doc.id, listOf(it), it) } },
        "baseline: keyword" to docs.map { doc ->
            val matched = codes.filter { code -> patterns.getValue(code).any { it.containsMatchIn(doc.text) } }
            sure(doc.id, matched, matched.firstOrNull() ?: NONE)
        },
    ).toMap()
}

private fun wholeWord(phrase: String): Regex =
    Regex("(?<![\\p{L}\\p{N}])${Regex.escape(phrase)}(?![\\p{L}\\p{N}])", setOf(RegexOption.IGNORE_CASE))

/** The axes every score is broken down by; `fits` comes from the record, the rest from the document. */
private val AXES: Map<String, (Record, Doc) -> String> = mapOf(
    "language" to { _, doc -> doc.language },
    "type" to { _, doc -> doc.type },
    "completeness" to { _, doc -> doc.completeness },
    "provenance" to { _, doc -> doc.provenance },
    "hard" to { _, doc -> doc.hard ?: "none" },
    "fits" to { record, _ -> "${record.fits}" },
)

/**
 * The markdown leaderboard of [runs] (label to records) on [docs], reapplied with zero model calls: the main table
 * at the library default acceptAt ([Policy.acceptAt]) with the [baselines] (on the documents of every run), the
 * sweep over [acceptAts], then one table per axis with a row per axis value and run, holding every metric of the
 * main table.
 *
 * @throws IllegalArgumentException if a record's id is not in [docs].
 */
fun leaderboard(runs: Map<String, List<Record>>, docs: List<Doc>, categories: List<Category>, acceptAts: List<Double>): String {
    val byId = docs.associateBy { it.id }
    val seen = runs.values.flatten().map { it.id }.toSet()
    val scored = (runs + baselines(docs.filter { it.id in seen }, categories)).mapValues { (_, records) ->
        records.map { record -> record to (byId[record.id] ?: throw IllegalArgumentException("${record.id} is not in the fixture")) }
    }
    val codes = categories.map { it.code }
    val main = table(
        listOf("run") + METRICS + "cut",
        scored.map { (label, run) ->
            val judged = label in runs
            listOf(label) + metricCells(metrics(run, codes, policyAt(HEADLINE)), judged) + if (judged) "${run.cut()}" else DASH
        },
    )
    val notes = "At acceptAt ${two(HEADLINE)}. Cells: ${codes.size} feels per document; UNKNOWN is not predicted, so it " +
        "lowers recall and coverage, never precision. Baselines play no judge. model calls: 0"
    val axes = AXES.map { (axis, key) -> "### By $axis\n\n" + axisTable(axis, key, scored, runs.keys, codes) }
    return (listOf(main, notes, "### Sweep\n\n" + sweepTable(scored.filterKeys { it in runs }, codes, acceptAts)) + axes)
        .joinToString("\n\n")
}

private typealias Run = List<Pair<Record, Doc>>

private fun Run.cut(): Int = count { (record, _) -> !record.fits }

/** The metric columns every table shares; AUC is a dash for a baseline, whose p is 0 or 1. */
private val METRICS = listOf("n", "micro P", "micro R", "micro F1", "macro F1", "coverage", "accuracy decided", "AUC",
    "principal top-1 decided", "principal unknown", "principal top-1 all")

private fun metricCells(m: Metrics, judged: Boolean): List<String> =
    listOf("${m.n}", two(m.precision), two(m.recall), two(m.microF1), two(m.macroF1), pct(m.coverage), pct(m.accuracy),
        if (judged) two(m.auc) else DASH, pct(m.top1Decided), pct(m.unknownRate), pct(m.top1All))

/** Every judged run at each of [acceptAts]; n and AUC do not change with acceptAt, so they are left out. */
private fun sweepTable(judged: Map<String, Run>, codes: List<String>, acceptAts: List<Double>): String = table(
    listOf("acceptAt", "run") + METRICS - setOf("n", "AUC"),
    acceptAts.flatMap { t ->
        judged.map { (label, run) ->
            val m = metrics(run, codes, policyAt(t))
            listOf(two(t), label, two(m.precision), two(m.recall), two(m.microF1), two(m.macroF1), pct(m.coverage),
                pct(m.accuracy), pct(m.top1Decided), pct(m.unknownRate), pct(m.top1All))
        }
    },
)

/** One row per value of [axis] and run of [scored] at the headline acceptAt, skipping a run with no record there. */
private fun axisTable(axis: String, key: (Record, Doc) -> String, scored: Map<String, Run>, judged: Set<String>, codes: List<String>): String {
    val values = scored.values.flatten().map { (record, doc) -> key(record, doc) }.distinct().sorted()
    return table(
        listOf(axis, "run") + METRICS,
        values.flatMap { value ->
            scored.mapNotNull { (label, run) ->
                val group = run.filter { (record, doc) -> key(record, doc) == value }
                if (group.isEmpty()) null
                else listOf(value, label) + metricCells(metrics(group, codes, policyAt(HEADLINE)), label in judged)
            }
        },
    )
}

private const val DASH = "–"

private fun table(header: List<String>, rows: List<List<String>>): String =
    (listOf(header, header.map { "---" }) + rows).joinToString("\n") { "| " + it.joinToString(" | ") + " |" }

private fun two(x: Double): String = if (x.isNaN()) DASH else "%.2f".format(Locale.ROOT, x)

private fun pct(x: Double): String = if (x.isNaN()) DASH else "%.0f%%".format(Locale.ROOT, 100 * x)
