import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';
import { type RenderResult, screen } from '@testing-library/angular';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { COORDINATOR_USER, TEACHER_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { BASE_PATH } from '../../api';
import { AuthService } from '../../core/auth/auth.service';
import { FlagService } from '../../core/flags/flag.service';
import { SessionStore } from '../../core/auth/session.store';
import { jobLabel } from './staff-contacts';
import { StaffManagerPage } from './staff-manager.page';

/**
 * T1's `StaffContact` for a department manager: the two the owner named, Lina and Nour, with the
 * phone and the address her list asked for.
 */
const LINA = {
  userId: 'u-lina',
  displayName: 'Lina Haddad',
  email: 'lina@school.test',
  phone: '+201000000001',
  role: 'MANAGERIAL',
  curriculum: 'american',
  jobParts: [{ kind: 'manager', curriculum: 'american' }],
};

describe('StaffManagerPage', () => {
  let http: HttpTestingController;
  let navigate: ReturnType<typeof vi.spyOn>;
  let transloco: TranslocoService;

  /**
   * Renders the screen as `user`, with the `chat` flag on — the screen carries it, so with the
   * stub answering `false` there would be nothing on the page to assert about.
   */
  async function setUp(
    user: typeof TEACHER_USER,
    screenId: 'manager' | 'coordinators' = 'manager',
  ): Promise<RenderResult<StaffManagerPage>> {
    localStorage.clear();
    const rendered = await renderHq(StaffManagerPage, {
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: BASE_PATH, useValue: '' },
        { provide: FlagService, useValue: { isOn: () => true } },
        // Which directory this is — `area.routes.ts` puts the row's id on the route.
        { provide: ActivatedRoute, useValue: { snapshot: { data: { screenId } } } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    transloco = TestBed.inject(TranslocoService);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    TestBed.inject(SessionStore).set({ token: 'access-1' });
    TestBed.inject(AuthService).loadMe().subscribe();
    http.expectOne('/me').flush(user);
    // `StaffAreaService.ready` is what the read waits for, so the directory request is only
    // issued once `/me` has said which namespace this is.
    await Promise.resolve();
    TestBed.tick();
    return rendered;
  }

  it('shows the teacher her department manager, with a tel: and a mailto:', async () => {
    await setUp(TEACHER_USER);

    http.expectOne('/teacher/managers').flush([LINA]);
    expect(await screen.findByText('Lina Haddad')).toBeTruthy();
    expect(screen.getByText('American department manager')).toBeTruthy();
    expect(screen.getByRole('link', { name: '+201000000001' }).getAttribute('href')).toBe(
      'tel:+201000000001',
    );
    expect(screen.getByRole('link', { name: 'lina@school.test' }).getAttribute('href')).toBe(
      'mailto:lina@school.test',
    );
  });

  it("opens the teacher's staff thread and shows it on her own Messages screen", async () => {
    await setUp(TEACHER_USER);
    http.expectOne('/teacher/managers').flush([LINA]);

    (await screen.findByRole('button', { name: 'Message' })).click();

    const opened = http.expectOne('/teacher/chat/staff-threads');
    expect(opened.request.body).toEqual({ managerUserId: 'u-lina' });
    opened.flush({ id: 'th-7' });

    // `/teacher/chat` kept C1's path; the coordinator's is `/coordinator/messages`.
    expect(navigate).toHaveBeenCalledWith(['/teacher/chat'], { queryParams: { thread: 'th-7' } });
  });

  it('reads the coordinator her own namespace and her own thread route', async () => {
    await setUp(COORDINATOR_USER);
    http.expectOne('/coordinator/managers').flush([LINA]);

    (await screen.findByRole('button', { name: 'Message' })).click();
    http.expectOne('/coordinator/chat/threads').flush({ id: 'th-8' });

    expect(navigate).toHaveBeenCalledWith(['/coordinator/messages'], {
      queryParams: { thread: 'th-8' },
    });
  });

  it('says so, and offers nothing to press, when the department has no manager', async () => {
    await setUp(TEACHER_USER);
    http.expectOne('/teacher/managers').flush([]);

    expect(await screen.findByText('No department manager yet')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Message' })).toBeNull();
  });

  /**
   * The owner's two examples, and the one that has to keep working before T1 lands: a row with no
   * `jobParts` at all still reads as the department manager she is.
   */
  describe('the job label', () => {
    beforeEach(async () => {
      await setUp(TEACHER_USER);
      http.expectOne('/teacher/managers').flush([]);
    });

    it('names a manager by her department', () => {
      expect(jobLabel(transloco, LINA)).toEqual(['American department manager']);
    });

    it('lists a coordinator by grade, subject and track', () => {
      const nour = {
        userId: 'u-nour',
        displayName: 'Nour Fahmy',
        jobParts: [{ kind: 'coordinator', grades: [1], subject: 'arabic', curriculum: 'british' }],
      };
      expect(jobLabel(transloco, nour)).toEqual(['Coordinator · Grade 1 · Arabic · British']);
    });

    it('falls back to the row curriculum when the server sends no job parts', () => {
      expect(jobLabel(transloco, { userId: 'u-x', curriculum: 'british' })).toEqual([
        'British department manager',
      ]);
    });
  });

  /**
   * T2 item (a)'s other half, which needed T1's `GET /teacher/coordinators`. The same component: the
   * route's `screenId` is the only thing that differs, which is the whole reason it is one screen.
   */
  describe("the teacher's coordinators", () => {
    const NOUR = {
      userId: 'u-nour',
      displayName: 'Nour Fahmy',
      email: 'nour@school.test',
      phone: '+201000000002',
      role: 'COORDINATOR',
      jobParts: [
        { kind: 'coordinator', grades: [1, 2], subject: 'arabic', curriculum: 'british' },
        { kind: 'coordinator', grades: [1], subject: 'arabic', curriculum: 'american' },
      ],
    };

    it('reads her coordinators and gives each post its own line', async () => {
      await setUp(TEACHER_USER, 'coordinators');
      http.expectOne('/teacher/coordinators').flush([NOUR]);

      expect(await screen.findByText('Nour Fahmy')).toBeTruthy();
      // One line per `jobParts` entry: T1 keeps one per track and never flattens grades across
      // them, because "Grades 1, 2 · British and American" would claim four posts for two.
      expect(screen.getByText('Coordinator · Grades 1, 2 · Arabic · British')).toBeTruthy();
      expect(screen.getByText('Coordinator · Grade 1 · Arabic · American')).toBeTruthy();
    });

    it('opens the staff thread with coordinatorUserId, and never both ids', async () => {
      await setUp(TEACHER_USER, 'coordinators');
      http.expectOne('/teacher/coordinators').flush([NOUR]);

      (await screen.findByRole('button', { name: 'Message' })).click();

      const opened = http.expectOne('/teacher/chat/staff-threads');
      // `OpenStaffThreadRequest` takes one or the other; T1 refuses both or neither.
      expect(opened.request.body).toEqual({ coordinatorUserId: 'u-nour' });
      opened.flush({ id: 'th-4' });

      expect(navigate).toHaveBeenCalledWith(['/teacher/chat'], { queryParams: { thread: 'th-4' } });
    });

    it('says so when no coordinator covers a subject she teaches', async () => {
      await setUp(TEACHER_USER, 'coordinators');
      http.expectOne('/teacher/coordinators').flush([]);

      expect(await screen.findByText('No coordinator for your subjects yet')).toBeTruthy();
      expect(screen.queryByRole('button', { name: 'Message' })).toBeNull();
    });
  });
});
