package kleene

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CheckTest {

    private val output = "Upload failed. No files were saved. Please try again."

    private val uploadError by contract {
        +"Says the upload failed"
        +"Makes clear that no files were saved"
        +"Tells the user to retry"
        rule("Fits the error banner") { it.length <= 180 }
    }

    private val judge = ScriptedJudge {
        requirements(
            uploadError,
            "Says the upload failed" to 0.97,
            "Makes clear that no files were saved" to 0.62,
            "Tells the user to retry" to 0.05,
        )
    }
    private val ai = Kleene(judge)

    @Test
    fun `every rule runs in order even when the first fails`() = runTest {
        val ran = mutableListOf<String>()
        val rulesOnly by contract {
            rule("Mentions a ticket number") { ran += "ticket"; false }
            rule("Fits the error banner") { ran += "banner"; true }
            rule("Has no exclamation marks") { ran += "exclamation"; false }
        }

        val report = ai.check(output, rulesOnly)

        assertEquals(listOf("ticket", "banner", "exclamation"), ran)
        assertEquals(listOf(false, true, false), report.rules.map { it.passed })
        assertEquals(listOf(Outcome.FAIL, Outcome.PASS, Outcome.FAIL), report.findings.map { it.outcome })
        assertEquals(rulesOnly.rules, report.rules.map { it.rule })
        assertTrue(report.requirements.isEmpty())
    }

    @Test
    fun `all requirements are sent in one request as feels questions`() = runTest {
        ai.check(output, uploadError)

        val request = judge.requests.single()
        assertEquals(listOf("uploadError.1", "uploadError.2", "uploadError.3"), request.questions.map { it.name })
        assertEquals(
            listOf("Says the upload failed", "Makes clear that no files were saved", "Tells the user to retry"),
            request.questions.map { it.instructions },
        )
        assertTrue(request.questions.all { it.kind == Kind.FEELS })
    }

    @Test
    fun `a contract without requirements makes no request`() = runTest {
        val rulesOnly by contract { rule("Fits the error banner") { it.length <= 180 } }

        val report = ai.check(output, rulesOnly)

        assertTrue(judge.requests.isEmpty())
        assertEquals("scripted", report.judge)
        assertEquals("", report.model)
        val json = Json.parseToJsonElement(report.toJson()).jsonObject
        assertEquals(JsonPrimitive("scripted"), json["judge"])
        assertEquals(JsonNull, json["model"])
    }

    @Test
    fun `a blank requirement text is an invalid request when the contract is built`() {
        assertFailsWith<KleeneException.InvalidRequest> { contract("c") { +" " } }
        assertFailsWith<KleeneException.InvalidRequest> { contract("c") { +"" } }
        assertFailsWith<KleeneException.InvalidRequest> { val c by contract { +" " } }
    }

    @Test
    fun `a rules-only report says no model was called instead of naming a judge and model`() = runTest {
        val rulesOnly by contract { rule("Has a ticket number") { false } }

        val report = ai.check(output, rulesOnly)

        assertEquals("contract \"rulesOnly\": FAIL (no model call)\n  FAIL     Has a ticket number", report.toString())
        val error = assertFailsWith<AssertionError> { report.assertPassed() }
        assertContains(error.message!!, "contract \"rulesOnly\" failed (no model call)")
    }

    @Test
    fun `the state carries the candidate and no source when none is given`() = runTest {
        ai.check(output, uploadError)

        assertEquals(State.Json(buildJsonObject { put("candidate", output) }), judge.requests.single().state)
    }

    @Test
    fun `a text source is sent as a JSON string`() = runTest {
        ai.check(output, uploadError, source = State.Text("disk full"))

        val expected = buildJsonObject {
            put("candidate", output)
            put("source", "disk full")
        }
        assertEquals(State.Json(expected), judge.requests.single().state)
    }

    @Test
    fun `a JSON source is sent as the element`() = runTest {
        val source = buildJsonObject { put("error", "ENOSPC") }

        ai.check(output, uploadError, source = State.Json(source))

        val expected = buildJsonObject {
            put("candidate", output)
            put("source", source)
        }
        assertEquals(State.Json(expected), judge.requests.single().state)
    }

    @Test
    fun `a String source is sent as a JSON string`() = runTest {
        ai.check(output, uploadError, source = "disk full")
        ai.check(output, uploadError, source = State.Text("disk full"))

        assertEquals(judge.requests[1].state, judge.requests[0].state)
    }

    @Test
    fun `a JsonElement source is sent as the element`() = runTest {
        val source = buildJsonObject { put("error", "ENOSPC") }

        ai.check(output, uploadError, source = source)
        ai.check(output, uploadError, source = State.Json(source))

        assertEquals(judge.requests[1].state, judge.requests[0].state)
    }

    @Test
    fun `findings list rules then requirements, each requirement decided by the Kleene's policy`() = runTest {
        val report = ai.check(output, uploadError)

        assertEquals(
            listOf("Fits the error banner", "Says the upload failed", "Makes clear that no files were saved", "Tells the user to retry"),
            report.findings.map { it.label },
        )
        assertEquals(listOf(Outcome.PASS, Outcome.PASS, Outcome.UNKNOWN, Outcome.FAIL), report.findings.map { it.outcome })
        assertEquals(report.rules + report.requirements, report.findings)
        assertEquals(0.62, report.requirements[1].verdict.evidence.pTrue)
        assertEquals("scripted", report.judge)
        assertEquals("scripted", report.model)
        assertEquals(ai.policy, report.policy)
    }

    @Test
    fun `requirements are index-aligned with the contract and keep the verdict`() = runTest {
        val report = ai.check(output, uploadError)

        assertEquals(uploadError.requirements, report.requirements.map { it.requirement })
        val unknown = assertIs<Verdict.Unknown<Boolean>>(report.requirements[1].verdict)
        assertContains(unknown.reason, "0.62")
        assertEquals(Verdict.Accepted(true, unknown.evidence, Policy(0.6)), report.requirements[1].verdict.at(Policy(0.6)))
        assertEquals(1, judge.requests.size)
    }

    @Test
    fun `a stricter policy turns a pass into unknown`() = runTest {
        val strict = Kleene(judge, Policy(acceptAt = 0.99))

        val report = strict.check(output, uploadError)

        assertEquals(Outcome.UNKNOWN, report.findings[1].outcome)
        assertEquals(strict.policy, report.policy)
    }

    @Test
    fun `FAIL beats UNKNOWN`() = runTest {
        assertEquals(Outcome.FAIL, ai.check(output, uploadError).outcome)
    }

    @Test
    fun `UNKNOWN beats PASS`() = runTest {
        assertEquals(Outcome.UNKNOWN, reportFor(0.97, 0.5).outcome)
    }

    @Test
    fun `PASS when every finding passes`() = runTest {
        assertEquals(Outcome.PASS, reportFor(0.97, 0.9).outcome)
    }

    @Test
    fun `a failing rule fails the report even when every requirement passes`() = runTest {
        val reply by contract {
            rule("Mentions a ticket number") { false }
            +"Says the upload failed"
            +"Tells the user to retry"
        }
        val judge = ScriptedJudge { requirements(reply, "Says the upload failed" to 0.97, "Tells the user to retry" to 0.9) }

        val report = Kleene(judge).check(output, reply)

        judge.requests.single()
        assertEquals(listOf(Outcome.FAIL, Outcome.PASS, Outcome.PASS), report.findings.map { it.outcome })
        assertEquals(listOf(Outcome.PASS, Outcome.PASS), report.requirements.map { it.outcome })
        assertEquals(Outcome.FAIL, report.outcome)
    }

    @Test
    fun `assertPassed returns on PASS`() = runTest {
        reportFor(0.97, 0.9).assertPassed()
    }

    @Test
    fun `assertPassed on UNKNOWN throws an inconclusive AssertionError with the per-finding table`() = runTest {
        val report = reportFor(0.97, 0.5)

        val error = assertFailsWith<AssertionError> { report.assertPassed() }

        val message = assertNotNull(error.message)
        assertContains(message, "inconclusive")
        assertContains(message, Regex("""PASS\s+Fits the error banner\n"""))
        assertContains(message, Regex("""PASS\s+Says the upload failed\s+p\(true\)=0\.97"""))
        assertContains(message, Regex("""UNKNOWN\s+Tells the user to retry\s+p\(true\)=0\.5"""))
    }

    @Test
    fun `assertPassed on FAIL throws with the per-finding table`() = runTest {
        val report = ai.check(output, uploadError)

        val error = assertFailsWith<AssertionError> { report.assertPassed() }

        val message = assertNotNull(error.message)
        assertFalse("inconclusive" in message)
        assertContains(message, Regex("""FAIL\s+Tells the user to retry\s+p\(true\)=0\.05"""))
        assertContains(message, Regex("""UNKNOWN\s+Makes clear that no files were saved\s+p\(true\)=0\.62"""))
    }

    @Test
    fun `check leaves the output untouched`() = runTest {
        val original = output
        var seen: String? = null
        val watched by contract {
            +"Says the upload failed"
            rule("Sees the output") { seen = it; true }
        }
        val judge = ScriptedJudge { requirements(watched, "Says the upload failed" to 0.97) }

        Kleene(judge).check(original, watched)

        assertSame(original, seen)
        assertEquals(original, output)
        assertEquals(State.Json(buildJsonObject { put("candidate", original) }), judge.requests.single().state)
    }

    @Test
    fun `an explicit name is used when the contract is not bound to a property`() = runTest {
        val judge = ScriptedJudge { feels("reply.1", 0.97) }

        Kleene(judge).check(output, contract("reply") { +"Says the upload failed" })

        assertEquals("reply.1", judge.requests.single().questions.single().name)
    }

    @Test
    fun `a contract without a name is unnamed until it is bound with by`() {
        val unnamed: Contract.Unnamed = contract { +"Says the upload failed" }
        val bound by unnamed

        assertEquals("bound", bound.name)
        assertEquals(listOf("Says the upload failed"), bound.requirements)
    }

    @Test
    fun `an empty contract is an invalid request at definition`() {
        assertFailsWith<KleeneException.InvalidRequest> { contract { "Says the upload failed" } }
        assertFailsWith<KleeneException.InvalidRequest> { contract("reply") { } }
    }

    @Test
    fun `a provider error propagates instead of becoming UNKNOWN`() = runTest {
        val failing = Kleene(Judge { throw KleeneException.Overloaded("judge overloaded", 529) })

        assertFailsWith<KleeneException.Overloaded> { failing.check(output, uploadError) }
    }

    @Test
    fun `toJson reports contract, outcome, judge, model, policy and each finding`() = runTest {
        val report = reportFor(0.97, 0.5)

        val expected = buildJsonObject {
            put("contract", "reply")
            put("outcome", "UNKNOWN")
            put("judge", "scripted")
            put("model", "scripted")
            putJsonObject("policy") {
                put("acceptAt", 0.85)
                put("trueAt", 0.85)
                put("falseAt", 0.15)
                put("minConfidence", JsonNull)
            }
            putJsonArray("findings") {
                addJsonObject { put("label", "Fits the error banner"); put("kind", "rule"); put("outcome", "PASS") }
                addJsonObject { put("label", "Says the upload failed"); put("kind", "requirement"); put("outcome", "PASS"); put("pTrue", 0.97) }
                addJsonObject { put("label", "Tells the user to retry"); put("kind", "requirement"); put("outcome", "UNKNOWN"); put("pTrue", 0.5) }
            }
        }
        assertEquals(expected, Json.parseToJsonElement(report.toJson()))
    }

    @Test
    fun `toString prints the outcome, judge, model and one row per finding`() = runTest {
        val report = ai.check(output, uploadError)

        val expected = """
            contract "uploadError": FAIL (judge scripted, model scripted)
              PASS     Fits the error banner
              PASS     Says the upload failed                p(true)=0.97
              UNKNOWN  Makes clear that no files were saved  p(true)=0.62
              FAIL     Tells the user to retry               p(true)=0.05
        """.trimIndent()
        assertEquals(expected, report.toString())
    }

    @Test
    fun `toString and assertPassed do not throw for a report without findings`() {
        // Unreachable from the public API (contract() rejects an empty contract); guards the internal constructor.
        val report = Report(uploadError, emptyList(), emptyList(), "scripted", "", Policy())

        assertEquals("contract \"uploadError\": PASS (no model call)", report.toString())
        report.assertPassed()
    }

    private suspend fun reportFor(failed: Double, retry: Double): Report {
        val reply by contract {
            +"Says the upload failed"
            +"Tells the user to retry"
            rule("Fits the error banner") { true }
        }
        val judge = ScriptedJudge { requirements(reply, "Says the upload failed" to failed, "Tells the user to retry" to retry) }
        return Kleene(judge).check(output, reply)
    }
}
