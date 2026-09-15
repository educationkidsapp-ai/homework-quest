// GENERATED FILE — do not edit.
// Source: design/tokens.json (`motion` group). Regenerate with `pnpm tokens`.

/** Durations in milliseconds. Pass them through `MotionService.duration()` so reduced motion wins. */
export const MOTION_MS = {
  fast: 150,
  base: 250,
  slow: 400,
  countUp: 600,
  shake: 300,
  stagger: 30,
  skeletonDelay: 300,
  shimmer: 1200,
  undoWindow: 10000,
} as const;

/** Easing curves, matching `--hq-motion-ease` and `--hq-motion-ease-emphasised`. */
export const MOTION_EASING = {
  ease: 'cubic-bezier(0.2, 0, 0, 1)',
  easeEmphasised: 'cubic-bezier(0.05, 0.7, 0.1, 1)',
} as const;
