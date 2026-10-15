import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import { describe, expect, it } from 'vitest';
import { renderHq } from '../../../testing/render';
import { BASE_PATH } from '../../api';
import { BandService } from '../../core/band/band.service';
import { FlagService } from '../../core/flags/flag.service';
import { PermissionService } from '../../core/permissions/permission.service';
import { PlatformService } from '../../core/platform/platform.service';
import { DocumentReaderPage } from './document-reader.page';
import { MAX_DOCUMENT_BYTES, documentFileError } from './document-file';

/** A ministry circular as the server answers it: both languages, two dates, two actions, a draft. */
const SUMMARY = {
  id: 'ds-1',
  fileName: 'circular-412.pdf',
  pages: 2,
  title: 'Mid-year examination dates',
  summaryEn: 'The ministry has set the mid-year examinations for the first week of January.',
  summaryAr: 'حددت الوزارة اختبارات منتصف العام في الأسبوع الأول من يناير.',
  keyDates: [
    { date: '2027-01-04', labelEn: 'Examinations begin', labelAr: 'بداية الاختبارات' },
    { date: '2027-01-08', labelEn: 'Examinations end', labelAr: 'نهاية الاختبارات' },
  ],
  actionsEn: ['Publish the timetable to parents', 'Book the hall'],
  actionsAr: ['نشر الجدول لأولياء الأمور', 'حجز القاعة'],
  announcement: {
    title: 'Mid-year examinations',
    bodyEn: 'The mid-year examinations run from 4 to 8 January.',
    bodyAr: 'تُعقد اختبارات منتصف العام من ٤ إلى ٨ يناير.',
  },
  createdAt: 1_760_000_000_000,
};

/** An older one of hers, with no suggested announcement — a circular about staff, not parents. */
const OLDER = {
  id: 'ds-0',
  fileName: 'staff-letter.docx',
  pages: 1,
  title: 'Staff training day',
  summaryEn: 'A training day for all staff is set for the last Thursday of the month.',
  summaryAr: 'يوم تدريبي لجميع العاملين في آخر خميس من الشهر.',
  keyDates: [],
  actionsEn: [],
  actionsAr: [],
  createdAt: 1_759_000_000_000,
};

/** `GET /management/classes` for the sheet's academy and grade lists. */
const CLASSES = [
  {
    grade: 3,
    curriculum: 'british',
    classes: [
      {
        classId: 'c-1',
        className: '3A British',
        grade: 3,
        curriculum: 'british',
        subject: 'math',
        teacherId: 't-1',
        teacherName: 'Sara Al Harbi',
        childrenCount: 24,
        todayLessonId: null,
        todayStatus: 'none',
      },
    ],
  },
];

function pdf(name = 'circular-412.pdf', size = 120_000): File {
  const file = new File([new Uint8Array(8)], name, { type: 'application/pdf' });
  // `File` is read-only about its size in jsdom, and a 10 MB buffer per test is wasteful.
  Object.defineProperty(file, 'size', { value: size });
  return file;
}

/**
 * **Feature 26, the dashboard half.** What only this screen can get wrong:
 *
 * 1. a picked file is read and the summary is what she sees;
 * 2. each of the server's answers is a sentence she can act on, in the red band;
 * 3. an obviously oversized file never leaves the browser;
 * 4. **"Draft an announcement" posts nothing** until the sheet's own Send;
 * 5. a recent row shows its summary again;
 * 6. with the switch off there is no screen at all.
 */
