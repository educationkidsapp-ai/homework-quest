import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { screen, within } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { BASE_PATH, type TeacherAccount } from '../../api';
import { renderHq } from '../../../testing/render';
import { BandService } from '../../core/band/band.service';
import { AssignmentPickerComponent } from './assignment-picker.component';
import type { AdminClass } from './admin.models';

const providers: (Provider | EnvironmentProviders)[] = [
  provideHttpClient(),
  provideHttpClientTesting(),
  provideRouter([]),
  { provide: BASE_PATH, useValue: '' },
];

const ONE_A: AdminClass = {
  id: 'c-1a',
  schoolId: 'school-a',
  curriculum: 'british',
  grade: 1,
  name: '1A',
  active: true,
};
const ONE_B: AdminClass = { ...ONE_A, id: 'c-1b', name: '1B' };
/** Another curriculum entirely: it must not appear, however tempting the grade looks. */
const AMERICAN_ONE_A: AdminClass = { ...ONE_A, id: 'c-us-1a', curriculum: 'american', name: '1A' };

const SARA: TeacherAccount = {
  userId: 'u-sara',
  email: 'sara@alnoor.test',
  fullName: 'Sara',
  status: 'active',
  subjects: ['math'],
  curriculum: 'british',
  assignments: [],
};

async function renderPicker(taken: ReadonlyMap<string, string> = new Map(), teacher: TeacherAccount = SARA) {
  const rendered = await renderHq(AssignmentPickerComponent, {
    providers,
    inputs: { open: true, teacher, sections: [ONE_A, ONE_B, AMERICAN_ONE_A], taken },
  });
  return { rendered, backend: TestBed.inject(HttpTestingController) };
}

describe('the assignment picker', () => {
  it('offers every active class of her curriculum and subjects, and nothing else', async () => {
    await renderPicker();

    expect(screen.getByLabelText(/^1A · British/)).toBeInTheDocument();
    expect(screen.getByLabelText(/^1B · British/)).toBeInTheDocument();
    // One "1A", not two: the American section of the same name is not hers to teach.
    expect(screen.getAllByRole('checkbox')).toHaveLength(2);
  });

  it('disables a class another teacher already has for that subject, and names her', async () => {
    await renderPicker(new Map([['c-1a|math', 'Noura']]));

    const taken = screen.getByLabelText(/^1A · British/);
    expect(taken).toBeDisabled();
    expect(screen.getByText('Taught by Noura')).toBeInTheDocument();
    expect(screen.getByLabelText(/^1B · British/)).toBeEnabled();
  });

  it('sends every ticked pair as one replacement set', async () => {
    const { rendered, backend } = await renderPicker();

    await userEvent.click(screen.getByLabelText(/^1A · British/));
    await userEvent.click(screen.getByLabelText(/^1B · British/));
    rendered.fixture.detectChanges();
    await userEvent.click(screen.getByRole('button', { name: 'Save assignments' }));

    const request = backend.expectOne('/admin/teachers/u-sara/assignments');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({
      assignments: [
        { classId: 'c-1a', subject: 'math' },
        { classId: 'c-1b', subject: 'math' },
      ],
    });
  });

  /**
   * The acceptance of N1.2: a second Math teacher for 1A is refused **with the current teacher's
   * name**, and the tick she made goes back where it was rather than sitting there looking saved.
   */
  it('reverts the tick and shows the server’s sentence when the save is refused', async () => {
    const held: TeacherAccount = {
      ...SARA,
      assignments: [{ id: 'a-1', classId: 'c-1b', className: '1B', subject: 'math', teacherId: 'u-sara' }],
    };
    const { rendered, backend } = await renderPicker(new Map(), held);

    expect(screen.getByLabelText(/^1B · British/)).toBeChecked();

    await userEvent.click(screen.getByLabelText(/^1A · British/));
    rendered.fixture.detectChanges();
    expect(screen.getByLabelText(/^1A · British/)).toBeChecked();

    await userEvent.click(screen.getByRole('button', { name: 'Save assignments' }));
    backend
      .expectOne('/admin/teachers/u-sara/assignments')
      .flush(
        { code: 'conflict', message: '1A already has a Math teacher: Noura' },
        { status: 409, statusText: 'Conflict' },
      );
    rendered.fixture.detectChanges();

    expect(TestBed.inject(BandService).current()?.message).toBe('1A already has a Math teacher: Noura');
    expect(screen.getByLabelText(/^1A · British/)).not.toBeChecked();
    expect(screen.getByLabelText(/^1B · British/)).toBeChecked();
  });

  it('groups the checkboxes by subject so two subjects cannot be confused', async () => {
    const both: TeacherAccount = { ...SARA, subjects: ['math', 'english'] };
    await renderPicker(new Map(), both);

    const maths = screen.getByRole('group', { name: 'Math' });
    expect(within(maths).getAllByRole('checkbox')).toHaveLength(2);
    expect(within(screen.getByRole('group', { name: 'English' })).getAllByRole('checkbox')).toHaveLength(2);
  });
});
