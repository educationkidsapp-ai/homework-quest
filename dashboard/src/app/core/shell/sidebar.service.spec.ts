import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DRAWER_BREAKPOINT, SidebarService } from './sidebar.service';

/**
 * A `matchMedia` stub with a width behind it, because JSDOM has none at all and the whole
 * question this service answers — column or drawer — is one media query.
 */
function widthIs(width: number): () => void {
  const lists = new Set<{ matches: boolean; listeners: Set<(e: MediaQueryListEvent) => void> }>();
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    writable: true,
    value: (query: string) => {
      const max = Number(/max-width:\s*([\d.]+)px/.exec(query)?.[1] ?? Infinity);
      const entry = { matches: width <= max, listeners: new Set<(e: MediaQueryListEvent) => void>() };
      lists.add(entry);
      return {
        media: query,
        get matches() {
          return entry.matches;
        },
        addEventListener: (_: string, listener: (e: MediaQueryListEvent) => void) =>
          entry.listeners.add(listener),
        removeEventListener: (_: string, listener: (e: MediaQueryListEvent) => void) =>
          entry.listeners.delete(listener),
      };
    },
  });
  // Returns "the viewport grew past the breakpoint", which is the transition that matters.
  return () => {
    for (const entry of lists) {
      entry.matches = false;
      for (const listener of entry.listeners) listener({ matches: false } as MediaQueryListEvent);
    }
  };
}

describe('SidebarService', () => {
  beforeEach(() => {
    localStorage.clear();
    document.body.style.overflow = '';
    widthIs(1366);
  });

  afterEach(() => vi.restoreAllMocks());

  it('remembers the collapsed rail for this viewer', () => {
    const sidebar = TestBed.inject(SidebarService);
    expect(sidebar.collapsed()).toBe(false);

    sidebar.toggleCollapsed();

    expect(sidebar.collapsed()).toBe(true);
    expect(localStorage.getItem('hq.sidebar.collapsed')).toBe('true');

    // A second browser tab — a fresh injector — opens with the rail the way it was left.
    TestBed.resetTestingModule();
    expect(TestBed.inject(SidebarService).collapsed()).toBe(true);
  });

  it('survives a browser that will not give it storage at all', () => {
    // Lockdown Mode and a blocked third-party context both *throw* here rather than returning
    // null. A rail that cannot remember its width is still a rail.
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('denied');
    });

    const sidebar = TestBed.inject(SidebarService);
    expect(() => sidebar.toggleCollapsed()).not.toThrow();
    expect(sidebar.collapsed()).toBe(true);
  });

  it('is a column above the breakpoint and a drawer below it', () => {
    expect(TestBed.inject(SidebarService).drawer()).toBe(false);

    TestBed.resetTestingModule();
    widthIs(DRAWER_BREAKPOINT - 1);
    expect(TestBed.inject(SidebarService).drawer()).toBe(true);
  });

  it('opens and closes the drawer, locks the page behind it, and hands focus back', () => {
    TestBed.resetTestingModule();
    widthIs(768);
    const sidebar = TestBed.inject(SidebarService);
    const burger = document.createElement('button');
    document.body.append(burger);
    const focus = vi.spyOn(burger, 'focus');

    sidebar.openDrawer(burger);
    TestBed.tick();

    expect(sidebar.open()).toBe(true);
    expect(document.body.style.overflow).toBe('hidden');

    sidebar.closeDrawer();
    TestBed.tick();

    expect(sidebar.open()).toBe(false);
    expect(document.body.style.overflow).toBe('');
    expect(focus).toHaveBeenCalled();
    burger.remove();
  });

  it('closes the drawer when the window grows a rail again', () => {
    TestBed.resetTestingModule();
    const grow = widthIs(768);
    const sidebar = TestBed.inject(SidebarService);
    sidebar.openDrawer();
    TestBed.tick();
    expect(sidebar.open()).toBe(true);

    grow();
    TestBed.tick();

    // A scrim over a page that has its rail back is a modal with nothing behind it.
    expect(sidebar.drawer()).toBe(false);
    expect(sidebar.open()).toBe(false);
    expect(document.body.style.overflow).toBe('');
  });

  it('gives one button two jobs: collapse on a wide viewport, the drawer on a narrow one', () => {
    const wide = TestBed.inject(SidebarService);
    wide.toggle();
    expect(wide.collapsed()).toBe(true);
    expect(wide.open()).toBe(false);

    // A fresh browser, so the wide half's stored choice is not what makes this pass.
    localStorage.clear();
    TestBed.resetTestingModule();
    widthIs(375);
    const narrow = TestBed.inject(SidebarService);
    narrow.toggle();
    expect(narrow.open()).toBe(true);
    expect(narrow.collapsed()).toBe(false);
  });

  it('reports what the rail actually shows, not what was stored', () => {
    const wide = TestBed.inject(SidebarService);
    expect(wide.expanded()).toBe(true);
    wide.toggleCollapsed();
    expect(wide.expanded()).toBe(false);

    // On a narrow viewport the stored choice means nothing: the drawer is open or it is not.
    TestBed.resetTestingModule();
    widthIs(375);
    const narrow = TestBed.inject(SidebarService);
    expect(narrow.expanded()).toBe(false);
    narrow.openDrawer();
    expect(narrow.expanded()).toBe(true);
  });
});
