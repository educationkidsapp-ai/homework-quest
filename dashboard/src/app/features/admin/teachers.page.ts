/* hq-flag: none (shell) — the Teachers screen is the one-school build itself, gated by
   `teacher.read`/`teacher.manage` rather than by a flag (see `classes.page.ts`). */
import { CdkMenu, CdkMenuItem, CdkMenuTrigger } from '@angular/cdk/menu';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationStart, Router } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { filter } from 'rxjs';
import { ClassesApi, TeachersApi, apiErrorOf, type TeacherAccount } from '../../api';
import { BandService } from '../../core/band/band.service';
import { activeLang } from '../../core/i18n/active-lang';
import { CanDirective } from '../../core/permissions/can.directive';
import { PermissionService } from '../../core/permissions/permission.service';
import { UndoService } from '../../core/undo/undo.service';
import {
  BandComponent,
  ButtonComponent,
  CardComponent,
  CheckboxComponent,
  CountUpDirective,
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
import { AssignmentPickerComponent } from './assignment-picker.component';
import { CURRICULA, SUBJECTS, isCurriculum, type Curriculum, type Subject } from '../lessons/lessons.models';
import { byCourseThenName, pairKey, teacherLabel, type AdminClass, type TeacherRow } from './admin.models';

type FormMode = 'create' | 'edit';

/**
 * Teachers (§6, `docs/teacher-flow.md` §2): the school's teaching staff, what each of them
 * teaches, and where her assignments are made.
 *
 * **The temporary password is shown once.** `POST /admin/teachers` and `POST …/reset-password`
 * are the only two moments it exists in readable form; the server stores a hash and will not
 * answer it again. So it goes into a notice band with a copy button and a sentence saying it
 * will not be shown again, is held in one signal and nowhere else — never `localStorage`, never
 * a resource's cache — and is dropped the moment the person navigates away. A screen that
 * quietly kept it would turn "shown once" into "readable for as long as the tab is open".
 */
@Component({
  selector: 'hq-teachers-page',
  imports: [
    PageComponent,
    CardComponent,
    CountUpDirective,
    InputComponent,
    SelectComponent,
    CheckboxComponent,
    ButtonComponent,
    TableComponent,
    EmptyStateComponent,
    SkeletonComponent,
    BandComponent,
    DialogComponent,
    AssignmentPickerComponent,
    CanDirective,
    CdkMenu,
    CdkMenuItem,
    CdkMenuTrigger,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './teachers.page.html',
  styleUrl: './teachers.page.scss',
})
export class TeachersPage {
  private readonly teachersApi = inject(TeachersApi);
  private readonly classesApi = inject(ClassesApi);
  private readonly transloco = inject(TranslocoService);
  private readonly band = inject(BandService);
  private readonly undo = inject(UndoService);
  private readonly permissions = inject(PermissionService);
  private readonly router = inject(Router);
  private readonly lang = activeLang();

  // ---- the list ---------------------------------------------------------------------------

  protected readonly staff = rxResource<readonly TeacherAccount[], true>({
    params: () => true,
    stream: () => this.teachersApi.teachers(),
    defaultValue: [],
  });

  // ---- EduManage KPI Metrics ----
  protected readonly totalTeachers = computed(() => this.staff.value().length);
  protected readonly activeTeachers = computed(() => this.staff.value().filter((t) => t.status !== 'disabled').length);
  protected readonly totalAssignments = computed(() =>
    this.staff.value().reduce((acc, t) => acc + (t.assignments?.length ?? 0), 0),
  );
  protected readonly unassignedTeachers = computed(
    () => this.staff.value().filter((t) => (t.assignments?.length ?? 0) === 0).length,
  );

  protected readonly sections = rxResource<readonly AdminClass[], true>({
    params: () => true,
    stream: () => this.classesApi.classes(),
    defaultValue: [],
  });

  protected readonly sortedSections = computed(() => [...this.sections.value()].sort(byCourseThenName));

  protected readonly filterText = signal('');

  protected readonly rows = computed<readonly TeacherRow[]>(() => {
    this.lang();
    const query = this.filterText().trim().toLowerCase();
    return this.staff
      .value()
      .filter(
        (teacher) =>
          !query ||
          teacherLabel(teacher).toLowerCase().includes(query) ||
          (teacher.email ?? '').toLowerCase().includes(query),
      )
      .map((teacher) => this.toRow(teacher));
  });

  protected readonly columns = computed<readonly TableColumn<TeacherRow>[]>(() => {
    this.lang();
    return [
      { key: 'fullName', header: this.t('admin.teachers.table.name'), width: '20%' },
      { key: 'email', header: this.t('admin.teachers.table.email') },
      { key: 'subjects', header: this.t('admin.teachers.table.subjects') },
      { key: 'curriculum', header: this.t('admin.teachers.table.curriculum') },
      { key: 'assignments', header: this.t('admin.teachers.table.assignments'), width: '24%' },
      { key: 'active', header: this.t('admin.teachers.table.status') },
    ];
  });

  protected trackRow = (row: TeacherRow): string => row.id;

  /**
   * The empty state's action, or `null` when this account may not create one.
   *
   * `*hqCan` is structural — on `hq-empty-state` it would take the whole empty state with it,
   * and "no teachers yet" is exactly what a MANAGERIAL account needs to be told. So the same
   * permission decides the label instead, and `hq-empty-state` draws no button without one.
   */
  protected readonly createLabel = computed(() => {
    this.lang();
    return this.permissions.can('teacher.manage') ? this.t('admin.teachers.form.createAction') : null;
  });

  /**
   * `classId|subject` → the teacher who already holds it. The picker reads it to disable the
   * pairs it cannot take and to say whose they are; the teacher it is opened for is left out,
   * so her own ticks are not disabled against herself.
   */
  protected readonly takenBy = computed<ReadonlyMap<string, string>>(() => {
    const openFor = this.assignFor()?.userId;
    const map = new Map<string, string>();
    for (const teacher of this.staff.value()) {
      if (teacher.userId === openFor) continue;
      for (const assignment of teacher.assignments ?? []) {
        map.set(pairKey(assignment.classId ?? '', assignment.subject ?? ''), teacherLabel(teacher));
      }
    }
    return map;
  });

  // ---- create / edit ------------------------------------------------------------------------

  protected readonly formOpen = signal(false);
  protected readonly formMode = signal<FormMode>('create');
  protected readonly formError = signal<string | null>(null);
  private readonly editing = signal<TeacherAccount | null>(null);
  protected readonly saving = signal(false);

  protected readonly fullName = signal('');
  protected readonly email = signal('');
  protected readonly curriculum = signal<Curriculum | ''>('');
  protected readonly photoUrl = signal('');
  protected readonly subjects = signal<ReadonlySet<Subject>>(new Set());

  protected readonly allSubjects = SUBJECTS;

  protected readonly curriculumOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return CURRICULA.map((curriculum) => ({ value: curriculum, label: this.t(`curriculum.${curriculum}`) }));
  });

  protected readonly formTitle = computed(() => {
    this.lang();
    return this.t(
      this.formMode() === 'create' ? 'admin.teachers.form.createTitle' : 'admin.teachers.form.editTitle',
    );
  });

  protected readonly canSave = computed(
    () =>
      this.fullName().trim().length > 0 &&
      (this.formMode() === 'edit' || this.email().trim().length > 0) &&
      this.subjects().size > 0,
  );

  protected hasSubject(subject: Subject): boolean {
    return this.subjects().has(subject);
  }

  protected toggleSubject(subject: Subject, on: boolean): void {
    this.formError.set(null);
    const next = new Set(this.subjects());
    if (on) next.add(subject);
    else next.delete(subject);
    this.subjects.set(next);
  }

  protected setFullName(val: string): void {
    this.formError.set(null);
    this.fullName.set(val);
  }

  protected setEmail(val: string): void {
    this.formError.set(null);
    this.email.set(val);
  }

  protected setCurriculum(val: string): void {
    this.formError.set(null);
    this.curriculum.set(val === 'american' || val === 'british' ? val : '');
  }

  protected setPhotoUrl(val: string): void {
    this.formError.set(null);
    this.photoUrl.set(val);
  }

  protected openCreate(): void {
    this.formError.set(null);
    this.formMode.set('create');
    this.editing.set(null);
    this.fullName.set('');
    this.email.set('');
    this.curriculum.set('');
    this.photoUrl.set('');
    this.subjects.set(new Set());
    this.formOpen.set(true);
  }

  protected openEdit(teacher: TeacherAccount | null): void {
    if (!teacher) return;
    this.formError.set(null);
    this.formMode.set('edit');
    this.editing.set(teacher);
    this.fullName.set(teacher.fullName ?? '');
    this.email.set(teacher.email ?? '');
    this.curriculum.set(isCurriculum(teacher.curriculum) ? teacher.curriculum : '');
    this.photoUrl.set(teacher.photoUrl ?? '');
    this.subjects.set(new Set(SUBJECTS.filter((subject) => (teacher.subjects ?? []).includes(subject))));
    this.formOpen.set(true);
  }

  protected save(): void {
    if (!this.canSave()) return;
    const body = {
      fullName: this.fullName().trim(),
      subjects: [...this.subjects()],
      curriculum: this.curriculum() || undefined,
      photoUrl: this.photoUrl().trim() || undefined,
    };
    this.saving.set(true);
    const editing = this.editing();
    const fail = (error: unknown): void => {
      this.saving.set(false);
      const message = apiErrorOf(error)?.message ?? this.t('band.unreachable');
      this.formError.set(message);
      this.band.fail(message);
    };
    const done = (): void => {
      this.saving.set(false);
      this.formError.set(null);
      this.formOpen.set(false);
      this.staff.reload();
    };

    if (this.formMode() === 'edit' && editing) {
      this.teachersApi.updateTeacher(editing.userId ?? '', body).subscribe({ next: done, error: fail });
      return;
    }
    this.teachersApi.createTeacher({ ...body, email: this.email().trim() }).subscribe({
      next: (created) => {
        done();
        this.showPassword(created.teacher, created.temporaryPassword);
      },
      error: fail,
    });
  }

  // ---- the temporary password, shown once -----------------------------------------------------

  private readonly password = signal<{ readonly forName: string; readonly value: string } | null>(null);
  protected readonly passwordCopied = signal(false);

  protected readonly passwordShown = computed(() => this.password() !== null);
  protected readonly passwordValue = computed(() => this.password()?.value ?? '');
  protected readonly passwordTitle = computed(() => {
    this.lang();
    const shown = this.password();
    return shown ? this.t('admin.teachers.password.title', { name: shown.forName }) : '';
  });

  private showPassword(teacher: TeacherAccount | undefined, value: string | undefined): void {
    if (!value) return;
    this.passwordCopied.set(false);
    this.password.set({ forName: teacher ? teacherLabel(teacher) : '', value });
  }

  protected copyPassword(): void {
    const value = this.passwordValue();
    if (!value) return;
    void navigator.clipboard?.writeText(value).then(
      () => this.passwordCopied.set(true),
      () => this.passwordCopied.set(false),
    );
  }

  protected dismissPassword(): void {
    this.password.set(null);
    this.passwordCopied.set(false);
  }

  protected resetPassword(teacher: TeacherAccount | null): void {
    if (!teacher) return;
    this.teachersApi.resetTeacherPassword(teacher.userId ?? '').subscribe({
      next: (result) => this.showPassword(teacher, result.temporaryPassword),
      error: (error: unknown) => this.band.fail(apiErrorOf(error)?.message ?? this.t('band.unreachable')),
    });
  }

  // ---- assignments ----------------------------------------------------------------------------

  protected readonly assignFor = signal<TeacherAccount | null>(null);
  protected readonly assignOpen = signal(false);

  protected openAssignments(teacher: TeacherAccount | null): void {
    if (!teacher) return;
    this.assignFor.set(teacher);
    this.assignOpen.set(true);
  }

  protected onAssignmentsSaved(): void {
    this.assignFor.set(null);
    this.staff.reload();
    this.sections.reload();
  }

  // ---- deactivate -----------------------------------------------------------------------------

  protected readonly menuRow = signal<TeacherRow | null>(null);
  protected readonly pendingDeactivate = signal<TeacherRow | null>(null);

  protected readonly confirmTitle = computed(() => {
    this.lang();
    return this.pendingDeactivate() ? this.t('admin.teachers.deactivateConfirm.title') : '';
  });

  protected readonly confirmMessage = computed(() => {
    this.lang();
    const row = this.pendingDeactivate();
    return row ? this.t('admin.teachers.deactivateConfirm.message', { name: row.fullName }) : '';
  });

  protected readonly confirmLabel = computed(() => {
    this.lang();
    return this.pendingDeactivate() ? this.t('admin.teachers.deactivateConfirm.confirm') : '';
  });

  protected requestDeactivate(row: TeacherRow | null): void {
    if (row) this.pendingDeactivate.set(row);
  }

  protected cancelPending(): void {
    this.pendingDeactivate.set(null);
  }

  protected confirmPending(): void {
    const row = this.pendingDeactivate();
    this.pendingDeactivate.set(null);
    if (row) this.setActive(row, false);
  }

  protected setActive(row: TeacherRow, active: boolean): void {
    const status = active ? 'active' : 'disabled';
    this.patch(row.id, status);
    this.teachersApi.updateTeacher(row.id, { active }).subscribe({
      next: () =>
        this.undo.offerUndo({
          message: this.t(active ? 'admin.teachers.undo.activated' : 'admin.teachers.undo.deactivated', {
            name: row.fullName,
          }),
          undo: () => this.setActive(row, !active),
        }),
      error: (error: unknown) => {
        this.patch(row.id, active ? 'disabled' : 'active');
        this.band.fail(apiErrorOf(error)?.message ?? this.t('band.unreachable'));
      },
    });
  }

  private patch(userId: string, status: string): void {
    this.staff.update((rows) => rows.map((t) => (t.userId === userId ? { ...t, status } : t)));
  }

  // ---- wiring ---------------------------------------------------------------------------------

  constructor() {
    // The temporary password never survives leaving the screen (see the class comment).
    this.router.events
      .pipe(
        filter((event) => event instanceof NavigationStart),
        takeUntilDestroyed(),
      )
      .subscribe(() => this.dismissPassword());
  }

  private toRow(teacher: TeacherAccount): TeacherRow {
    return {
      id: teacher.userId ?? '',
      fullName: teacherLabel(teacher),
      email: teacher.email ?? '',
      subjects: (teacher.subjects ?? []).map((subject) => this.subjectWord(subject)).join(' · '),
      curriculum: isCurriculum(teacher.curriculum) ? this.t(`curriculum.${teacher.curriculum}`) : '',
      assignments: (teacher.assignments ?? []).map(
        (assignment) => `${assignment.className ?? ''} · ${this.subjectWord(assignment.subject ?? '')}`,
      ),
      active: teacher.status !== 'disabled',
      source: teacher,
    };
  }

  private subjectWord(subject: string): string {
    const text = this.t(`subject.${subject}`);
    return text === `subject.${subject}` ? subject : text;
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}
