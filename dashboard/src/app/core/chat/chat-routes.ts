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
} from '../../api';
import { AuthService } from '../auth/auth.service';
import { SchoolScopeStore } from '../auth/school-scope.store';
import { FLAGS, FlagService } from '../flags/flag.service';
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
  send(key: string, body: string): Observable<ChatMessage>;
  read(key: string): Observable<ChatReadReceipt>;
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
    params: () => this.auth.role() === 'ADMIN' && this.flags.ready() && !this.flags.isOn(FLAGS.multiSchool),
    stream: ({ params }) =>
      params
        ? this.schools.listSchools().pipe(
            map((rows) => (rows.length === 1 ? (rows[0]?.id ?? null) : null)),
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
      send: (key, body) => this.teacher.teacherSendStaffMessage(key, { body }),
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
          send: (key, body) => this.teacher.teacherSendChatMessage(key, { body }),
          read: (key) => this.teacher.teacherMarkChatRead(key),
        };
      case 'COORDINATOR':
        return {
          owns: () => true,
          keyOf: (thread) => thread.id ?? '',
          commandKey: (key) => ({ threadId: key }),
          threads: () => this.coordinator.coordinatorChatThreads(),
          messages: (key, since) => this.coordinator.coordinatorChatMessages(key, undefined, since),
          send: (key, body) => this.coordinator.coordinatorSendChatMessage(key, { body }),
          read: (key) => this.coordinator.coordinatorMarkChatRead(key),
        };
      case 'MANAGERIAL':
        return {
          owns: () => true,
          keyOf: (thread) => thread.id ?? '',
          commandKey: (key) => ({ threadId: key }),
          threads: () => this.management.managementChatThreads(),
          messages: (key, since) => this.management.managementChatMessages(key, undefined, since),
          send: (key, body) => this.management.managementSendChatMessage(key, { body }),
          read: (key) => this.management.managementMarkChatRead(key),
        };
      // The Admin's own threads with the managers. `GET /admin/chat/threads` is wider than that —
      // it is the school's whole chat, for support — and `/admin/messages` says so rather than
      // pretending the list is hers; the three writes below are only ever accepted on a row she
      // is actually on, which is the server's rule and not one this class could enforce.
      // No transport until the school is settled — the one she picked, or the only one there is:
      // a GET without the header is a 400 in a red band on every reconnect. Left null only when a
      // multi-school deployment is waiting for her to choose, which the screen then says.
      case 'ADMIN':
        if (this.adminSchoolId() === null) return null;
        return {
          owns: () => true,
          keyOf: (thread) => thread.id ?? '',
          commandKey: (key) => ({ threadId: key }),
          threads: () => this.teacher.supportChatThreads(),
          messages: (key, since) => this.teacher.supportChatMessages(key, undefined, since),
          send: (key, body) => this.teacher.supportSendChatMessage(key, { body }),
          read: (key) => this.teacher.supportMarkChatRead(key),
        };
      default:
        return null;
    }
  });
}
