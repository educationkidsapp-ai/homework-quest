import {
  DOCUMENT,
  DestroyRef,
  Injectable,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';

/**
 * Below this the rail cannot be a column — it would leave the content under 700 px — so it
 * becomes the off-canvas drawer of the spec's §2. The same number is `--hq-size-drawer-breakpoint`
 * in `_theme.scss`; the media query and this service have to agree, and the only honest way to
 * make them agree is for the query to be built from the number rather than written twice.
 */
export const DRAWER_BREAKPOINT = 1024;

/**
 * The rail's element id, shared by the two components that need it: the rail puts it on itself
 * and the header's burger points `aria-controls` at it. One constant, so the two cannot drift
 * into an `aria-controls` that names nothing — which is a failed audit, not a typo.
 */
export const SIDEBAR_ID = 'hq-sidebar';

/** Per viewer, per browser — not a school setting and not on the account. */
const STORAGE_KEY = 'hq.sidebar.collapsed';

function storedCollapsed(): boolean {
  try {
    return localStorage.getItem(STORAGE_KEY) === 'true';
  } catch {
    // Lockdown Mode, a blocked third-party context and a full disk all *throw* here. A rail
    // that cannot remember its width is still a rail.
    return false;
  }
}

/**
 * Whether the rail is a 290 px column, a 90 px strip of icons, or a drawer over the page.
 *
 * Three pieces of state, deliberately separate:
 *
 * - **`collapsed`** is the viewer's choice and is remembered. It only means anything on a wide
 *   viewport; the drawer is either open or it is not.
 * - **`drawer`** is the viewport's answer, read from `matchMedia` rather than from a resize
 *   listener, so it costs one subscription and not a frame's work per pixel dragged.
 * - **`open`** is the drawer's, and is never true on a wide viewport: growing the window while
 *   the drawer is open would otherwise leave a scrim over a page that has a rail again.
 *
 * The service owns the body scroll lock and the focus return, because both belong to "the
 * drawer is open" rather than to whichever button happened to open it — Escape, the scrim and
 * a navigation all close it, and all three have to put focus back on the burger.
 */
@Injectable({ providedIn: 'root' })
export class SidebarService {
  private readonly doc = inject(DOCUMENT);
  private readonly injector = inject(Injector);
  private readonly collapsedState = signal(storedCollapsed());
  private readonly openState = signal(false);
  private readonly narrow = signal(false);
  /** The control that opened the drawer, so closing it can hand focus back. */
  private trigger: HTMLElement | null = null;

  readonly collapsed = this.collapsedState.asReadonly();
  readonly open = this.openState.asReadonly();
  readonly drawer = this.narrow.asReadonly();

  /** What the rail actually renders as: a column, or a panel over the page. */
  readonly expanded = computed(() => (this.narrow() ? this.openState() : !this.collapsedState()));

  constructor() {
    const view = this.doc.defaultView;
    // `matchMedia` is missing in a bare JSDOM and in any SSR pass; the rail is a column there,
    // which is the right answer for a 1366 px screenshot and for a server render alike.
    const query = view?.matchMedia?.(`(max-width: ${DRAWER_BREAKPOINT - 0.02}px)`);
    if (query) {
      this.narrow.set(query.matches);
      const onChange = (event: MediaQueryListEvent) => this.narrow.set(event.matches);
      query.addEventListener('change', onChange);
      inject(DestroyRef).onDestroy(() => query.removeEventListener('change', onChange));
    }

    // Widening the window closes the drawer: the rail is back as a column, and a scrim over it
    // would be a modal with nothing behind it.
    effect(() => {
      if (!this.narrow()) this.openState.set(false);
    });

    // The page behind a drawer does not scroll (§2 Drawer). Written from the state rather than
    // from the handlers, so there is exactly one place the two can disagree — none.
    effect(() => {
      const locked = this.narrow() && this.openState();
      this.doc.body.style.overflow = locked ? 'hidden' : '';
    });
  }

  toggleCollapsed(): void {
    const next = !this.collapsedState();
    this.collapsedState.set(next);
    try {
      localStorage.setItem(STORAGE_KEY, String(next));
    } catch {
      // A width that cannot be remembered is still a width for this session.
    }
  }

  openDrawer(trigger?: HTMLElement | null): void {
    this.trigger = trigger ?? null;
    this.openState.set(true);
  }

  /**
   * `untracked`, because the guard *reads* the state and the shell calls this from an effect
   * that watches the URL. Read plainly, the read made `open` a dependency of that effect, so
   * opening the drawer immediately re-ran it and closed the drawer again — the burger looked
   * dead on every viewport under 1024 px.
   *
   * Focus goes back **after the next render**, not on the spot. The drawer is modal, so while
   * it is open the whole of the shell behind it carries `inert` — including the burger it came
   * from — and an inert element cannot take focus. The attribute comes off in the change
   * detection pass this `set` schedules, and the focus has to land after it.
   */
  closeDrawer(): void {
    if (!untracked(this.openState)) return;
    this.openState.set(false);
    // Focus was inside a panel that is now gone; without this it falls to `<body>` and the
    // next Tab starts the page again from the top.
    const trigger = this.trigger;
    this.trigger = null;
    if (trigger) afterNextRender({ read: () => trigger.focus() }, { injector: this.injector });
  }

  /** One control on a narrow viewport, another on a wide one — the header does not branch. */
  toggle(trigger?: HTMLElement | null): void {
    if (!this.narrow()) this.toggleCollapsed();
    else if (this.openState()) this.closeDrawer();
    else this.openDrawer(trigger);
  }
}
