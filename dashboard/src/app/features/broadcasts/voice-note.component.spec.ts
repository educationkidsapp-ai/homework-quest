import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { screen } from '@testing-library/angular';
import { beforeEach, describe, expect, it } from 'vitest';
import { renderHq } from '../../../testing/render';
import { BASE_PATH } from '../../api';
import { BandService } from '../../core/band/band.service';
import { type ComposeContext, endOfDayIn } from '../../core/broadcasts/broadcast.rules';
import { FlagService } from '../../core/flags/flag.service';
import { PermissionService } from '../../core/permissions/permission.service';
import {
  type SpeechInput,
  type SpeechListener,
  type SpeechSession,
  SPEECH_INPUT,
} from '../../core/speech/speech-input';
import { VoiceNoteComponent } from './voice-note.component';

/** A manager of the British track, with one grade she manages a class in. */
const CTX: ComposeContext = {
  role: 'manager',
  departments: ['british'],
  sections: [{ classId: 'c-1', curriculum: 'british', grade: 3 }],
  today: '2026-10-15',
  zone: 'Asia/Riyadh',
};

const SAID = 'بكرة الخميس رحلة لطلاب ثالث، أبلغوا الأمهات';

const PREPARED = {
  id: 'd-1',
  title: 'School trip on Thursday',
  bodyEn: 'Grade 3 goes on a trip on Thursday. Please send a packed lunch.',
  bodyAr: 'تذهب الصف الثالث في رحلة يوم الخميس. يُرجى إرسال وجبة معه.',
  audience: { curriculum: 'british', grade: 3 },
  expiresAt: endOfDayIn('2026-10-22', 'Asia/Riyadh'),
  transcript: SAID,
};

/** The device, faked: a spec drives what was "heard" and when the recording ends. */
class FakeSpeech implements SpeechInput {
  listener: SpeechListener | null = null;
  can = true;

  supported(): boolean {
    return this.can;
  }

  start(listener: SpeechListener): SpeechSession {
    this.listener = listener;
    return { stop: () => this.listener?.ended('stopped') };
  }

  say(text: string): void {
    this.listener?.transcript(text);
  }
}

/**
 * **Feature 22, the dashboard half.** The three things only this component can get wrong:
 *
 * 1. the draft call happens when she stops talking, and **not before**;
 * 2. what comes back fills the real compose sheet, every field editable;
 * 3. **nothing is posted** until she presses Send on that sheet — and when she does, the post
 *    carries the draft's id so the transcript stays with the announcement.
 */
