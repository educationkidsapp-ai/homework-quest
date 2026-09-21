/* hq-flag: none (shell) — the Classes screen is the one-school build itself, gated by
   `section.read`/`class.write` rather than by a flag; `multiSchool` gates the screens this one
   replaces (Schools, the wizard, the header switcher), not this one. */
import { CdkMenu, CdkMenuItem, CdkMenuTrigger } from '@angular/cdk/menu';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { Observable } from 'rxjs';
import { ClassesApi, apiErrorOf } from '../../api';
import { BandService } from '../../core/band/band.service';
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
  PageComponent,
  SelectComponent,
  SkeletonComponent,
  TableComponent,
  type SelectOption,
  type TableColumn,
} from '../../ui';
import { CURRICULA, GRADES, isCurriculum, type Curriculum } from '../lessons/lessons.models';
import { byCourseThenName, isSectionName, type AdminClass, type ClassRow } from './admin.models';

interface Pending {
  readonly mode: 'regenerate' | 'deactivate';
  readonly row: ClassRow;
}

/**
 * Classes (§6, `docs/teacher-flow.md` §1): every section of the school, by curriculum, grade and
 * name, with the join code parents type and the counts that say whether the section is ready.
 *
 * Reading is `section.read`; every change is `class.write`, so a MANAGERIAL account sees the
 * screen and none of its actions. §7's two rules divide the actions: **regenerating a join code
 * and deactivating a section confirm first** — the first invalidates every printed card, the
 * second takes a class away from its teacher — and **everything is undoable for ten seconds**
 * afterwards, because a confirmation that cannot be walked back is a worse trade than one that
 * can.
 */
