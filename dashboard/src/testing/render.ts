import { Type } from '@angular/core';
import { RenderComponentOptions, render } from '@testing-library/angular';
import { TranslocoTestingModule, TranslocoTestingOptions } from '@jsverse/transloco';
import ar from '../assets/i18n/ar.json';
import en from '../assets/i18n/en.json';

/** Exported for specs that build their own `TestBed.configureTestingModule` (no `renderHq`). */
export function translocoTesting(options: TranslocoTestingOptions = {}) {
  return TranslocoTestingModule.forRoot({
    langs: { en, ar },
    translocoConfig: { availableLangs: ['en', 'ar'], defaultLang: 'en', reRenderOnLangChange: true },
    preloadLangs: true,
    ...options,
  });
}

/**
 * Renders a component with the real translations loaded.
 *
 * Specs assert on the strings users actually see rather than on translation keys — which
 * is also how a missing key gets caught before it reaches a screenshot.
 */
export function renderHq<T>(component: Type<T>, options: RenderComponentOptions<T> = {}) {
  return render(component, {
    ...options,
    imports: [translocoTesting(), ...(options.imports ?? [])],
  });
}
