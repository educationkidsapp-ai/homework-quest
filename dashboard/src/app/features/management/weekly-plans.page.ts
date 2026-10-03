/* hq-flag: announcements — the weekly plan is a broadcast (RM2), so the screen that writes and
   lists them carries the key the whole feature is behind. `screens.ts` puts it on the row; the
   `*hqFeature` block and `notEnabled` below are the answer to a bookmark. */
import { ChangeDetectionStrategy, Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { HttpEventType } from '@angular/common/http';
import { EMPTY, catchError, filter, of, take, tap } from 'rxjs';
import { BroadcastsApi, MediaApi } from '../../api';
import { sundayOf, weekOptions } from '../../core/broadcasts/broadcast.rules';
import {
  type PlanContext,
  type PlanDraft,
  EMPTY_PLAN_DRAFT,
  planRequestOf,
} from '../../core/broadcasts/plan-rules';
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
  AttachmentImageDirective,
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
import { PlanPdfComponent } from '../broadcasts/plan-pdf.component';
import { PlanWeeksComponent } from '../broadcasts/plan-weeks.component';
import { StaffScopeService } from '../coordinator/staff-scope.service';
import { CoordinatorReadFailedComponent } from '../coordinator/read-failed.component';
import { PlanComposeComponent, weekLabel } from './plan-compose.component';

/** How far back the archive is read by default — the server's own default window (MG1). */
const DEFAULT_WEEKS = 12;

/** One card of "this week at a glance": a grade of her department, and the plan it has or has not. */
interface GlanceCard {
  readonly grade: number;
  readonly label: string;
  readonly plan: PlanRow | null;
}

/**
 * **Weekly plans** (MG2b items 3 and 4; reworked by MH2 item 4).
 *
 * MH1 settled what a plan *is*: one grade, one week, one image — and since list 3 (D2) the file may
 * be a **PDF** instead, which every card draws as its name and an Open action (`hq-plan-pdf`). So
 * this screen is three things, in the order she uses them on a Sunday morning:
 *
 * 1. **This week at a glance** — one card per grade of her department, showing the picture that is
 *    posted or an "Add plan" action. There is no all-grades card any more: the server refuses a plan
 *    without a grade ("A weekly plan is for one grade"), so a card that offered one was a card that
 *    answered a red band.
 * 2. **The composer** (`plan-compose.component.ts`): grade, week, image, with the two rules
 *    `MediaController` enforces checked before the bytes leave the browser.
 * 3. **The archive** — `GET /management/weekly-plans`, newest week first, each row its thumbnail,
 *    with `readBy` and a CSV of the list.
 *
 * **Every picture is fetched with the bearer.** `GET /media/attachments/{id}` requires one and the
 * `url` the DTO carries is the server's own absolute host, so an `<img src>` pointed at it answers
 * 401 on every card: `AttachmentImageDirective` reads the bytes through the generated client and
 * paints them as a `data:` URL, which is what the shipped CSP allows.
 *
 * **The glance has its own read.** It was a slice of the filtered archive, and that was a defect: a
 * grade filter or an end date in the past made a card say "No plan yet" for a week that had one, and
 * "Add plan" there *replaces* the plan it could not see. So this week is asked for on its own — one
 * week, no grade — and the archive's filters touch the list below and nothing else.
 */
@Component({
  selector: 'hq-weekly-plans-page',
  imports: [
    AttachmentImageDirective,
    BandComponent,
    ButtonComponent,
    CanDirective,
    CardComponent,
    CoordinatorReadFailedComponent,
    EmptyStateComponent,
    FeatureDirective,
    InputComponent,
    PageComponent,
    PlanComposeComponent,
    PlanPdfComponent,
    PlanWeeksComponent,
    SelectComponent,
    SkeletonComponent,
    ToastComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-page [title]="'nav.weeklyPlans' | transloco" [subtitle]="'plans.subtitle' | transloco">
      <hq-button page-actions *hqCan="'management.broadcast'" (pressed)="add(null)">
        {{ 'plans.add' | transloco }}
      </hq-button>

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
            {{ 'plans.postFailed' | transloco }}
          </hq-band>
        }

        <hq-card [title]="'plans.thisWeek' | transloco">
          @if (singleCard(); as card) {
            <hq-button
              card-actions
              *hqCan="'management.broadcast'"
              variant="secondary"
              (pressed)="add(card)"
            >
              {{ (card.plan ? 'plans.replace' : 'plans.add') | transloco }}
            </hq-button>
          }

          <p class="hq-muted">{{ weekLabel(thisWeek()) }}</p>
          <!-- Until this week's own read has landed, no card may say "no plan yet": that sentence
               with a button beside it is an offer to replace a plan nobody has seen. -->
          @if (upcomingPlans.isLoading()) {
            <hq-skeleton [loading]="true" [lines]="2" [label]="'ui.loading' | transloco" />
          } @else if (upcomingPlans.error()) {
            <hq-coordinator-read-failed (retry)="upcomingPlans.reload()" />
          } @else if (glance().length === 0) {
            <hq-empty-state [message]="'plans.noGrades' | transloco" />
          } @else {
            <div class="wp__glance">
              @for (card of glance(); track card.grade) {
                <div class="wp__card">
                  <div class="wp__card-header">
                    <p class="wp__grade">{{ card.label }}</p>
                    @if (glance().length > 1) {
                      <hq-button *hqCan="'management.broadcast'" variant="secondary" (pressed)="add(card)">
                        {{ (card.plan ? 'plans.replace' : 'plans.add') | transloco }}
                      </hq-button>
                    }
                  </div>
                  @if (card.plan; as plan) {
                    <!-- Eager: these few are above the fold and are the Sunday-morning question.
                         The archive below them loads a row when it is scrolled to. -->
                    <div class="wp__preview">
                      @if (plan.pdf) {
                        <hq-plan-pdf
                          [attachmentId]="plan.attachmentId"
                          [name]="plan.attachmentName"
                          [label]="altOf(plan)"
                        />
                      } @else {
                        <img
                          class="wp__thumb"
                          [eager]="true"
                          [hqAttachmentImage]="plan.attachmentId"
                          [alt]="altOf(plan)"
                        />
                      }
                    </div>
                  } @else {
                    <div class="wp__empty-slot">
                      <svg class="wp__empty-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" width="28" height="28" aria-hidden="true">
                        <path stroke-linecap="round" stroke-linejoin="round" d="M19.5 14.25v-2.625a3.375 3.375 0 00-3.375-3.375h-1.5A1.125 1.125 0 0113.5 7.125v-1.5a3.375 3.375 0 00-3.375-3.375H8.25m2.25 0H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 00-9-9z" />
                      </svg>
                      <p class="hq-muted">{{ 'plans.none' | transloco }}</p>
                    </div>
                  }
                </div>
              }
            </div>
          }
        </hq-card>

        <hq-card [title]="'plans.archive' | transloco">
          <hq-button
            card-actions
            variant="secondary"
            [disabled]="weeks().length === 0"
            (pressed)="exportCsv()"
          >
            {{ 'plans.export' | transloco }}
          </hq-button>

          <div class="mg-filters">
            <hq-select
              class="wp-grade-filter"
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
            <hq-plan-weeks [weeks]="weeks()" [open]="openPlan()" />
          }
        </hq-card>

        <hq-plan-compose
          [(open)]="composing"
          [ctx]="ctx()"
          [today]="today()"
          [initial]="initial()"
          [posting]="posting()"
          [progress]="progress()"
          [taken]="taken()"
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
    hq-card {
      display: block;
      margin-block-end: 20px;
    }

    .mg-filters {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      gap: var(--hq-space-16);
      margin-block-end: var(--hq-space-16);

      hq-select,
      hq-input {
        flex: 0 1 auto;
        min-inline-size: 160px;
        margin: 0;
      }
    }

    .wp__glance {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(280px, 420px));
      gap: var(--hq-space-16);
      margin-block-start: var(--hq-space-8);
    }

    .wp__card {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-12);
      padding: var(--hq-space-16);
      background: var(--hq-color-surface);
      border: var(--hq-size-rule-thin) solid var(--hq-color-divider);
      border-radius: var(--hq-radius-card);
      box-shadow: 0 1px 3px 0 rgba(0, 0, 0, 0.04), 0 1px 2px -1px rgba(0, 0, 0, 0.03);
      transition: box-shadow 0.2s ease, border-color 0.2s ease, transform 0.2s ease;

      &:hover {
        border-color: color-mix(in srgb, var(--hq-color-ink) 25%, var(--hq-color-divider));
        box-shadow: 0 6px 16px -4px rgba(0, 0, 0, 0.08);
      }
    }

    .wp__card-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--hq-space-12);
    }

    .wp__grade {
      margin: 0;
      font-size: var(--hq-text-size-body);
      font-weight: var(--hq-text-weight-semibold);
      color: var(--hq-color-ink);
    }

    .wp__preview {
      width: 100%;
      border-radius: var(--hq-radius-control);
      overflow: hidden;
      background: var(--hq-color-surface-sunken);
      border: var(--hq-size-rule-thin) solid var(--hq-color-divider);

      &:has(hq-plan-pdf) {
        background: transparent;
        border: none;
      }
    }

    .wp__thumb {
      display: block;
      width: 100%;
      max-height: 240px;
      aspect-ratio: 16 / 10;
      object-fit: contain;
      background: var(--hq-color-surface-sunken);
      transition: transform 0.2s ease;
    }

    .wp__card:hover .wp__thumb {
      transform: scale(1.02);
    }

    .wp__empty-slot {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: var(--hq-space-8);
      min-height: 180px;
      padding: var(--hq-space-24);
      background: var(--hq-color-surface-sunken);
      border: 1.5px dashed var(--hq-color-divider);
      border-radius: var(--hq-radius-control);
      text-align: center;

      p {
        margin: 0;
        font-size: var(--hq-text-size-sm);
      }
    }

    .wp__empty-icon {
      color: var(--hq-color-ink-soft);
      opacity: 0.6;
    }
  `,
})
export class WeeklyPlansPage {
  private readonly api = inject(BroadcastsApi);
  private readonly media = inject(MediaApi);
  private readonly flags = inject(FlagService);
  private readonly transloco = inject(TranslocoService);
  private readonly platform = inject(PlatformService);
  private readonly scope = inject(StaffScopeService);

  protected readonly enabled = computed(() => this.flags.isOn(FLAGS.announcements));

  protected readonly composing = signal(false);
  protected readonly posting = signal(false);
  protected readonly progress = signal(0);
  protected readonly posted = signal(false);
  protected readonly failed = signal(false);
  protected readonly initial = signal<PlanDraft>(EMPTY_PLAN_DRAFT);
  /**
   * The picture that is already on the server, and which file it was.
   *
   * So a post that failed *after* the upload succeeded can be retried without sending the bytes
   * again — and without leaving the first attachment behind with nothing pointing at it.
   */
  private readonly uploaded = signal<{ readonly file: File; readonly id: string } | null>(null);

  /**
   * MH2 item 6: `?open=<id>`, where a `broadcast.posted` for a plan sends her. The Announcements
   * screen forwards it here once it has seen the row's kind, because the notification carries none.
   */
  private readonly query = toSignal(inject(ActivatedRoute).queryParamMap, { requireSync: true });
  protected readonly openPlan = computed(() => this.query().get('open') ?? '');

  /** Today, and so this week's Sunday, in the **school's** zone — the week the server snaps to. */
  protected readonly today = computed(() =>
    new Intl.DateTimeFormat('en-CA', { timeZone: this.platform.timezone() }).format(new Date()),
  );
  protected readonly thisWeek = computed(() => sundayOf(this.today()));

  protected readonly gradeChoice = signal<string>('any');
  protected readonly from = linkedSignal(() => weeksBefore(this.thisWeek(), DEFAULT_WEEKS - 1));
  protected readonly to = linkedSignal(() => this.thisWeek());

  protected readonly backwards = computed(() => this.from() !== '' && this.from() > this.to());

  /** The Sundays the composer offers — this week and the next four (`weekOptions`). */
  private readonly composableWeeks = computed(() => weekOptions(this.today()));

  /**
   * **The weeks she can post for, unfiltered** — the read the cards and the replace warning are
   * built from, and nothing else.
   *
   * The glance used to be a slice of the filtered archive, so picking grade 1 or an end date in the
   * past made every card outside the filter say "No plan yet" for a week that already had a plan —
   * and "Add plan" on that card is a *replace* (`BroadcastService.replacePlan` deletes the previous
   * row, its read marks and its bell rows). A screen may not offer a destructive action because of a
   * filter she set to look at something else, so these weeks have their own request.
   *
   * **It covers every selectable week, not only this one** (review blocker 2). The week select
   * offers five Sundays, so a plan already posted for one of the four future ones has to be known
   * here too — otherwise posting a corrected image for next week says "Post" with no red band and
   * deletes the row anyway.
   */
  protected readonly upcomingPlans = rxResource({
    params: () => {
      if (!this.enabled()) return undefined;
      const weeks = this.composableWeeks();
      return { from: weeks[0] ?? this.thisWeek(), to: weeks.at(-1) ?? this.thisWeek() };
    },
    stream: ({ params }) => this.api.managementWeeklyPlans(params.from, params.to, undefined),
  });

  private readonly upcomingWeeks = computed(() => planWeeks(this.upcomingPlans.value()));

  /**
   * The plans posted for **this** week, whatever the archive below is filtered to.
   *
   * Strictly the week that matches: the read now spans five of them, so falling back to the first
   * week in the answer — which it used to, when `from` and `to` were the same Sunday — would draw
   * next week's plans on this week's cards.
   */
  private readonly postedThisWeek = computed<readonly PlanRow[]>(
    () => this.upcomingWeeks().find((week) => week.weekStart === this.thisWeek())?.rows ?? [],
  );

  /**
   * The archive.
   *
   * `grade` goes to the server when she names one — her route takes it, and asking for one grade of
   * two years is a smaller answer than one page of 200 plans filtered in the browser. Both then run
   * through `filterWeeks`, which is the only thing the list and the export read.
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

  private readonly filter = computed<GradeFilter>(() =>
    this.gradeChoice() === 'any' ? 'any' : Number(this.gradeChoice()),
  );

  /** Her departments, as the scope header spells them (`GET /management/me`). */
  private readonly departments = computed<readonly string[]>(() =>
    this.scope
      .scopes()
      .map((chip) => chip.curriculum)
      .filter((curriculum): curriculum is string => curriculum !== null),
  );

  /** The grades she manages — the cards' source, the composer's, and the filter's. */
  private readonly myGrades = computed<readonly number[]>(() =>
    [...new Set(this.scope.classes().map((row) => row.grade))].sort((a, b) => a - b),
  );

  protected readonly ctx = computed<PlanContext>(() => ({
    grades: this.myGrades(),
    departments: this.departments(),
  }));

  /**
   * This week's cards: one per grade she manages, and no all-grades card.
   *
   * MH1 made `grade` required on a plan, so "the plan for all grades" is not a row the server will
   * write — what the owner's "for all grades" asked for is a plan *in every grade*, which is what a
   * card per grade is.
   */
  protected readonly glance = computed<readonly GlanceCard[]>(() => {
    const posted = this.postedThisWeek();
    return this.myGrades().map((grade) => ({
      grade,
      label: this.transloco.translate<string>('broadcasts.gradeN', { grade }),
      plan: posted.find((row) => row.grade === grade) ?? null,
    }));
  });

  protected readonly singleCard = computed<GlanceCard | null>(() => {
    const list = this.glance();
    return list.length === 1 ? (list[0] ?? null) : null;
  });

  /**
   * The grade/week pairs that already have a plan — what makes the sheet say "Replace".
   *
   * Every week the select offers, not only this one: she posts next week's plan on Thursday and a
   * corrected image on Friday, and the second one must confirm what it is about to delete.
   */
  protected readonly taken = computed<readonly string[]>(() =>
    this.upcomingWeeks()
      .flatMap((week) => week.rows)
      .filter((row) => row.grade !== null)
      .map((row) => `${row.grade}|${row.weekStart}`),
  );

  /** The archive filter's options, from **her grades** rather than from the server-filtered answer. */
  protected readonly gradeFilters = computed<readonly SelectOption[]>(() => [
    { value: 'any', label: this.transloco.translate<string>('plans.gradeAny') },
    ...this.myGrades().map((grade) => ({
      value: String(grade),
      label: this.transloco.translate<string>('broadcasts.gradeN', { grade }),
    })),
  ]);

  /** "Add plan" — from a grade's card, with that grade and this week already filled in. */
  protected add(card: GlanceCard | null): void {
    this.initial.set({
      ...EMPTY_PLAN_DRAFT,
      weekStart: this.thisWeek(),
      grade: card?.grade ?? null,
    });
    this.progress.set(0);
    // A new sheet is a new picture: the id from the last attempt belongs to the file she picked then.
    this.uploaded.set(null);
    this.composing.set(true);
  }

  /**
   * Post: **upload the image, then broadcast its id.**
   *
   * Two requests, in that order, because `attachmentId` is required on the plan and only the upload
   * knows it. `reportProgress` on the first one, so a photograph on a school's uplink is a bar rather
   * than a frozen sheet; a failure at either step leaves the sheet open with the red band above it,
   * which is the rule for a write that did not happen — nothing was posted, so there is nothing
   * to undo.
   *
   * **The upload is not repeated on a retry.** If the broadcast failed after the bytes were already
   * accepted, pressing Post again reuses the `attachmentId` the server gave: re-uploading five
   * megabytes she has already sent is a slow retry that also orphans the first attachment (review,
   * non-blocking 1). The id is kept against the exact `File` object, so picking a different picture
   * uploads again.
   */
  protected post(draft: PlanDraft): void {
    const file = draft.file;
    if (file === null) return;
    this.posting.set(true);
    this.failed.set(false);

    const already = this.uploaded();
    if (already !== null && already.file === file) {
      this.progress.set(100);
      this.write(draft, already.id);
      return;
    }

    this.progress.set(0);
    this.media
      .uploadAttachment(file, 'events', true)
      .pipe(
        tap((event) => {
          if (event.type === HttpEventType.UploadProgress && event.total) {
            this.progress.set(Math.round((event.loaded / event.total) * 100));
          }
        }),
        filter((event) => event.type === HttpEventType.Response),
        take(1),
        catchError(() => {
          this.stall();
          return EMPTY;
        }),
      )
      .subscribe((event) => {
        // No cast: `filter` on `event.type` narrows the event to the response, so `body` is typed.
        const id = event.body?.id ?? '';
        // An accepted upload that named no id is nothing to post. Before this the stream simply
        // ended and the sheet sat on a spinner for ever (review, non-blocking 2).
        if (id === '') {
          this.stall();
          return;
        }
        this.uploaded.set({ file, id });
        this.write(draft, id);
      });
  }

  /** The second half: the broadcast itself, from an attachment id that is already on the server. */
  private write(draft: PlanDraft, attachmentId: string): void {
    this.api
      .createManagementBroadcast(planRequestOf(draft, attachmentId))
      .pipe(
        tap(() => {
          this.posting.set(false);
          this.composing.set(false);
          this.posted.set(true);
          // Posted, so the id has been spent: a later plan must upload its own picture.
          this.uploaded.set(null);
          this.archive.reload();
          this.upcomingPlans.reload();
        }),
        catchError(() => {
          this.stall();
          return of(null);
        }),
      )
      .subscribe();
  }

  /** Nothing was posted: the band says so and the sheet stays open, with her draft in it. */
  private stall(): void {
    this.posting.set(false);
    this.failed.set(true);
  }

  protected exportCsv(): void {
    saveFile(
      planCsv(this.weeks(), {
        image: this.transloco.translate<string>('plans.csv.image'),
        week: this.transloco.translate<string>('plans.csv.week'),
        grade: this.transloco.translate<string>('plans.csv.grade'),
        readBy: this.transloco.translate<string>('plans.csv.readBy'),
      }),
      `weekly-plans-${this.from()}-${this.to()}.csv`,
      'text/csv;charset=utf-8',
    );
  }

  /** A11y: what the picture is, said in words — "Weekly plan · Grade 3 · Week of 12 Oct 2026". */
  protected altOf(row: PlanRow): string {
    return this.transloco.translate<string>('plans.imageAlt', {
      grade: row.grade ?? '',
      week: weekLabel(this.transloco, row.weekStart),
    });
  }

  protected weekLabel(week: string): string {
    return weekLabel(this.transloco, week);
  }
}

/** `count` weeks before this Sunday, as `YYYY-MM-DD` — the default window's start. */
function weeksBefore(sunday: string, count: number): string {
  const day = new Date(`${sunday}T00:00:00Z`);
  day.setUTCDate(day.getUTCDate() - count * 7);
  return day.toISOString().slice(0, 10);
}
