/* hq-flag: none (shell) — the frame every screen sits in. It is what shows a flag-gated
   item or hides it; gating the frame itself would leave a school with no dashboard. */
import { LiveAnnouncer } from '@angular/cdk/a11y';
import {
  DOCUMENT,
  ChangeDetectionStrategy,
  Component,
  afterRenderEffect,
  computed,
  effect,
  inject,
} from '@angular/core';
import { NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { filter, map } from 'rxjs/operators';
import {
  BandComponent,
  NavComponent,
  ShortcutsDialogComponent,
  UndoStripComponent,
  type NavItem,
  type Shortcut,
} from '../ui';
import { AuthService } from '../core/auth/auth.service';
import { BandService } from '../core/band/band.service';
import { FlagService } from '../core/flags/flag.service';
import { activeLang } from '../core/i18n/active-lang';
import { ClassContextService } from '../core/nav/class-context.service';
import { navScreens } from '../core/nav/screens';
import { PermissionService } from '../core/permissions/permission.service';
import { SchoolScopeStore } from '../core/auth/school-scope.store';
import { SIDEBAR_ID, SidebarService } from '../core/shell/sidebar.service';
import { ThemeService } from '../core/theme/theme.service';
import { TourComponent } from '../core/tour/tour.component';
import { TourService } from '../core/tour/tour.service';
import { UndoService } from '../core/undo/undo.service';
import { ShellHeaderComponent } from './shell-header.component';

/**
 * The authenticated frame: the 290 px sidebar, the header, one screen, and the four things that
 * belong to no screen in particular — the red band, the Undo strip, the shortcut sheet and the
 * tour (spec §2 "App shell").
 *
 * **The rail is filtered, not fixed.** An item appears only when its flag is on for the
 * school in scope *and* the account holds its permission, which is why the same component
 * serves three roles: switching school re-reads the flags and the rail changes underneath.
 *
 * **`/` focuses the screen's search.** The shell looks for `[data-hq-search]` rather than
 * holding a search box of its own, so a screen with a filter gets the shortcut and a screen
 * without one is not given a box that does nothing.
 *
 * **The band is cleared on navigation**, and focus moves to the new screen's heading while
 * `LiveAnnouncer` reads it. A failure belongs to the screen it happened on; carrying it to the
 * next one would be a toast with extra steps. And a router outlet that swaps its contents
 * leaves focus on the link that was clicked — so a screen-reader user hears nothing at all and
 * the next Tab continues from the rail rather than from the page they just opened.
 */
@Component({
  selector: 'hq-shell',
  imports: [
    RouterOutlet,
    NavComponent,
    BandComponent,
    UndoStripComponent,
    ShortcutsDialogComponent,
    ShellHeaderComponent,
    TourComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(document:keydown)': 'onKeydown($event)' },
  template: `
    <div class="shell">
      <hq-nav
        class="shell__nav"
        [id]="sidebarId"
        data-hq-tour="nav"
        [items]="items()"
        [active]="activeId()"
        [label]="'nav.label.' + (auth.role() ?? 'ADMIN') | transloco"
        [collapsed]="sidebar.collapsed()"
        [drawer]="sidebar.drawer()"
        [open]="sidebar.open()"
        [brandName]="brandName()"
        [brandLogo]="brandLogo()"
        (dismissed)="sidebar.closeDrawer()"
      >
        <span nav-brand class="shell__brand">{{ 'nav.brand' | transloco }}</span>
      </hq-nav>

      <!--
        Everything behind a modal drawer is inert: not merely untabbable but out of reach of a
        click, a pointer and the accessibility tree, which is what aria-modal="true" on the
        panel is promising. The focus trap alone keeps Tab inside; inert is what makes the
        promise true for a screen reader and for a stray tap on the header.
      -->
      <div class="shell__main" [attr.inert]="drawerOpen() ? '' : null">
        <hq-shell-header (signOut)="signOut()" />

        @if (band.current(); as message) {
          <div class="shell__band">
            <hq-band
              [open]="true"
              [variant]="message.variant ?? 'error'"
              [title]="message.titleKey ?? 'band.failed' | transloco"
              (dismissed)="band.dismiss()"
            >
              {{ message.message }}
            </hq-band>
          </div>
        }

        <main class="shell__content" tabindex="-1"><router-outlet /></main>

        @if (undo.offer(); as offer) {
          <hq-undo-strip
            [open]="true"
            [message]="offer.message"
            (undone)="undo.undo()"
            (expired)="undo.expire()"
          />
        }
      </div>
    </div>

    <hq-shortcuts-dialog [shortcuts]="shortcuts()" [title]="'shortcuts.title' | transloco" />
    <hq-tour />
  `,
  styles: `
    :host {
      display: block;
      block-size: 100%;
    }

    .shell {
      display: flex;
      align-items: stretch;
      min-block-size: 100vh;
      background: linear-gradient(135deg, #eff6ff 0%, #faf5ff 50%, #fff1f2 100%);
    }

    // The rail's own sticky/width behaviour, and its stacking, are the component's; the shell
    // only places it. It carries **no** z-index here on purpose: 'z-index' applies to a flex
    // item whatever its position, so the 20 this used to hold made the rail a stacking context
    // and sealed its drawer — z-index 60, fixed, over everything — inside a level *below* the
    // header's 40. The drawer opened underneath the bar that opened it.

    // The role's name, under the school's in the sidebar's logo block.
    .shell__brand {
      font-size: var(--hq-text-theme-xs);
      line-height: calc(var(--hq-text-theme-xs-line) / var(--hq-text-theme-xs));
      color: var(--hq-color-ink-soft);
    }

    .shell__main {
      display: flex;
      flex-direction: column;
      flex: 1;
      min-inline-size: 0;
    }

    .shell__band {
      padding: var(--hq-space-24) var(--hq-space-24) 0;
    }

    // §2 Content well. 'hq-page' keeps its own header and its sticky footer; its padding and
    // its reading measure are handed to it here, so there is one well and not two.
    .shell__content {
      flex: 1;
      inline-size: 100%;
      max-inline-size: var(--hq-size-content-well);
      padding: var(--hq-space-24);
      margin-inline: auto;
      --hq-page-padding: 0;
      --hq-page-max-width: 100%;
    }
  `,
})
export class ShellComponent {
  private readonly doc = inject(DOCUMENT);
  private readonly router = inject(Router);
  private readonly flags = inject(FlagService);
  private readonly permissions = inject(PermissionService);
  private readonly classContext = inject(ClassContextService);
  private readonly tour = inject(TourService);
  private readonly transloco = inject(TranslocoService);
  private readonly announcer = inject(LiveAnnouncer);
  /** The rail's labels are built in a computed, so they need the language as a dependency. */
  private readonly lang = activeLang();

  private readonly theme = inject(ThemeService);
  private readonly scope = inject(SchoolScopeStore);

  protected readonly auth = inject(AuthService);
  protected readonly band = inject(BandService);
  protected readonly undo = inject(UndoService);
  protected readonly sidebar = inject(SidebarService);
  protected readonly sidebarId = SIDEBAR_ID;
  /** The one condition the shell behind the rail is inert under. */
  protected readonly drawerOpen = computed(() => this.sidebar.drawer() && this.sidebar.open());

  // §2's logo block: the school in scope, or the platform when there is none. The same two
  // values the header used to carry — the brand belongs to the rail now, not to the bar.
  protected readonly brandName = computed(() => this.scope.scope()?.name ?? this.theme.appName());
  protected readonly brandLogo = computed(() => this.theme.logoUrl());

  /** The current URL, as a signal, so the rail's rule follows the router rather than clicks. */
  private readonly url = toSignal(
    this.router.events.pipe(
      filter((event): event is NavigationEnd => event instanceof NavigationEnd),
      map((event) => event.urlAfterRedirects),
    ),
    { initialValue: this.router.url },
  );

  protected readonly items = computed<readonly NavItem[]>(() => {
    this.lang();
    const role = this.auth.role();
    if (role === null) return [];
    const items = navScreens(role)
      .filter(({ screen }) => (screen.flag ? this.flags.isOn(screen.flag) : true))
      .filter(({ screen }) => (screen.permission ? this.permissions.can(screen.permission) : true))
      .map(({ screen, link }) => ({
        id: screen.id,
        label: this.transloco.translate<string>(screen.labelKey ?? ''),
        link,
      }));
    // `docs/teacher-flow.md` §5: the class she is inside joins the rail as a third item, and
    // leaves with her. It is not a row in the table — the label is a class's name, which only
    // the screen that fetched it knows — so the class page publishes it and the rail appends it.
    const klass = this.classContext.current();
    return klass ? [...items, { id: 'class', label: klass.label, link: klass.link }] : items;
  });

  /** The longest matching link wins, so `/teacher/lessons/new` does not light "My lessons". */
  protected readonly activeId = computed(() => {
    const url = this.url();
    const best = [...this.items()]
      .filter(
        (item) =>
          item.link !== undefined &&
          (url === item.link || url.startsWith(`${item.link}/`) || url.startsWith(`${item.link}?`)),
      )
      .sort((a, b) => (b.link?.length ?? 0) - (a.link?.length ?? 0))[0];
    return best?.id ?? this.items()[0]?.id ?? '';
  });

  protected readonly shortcuts = computed<readonly Shortcut[]>(() => {
    this.lang();
    return [
      { keys: '/', description: this.transloco.translate<string>('shortcuts.search') },
      { keys: '?', description: this.transloco.translate<string>('shortcuts.help') },
      { keys: 'Esc', description: this.transloco.translate<string>('shortcuts.close') },
      { keys: 'Enter', description: this.transloco.translate<string>('shortcuts.submit') },
    ];
  });

  constructor() {
    effect(() => {
      this.url();
      this.band.dismiss();
      // A drawer is modal; the screen behind it just changed, so it has done its job.
      this.sidebar.closeDrawer();
    });

    // After the new screen has rendered, not before: the heading it moves to does not exist
    // until then. The first paint is skipped — landing on a page is not "the page changed".
    let first = true;
    afterRenderEffect(() => {
      this.url();
      if (first) {
        first = false;
        return;
      }
      this.focusScreen();
    });
    // First sign-in for this role in this browser gets the four-step tour.
    effect(() => {
      const role = this.auth.role();
      if (role) this.tour.offer(role);
    });
  }

  protected signOut(): void {
    this.auth.signOut().subscribe(() => void this.router.navigate(['/sign-in']));
  }

  /**
   * Moves focus to the new screen's heading and announces it.
   *
   * The `h1` when there is one, because that is the screen's name and a screen reader will
   * read it on focus; the `<main>` landmark otherwise, so focus is at least inside the new
   * content. `LiveAnnouncer` says the title out loud for anyone whose focus did not move
   * visibly — the two together are what make a route change perceivable without a page load.
   */
  private focusScreen(): void {
    const main = this.doc.querySelector<HTMLElement>('main.shell__content');
    const heading = main?.querySelector<HTMLElement>('h1') ?? main;
    if (!heading) return;
    if (!heading.hasAttribute('tabindex')) heading.setAttribute('tabindex', '-1');
    heading.focus({ preventScroll: true });
    const title = heading.textContent?.trim();
    if (title) void this.announcer.announce(title, 'polite');
  }

  /** `/` focuses the screen's search field, when the screen has one. */
  protected onKeydown(event: KeyboardEvent): void {
    if (event.key !== '/' || isTyping(event.target)) return;
    const search = this.doc.querySelector<HTMLInputElement>('[data-hq-search] input, input[data-hq-search]');
    if (!search) return;
    event.preventDefault();
    search.focus();
    search.select();
  }
}

function isTyping(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  return target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName);
}
