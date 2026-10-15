import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { type FloorClassAttendance, type FloorHome, type FloorMe, FloorSupervisorApi } from '../../api';
import { FLAGS } from '../../core/flags/flag.service';
import { FeatureDirective } from '../../core/flags/feature.directive';
import { activeLang } from '../../core/i18n/active-lang';
import {
  type TableColumn,
  CardComponent,
  EmptyStateComponent,
  PageComponent,
  SkeletonComponent,
  TableComponent,
} from '../../ui';
import { CoordinatorReadFailedComponent } from '../coordinator/read-failed.component';
import { AttendanceBoardComponent } from '../staff-attendance/attendance-board.component';

/** One row of the children's table: every number resolved, so the template has no `?? 0` in it. */
interface ClassRow {
  readonly classId: string;
  readonly className: string;
  readonly floorName: string;
  readonly children: number;
  readonly present: number;
  readonly absent: number;
  readonly late: number;
  readonly excused: number;
  readonly notMarked: number;
}

function rowOf(one: FloorClassAttendance): ClassRow {
  return {
    classId: one.classId ?? '',
    className: one.className ?? '',
    floorName: one.floorName ?? '',
    children: one.children ?? 0,
    present: one.present ?? 0,
    absent: one.absent ?? 0,
    late: one.late ?? 0,
    excused: one.excused ?? 0,
    notMarked: one.notMarked ?? 0,
  };
}

/**
 * **The floor supervisor's Home** (feature 20, `docs/features/20-floor-supervisor-role.md`):
 * her floor's staff this morning, over her floor's children this morning.
 *
 * Two blocks, in the order she asks them in. **Staff** is feature 17's board
 * ({@link AttendanceBoardComponent}) — the same five count cards, the same filter-by-tapping, the
 * same thirty-second refresh — scoped by the server to the teachers of her floor's classes plus
 * anyone whose direct manager she is (`BoardScope`, scope word `REPORTS`). It is the component
 * rather than a copy of it: a second board would have been a second thing to keep in step, and
 * hers is the same question one scope over.
 *
 * Behind `floorSupervisor` twice over: `featureGuard` on the row in `core/nav/screens.ts`, and
 * `*hqFeature` over the page's own contents, so a flag flipped off while she is looking empties
 * the screen rather than leaving a board of somebody else's floor on it.
 *
 * **`GET /floor/home` is what decides whether that block exists at all.** Its `board` is null when
 * the school has `staffAttendance` off, which is the one case where she has a floor and no staff
 * attendance to show; the board then draws itself from `/staff-attendance/board` as it does
 * everywhere else, because it owns its own refresh and its own filters and feeding it a snapshot
 * would freeze both.
 *
 * **Children** is one row per class of her floor with today's register: present, absent, late,
 * excused, and the children nobody has marked — which is the number she acts on. A class name
 * opens nothing: her way into a child is the Children screen, where she can search, and a row of
 * twenty-eight children is not a link.
 */
@Component({
  selector: 'hq-floor-home-page',
  imports: [
    AttendanceBoardComponent,
    CardComponent,
    CoordinatorReadFailedComponent,
    EmptyStateComponent,
    FeatureDirective,
    PageComponent,
    SkeletonComponent,
    TableComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './floor-home.page.html',
  styleUrl: './floor-home.page.scss',
})
export class FloorHomePage {
  private readonly api = inject(FloorSupervisorApi);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  /** Her whole area is behind it, and so is the content of this page. */
  protected readonly floorFlag = FLAGS.floorSupervisor;

  protected readonly home = rxResource<FloorHome, true>({
    params: () => true,
    stream: () => this.api.floorHome(),
    defaultValue: {},
  });

  protected readonly me = rxResource<FloorMe, true>({
    params: () => true,
    stream: () => this.api.floorMe(),
    defaultValue: {},
  });

  /** The floors she supervises, for the subtitle: "Floor 2 · Floor 3". */
  protected readonly subtitle = computed(() => {
    this.lang();
    const floors = (this.me.value().floors ?? []).map((floor) => floor.name ?? '').filter((name) => name);
    return floors.length === 0 ? this.t('floor.home.subtitle') : floors.join(' · ');
  });

  /**
   * Whether the staff block is drawn: the server sent a board, so the school takes attendance
   * from check-ins. Null is not a failure — it is a school with `staffAttendance` off.
   */
  protected readonly hasBoard = computed(() => Boolean(this.home.value().board));

  protected readonly rows = computed<readonly ClassRow[]>(() => (this.home.value().classes ?? []).map(rowOf));

  protected readonly columns = computed<readonly TableColumn<ClassRow>[]>(() => {
    this.lang();
    return [
      { key: 'className', header: this.t('floor.home.table.class'), width: '22%' },
      { key: 'children', header: this.t('floor.home.table.children'), align: 'end' },
      { key: 'present', header: this.t('floor.home.table.present'), align: 'end' },
      { key: 'absent', header: this.t('floor.home.table.absent'), align: 'end' },
      { key: 'late', header: this.t('floor.home.table.late'), align: 'end' },
      { key: 'excused', header: this.t('floor.home.table.excused'), align: 'end' },
      { key: 'notMarked', header: this.t('floor.home.table.notMarked'), align: 'end' },
    ];
  });

  protected trackRow = (row: ClassRow): string => row.classId;

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}
