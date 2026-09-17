import { ChangeDetectionStrategy, Component, computed, input, model, output } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { ShakeDirective } from '../motion';

let nextId = 0;

export type InputType = 'text' | 'email' | 'password' | 'number' | 'search' | 'tel' | 'url' | 'date';

/**
 * A labelled text field: 17 px text, 2 px rule, hint and error below.
 *
 * The error is announced (`role="alert"`) and shakes once, because the system has
 * no toasts — a failure has to be visible where it happened.
 */
@Component({
  selector: 'hq-input',
  imports: [TranslocoPipe, ShakeDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="field" [hqShake]="error() ?? null">
      <label class="field__label hq-label" [attr.for]="id">
        {{ label() }}
        @if (!required()) {
          <span class="field__optional">{{ 'ui.optional' | transloco }}</span>
        }
      </label>
      <input
        class="field__control"
        [class.is-invalid]="error() !== null && error() !== ''"
        [id]="id"
        [type]="type()"
        [value]="value()"
        [attr.name]="name()"
        [attr.placeholder]="placeholder() || null"
        [attr.autocomplete]="autocomplete() || null"
        [disabled]="disabled()"
        [required]="required()"
        [attr.aria-invalid]="error() ? 'true' : null"
        [attr.aria-describedby]="describedBy()"
        (input)="onInput($event)"
        (keydown.enter)="onEnter()"
      />
      @if (hint(); as hintText) {
        <p class="field__hint" [id]="id + '-hint'">{{ hintText }}</p>
      }
      @if (error(); as errorText) {
        <p class="field__error" [id]="id + '-error'" role="alert">{{ errorText }}</p>
      }
    </div>
  `,
  styles: `
    @use 'mixins' as m;

    :host {
      display: block;
    }

    .field {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-4);
    }

    .field__label {
      @include m.label;
      display: flex;
      align-items: baseline;
      gap: var(--hq-space-8);
      color: var(--hq-color-ink);
    }

    .field__optional {
      font-weight: var(--hq-font-body-weight);
      text-transform: none;
      color: var(--hq-color-ink-soft);
    }

    .field__control {
      block-size: var(--hq-size-input-height);
      inline-size: 100%;
      padding-inline: var(--hq-space-12);
      font-size: var(--hq-font-input-size);
      background: var(--hq-color-surface);
      border: var(--hq-size-rule) solid var(--hq-color-line);
      @include m.motion-safe('border-color, background-color');
      @include m.focus-ring;

      &::placeholder {
        color: var(--hq-color-disabled);
      }

      &:disabled {
        color: var(--hq-color-disabled);
        border-color: var(--hq-color-rule);
      }

      &.is-invalid {
        border-color: var(--hq-color-accent);
        background: var(--hq-color-accent-soft);
      }
    }

    .field__hint {
      font-size: var(--hq-font-label-size);
      color: var(--hq-color-ink-soft);
    }

    .field__error {
      font-size: var(--hq-font-label-size);
      font-weight: var(--hq-font-label-weight);
      color: var(--hq-color-accent-strong);
    }
  `,
})
export class InputComponent {
  protected readonly id = `hq-input-${nextId++}`;

  readonly label = input.required<string>();
  readonly value = model('');
  readonly type = input<InputType>('text');
  readonly name = input<string | null>(null);
  readonly placeholder = input('');
  readonly hint = input<string | null>(null);
  readonly error = input<string | null>(null);
  readonly disabled = input(false);
  readonly required = input(false);
  readonly autocomplete = input<string | null>(null);
  /** When true, Enter emits `enterSubmit` — screens wire it to their one primary action. */
  readonly enterSubmit = input(false);

  readonly enterSubmitted = output<string>();

  protected readonly describedBy = computed(() => {
    const ids = [this.hint() ? `${this.id}-hint` : null, this.error() ? `${this.id}-error` : null];
    const joined = ids.filter((value): value is string => value !== null).join(' ');
    return joined.length > 0 ? joined : null;
  });

  protected onInput(event: Event): void {
    this.value.set((event.target as HTMLInputElement).value);
  }

  protected onEnter(): void {
    if (this.enterSubmit()) this.enterSubmitted.emit(this.value());
  }
}
