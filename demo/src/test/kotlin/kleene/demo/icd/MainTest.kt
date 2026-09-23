package kleene.demo.icd

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class MainTest {

    @Test
    fun `rank on a missing jsonl is a usage error`() {
        assertEquals(2, run(arrayOf("rank", "/no/such/dir/kev.jsonl")))
    }

    @Test
    fun `log with a cut id that is not in the fixture is a usage error, before any judge is built`() {
        val cut = File.createTempFile("cut", ".txt").apply {
            deleteOnExit()
            writeText("no-such-document\n")
        }
        val out = File.createTempFile("icd", ".jsonl").apply { delete() }

        assertEquals(2, run(arrayOf("log", "--cut", cut.path, "--out", out.path)))
        assertEquals(false, out.exists())
    }
}
