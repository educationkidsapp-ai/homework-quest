import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { screen, within } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { type EvaluationBoard, BASE_PATH } from '../../api';
import { renderHq } from '../../../testing/render';
import { EvaluationBoardPage } from './evaluation-board.page';

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

/** The board itself: counts only, no rows — one request per teacher the leader opens. */
const BOARD: EvaluationBoard = {
  round: ROUND,
  rounds: [ROUND, OLDER],
  scope: 'SCHOOL',
  counts: { onTime: 2, late: 1, missing: 1, returned: 0, approved: 1 },
  rows: [
    {
      teacherId: 't-amy',
      fullName: 'Amy American',
      counts: { onTime: 2, late: 0, missing: 0, returned: 0, approved: 1 },
      rows: [],
    },
    {
      teacherId: 't-tala',
      fullName: 'تالا المعلمة',
      counts: { onTime: 0, late: 1, missing: 1, returned: 0, approved: 0 },
      rows: [],
    },
  ],
};

/** `GET /evaluations/board/{teacherId}` answers the same board with one teacher's rows filled. */
const AMY: EvaluationBoard = {
  ...BOARD,
  rows: [
    {
      teacherId: 't-amy',
      fullName: 'Amy American',
      counts: { onTime: 2, late: 0, missing: 0, returned: 0, approved: 1 },
      rows: [
        {
          evaluationId: 'e-1',
          roundId: 'r-1',
          classId: 'c-1',
          className: '1A British',
          subject: 'math',
          childId: 'ch-1',
          childName: 'Amal Ahmed',
          status: 'ON_TIME',
          state: 'SUBMITTED',
          participation: 4,
          effort: 3,
          behaviour: 4,
          understanding: 3,
          comment: 'Joins in every lesson',
          goingWell: 'Mental maths',
          nextStep: 'Word problems',
          submittedAt: '2026-11-01T08:00:00Z',
        },
        {
          evaluationId: 'e-2',
          roundId: 'r-1',
          classId: 'c-1',
          className: '1A British',
          subject: 'math',
          childId: 'ch-2',
          childName: 'Bader Omar',
          status: 'APPROVED',
          state: 'APPROVED',
          participation: 3,
          effort: 3,
          behaviour: 3,
          understanding: 3,
          comment: 'Steady',
          reviewedByName: 'Lina Lead',
          reviewedAt: '2026-11-02T07:00:00Z',
        },
      ],
    },
  ],
};

async function settle(rendered: { fixture: { detectChanges: () => void } }): Promise<void> {
  await Promise.resolve();
  TestBed.tick();
  rendered.fixture.detectChanges();
}

async function renderBoard(board: EvaluationBoard = BOARD) {
  const rendered = await renderHq(EvaluationBoardPage, { providers });
  const backend = TestBed.inject(HttpTestingController);
  TestBed.tick();
  backend.expectOne('/evaluations/board').flush(board);
  await settle(rendered);
  return { rendered, backend };
}

/** Opens Amy and answers her own request, which is where the rows come from. */
async function openAmy(
  rendered: { fixture: { detectChanges: () => void } },
  backend: HttpTestingController,
) {
  screen.getByRole('button', { name: 'Amy American' }).click();
  await settle(rendered);
  backend.expectOne('/evaluations/board/t-amy').flush(AMY);
  await settle(rendered);
}

