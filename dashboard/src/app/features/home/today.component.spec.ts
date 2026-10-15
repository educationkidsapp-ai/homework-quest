import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import { TranslocoService } from '@jsverse/transloco';
import { beforeEach, describe, expect, it } from 'vitest';
import { type TodayHome, BASE_PATH } from '../../api';
import { ADMIN_USER, MANAGERIAL_USER, type DashboardUserFixture } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { TodayComponent } from './today.component';

const providers: (Provider | EnvironmentProviders)[] = [
  provideHttpClient(),
  provideHttpClientTesting(),
  provideRouter([{ path: '**', children: [] }]),
  { provide: BASE_PATH, useValue: '' },
];

/** The Admin holds the device reset; nobody else does (`permissions.json`). */
const ADMIN_PERMISSIONS = {
  role: 'ADMIN',
  permissions: ['attendance.requests.decide', 'attendance.device.reset'],
  readOnly: false,
};

const MANAGER_PERMISSIONS = {
  role: 'MANAGERIAL',
  permissions: ['attendance.requests.decide'],
  readOnly: false,
};

const EMPTY: TodayHome = {
  day: '2026-10-19',
  timeZone: 'Asia/Riyadh',
  waiting: { requests: [], devices: [] },
  overdueComplaints: { total: 0, items: [] },
  unread: { count: 0, senders: [] },
  counts: [],
};

const REQUEST = {
  id: 'r-1',
  type: 'REMOTE_DAY',
  status: 'PENDING',
  staff: 'USER:u-tala',
  staffName: 'Tala',
  date: '2026-10-20',
  reason: 'The road is flooded',
  createdAt: 1_760_000_000_000,
};

const FULL: TodayHome = {
  ...EMPTY,
  attendance: {
    scope: 'SCHOOL',
    counts: { present: 18, late: 2, notArrived: 3, remote: 1, noCheckOut: 0 },
  },
  waiting: {
    requests: [REQUEST],
    devices: [{ kind: 'WORKER', id: 'w-walid', fullName: 'Walid Worker', lastAttemptAt: 1_760_000_000_000 }],
  },
  overdueComplaints: {
    total: 12,
    items: [
      {
        id: 'c-1',
        childName: 'Lina',
        title: 'The bus is always late',
        recipientName: 'Ms Sara',
        openedAt: 1_759_000_000_000,
        ageHours: 30,
      },
      {
        id: 'c-2',
        childName: 'Omar',
        title: 'No reply about the trip',
        recipientName: 'Ms Hana',
        openedAt: 1_758_000_000_000,
        ageHours: 72,
      },
    ],
  },
  unread: {
    count: 5,
    senders: [
      { threadId: 't-1', name: 'Mona Ahmed' },
      { threadId: 't-2', name: 'Khalid Saleh' },
      { threadId: 't-3', name: 'Reem Ali' },
      { threadId: 't-4', name: 'Nobody Fourth' },
    ],
  },
};

async function settle(rendered: { fixture: { detectChanges: () => void } }): Promise<void> {
  await Promise.resolve();
  TestBed.tick();
  rendered.fixture.detectChanges();
}

async function renderToday(
  today: TodayHome,
  user: DashboardUserFixture = ADMIN_USER,
  permissions: { role: string; permissions: readonly string[]; readOnly: boolean } = ADMIN_PERMISSIONS,
) {
  const rendered = await renderHq(TodayComponent, { providers });
  const backend = TestBed.inject(HttpTestingController);

  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(user);
  TestBed.tick();
  backend.expectOne('/me/permissions').flush(permissions);

  for (const request of backend.match('/me/today')) if (!request.cancelled) request.flush(today);
  // Whatever the shell's own services ask for is not this component's business.
  for (const request of backend.match(() => true)) if (!request.cancelled) request.flush({});
  await settle(rendered);
  return { rendered, backend };
}

/** The href of the one link carrying this data attribute. */
function hrefOf(attribute: string): string {
  return document.querySelector(`[${attribute}]`)?.getAttribute('href') ?? '';
}

