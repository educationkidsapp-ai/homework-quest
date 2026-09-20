import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { ResultsAndGradebookApi, apiErrorCodeOf, apiErrorOf } from '../../api';
import { BandService } from '../../core/band/band.service';
import { FLAGS, FlagService } from '../../core/flags/flag.service';
import { FeatureDirective } from '../../core/flags/feature.directive';
import { CanDirective } from '../../core/permissions/can.directive';
import { PermissionService } from '../../core/permissions/permission.service';
import { UndoService } from '../../core/undo/undo.service';
import { ButtonComponent, InputComponent, TextareaComponent } from '../../ui';
import { ChildWorkComponent } from './child-work.component';
import {
  draftOf,
  markableStops,
  marks,
  parseScore,
  request,
  scoreLabel,
  type MarkDraft,
  type ResultRow,
  type RowStop,
} from './results.models';

/** The server's refusal when a mark names a stop of a level the child has not played. */
const STOP_NOT_PLAYED = 'stop_not_played';
import { StarsInputComponent } from './stars-input.component';

/**
 * **One child's marking** (`docs/teacher-flow.md` §4 step 9) — her open stops, one score
 * override, one comment to her parent.
 *
 * Lifted out of `results.page.html` by N4.4 so the exam results page could have the same panel
 * rather than a second one that drifts from it. An exam is a lesson, its retells and drawings
 * arrive through the same `GET /teacher/lessons/{id}/results` and are marked with the same
 * `PUT /teacher/marks`, so there is exactly one marking editor in this dashboard and both
 * screens open it.
 *
 * **One request for the whole panel, and only what changed.** `PUT /teacher/marks` deletes a
 * mark whose fields are all null, so sending the full panel would wipe the stop marks of a
 * teacher who only typed a comment. The Undo sends the same diff the other way round, which is
 * why both sides are kept.
 *
 * Two gates, both here rather than at the caller: `openStopMarking` (a school can buy the
 * numbers without the marking) and `results.write` (a MANAGERIAL account reads the marks and
 * changes none of them). The fields are read-only under either, so nobody types a mark and then
 * finds there is no button to send it with.
 */
