import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { ShakeDirective } from '../motion';

export type StepState = 'pending' | 'running' | 'done' | 'error';

export interface PipelineStep {
  readonly id: string;
  readonly label: string;
  readonly state: StepState;
  /** Shown under the label — e.g. what failed, or how many slides were read. */
  readonly detail?: string;
}

/**
 * The lesson pipeline, as a strip of steps.
 *
 * Each state has one unambiguous signal: pending is an empty square, running spins a
 * pulsing accent arc, done draws its tick with a stroke animation, error shakes once
 * and stays red. The strip is a `<ol>` and each step announces its state, so the
 * pipeline is followable without seeing the animation.
 */
@Component({
  selector: 'hq-step-strip',
  imports: [ShakeDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ol class="steps" [attr.aria-label]="label()">
      @for (step of steps(); track step.id) {
        <li class="steps__step" [class]="'steps__step--' + step.state">
          <span class="steps__glyph" [hqShake]="step.state === 'error' ? step.id : null" aria-hidden="true">
            @switch (step.state) {
              @case ('running') {
                <span class="steps__arc"></span>
              }
              @case ('done') {
                <svg class="steps__tick" viewBox="0 0 24 24" focusable="false">
                  <path d="M5 13l5 5L19 7" />
                </svg>
              }
              @case ('error') {
                <span class="steps__cross"></span>
              }
            }
          </span>
          <span class="steps__text">
            <span class="steps__label">{{ step.label }}</span>
            <span class="hq-sr-only">{{ stateLabels()[step.state] }}</span>
            @if (step.detail; as detail) {
              <span class="steps__detail">{{ detail }}</span>
            }
          </span>
        </li>
      }
    </ol>
  `,
  styles: `
    @use 'mixins' as m;

    :host {
      display: block;
    }

    .steps {
      display: flex;
      flex-wrap: wrap;
      gap: var(--hq-space-24);
    }

    .steps__step {
      display: flex;
      align-items: flex-start;
      gap: var(--hq-space-12);
      color: var(--hq-color-ink-soft);
    }

    .steps__step--running,
    .steps__step--done {
      color: var(--hq-color-ink);
    }

    .steps__step--error {
      color: var(--hq-color-accent-strong);
    }

    .steps__glyph {
      position: relative;
      flex: none;
      display: grid;
      place-items: center;
      inline-size: var(--hq-space-24);
      block-size: var(--hq-space-24);
      border: var(--hq-size-rule) solid currentColor;
      background: var(--hq-color-surface);
    }

    // Running: a pulsing accent arc, one full turn per 400 ms slot.
    .steps__arc {
      position: absolute;
      inset: calc(var(--hq-size-rule) * -1);
      border: var(--hq-size-rule) solid transparent;
      border-block-start-color: var(--hq-color-accent);
      border-inline-end-color: var(--hq-color-accent);
      animation: hq-pulse-arc var(--hq-motion-slow) linear infinite;

      @include m.reduced-motion {
        animation: none;
        border-color: var(--hq-color-accent);
      }
    }

    // Done: the tick draws itself.
    .steps__tick {
      --hq-tick-length: 24;
      inline-size: var(--hq-space-16);
      block-size: var(--hq-space-16);
      fill: none;
      stroke: var(--hq-color-ink);
      stroke-width: var(--hq-size-rule);
      stroke-linecap: square;

      path {
        stroke-dasharray: var(--hq-tick-length);
        animation: hq-draw-tick var(--hq-motion-base) var(--hq-motion-ease) both;
      }

      @include m.reduced-motion {
        path {
          animation-duration: 0ms;
        }
      }
    }

    .steps__cross {
      inline-size: var(--hq-space-12);
      block-size: var(--hq-space-12);
      background: var(--hq-color-accent);
    }

    .steps__text {
      display: flex;
      flex-direction: column;
    }

    .steps__label {
      font-weight: var(--hq-font-label-weight);
    }

    .steps__detail {
      font-size: var(--hq-font-label-size);
      color: var(--hq-color-ink-soft);
    }
  `,
})
export class StepStripComponent {
  readonly steps = input.required<readonly PipelineStep[]>();
  /** Accessible name for the list — e.g. "Lesson pipeline". */
  readonly label = input.required<string>();
  /** Translated state names, read out after each step's label. */
  readonly stateLabels = input.required<Record<StepState, string>>();
}
