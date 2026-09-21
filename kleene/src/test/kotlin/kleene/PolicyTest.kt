package kleene

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PolicyTest {

    @Test
    fun `defaults derive the feels band from acceptAt`() {
        val policy = Policy(acceptAt = 0.9)

        assertEquals(0.9, policy.trueAt)
        assertEquals(1.0 - 0.9, policy.falseAt)
    }

    @Test
    fun `rejects acceptAt at or below one half`() {
        assertFailsWith<IllegalArgumentException> { Policy(acceptAt = 0.5, trueAt = 0.9, falseAt = 0.1) }
        assertFailsWith<IllegalArgumentException> { Policy(acceptAt = 0.3, trueAt = 0.9, falseAt = 0.1) }
    }

    @Test
    fun `rejects acceptAt above one`() {
        assertFailsWith<IllegalArgumentException> { Policy(acceptAt = 1.1, trueAt = 0.9, falseAt = 0.1) }
    }

    @Test
    fun `rejects trueAt at or below one half`() {
        assertFailsWith<IllegalArgumentException> { Policy(trueAt = 0.5) }
        assertFailsWith<IllegalArgumentException> { Policy(trueAt = 0.4) }
        assertFailsWith<IllegalArgumentException> { Policy(trueAt = 1.1) }
    }

    @Test
    fun `rejects falseAt at or above one half`() {
        assertFailsWith<IllegalArgumentException> { Policy(falseAt = 0.5) }
        assertFailsWith<IllegalArgumentException> { Policy(falseAt = 0.6) }
        assertFailsWith<IllegalArgumentException> { Policy(falseAt = -0.1) }
    }

    @Test
    fun `rejects minConfidence outside the unit interval`() {
        assertFailsWith<IllegalArgumentException> { Policy(minConfidence = -0.1) }
        assertFailsWith<IllegalArgumentException> { Policy(minConfidence = 1.5) }
        assertFailsWith<IllegalArgumentException> { Policy(minConfidence = Double.NaN) }
    }
}
