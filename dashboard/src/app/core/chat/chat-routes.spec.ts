import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ChatApi,
  ChatMessageSenderEnum,
  ChatThread,
  ChatThreadStaffRoleEnum,
  ChatThreadStatusEnum,
  ChatThreadTopicEnum,
  CoordinatorChatApi,
  ManagementChatApi,
  SchoolsApi,
} from '../../api';
import { TEACHER_USER } from '../../../testing/fixtures';
import { AuthService, type Role } from '../auth/auth.service';
import { SchoolScopeStore } from '../auth/school-scope.store';
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
  let managementApi: Partial<ManagementChatApi>;
  let schoolsApi: Partial<SchoolsApi>;
  const role = signal<Role | null>('COORDINATOR');
  /** D13: with `multiSchool` off there is one school and no switcher to pick it with. */
  const multiSchool = signal(false);
  const schools = signal<{ id: string; name: string }[]>([{ id: 's-1', name: 'Al Noor' }]);

  function setup(): { routes: ChatRoutes; chat: ChatService } {
    teacherApi = {
      teacherChatThreads: vi.fn().mockReturnValue(of([parentThread])),
      teacherChatMessages: vi.fn().mockReturnValue(of([])),
      teacherSendChatMessage: vi.fn().mockReturnValue(of({})),
      teacherMarkChatRead: vi.fn().mockReturnValue(of({})),
      // MG2b: her second transport — the department manager, keyed by thread.
      teacherStaffThreads: vi.fn().mockReturnValue(of([staffThread])),
      teacherStaffMessages: vi.fn().mockReturnValue(of([])),
      teacherSendStaffMessage: vi.fn().mockReturnValue(of({})),
      teacherMarkStaffRead: vi.fn().mockReturnValue(of({})),
      // RM3b: the Admin's own half of `/admin/chat/**`, which lives on `ChatApi` because
      // springdoc tags it `Chat` alongside the parent's and the teacher's.
      supportChatThreads: vi.fn().mockReturnValue(of([staffThread])),
      supportChatMessages: vi.fn().mockReturnValue(of([])),
      supportSendChatMessage: vi.fn().mockReturnValue(of({})),
      supportMarkChatRead: vi.fn().mockReturnValue(of({})),
    };
    coordinatorApi = {
      coordinatorChatThreads: vi.fn().mockReturnValue(of([parentThread, staffThread])),
      coordinatorChatMessages: vi.fn().mockReturnValue(of([])),
      coordinatorSendChatMessage: vi.fn().mockReturnValue(of({})),
      coordinatorMarkChatRead: vi.fn().mockReturnValue(of({})),
    };
    managementApi = {
      managementChatThreads: vi.fn().mockReturnValue(of([parentThread, staffThread])),
      managementChatMessages: vi.fn().mockReturnValue(of([])),
      managementSendChatMessage: vi.fn().mockReturnValue(of({})),
      managementMarkChatRead: vi.fn().mockReturnValue(of({})),
    };
    schoolsApi = { listSchools: vi.fn().mockImplementation(() => of(schools())) };

    TestBed.configureTestingModule({
      providers: [
        { provide: ChatApi, useValue: teacherApi },
        { provide: CoordinatorChatApi, useValue: coordinatorApi },
        { provide: ManagementChatApi, useValue: managementApi },
        { provide: SchoolsApi, useValue: schoolsApi },
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
        {
          provide: FlagService,
          useValue: {
            isOn: (key: string) => (key === 'multiSchool' ? multiSchool() : true),
            ready: () => true,
          },
        },
      ],
    });
    // What `FlagService` does once the flag map has settled (D13), and what the store's mask —
    // and therefore `soleSchoolId` — depends on.
    TestBed.inject(SchoolScopeStore).setMultiSchool(multiSchool());
    return { routes: TestBed.inject(ChatRoutes), chat: TestBed.inject(ChatService) };
  }

  beforeEach(() => {
    role.set('COORDINATOR');
    multiSchool.set(false);
    schools.set([{ id: 's-1', name: 'Al Noor' }]);
    localStorage.clear();
    sessionStorage.clear();
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
   * **MG2b: one screen, two transports for a teacher** (owner's item 6).
   *
   * Her parent threads are keyed by child and her threads with the department manager by thread.
   * Both lists are loaded, merged into one, and each row's key routes its own four calls — sending
   * a staff thread's id to `/teacher/chat/threads/{childId}/…` would be a 404 in a red band.
   */
  it('reads a teacher’s parent threads and her staff threads, and routes each by its own key', () => {
    role.set('TEACHER');
    const { routes, chat } = setup();

    expect(routes.transports()).toHaveLength(2);
    expect(routes.transportFor(parentThread)?.keyOf(parentThread)).toBe('ch-1');
    expect(routes.transportFor(staffThread)?.keyOf(staffThread)).toBe('th-2');
    // The socket names a staff thread by thread and a parent one by child, on the same connection.
    expect(routes.staffTransport()?.commandKey('th-2')).toEqual({ threadId: 'th-2' });

    chat.loadThreads();
    expect(teacherApi.teacherChatThreads).toHaveBeenCalled();
    expect(teacherApi.teacherStaffThreads).toHaveBeenCalled();
    expect(chat.threads().map((thread) => thread.id)).toEqual(['th-1', 'th-2']);

    chat.selectThread('th-2');
    expect(teacherApi.teacherStaffMessages).toHaveBeenCalledWith('th-2', undefined, undefined);
    expect(teacherApi.teacherChatMessages).not.toHaveBeenCalledWith('th-2', undefined, undefined);
    chat.sendMessage('Can we talk about grade 3?');
    expect(teacherApi.teacherSendStaffMessage).toHaveBeenCalledWith('th-2', {
      body: 'Can we talk about grade 3?',
    });

    chat.selectThread('ch-1');
    expect(teacherApi.teacherChatMessages).toHaveBeenCalledWith('ch-1', undefined, undefined);
  });

  /** The badge is one number over both lists: three unread parents' messages and two staff ones. */
  it('sums a teacher’s unread over both of her lists', () => {
    role.set('TEACHER');
    const { chat } = setup();
    (teacherApi.teacherStaffThreads as ReturnType<typeof vi.fn>).mockReturnValue(
      of([{ ...staffThread, unread: 2 }]),
    );

    chat.loadThreads();
    expect(chat.totalUnread()).toBe(5);
  });

  /** One half failing must not blank the other: each list catches its own error. */
  it('keeps a teacher’s parent threads when the staff list fails', () => {
    role.set('TEACHER');
    const { chat } = setup();
    (teacherApi.teacherStaffThreads as ReturnType<typeof vi.fn>).mockReturnValue(
      throwError(() => new Error('403')),
    );

    chat.loadThreads();
    expect(chat.threads().map((thread) => thread.id)).toEqual(['th-1']);
  });

  /**
   * RM2 gave her the list R7 had to do without, so RM3b gives her a transport: keyed by thread
   * like the coordinator's, because a parent thread of hers has a child and her two staff threads
   * do not.
   */
  it('keys a manager by thread and reads /management/chat/**', () => {
    role.set('MANAGERIAL');
    const { routes, chat } = setup();

    expect(routes.transport()?.keyOf(staffThread)).toBe('th-2');
    expect(routes.transport()?.commandKey('th-2')).toEqual({ threadId: 'th-2' });

    chat.loadThreads();
    expect(managementApi.managementChatThreads).toHaveBeenCalled();
    expect(coordinatorApi.coordinatorChatThreads).not.toHaveBeenCalled();
    expect(teacherApi.teacherChatThreads).not.toHaveBeenCalled();
    // Both kinds of row are hers: the parents of her department and her staff threads.
    expect(chat.threads().map((t) => t.id)).toEqual(['th-1', 'th-2']);

    chat.selectThread('th-2');
    expect(managementApi.managementChatMessages).toHaveBeenCalledWith('th-2', undefined, undefined);
    chat.sendMessage('I will look at it');
    expect(managementApi.managementSendChatMessage).toHaveBeenCalledWith('th-2', {
      body: 'I will look at it',
    });
    expect(managementApi.managementMarkChatRead).toHaveBeenCalledWith('th-2');
  });

  /** The Admin's inbox of manager threads: `GET` is the school's whole chat, the writes are hers. */
  it('keys an admin by thread and reads /admin/chat/**', () => {
    role.set('ADMIN');
    const { routes, chat } = setup();
    // `multiSchool` off: the one school comes from `GET /admin/schools`, not from a switcher that
    // is not rendered and not from the `localStorage` id D13's mask exists to distrust.
    TestBed.tick();
    expect(schoolsApi.listSchools).toHaveBeenCalled();
    expect(routes.adminSchoolId()).toBe('s-1');
    // And the interceptor is told, because the store holds no HTTP of its own.
    TestBed.tick();
    expect(TestBed.inject(SchoolScopeStore).soleSchoolId()).toBe('s-1');

    expect(routes.transport()?.keyOf(staffThread)).toBe('th-2');
    expect(routes.transport()?.commandKey('th-2')).toEqual({ threadId: 'th-2' });

    chat.loadThreads();
    expect(teacherApi.supportChatThreads).toHaveBeenCalled();
    expect(teacherApi.teacherChatThreads).not.toHaveBeenCalled();
    expect(managementApi.managementChatThreads).not.toHaveBeenCalled();

    chat.selectThread('th-2');
    expect(teacherApi.supportChatMessages).toHaveBeenCalledWith('th-2', undefined, undefined);
    chat.sendMessage('Noted');
    expect(teacherApi.supportSendChatMessage).toHaveBeenCalledWith('th-2', { body: 'Noted' });
    expect(teacherApi.supportMarkChatRead).toHaveBeenCalledWith('th-2');
  });

  /**
   * A frame for a thread the list has never seen is a refetch now, for every role — RM3b took the
   * frame-built row away, because a row invented from a message has no peer name and no child on
   * it and the real one is one GET away.
   */
  it('refetches the list when a frame names a thread it does not hold', () => {
    role.set('MANAGERIAL');
    const { chat } = setup();
    chat.loadThreads();

    chat.receive({
      type: 'message',
      message: {
        id: 'm-9',
        threadId: 'th-new',
        sender: ChatMessageSenderEnum.TEACHER,
        senderId: 'u-lina',
        body: 'Could you look at 3B?',
        createdAt: 1700000000000,
      },
    });

    expect(managementApi.managementChatThreads).toHaveBeenCalledTimes(2);
  });

  /**
   * The other branch: several schools, so which one is hers to choose — and until she has, there is
   * no id to put on the header and the screen says so rather than showing an inbox that would 400.
   */
  it('waits for an admin to pick a school only when there is more than one', () => {
    role.set('ADMIN');
    multiSchool.set(true);
    const { routes } = setup();
    TestBed.tick();

    expect(routes.transport()).toBeNull();
    expect(routes.adminSchoolId()).toBeNull();
    // With the flag on the switcher is rendered and the list is the switcher's own business.
    expect(schoolsApi.listSchools).not.toHaveBeenCalled();

    TestBed.inject(SchoolScopeStore).select({ id: 's-2', name: 'Green Valley' });
    expect(routes.adminSchoolId()).toBe('s-2');
    expect(routes.transport()).not.toBeNull();
    // The mask still holds: nothing was resolved, so there is no sole school to fall back on.
    expect(TestBed.inject(SchoolScopeStore).soleSchoolId()).toBeNull();
  });

  /** A single-school deployment that answers two rows is disagreeing with itself: pick, don't guess. */
  it('resolves no school when the one-school deployment answers more than one', () => {
    role.set('ADMIN');
    schools.set([
      { id: 's-1', name: 'Al Noor' },
      { id: 's-2', name: 'Green Valley' },
    ]);
    const { routes } = setup();
    TestBed.tick();
    expect(routes.adminSchoolId()).toBeNull();
    expect(routes.transport()).toBeNull();
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
