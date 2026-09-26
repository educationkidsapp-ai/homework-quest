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
  | { type: 'ping' }
  | { type: 'pong' }
  | { type: 'error'; code: string; message: string; clientId?: string };

export interface LocalMessage extends ChatMessage {
  pending?: boolean;
  failed?: boolean;
  errorMessage?: string;
  clientId?: string;
}
