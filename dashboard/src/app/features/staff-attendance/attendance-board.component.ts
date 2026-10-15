/* hq-flag: none (shell) — drawn by `AttendanceBoardPage`, whose rows carry `FLAGS.staffAttendance`,
   and by the manager's Staff attendance page only while that flag is on; the server's controller is
   behind the same key. */
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  input,
  linkedSignal,
  signal,
} from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { map } from 'rxjs/operators';
import { type BoardRow, type EmployeeDay, type ReportRow, StaffAttendanceBoardApi } from '../../api';
import { saveFile } from '../../core/download/download';
import { activeLang } from '../../core/i18n/active-lang';
import { PlatformService } from '../../core/platform/platform.service';
import {
  type SelectOption,
  type TableColumn,
  BandComponent,
  ButtonComponent,
  CardComponent,
  DialogComponent,
  EmptyStateComponent,
  InputComponent,
  SelectComponent,
  SkeletonComponent,
  TableComponent,
} from '../../ui';
import {
  type BoardFilter,
  type Card,
  CARDS,
  NO_FILTER,
  REFRESH_MS,
  STATUSES,
  academiesOf,
  cardFromStatus,
  countOf,
  filterRows,
  toggleCard,
  withBom,
} from './attendance-board.models';

export type BoardView = 'today' | 'report';

const XLSX = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
const CATEGORIES = ['TEACHER', 'ADMINISTRATIVE', 'WORKER'] as const;

/**
 * **Staff attendance** for leaders (feature 17, `docs/features/17-attendance-board-report.md`).
 *
 * **Today** is five count cards over a table of everyone the server says she leads — the Admin
 * the school, a director her academy, a direct manager her people. A card is a filter: tapping it
 * shows exactly the rows it counted, and tapping it again clears it. The board asks again every
 * 30 seconds while it is on screen, which is the spec's bound for a new check-in to appear.
 *
 * **A name** opens her day: every attempt with its refusal reason, what she and her leaders were
 * told, and her month — with the days marked by hand before the switch, labelled as such.
 *
 * **Monthly report** is one row per employee for the month picked, and the same rows as CSV and
 * Excel straight from the server, so the file payroll opens is the table on screen.
 */
