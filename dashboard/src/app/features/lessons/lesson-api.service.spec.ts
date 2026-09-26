import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { BASE_PATH } from '../../api';
import { ADMIN_USER, MANAGERIAL_USER, TEACHER_USER } from '../../../testing/fixtures';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { LessonApiService } from './lesson-api.service';
import { reorderBody, stopBody } from './lessons.models';

/**
 * The whole point of the façade is that a teacher never touches `/admin/**`: the admin routes
 * are tenant-wide, so a bug there is a teacher reading a colleague's lesson, not a 403 someone
 * would notice. Every operation is checked on both sides.
 */
function signIn(user: typeof ADMIN_USER): { api: LessonApiService; backend: HttpTestingController } {
  const backend = TestBed.inject(HttpTestingController);
  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(user);
  TestBed.tick();
  // No `/me/permissions` here: nothing renders `*hqCan`, so nothing asks for the matrix.
  return { api: TestBed.inject(LessonApiService), backend };
}

describe('LessonApiService', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: BASE_PATH, useValue: '' }],
    });
  });

  it('reads, saves and reorders through /admin/** for an Admin', () => {
    const { api, backend } = signIn(ADMIN_USER);

    api.getLesson('l-1').subscribe();
    backend.expectOne('/admin/lessons/l-1');

    api.updateStop('s-1', stopBody({ id: 's-1' })).subscribe();
    expect(backend.expectOne('/admin/stops/s-1').request.method).toBe('PUT');

    // The body is the kotlinx JSON verbatim, not an object — see `LessonApiService`'s header.
    api.reorder('p-1', reorderBody(['s-2', 's-1'])).subscribe();
    expect(backend.expectOne('/admin/plays/p-1/order').request.body).toBe('{"stopIds":["s-2","s-1"]}');

    api.updatePanel('l-1', '{}').subscribe();
    backend.expectOne('/admin/lessons/l-1/parent-panel');
    expect(api.isAdmin()).toBe(true);
  });

  it('reads, saves and reorders through /teacher/** for a Teacher', () => {
    const { api, backend } = signIn(TEACHER_USER);

    api.getLesson('l-1').subscribe();
    backend.expectOne('/teacher/lessons/l-1');

    api.updateStop('s-1', stopBody({ id: 's-1' })).subscribe();
    expect(backend.expectOne('/teacher/stops/s-1').request.method).toBe('PUT');

    api.addStop('p-1', stopBody({ id: 's-9' })).subscribe();
    backend.expectOne('/teacher/plays/p-1/stops');

    api.deleteStop('s-1').subscribe();
    expect(backend.expectOne('/teacher/stops/s-1').request.method).toBe('DELETE');

    api.reorder('p-1', reorderBody(['s-2', 's-1'])).subscribe();
    expect(backend.expectOne('/teacher/plays/p-1/order').request.body).toBe('{"stopIds":["s-2","s-1"]}');

    api.updatePanel('l-1', '{}').subscribe();
    backend.expectOne('/teacher/lessons/l-1/parent-panel');
    expect(api.isAdmin()).toBe(false);
  });

  /** The teacher route answers with the copies it made, so the lesson itself is re-read. */
  /** E5: one level on request, on the route family of whoever is signed in. */
  it('asks for one level through the route family of the signed-in role', () => {
    const teacher = signIn(TEACHER_USER);
    teacher.api.generateLevel('l-1', 'again').subscribe();
    const asked = teacher.backend.expectOne('/teacher/lessons/l-1/plays/again/generate?replace=false');
    expect(asked.request.method).toBe('POST');

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: BASE_PATH, useValue: '' }],
    });
    const admin = signIn(ADMIN_USER);
    admin.api.generateLevel('l-1', '2', true).subscribe();
    admin.backend.expectOne('/admin/lessons/l-1/plays/2/generate?replace=true');
  });

  it('publishes a teacher lesson into its own class, then reads the lesson back', () => {
    const { api, backend } = signIn(TEACHER_USER);

    api.publish('l-1', 'c-1').subscribe();
    const published = backend.expectOne('/teacher/lessons/l-1/publish');
    expect(published.request.body).toEqual({ classIds: ['c-1'] });
    published.flush([{ classId: 'c-1', lessonId: 'l-1', version: 2 }]);

    backend.expectOne('/teacher/lessons/l-1');
  });

  /** An empty `classIds` is a 400 from the server, so the façade never sends one. */
  it('refuses to publish a teacher lesson that does not name its class', () => {
    const { api, backend } = signIn(TEACHER_USER);

    let failed = false;
    api.publish('l-1').subscribe({ error: () => (failed = true) });
    expect(failed).toBe(true);
    backend.verify();
  });

  it('fans a teacher lesson out to every class the sheet ticked', () => {
    const { api, backend } = signIn(TEACHER_USER);

    api.publishToClasses('l-1', ['c-1', 'c-2']).subscribe();
    const published = backend.expectOne('/teacher/lessons/l-1/publish');
    expect(published.request.body).toEqual({ classIds: ['c-1', 'c-2'] });
  });

  /** The list and the create are the two the pages used to call `/admin/**` with directly. */
  it('lists and creates through the route family of the signed-in role', () => {
    const { api, backend } = signIn(TEACHER_USER);

    api.list({ curriculum: 'british', grade: 1, classId: 'c-1' }).subscribe();
    const listed = backend.expectOne((r) => r.url === '/teacher/lessons');
    expect(listed.request.params.get('grade')).toBe('1');
    expect(listed.request.params.get('classId')).toBe('c-1');

    api
      .create({ classId: 'c-1', subject: 'math', date: '2026-09-18', source: 'pdf', practiceLength: 7 })
      .subscribe();
    const created = backend.expectOne('/teacher/lessons');
    expect(created.request.body).toEqual({
      classId: 'c-1',
      subject: 'math',
      date: '2026-09-18',
      source: 'pdf',
      practiceLength: 7,
    });
  });

  it('lists and creates through /admin/** for an Admin', () => {
    const { api, backend } = signIn(ADMIN_USER);

    api.list({ curriculum: 'british', grade: 1, schoolId: 's-1' }).subscribe();
    const listed = backend.expectOne((r) => r.url === '/admin/lessons');
    expect(listed.request.params.get('schoolId')).toBe('s-1');

    api
      .create({ curriculum: 'british', grade: 1, subject: 'math', date: '2026-09-18', source: 'manual' })
      .subscribe();
    const created = backend.expectOne('/admin/lessons');
    expect(JSON.parse(created.request.body as string)).toEqual({
      curriculum: 'british',
      grade: 1,
      subject: 'math',
      date: '2026-09-18',
      source: 'manual',
    });
  });

  /**
   * A manager holds no teaching assignment, so `/teacher/**` would 404 on every read of hers;
   * she reads through the tenant-wide Admin routes and writes nothing (`*hqCan`).
   */
  it('reads through /admin/** for a MANAGERIAL account', () => {
    const { api, backend } = signIn(MANAGERIAL_USER);

    api.getLesson('l-1').subscribe();
    backend.expectOne('/admin/lessons/l-1');
    expect(api.isAdmin()).toBe(true);
  });

  /** The two the teacher route now serves — N2.4b's `plays` and `files` aliases. */
  it('creates a level and clears the files through /teacher/** for a Teacher', () => {
    const { api, backend } = signIn(TEACHER_USER);

    api.createPlay('l-1', '{}').subscribe();
    expect(backend.expectOne('/teacher/lessons/l-1/plays').request.method).toBe('POST');

    api.deleteFiles('l-1').subscribe();
    expect(backend.expectOne('/teacher/lessons/l-1/files').request.method).toBe('DELETE');
  });

  /** What still has no teacher alias says so instead of quietly calling `/admin/**`. */
  it('refuses the Admin-only sweep and the teacher-only move for the wrong role', () => {
    const teacher = signIn(TEACHER_USER);
    expect(teacher.api.supportsDeleteFailed()).toBe(false);
    let sweepFailed = false;
    teacher.api.deleteFailed().subscribe({ error: () => (sweepFailed = true) });
    expect(sweepFailed).toBe(true);

    let noClass = false;
    teacher.api
      .create({ subject: 'math', date: '2026-09-18', source: 'pdf' })
      .subscribe({ error: () => (noClass = true) });
    expect(noClass).toBe(true);
    teacher.backend.verify();
  });

  it('moves an unpublished lesson to another day, teacher-only', () => {
    const { api, backend } = signIn(TEACHER_USER);

    expect(api.supportsMoveDate()).toBe(true);
    api.moveDate('l-1', '2026-09-21').subscribe();
    const moved = backend.expectOne('/teacher/lessons/l-1');
    expect(moved.request.method).toBe('PATCH');
    expect(moved.request.body).toEqual({ date: '2026-09-21' });
  });
});
