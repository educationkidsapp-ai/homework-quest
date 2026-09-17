/**
 * The lesson pipeline's shapes.
 *
 * P3.0b typed every response `AdminLessonController` and `AdminReportsController` return
 * (`AdminLesson`, `JobRef`, `CalendarResponse`, …) properly in `server/openapi.json`, so
 * `pnpm gen:api` now generates real models for them under `api/generated/model/` — the
 * `Observable<string>` + hand-cast shapes this file used to carry are gone. Screens read
 * `AdminLesson` and friends straight from `'../../api'`.
 *
 * What is **not** fixed yet: `POST /admin/lessons`' body is still exported as a bare
 * `string` schema (`AdminLessonController.createLesson` takes the pre-serialized JSON the
 * same way the read side used to answer it), so `CreateLessonRequest` and `LessonSource`
 * have no generated model to import. They are hand-copied from `AdminApi.kt` below, same as
 * before — fixing the request side is a `backend` package, not a dashboard one.
 */
import {
  type AdminLesson,
  AdminLessonStatusEnum,
  type JobRefStatusEnum,
  LessonStepInfoStatusEnum,
} from '../../api';

/**
 * Plain string-literal unions, not the generated enums.
 *
 * The generator gives every response shape carrying a "curriculum" or a "status" its own
 * enum type (`AdminLessonStatusEnum`, `JobRefStatusEnum`, `CourseCurriculumEnum`, …), all
 * with the same members but nominally distinct — TypeScript will not assign one into a field
 * typed by another without a cast. A plain union matching the wire values is assignable
 * *from* every one of those enums (they are all subtypes of it), so it is what lets a
 * `JobRef.status` and an `AdminLesson.status` sit in the same `LessonStatus`-typed variable.
 */
export type Curriculum = 'american' | 'british';
export type Subject = 'math' | 'english';
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

/** Where a lesson's content came from — no generated model; see the file header. */
export type LessonSource = 'pdf' | 'slides' | 'images' | 'manual';

/** `POST /admin/lessons`'s body (`CreateLessonRequest` in `AdminApi.kt`) — hand-typed; see the file header. */
export interface CreateLessonRequest {
  readonly curriculum: Curriculum;
  readonly grade: number;
  readonly subject: Subject;
  readonly date: string;
  readonly practiceLength?: number;
  readonly source?: LessonSource;
  readonly title?: string;
}

/** `CreateLessonRequest`'s body goes over the wire pre-serialized — see the file header. */
export function createLessonBody(request: CreateLessonRequest): string {
  return JSON.stringify(request);
}

/** `POST /admin/lessons/{id}/skills`'s body (`ConfirmedSkill` in `AdminApi.kt`) — same reason. */
export interface ConfirmedSkillRequest {
  readonly id?: string;
  readonly name: string;
  readonly subject: Subject;
  readonly method?: string;
}

export function confirmSkillsBody(skills: readonly ConfirmedSkillRequest[]): string {
  return JSON.stringify(skills);
}

/**
 * `JobRef.status` and `AdminLesson.status` are two separately generated string enums with
 * identical members (the same `LessonStatus` on the Kotlin side), but the generator gives
 * each response shape its own enum type, and TypeScript treats generated enums as nominal —
 * so a job's status does not type-check where a lesson's is expected without this cast.
 */
export function jobStatusAsLessonStatus(status: JobRefStatusEnum): AdminLessonStatusEnum {
  return status as string as AdminLessonStatusEnum;
}

/** `Course.all`: two curricula, three grades each — the whole space, fixed by the contract. */
export const CURRICULA: readonly Curriculum[] = ['american', 'british'];
export const GRADES: readonly number[] = [1, 2, 3];
export const SUBJECTS: readonly Subject[] = ['math', 'english'];

export function isCurriculum(value: unknown): value is Curriculum {
  return value === 'american' || value === 'british';
}

export function isSubject(value: unknown): value is Subject {
  return value === 'math' || value === 'english';
}

/** `!LessonStatus.isTerminal` for exactly the three that are mid-pipeline. */
const RUNNING: ReadonlySet<string> = new Set(['uploading', 'analyzing', 'generating']);
export function isRunningStatus(status: LessonStatus): boolean {
  return RUNNING.has(status);
}

/** The step a row's row failed at, for "Error at: …" — the current one, or the first errored. */
export function errorStepOf(lesson: AdminLesson): PipelineStep | null {
  if (lesson.currentStep) return lesson.currentStep;
  return lesson.steps.find((step) => step.status === LessonStepInfoStatusEnum.ERROR)?.step ?? null;
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
      if (isCurriculum(curriculum) && typeof grade === 'number') {
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
// The calendar gap: `GET /admin/calendar` (`CalendarResponse`, now generated) has no gap or
// school-day flag — it lists only the days a lesson already covers. The per-class `GET
// /teacher/classes/{id}/calendar` does carry `schoolDay`/`gap`, but needs a class id this
// screen's curriculum+grade chooser does not resolve to one of (a course can be several
// classes, one per subject). So the gap here is computed the same way `ClassCalendarDay`
// documents it: Sunday–Thursday, not after today, nothing published that day.
// ---------------------------------------------------------------------------------------------

/** Gulf week: Friday (5) and Saturday (6) are not school days. Matches `TeacherCalendarService`. */
export function isSchoolDay(date: Date): boolean {
  const day = date.getUTCDay();
  return day !== 5 && day !== 6;
}
