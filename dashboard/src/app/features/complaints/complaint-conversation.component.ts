/* hq-flag: chat — a complaint is stored and delivered as a conversation (B6) */
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterRenderEffect,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { catchError, of, tap } from 'rxjs';
import {
  type ChatMessage,
  type ComplaintDetail,
  type ComplaintEvent,
  ChatMessageSenderEnum,
  ComplaintStatusEnum,
  apiErrorCodeOf,
} from '../../api';
import { AuthService } from '../../core/auth/auth.service';
import { CHAT_ACCEPT, CHAT_MAX_ATTACHMENTS, attachmentsOf } from '../../core/chat/chat-attachments';
import { ChatUploads } from '../../core/chat/chat-uploads';
import {
  COMPLAINT_PAGE,
  ComplaintsService,
  type ComplaintStatus,
} from '../../core/complaints/complaints.service';
import { activeLang } from '../../core/i18n/active-lang';
import { PermissionService } from '../../core/permissions/permission.service';
import { ChatAttachmentsComponent } from '../chat/chat-attachments.component';
import { ChatStagedFilesComponent } from '../chat/chat-staged-files.component';
import {
  BandComponent,
  ButtonComponent,
  ListStaggerDirective,
  SkeletonComponent,
  TextareaComponent,
} from '../../ui';

/** One line of the conversation: a message, or a status change drawn as a system line. */
type Line =
  | { readonly kind: 'message'; readonly key: string; readonly at: number; readonly message: LocalMessage }
  | { readonly kind: 'event'; readonly key: string; readonly at: number; readonly event: ComplaintEvent };

interface LocalMessage extends ChatMessage {
  readonly clientId?: string;
  readonly pending?: boolean;
}

/**
 * **One complaint** (D5): its messages and every status change, interleaved by time — "Resolved by
 * Nour · 3 Oct" between the bubbles — a composer for the two people on it, and Resolve / Reopen.
 *
 * Who may do what is the server's answer, not a guess from the role: `canReply` is on the row
 * (true for the recipient; a supervisor and the Admin read it), and every complaint a supervisor
 * can see is one she may move ({@link ComplaintsService.canMoveStatus}). A reply goes over REST
 * (B6: the socket's commands address Messages threads) and comes back on the socket too, so the
 * bubble is settled by whichever of the two lands first and never drawn twice.
 */
