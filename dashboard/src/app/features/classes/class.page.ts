/* hq-flag: none (shell) — the class page is My classes' detail route, gated by `teacher.week`
   like its list. Its own contents carry the gates: the Children tab's roster lives behind
   `teacher.rosterEdit` + `roster.teacher`, and Gradebook and Exams carry `gradebook` and
   `exams` — the flags their own endpoints carry. */
import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { catchError, of, tap } from 'rxjs';
import { type ClassCalendar, TeacherApi } from '../../api';
import { quietNotFound } from '../../core/http/error.interceptor';
import { activeLang } from '../../core/i18n/active-lang';
import { FLAGS, FlagService } from '../../core/flags/flag.service';
import { ClassContextService } from '../../core/nav/class-context.service';
import {
  EmptyStateComponent,
  PageComponent,
  SkeletonComponent,
  TabsComponent,
  type Breadcrumb,
  type Tab,
} from '../../ui';
import { ClassCalendarComponent } from './class-calendar.component';
import { GradebookComponent } from '../results/gradebook.component';
import { ExamsTabComponent } from '../exams/exams-tab.component';
import { ClassChildrenComponent } from './class-children.component';
import { ClassAttendanceComponent } from './class-attendance.component';
import { calendarCells, monthParam, shiftMonth } from './classes.models';

const TAB_IDS = ['calendar', 'children', 'attendance', 'gradebook', 'exams'] as const;
type TabId = (typeof TAB_IDS)[number];

/**
 * Which tabs read what a tab writes.
 *
 * - **Children** writes the roster: the register lists it, the gradebook has a row per child and
 *   an exam's counts are out of it.
 * - **Attendance** writes the register, which the roster's own row for a child summarises.
 * - **Gradebook** writes marks and releases: the calendar's results column and a child's level
 *   band on the roster are computed from them, and an exam's row carries its marking state.
 * - **Calendar** and **Exams** write nothing here — their actions open another screen, and coming
 *   back builds the whole class page again.
 */
const DEPENDENTS: Readonly<Record<TabId, readonly TabId[]>> = {
  calendar: [],
  children: ['attendance', 'gradebook', 'exams'],
  attendance: ['children'],
  gradebook: ['calendar', 'children', 'exams'],
  exams: [],
};

function isTabId(value: string | null): value is TabId {
  return TAB_IDS.includes((value ?? '') as TabId);
}

/**
 * **The class page** (`docs/teacher-flow.md` §4 step 3 and §5) — one class, five tabs, **one
 * shell**.
 *
 * All five are built. Gradebook (N4.2) and Exams (N4.4) sit behind their school's own flags and
 * say what they would hold when one is off, rather than disappearing — a teacher who sees three
 * tabs where a colleague has five assumes something is broken in her account.
 *
 * **The shell stays; only the tab body swaps** (the owner's list of 2026-10-01, TEACHER item 2).
 * The header, the actions and the tab strip belong to this component, which the router keeps for
 * as long as she is in the class — a tab is a *query parameter* of the one route, so moving
 * between tabs is a navigation Angular answers by re-using this instance. Each tab's component is
 * created the first time she opens it and then kept (hidden, not destroyed), so its data is
 * fetched once for the visit and refetched only by its own mutations or a reload of the page.
 *
 * **The tab is in the URL** (`?tab=children`), and the URL is the *only* state: the strip reads
 * the query and a click navigates. So a deep link opens on its tab, a reload comes back to it,
 * and Back walks the tabs she visited before it leaves the class. The previous version kept a
 * signal beside the URL and wrote the address with `Location.replaceState`, which the router
 * never heard about — after a reload or a deep link the two disagreed and the strip snapped back
 * to the tab the router still believed in.
 *
 * **A class that is not hers is not an error she caused.** The id in the address may be a
 * bookmark, a `returnTo`, or a class from before the school was re-created; the first request is
 * therefore quiet about a 404 — and only a 404: a dead session still goes to sign-in — and sends
 * her to My classes instead of raising the red band. Nothing
 * else — no roster, no register, no gradebook — is asked for until that first answer has
 * confirmed the class, so a stale id costs one quiet request rather than five loud ones.
 *
 * While she is here the class joins the rail as its third item (§5), and leaving clears it —
 * `ClassContextService`, set on arrival and cleared on destroy.
 */
