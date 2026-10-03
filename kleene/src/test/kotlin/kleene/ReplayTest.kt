package kleene

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReplayTest {

    @TempDir
    lateinit var dir: Path

    private val recording: Path get() = dir.resolve("recording.jsonl")

    private val scripted = ScriptedJudge {
        feels("urgent", 0.9)
        choose("route", "billing", 0.8, confidence = 0.7)
        score("clarity", 0.1, 0.3, 0.6)
    }

    /** The same definitions, bound to whichever Kleene asks them. */
    private class Asking(ai: Kleene) {
        val urgent by ai.feels("Needs a response today")
        val route by ai.choose("Which team should handle this?", "billing" to "B", "technical" to "T")
        val clarity by ai.score("How clearly is the problem described?", "Unclear", "Partly clear", "Clear")
    }

    @Test
    fun `replay gives the answers and evidence that were recorded`() = runTest {
        val recordedAi = Kleene(scripted.recordingTo(recording))
        val r = Asking(recordedAi)
        val recorded = recordedAi.ask("I was charged twice", r.urgent, r.route, r.clarity)
        val replayAi = Kleene(ReplayJudge(recording))
        val q = Asking(replayAi)

        val answers = replayAi.ask("I was charged twice", q.urgent, q.route, q.clarity)

        assertEquals(recorded[r.urgent], answers[q.urgent])
        assertEquals(recorded[r.route], answers[q.route])
        assertEquals(recorded[r.clarity], answers[q.clarity])
        assertEquals("scripted", answers.judge)
        assertEquals(recorded.model, answers.model)
        assertEquals(1, scripted.requests.size)
    }

    @Test
    fun `replay keeps usage, request id and a JSON state`() = runTest {
        val judge = Judge { request ->
            Response("m-1", request.questions.associate { it.id to Raw.Noul(0.1) }, Usage(3, 4), "req-1")
        }
        val state = State.Json(buildJsonObject { put("subject", "Charged twice") })
        val recordedAi = Kleene(judge.recordingTo(recording))
        val recorded = recordedAi.ask(state, Asking(recordedAi).urgent)
        val replayAi = Kleene(ReplayJudge(recording))
        val q = Asking(replayAi)

        val answers = replayAi.ask(state, q.urgent)

        assertEquals(recorded.usage, answers.usage)
        assertEquals("req-1", answers.requestId)
        assertEquals("custom", answers.judge)
        assertEquals(Verdict.Accepted(false, Evidence.feels(0.1, "custom", "m-1"), Policy()), answers[q.urgent])
    }

    @Test
    fun `each ask appends one line`() = runTest {
        val ai = Kleene(scripted.recordingTo(recording))
        val q = Asking(ai)

        ai.ask("first", q.urgent)
        ai.ask("second", q.route, q.clarity)

        assertEquals(2, Files.readAllLines(recording).size)
    }

    @Test
    fun `a request that was not recorded throws and never calls a judge`() = runTest {
        val recordedAi = Kleene(scripted.recordingTo(recording))
        recordedAi.ask("I was charged twice", Asking(recordedAi).urgent)
        val replayAi = Kleene(ReplayJudge(recording))
        val q = Asking(replayAi)
        val renamed by replayAi.feels("Needs a response this week")

        assertFailsWith<IllegalStateException> { replayAi.ask("I was charged once", q.urgent) }
        assertFailsWith<IllegalStateException> { replayAi.ask(State.Json(JsonPrimitive("I was charged twice")), q.urgent) }
        assertFailsWith<IllegalStateException> { replayAi.ask("I was charged twice", q.urgent, q.route) }
        assertFailsWith<IllegalStateException> { replayAi.ask("I was charged twice", renamed) }
        assertEquals(1, scripted.requests.size)
    }

    @Test
    fun `core validates a replayed response`() = runTest {
        val broken = ScriptedJudge { feels("urgent", 1.5) }
        val recordedAi = Kleene(broken.recordingTo(recording))
        assertFailsWith<KleeneException.Malformed> { recordedAi.ask("x", Asking(recordedAi).urgent) }
        val replayAi = Kleene(ReplayJudge(recording))

        assertFailsWith<KleeneException.Malformed> { replayAi.ask("x", Asking(replayAi).urgent) }
    }

    @Test
    fun `an empty recording is rejected`() {
        Files.createFile(recording)

        assertFailsWith<IllegalArgumentException> { ReplayJudge(recording) }
    }

    @Test
    fun `a recording from two judges is rejected`() = runTest {
        val other = Judge { request -> Response("m", request.questions.associate { it.id to Raw.Noul(0.2) }) }
        val a = Kleene(scripted.recordingTo(recording))
        a.ask("x", Asking(a).urgent)
        val b = Kleene(other.recordingTo(recording))
        b.ask("y", Asking(b).urgent)

        assertFailsWith<IllegalArgumentException> { ReplayJudge(recording) }
    }

    @Test
    fun `one request recorded with two different responses is rejected`() = runTest {
        val a = Kleene(scripted.recordingTo(recording))
        a.ask("x", Asking(a).urgent)
        val b = Kleene(ScriptedJudge { feels("urgent", 0.1) }.recordingTo(recording))
        b.ask("x", Asking(b).urgent)

        assertFailsWith<IllegalArgumentException> { ReplayJudge(recording) }
    }

    @Test
    fun `one request recorded twice with the same response replays`() = runTest {
        val a = Kleene(scripted.recordingTo(recording))
        a.ask("x", Asking(a).urgent)
        a.ask("x", Asking(a).urgent)
        val replayAi = Kleene(ReplayJudge(recording))

        replayAi.ask("x", Asking(replayAi).urgent)
    }

    @Test
    fun `one request recorded twice with different request ids replays`() = runTest {
        var calls = 0
        val judge = Judge { request ->
            calls++
            Response("m", request.questions.associate { it.id to Raw.Noul(0.1) }, Usage(1, calls.toLong()), "req-$calls")
        }
        val a = Kleene(judge.recordingTo(recording))
        val r = Asking(a)
        val first = a.ask("x", r.urgent)
        a.ask("x", r.urgent)
        val replayAi = Kleene(ReplayJudge(recording))
        val q = Asking(replayAi)

        val replayed = replayAi.ask("x", q.urgent)

        assertEquals(first[r.urgent].evidence, replayed[q.urgent].evidence)
        assertEquals(Usage(1, 1), replayed.usage)
        assertEquals("req-1", replayed.requestId)
    }

    @Test
    fun `one request recorded twice with different models is rejected`() = runTest {
        var calls = 0
        val judge = Judge { request -> calls++; Response("m-$calls", request.questions.associate { it.id to Raw.Noul(0.1) }) }
        val a = Kleene(judge.recordingTo(recording))
        a.ask("x", Asking(a).urgent)
        a.ask("x", Asking(a).urgent)

        assertFailsWith<IllegalArgumentException> { ReplayJudge(recording) }
    }

    @Test
    fun `a Judge that throws records nothing`() = runTest {
        val failing = Judge { throw KleeneException.Unavailable("down") }
        val ai = Kleene(failing.recordingTo(recording))

        assertFailsWith<KleeneException.Unavailable> { ai.ask("x", Asking(ai).urgent) }

        assertTrue(Files.notExists(recording) || Files.readString(recording).isEmpty())
    }

    /** Records one ask, then rewrites its line with [edit]. */
    private suspend fun recordedLineEditedBy(edit: (MutableMap<String, JsonElement>) -> Unit) {
        Files.deleteIfExists(recording)
        val ai = Kleene(scripted.recordingTo(recording))
        ai.ask("x", Asking(ai).urgent)
        val line = Json.parseToJsonElement(Files.readString(recording)).jsonObject.toMutableMap()
        edit(line)
        Files.writeString(recording, "${JsonObject(line)}\n")
    }

    private fun MutableMap<String, JsonElement>.editResponse(edit: (MutableMap<String, JsonElement>) -> Unit) {
        val response = getValue("response").jsonObject.toMutableMap()
        edit(response)
        put("response", JsonObject(response))
    }

    private fun assertNotARecording() {
        val error = assertFailsWith<IllegalArgumentException> { ReplayJudge(recording) }
        assertContains(error.message!!, "line 1 is not a recording")
    }

    @Test
    fun `a null judge id is not a recording`() = runTest {
        recordedLineEditedBy { it["judge"] = JsonNull }

        assertNotARecording()
    }

    @Test
    fun `a probability written as a string is not a recording`() = runTest {
        recordedLineEditedBy { line ->
            line.editResponse { response ->
                val answers = response.getValue("answers").jsonObject
                val (id, raw) = answers.entries.single()
                response["answers"] = JsonObject(mapOf(id to JsonObject(raw.jsonObject + ("p" to JsonPrimitive("0.5")))))
            }
        }

        assertNotARecording()
    }

    @Test
    fun `usage that is not an object of numbers is not a recording`() = runTest {
        recordedLineEditedBy { line -> line.editResponse { it["usage"] = JsonPrimitive("many") } }
        assertNotARecording()

        recordedLineEditedBy { line ->
            line.editResponse { it["usage"] = buildJsonObject { put("inputTokens", "3"); put("outputTokens", 4) } }
        }
        assertNotARecording()
    }

    /** Records one ask, then replaces the text [old] of its line with [new]. */
    private suspend fun recordedLineWith(judge: Judge, old: String, new: String) {
        Files.deleteIfExists(recording)
        val ai = Kleene(judge.recordingTo(recording))
        ai.ask("x", Asking(ai).urgent)
        val line = Files.readString(recording)
        assertContains(line, old)
        Files.writeString(recording, line.replace(old, new))
    }

    @Test
    fun `a number that is not a JSON number is not a recording`() = runTest {
        for (token in listOf("0.5f", "0.5d", ".5", "5.", "+0.5", "00.5", "0x1p-1", "NaN", "+Infinity")) {
            recordedLineWith(scripted, "\"p\":0.9", "\"p\":$token")

            assertNotARecording()
        }
    }

    @Test
    fun `a token count that is not a JSON integer is not a recording`() = runTest {
        val judge = Judge { request -> Response("m", request.questions.associate { it.id to Raw.Noul(0.1) }, Usage(3, 4)) }
        for (token in listOf("+3", "03", "3.0", "3e0")) {
            recordedLineWith(judge, "\"inputTokens\":3", "\"inputTokens\":$token")

            assertNotARecording()
        }
    }

    @Test
    fun `an exponent, a negative zero and the largest token count replay exactly`() = runTest {
        val answers = mapOf("a" to Raw.Noul(1.0E-7), "b" to Raw.Noul(-0.0), "c" to Raw.Noul(1.5E300), "d" to Raw.Noul(-2.5e-10))
        val response = Response("m", answers, Usage(Long.MAX_VALUE, 0), "req-1")
        val request = Request(State.Text("x"), emptyList())

        Judge { response }.recordingTo(recording).evaluate(request)

        assertContains(Files.readString(recording), "\"p\":1.0E-7")
        assertEquals(response, ReplayJudge(recording).evaluate(request))
    }

    @Test
    fun `a request that is not a JSON object is not a recording`() = runTest {
        recordedLineEditedBy { it["request"] = JsonNull }
        assertNotARecording()

        recordedLineEditedBy { it["request"] = JsonPrimitive(5) }
        assertNotARecording()
    }

    @Test
    fun `an unknown field outside the request is ignored`() = runTest {
        recordedLineEditedBy { line ->
            line["note"] = JsonPrimitive("checked by hand")
            line.editResponse { it["latencyMs"] = JsonPrimitive(12) }
        }
        val replayAi = Kleene(ReplayJudge(recording))
        val q = Asking(replayAi)

        val answers = replayAi.ask("x", q.urgent)

        assertEquals(Evidence.feels(0.9, "scripted", answers.model), answers[q.urgent].evidence)
    }

    @Test
    fun `a request id that is not a string is not a recording`() = runTest {
        recordedLineEditedBy { line -> line.editResponse { it["requestId"] = JsonPrimitive(7) } }

        assertNotARecording()
    }

    @Test
    fun `a recorded NaN is valid JSON and replays as NaN, which core rejects`() = runTest {
        val broken = ScriptedJudge { feels("urgent", Double.NaN) }
        val recordedAi = Kleene(broken.recordingTo(recording))
        assertFailsWith<KleeneException.Malformed> { recordedAi.ask("x", Asking(recordedAi).urgent) }

        val line = Json.parseToJsonElement(Files.readString(recording)).jsonObject
        val raw = line.getValue("response").jsonObject.getValue("answers").jsonObject.values.single().jsonObject
        assertEquals(JsonPrimitive("NaN"), raw["p"])
        val replayAi = Kleene(ReplayJudge(recording))
        assertFailsWith<KleeneException.Malformed> { replayAi.ask("x", Asking(replayAi).urgent) }
    }

    @Test
    fun `a missing recording is rejected`() {
        assertFailsWith<IllegalArgumentException> { ReplayJudge(dir.resolve("missing.jsonl")) }
    }

    @Test
    fun `a truncated line is rejected with its line number`() = runTest {
        val a = Kleene(scripted.recordingTo(recording))
        a.ask("x", Asking(a).urgent)
        Files.writeString(recording, Files.readString(recording) + "{\"judge\": \"scripted\", \"requ\n")

        val error = assertFailsWith<IllegalArgumentException> { ReplayJudge(recording) }

        assertContains(error.message!!, "line 2")
    }

    @Test
    fun `recording creates missing parent directories`() = runTest {
        val nested = dir.resolve("a/b/recording.jsonl")
        val ai = Kleene(scripted.recordingTo(nested))

        ai.ask("x", Asking(ai).urgent)

        assertEquals(1, Files.readAllLines(nested).size)
    }

    @Test
    fun `a recording path that cannot be created fails when it is wrapped, before any model call`() {
        val file = Files.createFile(dir.resolve("file"))

        assertFailsWith<IOException> { scripted.recordingTo(file.resolve("recording.jsonl")) }
        assertEquals(0, scripted.requests.size)
    }

    @Test
    fun `a State text that is not valid Unicode throws IOException from ask after the model call and records nothing`() = runTest {
        val ai = Kleene(scripted.recordingTo(recording))
        val loneSurrogate = "emoji 😀".take(7)

        assertFailsWith<IOException> { ai.ask(loneSurrogate, Asking(ai).urgent) }

        assertEquals(1, scripted.requests.size)
        assertTrue(Files.notExists(recording) || Files.readString(recording).isEmpty())
    }
}
