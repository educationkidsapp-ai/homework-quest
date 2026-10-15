import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { BASE_PATH } from '../../api';
import { FLOOR_SUPERVISOR_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { FlagService } from '../../core/flags/flag.service';
import { FloorChildrenPage } from './floor-children.page';

const providers: (Provider | EnvironmentProviders)[] = [
  provideHttpClient(),
  provideHttpClientTesting(),
  provideRouter([{ path: '**', children: [] }]),
  { provide: BASE_PATH, useValue: '' },
  { provide: FlagService, useValue: { isOn: () => true, refresh: () => undefined, ready: () => true } },
];

const PERMISSIONS = {
  role: 'FLOOR_SUPERVISOR',
  permissions: ['floor.read', 'floor.chat'],
  readOnly: false,
};

const LAYLA = {
  id: 'ch-1',
  name: 'Layla Nasser',
  classId: 'c-1a',
  className: '1A',
  floorId: 'f-2',
  floorName: 'Floor 2',
  avatarColor: 'mint',
};

async function settle(rendered: { fixture: { detectChanges: () => void } }): Promise<void> {
  await Promise.resolve();
  TestBed.tick();
  rendered.fixture.detectChanges();
}

async function renderChildren(children: readonly unknown[] = [LAYLA]) {
  const rendered = await renderHq(FloorChildrenPage, { providers });
  const backend = TestBed.inject(HttpTestingController);

  // No `q=` on the first read: the whole floor, until she types.
  backend.expectOne('/floor/children').flush(children);

  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(FLOOR_SUPERVISOR_USER);
  TestBed.tick();
  await settle(rendered);

  // `*hqCan` is on a cell template, so `PermissionService` is not injected until the first row
  // renders — which is after the list has landed, not with it.
  for (const request of backend.match('/me/permissions')) request.flush(PERMISSIONS);
  await settle(rendered);
  return { rendered, backend };
}

describe('the floor supervisor’s Children', () => {
  beforeEach(() => localStorage.clear());

  it('lists a child with her class and links her name at the read-only report', async () => {
    await renderChildren();

    expect(screen.getByRole('cell', { name: '1A' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Layla Nasser' })).toHaveAttribute(
      'href',
      '/floor/children/ch-1',
    );
  });

  /**
   * The acceptance criterion: "She can message parents of her floor." `POST /floor/chat/threads`
   * is idempotent, so the button is a navigation — onto the conversation itself, with whatever was
   * said before already on screen.
   */
  it('opens the parent thread and lands on it in her Messages', async () => {
    const { rendered, backend } = await renderChildren();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate');

    await userEvent.click(screen.getByRole('button', { name: 'Message parent' }));
    // Her threads list is read on the same URL by `ChatService`, so the POST is named by method.
    const request = backend.expectOne(
      (candidate) => candidate.url === '/floor/chat/threads' && candidate.method === 'POST',
    );
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ childId: 'ch-1' });
    request.flush({ id: 'th-9', childId: 'ch-1', childName: 'Layla Nasser', unread: 0 });
    await settle(rendered);

    expect(navigate).toHaveBeenCalledWith(['/floor/messages'], { queryParams: { thread: 'th-9' } });
  });

  /** A child whose parent has never signed up: a sentence about that row, not a bare red band. */
  it('says so when the child has no registered parent', async () => {
    const { rendered, backend } = await renderChildren();

    await userEvent.click(screen.getByRole('button', { name: 'Message parent' }));
    backend
      .expectOne((candidate) => candidate.url === '/floor/chat/threads' && candidate.method === 'POST')
      .flush({ code: 'no_parent', message: 'no parent' }, { status: 404, statusText: 'Not Found' });
    await settle(rendered);

    expect(
      screen.getByText('This child has no registered parent yet, so there is nobody to write to.'),
    ).toBeInTheDocument();
  });

  /** The search is the server's: one request for the name, 250 ms behind the keyboard. */
  it('asks the server for the name she typed', async () => {
    const { rendered, backend } = await renderChildren();

    // The field is marked optional, so the label reads "Search by name optional".
    await userEvent.type(screen.getByLabelText(/Search by name/), 'Layla');
    await new Promise((resolve) => setTimeout(resolve, 300));
    await settle(rendered);

    const request = backend.expectOne((candidate) => candidate.url === '/floor/children');
    expect(request.request.params.get('q')).toBe('Layla');
    request.flush([LAYLA]);
    await settle(rendered);
    expect(screen.getByRole('link', { name: 'Layla Nasser' })).toBeInTheDocument();
  });

  it('says a search with no match found nobody, rather than drawing an empty table', async () => {
    await renderChildren([]);

    expect(screen.getByText('No children found.')).toBeInTheDocument();
  });
});
