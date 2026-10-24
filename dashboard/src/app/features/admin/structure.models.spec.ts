import { describe, expect, it } from 'vitest';
import {
  type BandDraft,
  type TermDraft,
  academyOf,
  allClasses,
  bandDraftsOf,
  bandsOf,
  classOf,
  emptySpotsOf,
  hasEmptySpot,
  nodeKey,
  sameNode,
  schemeErrorOf,
  subjectsErrorOf,
  termsErrorOf,
} from './structure.models';

const TREE = {
  academies: [
    {
      academy: {
        academyKey: 'british',
        nameEn: 'British',
        director: { userId: 'u-mona', name: 'Mona Saeed' },
      },
      grades: [
        {
          grade: 1,
          labelEn: 'Year 2',
          classes: [
            {
              classId: 'c-1a',
              name: '1A',
              grade: 1,
              children: 20,
              subjects: [
                { subject: 'math', teacher: { userId: 'u-sara', name: 'Sara Ali' } },
                { subject: 'english' },
              ],
            },
          ],
        },
        { grade: 2, labelEn: 'Year 3', classes: [] },
      ],
    },
    {
      academy: { academyKey: 'american', nameEn: 'American' },
      grades: [{ grade: 1, labelEn: 'Grade 1', classes: [{ classId: 'c-2a', name: '2A', subjects: [] }] }],
    },
  ],
};

describe('the structure tree', () => {
  it('names every node once, and tells two nodes apart', () => {
    expect(nodeKey({ kind: 'academy', academyKey: 'british' })).toBe('a:british');
    expect(nodeKey({ kind: 'grade', academyKey: 'british', grade: 2 })).toBe('g:british:2');
    expect(nodeKey({ kind: 'class', academyKey: 'british', classId: 'c-1a' })).toBe('c:c-1a');
    expect(
      sameNode({ kind: 'academy', academyKey: 'british' }, { kind: 'academy', academyKey: 'british' }),
    ).toBe(true);
    expect(sameNode({ kind: 'academy', academyKey: 'british' }, null)).toBe(false);
  });

  it('finds an academy whatever the case of its key, and a class wherever it sits', () => {
    expect(academyOf(TREE, 'British')?.academy?.nameEn).toBe('British');
    expect(academyOf(TREE, 'ministry')).toBeNull();
    expect(classOf(TREE, 'c-2a')?.academyKey).toBe('american');
    expect(classOf(TREE, 'nope')).toBeNull();
    expect(allClasses(TREE).map((one) => one.classId)).toEqual(['c-1a', 'c-2a']);
  });

  /**
   * The three empty spots the spec names, counted from one tree: the American academy has no
   * director, British Year 3 has no class, and 1A's English is taught by nobody.
   */
  it('counts the empty spots', () => {
    const spots = emptySpotsOf(TREE);

    expect(spots).toEqual({
      academiesWithoutDirector: 1,
      gradesWithoutClass: 1,
      slotsWithoutTeacher: 1,
    });
    expect(hasEmptySpot(spots)).toBe(true);
    expect(hasEmptySpot({ academiesWithoutDirector: 0, gradesWithoutClass: 0, slotsWithoutTeacher: 0 })).toBe(
      false,
    );
  });

  it('counts nothing on an empty tree', () => {
    expect(emptySpotsOf({})).toEqual({
      academiesWithoutDirector: 0,
      gradesWithoutClass: 0,
      slotsWithoutTeacher: 0,
    });
  });
});

describe('the grading scheme editor', () => {
  const band = (over: Partial<BandDraft> = {}): BandDraft => ({
    band: 'A',
    lower: '0',
    nameEn: 'Top',
    nameAr: 'الأعلى',
    ...over,
  });

  it('accepts a scheme the server would accept', () => {
    expect(schemeErrorOf([band(), band({ band: 'B', lower: '50' })])).toBeNull();
  });

  /** The same five rules `GradingSchemes.validate` applies, so the Save is disabled with a reason. */
  it('names what is wrong, rule by rule', () => {
    expect(schemeErrorOf([band()])).toBe('admin.structure.scheme.errors.tooFew');
    expect(schemeErrorOf([band({ band: ' ' }), band({ band: 'B', lower: '50' })])).toBe(
      'admin.structure.scheme.errors.keyMissing',
    );
    expect(schemeErrorOf([band({ nameAr: '' }), band({ band: 'B', lower: '50' })])).toBe(
      'admin.structure.scheme.errors.nameMissing',
    );
    expect(schemeErrorOf([band({ lower: '101' }), band({ band: 'B', lower: '50' })])).toBe(
      'admin.structure.scheme.errors.lowerRange',
    );
    expect(schemeErrorOf([band(), band({ lower: '50' })])).toBe('admin.structure.scheme.errors.duplicateKey');
    expect(schemeErrorOf([band(), band({ band: 'B' })])).toBe('admin.structure.scheme.errors.duplicateLower');
    expect(schemeErrorOf([band({ lower: '10' }), band({ band: 'B', lower: '50' })])).toBe(
      'admin.structure.scheme.errors.noZero',
    );
  });

  it('round-trips the bands it was given', () => {
    const drafts = bandDraftsOf([{ band: 'A', lower: 40, nameEn: ' Top ', nameAr: 'الأعلى' }]);

    expect(drafts).toEqual([{ band: 'A', lower: '40', nameEn: ' Top ', nameAr: 'الأعلى' }]);
    expect(bandsOf(drafts)).toEqual([{ band: 'A', lower: 40, nameEn: 'Top', nameAr: 'الأعلى' }]);
  });
});

describe('the terms editor', () => {
  const term = (over: Partial<TermDraft> = {}): TermDraft => ({
    schoolYear: '2026/2027',
    nameEn: 'Term 1',
    nameAr: 'الفصل الأول',
    startsOn: '2026-09-01',
    endsOn: '2026-12-15',
    ...over,
  });

  it('accepts two terms of one year that do not meet', () => {
    expect(
      termsErrorOf([term(), term({ nameEn: 'Term 2', startsOn: '2027-01-05', endsOn: '2027-03-30' })]),
    ).toBeNull();
  });

  it('refuses an incomplete term, a backwards one, and an overlap inside one year', () => {
    expect(termsErrorOf([term({ nameAr: '' })])).toBe('admin.structure.terms.errors.incomplete');
    expect(termsErrorOf([term({ endsOn: '2026-08-01' })])).toBe('admin.structure.terms.errors.backwards');
    expect(
      termsErrorOf([term(), term({ nameEn: 'Term 2', startsOn: '2026-12-01', endsOn: '2027-03-01' })]),
    ).toBe('admin.structure.terms.errors.overlap');
  });

  /** Two school years' terms never meet, however their dates read. */
  it('lets two years share a day', () => {
    expect(
      termsErrorOf([term(), term({ schoolYear: '2025/2026', startsOn: '2026-09-01', endsOn: '2026-12-15' })]),
    ).toBeNull();
  });
});

describe('the subjects editor', () => {
  it('needs a name in both languages for every subject the academy offers', () => {
    expect(
      subjectsErrorOf([{ subject: 'math', nameEn: 'Math', nameAr: 'رياضيات', ministry: false }]),
    ).toBeNull();
    expect(subjectsErrorOf([{ subject: 'math', nameEn: 'Math', nameAr: ' ', ministry: true }])).toBe(
      'admin.structure.subjects.errors.nameMissing',
    );
  });
});
