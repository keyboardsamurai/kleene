package kleene.demo.icd

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FixtureTest {

    private val docs = fixture()
    private val labelSet = labels()
    private val languages = listOf("en", "de", "es", "fr", "it", "pt", "nl")

    /** The count of each value of [axis] over the fixture. */
    private fun counts(axis: (Doc) -> String?): Map<String?, Int> = docs.groupingBy(axis).eachCount()

    /** The languages the documents with value [value] on [axis] are written in. */
    private fun languagesOf(axis: (Doc) -> String?, value: String): Set<String> =
        docs.filter { axis(it) == value }.map { it.language }.toSet()

    // 1. fixture records

    @Test
    fun `the fixture has 100 records with unique ids`() {
        assertEquals(100, docs.size)
        assertEquals(100, docs.map { it.id }.distinct().size)
    }

    @Test
    fun `every gold category is in the label set and every subcode refines its category`() {
        val codes = labelSet.codes.map { it.code }.toSet()

        docs.flatMap { doc -> doc.codes.map { doc.id to it } }.forEach { (id, gold) ->
            assertTrue(gold.category in codes, "$id: ${gold.category} is not in the label set")
            assertTrue(gold.subcode.startsWith(gold.category), "$id: ${gold.subcode} does not refine ${gold.category}")
        }
    }

    @Test
    fun `each non-empty document has one principal among its codes and 5 documents have no code at all`() {
        val (empty, coded) = docs.partition { it.codes.isEmpty() }

        assertEquals(5, empty.size)
        empty.forEach { assertNull(it.principal, "${it.id}: a principal without codes") }
        coded.forEach { doc ->
            val principal = assertNotNull(doc.principal, "${doc.id}: codes without a principal")
            assertTrue(principal in doc.categories, "${doc.id}: principal $principal is not in its codes")
        }
    }

    @Test
    fun `each document has 0 to 4 distinct codes, about 2 on average`() {
        docs.forEach { doc ->
            assertTrue(doc.codes.size in 0..4, "${doc.id}: ${doc.codes.size} codes")
            assertEquals(doc.codes.size, doc.categories.size, "${doc.id}: a category twice")
        }
        val average = docs.sumOf { it.codes.size } / docs.size.toDouble()
        assertTrue(average in 1.5..2.5, "average codes per document: $average")
    }

    @Test
    fun `each text has 30 to 350 words`() {
        docs.forEach { doc ->
            val words = doc.text.split(Regex("\\s+")).count { it.isNotEmpty() }
            assertTrue(words in 30..350, "${doc.id}: $words words")
        }
    }

    // 2. stratification

    @Test
    fun `about 15 documents are English, 12 to 13 are in each other language, and some are code-switched`() {
        val byLanguage = counts { it.language }

        assertTrue(byLanguage.getValue("en") in 14..16, "en: ${byLanguage["en"]}")
        (languages - "en").forEach { assertTrue(byLanguage[it] in 12..13, "$it: ${byLanguage[it]}") }
        assertTrue(byLanguage.getValue("mixed") >= 1)
        assertEquals(languages.toSet() + "mixed", byLanguage.keys)
    }

    @Test
    fun `every type, completeness level and provenance appears at least 3 times and in at least 3 languages`() {
        val axes = mapOf<String, Pair<(Doc) -> String?, Set<String>>>(
            "type" to Pair({ it.type }, setOf(
                "discharge summary", "GP note", "ER triage", "radiology report", "pathology report",
                "referral letter", "lab report", "nursing note", "patient message", "insurance claim note",
            )),
            "completeness" to Pair({ it.completeness }, setOf("full", "terse", "truncated", "no-diagnosis")),
            "provenance" to Pair({ it.provenance }, setOf("clinician", "dictation", "ocr", "patient", "machine-translated")),
        )

        axes.forEach { (name, axisAndValues) ->
            val (axis, values) = axisAndValues
            assertEquals(values, counts(axis).keys, "$name values")
            values.forEach { value ->
                assertTrue(counts(axis).getValue(value) >= 3, "$name $value: ${counts(axis)[value]} documents")
                assertTrue(languagesOf(axis, value).size >= 3, "$name $value: languages ${languagesOf(axis, value)}")
            }
        }
    }

    @Test
    fun `about 20 percent of the documents are hard cases and every hard kind is present`() {
        val byHard = counts { it.hard }
        val kinds = setOf("negation", "history", "family", "suspected", "no-code")

        assertEquals(kinds + null, byHard.keys)
        kinds.forEach { assertTrue(byHard.getValue(it) >= 3, "$it: ${byHard[it]}") }
        assertTrue(docs.count { it.hard != null } in 15..25)
    }

    @Test
    fun `a no-code hard case has no codes`() {
        docs.filter { it.hard == "no-code" }.forEach { assertTrue(it.codes.isEmpty(), "${it.id}: coded") }
    }

    // 3. state

    @Test
    fun `the state holds only the document text, and no text names a gold code`() {
        docs.forEach { doc ->
            val json = state(doc).value.jsonObject

            assertEquals(setOf("document"), json.keys, doc.id)
            assertEquals(doc.text, json.getValue("document").jsonPrimitive.content)
            doc.codes.flatMap { listOf(it.category, it.subcode) }.forEach { code ->
                val token = Regex("(?<![\\w.])${Regex.escape(code)}(?!\\w)")
                assertFalse(token.containsMatchIn(doc.text), "${doc.id}: the text names $code")
            }
        }
    }

    // 4. label set

    @Test
    fun `the label set has unique codes from at least 12 chapters`() {
        val codes = labelSet.codes.map { it.code }

        assertEquals(codes.size, codes.distinct().size)
        assertTrue(labelSet.codes.map { it.chapter }.distinct().size >= 12)
    }

    @Test
    fun `the label set holds the confusable pairs and the history, family and symptom codes`() {
        val codes = labelSet.codes.map { it.code }.toSet()
        val pairs = listOf("E10" to "E11", "I20" to "I21", "J44" to "J45", "N17" to "N18",
            "F32" to "F33", "K80" to "K81", "M80" to "M81", "E03" to "E05")

        pairs.forEach { (a, b) -> assertTrue(a in codes && b in codes, "missing pair $a/$b") }
        listOf("Z87", "Z80", "R07", "R50").forEach { assertTrue(it in codes, "missing $it") }
    }

    @Test
    fun `every code has a title and synonyms in all 7 languages`() {
        labelSet.codes.forEach { label ->
            assertTrue(label.title.isNotBlank(), "${label.code}: no title")
            languages.forEach { language ->
                val synonyms = label.synonyms[language].orEmpty()
                assertTrue(synonyms.isNotEmpty(), "${label.code}: no $language synonyms")
                assertTrue(synonyms.none { it.isBlank() }, "${label.code}: a blank $language synonym")
            }
        }
    }

    @Test
    fun `the label set names its public-domain source and marks its synonyms as not official`() {
        assertTrue(labelSet.source.contains("public domain", ignoreCase = true))
        assertTrue(labelSet.synonymsNote.contains("not official", ignoreCase = true))
    }

    // 5. version

    @Test
    fun `the fixture version is 16 hex chars and stable across calls`() {
        val version = fixtureVersion()

        assertTrue(version.matches(Regex("[0-9a-f]{16}")), version)
        assertEquals(version, fixtureVersion())
    }
}
