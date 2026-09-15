/**
 * The named motion triggers. Nothing outside this folder may declare an animation.
 *
 * `@angular/animations` is deprecated as of Angular 22 (the package tells you to use
 * `animate.enter` / `animate.leave` instead), so each trigger below is a CSS class
 * whose keyframes live in `src/styles/_motion.scss`, applied either through Angular's
 * native `animate.enter` binding or by the directives in this folder. The names and
 * the durations are unchanged from the design brief.
 */
export const MOTION = {
  /** 24 px slide in the navigation direction + fade, 400 ms. */
  pageEnter: 'hq-anim-page-enter',
  /** Fade + rise, 30 ms stagger, first load only. */
  listStagger: 'hq-anim-list-stagger',
  /** One shake, 6 px, 300 ms. */
  shake: 'hq-anim-shake',
} as const;

export type MotionTrigger = keyof typeof MOTION;

export { MOTION_MS, MOTION_EASING } from './tokens.generated';
