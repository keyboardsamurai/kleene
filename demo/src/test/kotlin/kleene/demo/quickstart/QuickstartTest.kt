package kleene.demo.quickstart

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals

class QuickstartTest {
    @Test
    fun `quick start shows all three outcomes without a model`() {
        val output = ByteArrayOutputStream()
        val original = System.out
        try {
            System.setOut(PrintStream(output))
            main()
        } finally {
            System.setOut(original)
        }
        assertEquals(
            listOf("TRUE -> flag", "FALSE -> queue", "UNKNOWN -> human review"),
            output.toString().trim().lines(),
        )
    }
}
