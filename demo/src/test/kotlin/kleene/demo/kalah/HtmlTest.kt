package kleene.demo.kalah

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HtmlTest {

    @TempDir
    lateinit var dir: File

    private val board = listOf(0, 0, 0, 0, 2, 1, 10, 0, 0, 0, 0, 0, 1, 11)

    private val record = Record(
        position = 7,
        board = board,
        judge = "api.typesafe.ai/jev-1.13.0",
        model = "jev-1.13.0",
        move = listOf(0.0, 0.0, 0.0, 0.0, 0.25, 0.75),
        again = listOf(0.1, 0.1, 0.1, 0.1, 0.9, 0.9),
        takes = List(6) { 0.1 },
        lead = listOf(0.0, 0.1, 0.8, 0.1, 0.0),
        leadExpected = 2.0,
    )

    private fun jsonl(name: String): File = File(dir, name).apply {
        writeText(Json.encodeToString(record) + "\n")
    }

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
    fun `html embeds the runs, the engine facts of each board, the leaderboard and the acceptAt slider`() {
        val page = html(mapOf("jev" to listOf(record)))

        assertFalse(page.contains("/*DATA*/"))
        assertContains(page, "\"label\":\"jev\"")
        assertContains(page, "\"position\":7")
        assertContains(page, "\"0,0,0,0,2,1,10,0,0,0,0,0,1,11\":{\"legal\":[4,5]")
        assertContains(page, "\"best\":[5]")
        assertContains(page, "baseline: greedy")
        assertContains(page, "<input type=\"range\"")
        assertContains(page, "model calls: 0")
    }

    // 2. run

    @Test
    fun `run with no arguments, a bad acceptAt or an empty file returns 2`() {
        assertEquals(2, run(emptyArray()))
        assertEquals(2, run(arrayOf("rank", jsonl("bad.jsonl").path, "--accept-at", "0.85,x")))
        assertEquals(2, run(arrayOf("rank", jsonl("bad.jsonl").path, "--accept-at", "0.4")))
        assertEquals(2, run(arrayOf("rank", File(dir, "empty.jsonl").apply { writeText("") }.path)))
    }

    @Test
    fun `run rank prints the leaderboard labelled by file name and returns 0`() {
        val file = jsonl("kev-4b.jsonl")

        val (exitCode, stdout) = captureStdout { run(arrayOf("rank", file.path, "--accept-at", "0.9,0.6")) }

        assertEquals(0, exitCode)
        assertEquals(leaderboard(mapOf("kev-4b" to listOf(record)), listOf(0.9, 0.6)), stdout.trim())
    }

    @Test
    fun `run html writes the page and returns 0`() {
        val out = File(dir, "kalah.html")

        val exitCode = run(arrayOf("html", jsonl("laya.jsonl").path, "--out", out.path))

        assertEquals(0, exitCode)
        assertContains(out.readText(), "\"label\":\"laya\"")
    }
}