@Component({
  selector: 'hq-mark-panel',
  imports: [
    ButtonComponent,
    InputComponent,
    TextareaComponent,
    ChildWorkComponent,
    StarsInputComponent,
    FeatureDirective,
    CanDirective,
    RouterLink,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './mark-panel.component.html',
  styleUrl: './mark-panel.component.scss',
})
export class MarkPanelComponent {
  private readonly api = inject(ResultsAndGradebookApi);
  private readonly transloco = inject(TranslocoService);
  private readonly flags = inject(FlagService);
  private readonly permissions = inject(PermissionService);
  private readonly band = inject(BandService);
  private readonly undo = inject(UndoService);

  readonly lessonId = input.required<string>();
  readonly row = input.required<ResultRow>();
  /** Fired after a save and after an Undo: the caller reloads whatever it is showing. */
  readonly saved = output<void>();

  protected readonly markingFlag = FLAGS.openStopMarking;

  protected readonly canMark = computed(
    () => this.flags.isOn(FLAGS.openStopMarking) && this.permissions.can('results.write'),
  );

  protected readonly draft = signal<MarkDraft | null>(null);
  /** What the server held when the panel opened — the values Undo puts back. */
  private original: MarkDraft | null = null;
  protected readonly saving = signal(false);

  /**
   * Which child's marks the draft below holds, as `lessonId::childId`.
   *
   * **Not the row's object identity.** Both callers feed this panel from a resource that reloads
   * — the exam results page reloads on every release and every re-opening, and the lesson one on
   * its own — and each reload hands down a *new* `ResultRow` for the same child. Keyed on
   * identity, the draft was rebuilt from the server and a teacher who had typed half a comment
   * when a reload landed lost it with no warning and nothing to undo. Keyed on the child, a
   * reload changes nothing she is looking at: her typing is hers until she saves it or closes
   * the panel, which is the moment this component is destroyed.
   */
  private key = '';

  constructor() {
    effect(() => {
      const row = this.row();
      const key = `${this.lessonId()}::${row.childId}`;
      if (key === this.key) return;
      this.key = key;
      this.original = draftOf(row);
      this.draft.set(this.original);
    });
  }

  /**
   * The stops on offer: open, and **of the level she was scored on** (N4.5 D1).
   *
   * `row.stops` is the union over all three levels of the lesson. Filtered on `open` alone, as
   * this was until N4.5, the panel offered Level 3's retell to a child who had played Level 1
   * and `PUT /teacher/marks` stored the stars against a stop she never answered — 200, no
   * complaint, and her score never moved.
   */
  protected readonly openStops = computed<readonly RowStop[]>(() => markableStops(this.row()));

  protected starsOf(stopId: string): number | null {
    return this.draft()?.stops[stopId]?.stars ?? null;
  }

  protected commentOf(stopId: string): string {
    return this.draft()?.stops[stopId]?.comment ?? '';
  }

  protected setStars(stopId: string, stars: number | null): void {
    this.editStop(stopId, (mark) => ({ ...mark, stars }));
  }

  protected setStopComment(stopId: string, comment: string): void {
    this.editStop(stopId, (mark) => ({ ...mark, comment }));
  }

  private editStop(
    stopId: string,
    edit: (mark: { stars: number | null; comment: string }) => {
      stars: number | null;
      comment: string;
    },
  ): void {
    const draft = this.draft();
    if (!draft) return;
    const mark = draft.stops[stopId] ?? { stars: null, comment: '' };
    this.draft.set({ ...draft, stops: { ...draft.stops, [stopId]: edit(mark) } });
  }

  protected readonly overrideText = computed(() => {
    const score = this.draft()?.score;
    return score === null || score === undefined ? '' : String(score);
  });

  protected setOverride(text: string): void {
    const draft = this.draft();
    if (draft) this.draft.set({ ...draft, score: parseScore(text) });
  }

  protected setParentComment(comment: string): void {
    const draft = this.draft();
    if (draft) this.draft.set({ ...draft, comment });
  }

  protected readonly dirty = computed(() => {
    const draft = this.draft();
    return draft !== null && this.original !== null && marks('l', 'c', draft, this.original).length > 0;
  });

  protected save(): void {
    const draft = this.draft();
    const before = this.original;
    if (!draft || !before) return;
    const row = this.row();
    const payload = marks(this.lessonId(), row.childId, draft, before);
    if (payload.length === 0) return;

    this.saving.set(true);
    this.api.saveMarks(request(payload)).subscribe({
      next: () => {
        this.saving.set(false);
        this.saved.emit();
        this.undo.offerUndo({
          message: this.t('results.marking.saved', { name: row.name }),
          undo: () => this.restore(row.childId, draft, before),
          commit: () => this.saved.emit(),
        });
      },
      error: (error: unknown) => this.fail(error),
    });
  }

  private restore(childId: string, applied: MarkDraft, before: MarkDraft): void {
    const payload = marks(this.lessonId(), childId, before, applied);
    if (payload.length === 0) return;
    this.api.saveMarks(request(payload)).subscribe({
      next: () => this.saved.emit(),
      error: (error: unknown) => this.fail(error),
    });
  }

  protected childLink(): readonly string[] {
    return ['/teacher/children', this.row().childId];
  }

  protected score(value: number | null): string {
    return scoreLabel(value);
  }

  /**
   * A refusal, in the red band — and one of them in the dashboard's own words.
   *
   * `409 stop_not_played` is the server refusing a mark on a stop of a level the child never
   * played. It should be unreachable: {@link openStops} offers only her own level's. It is
   * caught anyway because it is the one failure that means *this screen is out of date* — the
   * child played on while the panel was open, or an older tab is still showing the union — and
   * the answer is to say so in words a teacher can act on and reload behind her, not to repeat
   * the server's sentence about a stop id.
   */
  private fail(error: unknown): void {
    this.saving.set(false);
    if (apiErrorCodeOf(error) === STOP_NOT_PLAYED) {
      this.band.fail(this.t('results.marking.notPlayed', { name: this.row().name }));
      this.saved.emit();
      return;
    }
    this.band.fail(apiErrorOf(error)?.message ?? this.t('band.unreachable'));
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}
