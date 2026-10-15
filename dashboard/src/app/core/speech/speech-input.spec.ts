import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  MAX_SPEECH_MS,
  SPEECH_LANG,
  type SpeechEnd,
  type SpeechRecognitionLike,
  type SpeechResultEventLike,
  WebSpeechInput,
} from './speech-input';

/**
 * A `SpeechRecognition` that records what was done to it and fires its own events.
 *
 * The real one needs a microphone, a permission grant and, in Chrome, Google's servers — none of
 * which belongs in a unit test. What is under test is the three things this file decides by
 * itself: that it restarts after a silence, that it stops at two minutes, and that a browser
 * without recognition says so.
 */
class FakeRecognition implements SpeechRecognitionLike {
  static readonly made: FakeRecognition[] = [];

  lang = '';
  continuous = false;
  interimResults = false;
  onresult: ((event: SpeechResultEventLike) => void) | null = null;
  onerror: ((event: { readonly error: string }) => void) | null = null;
  onend: (() => void) | null = null;

  starts = 0;
  aborts = 0;

  constructor() {
    FakeRecognition.made.push(this);
  }

  start(): void {
    this.starts += 1;
  }

  stop(): void {
    this.onend?.();
  }

  abort(): void {
    this.aborts += 1;
  }

  /** One result, as the browser hands them over: a list starting at `resultIndex`. */
  hear(text: string, isFinal: boolean): void {
    this.onresult?.({
      resultIndex: 0,
      results: { length: 1, 0: { isFinal, length: 1, 0: { transcript: text } } },
    });
  }
}

describe('speech input, on the device', () => {
  let heard: string[];
  let ends: SpeechEnd[];

  function listener() {
    return {
      transcript: (text: string) => void heard.push(text),
      ended: (reason: SpeechEnd) => void ends.push(reason),
    };
  }

  function only(): FakeRecognition {
    expect(FakeRecognition.made).toHaveLength(1);
    return FakeRecognition.made[0]!;
  }

  beforeEach(() => {
    FakeRecognition.made.length = 0;
    heard = [];
    ends = [];
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  /** Arabic, continuous and with interim results — the live transcript depends on all three. */
  it('listens in Saudi Arabic, continuously, with interim results', () => {
    new WebSpeechInput({ SpeechRecognition: FakeRecognition }).start(listener());

    const recognition = only();
    expect(recognition.lang).toBe(SPEECH_LANG);
    expect(recognition.continuous).toBe(true);
    expect(recognition.interimResults).toBe(true);
    expect(recognition.starts).toBe(1);
  });

  /**
   * The behaviour this wrapper exists for. Continuous recognition ends on its own after a few
   * seconds of silence, and a leader thinking about her next sentence has not finished talking —
   * so an `end` while she is still recording starts it again, keeping what was heard.
   */
  it('restarts itself after a silence, and keeps the text heard so far', () => {
    new WebSpeechInput({ SpeechRecognition: FakeRecognition }).start(listener());
    const recognition = only();

    recognition.hear('السلام عليكم', true);
    expect(heard.at(-1)).toBe('السلام عليكم');

    // The browser gave up on the pause. Nothing ended, and the same recognition is listening again.
    recognition.onend?.();
    expect(ends).toEqual([]);
    expect(recognition.starts).toBe(2);
    expect(FakeRecognition.made).toHaveLength(1);

    // And the second stretch is appended to the first rather than replacing it.
    recognition.hear('غدًا رحلة', true);
    expect(heard.at(-1)).toBe('السلام عليكم غدًا رحلة');
  });

  it('stops by itself at two minutes', () => {
    new WebSpeechInput({ SpeechRecognition: FakeRecognition }).start(listener());
    const recognition = only();
    recognition.hear('كلام', true);

    vi.advanceTimersByTime(MAX_SPEECH_MS - 1);
    expect(ends).toEqual([]);

    vi.advanceTimersByTime(1);
    expect(ends).toEqual(['capped']);
    expect(recognition.aborts).toBe(1);

    // The cap is final: the `end` the abort provokes must not start a third stretch.
    recognition.onend?.();
    expect(recognition.starts).toBe(1);
    expect(ends).toEqual(['capped']);
  });

  /** Her own Stop arrives mid-phrase, so the interim text is the end of her sentence. */
  it('keeps the unfinalised tail when she stops, and ends once', () => {
    const session = new WebSpeechInput({ SpeechRecognition: FakeRecognition }).start(listener());
    const recognition = only();
    recognition.hear('اجتماع', true);
    recognition.hear('الخميس', false);

    session.stop();

    expect(heard.at(-1)).toBe('اجتماع الخميس');
    expect(ends).toEqual(['stopped']);

    // Idempotent, and a late `end` from the browser changes nothing.
    session.stop();
    recognition.onend?.();
    expect(ends).toEqual(['stopped']);
    expect(recognition.starts).toBe(1);
  });

  /** A device failure is its own answer: the screen says so in a red band rather than preparing. */
  it('ends as failed on a real recognition error, and ignores silence', () => {
    new WebSpeechInput({ SpeechRecognition: FakeRecognition }).start(listener());
    const recognition = only();

    recognition.onerror?.({ error: 'no-speech' });
    expect(ends).toEqual([]);

    recognition.onerror?.({ error: 'not-allowed' });
    expect(ends).toEqual(['failed']);
  });

  /** Firefox has no speech recognition at all, which is what hides the whole feature there. */
  it('is unsupported where the browser has no recognition', () => {
    expect(new WebSpeechInput({}).supported()).toBe(false);
    // Chrome and Safari still publish it prefixed.
    expect(new WebSpeechInput({ webkitSpeechRecognition: FakeRecognition }).supported()).toBe(true);
    expect(new WebSpeechInput({ SpeechRecognition: FakeRecognition }).supported()).toBe(true);
  });
});
