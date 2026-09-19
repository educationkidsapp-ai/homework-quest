import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';
import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { BASE_PATH } from '../../api';
import { renderHq } from '../../../testing/render';
import { UndoService } from '../../core/undo/undo.service';
import { BandService } from '../../core/band/band.service';
import { DAYS, WEEK } from './week.fixture';
import { WeekPage } from './week.page';

const providers: (Provider | EnvironmentProviders)[] = [
  provideHttpClient(),
  provideHttpClientTesting(),
  provideRouter([]),
  { provide: BASE_PATH, useValue: '' },
];

const SETTINGS = { name: 'Dashboard', timezone: 'Asia/Riyadh', schoolWeek: ['SUNDAY', 'THURSDAY'] };

async function settle(rendered: { fixture: { detectChanges: () => void } }): Promise<void> {
  await Promise.resolve();
  TestBed.tick();
  rendered.fixture.detectChanges();
}

async function renderWeek(week = WEEK) {
  const rendered = await renderHq(WeekPage, { providers });
  const backend = TestBed.inject(HttpTestingController);
  backend.expectOne('/platform-settings').flush(SETTINGS);
  backend.expectOne('/teacher/week').flush(week);
  await settle(rendered);
  return { rendered, backend };
}

/** Opens the card's overflow menu — the keyboard twin of the drag. */
async function openCardMenu(
  rendered: { fixture: { detectChanges: () => void } },
  title: string,
): Promise<void> {
  await userEvent.click(screen.getByRole('button', { name: `Actions for ${title}` }));
  await settle(rendered);
}

