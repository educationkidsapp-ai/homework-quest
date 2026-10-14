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
import { RemoteDaysCardComponent } from './remote-days-card.component';

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

const DAYS = [
  { id: 'rd-2', date: '2026-11-02', reason: 'Rain', academy: 'british' },
  { id: 'rd-1', date: '2026-10-20', reason: 'Sandstorm' },
];

async function settle(rendered: { fixture: { detectChanges: () => void } }): Promise<void> {
  await Promise.resolve();
  TestBed.tick();
  rendered.fixture.detectChanges();
}

async function renderCard(permissions = ADMIN_PERMISSIONS, user = ADMIN_USER, days = DAYS) {
  const rendered = await renderHq(RemoteDaysCardComponent, { providers });
  const backend = TestBed.inject(HttpTestingController);

  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(user);
  TestBed.tick();
  backend.expectOne('/me/permissions').flush(permissions);

  backend.expectOne('/admin/attendance/remote-days').flush({ days });
  await settle(rendered);
  return { rendered, backend };
}

describe('feature 18 — the Remote days card', () => {
  beforeEach(() => localStorage.clear());

  it('lists the announced days soonest first, whole school or one academy', async () => {
    await renderCard();

    const rows = screen.getAllByRole('row').slice(1);
    expect(rows[0]).toHaveTextContent('2026-10-20');
    expect(rows[0]).toHaveTextContent('Whole school');
    expect(rows[0]).toHaveTextContent('Sandstorm');
    expect(rows[1]).toHaveTextContent('2026-11-02');
    expect(rows[1]).toHaveTextContent('British');
  });

  it('announces a remote day for one academy and reloads the list', async () => {
    const { rendered, backend } = await renderCard();

    await userEvent.type(screen.getByLabelText(/^Day/), '2026-12-01');
    await userEvent.selectOptions(screen.getByLabelText(/^Scope/), 'british');
    await userEvent.type(screen.getByLabelText(/^Reason/), 'Exam week');
    await settle(rendered);
    await userEvent.click(screen.getByRole('button', { name: 'Add remote day' }));

    const post = backend.expectOne('/admin/attendance/remote-days');
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({ date: '2026-12-01', reason: 'Exam week', academy: 'british' });
    post.flush({ id: 'rd-3', date: '2026-12-01', reason: 'Exam week', academy: 'british' });
    await settle(rendered);
    backend.expectOne('/admin/attendance/remote-days').flush({ days: DAYS });
  });

  it('leaves the academy out when the day is the whole school', async () => {
    const { rendered, backend } = await renderCard();

    await userEvent.type(screen.getByLabelText(/^Day/), '2026-12-01');
    await userEvent.type(screen.getByLabelText(/^Reason/), 'Rain day');
    await settle(rendered);
    await userEvent.click(screen.getByRole('button', { name: 'Add remote day' }));

    const post = backend.expectOne('/admin/attendance/remote-days');
    expect(post.request.body).toEqual({ date: '2026-12-01', reason: 'Rain day' });
  });

  it('refuses to add a day without a date or a reason', async () => {
    await renderCard();

    expect(screen.getByRole('button', { name: 'Add remote day' })).toBeDisabled();
  });

  it('deletes a day and reads the list again', async () => {
    const { rendered, backend } = await renderCard();

    await userEvent.click(screen.getByRole('button', { name: 'Remove the remote day on 2026-10-20' }));
    const deleted = backend.expectOne('/admin/attendance/remote-days/rd-1');
    expect(deleted.request.method).toBe('DELETE');
    deleted.flush(null);
    await settle(rendered);
    backend.expectOne('/admin/attendance/remote-days').flush({ days: [DAYS[0]] });
  });

  it('is read-only for a manager: the list, and no form or delete', async () => {
    await renderCard(MANAGER_PERMISSIONS, MANAGERIAL_USER);

    expect(screen.getByText('Sandstorm')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Add remote day' })).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/^Day/)).not.toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'Remove the remote day on 2026-10-20' }),
    ).not.toBeInTheDocument();
  });

  it('says so when there is nothing announced', async () => {
    await renderCard(ADMIN_PERMISSIONS, ADMIN_USER, []);

    expect(screen.getByText('No remote days yet.')).toBeInTheDocument();
  });
});
