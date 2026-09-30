import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import { TranslocoService } from '@jsverse/transloco';
import { beforeEach, describe, expect, it } from 'vitest';
import type { HomeResponse } from '../../api';
import { BASE_PATH } from '../../api';
import {
  ADMIN_HOME,
  ADMIN_USER,
  MANAGERIAL_HOME,
  MANAGERIAL_USER,
  TEACHER_HOME,
  TEACHER_USER,
  type DashboardUserFixture,
} from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { HomePage } from './home.page';

const providers: (Provider | EnvironmentProviders)[] = [
  provideHttpClient(),
  provideHttpClientTesting(),
  provideRouter([]),
  { provide: BASE_PATH, useValue: '' },
];

/** MA0: her quick actions are filtered by these, so the Home's spec has to answer them. */
const ADMIN_PERMISSIONS = {
  role: 'ADMIN',
  permissions: ['section.read', 'teacher.read', 'lesson.write'],
  readOnly: false,
};

/** Renders the Home for one role against the exact shape `GET /me/home` answers with. */
async function renderHome(
  user: DashboardUserFixture,
  home: HomeResponse,
  permissions: { role: string; permissions: readonly string[]; readOnly: boolean } = ADMIN_PERMISSIONS,
) {
  const rendered = await renderHq(HomePage, { providers });
  const backend = TestBed.inject(HttpTestingController);

  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(user);
  TestBed.tick();
  backend.expectOne('/me/permissions').flush(permissions);

  backend.expectOne('/me/home').flush(home);
  // Anything else the shell's services ask for is not this screen's business. Cancelled ones are
  // skipped: `PlatformService` re-reads `/platform-settings` as the account arrives (the school's
  // timezone overrides the platform's), which supersedes the anonymous request still in flight.
  for (const request of backend.match(() => true)) if (!request.cancelled) request.flush({});
  await Promise.resolve();
  rendered.fixture.detectChanges();
  return rendered;
}

/** The stat cards that are links: their label → their href. */
function cardLinks(rendered: { container: Element }): Map<string, string> {
  return new Map(
    [...rendered.container.querySelectorAll<HTMLAnchorElement>('a[data-hq-card-link]')].map((link) => [
      link.querySelector('.em-stat-label')?.textContent?.trim() ?? '',
      link.getAttribute('href') ?? '',
    ]),
  );
}

