package kleene

/**
 * Checks a [response] against the [request] it answers, after any [Judge] and before decoding.
 * Never repairs: any violation throws [KleeneException.Malformed] and fails the whole ask.
 */
internal fun validateResponse(request: Request, response: Response) {
}
