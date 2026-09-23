package kleene.demo.icd

import kleene.State
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.HexFormat

/** One ICD-10-CM 3-character category: the official English [title] and our own demo [synonyms] per language. */
@Serializable
data class Category(val code: String, val chapter: Int, val title: String, val synonyms: Map<String, List<String>>)

/** The committed label set, with the [source] of its titles and the note that its synonyms are not official. */
@Serializable
data class LabelSet(val source: String, val synonymsNote: String, val codes: List<Category>)

/** A gold code: the 3-character [category] the bench scores, and the full ICD-10-CM [subcode] kept for later. */
@Serializable
data class Gold(val category: String, val subcode: String)

/**
 * One synthetic clinical document with its axis tags and gold codes. Only [text] is ever sent to a Judge.
 * [hard] is null for an ordinary document; [principal] is null exactly when [codes] is empty.
 */
@Serializable
data class Doc(
    val id: String,
    val text: String,
    val language: String,
    val type: String,
    val completeness: String,
    val provenance: String,
    val hard: String? = null,
    val principal: String? = null,
    val codes: List<Gold>,
) {
    val categories: Set<String> get() = codes.map { it.category }.toSet()
}

private const val LABELS = "/kleene/demo/icd/labels.json"
private const val FIXTURE = "/kleene/demo/icd/fixture.jsonl"

private fun resource(path: String): ByteArray =
    object {}.javaClass.getResourceAsStream(path)?.use { it.readBytes() } ?: error("missing resource $path")

/** The committed label set. */
fun labels(): LabelSet = Json.decodeFromString(resource(LABELS).decodeToString())

/** The committed fixture, in file order. */
fun fixture(): List<Doc> =
    resource(FIXTURE).decodeToString().lines().filter { it.isNotBlank() }.map { Json.decodeFromString<Doc>(it) }

/** The first 16 hex chars of sha256(label set bytes, fixture bytes): editing either one gives a new version. */
fun fixtureVersion(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(resource(LABELS))
    digest.update(resource(FIXTURE))
    return HexFormat.of().formatHex(digest.digest()).take(16)
}

/** The [State] of [doc]: its text and nothing else, so no axis tag or gold code reaches the Judge. */
fun state(doc: Doc): State.Json = State.Json(buildJsonObject { put("document", doc.text) })
