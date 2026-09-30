package quest.server.platform;

import quest.server.config.ApiException;

/**
 * MH1: the one place a telephone number is read. `users.phone` and `parents.phone` are 20 characters because that is
 * E.164's own maximum (`+` and at most 15 digits) with nothing to spare, so what is stored is the normalised number
 * and never what was typed: the separators a person uses — spaces, dashes, brackets, dots — are dropped, a leading
 * `00` becomes `+`, and the result is `+` followed by digits, or digits alone for a local number.
 *
 * <p>Validation is deliberately loose about <em>which</em> numbers exist and strict about the shape: the school types
 * numbers in half a dozen countries' formats and a server that knew each of them would refuse the one it had not
 * heard of. Seven to fifteen digits, nothing but digits, and a blank value is null rather than an empty string —
 * "she has not given us one" is the state the directory screens print.
 */
public final class Phones {
    private Phones() {}
    /** E.164: at most fifteen digits, and nobody's number is shorter than seven. */
    private static final int MIN_DIGITS = 7, MAX_DIGITS = 15;

    /** The stored form of what a request sent, or null for a blank one. */
    public static String normalise(String raw, String field) {
        if (raw == null || raw.isBlank()) return null;
        var digits = new StringBuilder();
        String trimmed = raw.trim();
        boolean plus = trimmed.startsWith("+") || trimmed.startsWith("00");
        for (char c : trimmed.toCharArray()) {
            if (Character.isDigit(c)) digits.append(c);
            else if (c != '+' && c != '-' && c != ' ' && c != '(' && c != ')' && c != '.')
                throw ApiException.badRequest(field + " may hold digits, spaces, dashes, brackets and a leading +.");
        }
        String body = plus && digits.indexOf("00") == 0 ? digits.substring(2) : digits.toString();
        if (body.length() < MIN_DIGITS || body.length() > MAX_DIGITS)
            throw ApiException.badRequest(field + " needs between " + MIN_DIGITS + " and " + MAX_DIGITS + " digits.");
        return plus ? "+" + body : body;
    }
}
