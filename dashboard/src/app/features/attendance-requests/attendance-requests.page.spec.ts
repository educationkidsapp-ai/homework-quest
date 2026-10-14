import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { BASE_PATH } from '../../api';
import { ADMIN_USER, TEACHER_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { AuthService } from '../../core/auth/auth.service';
import { BandService } from '../../core/band/band.service';
import { SessionStore } from '../../core/auth/session.store';
import { AttendanceRequestsPage } from './attendance-requests.page';

const providers: (Provider | EnvironmentProviders)[] = [
  provideHttpClient(),
  provideHttpClientTesting(),
  provideRouter([{ path: '**', children: [] }]),
  { provide: BASE_PATH, useValue: '' },
];

/** An Admin decides every request and is the only role that may add a correction. */
const ADMIN_PERMISSIONS = {
  role: 'ADMIN',
  permissions: ['attendance.requests.decide', 'attendance.correction.write'],
  readOnly: false,
};
/** A teacher decides her own direct reports' and holds no `attendance.correction.write`. */
const TEACHER_PERMISSIONS = {
  role: 'TEACHER',
  permissions: ['attendance.requests.decide'],
  readOnly: false,
};

const REMOTE = {
  id: 'r-1',
  type: 'REMOTE_DAY',
  status: 'PENDING',
  staff: 'USER:u-tala',
  staffName: 'Tala',
  date: '2026-10-20',
  reason: 'The road is flooded',
  createdAt: 1_760_000_000_000,
};

const CORRECTION = {
  id: 'r-2',
  type: 'CORRECTION',
  status: 'PENDING',
  staff: 'WORKER:w-walid',
  staffName: 'Walid',
  date: '2026-10-13',
  reason: 'My phone was dead',
  correctionKind: 'FORGOT_CHECK_IN',
  checkIn: '07:35',
  attachment: { id: 'a-1', name: 'receipt.pdf', type: 'application/pdf', sizeBytes: 1024 },
  createdAt: 1_760_000_500_000,
};

const DECIDED = {
  ...CORRECTION,
  id: 'r-3',
  status: 'APPROVED',
  decidedBy: 'Dalia',
  decidedAt: 1_760_100_000_000,
  originalCheckIn: '08:10',
};

const STAFF = [
  { kind: 'USER', id: 'u-tala', fullName: 'Tala', role: 'TEACHER', category: 'TEACHER', categorySet: true },
  {
    kind: 'WORKER',
    id: 'w-walid',
    fullName: 'Walid',
    role: 'WORKER',
    category: 'WORKER',
    categorySet: true,
  },
];

async function settle(rendered: { fixture: { detectChanges: () => void } }): Promise<void> {
  await Promise.resolve();
  TestBed.tick();
  rendered.fixture.detectChanges();
}

async function renderPage(
  permissions = ADMIN_PERMISSIONS,
  user = ADMIN_USER,
  pending: unknown[] = [REMOTE, CORRECTION],
) {
  const rendered = await renderHq(AttendanceRequestsPage, { providers });
  const backend = TestBed.inject(HttpTestingController);

  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(user);
  TestBed.tick();
  backend.expectOne('/me/permissions').flush(permissions);

  backend.expectOne('/attendance/requests?status=PENDING').flush({ requests: pending });
  await settle(rendered);
  return { rendered, backend };
}

describe('feature 18 — Attendance requests', () => {
  beforeEach(() => localStorage.clear());

  it('shows what is pending: who asked, what for, the day, the claimed time and the reason', async () => {
    await renderPage();

    const rows = screen.getAllByRole('row').slice(1);
    // Oldest first: the one that has waited longest is the one to answer.
    expect(rows[0]).toHaveTextContent('Tala');
    expect(rows[0]).toHaveTextContent('Remote day');
    expect(rows[0]).toHaveTextContent('2026-10-20');
    expect(rows[0]).toHaveTextContent('The road is flooded');
    expect(rows[1]).toHaveTextContent('Walid');
    expect(rows[1]).toHaveTextContent('Correction');
    expect(rows[1]).toHaveTextContent('Forgot to check in');
    expect(rows[1]).toHaveTextContent('In 07:35');
    // A correction with a photo or a PDF offers it.
    expect(screen.getByRole('button', { name: 'Open attachment' })).toBeInTheDocument();
  });

  it('approves a request and reads the list again without it', async () => {
    const { rendered, backend } = await renderPage();

    await userEvent.click(screen.getAllByRole('button', { name: 'Approve' })[0]!);
    const post = backend.expectOne('/attendance/requests/r-1/approve');
    expect(post.request.method).toBe('POST');
    post.flush({ ...REMOTE, status: 'APPROVED' });
    await settle(rendered);

    backend.expectOne('/attendance/requests?status=PENDING').flush({ requests: [CORRECTION] });
    await settle(rendered);
    expect(screen.queryByText('The road is flooded')).not.toBeInTheDocument();
    expect(screen.getByText('My phone was dead')).toBeInTheDocument();
  });

  it('rejects a request through the reject route', async () => {
    const { rendered, backend } = await renderPage();

    await userEvent.click(screen.getAllByRole('button', { name: 'Reject' })[0]!);
    const post = backend.expectOne('/attendance/requests/r-1/reject');
    expect(post.request.method).toBe('POST');
    post.flush({ ...REMOTE, status: 'REJECTED' });
    await settle(rendered);
    backend.expectOne('/attendance/requests?status=PENDING').flush({ requests: [CORRECTION] });
  });

  it('says in a red band when somebody decided it first (409)', async () => {
    const { rendered, backend } = await renderPage();

    await userEvent.click(screen.getAllByRole('button', { name: 'Approve' })[0]!);
    backend
      .expectOne('/attendance/requests/r-1/approve')
      .flush({ code: 'conflict', message: 'already decided' }, { status: 409, statusText: 'Conflict' });
    await settle(rendered);

    // The red band is the shell's, so the message is asserted where it is written.
    expect(TestBed.inject(BandService).current()).toEqual({
      message: 'Somebody has already decided this request.',
      titleKey: 'band.failed',
      variant: 'error',
    });
    // The list on screen was out of date, so it is read again.
    backend.expectOne('/attendance/requests?status=PENDING').flush({ requests: [CORRECTION] });
  });

  it('shows the decision, who took it and the times that were there before', async () => {
    const { rendered, backend } = await renderPage();

    await userEvent.click(screen.getByRole('tab', { name: /Decided/ }));
    await settle(rendered);
    backend.expectOne('/attendance/requests?status=APPROVED').flush({ requests: [DECIDED] });
    backend.expectOne('/attendance/requests?status=REJECTED').flush({ requests: [] });
    await settle(rendered);

    expect(screen.getByText('Approved')).toBeInTheDocument();
    expect(screen.getByText(/Dalia/)).toBeInTheDocument();
    expect(screen.getByText(/Was In 08:10/)).toBeInTheDocument();
    // Nothing is decided twice.
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
  });

  it('says so when nothing is waiting', async () => {
    await renderPage(ADMIN_PERMISSIONS, ADMIN_USER, []);

    expect(screen.getByText('Nothing is waiting for you.')).toBeInTheDocument();
  });

  it('offers Add correction to the Admin only', async () => {
    await renderPage(TEACHER_PERMISSIONS, TEACHER_USER);

    expect(screen.queryByRole('button', { name: 'Add correction' })).not.toBeInTheDocument();
  });

  it('refuses the Admin a correction with no reason, then posts it with one', async () => {
    const { rendered, backend } = await renderPage();

    await userEvent.click(screen.getByRole('button', { name: 'Add correction' }));
    await settle(rendered);
    // The staff list is read when the dialog opens, never on the way in to the screen.
    backend.expectOne('/admin/staff/attendance-profiles').flush(STAFF);
    await settle(rendered);
    await userEvent.selectOptions(screen.getByLabelText(/^Employee/), 'WORKER:w-walid');
    await userEvent.clear(screen.getByLabelText(/^Day/));
    await userEvent.type(screen.getByLabelText(/^Day/), '2026-10-12');
    await settle(rendered);

    // The reason is the audit trail: without it nothing is sent.
    await userEvent.click(screen.getByRole('button', { name: 'Save correction' }));
    await settle(rendered);
    backend.expectNone('/admin/staff/WORKER/w-walid/attendance-corrections');
    expect(screen.getByText('A reason is required.')).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText(/^Reason/), 'Device was broken');
    await userEvent.type(screen.getByLabelText(/^Check-in/), '07:30');
    await settle(rendered);
    await userEvent.click(screen.getByRole('button', { name: 'Save correction' }));

    const post = backend.expectOne('/admin/staff/WORKER/w-walid/attendance-corrections');
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({
      date: '2026-10-12',
      reason: 'Device was broken',
      correctionKind: 'PHONE_PROBLEM',
      checkIn: '07:30',
    });
    post.flush({ ...CORRECTION, id: 'r-9', status: 'APPROVED' });
    await settle(rendered);
    backend.expectOne('/attendance/requests?status=PENDING').flush({ requests: [] });
  });
});
