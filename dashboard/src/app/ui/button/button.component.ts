import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

export type ButtonVariant = 'primary' | 'secondary' | 'danger' | 'quiet';
export type ButtonType = 'button' | 'submit' | 'reset';

let nextReasonId = 0;

/**
 * The only button in the system. 44 px tall, square, one red accent.
 *
 * `loading` keeps the label in place and swaps in a spinner, so the button never
 * changes width mid-flight and the layout cannot jump.
 */
@Component({
  selector: 'hq-button',
  imports: [TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <button
      class="btn"
      [class]="'btn--' + variant()"
      [class.btn--block]="block()"
      [class.is-loading]="loading()"
      [attr.type]="type()"
      [disabled]="disabled() || loading()"
      [attr.aria-busy]="loading() ? 'true' : null"
      [attr.title]="reason()"
      [attr.aria-describedby]="reason() ? reasonId : null"
      (click)="pressed.emit($event)"
    >
      @if (loading()) {
        <span class="btn__spinner" aria-hidden="true"></span>
        <span class="hq-sr-only">{{ 'ui.loading' | transloco }}</span>
      }
      <span class="btn__label"><ng-content /></span>
    </button>
    @if (reason(); as reasonText) {
      <span class="hq-sr-only" [id]="reasonId">{{ reasonText }}</span>
    }
  `,
  styles: `
    @use 'mixins' as m;

    :host {
      display: inline-block;
    }

    .btn {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: var(--hq-space-8);
      block-size: var(--hq-size-button-height);
      min-inline-size: var(--hq-size-touch-target);
      padding-inline: var(--hq-space-24);
      font-size: var(--hq-font-body-size);
      font-weight: var(--hq-font-label-weight);
      border: var(--hq-size-rule) solid var(--hq-color-line);
      background: var(--hq-color-surface);
      color: var(--hq-color-ink);
      cursor: pointer;
      @include m.motion-safe('background-color, color, border-color');
      @include m.focus-ring;

      &:disabled {
        cursor: not-allowed;
        color: var(--hq-color-disabled);
        border-color: var(--hq-color-rule);
        background: var(--hq-color-surface);
      }

      &.is-loading {
        cursor: progress;
      }
    }

    .btn--block {
      inline-size: 100%;
    }

    .btn--primary {
      background: var(--hq-color-ink);
      color: var(--hq-color-on-ink);

      &:hover:not(:disabled) {
        background: var(--hq-color-accent);
        border-color: var(--hq-color-accent);
      }

      &:disabled {
        background: var(--hq-color-rule);
        color: var(--hq-color-disabled);
      }
    }

    .btn--secondary:hover:not(:disabled) {
      background: var(--hq-color-accent-soft);
    }

    // accent-strong, not accent: white on the brand red is 4.2:1 — below AA for a label.
    .btn--danger {
      background: var(--hq-color-accent-strong);
      border-color: var(--hq-color-accent-strong);
      color: var(--hq-color-on-accent);

      &:hover:not(:disabled) {
        background: var(--hq-color-ink);
        border-color: var(--hq-color-ink);
      }
    }

    .btn--quiet {
      border-color: transparent;
      background: transparent;
      padding-inline: var(--hq-space-12);

      &:hover:not(:disabled) {
        background: var(--hq-color-accent-soft);
      }
    }

    .btn__spinner {
      inline-size: var(--hq-space-16);
      block-size: var(--hq-space-16);
      border: var(--hq-size-rule) solid currentColor;
      border-block-start-color: transparent;
      border-radius: 50%;
      animation: hq-pulse-arc var(--hq-motion-slow) linear infinite;

      @include m.reduced-motion {
        animation-duration: 0ms;
        opacity: 0.5;
      }
    }
  `,
})
export class ButtonComponent {
  readonly variant = input<ButtonVariant>('secondary');
  readonly type = input<ButtonType>('button');
  readonly disabled = input(false);
  readonly loading = input(false);
  /** Fills the width of its container — used by the sticky footer on narrow screens. */
  readonly block = input(false);
  /**
   * Why the button is disabled — a native tooltip on hover, and read out on focus via
   * `aria-describedby` (a screen-reader user tabbing to a disabled button gets no `title`
   * hover, so the description is what makes "disabled until valid" followable by keyboard).
   */
  readonly reason = input<string | null>(null);

  protected readonly reasonId = `hq-btn-reason-${nextReasonId++}`;

  readonly pressed = output<MouseEvent>();
}
