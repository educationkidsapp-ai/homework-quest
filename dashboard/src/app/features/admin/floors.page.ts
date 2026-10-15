import { CdkMenu, CdkMenuItem, CdkMenuTrigger } from '@angular/cdk/menu';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { type Floor, ClassesApi, FloorsApi, apiErrorOf } from '../../api';
import { BandService } from '../../core/band/band.service';
import { FLAGS } from '../../core/flags/flag.service';
import { FeatureDirective } from '../../core/flags/feature.directive';
import { activeLang } from '../../core/i18n/active-lang';
import { CanDirective } from '../../core/permissions/can.directive';
import { PermissionService } from '../../core/permissions/permission.service';
import {
  type SelectOption,
  type TableColumn,
  BandComponent,
  ButtonComponent,
  CardComponent,
  CheckboxComponent,
  DialogComponent,
  EmptyStateComponent,
  InputComponent,
  PageComponent,
  SelectComponent,
  SkeletonComponent,
  TableComponent,
} from '../../ui';
import { CURRICULA, type Curriculum } from '../lessons/lessons.models';
import type { AdminClass } from './admin.models';

/** A row of the table, with the two lists already resolved for display. */
interface FloorRow {
  readonly id: string;
  readonly name: string;
  readonly academy: string;
  readonly classes: string;
  readonly supervisors: string;
  readonly source: Floor;
}

/** One line of either chooser: an id, the words beside the box, and whether it is ticked. */
interface Choice {
  readonly id: string;
  readonly label: string;
}

type FormMode = 'create' | 'edit';

/**
 * **Floors** (feature 20, `docs/features/20-floor-supervisor-role.md`, the Admin's half).
 *
 * A floor is a **named group of classes in one academy**, with one or more supervisors over it.
 * The screen is therefore four fields and nothing else: a name, the academy, the classes on it,
 * and the people who look after them.
 *
 * **A class is on at most one floor.** The unique index says so and the server refuses the Save
 * with a `409` naming the floor that already holds it — so the refusal arrives as that sentence
 * in the form's own band rather than as a red band about a request. Nothing is written when it is
 * refused, which is what makes "pick again" the whole of the recovery.
 *
 * **Two chooser lists, not two screens.** Checkboxes rather than a tag field, for the reason the
 * manager's departments editor uses them: the whole set is short, it is read as much as it is
 * changed, and a tick is one tap where a tag field is three. Supervisor *accounts* are created on
 * the Floor supervisors screen next door; this screen assigns them, which is the half the school
 * changes in September.
 *
 * Behind `floorSupervisor` — `featureGuard` on the Admin's row (`core/nav/screens.ts`) and
 * `*hqFeature` over the contents — and `floor.manage`, which is the Admin's key alone.
 *
 * **Delete** asks first — a floor carries a supervisor's whole scope, and dropping it leaves her
 * with nothing to see — and is the one action here with no Undo: the server deletes the rows, and
 * offering ten seconds of undo over a request that cannot be replayed would be a promise this
 * screen cannot keep.
 */
