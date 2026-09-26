import { Injectable, computed, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  type ChildReport,
  type ClassAttendanceResponse,
  type ExamResults,
  type ExamRow,
  type Gradebook,
  type LessonResults,
  CoordinatorApi,
  CoordinatorResultsApi,
  ExamsApi,
  ResultsAndGradebookApi,
} from '../../api';
import { AuthService } from '../../core/auth/auth.service';

/**
 * One read surface over the results, exams and attendance the server publishes twice (R6, DR2).
 *
 * `/teacher/**` is scoped to the caller's own assignments and answers 404 for anybody else's
 * class; `/coordinator/**` (R3) answers the **same five shapes** for every section that carries
 * her subject, and refuses everything that writes. So the screens — the gradebook, the lesson
 * results, the exams list, an exam's results and a child's report — are one component each, and
 * this is the one place that knows which namespace a role reads them from. The same idea, and
 * for the same reason, as {@link LessonApiService}.
 *
 * Nothing here writes. Every write on those screens is rendered behind `*hqCan="'results.write'"`
 * or `'lesson.write'`, and a coordinator holds neither — which is what makes her copy of each
 * screen draw itself *without* the control rather than with a disabled one.
 */
@Injectable({ providedIn: 'root' })
export class ResultsApiService {
  private readonly teacher = inject(ResultsAndGradebookApi);
  private readonly teacherExams = inject(ExamsApi);
  private readonly results = inject(CoordinatorResultsApi);
  private readonly coordinator = inject(CoordinatorApi);
  private readonly auth = inject(AuthService);

  readonly isCoordinator = computed(() => this.auth.role() === 'COORDINATOR');

  /**
   * `/me` has landed, so which namespace to read is settled.
   *
   * Every resource on the shared screens waits for this. Without it a cold load — a bookmark
   * straight onto `/coordinator/gradebook`, a hard refresh — fires the read while `role()` is
   * still `null`, which reads as "not a coordinator" and sends her request to `/teacher/**`: a
   * 403 in a red band, and then the right request a moment later. `roleGuard` already waits for
   * `/me` before the route resolves, so in the app this only ever holds for a tick; it is the
   * guarantee that matters, not the tick.
   */
  readonly ready = computed(() => this.auth.role() !== null);

  /**
   * The area every link out of a shared screen belongs to — a child, a lesson, a class.
   *
   * `/coordinator/children/{id}` and `/teacher/children/{id}` are two different routes onto the
   * same report, and a gradebook row that sent her to the teacher's would be refused by
   * `roleGuard` on a screen she is allowed to read.
   */
  readonly base = computed(() => (this.isCoordinator() ? '/coordinator' : '/teacher'));

  /**
   * CSV, XLSX and the per-child PDF exist in the teacher's namespace only.
   *
   * Reported rather than hidden: her screens simply do not draw those buttons, because a button
   * that answers 403 is worse than no button. Her Attendance screen builds its own CSV in the
   * browser from the rows it already has (`attendance-range.ts`), which is why that one is there.
   */
  readonly supportsExport = computed(() => !this.isCoordinator());

  /** The area's own classes screen — the first crumb of every shared results page. */
  readonly classesLink = computed(() => `${this.base()}/classes`);

  /**
   * `/teacher/classes/{id}`, or `null` for a role whose area has no per-class page.
   *
   * The coordinator's Classes screen lists every section in scope and opens none of them (R5's
   * deliberate limit), so her middle crumb is the class's *name* and not a link: a crumb that
   * navigates to `/not-found` is worse than a crumb that only says where she is.
   */
  classLink(classId: string, tab?: string): string | null {
    if (this.isCoordinator() || classId === '') return null;
    return tab ? `/teacher/classes/${classId}?tab=${tab}` : `/teacher/classes/${classId}`;
  }

  gradebook(classId: string, from: string, to: string): Observable<Gradebook> {
    return this.isCoordinator()
      ? this.results.coordinatorClassResults(classId, from, to)
      : this.teacher.gradebook(classId, from, to);
  }

  lessonResults(id: string): Observable<LessonResults> {
    return this.isCoordinator() ? this.results.coordinatorLessonResults(id) : this.teacher.lessonResults(id);
  }

  childReport(id: string): Observable<ChildReport> {
    return this.isCoordinator() ? this.results.coordinatorChild(id) : this.teacher.childReport(id);
  }

  /**
   * The class's exams.
   *
   * R3's `ExamRow` is `ExamSettings` plus `state`, `sat`, `roster` and `needsMarking`, so her
   * list arrives complete and the tab skips the per-exam results fan-out the teacher's needs.
   */
  classExams(classId: string): Observable<readonly ExamRow[]> {
    return this.isCoordinator()
      ? this.results.coordinatorClassExams(classId)
      : this.teacherExams.classExams(classId);
  }

  examResults(id: string): Observable<ExamResults> {
    return this.isCoordinator() ? this.results.coordinatorExamResults(id) : this.teacherExams.examResults(id);
  }

  /**
   * A class's attendance over a range of days — hers alone.
   *
   * `GET /teacher/classes/{id}/attendance` takes one `date` and is a marking screen; the
   * coordinator's takes `from`/`to` and answers a day per element, which is the shape her table
   * needs. The teacher keeps her own single-day read (`AttendanceService`), so — unlike every
   * other method here — this does not branch at all: there is no teacher route to branch to. It
   * lives here so her screen has one door to the API like the other five.
   */
  attendanceRange(classId: string, from: string, to: string): Observable<readonly ClassAttendanceResponse[]> {
    return this.coordinator.coordinatorAttendance(classId, from, to);
  }
}

/** A breadcrumb's `link`, or no `link` key at all when the area has no page to send her to. */
export function linkOrNothing(link: string | null): { link?: string } {
  return link === null ? {} : { link };
}
