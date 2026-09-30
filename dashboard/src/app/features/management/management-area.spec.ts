import { Component } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, RouterOutlet, provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ADMIN_USER, MANAGERIAL_USER, TEACHER_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { type CreateBroadcastRequest, type ManagementStats, BASE_PATH } from '../../api';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { FlagService } from '../../core/flags/flag.service';
import { LessonApiService } from '../lessons/lesson-api.service';
import { ResultsApiService } from '../results/results-api.service';
import { csvOf } from '../../core/download/csv';
import { ManagementHomePage } from './management-home.page';
import {
  EXPORT_CONCURRENCY,
  EXPORT_MAX_PAGES,
  ManagementChildrenPage,
  exportPlan,
} from './management-children.page';
import { statsRows, quietTeachers } from './management-stats';
import { SchoolUsagePage } from './school-usage.page';
import { WeeklyPlansPage } from './weekly-plans.page';
import { usageSummary, usageTeacherRows } from './school-usage.models';
import { changedMarks, notEditableReason, rosterOf } from './staff-attendance.models';

/** `GET /management/me` — her department, and the four counts her Home's cards are (RM1). */
const ME = {
  userId: 'u-huda',
  displayName: 'Huda Salem',
  email: 'manager.a@school.test',
  departments: ['british'],
  sections: 15,
  teachers: 20,
  coordinators: 6,
  children: 300,
  grades: 6,
};

/**
 * `GET /management/stats` — DR5's shape, with the three nulls that matter: a grade nobody marked
 * a register in has no attendance rate, and a grade with no exams has neither average nor pass
 * rate.
 *
 * Cast, because the generated model says `attendanceRate?: number` and the server sends an
 * explicit `null` there. OpenAPI's `nullable` is not on the contract for these fields, so the
 * *type* cannot say what the server does; `statsRows` is what makes both shapes safe, and this
 * fixture is the wire, not the type.
 */
const STATS = {
  from: '2026-08-27',
  to: '2026-09-27',
  grades: [
    {
      grade: 2,
      curriculum: 'british',
      children: 40,
      sections: 2,
      attendanceRate: 92,
      lessonsPublished: 8,
      lessonsPlayed: 30,
      exams: 1,
      examAverage: 71,
      examPassRate: 80,
      quietTeachers: [{ userId: 't-9', displayName: 'Mr Omar', email: 'omar@school.test' }],
    },
    {
      grade: 1,
      curriculum: 'british',
      children: 24,
      sections: 1,
      attendanceRate: null,
      lessonsPublished: 3,
      lessonsPlayed: 12,
      exams: 0,
      examAverage: null,
      examPassRate: null,
      // The same teacher again: one line on the screen, not two.
      quietTeachers: [{ userId: 't-9', displayName: 'Mr Omar', email: 'omar@school.test' }],
    },
  ],
  total: {
    grade: 0,
    children: 64,
    sections: 3,
    attendanceRate: 92,
    lessonsPublished: 11,
    lessonsPlayed: 42,
    exams: 1,
    examAverage: 71,
    examPassRate: 80,
    quietTeachers: [],
  },
} as unknown as ManagementStats;

/** RM5's roster: one person marked late with a note, one nobody has touched. */
const ROSTER = {
  day: '2026-09-27',
  editable: true,
  schoolDay: true,
  people: [
    {
      userId: 't-1',
      displayName: 'Sara Al Harbi',
      email: 'sara@school.test',
      role: 'TEACHER',
      status: 'late',
      note: 'traffic',
    },
    { userId: 'c-1', displayName: 'Rasha Kamal', email: 'rasha@school.test', role: 'COORDINATOR' },
  ],
};

/** One page of `GET /management/people/children`: two of sixty, with both parent addresses. */
const CHILDREN = {
  page: 0,
  size: 25,
  total: 60,
  rows: [
    {
      childId: 'ch-1',
      name: 'Ali Hassan',
      className: '1A',
      grade: 1,
      curriculum: 'british',
      parentEmail: 'parent@home.test',
      rosterEmail: 'roster@school.test',
      parentPhone: '+968 9123 4567',
      parentId: 'u-parent',
    },
    // MH1: `parentId` null is a roster row nobody has signed up for — no phone, and nobody to
    // message. The two rows are the two states the action has.
    { childId: 'ch-2', name: 'Noor Saleh', className: '1B', grade: 1, curriculum: 'british' },
  ],
};

/**
 * `GET /school/usage` — MG2a. Two weeks of published lessons, three days of plays, and three
 * teachers chosen for the three cases the table has to keep apart: a busy but irregular one, a
 * steady one, and one who has never published at all.
 */
const USAGE = {
  schoolId: 's-1',
  schoolName: 'Al Noor',
  from: '2026-08-01',
  to: '2026-09-26',
  children: 300,
  activeFamilies: 210,
  lessonsPublishedPerWeek: [
    { week: '2026-09-13', count: 6 },
    { week: '2026-09-20', count: 8 },
  ],
  playsPerDay: [
    { date: '2026-09-24', count: 30 },
    { date: '2026-09-25', count: 25 },
    { date: '2026-09-26', count: 35 },
  ],
  teacherConsistency: [
    {
      teacherId: 't-1',
      displayName: 'Sara Al Harbi',
      lessonsPublished: 12,
      weeks: 8,
      weeksWithALesson: 2,
      lastPublishedAt: 1_758_000_000_000,
    },
    {
      teacherId: 't-2',
      displayName: 'Mr Omar',
      lessonsPublished: 8,
      weeks: 8,
      weeksWithALesson: 8,
      lastPublishedAt: 1_758_500_000_000,
    },
    { teacherId: 't-3', displayName: 'Nobody Yet', lessonsPublished: 0, weeks: 0, weeksWithALesson: 0 },
  ],
};

