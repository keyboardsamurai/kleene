package kleene

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class VerdictTest {

    @Test
    fun `at with a looser policy turns unknown into accepted on the same evidence`() {
        val unknown = feelsEvidence(0.8).decide(Policy())
        val looser = Policy(acceptAt = 0.75)

        val reapplied = unknown.at(looser)

        assertIs<Verdict.Unknown<Boolean>>(unknown)
        assertEquals(Verdict.Accepted(true, unknown.evidence, looser), reapplied)
        assertSame(unknown.evidence, reapplied.evidence)
    }

    @Test
    fun `at with a stricter policy turns accepted into unknown`() {
        val accepted = chooseEvidence("a" to 0.8, "b" to 0.2).decide(Policy(acceptAt = 0.75))

        assertIs<Verdict.Accepted<String>>(accepted)
        assertIs<Verdict.Unknown<String>>(accepted.at(Policy()))
    }

    @Test
    fun `at acceptAt moves the feels band symmetrically`() {
        val reapplied = feelsEvidence(0.2).decide(Policy()).at(0.75)

        assertEquals(Policy(acceptAt = 0.75, trueAt = 0.75, falseAt = 0.25), reapplied.policy)
        assertEquals(Truth.FALSE, reapplied.truth)
    }

    @Test
    fun `at acceptAt mirrors falseAt exactly`() {
        val reapplied = feelsEvidence(0.1).decide(Policy()).at(0.9)

        assertEquals(0.1, reapplied.policy.falseAt)
        assertEquals(Truth.FALSE, reapplied.truth)
    }

    @Test
    fun `at acceptAt keeps minConfidence`() {
        val verdict = chooseEvidence("a" to 0.9, "b" to 0.1, confidence = 0.4).decide(Policy(minConfidence = 0.5))

        val reapplied = verdict.at(0.6)

        assertEquals(0.5, reapplied.policy.minConfidence)
        assertIs<Verdict.Unknown<String>>(reapplied)
    }

    @Test
    fun `orElse returns the accepted value`() {
        val accepted = chooseEvidence("a" to 0.9, "b" to 0.1).decide(Policy())

        assertEquals("a", accepted.orElse { "fallback" })
    }

    @Test
    fun `orElse hands the unknown verdict to the fallback`() {
        val unknown = chooseEvidence("a" to 0.6, "b" to 0.4).decide(Policy())

        assertEquals("fallback after ${unknown.evidence.top}", unknown.orElse { "fallback after ${it.evidence.top}" })
    }

    @Test
    fun `truth maps feels verdicts onto the three truth values`() {
        assertEquals(Truth.TRUE, feelsEvidence(0.9).decide(Policy()).truth)
        assertEquals(Truth.FALSE, feelsEvidence(0.1).decide(Policy()).truth)
        assertEquals(Truth.UNKNOWN, feelsEvidence(0.5).decide(Policy()).truth)
    }
}
