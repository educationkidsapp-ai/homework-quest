import { Routes } from '@angular/router';
import { roleGuard } from '../../core/auth/auth.guards';
import { featureGuard } from '../../core/flags/feature.guard';
import { FLAGS } from '../../core/flags/flag.service';
import { stubRoute } from '../stub/stub.route';

/**
 * `/teacher/**` (§6 screens 11–16).
 *
 * Two of the six are flagged, and are guarded here as well as hidden in the rail: §4's rule
 * is that a flag is checked in the server, the dashboard *and* the app, and a hidden menu
 * item is not a check — a bookmark or a pasted link goes straight past it.
 *
 * `lessons/new` carries `classId`, `curriculum`, `grade`, `subject` and `date` in the query
 * string; the Home's "Add today's lesson" builds exactly that link, and P3.2's chooser reads
 * it. The path is declared now so the link works before the screen exists.
 */
export const TEACHER_ROUTES: Routes = [
  {
    path: '',
    canActivate: [roleGuard('TEACHER')],
    children: [
      { path: '', loadComponent: () => import('../home/home.page').then((m) => m.HomePage) },
      stubRoute('lessons'),
      stubRoute('lessons/new'),
      stubRoute('lessons/:id'),
      stubRoute('students'),
      { ...stubRoute('questions'), canActivate: [featureGuard(FLAGS.teacherQuestions)] },
      { ...stubRoute('announcements'), canActivate: [featureGuard(FLAGS.announcements)] },
    ],
  },
];
