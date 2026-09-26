import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { BASE_PATH } from '../../api';
import { TEACHER_USER } from '../../../testing/fixtures';
import { translocoTesting } from '../../../testing/render';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { LessonCreationService } from './lesson-creation.service';

const LESSON = { id: 'l-9', title: 'Shapes' };
const REQUEST = {
  classId: 'c-1',
  subject: 'math' as const,
  date: '2026-09-25',
  source: 'pdf' as const,
  practiceLength: 7,
};

function file(): File {
  return new File(['%PDF-1.4'], 'Shapes.pdf', { type: 'application/pdf' });
}

function setUp(): { service: LessonCreationService; backend: HttpTestingController } {
  const backend = TestBed.inject(HttpTestingController);
  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(TEACHER_USER);
  TestBed.tick();
  return { service: TestBed.inject(LessonCreationService), backend };
}

/**
 * E3: the chain that used to live in the New lesson page's three nested `subscribe`s.
 *
 * The two behaviours worth a test are the two the page could not have: the work surviving the
 * screen that started it, and the upload job's own verdict being read before an analyze is
 * built on it.
 */
describe('LessonCreationService', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      imports: [translocoTesting()],
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: BASE_PATH, useValue: '' }],
    });
  });

  it('finishes the chain after she leaves, and navigates nobody', () => {
    const { service, backend } = setUp();

    service.start(REQUEST, 'pdf', [file()]);
    backend.expectOne('/teacher/lessons').flush(LESSON);
    expect(service.step()).toBe('uploading');

    // She presses "Work in background" mid-upload: the page goes, the chain stays.
    service.workInBackground();

    backend.expectOne('/teacher/lessons/l-9/files').flush({ jobId: 'j-1', status: 'analyzing' });
    backend.expectOne('/teacher/lessons/l-9/analyze').flush({ jobId: 'j-2', status: 'analyzing' });

    expect(service.lessonId()).toBe('l-9');
    expect(service.done()).toBe(true);
    // `done` is what the page would navigate on — and `background` is what stops it.
    expect(service.background()).toBe(true);
    expect(service.error()).toBeNull();
    backend.verify();
  });

  it('stops at an upload job that came back `error`, and never analyzes an unreadable file', () => {
    const { service, backend } = setUp();

    service.start(REQUEST, 'pdf', [file()]);
    backend.expectOne('/teacher/lessons').flush(LESSON);
    backend.expectOne('/teacher/lessons/l-9/files').flush({ jobId: 'j-1', status: 'error' });

    // No analyze, and no rollback either: the draft is hers to retry or to delete.
    backend.verify();
    expect(service.uploadFailed()).toBe(true);
    expect(service.step()).toBeNull();
    expect(service.error()).not.toBeNull();
    expect(service.lessonId()).toBe('l-9');

    service.retryUpload();
    backend.expectOne('/teacher/lessons/l-9/files').flush({ jobId: 'j-3', status: 'analyzing' });
    backend.expectOne('/teacher/lessons/l-9/analyze').flush({ jobId: 'j-4', status: 'analyzing' });
    expect(service.done()).toBe(true);
    backend.verify();
  });

  it('rolls the draft back when the upload itself fails, so no orphan reaches the list', () => {
    const { service, backend } = setUp();

    service.start(REQUEST, 'pdf', [file()]);
    backend.expectOne('/teacher/lessons').flush(LESSON);
    backend
      .expectOne('/teacher/lessons/l-9/files')
      .flush({ message: 'File too large' }, { status: 413, statusText: 'Payload Too Large' });

    const rollback = backend.expectOne('/teacher/lessons/l-9');
    expect(rollback.request.method).toBe('DELETE');
    rollback.flush({});
    expect(service.error()).toContain('the draft lesson was removed');
    expect(service.lessonId()).toBeNull();
    expect(service.done()).toBe(false);
  });
});