describe('the voice note', () => {
  let speech: FakeSpeech;

  async function settle() {
    await Promise.resolve();
    TestBed.tick();
    await Promise.resolve();
    TestBed.tick();
  }

  beforeEach(() => {
    speech = new FakeSpeech();
  });

  async function mounted(ctx: ComposeContext = CTX) {
    await renderHq(VoiceNoteComponent, {
      inputs: { ctx, sections: [{ classId: 'c-1', curriculum: 'british', grade: 3, className: '3A' }] },
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: BASE_PATH, useValue: '' },
        { provide: FlagService, useValue: { isOn: () => true, refresh: () => undefined } },
        { provide: PermissionService, useValue: { can: () => true } },
        { provide: SPEECH_INPUT, useValue: speech },
      ],
    });
    await settle();
    return TestBed.inject(HttpTestingController);
  }

  /** Record, talk, Stop: the transcript is what is sent, and only then. */
  async function recorded(backend: HttpTestingController) {
    screen.getByRole('button', { name: 'Record a voice note' }).click();
    await settle();
    speech.say(SAID);
    await settle();

    // Still nothing has left the browser: she is talking.
    expect(backend.match('/management/broadcasts/voice-drafts')).toEqual([]);
    expect(document.body.textContent).toContain(SAID);

    screen.getByRole('button', { name: 'Stop and prepare it' }).click();
    await settle();
    const draft = backend.expectOne('/management/broadcasts/voice-drafts');
    expect(draft.request.method).toBe('POST');
    // Text only. No audio is recorded to a file, uploaded or stored anywhere.
    expect(draft.request.body).toEqual({ transcript: SAID });
    return draft;
  }

  it('prepares the announcement when she stops, and posts nothing until she sends it', async () => {
    const backend = await mounted();
    (await recorded(backend)).flush(PREPARED, { status: 201, statusText: 'Created' });
    await settle();

    // The real composer, pre-filled: a title, both bodies, the suggested grade and the expiry
    // read back in the school's zone — all of them hers to change.
    const inputs = [...document.querySelectorAll<HTMLInputElement>('hq-input input')];
    const bodies = [...document.querySelectorAll<HTMLTextAreaElement>('hq-textarea textarea')];
    expect(inputs[0]?.value).toBe(PREPARED.title);
    expect(bodies[0]?.value).toBe(PREPARED.bodyEn);
    expect(bodies[1]?.value).toBe(PREPARED.bodyAr);
    expect(inputs.at(-1)?.value).toBe('2026-10-22');
    // And what she said is under it, to check rather than to edit.
    expect(screen.getByText('What you said')).toBeTruthy();

    // The one acceptance criterion the screen owns: preparing sends nothing.
    expect(backend.match('/management/broadcasts')).toEqual([]);

    screen.getByRole('button', { name: 'Post' }).click();
    await settle();

    const post = backend.expectOne('/management/broadcasts');
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({
      kind: 'announcement',
      title: PREPARED.title,
      bodyEn: PREPARED.bodyEn,
      bodyAr: PREPARED.bodyAr,
      audience: ['parents'],
      grade: 3,
      expiresAt: PREPARED.expiresAt,
      // What links the transcript to what went out, and what makes a second Send a 409.
      voiceDraftId: 'd-1',
    });
    post.flush({ id: 'b-1' });
    await settle();
    backend.verify();
  });

  /**
   * **The Admin's shape**: both academies of the school, every grade of them (`GET
   * /management/classes`). The suggestion is pre-selected and still hers to change, and the post
   * says it the one way the server accepts from an author who spans two academies — that grade's
   * sections, with no `grade` field.
   */
  it('pre-selects the suggested academy and grade, and posts them as sections', async () => {
    const backend = await mounted({
      ...CTX,
      departments: ['british', 'american'],
      sections: [
        { classId: 'c-1', curriculum: 'british', grade: 1 },
        { classId: 'c-2', curriculum: 'british', grade: 3 },
        { classId: 'c-3', curriculum: 'american', grade: 5 },
      ],
    });
    (await recorded(backend)).flush(PREPARED, { status: 201, statusText: 'Created' });
    await settle();

    // Pre-selected on the real selects — the academy and the grade she can change before sending.
    const selects = [...document.querySelectorAll<HTMLSelectElement>('hq-select select')];
    expect(selects[1]?.value).toBe('british');
    expect(selects[2]?.value).toBe('3');
    expect([...selects[2]!.options].map((option) => option.getAttribute('value'))).toEqual(['', '1', '3']);

    screen.getByRole('button', { name: 'Post' }).click();
    await settle();

    const post = backend.expectOne('/management/broadcasts');
    expect(post.request.body).toEqual({
      kind: 'announcement',
      title: PREPARED.title,
      bodyEn: PREPARED.bodyEn,
      bodyAr: PREPARED.bodyAr,
      audience: ['parents'],
      expiresAt: PREPARED.expiresAt,
      sectionIds: ['c-2'],
      voiceDraftId: 'd-1',
    });
    post.flush({ id: 'b-1' });
    await settle();
  });

  /**
   * A suggestion the school's data does not hold is dropped rather than carried: a grade with no
   * class in it would be a 400, and a validation error on a field she never filled in.
   */
  it('drops a suggested grade the composer may not name', async () => {
    const backend = await mounted({ ...CTX, departments: [], sections: [] });
    (await recorded(backend)).flush(PREPARED, { status: 201, statusText: 'Created' });
    await settle();

    screen.getByRole('button', { name: 'Post' }).click();
    await settle();

    const post = backend.expectOne('/management/broadcasts');
    expect(post.request.body).toEqual({
      kind: 'announcement',
      title: PREPARED.title,
      bodyEn: PREPARED.bodyEn,
      bodyAr: PREPARED.bodyAr,
      audience: ['parents'],
      expiresAt: PREPARED.expiresAt,
      voiceDraftId: 'd-1',
    });
    post.flush({ id: 'b-1' });
    await settle();
  });

  /** 422 `rephrase`: the assistant could not write it twice over, so she is asked to say it again. */
  it('asks her to say it again on a 422, in the red band', async () => {
    const backend = await mounted();
    (await recorded(backend)).flush(
      { code: 'rephrase', message: 'could not be turned into an announcement' },
      { status: 422, statusText: 'Unprocessable Entity' },
    );
    await settle();

    expect(TestBed.inject(BandService).current()?.message).toContain('Say it again');
    // Back to the button, with no sheet and nothing posted.
    expect(screen.getByRole('button', { name: 'Record a voice note' })).toBeTruthy();
    expect(document.querySelector('hq-broadcast-compose')).toBeNull();
    expect(backend.match('/management/broadcasts')).toEqual([]);
  });

  /** Cancel throws the recording away: no draft call at all. */
  it('asks for nothing when she cancels the recording', async () => {
    const backend = await mounted();
    screen.getByRole('button', { name: 'Record a voice note' }).click();
    await settle();
    speech.say(SAID);
    await settle();

    screen.getByRole('button', { name: 'Cancel' }).click();
    await settle();

    expect(backend.match('/management/broadcasts/voice-drafts')).toEqual([]);
    expect(screen.getByRole('button', { name: 'Record a voice note' })).toBeTruthy();
  });

  /** Firefox: no recognition, so the button is absent rather than offered and broken. */
  it('offers nothing in a browser that cannot transcribe', async () => {
    speech.can = false;
    await mounted();

    expect(screen.queryByRole('button', { name: 'Record a voice note' })).toBeNull();
  });
});
