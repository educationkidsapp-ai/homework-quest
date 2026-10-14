import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { map } from 'rxjs';
import { AttendanceRequestsApi, type RemoteDay, apiErrorOf } from '../../api';
import { BandService } from '../../core/band/band.service';
import { activeLang } from '../../core/i18n/active-lang';
import { PermissionService } from '../../core/permissions/permission.service';
import {
  type SelectOption,
  type TableColumn,
  ButtonComponent,
  CardComponent,
  EmptyStateComponent,
  InputComponent,
  SelectComponent,
  SkeletonComponent,
  TableComponent,
} from '../../ui';
import { CURRICULA } from '../lessons/lessons.models';

/** One announced remote day, as the card's table shows it. */
interface RemoteDayRow {
  readonly id: string;
  readonly date: string;
  readonly academy: string;
  readonly reason: string;
}

/**
 * **Remote days** (feature 18) — the days the school declares remote, for everyone or for one
 * academy. On such a day the check-in skips the school's pin and the record is marked `remote`.
 *
 * A card of the Attendance settings page and not a part of its form: the page's Save is one PUT
 * of the whole settings document, while a remote day is its own row with its own POST and DELETE
 * (`/admin/attendance/remote-days`) — announcing one has to reach the people in scope now, not
 * when the Admin happens to press Save at the bottom of five other cards.
 *
 * `attendance.settings.write` is the Admin's. A MANAGERIAL account holds the read, so she sees
 * the list and neither the form nor the delete buttons.
 */
