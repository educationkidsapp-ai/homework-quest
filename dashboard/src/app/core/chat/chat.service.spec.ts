import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ChatApi,
  CoordinatorChatApi,
  ChatMessage,
  ChatMessageSenderEnum,
  ChatThread,
  ChatThreadStaffRoleEnum,
  ChatThreadStatusEnum,
  ChatThreadTopicEnum,
} from '../../api';
import { TEACHER_USER } from '../../../testing/fixtures';
import { AuthService } from '../auth/auth.service';
import { SessionStore } from '../auth/session.store';
import { FlagService } from '../flags/flag.service';
import { NotificationsService } from '../notifications/notifications.service';
import { ChatService } from './chat.service';

describe('ChatService', () => {
  let service: ChatService;
  let mockApi: Partial<ChatApi>;
  let mockCoordinatorApi: Partial<CoordinatorChatApi>;
  let mockAuth: Partial<AuthService>;
  let mockSession: Partial<SessionStore>;
  let mockFlags: Partial<FlagService>;

  const accessTokenSig = signal<string | null>('test-jwt');

  const sampleThread: ChatThread = {
    id: 'th-1',
    childId: 'ch-1',
    childName: 'Layla',
    className: '1A British',
    subject: 'English',
    teacherId: 'u-sara',
    teacherName: 'Ms Sara',
    unread: 2,
    staffRole: ChatThreadStaffRoleEnum.TEACHER,
    topic: ChatThreadTopicEnum.QUESTION,
    status: ChatThreadStatusEnum.OPEN,
  };

  const sampleMessage: ChatMessage = {
    id: 'msg-1',
    threadId: 'th-1',
    sender: ChatMessageSenderEnum.PARENT,
    senderId: 'parent-1',
    body: 'Hello Ms Sara',
    createdAt: 1700000000000,
  };

  beforeEach(() => {
    mockApi = {
      teacherChatThreads: vi.fn().mockReturnValue(of([sampleThread])),
      teacherChatMessages: vi.fn().mockReturnValue(of([sampleMessage])),
      teacherSendChatMessage: vi.fn().mockReturnValue(of({ ...sampleMessage, id: 'msg-2', body: 'Reply' })),
      teacherMarkChatRead: vi.fn().mockReturnValue(of({ threadId: 'th-1', readAt: 1700000001000 })),
      // MG2b: a teacher reads two lists now (`ChatRoutes.transports`), so every one of her tests
      // loads both. Empty here — the merge itself is `chat-routes.spec.ts`'s subject.
      teacherStaffThreads: vi.fn().mockReturnValue(of([])),
      teacherStaffMessages: vi.fn().mockReturnValue(of([])),
      teacherSendStaffMessage: vi.fn().mockReturnValue(of({})),
      teacherMarkStaffRead: vi.fn().mockReturnValue(of({})),
    };

    mockCoordinatorApi = {
      coordinatorChatThreads: vi.fn().mockReturnValue(of([])),
      coordinatorChatMessages: vi.fn().mockReturnValue(of([])),
      coordinatorSendChatMessage: vi.fn().mockReturnValue(of(sampleMessage)),
      coordinatorMarkChatRead: vi.fn().mockReturnValue(of({ threadId: 'th-1', readAt: 1 })),
    };

    mockAuth = {
      signedIn: signal(true),
      role: signal('TEACHER' as const),
      user: signal({ ...TEACHER_USER, id: 'u-sara', displayName: 'Ms Sara' }),
      refresh: vi.fn().mockReturnValue(of('new-token')),
      // T2 follow-up: the socket asks for the refresh that cannot end the session.
      refreshForReconnect: vi.fn().mockReturnValue(of('new-token')),
    };

    accessTokenSig.set('test-jwt');
    mockSession = {
      accessToken: accessTokenSig,
    };

    mockFlags = {
      isOn: vi.fn().mockReturnValue(true),
    };

    TestBed.configureTestingModule({
      providers: [
        ChatService,
        { provide: ChatApi, useValue: mockApi },
        { provide: CoordinatorChatApi, useValue: mockCoordinatorApi },
        { provide: AuthService, useValue: mockAuth },
        { provide: SessionStore, useValue: mockSession },
        { provide: FlagService, useValue: mockFlags },
      ],
    });

    service = TestBed.inject(ChatService);
  });

  it('loads threads and computes totalUnread', () => {
    service.loadThreads();
    expect(mockApi.teacherChatThreads).toHaveBeenCalled();
    expect(service.threads().length).toBe(1);
    expect(service.threads()[0]?.childName).toBe('Layla');
    expect(service.totalUnread()).toBe(2);
  });

  it('selects thread, loads messages and marks read', () => {
    service.loadThreads();
    service.selectThread('ch-1');

    expect(service.activeKey()).toBe('ch-1');
    expect(mockApi.teacherChatMessages).toHaveBeenCalledWith('ch-1', undefined, undefined);
    expect(service.messages().length).toBe(1);
    expect(mockApi.teacherMarkChatRead).toHaveBeenCalledWith('ch-1');
    expect(service.totalUnread()).toBe(0);
  });

  it('sends message via REST when WebSocket is not open', () => {
    service.loadThreads();
    service.selectThread('ch-1');

    service.sendMessage('Hello parent');
    expect(mockApi.teacherSendChatMessage).toHaveBeenCalledWith('ch-1', { body: 'Hello parent' });
  });

  /**
   * U1 item 6 — "Message parent" on a child nobody has written to yet. The thread row is created
   * by the first message, so there is nothing in the list to select: the conversation is drawn
   * from the name the link carried and the composer works straight away.
   */
  it('opens a conversation for a child who has no thread yet, and does not mark it read', () => {
    service.loadThreads();
    service.openWith('ch-9', 'Amina', '2B British');

    expect(service.activeKey()).toBe('ch-9');
    expect(service.activeThread()).toMatchObject({ childId: 'ch-9', childName: 'Amina', unread: 0 });
    // `POST /teacher/chat/threads/ch-9/read` is a 404 without a thread, and the interceptor would
    // put that 404 in a red band over a conversation she has only just opened.
    expect(mockApi.teacherMarkChatRead).not.toHaveBeenCalled();

    service.sendMessage('Good afternoon');
    expect(mockApi.teacherSendChatMessage).toHaveBeenCalledWith('ch-9', { body: 'Good afternoon' });
  });

  it('prefers the server’s own thread over the placeholder once it exists', () => {
    service.openWith('ch-1', 'Layla');
    expect(service.activeThread()?.id).toBe('');

    service.loadThreads();
    expect(service.activeThread()?.id).toBe('th-1');
    // The list is also the moment the unread badge can finally be cleared.
    expect(mockApi.teacherMarkChatRead).toHaveBeenCalledWith('ch-1');
  });

  it('does not send empty messages or messages exceeding 2000 chars', () => {
    service.loadThreads();
    service.selectThread('ch-1');

    service.sendMessage('   ');
    expect(mockApi.teacherSendChatMessage).not.toHaveBeenCalled();

    service.sendMessage('a'.repeat(2001));
    expect(mockApi.teacherSendChatMessage).not.toHaveBeenCalled();
  });

  /**
   * T2 item (d). The pill used to read this tab's own socket and say "Live"; these four say where
   * the answer comes from now — a `presence` frame first, the thread row second, and nothing at
   * all when neither has spoken. The manager who signed out is the third of them.
   */
  describe('peer presence', () => {
    /** T1 names the parent on a parent thread; presence for anybody the row does not name is
        not this thread's peer and is ignored. */
    const withParent = (extra: Record<string, unknown> = {}) => {
      mockApi.teacherChatThreads = vi
        .fn()
        .mockReturnValue(of([{ ...sampleThread, parentId: 'parent-1', ...extra }]));
    };

    it('has no answer for a peer nobody has reported on', () => {
      withParent();
      service.loadThreads();
      service.selectThread('ch-1');
      expect(service.activePeerOnline()).toBeUndefined();
    });

    it('takes the peer from a presence frame, not from its own connection', () => {
      withParent();
      service.loadThreads();
      service.selectThread('ch-1');

      service.receive({ type: 'presence', parentId: 'parent-1', online: true });
      expect(service.activePeerOnline()).toBe(true);

      // The manager (or parent) signed out: one frame, and the pill stops saying Live even though
      // this tab's own socket never wavered.
      service.receive({ type: 'presence', parentId: 'parent-1', online: false });
      expect(service.activePeerOnline()).toBe(false);
    });

    it('ignores presence for somebody who is not on this thread, and never for herself', () => {
      withParent();
      service.loadThreads();
      service.selectThread('ch-1');

      service.receive({ type: 'presence', userId: 'u-other', online: true });
      expect(service.activePeerOnline()).toBeUndefined();
      // `u-sara` is the signed-in teacher and also this row's `teacherId`: her own presence is
      // not the peer's, or every thread would read as Live for ever.
      service.receive({ type: 'presence', userId: 'u-sara', online: true });
      expect(service.activePeerOnline()).toBeUndefined();
    });

    it('falls back to peerOnline on the thread row until a frame arrives', () => {
      withParent({ peerOnline: true });
      service.loadThreads();
      service.selectThread('ch-1');
      expect(service.activePeerOnline()).toBe(true);

      // A frame is newer than the last GET, so it wins.
      service.receive({ type: 'presence', parentId: 'parent-1', online: false });
      expect(service.activePeerOnline()).toBe(false);
    });

    it('forgets every presence when the socket closes, because sign-out closes the socket', () => {
      withParent();
      service.loadThreads();
      service.selectThread('ch-1');
      service.receive({ type: 'presence', parentId: 'parent-1', online: true });

      service.disconnect();
      expect(service.activePeerOnline()).toBeUndefined();
    });
  });

  /**
   * T2 item (c). The bell row and the badge are never in question; the toast is, and the rule is
   * "not for the conversation she is already reading".
   */
  describe('the chat.message notification frame', () => {
    const notification = (thread: string) => ({
      id: `n-${thread}`,
      kind: 'chat.message' as never,
      title: 'New message',
      createdAt: 1,
      link: `/management/messages?thread=${thread}`,
    });

    it('toasts a message for a conversation she is not looking at', () => {
      const notifications = TestBed.inject(NotificationsService);
      service.loadThreads();
      service.selectThread('ch-1');

      service.receive({ type: 'notification', notification: notification('th-9') });
      expect(notifications.toast()?.id).toBe('n-th-9');
      expect(notifications.unreadCount()).toBe(1);
    });

    it('files a message for the open conversation without a toast over it', () => {
      const notifications = TestBed.inject(NotificationsService);
      service.loadThreads();
      service.selectThread('ch-1');

      service.receive({ type: 'notification', notification: notification('th-1') });
      expect(notifications.toast()).toBeNull();
      // Still in the bell, and still counted: only the toast was the duplicate.
      expect(notifications.notifications()[0]?.id).toBe('n-th-1');
      expect(notifications.unreadCount()).toBe(1);
    });
  });

  /**
   * T2 follow-up. Cloud Run closes this socket every hour and every network blip reopens it, so if
   * the reconnect could forget the session the socket alone would sign her out mid-lesson.
   */
  it('reconnects through the refresh that cannot end the session', () => {
    // No access token in memory — a reload, or one that has aged out — so `connect` has to get one
    // before it can open the socket.
    accessTokenSig.set(null);
    service.connect();

    expect(mockAuth.refreshForReconnect).toHaveBeenCalled();
    expect(mockAuth.refresh).not.toHaveBeenCalled();
  });
});
