import { Injectable, signal } from '@angular/core';

/**
 * The exam the lesson editor is currently showing, for the parts of that editor that are not
 * the settings card.
 *
 * There is exactly one consumer and one producer: the settings card puts §8's sentence here,
 * already spelled in her language and the school's clock, and `lesson.page.ts` reads it onto the
 * publish confirmation. One sentence and not the whole `ExamSettings` row — the page has no use
 * for the rest of it, and a field nothing reads is a field that quietly goes wrong.
 *
 * **The sentence travels ready-made** rather than as two timestamps the page would format for
 * itself. Formatting it there would mean the lesson editor injecting `PlatformService` for the
 * school's timezone on every lesson, exam or not, which is one request for a sentence most
 * lessons never show.
 *
 * **Why a service and not an input.** The card lives inside the editor's template, and the
 * publish band is a sibling of it built from computeds on the page. Passing the settings
 * upwards would mean the page fetching them itself — a second `GET /teacher/classes/{id}/exams`
 * on every lesson view, exam or not — or an output plumbed through two templates. `ClassContext`
 * solves the same problem for the rail the same way. Set on arrival, cleared on destroy.
 */
@Injectable({ providedIn: 'root' })
export class ExamContextService {
  private readonly line = signal('');

  /** "Children see it only between 18 Sep, 09:00 and 18 Sep, 10:00." */
  readonly sentence = this.line.asReadonly();

  set(sentence: string): void {
    this.line.set(sentence);
  }

  clear(): void {
    this.line.set('');
  }
}
