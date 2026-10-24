import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { screen, within } from '@testing-library/angular';
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

/** Feature 34: three days of annual leave, waiting for the Admin's second step. */
const LEAVE = {
  id: 'r-4',
  type: 'LEAVE',
  leaveType: 'ANNUAL',
  status: 'PENDING',
  stage: 'ADMIN',
  staff: 'USER:u-nada',
  staffName: 'Nada',
  date: '2026-11-01',
  toDate: '2026-11-03',
  days: 3,
  reason: 'My sister is getting married',
  createdAt: 1_760_001_000_000,
};

/** Feature 34: two hours of permission, still with her direct manager. */
const PERMISSION = {
  id: 'r-5',
  type: 'PERMISSION',
  status: 'PENDING',
  stage: 'MANAGER',
  staff: 'USER:u-tala',
  staffName: 'Tala',
  date: '2026-10-21',
  fromTime: '07:00',
  toTime: '09:00',
  minutes: 120,
  reason: 'A hospital appointment',
  createdAt: 1_760_001_500_000,
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

  // Pending is the filter the screen opens on: nothing else is asked for.
  backend.expectOne('/attendance/requests?status=PENDING').flush({ requests: pending });
  await settle(rendered);
  return { rendered, backend };
}

/** The dialog's own fields, so the filter bar's "Employee" is not matched instead. */
const inDialog = () => within(screen.getByRole('dialog'));

/** And the filter bar's own, so the correction dialog's "Employee" is not matched instead. */
const inFilters = () => within(document.querySelector<HTMLElement>('[data-hq-filters]')!);

