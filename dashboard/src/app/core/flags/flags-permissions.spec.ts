import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ChangeDetectionStrategy, Component, EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import { firstValueFrom, isObservable } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';
import { BASE_PATH } from '../../api';
import { TEACHER_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { AuthService } from '../auth/auth.service';
import { SessionStore } from '../auth/session.store';
import { CanDirective } from '../permissions/can.directive';
import { canGuard } from '../permissions/can.guard';
import { PermissionService } from '../permissions/permission.service';
import { FeatureDirective } from './feature.directive';
import { featureGuard } from './feature.guard';
import { FlagService } from './flag.service';

@Component({
  selector: 'hq-flag-host',
  imports: [FeatureDirective, CanDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <p *hqFeature="'complaints'">Complaints</p>
    <p *hqFeature="'announcements'">Announcements</p>
    <p *hqCan="'lesson.publish'">Publish</p>
    <p *hqCan="'school.write'">Rename the school</p>
  `,
})
class FlagHost {}

const providers: (Provider | EnvironmentProviders)[] = [
  provideHttpClient(),
  provideHttpClientTesting(),
  provideRouter([]),
  // Same origin, as in the built bundle.
  { provide: BASE_PATH, useValue: '' },
];

interface Session {
  readonly flags: Record<string, boolean>;
  readonly permissions: readonly string[];
  readonly readOnly?: boolean;
}

/**
 * Signs a teacher in and answers the flag and permission reads.
 *
 * Both services are resources keyed on the session, so nothing is requested until somebody is
 * signed in — which is also why the order here matters: `/me` first, then a tick to let the two
 * resources start, then their responses.
 */
async function signIn({ flags, permissions, readOnly = false }: Session): Promise<void> {
  const backend = TestBed.inject(HttpTestingController);
  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(TEACHER_USER);

  TestBed.inject(FlagService).flags();
  TestBed.inject(PermissionService).all();
  TestBed.tick();

  backend.expectOne('/schools/school-a/flags').flush({ schoolId: 'school-a', flags });
  backend.expectOne('/me/permissions').flush({ role: 'TEACHER', permissions, readOnly });
  // A resource applies a delivered value on the microtask queue, so one synchronous tick is
  // not enough: let the queue drain, then tick again to settle the graph.
  await Promise.resolve();
  TestBed.tick();
}

describe('feature flags and permissions', () => {
  beforeEach(() => localStorage.clear());

  it('renders only what the school has and the account may do', async () => {
    // Rendered first: `renderHq` configures the testing module, which cannot happen after
    // anything has been injected out of it.
    const { fixture } = await renderHq(FlagHost, { providers });
    await signIn({ flags: { complaints: true, announcements: false }, permissions: ['lesson.publish'] });
    fixture.detectChanges();

    expect(screen.getByText('Complaints')).toBeInTheDocument();
    expect(screen.queryByText('Announcements')).not.toBeInTheDocument();
    expect(screen.getByText('Publish')).toBeInTheDocument();
    expect(screen.queryByText('Rename the school')).not.toBeInTheDocument();
  });

  it('takes every write away in a View-as session, and leaves the reads', async () => {
    TestBed.configureTestingModule({ providers });
    await signIn({ permissions: ['lesson.publish', 'lesson.read', 'me.home'], flags: {}, readOnly: true });
    const permissions = TestBed.inject(PermissionService);

    expect(permissions.readOnly()).toBe(true);
    expect(permissions.can('lesson.publish')).toBe(false);
    expect(permissions.can('lesson.read')).toBe(true);
    expect(permissions.can('me.home')).toBe(true);
  });

  it('treats an unknown flag as off', async () => {
    TestBed.configureTestingModule({ providers });
    await signIn({ flags: { complaints: true }, permissions: [] });

    expect(TestBed.inject(FlagService).isOn('certificates')).toBe(false);
  });

  it('opens a route whose flag is on and sends one that is off to /not-found', async () => {
    TestBed.configureTestingModule({ providers });
    await signIn({ flags: { complaints: true, announcements: false }, permissions: [] });
    const router = TestBed.inject(Router);

    expect(await run(featureGuard('complaints'))).toBe(true);
    expect(String(await run(featureGuard('announcements')))).toBe(
      String(router.createUrlTree(['/not-found'])),
    );
  });

  it('sends a missing permission to /no-access', async () => {
    TestBed.configureTestingModule({ providers });
    await signIn({ flags: {}, permissions: ['lesson.read'] });
    const router = TestBed.inject(Router);

    expect(await run(canGuard('lesson.read'))).toBe(true);
    expect(String(await run(canGuard('school.write')))).toBe(String(router.createUrlTree(['/no-access'])));
  });
});

/** Runs a `CanActivateFn` in an injection context and normalises its several return shapes. */
function run(guard: ReturnType<typeof featureGuard>): Promise<boolean | UrlTree> {
  return TestBed.runInInjectionContext(async () => {
    const result = guard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot);
    return (isObservable(result) ? firstValueFrom(result) : await result) as boolean | UrlTree;
  });
}
