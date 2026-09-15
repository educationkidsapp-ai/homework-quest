import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PageEnterDirective, type NavDirection } from '../motion';

export interface Breadcrumb {
  readonly label: string;
  /** Absent on the current page. */
  readonly link?: string;
}

/**
 * The page frame every screen sits in: breadcrumb, 30 px title, content, sticky footer.
 *
 * The footer holds the screen's one primary action. It is sticky so the action is
 * reachable without scrolling to the bottom of a long form — the rule above it is the
 * same 2 px ink rule as everything else, so it reads as part of the page, not a bar.
 */
@Component({
  selector: 'hq-page',
  imports: [RouterLink, PageEnterDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page" [hqPageEnter]="direction()">
      <header class="page__header">
        @if (breadcrumbs().length > 0) {
          <nav class="page__crumbs" [attr.aria-label]="breadcrumbLabel()">
            <ol>
              @for (crumb of breadcrumbs(); track crumb.label; let last = $last) {
                <li>
                  @if (crumb.link && !last) {
                    <a [routerLink]="crumb.link">{{ crumb.label }}</a>
                    <span class="page__crumb-sep" aria-hidden="true">/</span>
                  } @else {
                    <span aria-current="page">{{ crumb.label }}</span>
                  }
                </li>
              }
            </ol>
          </nav>
        }
        <div class="page__title-row">
          <h1 class="page__title">{{ title() }}</h1>
          <div class="page__header-actions"><ng-content select="[page-actions]" /></div>
        </div>
        @if (subtitle(); as subtitleText) {
          <p class="page__subtitle">{{ subtitleText }}</p>
        }
      </header>

      <div class="page__body"><ng-content /></div>

      <footer class="page__footer"><ng-content select="[page-footer]" /></footer>
    </div>
  `,
  styles: `
    @use 'mixins' as m;

    :host {
      display: block;
    }

    .page {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-24);
      max-inline-size: var(--hq-size-content-max-width);
      padding: var(--hq-size-page-padding);
      margin-inline: auto;
    }

    .page__crumbs {
      @include m.label;
      color: var(--hq-color-ink-soft);

      ol {
        display: flex;
        flex-wrap: wrap;
        gap: var(--hq-space-8);
      }

      a {
        color: inherit;
      }
    }

    .page__crumb-sep {
      margin-inline-start: var(--hq-space-8);
    }

    .page__title-row {
      display: flex;
      align-items: flex-end;
      justify-content: space-between;
      gap: var(--hq-space-16);
      margin-block-start: var(--hq-space-8);
    }

    .page__title {
      @include m.title;
    }

    .page__subtitle {
      margin-block-start: var(--hq-space-4);
      color: var(--hq-color-ink-soft);
    }

    .page__footer:not(:empty) {
      position: sticky;
      inset-block-end: 0;
      display: flex;
      justify-content: flex-end;
      gap: var(--hq-space-8);
      padding-block: var(--hq-space-16);
      background: var(--hq-color-bg);
      border-block-start: var(--hq-size-rule) solid var(--hq-color-line);
      z-index: var(--hq-z-sticky-footer);
    }
  `,
})
export class PageComponent {
  readonly title = input.required<string>();
  readonly subtitle = input<string | null>(null);
  readonly breadcrumbs = input<readonly Breadcrumb[]>([]);
  /** Accessible name for the breadcrumb nav, translated by the caller. */
  readonly breadcrumbLabel = input('Breadcrumb');
  /** Which way the user arrived; drives the `pageEnter` slide. */
  readonly direction = input<NavDirection>('forward');
}
