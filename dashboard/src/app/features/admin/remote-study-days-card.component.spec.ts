import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BASE_PATH } from '../../api';
import { ADMIN_USER, MANAGERIAL_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { FlagService } from '../../core/flags/flag.service';
import { PlatformService } from '../../core/platform/platform.service';
import { RemoteStudyDaysCardComponent } from './remote-study-days-card.component';

/** The pilot school's own zone (UTC+3) — the one the date field's minimum is read in. */
const SCHOOL_ZONE = 'Asia/Riyadh';

function providersWith(flagOn: boolean, zone = SCHOOL_ZONE): (Provider | EnvironmentProviders)[] {
  return [
    provideHttpClient(),
    provideHttpClientTesting(),
    provideRouter([{ path: '**', children: [] }]),
    { provide: BASE_PATH, useValue: '' },
    { provide: FlagService, useValue: { isOn: () => flagOn, ready: () => true } },
    // The zone the school keeps, as `GET /platform-settings` answers it.
    { provide: PlatformService, useValue: { timezone: () => zone } },
  ];
}

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
  { id: 'rsd-2', date: '2026-11-02', reason: 'Rain', academy: 'british', grades: [1, 2] },
  { id: 'rsd-1', date: '2026-10-20', reason: 'Sandstorm', grades: [] },
];

/**
 * The school's own academies and grades, as `GET /management/classes` groups them: British teaches
 * grades 1 and 2, American grade 5 — and no school here teaches a grade 9, which is the point of
 * reading this instead of a hard-coded curriculum list.
 */
const CLASSES = [
  { curriculum: 'british', grade: 1, classes: [{ classId: 'c-1', className: '1A' }] },
  { curriculum: 'british', grade: 2, classes: [{ classId: 'c-2', className: '2A' }] },
  { curriculum: 'american', grade: 5, classes: [{ classId: 'c-3', className: '5A' }] },
];

async function settle(rendered: { fixture: { detectChanges: () => void } }): Promise<void> {
  await Promise.resolve();
  TestBed.tick();
  rendered.fixture.detectChanges();
}

async function renderCard(
  permissions = ADMIN_PERMISSIONS,
  user = ADMIN_USER,
  days = DAYS,
  flagOn = true,
  zone = SCHOOL_ZONE,
) {
  const rendered = await renderHq(RemoteStudyDaysCardComponent, {
    providers: providersWith(flagOn, zone),
  });
  const backend = TestBed.inject(HttpTestingController);

  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(user);
  TestBed.tick();
  backend.expectOne('/me/permissions').flush(permissions);

  if (flagOn) {
    backend.expectOne('/admin/remote-study-days').flush({ days });
    await settle(rendered);
    // The academies and grades are read only for the Admin who has a form to fill.
    if (permissions === ADMIN_PERMISSIONS) backend.expectOne('/management/classes').flush(CLASSES);
  }
  await settle(rendered);
  return { rendered, backend };
}

