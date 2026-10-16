import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { type MaterialRow, type TeacherMaterials, BASE_PATH } from '../../api';
import { renderHq } from '../../../testing/render';
import { MyMaterialsPage } from './my-materials.page';

const providers: (Provider | EnvironmentProviders)[] = [
  provideHttpClient(),
  provideHttpClientTesting(),
  provideRouter([{ path: '**', children: [] }]),
  { provide: BASE_PATH, useValue: '' },
];

function row(extra: Partial<MaterialRow> = {}): MaterialRow {
  return { classId: 'c-1', className: '1A British', subject: 'math', status: 'MISSING', ...extra };
}

/** One row per status the server computes, so the page is asserted on all four at once. */
const ROWS: MaterialRow[] = [
  row(),
  row({
    classId: 'c-2',
    className: '2A British',
    subject: 'english',
    status: 'ON_TIME',
    submissionId: 's-2',
    attachmentId: 'a-2',
    attachmentName: 'week-2a.pdf',
    note: 'Two reading stops',
    submittedAt: '2026-10-15T08:00:00Z',
  }),
  row({
    classId: 'c-3',
    className: '3A American',
    subject: 'science',
    status: 'LATE',
    submissionId: 's-3',
    submittedAt: '2026-10-16T13:00:00Z',
  }),
  row({
    classId: 'c-4',
    className: '4A American',
    subject: 'arabic',
    status: 'REVIEWED',
    submissionId: 's-4',
    attachmentId: 'a-4',
    attachmentName: 'week-4a.pdf',
    reviewedByName: 'Lina Lead',
    reviewedAt: '2026-10-17T07:30:00Z',
    reviewComment: 'Add a listening stop',
  }),
];

const MATERIALS: TeacherMaterials = {
  week: '2026-10-18',
  dueAt: '2026-10-15T09:00:00Z',
  timeZone: 'Asia/Riyadh',
  counts: { onTime: 1, late: 1, missing: 1, reviewed: 1 },
  rows: ROWS,
};

async function settle(rendered: { fixture: { detectChanges: () => void } }): Promise<void> {
  await Promise.resolve();
  TestBed.tick();
  rendered.fixture.detectChanges();
}

async function renderPage(materials: TeacherMaterials = MATERIALS) {
  const rendered = await renderHq(MyMaterialsPage, { providers });
  const backend = TestBed.inject(HttpTestingController);
  TestBed.tick();
  backend.expectOne('/teacher/materials').flush(materials);
  await settle(rendered);
  return { rendered, backend };
}

/** The hidden file input, fed the way a picker feeds it — `userEvent` cannot click a hidden one. */
function pick(name: string, type: string, size: number): void {
  const input = document.querySelector<HTMLInputElement>('[data-hq-file-input]')!;
  const picked = new File(['x'], name, { type });
  Object.defineProperty(picked, 'size', { value: size });
  Object.defineProperty(input, 'files', { value: [picked], configurable: true });
  input.dispatchEvent(new Event('change'));
}

