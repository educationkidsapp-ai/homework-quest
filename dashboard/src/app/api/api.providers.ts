import { EnvironmentProviders, makeEnvironmentProviders } from '@angular/core';
import { environment } from '../../environments/environment';
import { BASE_PATH } from './generated';

/**
 * Points the generated client at the API.
 *
 * `environment.apiBaseUrl` is `''` in QA and production — the container serves this bundle
 * from the API's own origin, so every generated URL is already same-origin and relative. In
 * development it is the dev API's origin, because `ng serve` hosts the bundle itself.
 *
 * Without this the generated `BaseService` falls back to `http://localhost`, which fails in a
 * way that looks like CORS rather than like a missing provider — hence the explicit token.
 */
export function provideApiClient(): EnvironmentProviders {
  return makeEnvironmentProviders([{ provide: BASE_PATH, useValue: environment.apiBaseUrl }]);
}
