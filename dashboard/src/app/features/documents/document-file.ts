/**
 * **What the Document reader accepts, decided before a byte leaves the browser.**
 *
 * The server is the authority — `DocumentReaderService` sniffs the first sixteen bytes and answers
 * `413 too_large` or a 400 — and these two rules are the same two, asked here as well so that a
 * twelve-megabyte scan is refused in the moment she picks it instead of after it has been pushed
 * over a school's upstream. The message she reads is the same one either way
 * (`documents.errors.tooLarge`), because the rule is the same rule.
 *
 * Nothing here guesses at the *contents*: a `.pdf` that is really a zip is the server's 400, and
 * no client-side check can be the one that matters.
 */

/** 10 MB — `DocumentReaderService.MAX_BYTES`. */
export const MAX_DOCUMENT_BYTES = 10 * 1024 * 1024;

/** 20 pages — `DocumentReaderService.MAX_PAGES`. Said on the screen, counted on the server. */
export const MAX_DOCUMENT_PAGES = 20;

/**
 * The five kinds the server reads: a PDF, a Word file, and a JPEG, PNG or WebP photograph.
 *
 * Both the media type and the extension, because a browser hands over an empty `File.type` often
 * enough — Windows with no association for `.webp`, a file picked from some Android providers —
 * and refusing a readable circular over a missing header is worse than letting the server sniff it.
 */
const DOCUMENT_KINDS: readonly { readonly mime: string; readonly extensions: readonly string[] }[] = [
  { mime: 'application/pdf', extensions: ['.pdf'] },
  {
    mime: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    extensions: ['.docx'],
  },
  { mime: 'image/jpeg', extensions: ['.jpg', '.jpeg'] },
  { mime: 'image/png', extensions: ['.png'] },
  { mime: 'image/webp', extensions: ['.webp'] },
];

/** The `accept` attribute of the picker: the types and the extensions, so both kinds of dialog filter. */
export const DOCUMENT_ACCEPT = [
  ...DOCUMENT_KINDS.map((kind) => kind.mime),
  ...DOCUMENT_KINDS.flatMap((kind) => kind.extensions),
].join(',');

/** Which rule a picked file breaks, as the suffix of its `documents.errors.*` key, or `null`. */
export type DocumentFileError = 'tooLarge' | 'badType';

export function documentFileError(file: File): DocumentFileError | null {
  if (file.size > MAX_DOCUMENT_BYTES) return 'tooLarge';
  return accepted(file) ? null : 'badType';
}

function accepted(file: File): boolean {
  const type = file.type.toLowerCase();
  const name = file.name.toLowerCase();
  return DOCUMENT_KINDS.some(
    (kind) => kind.mime === type || kind.extensions.some((suffix) => name.endsWith(suffix)),
  );
}

/**
 * A key date as the school says it: `2026-11-03` in the reader's language, in the school's zone.
 *
 * The date is a **calendar day**, not an instant, so it is read at noon UTC: midnight would land
 * on the day before in every zone west of Greenwich and the day itself everywhere else, which is
 * the one-day-early bug `dayIn` documents one file over. Noon is inside the same calendar day for
 * every real offset.
 */
export function keyDateLabel(date: string, lang: string, zone: string): string {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return date;
  const at = new Date(`${date}T12:00:00Z`);
  if (Number.isNaN(at.getTime())) return date;
  return new Intl.DateTimeFormat(lang === 'ar' ? 'ar' : 'en-GB', {
    timeZone: zone,
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  }).format(at);
}
