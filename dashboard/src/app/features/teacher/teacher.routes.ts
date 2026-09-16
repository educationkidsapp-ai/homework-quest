import { Routes } from '@angular/router';
import { areaRoutes } from '../../core/nav/area.routes';

/**
 * `/teacher/**` (§6 screens 11–16).
 *
 * Declared in `core/nav/screens.ts` and gated by `areaRoutes`, which attaches `featureGuard`
 * for `teacherQuestions` and `announcements` and `canGuard` for `lesson.read`/`lesson.write`.
 * §4's rule is that a flag is checked in the server, the dashboard *and* the app, and a hidden
 * menu item is not a check — a bookmark or a pasted link goes straight past it.
 *
 * `lessons/new` carries `classId`, `curriculum`, `grade`, `subject` and `date` in the query
 * string; the Home's "Add today's lesson" builds exactly that link and P3.2's chooser reads it.
 */
export const TEACHER_ROUTES: Routes = areaRoutes('TEACHER');