@Component({
  selector: 'hq-floors-page',
  imports: [
    BandComponent,
    ButtonComponent,
    CanDirective,
    CardComponent,
    CheckboxComponent,
    DialogComponent,
    EmptyStateComponent,
    FeatureDirective,
    InputComponent,
    PageComponent,
    SelectComponent,
    SkeletonComponent,
    TableComponent,
    CdkMenu,
    CdkMenuItem,
    CdkMenuTrigger,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './floors.page.html',
  styleUrl: './floors.page.scss',
})
export class FloorsPage {
  private readonly api = inject(FloorsApi);
  private readonly classesApi = inject(ClassesApi);
  private readonly transloco = inject(TranslocoService);
  private readonly band = inject(BandService);
  private readonly permissions = inject(PermissionService);
  private readonly lang = activeLang();

  /** The flag the whole feature is behind, on the row and inside the page. */
  protected readonly floorFlag = FLAGS.floorSupervisor;
  /** The one write key of this screen — `floor.manage`, the Admin's alone. */
  protected readonly manageKey = 'floor.manage';

  // ---- the lists --------------------------------------------------------------------------

  protected readonly floors = rxResource<readonly Floor[], true>({
    params: () => true,
    stream: () => this.api.floors(),
    defaultValue: [],
  });

  /** The school's sections, for the class chooser. Read once, not per dialog. */
  private readonly classes = rxResource<readonly AdminClass[], true>({
    params: () => true,
    stream: () => this.classesApi.classes(),
    defaultValue: [],
  });

  private readonly supervisors = rxResource({
    params: () => true,
    stream: () => this.api.floorSupervisors(),
    defaultValue: [],
  });

  protected readonly rows = computed<readonly FloorRow[]>(() => {
    this.lang();
    return this.floors.value().map((floor) => ({
      id: floor.id ?? '',
      name: floor.name ?? '',
      academy: this.word(`curriculum.${floor.academy ?? ''}`, floor.academy ?? ''),
      classes: (floor.classes ?? []).map((one) => one.className ?? '').join(' · '),
      supervisors: (floor.supervisors ?? []).map((one) => one.displayName ?? '').join(' · '),
      source: floor,
    }));
  });

  protected readonly columns = computed<readonly TableColumn<FloorRow>[]>(() => {
    this.lang();
    return [
      { key: 'name', header: this.t('admin.floors.table.name'), width: '18%' },
      { key: 'academy', header: this.t('admin.floors.table.academy'), width: '14%' },
      { key: 'classes', header: this.t('admin.floors.table.classes') },
      { key: 'supervisors', header: this.t('admin.floors.table.supervisors'), width: '24%' },
    ];
  });

  protected trackRow = (row: FloorRow): string => row.id;

  /**
   * The empty state's action, or `null` when this account may not create one — the same call
   * `classes.page.ts` makes, and for the same reason: `*hqCan` is structural and would take the
   * sentence with the button.
   */
  protected readonly createLabel = computed(() => {
    this.lang();
    return this.permissions.can(this.manageKey) ? this.t('admin.floors.create') : null;
  });

  // ---- create / edit ----------------------------------------------------------------------

  protected readonly formOpen = signal(false);
  protected readonly formMode = signal<FormMode>('create');
  protected readonly formError = signal<string | null>(null);
  protected readonly saving = signal(false);
  private readonly editing = signal<Floor | null>(null);

  protected readonly name = signal('');
  protected readonly academy = signal<Curriculum | ''>('');
  protected readonly chosenClasses = signal<ReadonlySet<string>>(new Set());
  protected readonly chosenSupervisors = signal<ReadonlySet<string>>(new Set());

  protected readonly academyOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return CURRICULA.map((one) => ({ value: one, label: this.word(`curriculum.${one}`, one) }));
  });

  /**
   * Every section of the school, named the way a person reads a timetable: "British · Grade 1 · 1A".
   *
   * **Not filtered by the floor's academy.** The server does not require a class to share it — a
   * school may run one corridor across both tracks — so the curriculum is on the label rather than
   * a reason to hide the row.
   */
  protected readonly classChoices = computed<readonly Choice[]>(() => {
    this.lang();
    return this.classes
      .value()
      .filter((one) => one.id !== undefined && one.active !== false)
      .map((one) => ({
        id: one.id ?? '',
        label: [
          this.word(`curriculum.${one.curriculum ?? ''}`, one.curriculum ?? ''),
          one.grade === undefined ? '' : this.t('admin.classes.gradeOption', { grade: one.grade }),
          one.name ?? '',
        ]
          .filter((part) => part !== '')
          .join(' · '),
      }));
  });

  protected readonly supervisorChoices = computed<readonly Choice[]>(() =>
    this.supervisors
      .value()
      .filter((one) => one.userId !== undefined && one.status !== 'disabled')
      .map((one) => ({ id: one.userId ?? '', label: one.fullName?.trim() || (one.email ?? '') })),
  );

  protected readonly formTitle = computed(() => {
    this.lang();
    return this.t(this.formMode() === 'create' ? 'admin.floors.createTitle' : 'admin.floors.editTitle');
  });

  /** A floor needs a name and an academy; classes and supervisors may be added later. */
  protected readonly canSave = computed(() => this.name().trim() !== '' && this.academy() !== '');

  protected hasClass(id: string): boolean {
    return this.chosenClasses().has(id);
  }

  protected toggleClass(id: string, on: boolean): void {
    this.formError.set(null);
    this.chosenClasses.set(toggled(this.chosenClasses(), id, on));
  }

  protected hasSupervisor(id: string): boolean {
    return this.chosenSupervisors().has(id);
  }

  protected toggleSupervisor(id: string, on: boolean): void {
    this.formError.set(null);
    this.chosenSupervisors.set(toggled(this.chosenSupervisors(), id, on));
  }

  protected setName(value: string): void {
    this.formError.set(null);
    this.name.set(value);
  }

  protected setAcademy(value: string): void {
    this.formError.set(null);
    this.academy.set(value === 'american' || value === 'british' ? value : '');
  }

  protected openCreate(): void {
    this.formError.set(null);
    this.formMode.set('create');
    this.editing.set(null);
    this.name.set('');
    this.academy.set('');
    this.chosenClasses.set(new Set());
    this.chosenSupervisors.set(new Set());
    this.formOpen.set(true);
  }

  protected openEdit(row: FloorRow | null): void {
    if (!row) return;
    const floor = row.source;
    this.formError.set(null);
    this.formMode.set('edit');
    this.editing.set(floor);
    this.name.set(floor.name ?? '');
    this.academy.set(floor.academy === 'american' || floor.academy === 'british' ? floor.academy : '');
    this.chosenClasses.set(new Set((floor.classes ?? []).map((one) => one.classId ?? '')));
    this.chosenSupervisors.set(new Set((floor.supervisors ?? []).map((one) => one.userId ?? '')));
    this.formOpen.set(true);
  }

  protected save(): void {
    if (!this.canSave()) return;
    this.saving.set(true);
    const body = {
      name: this.name().trim(),
      academy: this.academy(),
      classIds: [...this.chosenClasses()],
      supervisorIds: [...this.chosenSupervisors()],
    };
    const editing = this.editing();
    const call =
      this.formMode() === 'edit' && editing
        ? this.api.updateFloor(editing.id ?? '', body)
        : this.api.createFloor(body);
    call.subscribe({
      next: () => {
        this.saving.set(false);
        this.formError.set(null);
        this.formOpen.set(false);
        this.floors.reload();
      },
      // A class already on another floor is a `409` whose message names that floor. It belongs in
      // the form, beside the tick that caused it, not in a band over the page she has left.
      error: (error: unknown) => {
        this.saving.set(false);
        this.formError.set(apiErrorOf(error)?.message ?? this.t('band.unreachable'));
      },
    });
  }

  // ---- delete -----------------------------------------------------------------------------

  protected readonly menuRow = signal<FloorRow | null>(null);
  protected readonly pendingDelete = signal<FloorRow | null>(null);

  protected readonly confirmTitle = computed(() => {
    this.lang();
    return this.pendingDelete() ? this.t('admin.floors.deleteConfirm.title') : '';
  });

  protected readonly confirmMessage = computed(() => {
    this.lang();
    const row = this.pendingDelete();
    return row ? this.t('admin.floors.deleteConfirm.message', { name: row.name }) : '';
  });

  protected readonly confirmLabel = computed(() => {
    this.lang();
    return this.pendingDelete() ? this.t('admin.floors.deleteConfirm.confirm') : '';
  });

  protected requestDelete(row: FloorRow | null): void {
    if (row) this.pendingDelete.set(row);
  }

  protected cancelDelete(): void {
    this.pendingDelete.set(null);
  }

  protected confirmDelete(): void {
    const row = this.pendingDelete();
    this.pendingDelete.set(null);
    if (!row) return;
    this.api.deleteFloor(row.id).subscribe({
      next: () => this.floors.reload(),
      error: (error: unknown) => this.band.fail(apiErrorOf(error)?.message ?? this.t('band.unreachable')),
    });
  }

  private word(key: string, fallback: string): string {
    const text = this.t(key);
    return text === key ? fallback : text;
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}

/** A set with one member added or removed — the ticks are a value, not a mutable collection. */
function toggled(set: ReadonlySet<string>, id: string, on: boolean): ReadonlySet<string> {
  const next = new Set(set);
  if (on) next.add(id);
  else next.delete(id);
  return next;
}
