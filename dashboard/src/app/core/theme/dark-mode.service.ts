import { DOCUMENT, Injectable, computed, effect, inject, signal } from '@angular/core';

/** The two schemes. There is no `system` option: §5 of the spec says default light. */
export type ColorScheme = 'light' | 'dark';

/**
 * `theme`, not `hq.theme` — the spec fixes the key (§5) and it is the same one TailAdmin's own
 * inline script uses, so a browser that has both open does not disagree with itself.
 */
const STORAGE_KEY = 'theme';

/** The class `_theme.scss` keys its dark palette on, and TailAdmin's `dark:` variant. */
const DARK_CLASS = 'dark';

/**
 * Reads the stored scheme, tolerating a browser that will not give us storage at all.
 *
 * Safari in Lockdown Mode, a third-party-cookie block on an embedded page and a full disk all
 * make `localStorage` *throw* on access rather than return null. The dashboard's colour scheme
 * is not worth an unhandled exception during bootstrap.
 */
export function storedColorScheme(): ColorScheme | null {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    return stored === 'dark' || stored === 'light' ? stored : null;
  } catch {
    return null;
  }
}

/**
 * Puts the stored scheme on `<html>` before Angular bootstraps.
 *
 * Called from `main.ts` rather than from an initializer, because an initializer runs after the
 * first paint of `index.html` and the whole point is that a person who chose dark never sees a
 * white page flash. It is the same two lines the service runs later; doing it twice is free.
 */
export function applyStoredColorScheme(doc: Document = document): void {
  const scheme = storedColorScheme() ?? 'light';
  doc.documentElement.classList.toggle(DARK_CLASS, scheme === 'dark');
  doc.documentElement.style.colorScheme = scheme;
}

/**
 * Light or dark, chosen by the person and remembered.
 *
 * **How this composes with a school's theme.** `ThemeService` writes a school's colours onto
 * `<html>` as inline custom properties. In dark mode `_theme.scss` redefines four of those
 * roles — surface, ground, rule and ink — with `!important`, which is the one thing that beats
 * an inline declaration, so the school's choices for them do not apply. Its **accent does**:
 * that declaration is not important, so the inline value wins as usual. That is the intended
 * rule, not a side effect. A school picks its colours against a white page; on `#101828` its
 * ground and ink would be unreadable rather than branded, while its accent is the one part of
 * the identity that survives the change of ground.
 */
@Injectable({ providedIn: 'root' })
export class DarkModeService {
  private readonly doc = inject(DOCUMENT);
  private readonly current = signal<ColorScheme>(storedColorScheme() ?? 'light');

  readonly scheme = this.current.asReadonly();
  readonly isDark = computed(() => this.current() === 'dark');

  constructor() {
    // An effect rather than a write inside `set()`: the class on `<html>` is a projection of the
    // signal, so there is exactly one place that can put it out of step with the signal — none.
    effect(() => this.paint(this.current()));
  }

  set(scheme: ColorScheme): void {
    this.current.set(scheme);
    try {
      localStorage.setItem(STORAGE_KEY, scheme);
    } catch {
      // A scheme that cannot be remembered is still a scheme that applies for this session.
    }
  }

  toggle(): void {
    this.set(this.isDark() ? 'light' : 'dark');
  }

  private paint(scheme: ColorScheme): void {
    const root = this.doc.documentElement;
    root.classList.toggle(DARK_CLASS, scheme === 'dark');
    // Form controls, scrollbars and the canvas behind the page are the UA's to draw, and it
    // only knows which way round to draw them from this.
    root.style.colorScheme = scheme;
  }
}
