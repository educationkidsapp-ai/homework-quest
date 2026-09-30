import type { BroadcastView, WeeklyPlanArchive } from '../../api';
import { csvOf } from '../download/csv';

/**
 * **The weekly-plan archive, as the screens want it** (MG2b, owner's item 4: "see all weekly plans").
 *
 * `GET /management/weekly-plans` and `GET /me/weekly-plans` answer the same `WeeklyPlanArchive`:
 * `weeks[]` newest first, each week's `items[]` all-grades first then by grade, each item a
 * `{plan, readBy?}`. Two screens read it — the manager's own screen with `readBy` and an export,
 * and the read-only tab a teacher and a coordinator get — so the mapping, the filter and the CSV
 * live here as pure functions instead of twice inside two components.
 *
 * Two deliberate choices:
 *
 * - **A row, not a `{plan, readBy}` pair.** Every field either screen draws is on one flat object,
 *   so the templates ask nothing about the envelope and `grade: null` means "all grades of the
 *   department" in exactly one place.
 * - **The filter is client-side.** The manager's route takes `grade`, and the screen does send it;
 *   but her date range is already the window the request was made for, and a grade she flips
 *   between should not be a round trip through a skeleton. A teacher's route takes no `grade` at
 *   all, so hers could only ever be filtered here.
 */
export interface PlanRow {
  readonly id: string;
  /** The Sunday of the week the plan is for. */
  readonly weekStart: string;
  /**
   * The grade the plan is for. `null` only on a row written before MH1 made `grade` required — the
   * screens still have to draw one, and they label it the department's rather than dropping it.
   */
  readonly grade: number | null;
  /** MH1: a plan **is** an image. The id `/media/attachments/{id}` wants, and the file's own name. */
  readonly attachmentId: string | null;
  readonly attachmentName: string;
  /** How many people opened it — the manager's archive only; `null` on a reader's own. */
  readonly readBy: number | null;
  /** The row itself, for the author line and anything else a screen wants off the envelope. */
  readonly plan: BroadcastView;
}

export interface PlanWeek {
  readonly weekStart: string;
  readonly rows: readonly PlanRow[];
}

/** `'any'` is no filter at all. There is no all-grades plan since MH1 — every plan names a grade. */
export type GradeFilter = 'any' | number;

export interface PlanFilter {
  readonly grade: GradeFilter;
  /** `YYYY-MM-DD`, inclusive; `''` is unbounded. ISO dates compare as strings. */
  readonly from: string;
  readonly to: string;
}

export const NO_PLAN_FILTER: PlanFilter = { grade: 'any', from: '', to: '' };

/** The archive flattened into weeks of rows, dropping anything without an id to draw. */
export function planWeeks(archive: WeeklyPlanArchive | null | undefined): readonly PlanWeek[] {
  return (archive?.weeks ?? [])
    .map((week) => ({
      weekStart: week.weekStart ?? '',
      rows: (week.items ?? []).flatMap<PlanRow>((item) => {
        const plan = item.plan;
        if (plan === undefined || (plan.id ?? '') === '') return [];
        return [
          {
            id: plan.id ?? '',
            weekStart: week.weekStart ?? plan.weekStart ?? '',
            grade: plan.grade ?? null,
            attachmentId: plan.attachment?.id ?? null,
            attachmentName: plan.attachment?.name ?? '',
            readBy: item.readBy ?? null,
            plan,
          },
        ];
      }),
    }))
    .filter((week) => week.rows.length > 0);
}

/** The filter, applied to the mapped weeks; a week left with no row disappears with them. */
export function filterWeeks(weeks: readonly PlanWeek[], filter: PlanFilter): readonly PlanWeek[] {
  return weeks
    .filter((week) => inRange(week.weekStart, filter))
    .map((week) => ({ weekStart: week.weekStart, rows: week.rows.filter((row) => matches(row, filter)) }))
    .filter((week) => week.rows.length > 0);
}

function inRange(weekStart: string, filter: PlanFilter): boolean {
  if (filter.from !== '' && weekStart < filter.from) return false;
  return !(filter.to !== '' && weekStart > filter.to);
}

function matches(row: PlanRow, filter: PlanFilter): boolean {
  return filter.grade === 'any' || row.grade === filter.grade;
}

/** Every row of every week, newest week first — the order the screen lists them in. */
export function planRows(weeks: readonly PlanWeek[]): readonly PlanRow[] {
  return weeks.flatMap((week) => week.rows);
}

/** The four headers the export needs, already translated by the caller. */
export interface PlanCsvLabels {
  readonly image: string;
  readonly week: string;
  readonly grade: string;
  readonly readBy: string;
}

/**
 * The list she is looking at, as a file — week, grade, the image's file name, `readBy`.
 *
 * The file name rather than a title: MH1 took the title off a plan, and the name of the picture is
 * the only words a row has left. The rows on screen and never a second read, which is
 * `core/download/csv.ts`'s own rule: the three `weekly-plans` routes publish no `.csv`, and an
 * export that re-asked could disagree with the filter she set.
 */
export function planCsv(weeks: readonly PlanWeek[], labels: PlanCsvLabels): string {
  return csvOf(
    [labels.week, labels.grade, labels.image, labels.readBy],
    planRows(weeks).map((row) => [
      row.weekStart,
      row.grade === null ? '' : String(row.grade),
      row.attachmentName,
      row.readBy === null ? '' : String(row.readBy),
    ]),
  );
}

/**
 * The two bodies a row may carry, **the reader's own language first**, empty ones dropped.
 *
 * An Arabic reader who has to scroll past the English to find hers is reading somebody else's copy
 * of the same note. Shared by the feed and by the two archive screens, which draw the same row.
 */
export function readerBodies(
  row: BroadcastView,
  lang: string,
): readonly { readonly text: string; readonly dir: 'ltr' | 'rtl' }[] {
  const bodies = [
    { text: row.bodyEn ?? '', dir: 'ltr' as const },
    { text: row.bodyAr ?? '', dir: 'rtl' as const },
  ];
  if (lang === 'ar') bodies.reverse();
  return bodies.filter((body) => body.text !== '');
}
