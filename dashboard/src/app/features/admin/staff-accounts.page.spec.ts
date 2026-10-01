import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { BASE_PATH } from '../../api';
import { ADMIN_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { AdminThreadService } from './admin-thread.service';
import { StaffAccountsPage } from './staff-accounts.page';

const ADMIN_PERMISSIONS = {
  role: 'ADMIN',
  permissions: ['coordinator.manage', 'manager.manage'],
  readOnly: false,
};

const HODA = {
  userId: 'u-hoda',
  email: 'hoda@alnoor.test',
  fullName: 'Hoda',
  phone: '0501112233',
  status: 'active',
  scopes: [{ subject: 'math', curriculum: 'british' }],
};

const NADIA = {
  userId: 'u-nadia',
  email: 'nadia@alnoor.test',
  fullName: 'Nadia',
  phone: '0504445566',
  status: 'active',
  departments: ['british'],
};

/** A resource answers on a microtask; the render that follows is what the assertions read. */
async function settle(rendered: { fixture: { detectChanges: () => void } }): Promise<void> {
  await Promise.resolve();
  TestBed.tick();
  rendered.fixture.detectChanges();
}

/**
 * The route's `screenId` is what tells the one component which of the two accounts it is
 * (`core/nav/area.routes.ts`), so the spec supplies it exactly as the router does.
 */
function providersFor(screenId: 'coordinators' | 'managers'): (Provider | EnvironmentProviders)[] {
  return [
    provideHttpClient(),
    provideHttpClientTesting(),
    provideRouter([{ path: '**', children: [] }]),
    { provide: BASE_PATH, useValue: '' },
    { provide: ActivatedRoute, useValue: { snapshot: { data: { screenId } } } },
  ];
}

async function renderSignedIn(
  screenId: 'coordinators' | 'managers',
  rows: readonly unknown[],
  permissions = ADMIN_PERMISSIONS,
) {
  const rendered = await renderHq(StaffAccountsPage, { providers: providersFor(screenId) });
  const backend = TestBed.inject(HttpTestingController);

  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(ADMIN_USER);
  TestBed.tick();
  backend.expectOne('/me/permissions').flush(permissions);

  backend.expectOne(`/admin/${screenId}`).flush(rows);
  await settle(rendered);

  return { rendered, backend };
}

/**
 * D1: the row's "Message" hands one id to `AdminThreadService` (its own spec covers the request
 * and the landing). Faked here so the screen's part — *which* id, under *which* name — is what
 * the test reads, without a flag map and a chat permission to stand up first.
 */
function fakeThreads(available = true) {
  const open = vi.fn();
  TestBed.overrideProvider(AdminThreadService, {
    useValue: { available: signal(available), pending: signal(''), open },
  });
  return open;
}

describe('Coordinators', () => {
  beforeEach(() => localStorage.clear());

  it('lists a coordinator with her number and the subject she supervises', async () => {
    await renderSignedIn('coordinators', [HODA]);

    expect(screen.getByRole('heading', { name: 'Coordinators' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'Hoda' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '0501112233' })).toHaveAttribute('href', 'tel:0501112233');
    expect(screen.getByText('Math · British')).toBeInTheDocument();
  });

  it('creates one with a subject, and shows the one-time password once', async () => {
    const { rendered, backend } = await renderSignedIn('coordinators', []);

    await userEvent.click(screen.getAllByRole('button', { name: 'Add coordinator' })[0]!);
    await userEvent.type(screen.getByLabelText('Full name'), 'Hoda');
    await userEvent.type(screen.getByLabelText('Email'), 'hoda@alnoor.test');
    await userEvent.selectOptions(screen.getByLabelText('Subject'), 'math');
    await settle(rendered);
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    const created = backend.expectOne('/admin/coordinators');
    expect(created.request.method).toBe('POST');
    expect(created.request.body).toMatchObject({
      fullName: 'Hoda',
      email: 'hoda@alnoor.test',
      scopes: [{ subject: 'math' }],
    });
    created.flush({ coordinator: HODA, temporaryPassword: 'swift-otter-42' });
    await settle(rendered);
    backend.expectOne('/admin/coordinators').flush([HODA]);
    await settle(rendered);

    expect(screen.getByText('swift-otter-42')).toBeInTheDocument();
    expect(screen.getByText(/shown once and cannot be read again/)).toBeInTheDocument();
  });

  it('edits the name, the number and whether she may sign in — never the email', async () => {
    const { rendered, backend } = await renderSignedIn('coordinators', [HODA]);

    await userEvent.click(screen.getByRole('button', { name: 'Actions for Hoda' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Edit' }));
    await settle(rendered);

    // The sign-in address is the account's identity; the edit route does not take it.
    expect(screen.queryByLabelText('Email')).not.toBeInTheDocument();
    // A field that is not required carries the word "Optional" in its label, so match the start.
    await userEvent.clear(screen.getByLabelText(/^Mobile/));
    await userEvent.type(screen.getByLabelText(/^Mobile/), '0509998877');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    const patch = backend.expectOne('/admin/coordinators/u-hoda');
    expect(patch.request.method).toBe('PATCH');
    expect(patch.request.body).toEqual({ fullName: 'Hoda', phone: '0509998877', active: true });
  });

  it('writes the scopes editor to PUT …/scopes, dropping a row she left empty', async () => {
    const { rendered, backend } = await renderSignedIn('coordinators', [HODA]);

    await userEvent.click(screen.getByRole('button', { name: 'Actions for Hoda' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Subjects' }));
    await settle(rendered);
    await userEvent.click(screen.getByRole('button', { name: 'Add a subject' }));
    await settle(rendered);
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    const put = backend.expectOne('/admin/coordinators/u-hoda/scopes');
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual({ scopes: [{ subject: 'math', curriculum: 'british' }] });
  });

  /**
   * `PUT …/scopes` refuses a subject named twice for one track with a 400 by design, and a red band
   * must not be how the Admin learns it: the editor says so and the save is refused.
   */
  it('refuses a subject named twice, under the editor rather than from the server', async () => {
    const { rendered, backend } = await renderSignedIn('coordinators', [HODA]);

    await userEvent.click(screen.getByRole('button', { name: 'Actions for Hoda' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Subjects' }));
    await settle(rendered);
    await userEvent.click(screen.getByRole('button', { name: 'Add a subject' }));
    await settle(rendered);
    // The second row repeats Math on the British track, which is the row she already has.
    const subjects = screen.getAllByLabelText('Subject');
    await userEvent.selectOptions(subjects[1]!, 'math');
    await userEvent.selectOptions(screen.getAllByLabelText(/^Track/)[1]!, 'british');
    await settle(rendered);

    expect(screen.getByRole('alert')).toHaveTextContent('Math · British is named twice.');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    backend.expectNone('/admin/coordinators/u-hoda/scopes');
  });

  /** Two "both tracks" rows of one subject are two rows to the unique index, so they are named. */
  it('names a duplicate that has no track as "(both tracks)"', async () => {
    const { rendered } = await renderSignedIn('coordinators', []);

    await userEvent.click(screen.getAllByRole('button', { name: 'Add coordinator' })[0]!);
    await settle(rendered);
    await userEvent.selectOptions(screen.getByLabelText('Subject'), 'math');
    await userEvent.click(screen.getByRole('button', { name: 'Add a subject' }));
    await settle(rendered);
    await userEvent.selectOptions(screen.getAllByLabelText('Subject')[1]!, 'math');
    await settle(rendered);

    expect(screen.getByRole('alert')).toHaveTextContent('Math (both tracks) is named twice.');
  });

  it('answers a reset with a password shown once', async () => {
    const { rendered, backend } = await renderSignedIn('coordinators', [HODA]);

    await userEvent.click(screen.getByRole('button', { name: 'Actions for Hoda' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Reset password' }));
    backend
      .expectOne('/admin/coordinators/u-hoda/reset-password')
      .flush({ temporaryPassword: 'brisk-ibis-7' });
    await settle(rendered);

    expect(screen.getByText('brisk-ibis-7')).toBeInTheDocument();
  });

  it('disables an account behind a red confirm band', async () => {
    const { rendered, backend } = await renderSignedIn('coordinators', [HODA]);

    await userEvent.click(screen.getByRole('button', { name: 'Actions for Hoda' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Disable account' }));
    await settle(rendered);
    expect(screen.getByText('Disable this account?')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Disable' }));
    const patch = backend.expectOne('/admin/coordinators/u-hoda');
    expect(patch.request.body).toEqual({ active: false });
    patch.flush({ ...HODA, status: 'disabled' });
    await settle(rendered);

    expect(screen.getByRole('cell', { name: 'Disabled' })).toBeInTheDocument();
  });

  it('offers an account without the key no way to add one, empty list or not', async () => {
    await renderSignedIn('coordinators', [], { role: 'ADMIN', permissions: [], readOnly: false });

    expect(screen.getByText('No coordinators yet. Add the first one.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Add coordinator' })).not.toBeInTheDocument();
  });

  it('offers Message on a coordinator, and names her as the coordinator of the thread', async () => {
    const open = fakeThreads();
    await renderSignedIn('coordinators', [HODA]);

    await userEvent.click(screen.getByRole('button', { name: 'Actions for Hoda' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Message' }));

    expect(open).toHaveBeenCalledWith('u-hoda', { coordinatorUserId: 'u-hoda' });
  });
});

describe('Managers', () => {
  beforeEach(() => localStorage.clear());

  it('is the same screen, reading /admin/managers and showing departments', async () => {
    await renderSignedIn('managers', [NADIA]);

    expect(screen.getByRole('heading', { name: 'Managers' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Departments' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'British' })).toBeInTheDocument();
  });

  it('creates one with a single department, which is what POST takes', async () => {
    const { rendered, backend } = await renderSignedIn('managers', []);

    await userEvent.click(screen.getAllByRole('button', { name: 'Add manager' })[0]!);
    await userEvent.type(screen.getByLabelText('Full name'), 'Nadia');
    await userEvent.type(screen.getByLabelText('Email'), 'nadia@alnoor.test');
    await userEvent.selectOptions(screen.getByLabelText('Department'), 'british');
    await settle(rendered);
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    const created = backend.expectOne('/admin/managers');
    expect(created.request.body).toMatchObject({ fullName: 'Nadia', curriculum: 'british' });
    created.flush({ manager: NADIA, temporaryPassword: 'plain-heron-9' });
    await settle(rendered);
    backend.expectOne('/admin/managers').flush([NADIA]);
    await settle(rendered);

    expect(screen.getByText('plain-heron-9')).toBeInTheDocument();
  });

  it('writes several departments to PUT …/scopes as curricula', async () => {
    const { rendered, backend } = await renderSignedIn('managers', [NADIA]);

    await userEvent.click(screen.getByRole('button', { name: 'Actions for Nadia' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Departments' }));
    await settle(rendered);
    await userEvent.click(screen.getByLabelText('American'));
    await settle(rendered);
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    const put = backend.expectOne('/admin/managers/u-nadia/scopes');
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual({ curricula: ['british', 'american'] });
  });

  it('offers Message on a manager, and names her as the manager of the thread', async () => {
    const open = fakeThreads();
    await renderSignedIn('managers', [NADIA]);

    await userEvent.click(screen.getByRole('button', { name: 'Actions for Nadia' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Message' }));

    expect(open).toHaveBeenCalledWith('u-nadia', { managerUserId: 'u-nadia' });
  });
});
