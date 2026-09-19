import { CdkTrapFocus } from '@angular/cdk/a11y';
import {
  DOCUMENT,
  ChangeDetectionStrategy,
  Component,
  afterRenderEffect,
  computed,
  inject,
  signal,
} from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { ButtonComponent } from '../../ui';
import { AuthService } from '../auth/auth.service';
import { TourService } from './tour.service';

interface Spotlight {
  readonly top: number;
  readonly left: number;
  readonly width: number;
  readonly height: number;
}

/**
 * The spotlight and the bubble.
 *
 * The "spotlight" is one absolutely positioned box with a very large spread shadow in the
 * overlay colour: the box itself stays transparent, so the element underneath is lit and
 * everything else is dimmed, with no clip-path and no second overlay element to keep in step.
 *
 * It says `aria-modal="true"`, so it has to behave like one: `cdkTrapFocus` with
 * `cdkTrapFocusAutoCapture` moves focus into the bubble when a step opens and keeps Tab inside
 * it, and returns focus where it came from when the tour ends. Without that, the promise in the
 * attribute is false — Tab walks the page behind a spotlight that says it is modal, and a
 * screen-reader user is never told the tour is there at all.
 *
 * Esc closes it, like every other overlay in the dashboard. The target is found by
 * `data-hq-tour="…"`; when a step's target is not on screen (a nav item a flag hides, say)
 * the bubble simply centres itself rather than pointing at nothing.
 */
@Component({
  selector: 'hq-tour',
  imports: [CdkTrapFocus, TranslocoPipe, ButtonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(document:keydown.escape)': 'skip()' },
  template: `
    @if (tour.step(); as step) {
      <div class="tour" role="dialog" aria-modal="true" [attr.aria-label]="step.titleKey | transloco">
        @if (spotlight(); as box) {
          <div
            class="tour__spot"
            aria-hidden="true"
            [style.top.px]="box.top"
            [style.left.px]="box.left"
            [style.width.px]="box.width"
            [style.height.px]="box.height"
          ></div>
        } @else {
          <div class="tour__scrim" aria-hidden="true"></div>
        }
        <div
          class="tour__bubble"
          cdkTrapFocus
          [cdkTrapFocusAutoCapture]="true"
          [style.top.px]="bubbleTop()"
          [style.left.px]="bubbleLeft()"
        >
          <p class="tour__step">
            {{ 'tour.step' | transloco: { index: tour.position().index, total: tour.position().total } }}
          </p>
          <h2 class="tour__title" tabindex="-1" cdkFocusInitial>{{ step.titleKey | transloco }}</h2>
          <p class="tour__body">{{ step.bodyKey | transloco }}</p>
          <div class="tour__actions">
            <hq-button variant="quiet" (pressed)="skip()">{{ 'tour.skip' | transloco }}</hq-button>
            <hq-button variant="primary" (pressed)="advance()">
              {{ (last() ? 'tour.done' : 'tour.next') | transloco }}
            </hq-button>
          </div>
        </div>
      </div>
    }
  `,
  styles: `
    .tour {
      position: fixed;
      inset: 0;
      z-index: var(--hq-z-dialog);
    }

    .tour__scrim {
      position: absolute;
      inset: 0;
      background: var(--hq-color-overlay);
    }

    // The lit element is the hole: a transparent box with an enormous spread shadow.
    .tour__spot {
      position: absolute;
      box-shadow: 0 0 0 100vmax var(--hq-color-overlay);
      outline: var(--hq-size-selected-border) solid var(--hq-color-accent);
      pointer-events: none;
    }

    .tour__bubble {
      position: absolute;
      inline-size: var(--hq-size-stop-list-width);
      max-inline-size: 90vw;
      padding: var(--hq-space-24);
      // A floating panel: it sits on the CDK overlay with nothing opaque behind it, so it
      // takes the raised surface rather than the card one (§5 gives both, and they differ).
      background: var(--hq-color-surface-raised);
      border: var(--hq-size-rule) solid var(--hq-color-line);
      box-shadow: var(--hq-shadow-dialog);
    }

    .tour__step {
      font-size: var(--hq-font-label-size);
      font-weight: var(--hq-font-label-weight);
      letter-spacing: var(--hq-font-letter-spacing-label);
      text-transform: uppercase;
      color: var(--hq-color-ink-soft);
    }

    .tour__title {
      margin-block: var(--hq-space-4) var(--hq-space-8);
      font-size: var(--hq-font-body-size);
      font-weight: var(--hq-font-label-weight);
    }

    .tour__body {
      color: var(--hq-color-ink-soft);
    }

    .tour__actions {
      display: flex;
      justify-content: space-between;
      gap: var(--hq-space-8);
      margin-block-start: var(--hq-space-24);
    }
  `,
})
export class TourComponent {
  private readonly doc = inject(DOCUMENT);
  private readonly auth = inject(AuthService);
  private readonly box = signal<Spotlight | null>(null);

  protected readonly tour = inject(TourService);
  protected readonly spotlight = this.box.asReadonly();
  protected readonly last = computed(() => this.tour.position().index === this.tour.position().total);

  /** Below the spotlight where there is room, above it when there is not. */
  protected readonly bubbleTop = computed(() => {
    const box = this.box();
    const view = this.doc.defaultView?.innerHeight ?? 768;
    if (!box) return Math.max(16, view / 2 - 120);
    const below = box.top + box.height + 16;
    return below + 220 < view ? below : Math.max(16, box.top - 220);
  });

  protected readonly bubbleLeft = computed(() => {
    const box = this.box();
    const view = this.doc.defaultView?.innerWidth ?? 1366;
    const width = 320;
    if (!box) return Math.max(16, view / 2 - width / 2);
    return Math.min(Math.max(16, box.left), view - width - 16);
  });

  constructor() {
    // Measured after layout: a step may light an element that has only just been rendered.
    afterRenderEffect(() => {
      const step = this.tour.step();
      if (!step) {
        this.box.set(null);
        return;
      }
      const target = this.doc.querySelector(`[data-hq-tour="${step.target}"]`);
      if (!target) {
        this.box.set(null);
        return;
      }
      const rect = target.getBoundingClientRect();
      this.box.set({
        top: rect.top - 4,
        left: rect.left - 4,
        width: rect.width + 8,
        height: rect.height + 8,
      });
    });
  }

  protected advance(): void {
    const role = this.auth.role();
    if (this.last() && role) this.tour.dismiss(role);
    else this.tour.next();
  }

  protected skip(): void {
    const role = this.auth.role();
    if (role) this.tour.dismiss(role);
    else this.tour.finish();
  }
}
