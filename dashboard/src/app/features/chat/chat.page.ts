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
  untracked,
  viewChild,
} from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { catchError, of, tap } from 'rxjs';
import {
  ChatApi,
  ChatMessageSenderEnum,
  ChatThread,
  ChatThreadTopicEnum,
  ManagementApi,
  ManagementChatApi,
  ManagersApi,
} from '../../api';
import { AuthService } from '../../core/auth/auth.service';
import { ChatAttachment, ChatAttachmentStore } from '../../core/chat/chat-attachment.store';
import { ChatService } from '../../core/chat/chat.service';
import { FeatureDirective } from '../../core/flags/feature.directive';
import { FLAGS } from '../../core/flags/flag.service';
import {
  ButtonComponent,
  DialogComponent,
  PageComponent,
  type SelectOptionGroup,
  SelectComponent,
  type Tab,
  TabsComponent,
} from '../../ui';

/** Which of the manager's three kinds of correspondent a thread is with (RM3b). */
/**
 * Which correspondent a thread is with — one chip each, on the two inboxes that have more than one
 * kind of them.
 *
 * MG2b added `teachers` (the manager's new threads with her department's teachers) and
 * `management` (the teacher's own end of them, plus the coordinator's manager thread).
 */
type Peer = 'parents' | 'coordinators' | 'admin' | 'teachers' | 'management';

interface EmojiCategory {
  id: 'smileys' | 'education' | 'fun';
  icon: string;
  name: string;
  emojis: readonly string[];
}

const EMOJI_CATEGORIES: readonly EmojiCategory[] = [
  {
    id: 'smileys',
    icon: '😊',
    name: 'Smileys',
    emojis: [
      '😀',
      '😃',
      '😄',
      '😁',
      '😆',
      '😅',
      '😂',
      '🤣',
      '😊',
      '😇',
      '🙂',
      '😉',
      '😌',
      '😍',
      '🥰',
      '😘',
      '😋',
      '😛',
      '😜',
      '🤔',
      '🤫',
      '🤗',
      '🥳',
      '😎',
      '👍',
      '👏',
      '🙌',
      '🤝',
      '💖',
      '❤️',
      '⭐',
      '✨',
    ],
  },
  {
    id: 'education',
    icon: '📚',
    name: 'School',
    emojis: [
      '📚',
      '📖',
      '✏️',
      '📝',
      '🎨',
      '🎒',
      '🏫',
      '🔬',
      '📐',
      '📏',
      '📎',
      '📌',
      '🏆',
      '🥇',
      '🥈',
      '🥉',
      '🎯',
      '💯',
      '🧠',
      '💡',
      '⏰',
      '📅',
      '🔔',
      '📣',
      '✅',
      '❌',
      '❓',
      '❗',
      '🎓',
      '💻',
      '🏅',
      '🌟',
    ],
  },
  {
    id: 'fun',
    icon: '🎉',
    name: 'Fun',
    emojis: [
      '🍎',
      '🍌',
      '🍕',
      '🍰',
      '🍪',
      '⚽',
      '🏀',
      '🎮',
      '🚗',
      '🚀',
      '🌈',
      '☀️',
      '🌸',
      '🐱',
      '🐶',
      '🦁',
      '🎉',
      '🎈',
      '🎁',
      '💪',
      '🙏',
      '🙋',
      '👨‍🏫',
      '👩‍🏫',
      '👦',
      '👧',
      '💬',
      '💭',
      '🪄',
      '🔥',
      '👋',
      '🥳',
    ],
  },
];

interface ParsedChatMessage {
  text: string;
  attachment?: {
    id?: string;
    name: string;
    size?: string;
    type: 'image' | 'pdf';
    dataUrl: string;
  };
}

