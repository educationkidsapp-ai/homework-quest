import { ChangeDetectionStrategy, Component, computed, inject, input, linkedSignal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { type Observable, map, of, switchMap } from 'rxjs';
import { type StaffAttendanceProfile, StaffAttendanceSettingsApi } from '../../api';
import { activeLang } from '../../core/i18n/active-lang';
import { type SelectOption, SelectComponent } from '../../ui';
import { CATEGORIES, type Category } from './attendance-settings.models';

/**
 * Feature 14's two fields on an employee's edit form — **Direct manager** and **Category** — shared
 * by the Teachers, Coordinators, Managers and Workers dialogs.
 *
 * The page places it inside `*hqFeature` (`staffAttendance`) and in edit mode only, and calls
 * {@link commit} once its own save has succeeded: the profile is a row of its own on the server
 * (`PUT /admin/staff/attendance-profiles`), so the two writes stay two, and the second is skipped
 * when neither field moved.
 */
@Component({
  selector: 'hq-staff-attendance-fields',
  imports: [SelectComponent, TranslocoPipe],
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
  `,
})
export class StaffAttendanceFieldsComponent {
  private readonly api = inject(StaffAttendanceSettingsApi);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  /** `USER` for a teacher, coordinator or manager (`users.id`); `WORKER` for a worker. */
  readonly kind = input.required<'USER' | 'WORKER'>();
  readonly staffId = input.required<string>();

  protected readonly profiles = rxResource<readonly StaffAttendanceProfile[], string>({
    params: () => this.staffId(),
    stream: () => this.api.staffAttendanceProfiles(),
    defaultValue: [],
  });

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