@Component({
  selector: 'hq-remote-days-card',
  imports: [
    CardComponent,
    InputComponent,
    SelectComponent,
    ButtonComponent,
    TableComponent,
    EmptyStateComponent,
    SkeletonComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-card [title]="'admin.attendanceSettings.remoteDays.title' | transloco" data-hq-remote-days>
      <p class="hq-muted">{{ 'admin.attendanceSettings.remoteDays.hint' | transloco }}</p>

      @if (days.isLoading()) {
        <hq-skeleton
          [loading]="true"
          [lines]="3"
          [label]="'admin.attendanceSettings.remoteDays.loading' | transloco"
        />
      } @else {
        <hq-table
          [rows]="rows()"
          [columns]="columns()"
          [cellTemplate]="cell"
          [overflowTemplate]="canWrite() ? overflow : null"
          [trackBy]="trackDay"
          [label]="'admin.attendanceSettings.remoteDays.title' | transloco"
        >
          <hq-empty-state
            table-empty
            [message]="'admin.attendanceSettings.remoteDays.empty' | transloco"
          />
        </hq-table>
      }

      @if (canWrite()) {
        <div class="remote-days__form">
          <hq-input
            type="date"
            [label]="'admin.attendanceSettings.remoteDays.date' | transloco"
            [required]="true"
            [value]="date()"
            [disabled]="saving()"
            (valueChange)="date.set($event)"
          />
          <hq-select
            [label]="'admin.attendanceSettings.remoteDays.scope' | transloco"
            [placeholder]="'admin.attendanceSettings.remoteDays.wholeSchool' | transloco"
            [options]="scopeOptions()"
            [value]="academy()"
            [disabled]="saving()"
            (valueChange)="academy.set($event)"
          />
          <hq-input
            [label]="'admin.attendanceSettings.remoteDays.reason' | transloco"
            [required]="true"
            [maxLength]="120"
            [placeholder]="'admin.attendanceSettings.remoteDays.reasonPlaceholder' | transloco"
            [value]="reason()"
            [disabled]="saving()"
            (valueChange)="reason.set($event)"
          />
          <hq-button variant="secondary" [disabled]="!complete()" [loading]="saving()" (pressed)="add()">
            {{ 'admin.attendanceSettings.remoteDays.add' | transloco }}
          </hq-button>
        </div>
      }

      <ng-template #cell let-row let-column="column">
        @switch (column.key) {
          @case ('date') {
            {{ row.date }}
          }
          @case ('academy') {
            {{ scopeLabel(row.academy) }}
          }
          @case ('reason') {
            {{ row.reason }}
          }
        }
      </ng-template>

      <ng-template #overflow let-row>
        <hq-button
          variant="quiet"
          [disabled]="removing() !== null"
          [ariaLabel]="
            'admin.attendanceSettings.remoteDays.removeOne' | transloco: { date: row.date }
          "
          (pressed)="remove(row)"
        >
          {{ 'admin.attendanceSettings.remoteDays.remove' | transloco }}
        </hq-button>
      </ng-template>
    </hq-card>
  `,
  styles: `
    .remote-days__form {
      display: grid;
      gap: var(--hq-space-12);
      grid-template-columns: minmax(9rem, 1fr) minmax(9rem, 1fr) minmax(10rem, 2fr) auto;
      align-items: end;
      margin-block-start: var(--hq-space-16);

      @media (max-width: 48rem) {
        grid-template-columns: minmax(0, 1fr);
      }
    }
  `,
})
export class RemoteDaysCardComponent {
  private readonly api = inject(AttendanceRequestsApi);
  private readonly transloco = inject(TranslocoService);
  private readonly band = inject(BandService);
  private readonly permissions = inject(PermissionService);
  private readonly lang = activeLang();

  protected readonly canWrite = computed(() => this.permissions.can('attendance.settings.write'));

  protected readonly days = rxResource<readonly RemoteDay[], true>({
    params: () => true,
    // The wrapper exists so the contract can grow a field beside the list; the card wants the list.
    stream: () => this.api.remoteDays().pipe(map((list) => list.days ?? [])),
    defaultValue: [],
  });

  protected readonly date = signal('');
  protected readonly academy = signal('');
  protected readonly reason = signal('');
  protected readonly saving = signal(false);
  protected readonly removing = signal<string | null>(null);

  protected readonly complete = computed(
    () => this.date().trim() !== '' && this.reason().trim() !== '' && !this.saving(),
  );

  /** Soonest first: an announcement is read forwards, and the past is only ever a record. */
  protected readonly rows = computed<readonly RemoteDayRow[]>(() =>
    this.days
      .value()
      .map((day) => ({
        id: day.id ?? '',
        date: day.date,
        academy: day.academy ?? '',
        reason: day.reason,
      }))
      .sort((a, b) => a.date.localeCompare(b.date)),
  );

  protected readonly columns = computed<readonly TableColumn<RemoteDayRow>[]>(() => {
    this.lang();
    return [
      { key: 'date', header: this.t('admin.attendanceSettings.remoteDays.date'), width: '20%' },
      { key: 'academy', header: this.t('admin.attendanceSettings.remoteDays.scope'), width: '25%' },
      { key: 'reason', header: this.t('admin.attendanceSettings.remoteDays.reason') },
    ];
  });

  /** Whole school, then the school's curricula — the two scopes the server accepts. */
  protected readonly scopeOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return CURRICULA.map((curriculum) => ({ value: curriculum, label: this.t(`curriculum.${curriculum}`) }));
  });

  protected trackDay = (row: RemoteDayRow): string => row.id;

  protected scopeLabel(academy: string): string {
    this.lang();
    return academy === ''
      ? this.t('admin.attendanceSettings.remoteDays.wholeSchool')
      : this.t(`curriculum.${academy}`);
  }

  protected add(): void {
    if (!this.complete()) return;
    this.saving.set(true);
    const academy = this.academy().trim();
    this.api
      .createRemoteDay({
        date: this.date(),
        reason: this.reason().trim(),
        ...(academy === '' ? {} : { academy }),
      })
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.date.set('');
          this.academy.set('');
          this.reason.set('');
          this.days.reload();
        },
        error: (cause: unknown) => {
          this.saving.set(false);
          this.band.fail(apiErrorOf(cause)?.message ?? this.t('band.unreachable'));
        },
      });
  }

  protected remove(row: RemoteDayRow): void {
    if (this.removing() !== null || row.id === '') return;
    this.removing.set(row.id);
    this.api.deleteRemoteDay(row.id).subscribe({
      next: () => {
        this.removing.set(null);
        this.days.reload();
      },
      error: (cause: unknown) => {
        this.removing.set(null);
        this.band.fail(apiErrorOf(cause)?.message ?? this.t('band.unreachable'));
      },
    });
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}
