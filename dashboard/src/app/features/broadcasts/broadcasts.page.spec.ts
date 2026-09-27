import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import { beforeEach, describe, expect, it } from 'vitest';
import { COORDINATOR_USER, MANAGERIAL_USER, TEACHER_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { BASE_PATH } from '../../api';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { FlagService } from '../../core/flags/flag.service';
import { BroadcastsPage } from './broadcasts.page';

const PLAN = {
  id: 'b-plan',
  kind: 'weekly_plan',
  title: 'Week of subtraction',
  bodyEn: 'Subtraction all week; swimming on Thursday.',
  weekStart: '2026-09-27',
  audience: ['parents', 'teachers'],
  authorName: 'Huda Salem',
  authorRole: 'MANAGERIAL',
  curriculum: 'british',
  createdAt: 1758931200000,
  read: false,
};

const EVENT = {
  id: 'b-event',
  kind: 'event',
  title: 'Sports day',
  bodyEn: 'Thursday, on the big field.',
  audience: ['parents'],
  authorName: 'Lina Nasser',
  authorRole: 'COORDINATOR',
  subject: 'math',
  createdAt: 1758844800000,
  read: false,
};

const ME = {
  userId: 'u-huda',
  displayName: 'Huda Salem',
  departments: ['british'],
  sections: 15,
  teachers: 20,
  coordinators: 6,
  children: 300,
  grades: 6,
};

const CLASSES = [
  {
    grade: 1,
    classes: [
      {
        classId: 'c-1',
        className: '1A British',
        grade: 1,
        curriculum: 'british',
        subject: 'math',
        teacherId: 't-1',
        teacherName: 'Sara Al Harbi',
        childrenCount: 24,
        todayLessonId: null,
        todayStatus: 'none',
      },
    ],
  },
];

/**
 * RM3b: one feed, three roles. These tests are about the two things only the screen can get
 * wrong — which routes a role reads, and when a row stops being unread.
 */
describe('the broadcasts screen', () => {
  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
  });

  async function settle() {
    await Promise.resolve();
    TestBed.tick();
    await Promise.resolve();
    TestBed.tick();
  }

  async function signedInAs(user: typeof MANAGERIAL_USER) {
    await renderHq(BroadcastsPage, {
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: '**', children: [] }]),
        { provide: BASE_PATH, useValue: '' },
        { provide: FlagService, useValue: { isOn: () => true, refresh: () => undefined } },
      ],
    });
    const backend = TestBed.inject(HttpTestingController);
    TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
    TestBed.inject(AuthService).loadMe().subscribe();
    backend.expectOne('/me').flush(user);
    await settle();
    // `*hqCan` on the compose button reads `GET /me/permissions`, so the list has to land or the
    // footer is empty for the two roles that do hold a `*.broadcast` key.
    backend
      .match('/me/permissions')
      .forEach((request) =>
        request.flush({ permissions: ['broadcast.read', 'coordinator.broadcast', 'management.broadcast'] }),
      );
    await settle();
    return backend;
  }

  it('marks a row read when she opens it, and only then', async () => {
    const backend = await signedInAs(TEACHER_USER);
    backend.expectOne('/me/broadcasts').flush({ items: [EVENT], unread: 1 });
    await settle();

    // Arriving on the screen reads nothing: a feed that goes read on a glance is a bell that
    // stops ringing for things nobody looked at.
    expect(backend.match('/me/broadcasts/b-event/read')).toEqual([]);
    expect(screen.getByText('Sports day')).toBeTruthy();
    expect(document.body.textContent).not.toContain('Thursday, on the big field.');

    screen.getByRole('button', { name: /Sports day/ }).click();
    await settle();

    expect(document.body.textContent).toContain('Thursday, on the big field.');
    const read = backend.expectOne('/me/broadcasts/b-event/read');
    expect(read.request.method).toBe('POST');
    read.flush({ ...EVENT, read: true });
    await settle();
    // The feed is the unread badge, so a read mark refetches it rather than repainting one row.
    backend.expectOne('/me/broadcasts').flush({ items: [{ ...EVENT, read: true }], unread: 0 });
    await settle();
  });

  it("pins the week's plan, opens it, marks it read, and still lets her collapse it", async () => {
    const backend = await signedInAs(TEACHER_USER);
    // Newest first off the wire puts the event on top; the plan still has to be the first row.
    backend.expectOne('/me/broadcasts').flush({ items: [EVENT, PLAN], unread: 2 });
    await settle();

    const titles = [...document.querySelectorAll('.bc__title')].map((node) => node.textContent?.trim());
    expect(titles[0]).toBe('Week of subtraction');
    // Drawn open, because opening the screen *is* opening the one row she came for — and read for
    // exactly the same reason, so the badge does not go on counting what she is looking at.
    expect(document.body.textContent).toContain('Subtraction all week');
    expect(document.body.textContent).toContain('Huda Salem');
    expect(document.body.textContent).toContain('Management');
    backend.expectOne('/me/broadcasts/b-plan/read').flush({ ...PLAN, read: true });
    await settle();
    backend.expectOne('/me/broadcasts').flush({ items: [EVENT, { ...PLAN, read: true }], unread: 1 });
    await settle();

    // Open by default is not pinned open: the header still collapses it, once, and asks nothing.
    screen.getByRole('button', { name: /Week of subtraction/ }).click();
    await settle();
    expect(document.body.textContent).not.toContain('Subtraction all week');
    expect(backend.match('/me/broadcasts/b-plan/read')).toEqual([]);
  });

  it('gives a teacher no composer at all', async () => {
    const backend = await signedInAs(TEACHER_USER);
    backend.expectOne('/me/broadcasts').flush({ items: [], unread: 0 });
    await settle();

    expect(screen.queryByRole('button', { name: 'Write a broadcast' })).toBeNull();
    // And she asks for nobody's posted list: `GET /management|coordinator/broadcasts` are 403.
    expect(backend.match('/management/broadcasts')).toEqual([]);
    expect(backend.match('/coordinator/broadcasts')).toEqual([]);
  });

  it("reads the manager's own posts from /management/broadcasts and posts there", async () => {
    const backend = await signedInAs(MANAGERIAL_USER);
    backend.expectOne('/me/broadcasts').flush({ items: [], unread: 0 });
    backend.expectOne('/management/broadcasts').flush([PLAN]);
    backend.expectOne('/management/me').flush(ME);
    backend.expectOne('/management/teachers').flush([]);
    backend.expectOne('/management/classes').flush(CLASSES);
    backend.match((r) => r.url.startsWith('/management/lessons')).forEach((r) => r.flush([]));
    await settle();

    screen.getByRole('button', { name: 'Write a broadcast' }).click();
    await settle();

    const post = screen.getByRole('button', { name: 'Post' });
    expect(post.hasAttribute('disabled')).toBe(true);

    const title = document.querySelector('hq-input input') as HTMLInputElement;
    title.value = 'Sports day';
    title.dispatchEvent(new Event('input'));
    const body = document.querySelector('hq-textarea textarea') as HTMLTextAreaElement;
    body.value = 'Thursday, on the big field.';
    body.dispatchEvent(new Event('input'));
    await settle();

    screen.getByRole('button', { name: 'Post' }).click();
    await settle();

    const request = backend.expectOne('/management/broadcasts');
    expect(request.request.method).toBe('POST');
    // One department, so no `sectionIds`: the server reads that as the whole of it. `parents` is
    // the default audience, and a manager's request always carries one.
    expect(request.request.body).toEqual({
      kind: 'announcement',
      title: 'Sports day',
      bodyEn: 'Thursday, on the big field.',
      audience: ['parents'],
    });
  });

  it("posts a coordinator's announcement to /coordinator/broadcasts, with no audience", async () => {
    const backend = await signedInAs(COORDINATOR_USER);
    backend.expectOne('/me/broadcasts').flush({ items: [], unread: 0 });
    backend.expectOne('/coordinator/broadcasts').flush([]);
    backend.expectOne('/coordinator/me').flush({ ...ME, scopes: [{ subject: 'math', curriculum: null }] });
    backend.expectOne('/coordinator/teachers').flush([]);
    backend.expectOne('/coordinator/classes').flush(CLASSES[0]!.classes);
    backend.match((r) => r.url.startsWith('/coordinator/lessons')).forEach((r) => r.flush([]));
    backend.match('/coordinator/complaints?status=open').forEach((r) => r.flush([]));
    await settle();

    screen.getByRole('button', { name: 'Write a broadcast' }).click();
    await settle();

    // The weekly plan is the department's (DR6), so her kind select does not offer it.
    const kinds = [...document.querySelectorAll('hq-select option')].map((o) => o.getAttribute('value'));
    expect(kinds).not.toContain('weekly_plan');
    expect(document.body.textContent).toContain('The parents of the classes you coordinate');

    const title = document.querySelector('hq-input input') as HTMLInputElement;
    title.value = 'Reading week';
    title.dispatchEvent(new Event('input'));
    const body = document.querySelector('hq-textarea textarea') as HTMLTextAreaElement;
    body.value = 'Reading week starts on Sunday.';
    body.dispatchEvent(new Event('input'));
    await settle();

    (document.querySelector('hq-checkbox input') as HTMLInputElement).click();
    await settle();
    screen.getByRole('button', { name: 'Post' }).click();
    await settle();

    const request = backend.expectOne('/coordinator/broadcasts');
    expect(request.request.body).toEqual({
      kind: 'announcement',
      title: 'Reading week',
      bodyEn: 'Reading week starts on Sunday.',
      sectionIds: ['c-1'],
    });
  });
});
