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
import { UndoService } from '../../core/undo/undo.service';
import { ClassesPage } from './classes.page';

const providers: (Provider | EnvironmentProviders)[] = [
  provideHttpClient(),
  provideHttpClientTesting(),
  provideRouter([]),
  { provide: BASE_PATH, useValue: '' },
];

const ADMIN_PERMISSIONS = {
  role: 'ADMIN',
  permissions: ['section.read', 'class.write'],
  readOnly: false,
};

const ONE_A = {
  id: 'c-1a',
  schoolId: 'school-a',
  curriculum: 'british',
  grade: 1,
  name: '1A',
  joinCode: 'BRIT-1A-77',
  active: true,
  joinCodeEnabled: true,
  children: 24,
  assignments: 2,
};

async function settle(rendered: { fixture: { detectChanges: () => void } }): Promise<void> {
  await Promise.resolve();
  TestBed.tick();
  rendered.fixture.detectChanges();
}

async function renderSignedIn(sections: readonly unknown[] = [ONE_A]) {
  const rendered = await renderHq(ClassesPage, { providers });
  const backend = TestBed.inject(HttpTestingController);

  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(ADMIN_USER);
  TestBed.tick();
  backend.expectOne('/me/permissions').flush(ADMIN_PERMISSIONS);

  backend.expectOne('/admin/classes').flush(sections);
  await settle(rendered);

  return { rendered, backend };
}

describe('Classes', () => {
  beforeEach(() => localStorage.clear());

  it('shows a section with its course, join code and counts', async () => {
    await renderSignedIn();

    expect(screen.getByRole('cell', { name: '1A' })).toBeInTheDocument();
    expect(screen.getByText('British · Grade 1')).toBeInTheDocument();
    expect(screen.getByText('BRIT-1A-77')).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: '24' })).toBeInTheDocument();
  });

  it('creates a section from the curriculum, grade and name the dialog asks for', async () => {
    const { rendered, backend } = await renderSignedIn([]);

    await userEvent.click(screen.getAllByRole('button', { name: 'Create class' })[0]!);
    // Scoped to the dialog: the toolbar behind it has a curriculum chooser of its own.
    const dialog = within(screen.getByRole('dialog'));
    await userEvent.selectOptions(dialog.getByLabelText('Curriculum'), 'british');
    await userEvent.selectOptions(dialog.getByLabelText('Grade'), '1');
    await userEvent.type(dialog.getByLabelText('Class name'), '1A');
    await settle(rendered);
    await userEvent.click(screen.getByRole('button', { name: 'Create' }));

    const request = backend.expectOne('/admin/classes');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ curriculum: 'british', grade: 1, name: '1A' });
  });

  /**
   * Regenerating throws away every printed card, so it asks first — and the new code replaces
   * the old one in place rather than waiting for a refresh nobody would think to do.
   */
  it('confirms before regenerating a join code, then shows the new one', async () => {
    const { rendered, backend } = await renderSignedIn();

    await userEvent.click(screen.getByRole('button', { name: 'Actions for 1A' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Regenerate join code' }));
    await settle(rendered);

    expect(screen.getByText('Regenerate this join code?')).toBeInTheDocument();
    // Nothing has been asked of the server while the question is on screen.
    backend.expectNone('/admin/classes/c-1a/join-code');

    await userEvent.click(screen.getByRole('button', { name: 'Regenerate' }));
    backend
      .expectOne('/admin/classes/c-1a/join-code')
      .flush({ ...ONE_A, joinCode: 'BRIT-1A-88' });
    await settle(rendered);

    expect(screen.getByText('BRIT-1A-88')).toBeInTheDocument();
  });

  it('deactivates behind the red band and offers ten seconds of Undo', async () => {
    const { rendered, backend } = await renderSignedIn();

    await userEvent.click(screen.getByRole('button', { name: 'Actions for 1A' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Deactivate' }));
    await settle(rendered);
    await userEvent.click(screen.getByRole('button', { name: 'Deactivate' }));

    const request = backend.expectOne('/admin/classes/c-1a');
    expect(request.request.method).toBe('PATCH');
    expect(request.request.body).toEqual({ active: false });
    request.flush({ ...ONE_A, active: false });
    await settle(rendered);

    expect(screen.getByRole('cell', { name: 'Inactive' })).toBeInTheDocument();
    expect(TestBed.inject(UndoService).offer()?.message).toBe('1A deactivated');
  });

  it('puts the row back when the change is refused', async () => {
    const { rendered, backend } = await renderSignedIn();

    await userEvent.click(screen.getByRole('button', { name: 'Actions for 1A' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Deactivate' }));
    await settle(rendered);
    await userEvent.click(screen.getByRole('button', { name: 'Deactivate' }));

    backend
      .expectOne('/admin/classes/c-1a')
      .flush({ code: 'conflict', message: 'Not while a lesson is running' }, { status: 409, statusText: 'Conflict' });
    await settle(rendered);

    expect(screen.getByRole('cell', { name: 'Active' })).toBeInTheDocument();
  });
});
