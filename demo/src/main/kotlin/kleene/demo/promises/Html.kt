package kleene.demo.promises

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Renders [records] as the single-file HTML grid (`grid.html` on the classpath): a slider reapplies its `acceptAt`
 * to every cell client-side with zero model calls. [repoUrl], when set, lets a cell's detail panel link to its
 * commit; when null, no link is offered.
 */
fun html(records: List<Record>, repoUrl: String?): String {
    val data = buildJsonObject {
        put("repoUrl", repoUrl)
        putJsonArray("records") { records.forEach { add(Json.encodeToJsonElement(it)) } }
    }
    return gridTemplate().replace("/*DATA*/", data.toString())
}

/** Loaded relative to this package through the class's own loader, which also works under `exec:java`. */
private fun gridTemplate(): String =
    checkNotNull(Record::class.java.getResourceAsStream("grid.html")) { "grid.html missing from the classpath" }
        .bufferedReader(Charsets.UTF_8)
        .readText()
