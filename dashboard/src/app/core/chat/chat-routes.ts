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
 * RM3b filled the last two in. A manager's threads are keyed by thread as well (one of hers has
 * no child either), and so are an ADMIN's: `GET /admin/chat/threads` is the support view of the
 * school and `POST /admin/chat/threads/{id}/messages` is the half she may write. So all four
 * roles now have a transport, and `null` means nobody is signed in.
 */
export interface ChatTransport {
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

  /** `null` while nobody is signed in, and for an Admin who has not narrowed to one school. */
  readonly transport = computed<ChatTransport | null>(() => {
    switch (this.auth.role()) {
      case 'TEACHER':
        return {
          keyOf: (thread) => thread.childId,
          commandKey: (key) => ({ childId: key }),
          threads: () => this.teacher.teacherChatThreads(),
          messages: (key, since) => this.teacher.teacherChatMessages(key, undefined, since),
          send: (key, body) => this.teacher.teacherSendChatMessage(key, { body }),
          read: (key) => this.teacher.teacherMarkChatRead(key),
        };
      case 'COORDINATOR':
        return {
          keyOf: (thread) => thread.id ?? '',
          commandKey: (key) => ({ threadId: key }),
          threads: () => this.coordinator.coordinatorChatThreads(),
          messages: (key, since) => this.coordinator.coordinatorChatMessages(key, undefined, since),
          send: (key, body) => this.coordinator.coordinatorSendChatMessage(key, { body }),
          read: (key) => this.coordinator.coordinatorMarkChatRead(key),
        };
      case 'MANAGERIAL':
        return {
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
