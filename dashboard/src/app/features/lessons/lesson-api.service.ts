import { Injectable, computed, inject } from '@angular/core';
import { Observable, switchMap, throwError } from 'rxjs';
import {
  type AdminLesson,
  type AdminPlay,
  type JobRef,
  type LessonImage,
  type ParentPanel,
  type Play,
  type Stop,
  AdminLessonsApi,
  TeacherLessonsApi,
} from '../../api';
import { AuthService } from '../../core/auth/auth.service';

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
 * Two operations have no teacher alias, and this says so rather than hiding it:
 *   - `createPlay` (add L2/L3/Again to a manual lesson) — `/admin/lessons/{id}/plays` only;
 *     {@link supportsCreateLevel} is false for a teacher and the button is not rendered.
 *   - `deleteFiles` — `/admin/lessons/{id}/files` DELETE only; {@link supportsFileDeletion}
 *     gates "Remove all files", and a teacher replacing a file uploads over it instead.
 * Both are flagged for the planner: the server can add the aliases in a later package and only
 * these two flags flip.
 *
 * Bodies are strings on purpose. Every lesson-content endpoint declares `"type": "string"` in
 * `server/openapi.json` (see `ui/phone-preview/stop.model.ts`'s header), so the generated
 * client takes the kotlinx-serialization JSON verbatim; `lessons.models.ts` builds it.
 */
@Injectable({ providedIn: 'root' })
export class LessonApiService {
  private readonly admin = inject(AdminLessonsApi);
  private readonly teacher = inject(TeacherLessonsApi);
  private readonly auth = inject(AuthService);

  readonly isAdmin = computed(() => this.auth.role() === 'ADMIN');
  /** `POST /teacher/lessons/{id}/plays` does not exist — creating a level is ADMIN-only today. */
  readonly supportsCreateLevel = computed(() => this.isAdmin());
  /** `DELETE /teacher/lessons/{id}/files` does not exist — clearing files is ADMIN-only today. */
  readonly supportsFileDeletion = computed(() => this.isAdmin());

  // ---- the lesson ---------------------------------------------------------------------------

  getLesson(id: string): Observable<AdminLesson> {
    return this.isAdmin() ? this.admin.getLesson(id) : this.teacher.teacherLesson(id);
  }

  deleteLesson(id: string): Observable<unknown> {
    return this.isAdmin() ? this.admin.deleteLesson(id) : this.teacher.deleteTeacherLesson(id);
  }

  /**
   * `classIds` is the teacher's sibling-class fan-out (N2.4b's publish sheet); empty means "her
   * own class only", which is what the server defaults to. The admin route takes no classes at
   * all, so both branches answer with the lesson as it now stands — the teacher one by reading
   * it back, because its own response is the list of copies it made.
   */
  publish(id: string, classIds: readonly string[] = []): Observable<AdminLesson> {
    if (this.isAdmin()) return this.admin.publish(id);
    return this.teacher
      .publishTeacherLesson(id, { classIds: [...classIds] })
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
    return this.isAdmin() ? this.admin.generateFromText(id, body) : this.teacher.teacherGenerateFromText(id, body);
  }

  // ---- files and images ---------------------------------------------------------------------

  uploadFiles(id: string, files: readonly File[]): Observable<JobRef> {
    return this.isAdmin() ? this.admin.uploadFiles(id, [...files]) : this.teacher.teacherUploadFiles(id, [...files]);
  }

  deleteFiles(id: string): Observable<unknown> {
    if (!this.supportsFileDeletion()) return this.unsupported('deleteFiles');
    return this.admin.deleteFiles(id);
  }

  uploadImage(id: string, file: File): Observable<LessonImage> {
    return this.isAdmin() ? this.admin.uploadImage(id, file) : this.teacher.teacherUploadImage(id, file);
  }

  // ---- plays and stops ----------------------------------------------------------------------

  createPlay(lessonId: string, body: string): Observable<AdminPlay> {
    if (!this.supportsCreateLevel()) return this.unsupported('createPlay');
    return this.admin.createPlay(lessonId, body);
  }

  regeneratePlay(playId: string): Observable<Play> {
    return this.isAdmin() ? this.admin.regeneratePlay(playId) : this.teacher.teacherRegeneratePlay(playId);
  }

  reorder(playId: string, body: string): Observable<Play> {
    return this.isAdmin() ? this.admin.reorder(playId, body) : this.teacher.teacherReorder(playId, body);
  }

  addStop(playId: string, body: string): Observable<Stop> {
    return this.isAdmin() ? this.admin.addStop(playId, body) : this.teacher.teacherAddStop(playId, body);
  }

  updateStop(stopId: string, body: string): Observable<Stop> {
    return this.isAdmin() ? this.admin.updateStop(stopId, body) : this.teacher.teacherUpdateStop(stopId, body);
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
    return throwError(() => new Error(`${operation} has no /teacher alias — guard it with supports*.`));
  }
}
