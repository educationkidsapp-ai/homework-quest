/**
 * CR4 §4: what a source file's conversion looks like to a teacher.
 *
 * The pipeline gained a `convert` step between `upload` and `analyze` — every uploaded file is
 * turned into Markdown on our own machines and the model is handed that text and nothing else.
 * A teacher never hears the words "Markdown", "anydoc" or "OCR failed": she sees a file that is
 * *Converting…*, then *Ready*, or one we could not read with a sentence that says what to do
 * next. This module is the whole of that translation, kept pure so every code has a test rather
 * than a screenshot.
 *
 * Nothing here formats a number or reads a bundle: it answers in translation **keys** and plain
 * counts, and the component supplies the language. That is what lets the same mapping serve the
 * pill, the step strip's error sentence and the fallback dialog's title.
 */
import { type SourceFileInfo, SourceFileInfoConvertStatusEnum } from '../../api';

export type ConvertStatus = 'pending' | 'converting' | 'ready' | 'error';

/** `SourceFile.convertErrorCode` — the seven the server can write. */
export type ConvertErrorCode =
  | 'encrypted'
  | 'unsupported'
  | 'malformed'
  | 'needs_ocr'
  | 'ocr_failed'
  | 'tool_missing'
  | 'io';

/** How the text was got: the document converter, Tesseract, or the teacher's own typing. */
export type ConvertMethod = 'anydoc' | 'ocr' | 'text';

/** The fallback a teacher can ask for on a file we could not read. */
export type RetryMethod = 'ocr' | 'text';

const I18N = 'lessons.detail.files.convert';

/**
 * Words, not characters.
 *
 * `markdownChars` is what the server counts, and a character count tells a teacher nothing —
 * "1,240 words" is the size of a lesson she can picture. Five characters to a word is the usual
 * English average and close enough in Arabic for a rounded figure whose only job is to say
 * "this is a page" or "this is a chapter"; it is never arithmetic anyone depends on.
 */
export const CHARS_PER_WORD = 5;

export function wordsOf(markdownChars: number | undefined | null): number | null {
  if (typeof markdownChars !== 'number' || !Number.isFinite(markdownChars) || markdownChars <= 0) {
    return null;
  }
  return Math.max(1, Math.round(markdownChars / CHARS_PER_WORD));
}

/**
 * The friendly sentence's key, per error code.
 *
 * The seven codes collapse into five sentences because two pairs say the same thing to the
 * person reading them: `needs_ocr` and `ocr_failed` are both "the pages are pictures", and
 * `tool_missing` and `io` are both "not right now" — an operator's missing binary is not a
 * teacher's problem and naming it would only invite her to try to fix it. An unrecognised code
 * (a server newer than this bundle) takes the same "try again in a minute", which is true of
 * anything we cannot name.
 */
export function reasonKeyOf(code: string | undefined | null): string {
  switch (code) {
    case 'encrypted':
      return `${I18N}.reason.encrypted`;
    case 'unsupported':
      return `${I18N}.reason.unsupported`;
    case 'malformed':
      return `${I18N}.reason.malformed`;
    case 'needs_ocr':
    case 'ocr_failed':
      return `${I18N}.reason.pictures`;
    default:
      return `${I18N}.reason.unavailable`;
  }
}

/** The hint beside "Ready": how we got the text. An unknown method gets no hint at all. */
export function methodKeyOf(method: string | undefined | null): string | null {
  switch (method) {
    case 'anydoc':
      return `${I18N}.method.anydoc`;
    case 'ocr':
      return `${I18N}.method.ocr`;
    case 'text':
      return `${I18N}.method.text`;
    default:
      return null;
  }
}

const OCR_ABLE = /\.(pdf|png|jpe?g|webp|gif|tiff?|bmp)$/i;

/**
 * Whether "Read with OCR" is worth offering.
 *
 * Only pages that *are* pictures can be OCRed, so the offer is limited to PDFs and images, and
 * only for the three codes OCR could actually answer: pages that are pictures (`needs_ocr`), an
 * OCR pass that came out empty (`ocr_failed` — a second try on a different render is sometimes
 * enough), and a file the document converter choked on (`malformed`, where rendering the pages
 * sidesteps whatever it choked on). Offering it on `encrypted` would be a lie: no renderer gets
 * past a password either.
 */
export function canReadWithOcr(file: SourceFileInfo): boolean {
  if (file.convertStatus !== SourceFileInfoConvertStatusEnum.ERROR) return false;
  if (!OCR_ABLE.test(file.fileName)) return false;
  const code = file.convertErrorCode;
  return code === 'needs_ocr' || code === 'ocr_failed' || code === 'malformed';
}

/** Poll while anything is mid-conversion — and only then; every other state is terminal. */
export function anyConverting(files: readonly SourceFileInfo[]): boolean {
  return files.some(
    (file) => !file.deleted && file.convertStatus === SourceFileInfoConvertStatusEnum.CONVERTING,
  );
}

/** One file's row, as the list renders it. */
export interface FileConvertView {
  readonly status: ConvertStatus;
  /** `'neutral' | 'primary' | 'success' | 'error'` — the badge modifier, never the meaning. */
  readonly tone: 'neutral' | 'primary' | 'success' | 'error';
  /** The pill's own words. `ready` carries `{ words }`; nothing else interpolates. */
  readonly labelKey: string;
  readonly words: number | null;
  readonly methodKey: string | null;
  readonly reasonKey: string | null;
  readonly canPreview: boolean;
  readonly canOcr: boolean;
  /** Typing the text is the way out of every failure, including the ones OCR cannot help. */
  readonly canTypeText: boolean;
}

export function viewOf(file: SourceFileInfo): FileConvertView {
  const status = file.convertStatus as ConvertStatus;
  const words = wordsOf(file.markdownChars);
  switch (status) {
    case 'converting':
      return {
        status,
        tone: 'primary',
        labelKey: `${I18N}.converting`,
        words: null,
        methodKey: null,
        reasonKey: null,
        canPreview: false,
        canOcr: false,
        canTypeText: false,
      };
    case 'ready':
      return {
        status,
        tone: 'success',
        labelKey: words === null ? `${I18N}.readyEmpty` : `${I18N}.ready`,
        words,
        methodKey: methodKeyOf(file.convertMethod),
        reasonKey: null,
        canPreview: true,
        canOcr: false,
        canTypeText: true,
      };
    case 'error':
      return {
        status,
        tone: 'error',
        labelKey: `${I18N}.failed`,
        words: null,
        methodKey: null,
        reasonKey: reasonKeyOf(file.convertErrorCode),
        canPreview: false,
        canOcr: canReadWithOcr(file),
        canTypeText: true,
      };
    default:
      return {
        status: 'pending',
        tone: 'neutral',
        labelKey: `${I18N}.pending`,
        words: null,
        methodKey: null,
        reasonKey: null,
        canPreview: false,
        canOcr: false,
        canTypeText: false,
      };
  }
}

/**
 * `POST …/retry-conversion`'s body.
 *
 * `{}` asks the server to convert again its own way; `{ markdown }` hands it the teacher's
 * words verbatim. Pre-serialized for the same reason every other lesson body is — see
 * `lessons.models.ts`'s header.
 */
export function retryConversionBody(markdown?: string): string {
  return markdown === undefined ? '{}' : JSON.stringify({ markdown });
}
