import { Injectable, computed, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  ChatApi,
  ChatMessage,
  ChatReadReceipt,
  ChatThread,
  CoordinatorChatApi,
  ManagementChatApi,
} from '../../api';
import { AuthService } from '../auth/auth.service';
import { SchoolScopeStore } from '../auth/school-scope.store';
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
      // `/admin/chat/**` is read **one school at a time** (`Send X-School-Id: …`), and the
      // interceptor only sends the header while `multiSchool` is on and she has picked a school.
      // No transport rather than a 400 in a red band on every reconnect; the screen says why.
      case 'ADMIN':
        if (this.scope.schoolId() === null) return null;
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
