import { Routes } from '@angular/router';
import { anonymousGuard, authGuard, homeRedirectGuard, passwordChangeGuard } from './core/auth/auth.guards';
import { STYLEGUIDE_ROUTES } from './styleguide/styleguide.route';

/**
 * The route tree.
 *
 * Three layers, in the order the guards run:
 *
 * 1. **Signed out** — sign-in, forgot password, and the two screens an emailed link opens.
 *    The link screens are not behind `anonymousGuard`: mail is read in whatever browser, and
 *    bouncing a colleague's invite to somebody else's Home would make the link look broken.
 * 2. **`/change-password`** — authenticated but outside the shell, because an account with
 *    `mustChangePassword` may go exactly one place and a nav rail would suggest otherwise.
 * 3. **The shell** — `authGuard` then `passwordChangeGuard`, then one lazy chunk per role.
 *    Each role area is its own bundle, so a teacher never downloads the Admin screens.
 *
 * `''` resolves per role rather than redirecting to a fixed path: `/` means "my Home", and
 * whose Home that is is only known after the session is restored.
 */
export const routes: Routes = [
  {
    path: 'sign-in',
    canActivate: [anonymousGuard],
    loadComponent: () => import('./features/auth/sign-in.page').then((m) => m.SignInPage),
  },
  {
    path: 'forgot-password',
    canActivate: [anonymousGuard],
    loadComponent: () => import('./features/auth/forgot-password.page').then((m) => m.ForgotPasswordPage),
  },
  {
    path: 'reset-password',
    loadComponent: () => import('./features/auth/reset-password.page').then((m) => m.ResetPasswordPage),
  },
  {
    path: 'accept-invite',
    loadComponent: () => import('./features/auth/accept-invite.page').then((m) => m.AcceptInvitePage),
  },
  {
    path: 'change-password',
    canActivate: [authGuard],
    loadComponent: () => import('./features/auth/change-password.page').then((m) => m.ChangePasswordPage),
  },

  ...STYLEGUIDE_ROUTES,

  {
    path: '',
    canActivate: [authGuard, passwordChangeGuard],
    loadComponent: () => import('./shell/shell.component').then((m) => m.ShellComponent),
    children: [
      { path: '', pathMatch: 'full', canActivate: [homeRedirectGuard], children: [] },
      {
        path: 'admin',
        loadChildren: () => import('./features/admin/admin.routes').then((m) => m.ADMIN_ROUTES),
      },
      {
        path: 'teacher',
        loadChildren: () => import('./features/teacher/teacher.routes').then((m) => m.TEACHER_ROUTES),
      },
      {
        path: 'management',
        loadChildren: () =>
          import('./features/management/management.routes').then((m) => m.MANAGEMENT_ROUTES),
      },
      {
        path: 'coordinator',
        loadChildren: () =>
          import('./features/coordinator/coordinator.routes').then((m) => m.COORDINATOR_ROUTES),
      },
      // "Preview as child" (teacher-flow §8). Outside the three role areas because the player is
      // nobody's screen — it is the child's app, shown to whoever authored the lesson — and out
      // of `screens.ts` because a row there is a rail item or a role's route, and this is neither.
      {
        path: 'player/gallery',
        loadComponent: () => import('./features/player/gallery.page').then((m) => m.PlayerGalleryPage),
      },
      {
        path: 'profile',
        loadComponent: () => import('./features/profile/profile.page').then((m) => m.ProfilePage),
      },
      {
        path: 'notifications',
        loadComponent: () =>
          import('./features/notifications/notifications.page').then((m) => m.NotificationsPage),
      },
      {
        path: 'no-access',
        loadComponent: () => import('./features/errors/no-access.page').then((m) => m.NoAccessPage),
      },
      {
        path: 'not-found',
        loadComponent: () => import('./features/errors/not-found.page').then((m) => m.NotFoundPage),
      },
      {
        path: '**',
        loadComponent: () => import('./features/errors/not-found.page').then((m) => m.NotFoundPage),
      },
    ],
  },
];
