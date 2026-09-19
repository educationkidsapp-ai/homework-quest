import { ChangeDetectionStrategy, Component, computed, input, model, output } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { ShakeDirective } from '../motion';

let nextId = 0;

export type InputType = 'text' | 'email' | 'password' | 'number' | 'search' | 'tel' | 'url' | 'date';

/**
 * §3's input: 44 px, radius 8, a 1 px rule, `--hq-shadow-xs`, 14 px text and a muted
 * placeholder, with the label above and hint and error below.
 *
 * A leading icon (`[input-icon]`) pushes the text to 48 px; a trailing `keycap` — the `/` on a
 * search field — reserves 56 px, so neither ever sits under the caret.
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
      <label class="field__label" [attr.for]="id">
        {{ label() }}
        @if (!required()) {
          <span class="field__optional">{{ 'ui.optional' | transloco }}</span>
        }
      </label>
      <div class="field__shell">
        @if (icon()) {
          <span class="field__icon" aria-hidden="true"><ng-content select="[input-icon]" /></span>
        }
        <input
          class="field__control"
          [class.field__control--icon]="icon()"
          [class.field__control--keycap]="keycap() !== null"
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
        @if (keycap(); as key) {
          <kbd class="field__keycap" aria-hidden="true">{{ key }}</kbd>
        }
      </div>
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
      font-size: var(--hq-font-label-size);
      line-height: calc(var(--hq-font-label-line) / var(--hq-font-label-size));
      font-weight: var(--hq-text-weight-medium);
      display: flex;
      align-items: baseline;
      gap: var(--hq-space-8);
      color: var(--hq-color-ink-strong);
    }

    .field__optional {
      font-weight: var(--hq-font-body-weight);
      text-transform: none;
      color: var(--hq-color-ink-soft);
    }

    .field__shell {
      position: relative;
      display: block;
    }

    .field__control {
      @include m.control;
      display: block;
      inline-size: 100%;
      background: var(--hq-color-surface);
      @include m.motion-safe('border-color, background-color, box-shadow');
      @include m.focus-ring;

      &:disabled {
        color: var(--hq-color-disabled);
        background: var(--hq-color-surface-sunken);
      }

      // §5's error state is the ramp, not the accent: on this palette the accent is brand-500,
      // and a field that turns blue when it is wrong is a field nobody reads as wrong.
      &.is-invalid {
        border-color: var(--hq-color-error-500);
        background: var(--hq-color-error-soft);
        color: var(--hq-color-error-ink);
      }
    }

    .field__control--icon {
      padding-inline-start: var(--hq-space-48);
    }

    .field__control--keycap {
      padding-inline-end: calc(var(--hq-space-48) + var(--hq-space-8));
    }

    .field__icon {
      position: absolute;
      inset-block-start: 50%;
      inset-inline-start: var(--hq-space-16);
      inline-size: var(--hq-size-icon-control);
      block-size: var(--hq-size-icon-control);
      transform: translateY(-50%);
      color: var(--hq-color-ink-muted);
      pointer-events: none;
    }

    .field__keycap {
      @include m.keycap;
      position: absolute;
      inset-block-start: 50%;
      inset-inline-end: var(--hq-space-12);
      transform: translateY(-50%);
      pointer-events: none;
    }

    .field__hint {
      font-size: var(--hq-font-label-size);
      color: var(--hq-color-ink-soft);
    }

    .field__error {
      font-size: var(--hq-font-label-size);
      font-weight: var(--hq-font-label-weight);
      color: var(--hq-color-error-ink);
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
  /** Reserve the leading 48 px for a glyph projected into `[input-icon]` (§3 Input). */
  readonly icon = input(false);
  /** The trailing key hint, e.g. `/` on the shell's search field. */
  readonly keycap = input<string | null>(null);
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
