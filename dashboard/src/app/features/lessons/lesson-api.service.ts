import { Injectable, computed, inject } from '@angular/core';
import { Observable, switchMap, throwError } from 'rxjs';
import {
  type AdminLesson,
  type AdminPlay,
  type DeleteFailed200Response,
  type JobRef,
  type LessonImage,
  type LessonStatusView,
  type ParentPanel,
  type Play,
  type PublishedCopy,
  type Stop,
  AdminLessonsApi,
  CoordinatorApi,
  TeacherLessonsApi,
} from '../../api';
import { AuthService } from '../../core/auth/auth.service';
import { silentErrors } from '../../core/http/error.interceptor';
import { type RetryMethod, retryConversionBody } from './file-conversion';
import { type NewLessonRequest, createLessonBody } from './lessons.models';

/** E5's `level` path segment. Level 1 is the teacher's own to write, so it is not one of them. */
export type AskLevel = '2' | '3' | 'again';

/** What both list endpoints narrow by. `schoolId` is Admin-only — a teacher has exactly one. */
export interface LessonListFilters {
  readonly curriculum?: string;
  readonly grade?: number;
  readonly subject?: string;
  readonly from?: string;
  readonly to?: string;
  readonly classId?: string;
  readonly schoolId?: string;
  /** R5: `GET /coordinator/lessons` narrows by status; the other two lists do not. */
  readonly status?: string;
}

/**
 * One lesson-pipeline surface over the two the server publishes.
 *
 * `/admin/**` and `/teacher/**` carry the same lesson pipeline behind different authorization:
 * the admin routes are tenant-wide, the teacher ones are scoped to her own lessons and answer
 * 404 (never 403) for anyone else's. Until N2.4 the lesson page called `/admin/**` for both
 * roles, which worked only because a teacher's own lesson happens to pass the admin check on
 * the same school — the isolation tests would have caught it the day the server tightened.
 * Everything here picks by `AuthService.role()` so the screens never have to.
 *
 * N2.4b closed the last two gaps this file used to carry flags for: `POST
 * /teacher/lessons/{id}/plays` and `DELETE /teacher/lessons/{id}/files` now exist, so "Create
 * this level" and "Remove all files" are simply rendered for a teacher and the `supports*`
 * pair they hid behind is gone.
 *
 * What still has no teacher alias, and what this says rather than hides:
 *   - {@link deleteFailed} ("Delete all failed") — `/admin/lessons/failed` only; the list
 *     renders it behind {@link supportsDeleteFailed}.
 *   - {@link publishToClasses} and {@link moveDate} are the mirror case: teacher-only, and
 *     {@link supportsMoveDate} is what keeps the Admin screen from offering a date control
 *     no endpoint would accept.
 *
 * Bodies are strings on purpose. Every lesson-content endpoint declares `"type": "string"` in
 * `server/openapi.json` (see `ui/phone-preview/stop.model.ts`'s header), so the generated
 * client takes the kotlinx-serialization JSON verbatim; `lessons.models.ts` builds it.
 */
@Injectable({ providedIn: 'root' })
export class LessonApiService {
  private readonly admin = inject(AdminLessonsApi);
  private readonly teacher = inject(TeacherLessonsApi);
  private readonly coordinator = inject(CoordinatorApi);
  private readonly auth = inject(AuthService);

  /**
   * MANAGERIAL reads lessons through the Admin routes too: `/teacher/**` is scoped to the
   * caller's own assignments, and a manager has none — every one of her reads would 404.
   *
   * Written as two exclusions rather than as a list of the roles that *are* admin-side, so the
   * answer before `/me` has landed stays what it has always been — the Admin routes — and a fifth
   * dashboard role added later does not silently start reading `/teacher/**`.
   */
  readonly isAdmin = computed(() => {
    const role = this.auth.role();
    return role !== 'TEACHER' && role !== 'COORDINATOR';
  });

