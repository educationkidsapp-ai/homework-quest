import { CdkTrapFocus } from '@angular/cdk/a11y';
import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input, model, output, signal } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { navIcon } from './nav-icons';

export interface NavItem<T extends string = string> {
  readonly id: T;
  readonly label: string;
  /** Router link; omit for an item a screen drives itself (the styleguide does). */
  readonly link?: string;
  readonly badge?: number;
  /** A key in `NAV_ICONS`; the id is used when it is absent. */
  readonly icon?: string;
}

/**
 * The sidebar (spec §2 "App shell", §3 "Nav item").
 *
 * **290 px, 90 px, or over the page.** Three presentations of one list, chosen by the viewport
 * and the viewer rather than by three components:
 *
 * - wide and expanded — a sticky, full-height column with a 1 px rule on its inner edge;
 * - wide and collapsed — 90 px of centred icons, labels hidden, each item named by an
 *   `aria-label` and a `title` so neither a pointer nor a screen reader loses the word. The
 *   two are added only while the text is hidden: an `aria-label` that merely repeats visible
 *   text costs nothing to a screen reader and everything to `getByLabel`, which then finds the
 *   rail's "My classes" beside a form's "Class" field. It
 *   expands again while the pointer is over it, which is TailAdmin's own behaviour and what
 *   makes the collapsed rail usable rather than a guessing game;
 * - narrow — the off-canvas drawer: a `rgba(16,24,40,0.45)` scrim, a .22 s slide from the
 *   **inline-start** edge, Escape and the scrim to close, and Tab trapped inside while it is.
 *
 * Everything that moves is a logical property, so Arabic gets the rail on the right and a
 * drawer that slides in from the right with no second stylesheet — only the sign of the
 * translate has to flip, and that is one custom property.
 *
 * The active item is a background and a colour from §3's table, not the 4 px rule the old rail
 * glided between items: the spec's nav item is a filled, radius-8 row, and a rule sliding
 * behind a filled row reads as two answers to the same question.
 */
