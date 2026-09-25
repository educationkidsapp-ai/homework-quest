import {
  ChatMessage,
  ChatReadReceipt,
  ChatThread,
  NotificationView,
  SendChatMessageRequest,
} from '../../api';

export type { ChatMessage, ChatReadReceipt, ChatThread, SendChatMessageRequest };

export type ChatConnectionStatus = 'connecting' | 'connected' | 'reconnecting' | 'disconnected';

/** Commands sent by the client to /ws/chat */
export type ChatClientCommand =
  | { type: 'message'; childId: string; body: string; clientId: string }
  | { type: 'typing'; childId: string }
  | { type: 'read'; childId: string }
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
  | { type: 'ping' }
  | { type: 'pong' }
  | { type: 'error'; code: string; message: string; clientId?: string };

export interface LocalMessage extends ChatMessage {
  pending?: boolean;
  failed?: boolean;
  errorMessage?: string;
  clientId?: string;
}
