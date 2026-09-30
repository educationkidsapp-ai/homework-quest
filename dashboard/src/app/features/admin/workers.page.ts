/* hq-flag: none (shell) — gated by `worker.read` / `worker.write`. The server's own
   `WorkerController` is in `FeatureFlagCoverageTest.INFRASTRUCTURE` for the reason this screen
   carries no flag: a school's caretakers, drivers and nurses are not an optional feature of it,
   and `FlagKeys` has no key that could turn them off. */
import { CdkMenu, CdkMenuItem, CdkMenuTrigger } from '@angular/cdk/menu';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { type Worker, WorkersApi, apiErrorOf } from '../../api';
import { BandService } from '../../core/band/band.service';
import { MAX_PHONE_LENGTH, phoneErrorKey } from '../../core/forms/phone';
import { activeLang } from '../../core/i18n/active-lang';
import { CanDirective } from '../../core/permissions/can.directive';
import { PermissionService } from '../../core/permissions/permission.service';
import { UndoService } from '../../core/undo/undo.service';
import {
  type TableColumn,
  BandComponent,
  ButtonComponent,
  CardComponent,
  CheckboxComponent,
  DialogComponent,
  EmptyStateComponent,
  InputComponent,
  PageComponent,
  SkeletonComponent,
  TableComponent,
} from '../../ui';

/** A worker as the table shows her. */
interface WorkerRow {
  readonly id: string;
  readonly fullName: string;
  readonly job: string;
  readonly phone: string;
  readonly active: boolean;
  readonly source: Worker;
}

/**
 * Workers (MA1 item 4, `docs/admin-flow.md`) — the staff who do not teach: the nurse, the drivers,
 * the caretakers, the office.
 *
 * **No account comes into being.** `POST /admin/workers` writes a row with a name, a job, a mobile
 * number and nothing else — no login, no password, no role — so this screen has no create-password
 * band, no reset and no email column, and it is the one people screen of the Admin's that cannot
 * lock anybody out. The reason is the server's: an account nobody signs in to is an account nobody
 * rotates.
 *
 * **Retire, not delete.** `DELETE /admin/workers/{id}` sets `active = false` and removes no row, so
 * the confirm band says "retire" and the row stays on screen greyed rather than vanishing — a
 * number somebody rang last March is part of the school's record. It is a red confirm band rather
 * than the 10-second Undo strip every reversible action gets, because the person reading the list
 * is deciding about a colleague and ought to be asked once.
 */
