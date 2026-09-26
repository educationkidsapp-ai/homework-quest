/* hq-flag: none (shell) — gated by `coordinator.read`, the key R2 puts on
   `GET /coordinator/teachers`, not by a flag: the people she supervises are the role, not a
   feature of the school. */
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { activeLang } from '../../core/i18n/active-lang';
import {
  type TableColumn,
  EmptyStateComponent,
  InputComponent,
  PageComponent,
  SkeletonComponent,
  TableComponent,
} from '../../ui';
import { CoordinatorReadFailedComponent } from './read-failed.component';
import { CoordinatorService } from './coordinator.service';
import { translateOr } from './coordinator.labels';

interface TeacherRow {
  readonly userId: string;
  readonly name: string;
  readonly email: string;
  readonly subjects: string;
  readonly sections: string;
  /** How many of her sections have today's lesson, out of how many she teaches in scope. */
  readonly todayDone: number;
  readonly todayTotal: number;
}

/**
 * Teachers (R5, `docs/coordinator-flow.md` §3): who teaches her subject, and whether today has
 * happened yet in each of their sections.
 *
 * Read-only in the strongest sense available: there is no overflow menu, no row action and no
 * permission on this screen that could grow one. Her half of a problem here is a message, and
 * that is R7's Messages screen rather than a button on this table.
 *
 * "Today" is computed from `GET /coordinator/classes` rather than asked for separately — that
 * response already carries `todayStatus` per section, and a second endpoint answering the same
 * question per teacher would be a second thing to keep in step.
 */
@Component({
  selector: 'hq-coordinator-teachers-page',
  imports: [
    CoordinatorReadFailedComponent,
    EmptyStateComponent,
    InputComponent,
    PageComponent,
    SkeletonComponent,
    TableComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-page [title]="'nav.teachers' | transloco" [subtitle]="'coordinator.teachers.subtitle' | transloco">
      @if (co.loading()) {
        <hq-skeleton [loading]="true" [lines]="6" [label]="'ui.loading' | transloco" />
      } @else if (co.failed()) {
        <!-- Never the empty state on a failed read: "no teachers teach your subject yet" is a
             statement about her school, and this read did not happen. -->
        <hq-coordinator-read-failed (retry)="co.reload()" />
      } @else {
        <div data-hq-search>
          <hq-input
            type="search"
            keycap="/"
            [label]="'coordinator.teachers.search' | transloco"
            [placeholder]="'coordinator.teachers.search' | transloco"
            [value]="search()"
            (valueChange)="search.set($event)"
          />
        </div>

        <hq-table
          [rows]="rows()"
          [columns]="columns()"
          [cellTemplate]="cell"
          [trackBy]="trackRow"
          [label]="'nav.teachers' | transloco"
        >
          <hq-empty-state table-empty [message]="'coordinator.teachers.empty' | transloco" />
        </hq-table>
      }

      <ng-template #cell let-row let-column="column">
        @switch (column.key) {
          @case ('name') {
            {{ row.name }}
          }
          @case ('email') {
            {{ row.email }}
          }
          @case ('subjects') {
            {{ row.subjects }}
          }
          @case ('sections') {
            {{ row.sections }}
          }
          @case ('today') {
            <span
              class="hq-badge"
              [class.hq-badge--success]="row.todayTotal > 0 && row.todayDone === row.todayTotal"
              [class.hq-badge--warning]="row.todayDone < row.todayTotal"
            >
              {{ 'coordinator.teachers.today' | transloco: { done: row.todayDone, total: row.todayTotal } }}
            </span>
          }
        }
      </ng-template>
    </hq-page>
  `,
})
export class CoordinatorTeachersPage {
  protected readonly co = inject(CoordinatorService);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  protected readonly search = signal('');

  protected readonly columns = computed<readonly TableColumn<TeacherRow>[]>(() => {
    this.lang();
    return [
      { key: 'name', header: this.t('coordinator.teachers.columns.name'), width: '22%' },
      { key: 'email', header: this.t('coordinator.teachers.columns.email'), width: '24%' },
      { key: 'subjects', header: this.t('coordinator.teachers.columns.subjects'), width: '16%' },
      { key: 'sections', header: this.t('coordinator.teachers.columns.sections') },
      { key: 'today', header: this.t('coordinator.teachers.columns.today'), width: '16%' },
    ];
  });

  protected readonly rows = computed<readonly TeacherRow[]>(() => {
    this.lang();
    const needle = this.search().trim().toLowerCase();
    const classes = this.co.classes();
    return this.co
      .teachers()
      .map((teacher) => {
        // Her sections *in scope*, which is what the numbers must count: `CoordinatorTeacher`
        // lists every assignment the teacher holds, and a Math coordinator looking at a teacher
        // who also takes Science must not be shown the Science section as hers to watch. Joined
        // on the id, never on the name — two teachers of forty may share one.
        const mine = classes.filter((row) => row.teacherId === (teacher.userId ?? ''));
        return {
          userId: teacher.userId ?? '',
          name: teacher.displayName ?? '',
          email: teacher.email ?? '',
          subjects: (teacher.subjects ?? [])
            .map((subject) => translateOr(this.transloco, `subject.${subject}`, subject))
            .join(' · '),
          sections: mine.map((row) => row.className).join(' · '),
          todayDone: mine.filter((row) => row.todayLessonId !== null).length,
          todayTotal: mine.length,
        };
      })
      .filter(
        (row) =>
          needle === '' ||
          row.name.toLowerCase().includes(needle) ||
          row.email.toLowerCase().includes(needle) ||
          row.sections.toLowerCase().includes(needle),
      )
      .sort((a, b) => a.name.localeCompare(b.name));
  });

  protected readonly trackRow = (row: TeacherRow): string => row.userId;

  private t(key: string): string {
    return this.transloco.translate<string>(key);
  }
}
