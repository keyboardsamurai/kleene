package kleene.demo.kalah

import kleene.Evidence
import kleene.Kind
import kleene.Kleene
import kleene.Policy
import kleene.Question
import kleene.State
import kleene.Verdict
import kleene.ask
import kleene.choose
import kleene.feels
import kleene.score
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

/** The rubric of the `lead` question, lowest first; level i is [bucket] i. */
val LEAD_LEVELS = listOf("clearly behind", "behind", "even", "ahead", "clearly ahead")

/** The rules, sent once per position in the [State], never in a question's instructions. */
const val RULES =
    "Kalah. You and the opponent each have 6 pits and a store. you.pits lists your pits 1 to 6. " +
        "opponent.pits_opposite_yours lists the opponent pits opposite your pits 1 to 6. You pick one of your " +
        "pits; an empty pit cannot be sown. Its seeds go one per pit: your higher-numbered pits, your store, the " +
        "opponent pits from the one opposite your pit 6 to the one opposite your pit 1, then your pit 1 and on. " +
        "The opponent store is skipped. If the last seed lands in your store, you take another turn. If it lands " +
        "in an empty pit of yours and the opposite pit has seeds, you capture: that seed and the opposite seeds go " +
        "to your store. When all 6 pits of either side are empty, the game ends and each side adds the seeds left " +
        "in its pits to its own store. Most seeds in store wins."

/** Appended to the [RULES] in a hinted [state] only, so a no-hint state stays the one earlier runs logged. */
const val IF_SOWN_RULE =
    "if_sown says, for each of your pits, what sowing it now does, and then the most seeds the opponent can put " +
        "into their store with one sowing."

/**
 * [count] distinct positions, the same for the same [seed]: each is [0, 40] random plies from the start (a game
 * that ends starts again) and a real decision, with at least 2 legal pits and at least 2 seeds between the best
 * and the worst of their [values]. A position's id is its index.
 */
fun positions(seed: Int = 1, count: Int = 200): List<IntArray> {
    // ponytail: uncapped rejection loop, one search per candidate; ~2% are rejected at seed 1. Cap it if a filter gets strict.
    val random = Random(seed)
    val kept = linkedMapOf<List<Int>, IntArray>()
    while (kept.size < count) {
        val board = randomPlay(random, plies = random.nextInt(0, 41))
        if (board.toList() !in kept && isDecision(board)) kept[board.toList()] = board
    }
    return kept.values.toList()
}

private fun randomPlay(random: Random, plies: Int): IntArray {
    var board = start()
    repeat(plies) {
        val move = sow(board, legal(board).random(random))
        board = if (move.over) start() else move.board
    }
    return board
}

private fun isDecision(board: IntArray): Boolean {
    val values = values(board).values
    return values.size >= 2 && values.max() - values.min() >= 2
}

/**
 * The [State] of [board] for the side to move: the [RULES], `you` (pits 1..6, store) and `opponent`, whose
 * `pits_opposite_yours` is aligned with your pits, so its first entry faces your pit 1. With [hints], also
 * `if_sown`: one [ifSown] line per pit, and the [IF_SOWN_RULE] after the rules.
 */
fun state(board: IntArray, hints: Boolean = false): State.Json = State.Json(
    buildJsonObject {
        put("rules", if (hints) "$RULES $IF_SOWN_RULE" else RULES)
        putJsonObject("you") {
            putJsonArray("pits") { (0..5).forEach { add(board[it]) } }
            put("store", board[6])
        }
        putJsonObject("opponent") {
            putJsonArray("pits_opposite_yours") { (0..5).forEach { add(board[12 - it]) } }
            put("store", board[13])
        }
        if (hints) putJsonArray("if_sown") { (0..5).forEach { add(ifSown(board, it)) } }
    },
)

/**
 * What sowing [pit] does now, in one line: the gain, the capture, the extra turn, the game end, and else the
 * [threat]. One-ply facts only, never anything from the search, so reading them is judging, not computing.
 */
