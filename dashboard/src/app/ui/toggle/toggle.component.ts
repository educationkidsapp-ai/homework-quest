import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';

let nextId = 0;

/**
 * An on/off switch — used for feature flags, so it has to read as on or off at a glance.
 *
 * No sliding pill: the square glyph fills, scaling from 0.6 to 1 over 150 ms.
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
      flex: none;
      display: grid;
      place-items: center;
      inline-size: var(--hq-space-32);
      block-size: var(--hq-space-24);
      border: var(--hq-size-rule) solid var(--hq-color-line);
      background: var(--hq-color-surface);
    }

    .toggle__glyph {
      inline-size: var(--hq-space-16);
      block-size: var(--hq-space-12);
      background: var(--hq-color-accent);
      opacity: 0;
      transform: scale(0.6);
      @include m.motion-safe('transform, opacity');
    }

    .toggle__control[aria-checked='true'] .toggle__glyph {
      opacity: 1;
      transform: scale(1);
    }

    .toggle__control:disabled .toggle__box {
      border-color: var(--hq-color-rule);
    }

    .toggle__control:disabled .toggle__glyph {
      background: var(--hq-color-disabled);
    }

    .toggle__hint {
      margin-inline-start: calc(var(--hq-space-32) + var(--hq-space-12));
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
