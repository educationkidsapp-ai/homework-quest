import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { fireEvent, screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { BASE_PATH } from '../../api';
import { ADMIN_USER, TEACHER_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { AuthService } from '../../core/auth/auth.service';
import { SchoolScopeStore } from '../../core/auth/school-scope.store';
import { SessionStore } from '../../core/auth/session.store';
import { NewLessonPage } from './new-lesson.page';

const providers: (Provider | EnvironmentProviders)[] = [
  provideHttpClient(),
  provideHttpClientTesting(),
  provideRouter([]),
  { provide: BASE_PATH, useValue: '' },
];

const ALL_FLAGS_ON = {
  'lessons.pdf': true,
  'lessons.slides': true,
  'lessons.images': true,
  'lessons.manual': true,
};

/** A resource applies a delivered value on the microtask queue, then an effect chain settles. */
async function settle(): Promise<void> {
  TestBed.tick();
  await Promise.resolve();
  TestBed.tick();
}

/**
 * A teacher authors into one of her **assignments**, so the page reads `GET /teacher/classes`
 * and nothing else: N2.4b dropped `GET /teacher/options` from this screen entirely (see the
 * comment on `availableCurricula`).
 */
const SARA_CLASSES = [
  { classId: 'c-1a', className: '1A', curriculum: 'british', grade: 1, subject: 'math', childrenCount: 18 },
  { classId: 'c-1b', className: '1B', curriculum: 'british', grade: 1, subject: 'math', childrenCount: 19 },
];

async function renderTeacher(classes: readonly unknown[] = [SARA_CLASSES[0]!]) {
  const rendered = await renderHq(NewLessonPage, { providers });
  const backend = TestBed.inject(HttpTestingController);

  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(TEACHER_USER);
  await settle();
  backend.expectOne('/teacher/classes').flush([...classes]);
  await settle();

  return { rendered, backend };
}

async function renderAdmin(school: { id: string; name: string } | null) {
  const rendered = await renderHq(NewLessonPage, { providers });
  const backend = TestBed.inject(HttpTestingController);

  if (school) TestBed.inject(SchoolScopeStore).select(school);
  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(ADMIN_USER);
  await settle();

  if (school) {
    backend.expectOne(`/admin/schools/${school.id}`).flush({
      id: school.id,
      name: school.name,
      curriculumOptions: ['american', 'british'],
      gradeOptions: [1, 2, 3],
    });
    await settle();
  }

  return { rendered, backend };
}

/**
 * Flushes the flags request the source cards' `*hqFeature` triggers, once it appears. The
 * endpoint answers the flat map itself, not a `{ schoolId, flags }` envelope — see the
 * comment on `FlagService.mapFor`.
 */
async function flushFlags(backend: HttpTestingController, schoolId: string, flags: Record<string, boolean>) {
  await settle();
  backend.expectOne(`/schools/${schoolId}/flags`).flush(flags);
  await settle();
}

/**
 * The same teacher, arriving from This week's `+`: `?classId=` names the section, so the page
 * reads it back from `GET /teacher/classes` and creates through `POST /teacher/lessons`.
 */
async function renderFromWeekPlus(params: Record<string, string>) {
  const rendered = await renderHq(NewLessonPage, {
    providers: [
      ...providers,
      { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap(params) } } },
    ],
  });
  const backend = TestBed.inject(HttpTestingController);

  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(TEACHER_USER);
  await settle();
  backend.expectOne('/teacher/classes').flush([
    { classId: 'c-1a', className: '1A', curriculum: 'british', grade: 1, subject: 'math', childrenCount: 18 },
    { classId: 'c-3a', className: '3A', curriculum: 'british', grade: 3, subject: 'math', childrenCount: 20 },
  ]);
  await settle();

  return { rendered, backend };
}