private fun ifSown(board: IntArray, pit: Int): String {
    val name = "pit ${pit + 1}"
    if (board[pit] == 0) return "$name: empty, cannot be sown"
    val move = sow(board, pit)
    val capture = if (move.captured) ", including a capture of ${move.capturedSeeds} seeds" else ""
    val turn = if (move.extraTurn) "extra turn" else "no extra turn"
    val after = when {
        move.over -> "; the game ends"
        move.extraTurn -> ""
        else -> "; then the opponent can gain at most ${threat(move)}"
    }
    return "$name: ${seeds(move.gain)} into your store$capture; $turn$after"
}

private fun seeds(n: Int): String = if (n == 1) "1 seed" else "$n seeds"

/**
 * One ask about one position, serialized: every probability exactly as received. [move] is p(pit 1..6), [again]
 * and [takes] p(true) of `againN` and `takesN`, [lead] p per [LEAD_LEVELS] level and [leadExpected] the judge's
 * expected level. [hints] is whether the [state] had `if_sown`; false is not written, so older files read the same.
 */
@Serializable
data class Record(
    val position: Int,
    val board: List<Int>,
    val judge: String,
    val model: String,
    val move: List<Double>,
    val again: List<Double>,
    val takes: List<Double>,
    val lead: List<Double>,
    val leadExpected: Double,
    val hints: Boolean = false,
)

/** The 14 fixed questions, bound to [ai]. Fixed labels keep every wire id the same across positions. */
private class Questions(ai: Kleene) {
    val move = ai.choose(
        "Which pit should you sow to end the game with the most seeds?",
        *(0..5).map { "pit ${it + 1}" to it }.toTypedArray(),
        name = "move",
    )
    val again = (1..6).map { ai.feels("Sowing pit $it gives you another turn", name = "again$it") }
    val takes = (1..6).map { ai.feels("Sowing pit $it captures seeds", name = "takes$it") }
    val lead = ai.score("How far ahead are you with best play?", *LEAD_LEVELS.toTypedArray(), name = "lead")
    val all: Array<Question<*>> = arrayOf(move, *again.toTypedArray(), *takes.toTypedArray(), lead)
}

/**
 * Appends one [Record] per position to [out], one ask each on the [state] with or without [hints], skipping
 * position ids already there so a rerun after a crash resumes; that is the whole error strategy.
 *
 * @throws IllegalStateException if a logged board is not the regenerated one, or a logged record has another judge
 *   or [hints] flag: one file holds one benchmark.
 * @throws kleene.KleeneException if the judge fails: a failure is never turned into UNKNOWN or a missing cell.
 */
suspend fun log(ai: Kleene, positions: List<IntArray>, out: File, hints: Boolean = false) {
    val logged = if (out.exists()) readRecords(out).associateBy { it.position } else emptyMap()
    positions.forEachIndexed { id, board ->
        val previous = logged[id] ?: return@forEachIndexed
        check(previous.board == board.toList()) {
            "position $id in $out is ${previous.board}, not ${board.toList()}: log another seed into another file"
        }
        check(previous.judge == ai.judge.id) {
            "position $id in $out is from judge ${previous.judge}, not ${ai.judge.id}: log each judge into its own file"
        }
        check(previous.hints == hints) {
            "position $id in $out was logged with hints=${previous.hints}, not $hints: log each state into its own file"
        }
    }
    val questions = Questions(ai)
    positions.forEachIndexed { id, board ->
        if (id in logged) return@forEachIndexed
        out.appendText(Json.encodeToString(recordFor(ai, questions, id, board, hints)) + "\n")
        println("logged position $id (${id + 1} of ${positions.size})")
    }
}

private suspend fun recordFor(ai: Kleene, questions: Questions, id: Int, board: IntArray, hints: Boolean): Record {
    val answers = ai.ask(state(board, hints), *questions.all)
    val lead = answers[questions.lead]
    return Record(
        position = id,
        board = board.toList(),
        judge = answers.judge,
        model = answers.model,
        move = answers[questions.move].evidence.probabilities,
        again = questions.again.map { answers[it].evidence.probabilityOf(true) },
        takes = questions.takes.map { answers[it].evidence.probabilityOf(true) },
        lead = lead.probabilities,
        leadExpected = lead.expected,
        hints = hints,
    )
}

