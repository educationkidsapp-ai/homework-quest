/**
 * Development (`ng serve`). The dev server hosts the dashboard, so the API is another origin.
 *
 * There is exactly one other environment file. D11: the Docker image always builds the
 * `production` configuration and the same bundle serves QA and production, so nothing that
 * differs between the two may be a build-time value — the platform name, the logo, the theme
 * and the flag map are all read from the API at runtime, which is what makes promoting an
 * image digest from QA to production honest.
 */
export const environment = {
  production: false,
  name: 'development',
  /** Base URL for the API. An empty string means "same origin". */
  apiBaseUrl: 'http://localhost:8080',
} as const;
