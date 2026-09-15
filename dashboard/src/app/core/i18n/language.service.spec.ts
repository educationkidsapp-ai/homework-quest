import { TestBed } from '@angular/core/testing';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { beforeEach, describe, expect, it } from 'vitest';
import ar from '../../../assets/i18n/ar.json';
import en from '../../../assets/i18n/en.json';
import { LanguageService } from './language.service';

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
