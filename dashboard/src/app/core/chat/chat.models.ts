import { ChatMessage, ChatReadReceipt, ChatThread, SendChatMessageRequest } from '../../api';

export type { ChatMessage, ChatReadReceipt, ChatThread, SendChatMessageRequest };

export type ChatConnectionStatus = 'connecting' | 'connected' | 'reconnecting' | 'disconnected';

/** Commands sent by the client to /ws/chat */
export type ChatClientCommand =
  | { type: 'message'; childId: string; body: string; clientId: string }
  | { type: 'typing'; childId: string }
  | { type: 'read'; childId: string }
  | { type: 'ping' }
  | { type: 'pong' };

/** Frames received by the client from /ws/chat */
export type ChatServerFrame =
  | { type: 'message'; message: ChatMessage; clientId?: string }
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
