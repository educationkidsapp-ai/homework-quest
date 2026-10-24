import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { type EvaluationRound, EvaluationRoundsApi, apiErrorOf } from '../../api';
import { FLAGS } from '../../core/flags/flag.service';
import { FeatureDirective } from '../../core/flags/feature.directive';
import { silentErrors } from '../../core/http/error.interceptor';
import { activeLang } from '../../core/i18n/active-lang';
import { CanDirective } from '../../core/permissions/can.directive';
import {
  type TableColumn,
  BandComponent,
  ButtonComponent,
  CardComponent,
  DialogComponent,
  EmptyStateComponent,
  InputComponent,
  PageComponent,
  SkeletonComponent,
  TableComponent,
} from '../../ui';
import { type DueParts, dueAtOf, dueParts, roundName } from '../evaluations/evaluations.models';

/** The default the server uses when the Admin names no reminder window (`REMIND_HOURS_DEFAULT`). */
const REMIND_HOURS_DEFAULT = 48;

/**
 * **Evaluation rounds** (feature 33, the Admin's half) — the few times a year the school asks
 * every teacher to evaluate each child she teaches.
 *
 * A round is five fields: a name in **both** languages (the teacher reads one, the parent the
 * other), the date it opens, the deadline, and how many hours before it the teachers who are
 * still missing evaluations are reminded.
 *
 * **The deadline is the school's own clock.** The server stores an instant and reads the Admin's
 * `yyyy-MM-ddTHH:mm` in the school's zone, so the form is a date and a time of day with the zone
 * named beside them, and the round's own `timeZone` is what both are formatted in — never the
 * browser's, which would move a 9 pm Riyadh deadline a day for anybody editing from Europe.
 *
 * **Behind `childEvaluations`.** The row in `core/nav/screens.ts` carries the flag and so does
 * `*hqFeature` over the contents; with the switch off the `/admin/evaluation-rounds` routes
 * answer 404 and there is no older screen behind this one to fall back to.
 *
 * **Release is the one-way door.** Until she releases it a round is hers to edit; releasing it
 * makes this round's **approved** evaluations readable by the parents and tells each parent once
 * per child, so it asks first in a red band and the server answers 409 to a second attempt. An
 * edit after release is 409 too, which is why the table offers none.
 */
