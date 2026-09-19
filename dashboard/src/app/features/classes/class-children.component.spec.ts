import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { BASE_PATH } from '../../api';
import { TEACHER_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { FlagService } from '../../core/flags/flag.service';
import { ClassChildrenComponent } from './class-children.component';

const providers: (Provider | EnvironmentProviders)[] = [
  provideHttpClient(),
  provideHttpClientTesting(),
  provideRouter([]),
  { provide: BASE_PATH, useValue: '' },
];

const TEACHER_PERMISSIONS = {
  role: 'TEACHER',
  permissions: ['teacher.week', 'student.read', 'roster.teacher'],
  readOnly: false,
};

const STUDENT = {
  childId: 'ch-1',
  classId: 'c-1a',
  name: 'Amina',
  starsThisWeek: 9,
  levelReached: 2,
  lastPlayed: 1_772_000_000_000,
  weakSkills: [{ skillId: 's-1', name: 'Place value' }],
};

async function settle(): Promise<void> {
  await Promise.resolve();
  TestBed.tick();
  await Promise.resolve();
  TestBed.tick();
}

/**
 * Renders the Children tab for a signed-in teacher with `teacher.rosterEdit` set either way.
 *
 * The flag map and the permission set are both answered here because the tab reads both before
 * it decides whether to ask for the roster at all — which is the behaviour under test.
 */
async function renderTab(rosterEdit: boolean, permissions = TEACHER_PERMISSIONS) {
  const rendered = await renderHq(ClassChildrenComponent, {
    providers,
    inputs: { classId: 'c-1a', className: '1A British' },
  });
  const backend = TestBed.inject(HttpTestingController);

  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(TEACHER_USER);
  TestBed.tick();
  backend.expectOne('/me/permissions').flush(permissions);
  TestBed.inject(FlagService).flags();
  await settle();
  backend.expectOne('/schools/school-a/flags').flush({ 'teacher.rosterEdit': rosterEdit });
  await settle();

  backend.expectOne('/teacher/classes/c-1a/students').flush([STUDENT]);
  await settle();

  return { rendered, backend };
}

describe('the class page Children tab', () => {
  beforeEach(() => localStorage.clear());

  it('lists the class from the progress endpoint alone while the flag is off', async () => {
    const { backend } = await renderTab(false);

    expect(screen.getByText('Amina')).toBeTruthy();
    expect(screen.getByText('Place value')).toBeTruthy();
    // The roster endpoint is never called: a school without roster editing must not be asked
    // for a list its teachers may not change.
    backend.expectNone('/teacher/classes/c-1a/children');
    expect(screen.queryByRole('button', { name: /add a child/i })).toBeNull();
    expect(screen.queryByRole('button', { name: /edit/i })).toBeNull();
    backend.verify();
  });

  it('fetches the roster and offers add, edit and deactivate once the flag is on', async () => {
    const { backend } = await renderTab(true);

    backend
      .expectOne('/teacher/classes/c-1a/children')
      .flush([{ id: 'ch-1', classId: 'c-1a', name: 'Amina', parentEmail: 'p@x.test', active: true }]);
    await settle();

    expect(screen.getByRole('button', { name: /add a child/i })).toBeTruthy();
    expect(screen.getByText('p@x.test')).toBeTruthy();
    expect(screen.getByRole('button', { name: /deactivate/i })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Place an existing child' })).toBeTruthy();
    backend.verify();
  });

  /**
   * Off the section, still in the school — and the red band asks first, because a roster a
   * colleague reads tomorrow is not a thing to change by brushing past a button.
   */
  it('asks in a red band before it takes a child off the section, then detaches her', async () => {
    const { backend } = await renderTab(true);

    backend
      .expectOne('/teacher/classes/c-1a/children')
      .flush([{ id: 'ch-1', classId: 'c-1a', name: 'Amina', active: true }]);
    await settle();

    await userEvent.click(screen.getByRole('button', { name: 'Remove Amina' }));
    await settle();
    expect(
      screen.getByText(
        'Amina comes off 1A British and stays in the school. You can place her again at any time.',
      ),
    ).toBeTruthy();
    // Nothing has been sent yet: the question is the whole point.
    backend.expectNone('/teacher/classes/c-1a/roster/ch-1');

    await userEvent.click(screen.getByRole('button', { name: 'Remove from class' }));
    const request = backend.expectOne('/teacher/classes/c-1a/roster/ch-1');
    expect(request.request.method).toBe('DELETE');
    request.flush({ id: 'ch-1', name: 'Amina' });
    await settle();

    backend.expectOne('/teacher/classes/c-1a/students').flush([]);
    backend.expectOne('/teacher/classes/c-1a/children').flush([]);
    await settle();
    expect(screen.getByText('Amina was removed from 1A British.')).toBeTruthy();
    backend.verify();
  });

  it('leaves the roster alone when the red band is dismissed', async () => {
    const { backend } = await renderTab(true);

    backend
      .expectOne('/teacher/classes/c-1a/children')
      .flush([{ id: 'ch-1', classId: 'c-1a', name: 'Amina', active: true }]);
    await settle();

    await userEvent.click(screen.getByRole('button', { name: 'Remove Amina' }));
    await settle();
    await userEvent.click(screen.getByRole('button', { name: /dismiss|cancel|close/i }));
    await settle();

    backend.expectNone('/teacher/classes/c-1a/roster/ch-1');
    backend.verify();
  });

  /**
   * The flag is the school's answer, the permission is the account's, and both have to say yes.
   * A MANAGERIAL account in a school that has roster editing still may not touch a roster.
   */
  it('keeps the roster shut when the school has the flag and the account lacks the key', async () => {
    const { backend } = await renderTab(true, {
      role: 'TEACHER',
      permissions: ['teacher.week', 'student.read'],
      readOnly: false,
    });

    backend.expectNone('/teacher/classes/c-1a/children');
    expect(screen.queryByRole('button', { name: /add a child/i })).toBeNull();
    backend.verify();
  });
});