@Component({
  selector: 'hq-chat-page',
  imports: [
    ButtonComponent,
    DatePipe,
    DialogComponent,
    FeatureDirective,
    FormsModule,
    PageComponent,
    SelectComponent,
    TabsComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-page [title]="title() | transloco" [subtitle]="subtitle() | transloco">
      <div *hqFeature="'chat'" class="chat-layout" [class.chat-layout--show-convo]="mobileShowConvo()">
        <!-- Threads Sidebar -->
        <aside class="chat-sidebar">
          <div class="chat-sidebar__search">
            <div class="search-box">
              <svg class="search-box__icon" viewBox="0 0 20 20" fill="currentColor" width="16" height="16">
                <path
                  fill-rule="evenodd"
                  d="M9 3.5a5.5 5.5 0 100 11 5.5 5.5 0 000-11zM2 9a7 7 0 1112.452 4.391l3.328 3.329a.75.75 0 11-1.06 1.06l-3.329-3.328A7 7 0 012 9z"
                  clip-rule="evenodd"
                />
              </svg>
              <input
                type="text"
                class="search-box__input"
                data-hq-search
                [placeholder]="'chat.searchPlaceholder' | transloco"
                [ngModel]="searchQuery()"
                (ngModelChange)="searchQuery.set($event)"
              />
              @if (searchQuery().length > 0) {
                <button
                  type="button"
                  class="search-box__clear"
                  (click)="searchQuery.set('')"
                  aria-label="Clear search"
                >
                  ✕
                </button>
              }
            </div>
            <!-- RM3b: the two roles whose inbox they can start a thread in. A teacher and a
                 coordinator reach theirs from a roster row and from Complaints instead, so the
                 button is not "hidden" for them — there is nothing here for it to open. -->
            @if (canStartThread()) {
              <hq-button variant="secondary" (pressed)="startingThread.set(true)">
                {{ 'chat.newMessage' | transloco }}
              </hq-button>
            }
          </div>

          <!-- D2: the conversation she had open has left her list. One line, where the list is,
               instead of a screen that silently says "pick a conversation". -->
          @if (missingThread()) {
            <p class="chat-sidebar__missing">{{ 'chat.threadGone' | transloco }}</p>
          }

          @if (showsPeerTabs()) {
            <div class="chat-sidebar__tabs">
              <hq-tabs
                variant="chips"
                [tabs]="peerTabs()"
                [(selected)]="peer"
                [label]="'chat.peers' | transloco"
              />
            </div>
          }

          <div class="chat-sidebar__threads">
            @if (chatService.loadingThreads()) {
              <div class="chat-sidebar__state">{{ 'common.loading' | transloco }}</div>
            } @else if (visibleThreads().length === 0) {
              <div class="chat-sidebar__state">
                <p class="chat-sidebar__empty-title">{{ emptyTitle | transloco }}</p>
                <p class="chat-sidebar__empty-hint">{{ emptyHint() | transloco }}</p>
              </div>
            } @else {
              @for (thread of visibleThreads(); track keyOf(thread)) {
                <button
                  type="button"
                  class="thread-card"
                  [class.is-active]="chatService.activeKey() === keyOf(thread)"
                  (click)="onSelectThread(keyOf(thread))"
                >
                  <div class="thread-card__avatar">
                    {{ initialOf(nameOf(thread)) }}
                  </div>
                  <div class="thread-card__content">
                    <div class="thread-card__top">
                      <span class="thread-card__name">{{ nameOf(thread) }}</span>
                      @if (thread.lastMessage; as msg) {
                        <span class="thread-card__time">{{ msg.createdAt | date: 'shortTime' }}</span>
                      }
                    </div>
                    <div class="thread-card__meta">
                      @if (isStaff(thread)) {
                        <!-- R7: a coordinator ↔ manager thread has no child on it at all, so the
                             row says who the other end is instead of pretending to a class. -->
                        <span class="thread-card__badge thread-card__badge--staff">
                          {{ 'chat.peer.' + peerOf(thread) | transloco }}
                        </span>
                      }
                      @if (thread.topic === topicComplaint) {
                        <span class="thread-card__badge thread-card__badge--complaint">
                          {{ 'chat.complaint' | transloco }}
                        </span>
                      }
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
                          {{ formatPreview(msg.body) }}
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
                  <path
                    d="M15 19l-7-7 7-7"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="2"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                  />
                </svg>
              </button>

              <div class="convo-header__avatar">
                {{ initialOf(nameOf(active)) }}
              </div>

              <div class="convo-header__info">
                <h2 class="convo-header__title">{{ nameOf(active) }}</h2>
                <span class="convo-header__parent">
                  @if (isStaff(active)) {
                    {{ 'chat.peer.' + peerOf(active) | transloco }}
                  } @else {
                    {{ 'chat.parent' | transloco: { child: active.childName } }}
                    @if (active.className) {
                      · {{ active.className }}
                    }
                  }
                </span>
              </div>

              <!-- T2 item (d): the peer's presence, from the server. See peerState() below. -->
              <div class="convo-header__status">
                @if (peerState(); as state) {
                  <span
                    class="status-pill"
                    [class.status-pill--connected]="state === 'online'"
                    [class.status-pill--connecting]="state === 'connecting'"
                  >
                    <span class="status-pill__dot"></span>
                    @switch (state) {
                      @case ('online') {
                        {{ 'chat.live' | transloco }}
                      }
                      @case ('connecting') {
                        {{ 'chat.connecting' | transloco }}
                      }
                      @case ('away') {
                        {{ 'chat.away' | transloco }}
                      }
                      @default {
                        {{ 'chat.offline' | transloco }}
                      }
                    }
                  </span>
                }
              </div>
            </header>

            <!-- Message Stream -->
            <div #messageStream class="convo-stream">
              @if (chatService.loadingMessages()) {
                <div class="convo-stream__loading">{{ 'common.loading' | transloco }}</div>
              } @else {
                @for (msg of chatService.messages(); track msg.id) {
                  @let parsed = parseMessage(msg.body);
                  @let mineMsg = isMine(msg);
                  <div
                    class="message-row"
                    [class.message-row--teacher]="mineMsg"
                    [class.message-row--parent]="!mineMsg"
                  >
                    <div class="message-bubble">
                      <!-- Render Attachment if present -->
                      @if (parsed.attachment; as att) {
                        @if (att.type === 'image') {
                          <div
                            class="message-attachment message-attachment--image"
                            role="button"
                            tabindex="0"
                            (click)="openImagePreview(att.name, att.dataUrl)"
                            (keydown.enter)="openImagePreview(att.name, att.dataUrl)"
                          >
                            @if (att.dataUrl) {
                              <img [src]="att.dataUrl" [alt]="att.name" class="message-attachment__img" />
                            } @else {
                              <div class="message-attachment__placeholder">
                                <span>🖼️ {{ att.name }}</span>
                              </div>
                            }
                            <div class="message-attachment__overlay">
                              <svg viewBox="0 0 20 20" fill="currentColor" width="16" height="16">
                                <path d="M10 12.5a2.5 2.5 0 100-5 2.5 2.5 0 000 5z" />
                                <path
                                  fill-rule="evenodd"
                                  d="M.664 10.59a1.651 1.651 0 010-1.186A10.004 10.004 0 0110 3c4.257 0 7.893 2.66 9.336 6.41.147.381.146.804 0 1.186A10.004 10.004 0 0110 17c-4.257 0-7.893-2.66-9.336-6.41zM14 10a4 4 0 11-8 0 4 4 0 018 0z"
                                  clip-rule="evenodd"
                                />
                              </svg>
                              <span>{{ 'chat.viewImage' | transloco }}</span>
                            </div>
                          </div>
                        } @else {
                          <div class="message-attachment message-attachment--pdf">
                            <div class="message-attachment__pdf-icon">
                              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                                <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                                <polyline points="14 2 14 8 20 8" />
                                <line x1="16" y1="13" x2="8" y2="13" />
                                <line x1="16" y1="17" x2="8" y2="17" />
                                <polyline points="10 9 9 9 8 9" />
                              </svg>
                            </div>
                            <div class="message-attachment__pdf-info">
                              <div class="message-attachment__pdf-name" [title]="att.name">
                                {{ att.name }}
                              </div>
                              <div class="message-attachment__pdf-size">
                                {{ att.size || ('chat.pdfDocument' | transloco) }}
                              </div>
                            </div>
                            <button
                              type="button"
                              class="message-attachment__pdf-btn"
                              [title]="'chat.downloadFile' | transloco"
                              (click)="downloadAttachment(att.name, att.dataUrl)"
                            >
                              <svg viewBox="0 0 20 20" fill="currentColor" width="16" height="16">
                                <path
                                  fill-rule="evenodd"
                                  d="M3 17a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zm3.293-7.707a1 1 0 011.414 0L9 10.586V3a1 1 0 112 0v7.586l1.293-1.293a1 1 0 111.414 1.414l-3 3a1 1 0 01-1.414 0l-3-3a1 1 0 010-1.414z"
                                  clip-rule="evenodd"
                                />
                              </svg>
                            </button>
                          </div>
                        }
                      }

                      @if (parsed.text) {
                        <div class="message-bubble__body">{{ parsed.text }}</div>
                      }

                      <div class="message-bubble__meta">
                        <span class="message-bubble__time">{{ msg.createdAt | date: 'shortTime' }}</span>
                        @if (mineMsg) {
                          @if (msg.pending) {
                            <span class="message-bubble__status">⏳</span>
                          } @else if (msg.failed) {
                            <span class="message-bubble__status message-bubble__status--failed"
                              >⚠️ {{ 'chat.failed' | transloco }}</span
                            >
                          } @else if (msg.readAt) {
                            <span
                              class="message-bubble__status message-bubble__status--read"
                              [title]="'chat.read' | transloco"
                              >✓✓</span
                            >
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

            <!-- Composer Area with Attachment & Emoji Pickers. Absent, not disabled, for the
                 role the socket refuses a message command from: she has nothing to send with. -->
            @if (!chatService.canWrite()) {
              <footer class="convo-readonly">{{ 'chat.readOnlyPeer' | transloco }}</footer>
            } @else {
              <footer class="convo-composer">
                <!-- Emoji Picker Popup -->
                @if (showEmojiPicker()) {
                  <div class="emoji-picker">
                    <div class="emoji-picker__header">
                      <div class="emoji-picker__categories">
                        @for (cat of emojiCategories; track cat.id) {
                          <button
                            type="button"
                            class="emoji-picker__cat-btn"
                            [class.is-active]="activeEmojiCategory() === cat.id"
                            (click)="activeEmojiCategory.set(cat.id)"
                          >
                            <span class="emoji-picker__cat-icon">{{ cat.icon }}</span>
                            <span class="emoji-picker__cat-name">{{ cat.name }}</span>
                          </button>
                        }
                      </div>
                      <button
                        type="button"
                        class="emoji-picker__close"
                        (click)="showEmojiPicker.set(false)"
                        [title]="'chat.close' | transloco"
                      >
                        ✕
                      </button>
                    </div>

                    <div class="emoji-picker__search">
                      <input
                        type="text"
                        class="emoji-picker__search-input"
                        placeholder="Search emojis..."
                        [ngModel]="emojiSearch()"
                        (ngModelChange)="emojiSearch.set($event)"
                      />
                    </div>

                    <div class="emoji-picker__grid">
                      @for (emoji of filteredEmojis(); track emoji) {
                        <button type="button" class="emoji-btn" (click)="onSelectEmoji(emoji)">
                          {{ emoji }}
                        </button>
                      }
                    </div>
                  </div>
                }

                <div class="convo-composer__box">
                  <!-- Attached File Preview Chip -->
                  @if (attachedFile(); as att) {
                    <div class="composer-attachment-bar">
                      <div class="attachment-chip">
                        @if (att.type === 'image') {
                          <img [src]="att.dataUrl" alt="Thumbnail" class="attachment-chip__thumb" />
                        } @else {
                          <div class="attachment-chip__pdf-icon">
                            <svg
                              viewBox="0 0 24 24"
                              fill="none"
                              stroke="currentColor"
                              stroke-width="2"
                              width="16"
                              height="16"
                            >
                              <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                              <polyline points="14 2 14 8 20 8" />
                            </svg>
                          </div>
                        }
                        <div class="attachment-chip__details">
                          <span class="attachment-chip__name" [title]="att.name">{{ att.name }}</span>
                          <span class="attachment-chip__size">{{ att.size }}</span>
                        </div>
                      </div>
                      <button
                        type="button"
                        class="attachment-chip__remove"
                        (click)="removeAttachment()"
                        [title]="'chat.removeAttachment' | transloco"
                      >
                        ✕
                      </button>
                    </div>
                  }

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
                    <div class="convo-composer__tools">
                      <button
                        type="button"
                        class="composer-tool-btn"
                        (click)="fileInput.click()"
                        [title]="'chat.attachFile' | transloco"
                      >
                        <svg viewBox="0 0 20 20" fill="currentColor" width="18" height="18">
                          <path
                            fill-rule="evenodd"
                            d="M15.621 4.379a3 3 0 00-4.242 0l-7 7a3 3 0 004.241 4.243h.001l.497-.5a.75.75 0 011.064 1.057l-.498.501-.002.002a4.5 4.5 0 01-6.364-6.364l7-7a4.5 4.5 0 016.368 6.36l-3.455 3.553A2.625 2.625 0 119.5 9.525l3.45-3.451a.75.75 0 111.061 1.06l-3.45 3.451a1.125 1.125 0 001.587 1.595l3.454-3.553a3 3 0 000-4.248z"
                            clip-rule="evenodd"
                          />
                        </svg>
                      </button>
                      <input
                        #fileInput
                        type="file"
                        accept="image/*,application/pdf"
                        style="display: none"
                        (change)="onFileSelected($event)"
                      />

                      <button
                        type="button"
                        class="composer-tool-btn"
                        [class.is-active]="showEmojiPicker()"
                        (click)="toggleEmojiPicker($event)"
                        [title]="'chat.addEmoji' | transloco"
                      >
                        <svg viewBox="0 0 20 20" fill="currentColor" width="18" height="18">
                          <path
                            fill-rule="evenodd"
                            d="M10 18a8 8 0 100-16 8 8 0 000 16zM7 9a1 1 0 100-2 1 1 0 000 2zm7-1a1 1 0 11-2 0 1 1 0 012 0zm-.464 5.535a.75.75 0 10-1.06-1.06 3.5 3.5 0 01-4.952 0 .75.75 0 00-1.06 1.06 5 5 0 007.072 0z"
                            clip-rule="evenodd"
                          />
                        </svg>
                      </button>
                    </div>

                    <div class="convo-composer__actions">
                      <span class="convo-composer__count" [class.is-limit]="draftMessage().length > 1900">
                        {{ 'chat.charCount' | transloco: { count: draftMessage().length } }}
                      </span>
                      <button
                        type="button"
                        class="convo-composer__send"
                        [disabled]="!canSend()"
                        (click)="onSendMessage()"
                      >
                        <span>{{ 'chat.send' | transloco }}</span>
                        <svg viewBox="0 0 20 20" fill="currentColor" width="14" height="14">
                          <path
                            d="M10.894 2.553a1 1 0 00-1.788 0l-7 14a1 1 0 001.169 1.409l5-1.429A1 1 0 009 15.571V11a1 1 0 112 0v4.571a1 1 0 00.725.962l5 1.428a1 1 0 001.17-1.408l-7-14z"
                          />
                        </svg>
                      </button>
                    </div>
                  </div>
                </div>
              </footer>
            }
          } @else {
            <div class="convo-empty">
              <div class="convo-empty__card">
                <svg class="convo-empty__icon" viewBox="0 0 24 24" aria-hidden="true">
                  <path
                    d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 0 1-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8Z"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="1.8"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                  />
                </svg>
                <h3 class="convo-empty__title">{{ 'chat.selectThread' | transloco }}</h3>
                <p class="convo-empty__hint">{{ 'chat.selectThreadHint' | transloco }}</p>
              </div>
            </div>
          }
        </main>
      </div>

      <!-- Lightbox Modal for Full Image View -->
      @if (activeImageModal(); as modal) {
        <div
          role="dialog"
          aria-modal="true"
          tabindex="0"
          class="image-modal-backdrop"
          (click)="onBackdropClick($event)"
          (keydown.escape)="closeImagePreview()"
        >
          <div class="image-modal-card">
            <div class="image-modal-bar">
              <span class="image-modal-title" [title]="modal.name">{{ modal.name }}</span>
              <div class="image-modal-actions">
                <button
                  type="button"
                  class="image-modal-btn"
                  (click)="downloadAttachment(modal.name, modal.url)"
                  [title]="'chat.downloadFile' | transloco"
                >
                  <svg viewBox="0 0 20 20" fill="currentColor" width="16" height="16">
                    <path
                      fill-rule="evenodd"
                      d="M3 17a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zm3.293-7.707a1 1 0 011.414 0L9 10.586V3a1 1 0 112 0v7.586l1.293-1.293a1 1 0 111.414 1.414l-3 3a1 1 0 01-1.414 0l-3-3a1 1 0 010-1.414z"
                      clip-rule="evenodd"
                    />
                  </svg>
                  <span>{{ 'chat.downloadFile' | transloco }}</span>
                </button>
                <button
                  type="button"
                  class="image-modal-btn image-modal-btn--close"
                  (click)="closeImagePreview()"
                  [title]="'chat.close' | transloco"
                >
                  ✕
                </button>
              </div>
            </div>
            <div class="image-modal-content">
              <img [src]="modal.url" [alt]="modal.name" class="image-modal-img" />
            </div>
          </div>
        </div>
      }

      @if (canStartThread()) {
        <!-- RM3b: one thread per pair however many times either side asks for it, so this is
             "open the conversation", not "create" — and picking somebody she is already talking
             to lands on the row she already has. -->
        <hq-dialog
          [sheet]="true"
          [(open)]="startingThread"
          [title]="'chat.newMessage' | transloco"
          [confirmLabel]="'chat.openConversation' | transloco"
          [cancelLabel]="'ui.cancel' | transloco"
          [confirmDisabled]="chosenPerson() === ''"
          [loading]="startingThreadBusy()"
          (confirmed)="openThreadWith()"
        >
          <hq-select
            [label]="'chat.pickPerson' | transloco"
            [placeholder]="'chat.pickPersonHint' | transloco"
            [required]="true"
            [groups]="peopleGroups()"
            [value]="chosenPerson()"
            (valueChange)="chosenPerson.set($event)"
          />
        </hq-dialog>
      }
    </hq-page>
  `,
  styles: `
    :host {
      display: block;
      --hq-page-max-width: 100%;
      --hq-page-padding: 0 24px 20px;
    }

    .chat-layout {
      display: grid;
      grid-template-columns: 360px 1fr;
      height: calc(100vh - 140px);
      min-height: 520px;
      background: var(--hq-color-surface, #ffffff);
      border: 1px solid var(--hq-color-rule, #e2e8f0);
      border-radius: 16px;
      overflow: hidden;
      box-shadow: 0 4px 20px -2px rgba(0, 0, 0, 0.05);
    }

    .chat-sidebar {
      display: flex;
      flex-direction: column;
      border-inline-end: 1px solid var(--hq-color-rule, #e2e8f0);
      background: var(--hq-color-surface-sunken, #f8fafc);
      min-width: 0;
    }

    .chat-sidebar__search {
      display: grid;
      gap: var(--hq-space-2);
      padding: 12px 16px;
      border-bottom: 1px solid var(--hq-color-rule, #e2e8f0);
    }

    .chat-sidebar__tabs {
      padding: 12px 16px 0;
    }

    .search-box {
      position: relative;
      display: flex;
      align-items: center;
      width: 100%;
    }

    .search-box__icon {
      position: absolute;
      left: 12px;
      color: var(--hq-color-ink-faint, #94a3b8);
      pointer-events: none;
    }

    .search-box__input {
      width: 100%;
      padding: 9px 34px 9px 34px;
      font-size: 13.5px;
      border: 1px solid var(--hq-color-rule, #e2e8f0);
      border-radius: 12px;
      background: var(--hq-color-surface, #ffffff);
      color: var(--hq-color-ink, #0f172a);
      outline: none;
      transition: all 0.2s ease;

      &:focus {
        border-color: var(--hq-color-accent, var(--hq-color-brand-600));
        box-shadow: 0 0 0 3px color-mix(in srgb, var(--hq-color-brand-600) 12%, transparent);
      }
    }

    .search-box__clear {
      position: absolute;
      right: 10px;
      background: none;
      border: none;
      color: var(--hq-color-ink-faint, #94a3b8);
      cursor: pointer;
      font-size: 13px;
      padding: 2px 6px;
      border-radius: 6px;

      &:hover {
        background: rgba(0, 0, 0, 0.06);
        color: var(--hq-color-ink, #0f172a);
      }
    }

    .chat-sidebar__threads {
      flex: 1;
      overflow-y: auto;
      padding: 8px;
      display: flex;
      flex-direction: column;
      gap: 4px;
    }

    .chat-sidebar__missing {
      margin: 0;
      padding: var(--hq-space-2) var(--hq-space-3);
      color: var(--hq-accent);
      font-size: var(--hq-font-meta-size);
    }

    .chat-sidebar__state {
      padding: 40px 16px;
      text-align: center;
      color: var(--hq-color-ink-soft, #64748b);
    }

    .chat-sidebar__empty-title {
      font-weight: 700;
      color: var(--hq-color-ink, #0f172a);
      margin-bottom: 4px;
    }

    .chat-sidebar__empty-hint {
      font-size: 12.5px;
      line-height: 1.4;
    }

    .thread-card {
      display: flex;
      align-items: center;
      gap: 12px;
      width: 100%;
      padding: 10px 12px;
      border: 1px solid transparent;
      border-radius: 12px;
      background: transparent;
      text-align: start;
      cursor: pointer;
      transition: all 0.15s ease;

      &:hover {
        background: rgba(0, 0, 0, 0.03);
      }

      &.is-active {
        background: var(--hq-color-surface, #ffffff);
        border-color: var(--hq-color-accent, var(--hq-color-brand-600));
        box-shadow: 0 4px 12px -2px color-mix(in srgb, var(--hq-color-brand-600) 12%, transparent);
      }
    }

    .thread-card__avatar {
      flex-shrink: 0;
      width: 40px;
      height: 40px;
      border-radius: 12px;
      background: var(--hq-gradient-brand-fill);
      color: #fff;
      display: grid;
      place-items: center;
      font-weight: 700;
      font-size: 15px;
      box-shadow: 0 2px 8px -1px color-mix(in srgb, var(--hq-color-brand-600) 30%, transparent);
    }

    .thread-card__content {
      flex: 1;
      min-width: 0;
    }

    .thread-card__top {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: 8px;
    }

    .thread-card__name {
      font-weight: 700;
      font-size: 13.5px;
      color: var(--hq-color-ink, #0f172a);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .thread-card__time {
      font-size: 11px;
      color: var(--hq-color-ink-faint, #94a3b8);
      flex-shrink: 0;
    }

    .thread-card__meta {
      font-size: 11.5px;
      color: var(--hq-color-ink-soft, #64748b);
      margin-bottom: 2px;
    }

    .thread-card__bottom {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
    }

    .thread-card__preview {
      font-size: 12.5px;
      color: var(--hq-color-ink-soft, #64748b);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
      flex: 1;
    }

    .thread-card__you {
      color: var(--hq-color-ink, #0f172a);
    }

    .thread-card__no-messages {
      font-style: italic;
      color: var(--hq-color-ink-faint, #94a3b8);
    }

    .thread-card__badge--staff,
    .thread-card__badge--complaint {
      border-radius: 0;
      font-size: var(--hq-font-label-size);
      font-weight: var(--hq-font-label-weight);
      letter-spacing: var(--hq-font-letter-spacing-label);
      text-transform: uppercase;
    }

    .thread-card__badge--complaint {
      background: var(--hq-color-accent-strong);
    }

    .thread-card__badge {
      flex-shrink: 0;
      min-width: 20px;
      height: 20px;
      padding: 0 6px;
      border-radius: 9999px;
      background: var(--hq-color-accent, var(--hq-color-brand-600));
      color: #fff;
      font-size: 11px;
      font-weight: 700;
      display: grid;
      place-items: center;
    }

    .chat-convo {
      display: flex;
      flex-direction: column;
      height: 100%;
      min-width: 0;
      background: var(--hq-color-surface, #ffffff);
    }

    .convo-header {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 12px 20px;
      border-bottom: 1px solid var(--hq-color-rule, #e2e8f0);
      background: var(--hq-color-surface, #ffffff);
      flex-shrink: 0;
    }

    .convo-header__back {
      display: none;
      background: none;
      border: none;
      cursor: pointer;
      color: var(--hq-color-ink, #0f172a);
      padding: 4px;
      svg {
        width: 22px;
        height: 22px;
      }
    }

    .convo-header__avatar {
      width: 38px;
      height: 38px;
      border-radius: 12px;
      background: var(--hq-gradient-brand-fill);
      color: #fff;
      display: grid;
      place-items: center;
      font-weight: 700;
      font-size: 15px;
      flex-shrink: 0;
    }

    .convo-header__info {
      flex: 1;
      min-width: 0;
    }

    .convo-header__title {
      font-size: 15px;
      font-weight: 700;
      color: var(--hq-color-ink, #0f172a);
      margin: 0;
      line-height: 1.3;
    }

    .convo-header__parent {
      font-size: 12px;
      color: var(--hq-color-ink-soft, #64748b);
    }

    .status-pill {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 4px 10px;
      border-radius: 9999px;
      font-size: 11.5px;
      font-weight: 600;
      background: var(--hq-color-surface-sunken, #f1f5f9);
      color: #64748b;
    }

    .status-pill__dot {
      width: 7px;
      height: 7px;
      border-radius: 50%;
      background: #94a3b8;
    }

    .status-pill--connected {
      background: rgba(34, 197, 94, 0.12);
      color: #16a34a;
      .status-pill__dot {
        background: #22c55e;
        box-shadow: 0 0 0 2px rgba(34, 197, 94, 0.25);
      }
    }

    .status-pill--connecting {
      background: color-mix(in srgb, var(--hq-color-warning-500) 12%, transparent);
      color: var(--hq-color-warning-ink);
      .status-pill__dot {
        background: var(--hq-color-warning-500);
      }
    }

    .convo-stream {
      flex: 1;
      overflow-y: auto;
      padding: 16px 20px;
      display: flex;
      flex-direction: column;
      gap: 12px;
      background: var(--hq-color-bg, #f8fafc);
    }

    .convo-stream__loading {
      text-align: center;
      padding: 32px;
      color: var(--hq-color-ink-soft, #64748b);
    }

    .message-row {
      display: flex;
      width: 100%;
      &--teacher {
        justify-content: flex-end;
        .message-bubble {
          background: var(--hq-gradient-brand-fill);
          color: #ffffff;
          border-radius: 16px 16px 4px 16px;
          box-shadow: 0 4px 14px -2px color-mix(in srgb, var(--hq-color-brand-600) 25%, transparent);
          .message-bubble__time {
            color: rgba(255, 255, 255, 0.8);
          }
          .message-bubble__status {
            color: rgba(255, 255, 255, 0.9);
          }
          .message-bubble__status--read {
            color: var(--hq-color-brand-100);
            font-weight: 700;
          }
          .message-bubble__status--failed {
            color: #fecaca;
          }
        }
      }
      &--parent {
        justify-content: flex-start;
        .message-bubble {
          background: var(--hq-color-surface, #ffffff);
          color: var(--hq-color-ink, #0f172a);
          border: 1px solid var(--hq-color-rule, #e2e8f0);
          border-radius: 16px 16px 16px 4px;
          box-shadow: 0 2px 8px -2px rgba(0, 0, 0, 0.05);
          .message-bubble__time {
            color: var(--hq-color-ink-faint, #94a3b8);
          }
        }
      }
    }

    .message-bubble {
      max-width: 72%;
      padding: 10px 14px;
      word-break: break-word;
      font-size: 13.5px;
      line-height: 1.45;
    }

    .message-bubble__body {
      white-space: pre-wrap;
    }

    .message-attachment {
      margin-bottom: 8px;

      &--image {
        position: relative;
        overflow: hidden;
        border-radius: 12px;
        cursor: pointer;
        max-width: 280px;

        &:hover .message-attachment__overlay {
          opacity: 1;
        }
      }

      &--pdf {
        display: flex;
        align-items: center;
        gap: 10px;
        padding: 8px 12px;
        background: rgba(0, 0, 0, 0.04);
        border: 1px solid rgba(0, 0, 0, 0.08);
        border-radius: 10px;
        max-width: 320px;
      }
    }

    .message-attachment__img {
      display: block;
      width: 100%;
      max-height: 200px;
      object-fit: cover;
      border-radius: 10px;
      transition: transform 0.2s ease;
    }

    .message-attachment__placeholder {
      padding: 16px;
      background: rgba(0, 0, 0, 0.05);
      border-radius: 10px;
      font-size: 12.5px;
    }

    .message-attachment__overlay {
      position: absolute;
      inset: 0;
      background: rgba(0, 0, 0, 0.45);
      color: #fff;
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 6px;
      font-size: 12px;
      font-weight: 600;
      opacity: 0;
      transition: opacity 0.2s ease;
      border-radius: 10px;
    }

    .message-attachment__pdf-icon {
      flex-shrink: 0;
      width: 34px;
      height: 34px;
      border-radius: 8px;
      background: rgba(239, 68, 68, 0.15);
      color: #ef4444;
      display: grid;
      place-items: center;
    }

    .message-attachment__pdf-info {
      flex: 1;
      min-width: 0;
    }

    .message-attachment__pdf-name {
      font-weight: 600;
      font-size: 13px;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .message-attachment__pdf-size {
      font-size: 11px;
      opacity: 0.8;
    }

    .message-attachment__pdf-btn {
      flex-shrink: 0;
      background: none;
      border: none;
      cursor: pointer;
      color: inherit;
      padding: 6px;
      border-radius: 6px;
      transition: background 0.15s ease;

      &:hover {
        background: rgba(0, 0, 0, 0.1);
      }
    }

    .message-row--teacher .message-attachment--pdf {
      background: rgba(255, 255, 255, 0.15);
      border-color: rgba(255, 255, 255, 0.25);
      color: #ffffff;

      .message-attachment__pdf-icon {
        background: rgba(255, 255, 255, 0.25);
        color: #ffffff;
      }
    }

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
      background: var(--hq-color-surface, #ffffff);
      border: 1px solid var(--hq-color-rule, #e2e8f0);
      border-radius: 16px;
      width: fit-content;
      box-shadow: 0 2px 8px -2px rgba(0, 0, 0, 0.05);
    }

    .typing-indicator__dot {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: #94a3b8;
      animation: typingPulse 1.2s infinite ease-in-out;
      &:nth-child(2) {
        animation-delay: 0.2s;
      }
      &:nth-child(3) {
        animation-delay: 0.4s;
      }
    }

    .typing-indicator__text {
      margin-inline-start: 6px;
      font-size: 11.5px;
      color: var(--hq-color-ink-soft, #64748b);
    }

    @keyframes typingPulse {
      0%,
      60%,
      100% {
        transform: translateY(0);
        opacity: 0.4;
      }
      30% {
        transform: translateY(-4px);
        opacity: 1;
      }
    }

    .convo-readonly {
      padding: var(--hq-space-16);
      border-block-start: var(--hq-size-rule) solid var(--hq-color-ink);
      color: var(--hq-color-ink-muted);
      font-size: var(--hq-text-note);
      text-align: center;
    }

    .convo-composer {
      position: relative;
      padding: 12px 16px;
      border-top: 1px solid var(--hq-color-rule, #e2e8f0);
      background: var(--hq-color-surface, #ffffff);
      flex-shrink: 0;
    }

    .convo-composer__box {
      display: flex;
      flex-direction: column;
      border: 1px solid var(--hq-color-rule, #e2e8f0);
      border-radius: 14px;
      padding: 8px 12px;
      background: var(--hq-color-surface, #ffffff);
      transition: all 0.2s ease;

      &:focus-within {
        border-color: var(--hq-color-accent, var(--hq-color-brand-600));
        box-shadow: 0 0 0 3px color-mix(in srgb, var(--hq-color-brand-600) 12%, transparent);
      }
    }

    .composer-attachment-bar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 6px 10px;
      margin-bottom: 8px;
      background: var(--hq-color-surface-sunken, #f8fafc);
      border: 1px solid var(--hq-color-rule, #e2e8f0);
      border-radius: 8px;
    }

    .attachment-chip {
      display: flex;
      align-items: center;
      gap: 8px;
      min-width: 0;
      flex: 1;
    }

    .attachment-chip__thumb {
      width: 32px;
      height: 32px;
      border-radius: 6px;
      object-fit: cover;
      flex-shrink: 0;
    }

    .attachment-chip__pdf-icon {
      width: 32px;
      height: 32px;
      border-radius: 6px;
      background: rgba(239, 68, 68, 0.15);
      color: #ef4444;
      display: grid;
      place-items: center;
      flex-shrink: 0;
    }

    .attachment-chip__details {
      display: flex;
      flex-direction: column;
      min-width: 0;
    }

    .attachment-chip__name {
      font-size: 12.5px;
      font-weight: 600;
      color: var(--hq-color-ink, #0f172a);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .attachment-chip__size {
      font-size: 11px;
      color: var(--hq-color-ink-faint, #94a3b8);
    }

    .attachment-chip__remove {
      background: none;
      border: none;
      cursor: pointer;
      color: var(--hq-color-ink-faint, #94a3b8);
      padding: 4px;
      font-size: 14px;
      border-radius: 4px;

      &:hover {
        background: rgba(0, 0, 0, 0.06);
        color: var(--hq-color-error, #ef4444);
      }
    }

    .convo-composer__input {
      width: 100%;
      border: none;
      outline: none;
      resize: none;
      font-family: inherit;
      font-size: 13.5px;
      line-height: 1.45;
      color: var(--hq-color-ink, #0f172a);
      background: transparent;
    }

    .convo-composer__bottom {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-top: 6px;
      padding-top: 4px;
    }

    .convo-composer__tools {
      display: flex;
      align-items: center;
      gap: 4px;
    }

    .composer-tool-btn {
      background: none;
      border: none;
      cursor: pointer;
      padding: 6px;
      border-radius: 8px;
      color: var(--hq-color-ink-soft, #64748b);
      transition: all 0.15s ease;
      display: grid;
      place-items: center;

      &:hover {
        background: rgba(0, 0, 0, 0.05);
        color: var(--hq-color-accent, var(--hq-color-brand-600));
      }

      &.is-active {
        background: color-mix(in srgb, var(--hq-color-brand-600) 12%, transparent);
        color: var(--hq-color-accent, var(--hq-color-brand-600));
      }
    }

    .convo-composer__actions {
      display: flex;
      align-items: center;
      gap: 10px;
    }

    .convo-composer__count {
      font-size: 11px;
      color: var(--hq-color-ink-faint, #94a3b8);
      &.is-limit {
        color: var(--hq-color-error, #ef4444);
        font-weight: 700;
      }
    }

    .convo-composer__send {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 7px 16px;
      background: var(--hq-color-accent, var(--hq-color-brand-600));
      color: #fff;
      border: none;
      border-radius: 10px;
      font-size: 13px;
      font-weight: 600;
      cursor: pointer;
      box-shadow: 0 2px 8px -1px color-mix(in srgb, var(--hq-color-brand-600) 30%, transparent);
      transition: all 0.15s ease;

      &:hover:not(:disabled) {
        background: var(--hq-color-brand-700);
        transform: translateY(-1px);
      }
      &:disabled {
        opacity: 0.5;
        cursor: not-allowed;
      }
    }

    /* Emoji Picker Styling */
    .emoji-picker {
      position: absolute;
      bottom: 74px;
      left: 16px;
      width: 320px;
      background: var(--hq-color-surface, #ffffff);
      border: 1px solid var(--hq-color-rule, #e2e8f0);
      border-radius: 14px;
      box-shadow: 0 10px 30px -4px rgba(0, 0, 0, 0.15);
      z-index: 50;
      padding: 10px;
      display: flex;
      flex-direction: column;
      gap: 8px;
    }

    .emoji-picker__header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      border-bottom: 1px solid var(--hq-color-rule, #e2e8f0);
      padding-bottom: 6px;
    }

    .emoji-picker__categories {
      display: flex;
      gap: 4px;
    }

    .emoji-picker__cat-btn {
      display: flex;
      align-items: center;
      gap: 4px;
      background: none;
      border: none;
      cursor: pointer;
      padding: 4px 8px;
      border-radius: 8px;
      font-size: 11.5px;
      color: var(--hq-color-ink-soft, #64748b);
      transition: all 0.15s ease;

      &:hover {
        background: rgba(0, 0, 0, 0.04);
      }

      &.is-active {
        background: color-mix(in srgb, var(--hq-color-brand-600) 12%, transparent);
        color: var(--hq-color-accent, var(--hq-color-brand-600));
        font-weight: 600;
      }
    }

    .emoji-picker__close {
      background: none;
      border: none;
      cursor: pointer;
      color: var(--hq-color-ink-faint, #94a3b8);
      padding: 4px 6px;
      border-radius: 6px;
      font-size: 13px;

      &:hover {
        background: rgba(0, 0, 0, 0.05);
        color: var(--hq-color-ink, #0f172a);
      }
    }

    .emoji-picker__search {
      width: 100%;
    }

    .emoji-picker__search-input {
      width: 100%;
      padding: 6px 10px;
      font-size: 12px;
      border: 1px solid var(--hq-color-rule, #e2e8f0);
      border-radius: 8px;
      background: var(--hq-color-surface-sunken, #f8fafc);
      color: var(--hq-color-ink, #0f172a);
      outline: none;

      &:focus {
        border-color: var(--hq-color-accent, var(--hq-color-brand-600));
      }
    }

    .emoji-picker__grid {
      display: grid;
      grid-template-columns: repeat(8, 1fr);
      gap: 4px;
      max-height: 180px;
      overflow-y: auto;
      padding: 4px;
    }

    .emoji-btn {
      background: none;
      border: none;
      cursor: pointer;
      font-size: 18px;
      padding: 4px;
      border-radius: 6px;
      display: grid;
      place-items: center;
      transition:
        transform 0.1s ease,
        background 0.1s ease;

      &:hover {
        transform: scale(1.25);
        background: rgba(0, 0, 0, 0.06);
      }
    }

    /* Lightbox Modal */
    .image-modal-backdrop {
      position: fixed;
      inset: 0;
      background: rgba(0, 0, 0, 0.75);
      backdrop-filter: blur(4px);
      z-index: 9999;
      display: grid;
      place-items: center;
      padding: 24px;
    }

    .image-modal-card {
      background: var(--hq-color-surface, #ffffff);
      border-radius: 16px;
      max-width: 90vw;
      max-height: 90vh;
      overflow: hidden;
      display: flex;
      flex-direction: column;
      box-shadow: 0 20px 40px rgba(0, 0, 0, 0.3);
    }

    .image-modal-bar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
      padding: 12px 18px;
      border-bottom: 1px solid var(--hq-color-rule, #e2e8f0);
      background: var(--hq-color-surface, #ffffff);
    }

    .image-modal-title {
      font-weight: 700;
      font-size: 14px;
      color: var(--hq-color-ink, #0f172a);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
      max-width: 400px;
    }

    .image-modal-actions {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .image-modal-btn {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      background: var(--hq-color-surface-sunken, #f1f5f9);
      border: 1px solid var(--hq-color-rule, #e2e8f0);
      border-radius: 8px;
      padding: 6px 12px;
      font-size: 12.5px;
      font-weight: 600;
      color: var(--hq-color-ink, #0f172a);
      cursor: pointer;
      transition: all 0.15s ease;

      &:hover {
        background: var(--hq-color-accent, var(--hq-color-brand-600));
        color: #fff;
        border-color: var(--hq-color-accent, var(--hq-color-brand-600));
      }

      &--close {
        padding: 6px 10px;
        font-size: 14px;
      }
    }

    .image-modal-content {
      overflow: auto;
      padding: 16px;
      display: grid;
      place-items: center;
      background: #000000;
    }

    .image-modal-img {
      max-width: 100%;
      max-height: 75vh;
      object-fit: contain;
      border-radius: 8px;
    }

    .convo-empty {
      display: grid;
      place-items: center;
      height: 100%;
      padding: 32px;
      background: var(--hq-color-bg, #f8fafc);
    }

    .convo-empty__card {
      max-width: 380px;
      text-align: center;
      color: var(--hq-color-ink-soft, #64748b);
      background: var(--hq-color-surface, #ffffff);
      padding: 36px 28px;
      border-radius: 20px;
      border: 1px solid var(--hq-color-rule, #e2e8f0);
      box-shadow: 0 4px 16px -2px rgba(0, 0, 0, 0.05);
    }

    .convo-empty__icon {
      width: 52px;
      height: 52px;
      margin: 0 auto 16px;
      color: var(--hq-color-accent, var(--hq-color-brand-600));
    }

    .convo-empty__title {
      font-size: 18px;
      font-weight: 700;
      color: var(--hq-color-ink, #0f172a);
      margin: 0 0 6px;
    }

    .convo-empty__hint {
      font-size: 13.5px;
      line-height: 1.5;
      margin: 0;
    }

    /* Dark Mode Theme */
    :host-context(html.dark) {
      .chat-layout {
        background: var(--hq-color-surface, #171f2e) !important;
        border-color: rgba(255, 255, 255, 0.08) !important;
        box-shadow: 0 4px 20px -2px rgba(0, 0, 0, 0.4) !important;
      }

      .chat-sidebar {
        background: #0f172a !important;
        border-color: rgba(255, 255, 255, 0.08) !important;
      }

      .chat-sidebar__search {
        border-color: rgba(255, 255, 255, 0.08) !important;
      }

      .search-box__input {
        background: #1e293b !important;
        border-color: rgba(255, 255, 255, 0.12) !important;
        color: #f8fafc !important;

        &:focus {
          border-color: var(--hq-color-brand-500) !important;
        }
      }

      .search-box__clear {
        color: #94a3b8 !important;
        &:hover {
          background: rgba(255, 255, 255, 0.1) !important;
          color: #f8fafc !important;
        }
      }

      .thread-card:hover {
        background: rgba(255, 255, 255, 0.04) !important;
      }

      .thread-card.is-active {
        background: #1e293b !important;
        border-color: var(--hq-color-brand-500) !important;
        box-shadow: 0 4px 14px -2px rgba(0, 0, 0, 0.4) !important;
      }

      .thread-card__name {
        color: #f8fafc !important;
      }

      .thread-card__meta,
      .thread-card__preview {
        color: #94a3b8 !important;
      }

      .thread-card__you {
        color: #cbd5e1 !important;
      }

      .chat-convo {
        background: #0f172a !important;
      }

      .convo-header {
        background: #171f2e !important;
        border-color: rgba(255, 255, 255, 0.08) !important;
      }

      .convo-header__title {
        color: #f8fafc !important;
      }

      .convo-header__parent {
        color: #94a3b8 !important;
      }

      .convo-stream {
        background: #0b1120 !important;
      }

      .message-row--parent .message-bubble {
        background: #1e293b !important;
        border-color: rgba(255, 255, 255, 0.1) !important;
        color: #f8fafc !important;
        box-shadow: 0 2px 10px rgba(0, 0, 0, 0.3) !important;
      }

      .message-row--parent .message-bubble__time {
        color: #94a3b8 !important;
      }

      .message-attachment--pdf {
        background: rgba(255, 255, 255, 0.06) !important;
        border-color: rgba(255, 255, 255, 0.12) !important;
      }

      .typing-indicator {
        background: #1e293b !important;
        border-color: rgba(255, 255, 255, 0.1) !important;
      }

      .typing-indicator__text {
        color: #94a3b8 !important;
      }

      .convo-composer {
        background: #171f2e !important;
        border-color: rgba(255, 255, 255, 0.08) !important;
      }

      .convo-composer__box {
        background: #1e293b !important;
        border-color: rgba(255, 255, 255, 0.12) !important;
      }

      .composer-attachment-bar {
        background: #171f2e !important;
        border-color: rgba(255, 255, 255, 0.1) !important;
      }

      .attachment-chip__name {
        color: #f8fafc !important;
      }

      .convo-composer__input {
        color: #f8fafc !important;
      }

      .composer-tool-btn {
        color: #94a3b8 !important;
        &:hover {
          background: rgba(255, 255, 255, 0.08) !important;
          color: var(--hq-color-brand-ink) !important;
        }
      }

      .emoji-picker {
        background: #1e293b !important;
        border-color: rgba(255, 255, 255, 0.12) !important;
        box-shadow: 0 10px 30px rgba(0, 0, 0, 0.5) !important;
      }

      .emoji-picker__header {
        border-color: rgba(255, 255, 255, 0.1) !important;
      }

      .emoji-picker__cat-btn {
        color: #94a3b8 !important;
        &:hover {
          background: rgba(255, 255, 255, 0.06) !important;
        }
        &.is-active {
          background: color-mix(in srgb, var(--hq-color-brand-500) 20%, transparent) !important;
          color: var(--hq-color-brand-300) !important;
        }
      }

      .emoji-picker__search-input {
        background: #0f172a !important;
        border-color: rgba(255, 255, 255, 0.12) !important;
        color: #f8fafc !important;
      }

      .emoji-btn:hover {
        background: rgba(255, 255, 255, 0.1) !important;
      }

      .image-modal-card {
        background: #1e293b !important;
      }

      .image-modal-bar {
        background: #1e293b !important;
        border-color: rgba(255, 255, 255, 0.1) !important;
      }

      .image-modal-title {
        color: #f8fafc !important;
      }

      .image-modal-btn {
        background: #334155 !important;
        border-color: rgba(255, 255, 255, 0.1) !important;
        color: #f8fafc !important;
      }

      .convo-empty {
        background: #0b1120 !important;
      }

      .convo-empty__card {
        background: #171f2e !important;
        border-color: rgba(255, 255, 255, 0.08) !important;
        box-shadow: 0 4px 20px rgba(0, 0, 0, 0.4) !important;
      }

      .convo-empty__title {
        color: #f8fafc !important;
      }

      .convo-empty__hint {
        color: #94a3b8 !important;
      }
    }

    @media (max-width: 768px) {
      :host {
        --hq-page-padding: 0 8px 12px;
      }
      .chat-layout {
        grid-template-columns: 1fr;
        height: calc(100vh - 130px);
        border-radius: 12px;
      }
      .chat-sidebar {
        display: flex;
      }
      .chat-convo {
        display: none;
      }
      .chat-layout--show-convo {
        .chat-sidebar {
          display: none;
        }
        .chat-convo {
          display: flex;
        }
        .convo-header__back {
          display: block;
        }
      }
      .emoji-picker {
        width: 290px;
        left: 8px;
      }
    }
  `,
})
export class ChatPage implements AfterViewChecked {
  protected readonly chatService = inject(ChatService);
  private readonly auth = inject(AuthService);
  private readonly transloco = inject(TranslocoService);
  private readonly chatApi = inject(ChatApi);
  private readonly managementChat = inject(ManagementChatApi);
  private readonly management = inject(ManagementApi);
  private readonly managers = inject(ManagersApi);

  readonly flag = FLAGS.chat;
  readonly senderTeacher = ChatMessageSenderEnum.TEACHER;
  readonly topicComplaint = ChatThreadTopicEnum.COMPLAINT;

  /**
   * **What the header's pill says**, or `null` for no pill (T2 item d).
   *
   * It used to read `connectionStatus` — *this* tab's own socket — and say "Live", which every
   * reader took to mean the other person was there: a manager who had signed out still read as
   * Live in the teacher's tab, because the teacher's own socket was fine. It now says what the
   * `presence` frames and the row's `peerOnline` say (`ChatService.activePeerOnline`).
   *
   * Our own socket comes first and only while it is broken: with no socket there is no presence
   * to report, and saying "Away" would blame the peer for our connection. With the socket up the
   * answer is the peer's — and `undefined`, a peer nobody has reported on, is no pill at all: an
   * empty header is honest where a guess is not.
   */
  protected readonly peerState = computed<'connecting' | 'offline' | 'online' | 'away' | null>(() => {
    const status = this.chatService.connectionStatus();
    if (status === 'connecting' || status === 'reconnecting') return 'connecting';
    if (status === 'disconnected') return 'offline';
    const peer = this.chatService.activePeerOnline();
    return peer === undefined ? null : peer ? 'online' : 'away';
  });

  /**
   * R7: one screen, three doors. `/teacher/chat` is the parent conversations, and
   * `/coordinator/messages` is the same list over her own routes plus the staff threads;
   * `/management/messages` is the manager's read-only side of those (`ChatRoutes`).
   */
  protected readonly title = computed(() =>
    this.auth.role() === 'TEACHER' ? 'chat.title' : 'chat.messagesTitle',
  );

  protected readonly subtitle = computed(() => {
    switch (this.auth.role()) {
      // RM3b: a real inbox at last — parents of her department, her coordinators and the admin,
      // on `GET /management/chat/threads`. R7's "live view that starts empty on every reload" is
      // gone, and so is the title that had to admit it.
      case 'MANAGERIAL':
        return 'chat.managementSubtitle';
      case 'ADMIN':
        return 'chat.supportSubtitle';
      // Her threads are not all "parents of your students" — one of them is a manager.
      case 'COORDINATOR':
        return 'chat.coordinatorSubtitle';
      default:
        return 'chat.subtitle';
    }
  });

  protected readonly emptyTitle = 'chat.noThreads';
  protected readonly emptyHint = computed(() => {
    if (this.auth.role() !== 'ADMIN') return 'chat.noThreadsHint';
    // `/admin/chat/**` needs `X-School-Id`, so an Admin who has not narrowed to one school is
    // told that rather than shown an inbox that would have been a 400.
    return this.chatService.canWrite() ? 'chat.supportEmptyHint' : 'chat.supportNoSchool';
  });

  readonly emojiCategories = EMOJI_CATEGORIES;

  readonly searchQuery = signal<string>('');
  readonly draftMessage = signal<string>('');
  readonly mobileShowConvo = signal<boolean>(false);

  readonly attachedFile = signal<ChatAttachment | null>(null);
  readonly showEmojiPicker = signal<boolean>(false);
  readonly activeEmojiCategory = signal<'smileys' | 'education' | 'fun'>('smileys');
  readonly emojiSearch = signal<string>('');
  readonly activeImageModal = signal<{ name: string; url: string } | null>(null);

  private readonly streamRef = viewChild<ElementRef<HTMLElement>>('messageStream');
  private readonly composerInputRef = viewChild<ElementRef<HTMLTextAreaElement>>('composerInput');
  private shouldScrollToBottom = false;

  readonly filteredThreads = computed(() => {
    const q = this.searchQuery().trim().toLowerCase();
    const list = this.chatService.threads();
    if (!q) return list;
    return list.filter(
      (t) =>
        this.nameOf(t).toLowerCase().includes(q) ||
        (t.className && t.className.toLowerCase().includes(q)) ||
        (t.subject && t.subject.toLowerCase().includes(q)),
    );
  });

  protected keyOf(thread: ChatThread): string {
    return this.chatService.keyOf(thread);
  }

  /**
   * A staff-to-staff thread — no child on it at all.
   *
   * **`childId`, not `staffRole`.** RM3b found the old test wrong: `staff_role` is the *staff
   * side's* role, so a parent writing to a manager about her child produces `MANAGERIAL` with a
   * child on the row, and a coordinator ↔ manager thread produces `MANAGERIAL` without one. The
   * empty child is the only thing that separates them, and it is what the contract promises
   * ("`childId` is empty on those rows").
   */
  protected isStaff(thread: ChatThread): boolean {
    return thread.childId === '';
  }

  /** The child a parent thread is about, or the staff member on the other end of a staff one. */
  protected nameOf(thread: ChatThread): string {
    if (!this.isStaff(thread)) return thread.childName;
    // D2 (list 3): a thread the school's admin opened with a teacher or a coordinator says who is
    // on the other end by office, not by a name she may never have met.
    if (thread.withAdmin === true && (this.auth.role() === 'TEACHER' || this.auth.role() === 'COORDINATOR')) {
      return this.transloco.translate<string>('chat.schoolAdministration');
    }
    return thread.teacherName || this.transloco.translate<string>('chat.management');
  }

  // ---------------------------------------------------------------- RM3b: the manager's three inboxes

  protected readonly peer = signal<Peer | 'all'>('all');
  protected readonly startingThread = signal(false);
  protected readonly startingThreadBusy = signal(false);
  protected readonly chosenPerson = signal<string>('');

  /**
   * The roles that hold a `POST …/chat/threads` naming a person rather than a child.
   *
   * MG2b: a teacher does now — `POST /teacher/chat/staff-threads {managerUserId}` — so "New
   * message" is her way to write to the department manager without waiting to be written to. Her
   * *parent* threads still start from a roster row, which is where a child is chosen.
   */
  protected readonly canStartThread = computed(
    () =>
      this.auth.role() === 'MANAGERIAL' ||
      this.auth.role() === 'TEACHER' ||
      // Same header, same reason: there is no school for a new thread to belong to.
      (this.auth.role() === 'ADMIN' && this.chatService.canWrite()),
  );

  private readonly coordinators = rxResource({
    params: () => this.auth.role() === 'MANAGERIAL',
    stream: ({ params }) => (params ? this.management.managementCoordinators() : of([])),
    defaultValue: [],
  });

  private readonly admins = rxResource({
    params: () => this.auth.role() === 'MANAGERIAL',
    stream: ({ params }) => (params ? this.managementChat.managementAdmins() : of([])),
    defaultValue: [],
  });

  private readonly schoolManagers = rxResource({
    params: () => this.auth.role() === 'ADMIN',
    stream: ({ params }) => (params ? this.managers.managers() : of([])),
    defaultValue: [],
  });

  /** MG2b: the department's teachers, for her chooser and for the chip a thread of theirs carries. */
  private readonly departmentTeachers = rxResource({
    params: () => this.auth.role() === 'MANAGERIAL',
    stream: ({ params }) => (params ? this.management.managementTeachers() : of([])),
    defaultValue: [],
  });

  /** MG2b: the manager(s) of the departments this teacher teaches in (`GET /teacher/managers`). */
  private readonly myManagers = rxResource({
    params: () => this.auth.role() === 'TEACHER',
    stream: ({ params }) => (params ? this.chatApi.teacherManagers() : of([])),
    defaultValue: [],
  });

  /**
   * Which of her three correspondents a thread is with.
   *
   * The row cannot say on its own: a parent thread and both staff threads all carry
   * `staffRole: MANAGERIAL`, because she is the staff side of one and the named peer of the other
   * two. So the child settles "parent", and the peer's id is looked up in the two lists the
   * "New message" picker already needs — no extra call for the sake of a badge.
   */
  protected peerOf(thread: ChatThread): Peer {
    if (thread.childId !== '') return 'parents';
    // A teacher's only child-less threads are with the department manager, so there is nothing to
    // look up — and `GET /teacher/managers` may not have answered yet when the first row arrives.
    if (this.auth.role() === 'TEACHER') return 'management';
    if (this.admins.value().some((person) => person.userId === thread.teacherId)) return 'admin';
    return this.departmentTeachers.value().some((person) => person.userId === thread.teacherId)
      ? 'teachers'
      : 'coordinators';
  }

  /**
   * The chip strip: only where there is more than one kind of correspondent to separate.
   *
   * A manager always has four. A teacher has one until somebody from Management writes to her (or
   * she writes to them), and a strip with a single chip on it is a filter over nothing.
   */
  protected readonly showsPeerTabs = computed(() => {
    const rows = this.chatService.threads();
    if (rows.length === 0) return false;
    if (this.auth.role() === 'MANAGERIAL') return true;
    return this.auth.role() === 'TEACHER' && rows.some((thread) => this.isStaff(thread));
  });

  protected readonly peerTabs = computed<readonly Tab<Peer | 'all'>[]>(() => {
    const counted = (which: Peer) =>
      this.filteredThreads().filter((thread) => this.peerOf(thread) === which).length;
    const chip = (which: Peer) => ({
      id: which,
      label: this.transloco.translate<string>(`chat.peer.${which}`),
      badge: counted(which),
    });
    const all = { id: 'all' as const, label: this.transloco.translate<string>('chat.peer.all') };
    // MG2b: her two lists, one strip. The teacher's Management chip *is* the staff-thread list —
    // the rows come from the second transport and are counted in the same badge maths.
    if (this.auth.role() === 'TEACHER') return [all, chip('parents'), chip('management')];
    return [all, chip('parents'), chip('teachers'), chip('coordinators'), chip('admin')];
  });

  /** The search, then the tab. Both are filters over one list, so neither refetches anything. */
  protected readonly visibleThreads = computed(() => {
    const which = this.peer();
    const rows = this.filteredThreads();
    if (which === 'all' || !this.showsPeerTabs()) return rows;
    return rows.filter((thread) => this.peerOf(thread) === which);
  });

  /** `userId` is prefixed so one select can hold three kinds of person and still say which. */
  protected readonly peopleGroups = computed<readonly SelectOptionGroup[]>(() => {
    if (this.auth.role() === 'TEACHER') {
      return [
        {
          label: this.transloco.translate<string>('chat.peer.management'),
          options: this.myManagers
            .value()
            .filter((person) => person.userId !== undefined)
            .map((person) => ({
              value: `manager:${person.userId}`,
              label: [
                person.displayName,
                person.curriculum ? this.transloco.translate<string>(`curriculum.${person.curriculum}`) : '',
              ]
                .filter((part) => part !== '' && part !== undefined)
                .join(' · '),
            })),
        },
      ];
    }
    if (this.auth.role() === 'ADMIN') {
      return [
        {
          label: this.transloco.translate<string>('chat.peer.managers'),
          options: this.schoolManagers
            .value()
            .filter((person) => person.userId !== undefined)
            .map((person) => ({ value: `manager:${person.userId}`, label: person.fullName ?? '' })),
        },
      ];
    }
    return [
      {
        label: this.transloco.translate<string>('chat.peer.coordinators'),
        options: this.coordinators
          .value()
          .filter((person) => person.userId !== undefined)
          .map((person) => ({
            value: `coordinator:${person.userId}`,
            label: [person.displayName, (person.subjects ?? []).join(', ')]
              .filter((part) => part !== '' && part !== undefined)
              .join(' · '),
          })),
      },
      {
        label: this.transloco.translate<string>('chat.peer.teachers'),
        options: this.departmentTeachers
          .value()
          .filter((person) => person.userId !== undefined)
          .map((person) => ({
            value: `teacher:${person.userId}`,
            label: [person.displayName, (person.subjects ?? []).join(', ')]
              .filter((part) => part !== '' && part !== undefined)
              .join(' · '),
          })),
      },
      {
        label: this.transloco.translate<string>('chat.peer.admin'),
        options: this.admins
          .value()
          .filter((person) => person.userId !== undefined)
          .map((person) => ({ value: `admin:${person.userId}`, label: person.displayName ?? '' })),
      },
    ];
  });

  protected openThreadWith(): void {
    const chosen = this.chosenPerson();
    const [kind, userId] = [chosen.slice(0, chosen.indexOf(':')), chosen.slice(chosen.indexOf(':') + 1)];
    if (userId === '') return;
    this.startingThreadBusy.set(true);
    const request =
      this.auth.role() === 'TEACHER'
        ? // MG2b: her end of the pair. One thread per (teacher, manager), however many times
          // either side asks for it — so pressing this twice opens the same conversation.
          this.chatApi.teacherStaffThread({ managerUserId: userId })
        : kind === 'manager'
          ? this.chatApi.supportManagerThread({ managerUserId: userId })
          : this.managementChat.managementStaffThread(
              kind === 'admin'
                ? { adminUserId: userId }
                : kind === 'teacher'
                  ? { teacherUserId: userId }
                  : { coordinatorUserId: userId },
            );
    request
      .pipe(
        tap((thread) => {
          this.startingThreadBusy.set(false);
          this.startingThread.set(false);
          this.chosenPerson.set('');
          // The list, then the row: the thread may be brand new, and `selectThread` only marks
          // read a key the list already holds (a `POST …/read` on nothing is a 404 in a band).
          this.chatService.loadThreads();
          this.onSelectThread(thread.id ?? '');
        }),
        catchError(() => {
          this.startingThreadBusy.set(false);
          return of(null);
        }),
      )
      .subscribe();
  }

  /**
   * Whose bubble this is — by sender **identity**, not by role.
   *
   * On a staff thread both ends serialise as `sender: "teacher"` (the contract's `ChatSender` has
   * only `parent` and `teacher`), so aligning on the role alone drew the manager's replies as the
   * coordinator's own. `senderId` is the only thing that tells them apart; a parent thread still
   * answers the question by role, where the id is the signed-in teacher's anyway.
   */
  protected isMine(msg: { sender: ChatMessageSenderEnum; senderId: string }): boolean {
    if (msg.sender !== ChatMessageSenderEnum.TEACHER) return false;
    const active = this.chatService.activeThread();
    if (active === null || !this.isStaff(active)) return true;
    return msg.senderId === (this.auth.user()?.id ?? '');
  }

  readonly filteredEmojis = computed(() => {
    const cat = this.emojiCategories.find((c) => c.id === this.activeEmojiCategory());
    const list = cat ? cat.emojis : [];
    const search = this.emojiSearch().trim().toLowerCase();
    if (!search) return list;
    return list.filter((e) => e.includes(search));
  });

  readonly canSend = computed(() => {
    const body = this.draftMessage().trim();
    const hasAtt = this.attachedFile() !== null;
    return (body.length > 0 || hasAtt) && this.draftMessage().length <= 2000;
  });

  /**
   * The query **as a signal**, not `route.snapshot` (MG2b item 4, the fix `?open=` had on
   * Broadcasts).
   *
   * The bell is on every screen, so clicking a `chat.message` notification *while already on
   * Messages* is a query-param-only navigation: Angular reuses the component, the snapshot the
   * constructor read is never re-read, and the conversation the notification named would not open.
   * `requireSync` because `queryParamMap` emits the current query on subscribe.
   */
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly query = toSignal(this.route.queryParamMap, { requireSync: true });
  /** The link this visit has already acted on, so a rerun of the effect is not a second open. */
  private followed = '';
  /** The link the list has been re-read for, so one stale link is one extra read (D2). */
  private refetchedFor = '';
  /**
   * The thread she was *viewing* is gone from her list (`ChatService.activeGone`): said in a line.
   * A linked or remembered id that was never in the list is not this — it is dropped in silence.
   */
  protected readonly missingThread = computed(() => this.chatService.activeGone());

  constructor() {
    /*
     * U1 item 6: "Message parent" arrives here as `?childId=…&name=…`, and it has to open the
     * conversation even when the child has never been written to — the thread row is created by
     * the first message, so there is nothing in the list to select yet. `openWith` draws that
     * conversation from the name the link carried; the server's own row replaces it on send.
     *
     * MG2b: `?thread=` waits for the threads list. A key is a key *of a row* since a teacher holds
     * two transports (`core/chat/chat-routes.ts`), so selecting one the list has not answered yet
     * would send her staff thread's id to her child-keyed parent routes — a 404 in a red band over
     * the conversation the bell just promised her.
     */
    effect(() => {
      const params = this.query();
      const threadId = params.get('thread') ?? '';
      const childId = params.get('childId') ?? '';
      const link = `${threadId}|${childId}`;
      if (link === '|' || link === this.followed) return;
      if (threadId !== '') {
        if (!this.chatService.holds(threadId)) {
          // Said once the list has actually answered, because "not found" while it is still
          // loading is a lie.
          if (this.chatService.loadingThreads()) return;
          // D2: the list was read at sign-in, and a thread opened since — "Message" on a row, a
          // bell link for a conversation somebody just started — is not in it. Ask once more
          // before calling the link stale; only a second miss is "not in your list".
          if (this.refetchedFor !== link) {
            this.refetchedFor = link;
            untracked(() => this.chatService.loadThreads());
            if (this.chatService.loadingThreads()) return;
          }
          // Still not hers after a fresh read: a stale bell link, or an id remembered from
          // somewhere else. Not an error she caused — the id leaves the URL and the list is the
          // screen; nothing is said, and nothing is ever asked of the server by that id.
          this.followed = link;
          untracked(() => {
            void this.router.navigate([], {
              relativeTo: this.route,
              queryParams: { thread: null },
              queryParamsHandling: 'merge',
              replaceUrl: true,
            });
          });
          return;
        }
        // The row may be behind a chip or a search she left on: the link names one conversation,
        // so the list shows the tab it is in.
        this.peer.set('all');
        this.searchQuery.set('');
        this.followed = link;
        this.chatService.selectThread(threadId);
        this.mobileShowConvo.set(true);
        return;
      }
      this.followed = link;
      this.chatService.openWith(childId, params.get('name') ?? '', params.get('class') ?? undefined);
      this.mobileShowConvo.set(true);
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

  onSelectThread(key: string): void {
    this.chatService.selectThread(key);
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
    const att = this.attachedFile();
    const text = this.draftMessage().trim();
    let body = text;

    if (att) {
      const tag = `[attachment:${att.id}:${att.type}:${encodeURIComponent(att.name)}:${att.size}]`;
      body = text ? `${text}\n\n${tag}` : tag;
    }

    this.draftMessage.set('');
    this.attachedFile.set(null);
    this.showEmojiPicker.set(false);
    this.chatService.sendMessage(body);
    this.shouldScrollToBottom = true;
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (!input.files || input.files.length === 0) return;
    const file = input.files[0];
    if (!file) return;

    const isImage = file.type.startsWith('image/');
    const isPdf = file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf');

    if (!isImage && !isPdf) {
      input.value = '';
      return;
    }

    if (file.size > 10 * 1024 * 1024) {
      input.value = '';
      return;
    }

    const reader = new FileReader();
    reader.onload = () => {
      const dataUrl = reader.result as string;
      const att: ChatAttachment = {
        id: `att-${Date.now()}-${Math.random().toString(36).substring(2, 7)}`,
        name: file.name,
        size: this.formatFileSize(file.size),
        type: isImage ? 'image' : 'pdf',
        dataUrl,
      };
      ChatAttachmentStore.save(att);
      this.attachedFile.set(att);
      input.value = '';
    };
    reader.readAsDataURL(file);
  }

  removeAttachment(): void {
    this.attachedFile.set(null);
  }

  toggleEmojiPicker(event: Event): void {
    event.stopPropagation();
    this.showEmojiPicker.update((v) => !v);
  }

  onSelectEmoji(emoji: string): void {
    const textarea = this.composerInputRef()?.nativeElement;
    const current = this.draftMessage();
    if (textarea) {
      const start = textarea.selectionStart ?? current.length;
      const end = textarea.selectionEnd ?? current.length;
      const next = current.substring(0, start) + emoji + current.substring(end);
      this.draftMessage.set(next);
      setTimeout(() => {
        textarea.focus();
        textarea.setSelectionRange(start + emoji.length, start + emoji.length);
      }, 0);
    } else {
      this.draftMessage.set(current + emoji);
    }
  }

  openImagePreview(name: string, url: string): void {
    if (!url) return;
    this.activeImageModal.set({ name, url });
  }

  closeImagePreview(): void {
    this.activeImageModal.set(null);
  }

  onBackdropClick(event: MouseEvent): void {
    if ((event.target as HTMLElement).classList.contains('image-modal-backdrop')) {
      this.closeImagePreview();
    }
  }

  downloadAttachment(name: string, url: string): void {
    if (!url) return;
    const a = document.createElement('a');
    a.href = url;
    a.download = name;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
  }

  parseMessage(body: string): ParsedChatMessage {
    // 1. Check for custom attachment tag [attachment:id:type:name:size]
    const match = body.match(/\[attachment:([^:]+):(image|pdf):([^:]+):([^\]]+)\]/);
    if (match && match[1] && match[2] && match[3] && match[4]) {
      const tag = match[0];
      const id = match[1];
      const type = match[2] as 'image' | 'pdf';
      const encodedName = match[3];
      const size = match[4];
      const name = decodeURIComponent(encodedName);
      const text = body.replace(tag, '').trim();
      const stored = ChatAttachmentStore.get(id);
      return {
        text,
        attachment: {
          id,
          name,
          size,
          type,
          dataUrl: stored?.dataUrl ?? '',
        },
      };
    }

    // 2. Check for markdown image: ![name](url)
    const imgMatch = body.match(/!\[([^\]]*)\]\(([^)]+)\)/);
    if (imgMatch && imgMatch[2]) {
      const tag = imgMatch[0];
      const alt = imgMatch[1] || 'Image';
      const url = imgMatch[2];
      const text = body.replace(tag, '').trim();
      return {
        text,
        attachment: {
          name: alt,
          type: 'image',
          dataUrl: url,
        },
      };
    }

    // 3. Check for markdown pdf: [name](url)
    const pdfMatch = body.match(/\[([^\]]+)\]\((data:application\/pdf[^)]+|https?:\/\/[^)]+\.pdf[^)]*)\)/);
    if (pdfMatch && pdfMatch[1] && pdfMatch[2]) {
      const tag = pdfMatch[0];
      const name = pdfMatch[1];
      const url = pdfMatch[2];
      const text = body.replace(tag, '').trim();
      return {
        text,
        attachment: {
          name,
          type: 'pdf',
          dataUrl: url,
        },
      };
    }

    return { text: body };
  }

  formatPreview(body: string): string {
    const parsed = this.parseMessage(body);
    if (parsed.attachment) {
      const prefix = parsed.attachment.type === 'pdf' ? '📎 [PDF]' : '🖼️ [Image]';
      return parsed.text ? `${prefix} ${parsed.text}` : `${prefix} ${parsed.attachment.name}`;
    }
    return parsed.text || body;
  }

  initialOf(name: string): string {
    const trimmed = name.trim();
    return trimmed ? trimmed.charAt(0).toUpperCase() : '?';
  }

  private formatFileSize(bytes: number): string {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  }

  private scrollToBottom(): void {
    const el = this.streamRef()?.nativeElement;
    if (el) {
      el.scrollTop = el.scrollHeight;
    }
  }
}
