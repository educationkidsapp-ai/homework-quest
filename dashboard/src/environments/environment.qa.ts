/** QA — the API container serves this bundle at `<api>/panel/`, so the API is same origin. */
export const environment = {
  production: false,
  name: 'qa',
  /** Empty string = same origin. */
  apiBaseUrl: '',
  firebaseProject: 'homework-quest-qa',
} as const;
