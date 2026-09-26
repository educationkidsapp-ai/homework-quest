import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import { beforeEach, describe, expect, it } from 'vitest';
import { COORDINATOR_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { BASE_PATH } from '../../api';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { FlagService } from '../../core/flags/flag.service';
import { CoordinatorAnnouncementsPage } from './coordinator-announcements.page';
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

const ME = {
  userId: 'u-rasha',
  displayName: 'Rasha Kamal',
  scopes: [{ subject: 'math', curriculum: null }],
  sections: 2,
  teachers: 1,
  children: 40,
};
const CLASSES = [
  {
    classId: 'c-1',
    className: '1A British',
    grade: 1,
    curriculum: 'british',
    subject: 'math',
    teacherId: 't-1',
    teacherName: 'Sara Al Harbi',
    childrenCount: 24,
    todayLessonId: 'l-1',
    todayStatus: 'published',
  },
];

/**
 * R7 (DR3, DR4). The two screens where the read-only coordinator finally writes something: the
 * status of a complaint, and an announcement to the parents of her classes.
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
    backend.expectOne('/coordinator/complaints?status=open').flush([COMPLAINT]);
    await settle();

    expect(screen.getByText('Layla Ahmed')).toBeTruthy();
    // No parent name on the contract's `ChatThread`, so the From column says whose parent it is.
    expect(document.body.textContent).toContain('Parent of Layla Ahmed');

    screen.getByRole('button', { name: 'Mark resolved' }).click();
    await settle();
    // The band first: resolving is visible to the parent, so it is worth one question.
    expect(screen.getByText('Mark this complaint resolved?')).toBeTruthy();
    expect(backend.match('/coordinator/chat/threads/th-7/status')).toEqual([]);

    screen.getByRole('button', { name: 'Yes, resolve it' }).click();
    await settle();

    const patch = backend.expectOne('/coordinator/chat/threads/th-7/status');
    expect(patch.request.method).toBe('PATCH');
    expect(patch.request.body).toEqual({ status: 'resolved' });
    patch.flush({ ...COMPLAINT, status: 'resolved' });
    await settle();

    // The list *is* a status filter, so the row has to leave the open tab: refetch, not repaint.
    backend.expectOne('/coordinator/complaints?status=open').flush([]);
    await settle();
    expect(screen.getByText('No open complaints.')).toBeTruthy();
  });

  it('will not post an announcement without an English body, and sends classIds when she picks classes', async () => {
    const { backend } = await signedIn(CoordinatorAnnouncementsPage);
    backend.expectOne('/coordinator/announcements').flush([]);
    backend.expectOne('/coordinator/me').flush(ME);
    backend.expectOne('/coordinator/teachers').flush([]);
    backend.expectOne('/coordinator/classes').flush(CLASSES);
    backend.match((r) => r.url.startsWith('/coordinator/lessons')).forEach((r) => r.flush([]));
    backend.match('/coordinator/complaints?status=open').forEach((r) => r.flush([]));
    await settle();

    screen.getByRole('button', { name: 'Write an announcement' }).click();
    await settle();

    const post = screen.getByRole('button', { name: 'Post' });
    expect(post.hasAttribute('disabled')).toBe(true);

    const bodyEn = document.querySelector('hq-textarea textarea') as HTMLTextAreaElement;
    bodyEn.value = 'Reading week starts on Sunday.';
    bodyEn.dispatchEvent(new Event('input'));
    await settle();
    expect(screen.getByRole('button', { name: 'Post' }).hasAttribute('disabled')).toBe(false);

    (document.querySelector('hq-checkbox input') as HTMLInputElement).click();
    await settle();
    screen.getByRole('button', { name: 'Post' }).click();
    await settle();

    const request = backend.expectOne('/coordinator/announcements');
    expect(request.request.method).toBe('POST');
    // `classIds`, plural: the coordinator's own request shape (one row per class server-side).
    expect(request.request.body).toEqual({
      bodyEn: 'Reading week starts on Sunday.',
      classIds: ['c-1'],
    });
  });
});
