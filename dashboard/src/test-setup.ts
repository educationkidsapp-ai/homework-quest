import '@testing-library/jest-dom/vitest';
import { installStubIntersectionObserver } from './testing/intersection';

/**
 * jsdom has no `IntersectionObserver`, and MH2's plan images load when their row is near the
 * viewport. The stub reports nothing until a spec scrolls an element into view, so the default in
 * every spec is "nothing is on screen" — which is what makes an off-screen row's *absence* of a
 * request assertable rather than invisible.
 */
installStubIntersectionObserver();

/**
 * jsdom has no layout engine and no Web Animations API, and several components measure
 * or animate. Stubbing both here keeps every spec about behaviour rather than about
 * jsdom's gaps — the real animations are covered by the Playwright screenshots.
 */
if (typeof Element.prototype.animate !== 'function') {
  Element.prototype.animate = function stubAnimate(): Animation {
    return {
      finished: Promise.resolve(),
      cancel: () => undefined,
      finish: () => undefined,
      play: () => undefined,
      pause: () => undefined,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
    } as unknown as Animation;
  };
}

/**
 * Node 22+ exposes its own `localStorage` global, which needs `--localstorage-file` and
 * throws on `clear()`. Under jsdom that global shadows the window's store, so specs that
 * exercise persistence get an in-memory Storage instead.
 */
if (typeof globalThis.localStorage?.clear !== 'function') {
  const store = new Map<string, string>();
  const memoryStorage: Storage = {
    get length() {
      return store.size;
    },
    clear: () => store.clear(),
    getItem: (key) => store.get(key) ?? null,
    key: (index) => [...store.keys()][index] ?? null,
    removeItem: (key) => void store.delete(key),
    setItem: (key, value) => void store.set(key, String(value)),
  };
  Object.defineProperty(globalThis, 'localStorage', { value: memoryStorage, configurable: true });
  if (typeof window !== 'undefined') {
    Object.defineProperty(window, 'localStorage', { value: memoryStorage, configurable: true });
  }
}

if (typeof globalThis.matchMedia !== 'function') {
  globalThis.matchMedia = (query: string): MediaQueryList => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    addListener: () => undefined,
    removeListener: () => undefined,
    dispatchEvent: () => false,
  });
}
