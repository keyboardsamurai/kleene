package kleene.demo.promises

import kleene.Judge
import kleene.Kind
import kleene.Kleene
import kleene.Policy
import kleene.Raw
import kleene.Request
import kleene.Response
import kleene.State
import kleene.Truth
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PromisesTest {

    private fun longText(marker: String): String = "$marker filler ".repeat(300)

    private fun candidateOf(request: Request): String =
        (request.state as State.Json).value.jsonObject.getValue("candidate").jsonPrimitive.content

    /** Answers requirement 1 by the marker ("v1"/"v2"/"v3") found in the candidate text; the rest fixed at 0.9. */
    private fun markerJudge(requests: MutableList<Request>): Judge = Judge { request ->
        requests += request
        val candidate = candidateOf(request)
        val p1 = when {
            "v1" in candidate -> 0.97
            "v2" in candidate -> 0.41
            else -> 0.10
        }
        Response(model = "t", answers = request.questions.associate { q -> q.id to Raw.Noul(if (q.name == "userPromises.1") p1 else 0.9) })
    }

    // 1. chunk

    @Test
    fun `chunk with maxChars 0 returns the whole text unsplit`() {
        val text = "para one\n\npara two\n\npara three"

        assertEquals(listOf(text), chunk(text, 0))
    }

    @Test
    fun `chunk splits at blank lines with every piece within maxChars, losing nothing but the separators`() {
        val text = "a".repeat(10) + "\n\n" + "b".repeat(10) + "\n\n" + "c".repeat(10)

        val pieces = chunk(text, 15)

        assertTrue(pieces.size > 1)
        assertTrue(pieces.all { it.length <= 15 })
        assertEquals(text.replace("\n\n", ""), pieces.joinToString(""))
    }

    @Test
    fun `a paragraph longer than maxChars is hard-cut and nothing is lost`() {
        val longParagraph = "x".repeat(25)
        val text = "short\n\n$longParagraph\n\nshort2"

        val pieces = chunk(text, 10)

        assertTrue(pieces.all { it.length <= 10 })
        assertEquals(text.replace("\n\n", ""), pieces.joinToString(""))
    }

    @Test
    fun `chunk returns a text that fits, or is empty, as one piece`() {
        assertEquals(listOf("short"), chunk("short", 100))
        assertEquals(listOf(""), chunk("", 100))
    }

    // 2. log

    @Test
    fun `log writes one JSONL record per version, rules on the full text, requirements from the judge`() = runTest {
        val requests = mutableListOf<Request>()
        val ai = Kleene(markerJudge(requests))
        val versions = listOf(
            Version("a".repeat(40), Instant.parse("2020-01-01T00:00:00Z"), longText("v1")),
            Version("b".repeat(40), Instant.parse("2020-06-01T00:00:00Z"), "v2 too short"),
            Version("c".repeat(40), Instant.parse("2021-01-01T00:00:00Z"), longText("v3")),
        )
        val out = File.createTempFile("promises", ".jsonl").apply { deleteOnExit() }

        log(ai, versions, "Instagram/Privacy Policy.md", out)

        assertEquals(3, out.readLines().size)
        assertEquals(3, requests.size)
        requests.forEach { request ->
            assertEquals(listOf("userPromises.1", "userPromises.2", "userPromises.3", "userPromises.4"), request.questions.map { it.name })
            assertTrue(request.questions.all { it.kind == Kind.FEELS })
        }

        val records = readRecords(listOf(out))
        assertEquals(listOf("a".repeat(40), "b".repeat(40), "c".repeat(40)), records.map { it.commit })
        records.forEach { record ->
            assertEquals("Instagram/Privacy Policy.md", record.path)
            assertEquals("custom", record.judge)
            assertEquals("t", record.model)
            assertEquals(userPromises.requirements, record.requirements.map { it.label })
            assertTrue(record.requirements.all { it.pTrue.size == 1 })
        }
        assertEquals(listOf(0.97), records[0].requirements[0].pTrue)
        assertEquals(listOf(0.41), records[1].requirements[0].pTrue)
        assertEquals(listOf(0.10), records[2].requirements[0].pTrue)
        assertEquals(listOf("PASS", "FAIL", "PASS"), records.map { it.rules.single().outcome })
    }

    @Test
    fun `log with a small maxChars asks one chunk per request and one pTrue entry per chunk`() = runTest {
        val requests = mutableListOf<Request>()
        val ai = Kleene(markerJudge(requests))
        val text = longText("v1")
        val versions = listOf(Version("a".repeat(40), Instant.parse("2020-01-01T00:00:00Z"), text))
        val out = File.createTempFile("promises", ".jsonl").apply { deleteOnExit() }
        val expectedChunks = chunk(text, 200).size
        assertTrue(expectedChunks > 1)

        log(ai, versions, "path", out, maxChars = 200)

        assertEquals(expectedChunks, requests.size)
        val record = readRecords(listOf(out)).single()
        assertTrue(record.requirements.all { it.pTrue.size == expectedChunks })
    }

    @Test
    fun `log resumes after a crash, skipping commits already in the file with no new requests`() = runTest {
        val requests = mutableListOf<Request>()
        val ai = Kleene(markerJudge(requests))
        val versions = listOf(Version("a".repeat(40), Instant.parse("2020-01-01T00:00:00Z"), longText("v1")))
        val out = File.createTempFile("promises", ".jsonl").apply { deleteOnExit() }

        log(ai, versions, "path", out)
        val linesAfterFirst = out.readLines().size
        val requestsAfterFirst = requests.size

        log(ai, versions, "path", out)

        assertEquals(linesAfterFirst, out.readLines().size)
        assertEquals(requestsAfterFirst, requests.size)
    }

    // 5. decide

    @Test
    fun `decide reads p(true) per chunk and folds with K3 or`() {
        assertEquals(Truth.TRUE, decide(listOf(0.97), Policy(0.85), "j", "m"))
        assertEquals(Truth.UNKNOWN, decide(listOf(0.97), Policy(0.98), "j", "m"))
        assertEquals(Truth.FALSE, decide(listOf(0.10), Policy(0.85), "j", "m"))
        assertEquals(Truth.TRUE, decide(listOf(0.97, 0.10), Policy(0.85), "j", "m"))
        assertEquals(Truth.UNKNOWN, decide(listOf(0.5, 0.1), Policy(0.85), "j", "m"))
        assertEquals(Truth.FALSE, decide(listOf(0.1, 0.1), Policy(0.85), "j", "m"))
    }

    // 6. textGrid

    private fun record(judge: String, date: String, p1: Double, ruleOutcome: String): Record = Record(
        path = "Instagram/Privacy Policy.md",
        commit = "a".repeat(40),
        date = date,
        judge = judge,
        model = "t",
        requirements = userPromises.requirements.mapIndexed { i, label -> Requirement(label, listOf(if (i == 0) p1 else 0.9)) },
        rules = userPromises.rules.map { RuleResult(it.label, ruleOutcome) },
    )

    @Test
    fun `textGrid shows every label, dates as headers, T-F-unknown cells, a flip marker, and the model calls footer`() {
        val records = listOf(
            record("kev", "2020-01-01T00:00:00Z", 0.97, "PASS"),
            record("kev", "2020-06-01T00:00:00Z", 0.41, "FAIL"),
        )

        val grid = textGrid(records, Policy(0.85))

        (userPromises.requirements + userPromises.rules.map { it.label }).forEach { label -> assertContains(grid, label) }
        assertContains(grid, "2020-01-01")
        assertContains(grid, "2020-06-01")
        assertContains(grid, "T")
        assertContains(grid, "?*")
        assertContains(grid, "F")
        assertContains(grid, "model calls: 0")
    }

    @Test
    fun `textGrid prints one grid per distinct judge`() {
        val records = listOf(record("kev", "2020-01-01T00:00:00Z", 0.97, "PASS"), record("cloud", "2020-01-01T00:00:00Z", 0.97, "PASS"))

        val grid = textGrid(records, Policy(0.85))

        assertContains(grid, "kev")
        assertContains(grid, "cloud")
        assertEquals(2, Regex("model calls: 0").findAll(grid).count())
    }
}
