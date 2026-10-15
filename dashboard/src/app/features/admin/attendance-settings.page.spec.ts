import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { BASE_PATH } from '../../api';
import { ADMIN_USER, MANAGERIAL_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { AttendanceSettingsPage } from './attendance-settings.page';

const providers: (Provider | EnvironmentProviders)[] = [
  provideHttpClient(),
  provideHttpClientTesting(),
  provideRouter([{ path: '**', children: [] }]),
  { provide: BASE_PATH, useValue: '' },
];

const ADMIN_PERMISSIONS = {
  role: 'ADMIN',
  permissions: ['attendance.settings.read', 'attendance.settings.write'],
  readOnly: false,
};
/** MANAGERIAL holds the read and not the write (`permissions.json`). */
const MANAGER_PERMISSIONS = {
  role: 'MANAGERIAL',
  permissions: ['attendance.settings.read'],
  readOnly: false,
};

const SETTINGS = {
  latitude: 24.7136,
  longitude: 46.6753,
  radiusMeters: 150,
  graceMinutes: 10,
  notArrivedMinutes: 60,
  missingCheckOutMinutes: 60,
  directorNotify: 'DIGEST',
  adminNotify: 'DIGEST',
  digestTime: '07:30',
  timeZone: 'Asia/Riyadh',
  schedules: [],
  holidays: [],
};

const STAFF = [
  {
    kind: 'USER',
    id: 'u-dalia',
    fullName: 'Dalia',
    role: 'MANAGERIAL',
    category: 'ADMINISTRATIVE',
    categorySet: false,
  },
  { kind: 'USER', id: 'u-tala', fullName: 'Tala', role: 'TEACHER', category: 'TEACHER', categorySet: false },
  {
    kind: 'WORKER',
    id: 'w-walid',
    fullName: 'Walid',
    role: 'WORKER',
    category: 'WORKER',
    categorySet: false,
  },
];

async function settle(rendered: { fixture: { detectChanges: () => void } }): Promise<void> {
  await Promise.resolve();
  TestBed.tick();
  rendered.fixture.detectChanges();
}

async function renderSignedIn(permissions = ADMIN_PERMISSIONS, user = ADMIN_USER) {
  const rendered = await renderHq(AttendanceSettingsPage, { providers });
  const backend = TestBed.inject(HttpTestingController);

  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(user);
  TestBed.tick();
  backend.expectOne('/me/permissions').flush(permissions);

  backend.expectOne('/admin/attendance-settings').flush(SETTINGS);
  backend.expectOne('/admin/staff/attendance-profiles').flush(STAFF);
  await settle(rendered);
  // Feature 23: the Remote study days card replaced feature 18's remote-days card and asks the
  // `remoteStudyDays` flag itself. No flag map is fetched here, so the flag reads off and the card
  // draws nothing and reads nothing — `remote-study-days-card.component.spec.ts` covers it with
  // the flag on.
  backend.expectNone('/admin/remote-study-days');
  backend.expectNone('/admin/attendance/remote-days');
  return { rendered, backend };
}

describe('Attendance settings', () => {
  beforeEach(() => localStorage.clear());

  it('shows the pin, its circle and the school time zone', async () => {
    await renderSignedIn();

    expect(screen.getByLabelText(/^Latitude/)).toHaveValue(24.7136);
    expect(screen.getByRole('img', { name: /150 metres around the pin/ })).toBeInTheDocument();
    expect(screen.getByText('Asia/Riyadh')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Check the pin on a map' })).toHaveAttribute(
      'href',
      expect.stringContaining('mlat=24.7136'),
    );
  });

  it('lets the Admin change the radius and the grace period, and refuses a radius out of bounds', async () => {
    const { rendered, backend } = await renderSignedIn();

    const radius = screen.getByLabelText(/^Radius/);
    await userEvent.clear(radius);
    await userEvent.type(radius, '600');
    await settle(rendered);
    expect(screen.getByText('The radius is between 50 and 500 metres.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();

    await userEvent.clear(radius);
    await userEvent.type(radius, '200');
    const grace = screen.getByLabelText(/^Grace period/);
    await userEvent.clear(grace);
    await userEvent.type(grace, '15');
    await settle(rendered);
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    const put = backend.expectOne('/admin/attendance-settings');
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toMatchObject({ radiusMeters: 200, graceMinutes: 15, latitude: 24.7136 });
    expect((put.request.body as { schedules: unknown[] }).schedules).toHaveLength(3);
    put.flush({ ...SETTINGS, radiusMeters: 200, graceMinutes: 15 });
    await settle(rendered);
    expect(screen.getByText('Saved.')).toBeInTheDocument();
  });

  it('sets a direct manager for several employees at once', async () => {
    const { rendered, backend } = await renderSignedIn();

    await userEvent.click(screen.getByRole('checkbox', { name: 'Select Tala' }));
    await userEvent.click(screen.getByRole('checkbox', { name: 'Select Walid' }));
    await settle(rendered);
    expect(screen.getByText('2 selected')).toBeInTheDocument();
    await userEvent.selectOptions(screen.getByLabelText('Direct manager for the selected'), 'u-dalia');
    await settle(rendered);
    await userEvent.click(screen.getByRole('button', { name: 'Set direct manager' }));

    const put = backend.expectOne('/admin/staff/attendance-profiles');
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual({
      staff: [
        { kind: 'USER', id: 'u-tala' },
        { kind: 'WORKER', id: 'w-walid' },
      ],
      directManagerId: 'u-dalia',
    });
    put.flush([]);
    await settle(rendered);
    backend.expectOne('/admin/staff/attendance-profiles').flush(STAFF);
  });

  it('is read-only for a manager: every field disabled, no Save and no selection', async () => {
    await renderSignedIn(MANAGER_PERMISSIONS, MANAGERIAL_USER);

    expect(
      screen.getByText('You can view these settings. Only the Admin can change them.'),
    ).toBeInTheDocument();
    expect(screen.getByLabelText(/^Latitude/)).toBeDisabled();
    expect(screen.getByLabelText(/^Grace period/)).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Use my current location' })).not.toBeInTheDocument();
    expect(screen.queryByRole('checkbox', { name: 'Select Tala' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Set direct manager' })).not.toBeInTheDocument();
  });
});
