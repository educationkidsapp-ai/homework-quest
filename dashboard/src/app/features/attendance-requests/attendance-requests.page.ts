/* hq-flag: none (shell) — routed through `areaRoutes`, whose four `attendance-requests` rows
   carry `FLAGS.staffAttendance`; the server's controller is behind the same key. */
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { forkJoin, map, of } from 'rxjs';
import {
  AttendanceRequestsApi,
  StaffAttendanceSettingsApi,
  type AttendanceRequestView,
  type StaffAttendanceProfile,
  apiErrorCodeOf,
  apiErrorOf,
} from '../../api';
import { BandService } from '../../core/band/band.service';
import { reserveTab } from '../../core/download/download';
import { activeLang } from '../../core/i18n/active-lang';
import { MediaService } from '../../core/media/media.service';
import { PermissionService } from '../../core/permissions/permission.service';
import {
  type SelectOption,
  type Tab,
  type TableColumn,
  ButtonComponent,
  CardComponent,
  DialogComponent,
  EmptyStateComponent,
  InputComponent,
  PageComponent,
  SelectComponent,
  SkeletonComponent,
  TableComponent,
  TabsComponent,
} from '../../ui';

type Which = 'pending' | 'decided';

/** The three kinds a correction can be, in the order the staff app offers them. */
const CORRECTION_KINDS: readonly string[] = ['FORGOT_CHECK_IN', 'FORGOT_CHECK_OUT', 'PHONE_PROBLEM'];

/** Octet-stream rather than a guess: the server's `type` is what the tab is pointed at. */
const FALLBACK_TYPE = 'application/octet-stream';

/**
 * **Attendance requests** (feature 18, `docs/features/18-remote-days-corrections.md`) — the
 * remote days and the corrections the viewer's direct reports ask for, and her decision on each.
 *
 * **One screen in four areas.** `GET /attendance/requests` is not namespaced by role: the server
 * answers every caller with the requests *she* decides — her direct reports, or all of them for
 * an Admin — so a teacher who manages two assistants, a coordinator, a manager and the Admin all
 * read the same route and see different rows. There is nothing to vary by role in the component
 * but the Admin's "Add correction", which is gated on `attendance.correction.write`.
 *
 * **Two tabs, three statuses.** Pending is the one that matters and is the tab she lands on;
 * Decided is the record, and it is the two decided statuses fetched together, newest decision
 * first, because "approved" and "rejected" are one question answered two ways.
 *
 * **No confirmation, and no toast.** Approving is not destructive and the spec's two-tap rule is
 * the point of the screen: the buttons disable while the request is in flight and the row moves
 * to Decided when the server has agreed. A refusal is a red band — `conflict` (409, somebody
 * decided it first) in her own words, everything else in the server's.
 */
