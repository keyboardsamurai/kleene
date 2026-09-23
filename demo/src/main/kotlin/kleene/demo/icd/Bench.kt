package kleene.demo.icd

import kleene.Kleene
import kleene.Question
import kleene.Verdict
import kleene.ask
import kleene.choose
import kleene.feels
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** The value of the principal `choose` option "none of these": no codable diagnosis. */
const val NONE = "none"

/**
 * One ask about one document, serialized: every probability exactly as received. [feels] is p(true) per code,
 * [principal] p per code plus [NONE], [confidence] the judge's own choose confidence. [version] is the
 * [fixtureVersion] the document came from; [fits] says whether the longest request fitted the judge's context.
 */
@Serializable
data class Record(
    val id: String,
    val judge: String,
    val model: String,
    val feels: Map<String, Double>,
    val principal: Map<String, Double>,
    val confidence: Double? = null,
    val version: String,
    val fits: Boolean = true,
)

/**
 * The fixed question set, bound to [ai]: one `feels` per code, named by the code, and one `choose` named
 * `principal` with an option per code and "none of these". Fixed names keep every wire id the same per document.
 */
class Questions(ai: Kleene, labels: List<Label>) {
    val feels: Map<String, Question<Verdict<Boolean>>> = labels.associate { label ->
        label.code to ai.feels(
            "The document supports ${label.code} ${label.title} as a current diagnosis of this patient",
            name = label.code,
        )
    }
    val principal = ai.choose(
        "Which is the principal diagnosis of this patient in the document?",
        *(labels.map { "${it.code} ${it.title}" to it.code } + ("none of these" to NONE)).toTypedArray(),
        name = "principal",
    )
    val all: Array<Question<*>> = (feels.values + principal).toTypedArray()
}

/**
 * Appends one [Record] per document of [docs] to [out], one ask each, skipping ids already there so a rerun
 * after a crash resumes; that is the whole error strategy. [cut] lists the ids whose longest request is over the
 * judge's context window (counted outside, with the judge's tokenizer); their records get `fits = false`.
 *
 * @throws IllegalStateException if a logged record has another judge or another [version]: one file holds one
 *   judge on one benchmark.
 * @throws kleene.KleeneException if the judge fails: a failure is never turned into UNKNOWN or a missing cell.
 */
suspend fun log(ai: Kleene, labels: List<Label>, docs: List<Doc>, out: File, version: String, cut: Set<String> = emptySet()) {
    val logged = if (out.exists()) readRecords(out) else emptyList()
    logged.forEach { previous ->
        check(previous.judge == ai.judge.id) {
            "${previous.id} in $out is from judge ${previous.judge}, not ${ai.judge.id}: log each judge into its own file"
        }
        check(previous.version == version) {
            "${previous.id} in $out is from fixture version ${previous.version}, not $version: log each version into its own file"
        }
    }
    val done = logged.map { it.id }.toSet()
    val questions = Questions(ai, labels)
    docs.forEachIndexed { i, doc ->
        if (doc.id in done) return@forEachIndexed
        out.appendText(Json.encodeToString(recordFor(ai, questions, doc, version, doc.id !in cut)) + "\n")
        println("logged ${doc.id} (${i + 1} of ${docs.size})")
    }
}

private suspend fun recordFor(ai: Kleene, questions: Questions, doc: Doc, version: String, fits: Boolean): Record {
    val answers = ai.ask(state(doc), *questions.all)
    val principal = answers[questions.principal].evidence
    return Record(
        id = doc.id,
        judge = answers.judge,
        model = answers.model,
        feels = questions.feels.mapValues { (_, question) -> answers[question].evidence.probabilityOf(true) },
        principal = principal.options.zip(principal.probabilities).toMap(),
        confidence = principal.confidence,
        version = version,
        fits = fits,
    )
}

/** Reads every [Record] in [file], one JSONL line each. */
fun readRecords(file: File): List<Record> =
    file.readLines().filter { it.isNotBlank() }.map { Json.decodeFromString<Record>(it) }
