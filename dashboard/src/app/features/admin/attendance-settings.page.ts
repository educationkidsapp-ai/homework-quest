/* hq-flag: none (shell) — routed through `areaRoutes`, whose `attendance-settings` rows carry
   `FLAGS.staffAttendance`; the server's controller is behind the same key. */
import { ChangeDetectionStrategy, Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import {
  type AttendanceSettings,
  type StaffAttendanceProfile,
  StaffAttendanceSettingsApi,
  apiErrorOf,
} from '../../api';
import { BandService } from '../../core/band/band.service';
import { activeLang } from '../../core/i18n/active-lang';
import { PermissionService } from '../../core/permissions/permission.service';
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
import {
  CATEGORIES,
  NOTIFY_POLICIES,
  RADIUS_MAX,
  RADIUS_MIN,
  WEEKDAYS,
  type Category,
  type HolidayDraft,
  type ScheduleDraft,
  type SettingsDraft,
  draftOf,
  errorsOf,
  isNotifyPolicy,
  previewRadius,
  requestOf,
} from './attendance-settings.models';
import { MaterialDeadlineCardComponent } from './material-deadline-card.component';
import { RemoteStudyDaysCardComponent } from './remote-study-days-card.component';

/** One employee as the direct-managers table shows her. */
interface StaffRow {
  readonly key: string;
  readonly kind: string;
  readonly id: string;
  readonly fullName: string;
  readonly role: string;
  readonly category: string;
  readonly categorySet: boolean;
  readonly managerName: string | null;
  readonly source: StaffAttendanceProfile;
}

const EMPTY_SETTINGS: AttendanceSettings = {};

/**
 * **Attendance settings** (feature 14, `docs/features/14-attendance-settings.md`) — where and
 * when the school's staff must be present, and who hears about each of them.
 *
 * One page, six cards in the order the Admin decides them: the school pin and its radius, the
 * hours of each staff category (and Ramadan's), the holidays, the lateness rules, who is told
 * and when, and every employee's direct manager. The time zone is shown, not edited: it is the
 * school's own (`schools.timezone`), the one every date on the dashboard is already read in.
 *
 * **No map library.** The spec allows the lighter option and the dashboard has none: latitude and
 * longitude fields, "Use my current location" (read once, when she taps), and a drawn circle
 * whose size follows the radius, with a link that opens the pin in OpenStreetMap to check it.
 *
 * **A manager reads it.** `attendance.settings.write` is the Admin's; without it every field is
 * disabled, Save is gone, and the direct-managers table has no checkboxes or edit buttons.
 */
@Component({
  selector: 'hq-attendance-settings-page',
  imports: [
    PageComponent,
    CardComponent,
    InputComponent,
    SelectComponent,
    CheckboxComponent,
    ButtonComponent,
    BandComponent,
    DialogComponent,
    TableComponent,
    EmptyStateComponent,
    SkeletonComponent,
    RemoteStudyDaysCardComponent,
    MaterialDeadlineCardComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './attendance-settings.page.html',
  styleUrl: './attendance-settings.page.scss',
})
export class AttendanceSettingsPage {
  private readonly api = inject(StaffAttendanceSettingsApi);
  private readonly transloco = inject(TranslocoService);
  private readonly band = inject(BandService);
  private readonly permissions = inject(PermissionService);
  private readonly lang = activeLang();

  protected readonly categories = CATEGORIES;
  protected readonly weekdays = WEEKDAYS;
  protected readonly radiusMin = RADIUS_MIN;
  protected readonly radiusMax = RADIUS_MAX;

  protected readonly canWrite = computed(() => this.permissions.can('attendance.settings.write'));

  protected readonly settings = rxResource<AttendanceSettings, true>({
    params: () => true,
    stream: () => this.api.attendanceSettings(),
    defaultValue: EMPTY_SETTINGS,
  });

  /** The form, reset from the server whenever the server's answer changes. */
  protected readonly draft = linkedSignal<AttendanceSettings, SettingsDraft>({
    source: () => this.settings.value(),
    computation: (settings) => draftOf(settings),
  });

  protected readonly errors = computed(() => errorsOf(this.draft()));
  protected readonly valid = computed(() => Object.keys(this.errors()).length === 0);
  protected readonly dirty = computed(
    () =>
      JSON.stringify(requestOf(this.draft())) !== JSON.stringify(requestOf(draftOf(this.settings.value()))),
  );
  protected readonly saving = signal(false);
  protected readonly saved = signal(false);

  protected readonly timeZone = computed(() => this.settings.value().timeZone ?? 'Asia/Riyadh');

  // ---- labels ---------------------------------------------------------------------------------

  protected readonly notifyOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return NOTIFY_POLICIES.map((value) => ({
      value,
      label: this.t(`admin.attendanceSettings.notify.${value}`),
    }));
  });

  protected readonly categoryOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return CATEGORIES.map((value) => ({
      value,
      label: this.t(`admin.attendanceSettings.category.${value}`),
    }));
  });

  protected error(field: string): string | null {
    this.lang();
    const key = this.errors()[field];
    return key ? this.t(`admin.attendanceSettings.error.${key}`, { min: RADIUS_MIN, max: RADIUS_MAX }) : null;
  }

  // ---- edits ----------------------------------------------------------------------------------

  protected set<K extends keyof SettingsDraft>(field: K, value: SettingsDraft[K]): void {
    this.saved.set(false);
    this.draft.update((d) => ({ ...d, [field]: value }));
  }

  protected setNotify(field: 'directorNotify' | 'adminNotify', value: string): void {
    if (isNotifyPolicy(value)) this.set(field, value);
  }

  protected setSchedule(index: number, patch: Partial<ScheduleDraft>): void {
    this.set(
      'schedules',
      this.draft().schedules.map((s, i) => (i === index ? { ...s, ...patch } : s)),
    );
  }

  protected toggleDay(index: number, day: string, on: boolean): void {
    const days = this.draft().schedules[index]?.weekdays ?? [];
    this.setSchedule(index, {
      weekdays: on ? [...days.filter((d) => d !== day), day] : days.filter((d) => d !== day),
    });
  }

  protected addHoliday(): void {
    this.set('holidays', [...this.draft().holidays, { name: '', from: '', to: '' }]);
  }

  protected setHoliday(index: number, patch: Partial<HolidayDraft>): void {
    this.set(
      'holidays',
      this.draft().holidays.map((h, i) => (i === index ? { ...h, ...patch } : h)),
    );
  }

  protected removeHoliday(index: number): void {
    this.set(
      'holidays',
      this.draft().holidays.filter((_, i) => i !== index),
    );
  }

  protected reset(): void {
    this.saved.set(false);
    this.draft.set(draftOf(this.settings.value()));
  }

  protected save(): void {
    if (!this.valid() || this.saving()) return;
    this.saving.set(true);
    this.api.updateAttendanceSettings(requestOf(this.draft())).subscribe({
      next: (settings) => {
        this.saving.set(false);
        this.saved.set(true);
        this.settings.set(settings);
      },
      error: (cause: unknown) => {
        this.saving.set(false);
        this.band.fail(apiErrorOf(cause)?.message ?? this.t('band.unreachable'));
      },
    });
  }

  // ---- the school pin -------------------------------------------------------------------------

  protected readonly locating = signal(false);

  protected readonly hasPin = computed(
    () => this.errors()['location'] === undefined && this.draft().latitude.trim() !== '',
  );

  protected readonly circleRadius = computed(() => previewRadius(Number(this.draft().radius)));

  protected readonly mapLink = computed(() => {
    const { latitude, longitude } = this.draft();
    const lat = latitude.trim();
    const lng = longitude.trim();
    return `https://www.openstreetmap.org/?mlat=${encodeURIComponent(lat)}&mlon=${encodeURIComponent(lng)}#map=17/${encodeURIComponent(lat)}/${encodeURIComponent(lng)}`;
  });

  /** Read once, at the tap — never watched (the spec's privacy rule). */
  protected useMyLocation(): void {
    if (typeof navigator === 'undefined' || !navigator.geolocation) {
      this.band.fail(this.t('admin.attendanceSettings.location.unavailable'));
      return;
    }
    this.locating.set(true);
    navigator.geolocation.getCurrentPosition(
      (position) => {
        this.locating.set(false);
        this.saved.set(false);
        this.draft.update((d) => ({
          ...d,
          latitude: position.coords.latitude.toFixed(6),
          longitude: position.coords.longitude.toFixed(6),
        }));
      },
      () => {
        this.locating.set(false);
        this.band.fail(this.t('admin.attendanceSettings.location.denied'));
      },
      { enableHighAccuracy: true, timeout: 15000, maximumAge: 0 },
    );
  }

  // ---- direct managers ------------------------------------------------------------------------

  protected readonly staff = rxResource<readonly StaffAttendanceProfile[], true>({
    params: () => true,
    stream: () => this.api.staffAttendanceProfiles(),
    defaultValue: [],
  });

  protected readonly staffFilter = signal('');
  protected readonly selected = signal<ReadonlySet<string>>(new Set());

  protected readonly staffRows = computed<readonly StaffRow[]>(() => {
    const query = this.staffFilter().trim().toLowerCase();
    return this.staff
      .value()
      .filter((p) => !query || (p.fullName ?? '').toLowerCase().includes(query))
      .map((p) => ({
        key: `${p.kind}:${p.id}`,
        kind: p.kind ?? '',
        id: p.id ?? '',
        fullName: p.fullName ?? '',
        role: p.role ?? '',
        category: p.category ?? '',
        categorySet: p.categorySet === true,
        managerName: p.directManagerName ?? null,
        source: p,
      }));
  });

  protected readonly staffColumns = computed<readonly TableColumn<StaffRow>[]>(() => {
    this.lang();
    const columns: TableColumn<StaffRow>[] = [
      { key: 'fullName', header: this.t('admin.attendanceSettings.staff.name'), width: '28%' },
      { key: 'role', header: this.t('admin.attendanceSettings.staff.role'), width: '16%' },
      { key: 'category', header: this.t('admin.attendanceSettings.staff.category'), width: '18%' },
      { key: 'manager', header: this.t('admin.attendanceSettings.staff.manager') },
    ];
    return this.canWrite()
      ? [
          { key: 'select', header: this.t('admin.attendanceSettings.staff.select'), width: '3rem' },
          ...columns,
        ]
      : columns;
  });

  protected trackStaff = (row: StaffRow): string => row.key;
  protected isSelected = (row: StaffRow): boolean => this.selected().has(row.key);

  /** The people who may be a direct manager: every employee with an account. */
  protected readonly managerOptions = computed<readonly SelectOption[]>(() =>
    this.staff
      .value()
      .filter((p) => p.kind === 'USER')
      .map((p) => ({ value: p.id ?? '', label: p.fullName ?? '' })),
  );

  protected readonly allSelected = computed(
    () => this.staffRows().length > 0 && this.staffRows().every((row) => this.selected().has(row.key)),
  );

  protected toggleRow(row: StaffRow, on: boolean): void {
    const next = new Set(this.selected());
    if (on) next.add(row.key);
    else next.delete(row.key);
    this.selected.set(next);
  }

  protected toggleAll(on: boolean): void {
    this.selected.set(on ? new Set(this.staffRows().map((row) => row.key)) : new Set());
  }

  protected readonly bulkManager = signal('');
  protected readonly bulkSaving = signal(false);

  /** Several at once: "Set direct manager" for every ticked row. */
  protected applyBulk(clear: boolean): void {
    const refs = this.staff
      .value()
      .filter((p) => this.selected().has(`${p.kind}:${p.id}`))
      .map((p) => ({ kind: p.kind ?? '', id: p.id ?? '' }));
    if (refs.length === 0 || (!clear && this.bulkManager() === '')) return;
    this.bulkSaving.set(true);
    this.api
      .updateStaffAttendanceProfiles(
        clear
          ? { staff: refs, clearDirectManager: true }
          : { staff: refs, directManagerId: this.bulkManager() },
      )
      .subscribe({
        next: () => {
          this.bulkSaving.set(false);
          this.selected.set(new Set());
          this.bulkManager.set('');
          this.staff.reload();
        },
        error: (cause: unknown) => {
          this.bulkSaving.set(false);
          this.band.fail(apiErrorOf(cause)?.message ?? this.t('band.unreachable'));
        },
      });
  }

  // ---- one employee ---------------------------------------------------------------------------

  protected readonly editing = signal<StaffRow | null>(null);
  protected readonly editOpen = signal(false);
  protected readonly editManager = signal('');
  protected readonly editCategory = signal<Category | ''>('');
  protected readonly editSaving = signal(false);

  /** Managers for one employee: never herself. */
  protected readonly editManagerOptions = computed<readonly SelectOption[]>(() => {
    const row = this.editing();
    return this.managerOptions().filter((o) => !(row?.kind === 'USER' && o.value === row.id));
  });

  protected openEdit(row: StaffRow): void {
    this.editing.set(row);
    this.editManager.set(row.source.directManagerId ?? '');
    this.editCategory.set(row.categorySet ? (row.category as Category) : '');
    this.editOpen.set(true);
  }

  protected setEditCategory(value: string): void {
    this.editCategory.set((CATEGORIES as readonly string[]).includes(value) ? (value as Category) : '');
  }

  protected saveEdit(): void {
    const row = this.editing();
    if (!row) return;
    this.editSaving.set(true);
    const manager = this.editManager();
    const category = this.editCategory();
    this.api
      .updateStaffAttendanceProfiles({
        staff: [{ kind: row.kind, id: row.id }],
        ...(manager ? { directManagerId: manager } : { clearDirectManager: true }),
        ...(category ? { category } : { clearCategory: true }),
      })
      .subscribe({
        next: () => {
          this.editSaving.set(false);
          this.editOpen.set(false);
          this.staff.reload();
        },
        error: (cause: unknown) => {
          this.editSaving.set(false);
          this.band.fail(apiErrorOf(cause)?.message ?? this.t('band.unreachable'));
        },
      });
  }

  protected roleLabel(role: string): string {
    this.lang();
    return this.t(`admin.attendanceSettings.role.${role}`);
  }

  protected categoryLabel(category: string): string {
    this.lang();
    return this.t(`admin.attendanceSettings.category.${category}`);
  }

  protected dayLabel(day: string): string {
    this.lang();
    return this.t(`admin.attendanceSettings.day.${day}`);
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}
