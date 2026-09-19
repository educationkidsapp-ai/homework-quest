import { ChangeDetectionStrategy, Component, computed, input, model } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { ShakeDirective } from '../motion';

let nextId = 0;

/**
 * `hq-input`'s multi-line sibling: the same label, rule, hint and shaking error, over a
 * `<textarea>` whose height the caller sets in `rows`.
 *
 * It exists because three N2.4 surfaces need prose rather than a line — the stop editor's JSON
 * document, the parent panel's EN/AR paragraphs and "Generate the other levels" — and pushing
 * a `type` onto `hq-input` would mean one component with two shapes and two sets of styles.
 * `mono` switches to the code face for JSON, where a proportional font hides a stray comma.
 */
@Component({
  selector: 'hq-textarea',
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
      <textarea
        class="field__control"
        [class.field__control--mono]="mono()"
        [class.is-invalid]="error() !== null && error() !== ''"
        [id]="id"
        [rows]="rows()"
        [value]="value()"
        [attr.name]="name()"
        [attr.placeholder]="placeholder() || null"
        [attr.spellcheck]="mono() ? 'false' : null"
        [attr.dir]="dir()"
        [disabled]="disabled()"
        [required]="required()"
        [attr.aria-invalid]="error() ? 'true' : null"
        [attr.aria-describedby]="describedBy()"
        (input)="onInput($event)"
      ></textarea>
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

    .field__control {
      @include m.control;
      display: block;
      inline-size: 100%;
      font-family: inherit;
      background: var(--hq-color-surface);
      resize: vertical;
      @include m.motion-safe('border-color, background-color, box-shadow');
      @include m.focus-ring;

      &--mono {
        font-family: var(--hq-font-family-mono);
        font-size: var(--hq-text-theme-xs);
      }

      &::placeholder {
        color: var(--hq-color-ink-muted);
      }

      &:disabled {
        color: var(--hq-color-disabled);
        background: var(--hq-color-surface-sunken);
      }

      &.is-invalid {
        border-color: var(--hq-color-error-500);
        background: var(--hq-color-error-soft);
        color: var(--hq-color-error-ink);
      }
    }

    .field__hint {
      font-size: var(--hq-font-label-size);
      color: var(--hq-color-ink-soft);
    }

    .field__error {
      font-size: var(--hq-font-label-size);
      font-weight: var(--hq-font-label-weight);
      color: var(--hq-color-error-ink);
      white-space: pre-line;
    }
  `,
})
export class TextareaComponent {
  protected readonly id = `hq-textarea-${nextId++}`;

  readonly label = input.required<string>();
  readonly value = model('');
  readonly rows = input(4);
  readonly name = input<string | null>(null);
  readonly placeholder = input('');
  readonly hint = input<string | null>(null);
  readonly error = input<string | null>(null);
  readonly disabled = input(false);
  readonly required = input(false);
  /** JSON and other code: the mono face, no spellcheck, LTR whatever the page direction. */
  readonly mono = input(false);
  /** `rtl` on the Arabic half of a bilingual pair; `null` follows the page. */
  readonly dir = input<'ltr' | 'rtl' | null>(null);

  protected readonly describedBy = computed(() => {
    const ids = [this.hint() ? `${this.id}-hint` : null, this.error() ? `${this.id}-error` : null];
    const joined = ids.filter((part) => part !== null).join(' ');
    return joined.length > 0 ? joined : null;
  });

  protected onInput(event: Event): void {
    this.value.set((event.target as HTMLTextAreaElement).value);
  }
}
