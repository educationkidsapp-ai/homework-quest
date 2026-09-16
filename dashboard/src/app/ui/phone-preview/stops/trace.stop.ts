import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { ChildButtonComponent } from '../child-button.component';
import { TraceStop } from '../stop.model';

/**
 * Practice — trace (`docs/design.md` §6, screen 9): the letter or word drawn hollow and dotted, to
 * be traced with a finger, and Done underneath.
 *
 * The preview draws the lane and the dotted letter; the finger stroke itself is the app's canvas
 * (`quest.ui.trace.TraceCanvas`) and is not reproduced here — a teacher checking a lesson needs to
 * see *what* is traced, not to trace it.
 */
@Component({
  selector: 'hq-stop-trace',
  imports: [ChildButtonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="trace">
      <div class="trace__lane">
        <p class="trace__text" [attr.aria-label]="'trace ' + stop().text">{{ stop().text }}</p>
      </div>
      <p class="trace__hint">{{ stop().hint }}</p>
      <hq-child-button label="Done" emoji="✅" />
    </div>
  `,
  styles: `
    @use '../child-tokens' as child;

    .trace {
      @include child.child-stack(var(--hq-space-16));

      padding-inline: var(--hq-space-16);
    }

    .trace__lane {
      display: grid;
      place-items: center;
      inline-size: 100%;
      aspect-ratio: 3 / 2;
      border: var(--hq-child-rule) dashed var(--hq-child-sea);
      border-radius: var(--hq-child-radius-card);
      background: var(--hq-child-cream);
    }

    // Hollow, dotted letters: the outline is what the finger follows.
    .trace__text {
      margin: 0;
      font-size: var(--hq-child-pip-sm);
      font-weight: 700;
      line-height: 1;
      color: transparent;
      -webkit-text-stroke: var(--hq-size-rule) var(--hq-child-ink-soft);
    }

    .trace__hint {
      margin: 0;
      color: var(--hq-child-ink-soft);
      font-size: var(--hq-child-label);
      line-height: var(--hq-child-label-line);
      text-align: center;
    }
  `,
})
export class TraceStopComponent {
  readonly stop = input.required<TraceStop>();
}
