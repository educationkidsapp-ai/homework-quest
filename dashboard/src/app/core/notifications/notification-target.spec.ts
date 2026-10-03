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
   * where `?open=` comes from, and the Announcements page reads it.
   */
  it('opens a broadcast in the reader own feed, scrolled to the row it names', () => {
    const item = row(NotificationViewKindEnum.BROADCAST_POSTED, { lessonId: 'b-9' });
    expect(target(item, 'TEACHER')).toBe('/teacher/announcements?open=b-9');
    expect(target(item, 'COORDINATOR')).toBe('/coordinator/announcements?open=b-9');
    expect(target(item, 'MANAGERIAL')).toBe('/management/announcements?open=b-9');
    // An Admin has no feed of her own; the row itself is all there is to show her.
    expect(target(item, 'ADMIN')).toBe('/notifications');
    // Without an id the feed is still the right screen — just not one row of it.
    expect(target(row(NotificationViewKindEnum.BROADCAST_POSTED), 'MANAGERIAL')).toBe(
      '/management/announcements',
    );
  });

  /**
   * MH2 item 5: the server still writes `/<area>/broadcasts?open=…` (`broadcastLink`), and that path
   * is a redirect row now — which resolves, but drops the query on the way, so the row the bell named
   * would not open. The rename is applied here, which also keeps every notification written before
   * this deploy working.
   */
  it('rewrites the paths MH2 renamed, keeping the query', () => {
    const withLink = (link: string) =>
      row(NotificationViewKindEnum.BROADCAST_POSTED, { link, lessonId: 'b-9' });
    expect(target(withLink('/teacher/broadcasts?open=b-2'), 'TEACHER')).toBe(
      '/teacher/announcements?open=b-2',
    );
    expect(target(withLink('/coordinator/broadcasts'), 'COORDINATOR')).toBe('/coordinator/announcements');
    expect(target(withLink('/management/people'), 'MANAGERIAL')).toBe('/management/children');
    // And `/management/children` itself is a live screen again, not one of MG2a's retired rows —
    // only the child report under it is.
    expect(target(withLink('/management/children'), 'MANAGERIAL')).toBe('/management/children');
    expect(target(withLink('/management/children/ch-1'), 'MANAGERIAL')).toBe(
      '/management/announcements?open=b-9',
    );
  });

  it("sends a teacher's message to the threads list of whoever is reading it", () => {
    const item = row(NotificationViewKindEnum.TEACHER_MESSAGE);
    expect(target(item, 'MANAGERIAL')).toBe('/management/messages');
    expect(target(item, 'COORDINATOR')).toBe('/coordinator/messages');
    expect(target(item, 'ADMIN')).toBe('/admin/messages');
    // MG2b: she *is* a party to it now — MG1 files the message in her thread with the manager, so
    // the answer comes back on a row she holds. Her list lives at `/teacher/chat`.
    expect(target(item, 'TEACHER')).toBe('/teacher/chat');
    // R7's thread deep link, once a sender carries the id.
    expect(target(row(NotificationViewKindEnum.TEACHER_MESSAGE, { lessonId: 't-3' }), 'MANAGERIAL')).toBe(
      '/management/messages?thread=t-3',
    );
  });

  /**
   * MG2b blocker 4: `NotificationService.threadLink` always writes `/management/messages?thread=…`
   * — it was written for the manager, and MG1 made a teacher a party to the same conversation. The
   * thread is the same row whoever opens it, so the link is rewritten to the reader's own screen
   * rather than sent to the notifications page.
   */
  it('rewrites a thread link written for another area to the reader’s own list', () => {
    const item = row(NotificationViewKindEnum.TEACHER_MESSAGE, {
      link: '/management/messages?thread=t-7',
      lessonId: '',
    });
    expect(target(item, 'TEACHER')).toBe('/teacher/chat?thread=t-7');
    expect(target(item, 'COORDINATOR')).toBe('/coordinator/messages?thread=t-7');
    // Her own area's link is followed as it stands, as it always was.
    expect(target(item, 'MANAGERIAL')).toBe('/management/messages?thread=t-7');
    // The path is thrown away and only the id is read, so an absolute URL cannot send her out.
    expect(target({ ...item, link: 'https://evil.example/management/messages?thread=t-7' }, 'TEACHER')).toBe(
      '/teacher/chat?thread=t-7',
    );
  });

  /**
   * T2 item (c): T1's **`chat.message`**, the kind nobody had told this function about.
   *
   * It needs no case of its own and deliberately has none: the row carries a
   * `/<area>/messages?thread=…` link, and the two rules above already cover both halves of that —
   * the reader's own area is followed as written, and anybody else's is rewritten to the reader's
   * own list by the thread id alone. A `chat.message` with no link at all is the notifications
   * page, which is the one screen that can always show the row itself.
   */
  it("sends a chat.message row to the reader's own conversation", () => {
    const item = { ...row('teacher.message' as never), kind: 'chat.message' as never, lessonId: '' };
    const link = '/management/messages?thread=t-9';
    expect(target({ ...item, link }, 'TEACHER')).toBe('/teacher/chat?thread=t-9');
    expect(target({ ...item, link }, 'COORDINATOR')).toBe('/coordinator/messages?thread=t-9');
    expect(target({ ...item, link }, 'MANAGERIAL')).toBe('/management/messages?thread=t-9');
    expect(target({ ...item, link: '' }, 'TEACHER')).toBe('/notifications');
  });

  /**
   * MG1 is making the server write role-correct links. The resolver has to prefer them the day
   * they land — with two exceptions it must not: a link into somebody else's area, which is
   * exactly the bug this function exists for, and one of the manager's retired screens.
   */
  it("prefers the server's own link when it is inside the reader own area", () => {
    const withLink = (link: string, kind = NotificationViewKindEnum.BROADCAST_POSTED) =>
      row(kind, { link, lessonId: 'b-9' });

    expect(target(withLink('/management/announcements?open=b-2'), 'MANAGERIAL')).toBe(
      '/management/announcements?open=b-2',
    );
    // Another role's area: the kind decides instead.
    expect(target(withLink('/teacher/announcements'), 'MANAGERIAL')).toBe(
      '/management/announcements?open=b-9',
    );
    // A screen she no longer has: likewise.
    expect(
      target(withLink('/management/lessons/l-1', NotificationViewKindEnum.LESSON_FAILED), 'MANAGERIAL'),
    ).toBe('/notifications');
    // A coordinator still has hers, so the same link is followed for her.
    expect(
      target(withLink('/coordinator/lessons/l-1', NotificationViewKindEnum.LESSON_FAILED), 'COORDINATOR'),
    ).toBe('/coordinator/lessons/l-1');
    // Not a path of her area, however it is spelled.
    expect(target(withLink('https://evil.example/management/announcements'), 'MANAGERIAL')).toBe(
      '/management/announcements?open=b-9',
    );
    expect(target(withLink('/managementx/announcements'), 'MANAGERIAL')).toBe(
      '/management/announcements?open=b-9',
    );
  });

  /**
   * D5: B6's three complaint kinds open the complaint on the reader's own Complaints page — the
   * server's link when it is hers, else rebuilt from the entity id — and never Messages.
   */
  it('opens a complaint row on the reader own Complaints page', () => {
    const kinds = [
      NotificationViewKindEnum.COMPLAINT_NEW,
      NotificationViewKindEnum.COMPLAINT_MESSAGE,
      NotificationViewKindEnum.COMPLAINT_STATUS,
    ];
    for (const kind of kinds) {
      const item = row(kind, { link: '/management/complaints?open=c-7', lessonId: 'c-7' });
      expect(target(item, 'MANAGERIAL')).toBe('/management/complaints?open=c-7');
      // Written for another area (she changed role, or a role-blind sender): her own page.
      expect(target(item, 'TEACHER')).toBe('/teacher/complaints?open=c-7');
      expect(target(item, 'COORDINATOR')).toBe('/coordinator/complaints?open=c-7');
      expect(target(row(kind, { lessonId: '' }), 'TEACHER')).toBe('/teacher/complaints');
      expect(target(item, null)).toBe('/notifications');
    }
  });

  it('falls back to the notifications page before /me has landed', () => {
    expect(target(row(NotificationViewKindEnum.BROADCAST_POSTED, { lessonId: 'b-1' }), null)).toBe(
      '/notifications',
    );
  });
});
