import { Injectable, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { forkJoin, map, of } from 'rxjs';
import {
  type AdminLesson,
  type CoordinatorClass,
  type CoordinatorMe,
  type CoordinatorTeacher,
  AdminLessonStatusEnum,
  CoordinatorApi,
} from '../../api';
import { AuthService } from '../../core/auth/auth.service';
import { type CellStatus, normaliseStatus } from '../week/week.models';

/** One subject she supervises, with the track it is on — `null` means both (DR1). */
export interface ScopeChip {
  readonly subject: string;
  readonly curriculum: string | null;
}

/** A class of hers, as her four screens all want it. */
export interface CoordinatorClassView {
  readonly classId: string;
  readonly className: string;
  readonly subject: string;
  readonly curriculum: string;
  readonly grade: number;
  readonly teacherId: string;
  readonly teacherName: string;
  readonly childrenCount: number;
  readonly todayLessonId: string | null;
  readonly todayStatus: CellStatus;
}

/** One line of Home's "What needs you": a lesson to look at, or a class with nothing on today. */
export interface CoordinatorNeed {
  readonly kind: 'needs_review' | 'error' | 'no_lesson';
  readonly title: string;
  readonly className: string;
  /** `/coordinator/lessons/{id}`, or the Classes screen when there is no lesson to open. */
  readonly link: readonly string[];
}

/**
 * Everything `/coordinator/**` answers, once per session (R2, DR2).
 *
 * Root-provided rather than per-page: Home shows previews of the teachers and the classes the
 * Teachers and Classes screens list in full, and three copies of the same two requests — one
 * per screen, re-issued on every rail click — is three chances for them to disagree about how
 * many sections she has. The resources are read-only all the way down; there is no `reload` on
 * anything but the error path, because nothing in this area writes.
 *
 * `params` waits for the role. A cold load has no `/me` yet, and asking `/coordinator/me`
 * before it lands would 403 for whoever is actually signed in.
 */
@Injectable({ providedIn: 'root' })
export class CoordinatorService {
  private readonly api = inject(CoordinatorApi);
  private readonly auth = inject(AuthService);

  private readonly isCoordinator = computed(() => this.auth.role() === 'COORDINATOR');

  private readonly meRes = rxResource<CoordinatorMe, boolean>({
    params: () => this.isCoordinator(),
    stream: ({ params: mine }) => (mine ? this.api.coordinatorMe() : of({})),
    defaultValue: {},
  });

  private readonly teachersRes = rxResource<readonly CoordinatorTeacher[], boolean>({
    params: () => this.isCoordinator(),
    stream: ({ params: mine }) => (mine ? this.api.coordinatorTeachers() : of([])),
    defaultValue: [],
  });

  private readonly classesRes = rxResource<readonly CoordinatorClass[], boolean>({
    params: () => this.isCoordinator(),
    stream: ({ params: mine }) => (mine ? this.api.coordinatorClasses() : of([])),
    defaultValue: [],
  });

  /**
   * The two lesson statuses Home's "What needs you" is about, in one resource.
   *
   * `GET /coordinator/lessons` narrows by a single status, so this asks twice and joins. One
   * resource rather than two, because a half-loaded list would draw a shorter "needs you" than
   * the truth and then grow under her.
   */
  private readonly attentionRes = rxResource<readonly AdminLesson[], boolean>({
    params: () => this.isCoordinator(),
    stream: ({ params: mine }) =>
      mine
        ? forkJoin([
            this.api.coordinatorLessons(undefined, AdminLessonStatusEnum.NEEDS_REVIEW),
            this.api.coordinatorLessons(undefined, AdminLessonStatusEnum.ERROR),
          ]).pipe(map(([review, failed]) => [...review, ...failed]))
        : of([]),
    defaultValue: [],
  });

  readonly loading = computed(
    () => this.meRes.isLoading() || this.teachersRes.isLoading() || this.classesRes.isLoading(),
  );
  readonly failed = computed(() => this.meRes.error() !== undefined || this.classesRes.error() !== undefined);

  readonly displayName = computed(() => this.meRes.value().displayName ?? '');
  readonly counts = computed(() => ({
    sections: this.meRes.value().sections ?? 0,
    teachers: this.meRes.value().teachers ?? 0,
    children: this.meRes.value().children ?? 0,
  }));

  readonly scopes = computed<readonly ScopeChip[]>(() =>
    (this.meRes.value().scopes ?? []).map((scope) => ({
      subject: scope.subject,
      curriculum: scope.curriculum ?? null,
    })),
  );

  readonly teachers = computed<readonly CoordinatorTeacher[]>(() => this.teachersRes.value());

  readonly classes = computed<readonly CoordinatorClassView[]>(() =>
    this.classesRes
      .value()
      .map((row) => {
        const lessonId = row.todayLessonId ?? null;
        return {
          classId: row.classId ?? '',
          className: row.className ?? '',
          subject: row.subject ?? '',
          curriculum: row.curriculum ?? '',
          grade: row.grade ?? 0,
          teacherId: row.teacherId ?? '',
          teacherName: row.teacherName ?? '',
          childrenCount: row.childrenCount ?? 0,
          todayLessonId: lessonId,
          todayStatus: normaliseStatus(row.todayStatus, lessonId !== null),
        };
      })
      .sort((a, b) => a.grade - b.grade || a.className.localeCompare(b.className)),
  );

  /**
   * What needs her, in the order she would act: the lessons that failed, the ones waiting for a
   * review, then the classes with nothing on today.
   *
   * She cannot fix any of them herself — DR2 — so each line is a way *in* to what she would then
   * say to the teacher, never an action of her own.
   */
  readonly needs = computed<readonly CoordinatorNeed[]>(() => {
    const lessons = this.attentionRes.value().map((lesson) => ({
      kind: lesson.status === AdminLessonStatusEnum.ERROR ? ('error' as const) : ('needs_review' as const),
      title: (lesson.title ?? '').trim(),
      className: lesson.className ?? '',
      link: ['/coordinator/lessons', lesson.id],
    }));
    const quiet = this.classes()
      .filter((row) => row.todayLessonId === null)
      .map((row) => ({
        kind: 'no_lesson' as const,
        title: row.teacherName,
        className: row.className,
        link: ['/coordinator/classes'],
      }));
    const rank = { error: 0, needs_review: 1, no_lesson: 2 };
    return [...lessons, ...quiet].sort((a, b) => rank[a.kind] - rank[b.kind]);
  });

  reload(): void {
    this.meRes.reload();
    this.teachersRes.reload();
    this.classesRes.reload();
    this.attentionRes.reload();
  }
}
