/* hq-flag: none (shell) — the class page is My classes' detail route, gated by `teacher.week`
   like its list. Its own contents carry the gates: the Children tab's roster lives behind
   `teacher.rosterEdit` + `roster.teacher`, and Gradebook and Exams carry `gradebook` and
   `exams` — the flags their own endpoints carry. */
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { TeacherApi } from '../../api';
import { activeLang } from '../../core/i18n/active-lang';
import { FLAGS, FlagService } from '../../core/flags/flag.service';
import { ClassContextService } from '../../core/nav/class-context.service';
import { EmptyStateComponent, PageComponent, TabsComponent, type Breadcrumb, type Tab } from '../../ui';
import { ClassCalendarComponent } from './class-calendar.component';
import { GradebookComponent } from '../results/gradebook.component';
import { ExamsTabComponent } from '../exams/exams-tab.component';
import { ClassChildrenComponent } from './class-children.component';
import { ClassAttendanceComponent } from './class-attendance.component';
import { calendarCells, monthParam, shiftMonth } from './classes.models';

const TAB_IDS = ['calendar', 'children', 'attendance', 'gradebook', 'exams'] as const;
type TabId = (typeof TAB_IDS)[number];

function isTabId(value: string | null): value is TabId {
  return TAB_IDS.includes((value ?? '') as TabId);
}

/**
 * **The class page** (`docs/teacher-flow.md` §4 step 3 and §5) — one class, four tabs.
 *
 * All four are built. Gradebook (N4.2) and Exams (N4.4) sit behind their school's own flags and
 * say what they would hold when one is off, rather than disappearing — a teacher who sees two
 * tabs where a colleague has four assumes something is broken in her account.
 *
 * **The tab is in the URL** (`?tab=children`). A tab kept only in a signal is a screen that
 * cannot be linked to, comes back on the wrong panel after a reload, and answers Back by
 * leaving the class entirely. `replaceUrl` keeps the history one entry per class, not per tab.
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

  // ---- which month --------------------------------------------------------------------------

  private readonly now = new Date();
  protected readonly year = signal(this.now.getUTCFullYear());
  protected readonly month = signal(this.now.getUTCMonth() + 1);

  protected readonly calendar = rxResource({
    params: () => ({ classId: this.classId(), month: monthParam(this.year(), this.month()) }),
    stream: ({ params }) => this.teacherApi.classCalendar(params.classId, undefined, params.month),
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

  protected readonly tab = signal<TabId>(
    isTabId(this.route.snapshot.queryParamMap.get('tab'))
      ? (this.route.snapshot.queryParamMap.get('tab') as TabId)
      : 'calendar',
  );

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

    // The tab lives in the URL, replacing rather than pushing: one history entry per class.
    effect(() => {
      const tab = this.tab();
      if (this.query().get('tab') === tab) return;
      void this.router.navigate([], {
        relativeTo: this.route,
        queryParams: { tab },
        queryParamsHandling: 'merge',
        replaceUrl: true,
      });
    });

    // Sync incoming URL query param ?tab= to the active tab signal
    effect(() => {
      const queryTab = this.query().get('tab');
      if (isTabId(queryTab) && queryTab !== this.tab()) {
        this.tab.set(queryTab);
      }
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

  private word(key: string, fallback: string): string {
    const text = this.t(key);
    return text === key ? fallback : text;
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}