@Component({
  selector: 'hq-class-page',
  imports: [
    PageComponent,
    TabsComponent,
    EmptyStateComponent,
    SkeletonComponent,
    ClassCalendarComponent,
    ClassChildrenComponent,
    ClassAttendanceComponent,
    GradebookComponent,
    ExamsTabComponent,
    RouterLink,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './class.page.html',
  styleUrl: './class.page.scss',
})
export class ClassPage {
  private readonly teacherApi = inject(TeacherApi);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly transloco = inject(TranslocoService);
  private readonly classContext = inject(ClassContextService);
  private readonly flags = inject(FlagService);
  private readonly lang = activeLang();

  private readonly path = toSignal(this.route.paramMap, { initialValue: this.route.snapshot.paramMap });
  private readonly query = toSignal(this.route.queryParamMap, {
    initialValue: this.route.snapshot.queryParamMap,
  });

  protected readonly classId = computed(() => this.path().get('classId') ?? '');

  /**
   * The class whose first calendar answer has arrived — the proof that the id in the address is a
   * class of hers. Tab bodies wait on it; a month change afterwards does not unset it.
   */
  private readonly confirmedClass = signal<string | null>(null);
  protected readonly confirmed = computed(() => this.confirmedClass() === this.classId());

  // ---- the tabs: the URL is the state ---------------------------------------------------------

  /** `?tab=` — read, never mirrored. An absent or unknown value is the calendar. */
  protected readonly tab = computed<TabId>(() => {
    const value = this.query().get('tab');
    return isTabId(value) ? value : 'calendar';
  });

  /** The tabs opened on this visit to this class: each is created once and then kept. */
  protected readonly visitedTabs = signal<ReadonlySet<TabId>>(new Set([this.tab()]));
  private visitedFor = this.classId();

  protected hasVisited(id: TabId): boolean {
    return this.confirmed() && this.visitedTabs().has(id);
  }

  /**
   * A click on the strip. A query-only navigation of the route she is already on: the router
   * re-uses this component, so nothing above the tab body is rebuilt and nothing is refetched.
   * It pushes a history entry, which is what lets Back return to the tab she came from.
   */
  /**
   * **A write in one tab makes the others' copies old.**
   *
   * Tabs are kept for the visit, so a pane opened before the write would go on showing what was
   * true then: a child added to the roster missing from the register and the gradebook, a mark
   * missing from the calendar's results column. The tab that wrote says so (`changed`), and every
   * tab that reads what it wrote is dropped from the kept set — it is hidden at that moment, so
   * nothing visibly happens — and is built again, from the server, the next time she opens it.
   * The calendar lives in this shell rather than in a pane, so it is reloaded in place.
   */
  protected onChanged(source: TabId): void {
    const stale = DEPENDENTS[source];
    this.visitedTabs.update((seen) => new Set([...seen].filter((id) => id === this.tab() || !stale.includes(id))));
    if (stale.includes('calendar')) this.calendar.reload();
  }

  protected selectTab(id: TabId): void {
    if (id === this.tab()) return;
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { tab: id },
      queryParamsHandling: 'merge',
    });
  }

  // ---- which month --------------------------------------------------------------------------

  private readonly now = new Date();
  protected readonly year = signal(this.now.getUTCFullYear());
  protected readonly month = signal(this.now.getUTCMonth() + 1);

  protected readonly calendar = rxResource<ClassCalendar, { classId: string; month: string }>({
    params: () => ({ classId: this.classId(), month: monthParam(this.year(), this.month()) }),
    stream: ({ params }) =>
      this.teacherApi
        .classCalendar(params.classId, undefined, params.month, 'body', false, { context: quietNotFound() })
        .pipe(
          tap(() => this.confirmedClass.set(params.classId)),
          catchError((error: unknown) => {
            this.onCalendarFailed(error, params.classId);
            // An empty calendar rather than an errored resource: the screen is leaving (404) or the
            // interceptor has said why in the band, and `value()` of a failed resource throws.
            return of<ClassCalendar>({});
          }),
        ),
    defaultValue: {},
  });

  protected readonly cells = computed(() => calendarCells(this.calendar.value()));
  protected readonly gaps = computed(() => this.calendar.value().gaps ?? 0);

  protected shiftMonth(delta: number): void {
    const next = shiftMonth(this.year(), this.month(), delta);
    this.year.set(next.year);
    this.month.set(next.month);
  }

  // ---- the header ----------------------------------------------------------------------------

  /**
   * The header comes off the calendar response, which carries the whole identity of the section
   * (#70 added `className` and a real `subject`): one request for the screen rather than a
   * second list fetched only to read one name out of it.
   *
   * `?subject=` — the card's own — stands in for the one word the header needs before the
   * calendar answers, so the title does not change its mind halfway through the first paint.
   */
  protected readonly className = computed(() => this.calendar.value().className ?? '');
  protected readonly curriculum = computed(() => this.calendar.value().curriculum ?? '');
  protected readonly grade = computed(() => this.calendar.value().grade ?? 0);
  protected readonly subject = computed(
    () => this.calendar.value().subject ?? this.query().get('subject') ?? '',
  );

  /** "1A · Math" — §4's header, and the words the rail's third item uses. */
  protected readonly title = computed(() => {
    this.lang();
    const name = this.className();
    const subject = this.word(`subject.${this.subject()}`, this.subject());
    return name && subject ? `${name} · ${subject}` : name || subject;
  });

  protected readonly subtitle = computed(() => {
    this.lang();
    const grade = this.grade();
    if (!grade) return '';
    return this.t('classes.header.subtitle', {
      curriculum: this.word(`curriculum.${this.curriculum()}`, this.curriculum()),
      grade,
    });
  });

  /** "My classes / 1A · Math" — the way back, without pressing Back four times. */
  protected readonly breadcrumbs = computed<readonly Breadcrumb[]>(() => {
    this.lang();
    return [{ label: this.t('classes.title'), link: '/teacher/classes' }, { label: this.title() }];
  });

  // ---- the tabs --------------------------------------------------------------------------------

  protected readonly tabs = computed<readonly Tab<TabId>[]>(() => {
    this.lang();
    return [
      { id: 'calendar', label: this.t('classes.tabs.calendar'), controls: 'hq-class-panel' },
      { id: 'children', label: this.t('classes.tabs.children'), controls: 'hq-class-panel' },
      { id: 'attendance', label: this.t('classes.tabs.attendance'), controls: 'hq-class-panel' },
      { id: 'gradebook', label: this.t('classes.tabs.gradebook'), controls: 'hq-class-panel' },
      { id: 'exams', label: this.t('classes.tabs.exams'), controls: 'hq-class-panel' },
    ];
  });

  /** N4.2: the Gradebook tab's own gate — the flag the grid's endpoints carry. */
  protected readonly gradebookOn = computed(() => this.flags.isOn(FLAGS.gradebook));

  /** N4.4: the same arrangement for Exams — `ExamController` carries `exams` over every route. */
  protected readonly examsOn = computed(() => this.flags.isOn(FLAGS.exams));

  // ---- links out ---------------------------------------------------------------------------------

  protected readonly newLessonLink = ['/teacher/lessons/new'];
  protected readonly lessonsLink = ['/teacher/lessons'];

  protected readonly newLessonParams = computed<Record<string, string>>(() => ({
    classId: this.classId(),
    curriculum: this.curriculum(),
    grade: String(this.grade()),
    subject: this.subject(),
  }));

  /** The class's own lessons, in the list she already knows — same course, filtered to this class. */
  protected readonly lessonsParams = computed<Record<string, string>>(() => ({
    classId: this.classId(),
    curriculum: this.curriculum(),
    grade: String(this.grade()),
  }));

  // ---- wiring ---------------------------------------------------------------------------------------

  constructor() {
    const destroyRef = inject(DestroyRef);

    // A tab joins the kept set the first time it is opened; another class starts the set again.
    effect(() => {
      const classId = this.classId();
      const tab = this.tab();
      untracked(() => {
        if (classId !== this.visitedFor) {
          this.visitedFor = classId;
          this.visitedTabs.set(new Set([tab]));
        } else if (!this.visitedTabs().has(tab)) {
          this.visitedTabs.update((seen) => new Set([...seen, tab]));
        }
      });
    });

    // §5's third rail item, for as long as she is on this class.
    effect(() => {
      const label = this.title();
      const id = this.classId();
      if (!id || !label) return;
      this.classContext.set({ id, label, link: `/teacher/classes/${id}` });
    });
    destroyRef.onDestroy(() => this.classContext.clear());
  }

  /**
   * The calendar did not answer.
   *
   * A 404 is "this class is not yours, or is gone" — and she did not click anything to cause it
   * (My classes only lists classes that exist), so it is a stale address: back to the list,
   * replacing this entry so Back does not return to it. The request is `quietNotFound()`, so that
   * is the one status with no band; every other failure — a 401's trip to sign-in included — is
   * the error interceptor's, unchanged.
   */
  private onCalendarFailed(error: unknown, classId: string): void {
    const missing = error instanceof HttpErrorResponse && error.status === 404;
    if (missing && this.confirmedClass() !== classId) {
      void this.router.navigate(['/teacher/classes'], { replaceUrl: true });
    }
  }

  private word(key: string, fallback: string): string {
    const text = this.t(key);
    return text === key ? fallback : text;
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}
