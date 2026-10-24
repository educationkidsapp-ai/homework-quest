import { describe, expect, it } from 'vitest';
import { academyKeyOf, bandLabelOf, gradeLabelOf } from './academy-naming';

const BRITISH = { style: 'YEAR', offset: 1, wordEn: 'Year', wordAr: 'السنة' };
const AMERICAN = { style: 'GRADE', offset: 0, wordEn: 'Grade', wordAr: 'الصف' };

const LETTERS = {
  academyKey: 'british',
  kind: 'LETTER',
  custom: true,
  bands: [
    { band: 'U', lower: 0, nameEn: 'Ungraded', nameAr: 'غير مصنّف' },
    { band: 'A*', lower: 90, nameEn: 'A*', nameAr: 'ممتاز مرتفع' },
  ],
};

describe('gradeLabelOf', () => {
  /** The acceptance criterion in the spec, in one line: the same grade, two academies. */
  it('calls stored grade 1 "Year 2" in a British academy and "Grade 1" in an American one', () => {
    expect(gradeLabelOf(1, BRITISH, 'en')).toBe('Year 2');
    expect(gradeLabelOf(1, AMERICAN, 'en')).toBe('Grade 1');
  });

  it('uses the Arabic word in Arabic', () => {
    expect(gradeLabelOf(1, BRITISH, 'ar')).toBe('السنة 2');
    expect(gradeLabelOf(3, AMERICAN, 'ar-SA')).toBe('الصف 3');
  });

  /** No naming, no word, no grade: the caller's own translation is what shows. */
  it('answers null when there is nothing to name it with', () => {
    expect(gradeLabelOf(1, null, 'en')).toBeNull();
    expect(gradeLabelOf(1, { ...BRITISH, wordEn: '  ' }, 'en')).toBeNull();
    expect(gradeLabelOf(null, BRITISH, 'en')).toBeNull();
    expect(gradeLabelOf(undefined, BRITISH, 'en')).toBeNull();
  });

  /** An offset of 0 is still an offset: the absent one must not read as "no naming". */
  it('reads a missing offset as zero', () => {
    expect(gradeLabelOf(2, { wordEn: 'Grade', wordAr: 'الصف' }, 'en')).toBe('Grade 2');
  });
});

describe('bandLabelOf', () => {
  it('gives the academy word for a band key, in the active language', () => {
    expect(bandLabelOf(LETTERS, 'A*', 'en')).toBe('A*');
    expect(bandLabelOf(LETTERS, 'A*', 'ar')).toBe('ممتاز مرتفع');
  });

  /** The default scheme is today's four bands, which `results.band.*` already has in both. */
  it('leaves the default scheme to the bundle', () => {
    expect(bandLabelOf({ ...LETTERS, custom: false }, 'A*', 'en')).toBeNull();
    expect(bandLabelOf(null, 'secure', 'en')).toBeNull();
    expect(bandLabelOf(LETTERS, null, 'en')).toBeNull();
  });

  it('falls back when the scheme does not name that band', () => {
    expect(bandLabelOf(LETTERS, 'secure', 'en')).toBeNull();
  });
});

describe('academyKeyOf', () => {
  it('normalises the key, because a label looked up by the wrong case is a silent fallback', () => {
    expect(academyKeyOf(' British ')).toBe('british');
    expect(academyKeyOf(undefined)).toBe('');
  });
});
