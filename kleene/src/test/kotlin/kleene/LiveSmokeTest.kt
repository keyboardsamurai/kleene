package kleene

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One real ask against the System One server named by `KLEENE_BASE_URL` and `KLEENE_MODEL` (spec §3.6).
 * Run with `mvn test -Dgroups=live`; skipped when either variable is unset.
 */
@Tag("live")
class LiveSmokeTest {

    private enum class Team { Billing, Technical }

    @Test
    fun `one ask answers a feels, a choose and a score`(): Unit = runBlocking {
        val unset = listOf("KLEENE_BASE_URL", "KLEENE_MODEL").filter { System.getenv(it).isNullOrBlank() }
        assumeTrue(unset.isEmpty(), "not set: $unset")
        val ai = Kleene(SystemOneJudge.fromEnv())
        val urgent by ai.feels("Needs a response today")
        val route by ai.choose("Which team should handle this?", "billing" to Team.Billing, "technical" to Team.Technical)
        val clarity by ai.score("How clearly is the problem described?", "Unclear", "Partly clear", "Clear")

        val answers = ai.ask("The upload failed and nothing was saved. I need it fixed before tonight's demo.", urgent, route, clarity)

        assertEquals(ai.judge.id, answers.judge)
        assertEquals(listOf(true, false), answers[urgent].evidence.options)
        assertEquals(listOf(Team.Billing, Team.Technical), answers[route].evidence.options)
        assertEquals(3, answers[clarity].probabilities.size)
        assertTrue(answers[clarity].expected in 0.0..2.0)
    }
}
