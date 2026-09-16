/* hq-flag: none (shell) — the frame every screen sits in. It is what shows a flag-gated
   item or hides it; gating the frame itself would leave a school with no dashboard. */
import { DOCUMENT, ChangeDetectionStrategy, Component, computed, effect, inject } from '@angular/core';
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
import { NAV } from '../core/nav/nav-items';
import { PermissionService } from '../core/permissions/permission.service';
import { TourComponent } from '../core/tour/tour.component';
import { TourService } from '../core/tour/tour.service';
import { UndoService } from '../core/undo/undo.service';
import { ShellHeaderComponent } from './shell-header.component';

/**
 * The authenticated frame: 240 px rail, header, one screen, and the four things that belong
 * to no screen in particular — the red band, the Undo strip, the shortcut sheet and the tour.
 *
 * **The rail is filtered, not fixed.** An item appears only when its flag is on for the
 * school in scope *and* the account holds its permission, which is why the same component
 * serves three roles: switching school re-reads the flags and the rail changes underneath.
 *
 * **`/` focuses the screen's search.** The shell looks for `[data-hq-search]` rather than
 * holding a search box of its own, so a screen with a filter gets the shortcut and a screen
 * without one is not given a box that does nothing.
 *
 * **The band is cleared on navigation.** A failure belongs to the screen it happened on;
 * carrying it to the next one would be a toast with extra steps.
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
        data-hq-tour="nav"
        [items]="items()"
        [active]="activeId()"
        [label]="'nav.label.' + (auth.role() ?? 'ADMIN') | transloco"
      >
        <span nav-brand class="shell__brand">{{ 'nav.brand' | transloco }}</span>
      </hq-nav>

      <div class="shell__main">
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

        <main class="shell__content"><router-outlet /></main>

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
    }

    .shell__nav {
      position: sticky;
      inset-block-start: 0;
      block-size: 100vh;
      z-index: var(--hq-z-nav);
    }

    .shell__brand {
      font-size: var(--hq-font-label-size);
      font-weight: var(--hq-font-label-weight);
      letter-spacing: var(--hq-font-letter-spacing-label);
      text-transform: uppercase;
      color: var(--hq-color-ink-soft);
    }

    .shell__main {
      display: flex;
      flex-direction: column;
      flex: 1;
      min-inline-size: 0;
    }

    .shell__band {
      padding: var(--hq-space-16) var(--hq-size-page-padding) 0;
    }

    .shell__content {
      flex: 1;
    }
  `,
})
export class ShellComponent {
  private readonly doc = inject(DOCUMENT);
  private readonly router = inject(Router);
  private readonly flags = inject(FlagService);
  private readonly permissions = inject(PermissionService);
  private readonly tour = inject(TourService);
  private readonly transloco = inject(TranslocoService);
  /** The rail's labels are built in a computed, so they need the language as a dependency. */
  private readonly lang = activeLang();

  protected readonly auth = inject(AuthService);
  protected readonly band = inject(BandService);
  protected readonly undo = inject(UndoService);

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
    return NAV[role]
      .filter((item) => (item.flag ? this.flags.isOn(item.flag) : true))
      .filter((item) => (item.permission ? this.permissions.can(item.permission) : true))
      .map((item) => ({ id: item.id, label: this.transloco.translate(item.labelKey), link: item.link }));
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
