import { ChangeDetectionStrategy, Component, computed, input, model, output } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { ShakeDirective } from '../motion';

let nextId = 0;

export interface SelectOption<T extends string = string> {
  readonly value: T;
  readonly label: string;
  readonly disabled?: boolean;
}

/** A headed run of options — an `<optgroup>`, for a list long enough to need signposts. */
export interface SelectOptionGroup<T extends string = string> {
  readonly label: string;
  readonly options: readonly SelectOption<T>[];
}

/**
 * A native `<select>` in the system's clothes.
 *
 * Native on purpose: it is keyboard- and screen-reader-correct everywhere, and it
 * is the one control where a custom listbox would buy nothing. The chevron is drawn
 * with a border, so no icon font or SVG asset is needed.
 */
@Component({
  selector: 'hq-select',
  imports: [TranslocoPipe, ShakeDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="field" [hqShake]="error() ?? null">
      <label class="field__label" [attr.for]="id">
        {{ label() }}
        @if (optionalMark()) {
          <span class="field__optional">{{ 'ui.optional' | transloco }}</span>
        }
      </label>
      <div class="field__shell">
        <select
          class="field__control"
          [class.is-invalid]="error() !== null && error() !== ''"
          [id]="id"
          [disabled]="disabled()"
          [required]="required()"
          [attr.name]="name()"
          [attr.aria-invalid]="error() ? 'true' : null"
          [attr.aria-describedby]="describedBy()"
          (change)="onChange($event)"
          (blur)="blurred.emit()"
        >
          @if (placeholder(); as placeholderText) {
            <!--
              [selected] on the option, not [value] on the select: the select element's own
              value write can land before the @for block below has inserted the option it
              needs to match — options and the current value often change in the same tick, a
              course chooser going from "nothing loaded" to "one option, picked for you" being
              exactly that — and a write that finds nothing to select is never retried once the
              expression driving it stops changing. Binding selected on each option instead
              ties the selection to the same creation/update pass as the option itself, so
              there is nothing to race. Also not the disabled attribute here: a select whose
              selected option becomes disabled falls back to the first enabled one instead,
              silently — a field could show "Math" pre-selected while the value stayed empty.
            -->
            <option value="" [selected]="value() === ''">{{ placeholderText }}</option>
          }
          @for (option of options(); track option.value) {
            <option
              [value]="option.value"
              [selected]="option.value === value()"
              [disabled]="option.disabled ?? false"
            >
              {{ option.label }}
            </option>
          }
          @for (group of groups(); track group.label) {
            <optgroup [label]="group.label">
              @for (option of group.options; track option.value) {
                <option
                  [value]="option.value"
                  [selected]="option.value === value()"
                  [disabled]="option.disabled ?? false"
                >
                  {{ option.label }}
                </option>
              }
            </optgroup>
          }
        </select>
        <span class="field__chevron" aria-hidden="true"></span>
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
      font-size: var(--hq-text-theme-sm);
      line-height: calc(var(--hq-text-theme-sm-line) / var(--hq-text-theme-sm));
      font-weight: var(--hq-text-weight-medium);
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
      appearance: none;
      inline-size: 100%;
      block-size: var(--hq-size-control-height);
      padding-inline: var(--hq-space-16) var(--hq-space-32);
      background: var(--hq-color-surface);
      @include m.motion-safe('border-color, background-color, box-shadow');
      @include m.focus-ring;

      &:disabled {
        color: var(--hq-color-disabled);
        background: var(--hq-color-surface-sunken);
      }

      // §5's error state is the ramp, not the accent — the same one hq-input wears.
      &.is-invalid {
        border-color: var(--hq-color-error-500);
        background: var(--hq-color-error-soft);
        color: var(--hq-color-error-ink);
      }
    }

    // A chevron made of two borders — nothing to download.
    .field__chevron {
      position: absolute;
      inset-block-start: 50%;
      inset-inline-end: var(--hq-space-16);
      inline-size: var(--hq-space-8);
      block-size: var(--hq-space-8);
      border-inline-end: var(--hq-size-rule-thin) solid var(--hq-color-ink-soft);
      border-block-end: var(--hq-size-rule-thin) solid var(--hq-color-ink-soft);
      transform: translateY(-75%) rotate(45deg);
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
export class SelectComponent<T extends string = string> {
  protected readonly id = `hq-select-${nextId++}`;

  readonly label = input.required<string>();
  /** Flat options. A caller with signposted runs passes `groups` instead (or as well). */
  readonly options = input<readonly SelectOption<T>[]>([]);
  readonly groups = input<readonly SelectOptionGroup<T>[]>([]);
  readonly value = model<T | ''>('');
  readonly placeholder = input<string | null>(null);
  readonly hint = input<string | null>(null);
  /** What is wrong with the choice, announced and shaken exactly as `hq-input`'s is. */
  readonly error = input<string | null>(null);
  readonly name = input<string | null>(null);
  readonly disabled = input(false);
  readonly required = input(false);
  /**
   * Opt-in, unlike `hq-input`'s: most selects on this dashboard predate `required` and would
   * suddenly all read "(optional)". A form that marks its required fields asks for it.
   */
  readonly markOptional = input(false);

  readonly blurred = output<void>();

  protected readonly optionalMark = computed(() => this.markOptional() && !this.required());

  protected readonly describedBy = computed(() => {
    const ids = [this.hint() ? `${this.id}-hint` : null, this.error() ? `${this.id}-error` : null];
    const joined = ids.filter(Boolean).join(' ');
    return joined === '' ? null : joined;
  });

  protected onChange(event: Event): void {
    this.value.set((event.target as HTMLSelectElement).value as T);
  }
}
