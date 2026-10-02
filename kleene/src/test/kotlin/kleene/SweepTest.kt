package kleene

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SweepTest {

    /** Four hand-counted choose items: right, wrong, undecided below 0.85, and right with two gold options. */
    private val sweep = Sweep(
        listOf(
            Labeled(chooseEvidence("a" to 0.9, "b" to 0.1), gold = setOf("a")),
            Labeled(chooseEvidence("a" to 0.9, "b" to 0.1), gold = setOf("b")),
            Labeled(chooseEvidence("a" to 0.6, "b" to 0.4), gold = setOf("a")),
            Labeled(chooseEvidence("a" to 0.04, "b" to 0.96), gold = setOf("a", "b")),
        ),
    )

    @Test
    fun `a row counts accepted, wrong and unknown at one policy`() {
        val policy = Policy(acceptAt = 0.85)
        val row = sweep.at(policy)

        assertEquals(policy, row.policy)
        assertEquals(4, row.n)
        assertEquals(3, row.accepted)
        assertEquals(1, row.wrong)
        assertEquals(1, row.unknown)
        assertEquals(0.75, row.coverage)
        assertEquals(1.0 / 3, row.risk)
    }

    @Test
    fun `at acceptAts gives one row per acceptAt in order`() {
        val rows = sweep.at(listOf(0.95, 0.85, 0.55))

        assertEquals(listOf(Policy(0.95), Policy(0.85), Policy(0.55)), rows.map { it.policy })
        assertEquals(listOf(1, 3, 4), rows.map { it.accepted })
        assertEquals(listOf(0, 1, 1), rows.map { it.wrong })
    }

    @Test
    fun `risk is NaN when nothing is accepted`() {
        val row = sweep.at(Policy(acceptAt = 0.99))

        assertEquals(0, row.accepted)
        assertEquals(0.0, row.coverage)
        assertTrue(row.risk.isNaN())
    }

    @Test
    fun `feels evidence uses true or false as gold`() {
        val feels = Sweep(
            listOf(
                Labeled(feelsEvidence(0.9), gold = setOf(true)),
                Labeled(feelsEvidence(0.05), gold = setOf(true)),
                Labeled(feelsEvidence(0.5), gold = setOf(false)),
            ),
        )
        val row = feels.at(Policy(acceptAt = 0.85))

        assertEquals(2, row.accepted)
        assertEquals(1, row.wrong)
        assertEquals(1, row.unknown)
    }

    @Test
    fun `table prints the judge, the model, n and one line per acceptAt`() {
        assertEquals(
            """
            judge test, model m, n=4
            acceptAt  accepted  unknown  wrong  coverage    risk
                0.99         0        4      0         0     NaN
                0.85         3        1      1      0.75  0.3333
            """.trimIndent(),
            sweep.table(listOf(0.99, 0.85)),
        )
    }

    @Test
    fun `evidence from more than one judge or model is rejected`() {
        val a = Labeled(Evidence.feels(0.9, "j1", "m"), gold = setOf(true))

        assertFailsWith<IllegalArgumentException> { Sweep(listOf(a, Labeled(Evidence.feels(0.9, "j2", "m"), setOf(true)))) }
        assertFailsWith<IllegalArgumentException> { Sweep(listOf(a, Labeled(Evidence.feels(0.9, "j1", "m2"), setOf(true)))) }
    }

    @Test
    fun `an empty sweep and an empty gold set are rejected`() {
        assertFailsWith<IllegalArgumentException> { Sweep(emptyList<Labeled<String>>()) }
        assertFailsWith<IllegalArgumentException> { Labeled(feelsEvidence(0.9), gold = emptySet()) }
    }

    @Test
    fun `minConfidence on choose evidence without confidence throws Malformed`() {
        assertFailsWith<KleeneException.Malformed> { sweep.at(Policy(minConfidence = 0.5)) }
    }

    @Test
    fun `appending to the caller's list after construction does not change the Sweep`() {
        val items = mutableListOf(Labeled(chooseEvidence("a" to 0.9, "b" to 0.1), gold = setOf("a")))
        val sweep = Sweep(items)
        val table = sweep.table(listOf(0.85))

        items += Labeled(Evidence.choose(mapOf("a" to 0.9, "b" to 0.1), "other", "m", null), gold = setOf("a"))

        assertEquals(1, sweep.at(Policy(0.85)).n)
        assertEquals(table, sweep.table(listOf(0.85)))
    }
}
