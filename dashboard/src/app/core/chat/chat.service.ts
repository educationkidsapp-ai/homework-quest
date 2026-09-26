import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { catchError, of, tap } from 'rxjs';
import { ChatApi, ChatMessage, ChatMessageSenderEnum, ChatThread } from '../../api';
import { environment } from '../../../environments/environment';
import { AuthService } from '../auth/auth.service';
import { SessionStore } from '../auth/session.store';
import { FlagService } from '../flags/flag.service';
import { NotificationsService } from '../notifications/notifications.service';
import { ChatClientCommand, ChatConnectionStatus, ChatServerFrame, LocalMessage } from './chat.models';

const MAX_RECONNECT_DELAY_MS = 30_000;
const TYPING_TIMEOUT_MS = 3_000;
const TYPING_THROTTLE_MS = 2_000;

@Injectable({ providedIn: 'root' })
export class ChatService {
  private readonly api = inject(ChatApi);
  private readonly auth = inject(AuthService);
  private readonly session = inject(SessionStore);
  private readonly flags = inject(FlagService);
  private readonly notifications = inject(NotificationsService);

  private socket: WebSocket | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private reconnectAttempts = 0;
  private typingClearTimer: ReturnType<typeof setTimeout> | null = null;
  private lastTypingSentAt = 0;
  private intentionalDisconnect = false;

  readonly threads = signal<ChatThread[]>([]);
  readonly loadingThreads = signal<boolean>(false);
  readonly activeChildId = signal<string | null>(null);
  readonly messages = signal<LocalMessage[]>([]);
  readonly loadingMessages = signal<boolean>(false);
  readonly connectionStatus = signal<ChatConnectionStatus>('disconnected');
  readonly isParentTyping = signal<boolean>(false);

  /**
   * U1 item 6: the conversation she opened from a child who has never been written to.
   *
   * `GET /teacher/chat/threads` lists the threads that exist, and the row is created by the
   * first message (`ChatThreads.getOrCreate`), so "Message parent" on a fresh child had nothing
   * to select and the screen stayed on "pick a conversation". This is that conversation before
   * it exists: an empty thread with the child's name on it, replaced by the server's own row as
   * soon as the first message lands.
   */
  private readonly pending = signal<ChatThread | null>(null);

  readonly activeThread = computed(() => {
    const childId = this.activeChildId();
    if (!childId) return null;
    const existing = this.threads().find((t) => t.childId === childId);
    if (existing) return existing;
    const waiting = this.pending();
    return waiting !== null && waiting.childId === childId ? waiting : null;
  });

  readonly totalUnread = computed(() => this.threads().reduce((acc, t) => acc + (t.unread ?? 0), 0));

  /**
   * A `forbidden` error frame with no `clientId` is the handshake saying "you are on the
   * socket for notifications, not for chat". It is an answer, not a fault: the socket stays
   * open, nothing reconnects, and no chat command is sent again.
   */
  readonly chatDenied = signal(false);

  constructor() {
    // D26: the socket is the dashboard's event channel. It opens for every signed-in
    // dashboard role, because that is how the notification frame reaches an Admin or a
    // manager; the chat half of it (threads, sends, the screen) stays TEACHER + `chat`.
    effect(() => {
      const role = this.auth.role();
      const dashboardRole = role === 'ADMIN' || role === 'MANAGERIAL' || role === 'TEACHER';

      if (this.auth.signedIn() && dashboardRole) {
        this.intentionalDisconnect = false;
        this.connect();
        if (role === 'TEACHER' && this.flags.isOn('chat')) this.loadThreads();
      } else {
        this.disconnect();
      }
    });
  }

  loadThreads(): void {
    // The socket now opens for three roles; the chat REST routes still answer only one of
    // them, and only with the flag on. Asking anyway would be a 403 in the band on every
    // reconnect for an Admin.
    if (this.auth.role() !== 'TEACHER' || !this.flags.isOn('chat') || this.chatDenied()) return;
    this.loadingThreads.set(true);
    this.api
      .teacherChatThreads()
      .pipe(
        tap((threads) => {
          this.threads.set(threads);
          this.loadingThreads.set(false);
          // The list is the answer to "does this child have a thread": drop a placeholder the
          // server has since confirmed, and mark read what could not be marked without one.
          const active = this.activeChildId();
          const real = active === null ? undefined : threads.find((t) => t.childId === active);
          if (real) {
            if (this.pending()?.childId === active) this.pending.set(null);
            if (real.unread > 0) this.markRead(real.childId);
          }
        }),
        catchError(() => {
          this.loadingThreads.set(false);
          return of([]);
        }),
      )
      .subscribe();
  }

