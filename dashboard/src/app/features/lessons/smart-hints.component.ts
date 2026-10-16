import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import {
  type PracticePlayView,
  type SmartHintsView,
  type StopHintsView,
  apiErrorOf,
} from '../../api';
import { activeLang } from '../../core/i18n/active-lang';
import { CanDirective } from '../../core/permissions/can.directive';
import { BandComponent, ButtonComponent, CardComponent, InputComponent, SkeletonComponent } from '../../ui';
import { type PreviewImage, PhonePreviewComponent } from '../../ui/phone-preview';
import { toPreviewPlayOf } from './lesson-preview.mapper';
import { SmartHintsService } from './smart-hints.service';

/** The contract's own limit, and Prompt B's: `hint` is at most 90 characters. */
const HINT_MAX = 90;

/** Exactly two, always — the server refuses anything else, and an empty one is a missing hint. */
const HINT_COUNT = 2;

/** Unique per instance, so the two headings can name their own region. */
let nextId = 0;

/** One stop's two fields as the teacher has them now, against what the server last stored. */
type Draft = Readonly<Record<string, readonly string[]>>;

/**
 * Feature 30's "Hints and practice" section of the teacher's lesson page.
 *
 * What the lesson's `HINTS` step wrote: two graded hints for every answerable question, and one
 * short practice set per confirmed skill. **None of it reaches a child until she approves it**,
 * which is the one sentence at the top of the section and the reason every row carries its state
 * in words rather than only in a tick.
 *
 * Three rules shape the controls:
 *
 *   - **Editing clears approval** — the server does that, so an edited hint comes back
 *     `approved: false` and the row says so without this screen having to guess.
 *   - **Approve acts on what is stored**, so a stop with unsaved edits cannot be approved: the
 *     button says why instead of silently approving the old wording.
 *   - **Reject deletes** a practice set, so it confirms with the red band, like every other
 *     destructive action on this page. Approve does not — it is undone by editing.
 *
 * The 400 the server answers a bad hint with (longer than 90 characters, or containing the
 * answer) is shown under the two fields it is about. The whole section is behind `smartHints`:
 * the page renders it with `*hqFeature`, so the read below never fires for a school whose routes
 * would answer 404.
 */
