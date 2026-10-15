import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { catchError, of, tap } from 'rxjs';
import {
  type CreateBroadcastRequest,
  type VoiceDraft,
  BroadcastsApi,
  VoiceAnnouncementsApi,
  apiErrorOf,
  apiErrorCodeOf,
} from '../../api';
import { BandService } from '../../core/band/band.service';
import {
  type BroadcastDraft,
  type ComposeContext,
  EMPTY_DRAFT,
  canChooseGrade,
  dayIn,
  gradeOptions,
} from '../../core/broadcasts/broadcast.rules';
import { FLAGS, FlagService } from '../../core/flags/flag.service';
import { CanDirective } from '../../core/permissions/can.directive';
import {
  MAX_SPEECH_MS,
  SPEECH_INPUT,
  type SpeechEnd,
  type SpeechSession,
} from '../../core/speech/speech-input';
import { ButtonComponent } from '../../ui';
import { BroadcastComposeComponent, type ComposeSection } from './compose-sheet.component';

/** The server's cap on a transcript (`CreateVoiceDraftRequest`), so two minutes never answer 400. */
const MAX_TRANSCRIPT = 4000;

/**
 * Where she is: the button, the recording, the wait for the assistant, or the sheet.
 *
 * `review` is not one of them — the sheet is a dialog over whatever is behind it, and what is
 * behind it is the button she pressed, so the phase goes back to `idle` the moment it opens.
 */
type Phase = 'idle' | 'recording' | 'preparing';

/**
 * **Voice note to announcement** (feature 22): she says it, the assistant writes it, she sends it.
 *
 * Three things are deliberate here.
 *
 * **The transcription is on her device** (`core/speech/speech-input.ts`). No audio is recorded to a
 * file, uploaded or stored — the server is handed text — so the privacy criterion is a property of
 * the design and not a retention job. The button is absent in a browser without speech recognition
 * rather than offered and broken.
 *
 * **Nothing is posted by preparing.** `POST /management/broadcasts/voice-drafts` writes a draft
 * that is delivered to nobody; the only thing that sends is the sheet's own Send, which is the
 * same `POST /management/broadcasts` the typed composer uses, with the draft's id along for the
 * ride so the transcript stays with the announcement.
 *
 * **The sheet is the typed one.** `hq-broadcast-compose` is handed the assistant's answer as its
 * `initial` draft, which means every field is hers to change and the rules that refuse a bad one
 * are the rules the server enforces (`core/broadcasts/broadcast.rules.ts`) — not a second,
 * read-only preview that drifts from the real composer.
 */
