import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { BASE_PATH, type ParentPanel } from '../../api';
import { TEACHER_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { ParentPanelEditorComponent } from './parent-panel-editor.component';

const PANEL: ParentPanel = {
  objectives: {
    en: ['Add to ten', 'Count on', 'Check the answer'],
    ar: ['الجمع حتى العشرة', 'العد التصاعدي', 'تحقق من الإجابة'],
  },
  supported: [
    { en: 'Counting on', ar: 'العد التصاعدي' },
    { en: 'Number bonds', ar: 'روابط الأعداد' },
  ],
  challenge: [
    { en: 'Adding three numbers', ar: 'جمع ثلاثة أعداد' },
    { en: 'Missing addend', ar: 'المجموع الناقص' },
  ],
  stopTips: [{ stopId: 's-1', en: 'Read it together.', ar: 'اقرآها معًا.' }],
  modelAnswers: [{ stopId: 's-2', en: 'Any answer with a reason.' }],
};

/** Signed in with `lesson.write`, because every control here sits behind `*hqCan`. */
async function renderEditor(panel: ParentPanel = PANEL) {
  const saved = vi.fn();
  const dirtyChange = vi.fn();
  const rendered = await renderHq(ParentPanelEditorComponent, {
    providers: [provideHttpClient(), provideHttpClientTesting(), { provide: BASE_PATH, useValue: '' }],
    inputs: { panel },
    on: { saved, dirtyChange },
  });

  const backend = TestBed.inject(HttpTestingController);
  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(TEACHER_USER);
  TestBed.tick();
  backend
    .expectOne('/me/permissions')
    .flush({ role: 'TEACHER', permissions: ['lesson.read', 'lesson.write'], readOnly: false });
  await Promise.resolve();
  TestBed.tick();
  await rendered.fixture.whenStable();

  return { rendered, saved, dirtyChange };
}

describe('Parent panel editor', () => {
  beforeEach(() => localStorage.clear());

  it('shows every section in both languages', async () => {
    await renderEditor();

    expect(screen.getByDisplayValue('Add to ten')).toBeInTheDocument();
    expect(screen.getByDisplayValue('الجمع حتى العشرة')).toBeInTheDocument();
    expect(screen.getByDisplayValue('Counting on')).toBeInTheDocument();
    expect(screen.getByDisplayValue('Adding three numbers')).toBeInTheDocument();
    expect(screen.getByDisplayValue('Read it together.')).toBeInTheDocument();
    expect(screen.getByDisplayValue('Any answer with a reason.')).toBeInTheDocument();
  });

  /** The dirty flag is what `lessonUnsavedGuard` reads, so it has to be false until a keystroke. */
  it('is clean until something changes, and clean again after a discard', async () => {
    const { dirtyChange } = await renderEditor();
    expect(dirtyChange).toHaveBeenLastCalledWith(false);

    await userEvent.type(screen.getByDisplayValue('Add to ten'), '!');
    expect(dirtyChange).toHaveBeenLastCalledWith(true);

    await userEvent.click(screen.getByRole('button', { name: 'Discard changes' }));
    expect(dirtyChange).toHaveBeenLastCalledWith(false);
  });

  it('refuses to save a row whose Arabic is missing, and says why', async () => {
    const { saved } = await renderEditor();
    await userEvent.clear(screen.getByDisplayValue('الجمع حتى العشرة'));

    const save = screen.getByRole('button', { name: 'Save the parent panel' });
    expect(save).toBeDisabled();
    expect(screen.getByText('Every line needs both English and Arabic.')).toBeInTheDocument();
    expect(saved).not.toHaveBeenCalled();
  });

  it('emits the panel in the contract shape — objectives back into two parallel arrays', async () => {
    const { saved } = await renderEditor();
    await userEvent.type(screen.getByDisplayValue('Check the answer'), ' twice');
    await userEvent.click(screen.getByRole('button', { name: 'Save the parent panel' }));

    expect(saved).toHaveBeenCalledTimes(1);
    const emitted = saved.mock.calls[0]?.[0] as ParentPanel;
    expect(emitted.objectives.en).toEqual(['Add to ten', 'Count on', 'Check the answer twice']);
    expect(emitted.objectives.ar).toEqual(PANEL.objectives.ar);
    expect(emitted.modelAnswers).toEqual([{ stopId: 's-2', en: 'Any answer with a reason.' }]);
  });

  /** The schema allows three to five objectives; the buttons stop exactly where it would refuse. */
  it('will not remove below the schema minimum, and will not add past its maximum', async () => {
    await renderEditor();
    const removes = screen.getAllByRole('button', { name: 'Remove' });
    // objectives: 3 rows and a minimum of 3 — every Remove in that section is off.
    expect(removes[0]).toBeDisabled();

    const add = screen.getAllByRole('button', { name: 'Add a line' })[0];
    await userEvent.click(add as HTMLElement);
    await userEvent.click(add as HTMLElement);
    expect(add).toBeDisabled();
  });
});