describe('What needs me today', () => {
  beforeEach(() => localStorage.clear());

  it('draws the four cards from one call', async () => {
    await renderToday(FULL);

    expect(screen.getByText("Today's attendance")).toBeInTheDocument();
    expect(screen.getByText('Waiting for you')).toBeInTheDocument();
    expect(screen.getByText('Overdue complaints')).toBeInTheDocument();
    expect(screen.getByText('Unread messages')).toBeInTheDocument();
    expect(screen.queryByText('Nothing needs you right now')).not.toBeInTheDocument();
  });

  /** A card with nothing in it is not drawn — four empty frames are not an answer. */
  it('leaves out a card the server answered empty', async () => {
    await renderToday({ ...FULL, overdueComplaints: { total: 0, items: [] }, unread: { count: 0, senders: [] } });

    expect(screen.getByText('Waiting for you')).toBeInTheDocument();
    expect(screen.queryByText('Overdue complaints')).not.toBeInTheDocument();
    expect(screen.queryByText('Unread messages')).not.toBeInTheDocument();
  });

  /** `staffAttendance` off, so the board itself does not exist for this school. */
  it('leaves out the attendance card when the answer carries no attendance at all', async () => {
    await renderToday({ ...FULL, attendance: undefined });

    expect(screen.queryByText("Today's attendance")).not.toBeInTheDocument();
    expect(screen.getByText('Waiting for you')).toBeInTheDocument();
  });

  it('says one calm line, and nothing else, when all four are empty', async () => {
    await renderToday(EMPTY);

    expect(screen.getByText('Nothing needs you right now')).toBeInTheDocument();
    for (const title of ["Today's attendance", 'Waiting for you', 'Overdue complaints', 'Unread messages'])
      expect(screen.queryByText(title)).not.toBeInTheDocument();
  });

  it('links each attendance number to the board filtered to it', async () => {
    await renderToday(FULL);

    expect(hrefOf('data-hq-today-status="late"')).toBe('/admin/staff-attendance?status=late');
    expect(hrefOf('data-hq-today-status="notArrived"')).toBe('/admin/staff-attendance?status=notArrived');
    expect(hrefOf('data-hq-today-status="present"')).toBe('/admin/staff-attendance?status=present');
    expect(hrefOf('data-hq-today-status="remote"')).toBe('/admin/staff-attendance?status=remote');
    expect(screen.getByText('18')).toBeInTheDocument();
  });

  it("opens an overdue complaint itself, with its age, and counts the ones it did not list", async () => {
    await renderToday(FULL);

    expect(hrefOf('data-hq-today-complaint="c-1"')).toBe('/admin/complaints?open=c-1');
    // The server counts whole hours: under two days it is read in hours, after that in days.
    expect(screen.getByText('30 h')).toBeInTheDocument();
    expect(screen.getByText('3 days')).toBeInTheDocument();
    expect(screen.getByText('+10 more')).toBeInTheDocument();
  });

  it('names the three latest senders and opens her Messages', async () => {
    await renderToday(FULL);

    expect(screen.getByText('5 unread')).toBeInTheDocument();
    expect(hrefOf('data-hq-today-sender="t-1"')).toBe('/admin/messages?thread=t-1');
    expect(screen.getByText('Reem Ali')).toBeInTheDocument();
    // Three, not four: the card is a way in, not the inbox.
    expect(screen.queryByText('Nobody Fourth')).not.toBeInTheDocument();
  });

  /** The spec's two-tap rule: Approve is one tap from Home, and the row goes at once. */
  it('approves from the card with one call, and the request leaves the list', async () => {
    const { rendered, backend } = await renderToday(FULL);

    screen.getByRole('button', { name: 'Approve' }).click();
    const call = backend.expectOne('/attendance/requests/r-1/approve');
    expect(call.request.method).toBe('POST');
    call.flush({});
    await settle(rendered);

    expect(document.querySelector('[data-hq-today-request="r-1"]')).toBeNull();
    // Gone from the list *and* the whole answer re-read behind it.
    for (const request of backend.match('/me/today'))
      request.flush({ ...FULL, waiting: { requests: [], devices: FULL.waiting?.devices ?? [] } });
    await settle(rendered);
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
  });

  it('rejects the same way, with the reject call and no other', async () => {
    const { rendered, backend } = await renderToday(FULL);

    screen.getByRole('button', { name: 'Reject' }).click();
    backend.expectNone('/attendance/requests/r-1/approve');
    backend.expectOne('/attendance/requests/r-1/reject').flush({});
    await settle(rendered);

    expect(document.querySelector('[data-hq-today-request="r-1"]')).toBeNull();
  });

  /** 409: somebody decided it first. Her own sentence, and the item stays until the server says. */
  it('keeps the request and shows the red band when it was already decided', async () => {
    const { rendered, backend } = await renderToday(FULL);

    screen.getByRole('button', { name: 'Approve' }).click();
    backend
      .expectOne('/attendance/requests/r-1/approve')
      .flush({ code: 'conflict', message: 'already_decided' }, { status: 409, statusText: 'Conflict' });
    await settle(rendered);

    expect(document.querySelector('[data-hq-today-request="r-1"]')).not.toBeNull();
  });

  it('resets a blocked device from the card, and the block leaves the list', async () => {
    const { rendered, backend } = await renderToday(FULL);

    expect(screen.getByText('Walid Worker')).toBeInTheDocument();
    screen.getByRole('button', { name: 'Reset device' }).click();
    const call = backend.expectOne('/admin/staff/WORKER/w-walid/attendance-device');
    expect(call.request.method).toBe('DELETE');
    call.flush({});
    await settle(rendered);

    expect(document.querySelector('[data-hq-today-device="WORKER:w-walid"]')).toBeNull();
  });

  /**
   * Admin only, and the manager's links are her own namespace. The server leaves `devices` out of
   * her answer altogether; this is the second lock — a button that answers 403 is worse than none.
   */
  it("offers a manager no device reset, and points her links at /management", async () => {
    await renderToday(FULL, MANAGERIAL_USER, MANAGER_PERMISSIONS);

    expect(screen.queryByRole('button', { name: 'Reset device' })).not.toBeInTheDocument();
    expect(screen.queryByText('Walid Worker')).not.toBeInTheDocument();
    // She still decides her own people's requests.
    expect(screen.getByRole('button', { name: 'Approve' })).toBeInTheDocument();
    expect(hrefOf('data-hq-today-status="late"')).toBe('/management/staff-attendance?status=late');
    expect(hrefOf('data-hq-today-complaint="c-1"')).toBe('/management/complaints?open=c-1');
    expect(hrefOf('data-hq-today-sender="t-1"')).toBe('/management/messages?thread=t-1');
  });

  it('reads in Arabic without asking the server again', async () => {
    const { rendered, backend } = await renderToday(FULL);
    TestBed.inject(TranslocoService).setActiveLang('ar');
    await settle(rendered);

    expect(screen.getByText('حضور اليوم')).toBeInTheDocument();
    expect(screen.getByText('بانتظار موافقتك')).toBeInTheDocument();
    expect(screen.getByText('شكاوى متأخرة')).toBeInTheDocument();
    expect(screen.getByText('رسائل غير مقروءة')).toBeInTheDocument();
    backend.expectNone('/me/today');
  });
});
