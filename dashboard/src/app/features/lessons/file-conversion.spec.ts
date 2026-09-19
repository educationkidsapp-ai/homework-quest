import { describe, expect, it } from 'vitest';
import en from '../../../assets/i18n/en.json';
import type { SourceFileInfo } from '../../api';
import {
  type ConvertErrorCode,
  anyConverting,
  canReadWithOcr,
  methodKeyOf,
  reasonKeyOf,
  retryConversionBody,
  viewOf,
  wordsOf,
} from './file-conversion';

/**
 * A contract-shaped `SourceFileInfo`, overridden with plain wire values.
 *
 * `Record<string, unknown>` rather than `Partial<SourceFileInfo>`: the generator gives
 * `convertStatus` a nominal string enum, and a test that writes `'ready'` — which is exactly
 * what the wire carries — would not type-check against it. The cast at the end is the one place
 * that is admitted, instead of an enum import in every case.
 */
function file(over: Record<string, unknown> = {}): SourceFileInfo {
  return {
    id: 'f-1',
    fileName: 'lesson.pdf',
    fileHash: 'h',
    pageCount: 3,
    cacheHit: false,
    deleted: false,
    convertStatus: 'pending',
    ...over,
  } as SourceFileInfo;
}

/** The bundle itself, so a key that maps to nothing fails here rather than on a screenshot. */
function sentence(key: string): string {
  const value = key.split('.').reduce<unknown>((node, part) => (node as Record<string, unknown>)[part], en);
  expect(typeof value, `${key} is missing from en.json`).toBe('string');
  return value as string;
}

describe('wordsOf', () => {
  it('turns a character count into words a teacher can picture', () => {
    expect(wordsOf(6200)).toBe(1240);
    expect(wordsOf(5)).toBe(1);
  });

  it('has nothing to say about a missing, zero or nonsense count', () => {
    expect(wordsOf(undefined)).toBeNull();
    expect(wordsOf(0)).toBeNull();
    expect(wordsOf(-10)).toBeNull();
    expect(wordsOf(Number.NaN)).toBeNull();
  });

  it('never rounds a file with text in it down to nothing', () => {
    expect(wordsOf(2)).toBe(1);
  });
});

describe('reasonKeyOf', () => {
  const EXPECTED: readonly (readonly [ConvertErrorCode, string])[] = [
    ['encrypted', 'This PDF is password-protected. Remove the password and upload again, or type the text.'],
    [
      'unsupported',
      "This file type can't be read. Upload a PDF, PowerPoint, Word, Excel, CSV or an image.",
    ],
    ['malformed', 'This file seems damaged. Try exporting it again.'],
    ['needs_ocr', 'The pages are pictures, not text. Try reading with OCR, or type the text.'],
    ['ocr_failed', 'The pages are pictures, not text. Try reading with OCR, or type the text.'],
    ['tool_missing', 'Reading is unavailable right now. Try again in a minute.'],
    ['io', 'Reading is unavailable right now. Try again in a minute.'],
  ];

  it.each(EXPECTED)('says what to do about %s', (code, expected) => {
    expect(sentence(reasonKeyOf(code))).toBe(expected);
  });

  it('answers a code from a newer server with the one sentence that is still true', () => {
    expect(sentence(reasonKeyOf('quantum_entanglement'))).toBe(
      'Reading is unavailable right now. Try again in a minute.',
    );
    expect(sentence(reasonKeyOf(undefined))).toBe('Reading is unavailable right now. Try again in a minute.');
  });

  it('never names a binary, an exit code or an environment variable', () => {
    for (const [code] of EXPECTED) {
      expect(sentence(reasonKeyOf(code))).not.toMatch(/anydoc|tesseract|exit|env|null/i);
    }
  });
});

describe('methodKeyOf', () => {
  it('says how the text was got', () => {
    expect(sentence(methodKeyOf('anydoc')!)).toBe('read as text');
    expect(sentence(methodKeyOf('ocr')!)).toBe('read with OCR');
    expect(sentence(methodKeyOf('text')!)).toBe('typed in');
  });

  it('stays quiet about a method it does not know', () => {
    expect(methodKeyOf('telepathy')).toBeNull();
    expect(methodKeyOf(undefined)).toBeNull();
  });
});