  /**
   * R5: a coordinator reads the same two shapes through her own namespace.
   *
   * She has exactly two lesson routes — `GET /coordinator/lessons` and
   * `GET /coordinator/lessons/{id}` — and neither an Admin's nor a teacher's alias would answer
   * for her: `/admin/**` refuses her role outright and `/teacher/**` is scoped to the caller's
   * own assignments, which she has none of. Every other method here stays unreachable, and it
   * stays unreachable by *permission* rather than by an `if`: she holds no `lesson.write`,
   * `lesson.publish`, `play.write` or `stop.write`, so the controls that would call them are
   * never rendered (`lesson.page.html`, `*hqCan`).
   */
  readonly isCoordinator = computed(() => this.auth.role() === 'COORDINATOR');
  /** `DELETE /admin/lessons/failed` has no teacher alias — it is a tenant-wide sweep. */
  readonly supportsDeleteFailed = computed(() => this.isAdmin());

  // ---- the list and the create --------------------------------------------------------------

  list(filters: LessonListFilters): Observable<readonly AdminLesson[]> {
    const { curriculum, grade, subject, from, to, classId, schoolId, status } = filters;
    if (this.isCoordinator()) return this.coordinator.coordinatorLessons(classId, status, from, to);
    return this.isAdmin()
      ? this.admin.listLessons(curriculum, grade, subject, from, to, schoolId, classId)
      : this.teacher.listTeacherLessons(curriculum, grade, subject, from, to, classId);
  }

  /**
   * A teacher's lesson belongs to a **section**, an Admin's to a **course**.
   *
   * `POST /teacher/lessons` takes a `classId` and nothing else would do: two Grade 1 British
   * Math sections are two different lessons with two different result sets. `POST
   * /admin/lessons` has no class at all — it is the tenant-wide authoring route — so the two
   * branches take the two halves of {@link NewLessonRequest} and the caller supplies whichever
   * its role needs. A teacher without a `classId` is a bug in the screen, not a request to
   * fall back to the Admin route: that route would create a lesson no section owns.
   */
  create(request: NewLessonRequest): Observable<AdminLesson> {
    if (!this.isAdmin()) {
      if (!request.classId) return this.unsupported('create without a class');
      return this.teacher.createTeacherLesson({
        classId: request.classId,
        subject: request.subject,
        date: request.date,
        source: request.source,
        practiceLength: request.practiceLength,
        title: request.title,
        notes: request.notes,
      });
    }
    return this.admin.createLesson(
      createLessonBody({
        curriculum: request.curriculum!,
        grade: request.grade!,
        subject: request.subject,
        date: request.date,
        practiceLength: request.practiceLength,
        source: request.source === 'manual' ? 'manual' : undefined,
        title: request.title,
      }),
    );
  }

  deleteFailed(): Observable<DeleteFailed200Response> {
    if (!this.supportsDeleteFailed()) return this.unsupported('deleteFailed');
    return this.admin.deleteFailed();
  }

  // ---- the lesson ---------------------------------------------------------------------------

  getLesson(id: string): Observable<AdminLesson> {
    if (this.isCoordinator()) return this.coordinator.coordinatorLesson(id);
    return this.isAdmin() ? this.admin.getLesson(id) : this.teacher.teacherLesson(id);
  }

  deleteLesson(id: string): Observable<unknown> {
    return this.isAdmin() ? this.admin.deleteLesson(id) : this.teacher.deleteTeacherLesson(id);
  }

  /**
   * E1's light poll body — status, steps, files, plays, panel — and nothing else.
   *
   * The screens poll this while a job runs and read the full lesson back only when it says
   * something changed (`lesson-status.ts`).
   */
  status(id: string): Observable<LessonStatusView> {
    return this.isAdmin() ? this.admin.getLessonStatus(id) : this.teacher.teacherLessonStatus(id);
  }

  /**
   * Move an unpublished lesson to another day.
   *
   * `PATCH /teacher/lessons/{id}` is a teacher route only: an Admin's lesson is keyed by course
   * and date together, so moving one is a different operation the Admin screens do not offer.
   */
  readonly supportsMoveDate = computed(() => !this.isAdmin());

  moveDate(id: string, date: string): Observable<AdminLesson> {
    if (!this.supportsMoveDate()) return this.unsupported('moveDate');
    return this.teacher.moveTeacherLesson(id, { date });
  }

