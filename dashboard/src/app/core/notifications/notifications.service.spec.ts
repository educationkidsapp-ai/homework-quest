import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { BASE_PATH, type NotificationView, NotificationViewKindEnum } from '../../api';
import { TEACHER_USER } from '../../../testing/fixtures';
import { AuthService } from '../auth/auth.service';
import { SessionStore } from '../auth/session.store';
import { NotificationsService, bodyKeyOf, titleKeyOf } from './notifications.service';

function row(over: Partial<NotificationView> = {}): NotificationView {
  return {
    id: 'n-1',
    kind: NotificationViewKindEnum.LESSON_READY,
    title: 'Questions ready',
    link: '/teacher/lessons/l-1',
    lessonId: 'l-1',
    createdAt: 1_700_000_000_000,
    ...over,
  };
}

function signIn(): { service: NotificationsService; backend: HttpTestingController } {
  const backend = TestBed.inject(HttpTestingController);
  const service = TestBed.inject(NotificationsService);
  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(TEACHER_USER);
  TestBed.tick();
  // Signing in asks for the badge, and nothing else until the bell is opened.
  backend.expectOne('/me/notifications/unread-count').flush({ count: 1 });
  return { service, backend };
}

/**
 * E3: the bell, over E2's rows rather than over five objects in `localStorage`.
 */
describe('NotificationsService', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: BASE_PATH, useValue: '' }],
    });
  });

  it('reads the badge on sign-in and the list when the bell is opened', () => {
    const { service, backend } = signIn();
    expect(service.unreadCount()).toBe(1);
    expect(service.notifications()).toEqual([]);

    service.refresh();
    backend
      .expectOne((request) => request.url === '/me/notifications')
      .flush([row(), row({ id: 'n-0', readAt: 1_700_000_001_000 })]);

    expect(service.notifications().map((item) => item.id)).toEqual(['n-1', 'n-0']);
    expect(service.recent()).toHaveLength(2);
    expect(service.unreadCount()).toBe(1);
    backend.verify();
  });

  it('takes a socket frame straight into the list, the badge and the toast', () => {
    const { service, backend } = signIn();

    service.receive(row({ id: 'n-2', kind: NotificationViewKindEnum.LESSON_NEEDS_SKILLS }));

    expect(service.notifications().map((item) => item.id)).toEqual(['n-2']);
    expect(service.unreadCount()).toBe(2);
    expect(service.toast()?.id).toBe('n-2');
    // Nothing is replayed by the socket, so a frame is not a reason to refetch anything.
    backend.verify();
  });

  it('refetches the count on every (re)connect, because a missed frame is never resent', () => {
    const { service, backend } = signIn();

    service.onSocketOpen();
    backend.expectOne('/me/notifications/unread-count').flush({ count: 4 });
    expect(service.unreadCount()).toBe(4);
    expect(service.socketOpen()).toBe(true);
    backend.verify();
  });

  it('marks one read at once and confirms with the server; mark-all zeroes the badge', () => {
    const { service, backend } = signIn();
    service.refresh();
    backend.expectOne((request) => request.url === '/me/notifications').flush([row(), row({ id: 'n-3' })]);
    expect(service.unreadCount()).toBe(2);

    service.markRead('n-1');
    // Optimistic: the dot is gone before the POST answers.
    expect(service.unreadCount()).toBe(1);
    const read = backend.expectOne('/me/notifications/n-1/read');
    expect(read.request.method).toBe('POST');
    read.flush(row({ readAt: 1_700_000_002_000 }));
    expect(service.notifications()[0]?.readAt).toBe(1_700_000_002_000);

    service.markAllRead();
    expect(service.unreadCount()).toBe(0);
    expect(service.notifications().every((item) => item.readAt !== undefined)).toBe(true);
    backend.expectOne('/me/notifications/read-all').flush({ count: 0 });
    backend.verify();
  });

  it('keys its copy off the kind, not off the English the server wrote', () => {
    expect(titleKeyOf(NotificationViewKindEnum.LESSON_READY)).toBe('notifications.kind.lesson.ready.title');
    expect(bodyKeyOf(NotificationViewKindEnum.LESSON_FAILED)).toBe('notifications.kind.lesson.failed.body');
  });
});
