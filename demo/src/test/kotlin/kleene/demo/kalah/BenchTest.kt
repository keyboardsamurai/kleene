package kleene.demo.kalah

import kleene.Kind
import kleene.Kleene
import kleene.ScriptedJudge
import kleene.State
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BenchTest {

    /** A board seen from the side to move; [theirs] is in sowing order, so `theirs[5]` faces `mine[0]`. */
    private fun board(mine: List<Int>, myStore: Int, theirs: List<Int>, theirStore: Int): IntArray =
        (mine + myStore + theirs + theirStore).toIntArray()

    /** Answers all 14 questions: pit 3 is the move, only pit 3 gives another turn, nothing captures, even. */
    private fun scriptedJudge() = ScriptedJudge {
        choose("move", "pit 1" to 0.05, "pit 2" to 0.05, "pit 3" to 0.7, "pit 4" to 0.1, "pit 5" to 0.05, "pit 6" to 0.05)
        (1..6).forEach { feels("again$it", if (it == 3) 0.9 else 0.1) }
        (1..6).forEach { feels("takes$it", 0.2) }
        score("lead", 0.0, 0.1, 0.8, 0.1, 0.0)
    }

    private fun tempJsonl(): File = File.createTempFile("kalah", ".jsonl").apply {
        delete()
        deleteOnExit()
    }

    // 1. positions

    @Test
    fun `positions are deterministic per seed, distinct, and each one is a real decision`() {
        val first = positions(seed = 1, count = 20)
        val again = positions(seed = 1, count = 20)

        assertEquals(20, first.size)
        assertEquals(first.map { it.toList() }, again.map { it.toList() })
        assertEquals(20, first.map { it.toList() }.distinct().size)
        first.forEach { board ->
            val values = values(board).values
            assertTrue(values.size >= 2, "fewer than 2 legal moves: ${board.toList()}")
            assertTrue(values.max() - values.min() >= 2, "best and worst within one seed: ${board.toList()}")
        }
        assertFalse(first.map { it.toList() } == positions(seed = 2, count = 20).map { it.toList() })
    }

    // 2. state

    @Test
    fun `the state holds the rules once, your pits and store, and the opponent pits aligned opposite yours`() {
        val json = state(board(listOf(1, 2, 3, 4, 5, 6), 7, listOf(8, 9, 10, 11, 12, 13), 14)).value.jsonObject

        assertContains(json.getValue("rules").jsonPrimitive.content, "pit 1")
        val you = json.getValue("you").jsonObject
        assertEquals(listOf(1, 2, 3, 4, 5, 6), you.getValue("pits").jsonArray.map { it.jsonPrimitive.int })
        assertEquals(7, you.getValue("store").jsonPrimitive.int)
        val opponent = json.getValue("opponent").jsonObject
        assertEquals(listOf(13, 12, 11, 10, 9, 8), opponent.getValue("pits_opposite_yours").jsonArray.map { it.jsonPrimitive.int })
        assertEquals(14, opponent.getValue("store").jsonPrimitive.int)
    }

    // 3. log

    @Test
    fun `log asks once per position with the 14 fixed questions and stores the probabilities as received`() = runTest {
        val judge = scriptedJudge()
        val boards = positions(seed = 1, count = 3)
        val out = tempJsonl()

        log(Kleene(judge), boards, out)

        assertEquals(3, judge.requests.size)
        val names = listOf("move") + (1..6).map { "again$it" } + (1..6).map { "takes$it" } + "lead"
        judge.requests.forEachIndexed { i, request ->
            assertEquals(names, request.questions.map { it.name })
            assertEquals(listOf(Kind.CHOOSE) + List(12) { Kind.FEELS } + Kind.SCORE, request.questions.map { it.kind })
            assertEquals(state(boards[i]), request.state as State.Json)
        }
        val records = readRecords(out)
        assertEquals(listOf(0, 1, 2), records.map { it.position })
        assertEquals(boards.map { it.toList() }, records.map { it.board })
        val record = records.first()
        assertEquals("scripted", record.judge)
        assertEquals("scripted", record.model)
        assertEquals(listOf(0.05, 0.05, 0.7, 0.1, 0.05, 0.05), record.move)
        assertEquals(listOf(0.1, 0.1, 0.9, 0.1, 0.1, 0.1), record.again)
        assertEquals(List(6) { 0.2 }, record.takes)
        assertEquals(listOf(0.0, 0.1, 0.8, 0.1, 0.0), record.lead)
        assertEquals(2.0, record.leadExpected, 1e-9)
    }

    @Test
    fun `log resumes by position id, asking nothing for positions already in the file`() = runTest {
        val judge = scriptedJudge()
        val out = tempJsonl()
        log(Kleene(judge), positions(seed = 1, count = 2), out)

        log(Kleene(judge), positions(seed = 1, count = 3), out)

        assertEquals(3, judge.requests.size)
        assertEquals(listOf(0, 1, 2), readRecords(out).map { it.position })
    }

    @Test
    fun `log refuses a file whose logged board is not the regenerated one, so a run never mixes benchmarks`() = runTest {
        val out = tempJsonl()
        log(Kleene(scriptedJudge()), positions(seed = 1, count = 1), out)

        assertFailsWith<IllegalStateException> { log(Kleene(scriptedJudge()), positions(seed = 2, count = 1), out) }
    }

    @Test
    fun `log refuses a file that another judge wrote, so one file never mixes judges`() = runTest {
        val out = tempJsonl()
        log(Kleene(scriptedJudge()), positions(seed = 1, count = 1), out)
        out.writeText(out.readText().replace("\"judge\":\"scripted\"", "\"judge\":\"127.0.0.1/kev-4b\""))

        assertFailsWith<IllegalStateException> { log(Kleene(scriptedJudge()), positions(seed = 1, count = 2), out) }
    }

    // 4. leaderboard

    /** Pits 5 and 6 both land in your store; pit 6 first wins by one seed, pit 5 first loses by one (values -1, 1). */
    private val twoPits = board(listOf(0, 0, 0, 0, 2, 1), 10, listOf(0, 0, 0, 0, 0, 1), 11)

    /** Only pit 6: its seed ends in your store and the game, 21 to 11 (value 10, lead and storesLead clearly ahead). */
    private val lastSeed = board(listOf(0, 0, 0, 0, 0, 1), 20, listOf(0, 0, 0, 0, 0, 1), 10)

    private fun record(position: Int, board: IntArray, move: List<Double>, again: List<Double>, takes: List<Double>, leadExpected: Double) =
        Record(position, board.toList(), "j", "m", move, again, takes, List(5) { 0.2 }, leadExpected)

    /** On [twoPits]: 0.9 on empty pit 1, argmax over legal pits is pit 6; again6 0.7 is right, takes6 0.7 is wrong. */
    private val onTwoPits = record(
        0, twoPits,
        move = listOf(0.9, 0.0, 0.0, 0.0, 0.04, 0.06),
        again = listOf(0.1, 0.1, 0.1, 0.1, 0.9, 0.7),
        takes = listOf(0.1, 0.1, 0.1, 0.1, 0.1, 0.7),
        leadExpected = 2.0,
    )

    /** On [lastSeed]: 0.9 on pit 6, every again/takes right at 0.95, lead one level short. */
    private val onLastSeed = record(
        1, lastSeed,
        move = listOf(0.0, 0.0, 0.0, 0.0, 0.1, 0.9),
        again = listOf(0.05, 0.05, 0.05, 0.05, 0.05, 0.95),
        takes = List(6) { 0.05 },
        leadExpected = 3.0,
    )

    /** The cells of the first markdown table line whose first cell is [first]. */
    private fun cells(markdown: String, first: String): List<String> =
        markdown.lines().map { line -> line.trim().trim('|').split('|').map { it.trim() } }.first { it.first() == first }

    @Test
    fun `leaderboard scores each run over its own records and counts decided, wrong and illegal per acceptAt`() {
        val table = leaderboard(mapOf("kev" to listOf(onTwoPits, onLastSeed), "half" to listOf(onLastSeed)), listOf(0.95, 0.85, 0.60))

        // label, n, best move, regret, illegal mass, lead MAE, moves decided/wrong/illegal and again/takes decided/wrong at 0.85
        assertEquals(listOf("kev", "2", "100%", "0.00", "0.50", "0.50", "2/1/1", "22/0"), cells(table, "kev"))
        assertEquals(listOf("half", "1", "100%", "0.00", "0.10", "1.00", "1/0/0", "12/0"), cells(table, "half"))
        // acceptAt, then per run moves decided/wrong/illegal and again/takes decided/wrong
        assertEquals(listOf("0.95", "0/0/0", "12/0", "0/0/0", "12/0"), cells(table, "0.95"))
        assertEquals(listOf("0.85", "2/1/1", "22/0", "1/0/0", "12/0"), cells(table, "0.85"))
        assertEquals(listOf("0.60", "2/1/1", "24/1", "1/0/0", "12/0"), cells(table, "0.60"))
    }

    @Test
    fun `leaderboard baselines - random is the exact expectation, greedy takes the first highest gain, lead always even or stores only, again and takes always FALSE`() {
        val table = leaderboard(mapOf("kev" to listOf(onTwoPits, onLastSeed)), listOf(0.85))

        // Random: half the legal pits on twoPits are best (regret 2 or 0), lastSeed always. Greedy: both twoPits pits
        // gain 1, so it takes pit 5 and loses 2. Lead is even on twoPits and clearly ahead on lastSeed.
        assertEquals(listOf("baseline: random", "2", "75%", "0.50", "0.00", "–", "–", "–"), cells(table, "baseline: random"))
        assertEquals(listOf("baseline: greedy", "2", "50%", "1.00", "0.00", "–", "–", "–"), cells(table, "baseline: greedy"))
        assertEquals(listOf("baseline: always even", "2", "–", "–", "–", "1.00", "–", "–"), cells(table, "baseline: always even"))
        assertEquals(listOf("baseline: stores only", "2", "–", "–", "–", "0.00", "–", "–"), cells(table, "baseline: stores only"))
        // Always FALSE decides all 24 again/takes and misses the 3 TRUE ones: again5, again6 on twoPits, again6 on lastSeed.
        assertEquals(listOf("baseline: always FALSE", "2", "–", "–", "–", "–", "–", "24/3"), cells(table, "baseline: always FALSE"))
    }
}
