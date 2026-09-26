import { Injectable, computed, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ChatApi, ChatMessage, ChatReadReceipt, ChatThread, CoordinatorChatApi } from '../../api';
import { AuthService } from '../auth/auth.service';
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
 * A role with no chat REST at all (`null`) is not a bug: an ADMIN holds the socket for her
 * notifications, and a manager holds it for the coordinator ↔ manager threads whose REST list
 * R4 did not add (RM2). Asking anyway would put a 403 in a red band on every reconnect.
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
  private readonly auth = inject(AuthService);

  /** `null` while nobody is signed in, and for the roles that only listen (ADMIN, MANAGERIAL). */
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
      default:
        return null;
    }
  });

  /**
   * The manager's side of a staff thread: frames arrive, and the thread row is the frame itself.
   *
   * **MANAGERIAL by name, not "anybody without a transport".** An ADMIN has no transport either,
   * and letting her fall in here would have prepended a manager-shaped row on any `message` frame
   * she happened to receive — harmless today, because no Admin screen reads this list, and exactly
   * the kind of harmless that stops being harmless when one does.
   */
  readonly listensOnly = computed(() => this.auth.role() === 'MANAGERIAL');
}