  /**
   * The teacher's fan-out: her own class plus every sibling she ticked, each getting its own
   * copy with its own results. `PublishedCopy[]` names them back, which is what the success
   * band lists and links. The admin route publishes the one lesson and takes no classes, so
   * {@link publish} stays for it.
   */
  publishToClasses(id: string, classIds: readonly string[]): Observable<readonly PublishedCopy[]> {
    if (this.isAdmin()) return this.unsupported('publishToClasses');
    return this.teacher.publishTeacherLesson(id, { classIds: [...classIds] });
  }

  /**
   * Publish this lesson into its own class, and answer with it as it now stands — what the
   * Admin's confirm and the teacher's Undo-unpublish both want.
   *
   * A teacher must name the class: `POST /teacher/lessons/{id}/publish` refuses an empty
   * `classIds` with 400 ("Name at least one class to publish into"), because a lesson that lands
   * nowhere is not a publish. Its answer is the list of copies it made rather than the lesson,
   * so the lesson is read back; the sheet wants those copies and calls
   * {@link publishToClasses} instead.
   */
  publish(id: string, ownClassId?: string): Observable<AdminLesson> {
    if (this.isAdmin()) return this.admin.publish(id);
    if (!ownClassId) return this.unsupported("publish without the lesson's own class");
    return this.teacher
      .publishTeacherLesson(id, { classIds: [ownClassId] })
      .pipe(switchMap(() => this.teacher.teacherLesson(id)));
  }

  unpublish(id: string): Observable<AdminLesson> {
    return this.isAdmin() ? this.admin.unpublish(id) : this.teacher.unpublishTeacherLesson(id);
  }

  // ---- the pipeline -------------------------------------------------------------------------

  retry(id: string): Observable<JobRef> {
    return this.isAdmin() ? this.admin.retry(id) : this.teacher.teacherRetry(id);
  }

  retryStep(id: string, step: string): Observable<JobRef> {
    return this.isAdmin() ? this.admin.retryStep(id, step) : this.teacher.teacherRetryStep(id, step);
  }

  analyze(id: string): Observable<JobRef> {
    return this.isAdmin() ? this.admin.analyze(id) : this.teacher.teacherAnalyze(id);
  }

  confirmSkills(id: string, body: string): Observable<JobRef> {
    return this.isAdmin() ? this.admin.confirmSkills(id, body) : this.teacher.teacherConfirmSkills(id, body);
  }

  generateFromText(id: string, body: string): Observable<JobRef> {
    return this.isAdmin()
      ? this.admin.generateFromText(id, body)
      : this.teacher.teacherGenerateFromText(id, body);
  }

  /**
   * E5: write **one** level from the Level 1 already in hand (`POST …/plays/{level}/generate`).
   *
   * Answers the usual `JobRef` and runs as a single ledger step, so the caller has nothing to
   * draw from it beyond the status: the lesson goes `generating` and the editor's `/status` poll
   * owns everything after that.
   *
   * `silentErrors()` because all three refusals belong to the Add level card rather than to a
   * band at the top of the page — 409 `exists` asks her whether to write over the level, 409
   * `generating` is a strip, and the 400 for an empty Level 1 is a hint under the two buttons.
   */
  generateLevel(id: string, level: AskLevel, replace = false): Observable<JobRef> {
    const options = { context: silentErrors() };
    return this.isAdmin()
      ? this.admin.generateLevel(id, level, replace, 'body', false, options)
      : this.teacher.teacherGenerateLevel(id, level, replace, 'body', false, options);
  }

  // ---- files and images ---------------------------------------------------------------------

  uploadFiles(id: string, files: readonly File[]): Observable<JobRef> {
    return this.isAdmin()
      ? this.admin.uploadFiles(id, [...files])
      : this.teacher.teacherUploadFiles(id, [...files]);
  }

  deleteFiles(id: string): Observable<unknown> {
    return this.isAdmin() ? this.admin.deleteFiles(id) : this.teacher.teacherDeleteFiles(id);
  }

  uploadImage(id: string, file: File): Observable<LessonImage> {
    return this.isAdmin() ? this.admin.uploadImage(id, file) : this.teacher.teacherUploadImage(id, file);
  }

  // ---- CR4: the Markdown the model will read ------------------------------------------------

