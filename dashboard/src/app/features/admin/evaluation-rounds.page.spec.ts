import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { screen, within } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { type EvaluationRound, BASE_PATH } from '../../api';
import { ADMIN_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { FlagService } from '../../core/flags/flag.service';
import { EvaluationRoundsPage } from './evaluation-rounds.page';

const providers: (Provider | EnvironmentProviders)[] = [
  provideHttpClient(),
  provideHttpClientTesting(),
  provideRouter([{ path: '**', children: [] }]),
  { provide: BASE_PATH, useValue: '' },
  // `childEvaluations` is on; the whole screen is behind it (`*hqFeature` and the route's guard).
  { provide: FlagService, useValue: { isOn: () => true, refresh: () => undefined, ready: () => true } },
];

const ADMIN_PERMISSIONS = { role: 'ADMIN', permissions: ['evaluations.rounds'], readOnly: false };

const OPEN_ROUND: EvaluationRound = {
  id: 'r-1',
  nameEn: 'Term 1 — mid-term',
  nameAr: 'الفصل الأول — منتصف',
  opensOn: '2026-10-01',
  dueAt: '2026-11-05T18:00:00Z',
  remindHoursBefore: 48,
  timeZone: 'Asia/Riyadh',
  released: false,
};

const RELEASED: EvaluationRound = {
  ...OPEN_ROUND,
  id: 'r-0',
  nameEn: 'Term 1 — start',
  nameAr: 'الفصل الأول — البداية',
  released: true,
  releasedAt: '2026-10-20T09:00:00Z',
  releasedByName: 'Nada Admin',
};

async function settle(rendered: { fixture: { detectChanges: () => void } }): Promise<void> {
  await Promise.resolve();
  TestBed.tick();
  rendered.fixture.detectChanges();
}

async function renderRounds(
  rounds: readonly EvaluationRound[] = [OPEN_ROUND, RELEASED],
  permissions = ADMIN_PERMISSIONS,
) {
  const rendered = await renderHq(EvaluationRoundsPage, { providers });
  const backend = TestBed.inject(HttpTestingController);

  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(ADMIN_USER);
  TestBed.tick();
  backend.expectOne('/me/permissions').flush(permissions);

  backend.expectOne('/admin/evaluation-rounds').flush(rounds);
  await settle(rendered);
  return { rendered, backend };
}

describe('feature 33 — evaluation rounds', () => {
  beforeEach(() => localStorage.clear());

  /** The deadline is the school's own clock, which is the only clock it means anything in. */
  it('shows each round with its deadline in the school zone and whether parents have it', async () => {
    await renderRounds();

    expect(screen.getByRole('cell', { name: 'Term 1 — mid-term' })).toBeInTheDocument();
    const rows = [...document.querySelectorAll('tbody tr')].map((row) => row.textContent ?? '');
    expect(rows[0]).toContain('Asia/Riyadh');
    // 18:00 UTC is 21:00 in Riyadh, and the table says the school's hour and not the browser's.
    expect(rows[0]).toContain('9:00');
    expect(rows[0]).toContain('48 h before');
    expect(rows[0]).toContain('Not released');
    expect(document.querySelector('[data-hq-released]')?.textContent).toContain('Nada Admin');
  });

  /** The name in both languages and a whole deadline, said on Save rather than learnt from a 400. */
  it('creates a round with both names and the deadline in the school’s own zone', async () => {
    const { rendered, backend } = await renderRounds([]);

    document.querySelector<HTMLElement>('[data-hq-create] button')!.click();
    await settle(rendered);
    const dialog = within(screen.getByRole('dialog'));
    expect(dialog.getByRole('button', { name: 'Save' })).toBeDisabled();

    await userEvent.type(dialog.getByLabelText('Name in English'), 'Term 2 — mid-term');
    await userEvent.type(dialog.getByLabelText('Name in Arabic'), 'الفصل الثاني');
    await userEvent.type(dialog.getByLabelText('Deadline date'), '2027-02-10');
    await userEvent.type(dialog.getByLabelText(/^Deadline time/), '21:00');
    await settle(rendered);
    dialog.getByRole('button', { name: 'Save' }).click();
    await settle(rendered);

    const post = backend.expectOne('/admin/evaluation-rounds');
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({
      nameEn: 'Term 2 — mid-term',
      nameAr: 'الفصل الثاني',
      // A local date and time, which the server reads in the school's zone — never an instant.
      dueAt: '2027-02-10T21:00',
      remindHoursBefore: 48,
    });
    post.flush({ ...OPEN_ROUND, id: 'r-2' });
    await settle(rendered);
    backend.expectOne('/admin/evaluation-rounds').flush([OPEN_ROUND]);
  });

  /** The form opens on the school's hour, not the browser's, or an edit would move the deadline. */
  it('edits an open round, with its deadline split into the school zone’s date and time', async () => {
    const { rendered, backend } = await renderRounds();

    document.querySelector<HTMLElement>('[data-hq-edit] button')!.click();
    await settle(rendered);
    const dialog = within(screen.getByRole('dialog'));
    expect(dialog.getByLabelText('Deadline date')).toHaveValue('2026-11-05');
    expect(dialog.getByLabelText(/^Deadline time/)).toHaveValue('21:00');

    await userEvent.clear(dialog.getByLabelText(/^Remind this many hours before/));
    await userEvent.type(dialog.getByLabelText(/^Remind this many hours before/), '24');
    await settle(rendered);
    dialog.getByRole('button', { name: 'Save' }).click();
    await settle(rendered);

    const put = backend.expectOne('/admin/evaluation-rounds/r-1');
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toMatchObject({ dueAt: '2026-11-05T21:00', remindHoursBefore: 24 });
  });

  /** A released round is editable nowhere: the server answers 409, so the table offers nothing. */
  it('offers no edit and no release on a round the parents already have', async () => {
    await renderRounds([RELEASED]);

    expect(document.querySelector('[data-hq-edit]')).toBeNull();
    expect(document.querySelector('[data-hq-release]')).toBeNull();
    expect(document.querySelector('[data-hq-locked]')?.textContent).toContain('no longer editable');
  });

  /** The one-way door: a red band that says what it does, and nothing sent until she confirms. */
  it('asks before it releases a round to the parents', async () => {
    const { rendered, backend } = await renderRounds([OPEN_ROUND]);

    document.querySelector<HTMLElement>('[data-hq-release] button')!.click();
    await settle(rendered);

    const band = document.querySelector('[data-hq-release-confirm]');
    expect(band?.textContent).toContain('Term 1 — mid-term');
    expect(band?.textContent).toContain('cannot be undone');
    backend.expectNone('/admin/evaluation-rounds/r-1/release');

    within(band as HTMLElement).getByRole('button', { name: 'Release to parents' }).click();
    await settle(rendered);

    const post = backend.expectOne('/admin/evaluation-rounds/r-1/release');
    expect(post.request.method).toBe('POST');
    post.flush({ ...OPEN_ROUND, released: true });
    await settle(rendered);
    backend.expectOne('/admin/evaluation-rounds').flush([{ ...OPEN_ROUND, released: true }]);
    expect(document.querySelector('[data-hq-release-confirm]')).toBeNull();
  });

  /** 409: somebody released it first. Her own words, in the band, not a raw server message. */
  it('says so when the round had already been released', async () => {
    const { rendered, backend } = await renderRounds([OPEN_ROUND]);

    document.querySelector<HTMLElement>('[data-hq-release] button')!.click();
    await settle(rendered);
    within(document.querySelector('[data-hq-release-confirm]') as HTMLElement)
      .getByRole('button', { name: 'Release to parents' })
      .click();
    await settle(rendered);

    backend
      .expectOne('/admin/evaluation-rounds/r-1/release')
      .flush({ code: 'already_released', message: 'Already released.' }, { status: 409, statusText: 'Conflict' });
    await settle(rendered);

    expect(document.body.textContent).toContain('already been released to the parents');
  });

  it('says what to do when the school has no round yet', async () => {
    await renderRounds([]);

    expect(document.body.textContent).toContain('No evaluation round yet');
  });
});
