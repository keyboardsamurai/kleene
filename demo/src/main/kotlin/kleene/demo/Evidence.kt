package kleene.demo

import kleene.Evidence
import kleene.Kind

/**
 * The `feels` [Evidence] of a logged p(true). ponytail: p(false) is rebuilt as 1-p; `decide` reads p(true) only,
 * so the verdict is exact.
 */
internal fun feelsEvidence(pTrue: Double, judge: String, model: String) =
    Evidence(Kind.FEELS, listOf(true, false), listOf(pTrue, 1 - pTrue), null, judge, model)
