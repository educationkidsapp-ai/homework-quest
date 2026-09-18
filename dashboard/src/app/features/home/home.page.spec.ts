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

/** Renders the Home for one role against the exact shape `GET /me/home` answers with. */
async function renderHome(user: DashboardUserFixture, home: HomeResponse) {
  const rendered = await renderHq(HomePage, { providers });
  const backend = TestBed.inject(HttpTestingController);

  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(user);
  TestBed.tick();

  backend.expectOne('/me/home').flush(home);
  // Anything else the shell's services ask for is not this screen's business. Cancelled ones are
  // skipped: `PlatformService` re-reads `/platform-settings` as the account arrives (the school's
  // timezone overrides the platform's), which supersedes the anonymous request still in flight.
  for (const request of backend.match(() => true)) if (!request.cancelled) request.flush({});
  await Promise.resolve();
  rendered.fixture.detectChanges();
  return rendered;
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
