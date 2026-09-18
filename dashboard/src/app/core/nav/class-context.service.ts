import { Injectable, signal } from '@angular/core';

/** The class a teacher is currently inside, as the rail names it. */
export interface ClassContext {
  readonly id: string;
  /** Already-formatted — "1A · Math". The screen knows the language; the rail does not. */
  readonly label: string;
  readonly link: string;
}

/**
 * The rail's **third item** (`docs/teacher-flow.md` §5).
 *
 * A teacher's rail is two items — This week and My classes — until she opens a class, and then
 * the class itself joins them for as long as she is in it. §5 asks for that because the class
 * page has four tabs and a lesson page hanging off it: without the item, "back to the class"
 * is browser Back pressed an unknown number of times.
 *
 * A one-signal service rather than the shell reading the URL, because the rail's label is
 * "1A · Math" and only the screen that fetched the class knows those words. The class page sets
 * it on arrival and clears it on destroy, so leaving takes the item with it — including by Back,
 * by a rail click, and by signing out.
 */
@Injectable({ providedIn: 'root' })
export class ClassContextService {
  private readonly context = signal<ClassContext | null>(null);

  readonly current = this.context.asReadonly();

  set(context: ClassContext): void {
    this.context.set(context);
  }

  clear(): void {
    this.context.set(null);
  }
}
