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
  TableComponent,
} from '../../ui';
import { LessonApiService } from '../lessons/lesson-api.service';
import { CoordinatorReadFailedComponent } from './read-failed.component';
import { CoordinatorService } from './coordinator.service';

interface LessonRow {
  readonly id: string;
  readonly title: string;
  readonly className: string;
  readonly teacherName: string;
  readonly date: string;
  readonly status: string;
}

/** The statuses worth narrowing by: the two that need someone, and the two ordinary ends. */
const STATUSES: readonly AdminLessonStatusEnum[] = [
  AdminLessonStatusEnum.NEEDS_REVIEW,
  AdminLessonStatusEnum.ERROR,
  AdminLessonStatusEnum.REVIEW,
  AdminLessonStatusEnum.PUBLISHED,
];

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
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-page [title]="'nav.allLessons' | transloco" [subtitle]="'coordinator.lessons.subtitle' | transloco">
      <div class="co-filters">
        <hq-select
          [label]="'coordinator.lessons.byClass' | transloco"
          [options]="classOptions()"
          [placeholder]="'coordinator.lessons.allClasses' | transloco"
          [value]="classId()"
          (valueChange)="classId.set($event)"
        />
        <hq-select
          [label]="'coordinator.lessons.byStatus' | transloco"
          [options]="statusOptions()"
          [placeholder]="'coordinator.lessons.allStatuses' | transloco"
          [value]="status()"
          (valueChange)="status.set($event)"
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
            <a [routerLink]="['/coordinator/lessons', row.id]">{{ row.title }}</a>
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
            <span
              class="hq-badge"
              [class.hq-badge--error]="row.status === 'error'"
              [class.hq-badge--success]="row.status === 'published'"
              [class.hq-badge--warning]="row.status === 'needs_review'"
            >
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
  `,
})
export class CoordinatorLessonsPage {
  private readonly api = inject(LessonApiService);
  private readonly co = inject(CoordinatorService);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  protected readonly classId = signal('');
  protected readonly status = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');

  protected readonly lessons = rxResource({
    params: () => ({
      classId: this.classId() || undefined,
      status: this.status() || undefined,
      from: this.from() || undefined,
      to: this.to() || undefined,
    }),
    stream: ({ params }) => this.api.list(params),
    defaultValue: [],
  });

  protected readonly rows = computed<readonly LessonRow[]>(() => {
    this.lang();
    const untitled = this.transloco.translate<string>('lessons.untitled');
    return [...this.lessons.value()]
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

  protected readonly statusOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return STATUSES.map((status) => ({ value: status, label: this.t(`lessons.status.${status}`) }));
  });

  protected readonly trackRow = (row: LessonRow): string => row.id;

  private t(key: string): string {
    return this.transloco.translate<string>(key);
  }
}
