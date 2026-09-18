import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { BASE_PATH } from '../../api';
import { ADMIN_USER, TEACHER_USER } from '../../../testing/fixtures';
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
  it('publishes a teacher lesson to her sibling classes, then reads the lesson back', () => {
    const { api, backend } = signIn(TEACHER_USER);

    api.publish('l-1', ['c-1', 'c-2']).subscribe();
    const published = backend.expectOne('/teacher/lessons/l-1/publish');
    expect(published.request.body).toEqual({ classIds: ['c-1', 'c-2'] });
    published.flush([{ classId: 'c-1', lessonId: 'l-1', version: 2 }]);

    backend.expectOne('/teacher/lessons/l-1');
  });

  /** Two operations have no teacher alias — the façade says so instead of calling `/admin/**`. */
  it('refuses the two ADMIN-only operations for a Teacher rather than falling back to /admin', () => {
    const { api, backend } = signIn(TEACHER_USER);
    expect(api.supportsCreateLevel()).toBe(false);
    expect(api.supportsFileDeletion()).toBe(false);

    let createFailed = false;
    api.createPlay('l-1', '{}').subscribe({ error: () => (createFailed = true) });
    let deleteFailed = false;
    api.deleteFiles('l-1').subscribe({ error: () => (deleteFailed = true) });

    expect(createFailed).toBe(true);
    expect(deleteFailed).toBe(true);
    backend.verify();
  });
});
