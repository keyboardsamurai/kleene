package kleene.demo.kalah

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Renders [runs] (label to records) as the single-file HTML page (`board.html` on the classpath): the Kotlin
 * [leaderboard] at the default [ACCEPT_ATS], the engine [Facts] of every board keyed by the board joined with
 * commas, and one card per position. A slider reapplies its `acceptAt` to every card client-side with zero model
 * calls.
 */
fun html(runs: Map<String, List<Record>>): String {
    val facts = factsOf(runs)
    val data = buildJsonObject {
        putJsonArray("runs") {
            runs.forEach { (label, records) ->
                addJsonObject {
                    put("label", label)
                    put("records", Json.encodeToJsonElement(records))
                }
            }
        }
        putJsonObject("facts") { facts.forEach { (board, boardFacts) -> put(board.joinToString(","), Json.encodeToJsonElement(boardFacts)) } }
        putJsonArray("levels") { LEAD_LEVELS.forEach { add(it) } }
        put("leaderboard", leaderboard(runs, ACCEPT_ATS, facts))
    }
    // "</" never occurs in JSON outside a string, and "<\/" is the same string, so no label can close the <script>.
    return boardTemplate().replace("/*DATA*/", data.toString().replace("</", "<\\/"))
}

/** Loaded relative to this package through the class's own loader, which also works under `exec:java`. */
private fun boardTemplate(): String =
    checkNotNull(Record::class.java.getResourceAsStream("board.html")) { "board.html missing from the classpath" }
        .bufferedReader(Charsets.UTF_8)
        .readText()
