/* hq-flag: none (shell) — routed through `areaRoutes`, whose `my-materials` row carries
   `FLAGS.teachingMaterials`; the server's `TeacherMaterialsController` is behind the same key. */
import { ChangeDetectionStrategy, Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { map, switchMap } from 'rxjs/operators';
import { of } from 'rxjs';
import {
  type AttachmentRef,
  type MaterialRow,
  type TeacherMaterials,
  TeachingMaterialApi,
  apiErrorOf,
} from '../../api';
import { formatBytes, CHAT_ACCEPT } from '../../core/chat/chat-attachments';
import { reserveTab } from '../../core/download/download';
import { silentErrors } from '../../core/http/error.interceptor';
import { activeLang } from '../../core/i18n/active-lang';
import { MediaService } from '../../core/media/media.service';
import {
  type Tab,
  type TableColumn,
  BandComponent,
  ButtonComponent,
  CardComponent,
  DialogComponent,
  EmptyStateComponent,
  PageComponent,
  SkeletonComponent,
  TableComponent,
  TabsComponent,
  TextareaComponent,
} from '../../ui';
import { translateOr } from '../coordinator/coordinator.labels';
import {
  type MaterialCount,
  type MaterialStatus,
  MATERIAL_COUNTS,
  canReplace,
  countOf,
  isReviewed,
  materialRefusalOf,
  rowKey,
  statusOf,
  weekOf,
  weekPlus,
} from './materials.models';

type WeekTab = 'this' | 'next';

const EMPTY: TeacherMaterials = { rows: [] };

/**
 * **My teaching material / دفتر التحضير** (feature 31) — the weekly lesson preparation a teacher
 * hands in for each class and subject she teaches, by the deadline the Admin set.
 *
 * **The rows are her assignments**, never a list she keeps: `GET /teacher/materials?week=`
 * answers one row per (class, subject) `teaching_assignments` gives her for that teaching week,
 * each with its status, her file, her note and a leader's review when there is one.
 *
 * **Two weeks and no more.** This week's and next week's are the only ones the page offers,
 * because material for a week is due in the week before it — she prepares the coming week while
 * the current one runs. The tab does not compute a date of its own: the server answers which
 * week "now" is, and next week is seven days on from that, so a school whose week starts on a
 * Sunday and one that starts on a Monday both get the right two tabs.
 *
 * **Reviewed is read-only.** Until a leader has reviewed a row she may replace it as often as she
 * likes; after that the server answers 409 and the row shows the reviewer's name and comment
 * instead of a button. The 409 is said in her language in the dialog's own band rather than the
 * shell's, because it is about the one row she is looking at.
 */
@Component({
  selector: 'hq-my-materials-page',
  imports: [
    BandComponent,
    ButtonComponent,
    CardComponent,
    DialogComponent,
    EmptyStateComponent,
    PageComponent,
    SkeletonComponent,
    TableComponent,
    TabsComponent,
    TextareaComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './my-materials.page.html',
  styleUrl: './materials.scss',
})
export class MyMaterialsPage {
  private readonly api = inject(TeachingMaterialApi);
  private readonly media = inject(MediaService);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  protected readonly cards = MATERIAL_COUNTS;
  protected readonly accept = CHAT_ACCEPT;

  /**
   * The reminder's `?week=` (`MaterialReminderJob` writes `material=reminder&week=…`, which
   * `notificationTarget` turns into this screen's query): the bell names the week whose material
   * is still missing, which is next week's by the time a reminder is sent.
   */
  private readonly weekParam = toSignal(
    inject(ActivatedRoute).queryParamMap.pipe(map((params) => weekOf(params.get('week')))),
    { initialValue: '' },
  );

  /** `''` is "the week the server calls now" — the one request that needs no date at all. */
  protected readonly week = linkedSignal<string, string>({
    source: () => this.weekParam(),
    computation: (param) => param,
  });

  protected readonly materials = rxResource<TeacherMaterials, string>({
    params: () => this.week(),
    stream: ({ params: week }) => this.api.myMaterials(week === '' ? undefined : week),
    defaultValue: EMPTY,
  });

  protected readonly rows = computed(() => this.materials.value().rows ?? []);
  protected readonly counts = computed(() => this.materials.value().counts);
  protected readonly timeZone = computed(() => this.materials.value().timeZone ?? 'UTC');

  /**
   * The week start the server resolved the current one to, remembered so the Next week tab has a
   * date to ask for. A `linkedSignal` rather than an effect: it is a read of the two signals it
   * depends on, and it must survive the next week's own answer coming back.
   */
  private readonly thisWeek = linkedSignal<{ asked: string; answered: string }, string>({
    source: () => ({ asked: this.week(), answered: this.materials.value().week ?? '' }),
    computation: ({ asked, answered }, previous) =>
      asked === '' && answered !== '' ? answered : (previous?.value ?? ''),
  });

  private readonly nextWeek = computed(() => (this.thisWeek() === '' ? '' : weekPlus(this.thisWeek(), 1)));

  protected readonly selected = computed<WeekTab>(() =>
    this.week() === '' || this.week() === this.thisWeek() ? 'this' : 'next',
  );

  protected readonly tabs = computed<readonly Tab<WeekTab>[]>(() => {
    this.lang();
    return [
      { id: 'this', label: this.t('materials.thisWeek') },
      { id: 'next', label: this.t('materials.nextWeek'), disabled: this.nextWeek() === '' },
    ];
  });

  protected pickWeek(tab: WeekTab): void {
    this.week.set(tab === 'this' ? '' : this.nextWeek());
  }

  protected count(card: MaterialCount): number {
    return countOf(this.counts(), card);
  }

  protected readonly columns = computed<readonly TableColumn<MaterialRow>[]>(() => {
    this.lang();
    return [
      { key: 'className', header: this.t('materials.column.class') },
      { key: 'subject', header: this.t('materials.column.subject') },
      { key: 'status', header: this.t('materials.column.status') },
      { key: 'attachmentName', header: this.t('materials.column.file') },
      { key: 'note', header: this.t('materials.column.note') },
      { key: 'review', header: this.t('materials.column.review') },
      { key: 'hand', header: '' },
    ];
  });

  protected readonly trackRow = (row: MaterialRow): string => rowKey(row);

  // ---- the dialog: hand it in, or hand a different one in over it ------------------------------

  protected readonly editing = signal<MaterialRow | null>(null);
  protected readonly dialogOpen = signal(false);
  protected readonly note = signal('');
  protected readonly file = signal<File | null>(null);
  protected readonly refusal = signal<string | null>(null);
  protected readonly failure = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly opening = signal<string | null>(null);

  protected readonly isReviewed = isReviewed;
  protected readonly canReplace = canReplace;
  protected readonly statusOf = statusOf;

  protected open(row: MaterialRow): void {
    this.editing.set(row);
    this.note.set(row.note ?? '');
    this.file.set(null);
    this.refusal.set(null);
    this.failure.set(null);
    this.dialogOpen.set(true);
  }

  protected onDialog(open: boolean): void {
    this.dialogOpen.set(open);
    if (!open) this.editing.set(null);
  }

  /** At least one of the two, which is what `PUT /teacher/materials/{classId}/{subject}` requires. */
  protected readonly valid = computed(() => this.file() !== null || this.note().trim() !== '');

  protected pickFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    const picked = input.files?.[0] ?? null;
    input.value = '';
    this.failure.set(null);
    if (picked === null) return;
    const refusal = materialRefusalOf(picked);
    if (refusal !== null) {
      this.file.set(null);
      this.refusal.set(this.t(`chat.upload.${refusal}`, { name: picked.name }));
      return;
    }
    this.refusal.set(null);
    this.file.set(picked);
  }

  protected clearFile(): void {
    this.file.set(null);
    this.refusal.set(null);
  }

  protected fileLabel(file: File): string {
    return `${file.name} · ${formatBytes(file.size)}`;
  }

  /** Upload first when there is a file, then hand the row in; one of the two is always present. */
  protected submit(): void {
    const row = this.editing();
    if (row === null || !this.valid() || this.saving()) return;
    const classId = row.classId ?? '';
    const subject = row.subject ?? '';
    const note = this.note().trim();
    const picked = this.file();
    const week = this.week();
    this.saving.set(true);
    this.failure.set(null);

    const upload = picked === null ? of<AttachmentRef | null>(null) : this.uploadOf(picked);
    upload
      .pipe(
        switchMap((attachment) =>
          this.api.submitMaterial(
            classId,
            subject,
            {
              ...(attachment === null ? {} : { attachmentId: attachment.id }),
              ...(note === '' ? {} : { note }),
            },
            week === '' ? undefined : week,
            'body',
            false,
            { context: silentErrors() },
          ),
        ),
      )
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.dialogOpen.set(false);
          this.editing.set(null);
          this.materials.reload();
        },
        error: (error: unknown) => {
          this.saving.set(false);
          this.failure.set(apiErrorOf(error)?.message ?? this.t('band.unreachable'));
        },
      });
  }

  private uploadOf(file: File) {
    return this.api.uploadMaterial(file, 'body', false, { context: silentErrors() });
  }

  // ---- her own file, read back through the bearer ---------------------------------------------

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

  // ---- labels ---------------------------------------------------------------------------------

  /** The deadline in the school's own zone, which is the only zone it means anything in. */
  protected readonly dueLabel = computed(() => {
    this.lang();
    const dueAt = this.materials.value().dueAt ?? '';
    if (dueAt === '') return '';
    return this.t('materials.dueAt', { at: this.at(dueAt), zone: this.timeZone() });
  });

  protected readonly weekLabel = computed(() => {
    this.lang();
    const week = this.materials.value().week ?? '';
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
    const by = row.reviewedByName ?? '';
    const comment = (row.reviewComment ?? '').trim();
    const when = row.reviewedAt ? this.at(row.reviewedAt) : '';
    const head = this.t('materials.reviewedBy', { name: by || this.t('materials.aLeader'), at: when });
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
