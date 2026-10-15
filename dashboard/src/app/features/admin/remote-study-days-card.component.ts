import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { map } from 'rxjs';
import { ManagementApi, RemoteStudyDaysApi, type RemoteStudyDay, apiErrorOf } from '../../api';
import { BandService } from '../../core/band/band.service';
import { FLAGS, FlagService } from '../../core/flags/flag.service';
import { activeLang } from '../../core/i18n/active-lang';
import { PermissionService } from '../../core/permissions/permission.service';
import {
  type SelectOption,
  type TableColumn,
  BandComponent,
  ButtonComponent,
  CardComponent,
  CheckboxComponent,
  EmptyStateComponent,
  InputComponent,
  SelectComponent,
  SkeletonComponent,
  TableComponent,
} from '../../ui';
import { translateOr } from '../coordinator/coordinator.labels';

/** One declared day, as the card's table shows it. */
interface StudyDayRow {
  readonly id: string;
  readonly date: string;
  readonly scope: string;
  readonly reason: string;
}

/**
 * **Remote study days** (feature 23) — the whole school, one academy, or chosen grades study from
 * home for a day. It replaces feature 18's remote-days card: that card only ever said "the whole
 * school" or "one academy" and told staff alone, while a day declared here also reaches the
 * parents of every class in scope with one bilingual announcement that expires that evening.
 *
 * A card of the Attendance settings page and not a part of its form, for the reason feature 18's
 * card had: the page's Save is one PUT of the whole settings document, while a day is its own row
 * with its own POST and DELETE (`/admin/remote-study-days`) — declaring one has to reach the
 * people in scope now, not when the Admin happens to press Save at the bottom of five other cards.
 *
 * **The academies and the grades are the school's own.** They are read from
 * `GET /management/classes`, which answers a group per academy and grade and which `management.read`
 * opens to the Admin (the same read feature 22's composer uses for the audience). A hard-coded
 * curriculum list would offer a school academies it does not teach, and the server refuses those.
 *
 * `attendance.settings.write` is the Admin's. A MANAGERIAL account holds the read, so she sees the
 * list and neither the form nor the delete. Behind `remoteStudyDays`: with the flag off the whole
 * card is absent, because the routes behind it answer 404 and there is no older card to fall back
 * to.
 */
