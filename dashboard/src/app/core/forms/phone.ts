/**
 * The telephone-number rule, mirroring the server's one place that reads one
 * (`quest.server.platform.Phones`).
 *
 * MH1 stores the **normalised** number and never what was typed: separators a person uses — spaces,
 * dashes, brackets, dots — are dropped, a leading `00` becomes `+`, and what is stored is `+`
 * followed by digits or digits alone. `users.phone` and `parents.phone` are 20 characters because
 * that is E.164's own maximum with nothing to spare.
 *
 * Deliberately loose about *which* numbers exist and strict about the shape, for the server's own
 * reason: the school types numbers in half a dozen countries' formats and a rule that knew each of
 * them would refuse the one it had not heard of. Seven to fifteen digits, nothing but digits and the
 * separators above, and a `+` only at the front.
 *
 * Why the dashboard has a copy at all: without one the school learns the rule from a red band after
 * a request that also wrote nothing. This is the *same* rule, so a number this accepts is a number
 * the server accepts — and when the two ever disagree the server is right, which is why every form
 * still shows its refusal.
 */

/** E.164: at most fifteen digits, and nobody's number is shorter than seven. */
const MIN_DIGITS = 7;
const MAX_DIGITS = 15;
/** What `users.phone` and `parents.phone` hold, so a longer *typed* value cannot normalise into it. */
export const MAX_PHONE_LENGTH = 20;

const SEPARATORS = new Set([' ', '-', '(', ')', '.']);

/** The stored form of what was typed, or `null` for a blank value or one this rule refuses. */
export function normalisePhone(raw: string): string | null {
  const trimmed = raw.trim();
  if (trimmed === '') return null;
  const plus = trimmed.startsWith('+') || trimmed.startsWith('00');
  let digits = '';
  for (let at = 0; at < trimmed.length; at += 1) {
    const character = trimmed[at] ?? '';
    if (character >= '0' && character <= '9') digits += character;
    // A `+` is a country code, so it belongs at the front and nowhere else: `050+1002030` is two
    // numbers run together or a typo, and dropping the sign stores one plausible number for both.
    else if (character === '+' && at > 0) return null;
    else if (character !== '+' && !SEPARATORS.has(character)) return null;
  }
  const body = plus && digits.startsWith('00') ? digits.slice(2) : digits;
  if (body.length < MIN_DIGITS || body.length > MAX_DIGITS) return null;
  return plus ? `+${body}` : body;
}

/**
 * Whether a typed number is one the server will take. A blank value is **fine** — "she has not
 * given us one" is a state every directory screen prints.
 */
export function isPhone(raw: string): boolean {
  return raw.trim() === '' || normalisePhone(raw) !== null;
}

/**
 * The translation key for what is wrong with this number, or `null`.
 *
 * A key rather than a sentence, so the one caller that has `TranslocoService` does the translating
 * and this file stays free of it — and so the same rule can be asserted without a bundle.
 */
export function phoneErrorKey(raw: string): string | null {
  return isPhone(raw) ? null : 'form.phone.invalid';
}
