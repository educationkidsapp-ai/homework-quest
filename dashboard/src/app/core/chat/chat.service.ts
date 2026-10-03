import { Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { catchError, forkJoin, of, tap } from 'rxjs';
import {
  ChatMessage,
  ChatMessageSenderEnum,
  ChatThread,
  ChatThreadStaffRoleEnum,
  ChatThreadStatusEnum,
  ChatThreadTopicEnum,
  SendChatMessageRequest,
} from '../../api';
import { apiErrorCodeOf } from '../../api/api-error';
import { environment } from '../../../environments/environment';
import { AuthService } from '../auth/auth.service';
import { SessionStore } from '../auth/session.store';
import { FlagService } from '../flags/flag.service';
import { NotificationsService } from '../notifications/notifications.service';
import { type ChatAttachment } from './chat-attachments';
import { type ChatTransport, ChatRoutes } from './chat-routes';
import {
  type ChatClientCommand,
  type ChatConnectionStatus,
  type ChatServerFrame,
  type LocalMessage,
  peerIdsOf,
} from './chat.models';

const MAX_RECONNECT_DELAY_MS = 30_000;
/** `ChatSessions.SIGNED_OUT` — `CloseStatus.NORMAL.withReason("signed out")` (T1). */
const SIGNED_OUT_REASON = 'signed out';
/** How long the other party's "typing" stays on screen after her last `typing` frame. */
const TYPING_TIMEOUT_MS = 4_500;
/**
 * D4: at most one `typing` command per thread every 3 s while she types. The parent's app shows
 * "typing" for 4.5 s after a frame (`ChatConversationScreen`), so a frame every 3 s keeps it on
 * without a gap, and nothing at all is sent once she stops — the protocol has no "stopped" frame,
 * the other side simply lets the indicator lapse.
 */
const TYPING_EVERY_MS = 3_000;

@Injectable({ providedIn: 'root' })
export class ChatService {
  private readonly routes = inject(ChatRoutes);
  private readonly auth = inject(AuthService);
  private readonly session = inject(SessionStore);
  private readonly flags = inject(FlagService);
  private readonly notifications = inject(NotificationsService);

  private socket: WebSocket | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private reconnectAttempts = 0;
  private typingClearTimer: ReturnType<typeof setTimeout> | null = null;
  /** The thread and the time of the last `typing` command actually written to an open socket. */
  private lastTyping: { readonly key: string; readonly at: number } | null = null;
  private intentionalDisconnect = false;

  readonly threads = signal<ChatThread[]>([]);
  readonly loadingThreads = signal<boolean>(false);
  /**
   * The thread the screen is on, by the id **this role's** routes name it with (`ChatRoutes`):
   * a child for a teacher, a thread for a coordinator.
   */
  readonly activeKey = signal<string | null>(null);
  readonly messages = signal<LocalMessage[]>([]);
  readonly loadingMessages = signal<boolean>(false);
  readonly connectionStatus = signal<ChatConnectionStatus>('disconnected');
  /** The other party on the open thread is typing — the parent, or the staff member at the other end. */
  readonly peerTyping = signal<boolean>(false);

  /**
   * Who the server says is connected, by user id (a `presence` frame's `userId` or `parentId`).
   *
   * **T2 item (d).** Nothing but a frame writes this, and `disconnect` empties it: a presence this
   * tab cannot hear any more is not a presence it may keep showing, which is the whole of the
   * "the manager signed out and still reads as Live" bug. A peer nobody has said anything about
   * is absent from the map rather than `false` — "not known" and "offline" are different answers
   * and only one of them is worth a pill.
   */
  private readonly presence = signal<ReadonlyMap<string, boolean>>(new Map());

  /**
   * Is the person at the other end of the open conversation connected? `undefined` = no answer.
   *
   * A `presence` frame wins over the thread row's `peerOnline`, because the row is what the last
   * `GET …/threads` said and the frame is what is true now.
   */
  readonly activePeerOnline = computed<boolean | undefined>(() => {
    const thread = this.activeThread();
    if (!thread) return undefined;
    const heard = this.presence();
    for (const id of this.activePeerIds(thread)) {
      const state = heard.get(id);
      if (state !== undefined) return state;
    }
    // T1's `peerOnline` on the row: what the last `GET …/threads` said, which is the only answer
    // for a parent whose conversation is still empty (there is no parent id anywhere else).
    return thread.peerOnline;
  });

  /**
   * Whose presence would be *this* conversation's.
   *
   * The row names the staff side; a parent is named nowhere on the contract, so her own messages
   * are the source — `senderId` on anything she sent. That is not a guess: it is the parent of this
   * thread by construction, because a thread has exactly one parent on it.
   */
  private activePeerIds(thread: ChatThread): readonly string[] {
    const fromRow = peerIdsOf(thread, this.auth.user()?.id ?? null);
    const parent = this.messages().find(
      (message) => message.sender === ChatMessageSenderEnum.PARENT && (message.senderId ?? '') !== '',
    )?.senderId;
    return parent === undefined ? fromRow : [...fromRow, parent];
  }

  /**
   * Whether this account may write at all.
   *
   * The socket says the same thing from the other end: `ChatHandshake` gives `Peer.chat` to a
   * TEACHER and a COORDINATOR only, so a `message` command from a manager comes back `forbidden`
   * and a REST send has no route to go to. The composer is *hidden* on that answer rather than
   * left to fail — the review found Send enabled for a manager, doing nothing at all on a click.
   */
  readonly canWrite = computed(() => this.routes.transport() !== null);

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
    const key = this.activeKey();
    if (!key) return null;
    const existing = this.threads().find((t) => this.keyOf(t) === key);
    if (existing) return existing;
    const waiting = this.pending();
    return waiting !== null && this.keyOf(waiting) === key ? waiting : null;
  });

  /**
   * The id a thread row is tracked and routed by for whoever is signed in.
   *
   * MG2b: **per thread**, not per account. A teacher has two transports — her parent threads are
   * keyed by child and her threads with the manager by thread — so the row itself decides, and
   * every screen that asks for a key gets the one that row's four routes take.
   */
  keyOf(thread: ChatThread): string {
    return this.routes.transportFor(thread)?.keyOf(thread) ?? thread.id ?? '';
  }

  /**
   * The transport a key's calls go to.
   *
   * The list is the lookup table: a key is a key *of a row*, so the row says which half of the
   * chat it is on. A key with no row yet — the placeholder `openWith` draws for a child who has
   * never been written to — is the primary transport's, which is the only one that creates threads
   * by writing to them.
   */
  private transportFor(key: string): ChatTransport | null {
    const row = this.threads().find((thread) => this.keyOf(thread) === key) ?? this.pending();
    return row === null || row === undefined ? this.routes.transport() : this.routes.transportFor(row);
  }

  readonly totalUnread = computed(() => this.threads().reduce((acc, t) => acc + (t.unread ?? 0), 0));

  /**
   * A `forbidden` error frame with no `clientId` is the handshake saying "you are on the
   * socket for notifications, not for chat". It is an answer, not a fault: the socket stays
   * open, nothing reconnects, and no chat command is sent again.
   */
  readonly chatDenied = signal(false);

  /** Whose `threads`, `activeKey` and `messages` these are — see {@link forgetAccount}. */
  private owner: string | null = null;
  /**
   * Which account's state this is, counted. Bumped by {@link forgetAccount}; every read captures
   * it when it is *asked* and is ignored if it has moved on by the time it *answers* — account A's
   * list or messages landing after B signed in would otherwise put A's rows into B's screen.
   */
  private epoch = 0;

  /**
   * The conversation she was looking at is no longer in her list (she was taken off it, or it was
   * removed). The one case the screen says so; a linked or remembered id is simply let go of.
   */
  readonly activeGone = signal(false);

  constructor() {
    // D2: this service is a root singleton, so what one account opened outlives her sign-out.
    // The next account in the same tab then inherited `activeKey`, and the socket's `onopen`
    // refetched "the active thread" on *her* routes — a 404 "thread not found" in the red band
    // on the first screen she saw. An account's chat state ends with the account.
    effect(() => {
      const user = this.auth.user()?.id ?? null;
      untracked(() => {
        if (user !== this.owner) this.forgetAccount();
        this.owner = user;
      });
    });

    // D26: the socket is the dashboard's event channel. It opens for every signed-in
    // dashboard role, because that is how the notification frame reaches an Admin or a
    // manager; the chat half of it (threads, sends, the screen) stays TEACHER + `chat`.
    effect(() => {
      const role = this.auth.role();
      // R5: a coordinator holds `chat.socket` too (`permissions.json`), so her bell is fed by
      // the same frame every other dashboard role's is.
      const dashboardRole = role !== null;

      if (this.auth.signedIn() && dashboardRole) {
        this.intentionalDisconnect = false;
        this.connect();
        // R7: "the role that has a threads list" rather than "TEACHER" — a coordinator has one
        // too, on her own routes, and a manager has none until RM2 adds it.
        if (this.flags.isOn('chat')) this.loadThreads();
      } else {
        this.disconnect();
      }
    });
  }

  /**
   * The threads list — **every transport's, in one list** (MG2b).
   *
   * A teacher reads two: `GET /teacher/chat/threads` (her parents, by child) and
   * `GET /teacher/chat/staff-threads` (the department manager, by thread). One list rather than
   * two signals, because everything downstream — the badge, the socket's `message` handler, the
   * search — asks one question of one array, and a second copy would be a second thing to keep in
   * step. `forkJoin`, so the list is set once: two `set` calls would flash a half list, and a
   * failure in one half must not blank the other — each catches its own and answers nothing.
   */
  loadThreads(): void {
    // The socket opens for every dashboard role; not all of them have a threads list, and only
    // with the flag on. Asking anyway would be a 403 in the band on every reconnect for an Admin.
    const transports = this.routes.transports();
    if (transports.length === 0 || !this.flags.isOn('chat') || this.chatDenied()) return;
    this.loadingThreads.set(true);
    const asked = this.epoch;
    forkJoin(transports.map((transport) => transport.threads().pipe(catchError(() => of(null)))))
      .pipe(
        tap((lists) => {
          // Asked by an account that has since left: not this account's answer.
          if (asked !== this.epoch) return;
          // A half that failed answered nothing — which is not the same as "she has no threads".
          const complete = lists.every((list) => list !== null);
          const listed = lists.flatMap((list) => list ?? []);
          // A thread `adopt` took from a POST stays even when this read began before it existed.
          const adopted = this.pending();
          const threads =
            adopted !== null &&
            (adopted.id ?? '') !== '' &&
            !listed.some((row) => this.keyOf(row) === this.keyOf(adopted))
              ? [adopted, ...listed]
              : listed;
          this.threads.set(threads);
          this.loadingThreads.set(false);
          // The first read that names the adopted thread confirms it, whether or not she is still
          // on it: from here it is an ordinary row, and nothing keeps it alive but the list.
          if (adopted !== null && threads === listed && (adopted.id ?? '') !== '') this.pending.set(null);
          // The list is the answer to "does this child have a thread": drop a placeholder the
          // server has since confirmed, and mark read what could not be marked without one.
          const active = this.activeKey();
          const real = active === null ? undefined : threads.find((t) => this.keyOf(t) === active);
          if (real) {
            const confirmed = listed.some((row) => this.keyOf(row) === active);
            if (confirmed && this.pending() !== null && this.keyOf(this.pending()!) === active) {
              this.pending.set(null);
            }
            if (real.unread > 0) this.markRead(active!);
          } else if (complete && active !== null && !this.holds(active)) {
            // D2: a remembered key this list does not hold — a thread she left, or one that was
            // never hers. Forget it and fall back to the list; asking the server for it would be
            // a 404 in the red band for something she did not do.
            this.activeKey.set(null);
            this.messages.set([]);
            this.activeGone.set(true);
          }
        }),
        catchError(() => {
          if (asked === this.epoch) this.loadingThreads.set(false);
          return of([]);
        }),
      )
      .subscribe();
  }

  /** Drop everything that belonged to the account that was signed in (D2). */
  private forgetAccount(): void {
    this.epoch += 1;
    this.loadingThreads.set(false);
    this.loadingMessages.set(false);
    this.activeGone.set(false);
    this.threads.set([]);
    this.activeKey.set(null);
    this.messages.set([]);
    this.pending.set(null);
    this.peerTyping.set(false);
    this.chatDenied.set(false);
  }

  /** Whether `key` names a conversation this account can open: a listed row, or one just opened. */
  holds(key: string): boolean {
    if (this.threads().some((thread) => this.keyOf(thread) === key)) return true;
    const waiting = this.pending();
    return waiting !== null && this.keyOf(waiting) === key;
  }

  /**
   * Take a thread a `POST …/chat/threads` just answered (D2: "Message" on a row).
   *
   * The list was read at sign-in and a thread opened a second ago is not in it — and a list read
   * that is still in flight will overwrite whatever is put there. So the row goes in the list *and*
   * is remembered as the pending one: `activeThread` can draw it either way, and {@link loadThreads}
   * keeps it when the server's list does not (yet) name it. Returns the key to select it by.
   */
  adopt(thread: ChatThread): string {
    const key = this.keyOf(thread);
    if (key === '') return key;
    if (!this.threads().some((row) => this.keyOf(row) === key)) {
      this.threads.update((rows) => [thread, ...rows]);
      this.pending.set(thread);
    }
    return key;
  }

  selectThread(key: string): void {
    if (this.activeKey() === key) return;
    // Leaving a thread `adopt` took: it stops being the pending one. What keeps it in the list
    // from here is the server naming it, and no frame refetches the list on its account.
    const waiting = this.pending();
    if (waiting !== null && (waiting.id ?? '') !== '' && this.keyOf(waiting) !== key) this.pending.set(null);
    this.activeGone.set(false);
    this.activeKey.set(key);
    this.peerTyping.set(false);
    this.loadMessages(key);
    // Only a thread that exists can be marked read: `POST …/read` answers 404 without one, and
    // the error interceptor would put that 404 in a red band over a conversation she just opened.
    if (this.threads().some((t) => this.keyOf(t) === key)) this.markRead(key);
  }

  /**
   * Open the conversation with a child's parent, thread or no thread (U1 item 6).
   *
   * What "Message parent" on the Children tab and on the child's page mean: the screen shows the
   * composer straight away, and the first message creates the thread server-side.
   */
  openWith(childId: string, childName: string, className?: string): void {
    if (!this.threads().some((t) => this.keyOf(t) === childId)) {
      this.pending.set({
        id: '',
        childId,
        childName,
        className,
        teacherId: this.auth.user()?.id ?? '',
        teacherName: this.auth.user()?.displayName ?? '',
        unread: 0,
        // R4 made the staff peer, the topic and the status part of a thread row. A teacher's own
        // conversation about one child is the C1 shape, which is what these three defaults say.
        staffRole: ChatThreadStaffRoleEnum.TEACHER,
        topic: ChatThreadTopicEnum.QUESTION,
        status: ChatThreadStatusEnum.OPEN,
      });
    }
    this.selectThread(childId);
  }

  loadMessages(key: string): void {
    // The new thread's stream starts empty whether or not there is anything to fetch it with.
    // The review found the early return happening *first*: a manager moving between two
    // frame-built rows kept the previous thread's messages under the new header, which reads as
    // the wrong conversation rather than as an empty one.
    this.messages.set([]);
    const transport = this.transportFor(key);
    if (transport === null) return;
    this.loadingMessages.set(true);
    const asked = this.epoch;
    transport
      .messages(key)
      .pipe(
        tap((msgs) => {
          // Another account's conversation, or one she has already moved on from: not hers to show.
          if (asked !== this.epoch || this.activeKey() !== key) return;
          this.messages.set(msgs);
          this.loadingMessages.set(false);
        }),
        catchError(() => {
          if (asked === this.epoch && this.activeKey() === key) this.loadingMessages.set(false);
          return of([]);
        }),
      )
      .subscribe();
  }

  /**
   * Send what the composer holds: text, files already uploaded (D4), or both.
   *
   * Files travel by id (`attachmentIds`, B5) on the socket's `message` command and on the REST
   * send alike; the server fills in the rest of each attachment and the echo carries them whole.
   */
  sendMessage(body: string, attachments: readonly ChatAttachment[] = []): void {
    const key = this.activeKey();
    const transport = key === null ? null : this.transportFor(key);
    const cleanBody = body.trim();
    if (!key || transport === null || cleanBody.length > 2000) return;
    if (cleanBody === '' && attachments.length === 0) return;
    // A sent message ends the typing: the next keystroke is a new "typing", said at once.
    this.lastTyping = null;

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
      ...(attachments.length > 0 ? { attachments: [...attachments] } : {}),
    };

    this.messages.update((list) => [...list, optimistic]);

    const attachmentIds = attachments.map((attachment) => attachment.id);
    // On the socket when it is open — the echo with this `clientId` is the ack — else over REST.
    if (this.socketOpen()) {
      const command: ChatClientCommand = {
        type: 'message',
        ...transport.commandKey(key),
        body: cleanBody,
        clientId,
        ...(attachmentIds.length > 0 ? { attachmentIds } : {}),
      };
      this.socket?.send(JSON.stringify(command));
    } else {
      // Fall back to REST
      const request: SendChatMessageRequest = { body: cleanBody, clientId };
      if (attachmentIds.length > 0) request.attachmentIds = attachmentIds;
      transport
        .send(key, request)
        .pipe(
          tap((msg) => {
            this.handleServerMessage(msg, clientId);
          }),
          catchError((err: unknown) => {
            const errorCode = apiErrorCodeOf(err) ?? undefined;
            this.messages.update((list) =>
              list.map((m) =>
                m.clientId === clientId
                  ? { ...m, pending: false, failed: true, errorMessage: 'Failed to send', errorCode }
                  : m,
              ),
            );
            return of(err);
          }),
        )
        .subscribe();
    }
  }

  /**
   * Send a failed message again — the same words and the same files, by id (D4). The failed bubble
   * is replaced by the new attempt, so nothing she wrote or attached has to be done twice.
   */
  retry(clientId: string): void {
    const failed = this.messages().find((message) => message.clientId === clientId && message.failed);
    if (failed === undefined) return;
    this.messages.update((list) => list.filter((message) => message.clientId !== clientId));
    this.sendMessage(failed.body, failed.attachments ?? []);
  }

  /**
   * She typed in the open thread's composer: tell the other party, at most every
   * {@link TYPING_EVERY_MS} per thread.
   *
   * The time is taken only when a frame is actually written. Before D4 it was taken first, so a
   * keystroke made while the socket was reconnecting used up the window and the frames after it
   * were skipped too. A thread with no row yet (a first message) has nobody to tell.
   */
  sendTyping(): void {
    const key = this.activeKey();
    const transport = key === null ? null : this.transportFor(key);
    if (!key || transport === null || !this.socketOpen()) return;
    if (!this.threads().some((thread) => this.keyOf(thread) === key)) return;

    const now = Date.now();
    const last = this.lastTyping;
    if (last !== null && last.key === key && now - last.at < TYPING_EVERY_MS) return;

    const command: ChatClientCommand = { type: 'typing', ...transport.commandKey(key) };
    this.socket?.send(JSON.stringify(command));
    this.lastTyping = { key, at: now };
  }

  private socketOpen(): boolean {
    return this.socket !== null && this.socket.readyState === WebSocket.OPEN;
  }

  markRead(key: string): void {
    const transport = this.transportFor(key);
    if (transport === null) return;
    if (this.socketOpen()) {
      const command: ChatClientCommand = { type: 'read', ...transport.commandKey(key) };
      this.socket?.send(JSON.stringify(command));
    }

    // Call REST markRead as well for durability
    transport
      .read(key)
      .pipe(catchError(() => of(null)))
      .subscribe();

    // Clear local unread counter for this thread
    this.threads.update((list) => list.map((t) => (this.keyOf(t) === key ? { ...t, unread: 0 } : t)));
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
      // Need token: refresh session first. `endsSession: false` — opening a socket is not
      // something she did, so it must not be what signs her out (T2 follow-up).
      this.auth
        .refresh({ endsSession: false })
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
        // Only a conversation this account still holds (D2): a remembered id is not refetched.
        const activeId = this.activeKey();
        const transport = activeId === null || !this.holds(activeId) ? null : this.transportFor(activeId);
        if (activeId && transport !== null) {
          const msgs = this.messages();
          const lastMsg = msgs[msgs.length - 1];
          if (lastMsg && !lastMsg.pending) {
            const asked = this.epoch;
            transport
              .messages(activeId, lastMsg.id)
              .pipe(
                tap((newMsgs) => {
                  if (asked === this.epoch && this.activeKey() === activeId && newMsgs.length > 0) {
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
          this.receive(frame);
        } catch {
          // Ignore unparseable frame
        }
      };

      this.socket.onclose = (event: CloseEvent) => {
        this.notifications.onSocketClosed();
        // T1 closes every socket of an account that signs out, `1000 signed out`. Reconnecting on
        // that is a loop with nothing at the end of it: the refresh token is revoked, so each
        // attempt refreshes, fails, backs off and tries again for as long as the tab is open. A
        // `1000 idle` close is the opposite — she stopped typing, and coming back is right — so it
        // is the reason, not the code, that decides.
        const deliberate = this.intentionalDisconnect || event.reason === SIGNED_OUT_REASON;
        if (deliberate) {
          this.intentionalDisconnect = true;
          this.connectionStatus.set('disconnected');
          this.presence.set(new Map());
          return;
        }
        this.connectionStatus.set('reconnecting');
        this.scheduleReconnect();
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

  /**
   * One frame off `/ws/chat`. Public because the socket is not the only thing that can hand the
   * service a frame — a test does too, and so would a replay — and `NotificationsService.receive`
   * already reads that way.
   */
  receive(frame: ChatServerFrame): void {
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
        // T2 item (c): the toast is for a notification she is **not** already looking at. A
        // `chat.message` for the thread open on her screen is a bubble arriving in the same
        // second — a toast over it says the same thing twice and steals the focus ring.
        this.notifications.receive(frame.notification, {
          toast: !this.isViewingThread(frame.notification.link),
        });
        break;

      // T2 item (d): presence comes from the server, which is the only party that knows which
      // sockets are open. Sign-out closes them (T1), so the frame that clears a peer arrives
      // before the tab that was watching her can go stale.
      case 'presence': {
        const id = frame.userId ?? frame.parentId ?? '';
        if (id !== '') {
          this.presence.update((map) => new Map(map).set(id, frame.online));
        }
        break;
      }

      // The server sends `typing` to everybody on the thread *except* the person typing, on all
      // of her sessions, so a frame for the open thread is always the other party — a parent, or
      // (D4) the staff member at the other end of a staff thread, whose frames say `teacher`.
      case 'typing': {
        const active = this.activeThread();
        if (active && active.id === frame.threadId) {
          this.peerTyping.set(true);
          if (this.typingClearTimer) clearTimeout(this.typingClearTimer);
          this.typingClearTimer = setTimeout(() => {
            this.peerTyping.set(false);
          }, TYPING_TIMEOUT_MS);
        }
        break;
      }

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

      case 'status':
        // R4's `status` frame, which both parties hear. It updates this list and nothing else —
        // there is no refetch — so every screen that *reads* this signal moves with it: the
        // thread header, and the complaints inbox, whose rows take their status from here
        // (`coordinator-complaints.page.ts`). A screen backed only by its own request would not,
        // which is what the R7 review found.
        this.threads.update((list) =>
          list.map((t) =>
            t.id === frame.threadId
              ? {
                  ...t,
                  status:
                    frame.status === 'resolved' ? ChatThreadStatusEnum.RESOLVED : ChatThreadStatusEnum.OPEN,
                  resolvedAt: frame.status === 'resolved' ? frame.at : undefined,
                }
              : t,
          ),
        );
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
                ? { ...m, pending: false, failed: true, errorMessage: frame.message, errorCode: frame.code }
                : m,
            ),
          );
        }
        break;
    }
  }

  private handleServerMessage(message: ChatMessage, clientId?: string): void {
    const activeKey = this.activeKey();
    // The first message of a new conversation *is* the thread: refetch the list so the sidebar
    // has the row the server just created, and let it take the placeholder's place.
    // Only the placeholder `openWith` drew (it has no id yet) — an adopted thread is a real row.
    if (this.pending() !== null && (this.pending()?.id ?? '') === '' && message.threadId) {
      this.loadThreads();
    }
    const active = this.activeThread();
    const isCurrentThread = active !== null && active.id === message.threadId;
    // The echo of a message this client sent, identified by its own `clientId`. It settles the
    // optimistic bubble even when the thread it created is younger than the thread list.
    const isOwnEcho = clientId !== undefined && this.messages().some((m) => m.clientId === clientId);

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
      if (message.sender === ChatMessageSenderEnum.PARENT && activeKey) {
        this.markRead(activeKey);
      }
    }

    // Update threads list preview and unread count
    this.threads.update((threadsList) => {
      const idx = threadsList.findIndex((t) => t.id === message.threadId);
      if (idx === -1) {
        // RM3b: every role has a `GET …/threads` now, so a frame for a thread this list has never
        // seen is a refetch for everyone — which is also how a thread created by this very
        // message gets its real row, with the peer's name and the child on it.
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
      // Refresh token before reconnecting as per runbook — and with `endsSession: false`, so a
      // refusal here can only cost another backoff. Cloud Run closes this socket every hour and
      // every blip reopens it, so if this path could forget the session the socket alone would
      // sign her out while she was reading a lesson.
      this.auth
        .refresh({ endsSession: false })
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
    // Presence is only as live as the socket that carries it (T2 item d).
    this.presence.set(new Map());
    this.peerTyping.set(false);
  }

  /**
   * Does this notification's `link` name the conversation already on screen?
   *
   * The thread id and nothing else: the path is the sender's idea of which area the reader is in
   * (`NotificationService.threadLink` writes one for everybody), and the reader's own screen is
   * the authority on that — the same reason `notificationTarget` reads only the query.
   */
  private isViewingThread(link: string | undefined): boolean {
    const active = this.activeThread();
    if (!active?.id || !link) return false;
    const thread = new URLSearchParams(link.split('?', 2)[1] ?? '').get('thread')?.trim();
    return thread !== undefined && thread === active.id;
  }
}
