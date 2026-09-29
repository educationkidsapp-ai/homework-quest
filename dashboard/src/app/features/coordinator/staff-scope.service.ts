import { Injectable, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { catchError, forkJoin, map, of } from 'rxjs';
import {
  type ChatThread,
  type CoordinatorTeacher,
  AdminLessonStatusEnum,
  CoordinatorApi,
  CoordinatorChatApi,
  ManagementApi,
} from '../../api';
import { StaffAreaService } from '../../core/auth/staff-area';
import { FLAGS, FlagService } from '../../core/flags/flag.service';
import { type CellStatus, normaliseStatus } from '../week/week.models';

/** Nobody signed in, or nobody with a scope: zeros and no chips, never a half-drawn header. */
const EMPTY_SCOPE: ScopeView = {
  displayName: '',
  sections: 0,
  teachers: 0,
  children: 0,
  coordinators: 0,
  scopes: [],
};

/**
 * One line of the scope a supervisor is read to: a subject on a track, or a track alone.
 *
 * A coordinator's row is `{subject, curriculum}` and `curriculum: null` means both tracks (DR1);
 * a manager's is `{subject: null, curriculum}` — every subject of one department (RM1, DR5).
 * The same shape, one axis over, which is exactly how `ManagerScope` relates to
 * `CoordinatorScope` on the server.
 */
export interface ScopeChip {
  readonly subject: string | null;
  readonly curriculum: string | null;
}

/** Who she is and how much of the school she is read to, from `/me` on either namespace. */
interface ScopeView {
  readonly displayName: string;
  readonly sections: number;
  readonly teachers: number;
  readonly children: number;
  readonly coordinators: number;
  readonly scopes: readonly ScopeChip[];
}

/** A class of hers, as all her screens want it. */
export interface StaffClassView {
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

/** The sections of one grade, which is how `GET /management/classes` already groups them. */
export interface StaffGradeGroup {
  readonly grade: number;
  readonly rows: readonly StaffClassView[];
}

/** One line of Home's "What needs you": a lesson to look at, or a class with nothing on today. */
export interface StaffNeed {
  readonly kind: 'complaint' | 'needs_review' | 'error' | 'no_lesson';
  readonly title: string;
  readonly className: string;
  /**
   * `{base}/lessons/{id}`, or the Classes screen when there is no lesson to open.
   *
   * `null` for a manager's lesson lines since MG2a: All lessons and the read-only lesson page
   * left her rail, so the line still tells her which lesson needs somebody — that is the whole
   * point of the list — but it is not a link to a screen she no longer has.
   */
  readonly link: readonly string[] | null;
}

/**
 * Everything the supervisor's own namespace answers, once per session (R5/R6, RM3a).
 *
 * **One service, two areas.** `/coordinator/me|teachers|classes|lessons` and
 * `/management/me|teachers|classes|lessons` answer the same four questions about two different
 * scopes — a subject across every grade, a track across every subject — and the screens that
 * draw them are the same screens. The branch lives here and nowhere else: a page asks for
 * `classes()` and gets the ones it is allowed to see, and {@link StaffAreaService} is what
 * decided which. The alternative was a second copy of five screens that would have drifted
 * inside a phase.
 *
 * Root-provided rather than per-page: Home shows previews of the lists the Teachers and Classes
 * screens draw in full, and three copies of the same two requests — one per screen, re-issued on
 * every rail click — is three chances for them to disagree about how many sections she has. The
 * resources are read-only all the way down; there is no `reload` on anything but the error path,
 * because nothing in either area writes.
 *
 * `params` waits for the role. A cold load has no `/me` yet, and asking `/coordinator/me` before
 * it lands would 403 for whoever is actually signed in.
 */
@Injectable({ providedIn: 'root' })
export class StaffScopeService {
  private readonly api = inject(CoordinatorApi);
  private readonly management = inject(ManagementApi);
  private readonly chatApi = inject(CoordinatorChatApi);
  private readonly staff = inject(StaffAreaService);
  private readonly flags = inject(FlagService);

  readonly area = this.staff.area;
  readonly base = this.staff.base;

  /**
   * Which of the two namespaces to read, or `undefined` for anybody who reads neither.
   *
   * `undefined` leaves every resource below **idle** rather than firing at a default: a cold
   * load has no `/me` yet, and asking `/coordinator/me` before it lands would 403 for whoever is
   * actually signed in.
   */
  private readingArea(): 'coordinator' | 'management' | undefined {
    const area = this.area();
    return area === 'teacher' ? undefined : area;
  }
  /** RM3a: the department manager, for the screens only she has. */
  readonly isManager = computed(() => this.area() === 'management');
  /**
   * R7: `GET /coordinator/complaints` carries the `chat` flag, so a school without it is not
   * asked. A manager has no thread list to read at all until RM2's `/management/chat/threads`.
   */
  private readonly readsComplaints = computed(
    () => this.area() === 'coordinator' && this.flags.isOn(FLAGS.chat),
  );

  private readonly meRes = rxResource({
    params: () => this.readingArea(),
    stream: ({ params: area }) =>
      area === 'management'
        ? this.management.managementMe().pipe(
            map((me): ScopeView => ({
              displayName: me.displayName ?? '',
              sections: me.sections ?? 0,
              teachers: me.teachers ?? 0,
              children: me.children ?? 0,
              coordinators: me.coordinators ?? 0,
              scopes: (me.departments ?? []).map((curriculum) => ({ subject: null, curriculum })),
            })),
          )
        : this.api.coordinatorMe().pipe(
            map((me): ScopeView => ({
              displayName: me.displayName ?? '',
              sections: me.sections ?? 0,
              teachers: me.teachers ?? 0,
              children: me.children ?? 0,
              coordinators: 0,
              scopes: (me.scopes ?? []).map((scope) => ({
                subject: scope.subject,
                curriculum: scope.curriculum ?? null,
              })),
            })),
          ),
    defaultValue: EMPTY_SCOPE,
  });

  private readonly teachersRes = rxResource({
    params: () => this.readingArea(),
    stream: ({ params: area }) =>
      area === 'management' ? this.management.managementTeachers() : this.api.coordinatorTeachers(),
    defaultValue: [],
  });

  /**
   * Her sections. `GET /management/classes` answers a `GradeGroup` per grade, which is the same
   * rows in a shape that already says what {@link grades} below has to work out for a
   * coordinator, so it is flattened here and grouped again there — one list, one sort, two areas.
   */
  private readonly classesRes = rxResource({
    params: () => this.readingArea(),
    stream: ({ params: area }) =>
      area === 'management'
        ? this.management.managementClasses().pipe(map((groups) => groups.flatMap((g) => g.classes ?? [])))
        : this.api.coordinatorClasses(),
    defaultValue: [],
  });

  /**
   * The two lesson statuses Home's "What needs you" is about, in one resource.
   *
   * Both namespaces narrow by a single status, so this asks twice and joins. One resource rather
   * than two, because a half-loaded list would draw a shorter "needs you" than the truth and then
   * grow under her.
   */
  private readonly attentionRes = rxResource({
    params: () => this.readingArea(),
    stream: ({ params: area }) =>
      forkJoin([
        this.lessonsOf(area, AdminLessonStatusEnum.NEEDS_REVIEW),
        this.lessonsOf(area, AdminLessonStatusEnum.ERROR),
      ]).pipe(map(([review, failed]) => [...review, ...failed])),
    defaultValue: [],
  });

  /**
   * The complaints still open, for Home's "What needs you" (R7, DR3).
   *
   * Deliberately outside `failed`: a school with `chat` off answers 404 here, and a coordinator
   * whose Home turned into an error band because of a feature she does not have would have been
   * told her school is broken. A read that does not happen contributes no line, which is right.
   * The manager's own complaints across the department are RM3b's: RM2 shipped
   * `GET /management/chat/threads` and this package reads none of it — `core/chat/*` and
   * `features/chat/*` are RM3b's to wire.
   */
  private readonly complaintsRes = rxResource<readonly ChatThread[], boolean>({
    params: () => this.readsComplaints(),
    stream: ({ params: mine }) =>
      mine ? this.chatApi.coordinatorComplaints('open').pipe(catchError(() => of([]))) : of([]),
    defaultValue: [],
  });

  readonly openComplaints = computed(() => this.complaintsRes.value().length);

  readonly loading = computed(
    () => this.meRes.isLoading() || this.teachersRes.isLoading() || this.classesRes.isLoading(),
  );
  /**
   * Any of the three shared reads failed.
   *
   * All three, including the teachers: the review found `teachersRes` missing here, which made a
   * failed `GET /coordinator/teachers` invisible on every screen — the Home drew its preview card
   * empty and the Teachers table said "no teachers teach your subject yet".
   */
  readonly failed = computed(
    () =>
      this.meRes.error() !== undefined ||
      this.teachersRes.error() !== undefined ||
      this.classesRes.error() !== undefined,
  );

  readonly displayName = computed(() => this.meRes.value().displayName);
  readonly counts = computed(() => {
    const me = this.meRes.value();
    return {
      sections: me.sections,
      teachers: me.teachers,
      children: me.children,
      coordinators: me.coordinators,
    };
  });

  readonly scopes = computed<readonly ScopeChip[]>(() => this.meRes.value().scopes);

  readonly teachers = computed<readonly CoordinatorTeacher[]>(() => this.teachersRes.value());

  readonly classes = computed<readonly StaffClassView[]>(() =>
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
   * The same sections under a heading per grade.
   *
   * A coordinator supervises one subject in six grades and a manager six subjects in each of
   * them, so the manager's list is the one that is long enough to need the headings — but a
   * grade is the right first cut for both, and one list that reads the same way in both areas is
   * worth more than two that do not.
   */
  readonly grades = computed<readonly StaffGradeGroup[]>(() => {
    const byGrade = new Map<number, StaffClassView[]>();
    for (const row of this.classes()) {
      const rows = byGrade.get(row.grade);
      if (rows) rows.push(row);
      else byGrade.set(row.grade, [row]);
    }
    return [...byGrade.entries()].map(([grade, rows]) => ({ grade, rows })).sort((a, b) => a.grade - b.grade);
  });

  /** A month of her area's calendar — `/coordinator/calendar` or `/management/calendar`. */
  calendar(from: string, to: string) {
    return this.area() === 'management'
      ? this.management.managementCalendar(from, to)
      : this.api.coordinatorCalendar(from, to);
  }

  /**
   * What needs her, in the order she would act: the lessons that failed, the ones waiting for a
   * review, then the classes with nothing on today.
   *
   * She cannot fix any of them herself — DR2, and RM1 is read-only too — so each line is a way
   * *in* to what she would then say to the teacher, never an action of her own.
   */
  readonly needs = computed<readonly StaffNeed[]>(() => {
    const base = this.base();
    // A parent's complaint is the one line here that is about a person rather than a lesson, so
    // it leads: it is also the only one she can act on herself (answer it, then resolve it).
    const complaints = this.complaintsRes.value().map((thread) => ({
      kind: 'complaint' as const,
      title: thread.childName,
      className: thread.className ?? '',
      link: [`${base}/complaints`],
    }));
    // MG2a: a manager has no lesson page and no Classes screen any more, so her two lesson
    // kinds are lines rather than links, and a class with nothing on today sends her to
    // Teachers — the screen that says whether today has happened in a teacher's sections.
    const manager = this.area() === 'management';
    const lessons = this.attentionRes.value().map((lesson) => ({
      kind: lesson.status === AdminLessonStatusEnum.ERROR ? ('error' as const) : ('needs_review' as const),
      title: (lesson.title ?? '').trim(),
      className: lesson.className ?? '',
      link: manager ? null : [`${base}/lessons`, lesson.id],
    }));
    const quiet = this.classes()
      .filter((row) => row.todayLessonId === null)
      .map((row) => ({
        kind: 'no_lesson' as const,
        title: row.teacherName,
        className: row.className,
        link: [`${base}/${manager ? 'teachers' : 'classes'}`],
      }));
    const rank = { complaint: 0, error: 1, needs_review: 2, no_lesson: 3 };
    return [...complaints, ...lessons, ...quiet].sort((a, b) => rank[a.kind] - rank[b.kind]);
  });

  /** The `coordinator.*` / `management.*` half of a translation key both areas have a line for. */
  scoped(suffix: string): string {
    return `${this.area() === 'management' ? 'management' : 'coordinator'}.${suffix}`;
  }

  reload(): void {
    this.meRes.reload();
    this.teachersRes.reload();
    this.classesRes.reload();
    this.attentionRes.reload();
    this.complaintsRes.reload();
  }

  private lessonsOf(area: 'coordinator' | 'management', status: AdminLessonStatusEnum) {
    return area === 'management'
      ? this.management.managementLessons(undefined, status)
      : this.api.coordinatorLessons(undefined, status);
  }
}
