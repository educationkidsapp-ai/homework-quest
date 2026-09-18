import type { CanDeactivateFn } from '@angular/router';
import type { LessonPage } from './lesson.page';

/**
 * Blocks navigating away from an unsaved stop or parent-panel edit.
 *
 * The refusal is a red band on the page itself, never `window.confirm()` — the system has one
 * way of asking "are you sure", and a browser dialog is not it. `LessonPage.confirmLeave` both
 * answers the guard and raises the band, remembering where the teacher was going so that
 * "Leave anyway" resumes that navigation rather than dumping her on the list.
 */
export const lessonUnsavedGuard: CanDeactivateFn<LessonPage> = (component, _route, _state, nextState) =>
  component.confirmLeave(nextState.url);
