/* hq-flag: chat — real-time parent ↔ teacher chat */
import { DatePipe } from '@angular/common';
import {
  AfterViewChecked,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { ChatMessageSenderEnum } from '../../api';
import { ChatService } from '../../core/chat/chat.service';
import { FeatureDirective } from '../../core/flags/feature.directive';
import { FLAGS } from '../../core/flags/flag.service';
import { PageComponent } from '../../ui';

@Component({
  selector: 'hq-chat-page',
  imports: [PageComponent, FormsModule, TranslocoPipe, DatePipe, FeatureDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-page [title]="'chat.title' | transloco" [subtitle]="'chat.subtitle' | transloco">
      <div *hqFeature="'chat'" class="chat-layout" [class.chat-layout--show-convo]="mobileShowConvo()">
        <!-- Threads Sidebar -->
        <aside class="chat-sidebar">
          <div class="chat-sidebar__search">
            <input
              type="text"
              class="chat-sidebar__search-input"
              data-hq-search
              [placeholder]="'chat.searchPlaceholder' | transloco"
              [ngModel]="searchQuery()"
              (ngModelChange)="searchQuery.set($event)"
            />
          </div>

          <div class="chat-sidebar__threads">
            @if (chatService.loadingThreads()) {
              <div class="chat-sidebar__state">{{ 'common.loading' | transloco }}</div>
            } @else if (filteredThreads().length === 0) {
              <div class="chat-sidebar__state">
                <p class="chat-sidebar__empty-title">{{ 'chat.noThreads' | transloco }}</p>
                <p class="chat-sidebar__empty-hint">{{ 'chat.noThreadsHint' | transloco }}</p>
              </div>
            } @else {
              @for (thread of filteredThreads(); track thread.childId) {
                <button
                  type="button"
                  class="thread-card"
                  [class.is-active]="chatService.activeChildId() === thread.childId"
                  (click)="onSelectThread(thread.childId)"
                >
                  <div class="thread-card__avatar">
                    {{ initialOf(thread.childName) }}
                  </div>
                  <div class="thread-card__content">
                    <div class="thread-card__top">
                      <span class="thread-card__name">{{ thread.childName }}</span>
                      @if (thread.lastMessage; as msg) {
                        <span class="thread-card__time">{{ msg.createdAt | date: 'shortTime' }}</span>
                      }
                    </div>
                    <div class="thread-card__meta">
                      @if (thread.className) {
                        <span class="thread-card__class">{{ thread.className }}</span>
                      }
                      @if (thread.subject) {
                        <span class="thread-card__subject">· {{ thread.subject }}</span>
                      }
                    </div>
                    <div class="thread-card__bottom">
                      <span class="thread-card__preview">
                        @if (thread.lastMessage; as msg) {
                          @if (msg.sender === senderTeacher) {
                            <strong class="thread-card__you">{{ 'chat.you' | transloco }}: </strong>
                          }
                          {{ msg.body }}
                        } @else {
                          <em class="thread-card__no-messages">{{ 'chat.noThreads' | transloco }}</em>
                        }
                      </span>
                      @if (thread.unread > 0) {
                        <span class="thread-card__badge">{{ thread.unread }}</span>
                      }
                    </div>
                  </div>
                </button>
              }
            }
          </div>
        </aside>

        <!-- Active Conversation Area -->
        <main class="chat-convo">
          @if (chatService.activeThread(); as active) {
            <!-- Conversation Header -->
            <header class="convo-header">
              <button
                type="button"
                class="convo-header__back"
                [attr.aria-label]="'chat.backToList' | transloco"
                (click)="mobileShowConvo.set(false)"
              >
                <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
                  <path d="M15 19l-7-7 7-7" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              </button>

              <div class="convo-header__info">
                <h2 class="convo-header__title">{{ active.childName }}</h2>
                <span class="convo-header__parent">
                  {{ 'chat.parent' | transloco: { child: active.childName } }}
                  @if (active.className) {
                    · {{ active.className }}
                  }
                </span>
              </div>

              <div class="convo-header__status">
                <span
                  class="status-pill"
                  [class.status-pill--connected]="chatService.connectionStatus() === 'connected'"
                  [class.status-pill--connecting]="chatService.connectionStatus() === 'connecting' || chatService.connectionStatus() === 'reconnecting'"
                >
                  <span class="status-pill__dot"></span>
                  @if (chatService.connectionStatus() === 'connected') {
                    {{ 'chat.live' | transloco }}
                  } @else if (chatService.connectionStatus() === 'connecting' || chatService.connectionStatus() === 'reconnecting') {
                    {{ 'chat.connecting' | transloco }}
                  } @else {
                    {{ 'chat.offline' | transloco }}
                  }
                </span>
              </div>
            </header>

            <!-- Message Stream -->
            <div #messageStream class="convo-stream">
              @if (chatService.loadingMessages()) {
                <div class="convo-stream__loading">{{ 'common.loading' | transloco }}</div>
              } @else {
                @for (msg of chatService.messages(); track msg.id) {
                  <div
                    class="message-row"
                    [class.message-row--teacher]="msg.sender === senderTeacher"
                    [class.message-row--parent]="msg.sender === senderParent"
                  >
                    <div class="message-bubble">
                      <div class="message-bubble__body">{{ msg.body }}</div>
                      <div class="message-bubble__meta">
                        <span class="message-bubble__time">{{ msg.createdAt | date: 'shortTime' }}</span>
                        @if (msg.sender === senderTeacher) {
                          @if (msg.pending) {
                            <span class="message-bubble__status">⏳</span>
                          } @else if (msg.failed) {
                            <span class="message-bubble__status message-bubble__status--failed">⚠️ {{ 'chat.failed' | transloco }}</span>
                          } @else if (msg.readAt) {
                            <span class="message-bubble__status message-bubble__status--read" [title]="'chat.read' | transloco">✓✓</span>
                          } @else {
                            <span class="message-bubble__status" [title]="'chat.sent' | transloco">✓</span>
                          }
                        }
                      </div>
                    </div>
                  </div>
                }

                @if (chatService.isParentTyping()) {
                  <div class="typing-indicator">
                    <span class="typing-indicator__dot"></span>
                    <span class="typing-indicator__dot"></span>
                    <span class="typing-indicator__dot"></span>
                    <span class="typing-indicator__text">{{ 'chat.parentTyping' | transloco }}</span>
                  </div>
                }
              }
            </div>

            <!-- Composer -->
            <footer class="convo-composer">
              <div class="convo-composer__box">
                <textarea
                  #composerInput
                  class="convo-composer__input"
                  rows="2"
                  maxlength="2000"
                  [placeholder]="'chat.writeMessage' | transloco"
                  [ngModel]="draftMessage()"
                  (ngModelChange)="draftMessage.set($event); onInput()"
                  (keydown.enter)="onEnterKey($event)"
                ></textarea>
                <div class="convo-composer__bottom">
                  <span class="convo-composer__count" [class.is-limit]="draftMessage().length > 1900">
                    {{ 'chat.charCount' | transloco: { count: draftMessage().length } }}
                  </span>
                  <button
                    type="button"
                    class="convo-composer__send"
                    [disabled]="!canSend()"
                    (click)="onSendMessage()"
                  >
                    {{ 'chat.send' | transloco }}
                  </button>
                </div>
              </div>
            </footer>
          } @else {
            <div class="convo-empty">
              <div class="convo-empty__card">
                <svg class="convo-empty__icon" viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 0 1-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8Z" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
                <h3 class="convo-empty__title">{{ 'chat.selectThread' | transloco }}</h3>
                <p class="convo-empty__hint">{{ 'chat.selectThreadHint' | transloco }}</p>
              </div>
            </div>
          }
        </main>
      </div>
    </hq-page>
  `,
  styles: `
    :host {
      display: block;
    }

    .chat-layout {
      display: grid;
      grid-template-columns: 320px 1fr;
      min-height: calc(100vh - 220px);
      height: 720px;
      max-height: calc(100vh - 180px);
      background: var(--hq-color-surface-raised, #fff);
      border: 1px solid var(--hq-color-border-subtle, #e5e7eb);
      border-radius: var(--hq-radius-card, 16px);
      overflow: hidden;
      box-shadow: 0 4px 20px -2px rgba(0, 0, 0, 0.05);
    }

    .chat-sidebar {
      display: flex;
      flex-direction: column;
      border-inline-end: 1px solid var(--hq-color-border-subtle, #e5e7eb);
      background: var(--hq-color-surface-subtle, #f9fafb);
      min-width: 0;
    }

    .chat-sidebar__search {
      padding: 12px 16px;
      border-bottom: 1px solid var(--hq-color-border-subtle, #e5e7eb);
    }

    .chat-sidebar__search-input {
      width: 100%;
      padding: 8px 12px;
      font-size: 14px;
      border: 1px solid var(--hq-color-border-soft, #d1d5db);
      border-radius: var(--hq-radius-input, 8px);
      background: var(--hq-color-surface-raised, #fff);
      color: var(--hq-color-ink, #111827);
      outline: none;
      &:focus {
        border-color: var(--hq-color-primary, #4f46e5);
        box-shadow: 0 0 0 3px rgba(79, 70, 229, 0.12);
      }
    }

    .chat-sidebar__threads {
      flex: 1;
      overflow-y: auto;
      padding: 8px;
    }

    .chat-sidebar__state {
      padding: 32px 16px;
      text-align: center;
      color: var(--hq-color-ink-soft, #6b7280);
    }

    .chat-sidebar__empty-title {
      font-weight: 600;
      color: var(--hq-color-ink, #111827);
      margin-bottom: 4px;
    }

    .chat-sidebar__empty-hint {
      font-size: 12px;
      line-height: 1.4;
    }

    .thread-card {
      display: flex;
      align-items: center;
      gap: 12px;
      width: 100%;
      padding: 10px 12px;
      margin-bottom: 4px;
      border: 1px solid transparent;
      border-radius: var(--hq-radius-button, 10px);
      background: transparent;
      text-align: start;
      cursor: pointer;
      &:hover { background: var(--hq-color-surface-hover, #f3f4f6); }
      &.is-active {
        background: var(--hq-color-surface-raised, #fff);
        border-color: var(--hq-color-primary, #4f46e5);
        box-shadow: 0 2px 8px -1px rgba(79, 70, 229, 0.1);
      }
    }

    .thread-card__avatar {
      flex-shrink: 0;
      width: 40px;
      height: 40px;
      border-radius: 50%;
      background: linear-gradient(135deg, #6366f1, #4f46e5);
      color: #fff;
      display: grid;
      place-items: center;
      font-weight: 700;
      font-size: 15px;
    }

    .thread-card__content { flex: 1; min-width: 0; }

    .thread-card__top {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: 8px;
    }

    .thread-card__name {
      font-weight: 600;
      font-size: 14px;
      color: var(--hq-color-ink, #111827);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .thread-card__time {
      font-size: 11px;
      color: var(--hq-color-ink-faint, #9ca3af);
      flex-shrink: 0;
    }

    .thread-card__meta {
      font-size: 11px;
      color: var(--hq-color-ink-soft, #6b7280);
      margin-bottom: 2px;
    }

    .thread-card__bottom {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
    }

    .thread-card__preview {
      font-size: 12px;
      color: var(--hq-color-ink-soft, #6b7280);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
      flex: 1;
    }

    .thread-card__you { color: var(--hq-color-ink, #111827); }
    .thread-card__no-messages { font-style: italic; color: var(--hq-color-ink-faint, #9ca3af); }

    .thread-card__badge {
      flex-shrink: 0;
      min-width: 18px;
      height: 18px;
      padding: 0 5px;
      border-radius: 9px;
      background: var(--hq-color-accent, #ef4444);
      color: #fff;
      font-size: 10px;
      font-weight: 700;
      display: grid;
      place-items: center;
    }

    .chat-convo {
      display: flex;
      flex-direction: column;
      height: 100%;
      min-width: 0;
      background: var(--hq-color-surface-raised, #fff);
    }

    .convo-header {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 12px 20px;
      border-bottom: 1px solid var(--hq-color-border-subtle, #e5e7eb);
      background: var(--hq-color-surface-raised, #fff);
      flex-shrink: 0;
    }

    .convo-header__back {
      display: none;
      background: none;
      border: none;
      cursor: pointer;
      color: var(--hq-color-ink, #111827);
      padding: 4px;
      svg { width: 22px; height: 22px; }
    }

    .convo-header__info { flex: 1; min-width: 0; }

    .convo-header__title {
      font-size: 16px;
      font-weight: 700;
      color: var(--hq-color-ink, #111827);
      margin: 0;
      line-height: 1.3;
    }

    .convo-header__parent {
      font-size: 12px;
      color: var(--hq-color-ink-soft, #6b7280);
    }

    .status-pill {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 4px 10px;
      border-radius: 9999px;
      font-size: 12px;
      font-weight: 500;
      background: #f3f4f6;
      color: #6b7280;
    }

    .status-pill__dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      background: #9ca3af;
    }

    .status-pill--connected {
      background: #ecfdf5;
      color: #065f46;
      .status-pill__dot {
        background: #10b981;
        box-shadow: 0 0 0 2px rgba(16, 185, 129, 0.2);
      }
    }

    .status-pill--connecting {
      background: #fffbeb;
      color: #92400e;
      .status-pill__dot { background: #f59e0b; }
    }

    .convo-stream {
      flex: 1;
      overflow-y: auto;
      padding: 16px 20px;
      display: flex;
      flex-direction: column;
      gap: 12px;
      background: #fafbfc;
    }

    .convo-stream__loading {
      text-align: center;
      padding: 24px;
      color: var(--hq-color-ink-soft, #6b7280);
    }

    .message-row {
      display: flex;
      width: 100%;
      &--teacher {
        justify-content: flex-end;
        .message-bubble {
          background: var(--hq-color-primary, #4f46e5);
          color: #fff;
          border-bottom-right-radius: 2px;
          .message-bubble__time { color: rgba(255, 255, 255, 0.75); }
          .message-bubble__status { color: rgba(255, 255, 255, 0.85); }
          .message-bubble__status--read { color: #a7f3d0; font-weight: 700; }
          .message-bubble__status--failed { color: #fecaca; }
        }
      }
      &--parent {
        justify-content: flex-start;
        .message-bubble {
          background: #fff;
          color: var(--hq-color-ink, #111827);
          border: 1px solid var(--hq-color-border-subtle, #e5e7eb);
          border-bottom-left-radius: 2px;
          box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
          .message-bubble__time { color: var(--hq-color-ink-faint, #9ca3af); }
        }
      }
    }

    .message-bubble {
      max-width: 70%;
      padding: 10px 14px;
      border-radius: 14px;
      word-break: break-word;
      font-size: 14px;
      line-height: 1.45;
    }

    .message-bubble__body { white-space: pre-wrap; }

    .message-bubble__meta {
      display: flex;
      align-items: center;
      justify-content: flex-end;
      gap: 6px;
      margin-top: 4px;
      font-size: 11px;
    }

    .typing-indicator {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      padding: 6px 12px;
      background: #fff;
      border: 1px solid var(--hq-color-border-subtle, #e5e7eb);
      border-radius: 16px;
      width: fit-content;
    }

    .typing-indicator__dot {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: #9ca3af;
      animation: typingPulse 1.2s infinite ease-in-out;
      &:nth-child(2) { animation-delay: 0.2s; }
      &:nth-child(3) { animation-delay: 0.4s; }
    }

    .typing-indicator__text {
      margin-inline-start: 6px;
      font-size: 11px;
      color: var(--hq-color-ink-soft, #6b7280);
    }

    @keyframes typingPulse {
      0%, 60%, 100% { transform: translateY(0); opacity: 0.4; }
      30% { transform: translateY(-4px); opacity: 1; }
    }

    .convo-composer {
      padding: 12px 16px;
      border-top: 1px solid var(--hq-color-border-subtle, #e5e7eb);
      background: var(--hq-color-surface-raised, #fff);
      flex-shrink: 0;
    }

    .convo-composer__box {
      display: flex;
      flex-direction: column;
      border: 1px solid var(--hq-color-border-soft, #d1d5db);
      border-radius: var(--hq-radius-input, 10px);
      padding: 8px 12px;
      background: #fff;
      &:focus-within {
        border-color: var(--hq-color-primary, #4f46e5);
        box-shadow: 0 0 0 3px rgba(79, 70, 229, 0.12);
      }
    }

    .convo-composer__input {
      width: 100%;
      border: none;
      outline: none;
      resize: none;
      font-family: inherit;
      font-size: 14px;
      line-height: 1.4;
      color: var(--hq-color-ink, #111827);
    }

    .convo-composer__bottom {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-top: 6px;
    }

    .convo-composer__count {
      font-size: 11px;
      color: var(--hq-color-ink-faint, #9ca3af);
      &.is-limit { color: var(--hq-color-accent, #ef4444); font-weight: 600; }
    }

    .convo-composer__send {
      padding: 6px 16px;
      background: var(--hq-color-primary, #4f46e5);
      color: #fff;
      border: none;
      border-radius: var(--hq-radius-button, 8px);
      font-size: 13px;
      font-weight: 600;
      cursor: pointer;
      &:hover:not(:disabled) { background: #4338ca; }
      &:disabled { opacity: 0.5; cursor: not-allowed; }
    }

    .convo-empty {
      display: grid;
      place-items: center;
      height: 100%;
      padding: 32px;
    }

    .convo-empty__card {
      max-width: 380px;
      text-align: center;
      color: var(--hq-color-ink-soft, #6b7280);
    }

    .convo-empty__icon {
      width: 56px;
      height: 56px;
      margin: 0 auto 16px;
      color: var(--hq-color-ink-faint, #9ca3af);
    }

    .convo-empty__title {
      font-size: 18px;
      font-weight: 700;
      color: var(--hq-color-ink, #111827);
      margin: 0 0 6px;
    }

    .convo-empty__hint { font-size: 14px; line-height: 1.5; margin: 0; }

    @media (max-width: 768px) {
      .chat-layout { grid-template-columns: 1fr; height: calc(100vh - 160px); }
      .chat-sidebar { display: flex; }
      .chat-convo { display: none; }
      .chat-layout--show-convo {
        .chat-sidebar { display: none; }
        .chat-convo { display: flex; }
        .convo-header__back { display: block; }
      }
    }
  `,
})
export class ChatPage implements AfterViewChecked {
  protected readonly chatService = inject(ChatService);
  private readonly route = inject(ActivatedRoute);
  private readonly transloco = inject(TranslocoService);

  readonly flag = FLAGS.chat;
  readonly senderTeacher = ChatMessageSenderEnum.TEACHER;
  readonly senderParent = ChatMessageSenderEnum.PARENT;

  readonly searchQuery = signal<string>('');
  readonly draftMessage = signal<string>('');
  readonly mobileShowConvo = signal<boolean>(false);

  private readonly streamRef = viewChild<ElementRef<HTMLElement>>('messageStream');
  private shouldScrollToBottom = false;

  readonly filteredThreads = computed(() => {
    const q = this.searchQuery().trim().toLowerCase();
    const list = this.chatService.threads();
    if (!q) return list;
    return list.filter(
      (t) =>
        t.childName.toLowerCase().includes(q) ||
        (t.className && t.className.toLowerCase().includes(q)) ||
        (t.subject && t.subject.toLowerCase().includes(q)),
    );
  });

  readonly canSend = computed(() => {
    const body = this.draftMessage().trim();
    return body.length > 0 && body.length <= 2000;
  });

  constructor() {
    // Check query params for childId
    effect(() => {
      const childId = this.route.snapshot.queryParamMap.get('childId');
      if (childId) {
        this.chatService.selectThread(childId);
        this.mobileShowConvo.set(true);
      }
    });

    // Auto-scroll when messages array changes
    effect(() => {
      this.chatService.messages();
      this.shouldScrollToBottom = true;
    });
  }

  ngAfterViewChecked(): void {
    if (this.shouldScrollToBottom) {
      this.scrollToBottom();
      this.shouldScrollToBottom = false;
    }
  }

  onSelectThread(childId: string): void {
    this.chatService.selectThread(childId);
    this.mobileShowConvo.set(true);
    this.shouldScrollToBottom = true;
  }

  onInput(): void {
    this.chatService.sendTyping();
  }

  onEnterKey(event: Event): void {
    const keyboardEvent = event as KeyboardEvent;
    if (!keyboardEvent.shiftKey) {
      keyboardEvent.preventDefault();
      this.onSendMessage();
    }
  }

  onSendMessage(): void {
    if (!this.canSend()) return;
    const body = this.draftMessage();
    this.draftMessage.set('');
    this.chatService.sendMessage(body);
    this.shouldScrollToBottom = true;
  }

  initialOf(name: string): string {
    const trimmed = name.trim();
    return trimmed ? trimmed.charAt(0).toUpperCase() : '?';
  }

  private scrollToBottom(): void {
    const el = this.streamRef()?.nativeElement;
    if (el) {
      el.scrollTop = el.scrollHeight;
    }
  }
}
