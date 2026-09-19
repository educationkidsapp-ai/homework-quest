import { HttpErrorResponse } from '@angular/common/http';

/**
 * The server's error body: `{ code, message }` (`ApiException.ApiError`).
 *
 * It is not in `components/schemas` — error responses are not described in the OpenAPI
 * document — so it is declared here, next to the generated client, rather than invented
 * again in each screen. Codes the server uses: `bad_request`, `unauthorized`, `forbidden`,
 * `not_found`, `conflict`, plus validation failures.
 */
export interface ApiError {
  readonly code: string;
  readonly message: string;
}

/**
 * The `{code, message}` out of a failed request, or `null` when the failure was not the
 * server speaking — a dropped connection, a CORS refusal, an HTML error page from a proxy.
 * Those have a status of 0 or a body that is not our shape, and the caller must say
 * "could not reach the server" rather than show whatever text came back.
 *
 * **CR5.** `message` comes back through {@link readableServerText}, and this answers `null`
 * when nothing readable survives it. Every caller already writes
 * `apiErrorOf(error)?.message ?? t('band.unreachable')`, so a message that was only a JSON blob
 * becomes the translated sentence rather than a data format in a red band. {@link apiErrorCodeOf}
 * is for branching on the code, where the text does not matter.
 */
export function apiErrorOf(error: unknown): ApiError | null {
  const raw = rawApiErrorOf(error);
  if (raw === null) return null;
  const message = readableServerText(raw.message);
  return message === '' ? null : { code: raw.code, message };
}

/** The server's `code` alone — for branching, and never shown. */
export function apiErrorCodeOf(error: unknown): string | null {
  return rawApiErrorOf(error)?.code ?? null;
}

function rawApiErrorOf(error: unknown): ApiError | null {
  if (!(error instanceof HttpErrorResponse)) return null;
  const body: unknown = error.error;
  if (body === null || typeof body !== 'object') return null;
  const { code, message } = body as Record<string, unknown>;
  if (typeof code !== 'string' || typeof message !== 'string') return null;
  return { code, message };
}

/** A JSON Pointer the schema validator names a field by — `#/stops/3/options`. */
const POINTER = /#?\/[A-Za-z0-9_$]+(?:\/[A-Za-z0-9_$]+)*/g;
/** `"question":` left behind once the braces around it are gone. */
const KEYISH = /"[A-Za-z0-9_$]+"\s*:?/g;
/** Parentheses whose contents are now neither letter nor digit — a removed blob's leftovers. */
const EMPTY_PARENS = /\(\s*[^()\p{L}\p{N}]*\)/gu;

/**
 * The prose in a server message, with any JSON in it removed.
 *
 * CR5's rule is that a teacher never sees a data format, and the server's own messages are where
 * one leaks: `LessonSteps.Messages.of` appends the failing exception's text in parentheses,
 * truncated at 160 characters, and for a schema or model failure that text is the JSON that did
 * not validate — or half of it, cut mid-brace. Rather than ask the server to keep two
 * vocabularies, the dashboard shows only what is left when the braces, the pointers and the bare
 * keys are taken out, and says nothing at all (`''`) when that is everything: the caller then
 * falls back to its own sentence, which is always better than a fragment.
 *
 * Deliberately blunt. It deletes and never interprets, so the worst case is a message that says
 * less than it could — never one that says something a teacher cannot read.
 */
export function readableServerText(raw: string): string {
  const stripped = tidyParens(stripJsonRuns(raw))
    .replace(POINTER, ' ')
    .replace(KEYISH, ' ')
    // An orphaned ":" or ";" where the thing it introduced has just been deleted.
    .replace(/([.!?])\s*[:;,]+/g, '$1')
    .replace(/\s+/g, ' ')
    .replace(/\s+([,.;:!?])/g, '$1')
    .replace(/^[-–—,.;:…\s]+/, '')
    .replace(/[,;:\s]+$/, '')
    .trim();
  // A remnant with no letters in it ("…", "-", "2") is punctuation, not a sentence.
  return /\p{L}/u.test(stripped) ? stripped : '';
}

/**
 * Everything between a `{` or `[` and its match, dropped — and everything after an opener that
 * never closes, which is what the 160-character truncation leaves.
 *
 * A scan rather than a regex because the runs nest: a non-greedy `[^]*?` stops at the first `}`
 * and hands the rest of the document back as "prose".
 */
function stripJsonRuns(text: string): string {
  let out = '';
  let depth = 0;
  for (const ch of text) {
    if (ch === '{' || ch === '[') depth++;
    else if (ch === '}' || ch === ']') {
      if (depth > 0) depth--;
    } else if (depth === 0) out += ch;
  }
  return out;
}

/** The parentheses the removed blob sat in: empty ones go, and a lone one means it was cut. */
function tidyParens(text: string): string {
  const withoutEmpty = text.replace(EMPTY_PARENS, ' ');
  const opens = (withoutEmpty.match(/\(/g) ?? []).length;
  const closes = (withoutEmpty.match(/\)/g) ?? []).length;
  return opens === closes ? withoutEmpty : withoutEmpty.replace(/[()]/g, ' ');
}
