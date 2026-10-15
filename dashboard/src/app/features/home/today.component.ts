import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import {
  type AttendanceRequestView,
  type TodayComplaint,
  type TodayDeviceBlock,
  type TodaySender,
  AttendanceRequestsApi,
  StaffAttendanceSettingsApi,
  TodayApi,
  apiErrorCodeOf,
  apiErrorOf,
} from '../../api';
import { AuthService } from '../../core/auth/auth.service';
import { BandService } from '../../core/band/band.service';
import { activeLang } from '../../core/i18n/active-lang';
import { PermissionService } from '../../core/permissions/permission.service';
import {
  BandComponent,
  ButtonComponent,
  CardComponent,
  SkeletonComponent,
} from '../../ui';

/** The four counts the card offers, in the spec's order, and the `?status=` each link carries. */
const TILES = ['present', 'late', 'notArrived', 'remote'] as const;
type Tile = (typeof TILES)[number];

/** Below two whole days the age is still read in hours — "30 h" says more than "1 day". */
const HOURS_PER_DAY = 24;
const DAYS_THRESHOLD_HOURS = 2 * HOURS_PER_DAY;

/** The spec's "three latest senders". */
const MAX_SENDERS = 3;

/**
 * **"What needs me today?"** (feature 21, `docs/features/21-admin-today-home.md`) — the Admin's
 * and the department manager's Home, led by what needs a decision rather than by counts.
 *
 * One call, `GET /me/today`, and four cards in the spec's order: today's staff attendance, what
 * waits for her decision, the complaints open for more than a day, and her unread messages. The
 * server has already narrowed every section to the caller — the Admin reads the school, a manager
 * her academy, and a request she may not decide or a complaint outside her reach is simply not in
 * the answer — so there is no scoping to do here and nothing to branch on but the two link
 * namespaces (`/admin` and `/management`).
 *
 * **A card with nothing in it is not drawn**, and when all four are empty the screen says so in
 * one line instead of showing four empty frames.
 *
 * **One tap.** The spec's two-tap rule is the point of the screen: Approve, Reject and Reset
 * device are the same calls the dedicated screens make, made from here. The item leaves the list
 * the moment the server has agreed and the whole answer is re-read after it — nothing is written
 * locally before the answer, because a decision that failed must not look taken. A refusal is the
 * red band (§7: never a toast), with 409 in her own words, as on the requests screen.
 */
