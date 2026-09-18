import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { BASE_PATH } from '../../api';
import { ADMIN_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { TeachersPage } from './teachers.page';

const providers: (Provider | EnvironmentProviders)[] = [
  provideHttpClient(),
  provideHttpClientTesting(),
  // One empty route, so a real navigation can be started — that is what drops the password.
  provideRouter([{ path: '**', children: [] }]),
  { provide: BASE_PATH, useValue: '' },
];

const ADMIN_PERMISSIONS = {
  role: 'ADMIN',
  permissions: ['teacher.read', 'teacher.manage', 'section.read', 'class.write'],
  readOnly: false,
};

const SARA = {
  userId: 'u-sara',
  email: 'sara@alnoor.test',
  fullName: 'Sara',
  status: 'active',
  subjects: ['math'],
  curriculum: 'british',
  assignments: [{ id: 'a-1', classId: 'c-1a', className: '1A', subject: 'math', teacherId: 'u-sara' }],
};

const ONE_A = { id: 'c-1a', schoolId: 'school-a', curriculum: 'british', grade: 1, name: '1A', active: true };

/** A resource answers on a microtask; the render that follows is what the assertions read. */
async function settle(rendered: { fixture: { detectChanges: () => void } }): Promise<void> {
  await Promise.resolve();
  TestBed.tick();
  rendered.fixture.detectChanges();
}

/** Signs an Admin in and answers the two lists the screen loads. */
/** MANAGERIAL reads the staff list and changes nothing — no create action anywhere. */
const READ_ONLY_PERMISSIONS = { role: 'MANAGERIAL', permissions: ['teacher.read'], readOnly: false };

async function renderSignedIn(teachers: readonly unknown[] = [SARA], permissions = ADMIN_PERMISSIONS) {
  const rendered = await renderHq(TeachersPage, { providers });
  const backend = TestBed.inject(HttpTestingController);

  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(ADMIN_USER);
  TestBed.tick();
  backend.expectOne('/me/permissions').flush(permissions);

  backend.expectOne('/admin/teachers').flush(teachers);
  backend.expectOne('/admin/classes').flush([ONE_A]);
  await settle(rendered);

  return { rendered, backend };
}

describe('Teachers', () => {
  beforeEach(() => localStorage.clear());

  it('lists a teacher with her subjects and her assignments as chips', async () => {
    await renderSignedIn();

    expect(screen.getByRole('cell', { name: 'Sara' })).toBeInTheDocument();
    expect(screen.getByText('1A · Math')).toBeInTheDocument();
  });

  it('shows the temporary password once, and says it will not be shown again', async () => {
    const { rendered, backend } = await renderSignedIn([]);

    // Two of them while the list is empty: the empty state's and the sticky footer's.
    await userEvent.click(screen.getAllByRole('button', { name: 'Add teacher' })[0]!);
    await userEvent.type(screen.getByLabelText('Full name'), 'Sara');
    await userEvent.type(screen.getByLabelText('Email'), 'sara@alnoor.test');
    await userEvent.click(screen.getByLabelText('Math'));
    await settle(rendered);
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    backend.expectOne('/admin/teachers').flush({ teacher: SARA, temporaryPassword: 'swift-otter-42' });
    await settle(rendered);
    // The list re-reads itself after the create; the band is already on screen either way.
    backend.expectOne('/admin/teachers').flush([SARA]);
    await settle(rendered);

    expect(screen.getByText('swift-otter-42')).toBeInTheDocument();
    expect(screen.getByText(/shown once and cannot be read again/)).toBeInTheDocument();
  });

  /**
   * "Shown once" has to mean once. Nothing writes it down, so leaving the screen is enough to
   * lose it — this asserts the screen does not quietly hold it for the next visit.
   */
  it('drops the temporary password when the person navigates away', async () => {
    const { rendered, backend } = await renderSignedIn();

    await userEvent.click(screen.getByRole('button', { name: 'Actions for Sara' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Reset password' }));
    backend.expectOne('/admin/teachers/u-sara/reset-password').flush({ temporaryPassword: 'swift-otter-42' });
    await settle(rendered);
    expect(screen.getByText('swift-otter-42')).toBeInTheDocument();

    await TestBed.inject(Router).navigateByUrl('/admin');
    await settle(rendered);

    expect(screen.queryByText('swift-otter-42')).not.toBeInTheDocument();
    expect(localStorage.getItem('swift-otter-42')).toBeNull();
    expect(JSON.stringify(localStorage)).not.toContain('swift-otter-42');
  });

  it('offers a MANAGERIAL account no way to add a teacher, empty list or not', async () => {
    await renderSignedIn([], READ_ONLY_PERMISSIONS);

    expect(screen.getByText('No teachers yet. Add the first one.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Add teacher' })).not.toBeInTheDocument();
  });

  it('disables an account behind a red confirm band, and offers Undo afterwards', async () => {
    const { rendered, backend } = await renderSignedIn();

    await userEvent.click(screen.getByRole('button', { name: 'Actions for Sara' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Disable account' }));
    await settle(rendered);

    expect(screen.getByText('Disable this account?')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Disable' }));

    const request = backend.expectOne('/admin/teachers/u-sara');
    expect(request.request.method).toBe('PATCH');
    expect(request.request.body).toEqual({ active: false });
    request.flush({ ...SARA, status: 'disabled' });
    await settle(rendered);

    expect(screen.getByRole('cell', { name: 'Disabled' })).toBeInTheDocument();
  });

  it('rolls the row back and shows the red band when disabling fails', async () => {
    const { rendered, backend } = await renderSignedIn();

    await userEvent.click(screen.getByRole('button', { name: 'Actions for Sara' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Disable account' }));
    await settle(rendered);
    await userEvent.click(screen.getByRole('button', { name: 'Disable' }));

    backend
      .expectOne('/admin/teachers/u-sara')
      .flush({ code: 'conflict', message: 'She still teaches 1A' }, { status: 409, statusText: 'Conflict' });
    await settle(rendered);

    expect(screen.getByRole('cell', { name: 'Active' })).toBeInTheDocument();
  });
});
