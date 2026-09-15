import { DOCUMENT, Injectable, computed, effect, inject, signal } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';

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
