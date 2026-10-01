/* hq-flag: none (shell) — gated by `coordinator.lesson.read`, the key R2 puts on
   `GET /coordinator/lessons`. The lesson pipeline ships with the dashboard rather than behind a
   toggle; see `features/lessons/lessons.page.ts`'s header for the same reasoning. */
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { AdminLessonStatusEnum } from '../../api';
import { activeLang } from '../../core/i18n/active-lang';
import {
  type SelectOption,
  type TableColumn,
  EmptyStateComponent,
  InputComponent,
  PageComponent,
  SelectComponent,
  SkeletonComponent,
  type Tab,
  TableComponent,
  TabsComponent,
} from '../../ui';
import { LessonApiService } from '../lessons/lesson-api.service';
import { isRunningStatus } from '../lessons/lessons.models';
import { CoordinatorReadFailedComponent } from './read-failed.component';
import { StaffScopeService } from './staff-scope.service';

interface LessonRow {
  readonly id: string;
  readonly title: string;
  readonly className: string;
  readonly teacherName: string;
  readonly date: string;
  readonly status: string;
}

/**
 * **Every status a lesson can be in, in the order the pipeline passes through them** (D2, list 3).
 *
 * The filter used to offer four of them and send each as `?status=`, while the server knew only
 * its own three coarse words — so three of the four chips answered 400 "status is draft, ready or
 * published" and the fourth was the only one that worked. The list is now the contract's own enum,
 * which is what the teacher's lesson list labels its rows with and what `GET /coordinator/lessons`
 * accepts since S1: a chip per status, the status itself as the parameter, nothing invented here.
 */
export const LESSON_STATUS_CHIPS: readonly AdminLessonStatusEnum[] = [
  AdminLessonStatusEnum.DRAFT,
  AdminLessonStatusEnum.UPLOADING,
  AdminLessonStatusEnum.ANALYZING,
  AdminLessonStatusEnum.NEEDS_REVIEW,
  AdminLessonStatusEnum.GENERATING,
  AdminLessonStatusEnum.REVIEW,
  AdminLessonStatusEnum.PAUSED,
  AdminLessonStatusEnum.PUBLISHED,
  AdminLessonStatusEnum.ERROR,
];

/** The chip that is no filter at all. Never sent: `status` is simply left off the request. */
const ANY_STATUS = 'all';
type StatusChip = AdminLessonStatusEnum | typeof ANY_STATUS;

/**
 * The badge a status wears — the teacher's list's own rule (`LessonsPage.statusTone`): failed is
 * the error ramp, published the success one, a job still running the brand, and everything waiting
 * on a person the light pill. The word is always inside the pill, so colour never carries it alone.
 */
export function lessonStatusTone(status: string): 'error' | 'success' | 'primary' | 'light' {
  if (status === 'error') return 'error';
  if (status === 'published') return 'success';
  return isRunningStatus(status as AdminLessonStatusEnum) ? 'primary' : 'light';
}

/**
 * Lessons (R5, `docs/coordinator-flow.md` §5): every lesson of every class in scope, narrowed by
 * class, status and a date range, each row opening the lesson read-only.
 *
 * Her own screen rather than the teacher's list. That list is built around a curriculum → grade
 * chooser fed by `GET /teacher/options`, which is a teacher's profile and answers 403 for her;
 * bending it would have meant a third branch through every one of its filters to reach a shorter
 * screen than this. The lesson *page* is shared, because that is the screen with the content in
 * it and a second copy of it would drift.
 *
 * The filters are server-side — `GET /coordinator/lessons` takes all four — so the row count is
 * the truth about her scope rather than the truth about what one page of it happened to hold.
 */
