import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { BASE_PATH } from '../../api';
import { ADMIN_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { WorkersPage } from './workers.page';

const providers: (Provider | EnvironmentProviders)[] = [
  provideHttpClient(),
  provideHttpClientTesting(),
  provideRouter([{ path: '**', children: [] }]),
  { provide: BASE_PATH, useValue: '' },
];

const ADMIN_PERMISSIONS = { role: 'ADMIN', permissions: ['worker.read', 'worker.write'], readOnly: false };
/** MANAGERIAL holds the read and not the write (`permissions.json`). */
const READ_ONLY = { role: 'MANAGERIAL', permissions: ['worker.read'], readOnly: false };

const MONA = { id: 'w-mona', fullName: 'Mona', job: 'Nurse', phone: '0501234567', active: true };

async function settle(rendered: { fixture: { detectChanges: () => void } }): Promise<void> {
  await Promise.resolve();
  TestBed.tick();
  rendered.fixture.detectChanges();
}

async function renderSignedIn(rows: readonly unknown[] = [MONA], permissions = ADMIN_PERMISSIONS) {
  const rendered = await renderHq(WorkersPage, { providers });
  const backend = TestBed.inject(HttpTestingController);

  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(ADMIN_USER);
  TestBed.tick();
  backend.expectOne('/me/permissions').flush(permissions);

  backend.expectOne('/admin/workers').flush(rows);
  await settle(rendered);

  return { rendered, backend };
}

describe('Workers', () => {
  beforeEach(() => localStorage.clear());

  it('lists a worker with her job and a number that can be rung', async () => {
    await renderSignedIn();

    expect(screen.getByRole('cell', { name: 'Mona' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'Nurse' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '0501234567' })).toHaveAttribute('href', 'tel:0501234567');
  });

  /** No account comes into being, so there is nothing to show once and nothing to reset. */
  it('mints no password and offers no reset', async () => {
    await renderSignedIn();

    await userEvent.click(screen.getByRole('button', { name: 'Actions for Mona' }));
    expect(screen.queryByRole('menuitem', { name: 'Reset password' })).not.toBeInTheDocument();
  });

  it('creates one from a name, a job and a number', async () => {
    const { rendered, backend } = await renderSignedIn([]);

    await userEvent.click(screen.getAllByRole('button', { name: 'Add worker' })[0]!);
    await userEvent.type(screen.getByLabelText('Full name'), 'Mona');
    await userEvent.type(screen.getByLabelText('Job'), 'Nurse');
    await userEvent.type(screen.getByLabelText(/^Mobile/), '0501234567');
    await settle(rendered);
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    const created = backend.expectOne('/admin/workers');
    expect(created.request.method).toBe('POST');
    expect(created.request.body).toEqual({ fullName: 'Mona', job: 'Nurse', phone: '0501234567' });
    created.flush(MONA);
    await settle(rendered);
    backend.expectOne('/admin/workers').flush([MONA]);
    await settle(rendered);

    expect(screen.getByRole('cell', { name: 'Mona' })).toBeInTheDocument();
  });

  it('edits the job and the number', async () => {
    const { rendered, backend } = await renderSignedIn();

    await userEvent.click(screen.getByRole('button', { name: 'Actions for Mona' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Edit' }));
    await settle(rendered);
    await userEvent.clear(screen.getByLabelText('Job'));
    await userEvent.type(screen.getByLabelText('Job'), 'Head nurse');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    const patch = backend.expectOne('/admin/workers/w-mona');
    expect(patch.request.method).toBe('PATCH');
    expect(patch.request.body).toEqual({
      fullName: 'Mona',
      job: 'Head nurse',
      phone: '0501234567',
      active: true,
    });
  });

  it('retires behind a red confirm band, and DELETE is the soft one', async () => {
    const { rendered, backend } = await renderSignedIn();

    await userEvent.click(screen.getByRole('button', { name: 'Actions for Mona' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Retire' }));
    await settle(rendered);
    expect(screen.getByText('Retire this worker?')).toBeInTheDocument();
    expect(screen.getByText(/Nothing is deleted/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Retire' }));
    const gone = backend.expectOne('/admin/workers/w-mona');
    expect(gone.request.method).toBe('DELETE');
    gone.flush({});
    await settle(rendered);

    // The row stays and says so — a number somebody rang last March is part of the record.
    expect(screen.getByRole('cell', { name: 'Retired' })).toBeInTheDocument();
  });

  it('puts the row back and shows the red band when the retire fails', async () => {
    const { rendered, backend } = await renderSignedIn();

    await userEvent.click(screen.getByRole('button', { name: 'Actions for Mona' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Retire' }));
    await settle(rendered);
    await userEvent.click(screen.getByRole('button', { name: 'Retire' }));

    backend
      .expectOne('/admin/workers/w-mona')
      .flush({ code: 'conflict', message: 'Not yours' }, { status: 409, statusText: 'Conflict' });
    await settle(rendered);

    expect(screen.getByRole('cell', { name: 'Working' })).toBeInTheDocument();
  });

  it('shows a read-only account the list and none of its actions', async () => {
    await renderSignedIn([], READ_ONLY);

    expect(screen.getByText('No workers yet. Add the first one.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Add worker' })).not.toBeInTheDocument();
  });
});
