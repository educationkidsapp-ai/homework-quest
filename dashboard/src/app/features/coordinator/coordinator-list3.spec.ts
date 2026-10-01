import { type HttpRequest, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { COORDINATOR_USER, MANAGERIAL_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { AdminLessonStatusEnum, BASE_PATH } from '../../api';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { ChatService } from '../../core/chat/chat.service';
import { FlagService } from '../../core/flags/flag.service';
import { ManagementCoordinatorsPage } from '../management/management-coordinators.page';
import { CoordinatorComplaintsPage } from './coordinator-complaints.page';
import { CoordinatorLessonsPage, LESSON_STATUS_CHIPS, lessonStatusTone } from './coordinator-lessons.page';
import { CoordinatorTeachersPage } from './coordinator-teachers.page';

const TEACHERS = [
  { userId: 't-1', displayName: 'Sara Al Harbi', email: 'sara@school.test', subjects: ['math'], sections: [] },
];

const COORDINATORS = [
  { userId: 'co-1', displayName: 'Rasha Kamal', email: 'rasha@school.test', scopes: [], sections: 3 },
];

/** What `POST …/chat/threads` answers: the row itself, which is what Messages then has to show. */
const THREAD = { id: 'th-new', childId: '', childName: '', teacherId: 't-1', teacherName: 'Sara Al Harbi', unread: 0 };

/** The English label of every status, as `lessons.status.*` says it — the chip and the badge. */
const LABEL: Record<AdminLessonStatusEnum, string> = {
  draft: 'Draft',
  uploading: 'Uploading',
  analyzing: 'Reading the pages',
  needs_review: 'Needs review',
  generating: 'Generating',
  review: 'Ready to review',
  paused: 'Paused',
  published: 'Published',
  error: 'Failed',
};

/**
 * **List 3 (D2)** — the coordinator's and the manager's halves of the owner's list of 2026-10-01
 * that live on these screens: "Message" opens the direct thread with *that* person, and every
 * status chip of All lessons asks for that status and draws that status.
 */
describe('list 3 — message a person, and the lesson statuses', () => {
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

  /**
   * Signed in **before** the screen exists, as the route guard guarantees in the app: these pages
   * pick their namespace from the role, and a page drawn for nobody would ask the wrong one.
   */
  async function signedIn<T>(page: Type<T>, user: typeof COORDINATOR_USER, permissions: readonly string[]) {
    const rendered = await renderHq(page, {
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: '**', children: [] }]),
        { provide: BASE_PATH, useValue: '' },
        { provide: FlagService, useValue: { isOn: () => true, ready: () => true, refresh: () => undefined } },
      ],
      configureTestBed: (testBed) => {
        testBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
        testBed.inject(AuthService).loadMe().subscribe();
        testBed.inject(HttpTestingController).expectOne('/me').flush(user);
      },
    });
    const backend = TestBed.inject(HttpTestingController);
    const area = user.role === 'MANAGERIAL' ? 'management' : 'coordinator';
    for (let pass = 0; pass < 2; pass += 1) {
      await settle();
      backend.match('/me/permissions').forEach((request) => request.flush({ permissions }));
      backend
        .match(`/${area}/me`)
        .forEach((request) => request.flush({ userId: user.id, displayName: user.displayName }));
      backend.match(`/${area}/teachers`).forEach((request) => request.flush(TEACHERS));
      backend.match(`/${area}/classes`).forEach((request) => request.flush([]));
      backend.match(`/${area}/coordinators`).forEach((request) => request.flush(COORDINATORS));
      // The chat's own list, read at sign-in: empty, which is exactly why a new thread is not in it.
      backend
        .match((request) => request.method === 'GET' && request.url === `/${area}/chat/threads`)
        .forEach((request) => request.flush([]));
    }
    await settle();
    return { rendered, backend };
  }

  /** Press Message on the only row, answer the POST, and say where the screen went. */
  async function pressMessage(backend: HttpTestingController, url: string) {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    screen.getByRole('button', { name: 'Message' }).click();
    await settle();
    const opened = backend.expectOne((request) => request.method === 'POST' && request.url === url);
    opened.flush(THREAD);
    await settle();
    return { body: opened.request.body as unknown, navigate };
  }

  describe('Message on a row', () => {
    it('a coordinator opens the direct thread with that teacher, on her own route', async () => {
      const { rendered, backend } = await signedIn(CoordinatorTeachersPage, COORDINATOR_USER, ['coordinator.chat']);

      const { body, navigate } = await pressMessage(backend, '/coordinator/chat/threads');
      // The teacher, and nothing else: no `managerUserId` beside it for the server to choose from.
      expect(body).toEqual({ teacherUserId: 't-1' });
      expect(navigate).toHaveBeenCalledWith(['/coordinator/messages'], { queryParams: { thread: 'th-new' } });
      // The row the POST answered is already the chat's, so Messages can select it on arrival.
      expect(TestBed.inject(ChatService).holds('th-new')).toBe(true);
      rendered.fixture.destroy();
    });

    it('shows a coordinator no Message without the key her route asks for', async () => {
      const { rendered } = await signedIn(CoordinatorTeachersPage, COORDINATOR_USER, []);
      expect(screen.getByText('Sara Al Harbi')).toBeTruthy();
      expect(screen.queryByRole('button', { name: 'Message' })).toBeNull();
      rendered.fixture.destroy();
    });

    it('a manager opens the thread with that teacher from Teachers', async () => {
      const { rendered, backend } = await signedIn(CoordinatorTeachersPage, MANAGERIAL_USER, ['management.chat']);

      const { body, navigate } = await pressMessage(backend, '/management/chat/threads');
      expect(body).toEqual({ teacherUserId: 't-1' });
      expect(navigate).toHaveBeenCalledWith(['/management/messages'], { queryParams: { thread: 'th-new' } });
      expect(TestBed.inject(ChatService).holds('th-new')).toBe(true);
      rendered.fixture.destroy();
    });

    it('a manager opens the thread with that coordinator from Coordinators', async () => {
      const { rendered, backend } = await signedIn(ManagementCoordinatorsPage, MANAGERIAL_USER, [
        'management.chat',
      ]);

      const { body, navigate } = await pressMessage(backend, '/management/chat/threads');
      expect(body).toEqual({ coordinatorUserId: 'co-1' });
      expect(navigate).toHaveBeenCalledWith(['/management/messages'], { queryParams: { thread: 'th-new' } });
      expect(TestBed.inject(ChatService).holds('th-new')).toBe(true);
      rendered.fixture.destroy();
    });
  });

  /** S1 gave the manager the coordinator's pair of routes; the inbox is the same component. */
  it('a manager reads and resolves her complaints on her own routes', async () => {
    const { rendered, backend } = await signedIn(CoordinatorComplaintsPage, MANAGERIAL_USER, [
      'management.complaints',
    ]);
    const list = backend.expectOne((request) => request.url === '/management/complaints');
    expect(list.request.params.get('status')).toBe('open');
    list.flush([
      { ...THREAD, id: 'th-7', childId: 'ch-1', childName: 'Layla Ahmed', topic: 'complaint', status: 'open' },
    ]);
    await settle();
    expect(backend.match((request) => request.url.startsWith('/coordinator/'))).toEqual([]);

    // The row opens *her* Messages on that thread.
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    screen.getByText('Layla Ahmed').click();
    expect(navigate).toHaveBeenCalledWith(['/management/messages'], { queryParams: { thread: 'th-7' } });

    screen.getByRole('button', { name: 'Mark resolved' }).click();
    await settle();
    screen.getByRole('button', { name: 'Yes, resolve it' }).click();
    await settle();
    const patch = backend.expectOne('/management/chat/threads/th-7/status');
    expect(patch.request.method).toBe('PATCH');
    expect(patch.request.body).toEqual({ status: 'resolved' });
    rendered.fixture.destroy();
  });

  describe('All lessons — the status chips', () => {
    const lessonOf = (status: AdminLessonStatusEnum) => ({
      id: `l-${status}`,
      title: `Lesson ${status}`,
      className: '1A',
      teacherName: 'Sara Al Harbi',
      date: '2026-09-29',
      status,
    });

    async function renderLessons(user: typeof COORDINATOR_USER) {
      const { rendered, backend } = await signedIn(CoordinatorLessonsPage, user, []);
      const area = user.role === 'MANAGERIAL' ? 'management' : 'coordinator';
      const first = backend.expectOne((request) => isList(request, area));
      // "Any status" is no filter: the parameter is simply not sent.
      expect(first.request.params.has('status')).toBe(false);
      first.flush(LESSON_STATUS_CHIPS.map(lessonOf));
      await settle();
      return { rendered, backend, area };
    }

    /** The page's own read. The Home's "needs you" asks the same route with a `from`; this one has none. */
    const isList = (request: HttpRequest<unknown>, area: string): boolean =>
      request.url === `/${area}/lessons` && !request.params.has('from');

    const badges = () => [...document.querySelectorAll('tbody .hq-badge')];

    it('offers every status the contract has, and draws each row its own badge', async () => {
      const { rendered } = await renderLessons(COORDINATOR_USER);

      const chips = screen.getAllByRole('tab').map((chip) => chip.textContent?.trim());
      expect(chips).toEqual(['Any status', ...LESSON_STATUS_CHIPS.map((status) => LABEL[status])]);
      // No status of the contract is left without a chip.
      expect([...LESSON_STATUS_CHIPS].sort()).toEqual(Object.values(AdminLessonStatusEnum).sort());

      expect(badges().length).toBe(LESSON_STATUS_CHIPS.length);
      for (const status of LESSON_STATUS_CHIPS) {
        const row = screen.getByText(`Lesson ${status}`).closest('tr');
        const badge = row?.querySelector('.hq-badge');
        expect(badge?.textContent?.trim()).toBe(LABEL[status]);
        expect(badge?.classList.contains(`hq-badge--${lessonStatusTone(status)}`)).toBe(true);
      }
      rendered.fixture.destroy();
    });

    it('wears the teacher list’s own tones', () => {
      expect(lessonStatusTone('error')).toBe('error');
      expect(lessonStatusTone('published')).toBe('success');
      for (const running of ['uploading', 'analyzing', 'generating']) {
        expect(lessonStatusTone(running)).toBe('primary');
      }
      for (const waiting of ['draft', 'needs_review', 'review', 'paused']) {
        expect(lessonStatusTone(waiting)).toBe('light');
      }
    });

    it.each(LESSON_STATUS_CHIPS)('the %s chip asks for that status and shows that badge', async (status) => {
      const { rendered, backend } = await renderLessons(COORDINATOR_USER);

      screen.getByRole('tab', { name: LABEL[status] }).click();
      await settle();
      const asked = backend.expectOne((request) => isList(request, 'coordinator'));
      expect(asked.request.params.get('status')).toBe(status);
      // Answered with more than was asked for, as the server's coarse `draft` does: only the
      // chip's own status may be drawn, so no row ever wears a badge the pressed chip does not name.
      asked.flush(LESSON_STATUS_CHIPS.map(lessonOf));
      await settle();

      expect(badges().map((badge) => badge.textContent?.trim())).toEqual([LABEL[status]]);
      expect(screen.getByText(`Lesson ${status}`)).toBeTruthy();
      expect(document.querySelector('hq-coordinator-read-failed')).toBeNull();
      rendered.fixture.destroy();
    });

    it('never sends a manager’s route a status it would refuse', async () => {
      const { rendered, backend } = await renderLessons(MANAGERIAL_USER);

      screen.getByRole('tab', { name: 'Needs review' }).click();
      await settle();
      // `/management/lessons` knows three coarse words and answers 400 to the rest (MH0): nothing
      // is asked again, and the rows already on screen are narrowed to the chip.
      expect(backend.match((request) => isList(request, 'management'))).toEqual([]);
      expect(badges().map((badge) => badge.textContent?.trim())).toEqual(['Needs review']);
      rendered.fixture.destroy();
    });
  });
});
