/* hq-flag: none (shell) — gated by `admin.children.read` / `admin.children.write`. Admitting a
   child is not an optional feature of a school: `ChildrenAndParentsController` is in the server's
   own `FeatureFlagCoverageTest.INFRASTRUCTURE` list and `FlagKeys` has no key for it. */
import { CdkMenu, CdkMenuItem, CdkMenuTrigger } from '@angular/cdk/menu';
import { type OnDestroy, ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationStart, Router } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { filter } from 'rxjs';
import {
  type ChildAdmission,
  type FamilyRow,
  ChildrenAndParentsApi,
  ClassesApi,
  apiErrorCodeOf,
  apiErrorOf,
} from '../../api';
import { BandService } from '../../core/band/band.service';
import { MAX_PHONE_LENGTH, phoneErrorKey } from '../../core/forms/phone';
import { activeLang } from '../../core/i18n/active-lang';
import { CanDirective } from '../../core/permissions/can.directive';
import { PermissionService } from '../../core/permissions/permission.service';
import {
  type SelectOption,
  type TableColumn,
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
} from '../../ui';
import { CURRICULA, GRADES, type Curriculum } from '../lessons/lessons.models';
import { type AdminClass, byCourseThenName } from './admin.models';
import { AdminThreadService } from './admin-thread.service';
import { OneTimePasswordComponent } from './one-time-password.component';

/** One family, as the table shows it. */
interface FamilyView {
  readonly childId: string;
  readonly name: string;
  readonly className: string;
  readonly classId: string;
  readonly grade: string;
  readonly curriculum: string;
  readonly parentName: string;
  readonly parentEmail: string;
  readonly parentPhone: string;
  readonly source: FamilyRow;
}

/** The server caps `size` at 100; 25 is a page a person reads rather than scrolls past. */
const PAGE_SIZE = 25;
/** `ScreenSearchService`'s own number: long enough that a word is one request, not eight. */
const DEBOUNCE_MS = 250;
/** The server's floor. Enforced here too, so the field says so before the request. */
const MIN_PASSWORD = 8;
/** `ChildAdmissionService.MAX_CHILD_NAME` / `MAX_PARENT_NAME`, mirrored so the field stops at them. */
const MAX_CHILD_NAME = 40;
const MAX_PARENT_NAME = 80;

/**
 * Children & parents (MA1 item 5, `docs/admin-flow.md`) — the one Admin screen that creates a login
 * the server does not own.
 *
 * `POST /admin/children` writes three things in one request: the parent's login (in Firebase Auth,
 * through `quest.server.auth.ParentAccounts`), the local `parents` row, and the child on the
 * section's roster. Two flags come back and the screen has to read **both** out loud, because they
 * answer different questions:
 *
 * - `parentCreated` — whether a local `parents` row was written. `false` means a family this school
 *   already holds got a second child.
 * - `passwordApplied` — whether the password typed into this form is the one that now works. It is
 *   `false` whenever the login already existed, because overwriting a stranger's password is not
 *   this route's job — and when it is `false` the paper the Admin has just written the password on
 *   is worthless. That sentence is the whole point of the success sheet.
 *
 * **Not the roster.** Attaching a child who is already in the school to another section, moving one
 * between sections and the join code all stay on the Classes page; this screen is admission, which
 * is why it says so under its title rather than growing a second set of roster tools.
 */