describe('feature 31 — My teaching material', () => {
  beforeEach(() => localStorage.clear());

  it('shows one row per class and subject with its status, the deadline and the counts', async () => {
    await renderPage();

    const statuses = [...document.querySelectorAll('.materials__status')].map((cell) =>
      cell.textContent?.trim(),
    );
    expect(statuses).toEqual(['Not handed in', 'On time', 'Late', 'Reviewed']);
    // The deadline, in the school's own zone and said to be the week before.
    expect(document.querySelector('[data-hq-due]')?.textContent).toContain('Asia/Riyadh');
    expect(document.querySelector('[data-hq-due]')?.textContent).toContain('the week before');
    expect(document.querySelector('[data-hq-count="missing"]')?.textContent).toContain('1');
  });

  /** A reviewed row is a decision: no button, and the reviewer's comment in its place. */
  it('leaves a reviewed row read-only, with the reviewer and the comment', async () => {
    await renderPage();

    expect(screen.getByText(/Add a listening stop/)).toBeInTheDocument();
    expect(screen.getByText(/Lina Lead/)).toBeInTheDocument();
    expect(document.querySelectorAll('[data-hq-locked]')).toHaveLength(1);
    // Three rows may still be handed in or replaced; the reviewed one may not.
    expect(document.querySelectorAll('[data-hq-hand-in]')).toHaveLength(3);
    // The one with nothing on it says "Hand in"; the two already handed in say "Replace".
    expect(screen.getAllByRole('button', { name: 'Replace' })).toHaveLength(2);
    expect(screen.getAllByRole('button', { name: 'Hand in' })).toHaveLength(1);
  });

  it('uploads the file, then hands the row in with the attachment and the note', async () => {
    const { rendered, backend } = await renderPage();

    document.querySelector<HTMLElement>('[data-hq-hand-in] button')!.click();
    await settle(rendered);
    pick('week-1a.pdf', 'application/pdf', 2 * 1024 * 1024);
    await settle(rendered);
    expect(document.querySelector('[data-hq-picked]')?.textContent).toContain('week-1a.pdf');

    await userEvent.type(screen.getByLabelText(/^Note/), 'Three stops');
    await settle(rendered);
    // The row's button and the dialog's confirm share the word; the dialog's is the last one.
    screen.getAllByRole('button', { name: 'Hand in' }).at(-1)!.click();
    await settle(rendered);

    const upload = backend.expectOne('/teacher/materials/attachments');
    expect(upload.request.method).toBe('POST');
    upload.flush({ id: 'a-1', name: 'week-1a.pdf', sizeBytes: 2048, type: 'application/pdf' });
    await settle(rendered);

    const put = backend.expectOne('/teacher/materials/c-1/math');
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual({ attachmentId: 'a-1', note: 'Three stops' });
    put.flush(row({ status: 'ON_TIME', submissionId: 's-1', attachmentId: 'a-1' }));
    await settle(rendered);
    // The rows are read again rather than patched in place: the status is the server's to compute.
    backend.expectOne('/teacher/materials').flush(MATERIALS);
  });

  it('hands a note in with no file at all, and refuses a file the server would refuse', async () => {
    const { rendered, backend } = await renderPage();

    document.querySelector<HTMLElement>('[data-hq-hand-in] button')!.click();
    await settle(rendered);
    pick('plan.docx', 'application/msword', 1024);
    await settle(rendered);
    expect(document.querySelector('[data-hq-refusal]')?.textContent).toContain('cannot be attached');

    await userEvent.type(screen.getByLabelText(/^Note/), 'Paper copy on your desk');
    await settle(rendered);
    // The row's button and the dialog's confirm share the word; the dialog's is the last one.
    screen.getAllByRole('button', { name: 'Hand in' }).at(-1)!.click();
    await settle(rendered);

    // No upload: there is nothing to upload, and the note alone is a valid hand-in.
    backend.expectNone('/teacher/materials/attachments');
    const put = backend.expectOne('/teacher/materials/c-1/math');
    expect(put.request.body).toEqual({ note: 'Paper copy on your desk' });
  });

  /** 409: a leader reviewed it while the dialog was open. Her own words, next to what failed. */
  it('says so when the row was reviewed before her hand-in landed', async () => {
    const { rendered, backend } = await renderPage();

    document.querySelector<HTMLElement>('[data-hq-hand-in] button')!.click();
    await settle(rendered);
    await userEvent.type(screen.getByLabelText(/^Note/), 'Late note');
    await settle(rendered);
    // The row's button and the dialog's confirm share the word; the dialog's is the last one.
    screen.getAllByRole('button', { name: 'Hand in' }).at(-1)!.click();
    await settle(rendered);

    backend
      .expectOne('/teacher/materials/c-1/math')
      .flush(
        { code: 'conflict', message: 'This material has been reviewed and can no longer be replaced.' },
        { status: 409, statusText: 'Conflict' },
      );
    await settle(rendered);

    expect(document.body.textContent).toContain('can no longer be replaced');
  });

  /** Next week is seven days on from the week the server called "now", never a date of its own. */
  it('asks for next week when the second tab is chosen', async () => {
    const { rendered, backend } = await renderPage();

    screen.getByRole('tab', { name: 'Next week' }).click();
    await settle(rendered);

    backend
      .expectOne('/teacher/materials?week=2026-10-25')
      .flush({ ...MATERIALS, week: '2026-10-25', rows: [] });
    await settle(rendered);
    expect(screen.getByRole('tab', { name: 'Next week' })).toHaveAttribute('aria-selected', 'true');
    expect(document.body.textContent).toContain('Nothing is expected from you this week');
  });
});
