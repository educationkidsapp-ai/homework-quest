import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { BASE_PATH } from '../../api';
import { translocoTesting } from '../../../testing/render';
import { BandService } from '../../core/band/band.service';
import { errorInterceptor } from '../../core/http/error.interceptor';
import { ClassContextService } from '../../core/nav/class-context.service';
import { ClassPage } from './class.page';

@Component({ template: '<h1>My classes</h1>' })
class MyClassesStub {}

const TODAY = new Date().toISOString().slice(0, 10);

const CALENDAR = {
  classId: 'c-1a',
  className: '1A',
  subject: 'math',
  curriculum: 'british',
  grade: 1,
  days: [],
  gaps: 0,
};

const ROLL_CALL = {
  classId: 'c-1a',
  className: '1A',
  date: TODAY,
  students: [{ childId: 'ch-1', childName: 'Amina', status: 'NOT_MARKED', notes: null }],
  totalCount: 1,
  presentCount: 0,
  absentCount: 0,
  lateCount: 0,
  excusedCount: 0,
  attendanceRate: 0,
};

const isCalendar = (url: string) => /\/teacher\/classes\/[^/]+\/calendar/.test(url);
const isAttendance = (url: string) => /\/teacher\/classes\/[^/]+\/attendance/.test(url);

/**
 * D1 (the owner's list of 2026-10-01, TEACHER item 2): "moving from one tab to another reloads
 * the whole page". One class shell, five tabs: the header and the strip stay mounted, a tab's
 * data is fetched once for the visit, and the address still says which tab she is on.
 */
