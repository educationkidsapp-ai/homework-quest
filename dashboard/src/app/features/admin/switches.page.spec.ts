import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { BASE_PATH } from '../../api';
import { ADMIN_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { SwitchesPage } from './switches.page';

const providers: (Provider | EnvironmentProviders)[] = [
  provideHttpClient(),
  provideHttpClientTesting(),
  provideRouter([{ path: '**', children: [] }]),
  { provide: BASE_PATH, useValue: '' },
];

const ADMIN_PERMISSIONS = { role: 'ADMIN', permissions: ['flag.read', 'flag.write'], readOnly: false };
/** MANAGERIAL holds `flag.read` and not `flag.write` (`permissions.json`). */
const READ_ONLY_PERMISSIONS = { role: 'ADMIN', permissions: ['flag.read'], readOnly: false };

const SCHOOL = { id: 'school-a', name: 'Learning International School', status: 'active' };

/** The school's own row, which is what the toggles write. */
const FLAGS: Record<string, boolean> = {
  staffMobileApp: false,
  staffAttendance: false,
  announcements: false,
  voiceAnnouncement: false,
  floorSupervisor: false,
  chat: true,
  gradebook: true,
  mysteryFeature: false,
};

/**
 * `GET /admin/flags` as the server answers it. `mysteryFeature` is the point of the fixture: a key
 * the catalogue has no words for, which must still appear — under "Other", with the server's own
 * description — or a feature ships that nobody on the dashboard can turn on.
 */
const MATRIX = {
  definitions: [
    { key: 'staffMobileApp', description: 'Feature 13: staff sign in to the app.', defaultOn: false },
    { key: 'staffAttendance', description: 'Feature 14: staff attendance.', defaultOn: false },
    { key: 'announcements', description: 'School-wide announcements.', defaultOn: false },
    { key: 'voiceAnnouncement', description: 'Feature 22: a voice note becomes one.', defaultOn: false },
    { key: 'floorSupervisor', description: 'Feature 20: the floor supervisor role.', defaultOn: false },
    { key: 'chat', description: 'C1: parent and teacher chat.', defaultOn: false },
    { key: 'gradebook', description: 'Scores.', defaultOn: false },
    { key: 'mysteryFeature', description: 'A switch the dashboard has no words for yet.', defaultOn: false },
  ],
  schools: [{ schoolId: 'school-a', schoolName: SCHOOL.name, flags: FLAGS }],
};

const AUDIT = [
  {
    id: 'audit-1',
    flagKey: 'chat',
    schoolId: 'school-a',
    schoolName: SCHOOL.name,
    enabled: true,
    actorUserId: 'u-admin',
    actorEmail: 'admin@quest.local',
    createdAt: Date.UTC(2026, 9, 12, 6, 14),
  },
];

async function settle(rendered: { fixture: { detectChanges: () => void } }): Promise<void> {
  await Promise.resolve();
  TestBed.tick();
  rendered.fixture.detectChanges();
}

/** The flag map `FlagService` reads for the rail; it is not this page's copy and not asserted on. */
function answerRailFlags(backend: HttpTestingController): void {
  for (const request of backend.match('/schools/school-a/flags')) request.flush(FLAGS);
}

function answerAudit(backend: HttpTestingController, rows: unknown[] = AUDIT): void {
  for (const request of backend.match((candidate) => candidate.url === '/admin/flags/audit'))
    request.flush(rows);
}

async function renderSignedIn(permissions = ADMIN_PERMISSIONS) {
  const rendered = await renderHq(SwitchesPage, { providers });
  const backend = TestBed.inject(HttpTestingController);

  // The matrix and the audit are read on construction, before anybody is signed in — the token is
  // the interceptor's business, not the resource's.
  backend.expectOne('/admin/flags').flush(MATRIX);
  answerAudit(backend);

  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(ADMIN_USER);
  TestBed.tick();
  backend.expectOne('/me/permissions').flush(permissions);
  // An Admin's account carries no school, so `SoleSchoolService` asks the server which one is hers.
  backend.expectOne('/admin/schools').flush([SCHOOL]);
  await settle(rendered);
  answerRailFlags(backend);
  await settle(rendered);
  return { rendered, backend };
}

function toggleFor(name: string | RegExp): HTMLElement {
  return screen.getByRole('switch', { name });
}

describe('Feature switches', () => {
  beforeEach(() => localStorage.clear());

  it('groups the switches in plain language and keeps an unnamed key under Other', async () => {
    await renderSignedIn();

    expect(screen.getByRole('heading', { name: 'Staff & attendance' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: "Leaders' tools" })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Other' })).toBeInTheDocument();

    expect(toggleFor('Staff app')).toHaveAttribute('aria-checked', 'false');
    expect(toggleFor('Messages with parents')).toHaveAttribute('aria-checked', 'true');
    expect(
      screen.getByText(
        'Staff sign in to the same app the parents use, with their own area for attendance, messages and their classes.',
      ),
    ).toBeInTheDocument();
    expect(screen.getByText('Feature 13')).toBeInTheDocument();

    // The unnamed key: its own key as the name, and the server's sentence rather than silence.
    expect(toggleFor('mysteryFeature')).toBeInTheDocument();
    expect(screen.getByText('A switch the dashboard has no words for yet.')).toBeInTheDocument();

    // And what the audit knows about the one key it has a row for.
    expect(screen.getByText(/^Last changed by admin@quest\.local at /)).toBeInTheDocument();
  });

  it('names the switch another one rides on, and warns while that one is off', async () => {
    await renderSignedIn();

    expect(screen.getByText('Works with Announcements.')).toBeInTheDocument();
    expect(screen.getByText('Announcements is off. Turn that on first.')).toBeInTheDocument();
    // The caution that belongs to one switch whatever the others say.
    expect(
      screen.getByText(
        'Turn this on only after the parents have the latest app: an older app cannot read the new role.',
      ),
    ).toBeInTheDocument();
  });

  it('turns a switch on with one PUT for the one school, and updates the row in place', async () => {
    const { rendered, backend } = await renderSignedIn();

    await userEvent.click(toggleFor('Staff app'));
    await settle(rendered);
    // Optimistic: the switch has already moved while the request is in flight.
    expect(toggleFor('Staff app')).toHaveAttribute('aria-checked', 'true');

    const put = backend.expectOne('/admin/schools/school-a/flags/staffMobileApp');
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual({ enabled: true });
    put.flush({ ...FLAGS, staffMobileApp: true, staffAttendance: false });
    await settle(rendered);

    expect(toggleFor('Staff app')).toHaveAttribute('aria-checked', 'true');
    expect(screen.queryByText('Could not reach the server.')).not.toBeInTheDocument();
    // The rail is built from `FlagService`'s map, so it is re-read rather than left stale.
    expect(backend.match('/schools/school-a/flags')).toHaveLength(1);
    answerAudit(backend, []);
  });

  it('puts the switch back and shows the red band when the server refuses', async () => {
    const { rendered, backend } = await renderSignedIn();

    await userEvent.click(toggleFor('Staff attendance'));
    await settle(rendered);
    expect(toggleFor('Staff attendance')).toHaveAttribute('aria-checked', 'true');

    backend
      .expectOne('/admin/schools/school-a/flags/staffAttendance')
      .flush(
        { code: 'conflict', message: 'The staff app has to be on first.' },
        { status: 409, statusText: 'Conflict' },
      );
    await settle(rendered);

    expect(toggleFor('Staff attendance')).toHaveAttribute('aria-checked', 'false');
    expect(screen.getByText('The staff app has to be on first.')).toBeInTheDocument();
    // A failure is never a toast and never a second read: nothing was refreshed.
    expect(backend.match('/schools/school-a/flags')).toHaveLength(0);
  });

  it('disables every toggle for a session that may only read', async () => {
    const { rendered, backend } = await renderSignedIn(READ_ONLY_PERMISSIONS);

    expect(
      screen.getByText('You can see which features are on. Only the Admin can change them.'),
    ).toBeInTheDocument();
    for (const name of ['Staff app', 'Messages with parents', 'mysteryFeature'])
      expect(toggleFor(name)).toBeDisabled();

    await userEvent.click(toggleFor('Staff app'));
    await settle(rendered);
    backend.expectNone('/admin/schools/school-a/flags/staffMobileApp');
  });
});