@Component({
  selector: 'hq-today',
  imports: [BandComponent, ButtonComponent, CardComponent, SkeletonComponent, RouterLink, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (today.isLoading() && !today.hasValue()) {
      <hq-skeleton [loading]="true" [lines]="4" [label]="'today.loading' | transloco" />
    } @else if (today.error() && !today.hasValue()) {
      <hq-card>
        <hq-band variant="error" [open]="true" [title]="'band.failed' | transloco" [dismissible]="false">
          {{ 'band.unreachable' | transloco }}
        </hq-band>
        <hq-button variant="secondary" (pressed)="today.reload()">{{ 'ui.retry' | transloco }}</hq-button>
      </hq-card>
    } @else {
      <div class="today" data-hq-today>
        <!-- 1. Today's attendance — every number a door to the board, filtered to it. -->
        @if (tiles().length > 0) {
          <hq-card [title]="'today.attendance.title' | transloco">
            <div class="today__tiles">
              @for (tile of tiles(); track tile.key) {
                <a
                  class="today__tile"
                  [class]="'today__tile today__tile--' + tile.key"
                  [routerLink]="boardLink()"
                  [queryParams]="{ status: tile.key }"
                  [attr.data-hq-today-status]="tile.key"
                >
                  <span class="today__count">{{ tile.value }}</span>
                  <span class="today__tile-label">{{ 'staffBoard.card.' + tile.key | transloco }}</span>
                </a>
              }
            </div>
          </hq-card>
        }

        <!-- 2. Waiting for you — decided from the card, in one tap. -->
        @if (requests().length > 0 || devices().length > 0) {
          <hq-card [title]="'today.waiting.title' | transloco">
            <ul class="today__list">
              @for (request of requests(); track request.id) {
                <li class="today__row" [attr.data-hq-today-request]="request.id">
                  <span class="today__row-text">
                    <span class="today__row-title">{{ request.staffName }} · {{ typeLabel(request) }}</span>
                    <span class="today__row-sub">{{ request.date }} · {{ request.reason }}</span>
                  </span>
                  <span class="today__row-actions">
                    <hq-button
                      variant="primary"
                      [disabled]="busy() !== null"
                      [loading]="busy() === request.id"
                      (pressed)="approve(request)"
                    >
                      {{ 'attendanceRequests.approve' | transloco }}
                    </hq-button>
                    <hq-button variant="quiet" [disabled]="busy() !== null" (pressed)="reject(request)">
                      {{ 'attendanceRequests.reject' | transloco }}
                    </hq-button>
                  </span>
                </li>
              }
              @for (device of devices(); track deviceKey(device)) {
                <li class="today__row" [attr.data-hq-today-device]="deviceKey(device)">
                  <span class="today__row-text">
                    <span class="today__row-title">{{ device.fullName }}</span>
                    <span class="today__row-sub">{{ 'today.waiting.deviceBlocked' | transloco }}</span>
                  </span>
                  <span class="today__row-actions">
                    <hq-button
                      variant="secondary"
                      [disabled]="busy() !== null"
                      [loading]="busy() === deviceKey(device)"
                      (pressed)="resetDevice(device)"
                    >
                      {{ 'admin.attendanceFields.device.reset' | transloco }}
                    </hq-button>
                  </span>
                </li>
              }
            </ul>
          </hq-card>
        }

        <!-- 3. Overdue complaints — the complaint itself, opened by the open= query. -->
        @if (complaints().length > 0) {
          <hq-card [title]="'today.complaints.title' | transloco">
            <ul class="today__list">
              @for (complaint of complaints(); track complaint.id) {
                <li class="today__row">
                  <a
                    class="today__link"
                    [routerLink]="complaintsLink()"
                    [queryParams]="{ open: complaint.id }"
                    [attr.data-hq-today-complaint]="complaint.id"
                  >
                    <span class="today__row-title">{{ complaint.title }}</span>
                    <span class="today__row-sub">
                      {{ complaint.childName }} · {{ complaint.recipientName }}
                    </span>
                  </a>
                  <span class="today__age">{{ ageLabel(complaint.ageHours) }}</span>
                </li>
              }
            </ul>
            @if (moreComplaints() > 0) {
              <a class="today__more" [routerLink]="complaintsLink()">
                {{ 'today.complaints.more' | transloco: { count: moreComplaints() } }}
              </a>
            }
          </hq-card>
        }

        <!-- 4. Unread messages — the count, and the three latest people waiting for a reply. -->
        @if (unreadCount() > 0) {
          <hq-card [title]="'today.unread.title' | transloco">
            <p class="today__unread-count">
              {{ 'today.unread.count' | transloco: { count: unreadCount() } }}
            </p>
            <ul class="today__list">
              @for (sender of senders(); track sender.threadId) {
                <li class="today__row">
                  <a
                    class="today__link"
                    [routerLink]="messagesLink()"
                    [queryParams]="{ thread: sender.threadId }"
                    [attr.data-hq-today-sender]="sender.threadId"
                  >
                    <span class="today__row-title">{{ sender.name }}</span>
                  </a>
                </li>
              }
            </ul>
            <a class="today__more" [routerLink]="messagesLink()">
              {{ 'today.unread.openMessages' | transloco }}
            </a>
          </hq-card>
        }

        <!-- Nothing needs her: one calm line, not four empty frames. -->
        @if (allClear()) {
          <p class="today__calm" data-hq-today-calm>{{ 'today.allClear' | transloco }}</p>
        }
      </div>
    }
  `,
  styles: `
    :host {
      display: block;
    }

    .today {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-16);
    }

    .today__tiles {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(9rem, 1fr));
      gap: var(--hq-space-12);
    }

    .today__tile {
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      gap: var(--hq-space-4);
      min-block-size: var(--hq-size-touch-target);
      padding: var(--hq-space-16);
      border: var(--hq-size-rule-thin) solid var(--hq-color-rule);
      border-radius: var(--hq-radius-card);
      background: var(--hq-color-surface-raised);
      color: var(--hq-color-ink);
      text-align: start;
      text-decoration: none;

      &:focus-visible {
        outline: var(--hq-size-rule) solid var(--hq-color-focus-border);
        outline-offset: 2px;
      }
    }

    .today__count {
      font-size: var(--hq-text-theme-xl);
      line-height: var(--hq-text-theme-xl-line);
      font-weight: var(--hq-text-weight-medium);
    }

    .today__tile--late .today__count {
      color: var(--hq-color-warning-700);
    }

    .today__tile--notArrived .today__count {
      color: var(--hq-color-error-ink);
    }

    .today__tile--present .today__count,
    .today__tile--remote .today__count {
      color: var(--hq-color-success-ink);
    }

    .today__list {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-8);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .today__row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--hq-space-12);
      min-block-size: var(--hq-size-touch-target);
      padding-block: var(--hq-space-8);
      border-block-end: var(--hq-size-rule-thin) solid var(--hq-color-divider);

      &:last-child {
        border-block-end: 0;
      }
    }

    .today__row-text,
    .today__link {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-4);
      min-inline-size: 0;
    }

    .today__link {
      color: inherit;
      text-decoration: none;

      &:hover .today__row-title,
      &:focus-visible .today__row-title {
        text-decoration: underline;
      }
    }

    .today__row-title {
      font-weight: var(--hq-text-weight-medium);
    }

    .today__row-sub {
      font-size: var(--hq-text-theme-sm);
      color: var(--hq-color-ink-soft);
    }

    .today__row-actions {
      display: flex;
      flex-wrap: wrap;
      gap: var(--hq-space-8);
      flex: 0 0 auto;
    }

    .today__age {
      flex: 0 0 auto;
      font-size: var(--hq-text-theme-sm);
      color: var(--hq-color-error-ink);
      font-weight: var(--hq-text-weight-medium);
    }

    .today__unread-count {
      margin: 0 0 var(--hq-space-8);
      font-weight: var(--hq-text-weight-medium);
    }

    .today__more {
      display: inline-block;
      margin-block-start: var(--hq-space-8);
      font-size: var(--hq-text-theme-sm);
      color: var(--hq-color-accent);
    }

    .today__calm {
      margin: 0;
      color: var(--hq-color-ink-soft);
    }
  `,
})
export class TodayComponent {
  private readonly api = inject(TodayApi);
  private readonly requestsApi = inject(AttendanceRequestsApi);
  private readonly settingsApi = inject(StaffAttendanceSettingsApi);
  private readonly auth = inject(AuthService);
  private readonly band = inject(BandService);
  private readonly permissions = inject(PermissionService);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  protected readonly today = rxResource({
    params: () => (this.auth.signedIn() ? (this.auth.effectiveSchoolId() ?? 'all') : undefined),
    stream: () => this.api.todayHome(),
  });

  /**
   * The id of whatever decision is in flight, and the ids already answered.
   *
   * `done` is what makes the item disappear at once rather than a tick later: the reload is
   * asked for in the same breath, and until it lands the list on screen would otherwise still
   * carry the request the server has just closed.
   */
  protected readonly busy = signal<string | null>(null);
  private readonly done = signal<readonly string[]>([]);

  /** `/admin` or `/management` — the only thing on this screen that varies by role. */
  private readonly area = computed(() => (this.auth.role() === 'MANAGERIAL' ? '/management' : '/admin'));

  protected readonly boardLink = computed(() => `${this.area()}/staff-attendance`);
  protected readonly complaintsLink = computed(() => `${this.area()}/complaints`);
  protected readonly messagesLink = computed(() => `${this.area()}/messages`);

  // ---- the four cards -------------------------------------------------------------------------

  /**
   * Absent when the school has `staffAttendance` off, and not drawn either when nobody is on the
   * board at all: four zeros are not something that needs her.
   */
  protected readonly tiles = computed<readonly { readonly key: Tile; readonly value: number }[]>(() => {
    const counts = this.today.value()?.attendance?.counts;
    if (counts === undefined) return [];
    const tiles = TILES.map((key) => ({ key, value: counts[key] ?? 0 }));
    return tiles.some((tile) => tile.value > 0) ? tiles : [];
  });

  protected readonly requests = computed<readonly AttendanceRequestView[]>(() =>
    (this.today.value()?.waiting?.requests ?? []).filter((row) => !this.done().includes(row.id)),
  );

  /**
   * Admin only, twice over: the server leaves `devices` out of a manager's answer (she does not
   * hold `attendance.device.reset`), and a read-only "View as" session is offered no reset either
   * — the reset is a write, and a button that answers 403 is worse than no button.
   */
  protected readonly devices = computed<readonly TodayDeviceBlock[]>(() =>
    this.permissions.can('attendance.device.reset')
      ? (this.today.value()?.waiting?.devices ?? []).filter(
          (device) => !this.done().includes(this.deviceKey(device)),
        )
      : [],
  );

  protected readonly complaints = computed<readonly TodayComplaint[]>(
    () => this.today.value()?.overdueComplaints?.items ?? [],
  );

  /** The server sends the oldest ten and the real total; the rest are a line, not ten more rows. */
  protected readonly moreComplaints = computed(() =>
    Math.max(0, (this.today.value()?.overdueComplaints?.total ?? 0) - this.complaints().length),
  );

  protected readonly unreadCount = computed(() => this.today.value()?.unread?.count ?? 0);

  protected readonly senders = computed<readonly TodaySender[]>(() =>
    (this.today.value()?.unread?.senders ?? []).slice(0, MAX_SENDERS),
  );

  protected readonly allClear = computed(
    () =>
      this.tiles().length === 0 &&
      this.requests().length === 0 &&
      this.devices().length === 0 &&
      this.complaints().length === 0 &&
      this.unreadCount() === 0,
  );

  // ---- labels ---------------------------------------------------------------------------------

  protected deviceKey(device: TodayDeviceBlock): string {
    return `${device.kind}:${device.id}`;
  }

  protected typeLabel(row: AttendanceRequestView): string {
    this.lang();
    const type = this.t(`attendanceRequests.type.${row.type}`);
    const kind = row.correctionKind ? this.t(`attendanceRequests.kind.${row.correctionKind}`) : '';
    return kind === '' ? type : `${type} · ${kind}`;
  }

  /**
   * "30 h" up to two whole days, "3 days" after that — the age as the server counted it, in whole
   * hours, so neither the browser's zone nor its clock is involved.
   */
  protected ageLabel(ageHours: number | undefined): string {
    this.lang();
    const hours = ageHours ?? 0;
    return hours < DAYS_THRESHOLD_HOURS
      ? this.t('today.ageHours', { hours })
      : this.t('today.ageDays', { days: Math.floor(hours / HOURS_PER_DAY) });
  }

  // ---- the decisions, from the card -----------------------------------------------------------

  protected approve(row: AttendanceRequestView): void {
    this.decide(row, true);
  }

  protected reject(row: AttendanceRequestView): void {
    this.decide(row, false);
  }

  private decide(row: AttendanceRequestView, approve: boolean): void {
    if (this.busy() !== null) return;
    this.busy.set(row.id);
    const call = approve
      ? this.requestsApi.approveAttendanceRequest(row.id)
      : this.requestsApi.rejectAttendanceRequest(row.id);
    call.subscribe({
      next: () => this.settled(row.id, true),
      error: (cause: unknown) =>
        this.failed(
          row.id,
          apiErrorCodeOf(cause) === 'conflict'
            ? this.t('attendanceRequests.alreadyDecided')
            : (apiErrorOf(cause)?.message ?? this.t('band.unreachable')),
        ),
    });
  }

  protected resetDevice(device: TodayDeviceBlock): void {
    if (this.busy() !== null) return;
    const key = this.deviceKey(device);
    this.busy.set(key);
    this.settingsApi.resetApprovedDevice(device.kind ?? '', device.id ?? '').subscribe({
      next: () => this.settled(key, true),
      error: (cause: unknown) =>
        this.failed(key, apiErrorOf(cause)?.message ?? this.t('band.unreachable')),
    });
  }

  /** Gone from the list at once, and the whole answer re-read behind it. */
  private settled(key: string, remove: boolean): void {
    this.busy.set(null);
    if (remove) this.done.update((ids) => [...ids, key]);
    this.today.reload();
  }

  /**
   * The red band, and a reload: somebody else decided it, or it was never hers — either way the
   * card on screen is out of date. The item stays until the server says otherwise.
   */
  private failed(key: string, message: string): void {
    this.band.fail(message);
    this.settled(key, false);
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}
