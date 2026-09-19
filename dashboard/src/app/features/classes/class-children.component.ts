import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { type RosterChild, TeacherApi, TeacherRosterApi, apiErrorOf } from '../../api';
import { BandService } from '../../core/band/band.service';
import { FLAGS, FlagService } from '../../core/flags/flag.service';
import { FeatureDirective } from '../../core/flags/feature.directive';
import { activeLang } from '../../core/i18n/active-lang';
import { CanDirective } from '../../core/permissions/can.directive';
import { PermissionService } from '../../core/permissions/permission.service';
import { UndoService } from '../../core/undo/undo.service';
import {
  BandComponent,
  ButtonComponent,
  CardComponent,
  DialogComponent,
  EmptyStateComponent,
  InputComponent,
  SkeletonComponent,
  TableComponent,
  ToastComponent,
  type TableColumn,
} from '../../ui';
import { type RosterRow, rosterRows } from './classes.models';
import { PlaceChildComponent } from './place-child.component';

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
    CardComponent,
    TableComponent,
    ButtonComponent,
    InputComponent,
    DialogComponent,
    BandComponent,
    ToastComponent,
    EmptyStateComponent,
    SkeletonComponent,
    PlaceChildComponent,
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
  /** The section's own name — "1A British" — for the sentences that name where a child went. */
  readonly className = input('');

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

  /**
   * The class's own children, and the count the tab shows.
   *
   * Scoped to `classId` here as well as in the server (#70): the count under the tab is read as
   * "how big is 1A", and a list that quietly widened to the whole grade would make it lie.
   */
  protected readonly rows = computed<readonly RosterRow[]>(() =>
    rosterRows(this.students.value(), this.canEdit() ? this.children.value() : [], this.classId()),
  );

  protected readonly columns = computed<readonly TableColumn<RosterRow>[]>(() => {
    this.lang();
    const base: TableColumn<RosterRow>[] = [
      { key: 'name', header: this.t('classes.children.table.name'), width: '18%' },
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

  /** §3's leading tile carries the child's initial — a name, not an avatar we do not have. */
  protected initialOf(row: RosterRow): string {
    return row.name.trim().charAt(0).toUpperCase();
  }

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

  // ---- place a child who is already in the school ------------------------------------------------

  protected readonly placeOpen = signal(false);
  /** "Amina placed in 1A British", for four seconds. A success with nothing to undo — see CR2. */
  protected readonly placedToast = signal('');

  protected openPlace(): void {
    this.placeOpen.set(true);
  }

  /**
   * She is on the roster now: both lists are refetched, because the progress endpoint decides
   * what the other five columns say and the roster decides that she is there at all.
   */
  protected onPlaced(child: RosterChild): void {
    this.children.reload();
    this.students.reload();
    this.placedToast.set(
      this.t('classes.children.place.done', { name: child.name ?? '', class: this.sectionName() }),
    );
  }

  // ---- take a child off this section ----------------------------------------------------------

  /** The row the red band is asking about. Never a `hidden` band — see the P3.2b note. */
  protected readonly removing = signal<RosterRow | null>(null);

  protected readonly removeMessage = computed(() => {
    this.lang();
    const row = this.removing();
    return row
      ? this.t('classes.children.remove.message', { name: row.name, class: this.sectionName() })
      : '';
  });

  protected askRemove(row: RosterRow): void {
    this.removing.set(row);
  }

  protected cancelRemove(): void {
    this.removing.set(null);
  }

  /**
   * `DELETE …/roster/{childId}` — off this section, still in the school.
   *
   * Not undoable by the ten-second strip: the child is gone from the list the moment the server
   * answers, and an Undo on a row that is no longer drawn is a promise the screen cannot keep.
   * The way back is Place an existing child, which is where she now is.
   */
  protected confirmRemove(): void {
    const row = this.removing();
    this.removing.set(null);
    if (!row) return;
    this.rosterApi.detachFromMyClass(this.classId(), row.childId).subscribe({
      next: () => {
        this.children.reload();
        this.students.reload();
        this.placedToast.set(
          this.t('classes.children.remove.done', { name: row.name, class: this.sectionName() }),
        );
      },
      error: (error: unknown) => this.fail(error),
    });
  }

  /** The section's name, or the one word that stands in for it before the header has loaded. */
  private sectionName(): string {
    return this.className().trim() || this.t('classes.children.thisClass');
  }

  private fail(error: unknown): void {
    this.saving.set(false);
    this.band.fail(apiErrorOf(error)?.message ?? this.t('band.unreachable'));
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}
