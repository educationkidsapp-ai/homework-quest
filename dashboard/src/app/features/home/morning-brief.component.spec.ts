import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { screen } from '@testing-library/angular';
import { TranslocoService } from '@jsverse/transloco';
import { beforeEach, describe, expect, it } from 'vitest';
import { type MorningBrief, BASE_PATH } from '../../api';
import { ADMIN_USER, type DashboardUserFixture } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { MorningBriefComponent } from './morning-brief.component';

const providers: (Provider | EnvironmentProviders)[] = [
  provideHttpClient(),
  provideHttpClientTesting(),
  { provide: BASE_PATH, useValue: '' },
];

const DAY = '2026-10-16';
const COLLAPSED_KEY = 'hq.brief.collapsed';

const WAITING_EN = 'Three requests are waiting for your decision.';
const WAITING_AR = 'ثلاثة طلبات تنتظر قرارك.';

const BRIEF: MorningBrief = {
  day: DAY,
  source: 'ai',
  linesEn: ['Two people arrived late yesterday and one did not check out.', WAITING_EN],
  linesAr: ['تأخر شخصان أمس ولم يسجّل أحدهم الخروج.', WAITING_AR],
  facts: {
    scope: 'SCHOOL',
    yesterday: '2026-10-15',
    lateYesterday: 2,
    absentYesterday: 0,
    missingCheckoutYesterday: 1,
    requestsWaiting: 3,
    overdueComplaints: 0,
    eventsToday: 0,
    remoteStudyDayToday: false,
  },
};

async function settle(rendered: { fixture: { detectChanges: () => void } }): Promise<void> {
  await Promise.resolve();
  TestBed.tick();
  rendered.fixture.detectChanges();
}

/**
 * Renders the card and answers `GET /me/morning-brief` with `answer` — a brief, `null` for the
 * 204 the server sends on a day the school does not teach, or `'error'` for a request that failed.
 */
async function renderBrief(
  answer: MorningBrief | null | 'error',
  user: DashboardUserFixture = ADMIN_USER,
) {
  const rendered = await renderHq(MorningBriefComponent, { providers });
  const backend = TestBed.inject(HttpTestingController);

  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(user);
  TestBed.tick();

  for (const request of backend.match('/me/morning-brief')) {
    if (request.cancelled) continue;
    if (answer === 'error') request.flush({ code: 'server_error' }, { status: 500, statusText: 'Boom' });
    // A 204 reaches the client as a `null` body, which is what "no brief today" looks like here.
    else if (answer === null) request.flush(null, { status: 204, statusText: 'No Content' });
    else request.flush(answer);
  }
  // Whatever the shell's own services ask for is not this component's business.
  for (const request of backend.match(() => true)) if (!request.cancelled) request.flush({});
  await settle(rendered);
  return { rendered, backend };
}

function card(rendered: { container: Element }): Element | null {
  return rendered.container.querySelector('[data-hq-brief]');
}

function toggleOf(rendered: { container: Element }): HTMLButtonElement | null {
  return rendered.container.querySelector<HTMLButtonElement>('[data-hq-brief-toggle]');
}

