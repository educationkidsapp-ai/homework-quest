import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { NumberLine } from './stop.model';

interface Mark {
  readonly value: number;
  readonly highlighted: boolean;
}

/**
 * The `sea` number line the numeric stops jump along, and the one picture the hint sheet shows
 * for a numeric question (`docs/design.md` §6, screen 11).
 */
@Component({
  selector: 'hq-number-line',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ol class="line" [attr.aria-label]="label()">
      @for (mark of marks(); track mark.value) {
        <li class="line__mark" [class.line__mark--on]="mark.highlighted">{{ mark.value }}</li>
      }
    </ol>
  `,
  styles: `
    @use './child-tokens' as child;

    :host {
      display: block;
      inline-size: 100%;
      overflow-x: auto;
    }

    .line {
      display: flex;
      align-items: center;
      gap: var(--hq-space-4);
      margin: 0;
      padding: var(--hq-space-8);
      list-style: none;
      background: var(--hq-child-sea);
      border-radius: var(--hq-child-radius-chip);
    }

    .line__mark {
      display: grid;
      place-items: center;
      flex: none;
      inline-size: var(--hq-space-32);
      block-size: var(--hq-space-32);
      border-radius: 50%;
      background: transparent;
      color: var(--hq-child-ink);
      font-size: var(--hq-child-label);
      font-weight: 700;
    }

    .line__mark--on {
      background: var(--hq-child-sun);
    }
  `,
})
export class NumberLineComponent {
  readonly line = input.required<NumberLine>();
  readonly label = input<string>('Number line');

  protected readonly marks = computed<readonly Mark[]>(() => {
    const { from, to, step, highlight } = this.line();
    const stride = step > 0 ? step : 1;
    const marks: Mark[] = [];
    for (let value = from; value <= to; value += stride) {
      marks.push({ value, highlighted: highlight?.includes(value) ?? false });
    }
    return marks;
  });
}