describe('the class page', () => {
  let backend: HttpTestingController;
  let router: Router;

  async function settle(harness: RouterTestingHarness): Promise<void> {
    // Not `whenStable`: a resource with a request in flight is, correctly, never stable.
    for (let turn = 0; turn < 3; turn++) {
      await new Promise((resolve) => setTimeout(resolve, 0));
      TestBed.tick();
      harness.detectChanges();
    }
  }

  async function open(url: string): Promise<RouterTestingHarness> {
    const harness = await RouterTestingHarness.create(url);
    await settle(harness);
    return harness;
  }

  /** Opens the class and answers the one request that proves it is hers. */
  async function openClass(url = '/teacher/classes/c-1a'): Promise<RouterTestingHarness> {
    const harness = await open(url);
    backend.expectOne((request) => isCalendar(request.url)).flush(CALENDAR);
    await settle(harness);
    return harness;
  }

  async function answerAttendance(harness: RouterTestingHarness): Promise<void> {
    backend.expectOne((request) => isAttendance(request.url)).flush(ROLL_CALL);
    await settle(harness);
  }

  const tab = (name: string) => screen.getByRole('tab', { name });

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    TestBed.configureTestingModule({
      imports: [translocoTesting()],
      providers: [
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        provideRouter([
          { path: 'teacher/classes/:classId', component: ClassPage },
          { path: 'teacher/classes', component: MyClassesStub },
          { path: 'sign-in', component: MyClassesStub },
        ]),
        { provide: BASE_PATH, useValue: '' },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
  });

  it('opens on the calendar, with the class in the header', async () => {
    await openClass();

    expect(screen.getByRole('heading', { level: 1, name: '1A · Math' })).toBeInTheDocument();
    expect(tab('Calendar')).toHaveAttribute('aria-selected', 'true');
    expect(TestBed.inject(ClassContextService).current()?.id).toBe('c-1a');
  });

  it('keeps the shell and does not refetch the class when she changes tab', async () => {
    const harness = await openClass();
    const shell = harness.routeDebugElement?.componentInstance as ClassPage;
    const heading = screen.getByRole('heading', { level: 1 });
    const strip = screen.getByRole('tablist');

    await userEvent.click(tab('Attendance'));
    await settle(harness);
    await answerAttendance(harness);

    // The address says where she is…
    expect(router.url).toBe('/teacher/classes/c-1a?tab=attendance');
    expect(tab('Attendance')).toHaveAttribute('aria-selected', 'true');
    // …and nothing above the tab body was rebuilt: the same component, the same DOM nodes.
    expect(harness.routeDebugElement?.componentInstance).toBe(shell);
    expect(screen.getByRole('heading', { level: 1 })).toBe(heading);
    expect(screen.getByRole('tablist')).toBe(strip);
    // The class was asked for once, on arrival, and not again for the tab.
    backend.expectNone((request) => isCalendar(request.url));
    // No full-page skeleton stands in for the shell between tabs.
    expect(screen.getByRole('heading', { level: 1, name: '1A · Math' })).toBeInTheDocument();
  });

  it('fetches a tab once for the visit: going back to it asks for nothing', async () => {
    const harness = await openClass();

    await userEvent.click(tab('Attendance'));
    await settle(harness);
    await answerAttendance(harness);

    await userEvent.click(tab('Calendar'));
    await settle(harness);
    await userEvent.click(tab('Attendance'));
    await settle(harness);

    expect(router.url).toBe('/teacher/classes/c-1a?tab=attendance');
    backend.expectNone((request) => isAttendance(request.url));
    backend.expectNone((request) => isCalendar(request.url));
    // Kept, not rebuilt: the roll call she already loaded is still on the page.
    expect(screen.getByText('Amina')).toBeInTheDocument();
  });

  it('opens a deep link on its tab, and the strip still works afterwards', async () => {
    const harness = await openClass('/teacher/classes/c-1a?tab=attendance');
    await answerAttendance(harness);

    expect(tab('Attendance')).toHaveAttribute('aria-selected', 'true');

    // The bug this replaced: with `?tab=` in the address the strip snapped back to it.
    await userEvent.click(tab('Calendar'));
    await settle(harness);
    expect(tab('Calendar')).toHaveAttribute('aria-selected', 'true');
    expect(router.url).toBe('/teacher/classes/c-1a?tab=calendar');
  });

  it('walks the tabs with Back, without leaving the class or refetching it', async () => {
    const harness = await openClass();
    const shell = harness.routeDebugElement?.componentInstance as ClassPage;

    await userEvent.click(tab('Attendance'));
    await settle(harness);
    await answerAttendance(harness);

    // What Back does: the previous address of the same route.
    await router.navigateByUrl('/teacher/classes/c-1a');
    await settle(harness);

    expect(tab('Calendar')).toHaveAttribute('aria-selected', 'true');
    expect(harness.routeDebugElement?.componentInstance).toBe(shell);
    backend.expectNone((request) => isCalendar(request.url));
  });

  it('keeps the other parameters of the address when the tab changes', async () => {
    const harness = await openClass('/teacher/classes/c-1a?subject=math');

    await userEvent.click(tab('Attendance'));
    await settle(harness);
    await answerAttendance(harness);

    expect(router.url).toBe('/teacher/classes/c-1a?subject=math&tab=attendance');
  });

  /**
   * "Errors on first open": the class in the address is a bookmark, a `returnTo` or a class of
   * the school as it was before QA was re-created. She did not click it, so it is not her error.
   */
  it('sends a class that is not hers back to My classes, with no red band', async () => {
    const harness = await open('/teacher/classes/old-school:1a?tab=attendance');

    // One quiet request — the tab she deep-linked to has not asked for anything yet.
    backend.expectNone((request) => isAttendance(request.url));
    backend
      .expectOne((request) => isCalendar(request.url))
      .flush({ code: 'not_found', message: 'class not found' }, { status: 404, statusText: 'Not Found' });
    await settle(harness);

    expect(router.url).toBe('/teacher/classes');
    expect(TestBed.inject(BandService).current()).toBeNull();
    expect(TestBed.inject(ClassContextService).current()).toBeNull();
  });

  /** The review's case: quiet about a 404 only — a dead session still goes to sign-in. */
  it('sends a session that died under the class page to sign in, with the way back', async () => {
    const harness = await open('/teacher/classes/c-1a?tab=attendance');

    backend
      .expectOne((request) => isCalendar(request.url))
      .flush({ code: 'unauthorized', message: 'no' }, { status: 401, statusText: 'Unauthorized' });
    await settle(harness);

    expect(router.url).toBe(`/sign-in?returnTo=${encodeURIComponent('/teacher/classes/c-1a?tab=attendance')}`);
    expect(TestBed.inject(BandService).current()?.titleKey).toBe('band.signedOut');
  });

  /**
   * Tabs are kept for the visit, so a write in one has to reach the kept copies of the tabs that
   * read it: they are dropped while hidden and built again, from the server, when next opened.
   */
  it('rebuilds the register after a roster change, and leaves it alone otherwise', async () => {
    const harness = await openClass();
    const shell = harness.routeDebugElement?.componentInstance as unknown as {
      onChanged: (source: string) => void;
    };

    await userEvent.click(tab('Attendance'));
    await settle(harness);
    await answerAttendance(harness);
    await userEvent.click(tab('Calendar'));
    await settle(harness);

    // What the Children tab's `changed` output calls when a child is added, moved or removed.
    shell.onChanged('children');
    await settle(harness);
    // The calendar does not read the roster: it is not asked for again.
    backend.expectNone((request) => isCalendar(request.url));

    await userEvent.click(tab('Attendance'));
    await settle(harness);
    // The register does: a fresh pane, a fresh read.
    await answerAttendance(harness);
  });

  it('reloads the calendar in place after a mark, and never drops the tab she is on', async () => {
    const harness = await openClass('/teacher/classes/c-1a?tab=attendance');
    await answerAttendance(harness);
    const shell = harness.routeDebugElement?.componentInstance as unknown as {
      onChanged: (source: string) => void;
    };

    // The Gradebook's `changed`: the calendar's results column is computed from marks.
    shell.onChanged('gradebook');
    await settle(harness);
    backend.expectOne((request) => isCalendar(request.url)).flush(CALENDAR);

    // Her own register saving is not a reason to rebuild the register under her.
    shell.onChanged('attendance');
    await settle(harness);
    backend.expectNone((request) => isAttendance(request.url));
    expect(screen.getByText('Amina')).toBeInTheDocument();
  });

  it('still says so when the class request fails for any other reason', async () => {
    const harness = await open('/teacher/classes/c-1a');

    backend
      .expectOne((request) => isCalendar(request.url))
      .flush({ code: 'boom', message: 'The calendar is unavailable.' }, { status: 500, statusText: 'x' });
    await settle(harness);

    expect(router.url).toBe('/teacher/classes/c-1a');
    expect(TestBed.inject(BandService).current()?.message).toBe('The calendar is unavailable.');
  });
});
