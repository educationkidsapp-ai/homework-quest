/* hq-flag: none (shell) — routed through `areaRoutes`, whose four `attendance-requests` rows
   carry `FLAGS.staffAttendance`; the server's controller is behind the same key. Feature 34's
   leave and permission rows ride on the same route and the same `attendance.requests.decide`, so
   they are not gated again here: with `staffLeave` off the server simply never writes one. */
import { ChangeDetectionStrategy, Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { type Observable, map, of } from 'rxjs';
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
  TextareaComponent,
} from '../../ui';

/** The three kinds a correction can be, in the order the staff app offers them. */
const CORRECTION_KINDS: readonly string[] = ['FORGOT_CHECK_IN', 'FORGOT_CHECK_OUT', 'PHONE_PROBLEM'];

/** Every request type, in the order the filter offers them. */
const TYPES: readonly string[] = ['LEAVE', 'PERMISSION', 'REMOTE_DAY', 'CORRECTION'];

/** Every status a request can reach. `PENDING` is the one the screen opens on. */
const STATUSES: readonly string[] = ['PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'];

/** The two types a rejection must give a reason for (feature 34; the server answers 400 without). */
const REASON_REQUIRED: readonly string[] = ['LEAVE', 'PERMISSION'];

/** Octet-stream rather than a guess: the server's `type` is what the tab is pointed at. */
const FALLBACK_TYPE = 'application/octet-stream';

/** The four filters, as the one query the screen asks. */
interface RequestFilter {
  readonly status: string;
  readonly type: string;
  readonly staff: string;
  readonly month: string;
}

const DEFAULT_FILTER: RequestFilter = { status: 'PENDING', type: '', staff: '', month: '' };

/**
 * **Requests** (feature 18, widened by feature 34) — the leave, permissions, remote days and
 * corrections the viewer's direct reports ask for, and her decision on each.
 *
 * **One screen in four areas.** `GET /attendance/requests` is not namespaced by role: the server
 * answers every caller with the requests *she* decides — her direct reports, or all of them for
 * an Admin — so a teacher who manages two assistants, a coordinator, a manager and the Admin all
 * read the same route and see different rows. There is nothing to vary by role in the component
 * but the Admin's "Add correction", which is gated on `attendance.correction.write`.
 *
 * **Four filters, one list.** Feature 18's two tabs were a status filter with two settings; the
 * screen now asks the server for a status (Pending by default, which is the tab she used to land
 * on), a type, an employee and a month, and the list is sorted oldest-first while it is the
 * pending one — the request that has waited longest is the one to answer — and newest-decision
 * first once it is not.
 *
 * **A rejection needs a reason** for a leave or a permission, so Reject opens a dialog rather
 * than posting straight away. For a remote day or a correction the reason is optional, and the
 * released Android build's bare Reject still posts none at all.
 *
 * **No confirmation for Approve, and no toast.** Approving is not destructive and the spec's
 * two-tap rule is the point of the screen: the buttons disable while the request is in flight and
 * the row leaves the list when the server has agreed. A refusal is a red band — `conflict` (409,
 * somebody decided it first) in her own words, everything else in the server's.
 */
