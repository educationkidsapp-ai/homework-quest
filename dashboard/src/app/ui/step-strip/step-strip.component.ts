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
 * The lesson pipeline, as §3's timeline: a 22 px round marker tinted from the matching ramp,
 * a 2 px connector between markers, and a 14/500 name over a 13 px secondary note.
 *
 * Each state keeps one unambiguous signal on top of the tint — `–` pending, a pulsing arc
 * running, `✓` done, `!` error — so the pipeline is followable in greyscale and without seeing
 * the animation. The strip is an `<ol>` and each step announces its state.
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
                !
              }
              @default {
                –
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
      position: relative;
      display: flex;
      align-items: flex-start;
      gap: var(--hq-space-12);
      color: var(--hq-color-ink-soft);
    }

    // §3's 2 px connector. It runs from this marker to the next one, so the last step has
    // none — and it sits behind the markers, which are opaque.
    .steps__step:not(:last-child)::after {
      content: '';
      position: absolute;
      inset-block-start: calc(var(--hq-size-timeline-marker) / 2);
      inset-inline-start: var(--hq-size-timeline-marker);
      inline-size: var(--hq-space-24);
      block-size: var(--hq-size-rule);
      background: var(--hq-color-rule);
    }

    .steps__step--running,
    .steps__step--done {
      color: var(--hq-color-ink);
    }

    .steps__step--error {
      color: var(--hq-color-error-ink);
    }

    .steps__glyph {
      position: relative;
      z-index: 1;
      flex: none;
      display: grid;
      place-items: center;
      inline-size: var(--hq-size-timeline-marker);
      block-size: var(--hq-size-timeline-marker);
      border-radius: var(--hq-radius-pill);
      font-size: var(--hq-text-theme-xs);
      font-weight: var(--hq-text-weight-semibold);
      line-height: 1;
      background: var(--hq-color-neutral-soft);
      color: var(--hq-color-ink-muted);
    }

    .steps__step--running .steps__glyph {
      background: var(--hq-color-accent-soft);
      color: var(--hq-color-accent-on-soft);
    }

    .steps__step--done .steps__glyph {
      background: var(--hq-color-success-soft);
      color: var(--hq-color-success-ink);
    }

    .steps__step--error .steps__glyph {
      background: var(--hq-color-error-soft);
      color: var(--hq-color-error-ink);
    }

    // Running: a pulsing accent arc, one full turn per 400 ms slot.
    .steps__arc {
      position: absolute;
      inset: 0;
      border: var(--hq-size-rule) solid transparent;
      border-radius: var(--hq-radius-pill);
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
      inline-size: var(--hq-space-12);
      block-size: var(--hq-space-12);
      fill: none;
      stroke: currentColor;
      stroke-width: 3;
      stroke-linecap: round;
      stroke-linejoin: round;

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

    .steps__text {
      display: flex;
      flex-direction: column;
    }

    .steps__label {
      font-size: var(--hq-text-theme-sm);
      font-weight: var(--hq-text-weight-medium);
    }

    .steps__detail {
      font-size: var(--hq-text-note);
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
