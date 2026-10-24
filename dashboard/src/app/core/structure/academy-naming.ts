import type { GradeNaming, GradingScheme } from '../../api';

/**
 * Feature 36's two naming questions, as pure functions: **what is this grade called** in an
 * academy, and **what word does this academy's grading scheme give a band**.
 *
 * Both answer `null` rather than a word of their own when the academy has said nothing. That is
 * the whole fallback rule of the feature: an academy with no naming reads exactly the strings the
 * dashboard showed before it existed (`admin.classes.gradeOption`, `results.band.*`), so a school
 * that never opens the School structure screen sees no change at all.
 *
 * `grade` is the stored number and never moves. The label is the *word* beside it: a British
 * academy with the word "Year" and an offset of 1 calls stored grade 1 "Year 2", which is exactly
 * `AcademyService.labelEn` on the server (`word + " " + (grade + offset)`). Keeping the arithmetic
 * in one line in both places is cheaper than a second endpoint for a string.
 */

/** An academy's grade naming, as `GET /admin/structure/academies` answers it. */
export type Naming = GradeNaming;

/** Both grade-naming styles the contract allows. `style` is descriptive — the word does the work. */
export const GRADE_STYLES = ['YEAR', 'GRADE'] as const;
export type GradeStyle = (typeof GRADE_STYLES)[number];

/** The four curriculum types an academy may declare. */
export const CURRICULUM_TYPES = ['BRITISH', 'AMERICAN', 'MINISTRY', 'OTHER'] as const;
export type CurriculumType = (typeof CURRICULUM_TYPES)[number];

/** The two kinds of grading scheme: letter bands (A*–U) or numeric ones. */
export const SCHEME_KINDS = ['LETTER', 'NUMERIC'] as const;
export type SchemeKind = (typeof SCHEME_KINDS)[number];

/** Academy keys are stored lower case; a label looked up by the wrong case is a silent fallback. */
export function academyKeyOf(key: string | null | undefined): string {
  return (key ?? '').trim().toLowerCase();
}

/** Is this one of the languages whose Arabic names the bundle uses? */
function isArabic(lang: string | null | undefined): boolean {
  return (lang ?? '').startsWith('ar');
}

/**
 * "Year 2" / "السنة 2" for a naming, or `null` when the academy has none — the caller then
 * translates `Grade {{grade}}` as it always did.
 */
export function gradeLabelOf(
  grade: number | null | undefined,
  naming: Naming | null | undefined,
  lang: string,
): string | null {
  if (grade === null || grade === undefined || !naming) return null;
  const word = (isArabic(lang) ? naming.wordAr : naming.wordEn)?.trim();
  if (!word) return null;
  return `${word} ${grade + (naming.offset ?? 0)}`;
}

/**
 * The word an academy's grading scheme gives a band key, or `null`.
 *
 * `bandLabel` on a DTO carries the **band key of the academy's scheme**, not a word — the server
 * keeps the four legacy `band` keys beside it so existing screens are untouched (D: additive). So
 * the lookup is here, against the `scheme` the same response carries, and a scheme that is not
 * the academy's own (`custom: false`, which is today's four bands word for word) is left alone:
 * `results.band.*` already has those four in both languages.
 */
export function bandLabelOf(
  scheme: GradingScheme | null | undefined,
  bandKey: string | null | undefined,
  lang: string,
): string | null {
  if (!scheme || scheme.custom !== true || !bandKey) return null;
  const band = (scheme.bands ?? []).find((one) => one.band === bandKey);
  if (!band) return null;
  const name = (isArabic(lang) ? band.nameAr : band.nameEn)?.trim();
  return name ? name : null;
}