describe('features 18 and 34 — Requests', () => {
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

  it('reads a leave as its range, its days and its type, and a permission as its window', async () => {
    await renderPage(ADMIN_PERMISSIONS, ADMIN_USER, [LEAVE, PERMISSION]);

    const [leave, permission] = screen.getAllByRole('row').slice(1);
    expect(leave).toHaveTextContent('Leave · Annual');
    expect(leave).toHaveTextContent('2026-11-01 to 2026-11-03');
    expect(leave).toHaveTextContent('3 days');
    expect(permission).toHaveTextContent('Permission');
    expect(permission).toHaveTextContent('2026-10-21');
    expect(permission).toHaveTextContent('07:00–09:00');
    expect(permission).toHaveTextContent('120 minutes');
  });

  it('marks the request her manager approved as waiting for the Admin, and no other', async () => {
    await renderPage(ADMIN_PERMISSIONS, ADMIN_USER, [LEAVE, PERMISSION]);

    const stages = screen.getAllByText('Waiting for the Admin');
    expect(stages).toHaveLength(1);
    expect(screen.getAllByRole('row')[1]).toHaveTextContent('Waiting for the Admin');
  });

  it('asks the server again for each of the four filters', async () => {
    const { rendered, backend } = await renderPage();

    await userEvent.selectOptions(inFilters().getByLabelText(/^Request type/), 'LEAVE');
    await settle(rendered);
    backend.expectOne('/attendance/requests?status=PENDING&type=LEAVE').flush({ requests: [LEAVE] });
    await settle(rendered);

    await userEvent.selectOptions(inFilters().getByLabelText(/^Status/), 'APPROVED');
    await settle(rendered);
    backend.expectOne('/attendance/requests?status=APPROVED&type=LEAVE').flush({ requests: [] });
    await settle(rendered);

    // The employee list is the people the screen has seen, not a read of the staff directory.
    await userEvent.selectOptions(inFilters().getByLabelText(/^Employee/), 'USER:u-nada');
    await settle(rendered);
    backend
      .expectOne('/attendance/requests?status=APPROVED&type=LEAVE&staff=USER%3Au-nada')
      .flush({ requests: [] });
    await settle(rendered);

    await userEvent.type(inFilters().getByLabelText(/^Month/), '2026-11');
    await settle(rendered);
    backend
      .expectOne('/attendance/requests?status=APPROVED&type=LEAVE&staff=USER%3Au-nada&month=2026-11')
      .flush({ requests: [] });
    await settle(rendered);
    expect(screen.getByText('Nothing has been decided yet.')).toBeInTheDocument();
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

  it('refuses to reject a leave without a reason, then posts the reason it was given', async () => {
    const { rendered, backend } = await renderPage(ADMIN_PERMISSIONS, ADMIN_USER, [LEAVE]);

    await userEvent.click(screen.getByRole('button', { name: 'Reject' }));
    await settle(rendered);
    expect(screen.getByText(/Nada will read this reason, so it is required./)).toBeInTheDocument();

    await userEvent.click(inDialog().getByRole('button', { name: 'Reject the request' }));
    await settle(rendered);
    backend.expectNone('/attendance/requests/r-4/reject');
    expect(screen.getByText('A reason is required.')).toBeInTheDocument();

    await userEvent.type(inDialog().getByLabelText(/^Reason for rejecting/), 'The week is exam week');
    await settle(rendered);
    await userEvent.click(inDialog().getByRole('button', { name: 'Reject the request' }));

    const post = backend.expectOne('/attendance/requests/r-4/reject');
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({ reason: 'The week is exam week' });
    post.flush({ ...LEAVE, status: 'REJECTED' });
    await settle(rendered);
    backend.expectOne('/attendance/requests?status=PENDING').flush({ requests: [] });
  });

  it('rejects a remote day with no reason at all, as feature 18 did', async () => {
    const { rendered, backend } = await renderPage(ADMIN_PERMISSIONS, ADMIN_USER, [REMOTE]);

    await userEvent.click(screen.getByRole('button', { name: 'Reject' }));
    await settle(rendered);
    expect(screen.getByText(/Tala will read this reason if you give one./)).toBeInTheDocument();

    await userEvent.click(inDialog().getByRole('button', { name: 'Reject the request' }));
    const post = backend.expectOne('/attendance/requests/r-1/reject');
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toBeNull();
    post.flush({ ...REMOTE, status: 'REJECTED' });
    await settle(rendered);
    backend.expectOne('/attendance/requests?status=PENDING').flush({ requests: [] });
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

  it('shows the decision, who took it, the times that were there before and no decide buttons', async () => {
    const { rendered, backend } = await renderPage();

    await userEvent.selectOptions(inFilters().getByLabelText(/^Status/), 'APPROVED');
    await settle(rendered);
    backend.expectOne('/attendance/requests?status=APPROVED').flush({ requests: [DECIDED] });
    await settle(rendered);

    const row = screen.getAllByRole('row')[1]!;
    expect(row).toHaveTextContent('Approved');
    expect(row).toHaveTextContent('Dalia');
    expect(screen.getByText(/Was In 08:10/)).toBeInTheDocument();
    // Nothing is decided twice.
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
  });

  it('says why a rejected request was refused', async () => {
    const { rendered, backend } = await renderPage();

    await userEvent.selectOptions(inFilters().getByLabelText(/^Status/), 'REJECTED');
    await settle(rendered);
    backend
      .expectOne('/attendance/requests?status=REJECTED')
      .flush({ requests: [{ ...LEAVE, status: 'REJECTED', stage: undefined, rejectReason: 'Exam week' }] });
    await settle(rendered);

    expect(screen.getByText('Reason: Exam week')).toBeInTheDocument();
    expect(screen.queryByText('Waiting for the Admin')).not.toBeInTheDocument();
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
    await userEvent.selectOptions(inDialog().getByLabelText(/^Employee/), 'WORKER:w-walid');
    await userEvent.clear(inDialog().getByLabelText(/^Day/));
    await userEvent.type(inDialog().getByLabelText(/^Day/), '2026-10-12');
    await settle(rendered);

    // The reason is the audit trail: without it nothing is sent.
    await userEvent.click(screen.getByRole('button', { name: 'Save correction' }));
    await settle(rendered);
    backend.expectNone('/admin/staff/WORKER/w-walid/attendance-corrections');
    expect(screen.getByText('A reason is required.')).toBeInTheDocument();

    await userEvent.type(inDialog().getByLabelText(/^Reason/), 'Device was broken');
    await userEvent.type(inDialog().getByLabelText(/^Check-in/), '07:30');
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
