import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { screen, within } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { BASE_PATH } from '../../api';
import { ADMIN_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { FlagService } from '../../core/flags/flag.service';
import { FloorsPage } from './floors.page';

const providers: (Provider | EnvironmentProviders)[] = [
  provideHttpClient(),
  provideHttpClientTesting(),
  provideRouter([{ path: '**', children: [] }]),
  { provide: BASE_PATH, useValue: '' },
  // `floorSupervisor` is on; the whole screen is behind it (`*hqFeature` and the route's guard).
  { provide: FlagService, useValue: { isOn: () => true, refresh: () => undefined, ready: () => true } },
];

const ADMIN_PERMISSIONS = { role: 'ADMIN', permissions: ['floor.manage', 'section.read'], readOnly: false };
/** A read-only "View as" session holds the key and may press nothing (`WRITE_PERMISSIONS`). */
const VIEW_AS = { role: 'ADMIN', permissions: ['floor.manage', 'section.read'], readOnly: true };

const FLOOR_TWO = {
  id: 'f-2',
  name: 'Floor 2',
  academy: 'british',
  classes: [
    {
      floorId: 'f-2',
      floorName: 'Floor 2',
      classId: 'c-1a',
      className: '1A',
      curriculum: 'british',
      grade: 1,
    },
  ],
  supervisors: [{ userId: 'u-mona', displayName: 'Mona Saeed' }],
};

const CLASSES = [
  { id: 'c-1a', schoolId: 'school-a', curriculum: 'british', grade: 1, name: '1A', active: true },
  { id: 'c-1b', schoolId: 'school-a', curriculum: 'british', grade: 1, name: '1B', active: true },
];

const SUPERVISORS = [
  { userId: 'u-mona', fullName: 'Mona Saeed', email: 'floor2@alnoor.test', status: 'active', floors: [] },
  { userId: 'u-hind', fullName: 'Hind Ali', email: 'floor3@alnoor.test', status: 'active', floors: [] },
];

async function settle(rendered: { fixture: { detectChanges: () => void } }): Promise<void> {
  await Promise.resolve();
  TestBed.tick();
  rendered.fixture.detectChanges();
}

async function renderFloors(floors: readonly unknown[] = [FLOOR_TWO], permissions = ADMIN_PERMISSIONS) {
  const rendered = await renderHq(FloorsPage, { providers });
  const backend = TestBed.inject(HttpTestingController);

  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(ADMIN_USER);
  TestBed.tick();
  backend.expectOne('/me/permissions').flush(permissions);

  backend.expectOne('/admin/floors').flush(floors);
  backend.expectOne('/admin/classes').flush(CLASSES);
  backend.expectOne('/admin/floor-supervisors').flush(SUPERVISORS);
  await settle(rendered);
  return { rendered, backend };
}

describe('Floors', () => {
  beforeEach(() => localStorage.clear());

  it('shows a floor with its academy, its classes and its supervisors', async () => {
    await renderFloors();

    expect(screen.getByRole('cell', { name: 'Floor 2' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'British' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: '1A' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'Mona Saeed' })).toBeInTheDocument();
  });

  /**
   * The acceptance criterion: "The Admin creates a floor with classes and assigns a supervisor."
   * One dialog, four fields, one POST — and the body is what the contract takes.
   */
  it('creates a floor with the classes and the supervisor chosen', async () => {
    const { rendered, backend } = await renderFloors([]);

    await userEvent.click(screen.getAllByRole('button', { name: 'Add floor' })[0]!);
    const dialog = within(screen.getByRole('dialog'));
    await userEvent.type(dialog.getByLabelText('Floor name'), 'Floor 2');
    await userEvent.selectOptions(dialog.getByLabelText('Academy'), 'british');
    await settle(rendered);
    await userEvent.click(dialog.getByLabelText('British · Grade 1 · 1A'));
    await userEvent.click(dialog.getByLabelText('Mona Saeed'));
    await settle(rendered);
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    const request = backend.expectOne('/admin/floors');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      name: 'Floor 2',
      academy: 'british',
      classIds: ['c-1a'],
      supervisorIds: ['u-mona'],
    });
    request.flush(FLOOR_TWO);
    await settle(rendered);
    // The list is read again, so the new row is on screen without a refresh nobody would do.
    backend.expectOne('/admin/floors').flush([FLOOR_TWO]);
    await settle(rendered);
    expect(screen.getByRole('cell', { name: 'Floor 2' })).toBeInTheDocument();
  });

  /**
   * A class is on at most one floor, and the server says which floor already holds it. That
   * sentence belongs in the form, beside the tick that caused it — and nothing may have been
   * written, so the dialog stays open on exactly what she chose.
   */
  it('shows the 409 that names the floor already holding a class, in the form', async () => {
    const { rendered, backend } = await renderFloors([]);

    await userEvent.click(screen.getAllByRole('button', { name: 'Add floor' })[0]!);
    const dialog = within(screen.getByRole('dialog'));
    await userEvent.type(dialog.getByLabelText('Floor name'), 'Floor 3');
    await userEvent.selectOptions(dialog.getByLabelText('Academy'), 'british');
    await settle(rendered);
    await userEvent.click(dialog.getByLabelText('British · Grade 1 · 1A'));
    await settle(rendered);
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    backend
      .expectOne('/admin/floors')
      .flush(
        { code: 'conflict', message: '1A is already on Floor 2' },
        { status: 409, statusText: 'Conflict' },
      );
    await settle(rendered);

    expect(screen.getByText('1A is already on Floor 2')).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('opens the edit dialog on the floor as it is, and sends only a PATCH', async () => {
    const { rendered, backend } = await renderFloors();

    await userEvent.click(screen.getByRole('button', { name: 'Actions for Floor 2' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Edit' }));
    await settle(rendered);

    const dialog = within(screen.getByRole('dialog'));
    expect(dialog.getByLabelText('Floor name')).toHaveValue('Floor 2');
    // The class it already holds and the supervisor it already has are ticked.
    expect(dialog.getByLabelText('British · Grade 1 · 1A')).toBeChecked();
    expect(dialog.getByLabelText('Mona Saeed')).toBeChecked();
    expect(dialog.getByLabelText('Hind Ali')).not.toBeChecked();

    await userEvent.click(dialog.getByLabelText('Hind Ali'));
    await settle(rendered);
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    const request = backend.expectOne('/admin/floors/f-2');
    expect(request.request.method).toBe('PATCH');
    expect(request.request.body).toEqual({
      name: 'Floor 2',
      academy: 'british',
      classIds: ['c-1a'],
      supervisorIds: ['u-mona', 'u-hind'],
    });
  });

  /** Deleting takes a supervisor's whole scope away, so it asks first — and asks nothing of the
   * server while the question is on screen. */
  it('confirms before deleting a floor', async () => {
    const { rendered, backend } = await renderFloors();

    await userEvent.click(screen.getByRole('button', { name: 'Actions for Floor 2' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Delete floor' }));
    await settle(rendered);

    expect(screen.getByText('Delete this floor?')).toBeInTheDocument();
    backend.expectNone('/admin/floors/f-2');

    await userEvent.click(screen.getByRole('button', { name: 'Delete' }));
    const request = backend.expectOne('/admin/floors/f-2');
    expect(request.request.method).toBe('DELETE');
    request.flush(null);
    await settle(rendered);
    backend.expectOne('/admin/floors').flush([]);
    await settle(rendered);

    expect(screen.getByText('No floors yet. Add the first one.')).toBeInTheDocument();
  });

  it('offers a read-only session the list and nothing to press', async () => {
    await renderFloors([FLOOR_TWO], VIEW_AS);

    expect(screen.getByRole('cell', { name: 'Floor 2' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Add floor' })).not.toBeInTheDocument();
  });

  it('says so when a floor has nobody looking after it', async () => {
    await renderFloors([{ ...FLOOR_TWO, supervisors: [] }]);

    expect(screen.getByText('No supervisor')).toBeInTheDocument();
  });
});