describe('feature 23 — the Remote study days card', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => vi.useRealTimers());

  it('lists today and later, soonest first, with the academy and the grades in scope', async () => {
    await renderCard();

    const rows = screen.getAllByRole('row').slice(1);
    expect(rows[0]).toHaveTextContent('2026-10-20');
    expect(rows[0]).toHaveTextContent('Whole school');
    expect(rows[0]).toHaveTextContent('Sandstorm');
    expect(rows[1]).toHaveTextContent('2026-11-02');
    expect(rows[1]).toHaveTextContent('British · Grades 1, 2');
  });

  it('says in one line who hears about the day', async () => {
    await renderCard();

    expect(
      screen.getByText(/their parents get an announcement, and the staff in scope check in from home/),
    ).toBeInTheDocument();
  });

  it('offers the school’s real academies, not a hard-coded list', async () => {
    await renderCard();

    const academies = screen.getByLabelText(/^Academy/);
    expect(academies).toHaveTextContent('American');
    expect(academies).toHaveTextContent('British');
    // The placeholder is the whole school, which is what no academy means on the wire.
    expect(academies).toHaveTextContent('Whole school');
  });

  it('offers only the chosen academy’s grades, and forgets them when the academy changes', async () => {
    const { rendered } = await renderCard();

    // Every grade of the school while the day is the whole school's.
    expect(screen.getByLabelText('Grade 1')).toBeInTheDocument();
    expect(screen.getByLabelText('Grade 5')).toBeInTheDocument();

    await userEvent.selectOptions(screen.getByLabelText(/^Academy/), 'british');
    await settle(rendered);

    expect(screen.getByLabelText('Grade 1')).toBeInTheDocument();
    expect(screen.getByLabelText('Grade 2')).toBeInTheDocument();
    expect(screen.queryByLabelText('Grade 5')).not.toBeInTheDocument();

    await userEvent.click(screen.getByLabelText('Grade 2'));
    await settle(rendered);
    await userEvent.selectOptions(screen.getByLabelText(/^Academy/), 'american');
    await settle(rendered);

    // Grade 2 belonged to British, so picking American starts from every grade of American.
    expect(screen.getByLabelText('Grade 5')).not.toBeChecked();
  });

  it('declares a day for one academy’s grades and reads the list again', async () => {
    const { rendered, backend } = await renderCard();

    await userEvent.type(screen.getByLabelText(/^Day/), '2026-12-01');
    await userEvent.selectOptions(screen.getByLabelText(/^Academy/), 'british');
    await settle(rendered);
    await userEvent.click(screen.getByLabelText('Grade 2'));
    await userEvent.type(screen.getByLabelText(/^Reason/), 'Exam week');
    await settle(rendered);
    await userEvent.click(screen.getByRole('button', { name: 'Add remote study day' }));

    const post = backend.expectOne('/admin/remote-study-days');
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({
      date: '2026-12-01',
      reason: 'Exam week',
      grades: [2],
      academy: 'british',
    });
    post.flush({ id: 'rsd-3', date: '2026-12-01', reason: 'Exam week', academy: 'british', grades: [2] });
    await settle(rendered);
    backend.expectOne('/admin/remote-study-days').flush({ days: DAYS });
  });

  it('leaves the academy out and the grades empty when the day is the whole school', async () => {
    const { rendered, backend } = await renderCard();

    await userEvent.type(screen.getByLabelText(/^Day/), '2026-12-01');
    await userEvent.type(screen.getByLabelText(/^Reason/), 'Sandstorm warning');
    await settle(rendered);
    await userEvent.click(screen.getByRole('button', { name: 'Add remote study day' }));

    const post = backend.expectOne('/admin/remote-study-days');
    expect(post.request.body).toEqual({
      date: '2026-12-01',
      reason: 'Sandstorm warning',
      grades: [],
    });
  });

  it('refuses to declare a day without a date or a reason', async () => {
    await renderCard();

    expect(screen.getByRole('button', { name: 'Add remote study day' })).toBeDisabled();
  });

  it('asks before it cancels a day, then deletes it and reads the list again', async () => {
    const { rendered, backend } = await renderCard();

    await userEvent.click(screen.getByRole('button', { name: 'Remove the remote study day on 2026-10-20' }));
    await settle(rendered);
    // The announcement the parents already read is withdrawn by this, so it is confirmed first.
    expect(screen.getByText('Cancel this remote study day?')).toBeInTheDocument();
    backend.expectNone('/admin/remote-study-days/rsd-1');

    await userEvent.click(screen.getByRole('button', { name: 'Remove' }));
    const deleted = backend.expectOne('/admin/remote-study-days/rsd-1');
    expect(deleted.request.method).toBe('DELETE');
    deleted.flush(null);
    await settle(rendered);
    backend.expectOne('/admin/remote-study-days').flush({ days: [DAYS[0]] });
  });

  it('is read-only for a manager: the list, and no form or delete', async () => {
    await renderCard(MANAGER_PERMISSIONS, MANAGERIAL_USER);

    expect(screen.getByText('Sandstorm')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Add remote study day' })).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/^Day/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Grade 1')).not.toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'Remove the remote study day on 2026-10-20' }),
    ).not.toBeInTheDocument();
  });

  it('says so when there is nothing declared', async () => {
    await renderCard(ADMIN_PERMISSIONS, ADMIN_USER, []);

    expect(screen.getByText('No remote study days yet.')).toBeInTheDocument();
  });

  /** With the flag off the routes answer 404, and there is no older card to fall back to. */
  it('draws nothing at all, and asks for nothing, while the flag is off', async () => {
    const { backend } = await renderCard(ADMIN_PERMISSIONS, ADMIN_USER, DAYS, false);

    expect(screen.queryByText('Remote study days')).not.toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    backend.expectNone('/admin/remote-study-days');
    backend.expectNone('/management/classes');
  });

  it('offers no day before today **in the school\u2019s zone**, not today in Greenwich', async () => {
    // 22:30 UTC is already half past one the next morning in Riyadh. The UTC date is the day
    // before, which is the past day the picker used to offer and the server then refused.
    vi.setSystemTime(new Date('2026-10-16T22:30:00Z'));

    await renderCard();

    const date = screen.getByLabelText(/^Day/);
    expect(date.getAttribute('min')).toBe('2026-10-17');
    expect(new Date().toISOString().slice(0, 10)).toBe('2026-10-16');
  });
});
