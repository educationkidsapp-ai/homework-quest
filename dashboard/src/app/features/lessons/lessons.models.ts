/**
 * The lesson pipeline's shapes, hand-written rather than imported from `../../api`.
 *
 * `AdminLessonController` and `AdminReportsController` return their bodies as a pre-serialized
 * `String` (`json.encodeShared(...)`) with `produces = application/json` — valid JSON on the
 * wire, but `OpenApiExportTest` records the schema as a bare `string` because that is the
 * method's Java return type. `pnpm gen:api` therefore types `listLessons`, `retry`,
 * `deleteFailed` and `AdminReportsApi.calendar` as `Observable<string>`, while the interceptor
 * pipeline still parses the response as JSON (`Accept: application/json` selects
 * `responseType: 'json'` regardless of the declared schema) — so the value that actually
 * arrives is the object below, not a string. This file names that object so the page does not
 * scatter `as unknown as …` casts with no shape to point at; the cast still happens once per
 * call site. Fixing the export is a `backend` package (typing the controller methods'
 * response), not a dashboard one. There is no generated `AdminLesson` model to import either,
 * for the same reason — the shapes below are hand-copied from `AdminApi.kt`.
 */

export type Curriculum = 'american' | 'british';
export type Subject = 'math' | 'english' | 'science';
export type LessonStatus =
  | 'draft'
  | 'uploading'
  | 'analyzing'
  | 'needs_review'
  | 'generating'
  | 'review'
  | 'published'
  | 'error'
  | 'paused';
export type PipelineStep =
  | 'upload'
  | 'analyze'
  | 'skills'
  | 'generate_L1'
  | 'generate_L2'
  | 'generate_L3'
  | 'generate_again'
  | 'panel';
export type StepStatus = 'pending' | 'running' | 'done' | 'error';

export interface LessonStepInfo {
  readonly step: PipelineStep;
  readonly status: StepStatus;
}

/** `AdminLesson` (`shared-api/.../AdminApi.kt`) — the fields this screen reads. */
export interface AdminLessonRow {
  readonly id: string;
  readonly course: { readonly curriculum: Curriculum; readonly grade: number };
  readonly subject: Subject;
  readonly date: string;
  readonly status: LessonStatus;
  readonly title?: string | null;
  readonly steps?: readonly LessonStepInfo[];
  readonly currentStep?: PipelineStep | null;
  readonly schoolId?: string | null;
  readonly schoolName?: string | null;
}

/** `JobRef` — what `retry` answers with: the status the pipeline is in right after the call. */
export interface JobRef {
  readonly jobId: string;
  readonly status: LessonStatus;
}

/** `Course.all`: two curricula, three grades each — the whole space, fixed by the contract. */
export const CURRICULA: readonly Curriculum[] = ['american', 'british'];
export const GRADES: readonly number[] = [1, 2, 3];

/** `!LessonStatus.isTerminal` for exactly the three that are mid-pipeline. */
const RUNNING: ReadonlySet<LessonStatus> = new Set(['uploading', 'analyzing', 'generating']);
export function isRunningStatus(status: LessonStatus): boolean {
  return RUNNING.has(status);
}

/** The step a row's row failed at, for "Error at: …" — the current one, or the first errored. */
export function errorStepOf(lesson: AdminLessonRow): PipelineStep | null {
  if (lesson.currentStep) return lesson.currentStep;
  return lesson.steps?.find((step) => step.status === 'error')?.step ?? null;
}

export function asLessonRows(raw: unknown): readonly AdminLessonRow[] {
  return (raw as readonly AdminLessonRow[] | undefined) ?? [];
}

export function asJobRef(raw: unknown): JobRef {
  return raw as JobRef;
}

/** `deleteFailed`'s body is `{"deleted": n}`, built by hand rather than through the serializer. */
export function asDeletedCount(raw: unknown): number {
  const value = (raw as { deleted?: unknown } | undefined)?.deleted;
  return typeof value === 'number' ? value : 0;
}

// ---------------------------------------------------------------------------------------------
// The chooser's memory: `hq.course.<userId>`.
// ---------------------------------------------------------------------------------------------

export interface CourseSelection {
  readonly curriculum: Curriculum;
  readonly grade: number;
}

function storageKey(userId: string): string {
  return `hq.course.${userId}`;
}

export function readStoredCourse(userId: string): CourseSelection | null {
  try {
    const raw = localStorage.getItem(storageKey(userId));
    if (!raw) return null;
    const parsed: unknown = JSON.parse(raw);
    if (parsed && typeof parsed === 'object' && 'curriculum' in parsed && 'grade' in parsed) {
      const { curriculum, grade } = parsed as Record<string, unknown>;
      if ((curriculum === 'american' || curriculum === 'british') && typeof grade === 'number') {
        return { curriculum, grade };
      }
    }
  } catch {
    // A hand-edited or half-written value is not worth a crash on boot.
  }
  return null;
}

export function writeStoredCourse(userId: string, selection: CourseSelection): void {
  try {
    localStorage.setItem(storageKey(userId), JSON.stringify(selection));
  } catch {
    // Private browsing / a full quota: the chooser just stops remembering, silently.
  }
}

// ---------------------------------------------------------------------------------------------
// The calendar: `GET /admin/calendar` (`CalendarResponse`) has no gap or school-day flag — it
// lists only the days a lesson already covers. The per-class `GET
// /teacher/classes/{id}/calendar` does carry `schoolDay`/`gap`, but needs a class id this
// screen's curriculum+grade chooser does not resolve to one of (a course can be several
// classes, one per subject). So the gap here is computed the same way `ClassCalendarDay`
// documents it: Sunday–Thursday, not after today, nothing published that day.
// ---------------------------------------------------------------------------------------------

export interface CalendarDay {
  readonly date: string;
  readonly math: boolean;
  readonly english: boolean;
}

export interface CalendarResponse {
  readonly course: { readonly curriculum: Curriculum; readonly grade: number };
  readonly year: number;
  readonly month: number;
  readonly days: readonly CalendarDay[];
}

export function asCalendar(raw: unknown): CalendarResponse {
  return raw as CalendarResponse;
}

/** Gulf week: Friday (5) and Saturday (6) are not school days. Matches `TeacherCalendarService`. */
export function isSchoolDay(date: Date): boolean {
  const day = date.getUTCDay();
  return day !== 5 && day !== 6;
}
