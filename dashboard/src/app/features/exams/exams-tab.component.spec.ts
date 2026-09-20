import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { screen, within } from '@testing-library/angular';
import { describe, expect, it } from 'vitest';
import { BASE_PATH, type ExamResults, type ExamSettings } from '../../api';
import { TEACHER_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { FlagService } from '../../core/flags/flag.service';
import { ExamsTabComponent } from './exams-tab.component';

const PERMISSIONS = {
  role: 'TEACHER',
  permissions: ['teacher.week', 'lesson.read', 'lesson.write', 'results.read', 'results.write'],
  readOnly: false,
};

const HOUR = 60 * 60 * 1000;

/** Three exams of one class: one she is still writing, one on Sunday, one already sat. */
function exams(now: number): ExamSettings[] {
  return [
    {
      examId: 'e-open',
      title: 'Mid-term',
      classId: 'c-1a',
      className: '1A British',
      opensAt: now - HOUR,
      closesAt: now + HOUR,
      level: 'mixed',
      releaseMode: 'manual',
      status: 'published',
      released: false,
    },
    {
      examId: 'e-next',
      title: 'Unit 5',
      classId: 'c-1a',
      opensAt: now + 48 * HOUR,
      closesAt: now + 49 * HOUR,
      level: '2',
      releaseMode: 'auto_on_close',
      status: 'published',
      released: false,
    },
    {
      examId: 'e-draft',
      title: 'Unit 6',
      classId: 'c-1a',
      opensAt: now + 96 * HOUR,
      closesAt: now + 97 * HOUR,
      level: '1',
      releaseMode: 'auto_on_close',
      status: 'review',
      released: false,
    },
  ];
}

const RESULTS: ExamResults = { examId: 'e-open', roster: 24, sat: 18, needsMarking: 3 };

const providers: (Provider | EnvironmentProviders)[] = [
  provideHttpClient(),
  provideHttpClientTesting(),
  provideRouter([]),
  { provide: BASE_PATH, useValue: '' },
];

async function settle(): Promise<void> {
  await Promise.resolve();
  TestBed.tick();
  await Promise.resolve();
  TestBed.tick();
}

/**
 * Answers whatever the screen has asked for, in whatever order it asked.
 *
 * Not a fixed script of `expectOne`s: the tab's `*hqCan` sits *inside* its `*hqFeature`, so
 * `/me/permissions` is not requested until the flags have arrived and the directive is
 * instantiated — an ordering a hard-coded sequence gets wrong, and one that is correct (a screen
 * whose controls are behind a flag should not ask what the account may do until it knows the
 * school has the feature).
 */
async function answer(
  backend: HttpTestingController,
  replies: Record<string, object | unknown[]>,
): Promise<void> {
  for (let round = 0; round < 6; round += 1) {
    for (const [url, body] of Object.entries(replies)) {
      for (const request of backend.match((candidate) => candidate.url === url)) request.flush(body);
    }
    await settle();
  }
}

async function renderTab(flags: Record<string, boolean> = { exams: true }, results = RESULTS) {
  const now = Date.now();
  await renderHq(ExamsTabComponent, {
    providers,
    inputs: { classId: 'c-1a', className: '1A British' },
  });
  const backend = TestBed.inject(HttpTestingController);

  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  TestBed.inject(FlagService).flags();

  await answer(backend, {
    '/me': TEACHER_USER,
    '/me/permissions': PERMISSIONS,
    '/schools/school-a/flags': flags,
    '/platform-settings': { timezone: 'UTC' },
    '/teacher/classes/c-1a/exams': exams(now),
    '/teacher/exams/e-open/results': results,
  });
  return backend;
}

function rowOf(title: string) {
  const row = screen.getAllByRole('row').find((candidate) => candidate.textContent?.includes(title));
  return within(row!);
}

describe('the Exams tab', () => {
  it('reads each exam’s state off the clock, not off the row’s `open`', async () => {
    await renderTab();

    expect(rowOf('Mid-term').getByText('Open now')).toBeInTheDocument();
    expect(rowOf('Unit 5').getByText('Scheduled')).toBeInTheDocument();
    // Published is what separates a draft from a scheduled exam, not the window.
    expect(rowOf('Unit 6').getByText('Draft')).toBeInTheDocument();
  });

  it('pays for the sat count only on the exams that have opened', async () => {
    const backend = await renderTab();

    // The scheduled and the draft rows have nobody in them, so nothing was asked about them.
    backend.expectNone('/teacher/exams/e-next/results');
    backend.expectNone('/teacher/exams/e-draft/results');
    expect(rowOf('Mid-term').getByText('18 of 24')).toBeInTheDocument();
    expect(rowOf('Mid-term').getByText('3 to mark')).toBeInTheDocument();
  });

  it('offers New exam to a teacher who may write one', async () => {
    await renderTab();

    const link = screen.getByRole('link', { name: 'New exam' });
    expect(link.getAttribute('href')).toContain('/teacher/exams/new');
    expect(link.getAttribute('href')).toContain('classId=c-1a');
  });

  it('hides New exam when the school does not have exams, though the tab still lists them', async () => {
    await renderTab({ exams: false });

    expect(screen.queryByRole('link', { name: 'New exam' })).toBeNull();
  });

  it('says so plainly when the class has no exam yet', async () => {
    await renderHq(ExamsTabComponent, {
      providers,
      inputs: { classId: 'c-1a', className: '1A British' },
    });
    const backend = TestBed.inject(HttpTestingController);
    await answer(backend, {
      '/platform-settings': { timezone: 'UTC' },
      '/teacher/classes/c-1a/exams': [],
    });

    expect(screen.getByText('No exam yet.')).toBeInTheDocument();
  });
});
