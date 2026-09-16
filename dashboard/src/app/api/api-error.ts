import { HttpErrorResponse } from '@angular/common/http';

/**
 * The server's error body: `{ code, message }` (`ApiException.ApiError`).
 *
 * It is not in `components/schemas` — error responses are not described in the OpenAPI
 * document — so it is declared here, next to the generated client, rather than invented
 * again in each screen. Codes the server uses: `bad_request`, `unauthorized`, `forbidden`,
 * `not_found`, `conflict`, plus validation failures.
 */
export interface ApiError {
  readonly code: string;
  readonly message: string;
}

/**
 * The `{code, message}` out of a failed request, or `null` when the failure was not the
 * server speaking — a dropped connection, a CORS refusal, an HTML error page from a proxy.
 * Those have a status of 0 or a body that is not our shape, and the caller must say
 * "could not reach the server" rather than show whatever text came back.
 */
export function apiErrorOf(error: unknown): ApiError | null {
  if (!(error instanceof HttpErrorResponse)) return null;
  const body: unknown = error.error;
  if (body === null || typeof body !== 'object') return null;
  const { code, message } = body as Record<string, unknown>;
  if (typeof code !== 'string' || typeof message !== 'string') return null;
  return { code, message };
}
