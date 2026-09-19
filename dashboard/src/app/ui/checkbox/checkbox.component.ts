import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';

let nextId = 0;

/**
 * §1's checkbox: a 20 px radius-8 box on the control rule, filling with the brand and drawing
 * its tick over 150 ms.
 *
 * The real `<input type="checkbox">` stays in the DOM (visually hidden) so the
 * control keeps native keyboard and screen-reader behaviour; the square is drawn
 * next to it and reacts through `:checked`.
 */
@Component({
  selector: 'hq-checkbox',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <label class="check" [class.is-disabled]="disabled()">
      <input
        class="check__native hq-sr-only"
        type="checkbox"
        [id]="id"
        [checked]="checked()"
        [disabled]="disabled()"
        [attr.name]="name()"
        [attr.aria-describedby]="hint() ? id + '-hint' : null"
        (change)="onChange($event)"
      />
      <span class="check__box" aria-hidden="true">
        <svg class="check__tick" viewBox="0 0 24 24" focusable="false" aria-hidden="true">
          <path d="M5 13l4 4L19 7" />
        </svg>
      </span>
      <span class="check__text">
        <span class="check__label">{{ label() }}</span>
        @if (hint(); as hintText) {
          <span class="check__hint" [id]="id + '-hint'">{{ hintText }}</span>
        }
      </span>
    </label>
  `,
  styles: `
    @use 'mixins' as m;

    :host {
      display: block;
    }

    .check {
      display: flex;
      align-items: flex-start;
      gap: var(--hq-space-12);
      min-block-size: var(--hq-size-touch-target);
      padding-block: var(--hq-space-8);
      cursor: pointer;

      &.is-disabled {
        cursor: not-allowed;
        color: var(--hq-color-disabled);
      }
    }

    .check__box {
      flex: none;
      display: grid;
      place-items: center;
      inline-size: var(--hq-size-icon-control);
      block-size: var(--hq-size-icon-control);
      border: var(--hq-size-rule-thin) solid var(--hq-color-control-rule);
      border-radius: var(--hq-radius-control);
      background: var(--hq-color-surface);
      @include m.motion-safe('background-color, border-color, box-shadow');
    }

    .check__tick {
      inline-size: var(--hq-space-12);
      block-size: var(--hq-space-12);
      fill: none;
      stroke: var(--hq-color-on-accent);
      stroke-width: 3;
      stroke-linecap: round;
      stroke-linejoin: round;
      transform: scale(0);
      @include m.motion-safe('transform');
    }

    .check__native:checked + .check__box {
      background: var(--hq-color-accent);
      border-color: var(--hq-color-accent);
    }

    .check__native:checked + .check__box .check__tick {
      transform: scale(1);
    }

    .check__native:focus-visible + .check__box {
      border-color: var(--hq-color-focus-border);
      box-shadow: var(--hq-focus-ring);
    }

    .check__native:disabled + .check__box {
      border-color: var(--hq-color-rule);
      background: var(--hq-color-surface-sunken);
    }

    .check__text {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-4);
    }

    .check__hint {
      font-size: var(--hq-font-label-size);
      color: var(--hq-color-ink-soft);
    }
  `,
})
export class CheckboxComponent {
  protected readonly id = `hq-checkbox-${nextId++}`;

  readonly label = input.required<string>();
  readonly checked = model(false);
  readonly hint = input<string | null>(null);
  readonly name = input<string | null>(null);
  readonly disabled = input(false);

  protected onChange(event: Event): void {
    this.checked.set((event.target as HTMLInputElement).checked);
  }
}
