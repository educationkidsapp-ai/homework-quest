import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { type MaterialBoard, BASE_PATH } from '../../api';
import { renderHq } from '../../../testing/render';
import { MaterialBoardPage } from './material-board.page';

const providers: (Provider | EnvironmentProviders)[] = [
  provideHttpClient(),
  provideHttpClientTesting(),
  provideRouter([{ path: '**', children: [] }]),
  { provide: BASE_PATH, useValue: '' },
];

const BOARD: MaterialBoard = {
  week: '2026-10-18',
  dueAt: '2026-10-15T09:00:00Z',
  timeZone: 'Asia/Riyadh',
  scope: 'SCHOOL',
  counts: { onTime: 2, late: 1, missing: 1, reviewed: 1 },
  rows: [
    {
      teacherId: 't-amy',
      fullName: 'Amy American',
      counts: { onTime: 2, late: 0, missing: 0, reviewed: 1 },
      rows: [
        {
          submissionId: 's-1',
          classId: 'c-1',
          className: '1A British',
          subject: 'math',
          status: 'ON_TIME',
          attachmentId: 'a-1',
          attachmentName: 'week-1a.pdf',
          submittedAt: '2026-10-14T08:00:00Z',
        },
        {
          submissionId: 's-2',
          classId: 'c-2',
          className: '2A British',
          subject: 'english',
          status: 'REVIEWED',
          reviewedByName: 'Lina Lead',
          reviewedAt: '2026-10-16T07:00:00Z',
          reviewComment: 'Good',
        },
      ],
    },
    {
      teacherId: 't-tala',
      fullName: 'تالا المعلمة',
      counts: { onTime: 0, late: 1, missing: 1, reviewed: 0 },
      rows: [
        { submissionId: 's-3', classId: 'c-3', className: '3A American', subject: 'science', status: 'LATE' },
        { classId: 'c-4', className: '4A American', subject: 'arabic', status: 'MISSING' },
      ],
    },
  ],
};

async function settle(rendered: { fixture: { detectChanges: () => void } }): Promise<void> {
  await Promise.resolve();
  TestBed.tick();
  rendered.fixture.detectChanges();
}

async function renderBoard(board: MaterialBoard = BOARD) {
  const rendered = await renderHq(MaterialBoardPage, { providers });
  const backend = TestBed.inject(HttpTestingController);
  TestBed.tick();
  backend.expectOne('/materials/board').flush(board);
  await settle(rendered);
  return { rendered, backend };
}

describe('feature 31 — the teaching material board', () => {
  beforeEach(() => localStorage.clear());

  /** One row per teacher, the one with something missing first, with the page's own totals. */
  it('shows a row per teacher with her counts, and the totals of the week', async () => {
    await renderBoard();

    const teachers = screen.getAllByRole('button', { name: /Amy American|تالا المعلمة/ });
    expect(teachers.map((button) => button.textContent?.trim())).toEqual(['تالا المعلمة', 'Amy American']);
    expect(document.querySelector('[data-hq-count="missing"]')?.textContent).toContain('1');
    expect(document.querySelector('[data-hq-due]')?.textContent).toContain('Asia/Riyadh');
    // Her classes are one tap away, not on the page.
    expect(document.querySelector('[data-hq-teacher-rows]')).toBeNull();
  });

  it('opens a teacher onto her classes and subjects', async () => {
    const { rendered } = await renderBoard();

    screen.getByRole('button', { name: 'Amy American' }).click();
    await settle(rendered);

    expect(document.querySelector('[data-hq-teacher-rows="t-amy"]')).not.toBeNull();
    expect(screen.getByText('1A British')).toBeInTheDocument();
    expect(screen.getByText(/Reviewed by Lina Lead/)).toBeInTheDocument();
    // A reviewed row offers no second review, and one with nothing handed in offers none either.
    expect(document.querySelectorAll('[data-hq-review]')).toHaveLength(1);
  });

  it('marks one row reviewed with a comment', async () => {
    const { rendered, backend } = await renderBoard();

    screen.getByRole('button', { name: 'Amy American' }).click();
    await settle(rendered);
    document.querySelector<HTMLElement>('[data-hq-review] button')!.click();
    await settle(rendered);
    await userEvent.type(screen.getByLabelText(/^Comment/), 'Clear and complete');
    await settle(rendered);
    screen.getAllByRole('button', { name: 'Mark reviewed' }).at(-1)!.click();
    await settle(rendered);

    const post = backend.expectOne('/materials/s-1/review');
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({ comment: 'Clear and complete' });
    post.flush({ ...BOARD.rows![0]!.rows![0]!, status: 'REVIEWED' });
    await settle(rendered);
    // Read again: the counts and the statuses are the server's to compute.
    backend.expectOne('/materials/board').flush(BOARD);
  });

  /** 409: a review is a decision, and the second one is refused. Said in her own words. */
  it('says plainly when somebody reviewed the row first', async () => {
    const { rendered, backend } = await renderBoard();

    screen.getByRole('button', { name: 'Amy American' }).click();
    await settle(rendered);
    document.querySelector<HTMLElement>('[data-hq-review] button')!.click();
    await settle(rendered);
    screen.getAllByRole('button', { name: 'Mark reviewed' }).at(-1)!.click();
    await settle(rendered);

    backend
      .expectOne('/materials/s-1/review')
      .flush({ code: 'conflict', message: 'Already reviewed.' }, { status: 409, statusText: 'Conflict' });
    await settle(rendered);

    expect(document.body.textContent).toContain('Somebody has already reviewed this row');
  });

  it('downloads the same rows as a CSV, with the byte-order mark Excel needs', async () => {
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
    backend.expectOne('/materials/board.csv').flush('teacher,class\nAmy,1A\n');
    await settle(rendered);

    expect(created).toHaveLength(1);
    // Read as bytes, not as text: `Blob.text()` is a UTF-8 decode, which eats the very mark
    // this asserts is there.
    const bytes = new Uint8Array(await created[0]!.arrayBuffer());
    expect([...bytes.slice(0, 3)]).toEqual([0xef, 0xbb, 0xbf]);
    expect(new TextDecoder('utf-8', { ignoreBOM: true }).decode(bytes).slice(1)).toBe(
      'teacher,class\nAmy,1A\n',
    );

    URL.createObjectURL = createObjectURL;
    URL.revokeObjectURL = revokeObjectURL;
  });

  it('walks to the week before and asks the server for it', async () => {
    const { rendered, backend } = await renderBoard();

    document.querySelector<HTMLElement>('[data-hq-prev-week] button')!.click();
    await settle(rendered);

    backend.expectOne('/materials/board?week=2026-10-11').flush({ ...BOARD, week: '2026-10-11' });
    await settle(rendered);
    expect(document.querySelector('[data-hq-due]')?.textContent).toContain('Oct 11');
  });

  /** A teacher or coordinator nobody reports to: the empty state says so, not "no rows". */
  it('tells a leader who leads nobody that nobody reports to her', async () => {
    await renderBoard({ ...BOARD, scope: 'REPORTS', rows: [], counts: {} });

    expect(document.querySelector('[data-hq-leads-nobody]')).not.toBeNull();
    expect(document.body.textContent).toContain('Nobody reports to you yet');
  });
});
