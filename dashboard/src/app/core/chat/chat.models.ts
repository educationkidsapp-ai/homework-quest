import {
  ChatMessage,
  ChatReadReceipt,
  ChatThread,
  NotificationView,
  SendChatMessageRequest,
} from '../../api';
import { type ChatAttachment } from './chat-attachments';

export type { ChatMessage, ChatReadReceipt, ChatThread, SendChatMessageRequest };

/** D4: a REST send with the files B5 added to it, by id. */
export type ChatSendRequest = SendChatMessageRequest & { attachmentIds?: string[] };

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
  | ({ type: 'message'; body: string; clientId: string; attachmentIds?: readonly string[] } & ChatCommandKey)
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
  // `null` as well as absent: the schema declares both `userId` and `parentId` nullable and sends
  // whichever one this presence is not.
  | { type: 'presence'; userId?: string | null; parentId?: string | null; online: boolean }
  | { type: 'ping' }
  | { type: 'pong' }
  | { type: 'error'; code: string; message: string; clientId?: string };

/**
 * **The ids a thread row names for the person on the other end of it.**
 *
 * The peer is found by elimination: a row names the staff side by `teacherId` — the subordinate on
 * a staff thread, the teacher on a parent one — and one of those is the viewer. Dropping her own id
 * leaves the peer, which is why this needs no branch on her role.
 *
 * T1 puts `peerOnline` on the row but **no parent id**, so a parent thread's peer cannot be named
 * from the row at all. `ChatService` adds the `senderId` of the parent's own messages for that
 * case, and falls back to `peerOnline` while the conversation is still empty.
 */
export function peerIdsOf(thread: ChatThread, ownUserId: string | null): readonly string[] {
  return [thread.teacherId].filter(
    (id): id is string => typeof id === 'string' && id !== '' && id !== ownUserId,
  );
}

export interface LocalMessage extends ChatMessage {
  /** D4 (B5): files on the message; the server's echo replaces the optimistic copy. */
  attachments?: ChatAttachment[];
  pending?: boolean;
  failed?: boolean;
  errorMessage?: string;
  clientId?: string;
}
