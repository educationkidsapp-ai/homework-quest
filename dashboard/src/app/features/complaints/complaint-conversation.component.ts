/* hq-flag: chat — a complaint is stored and delivered as a conversation (B6) */
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { catchError, of } from 'rxjs';
import {
  type ChatMessage,
  type ComplaintDetail,
  type ComplaintEvent,
  ChatMessageSenderEnum,
  ComplaintStatusEnum,
  apiErrorOf,
} from '../../api';
import { AuthService } from '../../core/auth/auth.service';
import { ComplaintsService, type ComplaintStatus } from '../../core/complaints/complaints.service';
import { activeLang } from '../../core/i18n/active-lang';
import { PermissionService } from '../../core/permissions/permission.service';
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
  imports: [
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

    <div class="complaint__bar">
      <hq-button variant="quiet" (pressed)="closed.emit()">{{ 'complaints.back' | transloco }}</hq-button>
    </div>

    @if (detail.isLoading() && !detail.hasValue()) {
      <hq-skeleton [loading]="true" [lines]="6" [label]="'ui.loading' | transloco" />
    } @else if (complaint(); as c) {
      <section class="complaint" [attr.aria-labelledby]="'complaint-title-' + c.id">
        <header class="complaint__head">
          <div class="complaint__heading">
            <h2 class="complaint__title" [id]="'complaint-title-' + c.id">{{ c.title }}</h2>
            <p class="complaint__meta">
              {{ c.childName }}
              @if (c.className) {
                · {{ c.className }}
              }
              · {{ 'complaints.from' | transloco }} {{ parentLabel() }} · {{ 'complaints.to' | transloco }}
              {{ c.recipientName }} ({{ 'complaints.role.' + c.recipientRole | transloco }})
              @if (c.subject) {
                · {{ c.subject }}
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

        <ol class="complaint__stream" hqListStagger [attr.aria-label]="'complaints.conversation' | transloco">
          @for (line of lines(); track line.key) {
            @if (line.kind === 'event') {
              <li class="complaint__event">
                {{ 'complaints.event.' + line.event.status | transloco: { name: line.event.byName } }}
                · {{ day(line.at) }}
              </li>
            } @else {
              @let mine = isMine(line.message);
              <li class="complaint__message" [class.complaint__message--mine]="mine">
                <span class="complaint__sender">{{ senderOf(line.message) }}</span>
                <p class="complaint__body">{{ line.message.body }}</p>
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
          <form class="complaint__composer" (submit)="$event.preventDefault(); send()">
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
              <hq-button variant="primary" type="submit" [disabled]="draft().trim() === ''">
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

    .complaint__send {
      display: flex;
      justify-content: flex-end;
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

  protected readonly draft = signal('');
  protected readonly asking = signal<ComplaintStatus | null>(null);
  protected readonly moving = signal(false);
  protected readonly failure = signal<string | null>(null);

  protected readonly detail = rxResource<ComplaintDetail, string>({
    params: () => this.complaintId(),
    stream: ({ params }) => this.complaints.detail(params),
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

  protected readonly lines = computed<readonly Line[]>(() => {
    if (!this.detail.hasValue()) return [];
    const { messages, events } = this.detail.value() as {
      messages: LocalMessage[];
      events: ComplaintEvent[];
    };
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

  constructor() {
    const destroyRef = inject(DestroyRef);

    effect(() => {
      const id = this.complaintId();
      untracked(() => this.complaints.viewing.set(id));
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

  protected send(): void {
    const body = this.draft().trim();
    const c = this.complaint();
    if (body === '' || c === null || !this.canReply()) return;
    const clientId = crypto.randomUUID();
    const me = this.auth.user()?.id ?? '';
    this.draft.set('');
    this.failure.set(null);
    this.patchMessages((list) => [
      ...list,
      {
        id: `pending:${clientId}`,
        clientId,
        pending: true,
        threadId: c.id,
        body,
        sender: ChatMessageSenderEnum.TEACHER,
        senderId: me,
        createdAt: Date.now(),
      },
    ]);
    this.complaints.reply(c.id, body, clientId).subscribe({
      next: (message) => {
        this.accept(message, clientId);
        this.changed.emit();
      },
      // Rolled back: the bubble leaves, the words go back in the box, and the band says why.
      error: (error: unknown) => {
        this.patchMessages((list) => list.filter((m) => m.clientId !== clientId));
        if (this.draft() === '') this.draft.set(body);
        this.failure.set(this.reason(error, 'complaints.sendFailed'));
      },
    });
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
        // Keep a bubble still on its way: the server has not seen it yet.
        const pending = this.detail.hasValue()
          ? (this.detail.value().messages as LocalMessage[]).filter((m) => m.pending === true)
          : [];
        this.detail.set({ ...fresh, messages: [...fresh.messages, ...pending] });
      });
  }

  private read(id: string): void {
    this.complaints
      .markRead(id)
      .pipe(catchError(() => of(null)))
      .subscribe(() => this.changed.emit());
  }

  private reason(error: unknown, fallback: string): string {
    return apiErrorOf(error)?.message ?? this.transloco.translate<string>(fallback);
  }

  private locale(): string {
    return this.lang() === 'ar' ? 'ar' : 'en-GB';
  }
}
