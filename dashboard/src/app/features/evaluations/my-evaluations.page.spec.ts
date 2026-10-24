import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { screen, within } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { type ChildEvaluationRow, type TeacherEvaluations, BASE_PATH } from '../../api';
import { renderHq } from '../../../testing/render';
import { MyEvaluationsPage } from './my-evaluations.page';

const providers: (Provider | EnvironmentProviders)[] = [
  provideHttpClient(),
  provideHttpClientTesting(),
  provideRouter([{ path: '**', children: [] }]),
  { provide: BASE_PATH, useValue: '' },
];

const ROUND = {
  id: 'r-1',
  nameEn: 'Term 1 — mid-term',
  nameAr: 'الفصل الأول — منتصف',
  opensOn: '2026-10-01',
  dueAt: '2026-11-05T18:00:00Z',
  remindHoursBefore: 48,
  timeZone: 'Asia/Riyadh',
  released: false,
};

const OLDER = { ...ROUND, id: 'r-0', nameEn: 'Term 1 — start', nameAr: 'الفصل الأول — البداية' };

function row(extra: Partial<ChildEvaluationRow> = {}): ChildEvaluationRow {
  return {
    roundId: 'r-1',
    classId: 'c-1',
    className: '1A British',
    subject: 'math',
    childId: 'ch-1',
    childName: 'Amal Ahmed',
    status: 'MISSING',
    ...extra,
  };
}

/** One child per state, so the page is asserted on all four at once. */
const ROWS: ChildEvaluationRow[] = [
  row(),
  row({
    childId: 'ch-2',
    childName: 'Bader Omar',
    status: 'ON_TIME',
    state: 'SUBMITTED',
    evaluationId: 'e-2',
    participation: 4,
    effort: 3,
    behaviour: 4,
    understanding: 3,
    comment: 'Joins in every lesson',
    submittedAt: '2026-11-01T08:00:00Z',
  }),
  row({
    childId: 'ch-3',
    childName: 'Carla Nabil',
    status: 'RETURNED',
    state: 'RETURNED',
    evaluationId: 'e-3',
    participation: 2,
    effort: 2,
    behaviour: 3,
    understanding: 2,
    comment: 'Quiet',
    reviewedByName: 'Lina Lead',
    reviewedAt: '2026-11-02T07:30:00Z',
    reviewComment: 'Say what you will try next',
  }),
  row({
    classId: 'c-2',
    className: '2A British',
    subject: 'english',
    childId: 'ch-4',
    childName: 'Dana Faris',
    status: 'APPROVED',
    state: 'APPROVED',
    evaluationId: 'e-4',
    participation: 4,
    effort: 4,
    behaviour: 4,
    understanding: 4,
    comment: 'A pleasure',
    reviewedByName: 'Lina Lead',
    reviewedAt: '2026-11-03T07:30:00Z',
  }),
];

const EVALUATIONS: TeacherEvaluations = {
  round: ROUND,
  rounds: [ROUND, OLDER],
  counts: { onTime: 1, late: 0, missing: 1, returned: 1, approved: 1 },
  rows: ROWS,
};

async function settle(rendered: { fixture: { detectChanges: () => void } }): Promise<void> {
  await Promise.resolve();
  TestBed.tick();
  rendered.fixture.detectChanges();
}

async function renderPage(evaluations: TeacherEvaluations = EVALUATIONS) {
  const rendered = await renderHq(MyEvaluationsPage, { providers });
  const backend = TestBed.inject(HttpTestingController);
  TestBed.tick();
  backend.expectOne('/teacher/evaluations').flush(evaluations);
  await settle(rendered);
  return { rendered, backend };
}

