import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BASE_PATH, type Stop } from '../../api';
import { translocoTesting } from '../../../testing/render';
import { DRAFT_TIMEOUT_MS, StopDraftService } from './stop-draft.service';

const TEMPLATE = '{"type":"match","title":"Match the pairs"}';
const TEXT = 'Match the pairs\n\nJoin each word to its picture.';

function stop(id: string, title: string): Stop {
  return {
    id,
    type: 'match',
    title,
    speak: 'Join them up.',
    ingredient: { emoji: '🥕', name: 'carrot' },
    parentTip: { en: 'Read it together.', ar: 'اقرآها معًا.' },
  };
}

function setUp() {
  TestBed.configureTestingModule({
    imports: [translocoTesting()],
    providers: [provideHttpClient(), provideHttpClientTesting(), { provide: BASE_PATH, useValue: '' }],
  });
  const service = TestBed.inject(StopDraftService);
  const backend = TestBed.inject(HttpTestingController);
  const created = vi.fn();
  const written = vi.fn();
  const removed = vi.fn();
  const said = vi.fn();
  service.created$.subscribe(created);
  service.written$.subscribe(written);
  service.removed$.subscribe(removed);
  service.said$.subscribe(said);
  return { service, backend, created, written, removed, said };
}

/** The create, flushed — every test starts from "the template stop exists, the model is writing". */
function startDraft(t: ReturnType<typeof setUp>) {
  t.service.add('l-1', 'p-1', TEMPLATE, TEXT);
  t.backend.expectOne('/admin/plays/p-1/stops').flush(stop('st-9', 'Match the pairs'));
  return t.backend.expectOne('/admin/stops/st-9/from-text');
}

describe('StopDraftService', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('adds the template, marks the stop as being written, then patches it from the answer', () => {
    const t = setUp();

    const convert = startDraft(t);
    expect(t.created).toHaveBeenCalledWith({ lessonId: 'l-1', playId: 'p-1', stop: stop('st-9', 'Match the pairs') });
    expect(t.service.writing('st-9')).toBe(true);
    expect(JSON.parse(convert.request.body as string)).toEqual({ text: TEXT });

    convert.flush(stop('st-9', 'Match each word'));

    // The row's state is gone and the one changed stop is handed back — no lesson re-read.
    expect(t.service.draftOf('st-9')).toBeNull();
    expect(t.written).toHaveBeenCalledWith({
      lessonId: 'l-1',
      playId: 'p-1',
      stop: stop('st-9', 'Match each word'),
    });
  });

  it('keeps the stop on a 422 and puts the refusal on its row, ready to retry with the same words', () => {
    const t = setUp();

    startDraft(t).flush(
      { code: 'rephrase', message: "Couldn't save, please rephrase. #/pairs: minItems 2" },
      { status: 422, statusText: 'Unprocessable Entity' },
    );

    // The old form deleted the template stop here. The row is where it is reported now, so the
    // stop — with her title and her picture on it — stays.
    t.backend.expectNone('/admin/stops/st-9');
    const draft = t.service.draftOf('st-9');
    expect(draft?.state).toBe('error');
    expect(draft?.reason).toBe("Couldn't save, please rephrase.");
    // With the lesson on it: the page filters this the way it filters the other three, so a
    // refusal on one lesson never raises a toast on another.
    expect(t.said).toHaveBeenCalledWith({
      lessonId: 'l-1',
      message: "Couldn't save, please rephrase.",
    });
    expect(t.service.writing('st-9')).toBe(false);

    t.service.retry('st-9');
    const again = t.backend.expectOne('/admin/stops/st-9/from-text');
    expect(JSON.parse(again.request.body as string)).toEqual({ text: TEXT });
    expect(t.service.writing('st-9')).toBe(true);

    again.flush(stop('st-9', 'Match each word'));
    expect(t.service.draftOf('st-9')).toBeNull();
  });

  it('says so on the row when the lesson is still generating', () => {
    const t = setUp();

    startDraft(t).flush(
      { code: 'bad_request', message: 'Wait for this lesson to finish generating before editing a stop.' },
      { status: 400, statusText: 'Bad Request' },
    );

    expect(t.service.draftOf('st-9')?.reason).toMatch(/finish generating/);
  });

  it('aborts the request after 90 s and offers the row instead of waiting for ever', () => {
    const t = setUp();

    const convert = startDraft(t);
    vi.advanceTimersByTime(DRAFT_TIMEOUT_MS - 1);
    expect(t.service.writing('st-9')).toBe(true);

    vi.advanceTimersByTime(2);

    expect(convert.cancelled).toBe(true);
    expect(t.service.draftOf('st-9')?.state).toBe('error');
    expect(t.service.draftOf('st-9')?.reason).toMatch(/did not answer in time/);
  });

  it('removes the template stop when she gives up on it', () => {
    const t = setUp();

    startDraft(t).flush({ code: 'rephrase', message: 'No' }, { status: 422, statusText: 'x' });
    t.service.remove('st-9');
    const deleted = t.backend.expectOne('/admin/stops/st-9');
    expect(deleted.request.method).toBe('DELETE');
    deleted.flush(null);

    expect(t.service.draftOf('st-9')).toBeNull();
    expect(t.removed).toHaveBeenCalledWith({ lessonId: 'l-1', stopId: 'st-9' });
  });

  /**
   * The service is `providedIn: 'root'`, so the lesson page's lifetime is not the draft's: a
   * teacher who goes back to This week mid-write comes back to a finished stop, and one who stays
   * on the page sees the row settle. Nothing here is owned by a component.
   */
  it('carries on across a page destroy, with the row state still there afterwards', () => {
    const t = setUp();
    const convert = startDraft(t);

    // What a destroyed lesson page leaves behind: no listener, and no cancellation either.
    t.written.mockClear();
    expect(convert.cancelled).toBe(false);
    expect(t.service.writing('st-9')).toBe(true);
    expect(t.service.pending()).toBe(1);

    convert.flush(stop('st-9', 'Match each word'));
    expect(t.service.pending()).toBe(0);
  });

  /** A create that made nothing has no row to carry it — `POST …/stops` is not `silentErrors()`, so
   *  the red band the interceptor raises is the report, and nothing half-made is left behind. */
  it('registers no row when the template stop was never made', () => {
    const t = setUp();

    t.service.add('l-1', 'p-1', TEMPLATE, TEXT);
    t.backend
      .expectOne('/admin/plays/p-1/stops')
      .flush({ code: 'bad_request', message: 'That title is too long.' }, { status: 400, statusText: 'x' });

    expect(t.created).not.toHaveBeenCalled();
    expect(t.said).not.toHaveBeenCalled();
    expect(t.service.pending()).toBe(0);
  });
});