describe('Feature 25 — the morning brief on Home', () => {
  beforeEach(() => localStorage.clear());

  it("greets her and prints the server's lines in the language on screen", async () => {
    const { rendered } = await renderBrief(BRIEF);

    expect(screen.getByText('Good morning')).toBeInTheDocument();
    expect(
      screen.getByText('Two people arrived late yesterday and one did not check out.'),
    ).toBeInTheDocument();
    expect(screen.getByText(WAITING_EN)).toBeInTheDocument();
    // The Arabic half of the same answer is not on screen in English.
    expect(screen.queryByText(WAITING_AR)).not.toBeInTheDocument();
    expect(card(rendered)).not.toBeNull();
  });

  /** One answer carries both languages, so a switch costs no second request. */
  it('switches to the Arabic lines without asking the server again', async () => {
    const { rendered, backend } = await renderBrief(BRIEF);

    TestBed.inject(TranslocoService).setActiveLang('ar');
    rendered.fixture.detectChanges();

    expect(screen.getByText('صباح الخير')).toBeInTheDocument();
    expect(screen.getByText(WAITING_AR)).toBeInTheDocument();
    expect(screen.queryByText(WAITING_EN)).not.toBeInTheDocument();
    backend.expectNone('/me/morning-brief');
  });

  it('discloses the assistant when it wrote the lines', async () => {
    const { rendered } = await renderBrief(BRIEF);

    expect(rendered.container.querySelector('[data-hq-brief-ai]')).not.toBeNull();
    expect(screen.getByText('Written by the assistant')).toBeInTheDocument();
  });

  /** The template's lines are the server's own sentences round its own numbers: nothing to say. */
  it('says nothing about the assistant on a template brief', async () => {
    const { rendered } = await renderBrief({ ...BRIEF, source: 'template' });

    expect(card(rendered)).not.toBeNull();
    expect(rendered.container.querySelector('[data-hq-brief-ai]')).toBeNull();
    expect(screen.queryByText('Written by the assistant')).not.toBeInTheDocument();
  });

  /** A day the school does not teach: 204, and nothing at all on Home. */
  it('draws nothing when there is no brief today', async () => {
    const { rendered } = await renderBrief(null);

    expect(card(rendered)).toBeNull();
    expect(screen.queryByText('Good morning')).not.toBeInTheDocument();
  });

  /**
   * She did not ask for the brief, so a brief that failed is not an error she has to read: no
   * card and no red band — the rest of Home is untouched.
   */
  it('draws nothing when the call failed', async () => {
    const { rendered } = await renderBrief('error');

    expect(card(rendered)).toBeNull();
    expect(screen.queryByText('Good morning')).not.toBeInTheDocument();
  });

  it('draws nothing when the answer carries no line in this language', async () => {
    const { rendered } = await renderBrief({ ...BRIEF, linesEn: ['', '   '] });

    expect(card(rendered)).toBeNull();
  });

  it('folds the lines away, leaves the greeting, and writes the day down', async () => {
    const { rendered } = await renderBrief(BRIEF);

    expect(toggleOf(rendered)?.getAttribute('aria-expanded')).toBe('true');
    expect(toggleOf(rendered)?.textContent?.trim()).toBe('Hide');

    toggleOf(rendered)?.click();
    await settle(rendered);

    expect(screen.queryByText(WAITING_EN)).not.toBeInTheDocument();
    expect(screen.getByText('Good morning')).toBeInTheDocument();
    expect(toggleOf(rendered)?.getAttribute('aria-expanded')).toBe('false');
    expect(localStorage.getItem(COLLAPSED_KEY)).toBe(DAY);
  });

  /** The same day in a new tab: still folded, and one tap opens it and forgets the fold. */
  it('opens folded on a day this browser folded, and unfolds in one tap', async () => {
    localStorage.setItem(COLLAPSED_KEY, DAY);
    const { rendered } = await renderBrief(BRIEF);

    expect(card(rendered)).not.toBeNull();
    expect(screen.queryByText(WAITING_EN)).not.toBeInTheDocument();
    expect(screen.getByText('Show')).toBeInTheDocument();

    toggleOf(rendered)?.click();
    await settle(rendered);

    expect(screen.getByText(WAITING_EN)).toBeInTheDocument();
    expect(localStorage.getItem(COLLAPSED_KEY)).toBeNull();
  });

  /** A fold belongs to the day it was made on: tomorrow's brief opens by itself. */
  it("opens again on the next day's brief", async () => {
    localStorage.setItem(COLLAPSED_KEY, '2026-10-15');
    const { rendered } = await renderBrief(BRIEF);

    expect(card(rendered)).not.toBeNull();
    expect(screen.getByText(WAITING_EN)).toBeInTheDocument();
  });

  /** Storage that throws is storage with nothing remembered, not a broken Home. */
  it('still draws the brief when localStorage cannot be read', async () => {
    const original = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      get() {
        throw new Error('denied');
      },
    });
    try {
      const { rendered } = await renderBrief(BRIEF);

      expect(card(rendered)).not.toBeNull();
      expect(screen.getByText(WAITING_EN)).toBeInTheDocument();

      toggleOf(rendered)?.click();
      await settle(rendered);
      // The fold still holds for this visit, with nothing written down.
      expect(screen.queryByText(WAITING_EN)).not.toBeInTheDocument();
    } finally {
      if (original !== undefined) Object.defineProperty(globalThis, 'localStorage', original);
    }
  });
});
