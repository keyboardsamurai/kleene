package kleene.demo.icd

import kleene.Judge
import kleene.KleeneException
import kleene.Kind
import kleene.Kleene
import kleene.ScriptedJudge
import kleene.State
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BenchTest {

    private val categories = listOf(
        Category("E10", 4, "Type 1 diabetes mellitus", emptyMap()),
        Category("E11", 4, "Type 2 diabetes mellitus", emptyMap()),
    )

    private fun doc(id: String, text: String) = Doc(id, text, "en", "GP note", "terse", "clinician", null, "E11", listOf(Gold("E11", "E11.9")))

    private val docs = listOf(doc("d1", "Type 2 diabetes, on metformin."), doc("d2", "Known T2DM, well controlled."), doc("d3", "DM2."))

    private fun scriptedJudge() = ScriptedJudge {
        feels("E10", 0.1)
        feels("E11", 0.93)
        choose("principal", "E10 Type 1 diabetes mellitus" to 0.05, "E11 Type 2 diabetes mellitus" to 0.9, "none of these" to 0.05, confidence = 0.7)
    }

    private fun tempJsonl(): File = File.createTempFile("icd", ".jsonl").apply {
        delete()
        deleteOnExit()
    }

    @Test
    fun `log asks once per document with the fixed question set and the text as the only state`() = runTest {
        val judge = scriptedJudge()

        log(Kleene(judge), categories, docs, tempJsonl(), "v1")

        assertEquals(3, judge.requests.size)
        judge.requests.forEachIndexed { i, request ->
            assertEquals(listOf("E10", "E11", "principal"), request.questions.map { it.name })
            assertEquals(listOf(Kind.FEELS, Kind.FEELS, Kind.CHOOSE), request.questions.map { it.kind })
            assertEquals(listOf("E10 Type 1 diabetes mellitus", "E11 Type 2 diabetes mellitus", "none of these"), request.questions[2].labels)
            assertEquals(setOf("document"), (request.state as State.Json).value.jsonObject.keys)
            assertEquals(state(docs[i]), request.state)
        }
        assertEquals(1, judge.requests.map { r -> r.questions.map { it.id } }.distinct().size)
    }

    @Test
    fun `log stores every probability and the confidence as received, with the fixture version and the fits flag`() = runTest {
        val out = tempJsonl()

        log(Kleene(scriptedJudge()), categories, docs, out, "v1", cut = setOf("d2"))

        val records = readRecords(out)
        assertEquals(listOf("d1", "d2", "d3"), records.map { it.id })
        val first = records.first()
        assertEquals("scripted", first.judge)
        assertEquals("scripted", first.model)
        assertEquals(mapOf("E10" to 0.1, "E11" to 0.93), first.feels)
        assertEquals(mapOf("E10" to 0.05, "E11" to 0.9, NONE to 0.05), first.principal)
        assertEquals(0.7, first.confidence)
        assertEquals("v1", first.version)
        assertEquals(listOf(true, false, true), records.map { it.fits })
    }

    @Test
    fun `log resumes by document id, asking nothing for documents already in the file`() = runTest {
        val judge = scriptedJudge()
        val out = tempJsonl()
        log(Kleene(judge), categories, docs.take(2), out, "v1")

        log(Kleene(judge), categories, docs, out, "v1")

        assertEquals(3, judge.requests.size)
        assertEquals(listOf("d1", "d2", "d3"), readRecords(out).map { it.id })
    }

    @Test
    fun `log refuses a file that another judge wrote, so one file never mixes judges`() = runTest {
        val out = tempJsonl()
        log(Kleene(scriptedJudge()), categories, docs.take(1), out, "v1")
        out.writeText(out.readText().replace("\"judge\":\"scripted\"", "\"judge\":\"127.0.0.1/kev-4b\""))

        assertFailsWith<IllegalStateException> { log(Kleene(scriptedJudge()), categories, docs, out, "v1") }
    }

    @Test
    fun `log refuses a file logged against another fixture version, so one file never mixes benchmarks`() = runTest {
        val out = tempJsonl()
        log(Kleene(scriptedJudge()), categories, docs.take(1), out, "v1")

        assertFailsWith<IllegalStateException> { log(Kleene(scriptedJudge()), categories, docs, out, "v2") }
    }

    @Test
    fun `log refuses a file whose fits flags another cut wrote, so one file never mixes cuts`() = runTest {
        val out = tempJsonl()
        log(Kleene(scriptedJudge()), categories, docs.take(1), out, "v1")

        assertFailsWith<IllegalStateException> { log(Kleene(scriptedJudge()), categories, docs, out, "v1", cut = setOf("d1")) }
    }

    @Test
    fun `a judge error propagates, nothing is caught, and the documents before it stay logged`() = runTest {
        val scripted = scriptedJudge()
        var calls = 0
        val failing = object : Judge {
            override val id = "scripted"
            override suspend fun evaluate(request: kleene.Request) =
                if (++calls == 2) throw KleeneException.Overloaded("503 after retries", 503) else scripted.evaluate(request)
        }
        val out = tempJsonl()

        assertFailsWith<KleeneException.Overloaded> { log(Kleene(failing), categories, docs, out, "v1") }
        assertEquals(listOf("d1"), readRecords(out).map { it.id })
    }
}
