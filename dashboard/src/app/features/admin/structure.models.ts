import type {
  AcademySubject,
  AcademyTerm,
  GradingBand,
  StructureAcademyNode,
  StructureClassNode,
  StructureTreeView,
} from '../../api';
import { academyKeyOf } from '../../core/structure/academy-naming';

/**
 * The arithmetic behind the **School structure** tree (feature 36,
 * `docs/features/36-school-structure-builder.md`): which node is selected, which spots are empty,
 * and whether a scheme or a list of terms is worth sending.
 *
 * Pure functions, as everywhere else in the dashboard: the three things a mistake here would break
 * — the node the side panel edits, the "nobody teaches this" marks the Admin came to the screen
 * for, and the payload the server would refuse — are all testable without a DOM.
 *
 * The validation mirrors `GradingSchemes.validate` and the server's term check **as a courtesy,
 * not as the gate**: the server refuses either way, and its sentence is what the panel shows. The
 * point of doing it here is that the Save button is disabled with a reason instead of inviting a
 * round trip that can only end in a red band.
 */

export type NodeKind = 'academy' | 'grade' | 'class';

/** The node the side panel is editing. Identified by value, so a reload keeps the selection. */
export interface SelectedNode {
  readonly kind: NodeKind;
  readonly academyKey: string;
  readonly grade?: number;
  readonly classId?: string;
}

/** One stable string per node, for the expanded set and for `track`. */
export function nodeKey(node: SelectedNode): string {
  if (node.kind === 'academy') return `a:${node.academyKey}`;
  if (node.kind === 'grade') return `g:${node.academyKey}:${String(node.grade)}`;
  return `c:${node.classId ?? ''}`;
}

export function sameNode(a: SelectedNode | null, b: SelectedNode | null): boolean {
  return a !== null && b !== null && nodeKey(a) === nodeKey(b);
}

export function academyOf(view: StructureTreeView, academyKey: string): StructureAcademyNode | null {
  const key = academyKeyOf(academyKey);
  return (view.academies ?? []).find((one) => academyKeyOf(one.academy?.academyKey) === key) ?? null;
}

/** A class and the academy it sits in — what the Move class dialog and the class panel both need. */
export function classOf(
  view: StructureTreeView,
  classId: string,
): { readonly academyKey: string; readonly node: StructureClassNode } | null {
  for (const academy of view.academies ?? []) {
    for (const grade of academy.grades ?? []) {
      const node = (grade.classes ?? []).find((one) => one.classId === classId);
      if (node) return { academyKey: academyKeyOf(academy.academy?.academyKey), node };
    }
  }
  return null;
}

/** Every class of the tree, newest-first order left as the server gave it. */
export function allClasses(view: StructureTreeView): readonly StructureClassNode[] {
  return (view.academies ?? []).flatMap((academy) =>
    (academy.grades ?? []).flatMap((grade) => grade.classes ?? []),
  );
}

/**
 * **The empty spots** — the three the spec names, counted so the page can say how many there are
 * before the Admin has expanded anything: an academy with no director, a grade with no class, and
 * a class with a subject nobody teaches.
 */
export interface EmptySpots {
  readonly academiesWithoutDirector: number;
  readonly gradesWithoutClass: number;
  readonly slotsWithoutTeacher: number;
}

export function emptySpotsOf(view: StructureTreeView): EmptySpots {
  let academiesWithoutDirector = 0;
  let gradesWithoutClass = 0;
  let slotsWithoutTeacher = 0;
  for (const academy of view.academies ?? []) {
    if (!academy.academy?.director) academiesWithoutDirector += 1;
    for (const grade of academy.grades ?? []) {
      if ((grade.classes ?? []).length === 0) gradesWithoutClass += 1;
      for (const section of grade.classes ?? [])
        slotsWithoutTeacher += (section.subjects ?? []).filter((slot) => !slot.teacher).length;
    }
  }
  return { academiesWithoutDirector, gradesWithoutClass, slotsWithoutTeacher };
}

export function hasEmptySpot(spots: EmptySpots): boolean {
  return spots.academiesWithoutDirector + spots.gradesWithoutClass + spots.slotsWithoutTeacher > 0;
}

// ---------------------------------------------------------------- the grading scheme editor

/** A band being edited: the numbers are strings while they are typed, as in every other form. */
export interface BandDraft {
  readonly band: string;
  readonly lower: string;
  readonly nameEn: string;
  readonly nameAr: string;
}

export function bandDraftsOf(bands: readonly GradingBand[] | undefined): readonly BandDraft[] {
  return (bands ?? []).map((one) => ({
    band: one.band ?? '',
    lower: String(one.lower ?? 0),
    nameEn: one.nameEn ?? '',
    nameAr: one.nameAr ?? '',
  }));
}

