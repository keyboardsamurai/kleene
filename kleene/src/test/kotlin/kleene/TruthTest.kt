package kleene

import kleene.Truth.FALSE
import kleene.Truth.TRUE
import kleene.Truth.UNKNOWN
import kotlin.test.Test
import kotlin.test.assertEquals

class TruthTest {

    @Test
    fun `and follows the K3 truth table`() {
        val table = listOf(
            Triple(TRUE, TRUE, TRUE),
            Triple(TRUE, FALSE, FALSE),
            Triple(TRUE, UNKNOWN, UNKNOWN),
            Triple(FALSE, TRUE, FALSE),
            Triple(FALSE, FALSE, FALSE),
            Triple(FALSE, UNKNOWN, FALSE),
            Triple(UNKNOWN, TRUE, UNKNOWN),
            Triple(UNKNOWN, FALSE, FALSE),
            Triple(UNKNOWN, UNKNOWN, UNKNOWN),
        )

        table.forEach { (a, b, expected) -> assertEquals(expected, a and b, "$a and $b") }
    }

    @Test
    fun `or follows the K3 truth table`() {
        val table = listOf(
            Triple(TRUE, TRUE, TRUE),
            Triple(TRUE, FALSE, TRUE),
            Triple(TRUE, UNKNOWN, TRUE),
            Triple(FALSE, TRUE, TRUE),
            Triple(FALSE, FALSE, FALSE),
            Triple(FALSE, UNKNOWN, UNKNOWN),
            Triple(UNKNOWN, TRUE, TRUE),
            Triple(UNKNOWN, FALSE, UNKNOWN),
            Triple(UNKNOWN, UNKNOWN, UNKNOWN),
        )

        table.forEach { (a, b, expected) -> assertEquals(expected, a or b, "$a or $b") }
    }

    @Test
    fun `not swaps TRUE and FALSE and keeps UNKNOWN`() {
        assertEquals(FALSE, !TRUE)
        assertEquals(TRUE, !FALSE)
        assertEquals(UNKNOWN, !UNKNOWN)
    }
}