@Component({
  selector: 'hq-evaluation-rounds-page',
  imports: [
    BandComponent,
    ButtonComponent,
    CanDirective,
    CardComponent,
    DialogComponent,
    EmptyStateComponent,
    FeatureDirective,
    InputComponent,
    PageComponent,
    SkeletonComponent,
    TableComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './evaluation-rounds.page.html',
  styleUrl: './evaluation-rounds.page.scss',
})
export class EvaluationRoundsPage {
  private readonly api = inject(EvaluationRoundsApi);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  protected readonly flag = FLAGS.childEvaluations;
  protected readonly roundsKey = 'evaluations.rounds';

  protected readonly rounds = rxResource<readonly EvaluationRound[], true>({
    params: () => true,
    stream: () => this.api.evaluationRounds(),
    defaultValue: [],
  });

  protected readonly rows = computed(() => this.rounds.value());

  protected readonly columns = computed<readonly TableColumn<EvaluationRound>[]>(() => {
    this.lang();
    return [
      { key: 'name', header: this.t('admin.rounds.column.name') },
      { key: 'opensOn', header: this.t('admin.rounds.column.opensOn') },
      { key: 'dueAt', header: this.t('admin.rounds.column.dueAt') },
      { key: 'remindHoursBefore', header: this.t('admin.rounds.column.remind') },
      { key: 'released', header: this.t('admin.rounds.column.released') },
      { key: 'act', header: '' },
    ];
  });

  protected readonly trackRow = (round: EvaluationRound): string => round.id ?? '';

  // ---- the form ---------------------------------------------------------------------------------

  protected readonly editing = signal<EvaluationRound | null>(null);
  protected readonly formOpen = signal(false);
  protected readonly nameEn = signal('');
  protected readonly nameAr = signal('');
  protected readonly opensOn = signal('');
  protected readonly due = signal<DueParts>({ date: '', time: '' });
  protected readonly remindHours = signal(String(REMIND_HOURS_DEFAULT));
  protected readonly failure = signal<string | null>(null);
  protected readonly saving = signal(false);

  protected readonly formTitle = computed(() =>
    this.t(this.editing() === null ? 'admin.rounds.createTitle' : 'admin.rounds.editTitle'),
  );

  protected openCreate(): void {
    this.editing.set(null);
    this.nameEn.set('');
    this.nameAr.set('');
    this.opensOn.set('');
    this.due.set({ date: '', time: '' });
    this.remindHours.set(String(REMIND_HOURS_DEFAULT));
    this.failure.set(null);
    this.formOpen.set(true);
  }

  protected openEdit(round: EvaluationRound): void {
    this.editing.set(round);
    this.nameEn.set(round.nameEn ?? '');
    this.nameAr.set(round.nameAr ?? '');
    this.opensOn.set(round.opensOn ?? '');
    this.due.set(dueParts(round.dueAt, round.timeZone ?? 'UTC'));
    this.remindHours.set(String(round.remindHoursBefore ?? REMIND_HOURS_DEFAULT));
    this.failure.set(null);
    this.formOpen.set(true);
  }

  protected setDueDate(date: string): void {
    this.due.update((parts) => ({ ...parts, date }));
  }

  protected setDueTime(time: string): void {
    this.due.update((parts) => ({ ...parts, time }));
  }

  /** Both names and a whole deadline: exactly what the server refuses the request without. */
  protected readonly canSave = computed(
    () =>
      this.nameEn().trim() !== '' && this.nameAr().trim() !== '' && dueAtOf(this.due()) !== '',
  );

  protected save(): void {
    if (!this.canSave() || this.saving()) return;
    const round = this.editing();
    const hours = Number(this.remindHours());
    const body = {
      nameEn: this.nameEn().trim(),
      nameAr: this.nameAr().trim(),
      dueAt: dueAtOf(this.due()),
      ...(this.opensOn().trim() === '' ? {} : { opensOn: this.opensOn().trim() }),
      ...(Number.isFinite(hours) && hours > 0 ? { remindHoursBefore: hours } : {}),
    };
    this.saving.set(true);
    this.failure.set(null);
    const call =
      round === null
        ? this.api.createEvaluationRound(body, 'body', false, { context: silentErrors() })
        : this.api.updateEvaluationRound(round.id ?? '', body, 'body', false, {
            context: silentErrors(),
          });
    call.subscribe({
      next: () => {
        this.saving.set(false);
        this.formOpen.set(false);
        this.editing.set(null);
        this.rounds.reload();
      },
      error: (error: unknown) => {
        this.saving.set(false);
        const failed = apiErrorOf(error);
        this.failure.set(
          failed?.code === 'already_released'
            ? this.t('admin.rounds.alreadyReleased')
            : (failed?.message ?? this.t('band.unreachable')),
        );
      },
    });
  }

  // ---- releasing it to the parents --------------------------------------------------------------

  protected readonly releasing = signal<EvaluationRound | null>(null);
  protected readonly released = signal(false);

  protected requestRelease(round: EvaluationRound): void {
    this.releasing.set(round);
    this.failure.set(null);
  }

  protected cancelRelease(): void {
    this.releasing.set(null);
  }

  protected readonly confirmTitle = computed(() => {
    const round = this.releasing();
    return round === null
      ? ''
      : this.t('admin.rounds.releaseTitle', { name: roundName(round, this.lang()) });
  });

  protected confirmRelease(): void {
    const round = this.releasing();
    const id = round?.id ?? '';
    if (id === '' || this.released()) return;
    this.released.set(true);
    this.api.releaseEvaluationRound(id, 'body', false, { context: silentErrors() }).subscribe({
      next: () => {
        this.released.set(false);
        this.releasing.set(null);
        this.rounds.reload();
      },
      error: (error: unknown) => {
        this.released.set(false);
        this.releasing.set(null);
        const failed = apiErrorOf(error);
        this.failure.set(
          failed?.code === 'already_released'
            ? this.t('admin.rounds.alreadyReleased')
            : (failed?.message ?? this.t('band.unreachable')),
        );
      },
    });
  }

  // ---- labels -----------------------------------------------------------------------------------

  protected name(round: EvaluationRound): string {
    return roundName(round, this.lang());
  }

  protected dueLabel(round: EvaluationRound): string {
    this.lang();
    const dueAt = round.dueAt ?? '';
    if (dueAt === '') return '—';
    return this.t('admin.rounds.at', {
      at: this.at(dueAt, round.timeZone ?? 'UTC'),
      zone: round.timeZone ?? 'UTC',
    });
  }

  protected releasedLabel(round: EvaluationRound): string {
    this.lang();
    if (round.released !== true) return this.t('admin.rounds.notReleased');
    const when = round.releasedAt ? this.at(round.releasedAt, round.timeZone ?? 'UTC') : '';
    return this.t('admin.rounds.releasedBy', {
      name: round.releasedByName ?? this.t('admin.rounds.anAdmin'),
      at: when,
    });
  }

  /** The zone the deadline means something in, named on the form beside the two fields. */
  protected readonly formZone = computed(
    () => this.editing()?.timeZone ?? this.rows()[0]?.timeZone ?? '',
  );

  private at(iso: string, timeZone: string): string {
    return new Intl.DateTimeFormat(this.transloco.getActiveLang(), {
      day: 'numeric',
      month: 'short',
      year: 'numeric',
      hour: 'numeric',
      minute: '2-digit',
      timeZone,
    }).format(new Date(iso));
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}
