import { signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHq } from '../../../testing/render';
import {
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
      keyOf: (thread: ChatThread) => thread.childId,
      canWrite: canWriteSig,
      sendMessage: vi.fn(),
      sendTyping: vi.fn(),
      markRead: vi.fn(),
    };

    mockFlags = {
      isOn: vi.fn().mockReturnValue(true),
    };
  });

  async function renderPage() {
    return renderHq(ChatPage, {
      providers: [
        provideRouter([]),
        { provide: ChatService, useValue: mockChatService },
        {
          provide: AuthService,
          useValue: { role: signal('TEACHER' as const), user: signal({ id: 'u-sara' }) },
        },
        { provide: FlagService, useValue: mockFlags },
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
});
