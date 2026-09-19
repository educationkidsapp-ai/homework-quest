import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * A 396 × 812 Android device frame for previewing what a parent or child will see.
 *
 * The frame is the one rounded thing in the system — it is a picture of a device, not
 * a panel — and its content scrolls on its own so a long lesson preview never scrolls
 * the dashboard page underneath it.
 *
 * The screen carries `hq-scheme-light` because what is inside it is the **child's app**, and
 * the child's app has no dark mode. A teacher previewing a lesson at midnight should see the
 * screen the child will see in the morning, not a recoloured copy of it.
 */
@Component({
  selector: 'hq-phone-frame',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <figure class="phone">
      <div class="phone__device">
        <div class="phone__screen hq-scheme-light" [attr.aria-label]="label()" role="group" tabindex="0">
          <ng-content />
        </div>
      </div>
      @if (caption(); as captionText) {
        <figcaption class="phone__caption">{{ captionText }}</figcaption>
      }
    </figure>
  `,
  styles: `
    @use 'mixins' as m;

    :host {
      display: block;
    }

    .phone {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: var(--hq-space-8);
      margin: 0;
    }

    .phone__device {
      inline-size: calc(var(--hq-size-phone-width) + var(--hq-size-phone-bezel) * 2);
      block-size: calc(var(--hq-size-phone-height) + var(--hq-size-phone-bezel) * 2);
      padding: var(--hq-size-phone-bezel);
      // Its own role, not the ink colour: ink is near-white in dark mode and a white bezel is
      // not a phone — and the screen inside it composited over that to a light grey.
      background: var(--hq-color-bezel);
      border-radius: var(--hq-size-phone-corner);
    }

    .phone__screen {
      inline-size: var(--hq-size-phone-width);
      block-size: var(--hq-size-phone-height);
      overflow: auto;
      overscroll-behavior: contain;
      background: var(--hq-color-surface);
      border-radius: calc(var(--hq-size-phone-corner) - var(--hq-size-phone-bezel));
      @include m.focus-ring(var(--hq-color-accent));
    }

    .phone__caption {
      @include m.label;
      color: var(--hq-color-ink-soft);
    }
  `,
})
export class PhoneFrameComponent {
  /** Accessible name for the preview region, e.g. "Parent app preview". */
  readonly label = input.required<string>();
  readonly caption = input<string | null>(null);
}
