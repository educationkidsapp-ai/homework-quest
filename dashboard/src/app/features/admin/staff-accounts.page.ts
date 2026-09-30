/* hq-flag: none (shell) — Coordinators and Managers are the one-school build itself, gated by
   `coordinator.manage` / `manager.manage` rather than by a flag, exactly as the Teachers page
   next to them is (`teachers.page.ts`). A school that has classes has the people who run them. */
import { CdkMenu, CdkMenuItem, CdkMenuTrigger } from '@angular/cdk/menu';
import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, NavigationStart, Router } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { type Observable, filter, map } from 'rxjs';
import {
  type CoordinatorAccount,
  type ManagerAccount,
  type Scope,
  type TemporaryPassword,
  CoordinatorsApi,
  ManagersApi,
  apiErrorOf,
} from '../../api';
import { BandService } from '../../core/band/band.service';
import { activeLang } from '../../core/i18n/active-lang';
import { CanDirective } from '../../core/permissions/can.directive';
import { PermissionService } from '../../core/permissions/permission.service';
import { UndoService } from '../../core/undo/undo.service';
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
import { CURRICULA, SUBJECTS, type Curriculum, type Subject } from '../lessons/lessons.models';
import { OneTimePasswordComponent } from './one-time-password.component';

/** Which of the two accounts this screen is. Set by the route's `screenId` (`area.routes.ts`). */
export type StaffKind = 'coordinator' | 'manager';

/**
 * One account, normalised.
 *
 * `CoordinatorAccount` carries `scopes` (subject, optionally a track) and `ManagerAccount` carries
 * `departments` (curricula). Narrowing a union in the template would have put a `kind` test on
 * every binding, so the two shapes are flattened on the way in and the only place that still knows
 * which is which is {@link kind} and the four calls it picks.
 */
interface Person {
  readonly userId: string;
  readonly fullName: string;
  readonly email: string;
  readonly phone: string;
  readonly active: boolean;
  readonly scopes: readonly Scope[];
  readonly curricula: readonly string[];
}

/** A row of the table, with everything resolved for display. */
interface StaffRow {
  readonly id: string;
  readonly fullName: string;
  readonly email: string;
  readonly phone: string;
  readonly scopes: string;
  readonly active: boolean;
  readonly source: Person;
}

/** One line of the coordinator's scope editor: a subject, and the track it is limited to. */
interface ScopeRow {
  readonly subject: Subject | '';
  readonly curriculum: Curriculum | '';
}

type FormMode = 'create' | 'edit';

/** `math/british` or `math/` — a subject on one track is not the same row as the same subject on both. */
function pairKey(subject: Subject | '', curriculum: Curriculum | ''): string {
  return `${subject}/${curriculum}`;
}

/**
 * Coordinators and Managers (MA1 items 3, `docs/admin-flow.md`) — **one** screen, twice.
 *
 * Both are the Teachers page's shape: a list with a phone number and a status, a create that mints
 * a password shown once, an edit of the three fields the server takes (`fullName`, `phone`,
 * `active`), a reset, and an editor for what the account may see. Only that last part differs — a
 * coordinator holds (subject, track) pairs, a manager holds whole curricula — so it is the one
 * thing branched on rather than a reason for two nearly identical pages.
 *
 * **The Teachers page itself is not this component.** It carries subjects, a curriculum, a photo
 * and the assignment picker, none of which either of these accounts has; sharing it would have
 * meant a page with half its controls switched off. What is shared is the part that was genuinely
 * the same: {@link OneTimePasswordComponent}, lifted out of it.
 */
