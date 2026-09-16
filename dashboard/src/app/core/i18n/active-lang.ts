import { Signal, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { TranslocoService } from '@jsverse/transloco';

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
 */
export function activeLang(): Signal<string> {
  const transloco = inject(TranslocoService);
  return toSignal(transloco.langChanges$, { initialValue: transloco.getActiveLang() });
}
