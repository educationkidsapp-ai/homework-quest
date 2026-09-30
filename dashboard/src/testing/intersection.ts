/**
 * A controllable `IntersectionObserver` for jsdom, which has none.
 *
 * MH2's plan images load when their row is near the viewport, and jsdom has no layout engine to
 * decide that — so the stub reports **nothing** until a spec says otherwise. That default is the
 * point: "an off-screen row fetches no bytes" is only assertable if off-screen is what a row is
 * until the test scrolls to it.
 *
 * Installed once from `src/test-setup.ts`; {@link scrollIntoView} is how a spec moves one.
 */
interface Watcher {
  readonly callback: IntersectionObserverCallback;
  readonly observer: IntersectionObserver;
  readonly elements: Set<Element>;
}

const watchers = new Set<Watcher>();

export function installStubIntersectionObserver(): void {
  // Not `implements IntersectionObserver`: the DOM lib grows read-only members (`scrollMargin`
  // arrived with a TypeScript bump) that a stub has no business restating. The cast at the
  // assignment is where the shape is claimed, once.
  class StubIntersectionObserver {
    readonly root = null;
    readonly rootMargin: string;
    readonly thresholds: readonly number[] = [0];
    private readonly watcher: Watcher;

    constructor(callback: IntersectionObserverCallback, options?: IntersectionObserverInit) {
      this.rootMargin = options?.rootMargin ?? '0px';
      this.watcher = {
        callback,
        observer: this as unknown as IntersectionObserver,
        elements: new Set<Element>(),
      };
      watchers.add(this.watcher);
    }

    observe(element: Element): void {
      this.watcher.elements.add(element);
    }

    unobserve(element: Element): void {
      this.watcher.elements.delete(element);
    }

    disconnect(): void {
      this.watcher.elements.clear();
      watchers.delete(this.watcher);
    }

    takeRecords(): IntersectionObserverEntry[] {
      return [];
    }
  }

  globalThis.IntersectionObserver = StubIntersectionObserver as unknown as typeof IntersectionObserver;
}

/** Tell every observer watching this element that it is now on screen. */
export function scrollIntoView(element: Element): void {
  for (const watcher of [...watchers]) {
    if (!watcher.elements.has(element)) continue;
    watcher.callback(
      [{ target: element, isIntersecting: true } as unknown as IntersectionObserverEntry],
      watcher.observer,
    );
  }
}

/** Everything an observer is currently watching — "the rows that exist but are not on screen". */
export function observedElements(): readonly Element[] {
  return [...watchers].flatMap((watcher) => [...watcher.elements]);
}
