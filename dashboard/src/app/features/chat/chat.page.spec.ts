import { signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHq } from '../../../testing/render';
import { ChatMessageSenderEnum, ChatThread } from '../../api';
import { LocalMessage } from '../../core/chat/chat.models';
import { ChatService } from '../../core/chat/chat.service';
import { FlagService } from '../../core/flags/flag.service';
import { ChatPage } from './chat.page';

describe('ChatPage', () => {
  let mockChatService: Partial<ChatService>;
  let mockFlags: Partial<FlagService>;
  const activeChildIdSig = signal<string | null>(null);
  const activeThreadSig = signal<ChatThread | null>(null);
  const messagesSig = signal<LocalMessage[]>([]);

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
  };

  beforeEach(() => {
    activeChildIdSig.set(null);
    activeThreadSig.set(null);
    messagesSig.set([]);

    mockChatService = {
      threads: signal([sampleThread]),
      loadingThreads: signal(false),
      activeChildId: activeChildIdSig,
      activeThread: activeThreadSig,
      messages: messagesSig,
      loadingMessages: signal(false),
      connectionStatus: signal('connected'),
      isParentTyping: signal(false),
      totalUnread: signal(1),
      loadThreads: vi.fn(),
      selectThread: vi.fn((childId: string) => {
        activeChildIdSig.set(childId);
        activeThreadSig.set(sampleThread);
        messagesSig.set([sampleThread.lastMessage!]);
      }),
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

  it('sends message via composer', async () => {
    activeChildIdSig.set('ch-1');
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
