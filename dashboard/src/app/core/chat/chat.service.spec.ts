import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ChatApi, ChatMessage, ChatMessageSenderEnum, ChatThread } from '../../api';
import { TEACHER_USER } from '../../../testing/fixtures';
import { AuthService } from '../auth/auth.service';
import { SessionStore } from '../auth/session.store';
import { FlagService } from '../flags/flag.service';
import { ChatService } from './chat.service';

describe('ChatService', () => {
  let service: ChatService;
  let mockApi: Partial<ChatApi>;
  let mockAuth: Partial<AuthService>;
  let mockSession: Partial<SessionStore>;
  let mockFlags: Partial<FlagService>;

  const sampleThread: ChatThread = {
    id: 'th-1',
    childId: 'ch-1',
    childName: 'Layla',
    className: '1A British',
    subject: 'English',
    teacherId: 'u-sara',
    teacherName: 'Ms Sara',
    unread: 2,
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
    };

    mockAuth = {
      signedIn: signal(true),
      role: signal('TEACHER' as const),
      user: signal({ ...TEACHER_USER, id: 'u-sara', displayName: 'Ms Sara' }),
      refresh: vi.fn().mockReturnValue(of('new-token')),
    };

    mockSession = {
      accessToken: signal('test-jwt'),
    };

    mockFlags = {
      isOn: vi.fn().mockReturnValue(true),
    };

    TestBed.configureTestingModule({
      providers: [
        ChatService,
        { provide: ChatApi, useValue: mockApi },
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

    expect(service.activeChildId()).toBe('ch-1');
    expect(mockApi.teacherChatMessages).toHaveBeenCalledWith('ch-1');
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

    expect(service.activeChildId()).toBe('ch-9');
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
});
