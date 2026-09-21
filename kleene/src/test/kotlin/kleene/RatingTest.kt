package kleene

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RatingTest {

    private fun rating(vararg probabilities: Double) = Rating(
        levels = probabilities.indices.map { "level $it" },
        probabilities = probabilities.toList(),
        expected = probabilities.withIndex().sumOf { (i, p) -> i * p },
        confidence = null,
        judge = "test",
        model = "m",
    )

    @Test
    fun `mode is the first most likely level`() {
        assertEquals(1, rating(0.1, 0.4, 0.4, 0.1).mode)
        assertEquals(3, rating(0.1, 0.2, 0.3, 0.4).mode)
    }

    @Test
    fun `probabilityAtOrAbove sums the level and every level above it`() {
        val rating = rating(0.1, 0.2, 0.3, 0.4)

        assertEquals(1.0, rating.probabilityAtOrAbove(0), 1e-12)
        assertEquals(0.7, rating.probabilityAtOrAbove(2), 1e-12)
        assertEquals(0.4, rating.probabilityAtOrAbove(3), 1e-12)
    }

    @Test
    fun `probabilityAtOrAbove rejects a level outside the rubric`() {
        assertFailsWith<IllegalArgumentException> { rating(0.5, 0.5).probabilityAtOrAbove(2) }
        assertFailsWith<IllegalArgumentException> { rating(0.5, 0.5).probabilityAtOrAbove(-1) }
    }
}