describe('RM3a — the management area', () => {
  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
  });

  describe('role routing', () => {
    /** Signs the given user in so the two API services can answer "which namespace". */
    async function signedInAs(user: typeof MANAGERIAL_USER) {
      TestBed.configureTestingModule({
        providers: [provideHttpClient(), provideHttpClientTesting(), { provide: BASE_PATH, useValue: '' }],
      });
      const backend = TestBed.inject(HttpTestingController);
      TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
      TestBed.inject(AuthService).loadMe().subscribe();
      backend.expectOne('/me').flush(user);
      await Promise.resolve();
      TestBed.tick();
      return backend;
    }

    it("sends a manager's lesson reads to /management, not to /admin", async () => {
      const backend = await signedInAs(MANAGERIAL_USER);
      const lessons = TestBed.inject(LessonApiService);

      expect(lessons.isAdmin()).toBe(false);
      expect(lessons.isManager()).toBe(true);
      // A `/status` alias of her own is what lets the read-only lesson page poll (RM1).
      expect(lessons.supportsStatusPoll()).toBe(true);

      lessons.list({ classId: 'c-1', status: 'published' }).subscribe();
      backend.expectOne('/management/lessons?classId=c-1&status=published').flush([]);
      lessons.getLesson('l-1').subscribe();
      backend.expectOne('/management/lessons/l-1').flush({});
      lessons.status('l-1').subscribe();
      backend.expectOne('/management/lessons/l-1/status').flush({});
      backend.verify();
    });

    it("sends a manager's records to /management and offers her no export", async () => {
      const backend = await signedInAs(MANAGERIAL_USER);
      const reads = TestBed.inject(ResultsApiService);

      expect(reads.base()).toBe('/management');
      // Every `.csv`, `.xlsx` and per-child `.pdf` is a teacher-namespace route: a button that
      // answers 403 is worse than no button.
      expect(reads.supportsExport()).toBe(false);
      // Her Classes screen opens no section, so the middle breadcrumb is a name, not a link.
      expect(reads.classLink('c-1')).toBeNull();

      reads.gradebook('c-1', '2026-09-01', '2026-09-30').subscribe();
      backend.expectOne('/management/classes/c-1/results?from=2026-09-01&to=2026-09-30').flush({});
      reads.lessonResults('l-1').subscribe();
      backend.expectOne('/management/lessons/l-1/results').flush({});
      reads.childReport('ch-1').subscribe();
      backend.expectOne('/management/children/ch-1').flush({});
      reads.classExams('c-1').subscribe();
      backend.expectOne('/management/classes/c-1/exams').flush([]);
      reads.examResults('e-1').subscribe();
      backend.expectOne('/management/exams/e-1/results').flush({});
      reads.attendanceRange('c-1', '2026-09-21', '2026-09-27').subscribe();
      backend.expectOne('/management/classes/c-1/attendance?from=2026-09-21&to=2026-09-27').flush([]);
      backend.verify();
    });

    it('leaves the teacher on her own namespace — the branch is one role, not "not an admin"', async () => {
      const backend = await signedInAs(TEACHER_USER);
      const reads = TestBed.inject(ResultsApiService);

      expect(reads.base()).toBe('/teacher');
      expect(reads.supportsExport()).toBe(true);
      expect(TestBed.inject(LessonApiService).isManager()).toBe(false);

      reads.lessonResults('l-1').subscribe();
      backend.expectOne('/teacher/lessons/l-1/results').flush({});
      backend.verify();
    });
  });

  describe('the statistics table', () => {
    it('draws a row per grade and the department total, with a dash for every null', async () => {
      const rendered = await renderHq(ManagementHomePage, {
        providers: [
          provideHttpClient(),
          provideHttpClientTesting(),
          provideRouter([{ path: '**', children: [] }]),
          { provide: BASE_PATH, useValue: '' },
        ],
      });
      const backend = TestBed.inject(HttpTestingController);
      TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
      TestBed.inject(AuthService).loadMe().subscribe();
      backend.expectOne('/me').flush(MANAGERIAL_USER);
      await Promise.resolve();
      TestBed.tick();

      backend.expectOne('/management/me').flush(ME);
      backend.expectOne('/management/teachers').flush([]);
      backend.expectOne('/management/classes').flush([]);
      backend.expectOne((request) => request.url === '/management/stats').flush(STATS);
      await Promise.resolve();
      TestBed.tick();
      await Promise.resolve();
      TestBed.tick();

      expect(screen.getByRole('heading', { level: 1 }).textContent).toContain('Huda Salem');
      // Her department, from `/management/me` — one curriculum, no subject beside it (DR5).
      expect(document.body.textContent).toContain('British');

      const rows = [...document.querySelectorAll('tbody tr')].map((row) => row.textContent ?? '');
      expect(rows.length).toBe(3);
      expect(rows[0]).toContain('Grade 1');
      // Grade 1 marked no register and sat no exam: three dashes, not three zeros and not 100 %.
      expect(rows[0]?.match(/—/g)?.length).toBe(3);
      expect(rows[1]).toContain('Grade 2');
      expect(rows[1]).toContain('92%');
      expect(rows[2]).toContain('Whole department');

      // One quiet teacher, although `/management/stats` named him under two grades.
      expect(screen.getAllByText('Mr Omar').length).toBe(1);
      rendered.fixture.destroy();
    });

    it('sorts grades, puts the total last, and keeps a missing count apart from a missing rate', () => {
      const rows = statsRows(STATS);

      expect(rows.map((row) => row.grade)).toEqual([1, 2, null]);
      expect(rows[0]?.exams).toBe(0);
      expect(rows[0]?.attendanceRate).toBeNull();
      expect(rows[0]?.examAverage).toBeNull();
      expect(quietTeachers(STATS).map((teacher) => teacher.userId)).toEqual(['t-9']);
    });

    it('draws nothing at all rather than an empty total row when the window answered nothing', () => {
      expect(statsRows(undefined)).toEqual([]);
      expect(quietTeachers(undefined)).toEqual([]);
    });
  });

  /**
   * MH0 — the two bugs the owner hit on every open of her Home.
   *
   * They were one fault with two faces. `GET /management/lessons` narrows by
   * `draft|ready|published`, so the "what needs you" ask for `needs_review` came back 400 —
   * the red band, verbatim — and an errored resource's `value()` *throws*, so the computed that
   * read it threw inside her template: change detection died on the way out of the skeleton and
   * only a full reload, which renders in a different order, ever got past it.
   */
  describe('her Home on the second visit', () => {
    @Component({ selector: 'hq-mg-elsewhere', template: '<p>Elsewhere</p>' })
    class ElsewherePage {}

    @Component({ selector: 'hq-mg-shell', imports: [RouterOutlet], template: '<router-outlet />' })
    class ShellComponent {}

    async function settle() {
      await Promise.resolve();
      TestBed.tick();
      await Promise.resolve();
      TestBed.tick();
    }

    /** Answers everything outstanding the way the live server would, and says what was asked. */
    function serve(backend: HttpTestingController): string[] {
      const asked: string[] = [];
      for (const request of backend.match(() => true)) {
        const url = request.request.urlWithParams;
        asked.push(url);
        if (request.cancelled) continue;
        if (url === '/management/me') request.flush(ME);
        else if (url.startsWith('/management/stats')) request.flush(STATS);
        else if (url.startsWith('/management/lessons')) {
          // The 400 the owner saw. Reaching this line at all is the bug.
          request.flush(
            { message: 'Status is draft, ready or published' },
            { status: 400, statusText: 'Bad Request' },
          );
        } else request.flush([]);
      }
      return asked;
    }

    it('draws the department again when she clicks Home in the rail, and never asks for lessons', async () => {
      const rendered = await renderHq(ShellComponent, {
        providers: [
          provideHttpClient(),
          provideHttpClientTesting(),
          provideRouter([
            { path: 'management', component: ManagementHomePage },
            { path: 'management/elsewhere', component: ElsewherePage },
          ]),
          { provide: BASE_PATH, useValue: '' },
        ],
      });
      const backend = TestBed.inject(HttpTestingController);
      const router = TestBed.inject(Router);
      TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
      TestBed.inject(AuthService).loadMe().subscribe();
      backend.expectOne('/me').flush(MANAGERIAL_USER);
      await settle();

      await router.navigateByUrl('/management');
      await settle();
      const first = serve(backend);
      await settle();
      const second = serve(backend);
      await settle();
      expect(document.body.textContent).toContain('Whole department');

      // Away to another of her screens, then back — no reload anywhere in between.
      await router.navigateByUrl('/management/elsewhere');
      await settle();
      serve(backend);
      expect(document.body.textContent).toContain('Elsewhere');

      await router.navigateByUrl('/management');
      await settle();
      const third = serve(backend);
      await settle();
      serve(backend);
      await settle();

      // The bug: her Home came back as the skeleton and stayed there until a force-refresh.
      expect(document.body.textContent).not.toContain('Loading your home');
      expect(screen.getByRole('heading', { level: 1 }).textContent).toContain('Huda Salem');
      expect(document.body.textContent).toContain('How the department is doing');
      expect(document.body.textContent).toContain('Whole department');
      // Her "what needs you" is the sections with nothing on today; no lesson read on any visit.
      expect([...first, ...second, ...third].filter((url) => url.startsWith('/management/lessons'))).toEqual(
        [],
      );
      backend.verify();
      rendered.fixture.destroy();
    });
  });

  describe('the children directory', () => {
    async function renderChildren() {
      const rendered = await renderHq(ManagementChildrenPage, {
        providers: [
          provideHttpClient(),
          provideHttpClientTesting(),
          provideRouter([{ path: '**', children: [] }]),
          { provide: BASE_PATH, useValue: '' },
        ],
      });
      const backend = TestBed.inject(HttpTestingController);
      TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
      TestBed.inject(AuthService).loadMe().subscribe();
      backend.expectOne('/me').flush(MANAGERIAL_USER);
      await Promise.resolve();
      TestBed.tick();
      backend.expectOne('/management/people/children?page=0&size=25').flush(CHILDREN);
      await Promise.resolve();
      TestBed.tick();
      return { rendered, backend };
    }

    /** One keystroke at a time, the way the box receives them. */
    async function type(value: string): Promise<void> {
      const search = screen.getByLabelText<HTMLInputElement>(/Search by name or email/);
      search.value = value;
      search.dispatchEvent(new Event('input', { bubbles: true }));
      await Promise.resolve();
      TestBed.tick();
    }

    /** Waits the debounce out with real timers, then lets the resource fire. */
    async function settle(): Promise<void> {
      await new Promise((resolve) => setTimeout(resolve, 300));
      await Promise.resolve();
      TestBed.tick();
    }

    it('asks once for a typed word, not once per keystroke', async () => {
      const { rendered, backend } = await renderChildren();

      // Eight keystrokes. Before the debounce every one of them was a server-side search across
      // the whole department; "Mohammed" is now one request, for the word she finished typing.
      for (const upto of ['M', 'Mo', 'Moh', 'Moha', 'Moham', 'Mohamm', 'Mohamme', 'Mohammed']) {
        await type(upto);
      }
      backend.expectNone((request) => request.url.startsWith('/management/people/'));

      await settle();
      backend.expectOne('/management/people/children?q=Mohammed&page=0&size=25').flush(CHILDREN);
      backend.expectNone((request) => request.url.startsWith('/management/people/'));
      rendered.fixture.destroy();
    });

    it('puts the department back at once when the box is emptied — clearing is not debounced', async () => {
      const { rendered, backend } = await renderChildren();

      await type('ali');
      await settle();
      backend.expectOne('/management/people/children?q=ali&page=0&size=25').flush(CHILDREN);
      await Promise.resolve();
      TestBed.tick();

      await type('');
      // No timer to wait out: there is nothing left to type ahead of.
      backend.expectOne('/management/people/children?page=0&size=25').flush(CHILDREN);
      rendered.fixture.destroy();
    });

    it("pages on the server's own count and says where in it she is", async () => {
      const { rendered } = await renderChildren();

      expect(screen.getByText('Ali Hassan')).toBeTruthy();
      // The parent's own address, with the roster's as the fallback: two columns would be two
      // mostly identical ones, and the roster's exists before a parent has opened the app.
      expect(document.body.textContent).toContain('parent@home.test');
      expect(document.body.textContent).toContain('1–2 of 60');
      rendered.fixture.destroy();
    });

    it('takes a new needle back to the first page', async () => {
      const { rendered, backend } = await renderChildren();

      screen.getByRole('button', { name: 'Next' }).click();
      await Promise.resolve();
      TestBed.tick();
      backend.expectOne('/management/people/children?page=1&size=25').flush({ ...CHILDREN, page: 1 });
      await Promise.resolve();
      TestBed.tick();

      // Page 1 of a list that no longer exists is not an answer, so a new word starts at the top.
      await type('ali');
      await settle();
      backend.expectOne('/management/people/children?q=ali&page=0&size=25').flush(CHILDREN);
      rendered.fixture.destroy();
    });

    /**
     * MH2 item 3: the teachers and the coordinators tabs are gone — they are rail rows of their own
     * with a phone number and a Message action — and what is left carries the parent's phone.
     */
    it('is one list of children, with the parent’s phone as a tel: link', async () => {
      const { rendered, backend } = await renderChildren();

      expect(screen.queryByRole('tab', { name: 'Coordinators' })).toBeNull();
      expect(backend.match((request) => request.url.includes('/people/teachers'))).toEqual([]);

      const tel = document.querySelector('a[href^="tel:"]') as HTMLAnchorElement;
      expect(tel.getAttribute('href')).toBe('tel:+968 9123 4567');
      expect(tel.textContent?.trim()).toBe('+968 9123 4567');
      rendered.fixture.destroy();
    });

    /**
     * MH1 answers 404 `no_parent` for a roster row nobody has signed up for, so the button that
     * would post it is disabled with the reason on it rather than left to fail.
     */
    it('opens the parent thread from a row, and disables it when there is no parent', async () => {
      const { rendered, backend } = await renderChildren();
      backend
        .match('/me/permissions')
        .forEach((request) => request.flush({ permissions: ['management.people', 'management.chat'] }));
      await Promise.resolve();
      TestBed.tick();

      const buttons = screen.getAllByRole('button', { name: 'Message parent' });
      expect(buttons).toHaveLength(2);
      expect(buttons[1]!.hasAttribute('disabled')).toBe(true);

      buttons[0]!.click();
      await Promise.resolve();
      TestBed.tick();
      const opened = backend.expectOne('/management/chat/threads');
      expect(opened.request.method).toBe('POST');
      // The **child**, not the parent's user id: the server resolves the parent, and a dashboard
      // that guessed would open a thread with whoever happened to be attached last.
      expect(opened.request.body).toEqual({ childId: 'ch-1' });
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
      opened.flush({ id: 'th-9' });
      await Promise.resolve();
      TestBed.tick();
      expect(navigate).toHaveBeenCalledWith(['/management/messages'], {
        queryParams: { thread: 'th-9' },
      });
      rendered.fixture.destroy();
    });

    it('writes a CSV Excel opens: a byte-order mark, CRLF, and a tab before a leading dash', () => {
      const csv = csvOf(
        ['Name', 'Class'],
        [
          ['-Ali', '1A'],
          ['Say "hi"', '=2+2'],
        ],
      );

      expect(csv.startsWith('\ufeff')).toBe(true);
      expect(csv).toContain('\r\n');
      // A child called "-Ali" is a formula to Excel, and so is a class called "=2+2".
      expect(csv).toContain('"\t-Ali"');
      expect(csv).toContain('"\t=2+2"');
      expect(csv).toContain('"Say ""hi"""');
    });

    it('caps the export, and only then says the file is short of the department', () => {
      // Sixty children is one page of a hundred and the whole department; four thousand is forty
      // pages, which the cap takes to thirty — read four at a time, never forty at once.
      expect(exportPlan(60)).toEqual({ pages: 1, truncated: false });
      expect(exportPlan(3000)).toEqual({ pages: 30, truncated: false });
      expect(exportPlan(4000)).toEqual({ pages: 30, truncated: true });
      // An empty tab still reads one page: the export answers with headers, not with nothing.
      expect(exportPlan(0)).toEqual({ pages: 1, truncated: false });
      expect(EXPORT_CONCURRENCY).toBeLessThan(EXPORT_MAX_PAGES);
    });
  });

  /**
   * MG2a item 5 — `GET /school/usage`, the endpoint the "Coming soon" stub was standing in for.
   *
   * **It is the school's, not her department's**: `mySchoolUsage` resolves the caller's own
   * school and `SchoolUsage` has no curriculum axis at all, so both departments' teachers are in
   * the table. The screen says so; these tests assert the mapping, which is where the two
   * numbers that are easy to get wrong live — a window's total rather than its last week, and
   * "weeks with a lesson" kept apart from "lessons".
   */
  describe('School usage', () => {
    it('sums the window rather than reading the last week, and keeps a teacher two counts apart', () => {
      const summary = usageSummary(USAGE);
      expect(summary).toEqual({ children: 300, activeFamilies: 210, lessonsPublished: 14, plays: 90 });
      // Nothing on the wire is required, and a school that answered nothing is zeros, not blanks.
      expect(usageSummary(undefined)).toEqual({
        children: 0,
        activeFamilies: 0,
        lessonsPublished: 0,
        plays: 0,
      });

      const rows = usageTeacherRows(USAGE);
      // Busiest first: twelve lessons in two of eight weeks is a different fact from eight in eight.
      expect(rows.map((row) => row.displayName)).toEqual(['Sara Al Harbi', 'Mr Omar', 'Nobody Yet']);
      expect(rows[0]?.consistency).toBe(25);
      expect(rows[1]?.consistency).toBe(100);
      // A teacher who never published has no date — a dash on the screen, never the epoch.
      expect(rows[2]?.lastPublishedAt).toBeNull();
      expect(rows[2]?.consistency).toBeNull();
    });

    it('draws the tiles and the per-teacher table, and exports the rows on screen', async () => {
      const rendered = await renderHq(SchoolUsagePage, {
        providers: [
          provideHttpClient(),
          provideHttpClientTesting(),
          provideRouter([{ path: '**', children: [] }]),
          { provide: BASE_PATH, useValue: '' },
        ],
      });
      const backend = TestBed.inject(HttpTestingController);
      // MG2b: which endpoint this screen reads depends on the role, so the account has to land
      // before anything is asked — an Admin's is the whole school (`GET /school/usage`).
      TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
      TestBed.inject(AuthService).loadMe().subscribe();
      backend.expectOne('/me').flush(ADMIN_USER);
      await Promise.resolve();
      TestBed.tick();
      backend.expectOne((request) => request.url === '/school/usage').flush(USAGE);
      await Promise.resolve();
      TestBed.tick();

      expect(document.body.textContent).toContain('Sara Al Harbi');
      // The four tiles are drawn; their figures are `hqCountUp`'s, which needs a layout engine,
      // so what they add up to is asserted on `usageSummary` above instead.
      expect(document.body.textContent).toContain('Lessons published');
      expect(document.body.textContent).toContain('Active families');
      // It says whose numbers these are: the school's, not her department's.
      expect(document.body.textContent).toContain('The whole school');
      const rows = [...document.querySelectorAll('tbody tr')].map((row) => row.textContent ?? '');
      expect(rows.length).toBe(3);
      expect(rows[0]).toContain('25%');
      expect(rows[2]).toContain('—');
      rendered.fixture.destroy();
    });

    /**
     * The review's third blocker: the two date boxes fired a request on every `valueChange`, and
     * a `type="date"` input emits a *complete* value while the year is being typed — `0002-09-05`
     * is a full date, a window of two thousand years, and a 400 the page showed as a bare red
     * retry band. Now: nothing is asked while she types, and the two windows the server would
     * refuse are refused in words first.
     */
    it('asks nothing while she types, and refuses both bad windows in words', async () => {
      vi.useFakeTimers();
      try {
        const rendered = await renderHq(SchoolUsagePage, {
          providers: [
            provideHttpClient(),
            provideHttpClientTesting(),
            provideRouter([{ path: '**', children: [] }]),
            { provide: BASE_PATH, useValue: '' },
          ],
        });
        const backend = TestBed.inject(HttpTestingController);
        TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
        TestBed.inject(AuthService).loadMe().subscribe();
        backend.expectOne('/me').flush(ADMIN_USER);
        await Promise.resolve();
        TestBed.tick();
        backend.expectOne((request) => request.url === '/school/usage').flush(USAGE);
        await Promise.resolve();
        TestBed.tick();

        const boxes = [...document.querySelectorAll('input[type="date"]')] as HTMLInputElement[];
        const type = (box: HTMLInputElement, value: string) => {
          box.value = value;
          box.dispatchEvent(new Event('input', { bubbles: true }));
          TestBed.tick();
        };

        // A year typed digit by digit: three complete dates, and not one of them a request.
        for (const year of ['0002', '0020', '0202']) type(boxes[0]!, `${year}-09-05`);
        expect(backend.match('/school/usage')).toEqual([]);

        // It settles on a window longer than the server's 400 days: the sentence, not a request.
        vi.advanceTimersByTime(400);
        TestBed.tick();
        expect(backend.match('/school/usage')).toEqual([]);
        expect(document.body.textContent).toContain('400 days or less');

        // A window that ends before it starts says so rather than drawing four zero tiles.
        type(boxes[0]!, '2026-09-26');
        type(boxes[1]!, '2026-09-01');
        vi.advanceTimersByTime(400);
        TestBed.tick();
        expect(backend.match('/school/usage')).toEqual([]);
        expect(document.body.textContent).toContain('ends before it starts');

        // Put it right and exactly one request goes, for the window she actually chose.
        type(boxes[1]!, '2026-09-30');
        vi.advanceTimersByTime(400);
        TestBed.tick();
        const asked = backend.match('/school/usage?from=2026-09-26&to=2026-09-30');
        expect(asked.length).toBe(1);
        asked[0]!.flush(USAGE);
        rendered.fixture.destroy();
      } finally {
        vi.useRealTimers();
      }
    });

    it('writes a CSV Excel opens, from the rows and not from a second read', () => {
      const rows = usageTeacherRows(USAGE);
      const file = csvOf(
        ['Teacher', 'Lessons published', 'Weeks with a lesson', 'Consistency'],
        rows.map((row) => [
          row.displayName,
          String(row.lessonsPublished),
          `${row.weeksWithALesson} / ${row.weeks}`,
          row.consistency === null ? '—' : `${row.consistency}%`,
        ]),
      );

      expect(file.startsWith('\uFEFF"Teacher"')).toBe(true);
      expect(file).toContain('\r\n');
      expect(file).toContain('"Sara Al Harbi","12","2 / 8","25%"');
    });
  });

  describe('the staff register', () => {
    it('reads a mark as what it is and an unmarked person as nothing — never as present', () => {
      const roster = rosterOf(ROSTER);

      expect(roster.map((row) => row.name)).toEqual(['Rasha Kamal', 'Sara Al Harbi']);
      expect(roster[0]?.status).toBeNull();
      expect(roster[1]?.status).toBe('late');
      expect(roster[1]?.note).toBe('traffic');
    });

    it('sends only the people she changed, and drops an empty note rather than saving one', () => {
      const before = rosterOf(ROSTER);
      const after = before.map((row) =>
        row.userId === 'c-1' ? { ...row, status: 'present' as const } : row,
      );

      expect(changedMarks(before, after)).toEqual([{ userId: 'c-1', status: 'present', note: undefined }]);
      // Nothing touched is nothing sent: an unchanged roster must not re-stamp `markedBy`.
      expect(changedMarks(before, before)).toEqual([]);
    });

    it('never sends a person nobody marked, even when her note was typed first', () => {
      const before = rosterOf(ROSTER);
      const after = before.map((row) => (row.userId === 'c-1' ? { ...row, note: 'called in' } : row));

      expect(changedMarks(before, after)).toEqual([]);
    });

    it('tells a day that has not happened from a day the school does not teach on', () => {
      expect(notEditableReason(ROSTER)).toBeNull();
      expect(notEditableReason({ ...ROSTER, editable: false, schoolDay: false })).toBe('closed');
      expect(notEditableReason({ ...ROSTER, editable: false, schoolDay: true })).toBe('future');
      // No answer yet is not a refusal: the chips stay as they are until the roster lands.
      expect(notEditableReason(undefined)).toBeNull();
    });
  });

  /**
   * **MG2b items 3 and 4** — the weekly plan per grade, and the archive of every one of them.
   *
   * One request feeds both halves of the screen: the glance is this week filtered out of the
   * archive, so the card that says "no plan yet" and the list below it can never disagree.
   */
  describe('Weekly plans', () => {
    /** MH1: a plan is a grade, a week and an image — no title and no body on the wire at all. */
    const PLAN = {
      id: 'b-g1',
      kind: 'weekly_plan',
      weekStart: '2026-09-27',
      grade: 1,
      curriculum: 'british',
      authorName: 'Huda Salem',
      authorRole: 'MANAGERIAL',
      attachment: { id: 'att-1', name: 'g1.png', type: 'image/png', url: 'https://api.example/x' },
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
      {
        grade: 3,
        classes: [
          {
            classId: 'c-2',
            className: '3A British',
            grade: 3,
            curriculum: 'british',
            subject: 'math',
            teacherId: 't-2',
            teacherName: 'Mona Adel',
            childrenCount: 22,
            todayLessonId: null,
            todayStatus: 'none',
          },
        ],
      },
    ];

    /** A PNG of a known type and size, without allocating megabytes to prove the cap. */
    function pick(type = 'image/png', size = 2048): File {
      const file = new File(['plan'], 'plan.png', { type });
      Object.defineProperty(file, 'size', { value: size });
      const input = document.querySelector('input[type="file"]') as HTMLInputElement;
      Object.defineProperty(input, 'files', { value: [file], configurable: true });
      input.dispatchEvent(new Event('change', { bubbles: true }));
      return file;
    }

    async function settle() {
      await Promise.resolve();
      TestBed.tick();
      await Promise.resolve();
      TestBed.tick();
    }

    /** The two `weekly-plans` reads the screen opens with, url and params, for the tests to assert. */
    let weeklyReads: string[] = [];

    async function openScreen(weeks: unknown[]) {
      await renderHq(WeeklyPlansPage, {
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
      backend.expectOne('/me').flush(MANAGERIAL_USER);
      await settle();
      // `*hqCan` on the footer's action reads `GET /me/permissions`, so the list has to land or every
      // "Add plan" on the screen is missing — the cards' own actions carry the same key.
      backend
        .match('/me/permissions')
        .forEach((request) => request.flush({ permissions: ['management.broadcast'] }));
      backend.match('/management/me').forEach((request) => request.flush(ME));
      backend.match('/management/classes').forEach((request) => request.flush(CLASSES));
      backend.match('/management/teachers').forEach((request) => request.flush([]));
      await settle();
      // Two reads: the five weeks she can post for (the cards and the replace warning) and the
      // window (the archive). The cards may never be built from the filtered one — see below.
      const reads = backend.match((request) => request.url === '/management/weekly-plans');
      expect(reads.length).toBe(2);
      weeklyReads = reads.map((request) => request.request.urlWithParams);
      reads.forEach((request) => request.flush({ from: '2026-07-12', to: '2026-09-27', weeks }));
      await settle();
      return backend;
    }

    /**
     * MH2 item 4: a card per grade she manages, **and no all-grades card** — the server refuses a
     * plan with no grade, so a card offering one was a card that answered a red band.
     */
    it('shows a card per grade with the posted image, and no all-grades card', async () => {
      const backend = await openScreen([{ weekStart: '2026-09-27', items: [{ plan: PLAN, readBy: 12 }] }]);

      const grades = [...document.querySelectorAll('.wp__grade')].map((node) => node.textContent?.trim());
      expect(grades).toEqual(['Grade 1', 'Grade 3']);
      expect(document.body.textContent).not.toContain('All grades');

      // Grade 1 has this week's plan, so its card offers a replacement; grade 3 has none.
      expect(screen.getByRole('button', { name: 'Replace plan' })).toBeTruthy();
      expect(document.body.textContent).toContain('No plan yet');

      // The picture is fetched with the bearer, never pointed at the DTO's absolute url.
      const bytes = backend.match((request) => request.url === '/media/attachments/att-1');
      expect(bytes.length).toBeGreaterThan(0);
      const alt = (document.querySelector('.wp__thumb') as HTMLImageElement).getAttribute('alt');
      expect(alt).toContain('Weekly plan');
      expect(alt).toContain('Grade 1');
      bytes.forEach((request) => request.flush(new Blob(['x'], { type: 'image/png' })));
      await settle();
      // And the archive still answers `readBy`, which is hers alone.
      expect(document.body.textContent).toContain('Read by 12');
    });

    /**
     * The review's first blocker, as a test: the cards come from **this week's own unfiltered
     * read**, so no filter she sets on the archive can make a posted plan look missing — and
     * "Add plan" on a card is never an offer to replace a plan the screen could not see.
     */
    it('keeps this week’s cards when the archive is filtered to a grade and a past week', async () => {
      const backend = await openScreen([{ weekStart: '2026-09-27', items: [{ plan: PLAN, readBy: 3 }] }]);
      expect(screen.getByRole('button', { name: 'Replace plan' })).toBeTruthy();

      // A grade and an end date that exclude grade 1's plan from the archive entirely.
      const grade = document.querySelectorAll('select')[0] as HTMLSelectElement;
      grade.value = '3';
      grade.dispatchEvent(new Event('change', { bubbles: true }));
      const to = document.querySelectorAll('input[type="date"]')[1] as HTMLInputElement;
      to.value = '2026-09-13';
      to.dispatchEvent(new Event('input', { bubbles: true }));
      await settle();

      // The archive asks again, with her filter, and answers nothing for that window.
      const refetch = backend.match((request) => request.url === '/management/weekly-plans');
      expect(refetch.length).toBeGreaterThan(0);
      refetch.forEach((request) => {
        expect(request.request.params.get('grade')).toBe('3');
        request.flush({ weeks: [] });
      });
      await settle();

      // The card still offers Replace rather than Add, and the archive says it found nothing.
      expect(screen.getByRole('button', { name: 'Replace plan' })).toBeTruthy();
      expect(document.body.textContent).toContain('No weekly plan in this window');

      // And the filter still offers every grade she manages, not only the one she chose.
      expect([...grade.options].map((option) => option.textContent?.trim())).toEqual([
        'Every grade',
        'Grade 1',
        'Grade 3',
      ]);
    });

    it('refuses a file the upload route would refuse, without uploading it', async () => {
      const backend = await openScreen([]);
      screen.getAllByRole('button', { name: 'Add plan' })[0]!.click();
      await settle();

      pick('application/pdf');
      await settle();
      expect(document.body.textContent).toContain('JPEG, a PNG or a WebP');
      expect(screen.getByRole('button', { name: 'Post' }).hasAttribute('disabled')).toBe(true);

      pick('image/png', 6 * 1024 * 1024);
      await settle();
      expect(document.body.textContent).toContain('over 5 MB');
      // Neither of them left the browser: the cap is checked before the twenty-second upload.
      expect(backend.match('/media/attachments')).toEqual([]);
    });

    /**
     * MH2 item 4, the round trip: upload the image, then post the plan with the id it answered.
     * Two requests in that order, because `attachmentId` is required and only the upload knows it.
     */
    it('uploads the image and posts the grade, the week and the attachment id', async () => {
      const backend = await openScreen([]);

      // The grade-1 card's "Add plan" prefills the week and the grade the card is for.
      screen.getAllByRole('button', { name: 'Add plan' })[0]!.click();
      await settle();
      // No title and no body on this sheet at all — a plan has neither.
      expect(document.querySelector('textarea')).toBeNull();

      pick();
      await settle();
      screen.getByRole('button', { name: 'Post' }).click();
      await settle();

      const upload = backend.expectOne('/media/attachments');
      expect(upload.request.method).toBe('POST');
      expect(upload.request.body instanceof FormData).toBe(true);
      upload.flush({ id: 'att-9', name: 'plan.png', type: 'image/png', sizeBytes: 2048 });
      await settle();

      const posted = backend.expectOne('/management/broadcasts');
      const sent = posted.request.body as CreateBroadcastRequest;
      expect(sent).toEqual({
        kind: 'weekly_plan',
        weekStart: sent.weekStart,
        grade: 1,
        attachmentId: 'att-9',
      });
      expect(sent.weekStart).toBeTruthy();
      posted.flush({ ...PLAN, id: 'b-new' });
      await settle();
      backend
        .match((request) => request.url === '/management/weekly-plans')
        .forEach((request) => request.flush({ weeks: [] }));
    });

    /** Posting over a grade's existing plan deletes it, its read marks and its bell rows. */
    it('confirms a replacement with a red band before it posts', async () => {
      await openScreen([{ weekStart: '2026-09-27', items: [{ plan: PLAN, readBy: 3 }] }]);
      screen.getByRole('button', { name: 'Replace plan' }).click();
      await settle();

      expect(document.body.textContent).toContain('This replaces the plan that is already there');
      expect(screen.getAllByRole('button', { name: 'Replace plan' }).length).toBeGreaterThan(1);
    });

    /**
     * Review blocker 2: the week select offers five Sundays, and the confirm used to know only about
     * this one. She posts next week's plan on Thursday and a corrected image on Friday — the second
     * one deletes the first, its read marks and its bell rows, so it has to say so.
     */
    it('asks for every week the select offers, and confirms a replacement in a future one', async () => {
      const nextWeek = '2026-10-04';
      await openScreen([
        { weekStart: nextWeek, items: [{ plan: { ...PLAN, id: 'b-next', weekStart: nextWeek } }] },
      ]);

      // The cards' own read spans the five Sundays the select offers, not only this one.
      const cards = weeklyReads.find((url) => !url.includes('2026-07-12'));
      expect(cards).toContain('from=2026-09-27');
      expect(cards).toContain('to=2026-10-25');

      // This week has no plan of its own, so grade 1's card offers a first one — the wider read
      // must not draw next week's plan on it.
      expect(screen.queryByRole('button', { name: 'Replace plan' })).toBeNull();
      screen.getAllByRole('button', { name: 'Add plan' })[0]!.click();
      await settle();

      // Picking next week turns the sheet into a replacement, wording and red band together.
      const week = document.querySelectorAll('hq-dialog select')[1] as HTMLSelectElement;
      week.value = nextWeek;
      week.dispatchEvent(new Event('change', { bubbles: true }));
      await settle();

      expect(document.body.textContent).toContain('This replaces the plan that is already there');
      expect(screen.queryByRole('button', { name: 'Post' })).toBeNull();
    });
  });

  /**
   * MG2b item 5: the same screen, two reads. MG1 gave her `GET /management/usage` — the same
   * `SchoolUsage` shape scoped to her department — so a manager reads that one and everybody else
   * who holds `usage.school` keeps the whole school's.
   */
  describe('usage, by who is reading', () => {
    it('reads /management/usage for a manager and says the numbers are her department’s', async () => {
      const rendered = await renderHq(SchoolUsagePage, {
        providers: [
          provideHttpClient(),
          provideHttpClientTesting(),
          provideRouter([{ path: '**', children: [] }]),
          { provide: BASE_PATH, useValue: '' },
        ],
      });
      const backend = TestBed.inject(HttpTestingController);
      TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
      TestBed.inject(AuthService).loadMe().subscribe();
      backend.expectOne('/me').flush(MANAGERIAL_USER);
      await Promise.resolve();
      TestBed.tick();

      expect(backend.match((request) => request.url === '/school/usage')).toEqual([]);
      backend.expectOne((request) => request.url === '/management/usage').flush(USAGE);
      await Promise.resolve();
      TestBed.tick();

      expect(document.body.textContent).toContain('Your department');
      expect(document.body.textContent).not.toContain('The whole school');
      expect(document.body.textContent).toContain('Sara Al Harbi');
      rendered.fixture.destroy();
    });
  });
});