@Component({
  selector: 'hq-attendance-board',
  imports: [
    BandComponent,
    ButtonComponent,
    CardComponent,
    DialogComponent,
    EmptyStateComponent,
    InputComponent,
    SelectComponent,
    SkeletonComponent,
    TableComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './attendance-board.component.html',
  styleUrl: './attendance-board.component.scss',
})
export class AttendanceBoardComponent {
  private readonly api = inject(StaffAttendanceBoardApi);
  private readonly transloco = inject(TranslocoService);
  private readonly platform = inject(PlatformService);
  private readonly lang = activeLang();

  readonly view = input<BoardView>('today');

  protected readonly cards = CARDS;

  // ---- Today --------------------------------------------------------------------------------

  /** Bumped every {@link REFRESH_MS}; the board's params read it, so each bump is a fresh ask. */
  private readonly tick = signal(0);

  protected readonly board = rxResource({
    params: () => (this.view() === 'today' ? this.tick() : undefined),
    stream: () => this.api.attendanceBoard(),
  });

  /**
   * Feature 21: `?status=late` from the "What needs me today?" Home, read into the card filter
   * the board already has — so the number she tapped and the rows she lands on are the same
   * thing, and there is nothing new to keep in step with the cards.
   *
   * A `linkedSignal` rather than a one-off read in the constructor: she may come back to this
   * screen from a different number without the component being built again, and tapping a card
   * here still writes the filter directly. An unknown or absent value is no filter at all.
   */
  private readonly statusParam = toSignal(
    inject(ActivatedRoute).queryParamMap.pipe(map((params) => params.get('status') ?? '')),
    { initialValue: '' },
  );

  protected readonly filter = linkedSignal<Card | null, BoardFilter>({
    source: () => cardFromStatus(this.statusParam()),
    computation: (card, previous) => ({ ...(previous?.value ?? NO_FILTER), card }),
  });
  protected readonly rows = computed(() => this.board.value()?.rows ?? []);
  protected readonly shown = computed(() => filterRows(this.rows(), this.filter()));
  protected readonly counts = computed(() => this.board.value()?.counts);
  /** A teacher or coordinator nobody reports to — the empty state says so instead of "no rows". */
  protected readonly leadsNobody = computed(
    () => this.board.value()?.scope === 'REPORTS' && this.rows().length === 0,
  );

  constructor() {
    const timer = setInterval(() => this.tick.update((n) => n + 1), REFRESH_MS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  protected count(card: Card): number {
    return countOf(this.counts(), card);
  }

  protected tapCard(card: Card): void {
    this.filter.update((f) => toggleCard(f, card));
  }

  protected setFilter(patch: Partial<BoardFilter>): void {
    this.filter.update((f) => ({ ...f, ...patch }));
  }

  protected readonly statusOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return [
      { value: '', label: this.t('staffBoard.filter.anyStatus') },
      ...STATUSES.map((s) => ({ value: s, label: this.t(`staffBoard.status.${s}`) })),
    ];
  });

  protected readonly academyOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return [
      { value: '', label: this.t('staffBoard.filter.anyAcademy') },
      ...academiesOf(this.rows()).map((a) => ({ value: a, label: this.academyLabel(a) })),
    ];
  });

  protected readonly categoryOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return [
      { value: '', label: this.t('staffBoard.filter.anyCategory') },
      ...CATEGORIES.map((c) => ({ value: c, label: this.t(`admin.attendanceSettings.category.${c}`) })),
    ];
  });

  protected readonly boardColumns = computed<readonly TableColumn<BoardRow>[]>(() => {
    this.lang();
    return [
      { key: 'fullName', header: this.t('staffBoard.column.name') },
      { key: 'category', header: this.t('staffBoard.column.category') },
      { key: 'academies', header: this.t('staffBoard.column.academy') },
      { key: 'checkIn', header: this.t('staffBoard.column.checkIn') },
      { key: 'checkOut', header: this.t('staffBoard.column.checkOut') },
      { key: 'status', header: this.t('staffBoard.column.status') },
      { key: 'mode', header: this.t('staffBoard.column.mode') },
    ];
  });

  protected readonly trackRow = (row: { kind?: string; id?: string }): string => `${row.kind}:${row.id}`;

  // ---- one employee's day -------------------------------------------------------------------

  protected readonly dayOpen = signal(false);
  private readonly person = signal<{ kind: string; id: string } | null>(null);

  protected readonly employee = rxResource<EmployeeDay | undefined, { kind: string; id: string } | undefined>(
    {
      params: () => this.person() ?? undefined,
      stream: ({ params }) => this.api.attendanceEmployeeDay(params.kind, params.id),
    },
  );

  protected openDay(row: { kind?: string; id?: string }): void {
    if (!row.kind || !row.id) return;
    this.person.set({ kind: row.kind, id: row.id });
    this.dayOpen.set(true);
  }

  // ---- the monthly report -------------------------------------------------------------------

  /** This month where the school is, so a leader in another zone still opens on the right one. */
  protected readonly month = signal(
    new Intl.DateTimeFormat('en-CA', { timeZone: this.platform.timezone() }).format(new Date()).slice(0, 7),
  );

  protected readonly report = rxResource({
    params: () => (this.view() === 'report' && this.month() !== '' ? this.month() : undefined),
    stream: ({ params: month }) => this.api.attendanceReport(month),
  });

  protected readonly reportColumns = computed<readonly TableColumn<ReportRow>[]>(() => {
    this.lang();
    const number = (key: keyof ReportRow): TableColumn<ReportRow> => ({
      key,
      header: this.t(`staffBoard.report.${key}`),
      align: 'end',
    });
    return [
      { key: 'fullName', header: this.t('staffBoard.column.name') },
      { key: 'category', header: this.t('staffBoard.column.category') },
      number('workingDays'),
      number('presentOnsite'),
      number('presentRemote'),
      number('lateCount'),
      number('lateMinutes'),
      number('absentDays'),
      number('earlyLeaveMinutes'),
      number('missingCheckOuts'),
      number('corrections'),
    ];
  });

  protected readonly exporting = signal(false);

  protected exportCsv(): void {
    const month = this.month();
    this.exporting.set(true);
    this.api.attendanceReportCsv(month).subscribe({
      next: (csv) => {
        this.exporting.set(false);
        saveFile(withBom(csv), `staff-attendance-${month}.csv`, 'text/csv;charset=utf-8');
      },
      error: () => this.exporting.set(false),
    });
  }

  protected exportXlsx(): void {
    const month = this.month();
    this.exporting.set(true);
    // Neither text nor JSON, so the generated client hands back a blob despite its `string` type.
    this.api.attendanceReportXlsx(month).subscribe({
      next: (sheet) => {
        this.exporting.set(false);
        saveFile(sheet, `staff-attendance-${month}.xlsx`, XLSX);
      },
      error: () => this.exporting.set(false),
    });
  }

  // ---- labels -------------------------------------------------------------------------------

  protected statusLabel(status: string | undefined): string {
    this.lang();
    return status ? this.t(`staffBoard.status.${status}`) : '';
  }

  protected modeLabel(mode: string | undefined | null): string {
    this.lang();
    return mode ? this.t(`staffBoard.mode.${mode}`) : '—';
  }

  protected categoryLabel(category: string | undefined): string {
    this.lang();
    return category ? this.t(`admin.attendanceSettings.category.${category}`) : '';
  }

  protected academyLabel(academy: string): string {
    this.lang();
    return this.t(`curriculum.${academy}`);
  }

  protected academiesLabel(academies: readonly string[] | undefined): string {
    return (academies ?? []).map((a) => this.academyLabel(a)).join('، ') || '—';
  }

  protected reasonLabel(reason: string | undefined | null): string {
    this.lang();
    return reason ? this.t(`staffBoard.reason.${reason}`) : '';
  }

  protected alertLabel(type: string | undefined): string {
    this.lang();
    return type ? this.t(`staffBoard.alert.${type}`) : '';
  }

  private t(key: string): string {
    return this.transloco.translate<string>(key);
  }
}
