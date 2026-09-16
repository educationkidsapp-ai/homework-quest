import { Routes } from '@angular/router';
import { roleGuard } from '../../core/auth/auth.guards';
import { featureGuard } from '../../core/flags/feature.guard';
import { FLAGS } from '../../core/flags/flag.service';
import { stubRoute } from '../stub/stub.route';

/**
 * `/management/**` (§6 screens 17–20).
 *
 * Complaints is the role's main screen and is flagged: a school that has not turned
 * `complaints` on has no inbox, the endpoint 404s, and the rail does not offer it. Guarded
 * here too, for the same reason as the teacher's flagged screens.
 */
export const MANAGEMENT_ROUTES: Routes = [
  {
    path: '',
    canActivate: [roleGuard('MANAGERIAL')],
    children: [
      { path: '', loadComponent: () => import('../home/home.page').then((m) => m.HomePage) },
      { ...stubRoute('complaints'), canActivate: [featureGuard(FLAGS.complaints)] },
      { ...stubRoute('complaints/:id'), canActivate: [featureGuard(FLAGS.complaints)] },
      stubRoute('usage'),
      stubRoute('teachers'),
    ],
  },
];
