/** Production — the API container serves this bundle at `<api>/panel/`, so the API is same origin. */
export const environment = {
  production: true,
  name: 'production',
  /** Empty string = same origin. */
  apiBaseUrl: '',
  firebaseProject: 'homework-quest',
} as const;
