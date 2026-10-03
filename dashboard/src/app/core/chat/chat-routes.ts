import { Injectable, computed, effect, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Observable, catchError, map, of } from 'rxjs';
import {
  ChatApi,
  ChatMessage,
  ChatReadReceipt,
  ChatThread,
  CoordinatorChatApi,
  ManagementChatApi,
  SchoolsApi,
  SendChatMessageRequest,
} from '../../api';
import { AuthService } from '../auth/auth.service';
import { SchoolScopeStore } from '../auth/school-scope.store';
import { FLAGS, FlagService } from '../flags/flag.service';
import { NAV_CONFIG } from '../nav/nav-config';
import { ChatCommandKey } from './chat.models';

/**
 * **Which `/chat/**` routes this account's chat runs on, and what a thread is keyed by.**
 *
 * R7. A teacher's half of the chat is keyed by **child** — `/teacher/chat/threads/{childId}/…` —
 * because one of her threads is always about one child. A coordinator's is keyed by **thread**,
 * because one of hers has no child on it at all: `POST /coordinator/chat/threads {managerUserId}`
 * opens a staff conversation with a manager of her department, and `childId` on that row is empty.
 *
 * So the key is the seam. `ChatService` and the chat screen both speak in keys and never in
 * child ids, and this class is the single place that knows which id a key is and which four
 * routes carry it. Two copies of the threads list, the conversation and the composer — one per
 * role — would have been two screens to keep in step, and the second would have drifted.
 *
 * MG2b gave the **teacher two of them**. Her parent threads stay keyed by child, and her new
 * threads with the department manager (`GET|POST /teacher/chat/staff-threads`) are keyed by
 * thread — the same seam one role over. So a transport now also says which rows are *its own*
 * ({@link ChatTransport.owns}), `transports()` is the list the screen loads, and `transportFor`
 * is how one merged thread list routes each row to the four calls that carry it.
 *
 * One namespace, two kinds of key: a teacher's parent keys are child ids and her staff keys are
 * thread ids, and {@link ChatTransport.owns} decides per row which is which — a collision would take
 * a child whose id is also a thread id, and both are server-side UUIDs.
 *
 * RM3b filled the last two in. A manager's threads are keyed by thread as well (one of hers has
 * no child either), and so are an ADMIN's: `GET /admin/chat/threads` is the support view of the
 * school and `POST /admin/chat/threads/{id}/messages` is the half she may write. So all four
 * roles now have a transport, and `null` means nobody is signed in.
 */
export interface ChatTransport {
  /**
   * Whether this thread belongs to this transport.
   *
   * MG2b: a teacher holds two, and the only thing that separates their rows is the child —
   * `childId` is empty on a staff thread and is the key of a parent one. Every other role has one
   * transport, which owns everything its own `GET …/threads` answered.
   */
  owns(thread: ChatThread): boolean;
  /** The id `{id}`/`{childId}` in this role's routes, and the id a thread row is tracked by. */
  keyOf(thread: ChatThread): string;
  /** What a socket `ChatCommand` names this thread by: `childId` for a teacher, `threadId` for her. */
  commandKey(key: string): ChatCommandKey;
  threads(): Observable<ChatThread[]>;
  messages(key: string, since?: string): Observable<ChatMessage[]>;
  /** D4: a REST send carries the request whole — the body, the client id and the attachments. */
  send(key: string, request: SendChatMessageRequest): Observable<ChatMessage>;
  read(key: string): Observable<ChatReadReceipt>;
  /**
   * Whether this account's `message`, `read` and `typing` commands may go on the socket.
   *
   * D4: false for an ADMIN. Her token names no school, so the handshake admits her for the bell
   * with chat off (`ChatHandshake`), and every chat command she sent came back `forbidden` — the
   * frame `ChatService.chatDenied` reads as "this socket is not for chat", which then stopped her
   * thread list from loading. Her messages and reads go over REST, which takes `X-School-Id`.
   */
  readonly socket: boolean;
}

@Injectable({ providedIn: 'root' })
export class ChatRoutes {
  private readonly teacher = inject(ChatApi);
  private readonly coordinator = inject(CoordinatorChatApi);
  private readonly management = inject(ManagementChatApi);
  private readonly auth = inject(AuthService);
  private readonly scope = inject(SchoolScopeStore);
  private readonly schools = inject(SchoolsApi);
  private readonly flags = inject(FlagService);
  private readonly navConfig = inject(NAV_CONFIG);

  /**
   * **Which school an Admin's chat is read in.**
   *
   * `/admin/chat/**` is read one school at a time (`400 Send X-School-Id`). With `multiSchool` on
   * that is whichever school she picked in the header. With it **off** there is exactly one school
   * and no switcher to pick it with, so asking her to choose was a screen that could never load —
   * this resolves the one row of `GET /admin/schools` instead, which is the call the switcher
   * itself makes, and is deliberately the server's answer rather than the id in `localStorage`
   * that D13's mask exists to distrust. More than one row with the flag off is a deployment
   * disagreeing with itself: null, and the screen asks her to pick.
   */
  private readonly sole = rxResource<string | null, boolean>({
    // D1: with the switcher hidden nobody can pick whatever `multiSchool` says, so the one school
    // is resolved for every Admin — and **without waiting on the flags**: the store pins her to
    // it (`SchoolScopeStore.schoolId`), which is the school the flag map is then read *for*, and a
    // resolver that waited on that map would unresolve itself each time it reloaded.
    params: () =>
      this.auth.role() === 'ADMIN' &&
      (!this.navConfig.schoolSurfaces || (this.flags.ready() && !this.flags.isOn(FLAGS.multiSchool))),
    stream: ({ params }) =>
      params
        ? this.schools.listSchools().pipe(
            map((rows) => {
              if (rows.length === 1) return rows[0]?.id ?? null;
              // D1 review: with the switcher hidden a second school must not leave her with no
              // scope at all — `/admin/chat/**` answers 400 without one and nothing on screen can
              // fix it. The first active school of the server's list is hers until the switcher
              // is back; with the switcher on screen, several schools are still hers to choose.
              if (this.navConfig.schoolSurfaces) return null;
              return (rows.find((row) => row.status === 'active') ?? rows[0])?.id ?? null;
            }),
            catchError(() => of(null)),
          )
        : of(null),
    defaultValue: null,
  });

