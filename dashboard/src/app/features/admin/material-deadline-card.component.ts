import { ChangeDetectionStrategy, Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { type MaterialSettings, TeachingMaterialSettingsApi, apiErrorOf } from '../../api';
import { FLAGS, FlagService } from '../../core/flags/flag.service';
import { activeLang } from '../../core/i18n/active-lang';
import { PermissionService } from '../../core/permissions/permission.service';
import {
  type SelectOption,
  BandComponent,
  ButtonComponent,
  CardComponent,
  InputComponent,
  SelectComponent,
  SkeletonComponent,
} from '../../ui';
import { WEEKDAYS } from './attendance-settings.models';

/** The three fields, as the form holds them while she types. */
interface DeadlineDraft {
  readonly dueWeekday: string;
  readonly dueTime: string;
  readonly remindHoursBefore: string;
}

const REMIND_MIN = 1;
const REMIND_MAX = 72;

/**
 * **Teaching material deadline** (feature 31) — the weekday and time each week's teaching material
 * is due, and how long before it a teacher is reminded.
 *
 * A card of the Attendance settings page rather than a page of its own: it is one rule of three
 * fields, read and written under the attendance settings' own two permissions
 * (`attendance.settings.read` / `.write`), and the Admin decides it in the same sitting as the
 * hours her staff keep.
 *
 * **Its own `PUT`, like feature 23's card.** The page's Save is one document
 * (`PUT /admin/attendance-settings`); this rule is `PUT /admin/material-settings`, so it saves
 * itself and says so, rather than riding on a button five cards below it.
 *
 * **The deadline falls in the week before the week it is for.** A teacher prepares the coming week
 * while the current one runs, and the hint on the card says so — otherwise "Thursday 12:00" reads
 * as the Thursday of the week being taught.
 *
 * **Behind `teachingMaterials`.** With the switch off the routes answer 404 and there is no older
 * card to fall back to, so the whole card is absent. A manager holds the read and not the write:
 * every field is then disabled and Save is gone.
 */
@Component({
  selector: 'hq-material-deadline-card',
  imports: [
    BandComponent,
    ButtonComponent,
    CardComponent,
    InputComponent,
    SelectComponent,
    SkeletonComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (enabled()) {
      <hq-card
        [title]="'admin.attendanceSettings.materialDeadline.title' | transloco"
        data-hq-material-deadline
      >
        <p class="hq-muted">{{ 'admin.attendanceSettings.materialDeadline.hint' | transloco }}</p>

        @if (settings.isLoading() && !settings.hasValue()) {
          <hq-skeleton
            [loading]="true"
            [lines]="2"
            [label]="'admin.attendanceSettings.materialDeadline.loading' | transloco"
          />
        } @else {
          @if (failure(); as message) {
            <hq-band
              variant="error"
              [open]="true"
              [title]="'band.failed' | transloco"
              (dismissed)="failure.set(null)"
            >
              {{ message }}
            </hq-band>
          }

          <div class="deadline">
            <hq-select
              [label]="'admin.attendanceSettings.materialDeadline.weekday' | transloco"
              [options]="weekdayOptions()"
              [value]="draft().dueWeekday"
              [disabled]="!canWrite()"
              (valueChange)="patch({ dueWeekday: $event })"
            />
            <hq-input
              type="time"
              [label]="'admin.attendanceSettings.materialDeadline.time' | transloco"
              [value]="draft().dueTime"
              [disabled]="!canWrite()"
              (valueChange)="patch({ dueTime: $event })"
            />
            <hq-input
              type="number"
              [label]="'admin.attendanceSettings.materialDeadline.remind' | transloco"
              [hint]="'admin.attendanceSettings.materialDeadline.remindHint' | transloco"
              [min]="remindMin"
              [max]="remindMax"
              [error]="remindError()"
              [value]="draft().remindHoursBefore"
              [disabled]="!canWrite()"
              (valueChange)="patch({ remindHoursBefore: $event })"
            />
          </div>

          <p class="hq-muted" data-hq-material-zone>
            {{
              'admin.attendanceSettings.materialDeadline.zone'
                | transloco: { zone: settings.value().timeZone ?? '' }
            }}
          </p>

          @if (canWrite()) {
            <div class="deadline__actions">
              <hq-button
                variant="primary"
                data-hq-save-material-deadline
                [disabled]="!dirty() || !valid() || saving()"
                [loading]="saving()"
                (pressed)="save()"
              >
                {{ 'ui.save' | transloco }}
              </hq-button>
              @if (saved()) {
                <span class="hq-muted" role="status">
                  {{ 'admin.attendanceSettings.saved' | transloco }}
                </span>
              }
            </div>
          }
        }
      </hq-card>
    }
  `,
  styles: `
    :host {
      display: block;
    }

    .deadline {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-start;
      gap: var(--hq-space-12);

      > * {
        flex: 1 1 10rem;
      }
    }

    .deadline__actions {
      display: flex;
      align-items: center;
      gap: var(--hq-space-12);
      margin-block-start: var(--hq-space-16);
    }
  `,
})
export class MaterialDeadlineCardComponent {
  private readonly api = inject(TeachingMaterialSettingsApi);
  private readonly transloco = inject(TranslocoService);
  private readonly permissions = inject(PermissionService);
  private readonly flags = inject(FlagService);
  private readonly lang = activeLang();

  protected readonly remindMin = String(REMIND_MIN);
  protected readonly remindMax = String(REMIND_MAX);

  protected readonly enabled = computed(() => this.flags.isOn(FLAGS.teachingMaterials));
  protected readonly canWrite = computed(() => this.permissions.can('attendance.settings.write'));

  protected readonly settings = rxResource<MaterialSettings, boolean>({
    params: () => this.enabled(),
    stream: () => this.api.materialSettings(),
    defaultValue: {},
  });

  protected readonly draft = linkedSignal<MaterialSettings, DeadlineDraft>({
    source: () => this.settings.value(),
    computation: (settings) => ({
      dueWeekday: settings.dueWeekday ?? 'THURSDAY',
      dueTime: settings.dueTime ?? '12:00',
      remindHoursBefore: String(settings.remindHoursBefore ?? 4),
    }),
  });

  protected readonly saving = signal(false);
  protected readonly saved = signal(false);
  protected readonly failure = signal<string | null>(null);

  protected readonly weekdayOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return WEEKDAYS.map((day) => ({
      value: day,
      label: this.transloco.translate<string>(`admin.attendanceSettings.day.${day}`),
    }));
  });

  private readonly hours = computed(() => Number.parseInt(this.draft().remindHoursBefore, 10));

  protected readonly remindError = computed(() => {
    this.lang();
    const hours = this.hours();
    if (Number.isInteger(hours) && hours >= REMIND_MIN && hours <= REMIND_MAX) return null;
    return this.transloco.translate<string>('admin.attendanceSettings.materialDeadline.remindError', {
      min: REMIND_MIN,
      max: REMIND_MAX,
    });
  });

  protected readonly valid = computed(
    () => this.remindError() === null && /^\d{2}:\d{2}$/.test(this.draft().dueTime),
  );

  protected readonly dirty = computed(() => {
    const settings = this.settings.value();
    const draft = this.draft();
    return (
      draft.dueWeekday !== (settings.dueWeekday ?? 'THURSDAY') ||
      draft.dueTime !== (settings.dueTime ?? '12:00') ||
      draft.remindHoursBefore !== String(settings.remindHoursBefore ?? 4)
    );
  });

  protected patch(change: Partial<DeadlineDraft>): void {
    this.draft.update((draft) => ({ ...draft, ...change }));
    this.saved.set(false);
  }

  protected save(): void {
    if (!this.valid() || this.saving()) return;
    const draft = this.draft();
    this.saving.set(true);
    this.failure.set(null);
    this.api
      .updateMaterialSettings({
        dueWeekday: draft.dueWeekday,
        dueTime: draft.dueTime,
        remindHoursBefore: this.hours(),
      })
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.saved.set(true);
          this.settings.reload();
        },
        error: (error: unknown) => {
          this.saving.set(false);
          this.failure.set(
            apiErrorOf(error)?.message ?? this.transloco.translate<string>('band.unreachable'),
          );
        },
      });
  }
}
