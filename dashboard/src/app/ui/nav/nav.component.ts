import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterRenderEffect,
  input,
  model,
  viewChild,
  viewChildren,
} from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

export interface NavItem<T extends string = string> {
  readonly id: T;
  readonly label: string;
  /** Router link; omit for an item a screen drives itself (the styleguide does). */
  readonly link?: string;
  readonly badge?: number;
}

/**
 * The 240 px navigation rail.
 *
 * The active item is marked by a single red rule that glides between items: one
 * absolutely positioned element moved with `transform`, never a per-item border
 * fading in and out — the movement is what tells you where you came from.
 */
@Component({
  selector: 'hq-nav',
  imports: [RouterLink, RouterLinkActive],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nav class="nav" [attr.aria-label]="label()">
      <div class="nav__brand"><ng-content select="[nav-brand]" /></div>
      <div class="nav__list">
        <span #rule class="nav__rule" aria-hidden="true"></span>
        <ul>
          @for (item of items(); track item.id) {
            <li>
              @if (item.link; as link) {
                <a
                  #entry
                  class="nav__item"
                  [routerLink]="link"
                  routerLinkActive="is-active"
                  [attr.aria-current]="item.id === active() ? 'page' : null"
                  (click)="select(item.id)"
                >
                  <span>{{ item.label }}</span>
                  @if (item.badge !== undefined) {
                    <span class="nav__badge">{{ item.badge }}</span>
                  }
                </a>
              } @else {
                <button
                  #entry
                  type="button"
                  class="nav__item"
                  [class.is-active]="item.id === active()"
                  [attr.aria-current]="item.id === active() ? 'page' : null"
                  (click)="select(item.id)"
                >
                  <span>{{ item.label }}</span>
                  @if (item.badge !== undefined) {
                    <span class="nav__badge">{{ item.badge }}</span>
                  }
                </button>
              }
            </li>
          }
        </ul>
      </div>
      <div class="nav__footer"><ng-content select="[nav-footer]" /></div>
    </nav>
  `,
  styles: `
    @use 'mixins' as m;

    :host {
      display: block;
      inline-size: var(--hq-size-nav-width);
      flex: none;
    }

    .nav {
      position: relative;
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-24);
      block-size: 100%;
      padding: var(--hq-size-page-padding) 0;
      border-inline-end: var(--hq-size-rule) solid var(--hq-color-line);
    }

    .nav__brand,
    .nav__footer {
      padding-inline: var(--hq-space-24);
    }

    .nav__list {
      position: relative;
      flex: 1;
    }

    // The one moving part: a single rule translated between items.
    .nav__rule {
      position: absolute;
      inset-block-start: 0;
      inset-inline-start: 0;
      inline-size: var(--hq-size-selected-border);
      block-size: var(--hq-nav-rule-height, 0);
      background: var(--hq-color-accent);
      transform: translateY(var(--hq-nav-rule-offset, 0));
      opacity: var(--hq-nav-rule-opacity, 0);
      pointer-events: none;
      @include m.motion-safe(
        'transform, block-size, opacity',
        var(--hq-motion-base),
        var(--hq-motion-ease-emphasised)
      );
    }

    .nav__item {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--hq-space-8);
      inline-size: 100%;
      min-block-size: var(--hq-size-touch-target);
      padding-inline: var(--hq-space-24);
      background: none;
      border: 0;
      color: var(--hq-color-ink-soft);
      font-size: var(--hq-font-body-size);
      text-align: start;
      text-decoration: none;
      cursor: pointer;
      @include m.hover-tint;
      @include m.focus-ring;

      &.is-active {
        color: var(--hq-color-ink);
        font-weight: var(--hq-font-label-weight);
      }
    }

    .nav__badge {
      font-size: var(--hq-font-label-size);
      color: var(--hq-color-ink-soft);
    }
  `,
})
export class NavComponent<T extends string = string> {
  private readonly rule = viewChild.required<ElementRef<HTMLElement>>('rule');
  private readonly entries = viewChildren<ElementRef<HTMLElement>>('entry');

  readonly items = input.required<readonly NavItem<T>[]>();
  readonly active = model.required<T>();
  /** Accessible name — say whose navigation this is, e.g. "Admin". */
  readonly label = input.required<string>();

  constructor() {
    // Position must be read after layout, so this runs in the render phase rather
    // than in a plain effect (which would measure a stale or unlaid-out element).
    afterRenderEffect(() => {
      const activeId = this.active();
      const index = this.items().findIndex((item) => item.id === activeId);
      const entry = this.entries().at(index)?.nativeElement;
      const rule = this.rule().nativeElement;
      if (!entry) {
        rule.style.setProperty('--hq-nav-rule-opacity', '0');
        return;
      }
      rule.style.setProperty('--hq-nav-rule-opacity', '1');
      rule.style.setProperty('--hq-nav-rule-height', `${entry.offsetHeight}px`);
      rule.style.setProperty('--hq-nav-rule-offset', `${entry.offsetTop}px`);
    });
  }

  protected select(id: T): void {
    this.active.set(id);
  }
}
