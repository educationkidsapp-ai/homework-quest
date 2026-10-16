/* hq-flag: none (shell) — routed through `areaRoutes`, whose `materials` rows carry
   `FLAGS.teachingMaterials`; the server's `MaterialBoardController` is behind the same key. */
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import {
  type MaterialBoard,
  type MaterialBoardRow,
  type MaterialRow,
  TeachingMaterialBoardApi,
  apiErrorOf,
} from '../../api';
import { reserveTab, saveFile } from '../../core/download/download';
import { silentErrors } from '../../core/http/error.interceptor';
import { activeLang } from '../../core/i18n/active-lang';
import { MediaService } from '../../core/media/media.service';
import {
  type TableColumn,
  BandComponent,
  ButtonComponent,
  CardComponent,
  DialogComponent,
  EmptyStateComponent,
  InputComponent,
  PageComponent,
  SkeletonComponent,
  TableComponent,
  TextareaComponent,
} from '../../ui';
import { translateOr } from '../coordinator/coordinator.labels';
import {
  type MaterialCount,
  type MaterialStatus,
  MATERIAL_COUNTS,
  canReview,
  countOf,
  csvName,
  isReviewed,
  rowKey,
  sortedBoardRows,
  statusOf,
  weekOf,
  weekPlus,
  withBom,
} from './materials.models';

const EMPTY: MaterialBoard = { rows: [] };

/**
 * **Teaching material / متابعة التحضير** (feature 31) — who handed her weekly preparation in on
 * time, who was late, and who has not handed it in at all.
 *
 * **The rows are the attendance board's own scope and nothing else** (`GET /materials/board`):
 * the Admin sees the school, a department manager her academy and her direct reports, a floor
 * supervisor the teachers of her floors, and a coordinator or a teacher her direct reports only.
 * The page asks for no scope of its own — it has no way to ask for one — so there is exactly one
 * answer to "who do I see", and it is the same one as on Staff attendance.
 *
 * **One row per teacher, opened onto her classes.** A leader is looking for the three people who
 * have not handed in, not for a list of ninety (class, subject) pairs; her counts are on the row
 * and the pairs are one tap away.
 *
 * **Reviewing is a decision.** `POST /materials/{id}/review` answers 409 the second time, and the
 * dialog says so in her language rather than leaving a button that looks as if it did nothing.
 */