  /** The id the interceptor puts on `/admin/chat/**`; the store is HTTP-free, so it is told. */
  readonly adminSchoolId = computed(() => this.scope.schoolId() ?? this.sole.value());

  constructor() {
    effect(() => this.scope.setSoleSchool(this.sole.value()));
  }

  /**
   * **A teacher's second half** (MG2b, owner's item 6): her threads with the department manager.
   *
   * `GET /teacher/chat/staff-threads` and not `GET /teacher/chat/threads` — the latter still
   * answers only her child-keyed parent threads, deliberately, so nothing she had before moved.
   * Keyed by thread, because there is no child on these rows at all, and that is also what the
   * socket names them by (`{threadId}`).
   *
   * `null` for every other role: a coordinator's and a manager's staff threads are already in the
   * one list their own routes answer.
   */
  readonly staffTransport = computed<ChatTransport | null>(() => {
    if (this.auth.role() !== 'TEACHER') return null;
    return {
      owns: (thread) => thread.childId === '',
      keyOf: (thread) => thread.id ?? '',
      commandKey: (key) => ({ threadId: key }),
      threads: () => this.teacher.teacherStaffThreads(),
      messages: (key, since) => this.teacher.teacherStaffMessages(key, undefined, since),
      send: (key, request) => this.teacher.teacherSendStaffMessage(key, request),
      socket: true,
      read: (key) => this.teacher.teacherMarkStaffRead(key),
    };
  });

  /**
   * Every transport this account reads, in the order the merged list is built from them.
   *
   * One for three roles, two for a teacher. The screen loads all of them and shows one list, so a
   * thread's unread count is counted once wherever it came from.
   */
  readonly transports = computed<readonly ChatTransport[]>(() => {
    const primary = this.transport();
    if (primary === null) return [];
    const staff = this.staffTransport();
    return staff === null ? [primary] : [primary, staff];
  });

  /** Which of them carries this row's four calls. The primary is the answer for three roles. */
  transportFor(thread: ChatThread): ChatTransport | null {
    return this.transports().find((transport) => transport.owns(thread)) ?? this.transport();
  }

  /** `null` while nobody is signed in, and for an Admin who has not narrowed to one school. */
  readonly transport = computed<ChatTransport | null>(() => {
    switch (this.auth.role()) {
      case 'TEACHER':
        return {
          // Her parent threads: one child each, which is what `{childId}` in her routes is.
          owns: (thread) => thread.childId !== '',
          keyOf: (thread) => thread.childId,
          commandKey: (key) => ({ childId: key }),
          threads: () => this.teacher.teacherChatThreads(),
          messages: (key, since) => this.teacher.teacherChatMessages(key, undefined, since),
          send: (key, request) => this.teacher.teacherSendChatMessage(key, request),
          socket: true,
          read: (key) => this.teacher.teacherMarkChatRead(key),
        };
      case 'COORDINATOR':
        return {
          owns: () => true,
          keyOf: (thread) => thread.id ?? '',
          commandKey: (key) => ({ threadId: key }),
          threads: () => this.coordinator.coordinatorChatThreads(),
          messages: (key, since) => this.coordinator.coordinatorChatMessages(key, undefined, since),
          send: (key, request) => this.coordinator.coordinatorSendChatMessage(key, request),
          socket: true,
          read: (key) => this.coordinator.coordinatorMarkChatRead(key),
        };
      case 'MANAGERIAL':
        return {
          owns: () => true,
          keyOf: (thread) => thread.id ?? '',
          commandKey: (key) => ({ threadId: key }),
          threads: () => this.management.managementChatThreads(),
          messages: (key, since) => this.management.managementChatMessages(key, undefined, since),
          send: (key, request) => this.management.managementSendChatMessage(key, request),
          socket: true,
          read: (key) => this.management.managementMarkChatRead(key),
        };
      // The Admin's own threads — with a manager, a coordinator, a teacher or a parent (S1's
      // direct messages). `GET /admin/chat/threads` alone is wider than that — the school's whole
      // chat, for support — so the list is asked for with `mine=true`; the three writes below are
      // only ever accepted on a row she is actually on, which is the server's rule.
      // No transport until the school is settled — the one she picked, or the only one there is:
      // a GET without the header is a 400 in a red band on every reconnect. Left null only when a
      // multi-school deployment is waiting for her to choose, which the screen then says.
      case 'ADMIN':
        if (this.adminSchoolId() === null) return null;
        return {
          owns: () => true,
          keyOf: (thread) => thread.id ?? '',
          commandKey: (key) => ({ threadId: key }),
          // Her inbox: her threads and her unread, so the badge on her rail counts only hers.
          threads: () => this.teacher.supportChatThreads(true),
          messages: (key, since) => this.teacher.supportChatMessages(key, undefined, since),
          send: (key, request) => this.teacher.supportSendChatMessage(key, request),
          // Her token names no school, so the socket is hers for the bell only (D4, see above).
          socket: false,
          read: (key) => this.teacher.supportMarkChatRead(key),
        };
      default:
        return null;
    }
  });
}
