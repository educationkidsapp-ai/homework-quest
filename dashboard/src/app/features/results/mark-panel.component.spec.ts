import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { type RenderResult, screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { BASE_PATH } from '../../api';
import { TEACHER_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { FlagService } from '../../core/flags/flag.service';
import { MarkPanelComponent } from './mark-panel.component';
import type { ResultRow } from './results.models';

const PERMISSIONS = {
  role: 'TEACHER',
  permissions: ['teacher.week', 'results.read', 'results.write'],
  readOnly: false,
};

/** One child with one open stop — the shape a row opens onto. */
function row(childId = 'ch-1', overrides: Partial<ResultRow> = {}): ResultRow {
  return {
    childId,
    name: childId === 'ch-1' ? 'Amina Al Amin' : 'Zain Lutfi',
    attempted: true,
    levelReached: 1,
    autoScore: 100,
    teacherScore: null,
    score: 100,
    band: 'exceeding',
    comment: '',
    needsMarking: 1,
    stops: [
      {
        stopId: 's-retell',
        title: 'Tell the story back',
        type: 'retell',
        open: true,
        attempted: true,
        stars: null,
        markStars: null,
        markComment: '',
        score: null,
        needsMarking: true,
        workUrl: null,
      },
    ],
    ...overrides,
  } as ResultRow;
}

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

async function answer(backend: HttpTestingController, replies: Record<string, object>): Promise<void> {
  for (let round = 0; round < 6; round += 1) {
    for (const [url, body] of Object.entries(replies)) {
      for (const request of backend.match((candidate) => candidate.url === url)) request.flush(body);
    }
    await settle();
  }
}

/**
 * A new `row` on the same instance — what a resource reload does to this panel.
 *
 * `setInput` rather than testing-library's `rerender`: `rerender` builds a *second* component
 * into the same document, which is the one thing this test must not do (two panels, two drafts,
 * and the assertion would pass on the fresh one whatever the old one did).
 */
function handDown(rendered: RenderResult<MarkPanelComponent>, next: ResultRow): void {
  rendered.fixture.componentRef.setInput('row', next);
  rendered.fixture.detectChanges();
}

async function renderPanel(): Promise<RenderResult<MarkPanelComponent>> {
  const rendered = await renderHq(MarkPanelComponent, {
    providers,
    inputs: { lessonId: 'l-1', row: row() },
  });
  const backend = TestBed.inject(HttpTestingController);

  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  TestBed.inject(FlagService).flags();
  await answer(backend, {
    '/me': TEACHER_USER,
    '/me/permissions': PERMISSIONS,
    '/schools/school-a/flags': { gradebook: true, openStopMarking: true },
  });
  return rendered;
}

describe('the marking panel', () => {
  it('keeps what she has typed when a reload hands down a new row for the same child', async () => {
    const rendered = await renderPanel();
    const comment = screen.getByLabelText(/Comment for the parent/);
    await userEvent.type(comment, 'Much better this week');
    await settle();

    // What a reload looks like from in here: the same child, a different object, and — as a
    // release or a re-opening on the exam page would — different numbers beside her.
    handDown(rendered, row('ch-1', { score: 90 }));
    await settle();

    expect(screen.getByLabelText(/Comment for the parent/)).toHaveValue('Much better this week');
    // Still dirty, so the Save she was walking towards is still offered.
    expect(screen.getByRole('button', { name: 'Save marks' })).toBeEnabled();
  });

  it('starts again when the panel is pointed at a different child', async () => {
    const rendered = await renderPanel();
    await userEvent.type(screen.getByLabelText(/Comment for the parent/), 'Much better this week');
    await settle();

    handDown(rendered, row('ch-2'));
    await settle();

    expect(screen.getByLabelText(/Comment for the parent/)).toHaveValue('');
    expect(screen.getByRole('button', { name: 'Save marks' })).toBeDisabled();
  });
});
