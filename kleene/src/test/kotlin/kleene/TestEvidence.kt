package kleene

/** Feels [Evidence] as a judge would report p(true) = [p]. */
internal fun feelsEvidence(p: Double) =
    Evidence(Kind.FEELS, listOf(true, false), listOf(p, 1 - p), null, "test", "m")

/** Choose [Evidence] over string options, in the given order. */
internal fun chooseEvidence(vararg probabilities: Pair<String, Double>, confidence: Double? = null) =
    Evidence(Kind.CHOOSE, probabilities.map { it.first }, probabilities.map { it.second }, confidence, "test", "m")