describe('Home', () => {
  beforeEach(() => localStorage.clear());

  it("greets an Admin and counts the platform's three numbers", async () => {
    await renderHome(ADMIN_USER, ADMIN_HOME);

    expect(screen.getByRole('heading', { name: 'Hello, Platform Admin' })).toBeInTheDocument();
    expect(screen.getByText('Schools')).toBeInTheDocument();
    expect(screen.getByText('Children')).toBeInTheDocument();
    expect(screen.getByText('Lessons this week')).toBeInTheDocument();
  });

  /**
   * MA2 (the owner's admin list item 1): `GET /me/home` answers her eight counts and six of them
   * are rail rows of their own, so each of the six is the door to the screen it counts. `schools`
   * needs `multiSchool` to have a screen at all and `lessonsThisWeek` stands for no one list, so
   * both are drawn flat — a card that looks clickable and is not is worse than one that plainly
   * is not.
   */
  it('makes each of her six people counts a link to the screen behind it', async () => {
    const rendered = await renderHome(
      ADMIN_USER,
      {
        ...ADMIN_HOME,
        cards: [
          { key: 'managers', value: 2 },
          { key: 'coordinators', value: 4 },
          { key: 'teachers', value: 18 },
          { key: 'children', value: 148 },
          { key: 'classes', value: 12 },
          { key: 'workers', value: 6 },
          { key: 'lessonsThisWeek', value: 9 },
        ],
      },
      {
        role: 'ADMIN',
        permissions: [
          'manager.manage',
          'coordinator.manage',
          'teacher.read',
          'admin.children.read',
          'section.read',
          'worker.read',
        ],
        readOnly: false,
      },
    );

    // By the cards' own anchors rather than by their words: "Teachers" and "Classes" are also
    // quick actions on the same screen, and `getByText` would find either.
    expect(cardLinks(rendered)).toEqual(
      new Map([
        ['Managers', '/admin/managers'],
        ['Coordinators', '/admin/coordinators'],
        ['Teachers', '/admin/teachers'],
        ['Children', '/admin/children'],
        ['Classes', '/admin/classes'],
        ['Workers', '/admin/workers'],
      ]),
    );
  });

  /** A card whose screen the router would refuse is a card, not a link onto a guard. */
  it('draws a count flat when the account lacks the key its screen is gated by', async () => {
    const rendered = await renderHome(
      ADMIN_USER,
      { ...ADMIN_HOME, cards: [{ key: 'workers', value: 6 }] },
      { role: 'ADMIN', permissions: ['section.read'], readOnly: false },
    );

    expect(screen.getByText('Workers')).toBeInTheDocument();
    expect(cardLinks(rendered).size).toBe(0);
  });

  /**
   * MA0: the panel used to offer an Admin six of the teacher's links, and it is now the only door
   * to the new-lesson wizard — All lessons, whose primary button was the other one, left her rail.
   */
  it('offers an Admin her own quick actions, and the wizard among them', async () => {
    await renderHome(ADMIN_USER, ADMIN_HOME);

    expect(screen.getByRole('link', { name: 'New lesson' })).toHaveAttribute('href', '/admin/lessons/new');
    expect(screen.getByRole('link', { name: 'Classes' })).toHaveAttribute('href', '/admin/classes');
    expect(screen.getByRole('link', { name: 'Teachers' })).toHaveAttribute('href', '/admin/teachers');
    // None of the teacher's six, which is what she used to be shown.
    for (const teachers of ['This Week', 'My Classes', 'Create Exam', 'Parent Chat'])
      expect(screen.queryByRole('link', { name: teachers })).not.toBeInTheDocument();
  });

  /** A read-only "View as" session is offered no write: `lesson.write` is one. */
  it('offers no New lesson while the session is read-only', async () => {
    await renderHome(ADMIN_USER, ADMIN_HOME, {
      role: 'ADMIN',
      permissions: ['section.read', 'teacher.read', 'lesson.write'],
      readOnly: true,
    });

    expect(screen.queryByRole('link', { name: 'New lesson' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Classes' })).toBeInTheDocument();
  });

  it('turns a needs-you row into a sentence from its kind and params', async () => {
    await renderHome(ADMIN_USER, ADMIN_HOME);

    expect(screen.getByText('Adding to ten failed to build')).toBeInTheDocument();
    expect(screen.getByText('Green Valley has no teacher yet')).toBeInTheDocument();
  });

  it('drops a row whose kind this build has no string for, rather than printing the id', async () => {
    await renderHome(ADMIN_USER, ADMIN_HOME);

    expect(screen.queryByText(/complaint\.breachedSla/)).not.toBeInTheDocument();
    expect(screen.queryByText(/home\.needs/)).not.toBeInTheDocument();
  });

  it("shows a teacher's classes and offers today's lesson only where one is missing", async () => {
    await renderHome(TEACHER_USER, TEACHER_HOME);

    expect(screen.getByRole('heading', { name: 'Hello, Ms Sara' })).toBeInTheDocument();
    expect(screen.getByText('British · Grade 1 · Math')).toBeInTheDocument();
    expect(screen.getByText('British · Grade 2 · Math')).toBeInTheDocument();

    // One class has today's lesson, the other does not — so exactly one invitation to add it.
    const links = screen.getAllByRole('link', { name: "Add today's lesson" });
    expect(links).toHaveLength(1);
    expect(links[0]?.getAttribute('href')).toContain('classId=c-1');
    expect(links[0]?.getAttribute('href')).toContain('subject=math');
  });

  it("names a teacher's weakest skills in words, never a percentage", async () => {
    await renderHome(TEACHER_USER, TEACHER_HOME);

    expect(screen.getByText('Number bonds to ten')).toBeInTheDocument();
    expect(screen.getByText('Needs another look')).toBeInTheDocument();
  });

  it('draws no class section for a role that has none — null is not an empty list', async () => {
    await renderHome(MANAGERIAL_USER, MANAGERIAL_HOME);

    expect(screen.getByText('Active families')).toBeInTheDocument();
    expect(screen.getByText('Mr Omar has published nothing for 7 days')).toBeInTheDocument();
    expect(screen.queryByText('Your classes')).not.toBeInTheDocument();
    expect(screen.queryByText('Weakest skills')).not.toBeInTheDocument();
  });

  it('re-renders in Arabic without asking the server again', async () => {
    const { fixture } = await renderHome(TEACHER_USER, TEACHER_HOME);
    const backend = TestBed.inject(HttpTestingController);

    TestBed.inject(TranslocoService).setActiveLang('ar');
    fixture.detectChanges();

    expect(screen.getByRole('heading', { name: 'مرحبًا، Ms Sara' })).toBeInTheDocument();
    expect(screen.getByText('بانتظار المراجعة')).toBeInTheDocument();
    // The server sent ids and values, not sentences: switching language costs no request.
    backend.expectNone('/me/home');
  });
});