  selectThread(childId: string): void {
    if (this.activeChildId() === childId) return;
    this.activeChildId.set(childId);
    this.isParentTyping.set(false);
    this.loadMessages(childId);
    // Only a thread that exists can be marked read: `POST …/read` answers 404 without one, and
    // the error interceptor would put that 404 in a red band over a conversation she just opened.
    if (this.threads().some((t) => t.childId === childId)) this.markRead(childId);
  }

  /**
   * Open the conversation with a child's parent, thread or no thread (U1 item 6).
   *
   * What "Message parent" on the Children tab and on the child's page mean: the screen shows the
   * composer straight away, and the first message creates the thread server-side.
   */
  openWith(childId: string, childName: string, className?: string): void {
    if (!this.threads().some((t) => t.childId === childId)) {
      this.pending.set({
        id: '',
        childId,
        childName,
        className,
        teacherId: this.auth.user()?.id ?? '',
        teacherName: this.auth.user()?.displayName ?? '',
        unread: 0,
      });
    }
    this.selectThread(childId);
  }

  loadMessages(childId: string): void {
    this.loadingMessages.set(true);
    this.api
      .teacherChatMessages(childId)
      .pipe(
        tap((msgs) => {
          this.messages.set(msgs);
          this.loadingMessages.set(false);
        }),
        catchError(() => {
          this.loadingMessages.set(false);
          return of([]);
        }),
      )
      .subscribe();
  }

  sendMessage(body: string): void {
    const childId = this.activeChildId();
    const cleanBody = body.trim();
    if (!childId || !cleanBody || cleanBody.length > 2000) return;

    const clientId =
      typeof crypto !== 'undefined' && crypto.randomUUID
        ? crypto.randomUUID()
        : `c-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`;

    const optimistic: LocalMessage = {
      id: `temp-${clientId}`,
      threadId: this.activeThread()?.id ?? '',
      sender: ChatMessageSenderEnum.TEACHER,
      senderId: this.auth.user()?.id ?? 'teacher',
      body: cleanBody,
      createdAt: Date.now(),
      pending: true,
      clientId,
    };

    this.messages.update((list) => [...list, optimistic]);

    // Try WebSocket send first
    if (this.socket && this.socket.readyState === WebSocket.OPEN) {
      const command: ChatClientCommand = {
        type: 'message',
        childId,
        body: cleanBody,
        clientId,
      };
      this.socket.send(JSON.stringify(command));
    } else {
      // Fall back to REST
      this.api
        .teacherSendChatMessage(childId, { body: cleanBody })
        .pipe(
          tap((msg) => {
            this.handleServerMessage(msg, clientId);
          }),
          catchError((err: unknown) => {
            this.messages.update((list) =>
              list.map((m) =>
                m.clientId === clientId
                  ? { ...m, pending: false, failed: true, errorMessage: 'Failed to send' }
                  : m,
              ),
            );
            return of(err);
          }),
        )
        .subscribe();
    }
  }

  sendTyping(): void {
    const childId = this.activeChildId();
    if (!childId) return;

    const now = Date.now();
    if (now - this.lastTypingSentAt < TYPING_THROTTLE_MS) return;
    this.lastTypingSentAt = now;

    if (this.socket && this.socket.readyState === WebSocket.OPEN) {
      const command: ChatClientCommand = { type: 'typing', childId };
      this.socket.send(JSON.stringify(command));
    }
  }

  markRead(childId: string): void {
    if (this.socket && this.socket.readyState === WebSocket.OPEN) {
      const command: ChatClientCommand = { type: 'read', childId };
      this.socket.send(JSON.stringify(command));
    }

    // Call REST markRead as well for durability
    this.api
      .teacherMarkChatRead(childId)
      .pipe(catchError(() => of(null)))
      .subscribe();

    // Clear local unread counter for this thread
    this.threads.update((list) => list.map((t) => (t.childId === childId ? { ...t, unread: 0 } : t)));
  }

