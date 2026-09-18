import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { TeacherApi, TeacherRosterApi, apiErrorOf } from '../../api';
import { BandService } from '../../core/band/band.service';
import { FLAGS, FlagService } from '../../core/flags/flag.service';
import { FeatureDirective } from '../../core/flags/feature.directive';
import { activeLang } from '../../core/i18n/active-lang';
import { CanDirective } from '../../core/permissions/can.directive';
import { PermissionService } from '../../core/permissions/permission.service';
import { UndoService } from '../../core/undo/undo.service';
import {
  ButtonComponent,
  DialogComponent,
  EmptyStateComponent,
  InputComponent,
  SkeletonComponent,
  TableComponent,
  type TableColumn,
} from '../../ui';
import { type RosterRow, rosterRows } from './classes.models';

/**
 * The class page's **Children** tab (`docs/teacher-flow.md` §4 step 3): who is in the class and
 * how each of them is doing.
 *
 * Two endpoints, because they answer two questions under two permissions:
 *
 * * `GET /teacher/classes/{id}/students` (`student.read`) — stars this week, the level she has
 *   reached, the skills she keeps missing. Every teacher gets this, always.
 * * `GET /teacher/classes/{id}/children` (`roster.teacher`, behind `teacher.rosterEdit`) — the
 *   roster itself: who may be added, renamed, or switched off. Fetched **only** when the flag
 *   is on *and* the account holds the key, so a school that has not bought roster editing never
 *   makes the request and never sees a column it cannot use.
 *
 * `*hqFeature` and `*hqCan` guard the controls, and the same two conditions gate the request —
 * a hidden button over a request that still fires is not a closed door.
 */
@Component({
  selector: 'hq-class-children',
  imports: [
    TableComponent,
    ButtonComponent,
    InputComponent,
    DialogComponent,
    EmptyStateComponent,
    SkeletonComponent,
    FeatureDirective,
    CanDirective,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './class-children.component.html',
  styleUrl: './class-children.component.scss',
})
export class ClassChildrenComponent {
  private readonly teacherApi = inject(TeacherApi);
  private readonly rosterApi = inject(TeacherRosterApi);
  private readonly flags = inject(FlagService);
  private readonly permissions = inject(PermissionService);
  private readonly band = inject(BandService);
  private readonly undo = inject(UndoService);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  readonly classId = input.required<string>();

  /** The flag key both the request and the `*hqFeature` on every control read. */
  protected readonly rosterFlag = FLAGS.teacherRosterEdit;

  protected readonly canEdit = computed(
    () => this.flags.isOn(FLAGS.teacherRosterEdit) && this.permissions.can('roster.teacher'),
  );

  private readonly students = rxResource({
    params: () => this.classId(),
    stream: ({ params }) => this.teacherApi.classStudents(params),
    defaultValue: [],
  });

  private readonly children = rxResource({
    params: () => (this.canEdit() ? this.classId() : undefined),
    stream: ({ params }) => this.rosterApi.myClassChildren(params),
    defaultValue: [],
  });

  protected readonly loading = computed(() => this.students.isLoading() || this.children.isLoading());

  protected readonly rows = computed<readonly RosterRow[]>(() =>
    rosterRows(this.students.value(), this.canEdit() ? this.children.value() : []),
  );

  protected readonly columns = computed<readonly TableColumn<RosterRow>[]>(() => {
    this.lang();
    const base: TableColumn<RosterRow>[] = [
      { key: 'name', header: this.t('classes.children.table.name'), width: '24%' },
      { key: 'starsThisWeek', header: this.t('classes.children.table.stars'), align: 'end' },
      { key: 'levelReached', header: this.t('classes.children.table.level'), align: 'end' },
      { key: 'weakSkills', header: this.t('classes.children.table.weakSkills') },
      { key: 'lastPlayed', header: this.t('classes.children.table.lastPlayed') },
    ];
    if (this.canEdit()) {
      base.push({ key: 'parentEmail', header: this.t('classes.children.table.parentEmail') });
      base.push({ key: 'childId', header: this.t('classes.children.table.actions') });
    }
    return base;
  });

  protected trackRow = (row: RosterRow): string => row.childId;

  // ---- reading a row ------------------------------------------------------------------------

  /** "Never" rather than an epoch of 0 — a child who has not started is not a child from 1970. */
  protected lastPlayedLabel(row: RosterRow): string {
    this.lang();
    if (row.lastPlayed === null) return this.t('classes.children.never');
    return new Intl.DateTimeFormat(this.lang(), { day: 'numeric', month: 'short' }).format(
      new Date(row.lastPlayed),
    );
  }

  // ---- inline edit ---------------------------------------------------------------------------

  protected readonly editing = signal<string | null>(null);
  protected readonly draftName = signal('');
  protected readonly draftEmail = signal('');
  protected readonly saving = signal(false);

  protected startEdit(row: RosterRow): void {
    this.editing.set(row.childId);
    this.draftName.set(row.name);
    this.draftEmail.set(row.parentEmail ?? '');
  }

  protected cancelEdit(): void {
    this.editing.set(null);
  }

  protected saveEdit(row: RosterRow): void {
    const name = this.draftName().trim();
    if (!name) return;
    const email = this.draftEmail().trim();
    this.saving.set(true);
    this.rosterApi
      .updateInMyClass(this.classId(), row.childId, { name, parentEmail: email || undefined })
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.editing.set(null);
          this.children.reload();
          this.undo.offerUndo({
            message: this.t('classes.children.undo.renamed', { name }),
            // Undo puts the old values back the same way they were changed: another PATCH.
            undo: () => this.restore(row),
            commit: () => this.children.reload(),
          });
        },
        error: (error: unknown) => this.fail(error),
      });
  }

  private restore(row: RosterRow): void {
    this.rosterApi
      .updateInMyClass(this.classId(), row.childId, {
        name: row.name,
        parentEmail: row.parentEmail ?? undefined,
      })
      .subscribe({ next: () => this.children.reload(), error: (error: unknown) => this.fail(error) });
  }

  // ---- activate / deactivate -------------------------------------------------------------------

  protected toggleActive(row: RosterRow): void {
    const next = !(row.active ?? true);
    this.rosterApi.updateInMyClass(this.classId(), row.childId, { active: next }).subscribe({
      next: () => {
        this.children.reload();
        this.undo.offerUndo({
          message: this.t(next ? 'classes.children.undo.activated' : 'classes.children.undo.deactivated', {
            name: row.name,
          }),
          undo: () => this.toggleActive({ ...row, active: next }),
          commit: () => this.children.reload(),
        });
      },
      error: (error: unknown) => this.fail(error),
    });
  }

  // ---- add a child --------------------------------------------------------------------------------

  protected readonly addOpen = signal(false);
  protected readonly newName = signal('');
  protected readonly newEmail = signal('');
  protected readonly adding = signal(false);

  protected openAdd(): void {
    this.newName.set('');
    this.newEmail.set('');
    this.addOpen.set(true);
  }

  protected confirmAdd(): void {
    const name = this.newName().trim();
    if (!name) return;
    const parentEmail = this.newEmail().trim();
    this.adding.set(true);
    this.rosterApi.addToMyClass(this.classId(), { name, parentEmail: parentEmail || undefined }).subscribe({
      next: () => {
        this.adding.set(false);
        this.addOpen.set(false);
        this.children.reload();
        this.students.reload();
      },
      error: (error: unknown) => {
        this.adding.set(false);
        this.fail(error);
      },
    });
  }

  private fail(error: unknown): void {
    this.saving.set(false);
    this.band.fail(apiErrorOf(error)?.message ?? this.t('band.unreachable'));
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}