  /**
   * What the model will be handed for this file, as `text/markdown`.
   *
   * 404 `markdown_missing` until the convert step has run, which is exactly the case the
   * preview link is not rendered in — the caller shows it only on a `ready` file. It is still a
   * 404 the error interceptor must not turn into "not found" navigation, so the caller passes
   * `silentErrors` and puts its own sentence in the dialog.
   */
  fileMarkdown(lessonId: string, fileId: string): Observable<string> {
    const options = { context: silentErrors() };
    return this.isAdmin()
      ? this.admin.fileMarkdown(lessonId, fileId, 'body', false, options)
      : this.teacher.teacherFileMarkdown(lessonId, fileId, 'body', false, options);
  }

  /**
   * The teacher's two ways out of a file we could not read: OCR it, or type it.
   *
   * Answers the **whole lesson** rather than a job, because the server resets the pipeline from
   * `convert` — so the caller replaces its lesson with this and the step strip, the file's pill
   * and the status all move together, with no reload in between that could show a half-state.
   */
  retryConversion(
    lessonId: string,
    fileId: string,
    method: RetryMethod,
    markdown?: string,
  ): Observable<AdminLesson> {
    const body = retryConversionBody(markdown);
    return this.isAdmin()
      ? this.admin.retryConversion(lessonId, fileId, method, body)
      : this.teacher.teacherRetryConversion(lessonId, fileId, method, body);
  }

  // ---- plays and stops ----------------------------------------------------------------------

  createPlay(lessonId: string, body: string): Observable<AdminPlay> {
    return this.isAdmin()
      ? this.admin.createPlay(lessonId, body)
      : this.teacher.teacherCreatePlay(lessonId, body);
  }

  regeneratePlay(playId: string): Observable<Play> {
    return this.isAdmin() ? this.admin.regeneratePlay(playId) : this.teacher.teacherRegeneratePlay(playId);
  }

  reorder(playId: string, body: string): Observable<Play> {
    return this.isAdmin() ? this.admin.reorder(playId, body) : this.teacher.teacherReorder(playId, body);
  }

  /**
   * `silent` is {@link StopDraftService.addNow}'s. That path keeps the Add question sheet open on
   * the question the server refused and puts the sentence in it, so the interceptor's band would be
   * the same sentence twice; {@link StopDraftService.add} leaves it loud, because its sheet has
   * already closed and the band is the only place left to say anything.
   */
  addStop(playId: string, body: string, silent = false): Observable<Stop> {
    const options = silent ? { context: silentErrors() } : undefined;
    return this.isAdmin()
      ? this.admin.addStop(playId, body, 'body', false, options)
      : this.teacher.teacherAddStop(playId, body, 'body', false, options);
  }

  updateStop(stopId: string, body: string): Observable<Stop> {
    return this.isAdmin()
      ? this.admin.updateStop(stopId, body)
      : this.teacher.teacherUpdateStop(stopId, body);
  }

  /**
   * CR5: save one stop from the teacher's English (`POST …/stops/{stopId}/from-text`).
   *
   * `silentErrors()` because two of its answers belong to the editor rather than to a band at the
   * top of the page: 422 `rephrase` (the model could not make this wording fit the schema, twice)
   * and 400 while the lesson is still generating. The page raises a band itself for the rest.
   */
  stopFromText(stopId: string, body: string): Observable<Stop> {
    const options = { context: silentErrors() };
    return this.isAdmin()
      ? this.admin.stopFromText(stopId, body, 'body', false, options)
      : this.teacher.teacherStopFromText(stopId, body, 'body', false, options);
  }

  deleteStop(stopId: string): Observable<unknown> {
    return this.isAdmin() ? this.admin.deleteStop(stopId) : this.teacher.teacherDeleteStop(stopId);
  }

  regenerateStop(stopId: string): Observable<Stop> {
    return this.isAdmin() ? this.admin.regenerateStop(stopId) : this.teacher.teacherRegenerateStop(stopId);
  }

  // ---- the parent panel ---------------------------------------------------------------------

  updatePanel(id: string, body: string): Observable<ParentPanel> {
    return this.isAdmin() ? this.admin.updatePanel(id, body) : this.teacher.teacherUpdatePanel(id, body);
  }

  private unsupported<T>(operation: string): Observable<T> {
    return throwError(
      () => new Error(`${operation} is not available for this role — guard it with supports*.`),
    );
  }
}
