package kleene.demo.promises

import kleene.Policy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HtmlTest {

    private fun record(judge: String, date: String, commit: String = "a".repeat(40)): Record = Record(
        path = "Instagram/Privacy Policy.md",
        commit = commit,
        date = date,
        judge = judge,
        model = "t",
        requirements = userPromises.requirements.map { label -> Requirement(label, listOf(0.9)) },
        rules = userPromises.rules.map { RuleResult(it.label, "PASS") },
    )

    /** Runs [block] with [System.out] captured, restoring it afterwards. */
    private fun captureStdout(block: () -> Int): Pair<Int, String> {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        try {
            val exitCode = block()
            return exitCode to buffer.toString()
        } finally {
            System.setOut(original)
        }
    }

    // 1. html

    @Test
    fun `html embeds the commit sha, every requirement label, the model-calls footer and the acceptAt slider, with the repo url only when given`() {
        val records = listOf(record("kev", "2020-01-01T00:00:00Z", commit = "b".repeat(40)))

        val withRepo = html(records, "https://example.com/r")

        assertContains(withRepo, "b".repeat(40))
        userPromises.requirements.forEach { label -> assertContains(withRepo, label) }
        assertContains(withRepo, "model calls: 0")
        assertContains(withRepo, "<input type=\"range\"")
        assertContains(withRepo, "https://example.com/r")

        // The commit link is built client-side from the embedded repoUrl (see grid.html); with no repoUrl there is
        // no host string in the page at all, so no "<host>/commit/<sha>" link can ever be assembled.
        val withoutRepo = html(records, null)
        assertFalse(withoutRepo.contains("example.com"))
    }

    // 2. run

    @Test
    fun `run with no arguments returns 2`() {
        assertEquals(2, run(emptyArray()))
    }

    @Test
    fun `run grid prints the text grid and returns 0`() {
        val records = listOf(
            record("kev", "2020-01-01T00:00:00Z", commit = "a".repeat(40)),
            record("kev", "2020-06-01T00:00:00Z", commit = "b".repeat(40)),
        )
        val jsonl = File.createTempFile("promises", ".jsonl").apply {
            deleteOnExit()
            writeText(records.joinToString("\n") { Json.encodeToString(it) })
        }

        val (exitCode, stdout) = captureStdout { run(arrayOf("grid", jsonl.path)) }

        assertEquals(0, exitCode)
        assertEquals(textGrid(records, Policy(0.85)), stdout.trim())
    }

    @Test
    fun `run html writes the grid file and returns 0`() {
        val records = listOf(record("kev", "2020-01-01T00:00:00Z", commit = "c".repeat(40)))
        val jsonl = File.createTempFile("promises", ".jsonl").apply {
            deleteOnExit()
            writeText(records.joinToString("\n") { Json.encodeToString(it) })
        }
        val out = File.createTempFile("grid", ".html").apply { deleteOnExit() }

        val exitCode = run(arrayOf("html", jsonl.path, "--out", out.path))

        assertEquals(0, exitCode)
        assertContains(out.readText(), "c".repeat(40))
    }
}
