import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, isDevMode, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import { provideTransloco } from '@jsverse/transloco';
import { provideApiClient } from './api';
import { routes } from './app.routes';
import { authInterceptor } from './core/http/auth.interceptor';
import { errorInterceptor } from './core/http/error.interceptor';
import { LANGUAGES, provideLanguage } from './core/i18n/language.service';
import { HttpTranslocoLoader } from './core/i18n/transloco-loader';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(
      routes,
      withComponentInputBinding(),
      withInMemoryScrolling({ scrollPositionRestoration: 'top', anchorScrolling: 'enabled' }),
    ),
    // Order matters. `errorInterceptor` is outermost so it never sees a 401 that
    // `authInterceptor` is about to fix with a refresh and a retry — the other way round,
    // every expired access token would flash a red band before quietly succeeding.
    provideHttpClient(withFetch(), withInterceptors([errorInterceptor, authInterceptor])),
    provideApiClient(),
    provideTransloco({
      config: {
        availableLangs: [...LANGUAGES],
        defaultLang: 'en',
        fallbackLang: 'en',
        reRenderOnLangChange: true,
        prodMode: !isDevMode(),
        missingHandler: { logMissingKey: isDevMode() },
      },
      loader: HttpTranslocoLoader,
    }),
    // After `provideTransloco`: it reads the language the person last chose and waits for that
    // bundle, so no screen can paint its keys. See `provideLanguage`.
    provideLanguage(),
  ],
};
