import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import { beforeEach, describe, expect, it } from 'vitest';
import { COORDINATOR_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { type ChatThread, BASE_PATH } from '../../api';
import { ChatService } from '../../core/chat/chat.service';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { FlagService } from '../../core/flags/flag.service';
import { CoordinatorComplaintsPage } from './coordinator-complaints.page';

const COMPLAINT = {
  id: 'th-7',
  childId: 'ch-1',
  childName: 'Layla Ahmed',
  className: '1A British',
  teacherId: 'u-rasha',
  teacherName: 'Rasha Kamal',
  unread: 1,
  staffRole: 'COORDINATOR',
  topic: 'complaint',
  status: 'open',
  lastMessage: {
    id: 'm-1',
    threadId: 'th-7',
    sender: 'parent',
    senderId: 'p-1',
    body: 'Nobody marked the homework',
    createdAt: 1700000000000,
  },
};

/**
 * R7 (DR3, DR4), narrowed by RM3b: the one screen in `/coordinator/**` where the read-only
 * coordinator writes something is Complaints. Her announcements became broadcasts and moved to
 * `features/broadcasts/announcements.page.spec.ts` with the composer they belong to.
 */
describe('the coordinator comms screens', () => {
  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
  });

  async function signedIn<T>(page: Type<T>) {
    const rendered = await renderHq(page, {
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: '**', children: [] }]),
        { provide: BASE_PATH, useValue: '' },
        // Both screens are flagged; the flag map is the Admin's, and here it is simply on.
        { provide: FlagService, useValue: { isOn: () => true, refresh: () => undefined } },
      ],
    });
    const backend = TestBed.inject(HttpTestingController);
    TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
    TestBed.inject(AuthService).loadMe().subscribe();
    backend.expectOne('/me').flush(COORDINATOR_USER);
    await settle();
    return { rendered, backend };
  }

  async function settle() {
    await Promise.resolve();
    TestBed.tick();
    await Promise.resolve();
    TestBed.tick();
  }

  it('asks before it resolves a complaint, and reloads the list it filters', async () => {
    const { backend } = await signedIn(CoordinatorComplaintsPage);
    backend.expectOne('/coordinator/complaints?status=open').flush({ complaints: [COMPLAINT], open: 1, resolved: 0 });
    await settle();

    expect(screen.getByText('Layla Ahmed')).toBeTruthy();
    // No parent name on the contract's `ChatThread`, so the From column says whose parent it is.
    expect(document.body.textContent).toContain('Parent of Layla Ahmed');

    screen.getByRole('button', { name: 'Mark resolved' }).click();
    await settle();
    // The band first: resolving is visible to the parent, so it is worth one question.
    expect(screen.getByText('Mark this complaint resolved?')).toBeTruthy();
    expect(backend.match('/coordinator/complaints/th-7/status')).toEqual([]);

    screen.getByRole('button', { name: 'Yes, resolve it' }).click();
    await settle();

    const patch = backend.expectOne('/coordinator/complaints/th-7/status');
    expect(patch.request.method).toBe('PATCH');
    expect(patch.request.body).toEqual({ status: 'resolved' });
    patch.flush({ ...COMPLAINT, status: 'resolved' });
    await settle();

    // The list *is* a status filter, so the row has to leave the open tab: refetch, not repaint.
    backend.expectOne('/coordinator/complaints?status=open').flush({ complaints: [], open: 0, resolved: 1 });
    await settle();
    expect(screen.getByText('No open complaints.')).toBeTruthy();
  });

  /**
   * R4 sends the `status` frame to both parties, so a complaint resolved somewhere else — her
   * other tab, or the conversation itself — has to move here too, and this list is a filter *on*
   * status, so "move" means leave the tab.
   */
  it('lets go of a row the status frame resolved, with no second request', async () => {
    const { backend } = await signedIn(CoordinatorComplaintsPage);
    backend.expectOne('/coordinator/complaints?status=open').flush({ complaints: [COMPLAINT], open: 1, resolved: 0 });
    await settle();
    expect(screen.getByText('Layla Ahmed')).toBeTruthy();

    TestBed.inject(ChatService).threads.set([COMPLAINT as unknown as ChatThread]);
    TestBed.inject(ChatService).receive({
      type: 'status',
      threadId: 'th-7',
      status: 'resolved',
      at: 1700000009000,
    });
    await settle();

    expect(screen.getByText('No open complaints.')).toBeTruthy();
    backend.expectNone('/coordinator/complaints?status=open');
  });
});
