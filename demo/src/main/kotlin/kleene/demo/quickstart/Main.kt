package kleene.demo.quickstart

import kleene.*
import kleene.Truth.*
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    for (p in listOf(0.95, 0.05, 0.55)) {
        val ai = Kleene(ScriptedJudge { feels("urgent", p) })
        val urgent by ai.feels("Needs a response today")

        when (urgent("Can you look at this?").truth) {
            TRUE -> println("TRUE -> flag")
            FALSE -> println("FALSE -> queue")
            UNKNOWN -> println("UNKNOWN -> human review")
        }
    }
}
