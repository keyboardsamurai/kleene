package kleene

/** Feels [Evidence] as a judge would report p(true) = [p]. */
internal fun feelsEvidence(p: Double) = Evidence.feels(p, "test", "m")

/** Choose [Evidence] over string options, in the given order. */
internal fun chooseEvidence(vararg probabilities: Pair<String, Double>, confidence: Double? = null) =
    Evidence.choose(probabilities.toMap(), "test", "m", confidence)
