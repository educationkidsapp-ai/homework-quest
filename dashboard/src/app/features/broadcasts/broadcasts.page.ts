/* hq-flag: announcements — the whole feature, composer and feed alike, is behind the key the
   `announcements` feature it supersedes already carried (RM2). `screens.ts` puts the flag on
   every `broadcasts` row; `*hqFeature` and `notEnabled` below are the answer to a bookmark. */
import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DOCUMENT,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { catchError, of, tap } from 'rxjs';
import {
  type BroadcastFeed,
  type BroadcastView,
  type CreateBroadcastRequest,
  BroadcastsApi,
} from '../../api';
import { type BroadcastDraft, type ComposeContext, EMPTY_DRAFT } from '../../core/broadcasts/broadcast.rules';
import { planWeeks as weeksOf, readerBodies } from '../../core/broadcasts/plan-archive';
import { StaffAreaService } from '../../core/auth/staff-area';
import { FeatureDirective } from '../../core/flags/feature.directive';
import { activeLang } from '../../core/i18n/active-lang';
import { FLAGS, FlagService } from '../../core/flags/flag.service';
import { CanDirective } from '../../core/permissions/can.directive';
import { PlatformService } from '../../core/platform/platform.service';
import {
  BandComponent,
  ButtonComponent,
  CardComponent,
  EmptyStateComponent,
  PageComponent,
  SkeletonComponent,
  type Tab,
  TabsComponent,
  ToastComponent,
} from '../../ui';
import { BroadcastComposeComponent } from './compose-sheet.component';
import { PlanWeeksComponent } from './plan-weeks.component';
import { StaffScopeService } from '../coordinator/staff-scope.service';

type Panel = 'received' | 'posted' | 'plans';

/**
 * Broadcasts (RM3b, DR6): the weekly plan, the announcements and the events of a school, on one
 * screen for all three staff roles.
 *
 * **One component, three areas**, the way every shared screen in this dashboard works
 * (`core/auth/staff-area.ts`). The *feed* is the same call for everyone — `GET /me/broadcasts`
 * answers what this reader is an audience of, evaluated against her scope as it is now — so a
 * teacher, a coordinator and a manager read it with one list. What differs is the footer: a
 * coordinator and a manager also write, and the sheet that lets them is the only thing on the
 * screen keyed by role (`core/broadcasts/broadcast.rules.ts` holds the rules the server enforces).
 *
 * The week's plan is **pinned and opened**: it is the one row a teacher comes here for, it is
 * replaced rather than added to each week, and a replacement arrives unread — so it is also the
 * row most likely to be the reason the bell rang.
 */
