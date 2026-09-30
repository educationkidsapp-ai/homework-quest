package quest.feature.parent.domain

/**
 * MH3: the client's mirror of `quest.server.platform.Phones` — the one rule the server applies to a telephone number,
 * so the parent is told what is wrong before a round trip rather than after a 400.
 *
 * The server's rule, verbatim: separators (spaces, dashes, brackets, dots) are dropped, a leading `00` becomes `+`, a
 * `+` anywhere but the front is a refusal, and what is left is seven to fifteen digits — E.164's own maximum, loose
 * about *which* numbers exist and strict about the shape. A blank value clears the number rather than storing "".
 *
 * **What is sent is the normalised number**, not what she typed: the server would normalise it anyway, and sending the
 * same string it will store is what makes "Saved" mean the field on the screen.
 */
object Phones {
    private const val MIN_DIGITS = 7
    private const val MAX_DIGITS = 15
    /** Exactly the characters the server drops — nothing else, or the app would accept what the server refuses. */
    private const val SEPARATORS = "- ()."

    /** The stored form of [raw], an empty string for a blank one, or null when the shape is wrong. */
    fun normalise(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val plus = trimmed.startsWith("+") || trimmed.startsWith("00")
        val digits = StringBuilder()
        trimmed.forEachIndexed { index, c ->
            when {
                c.isDigit() -> digits.append(c)
                // A `+` is a country code, so it belongs at the front and nowhere else: "050+1002030" is two numbers run
                // together or a typo, and dropping the sign would store one plausible-looking number for both.
                c == '+' -> if (index > 0) return null
                c in SEPARATORS -> Unit
                else -> return null
            }
        }
        val body = if (plus && digits.startsWith("00")) digits.substring(2) else digits.toString()
        if (body.length !in MIN_DIGITS..MAX_DIGITS) return null
        return if (plus) "+$body" else body
    }

    /** Whether [raw] is a number the server will accept (a blank one clears it, and clearing is always allowed). */
    fun isValid(raw: String): Boolean = normalise(raw) != null
}
