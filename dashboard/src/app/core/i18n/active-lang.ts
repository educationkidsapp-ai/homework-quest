import { Signal, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { TranslocoService } from '@jsverse/transloco';
import { merge } from 'rxjs';
import { filter, map } from 'rxjs/operators';

/**
 * The active language, as a signal.
 *
 * The `| transloco` pipe re-renders on a language change by itself, but a `computed()` that
 * calls `TranslocoService.translate()` does not: nothing it read changed, so the cached English
 * sentence stands and half the screen flips while the other half does not. Depending on this
 * signal is what makes such a computed part of the change.
 *
 * Call it in an injection context (a field initializer):
 *
 *     private readonly lang = activeLang();
 *     readonly greeting = computed(() => (this.lang(), this.transloco.translate('home.greeting')));
 *
 * It also ticks when a **translation file finishes loading**, and that is not a nicety. The
 * bundle is loaded over HTTP, so `translate()` called before it lands answers with the key
 * itself; the language has not changed, so `langChanges$` never fires, and the computed keeps
 * the key forever. N2.2 found it the honest way — the nav rail read "nav.thisWeek" in a
 * screenshot, because This week's own requests won the race the Admin screens had been losing
 * safely. `equal: () => false` is what makes the second emission of the same language name
 * notify at all.
 */
export function activeLang(): Signal<string> {
  const transloco = inject(TranslocoService);
  const loaded = transloco.events$.pipe(
    filter((event) => event.type === 'translationLoadSuccess'),
    map(() => transloco.getActiveLang()),
  );
  return toSignal(merge(transloco.langChanges$, loaded), {
    initialValue: transloco.getActiveLang(),
    equal: () => false,
  });
}
