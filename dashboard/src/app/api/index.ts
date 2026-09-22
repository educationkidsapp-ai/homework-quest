/**
 * The only door to the API.
 *
 * `./generated` is written by `pnpm gen:api` from `server/openapi.json` and is git-ignored:
 * there is no checked-in copy that could drift from the contract. Everything else in the app
 * imports the services and models from here rather than reaching into the generated folder,
 * so the day the generator or its layout changes, one file moves.
 *
 * `hq/no-raw-http` fails the build on an `HttpClient` injected anywhere but this folder and
 * the interceptors — a hand-written call is an undeclared second copy of the contract.
 */
export * from './generated';
export { provideApiClient } from './api.providers';
export type { ApiError } from './api-error';
export { apiErrorOf, apiErrorCodeOf, readableServerText } from './api-error';
export { AttendanceApi } from './attendance.api';
