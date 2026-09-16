import { Injectable, signal } from '@angular/core';

export interface UndoOffer {
  /** What just happened, already translated: "Lesson deleted". */
  readonly message: string;
  /** Puts the change back. Called only if the person presses Undo inside the window. */
  readonly undo: () => void;
  /** Runs when the window closes without an Undo — where an optimistic change is committed. */
  readonly commit?: () => void;
}

/**
 * The Undo strip: ten seconds to change your mind.
 *
 * §7's rule is that only destructive actions confirm (with the red band) and **everything
 * else is undoable**. A confirmation dialog asks a question before the person can see the
 * result; an undo strip shows them the result and offers the way back, which is both faster
 * for the ninety-nine times they meant it and clearer for the one time they did not.
 *
 * One offer at a time: a second action commits the first. Stacking undos would mean the
 * strip's "Undo" no longer names a single thing.
 */
@Injectable({ providedIn: 'root' })
export class UndoService {
  private readonly current = signal<UndoOffer | null>(null);

  readonly offer = this.current.asReadonly();

  offerUndo(offer: UndoOffer): void {
    this.commitPending();
    this.current.set(offer);
  }

  /** The person pressed Undo. */
  undo(): void {
    const offer = this.current();
    this.current.set(null);
    offer?.undo();
  }

  /** The ten seconds ran out. */
  expire(): void {
    this.commitPending();
  }

  private commitPending(): void {
    const offer = this.current();
    this.current.set(null);
    offer?.commit?.();
  }
}
