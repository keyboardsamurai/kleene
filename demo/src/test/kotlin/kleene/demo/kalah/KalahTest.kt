package kleene.demo.kalah

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KalahTest {

    /** A board seen from the side to move; [theirs] is in sowing order, so `theirs[5]` faces `mine[0]`. */
    private fun board(mine: List<Int>, myStore: Int, theirs: List<Int>, theirStore: Int): IntArray =
        (mine + myStore + theirs + theirStore).toIntArray()

    // 1. sow

    @Test
    fun `a plain sow drops one seed per pit and hands the flipped board to the opponent`() {
        val move = sow(start(), 0)

        assertFalse(move.extraTurn)
        assertFalse(move.captured)
        assertFalse(move.over)
        assertEquals(0, move.gain)
        assertContentEquals(board(listOf(4, 4, 4, 4, 4, 4), 0, listOf(0, 5, 5, 5, 5, 4), 0), move.board)
    }

    @Test
    fun `pit 3 with 4 seeds from the start ends in your store and gives another turn on an unflipped board`() {
        val move = sow(start(), 2)

        assertTrue(move.extraTurn)
        assertEquals(1, move.gain)
        assertContentEquals(board(listOf(4, 4, 0, 5, 5, 5), 1, listOf(4, 4, 4, 4, 4, 4), 0), move.board)
    }

    @Test
    fun `a 13-seed wrap skips the opponent store and ends in the emptied start pit, which then captures`() {
        val before = board(listOf(13, 1, 1, 1, 1, 1), 0, listOf(1, 1, 1, 1, 1, 1), 5)

        val move = sow(before, 0)

        // Sown: pits 2..6 and your store +1, their pits +1, their store skipped, the 13th seed back in pit 1.
        // Pit 1 now holds 1 and faces their last pit (2 seeds): 3 seeds captured, 4 in your store.
        assertTrue(move.captured)
        assertFalse(move.extraTurn)
        assertEquals(4, move.gain)
        assertContentEquals(board(listOf(2, 2, 2, 2, 2, 0), 5, listOf(0, 2, 2, 2, 2, 2), 4), move.board)
    }

    @Test
    fun `the last seed in an empty pit of yours takes it and the opposite pit into your store`() {
        val before = board(listOf(0, 0, 1, 0, 0, 2), 0, listOf(3, 3, 3, 3, 3, 3), 0)

        val move = sow(before, 2)

        assertTrue(move.captured)
        assertEquals(4, move.gain)
        assertContentEquals(board(listOf(3, 3, 0, 3, 3, 3), 0, listOf(0, 0, 0, 0, 0, 2), 4), move.board)
    }

    @Test
    fun `no capture when the opposite pit is empty`() {
        val before = board(listOf(0, 0, 1, 0, 0, 2), 0, listOf(3, 3, 0, 3, 3, 3), 0)

        val move = sow(before, 2)

        assertFalse(move.captured)
        assertEquals(0, move.gain)
        assertContentEquals(board(listOf(3, 3, 0, 3, 3, 3), 0, listOf(0, 0, 0, 1, 0, 2), 0), move.board)
    }

    @Test
    fun `when your pits run empty the game ends and the opponent sweeps their own pits into their store`() {
        val before = board(listOf(0, 0, 0, 0, 0, 1), 10, listOf(2, 0, 0, 0, 0, 3), 5)

        val move = sow(before, 5)

        assertTrue(move.over)
        assertEquals(1, move.gain)
        assertContentEquals(board(listOf(0, 0, 0, 0, 0, 0), 11, listOf(0, 0, 0, 0, 0, 0), 10), move.board)
        assertEquals(emptyList(), legal(move.board))
    }

    @Test
    fun `gain counts the capture and your own end sweep when a capture empties the opponent side`() {
        val before = board(listOf(0, 0, 0, 1, 0, 5), 0, listOf(0, 2, 0, 0, 0, 0), 7)

        val move = sow(before, 3)

        // Pit 5 takes itself and their second pit (1 + 2); their side is then empty, so your 5 seeds are swept.
        assertTrue(move.captured)
        assertTrue(move.over)
        assertEquals(8, move.gain)
        assertContentEquals(board(listOf(0, 0, 0, 0, 0, 0), 7, listOf(0, 0, 0, 0, 0, 0), 8), move.board)
    }

    @Test
    fun `sowing an empty pit or a pit outside 0 to 5 is rejected`() {
        assertFailsWith<IllegalArgumentException> { sow(board(listOf(0, 1, 1, 1, 1, 1), 0, List(6) { 1 }, 0), 0) }
        assertFailsWith<IllegalArgumentException> { sow(start(), 6) }
    }

    @Test
    fun `legal lists the non-empty pits of the side to move`() {
        assertEquals(listOf(1, 4), legal(board(listOf(0, 3, 0, 0, 1, 0), 0, List(6) { 1 }, 0)))
    }

    // 2. search

    @Test
    fun `the search chains extra turns to the winning order where greedy's first-on-tie loses`() {
        // Pit 6 first: store, then pit 5 (2 seeds, store), then pit 6 again: all 3 seeds home, 13 to 12.
        // Pit 5 first: store, then pit 6 holds 2 and spills one seed across: 12 to 13.
        val endgame = board(listOf(0, 0, 0, 0, 2, 1), 10, listOf(0, 0, 0, 0, 0, 1), 11)

        assertEquals(mapOf(4 to -1, 5 to 1), values(endgame))
    }

    @Test
    fun `the search negates across the opponent's reply and assumes their best one`() {
        // Your only move steps pit 4 into pit 5. Their pit 1 then lands in their empty pit 2, takes your pit 5 and
        // empties your row: 10 to 13. Their pit 5 instead lets your pit 5 take their pit 1: 12 to 11. They take.
        val endgame = board(listOf(0, 0, 0, 1, 0, 0), 10, listOf(1, 0, 0, 0, 1, 0), 10)

        assertEquals(mapOf(3 to -3), values(endgame))
    }

    // 3. facts

    @Test
    fun `facts on the start position - only pit 3 gives another turn, nothing captures, best ties the top value`() {
        val facts = Facts(start())

        assertEquals(listOf(0, 1, 2, 3, 4, 5), facts.legal)
        assertEquals(facts.legal, facts.values.keys.toList())
        assertEquals(listOf(false, false, true, false, false, false), facts.again)
        assertEquals(List(6) { false }, facts.takes)
        val top = facts.values.values.max()
        assertEquals(facts.values.filterValues { it == top }.keys.toList(), facts.best)
        assertEquals(bucket(top), facts.lead)
        assertEquals(2, facts.storesLead)
    }

    @Test
    fun `facts give false for an empty pit and bucket the store margin alone for storesLead`() {
        val facts = Facts(board(listOf(0, 0, 1, 0, 0, 2), 9, listOf(3, 3, 3, 3, 3, 3), 1))

        assertEquals(listOf(2, 5), facts.legal)
        assertEquals(listOf(false, false, true, false, false, false), facts.takes)
        assertEquals(List(6) { false }, facts.again)
        assertEquals(4, facts.storesLead)
    }

    @Test
    fun `bucket splits a margin into clearly behind, behind, even, ahead, clearly ahead`() {
        val margins = listOf(-48, -6, -5, -2, -1, 0, 1, 2, 5, 6, 48)

        assertEquals(listOf(0, 0, 1, 1, 2, 2, 2, 3, 3, 4, 4), margins.map(::bucket))
    }
}
