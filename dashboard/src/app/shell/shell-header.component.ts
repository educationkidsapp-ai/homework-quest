import { CdkMenu, CdkMenuItem, CdkMenuTrigger } from '@angular/cdk/menu';
import { ChangeDetectionStrategy, Component, computed, inject, output, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { type NotificationView, NotificationViewKindEnum, SchoolSummary, SchoolsApi } from '../api';
import { AuthService } from '../core/auth/auth.service';
import { ChatService } from '../core/chat/chat.service';
import { SchoolScopeStore } from '../core/auth/school-scope.store';
import { FeatureDirective } from '../core/flags/feature.directive';
import { FLAGS, FlagService } from '../core/flags/flag.service';
import { activeLang } from '../core/i18n/active-lang';
import { LANGUAGES, LanguageService } from '../core/i18n/language.service';
import { SIDEBAR_ID, SidebarService } from '../core/shell/sidebar.service';
import { DarkModeService } from '../core/theme/dark-mode.service';
import { NotificationsService, bodyKeyOf, titleKeyOf } from '../core/notifications/notifications.service';
import { TourService } from '../core/tour/tour.service';
import { ViewModeService } from '../core/view-mode/view-mode.service';

/**
 * The bar above every screen (spec §2 "Header"): sticky, the raised surface, a 1 px rule under
 * it, `padding: 0 24px` outside and `16px 0` inside.
 *
 * Four controls and nothing else, because the brand moved to the sidebar's logo block where the
 * spec puts it:
 *
 * - the **rail control** on the inline-start edge — a burger under 1024 px, where it opens the
 *   drawer, and the collapse toggle above it. One button, because it answers one question ("show
 *   me the rail") and the viewport decides what that means;
 * - the **school switcher**, Admin-only and behind `multiSchool`. It is the whole of
 *   multi-tenancy in the UI: picking a school stores an id, the auth interceptor turns it into
 *   `X-School-Id`, and the flags, the theme and every screen follow;
 * - the **language switch** and the **scheme toggle**, both round icon buttons per §3's header
 *   variant. The scheme toggle keeps `data-hq-scheme-toggle` and `aria-pressed`, which is what
 *   the screenshot helper drives and what says "on" without a switch role;
 * - the **user dropdown** (§2 "Dropdown panel").
 *
 * The **View as** banner is not decoration. An impersonated session is read-only on the server
 * (`ReadOnlyGuard` refuses every non-GET) and audit-logged; without the banner an Admin would be
 * typing into a screen that will refuse to save, wondering why.
 *
 * Both menus are CDK menus rather than hand-rolled popups: the roving tab index, Esc, arrow keys,
 * `aria-expanded` and the focus return to the trigger are the platform's job, and this is exactly
 * what §0 allows the CDK for.
 */
@Component({
  selector: 'hq-shell-header',
  imports: [CdkMenu, CdkMenuItem, CdkMenuTrigger, FeatureDirective, RouterLink, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (auth.impersonatedBy(); as actor) {
      <p class="header__viewas" role="status">
        {{ 'shell.viewingAs' | transloco: { name: auth.displayName() } }}
        <span class="header__viewas-actor">{{ actor }}</span>
      </p>
    }

    <header class="header">
      <div class="header__row">
        <button
          type="button"
          class="header__icon"
          data-hq-sidebar-toggle
          [attr.aria-controls]="sidebarId"
          [attr.aria-label]="'shell.sidebar.toggle' | transloco"
          [attr.aria-expanded]="sidebar.expanded()"
          (click)="toggleSidebar($event)"
        >
          <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <path d="M4 7h16M4 12h16M4 17h16" />
          </svg>
        </button>

        <div class="header__search" role="search">
          <svg class="header__search-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <circle cx="11" cy="11" r="8" />
            <line x1="21" y1="21" x2="16.65" y2="16.65" />
          </svg>
          <input
            type="search"
            class="header__search-input"
            [placeholder]="'shell.searchPlaceholder' | transloco"
            [attr.aria-label]="'shell.searchLabel' | transloco"
          />
        </div>

        <div class="header__actions">
          @if (isAdmin()) {
            <button
              *hqFeature="'multiSchool'"
              type="button"
              class="header__pill"
              data-hq-tour="switcher"
              [cdkMenuTriggerFor]="schoolMenu"
              [attr.aria-label]="'shell.switcher.label' | transloco"
            >
              {{ scope.scope()?.name ?? ('shell.switcher.all' | transloco) }}
            </button>
          }
          <button
            type="button"
            class="header__icon header__bell-btn"
            [cdkMenuTriggerFor]="notificationsMenu"
            (cdkMenuOpened)="openNotifications()"
            [attr.aria-label]="'shell.notifications' | transloco"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
              <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9" />
              <path d="M13.73 21a2 2 0 0 1-3.46 0" />
            </svg>
            @if (unreadNotifications() > 0) {
              <span class="header__bell-badge">{{ unreadNotifications() }}</span>
            }
          </button>
          @if (isTeacher()) {
            <a
              *hqFeature="'chat'"
              routerLink="/teacher/chat"
              class="header__icon header__chat-btn"
              [attr.aria-label]="'nav.chat' | transloco"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
                <path
                  d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 0 1-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8Z"
                />
              </svg>
              @if (unreadChatCount() > 0) {
                <span class="header__chat-badge">{{ unreadChatCount() }}</span>
              }
            </a>
          }

          <button
            type="button"
            class="header__icon"
            data-hq-language
            [cdkMenuTriggerFor]="languageMenu"
            [attr.aria-label]="'shell.language.label' | transloco"
          >
            <span class="header__icon-text">{{ language.language() }}</span>
          </button>

          <!-- aria-pressed rather than a switch role: it is a button that is currently on. -->
          <button
            type="button"
            class="header__icon"
            data-hq-scheme-toggle
            [attr.aria-pressed]="darkMode.isDark()"
            [attr.aria-label]="'shell.darkMode' | transloco"
            (click)="darkMode.toggle()"
          >
            @if (darkMode.isDark()) {
              <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
                <circle cx="12" cy="12" r="4" />
                <path
                  d="M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M19.1 4.9l-1.4 1.4M6.3 17.7l-1.4 1.4"
                />
              </svg>
            } @else {
              <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
                <path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5Z" />
              </svg>
            }
          </button>

          <button type="button" class="header__user" data-hq-tour="profile" [cdkMenuTriggerFor]="profileMenu">
            <span class="header__avatar" aria-hidden="true">{{ monogram() }}</span>
            <span class="header__user-info">
              <span class="header__user-name">{{ auth.displayName() }}</span>
              <span class="header__user-role">{{ 'nav.label.' + (auth.role() ?? '') | transloco }}</span>
            </span>
            <svg class="header__chevron" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
              <path d="m7 10 5 5 5-5" />
            </svg>
          </button>
        </div>
      </div>
    </header>

    <ng-template #notificationsMenu>
      <div
        cdkMenu
        class="hq-menu hq-menu--notifications"
        [attr.aria-label]="'shell.notifications' | transloco"
      >
        <div class="notifications-popup__header">
          <span class="notifications-popup__title">{{ 'notifications.title' | transloco }}</span>
          @if (unreadNotifications() > 0) {
            <button
              type="button"
              class="notifications-popup__mark-read"
              (click)="notificationsService.markAllRead()"
            >
              {{ 'notifications.markAllRead' | transloco }}
            </button>
          }
        </div>
        <div class="notifications-popup__list">
          @for (item of recentNotifications(); track item.id) {
            <a
              cdkMenuItem
              class="notifications-popup__item"
              [class.is-unread]="item.readAt === undefined"
              [routerLink]="item.link ?? '/notifications'"
              (click)="notificationsService.markRead(item.id)"
            >
              <div class="notifications-popup__dot" [class.is-active]="item.readAt === undefined"></div>
              <div class="notifications-popup__content">
                <p class="notifications-popup__item-title">{{ notificationTitle(item) }}</p>
                <p class="notifications-popup__item-msg">{{ notificationBody(item) }}</p>
              </div>
            </a>
          } @empty {
            <p class="notifications-popup__empty">{{ 'notifications.empty' | transloco }}</p>
          }
        </div>
        <div class="notifications-popup__footer">
          <!-- E3: the browser's permission prompt, asked from a click and nowhere else. -->
          @if (canAskNotify()) {
            <button
              type="button"
              class="notifications-popup__mark-read"
              (click)="notificationsService.askPermission()"
            >
              {{ 'notifications.notifyMe' | transloco }}
            </button>
          }
          <a cdkMenuItem class="notifications-popup__view-all" routerLink="/notifications">
            {{ 'notifications.viewAll' | transloco }} &rarr;
          </a>
        </div>
      </div>
    </ng-template>

    <ng-template #schoolMenu>
      <div cdkMenu class="hq-menu" [attr.aria-label]="'shell.switcher.label' | transloco">
        <button type="button" cdkMenuItem class="hq-menu__item" (cdkMenuItemTriggered)="choose(null)">
          {{ 'shell.switcher.all' | transloco }}
        </button>
        @for (school of schools.value(); track school.id) {
          <button type="button" cdkMenuItem class="hq-menu__item" (cdkMenuItemTriggered)="choose(school)">
            {{ school.name }}
            <span class="hq-menu__hint">{{ school.code }}</span>
          </button>
        }
      </div>
    </ng-template>

    <ng-template #languageMenu>
      <div cdkMenu class="hq-menu" [attr.aria-label]="'shell.language.label' | transloco">
        @for (option of languages; track option) {
          <button
            type="button"
            cdkMenuItem
            class="hq-menu__item"
            [attr.aria-current]="option === language.language() ? 'true' : null"
            (cdkMenuItemTriggered)="language.use(option)"
          >
            {{ 'shell.language.' + option | transloco }}
          </button>
        }
      </div>
    </ng-template>

    <ng-template #profileMenu>
      <div cdkMenu class="hq-menu hq-menu--wide" [attr.aria-label]="'shell.profile.label' | transloco">
        <div class="hq-menu__head">
          <p class="hq-menu__head-name">{{ auth.displayName() }}</p>
          @if (auth.role(); as role) {
            <p class="hq-menu__head-meta">{{ 'nav.label.' + role | transloco }}</p>
          }
          @if (email(); as address) {
            <p class="hq-menu__head-meta">{{ address }}</p>
          }
        </div>
        <a cdkMenuItem class="hq-menu__item" routerLink="/profile" (cdkMenuItemTriggered)="openProfile()">{{
          'shell.profile.open' | transloco
        }}</a>
        <button type="button" cdkMenuItem class="hq-menu__item" (cdkMenuItemTriggered)="showMeAround()">
          {{ 'shell.showMeAround' | transloco }}
        </button>
        <!-- CR5: the raw surfaces, for the one role that is expected to read them. -->
        @if (viewMode.allowed()) {
          <button
            type="button"
            cdkMenuItem
            class="hq-menu__item"
            data-hq-view-mode
            [attr.aria-pressed]="viewMode.debug()"
            (cdkMenuItemTriggered)="viewMode.toggle()"
          >
            {{ 'shell.viewMode.' + viewMode.mode() | transloco }}
          </button>
        }
        <button
          type="button"
          cdkMenuItem
          class="hq-menu__item hq-menu__item--danger"
          (cdkMenuItemTriggered)="signOut.emit()"
        >
          {{ 'shell.signOut' | transloco }}
        </button>
      </div>
    </ng-template>
  `,
  styles: `
    @use 'mixins' as m;

    :host {
      display: block;
      position: sticky;
      inset-block-start: 0;
      z-index: var(--hq-z-header);
      background: var(--hq-color-surface-raised);
      border-block-end: var(--hq-size-rule-thin) solid var(--hq-color-rule);
    }

    .header__viewas {
      display: flex;
      gap: var(--hq-space-8);
      padding: var(--hq-space-8) var(--hq-space-header-inline);
      background: var(--hq-color-accent-soft);
      color: var(--hq-color-accent-on-soft);
      border-block-end: var(--hq-size-rule-thin) solid var(--hq-color-rule);
      font-size: var(--hq-text-theme-sm);
    }

    .header__viewas-actor {
      color: var(--hq-color-ink-soft);
    }

    .header {
      padding-inline: var(--hq-space-header-inline);
    }

    .header__row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--hq-space-16);
      padding-block: var(--hq-space-header-row);
    }

    .header__actions {
      display: flex;
      align-items: center;
      gap: var(--hq-space-8);
      min-inline-size: 0;
    }

    // §3 Button, icon variant — 44 × 44, and the round form the header takes.
    .header__icon {
      // The same spec row as the kit's btn--icon variant, at the radius §3 gives it in the
      // header. Not the component itself: cdkMenuTriggerFor and the suite's data-hq-* hooks
      // have to sit on the real button element, and hq-button renders its own inside.
      @include m.icon-button(var(--hq-radius-pill), var(--hq-color-surface-raised));
      cursor: pointer;
      @include m.motion-safe('background-color, color, border-color');
      @include m.focus-ring;

      svg {
        inline-size: var(--hq-size-icon-control);
        block-size: var(--hq-size-icon-control);
        fill: none;
        stroke: currentcolor;
        stroke-width: 1.8;
        stroke-linecap: round;
        stroke-linejoin: round;
      }

      &[aria-pressed='true'],
      &[aria-expanded='true'] {
        color: var(--hq-color-accent-on-soft);
        background: var(--hq-color-accent-soft);
      }
    }

    .header__chat-btn {
      position: relative;
      text-decoration: none;
    }

    .header__chat-badge {
      position: absolute;
      top: -2px;
      inset-inline-end: -2px;
      min-inline-size: 16px;
      block-size: 16px;
      padding-inline: 4px;
      border-radius: var(--hq-radius-pill);
      background: var(--hq-color-accent);
      color: #fff;
      font-size: 10px;
      font-weight: 700;
      display: grid;
      place-items: center;
      line-height: 1;
    }

    .header__bell-btn {
      position: relative;
    }

    .header__bell-badge {
      position: absolute;
      top: 4px;
      inset-inline-end: 4px;
      min-inline-size: 16px;
      block-size: 16px;
      padding: 0 4px;
      border-radius: var(--hq-radius-pill);
      background: var(--hq-color-error, #ef4444);
      color: #fff;
      font-size: 10px;
      font-weight: 700;
      display: grid;
      place-items: center;
      line-height: 1;
    }

    .hq-menu--notifications {
      inline-size: 320px;
      max-inline-size: 90vw;
      padding: 0;
      overflow: hidden;
    }

    .notifications-popup__header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 12px 16px;
      border-block-end: var(--hq-size-rule-thin) solid var(--hq-color-rule);
      background: var(--hq-color-surface-sunken);
    }

    .notifications-popup__title {
      font-size: 13px;
      font-weight: var(--hq-text-weight-bold);
      color: var(--hq-color-ink);
    }

    .notifications-popup__mark-read {
      background: none;
      border: none;
      font-size: 11px;
      font-weight: 600;
      color: var(--hq-color-accent);
      cursor: pointer;
      padding: 2px 4px;
      border-radius: 4px;

      &:hover {
        text-decoration: underline;
      }
    }

    .notifications-popup__list {
      max-block-size: 280px;
      overflow-y: auto;
    }

    .notifications-popup__item {
      display: flex;
      align-items: flex-start;
      gap: 10px;
      padding: 10px 14px;
      text-decoration: none;
      color: inherit;
      border-block-end: var(--hq-size-rule-thin) solid var(--hq-color-rule);
      transition: background-color 0.15s ease;

      &:hover {
        background: var(--hq-color-hover);
      }

      &.is-unread {
        background: var(--hq-color-accent-soft);
      }
    }

    .notifications-popup__dot {
      inline-size: 8px;
      block-size: 8px;
      border-radius: 50%;
      background: transparent;
      margin-block-start: 5px;
      flex-shrink: 0;

      &.is-active {
        background: var(--hq-color-accent);
      }
    }

    .notifications-popup__content {
      flex: 1;
      min-inline-size: 0;
    }

    .notifications-popup__item-title {
      font-size: 12px;
      font-weight: 600;
      color: var(--hq-color-ink);
      margin: 0 0 2px;
      line-height: 1.3;
    }

    .notifications-popup__item-msg {
      font-size: 11.5px;
      color: var(--hq-color-ink-soft);
      margin: 0;
      line-height: 1.35;
      display: -webkit-box;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
      overflow: hidden;
    }

    .notifications-popup__empty {
      padding: 16px;
      margin: 0;
      font-size: 13px;
      color: var(--hq-color-ink-soft);
      text-align: center;
    }

    .notifications-popup__footer {
      padding: 8px 14px;
      text-align: center;
      background: var(--hq-color-surface-sunken);
    }

    .notifications-popup__view-all {
      font-size: 12px;
      font-weight: 600;
      color: var(--hq-color-accent);
      text-decoration: none;
      display: block;
      padding: 4px;

      &:hover {
        text-decoration: underline;
      }
    }

    .header__search {
      display: flex;
      align-items: center;
      position: relative;
      flex: 1;
      max-inline-size: 440px;
      margin-inline-start: var(--hq-space-12);

      @include m.below(m.$compact-breakpoint) {
        display: none;
      }
    }

    .header__search-icon {
      position: absolute;
      inset-inline-start: 14px;
      inline-size: 16px;
      block-size: 16px;
      color: var(--hq-color-ink-soft);
      pointer-events: none;
      stroke: currentcolor;
      stroke-width: 2;
      fill: none;
    }

    .header__search-input {
      inline-size: 100%;
      block-size: 40px;
      padding-inline-start: 38px;
      padding-inline-end: 16px;
      border-radius: var(--hq-radius-pill);
      border: 1px solid var(--hq-color-rule, #e2e8f0);
      background: var(--hq-color-surface-sunken, #f8fafc);
      color: var(--hq-color-ink);
      font-size: var(--hq-text-theme-sm);
      outline: none;
      @include m.motion-safe('border-color, background-color, box-shadow');

      &:focus {
        border-color: var(--hq-color-accent, #6366f1);
        background: var(--hq-color-surface-raised, #ffffff);
        box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.12);
      }

      &::placeholder {
        color: var(--hq-color-ink-soft, #94a3b8);
      }
    }

    // The language switch says which language it is on rather than drawing a globe nobody can
    // read a language off. Upper-cased by CSS so 'ar'/'en' stay the codes the service uses.
    .header__icon-text {
      font-size: var(--hq-text-theme-2xs);
      line-height: calc(var(--hq-text-theme-2xs-line) / var(--hq-text-theme-2xs));
      font-weight: var(--hq-text-weight-semibold);
      text-transform: uppercase;
      letter-spacing: 0.02em;
    }

    .header__pill {
      min-block-size: var(--hq-size-control-height);
      padding: var(--hq-space-button);
      border: var(--hq-size-rule-thin) solid var(--hq-color-control-rule);
      border-radius: var(--hq-radius-control);
      background: var(--hq-color-surface-raised);
      color: var(--hq-color-ink-strong);
      font-size: var(--hq-text-theme-sm);
      font-weight: var(--hq-text-weight-medium);
      box-shadow: var(--hq-shadow-xs);
      cursor: pointer;
      @include m.motion-safe('background-color, color');
      @include m.focus-ring;

      &:hover {
        background: var(--hq-color-gray-50);
        color: var(--hq-color-ink);
      }
    }

    .header__user {
      display: inline-flex;
      align-items: center;
      gap: var(--hq-space-8);
      min-block-size: var(--hq-size-control-height);
      min-inline-size: 0;
      padding-inline: var(--hq-space-8);
      border: var(--hq-size-rule-thin) solid transparent;
      border-radius: var(--hq-radius-pill);
      background: none;
      color: var(--hq-color-ink);
      font-size: var(--hq-text-theme-sm);
      font-weight: var(--hq-text-weight-medium);
      cursor: pointer;
      @include m.motion-safe('background-color, border-color');
      @include m.focus-ring;

      &:hover,
      &[aria-expanded='true'] {
        background: var(--hq-color-hover);
        border-color: var(--hq-color-rule);
      }
    }

    .header__avatar {
      display: grid;
      place-items: center;
      inline-size: var(--hq-size-icon-nav);
      block-size: var(--hq-size-icon-nav);
      flex: none;
      border-radius: var(--hq-radius-pill);
      background: var(--hq-color-accent-soft);
      color: var(--hq-color-accent-on-soft);
      font-size: var(--hq-text-theme-2xs);
      font-weight: var(--hq-text-weight-semibold);
    }

    .header__user-info {
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      line-height: 1.2;
    }

    .header__user-name {
      overflow: hidden;
      white-space: nowrap;
      text-overflow: ellipsis;
      font-weight: var(--hq-text-weight-semibold);
    }

    .header__user-role {
      font-size: 11px;
      color: var(--hq-color-ink-soft);
      font-weight: var(--hq-text-weight-normal);
    }

    .header__chevron {
      inline-size: var(--hq-size-icon-button);
      block-size: var(--hq-size-icon-button);
      flex: none;
      fill: none;
      stroke: currentcolor;
      stroke-width: 1.8;
      stroke-linecap: round;
      stroke-linejoin: round;
      color: var(--hq-color-ink-soft);
    }

    // The name and the chevron are the first things to go on a phone; the avatar and the menu
    // behind it are the affordance, and the name is the first line of the panel anyway.
    @include m.below(m.$compact-breakpoint) {
      .header__user-name,
      .header__chevron {
        display: none;
      }
    }
  `,
})
export class ShellHeaderComponent {
  private readonly router = inject(Router);
  private readonly schoolsApi = inject(SchoolsApi);
  private readonly flags = inject(FlagService);
  private readonly tour = inject(TourService);
  private readonly chatService = inject(ChatService);

  protected readonly scope = inject(SchoolScopeStore);
  protected readonly darkMode = inject(DarkModeService);
  protected readonly sidebar = inject(SidebarService);
  protected readonly sidebarId = SIDEBAR_ID;
  protected readonly auth = inject(AuthService);
  protected readonly language = inject(LanguageService);
  protected readonly viewMode = inject(ViewModeService);
  protected readonly languages = LANGUAGES;

  protected readonly isAdmin = computed(() => this.auth.role() === 'ADMIN');
  protected readonly isTeacher = computed(() => this.auth.role() === 'TEACHER');
  protected readonly unreadChatCount = computed(() => this.chatService.totalUnread());
  protected readonly notificationsService = inject(NotificationsService);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();
  protected readonly unreadNotifications = this.notificationsService.unreadCount;
  protected readonly recentNotifications = this.notificationsService.recent;
  protected readonly canAskNotify = signal(this.notificationsService.canAskPermission());

  /**
   * The copy is the *kind*'s, in her language; `title`/`body` off the wire are the English
   * fallbacks the server wrote. `lesson.failed` is the exception — its body is the reason the
   * pipeline gave, and no translation of ours could say it.
   */
  /** The rows are fetched when the bell is opened, not on every page the shell draws. */
  protected openNotifications(): void {
    this.notificationsService.refresh();
    this.canAskNotify.set(this.notificationsService.canAskPermission());
  }

  protected notificationTitle(item: NotificationView): string {
    this.lang();
    const translated = this.transloco.translate<string>(titleKeyOf(item.kind));
    return translated === titleKeyOf(item.kind) ? item.title : translated;
  }

  protected notificationBody(item: NotificationView): string {
    this.lang();
    if (item.kind === NotificationViewKindEnum.LESSON_FAILED)
      return item.body ?? this.transloco.translate<string>(bodyKeyOf(item.kind));
    const translated = this.transloco.translate<string>(bodyKeyOf(item.kind));
    return translated === bodyKeyOf(item.kind) ? (item.body ?? '') : translated;
  }
  protected readonly email = computed(() => this.auth.user()?.email ?? '');
  /** `[...name]` rather than `name[0]`: an Arabic first character is not one UTF-16 unit. */
  protected readonly monogram = computed(() => [...this.auth.displayName().trim()][0] ?? '');

  /**
   * Only an Admin may list schools, and only an Admin has a switcher — so the request is
   * made once, when the menu is first about to be useful, and not at all for anyone else.
   *
   * D13: with `multiSchool` off the switcher is not rendered at all, so there is nothing to
   * fill and the request is not made either. Hiding the button while still asking the server
   * for a list nobody can see is the kind of thing that survives a review and shows up in the
   * access log.
   */
  protected readonly schools = rxResource<readonly SchoolSummary[], boolean | undefined>({
    params: () => (this.isAdmin() && this.flags.isOn(FLAGS.multiSchool) ? true : undefined),
    stream: () => this.schoolsApi.listSchools().pipe(catchError(() => of<SchoolSummary[]>([]))),
    defaultValue: [],
  });

  /** Emitted rather than handled here: signing out is the shell's business, not the header's. */
  readonly signOut = output<void>();

  /**
   * The button is handed to the service so that closing the drawer — by Escape, by the scrim or
   * by a navigation — can put focus back where it came from.
   */
  protected toggleSidebar(event: Event): void {
    this.sidebar.toggle(event.currentTarget as HTMLElement | null);
  }

  protected choose(school: SchoolSummary | null): void {
    if (school?.id && school.name) this.scope.select({ id: school.id, name: school.name });
    else this.scope.select(null);
  }

  protected showMeAround(): void {
    const role = this.auth.role();
    if (role) this.tour.start(role);
  }

  protected openProfile(): void {
    void this.router.navigateByUrl('/profile');
  }
}
