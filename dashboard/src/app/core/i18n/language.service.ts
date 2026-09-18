import {
  DOCUMENT,
  EnvironmentProviders,
  Injectable,
  computed,
  effect,
  inject,
  provideAppInitializer,
  signal,
} from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';

export const LANGUAGES = ['en', 'ar'] as const;
export type Language = (typeof LANGUAGES)[number];

const STORAGE_KEY = 'hq.language';
const RTL: ReadonlySet<Language> = new Set<Language>(['ar']);

function isLanguage(value: string | null): value is Language {
  return value !== null && (LANGUAGES as readonly string[]).includes(value);
}

/**
 * The one place the UI language lives.
 *
 * Sets `lang` and `dir` on `<html>` (every component uses logical properties, so
 * that attribute is the whole of RTL support), persists the choice in
 * localStorage, and keeps Transloco's active language in step.
 */
@Injectable({ providedIn: 'root' })
export class LanguageService {
  private readonly doc = inject(DOCUMENT);
  private readonly transloco = inject(TranslocoService);
  private readonly current = signal<Language>('en');

  readonly language = this.current.asReadonly();
  readonly dir = computed<'ltr' | 'rtl'>(() => (RTL.has(this.current()) ? 'rtl' : 'ltr'));
  readonly isRtl = computed(() => this.dir() === 'rtl');

  constructor() {
    this.current.set(this.restore());
    effect(() => {
      const language = this.current();
      const root = this.doc.documentElement;
      root.lang = language;
      root.dir = this.dir();
      this.transloco.setActiveLang(language);
      this.persist(language);
    });
  }

  use(language: Language): void {
    this.current.set(language);
  }

  /** The styleguide's toggle and the profile screen both need a plain flip. */
  toggle(): void {
    this.current.update((language) => (language === 'en' ? 'ar' : 'en'));
  }

  private restore(): Language {
    const stored = this.storage()?.getItem(STORAGE_KEY) ?? null;
    if (isLanguage(stored)) return stored;
    const browser = this.doc.defaultView?.navigator.language?.slice(0, 2) ?? 'en';
    return isLanguage(browser) ? browser : 'en';
  }

  private persist(language: Language): void {
    this.storage()?.setItem(STORAGE_KEY, language);
  }

  /** localStorage throws in private-mode Safari and is absent when prerendering. */
  private storage(): Storage | null {
    try {
      return this.doc.defaultView?.localStorage ?? null;
    } catch {
      return null;
    }
  }
}

/**
 * Load the stored language **before** the app renders anything.
 *
 * `TranslocoService.setActiveLang` does not fetch: the bundle arrives when something subscribes,
 * which is the first `| transloco` pipe. Everything rendered before it lands shows the key
 * itself, and a `computed()` calling `translate()` caches that key until `activeLang()` ticks it
 * loose. On a shallow route the gap is invisible; on a hard reload of a deep authenticated route
 * — `/teacher/lessons/{id}`, where `/me`, the lazy chunk and the lesson all have to land first —
 * the nav rail is painted from `screens.ts` and reads `nav.thisWeek` for as long as the download
 * takes. A Playwright probe with `ar.json` held back 1.5 s showed exactly that.
 *
 * Making it an initializer closes the window instead of narrowing it: Angular waits for the
 * returned promise before the first render, so there is no frame in which the active language
 * has no translations. `activeLang()` stays — it is still what carries a *later* switch into a
 * `computed()` — but nothing depends on it to recover from the first paint any more.
 */
export function provideLanguage(): EnvironmentProviders {
  return provideAppInitializer(async () => {
    const language = inject(LanguageService).language();
    const transloco = inject(TranslocoService);
    transloco.setActiveLang(language);
    // A failed load must not block the app: Transloco falls back to the key, which is what
    // would have happened anyway, and the red band for the asset is not worth a blank page.
    await firstValueFrom(transloco.load(language)).catch(() => undefined);
  });
}