@Component({
  selector: 'hq-workers-page',
  imports: [
    PageComponent,
    CardComponent,
    InputComponent,
    CheckboxComponent,
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
  template: `
    <hq-page [title]="'admin.workers.title' | transloco" [subtitle]="'admin.workers.subtitle' | transloco">
      <div class="workers">
        @if (pendingRetire(); as row) {
          <hq-band
            variant="confirm"
            [open]="true"
            [title]="'admin.workers.retireConfirm.title' | transloco"
            [confirmLabel]="'admin.workers.retireConfirm.confirm' | transloco"
            (confirmed)="confirmRetire()"
            (dismissed)="pendingRetire.set(null)"
          >
            {{ 'admin.workers.retireConfirm.message' | transloco: { name: row.fullName } }}
          </hq-band>
        }

        <div data-hq-search class="workers__search">
          <hq-input
            type="search"
            keycap="/"
            [label]="'admin.workers.filter.label' | transloco"
            [placeholder]="'admin.workers.filter.placeholder' | transloco"
            [value]="filterText()"
            (valueChange)="filterText.set($event)"
          />
        </div>

        @if (staff.isLoading()) {
          <hq-skeleton
            [loading]="true"
            [lines]="6"
            height="var(--hq-size-row-height)"
            [label]="'admin.workers.loading' | transloco"
          />
        } @else if (staff.error()) {
          <hq-card>
            <hq-band variant="error" [open]="true" [title]="'band.failed' | transloco" [dismissible]="false">
              {{ 'band.unreachable' | transloco }}
            </hq-band>
            <div class="workers__retry">
              <hq-button variant="secondary" (pressed)="staff.reload()">
                {{ 'ui.retry' | transloco }}
              </hq-button>
            </div>
          </hq-card>
        } @else {
          <hq-card [flush]="true">
            <hq-table
              [rows]="rows()"
              [columns]="columns()"
              [cellTemplate]="cell"
              [overflowTemplate]="overflow"
              [trackBy]="trackRow"
              [label]="'admin.workers.title' | transloco"
            >
              <hq-empty-state
                table-empty
                [message]="'admin.workers.empty' | transloco"
                [actionLabel]="createLabel()"
                (action)="openCreate()"
              />
            </hq-table>
          </hq-card>
        }
      </div>

      <ng-template #cell let-row let-column="column">
        @switch (column.key) {
          @case ('fullName') {
            {{ row.fullName }}
          }
          @case ('job') {
            {{ row.job }}
          }
          @case ('phone') {
            @if (row.phone) {
              <a [href]="'tel:' + row.phone" dir="ltr">{{ row.phone }}</a>
            } @else {
              <span class="hq-muted">—</span>
            }
          }
          @case ('active') {
            <span
              class="hq-badge"
              [class.hq-badge--success]="row.active"
              [class.hq-badge--error]="!row.active"
            >
              {{ (row.active ? 'admin.workers.working' : 'admin.workers.retired') | transloco }}
            </span>
          }
        }
      </ng-template>

      <ng-template #overflow let-row>
        <button
          type="button"
          class="workers__overflow"
          [cdkMenuTriggerFor]="rowMenu"
          (click)="menuRow.set(row)"
          [attr.aria-label]="'admin.people.rowActions' | transloco: { name: row.fullName }"
        >
          ⋮
        </button>
      </ng-template>

      <ng-template #rowMenu>
        <div cdkMenu class="hq-menu" [attr.aria-label]="'ui.table.actions' | transloco">
          <button
            type="button"
            cdkMenuItem
            class="hq-menu__item"
            *hqCan="'worker.write'"
            (cdkMenuItemTriggered)="openEdit(menuRow()?.source ?? null)"
          >
            {{ 'admin.people.editAction' | transloco }}
          </button>
          @if (menuRow()?.active) {
            <button
              type="button"
              cdkMenuItem
              class="hq-menu__item hq-menu__item--danger"
              *hqCan="'worker.write'"
              (cdkMenuItemTriggered)="pendingRetire.set(menuRow())"
            >
              {{ 'admin.workers.retire' | transloco }}
            </button>
          } @else {
            <button
              type="button"
              cdkMenuItem
              class="hq-menu__item"
              *hqCan="'worker.write'"
              (cdkMenuItemTriggered)="reinstate(menuRow()!)"
            >
              {{ 'admin.workers.reinstate' | transloco }}
            </button>
          }
        </div>
      </ng-template>

      <hq-dialog
        [(open)]="formOpen"
        [title]="formTitle()"
        [confirmLabel]="'ui.save' | transloco"
        [confirmDisabled]="!canSave()"
        [confirmReason]="canSave() ? null : ('admin.workers.formReason' | transloco)"
        [loading]="saving()"
        (confirmed)="save()"
      >
        @if (formError(); as err) {
          <hq-band
            variant="error"
            [open]="true"
            [title]="'band.failed' | transloco"
            (dismissed)="formError.set(null)"
          >
            {{ err }}
          </hq-band>
        }
        <hq-input
          [label]="'admin.people.form.fullName' | transloco"
          [value]="fullName()"
          [required]="true"
          (valueChange)="setFullName($event)"
        />
        <hq-input
          [label]="'admin.workers.form.job' | transloco"
          [value]="job()"
          [required]="true"
          [hint]="'admin.workers.form.jobHint' | transloco"
          (valueChange)="setJob($event)"
        />
        <hq-input
          [label]="'admin.people.form.phone' | transloco"
          type="tel"
          autocomplete="off"
          [value]="phone()"
          [maxLength]="maxPhone"
          [error]="phoneError()"
          [hint]="'form.phone.hint' | transloco"
          (valueChange)="setPhone($event)"
        />
        @if (formMode() === 'edit') {
          <hq-checkbox
            [label]="'admin.workers.form.working' | transloco"
            [checked]="active()"
            (checkedChange)="active.set($event)"
          />
        }
      </hq-dialog>

      <div page-footer>
        <hq-button *hqCan="'worker.write'" variant="primary" (pressed)="openCreate()">
          {{ 'admin.workers.createAction' | transloco }}
        </hq-button>
      </div>
    </hq-page>
  `,
  styles: `
    .workers__search {
      margin-block: var(--hq-space-16);
      max-width: 28rem;
    }

    .workers__retry {
      display: flex;
      justify-content: flex-end;
      margin-block-start: var(--hq-space-16);
    }

    .workers__overflow {
      background: none;
      border: 0;
      cursor: pointer;
      color: var(--hq-color-ink);
      font-size: var(--hq-text-theme-lg);
      line-height: 1;
      padding: var(--hq-space-8);
    }
  `,
})
export class WorkersPage {
  private readonly api = inject(WorkersApi);
  private readonly transloco = inject(TranslocoService);
  private readonly band = inject(BandService);
  private readonly undo = inject(UndoService);
  private readonly permissions = inject(PermissionService);
  private readonly lang = activeLang();

  protected readonly staff = rxResource<readonly Worker[], true>({
    params: () => true,
    stream: () => this.api.workers(),
    defaultValue: [],
  });

  protected readonly filterText = signal('');

  protected readonly rows = computed<readonly WorkerRow[]>(() => {
    const query = this.filterText().trim().toLowerCase();
    return this.staff
      .value()
      .filter(
        (worker) =>
          !query ||
          (worker.fullName ?? '').toLowerCase().includes(query) ||
          (worker.job ?? '').toLowerCase().includes(query) ||
          (worker.phone ?? '').includes(query),
      )
      .map((worker) => ({
        id: worker.id ?? '',
        fullName: worker.fullName ?? '',
        job: worker.job ?? '',
        phone: worker.phone ?? '',
        active: worker.active !== false,
        source: worker,
      }));
  });

  protected readonly columns = computed<readonly TableColumn<WorkerRow>[]>(() => {
    this.lang();
    return [
      { key: 'fullName', header: this.t('admin.people.table.name'), width: '28%' },
      { key: 'job', header: this.t('admin.workers.table.job') },
      { key: 'phone', header: this.t('admin.people.table.phone'), width: '20%' },
      { key: 'active', header: this.t('admin.people.table.status'), width: '14%' },
    ];
  });

  protected trackRow = (row: WorkerRow): string => row.id;

  /** `null` for an account that may not write: `*hqCan` on the empty state would hide it whole. */
  protected readonly createLabel = computed(() => {
    this.lang();
    return this.permissions.can('worker.write') ? this.t('admin.workers.createAction') : null;
  });

  // ---- create / edit ------------------------------------------------------------------------

  protected readonly formOpen = signal(false);
  protected readonly formMode = signal<'create' | 'edit'>('create');
  protected readonly formError = signal<string | null>(null);
  protected readonly saving = signal(false);
  private readonly editing = signal<Worker | null>(null);

  protected readonly fullName = signal('');
  protected readonly job = signal('');
  protected readonly phone = signal('');
  protected readonly active = signal(true);

  protected readonly formTitle = computed(() => {
    this.lang();
    return this.t(this.formMode() === 'create' ? 'admin.workers.createTitle' : 'admin.workers.editTitle');
  });

  /** Why this number will not do, or `null` — `core/forms/phone.ts`, the server's own rule. */
  protected readonly phoneError = computed(() => {
    this.lang();
    const key = phoneErrorKey(this.phone());
    return key === null ? null : this.t(key);
  });

  protected readonly maxPhone = MAX_PHONE_LENGTH;

  protected readonly canSave = computed(
    () => this.fullName().trim() !== '' && this.job().trim() !== '' && this.phoneError() === null,
  );

  protected setFullName(value: string): void {
    this.formError.set(null);
    this.fullName.set(value);
  }

  protected setJob(value: string): void {
    this.formError.set(null);
    this.job.set(value);
  }

  protected setPhone(value: string): void {
    this.formError.set(null);
    this.phone.set(value);
  }

  protected openCreate(): void {
    this.formError.set(null);
    this.formMode.set('create');
    this.editing.set(null);
    this.fullName.set('');
    this.job.set('');
    this.phone.set('');
    this.active.set(true);
    this.formOpen.set(true);
  }

  protected openEdit(worker: Worker | null): void {
    if (!worker) return;
    this.formError.set(null);
    this.formMode.set('edit');
    this.editing.set(worker);
    this.fullName.set(worker.fullName ?? '');
    this.job.set(worker.job ?? '');
    this.phone.set(worker.phone ?? '');
    this.active.set(worker.active !== false);
    this.formOpen.set(true);
  }

  protected save(): void {
    if (!this.canSave()) return;
    this.saving.set(true);
    const body = { fullName: this.fullName().trim(), job: this.job().trim(), phone: this.phone().trim() };
    const editing = this.editing();
    const done = (): void => {
      this.saving.set(false);
      this.formError.set(null);
      this.formOpen.set(false);
      this.staff.reload();
    };
    const fail = (error: unknown): void => {
      this.saving.set(false);
      const message = apiErrorOf(error)?.message ?? this.t('band.unreachable');
      this.formError.set(message);
      this.band.fail(message);
    };
    if (this.formMode() === 'edit' && editing) {
      this.api
        .updateWorker(editing.id ?? '', { ...body, active: this.active() })
        .subscribe({ next: done, error: fail });
      return;
    }
    this.api.createWorker(body).subscribe({ next: done, error: fail });
  }

  // ---- retire / reinstate ---------------------------------------------------------------------

  protected readonly menuRow = signal<WorkerRow | null>(null);
  protected readonly pendingRetire = signal<WorkerRow | null>(null);

  protected confirmRetire(): void {
    const row = this.pendingRetire();
    this.pendingRetire.set(null);
    if (!row) return;
    this.patchActive(row.id, false);
    this.api.retireWorker(row.id).subscribe({
      next: () =>
        this.undo.offerUndo({
          message: this.t('admin.workers.undo.retired', { name: row.fullName }),
          undo: () => this.reinstate(row),
        }),
      error: (error: unknown) => {
        this.patchActive(row.id, true);
        this.band.fail(apiErrorOf(error)?.message ?? this.t('band.unreachable'));
      },
    });
  }

  /** The other half of the retire: `PATCH {active: true}`, which is what the Undo strip calls. */
  protected reinstate(row: WorkerRow): void {
    this.patchActive(row.id, true);
    this.api.updateWorker(row.id, { active: true }).subscribe({
      error: (error: unknown) => {
        this.patchActive(row.id, false);
        this.band.fail(apiErrorOf(error)?.message ?? this.t('band.unreachable'));
      },
    });
  }

  private patchActive(id: string, active: boolean): void {
    this.staff.update((rows) => rows.map((one) => (one.id === id ? { ...one, active } : one)));
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}
