import { describe, expect, it } from 'vitest';
import { type NotificationView, NotificationViewKindEnum } from '../../api';
import type { Role } from '../auth/auth.service';
import { notificationTarget, notificationUrl } from './notification-target';

function row(kind: NotificationViewKindEnum, extra: Partial<NotificationView> = {}): NotificationView {
  return { id: 'n-1', kind, title: 'x', createdAt: 0, ...extra };
}

/** The whole answer in one string, which is what both call sites end up navigating to. */
function target(item: NotificationView, role: Role | null): string {
  return notificationUrl(notificationTarget(item, role));
}

describe('MG2a — where a notification sends its reader', () => {
  it('sends the three lesson kinds to that reader own lesson page, and never a manager', () => {
    for (const kind of [
      NotificationViewKindEnum.LESSON_NEEDS_SKILLS,
      NotificationViewKindEnum.LESSON_READY,
      NotificationViewKindEnum.LESSON_FAILED,
    ]) {
      const item = row(kind, { lessonId: 'l-1' });
      expect(target(item, 'TEACHER')).toBe('/teacher/lessons/l-1');
      expect(target(item, 'ADMIN')).toBe('/admin/lessons/l-1');
      expect(target(item, 'COORDINATOR')).toBe('/coordinator/lessons/l-1');
      // MG2a took All lessons and the read-only lesson page off her rail.
      expect(target(item, 'MANAGERIAL')).toBe('/notifications');
    }
    // A lesson kind with no id is a row that cannot open anything but itself.
    expect(target(row(NotificationViewKindEnum.LESSON_READY), 'TEACHER')).toBe('/notifications');
  });

  /**
   * `lessonId` is E2's entity id rather than a lesson's alone: `BroadcastService` puts the
   * broadcast's id there, which is how a superseded weekly plan's bell rows are found. That is
   * where `?open=` comes from, and the Broadcasts page reads it.
   */
  it('opens a broadcast in the reader own feed, scrolled to the row it names', () => {
    const item = row(NotificationViewKindEnum.BROADCAST_POSTED, { lessonId: 'b-9' });
    expect(target(item, 'TEACHER')).toBe('/teacher/broadcasts?open=b-9');
    expect(target(item, 'COORDINATOR')).toBe('/coordinator/broadcasts?open=b-9');
    expect(target(item, 'MANAGERIAL')).toBe('/management/broadcasts?open=b-9');
    // An Admin has no feed of her own; the row itself is all there is to show her.
    expect(target(item, 'ADMIN')).toBe('/notifications');
    // Without an id the feed is still the right screen — just not one row of it.
    expect(target(row(NotificationViewKindEnum.BROADCAST_POSTED), 'MANAGERIAL')).toBe(
      '/management/broadcasts',
    );
  });

  it("sends a teacher's message to the inbox of whoever handles it", () => {
    const item = row(NotificationViewKindEnum.TEACHER_MESSAGE);
    expect(target(item, 'MANAGERIAL')).toBe('/management/messages');
    expect(target(item, 'COORDINATOR')).toBe('/coordinator/messages');
    expect(target(item, 'ADMIN')).toBe('/admin/messages');
    // A teacher never receives one, so there is nowhere to send her but the page.
    expect(target(item, 'TEACHER')).toBe('/notifications');
    // R7's thread deep link, once a sender carries the id.
    expect(target(row(NotificationViewKindEnum.TEACHER_MESSAGE, { lessonId: 't-3' }), 'MANAGERIAL')).toBe(
      '/management/messages?thread=t-3',
    );
  });

  /**
   * MG1 is making the server write role-correct links. The resolver has to prefer them the day
   * they land — with two exceptions it must not: a link into somebody else's area, which is
   * exactly the bug this function exists for, and one of the manager's retired screens.
   */
  it("prefers the server's own link when it is inside the reader own area", () => {
    const withLink = (link: string, kind = NotificationViewKindEnum.BROADCAST_POSTED) =>
      row(kind, { link, lessonId: 'b-9' });

    expect(target(withLink('/management/broadcasts?open=b-2'), 'MANAGERIAL')).toBe(
      '/management/broadcasts?open=b-2',
    );
    // Another role's area: the kind decides instead.
    expect(target(withLink('/teacher/broadcasts'), 'MANAGERIAL')).toBe('/management/broadcasts?open=b-9');
    // A screen she no longer has: likewise.
    expect(
      target(withLink('/management/lessons/l-1', NotificationViewKindEnum.LESSON_FAILED), 'MANAGERIAL'),
    ).toBe('/notifications');
    // A coordinator still has hers, so the same link is followed for her.
    expect(
      target(withLink('/coordinator/lessons/l-1', NotificationViewKindEnum.LESSON_FAILED), 'COORDINATOR'),
    ).toBe('/coordinator/lessons/l-1');
    // Not a path of her area, however it is spelled.
    expect(target(withLink('https://evil.example/management/broadcasts'), 'MANAGERIAL')).toBe(
      '/management/broadcasts?open=b-9',
    );
    expect(target(withLink('/managementx/broadcasts'), 'MANAGERIAL')).toBe('/management/broadcasts?open=b-9');
  });

  it('falls back to the notifications page before /me has landed', () => {
    expect(target(row(NotificationViewKindEnum.BROADCAST_POSTED, { lessonId: 'b-1' }), null)).toBe(
      '/notifications',
    );
  });
});