@Component({
  selector: 'hq-material-board-page',
  imports: [
    BandComponent,
    ButtonComponent,
    CardComponent,
    DialogComponent,
    EmptyStateComponent,
    InputComponent,
    PageComponent,
    SkeletonComponent,
    TableComponent,
    TextareaComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './material-board.page.html',
  styleUrl: './materials.scss',
})
export class MaterialBoardPage {
  private readonly api = inject(TeachingMaterialBoardApi);
  private readonly media = inject(MediaService);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  protected readonly cards = MATERIAL_COUNTS;

  /** `''` is the current teaching week — the one ask that needs no date. */
  protected readonly week = signal('');

  protected readonly board = rxResource<MaterialBoard, string>({
    params: () => this.week(),
    stream: ({ params: week }) => this.api.materialBoard(week === '' ? undefined : week),
    defaultValue: EMPTY,
  });

  protected readonly rows = computed(() => sortedBoardRows(this.board.value().rows ?? []));
  protected readonly counts = computed(() => this.board.value().counts);
  protected readonly timeZone = computed(() => this.board.value().timeZone ?? 'UTC');
  /** The week the server resolved the ask to — what the date field and the buttons work from. */
  protected readonly resolved = computed(() => this.board.value().week ?? '');
  /** A teacher or coordinator nobody reports to: say so rather than "no rows". */
  protected readonly leadsNobody = computed(
    () => this.board.value().scope === 'REPORTS' && this.rows().length === 0,
  );

  protected count(card: MaterialCount): number {
    return countOf(this.counts(), card);
  }

  protected pickWeek(value: string): void {
    this.week.set(weekOf(value));
  }

  protected shiftWeek(weeks: number): void {
    const from = this.resolved();
    if (from !== '') this.week.set(weekPlus(from, weeks));
  }

  protected readonly columns = computed<readonly TableColumn<MaterialBoardRow>[]>(() => {
    this.lang();
    const number = (key: MaterialCount): TableColumn<MaterialBoardRow> => ({
      key,
      header: this.t(`materials.card.${key}`),
      align: 'end',
    });
    return [
      { key: 'fullName', header: this.t('materials.column.teacher') },
      number('onTime'),
      number('late'),
      number('missing'),
      number('reviewed'),
    ];
  });

  protected readonly rowColumns = computed<readonly TableColumn<MaterialRow>[]>(() => {
    this.lang();
    return [
      { key: 'className', header: this.t('materials.column.class') },
      { key: 'subject', header: this.t('materials.column.subject') },
      { key: 'status', header: this.t('materials.column.status') },
      { key: 'attachmentName', header: this.t('materials.column.file') },
      { key: 'note', header: this.t('materials.column.note') },
      { key: 'review', header: this.t('materials.column.review') },
      { key: 'act', header: '' },
    ];
  });

  protected readonly trackTeacher = (row: MaterialBoardRow): string => row.teacherId ?? '';
  protected readonly trackRow = (row: MaterialRow): string => rowKey(row);

  private readonly opened = signal<string | null>(null);
  protected readonly isOpen = (row: MaterialBoardRow): boolean => this.opened() === row.teacherId;

  protected toggle(row: MaterialBoardRow): void {
    const id = row.teacherId ?? '';
    this.opened.update((open) => (open === id ? null : id));
  }

  protected countsOf(row: MaterialBoardRow, card: MaterialCount): number {
    return countOf(row.counts, card);
  }

  // ---- marking one row reviewed ----------------------------------------------------------------

  protected readonly reviewing = signal<MaterialRow | null>(null);
  protected readonly dialogOpen = signal(false);
  protected readonly comment = signal('');
  protected readonly failure = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly opening = signal<string | null>(null);

  protected readonly canReview = canReview;
  protected readonly isReviewed = isReviewed;
  protected readonly statusOf = statusOf;

  protected review(row: MaterialRow): void {
    this.reviewing.set(row);
    this.comment.set('');
    this.failure.set(null);
    this.dialogOpen.set(true);
  }

  protected onDialog(open: boolean): void {
    this.dialogOpen.set(open);
    if (!open) this.reviewing.set(null);
  }

  protected confirmReview(): void {
    const row = this.reviewing();
    const id = row?.submissionId ?? '';
    if (id === '' || this.saving()) return;
    const comment = this.comment().trim();
    this.saving.set(true);
    this.failure.set(null);
    this.api
      .reviewMaterial(id, comment === '' ? {} : { comment }, 'body', false, {
        context: silentErrors(),
      })
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.dialogOpen.set(false);
          this.reviewing.set(null);
          this.board.reload();
        },
        error: (error: unknown) => {
          this.saving.set(false);
          const error409 = apiErrorOf(error);
          this.failure.set(
            error409?.code === 'conflict'
              ? this.t('materials.alreadyReviewed')
              : (error409?.message ?? this.t('band.unreachable')),
          );
        },
      });
  }

  // ---- the file, and the same rows as a file ----------------------------------------------------

  protected openFile(row: MaterialRow): void {
    const id = row.attachmentId ?? '';
    if (id === '' || this.opening() !== null) return;
    // Reserved inside the click: a tab opened after the bytes arrive is a blocked popup.
    const tab = reserveTab();
    this.opening.set(id);
    this.media.attachmentFile(id).subscribe({
      next: (blob) => {
        this.opening.set(null);
        tab.show(blob, row.attachmentName ?? 'material');
      },
      error: () => {
        this.opening.set(null);
        tab.cancel();
        this.failure.set(this.t('chat.attachment.openFailed'));
      },
    });
  }

  protected readonly exporting = signal(false);

  protected exportCsv(): void {
    const week = this.week();
    this.exporting.set(true);
    this.api.materialBoardCsv(week === '' ? undefined : week).subscribe({
      next: (csv) => {
        this.exporting.set(false);
        saveFile(withBom(csv), csvName(this.resolved()), 'text/csv;charset=utf-8');
      },
      error: () => this.exporting.set(false),
    });
  }

  // ---- labels -----------------------------------------------------------------------------------

  protected readonly dueLabel = computed(() => {
    this.lang();
    const dueAt = this.board.value().dueAt ?? '';
    return dueAt === '' ? '' : this.t('materials.dueAt', { at: this.at(dueAt), zone: this.timeZone() });
  });

  protected readonly weekLabel = computed(() => {
    this.lang();
    const week = this.resolved();
    return week === '' ? '' : this.t('materials.weekOf', { date: this.day(week) });
  });

  protected statusLabel(status: MaterialStatus): string {
    this.lang();
    return this.t(`materials.status.${status}`);
  }

  protected subjectLabel(subject: string | undefined): string {
    this.lang();
    return subject === undefined || subject === ''
      ? '—'
      : translateOr(this.transloco, `subject.${subject}`, subject);
  }

  protected reviewLabel(row: MaterialRow): string {
    this.lang();
    if (!isReviewed(row)) return '—';
    const comment = (row.reviewComment ?? '').trim();
    const head = this.t('materials.reviewedBy', {
      name: row.reviewedByName ?? this.t('materials.aLeader'),
      at: row.reviewedAt ? this.at(row.reviewedAt) : '',
    });
    return comment === '' ? head : `${head} — ${comment}`;
  }

  protected at(iso: string): string {
    return new Intl.DateTimeFormat(this.transloco.getActiveLang(), {
      day: 'numeric',
      month: 'short',
      hour: 'numeric',
      minute: '2-digit',
      timeZone: this.timeZone(),
    }).format(new Date(iso));
  }

  private day(date: string): string {
    return new Intl.DateTimeFormat(this.transloco.getActiveLang(), {
      day: 'numeric',
      month: 'short',
      timeZone: 'UTC',
    }).format(new Date(`${date}T00:00:00Z`));
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}