@Component({
  selector: 'hq-voice-note',
  imports: [BroadcastComposeComponent, ButtonComponent, CanDirective, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (offered()) {
      <ng-container *hqCan="'broadcast.voice'">
        <div class="vn">
          @switch (phase()) {
            @case ('idle') {
              <hq-button (pressed)="record()">{{ 'voice.record' | transloco }}</hq-button>
              <p class="hq-muted vn__hint">{{ 'voice.hint' | transloco }}</p>
            }
            @case ('recording') {
              <p class="vn__clock">{{ clock() }} / {{ cap }}</p>
              <p class="vn__heard" dir="rtl" aria-live="polite">
                {{ heard() === '' ? ('voice.listening' | transloco) : heard() }}
              </p>
              <div class="vn__row">
                <hq-button variant="primary" (pressed)="stop()">{{ 'voice.stop' | transloco }}</hq-button>
                <hq-button variant="quiet" (pressed)="cancel()">{{ 'ui.cancel' | transloco }}</hq-button>
              </div>
            }
            @case ('preparing') {
              <p class="vn__clock" aria-live="polite">{{ 'voice.preparing' | transloco }}</p>
            }
          }
        </div>

        @if (draft(); as prepared) {
          <hq-broadcast-compose
            [(open)]="reviewing"
            [ctx]="ctx()"
            [sections]="sections()"
            [initial]="prepared"
            [posting]="posting()"
            [title]="'voice.review'"
            (submitted)="send($event)"
          >
            <!-- What she said, under what it became: readable, collapsed, and never editable —
                 the transcript is the record of the note, not a field of the announcement. -->
            <details class="vn__transcript">
              <summary>{{ 'voice.transcript' | transloco }}</summary>
              <p class="vn__heard" dir="rtl">{{ transcript() }}</p>
            </details>
          </hq-broadcast-compose>
        }
      </ng-container>
    }
  `,
  styles: `
    .vn {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--hq-space-12);
      margin-block-end: var(--hq-space-24);
      padding: var(--hq-space-16);
      border: var(--hq-size-rule-thin) solid var(--hq-color-rule);
      background: var(--hq-color-surface-raised);
    }

    .vn__hint,
    .vn__heard {
      margin: 0;
    }

    .vn__clock {
      margin: 0;
      font-weight: var(--hq-font-label-weight);
      font-variant-numeric: tabular-nums;
      color: var(--hq-color-accent-ink);
    }

    .vn__heard {
      flex: 1 1 100%;
      white-space: pre-wrap;
    }

    .vn__row {
      display: flex;
      gap: var(--hq-space-8);
    }

    .vn__transcript summary {
      font-weight: var(--hq-font-label-weight);
      cursor: pointer;
    }
  `,
})
export class VoiceNoteComponent {
  private readonly drafts = inject(VoiceAnnouncementsApi);
  private readonly api = inject(BroadcastsApi);
  private readonly speech = inject(SPEECH_INPUT);
  private readonly flags = inject(FlagService);
  private readonly band = inject(BandService);
  private readonly transloco = inject(TranslocoService);

  /** The same context the typed composer is given: her departments, her sections, today, the zone. */
  readonly ctx = input.required<ComposeContext>();
  readonly sections = input<readonly ComposeSection[]>([]);
  /** One announcement went out: the screen reloads its lists and says so. */
  readonly posted = output<void>();

  protected readonly cap = clockOf(MAX_SPEECH_MS);

  protected readonly phase = signal<Phase>('idle');
  /** The live transcript while she talks. */
  protected readonly heard = signal('');
  /** The transcript the draft was built from — what the sheet shows under the fields. */
  protected readonly transcript = signal('');
  protected readonly elapsed = signal(0);
  protected readonly draft = signal<BroadcastDraft | null>(null);
  protected readonly reviewing = signal(false);
  protected readonly posting = signal(false);

  private voiceDraftId = '';
  private session: SpeechSession | null = null;
  private ticker: ReturnType<typeof setInterval> | null = null;
  /** Stop prepares the announcement; Cancel throws the whole recording away. */
  private wanted: 'draft' | 'drop' = 'draft';

  /**
   * Both switches, and a browser that can transcribe.
   *
   * `announcements` as well as `voiceAnnouncement`, because this is a way of writing an
   * announcement: a school without the feed has nothing for it to write. `broadcast.voice` is the
   * key on the route and `*hqCan` in the template is what reads it.
   */
  protected readonly offered = computed(
    () =>
      this.flags.isOn(FLAGS.announcements) &&
      this.flags.isOn(FLAGS.voiceAnnouncement) &&
      this.speech.supported(),
  );

  protected readonly clock = computed(() => clockOf(this.elapsed()));

  protected record(): void {
    this.wanted = 'draft';
    this.heard.set('');
    this.transcript.set('');
    this.elapsed.set(0);
    this.draft.set(null);
    this.voiceDraftId = '';
    this.phase.set('recording');
    this.ticker = setInterval(() => this.elapsed.update((ms) => Math.min(ms + 1000, MAX_SPEECH_MS)), 1000);
    this.session = this.speech.start({
      transcript: (text) => this.heard.set(text),
      ended: (reason) => this.ended(reason),
    });
  }

  protected stop(): void {
    this.wanted = 'draft';
    this.session?.stop();
  }

  protected cancel(): void {
    this.wanted = 'drop';
    this.session?.stop();
  }

  /**
   * The two minutes running out prepares the announcement exactly as her own Stop does: she has
   * said her note, and a recording that ends by itself and then asks her to press something is a
   * dialog about a decision she already made.
   */
  private ended(reason: SpeechEnd): void {
    if (this.ticker !== null) clearInterval(this.ticker);
    this.ticker = null;
    this.session = null;
    if (this.wanted === 'drop') {
      this.phase.set('idle');
      this.heard.set('');
      return;
    }
    if (reason === 'failed') {
      this.phase.set('idle');
      this.band.fail(this.t('voice.errors.device'));
      return;
    }
    this.prepare();
  }

  /** Text only, capped at what the server accepts, and never an empty transcript. */
  private prepare(): void {
    const transcript = this.heard().trim().slice(0, MAX_TRANSCRIPT);
    if (transcript === '') {
      this.phase.set('idle');
      this.band.fail(this.t('voice.errors.silent'));
      return;
    }
    this.transcript.set(transcript);
    this.phase.set('preparing');
    this.drafts
      .createVoiceDraft({ transcript })
      .pipe(
        tap((prepared) => this.review(prepared)),
        catchError((cause: unknown) => {
          this.phase.set('idle');
          this.band.fail(this.failure(cause));
          return of(null);
        }),
      )
      .subscribe();
  }

  private review(prepared: VoiceDraft): void {
    this.voiceDraftId = prepared.id ?? '';
    this.transcript.set(prepared.transcript ?? this.transcript());
    this.draft.set(draftOf(prepared, this.ctx()));
    this.phase.set('idle');
    this.reviewing.set(true);
  }

  /**
   * Send: the **existing** route, with the draft's id on it.
   *
   * `POST /management/broadcasts` and not a route of its own, so a voice announcement is the same
   * row, the same audience rules and the same notifications as a typed one. The id is what links
   * the transcript to what went out, and what makes the server refuse to send the same draft twice.
   */
  protected send(body: CreateBroadcastRequest): void {
    this.posting.set(true);
    this.api
      .createManagementBroadcast({
        ...body,
        ...(this.voiceDraftId === '' ? {} : { voiceDraftId: this.voiceDraftId }),
      })
      .pipe(
        tap(() => {
          this.posting.set(false);
          this.reviewing.set(false);
          this.draft.set(null);
          this.voiceDraftId = '';
          this.posted.emit();
        }),
        catchError((cause: unknown) => {
          // The sheet stays open on its own draft: nothing was sent, and everything she reviewed
          // is still there to send again.
          this.posting.set(false);
          this.band.fail(this.failure(cause));
          return of(null);
        }),
      )
      .subscribe();
  }

  /** The three answers this feature has, and the server's own sentence for anything else. */
  private failure(cause: unknown): string {
    const code = apiErrorCodeOf(cause);
    if (code === 'rephrase') return this.t('voice.errors.rephrase');
    if (code === 'model_failed') return this.t('voice.errors.model');
    if (code === 'already_sent') return this.t('voice.errors.alreadySent');
    return apiErrorOf(cause)?.message ?? this.t('band.unreachable');
  }

  private t(key: string): string {
    return this.transloco.translate<string>(key);
  }
}

/**
 * The assistant's answer as a draft of the typed composer — **with every suggestion checked
 * against what this sheet can actually say**.
 *
 * The server already drops an audience that is not one of her real cells. This drops what is left:
 * a grade the sheet may not name (she holds two departments, or no class in that grade) and a
 * department that is not hers. Without that check a suggestion could arrive as a validation error
 * on a field she never filled in, and the Send button would be dead with nothing to explain it.
 * The Admin is the usual case — her scope is the whole school, so she has no department list to
 * narrow by and the announcement goes to all of it.
 */
export function draftOf(prepared: VoiceDraft, ctx: ComposeContext): BroadcastDraft {
  const curriculum = prepared.audience?.curriculum ?? '';
  const department = ctx.departments.includes(curriculum) ? curriculum : '';
  const suggested = prepared.audience?.grade ?? null;
  const base: BroadcastDraft = { ...EMPTY_DRAFT, kind: 'announcement', department };
  const grade =
    suggested !== null && canChooseGrade(ctx) && gradeOptions(base, ctx).includes(suggested)
      ? suggested
      : null;
  return {
    ...base,
    title: prepared.title ?? '',
    bodyEn: prepared.bodyEn ?? '',
    bodyAr: prepared.bodyAr ?? '',
    grade,
    expires: prepared.expiresAt === undefined ? '' : dayIn(prepared.expiresAt, ctx.zone),
  };
}

/** `m:ss`, which is how long two minutes are said. */
function clockOf(ms: number): string {
  const total = Math.max(0, Math.round(ms / 1000));
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`;
}
