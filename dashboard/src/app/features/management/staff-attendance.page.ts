/* hq-flag: none (shell) — gated by `management.staff.attendance`, the key RM5 puts on both the
   read and the write. No flag, deliberately, and for the server's own reason: taking the staff
   register is not an optional feature of a school and `FlagKeys` has no key for it, which is why
   `ManagementPeopleController` sits in `FeatureFlagCoverageTest.INFRASTRUCTURE`. */
import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { ManagementPeopleApi } from '../../api';
import { StaffAreaService } from '../../core/auth/staff-area';
import { activeLang } from '../../core/i18n/active-lang';
import { PlatformService } from '../../core/platform/platform.service';
import {
  type Tab,
  BandComponent,
  ButtonComponent,
  CardComponent,
  DialogComponent,
  InputComponent,
  PageComponent,
  SkeletonComponent,
  TabsComponent,
} from '../../ui';
import { CoordinatorReadFailedComponent } from '../coordinator/read-failed.component';
import {
  type RosterRow,
  type StaffStatus,
  NOTE_MAX_LENGTH,
  STAFF_STATUSES,
  changedMarks,
  notEditableReason,
  rosterOf,
} from './staff-attendance.models';

/**
 * Staff attendance (RM3a, RM5 — the **one** thing a manager writes).
 *
 * Two views of the same register. **Day** is the roster of her department for one day, each
 * person with four status chips and a note; Save sends only what she changed and the server
 * answers the whole roster back, which is what the screen then holds. **Month** is each person's
 * four counts and a rate, and a name there opens that person's marked days in a drawer.
 *
 * **When it may not be taken.** The rule is the server's — a teaching day of this school, not
 * after today in the school's own zone — and the roster carries `editable`. The chips go
 * disabled and the footer says which of the two refusals it is, because "we do not teach on
 * Fridays" and "that day has not happened yet" are different facts and a Save that is merely
 * grey is a control nobody can act on.
 *
 * **Nobody is present by default.** A person nobody has marked has no status at all, not
 * `present` — the same rule as a child with no register, and for the same reason: a screen that
 * invents a mark is a screen whose numbers are its own.
 */
