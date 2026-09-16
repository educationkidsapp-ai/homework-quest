import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { BASE_PATH } from '../../api';
import { SCHOOL_THEME, TEACHER_USER } from '../../../testing/fixtures';
import { AuthService } from '../auth/auth.service';
import { SessionStore } from '../auth/session.store';
import { ThemeService } from './theme.service';

/** The custom property each theme field lands on. Mirrors `THEME_PROPERTIES`. */
const MAPPING: readonly (readonly [keyof typeof SCHOOL_THEME, string])[] = [
  ['primary', '--hq-color-surface'],
  ['primaryInk', '--hq-color-ink'],
  ['accent', '--hq-color-accent'],
  ['ground', '--hq-color-bg'],
  ['softBorder', '--hq-color-rule'],
  ['mascotColor', '--hq-mascot-color-body'],
];

describe('ThemeService', () => {
  let theme: ThemeService;
  let backend: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute('style');
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: BASE_PATH, useValue: '' },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    theme = TestBed.inject(ThemeService);
  });

  it('paints every mapped colour onto the document and nothing else', async () => {
    TestBed.inject(SessionStore).set({ token: 'a', refreshToken: 'r' });
    TestBed.inject(AuthService).loadMe().subscribe();
    backend.expectOne('/me').flush(TEACHER_USER);
    theme.theme();
    TestBed.tick();

    backend.expectOne('/schools/school-a/theme').flush(SCHOOL_THEME);
    await Promise.resolve();
    TestBed.tick();

    const style = document.documentElement.style;
    for (const [field, property] of MAPPING)
      expect(style.getPropertyValue(property)).toBe(SCHOOL_THEME[field]);
    // The type scale, spacing and motion are the design system, not a school's to override.
    expect(style.getPropertyValue('--hq-font-title-size')).toBe('');
    expect(style.getPropertyValue('--hq-size-nav-width')).toBe('');
  });

  it('exposes the school name and logo for the header, with §A order', async () => {
    TestBed.inject(SessionStore).set({ token: 'a', refreshToken: 'r' });
    TestBed.inject(AuthService).loadMe().subscribe();
    backend.expectOne('/me').flush(TEACHER_USER);
    theme.theme();
    TestBed.tick();
    backend.expectOne('/schools/school-a/theme').flush(SCHOOL_THEME);
    await Promise.resolve();
    TestBed.tick();

    expect(theme.appName()).toBe('Al Noor');
    expect(theme.logoUrl()).toBe('https://example.test/alnoor.png');
  });

  it('falls back to the generated defaults when a theme is removed', () => {
    theme.apply(SCHOOL_THEME);
    expect(document.documentElement.style.getPropertyValue('--hq-color-accent')).toBe('#0B7A5A');

    theme.apply(null);

    // Removed, not set to a second hard-coded palette: `:root` from the generated tokens wins.
    for (const [, property] of MAPPING)
      expect(document.documentElement.style.getPropertyValue(property)).toBe('');
  });

  it('sets the transition length so a colour change reads as one movement', () => {
    theme.apply(SCHOOL_THEME);

    expect(document.documentElement.style.getPropertyValue('--hq-theme-transition')).toBe('300ms');
  });

  it('ignores a blank colour rather than writing an empty custom property', () => {
    theme.apply({ ...SCHOOL_THEME, accent: '' });

    expect(document.documentElement.style.getPropertyValue('--hq-color-accent')).toBe('');
    expect(document.documentElement.style.getPropertyValue('--hq-color-bg')).toBe(SCHOOL_THEME.ground);
  });
});
