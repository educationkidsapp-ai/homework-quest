import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { BASE_PATH } from '../../api';
import { PermissionService } from '../../core/permissions/permission.service';
import { renderHq } from '../../../testing/render';
import { StaffAttendanceFieldsComponent } from './staff-attendance-fields.component';

const providers = [provideHttpClient(), provideHttpClientTesting(), { provide: BASE_PATH, useValue: '' }];
const DEVICE_URL = '/admin/staff/USER/u-sara/attendance-device';

async function settle(rendered: { fixture: { detectChanges(): void } }) {
  await Promise.resolve();
  TestBed.tick();
  rendered.fixture.detectChanges();
}

async function renderFields(canReset: boolean) {
  const rendered = await renderHq(StaffAttendanceFieldsComponent, {
    inputs: { kind: 'USER', staffId: 'u-sara' },
    providers: [
      ...providers,
      { provide: PermissionService, useValue: { can: (key: string) => canReset && key === 'attendance.device.reset' } },
    ],
  });
  const backend = TestBed.inject(HttpTestingController);
  backend.expectOne('/admin/staff/attendance-profiles').flush([]);
  backend.expectOne(DEVICE_URL).flush({ installId: 'install-1', firstSeen: '2026-10-12T03:50:00Z' });
  await settle(rendered);
  return { rendered, backend };
}

describe('StaffAttendanceFieldsComponent — approved device (feature 15)', () => {
  beforeEach(() => localStorage.clear());

  it('shows the approved phone with its first-seen date', async () => {
    await renderFields(true);
    expect(screen.getByText('Approved device')).toBeTruthy();
    expect(screen.getByText(/First seen/)).toBeTruthy();
  });

  it('resets the device after the Admin confirms', async () => {
    const { rendered, backend } = await renderFields(true);
    await userEvent.click(screen.getByRole('button', { name: 'Reset device' }));
    await settle(rendered);
    expect(screen.getByText('Reset the approved device?')).toBeTruthy();

    const buttons = screen.getAllByRole('button', { name: 'Reset device' });
    await userEvent.click(buttons.at(-1)!);
    const reset = backend.expectOne(DEVICE_URL);
    expect(reset.request.method).toBe('DELETE');
    reset.flush({});
    await settle(rendered);

    expect(screen.getByText(/No phone approved yet/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Reset device' })).toBeNull();
  });

  it('hides Reset from a reader without attendance.device.reset', async () => {
    await renderFields(false);
    expect(screen.getByText(/First seen/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Reset device' })).toBeNull();
  });
});
