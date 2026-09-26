import { ChangeDetectionStrategy, Component, computed, inject, input, model } from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { activeLang } from '../../core/i18n/active-lang';
import { InputComponent, type SelectOption, SelectComponent, TextareaComponent } from '../../ui';
import {
  CAPS,
  type ChoiceValue,
  type StructuredType,
  type StructuredValue,
  EMPTY_STRUCTURED,
} from './structured-stop';

/**
 * E4b: the fields for the five types the sheet saves by itself — see `structured-stop.ts`.
 *
 * It holds no state of its own beyond the two-way {@link value}: the sheet owns the object, the
 * sheet computes the problems (from `structuredProblems`, so the same rules are unit-testable
 * without a DOM), and this draws them under the field each belongs to.
 *
 * A choice question's sub-form appears once for `choice` and three times for `exitTicket`, which
 * the schema requires to hold exactly three questions — the same markup over {@link slots}, so
 * the two cannot drift apart.
 */
@Component({
  selector: 'hq-structured-fields',
  imports: [InputComponent, TextareaComponent, SelectComponent, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="fields" data-hq-stop-fields>
      @switch (type()) {
        @case ('trueFalse') {
          <hq-input
            [label]="'lessons.detail.addStop.fields.statement' | transloco"
            [hint]="'lessons.detail.addStop.fields.statementHint' | transloco"
            [required]="true"
            [maxLength]="CAPS.statement"
            [error]="errorFor('statement')"
            [value]="value().statement"
            (valueChange)="value.set({ ...value(), statement: $event })"
          />
          <hq-select
            [label]="'lessons.detail.addStop.fields.correctTrueFalse' | transloco"
            [options]="answerOptions()"
            [required]="true"
            [value]="value().answer ? 'true' : 'false'"
            (valueChange)="value.set({ ...value(), answer: $event === 'true' })"
          />
        }
        @case ('writeSentence') {
          <hq-input
            [label]="'lessons.detail.addStop.fields.sentence' | transloco"
            [hint]="'lessons.detail.addStop.fields.sentenceHint' | transloco"
            [required]="true"
            [maxLength]="CAPS.frame"
            [error]="errorFor('frame')"
            [value]="value().frame"
            (valueChange)="value.set({ ...value(), frame: $event })"
          />
          <hq-input
            [label]="'lessons.detail.addStop.fields.blank' | transloco"
            [hint]="'lessons.detail.addStop.fields.blankHint' | transloco"
            [required]="true"
            [maxLength]="CAPS.answer"
            [error]="errorFor('answer')"
            [value]="value().blankAnswer"
            (valueChange)="value.set({ ...value(), blankAnswer: $event })"
          />
          <fieldset class="fields__group">
            <legend class="fields__legend">{{ 'lessons.detail.addStop.fields.others' | transloco }}</legend>
            <p class="fields__hint">{{ 'lessons.detail.addStop.fields.othersHint' | transloco }}</p>
            <div class="fields__row">
              @for (word of value().distractors; track $index) {
                <hq-input
                  [label]="t('lessons.detail.addStop.fields.other', { index: $index + 1 })"
                  [maxLength]="CAPS.answer"
                  [value]="word"
                  (valueChange)="setDistractor($index, $event)"
                />
              }
            </div>
            @if (errorFor('distractors'); as problem) {
              <p class="fields__error" role="alert">{{ problem }}</p>
            }
          </fieldset>
        }
        @case ('readPage') {
          <hq-textarea
            [label]="'lessons.detail.addStop.fields.page' | transloco"
            [hint]="'lessons.detail.addStop.fields.pageHint' | transloco"
            [rows]="6"
            [required]="true"
            [error]="errorFor('page')"
            [value]="value().page"
            (valueChange)="value.set({ ...value(), page: $event })"
          />
        }
        @default {
          @for (slot of slots(); track slot.prefix) {
            <fieldset class="fields__group">
              @if (slot.index >= 0) {
                <legend class="fields__legend">
                  {{ t('lessons.detail.addStop.fields.ticketQuestion', { index: slot.index + 1 }) }}
                </legend>
              }
              <hq-input
                [label]="'lessons.detail.addStop.fields.question' | transloco"
                [hint]="'lessons.detail.addStop.fields.questionHint' | transloco"
                [required]="true"
                [maxLength]="CAPS.question"
                [error]="errorFor(slot.prefix + 'question')"
                [value]="slot.choice.question"
                (valueChange)="setChoice(slot.index, { question: $event })"
              />
              <div class="fields__row">
                @for (option of slot.choice.options; track $index) {
                  <hq-input
                    [label]="t('lessons.detail.addStop.fields.answer', { index: $index + 1 })"
                    [maxLength]="CAPS.option"
                    [value]="option"
                    (valueChange)="setOption(slot.index, $index, $event)"
                  />
                }
              </div>
              @if (errorFor(slot.prefix + 'options'); as problem) {
                <p class="fields__error" role="alert">{{ problem }}</p>
              }
              <hq-select
                [label]="'lessons.detail.addStop.fields.correct' | transloco"
                [options]="correctOptions(slot.choice)"
                [required]="true"
                [error]="errorFor(slot.prefix + 'correct')"
                [value]="slotOf(slot.choice.correct)"
                (valueChange)="setCorrect(slot.index, $event)"
              />
            </fieldset>
          }
        }
      }
    </div>
  `,
  styles: `
    @use 'mixins' as m;

    .fields {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-24);
    }

    .fields__group {
      border: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-8);
    }

    .fields__legend {
      font-size: var(--hq-text-theme-sm);
      font-weight: var(--hq-text-weight-medium);
      color: var(--hq-color-ink-strong);
    }

    .fields__hint {
      font-size: var(--hq-font-label-size);
      color: var(--hq-color-ink-soft);
    }

    .fields__error {
      font-size: var(--hq-font-label-size);
      font-weight: var(--hq-font-label-weight);
      color: var(--hq-color-error-ink);
    }

    .fields__row {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: var(--hq-space-12);
    }

    @include m.below(m.$sheet-breakpoint) {
      .fields__row {
        grid-template-columns: 1fr;
      }
    }
  `,
})
export class StructuredFieldsComponent {
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  protected readonly CAPS = CAPS;

  readonly type = input.required<StructuredType>();
  readonly value = model<StructuredValue>(EMPTY_STRUCTURED);
  /** Field → message, from `structuredProblems`, already translated by the sheet. */
  readonly problems = input<ReadonlyMap<string, string>>(new Map());
  /** Problems are drawn once she has pressed Save, as everywhere else in this sheet. */
  readonly show = input(false);

  /** One sub-form for `choice`, three for an exit ticket. `index` is −1 for the single one. */
  protected readonly slots = computed<readonly { prefix: string; choice: ChoiceValue; index: number }[]>(
    () => {
      const value = this.value();
      if (this.type() !== 'exitTicket') return [{ prefix: '', choice: value.choice, index: -1 }];
      return value.ticket.map((choice, index) => ({ prefix: `q${index + 1}.`, choice, index }));
    },
  );

  protected readonly answerOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return [
      { value: 'true', label: this.t('lessons.detail.addStop.fields.true') },
      { value: 'false', label: this.t('lessons.detail.addStop.fields.false') },
    ];
  });

  /** The right answer is picked by its own words, so a blank slot still has something to show. */
  protected correctOptions(choice: ChoiceValue): readonly SelectOption[] {
    this.lang();
    return choice.options.map((option, index) => ({
      value: String(index),
      label: option.trim() || this.t('lessons.detail.addStop.fields.answer', { index: index + 1 }),
    }));
  }

  /** `hq-select` speaks in strings; which slot is right is an index. */
  protected slotOf(correct: number): string {
    return String(correct);
  }

  protected setCorrect(index: number, slot: string): void {
    this.setChoice(index, { correct: Number(slot) });
  }

  protected errorFor(field: string): string | null {
    if (!this.show()) return null;
    return this.problems().get(field) ?? null;
  }

  protected setChoice(index: number, patch: Partial<ChoiceValue>): void {
    this.value.update((value) => {
      if (index < 0) return { ...value, choice: { ...value.choice, ...patch } };
      const ticket = value.ticket.map((choice, at) => (at === index ? { ...choice, ...patch } : choice));
      return { ...value, ticket };
    });
  }

  protected setOption(index: number, slot: number, text: string): void {
    const choice = index < 0 ? this.value().choice : this.value().ticket[index]!;
    const options = choice.options.map((option, at) => (at === slot ? text : option));
    this.setChoice(index, { options });
  }

  protected setDistractor(index: number, text: string): void {
    this.value.update((value) => ({
      ...value,
      distractors: value.distractors.map((word, at) => (at === index ? text : word)),
    }));
  }

  protected t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate(key, params);
  }
}
