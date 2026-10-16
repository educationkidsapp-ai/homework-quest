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
import { FlagService } from '../../core/flags/flag.service';
import { MaterialDeadlineCardComponent } from './material-deadline-card.component';

function providersWith(flagOn: boolean): (Provider | EnvironmentProviders)[] {
  return [
    provideHttpClient(),
    provideHttpClientTesting(),
    provideRouter([{ path: '**', children: [] }]),
    { provide: BASE_PATH, useValue: '' },
    { provide: FlagService, useValue: { isOn: () => flagOn, ready: () => true } },
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

const SETTINGS = {
  dueWeekday: 'THURSDAY',
  dueTime: '12:00',
  remindHoursBefore: 4,
  timeZone: 'Asia/Riyadh',
};

async function settle(rendered: { fixture: { detectChanges: () => void } }): Promise<void> {
  await Promise.resolve();
  TestBed.tick();
  rendered.fixture.detectChanges();
}

async function renderCard(permissions = ADMIN_PERMISSIONS, user = ADMIN_USER, flagOn = true) {
  const rendered = await renderHq(MaterialDeadlineCardComponent, { providers: providersWith(flagOn) });
  const backend = TestBed.inject(HttpTestingController);

  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(user);
  TestBed.tick();
  backend.expectOne('/me/permissions').flush(permissions);

  if (flagOn) {
    backend.expectOne('/admin/material-settings').flush(SETTINGS);
  }
  await settle(rendered);
  return { rendered, backend };
}

describe('feature 31 — the Teaching material deadline card', () => {
  beforeEach(() => localStorage.clear());

  it('shows the saved rule, the school zone, and that the deadline is the week before', async () => {
    await renderCard();

    expect(document.querySelector('[data-hq-material-deadline]')).not.toBeNull();
    expect(screen.getByLabelText(/^Day/)).toHaveValue('THURSDAY');
    expect(screen.getByLabelText(/^Time/)).toHaveValue('12:00');
    expect(screen.getByLabelText(/^Remind this many hours before/)).toHaveValue(4);
    expect(document.querySelector('[data-hq-material-zone]')?.textContent).toContain('Asia/Riyadh');
    expect(document.body.textContent).toContain('the week before the week it is for');
  });

  it('saves the weekday, the time and the reminder hours', async () => {
    const { rendered, backend } = await renderCard();

    await userEvent.selectOptions(screen.getByLabelText(/^Day/), 'WEDNESDAY');
    const hours = screen.getByLabelText(/^Remind this many hours before/);
    await userEvent.clear(hours);
    await userEvent.type(hours, '6');
    await settle(rendered);

    document.querySelector<HTMLElement>('[data-hq-save-material-deadline] button')!.click();
    await settle(rendered);

    const put = backend.expectOne('/admin/material-settings');
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual({
      dueWeekday: 'WEDNESDAY',
      dueTime: '12:00',
      remindHoursBefore: 6,
    });
    put.flush({ ...SETTINGS, dueWeekday: 'WEDNESDAY', remindHoursBefore: 6 });
    await settle(rendered);
    backend.expectOne('/admin/material-settings').flush({ ...SETTINGS, dueWeekday: 'WEDNESDAY' });
  });

  it('refuses a reminder outside 1 to 72 hours rather than sending it', async () => {
    const { rendered } = await renderCard();

    const hours = screen.getByLabelText(/^Remind this many hours before/);
    await userEvent.clear(hours);
    await userEvent.type(hours, '200');
    await settle(rendered);

    expect(document.body.textContent).toContain('Between 1 and 72 hours');
    expect(
      document.querySelector<HTMLButtonElement>('[data-hq-save-material-deadline] button')?.disabled,
    ).toBe(true);
  });

  /** `attendance.settings.write` is the Admin's: a manager reads the rule and changes nothing. */
  it('gives a manager the fields disabled and no Save', async () => {
    await renderCard(MANAGER_PERMISSIONS, MANAGERIAL_USER);

    expect(screen.getByLabelText(/^Day/)).toBeDisabled();
    expect(screen.getByLabelText(/^Time/)).toBeDisabled();
    expect(document.querySelector('[data-hq-save-material-deadline]')).toBeNull();
  });

  /** With the switch off the routes answer 404 and there is no older card behind it. */
  it('is absent while the switch is off, and asks the server nothing', async () => {
    await renderCard(ADMIN_PERMISSIONS, ADMIN_USER, false);

    expect(document.querySelector('[data-hq-material-deadline]')).toBeNull();
  });
});
