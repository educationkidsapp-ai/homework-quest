/* hq-flag: none (shell) — the Admin area is the platform owner's own view, not one of the
   per-school features a flag can switch off; it is where the Feature flags matrix that owns
   every other flag lives (P3.3). Individual screens gate themselves as they land. */
import { Routes } from '@angular/router';
import { roleGuard } from '../../core/auth/auth.guards';
import { stubRoute } from '../stub/stub.route';

/**
 * `/admin/**` (§6 screens 4–10).
 *
 * The Home is real; the rest are stubs until P3.2 (All lessons), P3.3 (Schools, Users,
 * Feature flags, Platform settings) and P6.1 (Platform usage & cost). The `:id` paths exist
 * because the server's Home already links to them — `/admin/lessons/{id}` for a lesson in
 * error, `/admin/schools/{id}/users` for a school with no teacher.
 */
export const ADMIN_ROUTES: Routes = [
  {
    path: '',
    canActivate: [roleGuard('ADMIN')],
    children: [
      { path: '', loadComponent: () => import('../home/home.page').then((m) => m.HomePage) },
      stubRoute('schools'),
      stubRoute('schools/:id'),
      stubRoute('schools/:id/users'),
      stubRoute('users'),
      stubRoute('flags'),
      stubRoute('lessons'),
      stubRoute('lessons/:id'),
      stubRoute('usage'),
      stubRoute('settings'),
    ],
  },
];
