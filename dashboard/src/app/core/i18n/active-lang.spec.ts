import { Component, computed, inject } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TranslocoService, TranslocoTestingModule } from '@jsverse/transloco';
import { describe, expect, it } from 'vitest';
import ar from '../../../assets/i18n/ar.json';
import en from '../../../assets/i18n/en.json';
import { activeLang } from './active-lang';

/**
 * A `computed()` that calls `TranslocoService.translate()` without reading a reactive
 * dependency never re-runs on a language switch: nothing it read changed, Angular's memoized
 * value stands, and the screen shows English (or the previous language) under an Arabic UI.
 * `activeLang()` is the fix — depending on it is what makes such a computed part of the
 * change. This mirrors the real bug found in `stub.page.ts` and `profile.page.ts`: a
 * `computed()` deriving a string from `translate()` with no signal read to trigger it.
 */
@Component({ selector: 'hq-test-stale', template: '', standalone: true })
class StaleLabelHost {
  private readonly transloco = inject(TranslocoService);
  readonly label = computed(() => this.transloco.translate('stub.title'));
}

@Component({ selector: 'hq-test-fixed', template: '', standalone: true })
class FixedLabelHost {
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();
  readonly label = computed(() => {
    this.lang();
    return this.transloco.translate('stub.title');
  });
}

function setup() {
  TestBed.configureTestingModule({
    imports: [
      TranslocoTestingModule.forRoot({
        langs: { en, ar },
        translocoConfig: { availableLangs: ['en', 'ar'], defaultLang: 'en' },
        preloadLangs: true,
      }),
    ],
  });
  return TestBed.inject(TranslocoService);
}

describe('activeLang', () => {
  it('a computed() with no reactive dependency freezes on the pre-switch translation', () => {
    const transloco = setup();
    const host = TestBed.createComponent(StaleLabelHost).componentInstance;
    const english = host.label();

    transloco.setActiveLang('ar');
    TestBed.tick();

    expect(host.label()).toBe(english);
    expect(host.label()).not.toBe(ar['stub']['title']);
  });

  it('depending on activeLang() picks up the switch', () => {
    const transloco = setup();
    const host = TestBed.createComponent(FixedLabelHost).componentInstance;
    expect(host.label()).toBe(en['stub']['title']);

    transloco.setActiveLang('ar');
    TestBed.tick();

    expect(host.label()).toBe(ar['stub']['title']);
  });
});