@Component({
  selector: 'hq-broadcasts-page',
  imports: [
    BandComponent,
    BroadcastComposeComponent,
    ButtonComponent,
    CanDirective,
    CardComponent,
    DatePipe,
    EmptyStateComponent,
    FeatureDirective,
    PageComponent,
    PlanWeeksComponent,
    SkeletonComponent,
    TabsComponent,
    ToastComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-page [title]="'nav.broadcasts' | transloco" [subtitle]="'broadcasts.subtitle' | transloco">
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

        @if (tabs().length > 1) {
          <hq-tabs [tabs]="tabs()" [(selected)]="panel" [label]="'broadcasts.panels' | transloco" />
        }

        @if (panel() === 'plans') {
          <!-- MG2b item 2: the read-only archive, from GET /me/weekly-plans — past weeks and
               expired plans included, which the feed above deliberately drops. -->
          @if (plans.isLoading()) {
            <hq-skeleton [loading]="true" [lines]="4" [label]="'ui.loading' | transloco" />
          } @else if (planWeeks().length === 0) {
            <hq-empty-state
              [message]="'plans.empty' | transloco"
              [detail]="'plans.emptyReaderHint' | transloco"
            />
          } @else {
            <hq-plan-weeks [weeks]="planWeeks()" />
          }
        } @else if (loading()) {
          <hq-skeleton [loading]="true" [lines]="4" [label]="'ui.loading' | transloco" />
        } @else if (rows().length === 0) {
          <hq-empty-state
            [message]="(panel() === 'posted' ? 'broadcasts.noPosts' : 'broadcasts.empty') | transloco"
            [detail]="(panel() === 'posted' ? 'broadcasts.noPostsHint' : 'broadcasts.emptyHint') | transloco"
          />
        } @else {
          @for (row of rows(); track row.id) {
            <!-- MG2a: the anchor a notification's ?open= scrolls to, drawn open on arrival. -->
            <hq-card [attr.id]="domIdOf(row)" [eyebrow]="eyebrowOf(row)">
              <button
                type="button"
                class="bc__head"
                [class.is-unread]="row.read === false"
                [attr.aria-expanded]="isOpen(row)"
                (click)="toggle(row)"
              >
                <span class="bc__title">{{ row.title || ('broadcasts.untitled' | transloco) }}</span>
                @if (row.read === false) {
                  <span class="bc__new">{{ 'broadcasts.new' | transloco }}</span>
                }
              </button>
              @if (isOpen(row)) {
                <!-- The reader's own language first: an Arabic reader who has to scroll past the
                     English to find hers is reading somebody else's copy of the same note. -->
                @for (body of bodiesOf(row); track body.dir) {
                  <p class="bc__body" [dir]="body.dir">{{ body.text }}</p>
                }
                <p class="hq-muted">{{ authorOf(row) }}</p>
                @if (row.attachment; as file) {
                  <a class="bc__file" [href]="file.url" target="_blank" rel="noopener">
                    {{ file.name || ('broadcasts.attachment' | transloco) }}
                  </a>
                }
                @if (row.expiresAt; as until) {
                  <p class="hq-muted">
                    {{ 'broadcasts.until' | transloco: { date: (until | date: 'mediumDate') } }}
                  </p>
                }
              }
            </hq-card>
          }
        }

        @if (composer() !== null) {
          <div page-footer>
            <hq-button *hqCan="postKey()" (pressed)="composing.set(true)">
              {{ 'broadcasts.compose' | transloco }}
            </hq-button>
          </div>

          <hq-broadcast-compose
            [(open)]="composing"
            [ctx]="ctx()"
            [sections]="composableSections()"
            [initial]="blank()"
            [posting]="posting()"
            (submitted)="post($event)"
          />
        }

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
    .bc__head {
      display: flex;
      align-items: center;
      gap: var(--hq-space-2);
      width: 100%;
      padding: 0;
      border: 0;
      background: none;
      color: inherit;
      font: inherit;
      text-align: start;
      cursor: pointer;
    }

    .bc__title {
      font-weight: var(--hq-font-label-weight);
    }

    .bc__head.is-unread .bc__title {
      color: var(--hq-accent);
    }

    .bc__new {
      padding: 0 var(--hq-space-2);
      background: var(--hq-accent);
      color: var(--hq-accent-ink);
      font-size: var(--hq-font-meta-size);
    }

    .bc__body {
      white-space: pre-wrap;
    }

    .bc__file {
      color: var(--hq-accent);
    }

    .bc__set {
      margin: 0;
      padding: 0;
      border: 0;
    }

    .bc__set legend {
      padding: 0;
      font-weight: var(--hq-font-label-weight);
    }

    .bc__error {
      color: var(--hq-accent);
    }
  `,
})
export class BroadcastsPage {
  private readonly api = inject(BroadcastsApi);
  private readonly flags = inject(FlagService);
  private readonly transloco = inject(TranslocoService);
  private readonly platform = inject(PlatformService);
  private readonly area = inject(StaffAreaService);
  private readonly lang = activeLang();
  protected readonly scope = inject(StaffScopeService);

  protected readonly enabled = computed(() => this.flags.isOn(FLAGS.announcements));

  /** `null` for a teacher: she reads the feed and writes nothing (DR6). */
  protected readonly composer = computed(() => {
    const area = this.area.area();
    if (area === 'management') return 'manager' as const;
    return area === 'coordinator' ? ('coordinator' as const) : null;
  });

  protected readonly postKey = computed(() =>
    this.composer() === 'manager' ? 'management.broadcast' : 'coordinator.broadcast',
  );

  protected readonly panel = signal<Panel>('received');
  protected readonly composing = signal(false);
  protected readonly posting = signal(false);
  protected readonly posted = signal(false);
  protected readonly failed = signal(false);
  /**
   * The draft the sheet opens on. A constant for this screen — she starts from blank here, while
   * the Weekly plans screen prefills the week and the grade of the card she pressed.
   */
  protected readonly blank = computed<BroadcastDraft>(() => EMPTY_DRAFT);
  private readonly opened = signal<readonly string[]>([]);
  /** The plans this visit has already drawn open — deliberately not a signal (see the effect). */
  private readonly autoOpened = new Set<string>();
  private readonly doc = inject(DOCUMENT);
  /**
   * The query **as a signal**, not `route.snapshot`.
   *
   * The bell is on every screen, so clicking a `broadcast.posted` row *while already on
   * Broadcasts* is a query-param-only navigation: Angular reuses the component, the snapshot the
   * constructor read is never re-read, and the row the notification named would not open, scroll
   * or go read. `requireSync` because `queryParamMap` emits the current query on subscribe.
   */
  private readonly query = toSignal(inject(ActivatedRoute).queryParamMap, { requireSync: true });

  /** Today in the **school's** timezone: `en-CA` is the one locale that formats as `YYYY-MM-DD`. */
  protected readonly today = computed(() =>
    new Intl.DateTimeFormat('en-CA', { timeZone: this.platform.timezone() }).format(new Date()),
  );

  private readonly feed = rxResource<BroadcastFeed, boolean>({
    params: () => this.enabled(),
    stream: ({ params }) => (params ? this.api.myBroadcasts() : of({ items: [], unread: 0 })),
    defaultValue: { items: [], unread: 0 },
  });

  /** Her own posts, expired rows included — the composer's list, not a feed (`GET …/broadcasts`). */
  private readonly posts = rxResource<readonly BroadcastView[], 'manager' | 'coordinator' | null>({
    params: () => (this.enabled() ? this.composer() : null),
    stream: ({ params }) =>
      params === 'manager'
        ? this.api.managementBroadcasts()
        : params === 'coordinator'
          ? this.api.coordinatorBroadcasts()
          : of([]),
    defaultValue: [],
  });

  /**
   * MG2b: `GET /me/weekly-plans` — the plans whose audience includes her, newest week first, the
   * server's default twelve-week window. Read once the tab is opened, not on arrival: a teacher
   * comes here for this week's plan, and the twelve weeks behind it are a second question.
   */
  protected readonly plans = rxResource({
    params: () => (this.enabled() && this.panel() === 'plans' ? true : undefined),
    stream: () => this.api.myWeeklyPlans(),
  });

  protected readonly planWeeks = computed(() => weeksOf(this.plans.value()));

  protected readonly loading = computed(() =>
    this.panel() === 'posted' ? this.posts.isLoading() : this.feed.isLoading(),
  );

  /**
   * Received, Weekly plans, and — for the two roles that write — what she posted.
   *
   * MG2b item 2 put the plan archive here rather than on a rail row of its own: a teacher opens
   * Broadcasts for the week's plan already (it is the pinned row), and "the weeks before this one"
   * is the same screen one tab over. A manager has her own screen, which also composes, so hers
   * is the only rail row the feature adds.
   */
  protected readonly tabs = computed<readonly Tab<Panel>[]>(() => [
    {
      id: 'received',
      label: this.transloco.translate<string>('broadcasts.received'),
      badge: this.feed.value().unread ?? 0,
    },
    { id: 'plans', label: this.transloco.translate<string>('plans.tab') },
    ...(this.composer() === null
      ? []
      : [{ id: 'posted' as const, label: this.transloco.translate<string>('broadcasts.posted_') }]),
  ]);

  /**
   * The feed, with the week's plan first.
   *
   * There is one plan per week per department, so "the pinned row" is the newest `weekly_plan`
   * and the rest keep the server's order (newest first). Sorting the whole list by kind would
   * have buried this morning's event under a plan posted on Sunday.
   */
  protected readonly rows = computed<readonly BroadcastView[]>(() => {
    if (this.panel() === 'posted') return this.posts.value();
    const items = this.feed.value().items ?? [];
    const plan = items.find((row) => row.kind === 'weekly_plan');
    return plan === undefined ? items : [plan, ...items.filter((row) => row !== plan)];
  });

  protected readonly departments = computed<readonly string[]>(() =>
    this.scope
      .scopes()
      .map((chip) => chip.curriculum)
      .filter((curriculum): curriculum is string => curriculum !== null),
  );

  /** Every class of her scope; the sheet narrows them to the department she picks inside it. */
  protected readonly composableSections = computed(() => this.scope.classes());

  protected readonly ctx = computed<ComposeContext>(() => ({
    role: this.composer() ?? 'coordinator',
    departments: this.departments(),
    sections: this.scope.classes(),
    today: this.today(),
    zone: this.platform.timezone(),
  }));

  constructor() {
    /*
     * The week's plan is drawn **open**, so arriving on the screen *is* opening it: the first time
     * it comes back it joins `opened` and is marked read like any row she had clicked.
     *
     * The review found the two halves of that out of step — the row was pinned open by its kind,
     * so it could never be collapsed either, and the unread badge went on counting a plan she was
     * already looking at behind a header that appeared to do nothing.
     */
    effect(() => {
      const plan = (this.feed.value().items ?? []).find((row) => row.kind === 'weekly_plan');
      untracked(() => {
        const id = plan?.id ?? '';
        // `autoOpened` and not `opened()`: once, per plan. Reading the open rows here would have
        // made collapsing the plan re-open it, since the effect would run again on its own write.
        if (id === '' || this.autoOpened.has(id)) return;
        this.autoOpened.add(id);
        this.opened.update((ids) => [...ids, id]);
        if (plan?.read === false) this.markRead(id);
      });
    });

    /*
     * MG2a: `?open=<id>` — where a `broadcast.posted` notification sends her
     * (`core/notifications/notification-target.ts`). The row is drawn open, marked read like any
     * row she had clicked, and scrolled to, because a twelve-row feed that merely *contains* the
     * one she was told about has not answered the click she made on the bell.
     *
     * A row that is not in her feed is left alone rather than reported: a weekly plan replaced
     * while the bell sat unread is exactly that case, and the feed above it is still the answer.
     */
    effect(() => {
      const wanted = this.query().get('open') ?? '';
      const rows = this.feed.value().items ?? [];
      untracked(() => {
        if (wanted === '' || this.autoOpened.has(wanted)) return;
        const row = rows.find((candidate) => candidate.id === wanted);
        if (row === undefined) return;
        this.autoOpened.add(wanted);
        this.panel.set('received');
        this.opened.update((ids) => (ids.includes(wanted) ? ids : [...ids, wanted]));
        if (row.read === false) this.markRead(wanted);
        // After the row has been drawn open: the element does not exist until it has been.
        setTimeout(() => this.doc.getElementById(`bc-${wanted}`)?.scrollIntoView({ block: 'start' }));
      });
    });
  }

  /** The anchor `?open=` scrolls to. */
  protected domIdOf(row: BroadcastView): string | null {
    return row.id ? `bc-${row.id}` : null;
  }

  protected isOpen(row: BroadcastView): boolean {
    return this.opened().includes(row.id ?? '');
  }

  /** The two bodies a row may carry, the reader's language first (`core/broadcasts/plan-archive.ts`). */
  protected bodiesOf(row: BroadcastView) {
    return readerBodies(row, this.lang());
  }

  /**
   * Opening a row is what marks it read — not arriving on the screen.
   *
   * A feed of twelve rows that all go read because she glanced at the page is a bell that stops
   * ringing for things nobody looked at. The week's plan is the one exception, and the constructor
   * above is where it is made one.
   */
  protected toggle(row: BroadcastView): void {
    const id = row.id ?? '';
    if (id === '') return;
    const open = this.opened().includes(id);
    this.opened.update((ids) => (open ? ids.filter((other) => other !== id) : [...ids, id]));
    if (!open && row.read === false) this.markRead(id);
  }

  private markRead(id: string): void {
    this.api
      .markMyBroadcastRead(id)
      .pipe(
        tap(() => this.feed.reload()),
        catchError(() => of(null)),
      )
      .subscribe();
  }

  protected eyebrowOf(row: BroadcastView): string {
    const kind = this.transloco.translate<string>(`broadcasts.kinds.${row.kind}`);
    const when = row.weekStart
      ? this.weekLabel(row.weekStart)
      : row.createdAt === undefined
        ? ''
        : new Date(row.createdAt).toLocaleDateString();
    return [kind, when].filter((part) => part !== '').join(' · ');
  }

  /** Who wrote it, in her own words: the role, her name, and the scope the row carries. */
  protected authorOf(row: BroadcastView): string {
    const role = row.authorRole ? this.transloco.translate<string>(`role.${row.authorRole}`) : '';
    const track = row.curriculum ? this.transloco.translate<string>(`curriculum.${row.curriculum}`) : '';
    return this.transloco.translate<string>('broadcasts.by', {
      who: [row.authorName, role].filter((part) => part).join(' · '),
      scope: [row.subject, track].filter((part) => part).join(' · '),
    });
  }

  private weekLabel(week: string): string {
    return this.transloco.translate<string>('broadcasts.weekOf', {
      date: new Date(`${week}T00:00:00Z`).toLocaleDateString(undefined, {
        timeZone: 'UTC',
        day: 'numeric',
        month: 'short',
      }),
    });
  }

  protected post(body: CreateBroadcastRequest): void {
    const role = this.composer();
    if (role === null) return;
    this.posting.set(true);
    this.failed.set(false);
    (role === 'manager'
      ? this.api.createManagementBroadcast(body)
      : this.api.createCoordinatorBroadcast(body)
    )
      .pipe(
        tap(() => {
          this.posting.set(false);
          this.composing.set(false);
          this.posted.set(true);
          this.posts.reload();
          this.feed.reload();
        }),
        catchError(() => {
          this.posting.set(false);
          this.failed.set(true);
          return of(null);
        }),
      )
      .subscribe();
  }
}
