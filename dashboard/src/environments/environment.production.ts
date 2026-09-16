/**
 * The built bundle — the one the container serves at `<origin>/dashboard/` in both QA and
 * production (D11). The API is the same origin, so every generated URL is relative and there
 * is no per-environment value to get wrong when an image digest is promoted.
 */
export const environment = {
  production: true,
  name: 'production',
  /** Empty string = same origin. */
  apiBaseUrl: '',
} as const;
