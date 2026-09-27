/* hq-flag: none (shell) — gated by `management.read`, the key RM1 puts on
   `GET /management/coordinators`. The people she manages are the role, not a feature of the
   school; same reasoning as the coordinator's own Teachers screen. */
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { ManagementApi } from '../../api';
import { StaffAreaService } from '../../core/auth/staff-area';
import { activeLang } from '../../core/i18n/active-lang';
import {
  type TableColumn,
  EmptyStateComponent,
  InputComponent,
  PageComponent,
  SkeletonComponent,
  TableComponent,
} from '../../ui';
import { CoordinatorReadFailedComponent } from '../coordinator/read-failed.component';
import { translateOr } from '../coordinator/coordinator.labels';

interface CoordinatorRow {
  readonly userId: string;
  readonly name: string;
  readonly email: string;
  readonly subjects: string;
  readonly tracks: string;
  readonly sections: number;
}

/**
 * Coordinators (RM3a, DR5): the subject coordinators of her department.
 *
 * The one screen of hers with no counterpart in the coordinator's area, because it is the
 * relationship RM1 introduced — a coordinator is hers when one of her subject rows names a
 * department of hers **or names no track at all**, which is why a both-tracks coordinator shows
 * up for both managers and why the Tracks column says "both tracks" rather than a track name.
 *
 * Read-only in the strongest sense available: no row menu, no action, and no permission on this
 * screen that could grow one — RM1 is read-only everywhere but the staff register.
 */
@Component({
  selector: 'hq-management-coordinators-page',
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
    <hq-page
      [title]="'nav.coordinators' | transloco"
      [subtitle]="'management.coordinators.subtitle' | transloco"
    >
      @if (people.isLoading()) {
        <hq-skeleton [loading]="true" [lines]="6" [label]="'ui.loading' | transloco" />
      } @else if (people.error()) {
        <!-- Never the empty state on a failed read: "no coordinator carries your department" is
             a statement about her school, and this request did not happen. -->
        <hq-coordinator-read-failed (retry)="people.reload()" />
      } @else {
        <div data-hq-search>
          <hq-input
            type="search"
            keycap="/"
            [label]="'management.coordinators.search' | transloco"
            [placeholder]="'management.coordinators.search' | transloco"
            [value]="search()"
            (valueChange)="search.set($event)"
          />
        </div>

        <hq-table
          [rows]="rows()"
          [columns]="columns()"
          [cellTemplate]="cell"
          [trackBy]="trackRow"
          [label]="'nav.coordinators' | transloco"
        >
          <hq-empty-state table-empty [message]="'management.coordinators.empty' | transloco" />
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
          @case ('tracks') {
            {{ row.tracks }}
          }
          @case ('sections') {
            {{ row.sections }}
          }
        }
      </ng-template>
    </hq-page>
  `,
})
export class ManagementCoordinatorsPage {
  private readonly api = inject(ManagementApi);
  private readonly staff = inject(StaffAreaService);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  protected readonly search = signal('');

  protected readonly people = rxResource({
    params: () => (this.staff.area() === 'management' ? true : undefined),
    stream: () => this.api.managementCoordinators(),
    defaultValue: [],
  });

  protected readonly columns = computed<readonly TableColumn<CoordinatorRow>[]>(() => {
    this.lang();
    return [
      { key: 'name', header: this.t('coordinator.teachers.columns.name'), width: '24%' },
      { key: 'email', header: this.t('coordinator.teachers.columns.email'), width: '26%' },
      { key: 'subjects', header: this.t('coordinator.teachers.columns.subjects') },
      { key: 'tracks', header: this.t('management.coordinators.columns.tracks') },
      { key: 'sections', header: this.t('coordinator.teachers.columns.sections'), width: '12%' },
    ];
  });

  protected readonly rows = computed<readonly CoordinatorRow[]>(() => {
    this.lang();
    const needle = this.search().trim().toLowerCase();
    return this.people
      .value()
      .map((person) => ({
        userId: person.userId ?? '',
        name: person.displayName ?? '',
        email: person.email ?? '',
        subjects: (person.subjects ?? [])
          .map((subject) => translateOr(this.transloco, `subject.${subject}`, subject))
          .join(' · '),
        // No curriculum at all on her scope rows means every track, which is the wider claim —
        // saying "British" there would be a narrower one than the truth (DR1).
        tracks:
          (person.curricula ?? []).length === 0
            ? this.t('coordinator.scope.bothTracks')
            : (person.curricula ?? [])
                .map((track) => translateOr(this.transloco, `curriculum.${track}`, track))
                .join(' · '),
        sections: person.sections ?? 0,
      }))
      .filter(
        (row) =>
          needle === '' ||
          row.name.toLowerCase().includes(needle) ||
          row.email.toLowerCase().includes(needle) ||
          row.subjects.toLowerCase().includes(needle),
      )
      .sort((a, b) => a.name.localeCompare(b.name));
  });

  protected readonly trackRow = (row: CoordinatorRow): string => row.userId;

  private t(key: string): string {
    return this.transloco.translate<string>(key);
  }
}
