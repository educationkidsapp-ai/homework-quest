import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BASE_PATH } from '../../api';
import { renderHq } from '../../../testing/render';
import { ClassAttendanceComponent } from './class-attendance.component';

const providers: (Provider | EnvironmentProviders)[] = [
  provideHttpClient(),
  provideHttpClientTesting(),
  provideRouter([]),
  { provide: BASE_PATH, useValue: '' },
];

const TODAY = new Date().toISOString().slice(0, 10);

const ROLL_CALL = {
  classId: 'c-1a',
  className: '1A British',
  date: TODAY,
  students: [{ childId: 'ch-1', childName: 'Amina', status: 'NOT_MARKED', notes: null }],
  totalCount: 1,
  presentCount: 0,
  absentCount: 0,
  lateCount: 0,
  excusedCount: 0,
  attendanceRate: 0,
};

async function settle(): Promise<void> {
  await Promise.resolve();
  TestBed.tick();
  await Promise.resolve();
  TestBed.tick();
}

async function renderRollCall() {
  await renderHq(ClassAttendanceComponent, {
    providers,
    inputs: { classId: 'c-1a', className: '1A British' },
  });
  const backend = TestBed.inject(HttpTestingController);
  backend.expectOne(`/teacher/classes/c-1a/attendance?date=${TODAY}`).flush(ROLL_CALL);
  await settle();
  return { backend };
}

/** U1 item 5 — the roll call's own three defects. */
describe('the class page Attendance tab', () => {
  beforeEach(() => {
    localStorage.clear();
    // The strip closes itself on a timer, so the clock is ours for the whole spec.
    vi.useFakeTimers();
  });
  afterEach(() => vi.useRealTimers());

  it('takes a note as prose, in the row of the child it belongs to', async () => {
    await renderRollCall();

    const note = screen.getByRole('textbox', { name: 'Note for Amina' });
    expect(note.tagName).toBe('TEXTAREA');
    expect(note.getAttribute('rows')).toBe('2');
  });

  it('says a save worked in green and takes the message away after three seconds', async () => {
    const { backend } = await renderRollCall();

    screen.getByRole('button', { name: 'Save Attendance' }).click();
    await settle();
    backend.expectOne({ method: 'POST', url: '/teacher/classes/c-1a/attendance' }).flush(ROLL_CALL);
    await settle();

    const saved = screen.getByRole('status');
    expect(saved.textContent).toContain('Class attendance records have been successfully saved.');
    expect(saved.classList.contains('toast--success')).toBe(true);

    vi.advanceTimersByTime(2999);
    await settle();
    expect(screen.queryByRole('status')).not.toBeNull();

    vi.advanceTimersByTime(1);
    await settle();
    expect(screen.queryByRole('status')).toBeNull();
  });
});