@Component({
  selector: 'hq-attendance-requests-page',
  imports: [
    PageComponent,
    CardComponent,
    TabsComponent,
    TableComponent,
    ButtonComponent,
    DialogComponent,
    InputComponent,
    SelectComponent,
    EmptyStateComponent,
    SkeletonComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './attendance-requests.page.html',
  styleUrl: './attendance-requests.page.scss',
})
export class AttendanceRequestsPage {
  private readonly api = inject(AttendanceRequestsApi);
  private readonly staffApi = inject(StaffAttendanceSettingsApi);
  private readonly transloco = inject(TranslocoService);
  private readonly band = inject(BandService);
  private readonly permissions = inject(PermissionService);
  private readonly media = inject(MediaService);
  private readonly lang = activeLang();

  /** `attendance.correction.write` is the Admin's alone (`permissions.json`). */
  protected readonly canCorrect = computed(() => this.permissions.can('attendance.correction.write'));

  protected readonly which = signal<Which>('pending');

  /**
   * The request a notification named (`?request=`), highlighted in the list rather than opened:
   * there is no detail screen, and the row carries everything the bell's sentence did.
   */
  private readonly highlighted = toSignal(
    inject(ActivatedRoute).queryParamMap.pipe(map((params) => params.get('request') ?? '')),
    { initialValue: '' },
  );

  protected readonly requests = rxResource<readonly AttendanceRequestView[], Which>({
    params: () => this.which(),
    stream: ({ params }) =>
      params === 'pending'
        ? this.api.attendanceRequestsToDecide('PENDING').pipe(map((list) => list.requests ?? []))
        : forkJoin([
            this.api.attendanceRequestsToDecide('APPROVED'),
            this.api.attendanceRequestsToDecide('REJECTED'),
          ]).pipe(
            map(([approved, rejected]) =>
              [...(approved.requests ?? []), ...(rejected.requests ?? [])].sort(
                (a, b) => (b.decidedAt ?? b.createdAt) - (a.decidedAt ?? a.createdAt),
              ),
            ),
          ),
    defaultValue: [],
  });

  /** Oldest first while they are pending: the one that has waited longest is the one to answer. */
  protected readonly rows = computed<readonly AttendanceRequestView[]>(() =>
    this.which() === 'pending'
      ? [...this.requests.value()].sort((a, b) => a.createdAt - b.createdAt)
      : this.requests.value(),
  );

  protected readonly deciding = signal<string | null>(null);

  protected readonly tabs = computed<readonly Tab<Which>[]>(() => {
    this.lang();
    return [
      {
        id: 'pending',
        label: this.t('attendanceRequests.tab.pending'),
        ...(this.which() === 'pending' && this.rows().length > 0 ? { badge: this.rows().length } : {}),
      },
      { id: 'decided', label: this.t('attendanceRequests.tab.decided') },
    ];
  });

  protected readonly columns = computed<readonly TableColumn<AttendanceRequestView>[]>(() => {
    this.lang();
    const columns: TableColumn<AttendanceRequestView>[] = [
      { key: 'staffName', header: this.t('attendanceRequests.column.staff'), width: '20%' },
      { key: 'type', header: this.t('attendanceRequests.column.type'), width: '18%' },
      { key: 'date', header: this.t('attendanceRequests.column.date'), width: '14%' },
      { key: 'times', header: this.t('attendanceRequests.column.times'), width: '16%' },
      { key: 'reason', header: this.t('attendanceRequests.column.reason') },
    ];
    return this.which() === 'pending'
      ? columns
      : [...columns, { key: 'status', header: this.t('attendanceRequests.column.status'), width: '18%' }];
  });

  protected trackRequest = (row: AttendanceRequestView): string => row.id;
  protected isHighlighted = (row: AttendanceRequestView): boolean => row.id === this.highlighted();

  // ---- labels ---------------------------------------------------------------------------------

  protected typeLabel(row: AttendanceRequestView): string {
    this.lang();
    const type = this.t(`attendanceRequests.type.${row.type}`);
    const kind = row.correctionKind ? this.t(`attendanceRequests.kind.${row.correctionKind}`) : '';
    return kind === '' ? type : `${type} · ${kind}`;
  }

  /** "In 07:40 · Out 14:10", or the sentence that says she claimed no time at all. */
  protected timesLabel(checkIn?: string, checkOut?: string): string {
    this.lang();
    const parts = [
      checkIn ? this.t('attendanceRequests.checkIn', { time: checkIn }) : '',
      checkOut ? this.t('attendanceRequests.checkOut', { time: checkOut }) : '',
    ].filter((part) => part !== '');
    return parts.length === 0 ? this.t('attendanceRequests.none') : parts.join(' · ');
  }

  protected originalLabel(row: AttendanceRequestView): string | null {
    if (!row.originalCheckIn && !row.originalCheckOut) return null;
    return this.t('attendanceRequests.original', {
      times: this.timesLabel(row.originalCheckIn, row.originalCheckOut),
    });
  }

  protected statusLabel(row: AttendanceRequestView): string {
    this.lang();
    return this.t(`attendanceRequests.status.${row.status}`);
  }

  protected decidedLabel(row: AttendanceRequestView): string | null {
    this.lang();
    if (!row.decidedBy && row.decidedAt === undefined) return null;
    return this.t('attendanceRequests.decidedBy', {
      name: row.decidedBy ?? '',
      date: row.decidedAt === undefined ? '' : this.day(row.decidedAt),
    });
  }

  protected askedLabel(row: AttendanceRequestView): string {
    this.lang();
    return this.t('attendanceRequests.asked', { date: this.day(row.createdAt) });
  }

  private day(at: number): string {
    return new Intl.DateTimeFormat(this.transloco.getActiveLang(), {
      day: 'numeric',
      month: 'short',
    }).format(at);
  }

  // ---- the decision ---------------------------------------------------------------------------

  protected approve(row: AttendanceRequestView): void {
    this.decide(row, true);
  }

  protected reject(row: AttendanceRequestView): void {
    this.decide(row, false);
  }

  /**
   * The row leaves the list the moment the server has agreed — it is no longer pending, and the
   * other tab is where it now belongs. Nothing is written locally before the answer: a decision
   * that failed must not look taken.
   */
  private decide(row: AttendanceRequestView, approve: boolean): void {
    if (this.deciding() !== null) return;
    this.deciding.set(row.id);
    const call = approve
      ? this.api.approveAttendanceRequest(row.id)
      : this.api.rejectAttendanceRequest(row.id);
    call.subscribe({
      next: () => {
        this.deciding.set(null);
        this.requests.reload();
      },
      error: (cause: unknown) => {
        this.deciding.set(null);
        this.band.fail(
          apiErrorCodeOf(cause) === 'conflict'
            ? this.t('attendanceRequests.alreadyDecided')
            : (apiErrorOf(cause)?.message ?? this.t('band.unreachable')),
        );
        // Somebody else decided it, or it was never hers: the list on screen is out of date.
        this.requests.reload();
      },
    });
  }

  // ---- the attachment -------------------------------------------------------------------------

  protected readonly opening = signal<string | null>(null);

  /**
   * The photo or PDF that came with a correction, in a tab of its own.
   *
   * `GET /media/attachments/{id}` needs the bearer, so the bytes come through the generated
   * client and the tab is reserved inside the click (`core/download`) — a tab opened after the
   * request answered is a blocked popup.
   */
  protected openAttachment(row: AttendanceRequestView): void {
    const file = row.attachment;
    if (file === undefined || this.opening() !== null) return;
    const tab = reserveTab();
    this.opening.set(row.id);
    this.media.attachmentFile(file.id).subscribe({
      next: (blob) => {
        this.opening.set(null);
        tab.show(new Blob([blob], { type: file.type || FALLBACK_TYPE }), file.name);
      },
      error: () => {
        this.opening.set(null);
        tab.cancel();
        this.band.fail(this.t('attendanceRequests.attachmentFailed'));
      },
    });
  }

  // ---- the Admin's own correction -------------------------------------------------------------

  protected readonly addOpen = signal(false);
  protected readonly addStaff = signal('');
  protected readonly addDate = signal('');
  protected readonly addKind = signal('PHONE_PROBLEM');
  protected readonly addCheckIn = signal('');
  protected readonly addCheckOut = signal('');
  protected readonly addReason = signal('');
  protected readonly addSaving = signal(false);
  protected readonly addTouched = signal(false);

  /**
   * The employee list the Attendance settings page already reads — fetched when the dialog opens
   * and not before. A correction is the rare case; every other visit to this screen would
   * otherwise pay for a list of the whole staff it never shows.
   */
  private readonly staff = rxResource<readonly StaffAttendanceProfile[], boolean>({
    params: () => this.canCorrect() && this.addOpen(),
    stream: ({ params }) => (params ? this.staffApi.staffAttendanceProfiles() : of([])),
    defaultValue: [],
  });

  protected readonly staffOptions = computed<readonly SelectOption[]>(() =>
    this.staff.value().map((profile) => ({
      value: `${profile.kind ?? ''}:${profile.id ?? ''}`,
      label: profile.fullName ?? '',
    })),
  );

  protected readonly kindOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return CORRECTION_KINDS.map((kind) => ({
      value: kind,
      label: this.t(`attendanceRequests.kind.${kind}`),
    }));
  });

  /** Today, so the date field cannot be used to write a correction into the future. */
  protected readonly today = new Date().toISOString().slice(0, 10);

  protected readonly reasonError = computed(() =>
    this.addTouched() && this.addReason().trim() === ''
      ? this.t('attendanceRequests.add.reasonRequired')
      : null,
  );

  protected readonly addComplete = computed(
    () => this.addStaff() !== '' && this.addDate() !== '' && this.addReason().trim() !== '',
  );

  protected openAdd(): void {
    this.addStaff.set('');
    this.addDate.set(this.today);
    this.addKind.set('PHONE_PROBLEM');
    this.addCheckIn.set('');
    this.addCheckOut.set('');
    this.addReason.set('');
    this.addTouched.set(false);
    this.addOpen.set(true);
  }

  /** The reason is the Admin's audit trail, so an empty one stops the save rather than the server. */
  protected saveCorrection(): void {
    this.addTouched.set(true);
    if (!this.addComplete() || this.addSaving()) return;
    const [kind = '', id = ''] = this.addStaff().split(':', 2);
    this.addSaving.set(true);
    this.api
      .adminAttendanceCorrection(kind, id, {
        date: this.addDate(),
        reason: this.addReason().trim(),
        correctionKind: this.addKind(),
        ...(this.addCheckIn() === '' ? {} : { checkIn: this.addCheckIn() }),
        ...(this.addCheckOut() === '' ? {} : { checkOut: this.addCheckOut() }),
      })
      .subscribe({
        next: () => {
          this.addSaving.set(false);
          this.addOpen.set(false);
          this.requests.reload();
        },
        error: (cause: unknown) => {
          this.addSaving.set(false);
          this.band.fail(apiErrorOf(cause)?.message ?? this.t('band.unreachable'));
        },
      });
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}