describe('This week', () => {
  beforeEach(() => localStorage.clear());

  it('draws one row per assignment, grouped by grade, with a column per school day', async () => {
    await renderWeek();

    expect(screen.getByRole('grid', { name: 'This week' })).toBeTruthy();
    expect(screen.getByText('Grade 1')).toBeTruthy();
    expect(screen.getByText('Grade 3')).toBeTruthy();
    expect(screen.getByText('1A · Math · British')).toBeTruthy();
    expect(screen.getByRole('columnheader', { name: /Sun/ })).toBeTruthy();
    expect(screen.getAllByRole('columnheader')).toHaveLength(DAYS.length + 1);
  });

  it('shows the lesson, its status and the played count once children have played', async () => {
    await renderWeek();

    expect(screen.getByText('Fractions')).toBeTruthy();
    expect(screen.getByText('Draft')).toBeTruthy();
    expect(screen.getByText('Decimals')).toBeTruthy();
    expect(screen.getByText('12/18 played')).toBeTruthy();
    // 1A's draft has nobody on it yet, so there is exactly one count on the screen.
    expect(screen.queryByText('0/18 played')).toBeNull();
  });

  it('points the empty cell’s + at the editor, pre-set to that class, subject and day', async () => {
    await renderWeek();

    const add = screen.getByRole('link', { name: 'Add a lesson for 1B on Tuesday' });
    const href = add.getAttribute('href') ?? '';
    expect(href).toContain('/teacher/lessons/new');
    expect(href).toContain('classId=c-1b');
    expect(href).toContain('subject=math');
    expect(href).toContain(`date=${DAYS[2]}`);
    expect(href).toContain('curriculum=british');
  });

  it('tells a teacher with no assignments who can give her one', async () => {
    await renderWeek({ start: DAYS[0], days: [...DAYS], rows: [], summary: { gaps: [] } });

    expect(screen.getByText('Ask your admin to assign you a class')).toBeTruthy();
    expect(screen.queryByRole('grid')).toBeNull();
  });

  it('walks the cells with the arrow keys — one focusable control per cell, in grid order', async () => {
    await renderWeek();

    const cells = Array.from(document.querySelectorAll<HTMLElement>('[data-hq-cell]'));
    expect(cells).toHaveLength(3 * DAYS.length);

    cells[0]!.focus();
    await userEvent.keyboard('{ArrowRight}');
    expect(document.activeElement).toBe(cells[1]);

    await userEvent.keyboard('{ArrowDown}');
    expect(document.activeElement).toBe(cells[1 + DAYS.length]);

    await userEvent.keyboard('{ArrowLeft}');
    expect(document.activeElement).toBe(cells[DAYS.length]);

    // The edges of the grid are edges: focus does not wrap onto another class's week.
    await userEvent.keyboard('{ArrowLeft}');
    expect(document.activeElement).toBe(cells[DAYS.length]);

    await userEvent.keyboard('{ArrowUp}{ArrowUp}');
    expect(document.activeElement).toBe(cells[0]);
  });

  it('stops at the end of a week rather than wrapping onto the next class', async () => {
    await renderWeek();

    const cells = Array.from(document.querySelectorAll<HTMLElement>('[data-hq-cell]'));
    const lastOfFirstRow = cells[DAYS.length - 1]!;
    lastOfFirstRow.focus();

    // Thursday of 1A is not one step from Sunday of 1B, however the flat index reads.
    await userEvent.keyboard('{ArrowRight}');
    expect(document.activeElement).toBe(lastOfFirstRow);
  });

  // ---- move ---------------------------------------------------------------------------------

  it('moves a draft to another day optimistically, then offers Undo', async () => {
    const { rendered, backend } = await renderWeek();

    await openCardMenu(rendered, 'Fractions');
    await userEvent.click(screen.getByRole('menuitem', { name: 'Move to Wednesday' }));
    await settle(rendered);

    // The card is already on Wednesday before the server has answered.
    expect(screen.getByRole('link', { name: 'Add a lesson for 1A on Sunday' })).toBeTruthy();

    const request = backend.expectOne('/teacher/lessons/l-1');
    expect(request.request.method).toBe('PATCH');
    expect(request.request.body).toEqual({ date: DAYS[3] });
    request.flush({ id: 'l-1' });
    await settle(rendered);

    expect(TestBed.inject(UndoService).offer()?.message).toBe('“Fractions” moved to Wednesday');
  });

  it('rolls the move back under the red band when the server refuses it', async () => {
    const { rendered, backend } = await renderWeek();

    await openCardMenu(rendered, 'Fractions');
    await userEvent.click(screen.getByRole('menuitem', { name: 'Move to Wednesday' }));
    await settle(rendered);

    backend
      .expectOne('/teacher/lessons/l-1')
      .flush(
        { code: 'conflict', message: 'Unpublish the lesson before moving it to another day.' },
        { status: 409, statusText: 'Conflict' },
      );
    await settle(rendered);

    // Back on the Sunday, and the failure is a band rather than a toast.
    expect(screen.queryByRole('link', { name: 'Add a lesson for 1A on Sunday' })).toBeNull();
    expect(TestBed.inject(BandService).current()?.message).toBe(
      'Unpublish the lesson before moving it to another day.',
    );
  });

  it('offers no day to move a published lesson to', async () => {
    const { rendered } = await renderWeek();

    await openCardMenu(rendered, 'Decimals');
    expect(
      screen.getByRole('menuitem', { name: 'Published lessons cannot be moved. Unpublish it first.' }),
    ).toBeTruthy();
    expect(screen.queryByRole('menuitem', { name: /^Move to/ })).toBeNull();
    // 3A is the only Grade 3 row, so there is nowhere to copy it either, and the menu says so
    // rather than offering an action that would 403.
    expect(screen.queryByRole('menuitem', { name: /^Copy to/ })).toBeNull();
    expect(
      screen.getByRole('menuitem', { name: 'No other class of the same grade and subject' }),
    ).toBeTruthy();
  });

  // ---- copy ----------------------------------------------------------------------------------

  it('confirms a copy into a sibling class, names the day the copy will land on, then posts it', async () => {
    const { rendered, backend } = await renderWeek();

    await openCardMenu(rendered, 'Fractions');
    // 1B is the only sibling; 3A is another grade.
    expect(screen.queryByRole('menuitem', { name: 'Copy to 3A' })).toBeNull();
    await userEvent.click(screen.getByRole('menuitem', { name: 'Copy to 1B' }));
    await settle(rendered);

    expect(screen.getByText('Copy “Fractions” to 1B on Sunday?')).toBeTruthy();
    // The strip takes focus as it opens: the menu item that asked the question is gone.
    expect(document.activeElement?.textContent).toContain('Copy');

    await userEvent.click(screen.getByRole('button', { name: 'Copy' }));
    await settle(rendered);

    // The copy is drawn before the server answers: 1B's Sunday is no longer an empty cell.
    expect(screen.queryByRole('link', { name: 'Add a lesson for 1B on Sunday' })).toBeNull();

    const request = backend.expectOne('/teacher/lessons/l-1/copy');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ classId: 'c-1b' });
    request.flush({ id: 'l-3', title: 'Fractions', status: 'review', version: 0 });
    await settle(rendered);

    expect(TestBed.inject(UndoService).offer()?.message).toBe('“Fractions” copied to 1B');
  });

  it('draws the copy inert until the server answers, then makes it a real card', async () => {
    const { rendered, backend } = await renderWeek();

    await openCardMenu(rendered, 'Fractions');
    await userEvent.click(screen.getByRole('menuitem', { name: 'Copy to 1B' }));
    await settle(rendered);
    await userEvent.click(screen.getByRole('button', { name: 'Copy' }));
    await settle(rendered);

    // In flight: drawn and named, but not a link, not draggable, and with no menu of its own —
    // its id is a placeholder, so a link would 404 and a drag would PATCH a lesson nothing owns.
    const pending = document.querySelector('[aria-busy="true"]');
    expect(pending?.textContent).toContain('Copying…');
    expect(pending?.querySelector('a')).toBeNull();
    expect(pending?.querySelector('button')).toBeNull();
    expect(screen.getAllByRole('link', { name: /Fractions/ })).toHaveLength(1);
    expect(screen.getAllByRole('button', { name: 'Actions for Fractions' })).toHaveLength(1);

    const request = backend.expectOne('/teacher/lessons/l-1/copy');
    request.flush({ id: 'l-3', title: 'Fractions', status: 'review', version: 0 });
    await settle(rendered);

    // Settled on the response, not when the Undo window closes: two real cards, two menus.
    expect(document.querySelector('[aria-busy="true"]')).toBeNull();
    expect(screen.getAllByRole('link', { name: /Fractions/ })).toHaveLength(2);
    expect(screen.getAllByRole('button', { name: 'Actions for Fractions' })).toHaveLength(2);
    expect(
      screen
        .getAllByRole('link', { name: /Fractions/ })
        .some((link) => (link.getAttribute('href') ?? '').includes('l-3')),
    ).toBe(true);
    expect(document.body.innerHTML).not.toContain('pending:');
  });

  it('rolls the copy back under the red band when the server refuses it', async () => {
    const { rendered, backend } = await renderWeek();

    await openCardMenu(rendered, 'Fractions');
    await userEvent.click(screen.getByRole('menuitem', { name: 'Copy to 1B' }));
    await settle(rendered);
    await userEvent.click(screen.getByRole('button', { name: 'Copy' }));
    await settle(rendered);

    backend
      .expectOne('/teacher/lessons/l-1/copy')
      .flush(
        { code: 'bad_request', message: 'Finish the lesson before copying it into another class.' },
        { status: 400, statusText: 'Bad Request' },
      );
    await settle(rendered);

    expect(screen.getByRole('link', { name: 'Add a lesson for 1B on Sunday' })).toBeTruthy();
    expect(TestBed.inject(BandService).current()?.message).toBe(
      'Finish the lesson before copying it into another class.',
    );
  });

  // ---- Arabic ------------------------------------------------------------------------------------

  it('keeps the first school day first in Arabic — the mirroring is the layout’s job', async () => {
    const { rendered } = await renderWeek();
    TestBed.inject(TranslocoService).setActiveLang('ar');
    await settle(rendered);

    const headers = screen.getAllByRole('columnheader').map((cell) => cell.textContent?.trim() ?? '');
    expect(headers[0]).toBe('الفصل');
    // Sunday first, Thursday last, in Arabic weekday names and Arabic-locale day numbers.
    expect(headers).toHaveLength(DAYS.length + 1);
    expect(headers[1]).toContain('الأحد');
    expect(headers.at(-1)).toContain('الخميس');
  });
});
