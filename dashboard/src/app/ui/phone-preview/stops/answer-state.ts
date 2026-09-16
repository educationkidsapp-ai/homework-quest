import { Signal, WritableSignal, computed, linkedSignal } from '@angular/core';

/**
 * The two answer state machines every stop component shares, as signals.
 *
 * They are `linkedSignal`s keyed on the stop id, so selecting another stop of the same type in the
 * editor resets the preview instead of carrying the last child's taps across — the editor renders
 * one component per type and swaps the input, where the app builds a new screen each time.
 */

export interface SingleAnswer {
  /** Wrong tiles: dimmed and disabled, never removed (`docs/design.md` §7). */
  readonly dimmed: Signal<readonly string[]>;
  /** The tile that turned out right, once the child has found it. */
  readonly solved: Signal<string | null>;
  readonly done: Signal<boolean>;
  answer(id: string, correctId: string): void;
}

/** Single-answer stops: choice, trueFalse, sequence, count, compare, sound, word, readTap. */
export function singleAnswer(stopId: () => string): SingleAnswer {
  const dimmed = linkedSignal<string, readonly string[]>({ source: stopId, computation: () => [] });
  const solved = linkedSignal<string, string | null>({ source: stopId, computation: () => null });

  return {
    dimmed,
    solved,
    done: computed(() => solved() !== null),
    answer(id, correctId) {
      if (solved() !== null || dimmed().includes(id)) return;
      if (id === correctId) solved.set(id);
      else dimmed.set([...dimmed(), id]);
    },
  };
}

export interface PickAnswer {
  readonly selected: Signal<readonly string[]>;
  readonly lit: Signal<readonly string[]>;
  readonly dimmed: Signal<readonly string[]>;
  readonly done: Signal<boolean>;
  toggle(id: string, limit: number | null): void;
  check(correctIds: readonly string[]): void;
}

/**
 * multiSelect / selectAll: tap tiles, then Check. Right picks stay lit and never reset; wrong ones
 * dim and stay put. The child keeps going until every correct tile is lit — there is no failure
 * state to land in.
 */
export function pickAnswer(stopId: () => string): PickAnswer {
  const selected = linkedSignal<string, readonly string[]>({ source: stopId, computation: () => [] });
  const lit = linkedSignal<string, readonly string[]>({ source: stopId, computation: () => [] });
  const dimmed = linkedSignal<string, readonly string[]>({ source: stopId, computation: () => [] });
  const done = linkedSignal<string, boolean>({ source: stopId, computation: () => false });

  return {
    selected,
    lit,
    dimmed,
    done,
    toggle(id, limit) {
      if (done() || lit().includes(id) || dimmed().includes(id)) return;
      if (selected().includes(id)) {
        selected.set(selected().filter((other) => other !== id));
      } else if (limit === null || selected().length < limit - lit().length) {
        selected.set([...selected(), id]);
      }
    },
    check(correctIds) {
      const right = selected().filter((id) => correctIds.includes(id));
      const wrong = selected().filter((id) => !correctIds.includes(id));
      lit.set([...lit(), ...right]);
      dimmed.set([...dimmed(), ...wrong]);
      selected.set([]);
      if (correctIds.every((id) => lit().includes(id))) done.set(true);
    },
  };
}

/** A cursor — the sentence being read, the word card on screen — that resets with the stop. */
export function cursor(stopId: () => string, initial = 0): WritableSignal<number> {
  return linkedSignal<string, number>({ source: stopId, computation: () => initial });
}

/** Info stops that reveal one card at a time: which ones the child has opened. */
export function opened(stopId: () => string) {
  const ids = linkedSignal<string, readonly string[]>({ source: stopId, computation: () => [] });
  return {
    ids: ids as Signal<readonly string[]>,
    has: (id: string) => ids().includes(id),
    open(id: string) {
      if (!ids().includes(id)) ids.set([...ids(), id]);
    },
  };
}