@Component({
  selector: 'hq-smart-hints',
  imports: [
    BandComponent,
    ButtonComponent,
    CardComponent,
    InputComponent,
    SkeletonComponent,
    PhonePreviewComponent,
    CanDirective,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-card [title]="'lessons.detail.hints.title' | transloco">
      <div class="hints">
        <!-- The promise the whole section is about, before anything she could press. -->
        <p class="hints__note">{{ 'lessons.detail.hints.note' | transloco }}</p>

        @if (rejecting(); as play) {
          <hq-band
            variant="confirm"
            [open]="true"
            [title]="'lessons.detail.hints.rejectTitle' | transloco"
            [confirmLabel]="'lessons.detail.hints.rejectConfirm' | transloco"
            (confirmed)="confirmReject()"
            (dismissed)="rejecting.set(null)"
          >
            {{ 'lessons.detail.hints.rejectBody' | transloco: { skill: play.skillName } }}
          </hq-band>
        }

        @if (loading()) {
          <hq-skeleton
            [loading]="true"
            [lines]="4"
            height="var(--hq-size-row-height)"
            [label]="'lessons.detail.hints.loading' | transloco"
          />
        } @else if (failed()) {
          <p class="hints__empty" role="alert">{{ 'lessons.detail.hints.failed' | transloco }}</p>
        } @else if (stops().length === 0 && practice().length === 0) {
          <p class="hints__empty">{{ 'lessons.detail.hints.empty' | transloco }}</p>
        } @else {
          @if (stops().length > 0) {
            <section class="hints__section" [attr.aria-labelledby]="hintsHeadingId">
              <div class="hints__section-head">
                <h3 class="hints__heading" [id]="hintsHeadingId">
                  {{ 'lessons.detail.hints.hintsTitle' | transloco }}
                </h3>
                <hq-button
                  *hqCan="'lesson.write'"
                  variant="secondary"
                  [disabled]="!canApproveAll()"
                  [reason]="approveAllReason()"
                  (pressed)="approveAll()"
                >
                  {{ 'lessons.detail.hints.approveAll' | transloco }}
                </hq-button>
              </div>

              @for (row of stops(); track row.stopId; let position = $index) {
                <article class="hints__stop">
                  <header class="hints__stop-head">
                    <p class="hints__question">{{ position + 1 }}. {{ row.question }}</p>
                    <span class="hints__state" [class.hints__state--yes]="row.approved">
                      {{
                        (row.approved
                          ? 'lessons.detail.hints.approved'
                          : 'lessons.detail.hints.notApproved'
                        ) | transloco
                      }}
                    </span>
                  </header>
                  <!-- The words a hint may never contain, so she can judge the two without
                       opening the question in the editor above. -->
                  <p class="hints__answer">
                    {{ 'lessons.detail.hints.answer' | transloco: { answer: row.answer } }}
                  </p>

                  <div class="hints__fields">
                    @for (hint of draftFor(row.stopId); track $index) {
                      <hq-input
                        [label]="
                          'lessons.detail.hints.hintLabel'
                            | transloco: { index: $index + 1, position: position + 1 }
                        "
                        [value]="hint"
                        (valueChange)="setHint(row.stopId, $index, $event)"
                        [maxLength]="hintMax"
                        [required]="true"
                        [disabled]="disabled()"
                        [hint]="$last ? ('lessons.detail.hints.hintHint' | transloco) : null"
                      />
                    }
                  </div>

                  @if (errorFor(row.stopId); as message) {
                    <!-- role="alert": it arrives in answer to a Save she pressed, and it is the
                         only place the refusal is said — the interceptor's band is silenced. -->
                    <p class="hints__error" role="alert">{{ message }}</p>
                  }

                  <div class="hints__actions" *hqCan="'lesson.write'">
                    <hq-button
                      variant="secondary"
                      [disabled]="!canSave(row.stopId)"
                      [reason]="saveReason(row.stopId)"
                      [ariaLabel]="
                        'lessons.detail.hints.saveStop' | transloco: { position: position + 1 }
                      "
                      (pressed)="save(row.stopId)"
                    >
                      {{ 'lessons.detail.hints.save' | transloco }}
                    </hq-button>
                    <hq-button
                      variant="quiet"
                      [disabled]="!canApprove(row)"
                      [reason]="approveReason(row)"
                      [ariaLabel]="
                        'lessons.detail.hints.approveStop' | transloco: { position: position + 1 }
                      "
                      (pressed)="approveOne(row.stopId)"
                    >
                      {{ 'lessons.detail.hints.approve' | transloco }}
                    </hq-button>
                  </div>
                </article>
              }
            </section>
          }

          @if (practice().length > 0) {
            <section class="hints__section" [attr.aria-labelledby]="practiceHeadingId">
              <h3 class="hints__heading" [id]="practiceHeadingId">
                {{ 'lessons.detail.hints.practiceTitle' | transloco }}
              </h3>

              @for (play of practice(); track play.playId) {
                <article class="hints__practice">
                  <header class="hints__stop-head">
                    <p class="hints__skill">
                      {{ 'lessons.detail.hints.practiceSkill' | transloco: { skill: play.skillName } }}
                    </p>
                    <span class="hints__state" [class.hints__state--yes]="play.approved">
                      {{
                        (play.approved
                          ? 'lessons.detail.hints.approved'
                          : 'lessons.detail.hints.notApproved'
                        ) | transloco
                      }}
                    </span>
                  </header>

                  <div class="hints__practice-body">
                    <!-- Read-only on purpose: a practice set is approved or rejected whole, and
                         the editor above is where a question is rewritten. -->
                    <ul class="hints__practice-stops">
                      @for (stop of play.play.stops; track stop.id) {
                        <li>
                          <button
                            type="button"
                            class="hints__practice-stop"
                            [class.hints__practice-stop--selected]="stop.id === selectedStopIn(play)"
                            [attr.aria-pressed]="stop.id === selectedStopIn(play)"
                            (click)="selectedStop.set(stop.id)"
                          >
                            {{ stop.title }}
                          </button>
                        </li>
                      }
                    </ul>
                    <hq-phone-preview
                      [stop]="previewStopIn(play)"
                      [images]="images()"
                      [subject]="subject()"
                      [theme]="play.play.theme"
                      [label]="'lessons.detail.hints.previewLabel' | transloco"
                      [placeholder]="'lessons.detail.hints.previewEmpty' | transloco"
                      [caption]="previewStopIn(play)?.title ?? null"
                    />
                  </div>

                  <div class="hints__actions" *hqCan="'lesson.write'">
                    <hq-button
                      variant="secondary"
                      [disabled]="disabled() || play.approved"
                      [reason]="play.approved ? ('lessons.detail.hints.alreadyApproved' | transloco) : null"
                      [ariaLabel]="
                        'lessons.detail.hints.approvePractice' | transloco: { skill: play.skillName }
                      "
                      (pressed)="approvePractice(play.playId)"
                    >
                      {{ 'lessons.detail.hints.approve' | transloco }}
                    </hq-button>
                    <hq-button
                      variant="quiet"
                      [disabled]="disabled()"
                      [ariaLabel]="
                        'lessons.detail.hints.rejectPractice' | transloco: { skill: play.skillName }
                      "
                      (pressed)="rejecting.set(play)"
                    >
                      {{ 'lessons.detail.hints.reject' | transloco }}
                    </hq-button>
                  </div>
                </article>
              }
            </section>
          }
        }
      </div>
    </hq-card>
  `,
  styles: `
    @use 'mixins' as m;

    .hints {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-16);
    }

    .hints__note,
    .hints__empty {
      margin: 0;
      color: var(--hq-color-ink-soft);
    }

    .hints__section {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-12);
    }

    .hints__section-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--hq-space-12);
    }

    .hints__heading {
      @include m.label;

      margin: 0;
      color: var(--hq-color-ink-soft);
    }

    .hints__stop,
    .hints__practice {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-12);
      border: var(--hq-size-rule) solid var(--hq-color-line);
      padding: var(--hq-space-16);
    }

    .hints__stop-head {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: var(--hq-space-12);
    }

    .hints__question,
    .hints__skill {
      margin: 0;
      font-weight: var(--hq-font-label-weight);
    }

    .hints__answer {
      margin: 0;
      color: var(--hq-color-ink-soft);
    }

    .hints__state {
      @include m.badge;
    }

    .hints__state--yes {
      border-color: var(--hq-color-accent);
      color: var(--hq-color-accent);
    }

    .hints__error {
      margin: 0;
      color: var(--hq-color-accent);
    }

    .hints__fields {
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
      gap: var(--hq-space-12);
    }

    .hints__actions {
      display: flex;
      gap: var(--hq-space-8);
    }

    .hints__practice-body {
      display: flex;
      align-items: flex-start;
      gap: var(--hq-space-16);
    }

    .hints__practice-stops {
      display: flex;
      flex: 1;
      flex-direction: column;
      gap: var(--hq-space-4);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .hints__practice-stop {
      @include m.control;
      @include m.hover-tint;

      inline-size: 100%;
      min-block-size: var(--hq-size-touch-target);
      text-align: start;
    }

    .hints__practice-stop--selected {
      border-color: var(--hq-color-accent);
      border-width: var(--hq-size-selected-border);
    }

    @media (width <= 60rem) {
      .hints__fields {
        grid-template-columns: minmax(0, 1fr);
      }

      .hints__practice-body {
        flex-direction: column;
      }
    }
  `,
})
export class SmartHintsComponent {
  private readonly api = inject(SmartHintsService);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  readonly lessonId = input.required<string>();
  /** The lesson's page pictures, so a practice stop that names one previews with it. */
  readonly images = input<readonly PreviewImage[]>([]);
  /** Picks the preview's world palette, like the level editor's. */
  readonly subject = input<string>('english');
  /** The page's own lock — a running pipeline, or another write in flight. */
  readonly disabled = input(false);

  protected readonly hintMax = HINT_MAX;
  /** Each half of the section is a named region, so a screen reader can jump straight to one. */
  protected readonly hintsHeadingId = `hq-hints-${++nextId}`;
  protected readonly practiceHeadingId = `hq-practice-${nextId}`;

  private readonly view = rxResource<SmartHintsView | null, string>({
    params: () => this.lessonId(),
    stream: ({ params: id }) => this.api.read(id),
    defaultValue: null,
  });

  protected readonly loading = this.view.isLoading;
  protected readonly failed = computed(() => this.view.error() !== undefined);
  protected readonly stops = computed<readonly StopHintsView[]>(() => this.view.value()?.hints ?? []);
  protected readonly practice = computed<readonly PracticePlayView[]>(
    () => this.view.value()?.practice ?? [],
  );

  /** What she has typed, per stop — seeded from the server and reset whenever it answers. */
  private readonly draft = signal<Draft>({});
  /** The server's refusal for one stop, cleared the moment she types in it again. */
  private readonly errors = signal<Readonly<Record<string, string>>>({});
  protected readonly selectedStop = signal<string | null>(null);
  protected readonly rejecting = signal<PracticePlayView | null>(null);

  constructor() {
    effect(() => {
      const stops = this.stops();
      this.draft.set(Object.fromEntries(stops.map((row) => [row.stopId, padded(row.hints)])));
      this.errors.set({});
    });
  }

  protected draftFor(stopId: string): readonly string[] {
    return this.draft()[stopId] ?? padded([]);
  }

  protected errorFor(stopId: string): string | null {
    return this.errors()[stopId] ?? null;
  }

  protected setHint(stopId: string, index: number, value: string): void {
    this.draft.update((draft) => ({
      ...draft,
      [stopId]: (draft[stopId] ?? padded([])).map((hint, i) => (i === index ? value : hint)),
    }));
    this.clearError(stopId);
  }

  private stored(stopId: string): readonly string[] {
    return padded(this.stops().find((row) => row.stopId === stopId)?.hints ?? []);
  }

  private dirty(stopId: string): boolean {
    return this.draftFor(stopId).join('\u0000') !== this.stored(stopId).join('\u0000');
  }

  /** Both fields filled: the server wants exactly two hints, and an empty one is not a hint. */
  private complete(stopId: string): boolean {
    return this.draftFor(stopId).every((hint) => hint.trim().length > 0);
  }

  protected canSave(stopId: string): boolean {
    return !this.disabled() && this.dirty(stopId) && this.complete(stopId);
  }

  protected saveReason(stopId: string): string | null {
    this.lang();
    if (this.canSave(stopId) || this.disabled()) return null;
    return this.dirty(stopId)
      ? this.t('lessons.detail.hints.needBoth')
      : this.t('lessons.detail.editor.noChanges');
  }

  protected canApprove(row: StopHintsView): boolean {
    return !this.disabled() && !row.approved && !this.dirty(row.stopId) && row.hints.length > 0;
  }

  protected approveReason(row: StopHintsView): string | null {
    this.lang();
    if (this.canApprove(row) || this.disabled()) return null;
    if (this.dirty(row.stopId)) return this.t('lessons.detail.hints.saveFirst');
    return row.approved
      ? this.t('lessons.detail.hints.alreadyApproved')
      : this.t('lessons.detail.hints.noHints');
  }

  /** Every stop that has hints the server stored and she has not signed yet. */
  private readonly pending = computed(() =>
    this.stops().filter((row) => !row.approved && row.hints.length > 0),
  );

  private readonly anyDirty = computed(() => this.stops().some((row) => this.dirty(row.stopId)));

  protected readonly canApproveAll = computed(
    () => !this.disabled() && !this.anyDirty() && this.pending().length > 0,
  );

  protected readonly approveAllReason = computed(() => {
    this.lang();
    if (this.canApproveAll() || this.disabled()) return null;
    return this.anyDirty()
      ? this.t('lessons.detail.hints.saveFirst')
      : this.t('lessons.detail.hints.allApproved');
  });

  protected save(stopId: string): void {
    if (!this.canSave(stopId)) return;
    const hints = this.draftFor(stopId).map((hint) => hint.trim());
    this.api.save(this.lessonId(), stopId, hints).subscribe({
      // The answer carries the cleared approval, so the row's state follows the edit without a
      // re-read of the whole section — and `draft` is left alone: it is already what she typed.
      next: (saved) => this.replaceStop(saved),
      error: (error: unknown) => this.setError(stopId, error),
    });
  }

  protected approveOne(stopId: string): void {
    this.api.approveHints(this.lessonId(), [stopId]).subscribe({
      next: (view) => this.view.set(view),
      error: (error: unknown) => this.setError(stopId, error),
    });
  }

  protected approveAll(): void {
    if (!this.canApproveAll()) return;
    // `stopIds` left out — the server reads that as every stop, so one request signs the lesson.
    this.api.approveHints(this.lessonId()).subscribe({ next: (view) => this.view.set(view) });
  }

  protected approvePractice(playId: string): void {
    this.api.approvePractice(this.lessonId(), playId).subscribe({
      next: (saved) => this.replacePractice(saved),
    });
  }

  protected confirmReject(): void {
    const play = this.rejecting();
    this.rejecting.set(null);
    if (!play) return;
    // 204 and the play is gone, so it goes from the list rather than being read back — the
    // lesson page's own `/status` poll notices the play count and re-reads the levels.
    this.api.rejectPractice(this.lessonId(), play.playId).subscribe({
      next: () => this.dropPractice(play.playId),
    });
  }

  // ---- the practice preview -------------------------------------------------------------------

  protected selectedStopIn(play: PracticePlayView): string | null {
    const selected = this.selectedStop();
    return play.play.stops.some((stop) => stop.id === selected) ? selected : (play.play.stops[0]?.id ?? null);
  }

  protected previewStopIn(play: PracticePlayView) {
    const id = this.selectedStopIn(play);
    return toPreviewPlayOf(play.play).stops.find((stop) => stop.id === id) ?? null;
  }

  // ---- local updates --------------------------------------------------------------------------

  private replaceStop(saved: StopHintsView): void {
    this.view.update((view) =>
      view ? { ...view, hints: view.hints.map((row) => (row.stopId === saved.stopId ? saved : row)) } : view,
    );
  }

  private replacePractice(saved: PracticePlayView): void {
    this.view.update((view) =>
      view
        ? { ...view, practice: view.practice.map((row) => (row.playId === saved.playId ? saved : row)) }
        : view,
    );
  }

  private dropPractice(playId: string): void {
    this.view.update((view) =>
      view ? { ...view, practice: view.practice.filter((row) => row.playId !== playId) } : view,
    );
  }

  private setError(stopId: string, error: unknown): void {
    this.lang();
    const message = apiErrorOf(error)?.message ?? this.t('lessons.detail.hints.saveFailed');
    this.errors.update((errors) => ({ ...errors, [stopId]: message }));
  }

  private clearError(stopId: string): void {
    if (this.errors()[stopId] === undefined) return;
    this.errors.update(({ [stopId]: _gone, ...rest }) => rest);
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}

/** Always two fields, whatever the server kept: the validator may have dropped a hint. */
function padded(hints: readonly string[]): readonly string[] {
  return Array.from({ length: HINT_COUNT }, (_, index) => hints[index] ?? '');
}
