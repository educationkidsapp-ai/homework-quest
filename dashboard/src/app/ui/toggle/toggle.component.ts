import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';

let nextId = 0;

/**
 * An on/off switch — used for feature flags, so it has to read as on or off at a glance.
 *
 * §1 says badges are the only pills; a switch is the exception the shape itself makes, so this
 * is a 36 × 20 pill whose knob slides 150 ms and whose track fills with the brand when on.
 * `role="switch"` on a real button keeps Space/Enter and the announced state native.
 */
@Component({
  selector: 'hq-toggle',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="toggle">
      <button
        class="toggle__control"
        type="button"
        role="switch"
        [id]="id"
        [attr.aria-checked]="checked()"
        [attr.aria-describedby]="hint() ? id + '-hint' : null"
        [disabled]="disabled()"
        (click)="toggle()"
      >
        <span class="toggle__box" aria-hidden="true"><span class="toggle__glyph"></span></span>
        <span class="toggle__label">{{ label() }}</span>
      </button>
      @if (hint(); as hintText) {
        <p class="toggle__hint" [id]="id + '-hint'">{{ hintText }}</p>
      }
    </div>
  `,
  styles: `
    @use 'mixins' as m;

    :host {
      display: block;
    }

    .toggle__control {
      display: flex;
      align-items: center;
      gap: var(--hq-space-12);
      min-block-size: var(--hq-size-touch-target);
      inline-size: 100%;
      padding: 0;
      background: none;
      border: 0;
      text-align: start;
      cursor: pointer;
      @include m.focus-ring;

      &:disabled {
        cursor: not-allowed;
        color: var(--hq-color-disabled);
      }
    }

    .toggle__box {
      position: relative;
      flex: none;
      inline-size: var(--hq-size-switch);
      block-size: var(--hq-size-icon-control);
      padding: 2px;
      border: var(--hq-size-rule-thin) solid var(--hq-color-control-rule);
      border-radius: var(--hq-radius-pill);
      background: var(--hq-color-surface-sunken);
      @include m.motion-safe('background-color, border-color, box-shadow');
    }

    .toggle__glyph {
      display: block;
      inline-size: var(--hq-space-12);
      block-size: var(--hq-space-12);
      border-radius: var(--hq-radius-pill);
      background: var(--hq-color-ink-muted);
      @include m.motion-safe('transform, background-color');
    }

    .toggle__control[aria-checked='true'] .toggle__box {
      background: var(--hq-color-accent);
      border-color: var(--hq-color-accent);
    }

    .toggle__control[aria-checked='true'] .toggle__glyph {
      background: var(--hq-color-on-accent);
      transform: translateX(var(--hq-size-switch-travel));
    }

    // RTL: the knob travels the other way, and 'translateX' does not flip itself.
    :host-context([dir='rtl']) .toggle__control[aria-checked='true'] .toggle__glyph {
      transform: translateX(calc(var(--hq-size-switch-travel) * -1));
    }

    .toggle__control:focus-visible .toggle__box {
      border-color: var(--hq-color-focus-border);
      box-shadow: var(--hq-focus-ring);
    }

    .toggle__control:disabled .toggle__box {
      border-color: var(--hq-color-rule);
      background: var(--hq-color-surface-sunken);
    }

    .toggle__control:disabled .toggle__glyph {
      background: var(--hq-color-disabled);
    }

    .toggle__hint {
      margin-inline-start: calc(var(--hq-size-switch) + var(--hq-space-12));
      font-size: var(--hq-font-label-size);
      color: var(--hq-color-ink-soft);
    }
  `,
})
export class ToggleComponent {
  protected readonly id = `hq-toggle-${nextId++}`;

  readonly label = input.required<string>();
  readonly checked = model(false);
  readonly hint = input<string | null>(null);
  readonly disabled = input(false);

  protected toggle(): void {
    if (this.disabled()) return;
    this.checked.update((value) => !value);
  }
}
