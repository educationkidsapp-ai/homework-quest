import {
  ChatMessage,
  ChatReadReceipt,
  ChatThread,
  NotificationView,
  SendChatMessageRequest,
} from '../../api';

export type { ChatMessage, ChatReadReceipt, ChatThread, SendChatMessageRequest };

export type ChatConnectionStatus = 'connecting' | 'connected' | 'reconnecting' | 'disconnected';

/**
 * Commands sent by the client to /ws/chat.
 *
 * R4 made `childId` optional and added `threadId`: a teacher names her thread by the child,
 * a coordinator by the thread, because one of hers has no child on it. `ChatRoutes` decides
 * which of the two a key becomes — nothing else in the dashboard writes either field.
 */
export interface ChatCommandKey {
  childId?: string;
  threadId?: string;
}
export type ChatClientCommand =
  | ({ type: 'message'; body: string; clientId: string } & ChatCommandKey)
  | ({ type: 'typing' } & ChatCommandKey)
  | ({ type: 'read' } & ChatCommandKey)
  | { type: 'ping' }
  | { type: 'pong' };

/**
 * Frames received by the client from /ws/chat.
 *
 * D26: the socket is the dashboard's event channel, not only the chat's — `notification` is
 * sent to every signed-in dashboard role, while the chat frames stay behind the `chat` flag
 * and the TEACHER role. An unknown `type` is still dropped, so a frame added later is inert
 * rather than an exception.
 */
export type ChatServerFrame =
  | { type: 'message'; message: ChatMessage; clientId?: string }
  | { type: 'notification'; notification: NotificationView }
  | { type: 'typing'; threadId: string; from: 'parent' | 'teacher' }
  | { type: 'read'; threadId: string; readBy: 'parent' | 'teacher'; readAt: number }
  // R4: the staff side moved a thread between `open` and `resolved`. Both parties hear it, so a
  // complaint she resolves on one tab stops being open on the other without a refetch.
  | { type: 'status'; threadId: string; status: 'open' | 'resolved'; at: number }
  // T2 item (d): **who is actually connected**, from the server that holds the sockets. One of
  // `userId` (a staff peer) or `parentId` (a parent) names whose presence this is. The dashboard
  // has never known this and used to answer the question with its *own* connection state, which
  // is why a manager who signed out still read as "Live" in the teacher's tab.
  | { type: 'presence'; userId?: string; parentId?: string; online: boolean }
  | { type: 'ping' }
  | { type: 'pong' }
  | { type: 'error'; code: string; message: string; clientId?: string };

/**
 * **Who is on the other end of this thread, and are they connected?**
 *
 * T1 puts `peerOnline` on a thread row and, for a parent thread, the parent's id; both are read
 * here through one narrow widening rather than at every call site, so the day the generator
 * catches up these two functions lose their casts and nothing else moves.
 *
 * The peer is found by elimination: a row names two people by id — `teacherId` (the subordinate
 * on a staff thread, the teacher on a parent one) and `peerUserId` / `parentId` — and one of them
 * is the viewer. Dropping her own id leaves the peer, whichever side of the pair she is on, which
 * is why this needs no branch on her role.
 */
interface PeerFields {
  readonly peerUserId?: string;
  readonly parentId?: string;
  readonly peerOnline?: boolean;
}

export function peerIdsOf(thread: ChatThread, ownUserId: string | null): readonly string[] {
  const extra = thread as ChatThread & PeerFields;
  return [thread.teacherId, extra.peerUserId, extra.parentId].filter(
    (id): id is string => typeof id === 'string' && id !== '' && id !== ownUserId,
  );
}

/** What the last `GET …/threads` said about the peer, or `undefined` when it said nothing. */
export function rowPeerOnline(thread: ChatThread): boolean | undefined {
  return (thread as ChatThread & PeerFields).peerOnline;
}

export interface LocalMessage extends ChatMessage {
  pending?: boolean;
  failed?: boolean;
  errorMessage?: string;
  clientId?: string;
}