/** Reads every [Record] in [file], one JSONL line each. */
fun readRecords(file: File): List<Record> =
    file.readLines().filter { it.isNotBlank() }.map { line -> Json.decodeFromString<Record>(line) }

/** The acceptAts `score` reapplies by default, strictest first. */
val ACCEPT_ATS = listOf(0.95, 0.85, 0.75, 0.60)

/** The acceptAt of the main table: the library default. */
private val HEADLINE = Policy().acceptAt

/** The engine [Facts] of every distinct board in [runs], keyed by the board. */
fun factsOf(runs: Map<String, List<Record>>): Map<List<Int>, Facts> =
    runs.values.flatten().map { it.board }.distinct().associateWith { Facts(it.toIntArray()) }

/**
 * The markdown leaderboard of [runs] (label to records), reapplied at each of [acceptAts] with zero model calls.
 * The main table has one row per run, each scored over its own records, then six baselines that play no judge:
 * `random` (the exact expectation of a uniform legal pit), `greedy` (the highest immediate gain, first on a tie),
 * `gain minus threat` (the highest [netGain], first on a tie: a perfect reader of `if_sown`), `always even` and
 * `stores only` (lead), and `always FALSE` (again/takes). A second table counts decided/wrong moves and
 * again/takes per acceptAt.
 */
fun leaderboard(runs: Map<String, List<Record>>, acceptAts: List<Double>, facts: Map<List<Int>, Facts> = factsOf(runs)): String {
    val scored = runs.mapValues { (_, records) -> records.map { it to facts.getValue(it.board) } }
    val main = table(
        listOf("run", "n", "best move", "regret", "illegal mass", "lead MAE") +
            listOf("moves decided/wrong/illegal", "again/takes decided/wrong").map { "$it at ${twoDecimals(HEADLINE)}" },
        scored.map { (label, run) -> runRow(label, run) } + baselineRows(facts),
    )
    val perAcceptAt = table(
        listOf("acceptAt") + runs.keys.flatMap { listOf("$it moves", "$it again/takes") },
        acceptAts.map { t ->
            listOf(twoDecimals(t)) + scored.values.flatMap { run ->
                listOf(moveTally(run, Policy(t)).show(withIllegal = true), feelsTally(run, Policy(t)).show(withIllegal = false))
            }
        },
    )
    val notes = "Moves: decided/wrong/illegal out of n; wrong includes illegal. again/takes: 12 feels per position, " +
        "most of them FALSE, so compare with `always FALSE`. Engine answers: alpha-beta at depth $DEPTH. model calls: 0"
    return "$main\n\n$perAcceptAt\n\n$notes"
}

/** One run's records, each with the [Facts] of its board. */
private typealias Run = List<Pair<Record, Facts>>

private fun runRow(label: String, run: Run): List<String> {
    val picks = run.map { (record, facts) -> facts to facts.legal.maxBy { record.move[it] } }
    return listOf(label, "${run.size}") + pickColumns(picks) + listOf(
        twoDecimals(run.map { (record, facts) -> (0..5).filter { it !in facts.legal }.sumOf { record.move[it] } }.average()),
        twoDecimals(run.map { (record, facts) -> abs(record.leadExpected - facts.lead) }.average()),
        moveTally(run, Policy(HEADLINE)).show(withIllegal = true),
        feelsTally(run, Policy(HEADLINE)).show(withIllegal = false),
    )
}