@Component({
  selector: 'hq-remote-study-days-card',
  imports: [
    BandComponent,
    CardComponent,
    CheckboxComponent,
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
    @if (enabled()) {
      <hq-card
        [title]="'admin.attendanceSettings.remoteStudyDays.title' | transloco"
        data-hq-remote-study-days
      >
        <p class="hq-muted">{{ 'admin.attendanceSettings.remoteStudyDays.hint' | transloco }}</p>

        @if (pending(); as row) {
          <hq-band
            variant="confirm"
            [open]="true"
            [title]="'admin.attendanceSettings.remoteStudyDays.confirmTitle' | transloco"
            [confirmLabel]="'admin.attendanceSettings.remoteStudyDays.remove' | transloco"
            (confirmed)="confirmRemove()"
            (dismissed)="pending.set(null)"
          >
            {{
              'admin.attendanceSettings.remoteStudyDays.confirmBody'
                | transloco: { date: row.date, scope: row.scope }
            }}
          </hq-band>
        }

        @if (days.isLoading()) {
          <hq-skeleton
            [loading]="true"
            [lines]="3"
            [label]="'admin.attendanceSettings.remoteStudyDays.loading' | transloco"
          />
        } @else {
          <hq-table
            [rows]="rows()"
            [columns]="columns()"
            [cellTemplate]="cell"
            [overflowTemplate]="canWrite() ? overflow : null"
            [trackBy]="trackDay"
            [label]="'admin.attendanceSettings.remoteStudyDays.title' | transloco"
          >
            <hq-empty-state
              table-empty
              [message]="'admin.attendanceSettings.remoteStudyDays.empty' | transloco"
            />
          </hq-table>
        }

        @if (canWrite()) {
          <div class="study-days__form">
            <hq-input
              type="date"
              [label]="'admin.attendanceSettings.remoteStudyDays.date' | transloco"
              [required]="true"
              [min]="today"
              [value]="date()"
              [disabled]="saving()"
              (valueChange)="date.set($event)"
            />
            <hq-select
              [label]="'admin.attendanceSettings.remoteStudyDays.academy' | transloco"
              [placeholder]="'admin.attendanceSettings.remoteStudyDays.wholeSchool' | transloco"
              [options]="academyOptions()"
              [value]="academy()"
              [disabled]="saving()"
              (valueChange)="pickAcademy($event)"
            />
            <hq-input
              [label]="'admin.attendanceSettings.remoteStudyDays.reason' | transloco"
              [required]="true"
              [maxLength]="200"
              [placeholder]="'admin.attendanceSettings.remoteStudyDays.reasonPlaceholder' | transloco"
              [value]="reason()"
              [disabled]="saving()"
              (valueChange)="reason.set($event)"
            />
            <hq-button variant="secondary" [disabled]="!complete()" [loading]="saving()" (pressed)="add()">
              {{ 'admin.attendanceSettings.remoteStudyDays.add' | transloco }}
            </hq-button>
          </div>

          <!-- Grades narrow the children and their parents only: a grade does not say who teaches
               it, so staff in scope are the academy's whatever is ticked here. -->
          @if (gradeChoices().length > 0) {
            <fieldset class="study-days__grades">
              <legend>{{ 'admin.attendanceSettings.remoteStudyDays.grades' | transloco }}</legend>
              <p class="hq-muted">
                {{ 'admin.attendanceSettings.remoteStudyDays.gradesHint' | transloco }}
              </p>
              <div class="study-days__chips">
                @for (grade of gradeChoices(); track grade) {
                  <hq-checkbox
                    [label]="'admin.attendanceSettings.remoteStudyDays.gradeChip' | transloco: { grade }"
                    [checked]="grades().includes(grade)"
                    [disabled]="saving()"
                    (checkedChange)="toggleGrade(grade, $event)"
                  />
                }
              </div>
            </fieldset>
          }
        }

        <ng-template #cell let-row let-column="column">
          @switch (column.key) {
            @case ('date') {
              {{ row.date }}
            }
            @case ('scope') {
              {{ row.scope }}
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
            [ariaLabel]="'admin.attendanceSettings.remoteStudyDays.removeOne' | transloco: { date: row.date }"
            (pressed)="askRemove(row)"
          >
            {{ 'admin.attendanceSettings.remoteStudyDays.remove' | transloco }}
          </hq-button>
        </ng-template>
      </hq-card>
    }
  `,
  styles: `
    .study-days__form {
      display: grid;
      gap: var(--hq-space-12);
      grid-template-columns: minmax(9rem, 1fr) minmax(9rem, 1fr) minmax(10rem, 2fr) auto;
      align-items: end;
      margin-block-start: var(--hq-space-16);

      @media (max-width: 48rem) {
        grid-template-columns: minmax(0, 1fr);
      }
    }

    .study-days__grades {
      margin: var(--hq-space-16) 0 0;
      border: 0;
      padding: 0;

      legend {
        padding: 0;
        font-weight: var(--hq-font-label-weight);
      }
    }

    .study-days__chips {
      display: flex;
      flex-wrap: wrap;
      gap: var(--hq-space-12) var(--hq-space-16);
      margin-block-start: var(--hq-space-8);
    }
  `,
})
export class RemoteStudyDaysCardComponent {
  private readonly api = inject(RemoteStudyDaysApi);
  private readonly management = inject(ManagementApi);
  private readonly transloco = inject(TranslocoService);
  private readonly band = inject(BandService);
  private readonly permissions = inject(PermissionService);
  private readonly flagService = inject(FlagService);
  private readonly lang = activeLang();

  /** With the flag off the card is not drawn at all, and nothing behind it is asked for. */
  protected readonly enabled = computed(() => this.flagService.isOn(FLAGS.remoteStudyDays));

  protected readonly canWrite = computed(() => this.permissions.can('attendance.settings.write'));

  /** Today in the browser's zone — the earliest day the date field offers; the server owns the truth. */
  protected readonly today: string = new Date().toISOString().slice(0, 10);

  protected readonly days = rxResource({
    params: () => (this.enabled() ? true : undefined),
    // The wrapper exists so the contract can grow a field beside the list; the card wants the list.
    stream: () => this.api.remoteStudyDays().pipe(map((list): readonly RemoteStudyDay[] => list.days ?? [])),
    defaultValue: [] as readonly RemoteStudyDay[],
  });

  /**
   * The school's own academies and grades, as `GET /management/classes` groups them. Read only for
   * the Admin who may declare a day: a manager sees the list and has no form to fill.
   */
  private readonly groups = rxResource({
    params: () => (this.enabled() && this.canWrite() ? true : undefined),
    stream: () => this.management.managementClasses(),
    defaultValue: [],
  });

  protected readonly date = signal('');
  protected readonly academy = signal('');
  protected readonly grades = signal<readonly number[]>([]);
  protected readonly reason = signal('');
  protected readonly saving = signal(false);
  protected readonly removing = signal<string | null>(null);
  protected readonly pending = signal<StudyDayRow | null>(null);

  protected readonly complete = computed(
    () => this.date().trim() !== '' && this.reason().trim() !== '' && !this.saving(),
  );

  /** Soonest first: a declaration is read forwards, and the server answers today and later. */
  protected readonly rows = computed<readonly StudyDayRow[]>(() => {
    this.lang();
    return this.days
      .value()
      .map((day) => ({
        id: day.id,
        date: day.date,
        scope: this.scopeLabel(day.academy ?? '', day.grades ?? []),
        reason: day.reason,
      }))
      .sort((a, b) => a.date.localeCompare(b.date));
  });

  protected readonly columns = computed<readonly TableColumn<StudyDayRow>[]>(() => {
    this.lang();
    return [
      { key: 'date', header: this.t('admin.attendanceSettings.remoteStudyDays.date'), width: '20%' },
      {
        key: 'scope',
        header: this.t('admin.attendanceSettings.remoteStudyDays.scope'),
        width: '30%',
      },
      { key: 'reason', header: this.t('admin.attendanceSettings.remoteStudyDays.reason') },
    ];
  });

  /** The academies the school really teaches, each named once and in its own language. */
  protected readonly academyOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return [...new Set(this.groups.value().map((group) => group.curriculum ?? ''))]
      .filter((curriculum) => curriculum !== '')
      .sort()
      .map((curriculum) => ({ value: curriculum, label: this.academyLabel(curriculum) }));
  });

  /**
   * The grades on offer: the chosen academy's, or every grade of the school while the day is the
   * whole school's. An academy that teaches grades 1–6 must never be offered a grade 9 the server
   * would refuse.
   */
  protected readonly gradeChoices = computed<readonly number[]>(() => {
    const wanted = this.academy();
    const grades = this.groups
      .value()
      .filter((group) => wanted === '' || (group.curriculum ?? '') === wanted)
      .map((group) => group.grade)
      .filter((grade): grade is number => typeof grade === 'number');
    return [...new Set(grades)].sort((a, b) => a - b);
  });

  protected trackDay = (row: StudyDayRow): string => row.id;

  /** The grades belong to the academy, so a new academy starts from "every grade of it". */
  protected pickAcademy(value: string): void {
    this.academy.set(value);
    this.grades.set([]);
  }

  protected toggleGrade(grade: number, checked: boolean): void {
    const kept = this.grades().filter((value) => value !== grade);
    this.grades.set(checked ? [...kept, grade].sort((a, b) => a - b) : kept);
  }

  /** "Whole school", "British", or "British · Grades 1, 2" — what a row's scope reads as. */
  protected scopeLabel(academy: string, grades: readonly number[]): string {
    const where =
      academy === ''
        ? this.t('admin.attendanceSettings.remoteStudyDays.wholeSchool')
        : this.academyLabel(academy);
    if (grades.length === 0) return where;
    const list = grades
      .map((grade) => String(grade))
      .join(this.t('admin.attendanceSettings.remoteStudyDays.separator'));
    return `${where} · ${this.t('admin.attendanceSettings.remoteStudyDays.gradesValue', { grades: list })}`;
  }

  /** `curriculum.british` when the dashboard knows the academy, and the academy itself when not. */
  private academyLabel(academy: string): string {
    return translateOr(this.transloco, `curriculum.${academy}`, academy);
  }

  protected add(): void {
    if (!this.complete()) return;
    this.saving.set(true);
    const academy = this.academy().trim();
    this.api
      .createRemoteStudyDay({
        date: this.date(),
        reason: this.reason().trim(),
        grades: [...this.grades()],
        ...(academy === '' ? {} : { academy }),
      })
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.date.set('');
          this.academy.set('');
          this.grades.set([]);
          this.reason.set('');
          this.days.reload();
        },
        error: (cause: unknown) => {
          this.saving.set(false);
          this.band.fail(apiErrorOf(cause)?.message ?? this.t('band.unreachable'));
        },
      });
  }

  /**
   * Cancelling a day is destructive — the announcement the parents already read is expired by it —
   * so it is asked in the red band rather than undone afterwards.
   */
  protected askRemove(row: StudyDayRow): void {
    if (this.removing() !== null || row.id === '') return;
    this.pending.set(row);
  }

  protected confirmRemove(): void {
    const row = this.pending();
    if (row === null) return;
    this.pending.set(null);
    this.removing.set(row.id);
    this.api.deleteRemoteStudyDay(row.id).subscribe({
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
