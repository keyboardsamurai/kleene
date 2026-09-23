package kleene.demo.icd

import kleene.Policy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScoreTest {

    private fun category(code: String, vararg synonyms: String) = Category(code, 1, "title of $code", mapOf("en" to synonyms.toList()))

    private val categories = listOf(category("A"), category("B"), category("C"))

    private fun doc(id: String, principal: String?, vararg codes: String, language: String = "en", text: String = "text") =
        Doc(id, text, language, "GP note", "full", "clinician", null, principal, codes.map { Gold(it, "$it.9") })

    private fun record(id: String, feels: Map<String, Double>, principal: Map<String, Double>, fits: Boolean = true) =
        Record(id, "j", "m", feels, principal, null, "v", fits)

    /** Gold A and B, principal A. At 0.85: A TRUE (TP), B UNKNOWN on a gold code, C TRUE (FP); principal A accepted. */
    private val doc1 = doc("d1", "A", "A", "B", language = "de")
    private val onDoc1 = record("d1", mapOf("A" to 0.9, "B" to 0.5, "C" to 0.95), mapOf("A" to 0.9, "B" to 0.05, "C" to 0.0, NONE to 0.05))

    /** No gold code. At 0.85: A FALSE (TN), B UNKNOWN at 0.2, C FALSE (TN); principal none at 0.6 is UNKNOWN. */
    private val doc2 = doc("d2", null, language = "fr")
    private val onDoc2 = record("d2", mapOf("A" to 0.1, "B" to 0.2, "C" to 0.05), mapOf("A" to 0.4, "B" to 0.0, "C" to 0.0, NONE to 0.6))

    private val scored = listOf(onDoc1 to doc1, onDoc2 to doc2)

    @Test
    fun `cells are TP, FP, FN or TN on decided values, and UNKNOWN is counted apart`() {
        val m = metrics(scored, categories.map { it.code }, Policy(0.85))

        assertEquals(listOf(1, 1, 0, 2, 2), listOf(m.tp, m.fp, m.fn, m.tn, m.unknown))
    }

    @Test
    fun `micro and macro precision, recall and F1 treat UNKNOWN as not predicted`() {
        val m = metrics(scored, categories.map { it.code }, Policy(0.85))

        // TP 1, FP 1, gold positives 2 (A and the UNKNOWN B): P 1/2, R 1/2. Macro over codes with gold: A 1.0, B 0.0.
        assertEquals(0.5, m.precision, 1e-9)
        assertEquals(0.5, m.recall, 1e-9)
        assertEquals(0.5, m.microF1, 1e-9)
        assertEquals(0.5, m.macroF1, 1e-9)
    }

    @Test
    fun `coverage is decided cells over all cells, and accuracy is taken on decided cells`() {
        val at85 = metrics(scored, categories.map { it.code }, Policy(0.85))
        val at60 = metrics(scored, categories.map { it.code }, Policy(0.60))

        assertEquals(4.0 / 6, at85.coverage, 1e-9)
        assertEquals(3.0 / 4, at85.accuracy, 1e-9)
        // At 0.60 the band is [0.4, 0.6]: B at 0.2 on d2 becomes FALSE, B at 0.5 on d1 stays UNKNOWN.
        assertEquals(5.0 / 6, at60.coverage, 1e-9)
        assertEquals(4.0 / 5, at60.accuracy, 1e-9)
    }

    @Test
    fun `principal top-1 counts decided answers, the UNKNOWN rate, and none as a correct answer for an empty document`() {
        val at85 = metrics(scored, categories.map { it.code }, Policy(0.85))
        val at60 = metrics(scored, categories.map { it.code }, Policy(0.60))

        assertEquals(1.0, at85.top1Decided, 1e-9)
        assertEquals(0.5, at85.unknownRate, 1e-9)
        assertEquals(0.5, at85.top1All, 1e-9)
        assertEquals(1.0, at60.top1All, 1e-9)
        assertEquals(0.0, at60.unknownRate, 1e-9)
    }

    @Test
    fun `AUC ranks every gold cell over every other cell on raw p, ties counting half`() {
        // Gold: A 0.9, B 0.5. Others: C 0.95, A 0.1, B 0.2, C 0.05. Each gold cell beats 3 of 4: 6 of 8.
        assertEquals(0.75, metrics(scored, categories.map { it.code }, Policy()).auc, 1e-9)
        val tied = record("d1", mapOf("A" to 0.5, "B" to 0.5, "C" to 0.5), onDoc1.principal)
        assertEquals(0.5, metrics(listOf(tied to doc1), categories.map { it.code }, Policy()).auc, 1e-9)
    }

    @Test
    fun `acceptAt 0_5 decides every cell that is not exactly 0_5 and every principal`() {
        val m = metrics(scored, categories.map { it.code }, policyAt(0.5))

        assertEquals(1, m.unknown)
        assertEquals(0.0, m.unknownRate, 1e-9)
    }

    /** The cells of the first markdown table line whose first cells are [first]. */
    private fun cells(markdown: String, vararg first: String): List<String> =
        markdown.lines().map { line -> line.trim().trim('|').split('|').map { it.trim() } }
            .first { it.take(first.size) == first.toList() }

    @Test
    fun `leaderboard prints each run at the default acceptAt, the sweep, and every metric per axis value and run`() {
        val table = leaderboard(mapOf("kev" to listOf(onDoc1, onDoc2)), listOf(doc1, doc2), categories, listOf(0.85, 0.6))

        // run, n, micro P, micro R, micro F1, macro F1, coverage, accuracy, AUC, top-1 decided, unknown, top-1 all, cut
        assertEquals(
            listOf("kev", "2", "0.50", "0.50", "0.50", "0.50", "67%", "75%", "0.75", "100%", "50%", "50%", "0"),
            cells(table, "kev"),
        )
        // acceptAt, run, micro P, micro R, micro F1, macro F1, coverage, accuracy, top-1 decided, unknown, top-1 all
        assertEquals(
            listOf("0.60", "kev", "0.50", "0.50", "0.50", "0.50", "83%", "80%", "100%", "0%", "100%"),
            cells(table, "0.60", "kev"),
        )
        // Per axis: value, run, then the main table's metrics. d1 (de) alone: 1 TP, 1 FP, B UNKNOWN, both gold cells
        // under C; d2 (fr) alone has no gold code, so P, R, F1 and AUC are undefined and its principal is UNKNOWN.
        assertEquals(
            listOf("de", "kev", "1", "0.50", "0.50", "0.50", "0.50", "67%", "50%", "0.00", "100%", "0%", "100%"),
            cells(table, "de", "kev"),
        )
        assertEquals(
            listOf("fr", "kev", "1", "–", "–", "–", "–", "67%", "100%", "–", "–", "100%", "0%"),
            cells(table, "fr", "kev"),
        )
        assertEquals(listOf("true", "kev", "2"), cells(table, "true", "kev").take(3))
        assertEquals(listOf("de", "baseline: always empty", "1"), cells(table, "de", "baseline: always empty").take(3))
        assertTrue(table.lines().none { it.startsWith("| false |") }, "no row for an axis value without records")
    }

    @Test
    fun `the always-empty baseline says FALSE to every code and none for the principal`() {
        val empty = baselines(listOf(doc1, doc2), categories).getValue("baseline: always empty")
        val m = metrics(empty zip listOf(doc1, doc2), categories.map { it.code }, Policy())

        assertEquals(listOf(0, 0, 2, 4, 0), listOf(m.tp, m.fp, m.fn, m.tn, m.unknown))
        assertEquals(0.5, m.top1All, 1e-9)
    }

    @Test
    fun `the most-frequent baseline codes the most frequent gold category as the principal and only code`() {
        val docs = listOf(doc1, doc2, doc("d3", "B", "B", "C"))
        val frequent = baselines(docs, categories).getValue("baseline: most frequent (B)")
        val m = metrics(frequent zip docs, categories.map { it.code }, Policy())

        // B is gold twice. Predicting B everywhere: TP on d1 and d3, FP on d2; principal right on d3 only.
        assertEquals(listOf(2, 1), listOf(m.tp, m.fp))
        assertEquals(1.0 / 3, m.top1All, 1e-9)
    }

    @Test
    fun `the keyword baseline matches whole synonyms in any language, case-insensitive, and misses a negation`() {
        val keywordCategories = listOf(category("E11", "type 2 diabetes"), category("J18", "pneumonia", "Lungenentzündung"), category("I10", "HTN"))
        val text = "Known Type 2 Diabetes. Chest X-ray: no pneumonia. Pneumonias of the past. HTNx noted."
        val docs = listOf(doc("k1", "E11", "E11", text = text), doc("k2", "J18", "J18", text = "V.a. Lungenentzündung rechts"))

        val keyword = baselines(docs, keywordCategories).getValue("baseline: keyword")

        assertEquals(mapOf("E11" to 1.0, "J18" to 1.0, "I10" to 0.0), keyword[0].feels)
        assertEquals("E11", keyword[0].principal.maxBy { it.value }.key)
        assertEquals(mapOf("E11" to 0.0, "J18" to 1.0, "I10" to 0.0), keyword[1].feels)
        val none = baselines(listOf(doc("k3", null, text = "nothing here")), keywordCategories).getValue("baseline: keyword")
        assertEquals(NONE, none[0].principal.maxBy { it.value }.key)
    }
}
