import { ApplicationInitStatus } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import {
  Translation,
  TranslocoLoader,
  TranslocoService,
  TranslocoTestingModule,
  provideTransloco,
} from '@jsverse/transloco';
import { Observable, of } from 'rxjs';
import { delay } from 'rxjs/operators';
import { beforeEach, describe, expect, it } from 'vitest';
import ar from '../../../assets/i18n/ar.json';
import en from '../../../assets/i18n/en.json';
import { LanguageService, provideLanguage } from './language.service';

/**
 * The service does its work in an `effect`, which runs when the application ticks —
 * so every spec ticks after changing the language, exactly as the app does.
 */
function setup(): LanguageService {
  TestBed.configureTestingModule({
    imports: [
      TranslocoTestingModule.forRoot({
        langs: { en, ar },
        translocoConfig: { availableLangs: ['en', 'ar'], defaultLang: 'en' },
        preloadLangs: true,
      }),
    ],
  });
  const service = TestBed.inject(LanguageService);
  TestBed.tick();
  return service;
}

describe('LanguageService', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  it('starts in English, LTR', () => {
    const language = setup();

    expect(language.language()).toBe('en');
    expect(language.dir()).toBe('ltr');
    expect(document.documentElement.lang).toBe('en');
    expect(document.documentElement.dir).toBe('ltr');
  });

  it('switches to Arabic and flips the document direction', () => {
    const language = setup();

    language.use('ar');
    TestBed.tick();

    expect(language.isRtl()).toBe(true);
    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
  });

  it('remembers the choice across reloads', () => {
    const language = setup();
    language.use('ar');
    TestBed.tick();

    TestBed.resetTestingModule();
    const reloaded = setup();

    expect(reloaded.language()).toBe('ar');
  });

  it('toggles between the two languages', () => {
    const language = setup();

    language.toggle();
    expect(language.language()).toBe('ar');

    language.toggle();
    expect(language.language()).toBe('en');
  });
});

/**
 * The bug this closes: with `ar` in localStorage, a hard reload of a deep authenticated route
 * painted the nav rail from `screens.ts` before `ar.json` had landed, so it read `nav.thisWeek`
 * — `translate()` answers with the key when the bundle is not in yet, and a `computed()` holding
 * that key only lets go when `activeLang()` ticks. `provideLanguage` waits instead.
 */
class SlowLoader implements TranslocoLoader {
  static delayMs = 50;
  getTranslation(lang: string): Observable<Translation> {
    return of((lang === 'ar' ? ar : en) as Translation).pipe(delay(SlowLoader.delayMs));
  }
}

describe('provideLanguage', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  it('has the stored language loaded before the app is allowed to render', async () => {
    localStorage.setItem('hq.language', 'ar');
    TestBed.configureTestingModule({
      providers: [
        provideTransloco({
          config: { availableLangs: ['en', 'ar'], defaultLang: 'en', reRenderOnLangChange: true },
          loader: SlowLoader,
        }),
        provideLanguage(),
      ],
    });

    await TestBed.inject(ApplicationInitStatus).donePromise;

    const transloco = TestBed.inject(TranslocoService);
    expect(transloco.getActiveLang()).toBe('ar');
    // The assertion that matters: the *first* imperative read already answers in Arabic. Before
    // the initializer this returned the key itself for as long as the download took.
    expect(transloco.translate('nav.thisWeek')).toBe(ar.nav.thisWeek);
  });

  it('lets the app start even when the language file cannot be fetched', async () => {
    localStorage.setItem('hq.language', 'ar');
    TestBed.configureTestingModule({
      providers: [
        provideTransloco({
          config: { availableLangs: ['en', 'ar'], defaultLang: 'en' },
          loader: class {
            getTranslation(): Observable<Translation> {
              return new Observable((subscriber) => subscriber.error(new Error('offline')));
            }
          },
        }),
        provideLanguage(),
      ],
    });

    // A blank page is a worse answer than a page of keys: the person can still sign out of one.
    await expect(TestBed.inject(ApplicationInitStatus).donePromise).resolves.toBeUndefined();
  });
});