@Component({
  selector: 'hq-nav',
  imports: [NgTemplateOutlet, RouterLink, RouterLinkActive, CdkTrapFocus],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '[class.hq-nav--drawer]': 'drawer()',
    '[class.hq-nav--collapsed]': 'collapsed() && !drawer()',
    '[class.hq-nav--open]': 'drawer() && open()',
    '(document:keydown.escape)': 'onEscape()',
  },
  template: `
    @if (drawer()) {
      <div class="nav__scrim" [class.is-open]="open()" aria-hidden="true" (click)="dismissed.emit()"></div>
    }

    <!--
      A closed drawer is **not in the document**, rather than parked off-canvas with a
      transform. Chrome counts a fixed element's box in the document's scrollable width even
      when it is translated past the edge, so in Arabic the parked panel gave every page a
      290 px horizontal scroll and a screenshot at 375 px came out as empty ground. Not
      rendering it is the only version of this with no overflow to clip, and it costs nothing:
      the slide-in is a keyframe animation, which runs on an element that has just appeared
      where a transition has no previous value to run from.
    -->
    @if (!drawer() || open()) {
      <div
        class="nav__panel"
        [class.is-collapsed]="narrowed()"
        [cdkTrapFocus]="drawer() && open()"
        [cdkTrapFocusAutoCapture]="drawer() && open()"
        (mouseenter)="hovering.set(true)"
        (mouseleave)="hovering.set(false)"
      >
        <div class="nav__brand">
          @if (brandLogo(); as logo) {
            <img class="nav__logo" [src]="logo" alt="" />
          } @else if (monogram(); as initial) {
            <span class="nav__logo nav__logo--initial" aria-hidden="true">{{ initial }}</span>
          }
          <span class="nav__brand-text">
            @if (brandName(); as name) {
              <span class="nav__brand-name">{{ name }}</span>
            }
            <ng-content select="[nav-brand]" />
          </span>
        </div>

        <nav class="nav__nav" [attr.aria-label]="label()">
          <ul class="nav__list">
            @for (item of items(); track item.id) {
              <li>
                @if (item.link; as link) {
                  <a
                    class="nav__item"
                    [routerLink]="link"
                    routerLinkActive="is-active"
                    [attr.aria-label]="narrowed() ? item.label : null"
                    [attr.title]="narrowed() ? item.label : null"
                    [attr.aria-current]="item.id === active() ? 'page' : null"
                    (click)="select(item.id)"
                  >
                    <ng-container [ngTemplateOutlet]="body" [ngTemplateOutletContext]="{ $implicit: item }" />
                  </a>
                } @else {
                  <button
                    type="button"
                    class="nav__item"
                    [class.is-active]="item.id === active()"
                    [attr.aria-label]="narrowed() ? item.label : null"
                    [attr.title]="narrowed() ? item.label : null"
                    [attr.aria-current]="item.id === active() ? 'page' : null"
                    (click)="select(item.id)"
                  >
                    <ng-container [ngTemplateOutlet]="body" [ngTemplateOutletContext]="{ $implicit: item }" />
                  </button>
                }
              </li>
            }
          </ul>
        </nav>

        <div class="nav__footer"><ng-content select="[nav-footer]" /></div>
      </div>
    }

    <ng-template #body let-item>
      <svg class="nav__icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
        <path [attr.d]="iconOf(item)" />
      </svg>
      <span class="nav__label">{{ item.label }}</span>
      @if (item.badge !== undefined) {
        <span class="nav__badge">{{ item.badge }}</span>
      }
    </ng-template>
  `,
  styles: `
    @use 'mixins' as m;

    :host {
      display: block;
      inline-size: var(--hq-size-sidebar);
      flex: none;
      // The sign of the drawer's translate. '-1' in Arabic, where "off-canvas to the
      // inline-start" means off the *right* edge.
      --hq-nav-slide: 1;
      @include m.motion-safe('inline-size', var(--hq-motion-sidebar), ease);
    }

    :host-context([dir='rtl']) {
      --hq-nav-slide: -1;
    }

    // The host is what the page's flex row measures, so it carries the collapsed width; the
    // panel carries the *rendered* one. They are the same except while a pointer is over a
    // collapsed rail, and that is the point — the rail expands over the content rather than
    // shoving it 200 px sideways every time the mouse passes.
    :host(.hq-nav--collapsed) {
      inline-size: var(--hq-size-sidebar-collapsed);
    }

    :host(.hq-nav--drawer) {
      inline-size: 0;
    }

    .nav__panel {
      position: sticky;
      inset-block-start: 0;
      z-index: var(--hq-z-nav);
      display: flex;
      flex-direction: column;
      block-size: 100vh;
      inline-size: var(--hq-size-sidebar);
      padding-inline: var(--hq-size-sidebar-padding);
      overflow-y: auto;
      // §5: the sidebar and the header are the *raised* surface — white in light, '#1a2231'
      // in dark, where the card surface is a different colour again.
      background: var(--hq-color-surface-raised);
      border-inline-end: var(--hq-size-rule-thin) solid var(--hq-color-rule);
      @include m.motion-safe('inline-size, transform', var(--hq-motion-sidebar), ease);

      &.is-collapsed {
        inline-size: var(--hq-size-sidebar-collapsed);
        padding-inline: var(--hq-space-12);
      }
    }

    // --- Collapsed -----------------------------------------------------------
    // Labels out of the flow rather than hidden with 'visibility', so the icon centres in the
    // 90 px strip instead of sitting where the 290 px row left it.
    .is-collapsed {
      .nav__item,
      .nav__brand {
        justify-content: center;
      }

      .nav__label,
      .nav__badge,
      .nav__brand-text {
        display: none;
      }
    }

    // --- Drawer ------------------------------------------------------------- §2 Drawer
    :host(.hq-nav--drawer) .nav__panel {
      position: fixed;
      inset-block: 0;
      inset-inline-start: 0;
      z-index: var(--hq-z-drawer);
      box-shadow: var(--hq-shadow-xl);
      animation: hq-drawer-in var(--hq-motion-drawer) ease-out both;

      @include m.reduced-motion {
        animation-duration: 0ms;
      }
    }

    // In Arabic the inline-start edge is the right one, so the panel slides in from there —
    // one custom property, no second stylesheet.
    @keyframes hq-drawer-in {
      from {
        transform: translateX(calc(-100% * var(--hq-nav-slide)));
      }
      to {
        transform: none;
      }
    }

    .nav__scrim {
      position: fixed;
      inset: 0;
      z-index: var(--hq-z-dialog);
      background: var(--hq-color-overlay);
      opacity: 0;
      pointer-events: none;
      transition: opacity var(--hq-motion-drawer) ease-out;

      @include m.reduced-motion {
        transition-duration: 0ms;
      }

      &.is-open {
        opacity: 1;
        pointer-events: auto;
      }
    }

    // --- Brand --------------------------------------------------------------- §2 logo block
    .nav__brand {
      display: flex;
      align-items: center;
      gap: var(--hq-space-12);
      padding-block: var(--hq-space-logo-block);
    }

    .nav__logo {
      object-fit: contain;
    }

    // A school with no logo yet still needs something in the 90 px strip, where the name is
    // not rendered at all — its initial on the accent, which is the school's own colour.
    .nav__logo--initial {
      display: grid;
      place-items: center;
      inline-size: var(--hq-size-control-height);
      block-size: var(--hq-size-control-height);
      border-radius: var(--hq-radius-tile);
      background: var(--hq-color-accent);
      color: var(--hq-color-on-accent);
      font-size: var(--hq-text-card-title);
      font-weight: var(--hq-text-weight-semibold);
    }

    .nav__brand-text {
      display: flex;
      flex-direction: column;
      min-inline-size: 0;
    }

    .nav__brand-name {
      font-size: var(--hq-text-card-title);
      line-height: var(--hq-text-card-title-line);
      font-weight: var(--hq-text-weight-semibold);
      color: var(--hq-color-ink);
    }

    .nav__nav {
      flex: 1;
    }

    .nav__list {
      display: grid;
      gap: var(--hq-space-4);
    }

    .nav__footer:not(:empty) {
      padding-block: var(--hq-space-24);
    }

    // --- Nav item ------------------------------------------------------------ §3 Nav item
    .nav__item {
      display: flex;
      align-items: center;
      gap: var(--hq-space-12);
      inline-size: 100%;
      padding: var(--hq-space-nav-item);
      border: 0;
      border-radius: var(--hq-radius-control);
      background: none;
      color: var(--hq-color-ink-strong);
      font-size: var(--hq-text-theme-sm);
      line-height: var(--hq-text-theme-sm-line);
      font-weight: var(--hq-text-weight-medium);
      text-align: start;
      text-decoration: none;
      cursor: pointer;
      @include m.motion-safe('background-color, color');
      @include m.focus-ring;

      .nav__icon {
        color: var(--hq-color-ink-soft);
      }

      &:hover {
        background: var(--hq-color-hover);
        color: var(--hq-color-ink);

        .nav__icon {
          color: var(--hq-color-ink-strong);
        }
      }

      &.is-active {
        background: var(--hq-color-accent-soft);
        color: var(--hq-color-accent-on-soft);

        .nav__icon {
          color: var(--hq-color-accent-on-soft);
        }
      }
    }

    .nav__icon,
    .nav__logo {
      inline-size: var(--hq-size-icon-nav);
      block-size: var(--hq-size-icon-nav);
      flex: none;
    }

    .nav__icon {
      fill: none;
      stroke: currentcolor;
      stroke-width: 1.7;
      stroke-linecap: round;
      stroke-linejoin: round;
    }

    .nav__label {
      flex: 1;
      min-inline-size: 0;
    }

    .nav__label,
    .nav__brand-name {
      overflow: hidden;
      white-space: nowrap;
      text-overflow: ellipsis;
    }

    // §3's trailing badge: the success tint, brighter when the item is the current page.
    .nav__badge {
      padding: var(--hq-space-4) var(--hq-space-8);
      border-radius: var(--hq-radius-pill);
      background: var(--hq-color-success-soft);
      color: var(--hq-color-success-ink);
      font-size: var(--hq-text-theme-xs);
      line-height: var(--hq-text-theme-xs-line);
      font-weight: var(--hq-text-weight-medium);
    }

    .is-active .nav__badge {
      background: var(--hq-color-success-100);
    }
  `,
})
export class NavComponent<T extends string = string> {
  readonly items = input.required<readonly NavItem<T>[]>();
  readonly active = model.required<T>();
  /** Accessible name — say whose navigation this is, e.g. "Admin". */
  readonly label = input.required<string>();
  /** The viewer chose the 90 px strip. Ignored while the rail is a drawer. */
  readonly collapsed = input(false);
  /** The viewport is under 1024 px, so the rail is an off-canvas panel. */
  readonly drawer = input(false);
  /** Drawer only. */
  readonly open = input(false);
  /** The school's name (or the platform's) — §2's logo block, from the existing theme data. */
  readonly brandName = input('');
  readonly brandLogo = input<string | null>(null);
  /** Escape, or the scrim. The shell decides what closing means. */
  readonly dismissed = output<void>();

  protected readonly hovering = signal(false);

  /** Collapsed *now* — a pointer over the rail expands it again while it is there. */
  protected readonly narrowed = computed(() => !this.drawer() && this.collapsed() && !this.hovering());

  /** `[...name]` rather than `name[0]`: an Arabic or emoji first character is not one UTF-16 unit. */
  protected readonly monogram = computed(() => [...this.brandName().trim()][0] ?? '');

  protected iconOf(item: NavItem<T>): string {
    return navIcon(item.icon ?? item.id);
  }

  protected select(id: T): void {
    this.active.set(id);
  }

  protected onEscape(): void {
    if (this.drawer() && this.open()) this.dismissed.emit();
  }
}
