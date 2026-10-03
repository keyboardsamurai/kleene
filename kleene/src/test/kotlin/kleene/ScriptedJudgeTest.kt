package kleene

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScriptedJudgeTest {

    private val output = "Upload failed. Please try again."

    private val uploadError by contract {
        +"Says the upload failed"
        +"Tells the user to retry"
    }

    @Test
    fun `requirements are scripted by their text, in any order`() = runTest {
        val judge = ScriptedJudge { requirements(uploadError, "Tells the user to retry" to 0.05, "Says the upload failed" to 0.97) }

        val report = Kleene(judge).check(output, uploadError)

        assertEquals(listOf(0.97, 0.05), report.requirements.map { it.verdict.evidence.pTrue })
    }

    @Test
    fun `an unknown requirement text fails when the script is built`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ScriptedJudge { requirements(uploadError, "Says the uplod failed" to 0.97) }
        }

        assertContains(error.message!!, "Says the uplod failed")
    }

    @Test
    fun `a requirement text that the contract holds twice fails when the script is built`() {
        val twice by contract {
            +"Says the upload failed"
            +"Says the upload failed"
        }

        assertFailsWith<IllegalArgumentException> { ScriptedJudge { requirements(twice, "Says the upload failed" to 0.97) } }
    }

    @Test
    fun `the same requirement text scripted twice fails when the script is built`() {
        assertFailsWith<IllegalArgumentException> {
            ScriptedJudge { requirements(uploadError, "Says the upload failed" to 0.97, "Says the upload failed" to 0.1) }
        }
        assertFailsWith<IllegalArgumentException> {
            ScriptedJudge {
                requirements(uploadError, "Says the upload failed" to 0.97)
                requirements(uploadError, "Says the upload failed" to 0.1)
            }
        }
    }

    @Test
    fun `choose by one label spreads the rest evenly over the other labels as asked`() = runTest {
        val judge = ScriptedJudge { choose("route", "billing", p = 0.88, confidence = 0.7) }
        val ai = Kleene(judge)
        val route by ai.choose("Which team?", listOf("technical", "billing", "other")) { it }

        val evidence = ai.ask("I was charged twice", route)[route].evidence

        assertEquals(listOf("technical", "billing", "other"), evidence.distribution.keys.toList())
        assertEquals(0.88, evidence.distribution.getValue("billing"))
        assertEquals(0.06, evidence.distribution.getValue("technical"), 1e-12)
        assertEquals(0.06, evidence.distribution.getValue("other"), 1e-12)
        assertEquals(0.7, evidence.confidence)
    }

    @Test
    fun `choose by one label defaults to p = 1`() = runTest {
        val judge = ScriptedJudge { choose("route", "billing") }
        val ai = Kleene(judge)
        val route by ai.choose("Which team?", listOf("technical", "billing")) { it }

        assertEquals(mapOf("technical" to 0.0, "billing" to 1.0), ai.ask("I was charged twice", route)[route].evidence.distribution)
    }

    @Test
    fun `choose by a label the question does not ask fails with the label named`() = runTest {
        val judge = ScriptedJudge { choose("route", "biling") }
        val ai = Kleene(judge)
        val route by ai.choose("Which team?", listOf("technical", "billing")) { it }

        val error = assertFailsWith<IllegalStateException> { ai.ask("I was charged twice", route) }

        assertContains(error.message!!, "\"biling\"")
    }

    @Test
    fun `a question name scripted twice across calls fails when the script is built`() {
        assertFailsWith<IllegalArgumentException> { ScriptedJudge { feels("route", 0.9); choose("route", "billing") } }
        assertFailsWith<IllegalArgumentException> { ScriptedJudge { score("clarity", 0.5, 0.5); score("clarity", 0.1, 0.9) } }
        assertFailsWith<IllegalArgumentException> {
            ScriptedJudge {
                feels("uploadError.1", 0.1)
                requirements(uploadError, "Says the upload failed" to 0.97)
            }
        }
    }
}
