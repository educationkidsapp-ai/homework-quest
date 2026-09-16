import { Route } from '@angular/router';

/**
 * A route whose screen a later package builds.
 *
 * Declaring them now is not busywork: the nav rail shows every item a role has, the server's
 * "what needs you" rows already carry hrefs like `/admin/schools/{id}/users` and
 * `/teacher/lessons/new?classId=…`, and a link that 404s is worse than one that says which
 * phase brings the screen. The paths here are the ones P3.2, P3.3, P4.1, P5.1 and P6.1 will
 * take over, so nothing linked today has to be relinked then.
 */
export function stubRoute(path: string): Route {
  return { path, loadComponent: () => import('./stub.page').then((m) => m.StubPage) };
}
