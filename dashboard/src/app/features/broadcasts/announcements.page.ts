/* hq-flag: announcements — the whole feature, composer and feed alike, is behind the key the
   `announcements` feature it supersedes already carried (RM2). `screens.ts` puts the flag on
   every `announcements` row; `*hqFeature` and `notEnabled` below are the answer to a bookmark. */
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
import { ActivatedRoute, Router } from '@angular/router';
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
 * **Announcements** (RM3b, DR6; renamed and narrowed by MH2 item 5): the announcements and the
 * events of a school, on one screen for all three staff roles.
 *
 * **One component, three areas**, the way every shared screen in this dashboard works
 * (`core/auth/staff-area.ts`). The *feed* is the same call for everyone — `GET /me/broadcasts`
 * answers what this reader is an audience of, evaluated against her scope as it is now — so a
 * teacher, a coordinator and a manager read it with one list. What differs is the footer: a
 * coordinator and a manager also write, and the sheet that lets them is the only thing on the
 * screen keyed by role (`core/broadcasts/broadcast.rules.ts` holds the rules the server enforces).
 *
 * **A weekly plan is not on this feed.** It is a grade, a week and a picture now, and it has a
 * screen of its own: the manager writes it on Weekly plans, and a teacher and a coordinator read
 * it on the tab here. So `weekly_plan` rows are filtered out of the list and out of the composer's
 * kinds — the one screen that showed all three kinds showed none of them well, and a plan drawn as
 * a title with an expanding body was a picture nobody could see.
 *
 * `?open=` still arrives for a plan, because `NotificationService.broadcastLink` writes one link
 * for every kind and `NotificationView` carries no kind to tell them apart. {@link forwardPlan} is
 * where that is sorted out, from the row itself.
 */
@Component({
  selector: 'hq-announcements-page',
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
    <hq-page [title]="'nav.announcements' | transloco" [subtitle]="'announcements.subtitle' | transloco">
      @if (composer() !== null) {
        <hq-button page-actions *hqCan="postKey()" (pressed)="composing.set(true)">
          {{ 'broadcasts.compose' | transloco }}
        </hq-button>
      }

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
          <!-- MG2b item 2, MH2 item 4: the read-only archive, from GET /me/weekly-plans — past
               weeks and expired plans included, each plan the picture the manager uploaded. -->
          @if (plans.isLoading()) {
            <hq-skeleton [loading]="true" [lines]="4" [label]="'ui.loading' | transloco" />
          } @else if (planWeeks().length === 0) {
            <hq-empty-state
              [message]="'plans.empty' | transloco"
              [detail]="'plans.emptyReaderHint' | transloco"
            />
          } @else {
            <hq-plan-weeks [weeks]="planWeeks()" [open]="openPlan()" />
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

    hq-tabs {
      display: block;
      margin-block-end: 20px;
    }

    hq-card {
      display: block;
      margin-block-end: 20px;
    }

    :host ::ng-deep {
      .tabs--underline {
        border-block-end: none;
        gap: var(--hq-space-8);
      }

      .tabs--underline .tabs__tab {
        padding: 8px 16px;
        border-radius: var(--hq-radius-control, 10px);
        border-block-end: none;
        margin-block-end: 0;
        min-block-size: auto;
        transition: all 0.15s ease;

        &[aria-selected='true'] {
          background: var(--hq-gradient-brand-fill);
          color: #ffffff !important;
          box-shadow: 0 4px 12px -2px color-mix(in srgb, var(--hq-color-brand-500) 35%, transparent);

          .tabs__badge {
            background: rgba(255, 255, 255, 0.25);
            color: #ffffff;
          }
        }
      }
    }
  `,
})
export class AnnouncementsPage {
  private readonly api = inject(BroadcastsApi);
  private readonly router = inject(Router);
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

  /** The plan a `broadcast.posted` notification named, so the tab draws it open. */
  protected readonly openPlan = signal('');

  protected readonly loading = computed(() =>
    this.panel() === 'posted' ? this.posts.isLoading() : this.feed.isLoading(),
  );

  /**
   * Received, Weekly plans, and — for the two roles that write — what she posted.
   *
   * MG2b item 2 put the plan archive here rather than on a rail row of its own, and MH2 kept it
   * there: a teacher's plan is a picture she looks at on Sunday, one tab from the notes she was
   * sent. A manager has her own screen, which also composes, so hers is the only rail row the
   * feature adds.
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
   * The feed and her own posts, **announcements and events only**, in the server's order.
   *
   * MH2 item 5: a weekly plan is a picture, and this list draws a title with an expanding body. It
   * used to be pinned at the top of the feed with its attachment as a link nobody could open (the
   * route wants a bearer); it is now the Weekly plans tab beside this one, where it is drawn as the
   * thing it is.
   */
  protected readonly rows = computed<readonly BroadcastView[]>(() =>
    (this.panel() === 'posted' ? this.posts.value() : (this.feed.value().items ?? [])).filter(
      (row) => row.kind !== 'weekly_plan',
    ),
  );

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
     * MG2a: `?open=<id>` — where a `broadcast.posted` notification sends her
     * (`core/notifications/notification-target.ts`). The row is drawn open, marked read like any
     * row she had clicked, and scrolled to, because a twelve-row feed that merely *contains* the
     * one she was told about has not answered the click she made on the bell.
     *
     * MH2 item 6: a **weekly plan** arrives here too, because the server writes one link for every
     * kind and the notification carries no kind to tell them apart. The row itself does, so a plan
     * is forwarded rather than opened here — see {@link forwardPlan}.
     *
     * A row that is not in her feed at all is left alone rather than reported: a weekly plan
     * replaced while the bell sat unread is exactly that case, and the feed is still the answer.
     */
    effect(() => {
      const wanted = this.query().get('open') ?? '';
      const rows = this.feed.value().items ?? [];
      untracked(() => {
        if (wanted === '' || this.autoOpened.has(wanted)) return;
        const row = rows.find((candidate) => candidate.id === wanted);
        if (row === undefined) return;
        this.autoOpened.add(wanted);
        if (row.kind === 'weekly_plan') {
          this.forwardPlan(wanted);
          if (row.read === false) this.markRead(wanted);
          return;
        }
        this.panel.set('received');
        this.opened.update((ids) => (ids.includes(wanted) ? ids : [...ids, wanted]));
        if (row.read === false) this.markRead(wanted);
        // After the row has been drawn open: the element does not exist until it has been.
        setTimeout(() => this.doc.getElementById(`bc-${wanted}`)?.scrollIntoView({ block: 'start' }));
      });
    });
  }

  /**
   * A `broadcast.posted` that turned out to be a weekly plan (MH2 item 6).
   *
   * The manager has a screen for it, so she goes there with the id; a teacher and a coordinator
   * have the tab beside this one, so the tab opens on that plan. Either way the click on the bell
   * ends at the picture rather than at a feed the plan is not even in.
   */
  private forwardPlan(id: string): void {
    if (this.composer() === 'manager') {
      void this.router.navigate(['/management/weekly-plans'], { queryParams: { open: id } });
      return;
    }
    this.openPlan.set(id);
    this.panel.set('plans');
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
