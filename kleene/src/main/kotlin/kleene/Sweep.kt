package kleene

/**
 * One item of a [Sweep]: the stored [evidence] and the [gold] options that count as right. A feels item has the gold
 * set `{true}` or `{false}`.
 */
data class Labeled<T : Any>(val evidence: Evidence<T>, val gold: Set<T>) {
    init {
        require(gold.isNotEmpty()) { "gold must not be empty" }
    }
}

/**
 * A risk and coverage table of [labeled] items, one [Row] per [Policy], with zero model calls. Every item must hold
 * [Evidence] from the same judge and model, because confidence and calibration differ across judges.
 *
 * A Sweep never picks or installs a Policy (ADR-0007): the caller reads the rows and builds its own [Kleene].
 */
class Sweep<T : Any>(labeled: List<Labeled<T>>) {
    private val labeled = labeled.toList()
    val judge: String
    val model: String

    init {
        val sources = labeled.map { it.evidence.judge to it.evidence.model }.distinct()
        require(sources.size == 1) { "a Sweep needs Evidence from exactly one judge and model, got $sources" }
        judge = sources.single().first
        model = sources.single().second
    }

    /**
     * The counts at [policy] over [n] items: [accepted] Verdicts, of which [wrong] are not in their gold set.
     * [risk] is NaN when nothing is accepted.
     */
    data class Row(val policy: Policy, val n: Int, val accepted: Int, val wrong: Int) {
        val unknown: Int get() = n - accepted
        val coverage: Double get() = accepted.toDouble() / n
        val risk: Double get() = if (accepted == 0) Double.NaN else wrong.toDouble() / accepted
    }

    /** Applies [policy] to every item. */
    fun at(policy: Policy): Row {
        val accepted = labeled.mapNotNull { (evidence, gold) -> (evidence.decide(policy) as? Verdict.Accepted)?.let { it.value in gold } }
        return Row(policy, labeled.size, accepted.size, accepted.count { !it })
    }

    /** One [Row] per acceptAt, in order, each at `Policy(acceptAt)`. */
    fun at(acceptAts: Iterable<Double>): List<Row> = acceptAts.map { at(Policy(it)) }

    /** The rows of [at] as a plain text table under a heading with the judge, the model and n. */
    fun table(acceptAts: Iterable<Double>): String {
        val header = listOf("acceptAt", "accepted", "unknown", "wrong", "coverage", "risk")
        val cells = at(acceptAts).map { row ->
            listOf(row.policy.acceptAt.show(), "${row.accepted}", "${row.unknown}", "${row.wrong}", row.coverage.show(), row.risk.show())
        }
        val lines = listOf(header) + cells
        val widths = header.indices.map { column -> lines.maxOf { it[column].length } }
        val body = lines.joinToString("\n") { line -> line.indices.joinToString("  ") { line[it].padStart(widths[it]) } }
        return "judge $judge, model $model, n=${labeled.size}\n$body"
    }
}
