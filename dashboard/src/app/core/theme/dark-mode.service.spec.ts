import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DarkModeService, applyStoredColorScheme, storedColorScheme } from './dark-mode.service';

function service(): DarkModeService {
  return TestBed.inject(DarkModeService);
}

describe('DarkModeService', () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.classList.remove('dark');
    document.documentElement.style.colorScheme = '';
    TestBed.resetTestingModule();
  });

  afterEach(() => vi.restoreAllMocks());

  it('starts light when nothing has been chosen', () => {
    expect(service().scheme()).toBe('light');
    TestBed.tick();
    expect(document.documentElement).not.toHaveClass('dark');
  });

  it('puts the dark class and the UA colour-scheme on the document', () => {
    service().set('dark');
    TestBed.tick();

    expect(document.documentElement).toHaveClass('dark');
    expect(document.documentElement.style.colorScheme).toBe('dark');
  });

  it('takes the class off again on the way back', () => {
    const scheme = service();
    scheme.set('dark');
    TestBed.tick();
    scheme.toggle();
    TestBed.tick();

    expect(scheme.scheme()).toBe('light');
    expect(document.documentElement).not.toHaveClass('dark');
  });

  it('remembers the choice under the key the spec fixes', () => {
    service().set('dark');

    expect(localStorage.getItem('theme')).toBe('dark');
    expect(storedColorScheme()).toBe('dark');
  });

  it('reads the stored choice back on the next visit', () => {
    localStorage.setItem('theme', 'dark');

    expect(service().isDark()).toBe(true);
  });

  it('ignores a stored value that is not a scheme', () => {
    localStorage.setItem('theme', 'aubergine');

    expect(service().scheme()).toBe('light');
  });

  // Lockdown Mode, a blocked third-party context and a full disk all make these *throw*
  // rather than return null, and a colour scheme is not worth failing bootstrap over.
  it('still applies a scheme when storage refuses to be written', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('QuotaExceededError');
    });

    const scheme = service();
    expect(() => scheme.set('dark')).not.toThrow();
    TestBed.tick();
    expect(document.documentElement).toHaveClass('dark');
  });

  it('starts light when storage refuses to be read', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('SecurityError');
    });

    expect(storedColorScheme()).toBeNull();
    expect(service().scheme()).toBe('light');
  });

  it('paints the stored scheme before bootstrap, without Angular', () => {
    localStorage.setItem('theme', 'dark');

    applyStoredColorScheme(document);

    expect(document.documentElement).toHaveClass('dark');
    expect(document.documentElement.style.colorScheme).toBe('dark');
  });
});
