import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { fireEvent, screen, waitFor, within } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { BASE_PATH } from '../../api';
import { ADMIN_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { LessonsPage } from './lessons.page';

const providers: (Provider | EnvironmentProviders)[] = [
  provideHttpClient(),
  provideHttpClientTesting(),
  provideRouter([]),
  { provide: BASE_PATH, useValue: '' },
];

const ADMIN_PERMISSIONS = {
  role: 'ADMIN',
  permissions: ['lesson.read', 'lesson.write', 'lesson.delete', 'calendar.read'],
  readOnly: false,
};

const FAILED_LESSON = {
  id: 'l-1',
  course: { curriculum: 'british', grade: 1 },
  subject: 'math',
  date: '2026-09-10',
  status: 'error',
  title: 'Adding to ten',
  currentStep: 'generate_L1',
  schoolId: 'school-a',
  schoolName: 'Al Noor School',
};

const isLessonsRequest = (req: { url: string; method: string }): boolean =>
  req.url.startsWith('/admin/lessons') && req.method === 'GET';

/** Signs an Admin in and flushes what every render asks for on the way to the chooser. */
async function renderSignedIn() {
  const rendered = await renderHq(LessonsPage, { providers });
  const backend = TestBed.inject(HttpTestingController);

  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(ADMIN_USER);
  TestBed.tick();
  backend.expectOne('/me/permissions').flush(ADMIN_PERMISSIONS);

  return { rendered, backend };
}

describe('Lessons', () => {
  beforeEach(() => localStorage.clear());

  it('remembers the curriculum and grade this Admin chose, per user', async () => {
    const { rendered, backend } = await renderSignedIn();

    fireEvent.change(screen.getByLabelText('Curriculum'), { target: { value: 'british' } });
    rendered.fixture.detectChanges();
    fireEvent.change(screen.getByLabelText('Grade'), { target: { value: '1' } });
    rendered.fixture.detectChanges();

    backend.expectOne(isLessonsRequest).flush([]);
    await Promise.resolve();

    expect(localStorage.getItem('hq.course.u-admin')).toBe(
      JSON.stringify({ curriculum: 'british', grade: 1 }),
    );
  });

  it("restores last time's choice on the next visit and fetches its lessons without asking again", async () => {
    localStorage.setItem('hq.course.u-admin', JSON.stringify({ curriculum: 'british', grade: 1 }));
    const { backend } = await renderSignedIn();

    // No selection made — the chooser read the stored course and the list fetched itself.
    backend.expectOne(isLessonsRequest).flush([FAILED_LESSON]);
    await Promise.resolve();

    expect(await screen.findByText('Adding to ten')).toBeInTheDocument();
  });

  it('retries a failed lesson optimistically, and rolls the status back if the retry itself fails', async () => {
    localStorage.setItem('hq.course.u-admin', JSON.stringify({ curriculum: 'british', grade: 1 }));
    const { backend } = await renderSignedIn();
    backend.expectOne(isLessonsRequest).flush([FAILED_LESSON]);
    await Promise.resolve();

    expect(await screen.findByText('Failed')).toBeInTheDocument();
    expect(screen.getByText('Error at: generate L1')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));

    // Optimistic: the row already reads "Uploading" before the server has answered.
    expect(screen.getByText('Uploading')).toBeInTheDocument();
    expect(screen.queryByText('Failed')).not.toBeInTheDocument();

    backend.expectOne((req) => req.url === '/admin/lessons/l-1/retry' && req.method === 'POST').flush(
      null,
      { status: 500, statusText: 'Server Error' },
    );
    await Promise.resolve();

    expect(await screen.findByText('Failed')).toBeInTheDocument();
    expect(screen.queryByText('Uploading')).not.toBeInTheDocument();
  });

  it('confirms with a red band before deleting a lesson, and removes it only once the server agrees', async () => {
    localStorage.setItem('hq.course.u-admin', JSON.stringify({ curriculum: 'british', grade: 1 }));
    const { backend } = await renderSignedIn();
    backend.expectOne(isLessonsRequest).flush([FAILED_LESSON]);
    await Promise.resolve();
    await screen.findByText('Adding to ten');

    expect(screen.queryByText('Delete this lesson?')).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Actions for Adding to ten' }));
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Delete' }));

    const band = screen.getByRole('alert');
    expect(within(band).getByText('Delete this lesson?')).toBeInTheDocument();
    expect(within(band).getByText('This removes Adding to ten and cannot be undone.')).toBeInTheDocument();

    await userEvent.click(within(band).getByRole('button', { name: 'Delete' }));

    backend.expectOne((req) => req.url === '/admin/lessons/l-1' && req.method === 'DELETE').flush(null);
    await waitFor(() => expect(screen.queryByText('Adding to ten')).not.toBeInTheDocument());
    expect(screen.queryByText('Delete this lesson?')).not.toBeInTheDocument();
  });
});