  connect(): void {
    if (typeof window === 'undefined') return;
    if (
      this.socket &&
      (this.socket.readyState === WebSocket.OPEN || this.socket.readyState === WebSocket.CONNECTING)
    ) {
      return;
    }

    const token = this.session.accessToken();
    if (!token) {
      // Need token: refresh session first
      this.auth
        .refresh()
        .pipe(
          tap(() => this.connectWithToken()),
          catchError(() => of(null)),
        )
        .subscribe();
      return;
    }

    this.connectWithToken();
  }

  private connectWithToken(): void {
    const token = this.session.accessToken();
    if (!token) {
      this.connectionStatus.set('disconnected');
      return;
    }

    this.connectionStatus.set(this.reconnectAttempts > 0 ? 'reconnecting' : 'connecting');

    const base = environment.apiBaseUrl || (typeof window !== 'undefined' ? window.location.origin : '');
    const wsProto = base.startsWith('https')
      ? 'wss:'
      : base.startsWith('http')
        ? 'ws:'
        : typeof window !== 'undefined' && window.location.protocol === 'https:'
          ? 'wss:'
          : 'ws:';
    const host = base
      ? base.replace(/^https?:\/\//, '')
      : typeof window !== 'undefined'
        ? window.location.host
        : 'localhost:8080';

    const wsUrl = `${wsProto}//${host}/ws/chat?token=${encodeURIComponent(token)}`;

    try {
      this.socket = new WebSocket(wsUrl);

      this.socket.onopen = () => {
        this.connectionStatus.set('connected');
        this.reconnectAttempts = 0;
        // Nothing is replayed after a missed frame: whatever arrived while we were away is
        // only in the database, so the bell asks for it again (E3).
        this.notifications.onSocketOpen();
        this.loadThreads();

        // Refetch recent messages for active thread on reconnect
        const activeId = this.activeChildId();
        if (activeId) {
          const msgs = this.messages();
          const lastMsg = msgs[msgs.length - 1];
          if (lastMsg && !lastMsg.pending) {
            this.api
              .teacherChatMessages(activeId, undefined, lastMsg.id)
              .pipe(
                tap((newMsgs) => {
                  if (newMsgs.length > 0) {
                    this.mergeNewMessages(newMsgs);
                  }
                }),
                catchError(() => of([])),
              )
              .subscribe();
          } else {
            this.loadMessages(activeId);
          }
        }
      };

      this.socket.onmessage = (event) => {
        try {
          const frame = JSON.parse(event.data as string) as ChatServerFrame;
          this.handleFrame(frame);
        } catch {
          // Ignore unparseable frame
        }
      };

      this.socket.onclose = () => {
        this.notifications.onSocketClosed();
        if (!this.intentionalDisconnect) {
          this.connectionStatus.set('reconnecting');
          this.scheduleReconnect();
        } else {
          this.connectionStatus.set('disconnected');
        }
      };

      this.socket.onerror = () => {
        if (this.socket) {
          this.socket.close();
        }
      };
    } catch {
      this.connectionStatus.set('disconnected');
      this.scheduleReconnect();
    }
  }

  private handleFrame(frame: ChatServerFrame): void {
    switch (frame.type) {
      case 'ping':
        if (this.socket && this.socket.readyState === WebSocket.OPEN) {
          this.socket.send(JSON.stringify({ type: 'pong' }));
        }
        break;

      case 'message':
        this.handleServerMessage(frame.message, frame.clientId);
        break;

      case 'notification':
        this.notifications.receive(frame.notification);
        break;

      case 'typing':
        if (frame.from === 'parent') {
          const active = this.activeThread();
          if (active && active.id === frame.threadId) {
            this.isParentTyping.set(true);
            if (this.typingClearTimer) clearTimeout(this.typingClearTimer);
            this.typingClearTimer = setTimeout(() => {
              this.isParentTyping.set(false);
            }, TYPING_TIMEOUT_MS);
          }
        }
        break;

      case 'read':
        if (frame.readBy === 'parent') {
          this.messages.update((list) =>
            list.map((m) =>
              m.sender === ChatMessageSenderEnum.TEACHER && !m.readAt ? { ...m, readAt: frame.readAt } : m,
            ),
          );
        } else if (frame.readBy === 'teacher') {
          this.threads.update((list) => list.map((t) => (t.id === frame.threadId ? { ...t, unread: 0 } : t)));
        }
        break;

      case 'error':
        // "You are here for notifications" — an answer to a chat command this peer may not
        // send. The socket is fine; only the chat half of it is closed to us.
        if (frame.code === 'forbidden' && !frame.clientId) {
          this.chatDenied.set(true);
          break;
        }
        if (frame.clientId) {
          this.messages.update((list) =>
            list.map((m) =>
              m.clientId === frame.clientId
                ? { ...m, pending: false, failed: true, errorMessage: frame.message }
                : m,
            ),
          );
        }
        break;
    }
  }

  private handleServerMessage(message: ChatMessage, clientId?: string): void {
    const activeChild = this.activeChildId();
    // The first message of a new conversation *is* the thread: refetch the list so the sidebar
    // has the row the server just created, and let it take the placeholder's place.
    if (this.pending() !== null && message.threadId) {
      this.loadThreads();
    }
    const active = this.activeThread();
    const isCurrentThread =
      (active && active.id === message.threadId) ||
      (activeChild && this.threads().some((t) => t.childId === activeChild && t.id === message.threadId));
    // The echo of a message this client sent, identified by its own `clientId`. It settles the
    // optimistic bubble even when the thread it created is younger than the thread list.
    const isOwnEcho =
      clientId !== undefined && this.messages().some((m) => m.clientId === clientId);

    if (isCurrentThread || isOwnEcho) {
      this.messages.update((list) => {
        // If message has clientId, replace the pending optimistic message
        if (clientId) {
          const idx = list.findIndex((m) => m.clientId === clientId);
          if (idx !== -1) {
            const next = [...list];
            next[idx] = message;
            return next;
          }
        }
        // Deduplicate by message ID
        if (list.some((m) => m.id === message.id)) {
          return list;
        }
        return [...list, message];
      });

      // If message is from parent in active view, mark read immediately
      if (message.sender === ChatMessageSenderEnum.PARENT && activeChild) {
        this.markRead(activeChild);
      }
    }

    // Update threads list preview and unread count
    this.threads.update((threadsList) => {
      const idx = threadsList.findIndex((t) => t.id === message.threadId);
      if (idx === -1) {
        this.loadThreads();
        return threadsList;
      }
      const existing = threadsList[idx]!;
      const isUnreadInc = message.sender === ChatMessageSenderEnum.PARENT && !isCurrentThread;
      const updated: ChatThread = {
        ...existing,
        lastMessage: message,
        unread: (existing.unread ?? 0) + (isUnreadInc ? 1 : 0),
      };
      // Move active thread to top
      const nextList = threadsList.filter((_, i) => i !== idx);
      return [updated, ...nextList];
    });
  }

  private mergeNewMessages(newMsgs: ChatMessage[]): void {
    this.messages.update((existing) => {
      const ids = new Set(existing.map((m) => m.id));
      const toAppend = newMsgs.filter((m) => !ids.has(m.id));
      return [...existing, ...toAppend];
    });
  }

  private scheduleReconnect(): void {
    if (this.intentionalDisconnect || this.reconnectTimer) return;
    this.reconnectAttempts++;
    const delay = Math.min(1000 * 2 ** (this.reconnectAttempts - 1), MAX_RECONNECT_DELAY_MS);
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      // Refresh token before reconnecting as per runbook
      this.auth
        .refresh()
        .pipe(
          tap(() => this.connectWithToken()),
          catchError(() => {
            this.scheduleReconnect();
            return of(null);
          }),
        )
        .subscribe();
    }, delay);
  }

  disconnect(): void {
    this.intentionalDisconnect = true;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    if (this.typingClearTimer) {
      clearTimeout(this.typingClearTimer);
      this.typingClearTimer = null;
    }
    if (this.socket) {
      this.socket.close(1000, 'normal_close');
      this.socket = null;
    }
    this.notifications.onSocketClosed();
    this.connectionStatus.set('disconnected');
  }
}
