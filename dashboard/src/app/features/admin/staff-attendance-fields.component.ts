import { ChangeDetectionStrategy, Component, computed, inject, input, linkedSignal, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { type Observable, map, of, switchMap } from 'rxjs';
import { type ApprovedDevice, type StaffAttendanceProfile, StaffAttendanceSettingsApi, apiErrorOf } from '../../api';
import { BandService } from '../../core/band/band.service';
import { activeLang } from '../../core/i18n/active-lang';
import { CanDirective } from '../../core/permissions/can.directive';
import { BandComponent, ButtonComponent, type SelectOption, SelectComponent } from '../../ui';
import { CATEGORIES, type Category } from './attendance-settings.models';

/**
 * Feature 14's two fields on an employee's edit form — **Direct manager** and **Category** — shared
 * by the Teachers, Coordinators, Managers and Workers dialogs.
 *
 * The page places it inside `*hqFeature` (`staffAttendance`) and in edit mode only, and calls
 * {@link commit} once its own save has succeeded: the profile is a row of its own on the server
 * (`PUT /admin/staff/attendance-profiles`), so the two writes stay two, and the second is skipped
 * when neither field moved.
 *
 * Feature 15 adds the **approved device** under them: the phone the employee's first successful
 * check-in bound, its first-seen date, and the Admin's **Reset** (`attendance.device.reset`) so the
 * next successful check-in binds a new phone. The reset is immediate, not part of the form's save.
 */
@Component({
  selector: 'hq-staff-attendance-fields',
  imports: [BandComponent, ButtonComponent, CanDirective, SelectComponent, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <fieldset class="fields" data-hq-attendance-fields>
      <legend>{{ 'admin.attendanceFields.title' | transloco }}</legend>
      <hq-select
        [label]="'admin.attendanceFields.directManager' | transloco"
        [placeholder]="'admin.attendanceFields.fallback' | transloco"
        [hint]="'admin.attendanceFields.hint' | transloco"
        [options]="managerOptions()"
        [value]="manager()"
        [disabled]="profiles.isLoading()"
        (valueChange)="manager.set($event)"
      />
      <hq-select
        [label]="'admin.attendanceFields.category' | transloco"
        [placeholder]="'admin.attendanceFields.byRole' | transloco"
        [options]="categoryOptions()"
        [value]="category()"
        [disabled]="profiles.isLoading()"
        (valueChange)="setCategory($event)"
      />
      <div class="device" data-hq-approved-device>
        <span class="device__label">{{ 'admin.attendanceFields.device.title' | transloco }}</span>
        <span class="device__value">
          @if (firstSeen(); as seen) {
            {{ 'admin.attendanceFields.device.firstSeen' | transloco: { date: seen } }}
          } @else {
            {{ 'admin.attendanceFields.device.none' | transloco }}
          }
        </span>
        @if (firstSeen()) {
          <hq-button
            *hqCan="'attendance.device.reset'"
            variant="secondary"
            [loading]="resetting()"
            (pressed)="confirming.set(true)"
          >
            {{ 'admin.attendanceFields.device.reset' | transloco }}
          </hq-button>
        }
      </div>
      @if (confirming()) {
        <hq-band
          variant="confirm"
          [open]="true"
          [title]="'admin.attendanceFields.device.confirmTitle' | transloco"
          [confirmLabel]="'admin.attendanceFields.device.reset' | transloco"
          (confirmed)="confirmReset()"
          (dismissed)="confirming.set(false)"
        >
          {{ 'admin.attendanceFields.device.confirmMessage' | transloco }}
        </hq-band>
      }
    </fieldset>
  `,
  styles: `
    .fields {
      border: 0;
      padding: 0;
      margin: 0;
      display: grid;
      gap: var(--hq-space-12);
    }

    legend {
      font-weight: 600;
      color: var(--hq-color-ink-strong);
      margin-block-end: var(--hq-space-8);
    }

    .device {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--hq-space-8) var(--hq-space-12);
    }

    .device__label {
      font-weight: 600;
      color: var(--hq-color-ink-strong);
    }

    .device__value {
      flex: 1;
      color: var(--hq-color-ink-soft);
    }
  `,
})
export class StaffAttendanceFieldsComponent {
  private readonly api = inject(StaffAttendanceSettingsApi);
  private readonly transloco = inject(TranslocoService);
  private readonly band = inject(BandService);
  private readonly lang = activeLang();

  /** `USER` for a teacher, coordinator or manager (`users.id`); `WORKER` for a worker. */
  readonly kind = input.required<'USER' | 'WORKER'>();
  readonly staffId = input.required<string>();

  protected readonly profiles = rxResource<readonly StaffAttendanceProfile[], string>({
    params: () => this.staffId(),
    stream: () => this.api.staffAttendanceProfiles(),
    defaultValue: [],
  });

  protected readonly device = rxResource<ApprovedDevice | null, { kind: string; id: string }>({
    params: () => ({ kind: this.kind(), id: this.staffId() }),
    stream: ({ params }) => this.api.approvedDevice(params.kind, params.id),
    defaultValue: null,
  });

  /** The bound phone's first-seen date in the active language, or `null` when none is bound. */
  protected readonly firstSeen = computed(() => {
    const lang = this.lang();
    const device = this.device.value();
    if (!device?.installId || !device.firstSeen) return null;
    return new Intl.DateTimeFormat(lang, { dateStyle: 'medium' }).format(new Date(device.firstSeen));
  });

  protected readonly confirming = signal(false);
  protected readonly resetting = signal(false);

  protected confirmReset(): void {
    this.confirming.set(false);
    this.resetting.set(true);
    this.api.resetApprovedDevice(this.kind(), this.staffId()).subscribe({
      next: (device) => {
        this.resetting.set(false);
        this.device.set(device);
      },
      error: (error: unknown) => {
        this.resetting.set(false);
        this.band.fail(apiErrorOf(error)?.message ?? this.transloco.translate<string>('band.unreachable'));
      },
    });
  }

  private readonly mine = computed(
    () => this.profiles.value().find((p) => p.kind === this.kind() && p.id === this.staffId()) ?? null,
  );

  protected readonly manager = linkedSignal(() => this.mine()?.directManagerId ?? '');
  protected readonly category = linkedSignal<Category | ''>(() => {
    const mine = this.mine();
    return mine?.categorySet ? ((mine.category ?? '') as Category | '') : '';
  });

  protected readonly managerOptions = computed<readonly SelectOption[]>(() =>
    this.profiles
      .value()
      .filter((p) => p.kind === 'USER' && !(this.kind() === 'USER' && p.id === this.staffId()))
      .map((p) => ({ value: p.id ?? '', label: p.fullName ?? '' })),
  );

  protected readonly categoryOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return CATEGORIES.map((value) => ({
      value,
      label: this.transloco.translate<string>(`admin.attendanceSettings.category.${value}`),
    }));
  });

  protected setCategory(value: string): void {
    this.category.set((CATEGORIES as readonly string[]).includes(value) ? (value as Category) : '');
  }

  /** The write for what changed, or `null` when nothing did (or the profile never loaded). */
  commit(): Observable<unknown> | null {
    if (this.profiles.isLoading() || this.profiles.error()) return null;
    const mine = this.mine();
    const savedManager = mine?.directManagerId ?? '';
    const savedCategory = mine?.categorySet ? (mine.category ?? '') : '';
    const manager = this.manager();
    const category = this.category();
    if (manager === savedManager && category === savedCategory) return null;
    return this.api.updateStaffAttendanceProfiles({
      staff: [{ kind: this.kind(), id: this.staffId() }],
      ...(manager === savedManager
        ? {}
        : manager
          ? { directManagerId: manager }
          : { clearDirectManager: true }),
      ...(category === savedCategory ? {} : category ? { category } : { clearCategory: true }),
    });
  }
}

/**
 * An edit form's own save, then the attendance fields' write when they changed — so the dialog
 * closes once, after both, and a failure of either reaches the same error handler.
 */
export function thenAttendance<T>(
  save: Observable<T>,
  fields: StaffAttendanceFieldsComponent | undefined,
): Observable<T> {
  return save.pipe(
    switchMap((result) => {
      const extra = fields?.commit();
      return extra ? extra.pipe(map(() => result)) : of(result);
    }),
  );
}
