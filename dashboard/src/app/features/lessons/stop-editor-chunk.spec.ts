import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { BASE_PATH } from '../../api';
import { TEACHER_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import type { Stop } from '../../ui/phone-preview';
import { StopEditorComponent } from './stop-editor.component';
import { validatorsRequested } from './stop-validator';

/**
 * E4a: `stop-validators.generated.js` is 605 kB (34 kB gzipped) of precompiled Ajv, and it used to
 * load for **every** teacher — the lesson page selects the first stop as soon as it opens, which
 * ran the editor's validation effect, which imports the module. The only thing on the screen that
 * needs it is the Raw JSON panel, which `ViewModeService` opens for an Admin in debug mode alone.
 *
 * A file of its own: `stop-validator.ts` remembers the loaded module for the life of the module
 * registry, and vitest gives each spec file its own — so "was it ever asked for" is only a clean
 * question in a file where nothing is allowed to ask for it.
 */
const STOP = {
  id: 'st-1',
  type: 'trueFalse',
  title: 'True or false?',
  speak: 'Alan carries the bowl.',
  ingredient: { emoji: '🥕', name: 'carrot' },
  parentTip: { en: 'Read it together.', ar: 'اقرآها معًا.' },
  hint: 'Page 5 tells us.',
  statement: 'Alan carries the bowl.',
  answer: true,
  teacherText: 'True or false?\nPip says: Alan carries the bowl.',
} as unknown as Stop;

async function renderAsTeacher() {
  const rendered = await renderHq(StopEditorComponent, {
    providers: [provideHttpClient(), provideHttpClientTesting(), { provide: BASE_PATH, useValue: '' }],
    inputs: { stop: STOP },
  });
  const backend = TestBed.inject(HttpTestingController);
  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(TEACHER_USER);
  TestBed.tick();
  backend
    .expectOne('/me/permissions')
    .flush({ role: 'TEACHER', permissions: ['lesson.read', 'stop.write'], readOnly: false });
  await Promise.resolve();
  TestBed.tick();
  await rendered.fixture.whenStable();
  return rendered;
}

/** The effect's 250 ms debounce plus room: a load that was going to happen has by now. */
async function pastTheDebounce(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 400));
  TestBed.tick();
}

describe('the schema-validator chunk', () => {
  it('is not requested when a teacher opens a stop, nor when she edits its fields or its prose', async () => {
    await renderAsTeacher();
    await pastTheDebounce();
    expect(validatorsRequested()).toBe(false);

    await userEvent.type(screen.getByLabelText(/^Title/), ' really?');
    await pastTheDebounce();
    expect(validatorsRequested()).toBe(false);

    await userEvent.type(screen.getByLabelText(/This stop, in your words/), ' Say why.');
    await pastTheDebounce();
    expect(validatorsRequested()).toBe(false);
  });
});
