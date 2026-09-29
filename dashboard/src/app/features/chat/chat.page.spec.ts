import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { BehaviorSubject, map, of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHq } from '../../../testing/render';
import {
  ChatApi,
  ChatMessageSenderEnum,
  ChatThread,
  ChatThreadStaffRoleEnum,
  ChatThreadStatusEnum,
  ChatThreadTopicEnum,
} from '../../api';
import { AuthService } from '../../core/auth/auth.service';
import { LocalMessage } from '../../core/chat/chat.models';
import { ChatService } from '../../core/chat/chat.service';
import { FlagService } from '../../core/flags/flag.service';
import { ChatPage } from './chat.page';

describe('ChatPage', () => {
  let mockChatService: Partial<ChatService>;
  let mockFlags: Partial<FlagService>;
  const activeKeySig = signal<string | null>(null);
  const activeThreadSig = signal<ChatThread | null>(null);
  const messagesSig = signal<LocalMessage[]>([]);
  const canWriteSig = signal(true);

  const sampleThread: ChatThread = {
    id: 'th-1',
    childId: 'ch-1',
    childName: 'Layla',
    className: '1A British',
    subject: 'English',
    teacherId: 'u-sara',
    teacherName: 'Ms Sara',
    unread: 1,
    lastMessage: {
      id: 'msg-1',
      threadId: 'th-1',
      sender: ChatMessageSenderEnum.PARENT,
      senderId: 'parent-1',
      body: 'Hello teacher',
      createdAt: 1700000000000,
    },
    staffRole: ChatThreadStaffRoleEnum.TEACHER,
    topic: ChatThreadTopicEnum.QUESTION,
    status: ChatThreadStatusEnum.OPEN,
  };

  beforeEach(() => {
    activeKeySig.set(null);
    activeThreadSig.set(null);
    messagesSig.set([]);
    canWriteSig.set(true);

    mockChatService = {
      threads: signal([sampleThread]),
      loadingThreads: signal(false),
      activeKey: activeKeySig,
      activeThread: activeThreadSig,
      messages: messagesSig,
      loadingMessages: signal(false),
      connectionStatus: signal('connected'),
      isParentTyping: signal(false),
      totalUnread: signal(1),
      loadThreads: vi.fn(),
      selectThread: vi.fn((childId: string) => {
        activeKeySig.set(childId);
        activeThreadSig.set(sampleThread);
        messagesSig.set([sampleThread.lastMessage!]);
      }),
      // MG2b: a teacher's key is the child on a parent thread and the thread on a staff one.
      keyOf: (thread: ChatThread) => thread.childId || thread.id!,
      canWrite: canWriteSig,
      sendMessage: vi.fn(),
      sendTyping: vi.fn(),
      markRead: vi.fn(),
    };

    mockFlags = {
      isOn: vi.fn().mockReturnValue(true),
    };
  });

  /** MG2b: a teacher's chooser asks for the department managers, so the page needs the API stubbed. */
  const chatApi = {
    teacherManagers: vi.fn().mockReturnValue(of([])),
    teacherStaffThread: vi.fn().mockReturnValue(of({ id: 'th-2' })),
  };

  async function renderPage(query: BehaviorSubject<Record<string, string>> | null = null) {
    return renderHq(ChatPage, {
      providers: [
        provideRouter([]),
        { provide: ChatService, useValue: mockChatService },
        { provide: ChatApi, useValue: chatApi },
        {
          provide: AuthService,
          useValue: { role: signal('TEACHER' as const), user: signal({ id: 'u-sara' }) },
        },
        { provide: FlagService, useValue: mockFlags },
        ...(query === null
          ? []
          : [
              {
                provide: ActivatedRoute,
                useValue: {
                  snapshot: { queryParamMap: convertToParamMap({}) },
                  queryParamMap: query.pipe(map(convertToParamMap)),
                },
              },
            ]),
      ],
    });
  }

  it('renders threads sidebar with student name and class', async () => {
    await renderPage();

    expect(screen.getByText('Messages')).toBeTruthy();
    expect(screen.getByText('Layla')).toBeTruthy();
    expect(screen.getByText('1A British')).toBeTruthy();
    expect(screen.getByText('Hello teacher')).toBeTruthy();
  });

  it('filters threads by search query', async () => {
    const rendered = await renderPage();
    const searchInput = screen.getByPlaceholderText('Search by child or class...');

    await userEvent.type(searchInput, 'xyz');
    rendered.fixture.detectChanges();

    expect(screen.queryByText('Layla')).toBeNull();

    await userEvent.clear(searchInput);
    rendered.fixture.detectChanges();

    expect(screen.getByText('Layla')).toBeTruthy();
  });

  it('selects a thread and displays the conversation header and messages', async () => {
    const rendered = await renderPage();
    const threadButton = screen.getByText('Layla');

    await userEvent.click(threadButton);
    rendered.fixture.detectChanges();

    expect(mockChatService.selectThread).toHaveBeenCalledWith('ch-1');
    expect(screen.getByText('Parent of Layla · 1A British')).toBeTruthy();
    expect(screen.getByText('Live')).toBeTruthy();
  });

  /**
   * R7: a manager holds the socket and no `Peer.chat`, so a `message` command of hers comes back
   * `forbidden` and there is no REST route either. The composer is *absent* on that answer — the
   * review found Send enabled for her, doing nothing whatever on a click.
   */
  it('hides the composer entirely for a role that may not write', async () => {
    canWriteSig.set(false);
    activeKeySig.set('ch-1');
    activeThreadSig.set(sampleThread);
    await renderPage();

    expect(screen.queryByPlaceholderText('Write a message...')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Send' })).toBeNull();
    expect(document.querySelector('.convo-readonly')).toBeTruthy();
  });

  it('sends message via composer', async () => {
    activeKeySig.set('ch-1');
    activeThreadSig.set(sampleThread);
    const rendered = await renderPage();

    const input = screen.getByPlaceholderText('Write a message...');
    await userEvent.type(input, 'Welcome to class');
    rendered.fixture.detectChanges();

    const sendBtn = screen.getByRole('button', { name: 'Send' });
    expect((sendBtn as HTMLButtonElement).disabled).toBe(false);

    await userEvent.click(sendBtn);
    expect(mockChatService.sendMessage).toHaveBeenCalledWith('Welcome to class');
  });

  /**
   * **MG2b item 7**: the bell's `?thread=` has to open the conversation *while she is already on
   * this screen*. A query-param-only navigation reuses the component, so `route.snapshot` — what
   * the constructor used to read — is never re-read and the click did nothing.
   */
  it('opens the thread a notification names, and again when the query changes', async () => {
    const query = new BehaviorSubject<Record<string, string>>({});
    await renderPage(query);
    expect(mockChatService.selectThread).not.toHaveBeenCalled();

    query.next({ thread: 'ch-1' });
    await screen.findByText('Hello teacher');
    expect(mockChatService.selectThread).toHaveBeenCalledWith('ch-1');

    // The second click of the bell, on this same screen, at her staff thread with the manager —
    // keyed by thread, not by child, and opened by the same effect.
    (mockChatService.selectThread as ReturnType<typeof vi.fn>).mockClear();
    (mockChatService.threads as ReturnType<typeof signal<ChatThread[]>>).set([
      sampleThread,
      { ...sampleThread, id: 'th-2', childId: '', childName: '', teacherName: 'Nada Fahad' },
    ]);
    query.next({ thread: 'th-2' });
    TestBed.tick();
    expect(mockChatService.selectThread).toHaveBeenCalledWith('th-2');

    // A thread this list has never answered is left alone: selecting it would send its id to the
    // child-keyed routes and buy a 404 in a red band.
    (mockChatService.selectThread as ReturnType<typeof vi.fn>).mockClear();
    query.next({ thread: 'th-9' });
    TestBed.tick();
    expect(mockChatService.selectThread).not.toHaveBeenCalled();
  });
});
