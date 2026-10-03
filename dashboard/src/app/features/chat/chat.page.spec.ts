import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { HttpEventType } from '@angular/common/http';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { BehaviorSubject, map, of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { scrollIntoView } from '../../../testing/intersection';
import { renderHq } from '../../../testing/render';
import {
  ChatApi,
  ChatMessageSenderEnum,
  ChatThread,
  ChatThreadPeerRoleEnum,
  ChatThreadStaffRoleEnum,
  ChatThreadStatusEnum,
  ChatThreadTopicEnum,
  ManagementApi,
  ManagementChatApi,
  ManagersApi,
} from '../../api';
import { AuthService } from '../../core/auth/auth.service';
import { LocalMessage } from '../../core/chat/chat.models';
import { ChatService } from '../../core/chat/chat.service';
import { FlagService } from '../../core/flags/flag.service';
import { MediaService } from '../../core/media/media.service';
import { ChatPage } from './chat.page';

describe('ChatPage', () => {
  let mockChatService: Partial<ChatService>;
  let mockFlags: Partial<FlagService>;
  const activeKeySig = signal<string | null>(null);
  const activeThreadSig = signal<ChatThread | null>(null);
  const messagesSig = signal<LocalMessage[]>([]);
  const canWriteSig = signal(true);
  const peerOnlineSig = signal<boolean | undefined>(undefined);
  const activeGoneSig = signal(false);

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
    peerOnlineSig.set(undefined);
    activeGoneSig.set(false);

    mockChatService = {
      threads: signal([sampleThread]),
      loadingThreads: signal(false),
      activeKey: activeKeySig,
      activeThread: activeThreadSig,
      messages: messagesSig,
      loadingMessages: signal(false),
      connectionStatus: signal('connected'),
      // T2 item (d): the header's pill reads the peer's presence, not this tab's own socket.
      // `undefined` is "the server has not said", which draws no pill at all.
      activePeerOnline: peerOnlineSig,
      peerTyping: signal(false),
      totalUnread: signal(1),
      loadThreads: vi.fn(),
      selectThread: vi.fn((childId: string) => {
        activeKeySig.set(childId);
        activeThreadSig.set(sampleThread);
        messagesSig.set([sampleThread.lastMessage!]);
      }),
      // MG2b: a teacher's key is the child on a parent thread and the thread on a staff one.
      keyOf: (thread: ChatThread) => thread.childId || thread.id!,
      // D2: "is this key a row of the list" is the service's own question now.
      activeGone: activeGoneSig,
      holds: (key: string) =>
        (mockChatService.threads as () => ChatThread[])().some(
          (thread) => (thread.childId || thread.id!) === key,
        ),
      canWrite: canWriteSig,
      sendMessage: vi.fn(),
      sendTyping: vi.fn(),
      markRead: vi.fn(),
    };

    mockFlags = {
      isOn: vi.fn().mockReturnValue(true),
    };
  });

  const PIXEL = 'data:image/png;base64,iVBORw0KGgo=';
  /** D4: every upload is accepted at once as `att-9`. */
  const uploadChatAttachment = vi.fn(() => of({ type: HttpEventType.Response, body: { id: 'att-9' } }));

  /** MG2b: a teacher's chooser asks for the department managers, so the page needs the API stubbed. */
  const chatApi = {
    teacherManagers: vi.fn().mockReturnValue(of([])),
    teacherStaffThread: vi.fn().mockReturnValue(of({ id: 'th-2' })),
    uploadChatAttachment,
  };

  async function renderPage(
    query: BehaviorSubject<Record<string, string>> | null = null,
    role: 'TEACHER' | 'ADMIN' | 'MANAGERIAL' | 'COORDINATOR' = 'TEACHER',
  ) {
    return renderHq(ChatPage, {
      providers: [
        provideRouter([]),
        { provide: ChatService, useValue: mockChatService },
        { provide: ChatApi, useValue: chatApi },
        // The Admin's "New message" picker reads the school's managers.
        {
          provide: ManagersApi,
          useValue: { managers: () => of([{ userId: 'u-mgr', fullName: 'Huda Manager' }]) },
        },
        // A manager's picker reads her coordinators, her teachers and the school's admins.
        {
          provide: ManagementApi,
          useValue: { managementCoordinators: () => of([]), managementTeachers: () => of([]) },
        },
        { provide: ManagementChatApi, useValue: { managementAdmins: () => of([]) } },
        {
          provide: AuthService,
          useValue: { role: signal(role), user: signal({ id: 'u-sara' }) },
        },
        { provide: FlagService, useValue: mockFlags },
        // D4: the bubbles read files through the media cache and the composer uploads through the client.
        {
          provide: MediaService,
          useValue: { attachmentImage: () => of(PIXEL), attachmentFile: () => of(new Blob()) },
        },
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
    // T2 item (d): with a live socket and nothing said about the parent, the header claims
    // nothing — "Live" used to mean "this tab's socket is up", which is what let a manager who
    // had signed out go on reading as present.
    expect(screen.queryByText('Live')).toBeNull();

    peerOnlineSig.set(true);
    rendered.fixture.detectChanges();
    expect(screen.getByText('Live')).toBeTruthy();

    peerOnlineSig.set(false);
    rendered.fixture.detectChanges();
    expect(screen.getByText('Away')).toBeTruthy();
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
    expect(mockChatService.sendMessage).toHaveBeenCalledWith('Welcome to class', []);
  });

  /** D4: what she types is "typing" for the other party; emptying the box is not. */
  it('says "typing" while she writes, and not when she empties the box', async () => {
    activeKeySig.set('ch-1');
    activeThreadSig.set(sampleThread);
    await renderPage();

    const input = screen.getByPlaceholderText('Write a message...');
    await userEvent.type(input, 'Hi');
    expect(mockChatService.sendTyping).toHaveBeenCalledTimes(2);

    await userEvent.clear(input);
    expect(mockChatService.sendTyping).toHaveBeenCalledTimes(2);
  });

  describe('files (D4)', () => {
    const user = userEvent.setup({ applyAccept: false });

    beforeEach(() => {
      activeKeySig.set('ch-1');
      activeThreadSig.set(sampleThread);
      uploadChatAttachment.mockClear();
    });

    function picker(): HTMLInputElement {
      return document.querySelector('input[type=file]') as HTMLInputElement;
    }

    it('uploads a picked picture and sends it by itself, with no words', async () => {
      const rendered = await renderPage();
      const picture = new File(['x'], 'board.png', { type: 'image/png' });

      await user.upload(picker(), picture);
      rendered.fixture.detectChanges();

      expect(uploadChatAttachment).toHaveBeenCalledWith(picture, 'events', true, expect.anything());
      expect(screen.getByText('board.png')).toBeTruthy();
      await user.click(screen.getByRole('button', { name: 'Send' }));
      expect(mockChatService.sendMessage).toHaveBeenCalledWith('', [
        { id: 'att-9', contentType: 'image/png', name: 'board.png', size: 1 },
      ]);
      // The composer is empty again for the next message.
      expect(screen.queryByText('board.png')).toBeNull();
    });

    it('refuses a file of another type in a band, in her words, and uploads nothing', async () => {
      const rendered = await renderPage();

      await user.upload(picker(), new File(['x'], 'clip.gif', { type: 'image/gif' }));
      rendered.fixture.detectChanges();

      expect(uploadChatAttachment).not.toHaveBeenCalled();
      expect(screen.getByRole('alert').textContent).toContain(
        'clip.gif cannot be attached. Attach a JPEG, PNG or WebP picture, or a PDF.',
      );
    });

    it('draws the files on a message, and shows a pre-D4 attachment tag as the text it is', async () => {
      messagesSig.set([
        {
          id: 'msg-2',
          threadId: 'th-1',
          sender: ChatMessageSenderEnum.PARENT,
          senderId: 'parent-1',
          body: '',
          createdAt: 1700000000000,
          attachments: [{ id: 'att-1', contentType: 'image/jpeg', name: 'homework.jpg', size: 9000 }],
        },
        {
          id: 'msg-3',
          threadId: 'th-1',
          sender: ChatMessageSenderEnum.TEACHER,
          senderId: 'u-sara',
          body: '[attachment:att-old:image:old.png:2 KB]',
          createdAt: 1700000000001,
        },
      ]);
      const rendered = await renderPage();

      const image = screen.getByRole('img', { name: 'homework.jpg' });
      scrollIntoView(image);
      rendered.fixture.detectChanges();
      expect(image.getAttribute('src')).toBe(PIXEL);
      expect(screen.getByText('[attachment:att-old:image:old.png:2 KB]')).toBeTruthy();
      expect(screen.queryByRole('img', { name: 'old.png' })).toBeNull();
    });

    it('says why a send with files was refused when the files were already sent', async () => {
      messagesSig.set([
        {
          id: 'temp-1',
          threadId: 'th-1',
          sender: ChatMessageSenderEnum.TEACHER,
          senderId: 'u-sara',
          body: '',
          createdAt: 1700000000000,
          failed: true,
          errorCode: 'attachment_already_sent',
        },
      ]);
      await renderPage();

      expect(screen.getByText(/these files were already sent in another message/)).toBeTruthy();
    });

    it('names a files-only last message "Photo" or by the document in the list', async () => {
      const photoOnly = {
        ...sampleThread.lastMessage!,
        body: '',
        attachments: [{ id: 'a', contentType: 'image/webp', name: 'x.webp', size: 1 }],
      };
      const pdfOnly = {
        ...photoOnly,
        attachments: [{ id: 'b', contentType: 'application/pdf', name: 'week 3.pdf', size: 1 }],
      };
      (mockChatService as { threads: unknown }).threads = signal([
        { ...sampleThread, lastMessage: photoOnly },
        { ...sampleThread, id: 'th-2', childId: 'ch-2', childName: 'Omar', lastMessage: pdfOnly },
      ]);
      await renderPage();

      expect(screen.getByText('📷 Photo')).toBeTruthy();
      expect(screen.getByText('📄 week 3.pdf')).toBeTruthy();
    });
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
    // child-keyed routes and buy a 404 in a red band. D2: the list is read once more, and an id
    // that is still not hers is let go of in silence — it leaves the URL, and the list is the
    // screen. Nothing is ever requested *by that id*.
    (mockChatService.selectThread as ReturnType<typeof vi.fn>).mockClear();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    query.next({ thread: 'th-9' });
    TestBed.tick();
    expect(mockChatService.selectThread).not.toHaveBeenCalled();
    expect(mockChatService.loadThreads).toHaveBeenCalledTimes(1);
    expect(navigate).toHaveBeenCalledTimes(1);
    expect(navigate.mock.calls[0]![1]).toMatchObject({
      queryParams: { thread: null },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
    expect(screen.queryByText('That conversation is not in your list any more.')).toBeNull();
    // The list is still there to pick from.
    expect(document.querySelectorAll('.thread-card').length).toBe(2);
  });

  it('says so only when the conversation she was viewing left her list', async () => {
    const rendered = await renderPage();
    expect(screen.queryByText('That conversation is not in your list any more.')).toBeNull();

    activeGoneSig.set(true);
    rendered.fixture.detectChanges();
    expect(screen.getByText('That conversation is not in your list any more.')).toBeTruthy();
  });

  /** D2 (list 3): S1 marks the admin's threads; a teacher reads the office, not a stranger's name. */
  it('labels a thread with the school admin "School administration"', async () => {
    (mockChatService.threads as ReturnType<typeof signal<ChatThread[]>>).set([
      {
        ...sampleThread,
        id: 'th-adm',
        childId: '',
        childName: '',
        teacherName: 'Omar Admin',
        withAdmin: true,
      },
    ]);
    await renderPage();

    expect(screen.getByText('School administration')).toBeTruthy();
    expect(screen.queryByText('Omar Admin')).toBeNull();
  });

  /**
   * N1: the words under a thread's name are the other end's role, read from `peerRole` — the
   * server's word, the same for every viewer — as plain text on the name's edge, never a pill.
   */
  describe('the role under a thread name', () => {
    const staff = { ...sampleThread, childId: '', childName: '' };
    const rolesShown = (host: HTMLElement) =>
      [...host.querySelectorAll('.thread-card')].map(
        (card) => card.querySelector('.thread-card__role')?.textContent?.trim() ?? null,
      );
    const render = async (role: 'TEACHER' | 'ADMIN' | 'MANAGERIAL' | 'COORDINATOR', rows: ChatThread[]) => {
      (mockChatService.threads as ReturnType<typeof signal<ChatThread[]>>).set(rows);
      const { fixture } = await renderPage(null, role);
      await fixture.whenStable();
      fixture.detectChanges();
      return fixture.nativeElement as HTMLElement;
    };

    it('is plain text, not a pill', async () => {
      const host = await render('TEACHER', [
        { ...staff, id: 'th-m', teacherName: 'Huda', peerRole: ChatThreadPeerRoleEnum.MANAGERIAL },
      ]);

      expect(rolesShown(host)).toEqual(['Manager']);
      expect(host.querySelector('.thread-card__badge--staff')).toBeNull();
    });

    it('does not repeat "School administration" under a row already named by that office', async () => {
      const host = await render('TEACHER', [
        {
          ...staff,
          id: 'th-a',
          teacherName: 'Omar Admin',
          withAdmin: true,
          peerRole: ChatThreadPeerRoleEnum.ADMIN,
        },
      ]);

      expect(screen.getByText('School administration')).toBeTruthy();
      expect(rolesShown(host)).toEqual([null]);
    });

    it('tells an Admin a teacher from a coordinator from a manager', async () => {
      const host = await render('ADMIN', [
        {
          ...staff,
          id: 'th-1',
          teacherId: 'u-t',
          teacherName: 'Teacher G1 Arabic',
          withAdmin: true,
          peerRole: ChatThreadPeerRoleEnum.TEACHER,
        },
        {
          ...staff,
          id: 'th-2',
          teacherId: 'u-c',
          teacherName: 'Lina',
          withAdmin: true,
          peerRole: ChatThreadPeerRoleEnum.COORDINATOR,
        },
        {
          ...staff,
          id: 'th-3',
          teacherId: 'u-m',
          teacherName: 'American Department Manager',
          withAdmin: true,
          peerRole: ChatThreadPeerRoleEnum.MANAGERIAL,
        },
      ]);

      expect(rolesShown(host)).toEqual(['Teacher', 'Coordinator', 'Manager']);
      expect(screen.queryByText('Coordinators')).toBeNull();
    });

    it('names a manager’s coordinator', async () => {
      expect(
        rolesShown(
          await render('MANAGERIAL', [
            { ...staff, id: 'th-c', teacherName: 'Lina', peerRole: ChatThreadPeerRoleEnum.COORDINATOR },
          ]),
        ),
      ).toContain('Coordinator');
    });

    it('names a coordinator’s teacher', async () => {
      expect(
        rolesShown(
          await render('COORDINATOR', [
            { ...staff, id: 'th-t', teacherName: 'Maya', peerRole: ChatThreadPeerRoleEnum.TEACHER },
          ]),
        ),
      ).toEqual(['Teacher']);
    });

    it('says Parent on a parent thread, and nothing on a row that carries no role', async () => {
      const host = await render('TEACHER', [
        { ...sampleThread, peerRole: ChatThreadPeerRoleEnum.PARENT },
        { ...staff, id: 'th-x', teacherName: 'Somebody' },
      ]);

      expect(rolesShown(host)).toEqual(['Parent', null]);
    });
  });

  /**
   * **D2 — "Message opens chat only".** The list is read at sign-in, so a thread that "Message" on
   * a row created a second ago is not in it. The screen used to call the link stale on the spot;
   * it now re-reads the list once and selects the row when it lands.
   */
  describe('a ?thread= the list does not hold yet', () => {
    const threadsSig = () => mockChatService.threads as ReturnType<typeof signal<ChatThread[]>>;
    const loadingSig = () => mockChatService.loadingThreads as ReturnType<typeof signal<boolean>>;
    const fresh: ChatThread = {
      ...sampleThread,
      id: 'th-new',
      childId: '',
      childName: '',
      teacherName: 'Nada Fahad',
    };

    it('re-reads the list for a brand new thread and selects it when it arrives', async () => {
      // The re-read, as the real service does it: loading, then the list with the new row in it.
      (mockChatService.loadThreads as ReturnType<typeof vi.fn>).mockImplementation(() =>
        loadingSig().set(true),
      );
      await renderPage(new BehaviorSubject<Record<string, string>>({ thread: 'th-new' }));
      TestBed.tick();

      expect(mockChatService.loadThreads).toHaveBeenCalledTimes(1);
      expect(mockChatService.selectThread).not.toHaveBeenCalled();
      // Not found *yet* is not "not in your list": nothing is said while the read is out.
      expect(screen.queryByText('That conversation is not in your list any more.')).toBeNull();

      threadsSig().set([fresh, sampleThread]);
      loadingSig().set(false);
      TestBed.tick();
      expect(mockChatService.selectThread).toHaveBeenCalledWith('th-new');
      expect(mockChatService.loadThreads).toHaveBeenCalledTimes(1);
      expect(screen.queryByText('That conversation is not in your list any more.')).toBeNull();
    });

    it('waits for a list that is still loading rather than calling the link stale', async () => {
      threadsSig().set([]);
      loadingSig().set(true);
      await renderPage(new BehaviorSubject<Record<string, string>>({ thread: 'th-new' }));
      TestBed.tick();

      // Already on its way: no second read, no selection, no sentence.
      expect(mockChatService.loadThreads).not.toHaveBeenCalled();
      expect(mockChatService.selectThread).not.toHaveBeenCalled();
      expect(screen.queryByText('That conversation is not in your list any more.')).toBeNull();

      threadsSig().set([fresh]);
      loadingSig().set(false);
      TestBed.tick();
      expect(mockChatService.selectThread).toHaveBeenCalledWith('th-new');
    });

    it('selects at once a thread the service already holds, on a page that is already open', async () => {
      const query = new BehaviorSubject<Record<string, string>>({});
      const rendered = await renderPage(query);
      // She had the list narrowed: the link names one conversation, so the filter lets go of it.
      await userEvent.type(screen.getByPlaceholderText('Search by child or class...'), 'xyz');
      rendered.fixture.detectChanges();

      // `ChatService.adopt` put the row in before the navigation (StaffThreadService).
      threadsSig().set([fresh, sampleThread]);
      query.next({ thread: 'th-new' });
      TestBed.tick();
      rendered.fixture.detectChanges();

      expect(mockChatService.selectThread).toHaveBeenCalledWith('th-new');
      expect(mockChatService.loadThreads).not.toHaveBeenCalled();
      expect(screen.getByText('Nada Fahad')).toBeTruthy();
    });
  });
});
