import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ScreenSearchService } from './screen-search.service';

/** U1 item 1: the header's box, and the debounce between the keyboard and the grid. */
describe('the screen search box', () => {
  let search: ScreenSearchService;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({});
    search = TestBed.inject(ScreenSearchService);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('is drawn only once a screen has claimed it, and says what that screen searches', () => {
    expect(search.active()).toBe(false);

    search.claim('week.search.placeholder');
    expect(search.active()).toBe(true);
    expect(search.placeholderKey()).toBe('week.search.placeholder');

    search.release();
    expect(search.active()).toBe(false);
    expect(search.placeholderKey()).toBe('shell.searchPlaceholder');
  });

  it('shows every keystroke but hands the screen a trimmed term 250 ms later', () => {
    search.set('fra');
    expect(search.query()).toBe('fra');
    expect(search.term()).toBe('');

    vi.advanceTimersByTime(249);
    expect(search.term()).toBe('');

    vi.advanceTimersByTime(1);
    expect(search.term()).toBe('fra');
  });

  it('debounces: only the last of a burst of keystrokes reaches the screen', () => {
    search.set('f');
    vi.advanceTimersByTime(100);
    search.set('fr');
    vi.advanceTimersByTime(100);
    search.set(' fractions ');
    vi.advanceTimersByTime(250);
    expect(search.term()).toBe('fractions');
  });

  it('clears at once — putting every row back does not wait for a timer', () => {
    search.set('fractions');
    vi.advanceTimersByTime(250);
    search.clear();
    expect(search.query()).toBe('');
    expect(search.term()).toBe('');
  });

  it('empties the box when a screen takes it over, so a term does not leak between screens', () => {
    search.claim('week.search.placeholder');
    search.set('fractions');
    vi.advanceTimersByTime(250);
    search.release();
    expect(search.term()).toBe('');
  });
});
