/* hq-flag: none (shell) — gated by `coordinator.read`, the key R2 puts on
   `GET /coordinator/teachers`, not by a flag: the people she supervises are the role, not a
   feature of the school. */
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { activeLang } from '../../core/i18n/active-lang';
import { FeatureDirective } from '../../core/flags/feature.directive';
import { CanDirective } from '../../core/permissions/can.directive';
import {
  type TableColumn,
  BandComponent,
  ButtonComponent,
  EmptyStateComponent,
  InputComponent,
  PageComponent,
  SkeletonComponent,
  TableComponent,
} from '../../ui';
import { StaffThreadService } from '../management/staff-thread.service';
import { CoordinatorReadFailedComponent } from './read-failed.component';
import { StaffScopeService } from './staff-scope.service';
import { translateOr } from './coordinator.labels';

interface TeacherRow {
  readonly userId: string;
  readonly name: string;
  readonly email: string;
  readonly phone: string;
  readonly subjects: string;
  readonly sections: string;
  /** Who supervises her, and in which subject — MH1 put it on both `teachers` rows. */
  readonly coordinators: string;
  /** How many of her sections have today's lesson, out of how many she teaches in scope. */
  readonly todayDone: number;
  readonly todayTotal: number;
}

/**
 * Teachers (R5, `docs/coordinator-flow.md` §3): who teaches her subject, and whether today has
 * happened yet in each of their sections.
 *
 * **One component, two areas**: a coordinator reads `GET /coordinator/teachers`, a department
 * manager `GET /management/teachers`, and both answer the same `CoordinatorTeacher` — which since
 * MH1 carries `phone` and the `coordinators` who supervise each teacher. Both columns are drawn for
 * both readers: "who else supervises Sara" is a question a coordinator of one subject has about a
 * teacher who also takes another.
 *
 * **A row has one action**: Message, which opens the direct thread with that teacher and shows it.
 * A manager's goes to RM2's `POST /management/chat/threads` (MH2 item 2) and a coordinator's to
 * `POST /coordinator/chat/threads {teacherUserId}` (D2, list 3) — `StaffThreadService` picks the
 * route by role, and the button is behind the chat key of whichever area is reading.
 *
 * "Today" is computed from `GET /coordinator/classes` rather than asked for separately — that
 * response already carries `todayStatus` per section, and a second endpoint answering the same
 * question per teacher would be a second thing to keep in step.
 */
@Component({
  selector: 'hq-coordinator-teachers-page',
  imports: [
    BandComponent,
    ButtonComponent,
    CanDirective,
    CoordinatorReadFailedComponent,
    EmptyStateComponent,
    FeatureDirective,
    InputComponent,
    PageComponent,
    SkeletonComponent,
    TableComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-page [title]="'nav.teachers' | transloco" [subtitle]="co.scoped('teachers.subtitle') | transloco">
      @if (threads.failed()) {
        <hq-band
          variant="error"
          [open]="true"
          [title]="'band.failed' | transloco"
          (dismissed)="threads.failed.set(false)"
        >
          {{ 'management.message.failed' | transloco }}
        </hq-band>
      }

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
          <hq-empty-state table-empty [message]="co.scoped('teachers.empty') | transloco" />
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
          @case ('phone') {
            @if (row.phone) {
              <a [href]="'tel:' + row.phone" dir="ltr">{{ row.phone }}</a>
            } @else {
              <span class="hq-muted">—</span>
            }
          }
          @case ('sections') {
            {{ row.sections }}
          }
          @case ('coordinators') {
            {{ row.coordinators || '—' }}
          }
          @case ('actions') {
            <!-- The key of the route it presses, and the flag that route carries: with chat off
                 there is no Messages screen for the thread to open in. -->
            <ng-container *hqFeature="'chat'">
              <hq-button
                *hqCan="chatKey()"
                variant="secondary"
                [loading]="threads.pending() === row.userId"
                (pressed)="message(row)"
              >
                {{ 'management.message.action' | transloco }}
              </hq-button>
            </ng-container>
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
  protected readonly co = inject(StaffScopeService);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();
  protected readonly threads = inject(StaffThreadService);

  /** The key on the route the button presses: each area's own `…/chat/threads`. */
  protected readonly chatKey = computed(() => (this.co.isManager() ? 'management.chat' : 'coordinator.chat'));

  protected readonly search = signal('');

  protected readonly columns = computed<readonly TableColumn<TeacherRow>[]>(() => {
    this.lang();
    return [
      { key: 'name', header: this.t('coordinator.teachers.columns.name'), width: '16%' },
      { key: 'email', header: this.t('coordinator.teachers.columns.email'), width: '18%' },
      { key: 'phone', header: this.t('management.columns.phone'), width: '12%' },
      { key: 'subjects', header: this.t('coordinator.teachers.columns.subjects'), width: '12%' },
      { key: 'sections', header: this.t('coordinator.teachers.columns.sections') },
      { key: 'coordinators', header: this.t('management.columns.coordinators'), width: '16%' },
      { key: 'today', header: this.t('coordinator.teachers.columns.today'), width: '10%' },
      { key: 'actions', header: this.t('ui.actions'), width: '12%' },
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
          phone: teacher.phone ?? '',
          subjects: (teacher.subjects ?? [])
            .map((subject) => translateOr(this.transloco, `subject.${subject}`, subject))
            .join(' · '),
          sections: mine.map((row) => row.className).join(' · '),
          // The name and the subject together: a teacher of two subjects has two supervisors, and
          // "Rasha Kamal" alone would not say which of them to go to about which lesson.
          coordinators: (teacher.coordinators ?? [])
            .map((who) =>
              this.t2('management.columns.coordinatorOf', {
                who: who.displayName ?? '',
                subject: translateOr(this.transloco, `subject.${who.subject}`, who.subject ?? ''),
              }),
            )
            .join(' · '),
          todayDone: mine.filter((row) => row.todayLessonId !== null).length,
          todayTotal: mine.length,
        };
      })
      .filter(
        (row) =>
          needle === '' ||
          row.name.toLowerCase().includes(needle) ||
          row.email.toLowerCase().includes(needle) ||
          row.phone.includes(needle) ||
          row.sections.toLowerCase().includes(needle),
      )
      .sort((a, b) => a.name.localeCompare(b.name));
  });

  protected readonly trackRow = (row: TeacherRow): string => row.userId;

  protected message(row: TeacherRow): void {
    this.threads.open(row.userId, { teacherUserId: row.userId });
  }

  private t(key: string): string {
    return this.transloco.translate<string>(key);
  }

  private t2(key: string, params: Record<string, string>): string {
    return this.transloco.translate<string>(key, params);
  }
}