export function bandsOf(drafts: readonly BandDraft[]): readonly GradingBand[] {
  return drafts.map((one) => ({
    band: one.band.trim(),
    lower: Number(one.lower),
    nameEn: one.nameEn.trim(),
    nameAr: one.nameAr.trim(),
  }));
}

/**
 * The translation key of what is wrong with these bands, or `null` when they are worth sending.
 * The same five rules `GradingSchemes.validate` applies, in the order it applies them.
 */
export function schemeErrorOf(drafts: readonly BandDraft[]): string | null {
  if (drafts.length < 2) return 'admin.structure.scheme.errors.tooFew';
  const keys = new Set<string>();
  const lowers = new Set<number>();
  for (const draft of drafts) {
    if (draft.band.trim() === '') return 'admin.structure.scheme.errors.keyMissing';
    if (draft.nameEn.trim() === '' || draft.nameAr.trim() === '')
      return 'admin.structure.scheme.errors.nameMissing';
    const lower = Number(draft.lower);
    if (!Number.isInteger(lower) || lower < 0 || lower > 100)
      return 'admin.structure.scheme.errors.lowerRange';
    if (keys.has(draft.band.trim())) return 'admin.structure.scheme.errors.duplicateKey';
    if (lowers.has(lower)) return 'admin.structure.scheme.errors.duplicateLower';
    keys.add(draft.band.trim());
    lowers.add(lower);
  }
  if (!lowers.has(0)) return 'admin.structure.scheme.errors.noZero';
  return null;
}

// ---------------------------------------------------------------- the terms editor

export interface TermDraft {
  readonly id?: string;
  readonly schoolYear: string;
  readonly nameEn: string;
  readonly nameAr: string;
  readonly startsOn: string;
  readonly endsOn: string;
}

export function termDraftsOf(terms: readonly AcademyTerm[] | undefined): readonly TermDraft[] {
  return (terms ?? []).map((one) => ({
    id: one.id,
    schoolYear: one.schoolYear ?? '',
    nameEn: one.nameEn ?? '',
    nameAr: one.nameAr ?? '',
    startsOn: one.startsOn ?? '',
    endsOn: one.endsOn ?? '',
  }));
}

export function termsOf(drafts: readonly TermDraft[]): readonly AcademyTerm[] {
  return drafts.map((one) => ({
    ...(one.id === undefined ? {} : { id: one.id }),
    schoolYear: one.schoolYear.trim(),
    nameEn: one.nameEn.trim(),
    nameAr: one.nameAr.trim(),
    startsOn: one.startsOn,
    endsOn: one.endsOn,
  }));
}

/** What is wrong with these terms, or `null`: a field missing, a backwards term, or an overlap. */
export function termsErrorOf(drafts: readonly TermDraft[]): string | null {
  for (const draft of drafts) {
    if (
      draft.schoolYear.trim() === '' ||
      draft.nameEn.trim() === '' ||
      draft.nameAr.trim() === '' ||
      draft.startsOn === '' ||
      draft.endsOn === ''
    )
      return 'admin.structure.terms.errors.incomplete';
    if (draft.startsOn > draft.endsOn) return 'admin.structure.terms.errors.backwards';
  }
  // Two terms of one school year may not share a day; two years' terms never meet.
  for (let i = 0; i < drafts.length; i += 1)
    for (let j = i + 1; j < drafts.length; j += 1) {
      const a = drafts[i];
      const b = drafts[j];
      if (!a || !b || a.schoolYear.trim() !== b.schoolYear.trim()) continue;
      if (a.startsOn <= b.endsOn && b.startsOn <= a.endsOn) return 'admin.structure.terms.errors.overlap';
    }
  return null;
}

// ---------------------------------------------------------------- the subjects editor

export interface SubjectDraft {
  readonly subject: string;
  readonly nameEn: string;
  readonly nameAr: string;
  readonly ministry: boolean;
}

export function subjectDraftsOf(subjects: readonly AcademySubject[] | undefined): readonly SubjectDraft[] {
  return (subjects ?? []).map((one) => ({
    subject: one.subject ?? '',
    nameEn: one.nameEn ?? '',
    nameAr: one.nameAr ?? '',
    ministry: one.ministry === true,
  }));
}

export function subjectsOf(drafts: readonly SubjectDraft[]): readonly AcademySubject[] {
  return drafts.map((one) => ({
    subject: one.subject,
    nameEn: one.nameEn.trim(),
    nameAr: one.nameAr.trim(),
    ministry: one.ministry,
  }));
}

/**
 * A subject the academy offers needs a name in both languages. **The six keys are fixed** — the
 * contract closes the list (the planner's note on feature 36), so this editor renames and marks
 * them and never adds one.
 */
export function subjectsErrorOf(drafts: readonly SubjectDraft[]): string | null {
  const bad = drafts.some((one) => one.nameEn.trim() === '' || one.nameAr.trim() === '');
  return bad ? 'admin.structure.subjects.errors.nameMissing' : null;
}
