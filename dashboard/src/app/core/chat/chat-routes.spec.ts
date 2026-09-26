import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ChatApi,
  ChatMessageSenderEnum,
  ChatThread,
  ChatThreadStaffRoleEnum,
  ChatThreadStatusEnum,
  ChatThreadTopicEnum,
  CoordinatorChatApi,
} from '../../api';
import { TEACHER_USER } from '../../../testing/fixtures';
import { AuthService, type Role } from '../auth/auth.service';
import { SessionStore } from '../auth/session.store';
import { FlagService } from '../flags/flag.service';
import { ChatRoutes } from './chat-routes';
import { ChatService } from './chat.service';

/**
 * R7: the seam between the two halves of one chat screen. The coordinator's routes are keyed by
 * **thread** and the teacher's by **child**, and the whole point of `ChatRoutes` is that the
 * screen never knows which — so these tests are about *which endpoint was called with what*.
 */
describe('ChatRoutes', () => {
  const parentThread: ChatThread = {
    id: 'th-1',
    childId: 'ch-1',
    childName: 'Layla',
    className: '1A British',
    teacherId: 'u-rasha',
    teacherName: 'Rasha Kamal',
    unread: 3,
    staffRole: ChatThreadStaffRoleEnum.COORDINATOR,
    topic: ChatThreadTopicEnum.COMPLAINT,
    status: ChatThreadStatusEnum.OPEN,
  };

  /** No child on it at all, which is why a coordinator's routes cannot be keyed by one. */
  const staffThread: ChatThread = {
    ...parentThread,
    id: 'th-2',
    childId: '',
    childName: '',
    className: undefined,
    teacherId: 'u-manager',
    teacherName: 'Nada Fahad',
    unread: 0,
    staffRole: ChatThreadStaffRoleEnum.MANAGERIAL,
    topic: ChatThreadTopicEnum.QUESTION,
  };

  let teacherApi: Partial<ChatApi>;
  let coordinatorApi: Partial<CoordinatorChatApi>;
  const role = signal<Role | null>('COORDINATOR');

  function setup(): { routes: ChatRoutes; chat: ChatService } {
    teacherApi = {
      teacherChatThreads: vi.fn().mockReturnValue(of([parentThread])),
      teacherChatMessages: vi.fn().mockReturnValue(of([])),
      teacherSendChatMessage: vi.fn().mockReturnValue(of({})),
      teacherMarkChatRead: vi.fn().mockReturnValue(of({})),
    };
    coordinatorApi = {
      coordinatorChatThreads: vi.fn().mockReturnValue(of([parentThread, staffThread])),
      coordinatorChatMessages: vi.fn().mockReturnValue(of([])),
      coordinatorSendChatMessage: vi.fn().mockReturnValue(of({})),
      coordinatorMarkChatRead: vi.fn().mockReturnValue(of({})),
    };

    TestBed.configureTestingModule({
      providers: [
        { provide: ChatApi, useValue: teacherApi },
        { provide: CoordinatorChatApi, useValue: coordinatorApi },
        {
          provide: AuthService,
          useValue: {
            signedIn: signal(true),
            role,
            user: signal({ ...TEACHER_USER, id: 'u-rasha' }),
            refresh: vi.fn().mockReturnValue(of('t')),
          },
        },
        { provide: SessionStore, useValue: { accessToken: signal('jwt') } },
        { provide: FlagService, useValue: { isOn: vi.fn().mockReturnValue(true) } },
      ],
    });
    return { routes: TestBed.inject(ChatRoutes), chat: TestBed.inject(ChatService) };
  }

  beforeEach(() => {
    role.set('COORDINATOR');
    TestBed.resetTestingModule();
  });

  it('keys a coordinator by thread and reads her own routes', () => {
    const { routes, chat } = setup();

    expect(routes.transport()?.keyOf(staffThread)).toBe('th-2');
    expect(routes.transport()?.commandKey('th-2')).toEqual({ threadId: 'th-2' });

    chat.loadThreads();
    expect(coordinatorApi.coordinatorChatThreads).toHaveBeenCalled();
    expect(teacherApi.teacherChatThreads).not.toHaveBeenCalled();

    // A staff thread has no child, so selecting it by its own id is the only thing that works.
    chat.selectThread('th-2');
    expect(coordinatorApi.coordinatorChatMessages).toHaveBeenCalledWith('th-2', undefined, undefined);
    chat.sendMessage('About 3B');
    expect(coordinatorApi.coordinatorSendChatMessage).toHaveBeenCalledWith('th-2', { body: 'About 3B' });
    expect(coordinatorApi.coordinatorMarkChatRead).toHaveBeenCalledWith('th-2');
  });

  it('keys a teacher by child and never touches the coordinator routes', () => {
    role.set('TEACHER');
    const { routes, chat } = setup();

    expect(routes.transport()?.keyOf(parentThread)).toBe('ch-1');
    expect(routes.transport()?.commandKey('ch-1')).toEqual({ childId: 'ch-1' });

    chat.loadThreads();
    chat.selectThread('ch-1');
    expect(teacherApi.teacherChatMessages).toHaveBeenCalledWith('ch-1', undefined, undefined);
    expect(coordinatorApi.coordinatorChatThreads).not.toHaveBeenCalled();
  });

  /**
   * A manager holds the socket (the frames of her staff threads arrive on it) and no chat REST at
   * all until RM2. Asking anyway would be a 403 in a red band on every reconnect, so the transport
   * is `null` and her inbox is built from the frames themselves.
   */
  it('gives a manager no transport, and builds her thread rows from the frames', () => {
    role.set('MANAGERIAL');
    const { routes, chat } = setup();

    expect(routes.transport()).toBeNull();
    expect(routes.listensOnly()).toBe(true);
    chat.loadThreads();
    expect(coordinatorApi.coordinatorChatThreads).not.toHaveBeenCalled();
    expect(teacherApi.teacherChatThreads).not.toHaveBeenCalled();

    chat.receive({
      type: 'message',
      message: {
        id: 'm-1',
        threadId: 'th-2',
        sender: ChatMessageSenderEnum.TEACHER,
        senderId: 'u-rasha',
        body: 'Could you look at 3B?',
        createdAt: 1700000000000,
      },
    });
    expect(chat.threads()).toHaveLength(1);
    expect(chat.threads()[0]?.id).toBe('th-2');
    expect(chat.totalUnread()).toBe(1);
  });

  it('moves a thread to resolved on the status frame, without a refetch', () => {
    const { chat } = setup();
    chat.loadThreads();

    chat.receive({ type: 'status', threadId: 'th-1', status: 'resolved', at: 1700000009000 });

    const row = chat.threads().find((t) => t.id === 'th-1');
    expect(row?.status).toBe(ChatThreadStatusEnum.RESOLVED);
    expect(row?.resolvedAt).toBe(1700000009000);
    expect(coordinatorApi.coordinatorChatThreads).toHaveBeenCalledTimes(1);
  });
});