private fun baselineRows(facts: Map<List<Int>, Facts>): List<List<String>> {
    val n = "${facts.size}"
    val all = facts.values
    val greedy = facts.map { (board, f) -> f to f.legal.maxBy { sow(board.toIntArray(), it).gain } }
    val gainMinusThreat = facts.map { (board, f) -> f to f.legal.maxBy { netGain(sow(board.toIntArray(), it)) } }
    val random = listOf(
        percent(all.map { it.best.size.toDouble() / it.legal.size }.average()),
        twoDecimals(all.map { f -> f.legal.map { regret(f, it) }.average() }.average()),
    )
    return listOf(
        listOf("baseline: random", n) + random + listOf(twoDecimals(0.0), NONE, NONE, NONE),
        listOf("baseline: greedy", n) + pickColumns(greedy) + listOf(twoDecimals(0.0), NONE, NONE, NONE),
        listOf("baseline: gain minus threat", n) + pickColumns(gainMinusThreat) + listOf(twoDecimals(0.0), NONE, NONE, NONE),
        listOf("baseline: always even", n, NONE, NONE, NONE, twoDecimals(all.map { abs(2 - it.lead).toDouble() }.average()), NONE, NONE),
        listOf("baseline: stores only", n, NONE, NONE, NONE, twoDecimals(all.map { abs(it.storesLead - it.lead).toDouble() }.average()), NONE, NONE),
        listOf("baseline: always FALSE", n, NONE, NONE, NONE, NONE, NONE, "${12 * all.size}/${all.sumOf { f -> (f.again + f.takes).count { it } }}"),
    )
}

/** The gain of [move] minus the opponent's [threat] after it; no threat on an extra turn or at the game end. */
private fun netGain(move: Move): Int = move.gain - if (move.extraTurn) 0 else threat(move)

/** Best move (the share of [picks] that is a best pit) and regret (the mean seeds lost), one pick per position. */
private fun pickColumns(picks: List<Pair<Facts, Int>>): List<String> = listOf(
    percent(picks.map { (facts, pit) -> if (pit in facts.best) 1.0 else 0.0 }.average()),
    twoDecimals(picks.map { (facts, pit) -> regret(facts, pit) }.average()),
)

/** Seeds lost against the engine by sowing [pit]. */
private fun regret(facts: Facts, pit: Int): Double = (facts.values.values.max() - facts.values.getValue(pit)).toDouble()

private class Tally(val decided: Int, val wrong: Int, val illegal: Int = 0) {
    fun show(withIllegal: Boolean): String = "$decided/$wrong" + if (withIllegal) "/$illegal" else ""
}

/** Every move reapplied at [policy], with no masking: an accepted empty pit is wrong and illegal. */
private fun moveTally(run: Run, policy: Policy): Tally {
    val accepted = run.mapNotNull { (record, facts) ->
        val evidence = Evidence(Kind.CHOOSE, (0..5).toList(), record.move, null, record.judge, record.model)
        (evidence.decide(policy) as? Verdict.Accepted)?.let { facts to it.value }
    }
    return Tally(accepted.size, accepted.count { (facts, pit) -> pit !in facts.best }, accepted.count { (facts, pit) -> pit !in facts.legal })
}

/** Every `againN` and `takesN` reapplied at [policy] against the engine's answer, false on an empty pit. */
private fun feelsTally(run: Run, policy: Policy): Tally {
    val accepted = run.flatMap { (record, facts) ->
        ((record.again zip facts.again) + (record.takes zip facts.takes)).mapNotNull { (p, engine) ->
            (feelsEvidence(p, record).decide(policy) as? Verdict.Accepted)?.let { it.value to engine }
        }
    }
    return Tally(accepted.size, accepted.count { (said, engine) -> said != engine })
}

/** ponytail: p(false) rebuilt as 1-p, as in promises.decide; decide reads p(true) only, so the verdict is exact. */
private fun feelsEvidence(p: Double, record: Record) =
    Evidence(Kind.FEELS, listOf(true, false), listOf(p, 1 - p), null, record.judge, record.model)

private const val NONE = "–"

private fun table(header: List<String>, rows: List<List<String>>): String =
    (listOf(header, header.map { "---" }) + rows).joinToString("\n") { "| " + it.joinToString(" | ") + " |" }

private fun twoDecimals(x: Double): String = "%.2f".format(Locale.ROOT, x)

private fun percent(x: Double): String = "%.0f%%".format(Locale.ROOT, 100 * x)
