import { InjectionToken } from '@angular/core';

/**
 * **Speech to text, on the device** (feature 22, the owner's decision).
 *
 * The browser transcribes; the server is handed text and nothing else. No audio file is created,
 * uploaded or stored anywhere in this product, which is what makes "the audio is gone after
 * transcription" a property of the design rather than a retention job somebody has to trust.
 *
 * The interface is the seam. `WebSpeechInput` below is the one implementation that talks to a
 * browser; every spec and the styleguide hand in a fake through {@link SPEECH_INPUT}, so the
 * recording screen can be tested without a microphone, a network or a grant dialog.
 *
 * **Caveat worth knowing**: Chrome's own recognition runs on Google's servers, and Firefox has
 * none at all — which is exactly what {@link SpeechInput.supported} answers, so the button is
 * absent rather than offered and broken.
 */

/** 2:00. The cap the spec sets, counted from the first `start()` and never reset by a restart. */
export const MAX_SPEECH_MS = 120_000;

/** Saudi dialect, which is what a leader actually speaks (`docs/features/22-…`). */
export const SPEECH_LANG = 'ar-SA';

/**
 * Why listening stopped.
 *
 * `stopped` is her own Stop, `capped` is the two minutes running out, `failed` is the device or
 * the permission — three different sentences, so the caller gets the reason rather than a boolean.
 */
export type SpeechEnd = 'stopped' | 'capped' | 'failed';

export interface SpeechListener {
  /** The whole transcript so far — settled text plus whatever is still being heard. */
  readonly transcript: (text: string) => void;
  readonly ended: (reason: SpeechEnd) => void;
}

/** One recording. `stop()` is idempotent and safe after it has already ended. */
export interface SpeechSession {
  stop(): void;
}

export interface SpeechInput {
  /** Whether this browser can transcribe at all. `false` hides the whole feature. */
  supported(): boolean;
  start(listener: SpeechListener): SpeechSession;
}

/* The four shapes of the Web Speech API this file touches, declared here rather than taken from
   `lib.dom`: `SpeechRecognition` is still prefixed in the browsers that have it, and a fake in a
   spec has to satisfy the same type as the real constructor. */
export interface SpeechAlternativeLike {
  readonly transcript: string;
}

export interface SpeechResultLike {
  readonly isFinal: boolean;
  readonly length: number;
  readonly [index: number]: SpeechAlternativeLike;
}

export interface SpeechResultListLike {
  readonly length: number;
  readonly [index: number]: SpeechResultLike;
}

export interface SpeechResultEventLike {
  readonly resultIndex: number;
  readonly results: SpeechResultListLike;
}

export interface SpeechErrorEventLike {
  readonly error: string;
}

export interface SpeechRecognitionLike {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  onresult: ((event: SpeechResultEventLike) => void) | null;
  onerror: ((event: SpeechErrorEventLike) => void) | null;
  onend: (() => void) | null;
  start(): void;
  stop(): void;
  abort(): void;
}

export type SpeechRecognitionCtor = new () => SpeechRecognitionLike;

/** Where the constructor is looked for — `window`, or a spec's object with a fake on it. */
export interface SpeechHost {
  readonly SpeechRecognition?: SpeechRecognitionCtor;
  readonly webkitSpeechRecognition?: SpeechRecognitionCtor;
}

/**
 * `SpeechRecognition` (and `webkitSpeechRecognition`, which is what Chrome and Safari still
 * publish it as), in continuous mode with interim results.
 *
 * **It restarts itself.** Continuous recognition ends on its own after a few seconds of silence —
 * a pause mid-sentence is enough — and a leader thinking about her next sentence is not finished
 * talking. So `end` while she is still recording starts it again, with the text heard so far kept,
 * until the two minutes are up. That cap is enforced twice, by a timer and by the clock on each
 * restart, because a tab that was backgrounded gets its timers throttled and would otherwise go
 * on listening.
 */
export class WebSpeechInput implements SpeechInput {
  constructor(private readonly host: SpeechHost = globalThis as unknown as SpeechHost) {}

  supported(): boolean {
    return this.ctor() !== undefined;
  }

  start(listener: SpeechListener): SpeechSession {
    const Recognition = this.ctor();
    if (Recognition === undefined) {
      listener.ended('failed');
      return { stop: () => undefined };
    }

    const recognition = new Recognition();
    const startedAt = Date.now();
    let settled = '';
    let live = '';
    let running = true;

    const finish = (reason: SpeechEnd): void => {
      if (!running) return;
      running = false;
      clearTimeout(cap);
      settled = joined(settled, live);
      live = '';
      // Her own Stop arrives mid-phrase: the interim text is the end of the sentence she just
      // said, so it is kept rather than dropped for not having been finalised.
      listener.transcript(settled);
      try {
        recognition.abort();
      } catch {
        // Already stopped, or never started: there is nothing left to abort.
      }
      listener.ended(reason);
    };

    const cap = setTimeout(() => finish('capped'), MAX_SPEECH_MS);

    recognition.lang = SPEECH_LANG;
    recognition.continuous = true;
    recognition.interimResults = true;

    recognition.onresult = (event) => {
      let interim = '';
      for (let index = event.resultIndex; index < event.results.length; index += 1) {
        const result = event.results[index];
        if (result === undefined) continue;
        const text = result[0]?.transcript ?? '';
        if (result.isFinal) settled = joined(settled, text);
        else interim = joined(interim, text);
      }
      live = interim;
      listener.transcript(joined(settled, live));
    };

    recognition.onerror = (event) => {
      // Silence is not a failure, and `aborted` is this file's own `stop()` coming back round.
      if (event.error === 'no-speech' || event.error === 'aborted') return;
      finish('failed');
    };

    recognition.onend = () => {
      if (!running) return;
      settled = joined(settled, live);
      live = '';
      if (Date.now() - startedAt >= MAX_SPEECH_MS) {
        finish('capped');
        return;
      }
      try {
        recognition.start();
      } catch {
        finish('failed');
      }
    };

    try {
      recognition.start();
    } catch {
      finish('failed');
    }

    return { stop: () => finish('stopped') };
  }

  private ctor(): SpeechRecognitionCtor | undefined {
    return this.host.SpeechRecognition ?? this.host.webkitSpeechRecognition;
  }
}

/** One space between two pieces of speech, and none at either end. */
function joined(before: string, after: string): string {
  const next = after.trim();
  if (next === '') return before;
  return before === '' ? next : `${before} ${next}`;
}

/**
 * The implementation the app runs with.
 *
 * A token rather than an injectable class so a spec can hand in a fake without touching the
 * component, and so the one place that knows about `window` is the factory below.
 */
export const SPEECH_INPUT = new InjectionToken<SpeechInput>('hq.speech.input', {
  providedIn: 'root',
  factory: () => new WebSpeechInput(),
});
