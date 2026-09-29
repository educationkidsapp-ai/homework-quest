/* hq-flag: announcements — the weekly plan is a broadcast (RM2), so the screen that writes and
   lists them carries the key the whole feature is behind. `screens.ts` puts it on the row; the
   `*hqFeature` block and `notEnabled` below are the answer to a bookmark. */
import { ChangeDetectionStrategy, Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { catchError, of, tap } from 'rxjs';
import { type CreateBroadcastRequest, BroadcastsApi } from '../../api';
import {
  type BroadcastDraft,
  type ComposeContext,
  EMPTY_DRAFT,
  sundayOf,
} from '../../core/broadcasts/broadcast.rules';
import {
  type GradeFilter,
  type PlanRow,
  filterWeeks,
  planCsv,
  planWeeks,
} from '../../core/broadcasts/plan-archive';
import { saveFile } from '../../core/download/download';
import { FeatureDirective } from '../../core/flags/feature.directive';
import { FLAGS, FlagService } from '../../core/flags/flag.service';
import { CanDirective } from '../../core/permissions/can.directive';
import { PlatformService } from '../../core/platform/platform.service';
import {
  BandComponent,
  ButtonComponent,
  CardComponent,
  EmptyStateComponent,
  InputComponent,
  PageComponent,
  type SelectOption,
  SelectComponent,
  SkeletonComponent,
  ToastComponent,
} from '../../ui';
import { BroadcastComposeComponent } from '../broadcasts/compose-sheet.component';
import { PlanWeeksComponent } from '../broadcasts/plan-weeks.component';
import { StaffScopeService } from '../coordinator/staff-scope.service';
import { CoordinatorReadFailedComponent } from '../coordinator/read-failed.component';

/** How far back the archive is read by default — the server's own default window (MG1). */
const DEFAULT_WEEKS = 12;

/** One card of "this week at a glance": a grade of her department, or the department itself. */
interface GlanceCard {
  readonly key: string;
  readonly label: string;
  /** `null` for the department's all-grades plan. */
  readonly grade: number | null;
  /** Which department the card writes for; `''` when she holds exactly one. */
  readonly department: string;
  readonly plan: PlanRow | null;
}

/**
 * **Weekly plans** (MG2b, owner's items 3 and 4): "the manager is who adds the weekly plan for all
 * grades", and "a feature to see all weekly plans".
 *
 * Three things on one screen, in the order she uses them:
 *
 * 1. **This week at a glance** — one card per grade of her department plus the department's own
 *    all-grades plan, each either the plan that is posted or an "Add plan" action. This is the
 *    question she opens the screen with on a Sunday morning, and a list of twelve weeks does not
 *    answer it.
 * 2. **The composer**, which is the Broadcasts screen's own sheet with the kind fixed to
 *    `weekly_plan` and the week and grade prefilled from the card she pressed
 *    (`features/broadcasts/compose-sheet.component.ts`).
 * 3. **The archive** — `GET /management/weekly-plans`, newest week first, past weeks and expired
 *    plans included, filtered by grade and by date range, with `readBy` on each row and a CSV of
 *    the list.
 *
 * **The glance has its own read.** It was a slice of the filtered archive, and that was a defect:
 * a grade filter or an end date in the past made a card say "No plan yet" for a week that had one,
 * and "Add plan" there replaces the plan it could not see. So this week is asked for on its own —
 * one week, no grade — and the archive's filters touch the list below and nothing else.
 *
 * **A grade needs one department.** `POST /management/broadcasts` accepts `grade` only on a row
 * that names no section, and a manager of two departments must name sections to say which
 * department she means (`BroadcastService.one`) — so she is offered one all-grades card per
 * department and the sheet says why. See `core/broadcasts/broadcast.rules.ts`.
 */
@Component({
  selector: 'hq-weekly-plans-page',
  imports: [
    BandComponent,
    BroadcastComposeComponent,
    ButtonComponent,
    CanDirective,
    CardComponent,
    CoordinatorReadFailedComponent,
    EmptyStateComponent,
    FeatureDirective,
    InputComponent,
    PageComponent,
    PlanWeeksComponent,
    SelectComponent,
    SkeletonComponent,
    ToastComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-page [title]="'nav.weeklyPlans' | transloco" [subtitle]="'plans.subtitle' | transloco">
      @if (!enabled()) {
        <hq-empty-state
          [message]="'broadcasts.notEnabled' | transloco"
          [detail]="'broadcasts.notEnabledHint' | transloco"
        />
      }

      <div *hqFeature="'announcements'">
        @if (failed()) {
          <hq-band
            variant="error"
            [open]="true"
            [title]="'band.failed' | transloco"
            (dismissed)="failed.set(false)"
          >
            {{ 'broadcasts.postFailed' | transloco }}
          </hq-band>
        }

        <hq-card [title]="'plans.thisWeek' | transloco">
          <p class="hq-muted">{{ weekLabel(thisWeek()) }}</p>
          <!-- Until this week's own read has landed, no card may say "no plan yet": that sentence
               with a button beside it is an offer to replace a plan nobody has seen. -->
          @if (glanceLoading()) {
            <hq-skeleton [loading]="true" [lines]="2" [label]="'ui.loading' | transloco" />
          } @else if (thisWeekPlans.error()) {
            <hq-coordinator-read-failed (retry)="thisWeekPlans.reload()" />
          } @else {
            <div class="wp__glance">
              @for (card of glance(); track card.key) {
                <div class="wp__card">
                  <p class="wp__grade">{{ card.label }}</p>
                  @if (card.plan; as plan) {
                    <p class="wp__posted">{{ plan.title || ('broadcasts.untitled' | transloco) }}</p>
                    <hq-button *hqCan="'management.broadcast'" variant="secondary" (pressed)="add(card)">
                      {{ 'plans.replace' | transloco }}
                    </hq-button>
                  } @else {
                    <p class="hq-muted">{{ 'plans.none' | transloco }}</p>
                    <hq-button *hqCan="'management.broadcast'" variant="secondary" (pressed)="add(card)">
                      {{ 'plans.add' | transloco }}
                    </hq-button>
                  }
                </div>
              }
            </div>
          }
        </hq-card>

        <hq-card [title]="'plans.archive' | transloco">
          <div class="mg-filters">
            <hq-select
              [label]="'plans.gradeFilter' | transloco"
              [options]="gradeFilters()"
              [value]="gradeChoice()"
              (valueChange)="gradeChoice.set($event)"
            />
            <hq-input
              type="date"
              [label]="'coordinator.lessons.from' | transloco"
              [value]="from()"
              (valueChange)="from.set($event)"
            />
            <hq-input
              type="date"
              [label]="'coordinator.lessons.to' | transloco"
              [value]="to()"
              (valueChange)="to.set($event)"
            />
            <hq-button variant="secondary" [disabled]="weeks().length === 0" (pressed)="exportCsv()">
              {{ 'plans.export' | transloco }}
            </hq-button>
          </div>

          @if (backwards()) {
            <hq-empty-state [message]="'plans.backwards' | transloco" />
          } @else if (archive.isLoading()) {
            <hq-skeleton [loading]="true" [lines]="6" [label]="'ui.loading' | transloco" />
          } @else if (archive.error()) {
            <hq-coordinator-read-failed (retry)="archive.reload()" />
          } @else if (weeks().length === 0) {
            <hq-empty-state [message]="'plans.empty' | transloco" [detail]="'plans.emptyHint' | transloco" />
          } @else {
            <hq-plan-weeks [weeks]="weeks()" />
          }
        </hq-card>

        <div page-footer>
          <hq-button *hqCan="'management.broadcast'" (pressed)="add(null)">
            {{ 'plans.add' | transloco }}
          </hq-button>
        </div>

        <hq-broadcast-compose
          [(open)]="composing"
          [ctx]="ctx()"
          [sections]="sections()"
          [kinds]="planOnly"
          [initial]="initial()"
          [posting]="posting()"
          [title]="'plans.add'"
          (submitted)="post($event)"
        />

        <hq-toast
          tone="success"
          [open]="posted()"
          [message]="'broadcasts.posted' | transloco"
          (expired)="posted.set(false)"
        />
      </div>
    </hq-page>
  `,
  styles: `
    .wp__glance {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
      gap: var(--hq-space-3);
    }

    .wp__card {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-2);
      padding: var(--hq-space-3);
      border: var(--hq-rule) solid var(--hq-ink);
    }

    .wp__grade {
      margin: 0;
      font-weight: var(--hq-font-label-weight);
    }

    .wp__posted {
      margin: 0;
      color: var(--hq-accent);
    }
  `,
})
export class WeeklyPlansPage {
  private readonly api = inject(BroadcastsApi);
  private readonly flags = inject(FlagService);
  private readonly transloco = inject(TranslocoService);
  private readonly platform = inject(PlatformService);
  private readonly scope = inject(StaffScopeService);

  protected readonly planOnly = ['weekly_plan'] as const;

  protected readonly enabled = computed(() => this.flags.isOn(FLAGS.announcements));

  protected readonly composing = signal(false);
  protected readonly posting = signal(false);
  protected readonly posted = signal(false);
  protected readonly failed = signal(false);
  protected readonly initial = signal<BroadcastDraft>(EMPTY_DRAFT);

  /** Today, and so this week's Sunday, in the **school's** zone — the week the server snaps to. */
  private readonly today = computed(() =>
    new Intl.DateTimeFormat('en-CA', { timeZone: this.platform.timezone() }).format(new Date()),
  );
  protected readonly thisWeek = computed(() => sundayOf(this.today()));

  protected readonly gradeChoice = signal<string>('any');
  protected readonly from = linkedSignal(() => weeksBefore(this.thisWeek(), DEFAULT_WEEKS - 1));
  protected readonly to = linkedSignal(() => this.thisWeek());

  protected readonly backwards = computed(() => this.from() !== '' && this.from() > this.to());

  /**
   * **This week, unfiltered** — the read the cards are built from, and nothing else.
   *
   * The review's first blocker: the glance used to be a slice of the filtered archive, so picking
   * grade 1 or an end date in the past made every card outside the filter say "No plan yet" for a
   * week that already had a plan — and "Add plan" on that card is a *replace*
   * (`BroadcastService.replacePlan` deletes the previous row, its read marks and its bell rows). A
   * screen may not offer a destructive action because of a filter she set to look at something
   * else, so this week has its own request: one week, both ends the same Sunday, no `grade`.
   */
  protected readonly thisWeekPlans = rxResource({
    params: () => (this.enabled() ? { week: this.thisWeek() } : undefined),
    stream: ({ params }) => this.api.managementWeeklyPlans(params.week, params.week, undefined),
  });

  /**
   * No card is drawn until this week's own answer is in — see the template.
   *
   * Her classes are allowed to arrive later: a grade whose card is not there yet cannot offer to
   * replace anything, while a card drawn before the plans are known could.
   */
  protected readonly glanceLoading = computed(() => this.thisWeekPlans.isLoading());

  /** The plans posted for this week, whatever the archive below is filtered to. */
  private readonly postedThisWeek = computed<readonly PlanRow[]>(() => {
    const week = this.thisWeek();
    const weeks = planWeeks(this.thisWeekPlans.value());
    return weeks.find((row) => row.weekStart === week)?.rows ?? weeks[0]?.rows ?? [];
  });

  /**
   * The archive.
   *
   * `grade` goes to the server when she names one — her route takes it, and asking for one grade
   * of two years is a smaller answer than one page of 200 plans filtered in the browser. "All
   * grades" (the department's own plans, which carry no grade) has no parameter to send, so that
   * one is a filter here; both then run through `filterWeeks`, which is the only thing the list
   * and the export read.
   */
  protected readonly archive = rxResource({
    params: () => {
      if (this.backwards() || !this.enabled()) return undefined;
      const grade = this.gradeChoice();
      return { from: this.from(), to: this.to(), grade: /^\d+$/.test(grade) ? Number(grade) : undefined };
    },
    stream: ({ params }) => this.api.managementWeeklyPlans(params.from, params.to, params.grade),
  });

  private readonly allWeeks = computed(() => planWeeks(this.archive.value()));

  protected readonly weeks = computed(() =>
    filterWeeks(this.allWeeks(), { grade: this.filter(), from: this.from(), to: this.to() }),
  );

  private readonly filter = computed<GradeFilter>(() => {
    const grade = this.gradeChoice();
    if (grade === 'any' || grade === 'all') return grade;
    return Number(grade);
  });

  /** Her departments, as the scope header spells them (`GET /management/me`). */
  private readonly departments = computed<readonly string[]>(() =>
    this.scope
      .scopes()
      .map((chip) => chip.curriculum)
      .filter((curriculum): curriculum is string => curriculum !== null),
  );

  protected readonly sections = computed(() => this.scope.classes());

  protected readonly ctx = computed<ComposeContext>(() => ({
    role: 'manager',
    departments: this.departments(),
    sections: this.sections(),
    today: this.today(),
    zone: this.platform.timezone(),
  }));

  /**
   * This week's cards.
   *
   * One department: the department's own all-grades plan first, then every grade she manages —
   * which is what "adds the weekly plan for all grades" means as a screen. Two departments: one
   * all-grades card each, because `grade` cannot be sent beside the `sectionIds` that say which
   * department a row is for (see the class comment).
   */
  protected readonly glance = computed<readonly GlanceCard[]>(() => {
    const posted = this.postedThisWeek();
    const departments = this.departments();
    if (departments.length > 1) {
      return departments.map((department) => ({
        key: department,
        label: this.transloco.translate<string>(`curriculum.${department}`),
        grade: null,
        department,
        plan: posted.find((row) => row.grade === null && row.plan.curriculum === department) ?? null,
      }));
    }
    return [
      {
        key: 'all',
        label: this.transloco.translate<string>('broadcasts.gradeAll'),
        grade: null,
        department: '',
        plan: posted.find((row) => row.grade === null) ?? null,
      },
      ...this.myGrades().map((grade) => ({
        key: String(grade),
        label: this.transloco.translate<string>('broadcasts.gradeN', { grade }),
        grade,
        department: '',
        plan: posted.find((row) => row.grade === grade) ?? null,
      })),
    ];
  });

  /** The grades she manages — the cards' own source, and the filter's (see {@link gradeFilters}). */
  private readonly myGrades = computed<readonly number[]>(() =>
    [...new Set(this.sections().map((row) => row.grade))].sort((a, b) => a - b),
  );

  /**
   * The filter's options, from **her grades** and not from the answer.
   *
   * The review's second blocker: built from `gradesIn(archive)` — the server-*filtered* answer —
   * the select offered only Any / All grades / 1 once she had chosen grade 1, so grade 2 was
   * reachable only by going back through Any. Her own classes are the same source the cards use,
   * and the same source `broadcast.rules.ts` validates a grade against.
   */
  protected readonly gradeFilters = computed<readonly SelectOption[]>(() => [
    { value: 'any', label: this.transloco.translate<string>('plans.gradeAny') },
    { value: 'all', label: this.transloco.translate<string>('broadcasts.gradeAll') },
    ...this.myGrades().map((grade) => ({
      value: String(grade),
      label: this.transloco.translate<string>('broadcasts.gradeN', { grade }),
    })),
  ]);

  /** "Add plan" — from a card, with its week and grade, or from the footer with this week only. */
  protected add(card: GlanceCard | null): void {
    this.initial.set({
      ...EMPTY_DRAFT,
      kind: 'weekly_plan',
      weekStart: this.thisWeek(),
      // A plan is the week every reader of the department is asked about, so all three by default;
      // she can still take one off in the sheet.
      audience: ['parents', 'teachers', 'coordinators'],
      grade: card?.grade ?? null,
      department: card?.department ?? '',
    });
    this.composing.set(true);
  }

  protected post(body: CreateBroadcastRequest): void {
    this.posting.set(true);
    this.failed.set(false);
    this.api
      .createManagementBroadcast(body)
      .pipe(
        tap(() => {
          this.posting.set(false);
          this.composing.set(false);
          this.posted.set(true);
          this.archive.reload();
          this.thisWeekPlans.reload();
        }),
        catchError(() => {
          this.posting.set(false);
          this.failed.set(true);
          return of(null);
        }),
      )
      .subscribe();
  }

  protected exportCsv(): void {
    saveFile(
      planCsv(this.weeks(), {
        title: this.transloco.translate<string>('plans.csv.title'),
        week: this.transloco.translate<string>('plans.csv.week'),
        grade: this.transloco.translate<string>('plans.csv.grade'),
        readBy: this.transloco.translate<string>('plans.csv.readBy'),
        allGrades: this.transloco.translate<string>('broadcasts.gradeAll'),
      }),
      `weekly-plans-${this.from()}-${this.to()}.csv`,
      'text/csv;charset=utf-8',
    );
  }

  protected weekLabel(week: string): string {
    return this.transloco.translate<string>('broadcasts.weekOf', {
      date: new Date(`${week}T00:00:00Z`).toLocaleDateString(undefined, {
        timeZone: 'UTC',
        day: 'numeric',
        month: 'short',
        year: 'numeric',
      }),
    });
  }
}

/** `count` weeks before this Sunday, as `YYYY-MM-DD` — the default window's start. */
function weeksBefore(sunday: string, count: number): string {
  const day = new Date(`${sunday}T00:00:00Z`);
  day.setUTCDate(day.getUTCDate() - count * 7);
  return day.toISOString().slice(0, 10);
}