@Component({
  selector: 'hq-staff-accounts-page',
  imports: [
    PageComponent,
    CardComponent,
    InputComponent,
    SelectComponent,
    CheckboxComponent,
    ButtonComponent,
    TableComponent,
    EmptyStateComponent,
    SkeletonComponent,
    BandComponent,
    DialogComponent,
    OneTimePasswordComponent,
    CanDirective,
    CdkMenu,
    CdkMenuItem,
    CdkMenuTrigger,
    NgTemplateOutlet,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './staff-accounts.page.html',
  styleUrl: './staff-accounts.page.scss',
})
export class StaffAccountsPage {
  private readonly coordinatorsApi = inject(CoordinatorsApi);
  private readonly managersApi = inject(ManagersApi);
  private readonly transloco = inject(TranslocoService);
  private readonly band = inject(BandService);
  private readonly undo = inject(UndoService);
  private readonly permissions = inject(PermissionService);
  private readonly router = inject(Router);
  private readonly lang = activeLang();

  /**
   * Read off the route rather than taken as an `input()`, because the route is what the rail and
   * the router already agree about (`core/nav/screens.ts`): a second declaration of "this URL is
   * the managers one" is a second thing to keep in step.
   */
  protected readonly kind: StaffKind =
    inject(ActivatedRoute).snapshot.data['screenId'] === 'managers' ? 'manager' : 'coordinator';

  /** The one write key of this screen — `coordinator.manage` or `manager.manage`, ADMIN only. */
  protected readonly manageKey = this.kind === 'manager' ? 'manager.manage' : 'coordinator.manage';

  private readonly words = this.kind === 'manager' ? 'admin.managers' : 'admin.coordinators';

  // ---- the list ---------------------------------------------------------------------------

  protected readonly staff = rxResource<readonly Person[], true>({
    params: () => true,
    stream: () => this.list(),
    defaultValue: [],
  });

  protected readonly filterText = signal('');

  protected readonly rows = computed<readonly StaffRow[]>(() => {
    this.lang();
    const query = this.filterText().trim().toLowerCase();
    return this.staff
      .value()
      .filter(
        (person) =>
          !query ||
          person.fullName.toLowerCase().includes(query) ||
          person.email.toLowerCase().includes(query) ||
          person.phone.includes(query),
      )
      .map((person) => ({
        id: person.userId,
        fullName: person.fullName,
        email: person.email,
        phone: person.phone,
        scopes: this.scopeWords(person),
        active: person.active,
        source: person,
      }));
  });

  protected readonly columns = computed<readonly TableColumn<StaffRow>[]>(() => {
    this.lang();
    return [
      { key: 'fullName', header: this.t('admin.people.table.name'), width: '20%' },
      { key: 'email', header: this.t('admin.people.table.email') },
      { key: 'phone', header: this.t('admin.people.table.phone'), width: '16%' },
      { key: 'scopes', header: this.t(`${this.words}.table.scopes`), width: '22%' },
      { key: 'active', header: this.t('admin.people.table.status'), width: '12%' },
    ];
  });

  protected trackRow = (row: StaffRow): string => row.id;

  protected readonly title = computed(() => {
    this.lang();
    return this.t(`${this.words}.title`);
  });

  protected readonly subtitle = computed(() => {
    this.lang();
    return this.t(`${this.words}.subtitle`);
  });

  protected readonly loadingLabel = computed(() => {
    this.lang();
    return this.t(`${this.words}.loading`);
  });

  protected readonly emptyMessage = computed(() => {
    this.lang();
    return this.t(`${this.words}.empty`);
  });

  protected readonly createAction = computed(() => {
    this.lang();
    return this.t(`${this.words}.createAction`);
  });

  /**
   * The empty state's action, or `null` when this account may not create one.
   *
   * `*hqCan` is structural and would take the whole empty state with it, and "none yet" is what a
   * read-only session needs to be told (`teachers.page.ts` made the same call).
   */
  protected readonly createLabel = computed(() =>
    this.permissions.can(this.manageKey) ? this.createAction() : null,
  );

  protected readonly scopesHeader = computed(() => {
    this.lang();
    return this.t(`${this.words}.table.scopes`);
  });

  // ---- create / edit ------------------------------------------------------------------------

  protected readonly formOpen = signal(false);
  protected readonly formMode = signal<FormMode>('create');
  protected readonly formError = signal<string | null>(null);
  protected readonly saving = signal(false);
  private readonly editing = signal<Person | null>(null);

  protected readonly fullName = signal('');
  protected readonly email = signal('');
  protected readonly phone = signal('');
  protected readonly active = signal(true);

  /** The coordinator's rows. One empty row to start, so the editor is never a bare button. */
  protected readonly scopeRows = signal<readonly ScopeRow[]>([{ subject: '', curriculum: '' }]);
  /** The manager's departments — a set on the scopes editor, one required on create. */
  protected readonly curricula = signal<ReadonlySet<Curriculum>>(new Set());
  protected readonly createCurriculum = signal<Curriculum | ''>('');

  protected readonly allSubjects = SUBJECTS;
  protected readonly allCurricula = CURRICULA;
  protected readonly isManager = this.kind === 'manager';

  protected readonly subjectOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return SUBJECTS.map((subject) => ({ value: subject, label: this.t(`subject.${subject}`) }));
  });

  protected readonly curriculumOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return CURRICULA.map((curriculum) => ({ value: curriculum, label: this.t(`curriculum.${curriculum}`) }));
  });

  protected readonly formTitle = computed(() => {
    this.lang();
    return this.t(`${this.words}.${this.formMode() === 'create' ? 'createTitle' : 'editTitle'}`);
  });

  protected readonly formReason = computed(() => {
    this.lang();
    return this.t(`${this.words}.formReason`);
  });

  protected readonly canSave = computed(() => {
    if (this.fullName().trim() === '') return false;
    if (this.formMode() === 'edit') return true;
    if (this.email().trim() === '') return false;
    if (this.isManager) return this.createCurriculum() !== '';
    return this.filledScopes().length > 0 && this.duplicateScope() === null;
  });

  /**
   * The rows that name a subject, **de-duplicated**. An empty row is a row she has not filled in,
   * not an error.
   *
   * `PUT …/scopes` refuses a subject named twice for one track with a 400 by design
   * (`CoordinatorAdminService.wanted`) — the unique index treats two "both tracks" rows of one
   * subject as distinct, so the refusal has to come from the service. That refusal must not be how
   * the Admin finds out: {@link duplicateScope} says so under the editor and the save is blocked,
   * and this never sends the pair twice even if something else got past that.
   */
  private readonly filledScopes = computed<readonly Scope[]>(() => {
    const seen = new Set<string>();
    const out: Scope[] = [];
    for (const row of this.scopeRows()) {
      const key = pairKey(row.subject, row.curriculum);
      // `Set.prototype.add` answers the Set itself, not whether it was new — `has` is the question.
      if (row.subject === '' || seen.has(key)) continue;
      seen.add(key);
      out.push({ subject: row.subject, curriculum: row.curriculum || undefined });
    }
    return out;
  });

  /**
   * The pair she has named twice, in words, or `null`.
   *
   * "Math · British" or "Math (both tracks)" — the same two shapes the server's own message uses,
   * because a coordinator who supervises Math on both tracks and a coordinator who supervises Math
   * on the British one are different rows and the sentence has to tell them apart.
   */
  protected readonly duplicateScope = computed(() => {
    this.lang();
    const seen = new Set<string>();
    for (const row of this.scopeRows()) {
      if (row.subject === '') continue;
      const key = pairKey(row.subject, row.curriculum);
      if (!seen.has(key)) {
        seen.add(key);
        continue;
      }
      const subject = this.word(`subject.${row.subject}`, row.subject);
      return this.t('admin.coordinators.scopes.duplicate', {
        scope: row.curriculum
          ? `${subject} · ${this.word(`curriculum.${row.curriculum}`, row.curriculum)}`
          : this.t('admin.coordinators.scopes.bothTracksOf', { subject }),
      });
    }
    return null;
  });

  protected setFullName(value: string): void {
    this.formError.set(null);
    this.fullName.set(value);
  }

  protected setEmail(value: string): void {
    this.formError.set(null);
    this.email.set(value);
  }

  protected setPhone(value: string): void {
    this.formError.set(null);
    this.phone.set(value);
  }

  protected setCreateCurriculum(value: string): void {
    this.formError.set(null);
    this.createCurriculum.set(value === 'american' || value === 'british' ? value : '');
  }

  protected hasCurriculum(curriculum: Curriculum): boolean {
    return this.curricula().has(curriculum);
  }

  protected toggleCurriculum(curriculum: Curriculum, on: boolean): void {
    const next = new Set(this.curricula());
    if (on) next.add(curriculum);
    else next.delete(curriculum);
    this.curricula.set(next);
  }

  protected setScopeSubject(index: number, value: string): void {
    this.patchScope(index, { subject: SUBJECTS.includes(value as Subject) ? (value as Subject) : '' });
  }

  protected setScopeCurriculum(index: number, value: string): void {
    this.patchScope(index, { curriculum: value === 'american' || value === 'british' ? value : '' });
  }

  private patchScope(index: number, patch: Partial<ScopeRow>): void {
    this.formError.set(null);
    this.scopeRows.update((rows) => rows.map((row, at) => (at === index ? { ...row, ...patch } : row)));
  }

  protected addScopeRow(): void {
    this.scopeRows.update((rows) => [...rows, { subject: '', curriculum: '' }]);
  }

  protected removeScopeRow(index: number): void {
    this.scopeRows.update((rows) => (rows.length === 1 ? rows : rows.filter((_row, at) => at !== index)));
  }

  protected openCreate(): void {
    this.formError.set(null);
    this.formMode.set('create');
    this.editing.set(null);
    this.fullName.set('');
    this.email.set('');
    this.phone.set('');
    this.active.set(true);
    this.scopeRows.set([{ subject: '', curriculum: '' }]);
    this.createCurriculum.set('');
    this.formOpen.set(true);
  }

  protected openEdit(person: Person | null): void {
    if (!person) return;
    this.formError.set(null);
    this.formMode.set('edit');
    this.editing.set(person);
    this.fullName.set(person.fullName);
    this.email.set(person.email);
    this.phone.set(person.phone);
    this.active.set(person.active);
    this.formOpen.set(true);
  }

  protected save(): void {
    if (!this.canSave()) return;
    this.saving.set(true);
    const editing = this.editing();
    if (this.formMode() === 'edit' && editing) {
      this.update(editing.userId, {
        fullName: this.fullName().trim(),
        phone: this.phone().trim(),
        active: this.active(),
      }).subscribe({ next: () => this.saved(), error: (error: unknown) => this.failed(error) });
      return;
    }
    this.create().subscribe({
      next: (created) => {
        this.saved();
        this.showPassword(this.fullName().trim(), created.temporaryPassword);
      },
      error: (error: unknown) => this.failed(error),
    });
  }

  private saved(): void {
    this.saving.set(false);
    this.formError.set(null);
    this.formOpen.set(false);
    this.staff.reload();
  }

  private failed(error: unknown): void {
    this.saving.set(false);
    const message = apiErrorOf(error)?.message ?? this.t('band.unreachable');
    this.formError.set(message);
    this.band.fail(message);
  }

  // ---- the scopes editor ---------------------------------------------------------------------

  protected readonly scopesFor = signal<Person | null>(null);
  protected readonly scopesOpen = signal(false);
  protected readonly scopesSaving = signal(false);

  protected readonly scopesTitle = computed(() => {
    this.lang();
    const person = this.scopesFor();
    return person ? this.t(`${this.words}.scopes.title`, { name: person.fullName }) : '';
  });

  protected readonly canSaveScopes = computed(() =>
    this.isManager
      ? this.curricula().size > 0
      : this.filledScopes().length > 0 && this.duplicateScope() === null,
  );

  protected openScopes(person: Person | null): void {
    if (!person) return;
    this.scopesFor.set(person);
    this.scopeRows.set(
      person.scopes.length === 0
        ? [{ subject: '', curriculum: '' }]
        : person.scopes.map((scope) => ({
            subject: (scope.subject ?? '') as Subject | '',
            curriculum: (scope.curriculum ?? '') as Curriculum | '',
          })),
    );
    this.curricula.set(
      new Set(person.curricula.filter((one): one is Curriculum => one === 'american' || one === 'british')),
    );
    this.scopesOpen.set(true);
  }

  protected saveScopes(): void {
    const person = this.scopesFor();
    if (!person || !this.canSaveScopes()) return;
    this.scopesSaving.set(true);
    const call: Observable<CoordinatorAccount | ManagerAccount> = this.isManager
      ? this.managersApi.setManagerDepartments(person.userId, { curricula: [...this.curricula()] })
      : this.coordinatorsApi.setCoordinatorScopes(person.userId, { scopes: [...this.filledScopes()] });
    call.subscribe({
      next: () => {
        this.scopesSaving.set(false);
        this.scopesOpen.set(false);
        this.scopesFor.set(null);
        this.staff.reload();
      },
      error: (error: unknown) => {
        this.scopesSaving.set(false);
        this.band.fail(apiErrorOf(error)?.message ?? this.t('band.unreachable'));
      },
    });
  }

  // ---- the one-time password ------------------------------------------------------------------

  private readonly password = signal<{ readonly forName: string; readonly value: string } | null>(null);

  protected readonly passwordValue = computed(() => this.password()?.value ?? null);

  protected readonly passwordTitle = computed(() => {
    this.lang();
    const shown = this.password();
    return shown ? this.t('admin.people.password.title', { name: shown.forName }) : '';
  });

  private showPassword(forName: string, value: string | undefined): void {
    if (!value) return;
    this.password.set({ forName, value });
  }

  protected dismissPassword(): void {
    this.password.set(null);
  }

  protected resetPassword(person: Person | null): void {
    if (!person) return;
    const call: Observable<TemporaryPassword> = this.isManager
      ? this.managersApi.resetManagerPassword(person.userId)
      : this.coordinatorsApi.resetCoordinatorPassword(person.userId);
    call.subscribe({
      next: (result: TemporaryPassword) => this.showPassword(person.fullName, result.temporaryPassword),
      error: (error: unknown) => this.band.fail(apiErrorOf(error)?.message ?? this.t('band.unreachable')),
    });
  }

  // ---- disable / enable ------------------------------------------------------------------------

  protected readonly menuRow = signal<StaffRow | null>(null);
  protected readonly pendingDisable = signal<StaffRow | null>(null);

  protected readonly confirmTitle = computed(() => {
    this.lang();
    return this.pendingDisable() ? this.t('admin.people.disableConfirm.title') : '';
  });

  protected readonly confirmMessage = computed(() => {
    this.lang();
    const row = this.pendingDisable();
    return row ? this.t('admin.people.disableConfirm.message', { name: row.fullName }) : '';
  });

  protected readonly confirmLabel = computed(() => {
    this.lang();
    return this.pendingDisable() ? this.t('admin.people.disableConfirm.confirm') : '';
  });

  protected requestDisable(row: StaffRow | null): void {
    if (row) this.pendingDisable.set(row);
  }

  protected cancelPending(): void {
    this.pendingDisable.set(null);
  }

  protected confirmPending(): void {
    const row = this.pendingDisable();
    this.pendingDisable.set(null);
    if (row) this.setActive(row, false);
  }

  protected setActive(row: StaffRow, active: boolean): void {
    this.patchActive(row.id, active);
    this.update(row.id, { active }).subscribe({
      next: () =>
        this.undo.offerUndo({
          message: this.t(active ? 'admin.people.undo.enabled' : 'admin.people.undo.disabled', {
            name: row.fullName,
          }),
          undo: () => this.setActive(row, !active),
        }),
      error: (error: unknown) => {
        this.patchActive(row.id, !active);
        this.band.fail(apiErrorOf(error)?.message ?? this.t('band.unreachable'));
      },
    });
  }

  private patchActive(userId: string, active: boolean): void {
    this.staff.update((rows) => rows.map((one) => (one.userId === userId ? { ...one, active } : one)));
  }

  // ---- the four calls that know which account this is ------------------------------------------

  private list(): Observable<readonly Person[]> {
    return this.isManager
      ? this.managersApi.managers().pipe(map((rows) => rows.map((one) => this.managerPerson(one))))
      : this.coordinatorsApi
          .coordinators()
          .pipe(map((rows) => rows.map((one) => this.coordinatorPerson(one))));
  }

  private create(): Observable<{ readonly temporaryPassword?: string }> {
    const body = { fullName: this.fullName().trim(), email: this.email().trim(), phone: this.phone().trim() };
    return this.isManager
      ? this.managersApi.createManager({ ...body, curriculum: this.createCurriculum() })
      : this.coordinatorsApi.createCoordinator({ ...body, scopes: [...this.filledScopes()] });
  }

  private update(
    id: string,
    body: { readonly fullName?: string; readonly phone?: string; readonly active?: boolean },
  ): Observable<CoordinatorAccount | ManagerAccount> {
    return this.isManager
      ? this.managersApi.updateManager(id, body)
      : this.coordinatorsApi.updateCoordinator(id, body);
  }

  private coordinatorPerson(one: CoordinatorAccount): Person {
    return {
      userId: one.userId ?? '',
      fullName: one.fullName?.trim() || (one.email ?? '').split('@')[0] || '',
      email: one.email ?? '',
      phone: one.phone ?? '',
      active: one.status !== 'disabled',
      scopes: one.scopes ?? [],
      curricula: [],
    };
  }

  private managerPerson(one: ManagerAccount): Person {
    return {
      userId: one.userId ?? '',
      fullName: one.fullName?.trim() || (one.email ?? '').split('@')[0] || '',
      email: one.email ?? '',
      phone: one.phone ?? '',
      active: one.status !== 'disabled',
      scopes: [],
      curricula: one.departments ?? [],
    };
  }

  /** What the Scopes column says: `Math · British`, or the departments, or "—". */
  private scopeWords(person: Person): string {
    const words = this.isManager
      ? person.curricula.map((one) => this.word(`curriculum.${one}`, one))
      : person.scopes.map((scope) => {
          const subject = this.word(`subject.${scope.subject ?? ''}`, scope.subject ?? '');
          const track = scope.curriculum ? this.word(`curriculum.${scope.curriculum}`, scope.curriculum) : '';
          return track ? `${subject} · ${track}` : subject;
        });
    return words.join(' / ');
  }

  private word(key: string, fallback: string): string {
    const text = this.t(key);
    return text === key ? fallback : text;
  }

  constructor() {
    // The password never survives leaving the screen ({@link OneTimePasswordComponent}).
    this.router.events
      .pipe(
        filter((event) => event instanceof NavigationStart),
        takeUntilDestroyed(),
      )
      .subscribe(() => this.dismissPassword());
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}