@Component({
  selector: 'hq-staff-attendance-page',
  imports: [
    BandComponent,
    ButtonComponent,
    CardComponent,
    CoordinatorReadFailedComponent,
    DialogComponent,
    InputComponent,
    PageComponent,
    SkeletonComponent,
    TabsComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-page [title]="'nav.staffAttendance' | transloco" [subtitle]="'management.staff.subtitle' | transloco">
      <hq-tabs [tabs]="tabs()" [(selected)]="view" [label]="'management.staff.viewLabel' | transloco" />

      @if (view() === 'day') {
        <hq-input
          class="st-day-input"
          type="date"
          [label]="'management.staff.day' | transloco"
          [value]="day()"
          (valueChange)="day.set($event)"
        />

        @if (roster.isLoading()) {
          <hq-skeleton [loading]="true" [lines]="6" [label]="'attendance.loading' | transloco" />
        } @else if (roster.error()) {
          <hq-coordinator-read-failed (retry)="roster.reload()" />
        } @else {
          @if (reason(); as why) {
            <hq-band
              class="st-notice-band"
              variant="notice"
              [open]="true"
              [dismissible]="false"
              [title]="'management.staff.closed.' + why | transloco"
            >
              {{ 'management.staff.closedBody' | transloco }}
            </hq-band>
          }

          <ul class="st-roster">
            @for (row of rows(); track row.userId) {
              <li class="st-roster__row">
                <span class="st-roster__who">
                  <span class="st-roster__name">{{ row.name }}</span>
                  <span class="hq-muted">{{ 'role.' + row.role | transloco }}</span>
                </span>
                <span
                  class="st-chips"
                  role="radiogroup"
                  [attr.aria-label]="'management.staff.statusFor' | transloco: { name: row.name }"
                >
                  @for (status of statuses; track status) {
                    <button
                      type="button"
                      class="st-chip"
                      role="radio"
                      [class.is-on]="row.status === status"
                      [attr.aria-checked]="row.status === status"
                      [disabled]="!editable()"
                      (click)="mark(row, status)"
                    >
                      {{ 'management.staff.status.' + status | transloco }}
                    </button>
                  }
                </span>
                <hq-input
                  class="st-roster__note"
                  [label]="'management.staff.noteFor' | transloco: { name: row.name }"
                  [hideLabel]="true"
                  [maxLength]="noteMaxLength"
                  [placeholder]="'management.staff.note' | transloco"
                  [disabled]="!editable()"
                  [value]="row.note"
                  (valueChange)="note(row, $event)"
                />
              </li>
            }
          </ul>

          @if (rows().length === 0) {
            <p class="hq-muted">{{ 'management.staff.empty' | transloco }}</p>
          }
        }
      } @else {
        <hq-input
          type="month"
          [label]="'management.staff.month' | transloco"
          [value]="month()"
          (valueChange)="month.set($event)"
        />

        @if (summary.isLoading()) {
          <hq-skeleton [loading]="true" [lines]="6" [label]="'attendance.loading' | transloco" />
        } @else if (summary.error()) {
          <hq-coordinator-read-failed (retry)="summary.reload()" />
        } @else {
          <hq-card [title]="'management.staff.summaryTitle' | transloco">
            <ul class="st-roster">
              @for (person of summary.value()?.people ?? []; track person.userId) {
                <li class="st-roster__row">
                  <button type="button" class="hq-linkbutton" (click)="openHistory(person.userId ?? '')">
                    {{ person.displayName }}
                  </button>
                  <span class="hq-muted">
                    {{
                      'management.staff.counts'
                        | transloco
                          : {
                              present: person.present ?? 0,
                              late: person.late ?? 0,
                              absent: person.absent ?? 0,
                              leave: person.leave ?? 0,
                            }
                    }}
                  </span>
                  <span class="st-rate">{{ rate(person.rate) }}</span>
                </li>
              }
            </ul>
          </hq-card>
        }
      }

      <div page-footer class="st-footer">
        @if (view() === 'day') {
          <hq-button
            [disabled]="!editable() || changed().length === 0 || saving()"
            [loading]="saving()"
            (pressed)="save()"
          >
            {{ 'ui.save' | transloco }}
          </hq-button>
        }
      </div>

      <hq-dialog
        [(open)]="historyOpen"
        [title]="history.value().displayName ?? ('management.staff.history' | transloco)"
      >
        @if (history.isLoading()) {
          <hq-skeleton [loading]="true" [lines]="4" [label]="'attendance.loading' | transloco" />
        } @else if ((history.value().marks ?? []).length === 0) {
          <p class="hq-muted">{{ 'management.staff.noMarks' | transloco }}</p>
        } @else {
          <ul class="st-roster">
            @for (mark of history.value().marks ?? []; track mark.day) {
              <li class="st-roster__row">
                <span>{{ mark.day }}</span>
                <span>{{ 'management.staff.status.' + mark.status | transloco }}</span>
                <span class="hq-muted">{{ mark.note }}</span>
              </li>
            }
          </ul>
        }
      </hq-dialog>
    </hq-page>
  `,
  styles: `
    .st-roster {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-8);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .st-roster__row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--hq-space-12);
      min-block-size: var(--hq-size-row);
      border-block-end: var(--hq-size-rule-thin) solid var(--hq-color-divider);
      margin-block-end: 20px;
    }

    .st-day-input {
      display: block;
      margin-block: 15px;
    }

    .st-notice-band,
    hq-band {
      display: block;
      margin-block: 20px;
    }

    .st-roster__who {
      display: flex;
      flex-direction: column;
      inline-size: 14rem;
    }

    .st-roster__name {
      font-weight: var(--hq-text-weight-medium);
    }

    .st-roster__note {
      flex: 1 1 12rem;
    }

    .st-chips {
      display: flex;
      gap: var(--hq-space-4);
    }

    .st-chip {
      min-block-size: var(--hq-size-touch-target);
      padding-inline: var(--hq-space-12);
      border: var(--hq-size-rule) solid var(--hq-color-ink);
      background: var(--hq-color-surface);
      color: var(--hq-color-ink);
      font: inherit;
      cursor: pointer;

      &.is-on {
        background: var(--hq-color-ink);
        color: var(--hq-color-surface);
      }

      &:disabled {
        cursor: not-allowed;
        opacity: var(--hq-opacity-disabled);
      }
    }

    .st-rate {
      margin-inline-start: auto;
      font-weight: var(--hq-text-weight-medium);
    }

    .st-footer {
      display: flex;
      align-items: center;
      justify-content: center;
      inline-size: 100%;
    }

    :host ::ng-deep {
      .page__footer {
        background: transparent !important;
        border-block-start: none !important;
        justify-content: center !important;
      }

      .st-day-input .field__label {
        margin-block: 15px;
      }

      .tabs--underline {
        border-block-end: none;
        gap: var(--hq-space-8);
      }

      .tabs--underline .tabs__tab {
        padding: 8px 16px;
        border-radius: var(--hq-radius-control, 10px);
        border-block-end: none;
        margin-block-end: 0;
        min-block-size: auto;
        transition: all 0.15s ease;

        &[aria-selected='true'] {
          background: var(--hq-gradient-brand-fill);
          color: #ffffff !important;
          box-shadow: 0 4px 12px -2px color-mix(in srgb, var(--hq-color-brand-500) 35%, transparent);

          .tabs__badge {
            background: rgba(255, 255, 255, 0.25);
            color: #ffffff;
          }
        }
      }
    }
  `,
})
export class StaffAttendancePage {
  private readonly api = inject(ManagementPeopleApi);
  private readonly staff = inject(StaffAreaService);
  private readonly platform = inject(PlatformService);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  protected readonly statuses = STAFF_STATUSES;
  /** `staff_attendance.note` is a 500-character column; the field refuses rather than the server. */
  protected readonly noteMaxLength = NOTE_MAX_LENGTH;

  protected readonly view = signal<'day' | 'month'>('day');
  protected readonly saving = signal(false);
  protected readonly historyOpen = signal(false);
  private readonly personId = signal('');

  /** Today where the school is: the register belongs to the morning it is taken on (RM5). */
  private readonly schoolToday = computed(() =>
    new Intl.DateTimeFormat('en-CA', { timeZone: this.platform.timezone() }).format(new Date()),
  );
  protected readonly day = signal(this.schoolToday());
  protected readonly month = signal(this.schoolToday().slice(0, 7));

  protected readonly roster = rxResource({
    params: () => (this.staff.area() === 'management' && this.day() !== '' ? this.day() : undefined),
    stream: ({ params: day }) => this.api.staffAttendance(day),
  });

  protected readonly summary = rxResource({
    params: () =>
      this.staff.area() === 'management' && this.view() === 'month' && this.month() !== ''
        ? this.month()
        : undefined,
    stream: ({ params: month }) => this.api.staffAttendanceSummary(month),
  });

  protected readonly history = rxResource({
    params: () => (this.personId() === '' ? undefined : this.personId()),
    stream: ({ params: userId }) => this.api.staffAttendanceHistory(userId),
    defaultValue: {},
  });

  /**
   * The roster as she has it on screen: the server's answer, plus whatever she has typed since.
   *
   * A plain signal seeded by an effect rather than a `linkedSignal` over the resource, because
   * the two directions are genuinely different — a new day (or a Save's answer) replaces her
   * edits, and a chip click must not be pulled back by the next recompute.
   */
  private readonly edited = signal<readonly RosterRow[]>([]);
  private readonly original = computed(() => rosterOf(this.roster.value()));

  protected readonly rows = computed(() => this.edited());
  protected readonly changed = computed(() => changedMarks(this.original(), this.edited()));
  protected readonly editable = computed(() => this.roster.value()?.editable === true);
  protected readonly reason = computed(() => notEditableReason(this.roster.value()));

  constructor() {
    effect(() => this.edited.set(this.original()));
  }

  protected readonly tabs = computed<readonly Tab<'day' | 'month'>[]>(() => {
    this.lang();
    return [
      { id: 'day', label: this.t('management.staff.dayTab') },
      { id: 'month', label: this.t('management.staff.monthTab') },
    ];
  });

  protected readonly footerLine = computed(() => {
    this.lang();
    const count = this.changed().length;
    return count === 0
      ? this.t('management.staff.nothingToSave')
      : this.transloco.translate<string>('management.staff.pending', { count });
  });

  protected mark(row: RosterRow, status: StaffStatus): void {
    this.edited.update((rows) => rows.map((one) => (one.userId === row.userId ? { ...one, status } : one)));
  }

  protected note(row: RosterRow, note: string): void {
    this.edited.update((rows) => rows.map((one) => (one.userId === row.userId ? { ...one, note } : one)));
  }

  /**
   * Save, and let the server's answer be what the screen then holds.
   *
   * `PUT /management/staff-attendance` upserts what it is given and replies with the **whole**
   * roster, marks and all, so nothing is applied optimistically: a refusal (a person of the
   * other department, a day that closed while the screen was open) leaves the register exactly
   * as it was, with the error interceptor's red band over it rather than a row that silently
   * disagrees with the database.
   */
  protected save(): void {
    const marks = this.changed();
    if (marks.length === 0) return;
    this.saving.set(true);
    this.api.markStaffAttendance([...marks], this.day()).subscribe({
      next: (day) => {
        this.saving.set(false);
        this.edited.set(rosterOf(day));
        this.roster.set(day);
      },
      error: () => this.saving.set(false),
    });
  }

  protected openHistory(userId: string): void {
    if (userId === '') return;
    this.personId.set(userId);
    this.historyOpen.set(true);
  }

  /** A person nobody marked all month has no rate — a dash, not a zero and not a 100. */
  protected rate(value: number | undefined): string {
    return value === undefined || value === null ? '—' : `${value}%`;
  }

  private t(key: string): string {
    return this.transloco.translate<string>(key);
  }
}
