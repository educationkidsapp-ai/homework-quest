import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, booleanAttribute, input, output } from '@angular/core';

/** Cream by default; mint once the child has dealt with the card; peach for the hint sheet. */
export type ChildCardTone = 'cream' | 'mint' | 'peach' | 'sand';

/**
 * The full-width rounded card most child content sits on (`BigCard` in `shared-ui`).
 *
 * `interactive` makes it a real `<button>` rather than a div with a click handler, so it is
 * reachable with Tab and Enter like everything else in the system.
 */
@Component({
  selector: 'hq-child-card',
  imports: [NgTemplateOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  // One `<ng-content>`, handed to whichever element the card turns out to be: a second slot with
  // the same selector would never be filled.
  template: `
    <ng-template #body><ng-content /></ng-template>

    @if (interactive()) {
      <button
        type="button"
        [class]="'card card--tap card--' + tone()"
        [class.card--selected]="selected()"
        [disabled]="disabled()"
        (click)="pressed.emit()"
      >
        <ng-container [ngTemplateOutlet]="body" />
      </button>
    } @else {
      <div [class]="'card card--' + tone()" [class.card--selected]="selected()">
        <ng-container [ngTemplateOutlet]="body" />
      </div>
    }
  `,
  styles: `
    @use 'mixins' as m;
    @use './child-tokens' as child;

    :host {
      display: block;
      inline-size: 100%;
    }

    .card {
      @include child.child-card;

      display: block;
      font-size: var(--hq-child-body);
      line-height: var(--hq-child-body-line);
    }

    .card--tap {
      @include child.child-target;
      @include m.motion-safe('background-color, opacity');

      &:disabled {
        @include child.child-dimmed;
      }
    }

    .card--mint {
      background: var(--hq-child-mint);
    }

    .card--peach {
      background: var(--hq-child-peach);
    }

    .card--sand {
      background: var(--hq-child-sand);
    }

    .card--selected {
      outline: var(--hq-child-rule) solid var(--hq-child-sun-deep);
      outline-offset: calc(var(--hq-child-rule) * -1);
    }
  `,
})
export class ChildCardComponent {
  readonly tone = input<ChildCardTone>('cream');
  readonly interactive = input(false, { transform: booleanAttribute });
  readonly selected = input(false, { transform: booleanAttribute });
  readonly disabled = input(false, { transform: booleanAttribute });

  readonly pressed = output<void>();
}