describe('New lesson', () => {
  beforeEach(() => localStorage.clear());

  // ---- the chooser -----------------------------------------------------------------------

  it('offers a teacher her assignments, not a course, and picks the only one for her', async () => {
    await renderTeacher();

    // §4: class and subject are fixed — a curriculum/grade pair cannot tell 1A from 1B.
    expect(screen.queryByLabelText('Curriculum')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Subject')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Class')).toHaveValue('c-1a::math');
  });

  it('leaves the class for the teacher to choose when she has more than one', async () => {
    await renderTeacher(SARA_CLASSES);

    expect(screen.getByLabelText('Class')).toHaveValue('');
    expect(screen.getByRole('option', { name: '1A · Math · British' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: '1B · Math · British' })).toBeInTheDocument();
    // Nothing downstream until she picks: the primary action says which step is missing.
    expect(screen.getByRole('button', { name: 'Create the lesson' })).toBeDisabled();
    expect(screen.getAllByText('Choose a class to continue.').length).toBeGreaterThan(0);
  });

  it('an admin must pick a school before starting a lesson', async () => {
    await renderAdmin(null);

    expect(screen.getByText('Pick a school in the header to start a lesson.')).toBeInTheDocument();
    expect(screen.queryByLabelText('Curriculum')).not.toBeInTheDocument();
  });

  it("an admin's course options come from the school picked in the header", async () => {
    await renderAdmin({ id: 'school-a', name: 'Al Noor School' });

    fireEvent.change(screen.getByLabelText('Curriculum'), { target: { value: 'american' } });
    expect(screen.getByLabelText('Grade')).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Math' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'English' })).toBeInTheDocument();
  });

  // ---- source cards behind their flag ------------------------------------------------------

  it('hides a source card whose flag is off', async () => {
    const { backend } = await renderTeacher();
    await flushFlags(backend, TEACHER_USER.schoolId ?? '', { ...ALL_FLAGS_ON, 'lessons.manual': false });

    expect(screen.getByRole('button', { name: /Upload a PDF/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Write it yourself/ })).not.toBeInTheDocument();
  });

  // ---- file limits: 25 MB/file, 100 MB total, 10 files -----------------------------------

  it('rejects a file over 25 MB by name, and does not add it', async () => {
    const { backend } = await renderTeacher();
    await flushFlags(backend, TEACHER_USER.schoolId ?? '', ALL_FLAGS_ON);

    await userEvent.click(screen.getByRole('button', { name: /Upload a PDF/ }));
    const huge = new File([new Uint8Array(26 * 1024 * 1024)], 'huge.pdf', { type: 'application/pdf' });
    await userEvent.upload(screen.getByLabelText('Drop a PDF here'), huge);

    expect(screen.getByRole('alert')).toHaveTextContent('huge.pdf is larger than 25 MB and was not added.');
    expect(screen.getByRole('button', { name: 'Create and read the PDF' })).toBeDisabled();
  });

  it('keeps only the first 10 images and says so, rather than silently dropping the rest', async () => {
    const { backend } = await renderTeacher();
    await flushFlags(backend, TEACHER_USER.schoolId ?? '', ALL_FLAGS_ON);

    await userEvent.click(screen.getByRole('button', { name: /Upload photos/ }));
    const photos = Array.from({ length: 12 }, (_, i) => new File(['x'], `p${i}.png`, { type: 'image/png' }));
    await userEvent.upload(screen.getByLabelText('Drop PNG or JPG photos here'), photos);

    expect(screen.getByRole('alert')).toHaveTextContent('Up to 10 files at a time.');
    expect(screen.getAllByRole('listitem')).toHaveLength(10);
  });

  // ---- create → upload → analyze, with rollback --------------------------------------------

  it('creates a lesson, uploads its file, starts analysis, and lands on the lesson route', async () => {
    const { backend } = await renderTeacher();
    await flushFlags(backend, TEACHER_USER.schoolId ?? '', ALL_FLAGS_ON);
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);

    await userEvent.click(screen.getByRole('button', { name: /Upload a PDF/ }));
    const file = new File(['%PDF-1.4'], 'lesson.pdf', { type: 'application/pdf' });
    await userEvent.upload(screen.getByLabelText('Drop a PDF here'), file);

    const create = screen.getByRole('button', { name: 'Create and read the PDF' });
    expect(create).toBeEnabled();
    await userEvent.click(create);

    const created = backend.expectOne('/teacher/lessons');
    expect(created.request.body).toMatchObject({
      classId: 'c-1a',
      subject: 'math',
      source: 'pdf',
      practiceLength: 7,
    });
    created.flush({
      id: 'l-9',
      course: { curriculum: 'british', grade: 1 },
      subject: 'math',
      date: '2026-09-17',
    });
    await settle();
    backend.expectOne('/teacher/lessons/l-9/files').flush({ jobId: 'j-1', status: 'uploading' });
    await settle();
    backend.expectOne('/teacher/lessons/l-9/analyze').flush({ jobId: 'j-2', status: 'analyzing' });
    await settle();

    expect(navigate).toHaveBeenCalledWith(
      ['/teacher/lessons', 'l-9'],
      expect.objectContaining({ queryParams: { notice: 'lessons.new.created' } }),
    );
  });

  it('rolls the draft lesson back when the upload fails, leaving no orphan', async () => {
    const { backend } = await renderTeacher();
    await flushFlags(backend, TEACHER_USER.schoolId ?? '', ALL_FLAGS_ON);

    await userEvent.click(screen.getByRole('button', { name: /Upload a PDF/ }));
    const file = new File(['%PDF-1.4'], 'lesson.pdf', { type: 'application/pdf' });
    await userEvent.upload(screen.getByLabelText('Drop a PDF here'), file);
    await userEvent.click(screen.getByRole('button', { name: 'Create and read the PDF' }));

    backend
      .expectOne('/teacher/lessons')
      .flush({ id: 'l-9', course: { curriculum: 'british', grade: 1 }, subject: 'math', date: '2026-09-17' });
    await settle();
    backend
      .expectOne('/teacher/lessons/l-9/files')
      .flush(
        { code: 'bad_request', message: 'The file is corrupt.' },
        { status: 400, statusText: 'Bad Request' },
      );
    await settle();

    backend.expectOne('/teacher/lessons/l-9').flush({ deleted: true });
    await settle();

    expect(screen.getByRole('alert')).toHaveTextContent('The file is corrupt.');
  });
  // ---- N2.2: arriving from This week's `+` ---------------------------------------------------

  it('takes the course off the section the + came from, and creates into that section', async () => {
    const { backend } = await renderFromWeekPlus({
      classId: 'c-3a',
      curriculum: 'british',
      grade: '1',
      subject: 'math',
      date: '2026-09-22',
    });
    await flushFlags(backend, TEACHER_USER.schoolId ?? '', ALL_FLAGS_ON);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    // `grade=1` in the link loses to the class it names: 3A is a Grade 3 section, and the
    // select is disabled because the `+` already decided which one this lesson belongs to.
    const classSelect = screen.getByLabelText('Class');
    expect(classSelect).toHaveValue('c-3a::math');
    expect(classSelect).toBeDisabled();

    await userEvent.click(screen.getByRole('button', { name: /Write it yourself/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Create and write the questions' }));

    const request = backend.expectOne('/teacher/lessons');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      classId: 'c-3a',
      subject: 'math',
      date: '2026-09-22',
      source: 'manual',
      practiceLength: 7,
      title: undefined,
      notes: undefined,
    });
    request.flush({ id: 'l-7' });
    await settle();

    expect(navigate).toHaveBeenCalledWith(
      ['/teacher/lessons', 'l-7'],
      expect.objectContaining({ queryParams: { notice: 'lessons.new.createdManual' } }),
    );
  });
});
