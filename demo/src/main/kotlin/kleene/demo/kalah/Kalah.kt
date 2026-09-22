package kleene.demo.kalah

import kotlinx.serialization.Serializable

/*
 * Kalah(6,4). A board is an IntArray(14) ring seen from the side to move:
 *   0..5  your pits 1..6        6  your store
 *   7..12 their pits, in sowing order (7 faces your pit 6, 12 faces your pit 1)   13  their store
 * The pit opposite i is 12 - i. Pits are passed around as indices 0..5; "pit N" in prose is index N - 1.
 */

private const val STORE = 6
private const val THEIR_STORE = 13

/**
 * Plies searched by [values], counting every sowing, extra turns included. The largest depth that keeps [values]
 * on a midgame position within ~150 ms, so 200 positions stay within 30 s. Measured on 200 random midgame
 * positions: mean 11 ms, worst 70 ms, 2.2 s in all; depth 14 already has a p90 of 156 ms and 252 ms at the start.
 */
const val DEPTH = 12

/** Outside every reachable margin (48 seeds in play), so it can be negated without overflow. */
private const val INFINITY = 100

/** The start position: 4 seeds in each pit, both stores empty. */
fun start(): IntArray = IntArray(14) { if (it == STORE || it == THEIR_STORE) 0 else 4 }

/**
 * The result of one [sow]. [board] is seen from whoever moves next: the mover again on an [extraTurn], else the
 * opponent (flipped). [gain] is what the mover's store grew by, capture and end sweep included. When [over], nobody
 * moves; [board] still follows the flip rule and holds only the two final stores.
 */
class Move(val board: IntArray, val extraTurn: Boolean, val captured: Boolean, val gain: Int, val over: Boolean)

/**
 * Sows [pit] (0..5, non-empty) counter-clockwise, one seed per pit, into your store but never theirs. The last seed
 * in your store gives an [Move.extraTurn]; the last seed in an empty pit of yours takes it and a non-empty opposite
 * pit into your store. When either row is then empty the game is over and each side sweeps its own row into its
 * own store. [board] is left untouched.
 */
fun sow(board: IntArray, pit: Int): Move {
    require(pit in 0..5 && board[pit] > 0) { "pit $pit is not a legal move" }
    val next = board.copyOf()
    var seeds = next[pit]
    next[pit] = 0
    var at = pit
    while (seeds > 0) {
        at = if (at == THEIR_STORE - 1) 0 else at + 1
        next[at]++
        seeds--
    }
    val captured = at < STORE && next[at] == 1 && next[12 - at] > 0
    if (captured) {
        next[STORE] += next[at] + next[12 - at]
        next[at] = 0
        next[12 - at] = 0
    }
    val over = over(next)
    if (over) {
        for (i in 0..5) {
            next[STORE] += next[i]
            next[THEIR_STORE] += next[7 + i]
            next[i] = 0
            next[7 + i] = 0
        }
    }
    val extraTurn = at == STORE
    return Move(if (extraTurn) next else flip(next), extraTurn, captured, next[STORE] - board[STORE], over)
}

/** The pits the side to move may sow: every non-empty one, none once the game is over. */
fun legal(board: IntArray): List<Int> = if (over(board)) emptyList() else (0..5).filter { board[it] > 0 }

/**
 * The value of each [legal] pit in seeds: the store margin (yours minus theirs) with best play by both sides, at
 * game end or after [DEPTH] plies, whichever comes first. Keys are in pit order.
 */
fun values(board: IntArray): Map<Int, Int> =
    // ponytail: fixed depth, store margin at the horizon; exact only when the game ends inside it. Upgrade path:
    // iterative deepening plus a transposition table.
    legal(board).associateWith { pit -> valueOf(sow(board, pit), DEPTH - 1, -INFINITY, INFINITY) }

/**
 * The lead bucket of a store [margin], in seeds: `<=-6` clearly behind 0, `-5..-2` behind 1, `-1..1` even 2,
 * `2..5` ahead 3, `>=6` clearly ahead 4. The one place these cut points live.
 */
fun bucket(margin: Int): Int = when {
    margin <= -6 -> 0
    margin <= -2 -> 1
    margin <= 1 -> 2
    margin <= 5 -> 3
    else -> 4
}

/**
 * What the engine knows about one position, for the side to move. [best] holds every pit tied for the top of
 * [values]. [again] and [takes] have one entry per pit (index 0..5), FALSE for an empty pit. [lead] is the [bucket]
 * of the best value; [storesLead] the [bucket] of the current store margin alone.
 */
@Serializable
data class Facts(
    val legal: List<Int>,
    val values: Map<Int, Int>,
    val best: List<Int>,
    val again: List<Boolean>,
    val takes: List<Boolean>,
    val lead: Int,
    val storesLead: Int,
)

/** Computes the [Facts] of [board]: one [values] search plus one [sow] per legal pit. */
fun Facts(board: IntArray): Facts {
    val legal = legal(board)
    val values = values(board)
    val top = values.values.maxOrNull() ?: margin(board)
    val moves = (0..5).map { pit -> if (pit in legal) sow(board, pit) else null }
    return Facts(
        legal = legal,
        values = values,
        best = values.filterValues { it == top }.keys.toList(),
        again = moves.map { it?.extraTurn ?: false },
        takes = moves.map { it?.captured ?: false },
        lead = bucket(top),
        storesLead = bucket(margin(board)),
    )
}

private fun over(board: IntArray): Boolean = (0..5).all { board[it] == 0 } || (7..12).all { board[it] == 0 }

private fun margin(board: IntArray): Int = board[STORE] - board[THEIR_STORE]

private fun flip(board: IntArray): IntArray = IntArray(14) { board[(it + 7) % 14] }

/**
 * Negamax with alpha-beta: the value of [board] (game not over) for its side to move, searching [depth] more plies.
 * Two passes over the pits order the moves: those whose last seed lands in your store first, they cut most often.
 */
private fun negamax(board: IntArray, depth: Int, alpha: Int, beta: Int): Int {
    if (depth == 0) return margin(board)
    var best = -INFINITY
    var low = alpha
    for (pass in 0..1) {
        for (pit in 0..5) {
            if (board[pit] == 0 || landsInStore(board, pit) != (pass == 0)) continue
            best = maxOf(best, valueOf(sow(board, pit), depth - 1, low, beta))
            low = maxOf(low, best)
            if (low >= beta) return best
        }
    }
    return best
}

/** Whether the last seed sown from [pit] lands in your store: one lap is 13 pits, their store skipped. */
private fun landsInStore(board: IntArray, pit: Int): Boolean = board[pit] % 13 == STORE - pit

/** The value of [move] for the side that made it: the sign stays on an extra turn and flips otherwise. */
private fun valueOf(move: Move, depth: Int, alpha: Int, beta: Int): Int = when {
    move.over -> if (move.extraTurn) margin(move.board) else -margin(move.board)
    move.extraTurn -> negamax(move.board, depth, alpha, beta)
    else -> -negamax(move.board, depth, -beta, -alpha)
}