describe('feature 33 — the evaluation board', () => {
  beforeEach(() => localStorage.clear());

  /** One row per teacher, the one with something missing first, with the round's own totals. */
  it('shows a row per teacher with her counts, and the totals of the round', async () => {
    await renderBoard();

    const teachers = screen.getAllByRole('button', { name: /Amy American|تالا المعلمة/ });
    expect(teachers.map((button) => button.textContent?.trim())).toEqual(['تالا المعلمة', 'Amy American']);
    expect(document.querySelector('[data-hq-count="missing"]')?.textContent).toContain('1');
    expect(document.querySelector('[data-hq-due]')?.textContent).toContain('Term 1 — mid-term');
    expect(document.querySelector('[data-hq-due]')?.textContent).toContain('Asia/Riyadh');
    // Her children are one request away, not on the page.
    expect(document.querySelector('[data-hq-teacher-rows]')).toBeNull();
  });

  it('opens a teacher onto her children, asking for her rows once', async () => {
    const { rendered, backend } = await renderBoard();

    await openAmy(rendered, backend);

    expect(document.querySelector('[data-hq-teacher-rows="t-amy"]')).not.toBeNull();
    expect(screen.getByText('Amal Ahmed')).toBeInTheDocument();
    expect(screen.getByText(/Approved by Lina Lead/)).toBeInTheDocument();
    // Only the submitted one is still hers to decide on; the approved one is decided.
    expect(document.querySelectorAll('[data-hq-approve]')).toHaveLength(1);
    expect(document.querySelectorAll('[data-hq-return]')).toHaveLength(1);

    // Closing and opening again reads nothing: the rows are kept while the round stands.
    screen.getByRole('button', { name: 'Amy American' }).click();
    await settle(rendered);
    screen.getByRole('button', { name: 'Amy American' }).click();
    await settle(rendered);
    backend.expectNone('/evaluations/board/t-amy');
  });

  /** Approving shows what she is approving: the four ratings and the three texts. */
  it('approves one evaluation, with the whole of it on screen first', async () => {
    const { rendered, backend } = await renderBoard();
    await openAmy(rendered, backend);

    document.querySelector<HTMLElement>('[data-hq-approve] button')!.click();
    await settle(rendered);
    const summary = document.querySelector('[data-hq-summary]');
    expect(summary?.textContent).toContain('Joins in every lesson');
    expect(summary?.textContent).toContain('Word problems');

    within(screen.getByRole('dialog')).getByRole('button', { name: 'Approve' }).click();
    await settle(rendered);

    const post = backend.expectOne('/evaluations/e-1/review');
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({ decision: 'APPROVE' });
    post.flush({ ...AMY.rows![0]!.rows![0]!, status: 'APPROVED', state: 'APPROVED' });
    await settle(rendered);
    // Both are read again: the counts and the statuses are the server's to compute.
    backend.expectOne('/evaluations/board/t-amy').flush(AMY);
    backend.expectOne('/evaluations/board').flush(BOARD);
  });

  /** "Fix it" with nothing to fix is not a review, so the return waits for the comment. */
  it('will not send an evaluation back without a comment', async () => {
    const { rendered, backend } = await renderBoard();
    await openAmy(rendered, backend);

    document.querySelector<HTMLElement>('[data-hq-return] button')!.click();
    await settle(rendered);
    const dialog = within(screen.getByRole('dialog'));
    expect(dialog.getByRole('button', { name: 'Send back' })).toBeDisabled();

    await userEvent.type(dialog.getByLabelText(/^Comment/), 'Say what you will try next');
    await settle(rendered);
    dialog.getByRole('button', { name: 'Send back' }).click();
    await settle(rendered);

    const post = backend.expectOne('/evaluations/e-1/review');
    expect(post.request.body).toEqual({ decision: 'RETURN', comment: 'Say what you will try next' });
  });

  /** 409: a decision is taken once, and the second one is refused. Said in her own words. */
  it('says plainly when somebody decided on the evaluation first', async () => {
    const { rendered, backend } = await renderBoard();
    await openAmy(rendered, backend);

    document.querySelector<HTMLElement>('[data-hq-approve] button')!.click();
    await settle(rendered);
    within(screen.getByRole('dialog')).getByRole('button', { name: 'Approve' }).click();
    await settle(rendered);

    backend
      .expectOne('/evaluations/e-1/review')
      .flush({ code: 'already_reviewed', message: 'Already decided.' }, { status: 409, statusText: 'Conflict' });
    await settle(rendered);

    expect(document.body.textContent).toContain('Somebody has already decided on this evaluation');
  });

  it('downloads the same counts as a CSV, with the byte-order mark Excel needs', async () => {
    const { rendered, backend } = await renderBoard();
    const created: Blob[] = [];
    const createObjectURL = URL.createObjectURL;
    URL.createObjectURL = vi.fn((blob: Blob) => {
      created.push(blob);
      return 'blob:csv';
    });
    const revokeObjectURL = URL.revokeObjectURL;
    URL.revokeObjectURL = vi.fn();

    document.querySelector<HTMLElement>('[data-hq-export-csv] button')!.click();
    await settle(rendered);
    backend.expectOne('/evaluations/board.csv').flush('teacher,missing\nAmy,0\n');
    await settle(rendered);

    expect(created).toHaveLength(1);
    // Read as bytes, not as text: `Blob.text()` is a UTF-8 decode, which eats the very mark
    // this asserts is there.
    const bytes = new Uint8Array(await created[0]!.arrayBuffer());
    expect([...bytes.slice(0, 3)]).toEqual([0xef, 0xbb, 0xbf]);
    expect(new TextDecoder('utf-8', { ignoreBOM: true }).decode(bytes).slice(1)).toBe(
      'teacher,missing\nAmy,0\n',
    );

    URL.createObjectURL = createObjectURL;
    URL.revokeObjectURL = revokeObjectURL;
  });

  it('asks for the round the picker names', async () => {
    const { rendered, backend } = await renderBoard();

    await userEvent.selectOptions(screen.getByLabelText('Round'), 'r-0');
    await settle(rendered);

    backend
      .expectOne('/evaluations/board?round=r-0')
      .flush({ ...BOARD, round: OLDER, rows: [] });
    await settle(rendered);
    expect(document.body.textContent).toContain('No teachers to show for this round');
  });

  /** A teacher or coordinator nobody reports to: the empty state says so, not "no rows". */
  it('tells a leader who leads nobody that nobody reports to her', async () => {
    await renderBoard({ ...BOARD, scope: 'REPORTS', rows: [], counts: {} });

    expect(document.querySelector('[data-hq-leads-nobody]')).not.toBeNull();
    expect(document.body.textContent).toContain('Nobody reports to you yet');
  });

  /** No round at all is a different sentence from "no teachers in this round". */
  it('says so when the school has opened no round', async () => {
    await renderBoard({ rounds: [], rows: [] });

    expect(document.querySelector('[data-hq-no-rounds]')).not.toBeNull();
    expect(document.body.textContent).toContain('No evaluation round has been opened yet');
  });
});
