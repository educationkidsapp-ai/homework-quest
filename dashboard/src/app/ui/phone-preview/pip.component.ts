import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/** Pip's five poses (`docs/design.md` §5). */
export type PipPose = 'idle' | 'thinking' | 'celebrating' | 'sleeping' | 'waving';

/** 96 / 140 / 200 px — the three sizes the app draws Pip at. */
export type PipSize = 'sm' | 'md' | 'lg';

/**
 * Pip: a round sky-blue blob with a coral scarf, drawn procedurally (`docs/design.md` §5).
 *
 * Inline SVG rather than an asset, for the same reason the app draws him in code: his body and
 * his scarf are `--hq-mascot-color-*`, so a school theme recolours him at runtime. The SVG shapes
 * are filled from CSS (a `var()` is not valid in a presentation attribute), which is also what
 * lets one stylesheet carry all five poses.
 */
@Component({
  selector: 'hq-pip',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '[class]': "'pip pip--' + size() + ' pip--' + pose()",
    '[attr.data-hq-pose]': 'pose()',
    '[attr.role]': "label() ? 'img' : null",
    '[attr.aria-label]': 'label()',
    '[attr.aria-hidden]': "label() ? null : 'true'",
  },
  template: `
    <svg class="pip__svg" viewBox="0 0 120 120" focusable="false" aria-hidden="true">
      <g class="pip__body-group">
        <path class="pip__tuft" d="M34 30 L44 8 L54 30 Z" />
        <path class="pip__tuft" d="M66 30 L76 8 L86 30 Z" />
        <circle class="pip__body" cx="60" cy="64" r="42" />
        <ellipse class="pip__belly" cx="60" cy="76" rx="26" ry="24" />

        @switch (pose()) {
          @case ('sleeping') {
            <path class="pip__lid" d="M36 58 q10 10 20 0" />
            <path class="pip__lid" d="M64 58 q10 10 20 0" />
            <text class="pip__zzz" x="86" y="34">z</text>
            <text class="pip__zzz pip__zzz--small" x="100" y="20">z</text>
          }
          @case ('thinking') {
            <circle class="pip__eye" cx="46" cy="58" r="9" />
            <circle class="pip__eye" cx="74" cy="58" r="9" />
            <circle class="pip__pupil" cx="42" cy="53" r="4" />
            <circle class="pip__pupil" cx="70" cy="53" r="4" />
          }
          @default {
            <circle class="pip__eye" cx="46" cy="58" r="9" />
            <circle class="pip__eye" cx="74" cy="58" r="9" />
            <circle class="pip__pupil" cx="46" cy="59" r="4" />
            <circle class="pip__pupil" cx="74" cy="59" r="4" />
          }
        }

        <path class="pip__beak" d="M54 72 L66 72 L60 82 Z" />
        <path class="pip__scarf" d="M32 90 q28 14 56 0 l0 12 q-28 14 -56 0 Z" />
        <path class="pip__scarf-tail" d="M78 96 l16 6 l-4 16 l-14 -10 Z" />

        <ellipse class="pip__wing pip__wing--start" cx="20" cy="74" rx="10" ry="18" />
        <ellipse class="pip__wing pip__wing--end" cx="100" cy="74" rx="10" ry="18" />
      </g>
    </svg>
  `,
  styles: `
    @use 'mixins' as m;
    @use './child-tokens' as child;

    :host {
      display: block;
      inline-size: var(--hq-child-pip-md);
      block-size: var(--hq-child-pip-md);
      flex: none;
    }

    :host(.pip--sm) {
      inline-size: var(--hq-child-pip-sm);
      block-size: var(--hq-child-pip-sm);
    }

    :host(.pip--lg) {
      inline-size: var(--hq-child-pip-lg);
      block-size: var(--hq-child-pip-lg);
    }

    .pip__svg {
      inline-size: 100%;
      block-size: 100%;
      overflow: visible;
    }

    .pip__body,
    .pip__tuft {
      fill: var(--hq-child-pip);
    }

    .pip__belly {
      fill: var(--hq-child-cream);
    }

    .pip__wing {
      fill: var(--hq-child-pip-dark);
      transform-origin: 50% 62%;
    }

    .pip__eye {
      fill: var(--hq-child-cream);
    }

    .pip__pupil {
      fill: var(--hq-child-ink);
    }

    .pip__lid {
      fill: none;
      stroke: var(--hq-child-ink);
      stroke-width: 4;
      stroke-linecap: round;
    }

    .pip__beak {
      fill: var(--hq-child-sun-deep);
    }

    .pip__scarf,
    .pip__scarf-tail {
      fill: var(--hq-child-coral);
    }

    .pip__zzz {
      fill: var(--hq-child-ink-soft);
      font-family: var(--hq-child-font);
      font-size: var(--hq-child-title);
      font-weight: 700;
    }

    .pip__zzz--small {
      font-size: var(--hq-child-label);
    }

    // --- poses ---------------------------------------------------------------
    // Nothing here moves under prefers-reduced-motion: the child-animation mixin
    // collapses every duration to 0 ms, which leaves the end pose drawn and still.

    :host(.pip--waving) .pip__wing--end {
      @include child.child-animation(hq-pip-wave 900ms var(--hq-motion-ease) infinite alternate);
    }

    :host(.pip--celebrating) .pip__wing--start,
    :host(.pip--celebrating) .pip__wing--end {
      transform: rotate(-40deg);
    }

    :host(.pip--celebrating) .pip__wing--start {
      transform: rotate(40deg);
    }

    :host(.pip--celebrating) .pip__body-group {
      @include child.child-animation(hq-pip-bounce 700ms var(--hq-motion-ease) infinite);
    }

    :host(.pip--thinking) .pip__wing--end {
      transform: rotate(-55deg) translateY(var(--hq-space-8));
    }

    :host(.pip--sleeping) .pip__zzz {
      @include child.child-animation(hq-pip-float 1600ms var(--hq-motion-ease) infinite);
    }

    @keyframes hq-pip-wave {
      from {
        transform: rotate(0deg);
      }

      to {
        transform: rotate(-45deg);
      }
    }

    @keyframes hq-pip-bounce {
      0%,
      100% {
        transform: translateY(0);
      }

      50% {
        transform: translateY(calc(var(--hq-space-4) * -1));
      }
    }

    @keyframes hq-pip-float {
      0%,
      100% {
        opacity: 0.4;
      }

      50% {
        opacity: 1;
      }
    }
  `,
})
export class PipComponent {
  readonly pose = input<PipPose>('idle');
  readonly size = input<PipSize>('md');
  /** Accessible name. Left null, Pip is decoration and is hidden from assistive tech. */
  readonly label = input<string | null>(null);
}
