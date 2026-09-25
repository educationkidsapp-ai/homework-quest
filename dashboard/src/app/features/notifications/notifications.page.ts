/* hq-flag: none (shell) — notifications are part of the core dashboard shell across all roles. */
import { DatePipe, NgClass } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { type NotificationView, NotificationViewKindEnum } from '../../api';
import { activeLang } from '../../core/i18n/active-lang';
import { NotificationsService, bodyKeyOf, titleKeyOf } from '../../core/notifications/notifications.service';
import { EmptyStateComponent, PageComponent, TabsComponent, type Tab } from '../../ui';

/** What the three kinds E2 writes can be narrowed by, and nothing that has no rows behind it. */
type CategoryFilter = 'all' | 'unread' | 'lessons';

@Component({
  selector: 'hq-notifications-page',
  imports: [NgClass, DatePipe, PageComponent, TabsComponent, EmptyStateComponent, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-page [title]="'notifications.title' | transloco" [subtitle]="'notifications.subtitle' | transloco">
      <div class="em-dashboard">
        <!-- Top Toolbar Card -->
        <div class="em-toolbar-card">
          <hq-tabs
            variant="chips"
            [tabs]="tabs()"
            [selected]="selectedTab()"
            (selectedChange)="selectedTab.set($event)"
            [label]="'notifications.title' | transloco"
          />

          <div class="notifications__actions">
            @if (notificationsService.unreadCount() > 0) {
              <button
                type="button"
                class="em-btn-sm em-btn-sm--ghost"
                (click)="notificationsService.markAllRead()"
              >
                <svg viewBox="0 0 20 20" fill="currentColor" width="14" height="14">
                  <path
                    fill-rule="evenodd"
                    d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z"
                    clip-rule="evenodd"
                  />
                </svg>
                {{ 'notifications.markAllRead' | transloco }}
              </button>
            }
          </div>
        </div>

        <!-- Notification List -->
        @if (filteredItems().length === 0) {
          <hq-empty-state
            [message]="'notifications.empty' | transloco"
            [detail]="'notifications.emptyHint' | transloco"
          />
        } @else {
          <div class="notifications__list">
            @for (item of filteredItems(); track item.id) {
              <div
                class="em-card notification-card"
                [class.is-unread]="item.readAt === undefined"
                role="button"
                tabindex="0"
                (click)="onOpenItem(item)"
                (keydown.enter)="onOpenItem(item)"
              >
                <div class="notification-card__icon" [ngClass]="iconGradient(item.kind)">
                  @switch (item.kind) {
                    @case (Kind.LESSON_FAILED) {
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <circle cx="12" cy="12" r="10" />
                        <line x1="12" y1="8" x2="12" y2="12" />
                        <line x1="12" y1="16" x2="12.01" y2="16" />
                      </svg>
                    }
                    @case (Kind.LESSON_NEEDS_SKILLS) {
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
                        <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" />
                      </svg>
                    }
                    @default {
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14" />
                        <polyline points="22 4 12 14.01 9 11.01" />
                      </svg>
                    }
                  }
                </div>

                <div class="notification-card__body">
                  <div class="notification-card__header">
                    <h3 class="notification-card__title">{{ titleOf(item) }}</h3>
                    <div class="notification-card__meta">
                      @if (item.readAt === undefined) {
                        <span
                          class="notification-card__unread-dot"
                          [title]="'notifications.tabs.unread' | transloco"
                        ></span>
                      }
                      <span class="notification-card__time"
                        >{{ item.createdAt | date: 'mediumDate' }} ·
                        {{ item.createdAt | date: 'shortTime' }}</span
                      >
                    </div>
                  </div>
                  <p class="notification-card__message">{{ bodyOf(item) }}</p>
                </div>
              </div>
            }
          </div>
        }
      </div>
    </hq-page>
  `,
  styles: `
    .notifications__actions {
      display: flex;
      align-items: center;
      gap: 12px;
      margin-inline-start: auto;
    }

    .notifications__list {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }

    .notification-card {
      display: flex;
      align-items: flex-start;
      gap: 16px;
      padding: 18px 20px;
      cursor: pointer;
      transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
      border-radius: 16px;
      position: relative;

      &:hover {
        transform: translateY(-2px);
        box-shadow: 0 8px 24px -4px rgba(0, 0, 0, 0.08);
      }

      &.is-unread {
        border-inline-start: 4px solid var(--hq-color-accent, #3b82f6);
        background: rgba(59, 130, 246, 0.03);
      }
    }

    .notification-card__icon {
      inline-size: 42px;
      block-size: 42px;
      border-radius: 12px;
      display: grid;
      place-items: center;
      flex-shrink: 0;
      color: #fff;

      svg {
        inline-size: 20px;
        block-size: 20px;
      }
    }

    .notification-card__body {
      flex: 1;
      min-inline-size: 0;
    }

    .notification-card__header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      margin-block-end: 4px;
    }

    .notification-card__title {
      font-size: 15px;
      font-weight: 700;
      color: var(--hq-color-ink, #0f172a);
      margin: 0;
    }

    .notification-card__meta {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .notification-card__unread-dot {
      inline-size: 8px;
      block-size: 8px;
      border-radius: 50%;
      background: var(--hq-color-accent, #3b82f6);
      box-shadow: 0 0 0 2px rgba(59, 130, 246, 0.2);
    }

    .notification-card__time {
      font-size: 12px;
      color: var(--hq-color-ink-soft, #64748b);
      white-space: nowrap;
    }

    .notification-card__message {
      font-size: 13.5px;
      color: var(--hq-color-ink-soft, #475467);
      margin: 0;
      line-height: 1.5;
    }

    :host-context(html.dark) {
      .notification-card {
        background: var(--hq-color-surface, #171f2e) !important;
        border-color: rgba(255, 255, 255, 0.08) !important;

        &.is-unread {
          background: rgba(59, 130, 246, 0.08) !important;
          border-inline-start-color: #60a5fa !important;
        }

        &:hover {
          box-shadow: 0 8px 24px -4px rgba(0, 0, 0, 0.4) !important;
        }
      }

      .notification-card__title {
        color: var(--hq-color-ink, #f8fafc) !important;
      }

      .notification-card__message,
      .notification-card__time {
        color: var(--hq-color-ink-soft, #94a3b8) !important;
      }
    }
  `,
})
export class NotificationsPage {
  protected readonly notificationsService = inject(NotificationsService);
  private readonly router = inject(Router);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  protected readonly selectedTab = signal<CategoryFilter>('all');
  /** The template switches on the kind, and a generated enum does not compare to a literal. */
  protected readonly Kind = NotificationViewKindEnum;

  protected readonly tabs = computed<readonly Tab<CategoryFilter>[]>(() => {
    this.lang();
    const unread = this.notificationsService.unreadCount();
    return [
      { id: 'all', label: this.transloco.translate('notifications.tabs.all') },
      {
        id: 'unread',
        label: this.transloco.translate('notifications.tabs.unread'),
        badge: unread > 0 ? unread : undefined,
      },
      { id: 'lessons', label: this.transloco.translate('notifications.tabs.lessons') },
    ];
  });

  protected readonly filteredItems = computed(() => {
    const list = this.notificationsService.notifications();
    switch (this.selectedTab()) {
      case 'unread':
        return list.filter((item) => item.readAt === undefined);
      case 'lessons':
        return list.filter((item) => item.kind.startsWith('lesson.'));
      default:
        return list;
    }
  });

  constructor() {
    this.notificationsService.refresh();
  }

  /** Her language, from the kind; `title`/`body` off the wire are the English fallbacks. */
  protected titleOf(item: NotificationView): string {
    this.lang();
    const key = titleKeyOf(item.kind);
    const translated = this.transloco.translate<string>(key);
    return translated === key ? item.title : translated;
  }

  /** Except for a failure, whose body is the reason the pipeline gave. */
  protected bodyOf(item: NotificationView): string {
    this.lang();
    const key = bodyKeyOf(item.kind);
    if (item.kind === NotificationViewKindEnum.LESSON_FAILED)
      return item.body ?? this.transloco.translate<string>(key);
    const translated = this.transloco.translate<string>(key);
    return translated === key ? (item.body ?? '') : translated;
  }

  protected iconGradient(kind: NotificationView['kind']): string {
    switch (kind) {
      case NotificationViewKindEnum.LESSON_READY:
        return 'em-gradient--green';
      case NotificationViewKindEnum.LESSON_NEEDS_SKILLS:
        return 'em-gradient--blue';
      default:
        return 'em-gradient--orange';
    }
  }

  protected onOpenItem(item: NotificationView): void {
    this.notificationsService.markRead(item.id);
    if (item.link) void this.router.navigateByUrl(item.link);
  }
}
