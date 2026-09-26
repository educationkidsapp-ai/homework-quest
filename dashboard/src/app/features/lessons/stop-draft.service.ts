import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import { Subject, TimeoutError, timeout } from 'rxjs';
import { type Stop, apiErrorOf } from '../../api';
import { LessonApiService } from './lesson-api.service';
import { stopFromTextBody } from './lessons.models';

/**
 * The client stops waiting for Prompt D here and aborts the request.
 *
 * The server's own read deadline is 120 s and a worst-case `from-text` has taken minutes; a row
 * that says "the assistant is writing" for four of them is indistinguishable from a broken one.
 * Unsubscribing aborts the XHR, so the template stop is left exactly as it was — which is what
 * Retry re-sends.
 */
export const DRAFT_TIMEOUT_MS = 90_000;

/** `writing` — `from-text` is in flight. `error` — it was refused, and the row offers both ways out. */
export type DraftState = 'writing' | 'error';

export interface StopDraft {
  readonly stopId: string;
  readonly lessonId: string;
  readonly playId: string;
  /** What she typed, kept so Retry sends the same words rather than asking for them again. */
  readonly text: string;
  readonly state: DraftState;
  /** Why it was refused, in the sentence the row and the toast share. `null` while writing. */
  readonly reason: string | null;
}

export interface DraftStopEvent {
  readonly lessonId: string;
  readonly playId: string;
  readonly stop: Stop;
}

/**
 * E4a: the assistant writes a new question in the background.
 *
 * Both calls behind "Save the question" used to be awaited by the Add-question sheet, which
 * therefore sat open and disabled for as long as the model took — up to minutes for one stop, and
 * a teacher writing ten of them waited ten times. Here the sheet closes on the click and the work
 * outlives it: `POST …/plays/{id}/stops` makes the template, the level's list gains that stop
 * straight away, and `POST …/stops/{id}/from-text` runs on from a **root** service, so leaving
 * the lesson (or the tab, or the level) does not cancel it and coming back finds the row's state
 * still here.
 *
 * A refusal keeps the stop rather than deleting it: her title and her words are in it, Retry
 * re-sends exactly the text that was refused, and Remove is the one call that throws it away.
 * That is the opposite of the old behaviour, which deleted the template under a still-open form —
 * defensible while the form held her words, and wrong once the row is where she can see them.
 */
@Injectable({ providedIn: 'root' })
export class StopDraftService {
  private readonly api = inject(LessonApiService);
  private readonly transloco = inject(TranslocoService);

  private readonly drafts = signal<ReadonlyMap<string, StopDraft>>(new Map());

  /** The template stop exists: append it to that level's list, selected. */
  readonly created$ = new Subject<DraftStopEvent>();
  /** The assistant finished: replace that one stop, and nothing else on the page. */
  readonly written$ = new Subject<DraftStopEvent>();
  /** "Remove" took the template stop away again. */
  readonly removed$ = new Subject<{ readonly lessonId: string; readonly stopId: string }>();
  /**
   * One sentence for the lesson page to say out loud, with the lesson it is about.
   *
   * Without the id a refusal on lesson A raised the toast on whatever lesson happened to be open
   * — the other three events were filtered and this one was not.
   */
  readonly said$ = new Subject<{ readonly lessonId: string; readonly message: string }>();

  readonly pending = computed(() => this.drafts().size);

  draftOf(stopId: string): StopDraft | null {
    return this.drafts().get(stopId) ?? null;
  }

  /** True while this stop's words are with the model — the row's busy state, not the page's. */
  writing(stopId: string): boolean {
    return this.drafts().get(stopId)?.state === 'writing';
  }

  /**
   * Save a new question: the template first, then the conversion.
   *
   * A create that fails leaves nothing — no stop, so no row — and `POST …/stops` is not
   * `silentErrors()`, so the error interceptor's red band has already said why. From the moment
   * the create lands, the row owns everything else.
   */
  add(lessonId: string, playId: string, body: string, text: string): void {
    this.api.addStop(playId, body).subscribe({
      next: (stop) => {
        this.created$.next({ lessonId, playId, stop });
        this.put({ stopId: stop.id, lessonId, playId, text, state: 'writing', reason: null });
        this.write(stop.id);
      },
      error: () => undefined,
    });
  }

  /**
   * E4b: a stop that is already finished — one request, and no draft row at all.
   *
   * The sheet built the whole document from its fields (`structured-stop.ts`), so there is nothing
   * for the assistant to do and nothing to wait for: the create lands, `created$` puts the stop in
   * the level's list selected, and a refusal is the error interceptor's red band as usual. It goes
   * through this service rather than the page only so that the "a new stop appeared" path is one
   * path, whichever way it was written.
   */
  addNow(lessonId: string, playId: string, body: string): void {
    this.api.addStop(playId, body).subscribe({
      next: (stop) => this.created$.next({ lessonId, playId, stop }),
      error: () => undefined,
    });
  }

  /** The same words again. Only from an errored row — a second run over a live one would race it. */
  retry(stopId: string): void {
    const draft = this.drafts().get(stopId);
    if (!draft || draft.state === 'writing') return;
    this.put({ ...draft, state: 'writing', reason: null });
    this.write(stopId);
  }

  /** Throw the template stop away. The row goes with it; the level keeps its other stops. */
  remove(stopId: string): void {
    const draft = this.drafts().get(stopId);
    if (!draft || draft.state === 'writing') return;
    this.api.deleteStop(stopId).subscribe({
      next: () => {
        this.drop(stopId);
        this.removed$.next({ lessonId: draft.lessonId, stopId });
      },
      // The row stays as it was: `DELETE …/stops/{id}` raises its own band, and a Remove that
      // did not happen must not look as though it did.
      error: () => undefined,
    });
  }

  private write(stopId: string): void {
    const draft = this.drafts().get(stopId);
    if (!draft) return;
    this.api
      .stopFromText(stopId, stopFromTextBody(draft.text))
      .pipe(timeout(DRAFT_TIMEOUT_MS))
      .subscribe({
        next: (stop) => {
          this.drop(stopId);
          this.written$.next({ lessonId: draft.lessonId, playId: draft.playId, stop });
        },
        error: (cause: unknown) => {
          const reason = this.reasonOf(cause);
          this.put({ ...draft, state: 'error', reason });
          this.said$.next({ lessonId: draft.lessonId, message: reason });
        },
      });
  }

  /**
   * The two answers this flow explains itself, and the generic one for the rest.
   *
   * 422 is Prompt D failing twice on this wording; 400 is the lesson still generating, which the
   * server refuses stop edits during. `stopFromText` is `silentErrors()`, so neither reached a
   * band — they reach the row instead, and the toast repeats the row.
   */
  private reasonOf(cause: unknown): string {
    if (cause instanceof TimeoutError) return this.t('lessons.detail.draft.timedOut');
    if (cause instanceof HttpErrorResponse) {
      if (cause.status === 422) return this.t('lessons.detail.editor.text.rephrase');
      if (cause.status === 400) return this.t('lessons.detail.editor.text.generating');
    }
    return apiErrorOf(cause)?.message ?? this.t('band.unreachable');
  }

  private put(draft: StopDraft): void {
    this.drafts.update((current) => new Map(current).set(draft.stopId, draft));
  }

  private drop(stopId: string): void {
    this.drafts.update((current) => {
      const next = new Map(current);
      next.delete(stopId);
      return next;
    });
  }

  private t(key: string): string {
    return this.transloco.translate<string>(key);
  }
}