describe('canReadWithOcr', () => {
  it('offers OCR on pages that turned out to be pictures', () => {
    expect(canReadWithOcr(file({ convertStatus: 'error', convertErrorCode: 'needs_ocr' }))).toBe(true);
    expect(canReadWithOcr(file({ convertStatus: 'error', convertErrorCode: 'ocr_failed' }))).toBe(true);
    expect(canReadWithOcr(file({ convertStatus: 'error', convertErrorCode: 'malformed' }))).toBe(true);
    expect(
      canReadWithOcr(file({ fileName: 'page.PNG', convertStatus: 'error', convertErrorCode: 'needs_ocr' })),
    ).toBe(true);
  });

  it('does not offer it where no renderer could help', () => {
    expect(canReadWithOcr(file({ convertStatus: 'error', convertErrorCode: 'encrypted' }))).toBe(false);
    expect(canReadWithOcr(file({ convertStatus: 'error', convertErrorCode: 'tool_missing' }))).toBe(false);
    expect(
      canReadWithOcr(file({ fileName: 'notes.docx', convertStatus: 'error', convertErrorCode: 'needs_ocr' })),
    ).toBe(false);
    expect(canReadWithOcr(file({ convertStatus: 'ready' }))).toBe(false);
  });
});

describe('anyConverting', () => {
  it('is the only reason to keep asking', () => {
    expect(anyConverting([file({ convertStatus: 'converting' })])).toBe(true);
    expect(anyConverting([file({ convertStatus: 'ready' }), file({ id: 'f-2', convertStatus: 'converting' })])).toBe(
      true,
    );
  });

  it('stops on every terminal state, and on an empty list', () => {
    expect(anyConverting([])).toBe(false);
    expect(anyConverting([file({ convertStatus: 'ready' })])).toBe(false);
    expect(anyConverting([file({ convertStatus: 'error' })])).toBe(false);
    expect(anyConverting([file({ convertStatus: 'pending' })])).toBe(false);
  });

  it('ignores a file that has been deleted', () => {
    expect(anyConverting([file({ convertStatus: 'converting', deleted: true })])).toBe(false);
  });
});

describe('viewOf', () => {
  it('shows a file still being read, with nothing to do about it yet', () => {
    const view = viewOf(file({ convertStatus: 'converting' }));
    expect(sentence(view.labelKey)).toBe('Converting…');
    expect(view).toMatchObject({ canPreview: false, canOcr: false, canTypeText: false, tone: 'primary' });
  });

  it('counts the words of a file that is ready, and says how it was read', () => {
    const view = viewOf(file({ convertStatus: 'ready', convertMethod: 'anydoc', markdownChars: 6200 }));
    expect(view.words).toBe(1240);
    expect(sentence(view.labelKey)).toBe('Ready · {{words}} words');
    expect(sentence(view.methodKey!)).toBe('read as text');
    expect(view.canPreview).toBe(true);
  });

  it('drops the count rather than claiming nought words', () => {
    const view = viewOf(file({ convertStatus: 'ready', markdownChars: 0 }));
    expect(view.words).toBeNull();
    expect(sentence(view.labelKey)).toBe('Ready');
  });

  it('turns a failure into one sentence and the two ways out', () => {
    const view = viewOf(file({ convertStatus: 'error', convertErrorCode: 'encrypted' }));
    expect(sentence(view.labelKey)).toBe("Couldn't read this file");
    expect(sentence(view.reasonKey!)).toMatch(/password-protected/);
    expect(view).toMatchObject({ canOcr: false, canTypeText: true, tone: 'error' });
  });

  it('reads a pre-CR4 file, which has no conversion of its own, as not read yet', () => {
    const view = viewOf(file({ convertStatus: 'pending' }));
    expect(sentence(view.labelKey)).toBe('Not read yet');
    expect(view.tone).toBe('neutral');
  });
});

describe('retryConversionBody', () => {
  it('asks the server to try again its own way', () => {
    expect(retryConversionBody()).toBe('{}');
  });

  it("hands over the teacher's words, escaped as JSON rather than pasted into it", () => {
    expect(retryConversionBody('# Page 1\n"quoted"')).toBe('{"markdown":"# Page 1\\n\\"quoted\\""}');
  });
});
