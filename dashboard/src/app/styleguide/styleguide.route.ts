import { Routes } from '@angular/router';

/**
 * The styleguide route. The production configuration replaces this file with
 * `styleguide.route.prod.ts` (an empty array), so the styleguide and everything it
 * pulls in is absent from the production bundle while staying available on QA.
 */
export const STYLEGUIDE_ROUTES: Routes = [
  {
    path: 'styleguide',
    loadComponent: () => import('./styleguide.page').then((m) => m.StyleguidePage),
    title: 'Styleguide',
  },
];
