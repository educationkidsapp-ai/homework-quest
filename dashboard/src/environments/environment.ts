/**
 * Development (`ng serve`). The dev server hosts the panel, so the API lives on another origin.
 * `qa` and `production` replace this file (see `fileReplacements` in angular.json).
 */
export const environment = {
  production: false,
  name: 'development',
  /** Base URL for the API. An empty string means "same origin". */
  apiBaseUrl: 'http://localhost:8080',
  firebaseProject: 'homework-quest-dev',
} as const;