@Component({
  selector: 'hq-classes-page',
  imports: [
    PageComponent,
    CardComponent,
    SelectComponent,
    InputComponent,
    ButtonComponent,
    TableComponent,
    EmptyStateComponent,
    SkeletonComponent,
    BandComponent,
    DialogComponent,
    CanDirective,
    CdkMenu,
    CdkMenuItem,
    CdkMenuTrigger,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './classes.page.html',
  styleUrl: './classes.page.scss',
})
export class ClassesPage {
  private readonly classesApi = inject(ClassesApi);
  private readonly transloco = inject(TranslocoService);
  private readonly band = inject(BandService);
  private readonly undo = inject(UndoService);
  private readonly permissions = inject(PermissionService);
  private readonly lang = activeLang();

  // ---- the list ---------------------------------------------------------------------------

  protected readonly sections = rxResource<readonly AdminClass[], true>({
    params: () => true,
    stream: () => this.classesApi.classes(),
    defaultValue: [],
  });

  protected readonly filterText = signal('');
  protected readonly curriculumFilter = signal<Curriculum | null>(null);

  protected readonly curriculumOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return [
      { value: '', label: this.t('admin.classes.filter.allCurricula') },
      ...CURRICULA.map((curriculum) => ({ value: curriculum, label: this.courseWord(curriculum) })),
    ];
  });

  protected readonly rows = computed<readonly ClassRow[]>(() => {
    this.lang();
    const query = this.filterText().trim().toLowerCase();
    const curriculum = this.curriculumFilter();
    return [...this.sections.value()]
      .filter((section) => curriculum === null || section.curriculum === curriculum)
      .filter((section) => !query || (section.name ?? '').toLowerCase().includes(query))
      .sort(byCourseThenName)
      .map((section) => this.toRow(section));
  });

  protected readonly columns = computed<readonly TableColumn<ClassRow>[]>(() => {
    this.lang();
    return [
      { key: 'name', header: this.t('admin.classes.table.name'), width: '14%' },
      { key: 'course', header: this.t('admin.classes.table.course') },
      { key: 'joinCode', header: this.t('admin.classes.table.joinCode') },
      { key: 'children', header: this.t('admin.classes.table.children'), align: 'end' },
      { key: 'teachers', header: this.t('admin.classes.table.teachers') },
      { key: 'active', header: this.t('admin.classes.table.status') },
    ];
  });

  protected trackRow = (row: ClassRow): string => row.id;

  /**
   * The empty state's action, or `null` when this account may not create one.
   *
   * `*hqCan` is structural — on `hq-empty-state` it would take the whole empty state with it,
   * and "no classes yet" is exactly what a MANAGERIAL account needs to be told. So the same
   * permission decides the label instead, and `hq-empty-state` draws no button without one.
   */
  protected readonly createLabel = computed(() => {
    this.lang();
    return this.permissions.can('class.write') ? this.t('admin.classes.create.action') : null;
  });

  // ---- create -----------------------------------------------------------------------------

  protected readonly createOpen = signal(false);
  protected readonly createError = signal<string | null>(null);
  protected readonly newCurriculum = signal<Curriculum | ''>('');
  protected readonly newGrade = signal('');
  protected readonly newName = signal('');
  protected readonly saving = signal(false);

  protected readonly createCurriculumOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return CURRICULA.map((curriculum) => ({ value: curriculum, label: this.courseWord(curriculum) }));
  });

  protected readonly gradeOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return GRADES.map((grade) => ({
      value: String(grade),
      label: this.t('admin.classes.gradeOption', { grade }),
    }));
  });

  /** Shown under the field, never a reason to refuse: the server decides what a name may be. */
  protected readonly nameHint = computed(() => {
    this.lang();
    const name = this.newName().trim();
    return name && !isSectionName(name) ? this.t('admin.classes.create.nameHint') : '';
  });

  protected readonly canCreate = computed(
    () => this.newCurriculum() !== '' && this.newGrade() !== '' && this.newName().trim().length > 0,
  );

  protected openCreate(): void {
    this.createError.set(null);
    this.newCurriculum.set('');
    this.newGrade.set('');
    this.newName.set('');
    this.createOpen.set(true);
  }

  protected setNewCurriculum(value: string): void {
    this.createError.set(null);
    this.newCurriculum.set(value === 'american' || value === 'british' ? value : '');
  }

  protected setNewGrade(value: string): void {
    this.createError.set(null);
    this.newGrade.set(value);
  }

  protected setNewName(value: string): void {
    this.createError.set(null);
    this.newName.set(value);
  }

  protected create(): void {
    const curriculum = this.newCurriculum();
    if (!this.canCreate() || curriculum === '') return;
    this.saving.set(true);
    this.classesApi
      .createSection({ curriculum, grade: Number(this.newGrade()), name: this.newName().trim() })
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.createError.set(null);
          this.createOpen.set(false);
          this.sections.reload();
        },
        error: (error: unknown) => {
          this.saving.set(false);
          const message = apiErrorOf(error)?.message ?? this.t('band.unreachable');
          this.createError.set(message);
          this.band.fail(message);
        },
      });
  }

  // ---- join code: copy, regenerate, print --------------------------------------------------

  protected readonly copied = signal<string | null>(null);

  protected copyJoinCode(row: ClassRow): void {
    void navigator.clipboard?.writeText(row.joinCode).then(
      () => this.copied.set(row.id),
      () => this.copied.set(null),
    );
  }

  protected printCard(row: ClassRow): void {
    // `application/pdf` makes the generated client ask for a blob; only its declared type says
    // `string`, because the document describes the body as a base64 `format: byte`.
    (this.classesApi.joinCard(row.id) as unknown as Observable<Blob>).subscribe({
      next: (pdf) => {
        const url = URL.createObjectURL(pdf);
        window.open(url, '_blank', 'noopener');
        // The tab has the bytes by now; revoking frees them without closing it.
        setTimeout(() => URL.revokeObjectURL(url), 60_000);
      },
      error: (error: unknown) => this.band.fail(apiErrorOf(error)?.message ?? this.t('band.unreachable')),
    });
  }

  // ---- the two actions that confirm ---------------------------------------------------------

  protected readonly menuRow = signal<ClassRow | null>(null);
  protected readonly pending = signal<Pending | null>(null);

  // Empty rather than hidden while nothing is pending: a screen reader must not find a
  // "Regenerate this join code?" band that merely carries `hidden` (the P3.2b note).
  protected readonly confirmTitle = computed(() => {
    this.lang();
    const pending = this.pending();
    return pending ? this.t(`admin.classes.${pending.mode}Confirm.title`) : '';
  });

  protected readonly confirmMessage = computed(() => {
    this.lang();
    const pending = this.pending();
    return pending ? this.t(`admin.classes.${pending.mode}Confirm.message`, { name: pending.row.name }) : '';
  });

  protected readonly confirmLabel = computed(() => {
    this.lang();
    const pending = this.pending();
    return pending ? this.t(`admin.classes.${pending.mode}Confirm.confirm`) : '';
  });

  protected request(mode: Pending['mode'], row: ClassRow | null): void {
    if (row) this.pending.set({ mode, row });
  }

  protected cancelPending(): void {
    this.pending.set(null);
  }

  protected confirmPending(): void {
    const pending = this.pending();
    this.pending.set(null);
    if (!pending) return;
    if (pending.mode === 'regenerate') this.regenerate(pending.row);
    else this.setActive(pending.row, false);
  }

  private regenerate(row: ClassRow): void {
    this.classesApi.regenerateJoinCode(row.id).subscribe({
      next: (updated: AdminClass) => {
        this.patch(row.id, { joinCode: updated.joinCode });
        this.copied.set(null);
      },
      error: (error: unknown) => this.band.fail(apiErrorOf(error)?.message ?? this.t('band.unreachable')),
    });
  }

  /** Activation needs no confirmation; deactivation has already had one. Both offer Undo. */
  protected setActive(row: ClassRow, active: boolean): void {
    this.patch(row.id, { active });
    this.classesApi.updateSection(row.id, { active }).subscribe({
      next: () =>
        this.undo.offerUndo({
          message: this.t(active ? 'admin.classes.undo.activated' : 'admin.classes.undo.deactivated', {
            name: row.name,
          }),
          undo: () => this.setActive(row, !active),
        }),
      error: (error: unknown) => {
        this.patch(row.id, { active: !active });
        this.band.fail(apiErrorOf(error)?.message ?? this.t('band.unreachable'));
      },
    });
  }

  private patch(id: string, change: Partial<AdminClass>): void {
    this.sections.update((sections) => sections.map((s) => (s.id === id ? { ...s, ...change } : s)));
  }

  // ---- wiring -------------------------------------------------------------------------------

  private toRow(section: AdminClass): ClassRow {
    return {
      id: section.id ?? '',
      course: this.t('admin.classes.course', {
        curriculum: this.courseWord(section.curriculum),
        grade: section.grade ?? 0,
      }),
      name: section.name ?? '',
      joinCode: section.joinCode ?? '',
      children: section.children ?? 0,
      teachers: this.t('admin.classes.teacherCount', { count: section.assignments ?? 0 }),
      active: section.active !== false,
      source: section,
    };
  }

  private courseWord(curriculum: string | undefined): string {
    if (!isCurriculum(curriculum)) return curriculum ?? '';
    return this.t(`curriculum.${curriculum}`);
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}
