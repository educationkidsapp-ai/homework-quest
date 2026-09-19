import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

export type ButtonVariant = 'primary' | 'secondary' | 'danger' | 'quiet' | 'icon';
export type ButtonType = 'button' | 'submit' | 'reset';

let nextReasonId = 0;

/**
 * §3's button. 44 px tall, radius 8, 14/500, a `--hq-shadow-xs` lift on the two filled
 * variants, an explicit hover on every one and the system's focus halo.
 *
 * * **primary** — the brand fill, white label.
 * * **secondary** — surface on a 1 px control rule.
 * * **danger** — §3's *outline*: surface on the error ramp's rule, error ink. Solid red is not
 *   a variant here, because the one place this system shouts is the red band behind it.
 * * **quiet** — no box until it is hovered.
 * * **icon** — a 44×44 square for a glyph, with the label read out rather than drawn.
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
      [attr.aria-haspopup]="menu() ? 'menu' : null"
      [attr.aria-expanded]="menu() ? (expanded() ? 'true' : 'false') : null"
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
      padding: var(--hq-space-button);
      font-size: var(--hq-text-theme-sm);
      line-height: calc(var(--hq-text-theme-sm-line) / var(--hq-text-theme-sm));
      font-weight: var(--hq-text-weight-medium);
      border: var(--hq-size-rule-thin) solid transparent;
      border-radius: var(--hq-radius-control);
      background: transparent;
      color: var(--hq-color-ink-strong);
      cursor: pointer;
      @include m.motion-safe('background-color, color, border-color, box-shadow');
      @include m.focus-ring;

      &:disabled {
        cursor: not-allowed;
        color: var(--hq-color-disabled);
        box-shadow: none;
      }

      &.is-loading {
        cursor: progress;
      }
    }

    .btn--block {
      inline-size: 100%;
    }

    .btn--primary {
      background: var(--hq-color-accent);
      border-color: var(--hq-color-accent);
      color: var(--hq-color-on-accent);
      box-shadow: var(--hq-shadow-xs);

      &:hover:not(:disabled) {
        background: var(--hq-color-accent-strong);
        border-color: var(--hq-color-accent-strong);
      }

      &:disabled {
        background: var(--hq-color-divider);
        border-color: var(--hq-color-divider);
        color: var(--hq-color-disabled);
      }
    }

    .btn--secondary {
      background: var(--hq-color-surface);
      border-color: var(--hq-color-control-rule);
      box-shadow: var(--hq-shadow-xs);

      &:hover:not(:disabled) {
        background: var(--hq-color-surface-sunken);
        color: var(--hq-color-ink);
      }

      &:disabled {
        border-color: var(--hq-color-rule);
      }
    }

    // §3's danger *outline*. The error ramp, not the accent: the accent is brand-500 on this
    // palette, and a blue Delete button is a button nobody hesitates over.
    .btn--danger {
      background: var(--hq-color-surface);
      border-color: var(--hq-color-error-rule);
      color: var(--hq-color-error-ink);

      &:hover:not(:disabled) {
        background: var(--hq-color-error-soft);
      }

      &:disabled {
        border-color: var(--hq-color-rule);
      }
    }

    .btn--quiet {
      padding-inline: var(--hq-space-12);

      &:hover:not(:disabled) {
        background: var(--hq-color-surface-sunken);
        color: var(--hq-color-ink);
      }
    }

    // §3 Icon button: a 44 × 44 square on the container rule, the glyph in secondary ink.
    .btn--icon {
      @include m.icon-button;
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
  /**
   * This button opens a menu. CDK's `cdkMenuTriggerFor` puts its own `aria-haspopup` and
   * `aria-expanded` on the *host* element, which is `<hq-button>` and not focusable — so the
   * announcement never reaches the control a screen-reader user actually lands on. Setting
   * `menu` (and binding `expanded` to the trigger's state) puts them where they belong.
   */
  readonly menu = input(false);
  readonly expanded = input(false);

  protected readonly reasonId = `hq-btn-reason-${nextReasonId++}`;

  readonly pressed = output<MouseEvent>();
}
