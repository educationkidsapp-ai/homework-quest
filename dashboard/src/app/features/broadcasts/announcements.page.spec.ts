import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, type ParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { screen } from '@testing-library/angular';
import { beforeEach, describe, expect, it } from 'vitest';
import { COORDINATOR_USER, MANAGERIAL_USER, TEACHER_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { BASE_PATH } from '../../api';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { FlagService } from '../../core/flags/flag.service';
import { AnnouncementsPage } from './announcements.page';

/** MH1: a plan is a grade, a week and an image — no title and no body at all. */
const PLAN = {
  id: 'b-plan',
  kind: 'weekly_plan',
  weekStart: '2026-09-27',
  grade: 1,
  audience: ['parents', 'teachers'],
  authorName: 'Huda Salem',
  authorRole: 'MANAGERIAL',
  curriculum: 'british',
  attachment: { id: 'att-1', name: 'week.png', type: 'image/png', url: 'https://api.example/x' },
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
 * RM3b: one feed, three roles — narrowed by MH2 item 5 to the announcements and the events. These
 * tests are about the three things only the screen can get wrong: which routes a role reads, when a
 * row stops being unread, and where a `broadcast.posted` for a *plan* ends up.
 */
describe('the announcements screen', () => {
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

  /** The query the page reads, pushable so a test can navigate without leaving the component. */
  let query: BehaviorSubject<ParamMap>;

  async function signedInAs(user: typeof MANAGERIAL_USER, params: Record<string, string> = {}) {
    query = new BehaviorSubject<ParamMap>(convertToParamMap(params));
    await renderHq(AnnouncementsPage, {
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: '**', children: [] }]),
        { provide: BASE_PATH, useValue: '' },
        { provide: FlagService, useValue: { isOn: () => true, refresh: () => undefined } },
        // MG2a: the deep link is the thing under test, and a bare `provideRouter` leaves the
        // query empty — so it is supplied as the observable the page subscribes to, which is
        // also what lets a test change it *after* the component is on screen.
        { provide: ActivatedRoute, useValue: { queryParamMap: query.asObservable() } },
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

  /**
   * MG2a item 4: `broadcast.posted` links here with `?open=<id>` — the id E2 carries as the
   * row's entity id. Opening the screen *is* opening that row: a twelve-row feed that merely
   * contains the one the bell named has not answered the click.
   */
  it('opens, reads and scrolls to the row a notification named with ?open=', async () => {
    const backend = await signedInAs(TEACHER_USER, { open: 'b-event' });
    backend.expectOne('/me/broadcasts').flush({ items: [PLAN, EVENT], unread: 2 });
    await settle();

    expect(document.body.textContent).toContain('Thursday, on the big field.');
    expect(document.querySelector('#bc-b-event')).toBeTruthy();
    backend.expectOne('/me/broadcasts/b-event/read').flush({ ...EVENT, read: true });
    await settle();
    backend.expectOne('/me/broadcasts').flush({ items: [PLAN, { ...EVENT, read: true }], unread: 1 });
    await settle();

    // Collapsing it is still hers to do — the deep link opens the row once, it does not pin it.
    screen.getByRole('button', { name: /Sports day/ }).click();
    await settle();
    expect(document.body.textContent).not.toContain('Thursday, on the big field.');
  });

  /**
   * The bell is on every screen, so the common case is clicking a `broadcast.posted` row while
   * already *on* Announcements — a query-param-only navigation that reuses the component. Reading
   * `route.snapshot` once in the constructor missed exactly that, which is why the query is a
   * signal and why this test changes it after the first render rather than before it.
   */
  it('opens the row when ?open= changes while she is already on the screen', async () => {
    const backend = await signedInAs(TEACHER_USER);
    backend.expectOne('/me/broadcasts').flush({ items: [PLAN, EVENT], unread: 2 });
    await settle();

    expect(document.body.textContent).not.toContain('Thursday, on the big field.');

    query.next(convertToParamMap({ open: 'b-event' }));
    await settle();

    expect(document.body.textContent).toContain('Thursday, on the big field.');
    backend.expectOne('/me/broadcasts/b-event/read').flush({ ...EVENT, read: true });
    await settle();
    backend.expectOne('/me/broadcasts').flush({ items: [PLAN, { ...EVENT, read: true }], unread: 1 });
    await settle();
  });

  /**
   * MH2 item 5: a weekly plan is **not on this feed**. It is a picture, and this list draws titles
   * with expanding bodies — a plan drawn here was a title over an attachment link that answered 401.
   */
  it('keeps a weekly plan out of the feed entirely', async () => {
    const backend = await signedInAs(TEACHER_USER);
    backend.expectOne('/me/broadcasts').flush({ items: [PLAN, EVENT], unread: 2 });
    await settle();

    const titles = [...document.querySelectorAll('.bc__title')].map((node) => node.textContent?.trim());
    expect(titles).toEqual(['Sports day']);
    // And nothing is marked read on arrival — there is no pinned row left to open by itself.
    expect(backend.match('/me/broadcasts/b-plan/read')).toEqual([]);
  });

  /**
   * MH2 item 6. `NotificationService.broadcastLink` writes one link for every kind and
   * `NotificationView` carries no kind, so a plan's `?open=` lands here. The row itself says what it
   * is, so the screen forwards it to the tab that draws pictures rather than looking for it in a
   * feed it was filtered out of.
   */
  it('forwards a plan’s ?open= to the Weekly plans tab', async () => {
    const backend = await signedInAs(TEACHER_USER, { open: 'b-plan' });
    backend.expectOne('/me/broadcasts').flush({ items: [PLAN, EVENT], unread: 2 });
    await settle();

    // The tab switched on its own, which is what makes the plans read happen at all.
    backend
      .expectOne((request) => request.url === '/me/weekly-plans')
      .flush({ weeks: [{ weekStart: '2026-09-27', items: [{ plan: PLAN }] }] });
    await settle();

    // It is still marked read: the bell must stop ringing for a plan she has now been shown.
    backend.expectOne('/me/broadcasts/b-plan/read').flush({ ...PLAN, read: true });
    await settle();
    backend.match('/me/broadcasts').forEach((request) => request.flush({ items: [EVENT], unread: 1 }));
    await settle();
    expect(document.body.textContent).toContain('Weekly plan');
  });

  it('gives a teacher no composer at all', async () => {
    const backend = await signedInAs(TEACHER_USER);
    backend.expectOne('/me/broadcasts').flush({ items: [], unread: 0 });
    await settle();

    expect(screen.queryByRole('button', { name: 'Write an announcement' })).toBeNull();
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

    screen.getByRole('button', { name: 'Write an announcement' }).click();
    await settle();

    const post = screen.getByRole('button', { name: 'Post' });
    expect(post.hasAttribute('disabled')).toBe(true);

    // MH2 item 5: neither composer offers a plan any more, whatever the role.
    const kinds = [...document.querySelectorAll('hq-select option')].map((o) => o.getAttribute('value'));
    expect(kinds).not.toContain('weekly_plan');

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

    screen.getByRole('button', { name: 'Write an announcement' }).click();
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

  /**
   * MG2b item 2, reworked by MH2 item 4: the read-only archive a teacher and a coordinator get, on
   * `GET /me/weekly-plans` — now **pictures**, fetched with the bearer rather than by `src`.
   */
  it('lists the weekly plans of past weeks on its own tab, and asks for them only then', async () => {
    const backend = await signedInAs(TEACHER_USER);
    backend.expectOne('/me/broadcasts').flush({ items: [PLAN], unread: 1 });
    await settle();
    // Nothing is asked on arrival: the twelve weeks behind this one are a second question.
    expect(backend.match((request) => request.url === '/me/weekly-plans')).toEqual([]);

    screen.getByRole('tab', { name: 'Weekly plans' }).click();
    await settle();

    backend
      .expectOne((request) => request.url === '/me/weekly-plans')
      .flush({
        from: '2026-09-13',
        to: '2026-09-27',
        weeks: [
          { weekStart: '2026-09-27', items: [{ plan: PLAN }] },
          {
            weekStart: '2026-09-13',
            items: [
              {
                plan: {
                  ...PLAN,
                  id: 'b-old',
                  grade: 3,
                  weekStart: '2026-09-13',
                  attachment: { id: 'att-old', name: 'old.png' },
                },
              },
            ],
          },
        ],
      });
    await settle();

    // The alt text is what a plan *says* now — "Weekly plan · Grade N · Week of …".
    const alts = [...document.querySelectorAll('img.pw__thumb')].map((node) => node.getAttribute('alt'));
    expect(alts.some((alt) => alt?.includes('Grade 1'))).toBe(true);
    expect(alts.some((alt) => alt?.includes('Grade 3'))).toBe(true);
    // And every one of them is fetched with the bearer, never pointed at the DTO's absolute url.
    const bytes = backend.match((request) => request.url.startsWith('/media/attachments/'));
    expect(bytes.map((request) => request.request.url).sort()).toEqual([
      '/media/attachments/att-1',
      '/media/attachments/att-old',
    ]);
    bytes.forEach((request) => request.flush(new Blob(['x'], { type: 'image/png' })));
    await settle();
    // `readBy` is the manager's own archive; a reader's rows carry none, so nothing says it here.
    expect(document.body.textContent).not.toContain('Read by');
  });
});