@Component({
  selector: 'hq-children-page',
  imports: [
    PageComponent,
    CardComponent,
    InputComponent,
    SelectComponent,
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
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './children.page.html',
  styleUrl: './children.page.scss',
})
export class ChildrenPage implements OnDestroy {
  private readonly api = inject(ChildrenAndParentsApi);
  private readonly classesApi = inject(ClassesApi);
  private readonly transloco = inject(TranslocoService);
  private readonly band = inject(BandService);
  private readonly permissions = inject(PermissionService);
  private readonly router = inject(Router);
  /** D1: "Message" on a row — the Admin's thread with this child's parent. */
  protected readonly threads = inject(AdminThreadService);
  private readonly lang = activeLang();

  // ---- the list ---------------------------------------------------------------------------

  protected readonly search = signal('');
  private readonly needle = signal('');
  private debounce: ReturnType<typeof setTimeout> | null = null;
  protected readonly page = signal(0);

  protected readonly families = rxResource({
    params: () => ({ q: this.needle(), page: this.page() }),
    stream: ({ params }) => this.api.families(params.q === '' ? undefined : params.q, params.page, PAGE_SIZE),
    defaultValue: { rows: [], total: 0 },
  });

  protected readonly sections = rxResource<readonly AdminClass[], true>({
    params: () => true,
    stream: () => this.classesApi.classes(),
    defaultValue: [],
  });

  protected readonly rows = computed<readonly FamilyView[]>(() => {
    this.lang();
    return (this.families.value().rows ?? []).map((row) => ({
      childId: row.childId ?? '',
      name: row.name ?? '',
      className: row.className ?? '',
      classId: row.classId ?? '',
      grade: row.grade === undefined ? '' : this.t('coordinator.classes.grade', { grade: row.grade }),
      curriculum: this.word(`curriculum.${row.curriculum ?? ''}`, row.curriculum ?? ''),
      parentName: row.parentName ?? '',
      parentEmail: row.parentEmail ?? '',
      parentPhone: row.parentPhone ?? '',
      source: row,
    }));
  });

  protected readonly columns = computed<readonly TableColumn<FamilyView>[]>(() => {
    this.lang();
    return [
      { key: 'name', header: this.t('admin.children.table.child'), width: '18%' },
      { key: 'className', header: this.t('admin.children.table.class'), width: '10%' },
      { key: 'grade', header: this.t('admin.children.table.grade'), width: '10%' },
      { key: 'curriculum', header: this.t('admin.children.table.curriculum'), width: '12%' },
      { key: 'parentName', header: this.t('admin.children.table.parent'), width: '16%' },
      { key: 'parentEmail', header: this.t('admin.children.table.parentEmail') },
      { key: 'parentPhone', header: this.t('admin.people.table.phone'), width: '14%' },
    ];
  });

  protected trackRow = (row: FamilyView): string => row.childId;

  protected readonly hasNext = computed(
    () => (this.page() + 1) * PAGE_SIZE < (this.families.value().total ?? 0),
  );

  protected readonly pageLine = computed(() => {
    this.lang();
    const body = this.families.value();
    const rows = body.rows ?? [];
    const first = rows.length === 0 ? 0 : this.page() * PAGE_SIZE + 1;
    return this.t('admin.children.page', {
      first,
      last: first === 0 ? 0 : first + rows.length - 1,
      total: body.total ?? 0,
    });
  });

  protected readonly createLabel = computed(() => {
    this.lang();
    return this.permissions.can('admin.children.write') ? this.t('admin.children.createAction') : null;
  });

  /**
   * A new needle is a new list, so page 1 of it. Clearing is not debounced — emptying the field
   * puts the whole school back at once and there is nothing to type ahead of.
   */
  protected onSearch(value: string): void {
    this.search.set(value);
    this.page.set(0);
    this.stopDebounce();
    if (value.trim() === '') {
      this.needle.set('');
      return;
    }
    this.debounce = setTimeout(() => {
      this.debounce = null;
      this.needle.set(value.trim());
    }, DEBOUNCE_MS);
  }

  private stopDebounce(): void {
    if (this.debounce !== null) clearTimeout(this.debounce);
    this.debounce = null;
  }

  // ---- the class select, filtered -------------------------------------------------------------

  protected readonly curriculum = signal<Curriculum | ''>('');
  protected readonly grade = signal<number | ''>('');

  protected readonly curriculumOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return CURRICULA.map((one) => ({ value: one, label: this.t(`curriculum.${one}`) }));
  });

  protected readonly gradeOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return GRADES.map((one) => ({
      value: String(one),
      label: this.t('coordinator.classes.grade', { grade: one }),
    }));
  });

  /**
   * Only the active sections of the chosen curriculum **and** grade.
   *
   * The server refuses a mismatch with a 400 — a Grade 1 British child in a Grade 1 American class
   * would be shown lessons for a syllabus she is not taught — so the form never offers one. Until
   * both are chosen the select is empty and says which answer it is waiting for, rather than
   * listing every section of the school for her to pick the wrong one out of.
   */
  protected readonly classOptions = computed<readonly SelectOption[]>(() => {
    const curriculum = this.curriculum();
    const grade = this.grade();
    if (curriculum === '' || grade === '') return [];
    return [...this.sections.value()]
      .filter(
        (one) =>
          one.active !== false &&
          one.curriculum === curriculum &&
          one.grade === grade &&
          // A section with no name would render an option with an empty label — a blank line she
          // can select and cannot tell from the placeholder. `GET /admin/classes` widens a record
          // springdoc publishes narrow (`admin.models.ts`), so `name` is optional to this client.
          (one.name ?? '').trim() !== '' &&
          (one.id ?? '') !== '',
      )
      .sort(byCourseThenName)
      .map((one) => ({ value: one.id ?? '', label: (one.name ?? '').trim() }));
  });

  protected readonly classHint = computed(() => {
    this.lang();
    if (this.curriculum() === '' || this.grade() === '') return this.t('admin.children.form.classWaiting');
    return this.classOptions().length === 0 ? this.t('admin.children.form.classNone') : null;
  });

  // ---- admit ----------------------------------------------------------------------------------

  /**
   * **One** dialog for admission and for the edit, not two.
   *
   * `hq-dialog` projects its content into the DOM whether or not the `<dialog>` is showing, so two
   * dialogs asking for the child's name, her curriculum, her grade and her section would put two
   * sets of those controls in the accessibility tree and only one of them on screen. The fields
   * that differ are the three the admission needs and the edit route does not take.
   */
  protected readonly formOpen = signal(false);
  protected readonly formMode = signal<'create' | 'edit'>('create');
  protected readonly saving = signal(false);
  protected readonly formError = signal<string | null>(null);
  /** The 503: Firebase is not configured here, and no amount of retrying will change that. */
  protected readonly notConfigured = signal(false);

  protected readonly name = signal('');
  protected readonly classId = signal('');
  protected readonly parentName = signal('');
  protected readonly parentPhone = signal('');
  protected readonly parentEmail = signal('');
  protected readonly parentPassword = signal('');
  protected readonly revealPassword = signal(false);

  protected readonly passwordType = computed(() => (this.revealPassword() ? 'text' : 'password'));

  /**
   * Why this password will not do, or `null`.
   *
   * Both rules are the server's (`ChildAdmissionService`), checked here so the answer arrives while
   * she is still typing rather than as a red band after a request that also wrote nothing.
   */
  protected readonly passwordError = computed(() => {
    this.lang();
    const password = this.parentPassword().trim();
    if (password === '') return null;
    if (password.length < MIN_PASSWORD)
      return this.t('admin.children.form.passwordShort', { min: MIN_PASSWORD });
    if (password.toLowerCase() === this.parentEmail().trim().toLowerCase())
      return this.t('admin.children.form.passwordIsEmail');
    return null;
  });

  /**
   * Weak / fair / strong — length first, then whether more than one kind of character is in it.
   *
   * A hint and never a gate: the server's rule is the eight characters, and a screen that refused
   * a password the server accepts would leave the Admin arguing with a strength meter in front of
   * a parent.
   */
  protected readonly passwordStrength = computed(() => {
    this.lang();
    const password = this.parentPassword().trim();
    if (password === '' || this.passwordError() !== null) return null;
    const kinds = [/[a-z]/, /[A-Z]/, /[0-9]/, /[^A-Za-z0-9]/].filter((kind) => kind.test(password)).length;
    const level =
      password.length >= 12 && kinds >= 3 ? 'strong' : password.length >= 10 || kinds >= 3 ? 'fair' : 'weak';
    return this.t(`admin.children.form.strength.${level}`);
  });

  /** Why this number will not do, or `null` — `core/forms/phone.ts`, the server's own rule. */
  protected readonly phoneError = computed(() => {
    this.lang();
    const key = phoneErrorKey(this.parentPhone());
    return key === null ? null : this.t(key);
  });

  protected readonly maxPhone = MAX_PHONE_LENGTH;
  protected readonly maxChildName = MAX_CHILD_NAME;
  protected readonly maxParentName = MAX_PARENT_NAME;

  protected readonly canAdmit = computed(
    () =>
      this.name().trim() !== '' &&
      this.classId() !== '' &&
      this.parentName().trim() !== '' &&
      this.parentEmail().trim() !== '' &&
      this.parentPassword().trim() !== '' &&
      this.passwordError() === null &&
      this.phoneError() === null,
  );

  protected setCurriculum(value: string): void {
    this.formError.set(null);
    this.curriculum.set(value === 'american' || value === 'british' ? value : '');
    // The section she had picked belongs to the old pair, so it is no longer an answer.
    this.classId.set('');
  }

  protected setGrade(value: string): void {
    this.formError.set(null);
    const grade = Number(value);
    this.grade.set(GRADES.includes(grade) ? grade : '');
    this.classId.set('');
  }

  protected openCreate(): void {
    this.formError.set(null);
    this.notConfigured.set(false);
    this.formMode.set('create');
    this.editing.set(null);
    this.name.set('');
    this.curriculum.set('');
    this.grade.set('');
    this.classId.set('');
    this.parentName.set('');
    this.parentPhone.set('');
    this.parentEmail.set('');
    this.parentPassword.set('');
    this.revealPassword.set(false);
    this.formOpen.set(true);
  }

  private admit(): void {
    if (!this.canAdmit()) return;
    this.saving.set(true);
    this.notConfigured.set(false);
    const curriculum = this.curriculum();
    const grade = this.grade();
    this.api
      .admitChild({
        name: this.name().trim(),
        classId: this.classId(),
        curriculum: curriculum || undefined,
        grade: grade === '' ? undefined : grade,
        parentName: this.parentName().trim(),
        parentPhone: this.parentPhone().trim() || undefined,
        parentEmail: this.parentEmail().trim(),
        parentInitialPassword: this.parentPassword().trim(),
      })
      .subscribe({
        next: (admission) => {
          this.saving.set(false);
          this.formOpen.set(false);
          this.admitted.set({ childName: this.name().trim(), admission });
          // It has been sent and it is hers now: nothing on this screen has any further use for it,
          // and a signal still holding it until the next `openCreate()` is a secret kept for no
          // reason. The band beside this says whether it was even applied.
          this.parentPassword.set('');
          this.revealPassword.set(false);
          this.families.reload();
        },
        error: (error: unknown) => {
          this.saving.set(false);
          // `unavailable` is `ParentAccountsConfig`'s 503: no Firebase credentials on this server.
          // A sentence naming the deployment, not the person.
          //
          // **It goes in the dialog she is looking at, not only on the page behind it.** `hq-dialog`
          // opens with `showModal()`, so the page's own band sits behind the backdrop: the first cut
          // set `notConfigured` alone and, on a deployment without Firebase, the Admin saw the
          // spinner stop and nothing else. Both are set — the dialog explains why this admission did
          // not happen, and the page's band is still there when she closes it, which is also where a
          // failed "Reset parent password" from a row lands.
          if (apiErrorCodeOf(error) === 'unavailable') {
            this.notConfigured.set(true);
            this.formError.set(this.t('admin.children.notConfigured.message'));
            return;
          }
          const message = apiErrorOf(error)?.message ?? this.t('band.unreachable');
          this.formError.set(message);
          this.band.fail(message);
        },
      });
  }

  // ---- the success sheet ------------------------------------------------------------------------

  protected readonly admitted = signal<{
    readonly childName: string;
    readonly admission: ChildAdmission;
  } | null>(null);

  protected readonly admittedTitle = computed(() => {
    this.lang();
    const done = this.admitted();
    return done ? this.t('admin.children.admitted.title', { name: done.childName }) : '';
  });

  /** "A new login was created" or "this family already had one". */
  protected readonly parentLine = computed(() => {
    this.lang();
    const done = this.admitted();
    if (!done) return '';
    return this.t(
      done.admission.parentCreated === true
        ? 'admin.children.admitted.parentCreated'
        : 'admin.children.admitted.parentReused',
    );
  });

  /** The one sentence that decides whether the password she wrote down is worth anything. */
  protected readonly passwordLine = computed(() => {
    this.lang();
    const done = this.admitted();
    if (!done) return '';
    return this.t(
      done.admission.passwordApplied === true
        ? 'admin.children.admitted.passwordApplied'
        : 'admin.children.admitted.passwordKept',
    );
  });

  protected readonly passwordApplied = computed(() => this.admitted()?.admission.passwordApplied === true);

  // ---- edit -------------------------------------------------------------------------------------

  protected readonly menuRow = signal<FamilyView | null>(null);
  private readonly editing = signal<FamilyView | null>(null);

  protected readonly isEdit = computed(() => this.formMode() === 'edit');

  protected readonly formTitle = computed(() => {
    this.lang();
    const row = this.editing();
    return row
      ? this.t('admin.children.editTitle', { name: row.name })
      : this.t('admin.children.createTitle');
  });

  protected readonly formConfirm = computed(() => {
    this.lang();
    return this.t(this.isEdit() ? 'ui.save' : 'admin.children.admitAction');
  });

  /**
   * The edit takes the child's name, a section and the parent's name and number; the admission
   * takes the parent's email and her password too.
   */
  protected readonly canSave = computed(() =>
    this.isEdit()
      ? this.name().trim() !== '' &&
        this.classId() !== '' &&
        (!this.editingHasParent() || this.parentName().trim() !== '') &&
        this.phoneError() === null
      : this.canAdmit(),
  );

  /**
   * Whether the child being edited has a parent account at all. One admitted from a roster has
   * none until somebody registers — her name and section must still be saveable, so the parent's
   * name is asked for, and sent, only when there is a parent to carry it.
   */
  protected readonly editingHasParent = computed(() => (this.editing()?.parentEmail ?? '') !== '');
  /** Always at admission (the contract requires it); on an edit, only for a child with a parent. */
  protected readonly parentNameRequired = computed(() => !this.isEdit() || this.editingHasParent());

  /**
   * D1: the parent's thread is named by the child — `{childId}` — because a parent has no user id
   * here. Offered only on a row that has a parent: without one the server answers `no_parent`.
   */
  protected message(row: FamilyView | null): void {
    if (row) this.threads.open(row.childId, { childId: row.childId });
  }

  protected save(): void {
    if (this.isEdit()) this.saveEdit();
    else this.admit();
  }

  /**
   * Name, section, and the parent's name and telephone number (D1 added the name).
   *
   * **Not the grade**, although the form asks for one: `PATCH /admin/children/{id}` takes the
   * section and a section carries its own grade and curriculum, so the two selects above the class
   * are how she *finds* the section rather than three fields that could disagree. Moving a child to
   * another grade is choosing a section in it.
   */
  protected openEdit(row: FamilyView | null): void {
    if (!row) return;
    this.formError.set(null);
    this.formMode.set('edit');
    this.editing.set(row);
    this.name.set(row.name);
    this.curriculum.set(
      row.source.curriculum === 'american' || row.source.curriculum === 'british'
        ? row.source.curriculum
        : '',
    );
    this.grade.set(typeof row.source.grade === 'number' ? row.source.grade : '');
    this.classId.set(row.classId);
    this.parentName.set(row.parentName);
    this.parentPhone.set(row.parentPhone);
    this.formOpen.set(true);
  }

  private saveEdit(): void {
    const row = this.editing();
    if (!row || !this.canSave()) return;
    this.saving.set(true);
    this.classesApi
      .updateChild1(row.childId, {
        name: this.name().trim(),
        classId: this.classId(),
        parentPhone: this.parentPhone().trim(),
        ...(this.editingHasParent() ? { parentName: this.parentName().trim() } : {}),
      })
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.formOpen.set(false);
          this.editing.set(null);
          this.families.reload();
        },
        error: (error: unknown) => {
          this.saving.set(false);
          const message = apiErrorOf(error)?.message ?? this.t('band.unreachable');
          this.formError.set(message);
          this.band.fail(message);
        },
      });
  }

  // ---- the parent's password --------------------------------------------------------------------

  protected readonly pendingReset = signal<FamilyView | null>(null);
  private readonly password = signal<{ readonly forName: string; readonly value: string } | null>(null);

  protected readonly resetTitle = computed(() => {
    this.lang();
    return this.pendingReset() ? this.t('admin.children.reset.title') : '';
  });

  protected readonly resetMessage = computed(() => {
    this.lang();
    const row = this.pendingReset();
    return row ? this.t('admin.children.reset.message', { name: row.parentName || row.parentEmail }) : '';
  });

  protected readonly resetConfirm = computed(() => {
    this.lang();
    return this.pendingReset() ? this.t('admin.children.reset.confirm') : '';
  });

  protected readonly passwordValue = computed(() => this.password()?.value ?? null);

  protected readonly passwordTitle = computed(() => {
    this.lang();
    const shown = this.password();
    return shown ? this.t('admin.people.password.title', { name: shown.forName }) : '';
  });

  protected dismissPassword(): void {
    this.password.set(null);
  }

  protected confirmReset(): void {
    const row = this.pendingReset();
    this.pendingReset.set(null);
    if (!row) return;
    this.api.resetParentPassword(row.childId).subscribe({
      next: (result) => {
        if (result.temporaryPassword)
          this.password.set({
            forName: row.parentName || row.parentEmail,
            value: result.temporaryPassword,
          });
      },
      error: (error: unknown) => {
        if (apiErrorCodeOf(error) === 'unavailable') {
          this.notConfigured.set(true);
          return;
        }
        this.band.fail(apiErrorOf(error)?.message ?? this.t('band.unreachable'));
      },
    });
  }

  constructor() {
    // Shown once means once: leaving the screen drops it ({@link OneTimePasswordComponent}).
    this.router.events
      .pipe(
        filter((event) => event instanceof NavigationStart),
        takeUntilDestroyed(),
      )
      .subscribe(() => this.dismissPassword());
  }

  ngOnDestroy(): void {
    this.stopDebounce();
  }

  private word(key: string, fallback: string): string {
    const text = this.t(key);
    return text === key ? fallback : text;
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}