describe('the document reader', () => {
  async function settle() {
    await Promise.resolve();
    TestBed.tick();
    await Promise.resolve();
    TestBed.tick();
  }

  async function mounted(on = true) {
    await renderHq(DocumentReaderPage, {
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: '**', children: [] }]),
        { provide: BASE_PATH, useValue: '' },
        { provide: FlagService, useValue: { isOn: () => on, refresh: () => undefined } },
        { provide: PermissionService, useValue: { can: () => true } },
        { provide: PlatformService, useValue: { timezone: () => 'Asia/Riyadh' } },
      ],
    });
    await settle();
    return TestBed.inject(HttpTestingController);
  }

  /** The file picker inside the drop zone, which is the only control on an empty screen. */
  function picker(): HTMLInputElement {
    return document.querySelector<HTMLInputElement>('input[type="file"]')!;
  }

  /** Picking a file the way a browser reports it: the input holds it, then `change` fires. */
  async function pick(file: File) {
    const input = picker();
    Object.defineProperty(input, 'files', { value: [file], configurable: true });
    input.dispatchEvent(new Event('change'));
    await settle();
  }

  /** Whether the compose sheet is actually open — it is a dialog, present in the DOM either way. */
  function sheetOpen(): boolean {
    return document.querySelector<HTMLDialogElement>('hq-broadcast-compose dialog')?.open ?? false;
  }

  async function read(backend: HttpTestingController, file = pdf()) {
    backend.expectOne('/management/documents').flush([]);
    await settle();
    await pick(file);
    const request = backend.expectOne('/management/documents/read');
    expect(request.request.method).toBe('POST');
    // Multipart with the file itself — the generated client's `file` part, not a hand-rolled body.
    expect(request.request.body).toBeInstanceOf(FormData);
    expect((request.request.body as FormData).get('file')).toBe(file);
    return request;
  }

  it('reads the file she picks and shows the summary, its dates and its actions', async () => {
    const backend = await mounted();
    const request = await read(backend);

    // While it is in flight she is told so, and there is no invented percentage.
    expect(document.body.textContent).toContain('Reading your document…');
    expect(document.querySelector('hq-progress-bar')).toBeTruthy();

    request.flush(SUMMARY, { status: 201, statusText: 'Created' });
    await settle();

    expect(screen.getByText('Mid-year examination dates')).toBeTruthy();
    expect(document.body.textContent).toContain(SUMMARY.summaryEn);
    // The dates, as the school says the day rather than as `2027-01-04`.
    expect(document.body.textContent).toContain('4 January 2027');
    expect(document.body.textContent).toContain('Examinations begin');
    expect(document.body.textContent).toContain('Book the hall');
    // And the promise the privacy criterion is about, said on the screen.
    expect(document.body.textContent).toContain('The file is deleted right after it is read.');

    // The language toggle shows the other half of the same summary.
    screen.getByRole('button', { name: 'Read it in Arabic' }).click();
    await settle();
    expect(document.body.textContent).toContain(SUMMARY.summaryAr);
    expect(document.body.textContent).toContain('نشر الجدول لأولياء الأمور');

    // A fresh read refetches her list, because the summary it wrote belongs on it.
    backend.expectOne('/management/documents').flush([SUMMARY]);
    await settle();
  });

  /**
   * The one rule asked **before** the upload: 10 MB. The band says the same thing the server's
   * `413 too_large` says, because it is the same rule — and nothing is sent at all.
   */
  it('refuses a file over 10 MB without uploading it', async () => {
    const backend = await mounted();
    backend.expectOne('/management/documents').flush([]);
    await settle();

    await pick(pdf('huge-scan.pdf', MAX_DOCUMENT_BYTES + 1));

    expect(backend.match('/management/documents/read')).toEqual([]);
    expect(TestBed.inject(BandService).current()?.message).toContain('larger than 10 MB');
    // The picker is still there: there is nothing to retry, only another file to choose.
    expect(picker()).toBeTruthy();
    expect(documentFileError(pdf('huge-scan.pdf', MAX_DOCUMENT_BYTES + 1))).toBe('tooLarge');
  });

  it('refuses a kind of file the server cannot read, before uploading it', async () => {
    const backend = await mounted();
    backend.expectOne('/management/documents').flush([]);
    await settle();

    await pick(new File(['x'], 'budget.xlsx', { type: 'application/vnd.ms-excel' }));

    expect(backend.match('/management/documents/read')).toEqual([]);
    expect(TestBed.inject(BandService).current()?.message).toContain('kind of file cannot be read');
  });

  /** Each of the server's four refusals, in words she can act on. */
  it.each([
    [413, 'too_large', 'larger than 10 MB'],
    [422, 'too_long', 'longer than 20 pages'],
    [422, 'unreadable_file', 'straight on and in better light'],
    [422, 'rephrase', 'could not be summarised'],
    [502, 'model_failed', 'did not answer'],
  ])('says what went wrong on a %i %s', async (status, code, said) => {
    const backend = await mounted();
    const request = await read(backend);
    request.flush({ code, message: 'the server’s own sentence' }, { status, statusText: 'Refused' });
    await settle();

    expect(TestBed.inject(BandService).current()?.message).toContain(said);
    // Nothing is shown as a summary, and the picker is where she left it.
    expect(document.querySelector('hq-card')).toBeTruthy();
    expect(document.body.textContent).not.toContain('Mid-year examination dates');
    expect(picker()).toBeTruthy();
  });

  /**
   * **The acceptance criterion this screen owns.** "Draft an announcement" opens the *typed*
   * composer with the suggestion in it, and the announcement goes out on that sheet's own Send —
   * `POST /management/broadcasts`, unchanged.
   */
  it('opens the compose sheet pre-filled and posts nothing until Send', async () => {
    const backend = await mounted();
    (await read(backend)).flush(SUMMARY, { status: 201, statusText: 'Created' });
    await settle();
    backend.expectOne('/management/documents').flush([SUMMARY]);
    await settle();

    // The sheet is a dialog over the page, so it is in the DOM and **closed** until she asks.
    expect(sheetOpen()).toBe(false);
    screen.getByRole('button', { name: 'Draft an announcement' }).click();
    await settle();
    // The sheet needs the academies and grades she may name; only now are they asked for.
    backend.expectOne('/management/classes').flush(CLASSES);
    await settle();

    const inputs = [...document.querySelectorAll<HTMLInputElement>('hq-input input')];
    const bodies = [...document.querySelectorAll<HTMLTextAreaElement>('hq-textarea textarea')];
    expect(inputs[0]?.value).toBe(SUMMARY.announcement.title);
    expect(bodies[0]?.value).toBe(SUMMARY.announcement.bodyEn);
    expect(bodies[1]?.value).toBe(SUMMARY.announcement.bodyAr);

    // Drafting posted nothing.
    expect(backend.match('/management/broadcasts')).toEqual([]);

    screen.getByRole('button', { name: 'Post' }).click();
    await settle();

    const post = backend.expectOne('/management/broadcasts');
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({
      kind: 'announcement',
      title: SUMMARY.announcement.title,
      bodyEn: SUMMARY.announcement.bodyEn,
      bodyAr: SUMMARY.announcement.bodyAr,
      audience: ['parents'],
    });
    post.flush({ id: 'b-1' });
    await settle();
    expect(sheetOpen()).toBe(false);
    backend.verify();
  });

  /** A circular nobody has to be told about has no button to tell them with. */
  it('offers no draft button when the circular suggests no announcement', async () => {
    const backend = await mounted();
    (await read(backend)).flush(OLDER, { status: 201, statusText: 'Created' });
    await settle();
    backend.expectOne('/management/documents').flush([OLDER]);
    await settle();

    expect(screen.queryByRole('button', { name: 'Draft an announcement' })).toBeNull();
    expect(document.body.textContent).toContain('No dates were named.');
    expect(document.body.textContent).toContain('Nothing was asked of the school.');
  });

  /**
   * Her last twenty, and Open on one of them. The row carries the whole summary (the server's list
   * route answers the same view the read does), so opening one asks for nothing.
   */
  it('shows a recent summary again when she opens it', async () => {
    const backend = await mounted();
    backend.expectOne('/management/documents').flush([SUMMARY, OLDER]);
    await settle();

    expect(document.body.textContent).toContain('staff-letter.docx');
    screen.getByRole('button', { name: `Open: ${OLDER.title}` }).click();
    await settle();

    expect(document.body.textContent).toContain(OLDER.summaryEn);
    expect(backend.match(`/management/documents/${OLDER.id}`)).toEqual([]);
    backend.verify();
  });

  /** The flag off: no drop zone, no list, no request — the same silence the server's 404 gives. */
  it('draws nothing but the explanation while the switch is off', async () => {
    const backend = await mounted(false);

    expect(document.querySelector('input[type="file"]')).toBeNull();
    expect(document.body.textContent).toContain('not switched on for this school');
    expect(backend.match('/management/documents')).toEqual([]);
    backend.verify();
  });
});