@Component({
  selector: 'hq-attendance-requests-page',
  imports: [
    PageComponent,
    CardComponent,
    TableComponent,
    ButtonComponent,
    DialogComponent,
    InputComponent,
    SelectComponent,
    TextareaComponent,
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

  protected readonly filter = signal<RequestFilter>(DEFAULT_FILTER);

  /**
   * The request a notification named (`?request=`), highlighted in the list rather than opened:
   * there is no detail screen, and the row carries everything the bell's sentence did.
   */
  private readonly highlighted = toSignal(
    inject(ActivatedRoute).queryParamMap.pipe(map((params) => params.get('request') ?? '')),
    { initialValue: '' },
  );

  protected readonly requests = rxResource<readonly AttendanceRequestView[], RequestFilter>({
    params: () => this.filter(),
    // Empty is "any": the generated client leaves an undefined parameter out of the query.
    stream: ({ params }) =>
      this.api
        .attendanceRequestsToDecide(
          params.status || undefined,
          params.type || undefined,
          params.staff || undefined,
          params.month || undefined,
        )
        .pipe(map((list) => list.requests ?? [])),
    defaultValue: [],
  });

  protected readonly pendingOnly = computed(() => this.filter().status === 'PENDING');

  protected readonly rows = computed<readonly AttendanceRequestView[]>(() =>
    this.pendingOnly()
      ? [...this.requests.value()].sort((a, b) => a.createdAt - b.createdAt)
      : [...this.requests.value()].sort(
          (a, b) => (b.decidedAt ?? b.createdAt) - (a.decidedAt ?? a.createdAt),
        ),
  );

  protected readonly deciding = signal<string | null>(null);

  // ---- the filters ----------------------------------------------------------------------------

  protected setFilter(patch: Partial<RequestFilter>): void {
    this.filter.update((current) => ({ ...current, ...patch }));
  }

  protected readonly statusOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return [
      { value: '', label: this.t('attendanceRequests.filter.anyStatus') },
      ...STATUSES.map((status) => ({ value: status, label: this.t(`attendanceRequests.status.${status}`) })),
    ];
  });

  protected readonly typeOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return [
      { value: '', label: this.t('attendanceRequests.filter.anyType') },
      ...TYPES.map((type) => ({ value: type, label: this.t(`attendanceRequests.type.${type}`) })),
    ];
  });

  /**
   * The employees the screen has seen, as the employee filter offers them.
   *
   * Taken from the rows rather than from the staff directory: `/admin/staff/attendance-profiles`
   * is the Admin's read, and this screen belongs to every direct manager. The list is the union of
   * every answer so far, so narrowing to one employee does not shrink the choice to her alone.
   */
  protected readonly staffOptions = linkedSignal<readonly AttendanceRequestView[], readonly SelectOption[]>({
    source: () => this.requests.value(),
    computation: (rows, previous) => {
      const byValue = new Map((previous?.value ?? []).map((option) => [option.value, option]));
      for (const row of rows) byValue.set(row.staff, { value: row.staff, label: row.staffName });
      return [...byValue.values()].sort((a, b) => String(a.label).localeCompare(String(b.label)));
    },
  });

  protected readonly employeeOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return [{ value: '', label: this.t('attendanceRequests.filter.anyStaff') }, ...this.staffOptions()];
  });

  protected readonly columns = computed<readonly TableColumn<AttendanceRequestView>[]>(() => {
    this.lang();
    return [
      { key: 'staffName', header: this.t('attendanceRequests.column.staff'), width: '18%' },
      { key: 'type', header: this.t('attendanceRequests.column.type'), width: '16%' },
      { key: 'date', header: this.t('attendanceRequests.column.date'), width: '16%' },
      { key: 'times', header: this.t('attendanceRequests.column.times'), width: '16%' },
      { key: 'reason', header: this.t('attendanceRequests.column.reason') },
      { key: 'status', header: this.t('attendanceRequests.column.status'), width: '16%' },
    ];
  });

  protected trackRequest = (row: AttendanceRequestView): string => row.id;
  protected isHighlighted = (row: AttendanceRequestView): boolean => row.id === this.highlighted();

  // ---- labels ---------------------------------------------------------------------------------

  /** "Leave · Sick", "Correction · Forgot to check in", or the bare type. */
  protected typeLabel(row: AttendanceRequestView): string {
    this.lang();
    const type = this.t(`attendanceRequests.type.${row.type}`);
    const detail = row.leaveType
      ? this.t(`admin.attendanceSettings.leave.type.${row.leaveType}`)
      : row.correctionKind
        ? this.t(`attendanceRequests.kind.${row.correctionKind}`)
        : '';
    return detail === '' ? type : `${type} · ${detail}`;
  }

  /** A leave's range, or the one day everything else is about. */
  protected dateLabel(row: AttendanceRequestView): string {
    this.lang();
    return row.toDate && row.toDate !== row.date
      ? this.t('attendanceRequests.range', { from: row.date, to: row.toDate })
      : row.date;
  }

  /**
   * What the row is worth: the days a leave uses, the window and minutes of a permission, or
   * feature 18's claimed check-in and check-out.
   */
  protected amountLabel(row: AttendanceRequestView): string {
    this.lang();
    if (row.type === 'LEAVE')
      return row.days === undefined
        ? this.t('attendanceRequests.none')
        : this.t('attendanceRequests.days', { days: row.days });
    if (row.type === 'PERMISSION') {
      const window =
        row.fromTime && row.toTime
          ? this.t('attendanceRequests.window', { from: row.fromTime, to: row.toTime })
          : '';
      const minutes =
        row.minutes === undefined ? '' : this.t('attendanceRequests.minutes', { minutes: row.minutes });
      const parts = [window, minutes].filter((part) => part !== '');
      return parts.length === 0 ? this.t('attendanceRequests.none') : parts.join(' · ');
    }
    return this.timesLabel(row.checkIn, row.checkOut);
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

  /**
   * The second step of the two-step chain: her manager has said yes and the Admin has not. Shown
   * only while the request is still pending — a decided row's stage is history.
   */
  protected isWaitingForAdmin(row: AttendanceRequestView): boolean {
    return row.status === 'PENDING' && row.stage === 'ADMIN';
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
    if (this.deciding() !== null) return;
    this.send(row.id, this.api.approveAttendanceRequest(row.id));
  }

  protected readonly rejecting = signal<AttendanceRequestView | null>(null);
  protected readonly rejectOpen = signal(false);
  protected readonly rejectReason = signal('');
  protected readonly rejectTouched = signal(false);

  /** A leave and a permission need the reason; the older two may be rejected without one. */
  protected readonly reasonRequired = computed(() =>
    REASON_REQUIRED.includes(this.rejecting()?.type ?? ''),
  );

  protected readonly rejectError = computed(() =>
    this.rejectTouched() && this.reasonRequired() && this.rejectReason().trim() === ''
      ? this.t('attendanceRequests.rejectDialog.reasonRequired')
      : null,
  );

  protected openReject(row: AttendanceRequestView): void {
    if (this.deciding() !== null) return;
    this.rejecting.set(row);
    this.rejectReason.set('');
    this.rejectTouched.set(false);
    this.rejectOpen.set(true);
  }

  /** The reason the employee will read, so an empty one stops the post rather than the server. */
  protected confirmReject(): void {
    const row = this.rejecting();
    if (row === null) return;
    this.rejectTouched.set(true);
    const reason = this.rejectReason().trim();
    if (this.reasonRequired() && reason === '') return;
    this.rejectOpen.set(false);
    this.send(
      row.id,
      reason === ''
        ? this.api.rejectAttendanceRequest(row.id)
        : this.api.rejectAttendanceRequest(row.id, { reason }),
    );
  }

  /**
   * The row leaves the list the moment the server has agreed — it is no longer pending, and
   * another filter is where it now belongs. Nothing is written locally before the answer: a
   * decision that failed must not look taken.
   */
  private send(id: string, call: Observable<unknown>): void {
    this.deciding.set(id);
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
   * The photo or PDF that came with a correction or a sick leave, in a tab of its own.
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

  protected readonly addStaffOptions = computed<readonly SelectOption[]>(() =>
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