@Component({
  selector: 'hq-complaint-conversation',
  // Its own staged files: they live and die with this composer, as Messages' do with its own.
  providers: [ChatUploads],
  imports: [
    ChatAttachmentsComponent,
    ChatStagedFilesComponent,
    BandComponent,
    ButtonComponent,
    ListStaggerDirective,
    SkeletonComponent,
    TextareaComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (asking(); as next) {
      <hq-band
        variant="error"
        [open]="true"
        [title]="'complaints.confirm.' + next + '.title' | transloco"
        [confirmLabel]="'complaints.confirm.' + next + '.action' | transloco"
        (confirmed)="move(next)"
        (dismissed)="asking.set(null)"
      >
        {{ 'complaints.confirm.' + next + '.body' | transloco }}
      </hq-band>
    }
    @if (failure(); as text) {
      <hq-band
        variant="error"
        [open]="true"
        [title]="'band.failed' | transloco"
        (dismissed)="failure.set(null)"
      >
        {{ text }}
      </hq-band>
    }

    <!-- A link to a complaint that is not hers (or gone) draws nothing: \`missing\` takes her back
         to the list, quietly (D1/D2) — never a Back bar over a blank page. -->
    @if (detail.error() === undefined) {
      <div class="complaint__bar">
        <hq-button variant="quiet" (pressed)="closed.emit()">{{ 'complaints.back' | transloco }}</hq-button>
      </div>
    }

    @if (detail.isLoading() && !detail.hasValue()) {
      <hq-skeleton [loading]="true" [lines]="6" [label]="'ui.loading' | transloco" />
    } @else if (complaint(); as c) {
      <section class="complaint" [attr.aria-labelledby]="'complaint-title-' + c.id">
        <header class="complaint__head">
          <div class="complaint__heading">
            <h2 #heading class="complaint__title" tabindex="-1" [id]="'complaint-title-' + c.id">
              <bdi>{{ c.title }}</bdi>
            </h2>
            <p class="complaint__meta">
              <bdi>{{ c.childName }}</bdi>
              @if (c.className) {
                · <bdi>{{ c.className }}</bdi>
              }
              · {{ 'complaints.from' | transloco }} <bdi>{{ parentLabel() }}</bdi> ·
              {{ 'complaints.to' | transloco }} <bdi>{{ c.recipientName }}</bdi> ({{
                'complaints.role.' + c.recipientRole | transloco
              }})
              @if (c.subject) {
                · <bdi>{{ c.subject }}</bdi>
              }
            </p>
          </div>
          <span class="hq-badge" [class.hq-badge--warning]="isOpen()" [class.hq-badge--success]="!isOpen()">
            {{ 'complaints.status.' + c.status | transloco }}
          </span>
          @if (complaints.canMoveStatus()) {
            <hq-button variant="secondary" [loading]="moving()" (pressed)="ask()">
              {{ (isOpen() ? 'complaints.resolve' : 'complaints.reopen') | transloco }}
            </hq-button>
          }
        </header>

        @if (hasOlder()) {
          <div class="complaint__older">
            <hq-button variant="quiet" [loading]="loadingOlder()" (pressed)="loadOlder()">
              {{ 'complaints.older' | transloco }}
            </hq-button>
          </div>
        }
        <ol
          #stream
          class="complaint__stream"
          hqListStagger
          [attr.aria-label]="'complaints.conversation' | transloco"
        >
          @for (line of lines(); track line.key) {
            @if (line.kind === 'event') {
              <li class="complaint__event" [attr.data-key]="line.key">
                {{ 'complaints.event.' + line.event.status | transloco: { name: line.event.byName } }}
                · {{ day(line.at) }}
              </li>
            } @else {
              @let mine = isMine(line.message);
              <li
                class="complaint__message"
                [class.complaint__message--mine]="mine"
                [attr.data-key]="line.key"
              >
                <bdi class="complaint__sender">{{ senderOf(line.message) }}</bdi>
                @if (line.message.body) {
                  <p class="complaint__body" dir="auto">{{ line.message.body }}</p>
                }
                @let files = attachmentsOf(line.message);
                @if (files.length > 0) {
                  <hq-chat-attachments class="complaint__files" [attachments]="files" />
                }
                <span class="complaint__time">
                  {{ day(line.at) }} {{ time(line.at) }}
                  @if (line.message.pending) {
                    · {{ 'complaints.sending' | transloco }}
                  } @else if (mine && line.message.readAt) {
                    · {{ 'chat.read' | transloco }}
                  }
                </span>
              </li>
            }
          }
        </ol>

        @if (canReply()) {
          <!-- \`novalidate\`: files alone are a reply too, so an empty box must not stop the send. -->
          <form class="complaint__composer" novalidate (submit)="$event.preventDefault(); send()">
            <hq-chat-staged-files />
            <hq-textarea
              [label]="'complaints.reply' | transloco"
              [placeholder]="'complaints.replyPlaceholder' | transloco"
              [rows]="2"
              [autoGrow]="true"
              [maxLength]="2000"
              [required]="true"
              [(value)]="draft"
              (keydown.enter)="onEnter($event)"
            />
            <div class="complaint__send">
              <hq-button
                variant="secondary"
                [disabled]="sending() || uploads.staged().length >= maxFiles"
                (pressed)="fileInput.click()"
              >
                {{ 'chat.attachFile' | transloco }}
              </hq-button>
              <input
                #fileInput
                type="file"
                multiple
                hidden
                [accept]="acceptedTypes"
                (change)="onFiles($event)"
              />
              <hq-button variant="primary" type="submit" [disabled]="!canSend()" [loading]="sending()">
                {{ 'complaints.send' | transloco }}
              </hq-button>
            </div>
          </form>
        } @else {
          <p class="complaint__readonly" role="note">
            {{
              (complaints.area() === 'admin' ? 'complaints.readOnly.admin' : 'complaints.readOnly.supervisor')
                | transloco: { name: c.recipientName }
            }}
          </p>
        }
        <!-- The end of the conversation, under the composer: scrolled to on open and on every
             new line, so the newest message sits right above the box rather than behind it. -->
        <div #end></div>
      </section>
    }
  `,
  styles: `
    :host {
      display: block;
    }

    .complaint__bar {
      margin-block-end: var(--hq-space-16);
    }

    .complaint__head {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: var(--hq-space-12);
      padding-block-end: var(--hq-space-16);
      border-block-end: var(--hq-size-rule) solid var(--hq-color-ink);
    }

    .complaint__heading {
      flex: 1 1 0;
      min-inline-size: 0;
    }

    .complaint__title {
      margin: 0;
      font-size: var(--hq-text-title-md);
      line-height: var(--hq-text-title-md-line);
      font-weight: var(--hq-text-weight-semibold);
      overflow-wrap: anywhere;
    }

    .complaint__meta {
      margin: var(--hq-space-4) 0 0;
      color: var(--hq-color-ink-muted);
      font-size: var(--hq-text-note);
    }

    .complaint__older {
      display: flex;
      justify-content: center;
      padding-block-start: var(--hq-space-16);
    }

    .complaint__title:focus {
      outline: none;
    }

    .complaint__title:focus-visible {
      outline: var(--hq-size-rule) solid var(--hq-color-accent);
    }

    .complaint__stream {
      list-style: none;
      margin: 0;
      padding: var(--hq-space-16) 0;
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-12);
    }

    .complaint__message {
      align-self: flex-start;
      max-inline-size: 72%;
      padding: var(--hq-space-12) var(--hq-space-16);
      border: var(--hq-size-rule-thin) solid var(--hq-color-rule);
      border-radius: var(--hq-radius-card);
      background: var(--hq-color-surface);
      color: var(--hq-color-ink);
    }

    .complaint__message--mine {
      align-self: flex-end;
      background: var(--hq-color-accent-soft);
      color: var(--hq-color-accent-on-soft);
      border-color: transparent;
    }

    .complaint__sender {
      display: block;
      font-size: var(--hq-font-label-size);
      font-weight: var(--hq-font-label-weight);
      letter-spacing: var(--hq-font-letter-spacing-label);
    }

    .complaint__body {
      margin: var(--hq-space-4) 0;
      white-space: pre-wrap;
      overflow-wrap: anywhere;
    }

    .complaint__time {
      font-size: var(--hq-text-theme-2xs);
      color: var(--hq-color-ink-muted);
    }

    .complaint__event {
      align-self: center;
      font-size: var(--hq-text-note);
      color: var(--hq-color-ink-muted);
      text-align: center;
    }

    .complaint__composer {
      position: sticky;
      inset-block-end: 0;
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-8);
      padding-block: var(--hq-space-16);
      border-block-start: var(--hq-size-rule) solid var(--hq-color-ink);
      background: var(--hq-color-bg);
    }

    .complaint__files {
      display: block;
      margin-block: var(--hq-space-4);
    }

    .complaint__send {
      display: flex;
      justify-content: flex-end;
      gap: var(--hq-space-8);
    }

    .complaint__readonly {
      margin: 0;
      padding: var(--hq-space-16);
      border-block-start: var(--hq-size-rule) solid var(--hq-color-ink);
      color: var(--hq-color-ink-muted);
      font-size: var(--hq-text-note);
      text-align: center;
    }
  `,
})
export class ComplaintConversationComponent {
  protected readonly complaints = inject(ComplaintsService);
  private readonly auth = inject(AuthService);
  private readonly permissions = inject(PermissionService);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  readonly complaintId = input.required<string>();
  /** Back to the list. */
  readonly closed = output<void>();
  /** It moved, or she wrote in it: the list behind it is stale. */
  readonly changed = output<void>();
  /** The link named a complaint she cannot open (another account's, gone, or never there). */
  readonly missing = output<void>();

  protected readonly draft = signal('');
  protected readonly asking = signal<ComplaintStatus | null>(null);
  protected readonly moving = signal(false);
  protected readonly failure = signal<string | null>(null);
  protected readonly sending = signal(false);
  protected readonly uploads = inject(ChatUploads);
  protected readonly acceptedTypes = CHAT_ACCEPT;
  protected readonly maxFiles = CHAT_MAX_ATTACHMENTS;
  protected readonly attachmentsOf = attachmentsOf;

  /** The page that was read stopped at {@link COMPLAINT_PAGE}: there may be earlier messages. */
  protected readonly hasOlder = signal(false);
  protected readonly loadingOlder = signal(false);

  protected readonly detail = rxResource<ComplaintDetail, string | undefined>({
    params: () => (this.complaints.ready() ? this.complaintId() : undefined),
    stream: ({ params }) =>
      this.complaints.detail(params).pipe(
        tap((detail) => {
          this.hasOlder.set(detail.messages.length >= COMPLAINT_PAGE);
          this.loadingOlder.set(false);
        }),
      ),
  });

  protected readonly complaint = computed(() =>
    this.detail.hasValue() ? this.detail.value().complaint : null,
  );
  protected readonly isOpen = computed(() => this.complaint()?.status === ComplaintStatusEnum.OPEN);
  protected readonly canReply = computed(
    () =>
      this.complaint()?.canReply === true &&
      this.complaints.area() !== 'admin' &&
      !this.permissions.readOnly(),
  );

  protected readonly parentLabel = computed(() => {
    this.lang();
    const c = this.complaint();
    if (c === null) return '';
    return c.parentName ?? this.transloco.translate<string>('chat.parent', { child: c.childName });
  });

  /**
   * The messages read so far and the status changes **between them**. B6 sends every event with
   * only the newest page of messages, so while earlier messages are still unread an event older
   * than the oldest one shown would sit above it with nothing to say where it happened.
   */
  protected readonly lines = computed<readonly Line[]>(() => {
    if (!this.detail.hasValue()) return [];
    const { messages, events: all } = this.detail.value() as {
      messages: LocalMessage[];
      events: ComplaintEvent[];
    };
    const from = this.hasOlder()
      ? (messages[0]?.createdAt ?? Number.NEGATIVE_INFINITY)
      : Number.NEGATIVE_INFINITY;
    const events = all.filter((event) => event.at >= from);
    return [
      ...messages.map((message) => ({
        kind: 'message' as const,
        key: message.id,
        at: message.createdAt,
        message,
      })),
      ...events.map((event) => ({
        kind: 'event' as const,
        key: `${event.status}:${event.at}`,
        at: event.at,
        event,
      })),
    ].sort((a, b) => a.at - b.at);
  });

  private readonly end = viewChild<ElementRef<HTMLElement>>('end');
  private readonly heading = viewChild<ElementRef<HTMLElement>>('heading');
  private readonly stream = viewChild<ElementRef<HTMLElement>>('stream');
  /** The newest line: the stream follows it, and only it — a page prepended above does not. */
  private readonly newest = computed(() => this.lines().at(-1)?.key ?? null);
  /** Where the first line sat before older ones were prepended above it. */
  private keepPlace: { readonly key: string; readonly top: number } | null = null;
  private focusedFor: string | null = null;

  constructor() {
    const destroyRef = inject(DestroyRef);

    afterRenderEffect(() => {
      const c = this.complaint();
      const newest = this.newest();
      if (c === null || newest === null) return;
      // Opened: the heading takes focus, so a screen reader starts at the complaint.
      if (this.focusedFor !== c.id) {
        this.focusedFor = c.id;
        this.heading()?.nativeElement.focus({ preventScroll: true });
      }
      // Instant, never smooth: it is where the screen opens, not a motion of its own.
      this.end()?.nativeElement.scrollIntoView?.({ block: 'end' });
    });

    // Older messages went in above: put the line she was looking at back where it was.
    afterRenderEffect(() => {
      this.lines();
      const keep = this.keepPlace;
      const line = keep === null ? null : this.lineElement(keep.key);
      if (keep === null || line === null) return;
      this.keepPlace = null;
      scrollerOf(line).scrollBy?.(0, line.getBoundingClientRect().top - keep.top);
    });

    effect(() => {
      if (this.detail.error() === undefined) return;
      untracked(() => this.missing.emit());
    });

    // Another complaint in the same component (the bell, a link) or another account: nothing
    // she had picked, typed or been asked about belongs to it — the files are cancelled and
    // dropped exactly as Messages drops them when the thread changes.
    let owner: string | null | undefined;
    effect(() => {
      const id = this.complaintId();
      const user = this.auth.user()?.id ?? null;
      untracked(() => {
        this.complaints.viewing.set(id);
        if (owner !== undefined) this.reset();
        owner = user;
      });
    });
    destroyRef.onDestroy(() => this.complaints.viewing.set(null));

    // Opening it reads it — for the recipient. A supervisor's `…/read` is 403 (B6): her unread is
    // always 0, so there is nothing for her to clear.
    effect(() => {
      const c = this.complaint();
      if (c === null || !c.canReply || c.unread === 0) return;
      untracked(() => this.read(c.id));
    });

    this.complaints.signals.pipe(takeUntilDestroyed()).subscribe((signal) => {
      const id = this.complaintId();
      if (signal.kind === 'message' && signal.message.threadId === id) {
        this.accept(signal.message, signal.clientId);
        if (signal.message.sender === ChatMessageSenderEnum.PARENT && this.canReply()) this.read(id);
      } else if (signal.kind === 'read' && signal.complaintId === id) {
        this.patchMessages((list) =>
          list.map((m) =>
            m.sender !== ChatMessageSenderEnum.PARENT && !m.readAt ? { ...m, readAt: signal.readAt } : m,
          ),
        );
      } else if (signal.kind === 'status' && signal.complaintId === id) {
        // The frame says what and when, not who: the detail is read again for the event line.
        this.refresh();
      }
    });
  }

  protected isMine(message: ChatMessage): boolean {
    return message.senderId === this.auth.user()?.id;
  }

  protected senderOf(message: ChatMessage): string {
    this.lang();
    if (this.isMine(message)) return this.transloco.translate<string>('chat.you');
    if (message.sender === ChatMessageSenderEnum.PARENT) return this.parentLabel();
    return this.complaint()?.recipientName ?? '';
  }

  protected day(at: number): string {
    return new Intl.DateTimeFormat(this.locale(), { day: 'numeric', month: 'short' }).format(at);
  }

  protected time(at: number): string {
    return new Intl.DateTimeFormat(this.locale(), { hour: 'numeric', minute: '2-digit' }).format(at);
  }

  protected onEnter(event: Event): void {
    if ((event as KeyboardEvent).shiftKey) return;
    event.preventDefault();
    this.send();
  }

  protected ask(): void {
    this.failure.set(null);
    this.asking.set(this.isOpen() ? 'resolved' : 'open');
  }

  protected move(status: ComplaintStatus): void {
    this.asking.set(null);
    this.moving.set(true);
    this.complaints.setStatus(this.complaintId(), status).subscribe({
      next: () => {
        this.moving.set(false);
        this.refresh();
        this.complaints.refreshCounts();
        this.changed.emit();
      },
      error: (error: unknown) => {
        this.moving.set(false);
        this.failure.set(this.reason(error, 'complaints.moveFailed'));
      },
    });
  }

  /**
   * Text, files, or both — never while a file is still uploading or was refused, so a reply never
   * leaves without the file she attached (D4's rule, the same as Messages').
   */
  protected readonly canSend = computed(() => {
    if (this.sending() || this.uploads.busy() || this.uploads.failed()) return false;
    return this.draft().trim() !== '' || this.uploads.attachments().length > 0;
  });

  protected onFiles(event: Event): void {
    const input = event.target as HTMLInputElement;
    const files = input.files ? Array.from(input.files) : [];
    input.value = '';
    if (files.length > 0) this.uploads.add(files);
  }

  protected send(): void {
    const body = this.draft().trim();
    const attachments = this.uploads.attachments();
    const c = this.complaint();
    if (!this.canSend() || c === null || !this.canReply()) return;
    const clientId = crypto.randomUUID();
    const me = this.auth.user()?.id ?? '';
    this.draft.set('');
    this.failure.set(null);
    this.sending.set(true);
    this.patchMessages((list) => [
      ...list,
      {
        id: `pending:${clientId}`,
        clientId,
        pending: true,
        threadId: c.id,
        body,
        attachments: [...attachments],
        sender: ChatMessageSenderEnum.TEACHER,
        senderId: me,
        createdAt: Date.now(),
      },
    ]);
    this.complaints
      .reply(
        c.id,
        body,
        clientId,
        attachments.map((file) => file.id),
      )
      .subscribe({
        next: (message) => {
          // She has moved on to another complaint: the reply landed, but not on this screen.
          if (c.id !== this.complaintId()) return;
          this.sending.set(false);
          // The files went with it: the composer is empty only now, so a failure keeps them.
          this.uploads.clear();
          this.accept(message, clientId);
          this.changed.emit();
        },
        // Rolled back: the bubble leaves, the words go back in the box (the files never left it),
        // and the band says why.
        error: (error: unknown) => {
          if (c.id !== this.complaintId()) return;
          this.sending.set(false);
          this.patchMessages((list) => list.filter((m) => m.clientId !== clientId));
          if (this.draft() === '') this.draft.set(body);
          // A file that already went with another message cannot go again: it leaves the
          // composer, and the band says to attach it afresh.
          if (apiErrorCodeOf(error) === 'attachment_already_sent') this.uploads.clear();
          this.failure.set(this.reason(error, 'complaints.sendFailed'));
        },
      });
  }

  /** The page before the oldest message shown, prepended, with the screen kept where it was. */
  protected loadOlder(): void {
    const id = this.complaintId();
    const oldest = this.loadedMessages().find((m) => m.pending !== true);
    if (oldest === undefined || this.loadingOlder()) return;
    const first = this.lines()[0];
    const line = first === undefined ? null : this.lineElement(first.key);
    this.loadingOlder.set(true);
    this.complaints.detail(id, oldest.id).subscribe({
      next: (page) => {
        this.loadingOlder.set(false);
        if (page.complaint.id !== this.complaintId() || !this.detail.hasValue()) return;
        this.keepPlace =
          first !== undefined && line !== null
            ? { key: first.key, top: line.getBoundingClientRect().top }
            : null;
        this.hasOlder.set(page.messages.length >= COMPLAINT_PAGE);
        const current = this.detail.value();
        const shown = new Set(current.messages.map((m) => m.id));
        this.detail.set({
          ...current,
          events: page.events,
          messages: [...page.messages.filter((m) => !shown.has(m.id)), ...current.messages],
        });
      },
      error: (error: unknown) => {
        this.loadingOlder.set(false);
        this.failure.set(this.reason(error, 'complaints.olderFailed'));
      },
    });
  }

  private loadedMessages(): readonly LocalMessage[] {
    return this.detail.hasValue() ? this.detail.value().messages : [];
  }

  private lineElement(key: string): HTMLElement | null {
    return this.stream()?.nativeElement.querySelector<HTMLElement>(`[data-key="${key}"]`) ?? null;
  }

  /** A message from the POST or the socket: settles its pending bubble, and is never drawn twice. */
  private accept(message: ChatMessage, clientId?: string): void {
    this.patchMessages((list) => {
      if (list.some((m) => m.id === message.id))
        return list.filter((m) => clientId === undefined || m.clientId !== clientId);
      const pending = clientId === undefined ? -1 : list.findIndex((m) => m.clientId === clientId);
      if (pending === -1) return [...list, message];
      const next = [...list];
      next[pending] = message;
      return next;
    });
  }

  private patchMessages(change: (list: readonly LocalMessage[]) => LocalMessage[]): void {
    if (!this.detail.hasValue()) return;
    const current = this.detail.value();
    this.detail.set({ ...current, messages: change(current.messages) });
  }

  /** Read again without the skeleton: the stream stays on screen while the new one is fetched. */
  private refresh(): void {
    this.complaints
      .detail(this.complaintId())
      .pipe(catchError(() => of(null)))
      .subscribe((fresh) => {
        if (fresh === null || fresh.complaint.id !== this.complaintId()) return;
        // The newest page again, under whatever older pages she had already read, and a bubble
        // still on its way on top — the server has not seen it yet.
        const loaded = this.loadedMessages();
        const newest = new Set(fresh.messages.map((m) => m.id));
        const freshFrom = fresh.messages[0]?.createdAt ?? Number.POSITIVE_INFINITY;
        const earlier = loaded.filter(
          (m) => m.pending !== true && !newest.has(m.id) && m.createdAt <= freshFrom,
        );
        const pending = loaded.filter((m) => m.pending === true);
        if (earlier.length === 0) this.hasOlder.set(fresh.messages.length >= COMPLAINT_PAGE);
        this.detail.set({ ...fresh, messages: [...earlier, ...fresh.messages, ...pending] });
      });
  }

  private read(id: string): void {
    this.complaints
      .markRead(id)
      .pipe(catchError(() => of(null)))
      .subscribe(() => this.changed.emit());
  }

  /** Empty the composer and the bands: another complaint, or another account. */
  private reset(): void {
    this.uploads.clear();
    this.draft.set('');
    this.failure.set(null);
    this.asking.set(null);
    this.sending.set(false);
    this.moving.set(false);
  }

  /**
   * What went wrong, in her language. The server's own text is English, so it is never shown:
   * the codes a reply's files can fail with have sentences of their own, the rest the fallback.
   */
  private reason(error: unknown, fallback: string): string {
    return this.transloco.translate<string>(reasonKeyOf(error) ?? fallback);
  }

  private locale(): string {
    return this.lang() === 'ar' ? 'ar' : 'en-GB';
  }
}

/** The box that scrolls this element: the shell's content pane, or the document. */
function scrollerOf(element: HTMLElement): Element {
  for (let node = element.parentElement; node !== null; node = node.parentElement) {
    const overflow = getComputedStyle(node).overflowY;
    if ((overflow === 'auto' || overflow === 'scroll') && node.scrollHeight > node.clientHeight) return node;
  }
  return document.scrollingElement ?? document.documentElement;
}

/** The EN/AR sentence for a failure a reply's files can cause; null for any other. */
export function reasonKeyOf(error: unknown): string | null {
  const code = apiErrorCodeOf(error);
  if (code === 'attachment_already_sent') return 'complaints.errors.alreadySent';
  if (code === 'image_too_large') return 'complaints.errors.tooManyPixels';
  if (error instanceof HttpErrorResponse && error.status === 413) return 'complaints.errors.tooBig';
  if (error instanceof HttpErrorResponse && error.status === 411) return 'complaints.errors.notUploaded';
  return null;
}