@Component({
  selector: 'hq-coordinator-lessons-page',
  imports: [
    CoordinatorReadFailedComponent,
    EmptyStateComponent,
    InputComponent,
    PageComponent,
    RouterLink,
    SelectComponent,
    SkeletonComponent,
    TableComponent,
    TabsComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-page [title]="'nav.allLessons' | transloco" [subtitle]="co.scoped('lessons.subtitle') | transloco">
      <div class="co-filters">
        <hq-select
          [label]="'coordinator.lessons.byClass' | transloco"
          [options]="classOptions()"
          [placeholder]="'coordinator.lessons.allClasses' | transloco"
          [value]="classId()"
          (valueChange)="classId.set($event)"
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

      <hq-tabs
        variant="chips"
        class="co-status"
        [tabs]="statusTabs()"
        [selected]="status()"
        (selectedChange)="status.set($event)"
        [label]="'coordinator.lessons.byStatus' | transloco"
      />

      @if (lessons.isLoading()) {
        <hq-skeleton [loading]="true" [lines]="6" [label]="'lessons.loading' | transloco" />
      } @else if (lessons.error()) {
        <!-- Its own resource, so its own error: "no lessons match these filters" would be a lie
             about the filters when what failed was the request. -->
        <hq-coordinator-read-failed (retry)="lessons.reload()" />
      } @else {
        <hq-table
          [rows]="rows()"
          [columns]="columns()"
          [cellTemplate]="cell"
          [trackBy]="trackRow"
          [label]="'nav.allLessons' | transloco"
        >
          <hq-empty-state table-empty [message]="'coordinator.lessons.empty' | transloco" />
        </hq-table>
      }

      <ng-template #cell let-row let-column="column">
        @switch (column.key) {
          @case ('title') {
            <a [routerLink]="[co.base() + '/lessons', row.id]">{{ row.title }}</a>
          }
          @case ('className') {
            {{ row.className }}
          }
          @case ('teacherName') {
            {{ row.teacherName }}
          }
          @case ('date') {
            {{ row.date }}
          }
          @case ('status') {
            <span class="hq-badge" [class]="'hq-badge--' + tone(row.status)">
              {{ 'lessons.status.' + row.status | transloco }}
            </span>
          }
        }
      </ng-template>
    </hq-page>
  `,
  styles: `
    .co-filters {
      display: flex;
      flex-wrap: wrap;
      gap: var(--hq-space-16);
      margin-block-end: var(--hq-space-16);
    }

    .co-status {
      display: block;
      margin-block-end: var(--hq-space-16);
    }
  `,
})
export class CoordinatorLessonsPage {
  private readonly api = inject(LessonApiService);
  protected readonly co = inject(StaffScopeService);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  protected readonly classId = signal('');
  protected readonly status = signal<StatusChip>(ANY_STATUS);
  protected readonly from = signal('');
  protected readonly to = signal('');

  protected readonly lessons = rxResource({
    params: () => ({
      classId: this.classId() || undefined,
      // A manager's route still knows only its three coarse words (MH0), so hers is never asked
      // by status at all — the rows are narrowed below, which answers every chip for her too.
      // (Her branch does not read the chip, so pressing one is not a second request either.)
      status: this.co.isManager() || this.status() === ANY_STATUS ? undefined : this.status(),
      from: this.from() || undefined,
      to: this.to() || undefined,
    }),
    stream: ({ params }) => this.api.list(params),
    defaultValue: [],
  });

  protected readonly rows = computed<readonly LessonRow[]>(() => {
    this.lang();
    const untitled = this.transloco.translate<string>('lessons.untitled');
    const wanted = this.status();
    return [...this.lessons.value()]
      // The server's filter is exact for every word but one: `draft` is still its coarse
      // "everything not yet ready or published" (the teacher's week), which would put Uploading
      // and Failed rows under the Draft chip. Narrowing here makes the chip mean its own word —
      // and is all that narrows a manager's rows, whose route is not asked by status.
      .filter((lesson) => wanted === ANY_STATUS || lesson.status === wanted)
      .map((lesson) => ({
        id: lesson.id,
        title: (lesson.title ?? '').trim() || untitled,
        className: lesson.className ?? '',
        teacherName: lesson.teacherName ?? '',
        date: lesson.date,
        status: lesson.status,
      }))
      .sort((a, b) => b.date.localeCompare(a.date) || a.className.localeCompare(b.className));
  });

  protected readonly columns = computed<readonly TableColumn<LessonRow>[]>(() => {
    this.lang();
    return [
      { key: 'title', header: this.t('lessons.columns.title'), width: '32%' },
      { key: 'className', header: this.t('lessons.columns.class') },
      { key: 'teacherName', header: this.t('coordinator.teachers.columns.name') },
      { key: 'date', header: this.t('lessons.columns.date') },
      { key: 'status', header: this.t('lessons.columns.status'), width: '16%' },
    ];
  });

  protected readonly classOptions = computed<readonly SelectOption[]>(() =>
    this.co.classes().map((row) => ({ value: row.classId, label: row.className })),
  );

  protected readonly statusTabs = computed<readonly Tab<StatusChip>[]>(() => {
    this.lang();
    return [
      { id: ANY_STATUS, label: this.t('coordinator.lessons.allStatuses') },
      ...LESSON_STATUS_CHIPS.map((status) => ({ id: status, label: this.t(`lessons.status.${status}`) })),
    ];
  });

  protected readonly tone = lessonStatusTone;

  protected readonly trackRow = (row: LessonRow): string => row.id;

  private t(key: string): string {
    return this.transloco.translate<string>(key);
  }
}
