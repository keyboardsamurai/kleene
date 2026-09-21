package kleene

import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EvidenceTest {

    private val policy = Policy()

    @Test
    fun `feels at exactly trueAt is accepted as true`() {
        val verdict = feelsEvidence(policy.trueAt).decide(policy)

        assertEquals(Verdict.Accepted(true, feelsEvidence(policy.trueAt), policy), verdict)
    }

    @Test
    fun `feels at exactly falseAt is accepted as false`() {
        val verdict = assertIs<Verdict.Accepted<Boolean>>(feelsEvidence(policy.falseAt).decide(policy))

        assertEquals(false, verdict.value)
    }

    @Test
    fun `feels at the mirrored falseAt of a round acceptAt is accepted as false`() {
        val verdict = assertIs<Verdict.Accepted<Boolean>>(feelsEvidence(0.1).decide(Policy(acceptAt = 0.9)))

        assertEquals(false, verdict.value)
    }

    @Test
    fun `feels at one half is unknown`() {
        assertIs<Verdict.Unknown<Boolean>>(feelsEvidence(0.5).decide(policy))
    }

    @Test
    fun `feels just inside the band is unknown`() {
        assertIs<Verdict.Unknown<Boolean>>(feelsEvidence(policy.trueAt - 1e-9).decide(policy))
        assertIs<Verdict.Unknown<Boolean>>(feelsEvidence(policy.falseAt + 1e-9).decide(policy))
    }

    @Test
    fun `unknown feels names p and the band in its reason`() {
        val verdict = assertIs<Verdict.Unknown<Boolean>>(feelsEvidence(0.62).decide(policy))

        assertTrue("p(true)=0.62" in verdict.reason, verdict.reason)
        assertTrue("falseAt=0.15" in verdict.reason, verdict.reason)
        assertTrue("trueAt=0.85" in verdict.reason, verdict.reason)
    }

    @Test
    fun `choose below acceptAt is unknown`() {
        val verdict = assertIs<Verdict.Unknown<String>>(chooseEvidence("a" to 0.7, "b" to 0.3).decide(policy))

        assertTrue("acceptAt" in verdict.reason, verdict.reason)
    }

    @Test
    fun `choose with minConfidence set and no confidence fails closed`() {
        val evidence = chooseEvidence("a" to 0.9, "b" to 0.1, confidence = null)

        assertFailsWith<KleeneException.Malformed> { evidence.decide(Policy(minConfidence = 0.5)) }
    }

    @Test
    fun `choose with minConfidence set and no confidence fails closed even below acceptAt`() {
        val evidence = chooseEvidence("a" to 0.6, "b" to 0.4, confidence = null)

        assertFailsWith<KleeneException.Malformed> { evidence.decide(Policy(minConfidence = 0.5)) }
    }

    @Test
    fun `choose with confidence below minConfidence is unknown`() {
        val evidence = chooseEvidence("a" to 0.9, "b" to 0.1, confidence = 0.4)

        val verdict = assertIs<Verdict.Unknown<String>>(evidence.decide(Policy(minConfidence = 0.5)))
        assertTrue("minConfidence" in verdict.reason, verdict.reason)
    }

    @Test
    fun `choose accepts the top option when every gate passes`() {
        val evidence = chooseEvidence("a" to 0.05, "b" to 0.9, "c" to 0.05, confidence = 0.6)
        val strict = Policy(minConfidence = 0.5)

        assertEquals(Verdict.Accepted("b", evidence, strict), evidence.decide(strict))
    }

    @Test
    fun `top is the first option with the highest probability`() {
        val evidence = chooseEvidence("a" to 0.2, "b" to 0.4, "c" to 0.4)

        assertEquals("b", evidence.top)
        assertEquals(0.4, evidence.topProbability)
    }

    @Test
    fun `probabilityOf returns the probability as received`() {
        val evidence = chooseEvidence("a" to 0.25, "b" to 0.76)

        assertEquals(0.76, evidence.probabilityOf("b"))
        assertEquals(0.25, evidence.probabilityOf("a"))
    }

    @Test
    fun `probabilityOf rejects a value that is not an option`() {
        assertFailsWith<IllegalArgumentException> { chooseEvidence("a" to 0.5, "b" to 0.5).probabilityOf("z") }
    }

    @Test
    fun `margin is top minus second highest`() {
        assertEquals(0.5, chooseEvidence("a" to 0.1, "b" to 0.7, "c" to 0.2).margin, 1e-12)
        assertEquals(0.0, feelsEvidence(0.5).margin)
    }

    @Test
    fun `normalized entropy is zero for certainty and one for uniform`() {
        assertEquals(0.0, chooseEvidence("a" to 1.0, "b" to 0.0, "c" to 0.0).normalizedEntropy, 1e-12)
        assertEquals(1.0, chooseEvidence("a" to 0.25, "b" to 0.25, "c" to 0.25, "d" to 0.25).normalizedEntropy, 1e-12)
        assertEquals(-(0.8 * ln(0.8) + 0.2 * ln(0.2)) / ln(2.0), feelsEvidence(0.8).normalizedEntropy, 1e-12)
    }
}