describe('feature 33 — my evaluations', () => {
  beforeEach(() => localStorage.clear());

  /** Her classes and subjects, then the children — with the ones still to write about first. */
  it('shows one section per class and subject, with a status chip per child', async () => {
    await renderPage();

    expect(document.querySelectorAll('[data-hq-group]')).toHaveLength(2);
    const statuses = [...document.querySelectorAll('.evaluations__status')].map((cell) =>
      cell.textContent?.trim(),
    );
    // Within 1A: the missing one, then the returned one, then the submitted one.
    expect(statuses).toEqual(['Not written', 'Sent back', 'On time', 'Approved']);
    // The round's name and its deadline, in the school's own zone.
    expect(document.querySelector('[data-hq-due]')?.textContent).toContain('Term 1 — mid-term');
    expect(document.querySelector('[data-hq-due]')?.textContent).toContain('Asia/Riyadh');
    expect(document.querySelector('[data-hq-count="missing"]')?.textContent).toContain('1');
  });

  /** A submitted or approved row is a decision taken: no button, and the review in its place. */
  it('leaves a submitted and an approved row read-only, and offers the returned one back', async () => {
    await renderPage();

    expect(document.querySelectorAll('[data-hq-locked]')).toHaveLength(2);
    expect(document.querySelectorAll('[data-hq-write]')).toHaveLength(2);
    expect(screen.getByRole('button', { name: 'Fix and send again' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Write' })).toBeInTheDocument();
    expect(screen.getByText(/Say what you will try next/)).toBeInTheDocument();
    expect(screen.getByText(/Approved by Lina Lead/)).toBeInTheDocument();
  });

  it('saves a draft without the four ratings, and leaves the blank fields out', async () => {
    const { rendered, backend } = await renderPage();

    screen.getByRole('button', { name: 'Write' }).click();
    await settle(rendered);
    const dialog = within(screen.getByRole('dialog'));
    await userEvent.selectOptions(dialog.getByLabelText('Participation'), '3');
    await userEvent.type(dialog.getByLabelText(/^Comment/), 'Settling in');
    await settle(rendered);
    document.querySelector<HTMLElement>('[data-hq-save-draft] button')!.click();
    await settle(rendered);

    const put = backend.expectOne('/teacher/evaluations/r-1/c-1/math/ch-1');
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual({ participation: 3, comment: 'Settling in' });
    put.flush(row({ state: 'DRAFT', evaluationId: 'e-1', participation: 3, comment: 'Settling in' }));
    await settle(rendered);
    // Read again rather than patched in place: the status and the counts are the server's.
    backend.expectOne('/teacher/evaluations').flush(EVALUATIONS);
  });

  /** All four and a comment, said on the primary action rather than learnt from a 400. */
  it('waits for all four ratings and a comment before it will submit', async () => {
    const { rendered, backend } = await renderPage();

    screen.getByRole('button', { name: 'Write' }).click();
    await settle(rendered);
    const dialog = within(screen.getByRole('dialog'));
    const submit = dialog.getByRole('button', { name: 'Submit' });
    expect(submit).toBeDisabled();

    for (const [label, value] of [
      ['Participation', '4'],
      ['Effort', '3'],
      ['Behaviour', '4'],
      ['Understanding', '2'],
    ] as const) {
      await userEvent.selectOptions(dialog.getByLabelText(label), value);
    }
    await settle(rendered);
    // Four ratings and no comment is still not a submission.
    expect(dialog.getByRole('button', { name: 'Submit' })).toBeDisabled();

    await userEvent.type(dialog.getByLabelText(/^Comment/), 'Tries hard every lesson');
    await userEvent.type(dialog.getByLabelText(/^Going well/), 'Reading aloud');
    await settle(rendered);
    dialog.getByRole('button', { name: 'Submit' }).click();
    await settle(rendered);

    const post = backend.expectOne('/teacher/evaluations/r-1/c-1/math/ch-1/submit');
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({
      participation: 4,
      effort: 3,
      behaviour: 4,
      understanding: 2,
      comment: 'Tries hard every lesson',
      goingWell: 'Reading aloud',
    });
  });

  /** The ratings are shown with their number: staff need the order of the four words. */
  it('names each point of the scale with its number', async () => {
    const { rendered } = await renderPage();

    screen.getByRole('button', { name: 'Write' }).click();
    await settle(rendered);
    const options = [...within(screen.getByRole('dialog')).getByLabelText('Effort').querySelectorAll('option')]
      .map((option) => option.textContent?.trim())
      .filter((label) => label !== 'Choose a rating');

    expect(options).toEqual(['Excellent (4)', 'Good (3)', 'Developing (2)', 'Needs support (1)']);
  });

  /** A returned row opens with the leader's comment above the form, not hidden in a column. */
  it('shows the leader’s comment on a returned evaluation', async () => {
    const { rendered } = await renderPage();

    screen.getByRole('button', { name: 'Fix and send again' }).click();
    await settle(rendered);

    const band = document.querySelector('[data-hq-returned]');
    expect(band).not.toBeNull();
    expect(band?.textContent).toContain('Say what you will try next');
    // What she already wrote is in the form, so "fix it" is an edit and not a retype.
    expect(within(screen.getByRole('dialog')).getByLabelText(/^Comment/)).toHaveValue('Quiet');
  });

  /** 409: a leader approved it while the dialog was open. Her own words, next to what failed. */
  it('says so when the evaluation was submitted before her save landed', async () => {
    const { rendered, backend } = await renderPage();

    screen.getByRole('button', { name: 'Write' }).click();
    await settle(rendered);
    document.querySelector<HTMLElement>('[data-hq-save-draft] button')!.click();
    await settle(rendered);

    backend.expectOne('/teacher/evaluations/r-1/c-1/math/ch-1').flush(
      { code: 'already_submitted', message: 'That evaluation has been submitted.' },
      { status: 409, statusText: 'Conflict' },
    );
    await settle(rendered);

    expect(document.body.textContent).toContain('ask your leader to send it back');
  });

  it('asks for the round the picker names', async () => {
    const { rendered, backend } = await renderPage();

    await userEvent.selectOptions(screen.getByLabelText('Round'), 'r-0');
    await settle(rendered);

    backend
      .expectOne('/teacher/evaluations?round=r-0')
      .flush({ ...EVALUATIONS, round: OLDER, rows: [] });
    await settle(rendered);
    expect(document.body.textContent).toContain('Nothing is expected from you in this round');
  });

  /** No round at all is a different sentence from "nothing expected of you". */
  it('says so when the school has opened no round', async () => {
    await renderPage({ rounds: [], rows: [] });

    expect(document.querySelector('[data-hq-no-rounds]')).not.toBeNull();
    expect(document.body.textContent).toContain('No evaluation round has been opened yet');
  });
});
