/* hq-flag: none (shell) — every role's Home. A flag can empty a section of it; it cannot
   take away the screen `/` redirects to. */
import { NgClass, NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { HomeApi, HomeResponse, NeedsYouItem, TeacherClassInfo } from '../../api';
import { AuthService } from '../../core/auth/auth.service';
import { activeLang } from '../../core/i18n/active-lang';
import { PermissionService } from '../../core/permissions/permission.service';
import { ThemeService } from '../../core/theme/theme.service';
import { BandComponent, ButtonComponent, CountUpDirective, PageComponent, SkeletonComponent } from '../../ui';

/**
 * MA2: the Admin's count → the screen behind it, with the key that screen's row is gated by.
 *
 * The same six rows `core/nav/screens.ts` declares. Listed again here only as `key → path`, which
 * is the one thing the table cannot answer: a server card's key is not a screen id.
 */
const ADMIN_CARD_LINKS: Readonly<Record<string, { readonly path: string; readonly key: string }>> = {
  managers: { path: '/admin/managers', key: 'manager.manage' },
  coordinators: { path: '/admin/coordinators', key: 'coordinator.manage' },
  teachers: { path: '/admin/teachers', key: 'teacher.read' },
  children: { path: '/admin/children', key: 'admin.children.read' },
  classes: { path: '/admin/classes', key: 'section.read' },
  workers: { path: '/admin/workers', key: 'worker.read' },
};

interface QuickActionItem {
  readonly label: string;
  readonly icon: string;
  readonly link: string;
  readonly queryParams?: Record<string, string>;
  readonly color: string;
  readonly bgColor: string;
}

/**
 * Home, for an Admin and a teacher.
 *
 * The layout came from a comp, and so — until RM3a — did a good deal of its content: a weekly
 * attendance chart, a staff directory with telephone numbers no table in this system holds,
 * three named "schedule gaps", two exam counters and a "+12.5 % vs last month" on every stat
 * card. None of it was read from anywhere. All of it is gone rather than translated: a Home is
 * the screen people trust most, and a number invented on it is worse than a number absent from
 * it. What is left is `GET /me/home` — the counts, the teacher's classes, her weakest skills,
 * and what needs somebody.
 *
 * The manager's Home is no longer this screen at all: DR5's department statistics are
 * `features/management/management-home.page.ts`.
 */
@Component({
  selector: 'hq-home-page',
  imports: [
    NgClass,
    NgTemplateOutlet,
    PageComponent,
    CountUpDirective,
    SkeletonComponent,
    BandComponent,
    ButtonComponent,
    RouterLink,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-page [title]="greeting()" [subtitle]="isTeacher() ? ('home.teacherSubtitle' | transloco) : (home.value()?.schoolName ?? null)">
      @if (logoUrl(); as logo) {
        <img page-actions class="home__logo" [src]="logo" alt="" />
      }

      @if (home.isLoading()) {
        <hq-skeleton
          [loading]="true"
          [lines]="6"
          height="var(--hq-space-48)"
          [label]="'home.loading' | transloco"
        />
      } @else if (home.error()) {
        <div class="home__error">
          <hq-band
            variant="error"
            [open]="true"
            [title]="'band.failed' | transloco"
            [dismissible]="false"
          >
            {{ 'band.unreachable' | transloco }}
          </hq-band>
          <div class="home__error-action">
            <hq-button variant="secondary" (pressed)="home.reload()">
              {{ 'ui.retry' | transloco }}
            </hq-button>
          </div>
        </div>
      } @else {
        <div class="em-dashboard">
          <!-- =========== 1. The counts, each a door to the screen behind it =========== -->
          <section class="em-stats-grid" [attr.aria-label]="'home.cardsLabel' | transloco" data-hq-tour="cards">
            @for (card of cards(); track card.key) {
              @if (cardLink(card.key); as link) {
                <a class="em-stat-card em-stat-card--link" [routerLink]="link" data-hq-card-link>
                  <ng-container
                    [ngTemplateOutlet]="statCard"
                    [ngTemplateOutletContext]="{ $implicit: card }"
                  />
                </a>
              } @else {
                <div class="em-stat-card">
                  <ng-container
                    [ngTemplateOutlet]="statCard"
                    [ngTemplateOutletContext]="{ $implicit: card }"
                  />
                </div>
              }
            }
          </section>

          <!-- MA2: one card body, drawn inside a link when the count has a screen of its own
               (managers, coordinators, teachers, children, classes, workers) and inside a plain
               div when it has none — a card that looks clickable and is not is worse than a card
               that plainly is not. -->
          <ng-template #statCard let-card>
            <div class="em-stat-card__body">
                <div class="em-stat-info">
                  <p class="em-stat-label">{{ cardLabel(card.key) }}</p>
                  <h3 class="em-stat-value"><span [hqCountUp]="card.value"></span></h3>
                </div>
                <div class="em-stat-icon-tile" [ngClass]="statIconGradient(card.key)">
                  @switch (card.key) {
                    @case ('playedYesterday') {
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <polygon points="6 3 20 12 6 21 6 3" />
                      </svg>
                    }
                    @case ('lessonsThisWeek') {
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <rect x="3" y="4" width="18" height="18" rx="2" />
                        <path d="M16 2v4M8 2v4M3 10h18" />
                      </svg>
                    }
                    @case ('needsReview') {
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M12 8v4m0 4h.01M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0z" />
                      </svg>
                    }
                    @case ('schools') {
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M3 21h18M3 7l9-4 9 4v14M9 21V9m6 12V9" />
                      </svg>
                    }
                    @case ('children') {
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" /><circle cx="9" cy="7" r="4" />
                        <path d="M23 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75" />
                      </svg>
                    }
                    @case ('teachers') {
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M22 10L12 5 2 10l10 5 10-5z" /><path d="M6 12v5c0 1 2.7 2.5 6 2.5s6-1.5 6-2.5v-5" />
                      </svg>
                    }
                    @case ('coordinators') {
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M9 3h6v3H9zM6 5h12v16H6z" /><polyline points="9 13 11 15 15 11" />
                      </svg>
                    }
                    @case ('managers') {
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <rect x="2" y="7" width="20" height="14" rx="2" /><path d="M9 7V4h6v3" /><path d="M2 13h20" />
                      </svg>
                    }
                    @case ('classes') {
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <rect x="3" y="3" width="7" height="7" /><rect x="14" y="3" width="7" height="7" />
                        <rect x="3" y="14" width="7" height="7" /><rect x="14" y="14" width="7" height="7" />
                      </svg>
                    }
                    @case ('workers') {
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M14.7 6.3a4 4 0 1 0 3 3l-8.4 8.4a2.1 2.1 0 1 1-3-3z" />
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
            </div>
          </ng-template>

          <!-- ================= 2. Quick Actions Panel ================= -->
          <section class="em-card em-quick-actions-card" [attr.aria-label]="'home.quickActions.title' | transloco">
            <div class="em-card-header">
              <div>
                <h2 class="em-card-title">{{ 'home.quickActions.title' | transloco }}</h2>
                <p class="em-card-subtitle">{{ 'home.quickActions.subtitle' | transloco }}</p>
              </div>
            </div>
            <div class="em-quick-actions-grid">
              @for (act of quickActions(); track act.link) {
                <a
                  class="em-quick-action-btn"
                  [ngClass]="act.bgColor"
                  [routerLink]="act.link"
                  [queryParams]="act.queryParams"
                >
                  <div class="em-quick-action-icon" [ngClass]="act.color">
                    @switch (act.icon) {
                      @case ('user-plus') {
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                          <path d="M16 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" /><circle cx="8.5" cy="7" r="4" />
                          <line x1="20" y1="8" x2="20" y2="14" /><line x1="23" y1="11" x2="17" y2="11" />
                        </svg>
                      }
                      @case ('file-text') {
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                          <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                          <polyline points="14 2 14 8 20 8" /><line x1="16" y1="13" x2="8" y2="13" /><line x1="16" y1="17" x2="8" y2="17" />
                        </svg>
                      }
                      @case ('calendar') {
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                          <rect x="3" y="4" width="18" height="18" rx="2" /><line x1="16" y1="2" x2="16" y2="6" /><line x1="8" y1="2" x2="8" y2="6" /><line x1="3" y1="10" x2="21" y2="10" />
                        </svg>
                      }
                      @case ('bell') {
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                          <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9" /><path d="M13.73 21a2 2 0 0 1-3.46 0" />
                        </svg>
                      }
                      @case ('mail') {
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                          <path d="M4 4h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V6c0-1.1.9-2 2-2z" /><polyline points="22,6 12,13 2,6" />
                        </svg>
                      }
                      @default {
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                          <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" /><polyline points="7 10 12 15 17 10" /><line x1="12" y1="15" x2="12" y2="3" />
                        </svg>
                      }
                    }
                  </div>
                  <span class="em-quick-action-label">{{ act.label }}</span>
                </a>
              }
            </div>
          </section>

          <!-- The teacher's own classes, from GET /me/home. Everything that used to sit
               around them — a weekly attendance chart, a staff directory with telephone
               numbers no table in this system holds, three named "schedule gaps" and two exam
               counters — was the comp's sample data: hard-coded, identical for every school and
               every role. RM3a took it out rather than translate it. The real versions live on
               the screens that read them: the register, This week, the exams tab, and DR5's
               department statistics on the manager's own Home. -->
          <section class="em-card">
            <div class="em-card-header">
              <div>
                <h2 class="em-card-title">{{ 'home.myClasses' | transloco }}</h2>
                <p class="em-card-subtitle">{{ 'home.activeClasses' | transloco }}</p>
              </div>
            </div>

                @if (classes(); as teacherClasses) {
                  @if (teacherClasses.length > 0) {
                    <div class="em-list-group">
                      @for (klass of teacherClasses; track klass.classId) {
                        <div class="em-list-item">
                          <div class="em-item-avatar em-gradient--blue">
                            G{{ klass.grade ?? 1 }}
                          </div>
                          <div class="em-item-details">
                            <h3 class="em-item-title">{{ classTitle(klass) }}</h3>
                            <p class="em-item-subtitle">{{ classStatus(klass) }}</p>
                          </div>
                          <div class="em-item-actions">
                            <a
                              class="em-btn-sm em-btn-sm--ghost"
                              [routerLink]="['/teacher/classes', klass.classId]"
                              [queryParams]="{tab: 'attendance'}"
                            >
                              <svg viewBox="0 0 20 20" fill="currentColor" width="14" height="14">
                                <path fill-rule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clip-rule="evenodd" />
                              </svg>
                              {{ 'home.takeAttendance' | transloco }}
                            </a>
                            @if (!klass.todayLessonId) {
                              <a
                                class="em-btn-sm em-btn-sm--primary"
                                [routerLink]="'/teacher/lessons/new'"
                                [queryParams]="newLessonParams(klass)"
                              >
                                {{ 'home.addToday' | transloco }}
                              </a>
                            }
                          </div>
                        </div>
                      }
                    </div>
                  }
                }

          </section>

          <!-- Weak Skills section (preserved for curriculum diagnostics) -->
              @if (weakSkills(); as skills) {
                @if (skills.length > 0) {
                  <section class="em-card">
                    <div class="em-card-header">
                      <div>
                        <h2 class="em-card-title">{{ 'home.weakSkills' | transloco }}</h2>
                      </div>
                      <span class="em-badge-count">{{ skills.length }}</span>
                    </div>
                    <div class="em-list-group">
                      @for (skill of skills; track skill.skillId) {
                        <div class="em-list-item">
                          <span class="em-item-title">{{ skill.name }}</span>
                          <span
                            class="em-pill"
                            [class.em-pill--leave]="skill.band === 'NEEDS_ANOTHER_LOOK'"
                            [class.em-pill--danger]="skill.band === 'GETTING_THERE'"
                            [class.em-pill--present]="skill.band === 'GOING_WELL'"
                          >
                            {{ 'band.level.' + skill.band | transloco }}
                          </span>
                        </div>
                      }
                    </div>
                  </section>
                }
              }

          <!-- Needs Attention for Admin / Managerial -->
              @if (needsYou().length > 0) {
                <section class="em-card">
                  <div class="em-card-header">
                    <h2 class="em-card-title">{{ 'home.needsYou' | transloco }}</h2>
                  </div>
                  <div class="em-list-group">
                    @for (item of needsYou(); track item.text) {
                      <div class="em-list-item">
                        <span class="em-item-title">{{ item.text }}</span>
                      </div>
                    }
                  </div>
                </section>
              }
        </div>
      }
    </hq-page>
  `,
  styles: `
    @use 'mixins' as m;

    .home__logo {
      inline-size: 32px;
      block-size: 32px;
      object-fit: contain;
    }

    .home__error {
      display: flex;
      flex-direction: column;
      gap: 16px;
    }

    .home__error-action {
      display: flex;
      justify-content: flex-start;
    }

    // --- EduManage Dashboard Outer ---
    .em-dashboard {
      display: flex;
      flex-direction: column;
      gap: 24px;
    }

    // --- 1. Hero Stats Grid ---
    .em-stats-grid {
      display: grid;
      grid-template-columns: repeat(4, 1fr);
      gap: 20px;

      @include m.below(1024px) {
        grid-template-columns: repeat(2, 1fr);
      }
      @include m.below(600px) {
        grid-template-columns: 1fr;
      }
    }

    /* MA2: the card's own layout moved one level in, so the same body draws inside a link. */
    .em-stat-card__body {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: var(--hq-space-16);
      inline-size: 100%;
    }

    .em-stat-card--link {
      text-decoration: none;
      color: inherit;
    }

    .em-stat-card {
      background: #ffffff;
      border-radius: 24px;
      padding: 22px;
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      border: 1px solid #f1f5f9;
      box-shadow: 0 4px 16px -2px rgba(0, 0, 0, 0.04);
      transition: transform 0.15s ease, box-shadow 0.15s ease;

      &:hover {
        transform: translateY(-2px);
        box-shadow: 0 10px 25px -4px rgba(0, 0, 0, 0.08);
      }
    }

    .em-stat-info {
      display: flex;
      flex-direction: column;
      gap: 6px;
    }

    .em-stat-label {
      font-size: 13px;
      font-weight: 500;
      color: #64748b;
      margin: 0;
    }

    .em-stat-value {
      font-size: 26px;
      font-weight: 700;
      color: #1e293b;
      margin: 0;
      line-height: 1.2;
    }

    .em-stat-icon-tile {
      inline-size: 46px;
      block-size: 46px;
      border-radius: 16px;
      display: grid;
      place-items: center;
      color: #ffffff;
      box-shadow: 0 6px 14px -2px rgba(0, 0, 0, 0.12);
      flex-shrink: 0;

      svg {
        inline-size: 22px;
        block-size: 22px;
      }
    }

    // Gradients
    .em-gradient--blue {
      background: linear-gradient(135deg, #60a5fa 0%, #2563eb 100%);
    }
    .em-gradient--purple {
      background: linear-gradient(135deg, #c084fc 0%, #9333ea 100%);
    }
    .em-gradient--pink {
      background: linear-gradient(135deg, #f472b6 0%, #db2777 100%);
    }
    .em-gradient--green {
      background: linear-gradient(135deg, #4ade80 0%, #16a34a 100%);
    }
    .em-gradient--orange {
      background: linear-gradient(135deg, #fb923c 0%, #ea580c 100%);
    }
    .em-gradient--cyan {
      background: linear-gradient(135deg, #22d3ee 0%, #0891b2 100%);
    }

    // --- 2. Card Container ---
    .em-card {
      background: #ffffff;
      border-radius: 24px;
      padding: 24px;
      border: 1px solid #f1f5f9;
      box-shadow: 0 4px 16px -2px rgba(0, 0, 0, 0.04);
      display: flex;
      flex-direction: column;
      gap: 18px;
    }

    .em-card-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
    }

    .em-card-title {
      font-size: 17px;
      font-weight: 700;
      color: #1e293b;
      margin: 0 0 2px 0;
    }

    .em-card-subtitle {
      font-size: 13px;
      color: #64748b;
      margin: 0;
    }

    .em-badge-count {
      font-size: 12px;
      font-weight: 600;
      padding: 3px 10px;
      border-radius: 999px;
      background: #fef3c7;
      color: #b45309;
    }

    // --- Quick Actions Grid ---
    .em-quick-actions-grid {
      display: grid;
      grid-template-columns: repeat(6, 1fr);
      gap: 16px;

      @include m.below(1100px) {
        grid-template-columns: repeat(3, 1fr);
      }
      @include m.below(600px) {
        grid-template-columns: repeat(2, 1fr);
      }
    }

    .em-quick-action-btn {
      padding: 18px 12px;
      border-radius: 18px;
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 10px;
      text-decoration: none;
      transition: transform 0.15s ease, box-shadow 0.15s ease;
      cursor: pointer;

      &:hover {
        transform: translateY(-3px);
        box-shadow: 0 8px 16px -4px rgba(0, 0, 0, 0.08);
      }
    }

    .em-bg--blue { background: #eff6ff; }
    .em-bg--purple { background: #faf5ff; }
    .em-bg--pink { background: #fdf2f8; }
    .em-bg--green { background: #f0fdf4; }
    .em-bg--orange { background: #fff7ed; }
    .em-bg--cyan { background: #ecfeff; }

    .em-quick-action-icon {
      inline-size: 44px;
      block-size: 44px;
      border-radius: 14px;
      display: grid;
      place-items: center;
      color: #ffffff;
      box-shadow: 0 4px 10px -2px rgba(0, 0, 0, 0.15);

      svg {
        inline-size: 20px;
        block-size: 20px;
      }
    }

    .em-quick-action-label {
      font-size: 12px;
      font-weight: 600;
      color: #334155;
      text-align: center;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
      max-inline-size: 100%;
    }

    // List Group (Staff / Classes)
    .em-list-group {
      display: flex;
      flex-direction: column;
      gap: 10px;
    }

    .em-list-item {
      display: flex;
      align-items: center;
      gap: 14px;
      padding: 12px 14px;
      border-radius: 16px;
      background: #f8fafc;
      transition: background-color 0.15s ease;

      &:hover {
        background: #f1f5f9;
      }
    }

    .em-item-avatar {
      inline-size: 42px;
      block-size: 42px;
      border-radius: 14px;
      display: grid;
      place-items: center;
      color: #ffffff;
      font-size: 13px;
      font-weight: 700;
      flex-shrink: 0;
    }

    .em-item-details {
      flex: 1;
      min-inline-size: 0;
    }

    .em-item-title {
      font-size: 14px;
      font-weight: 600;
      color: #1e293b;
      margin: 0 0 2px 0;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .em-item-subtitle {
      font-size: 12px;
      color: #64748b;
      margin: 0;
    }

    .em-item-actions {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .em-btn-sm {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 6px 12px;
      border-radius: 10px;
      font-size: 12px;
      font-weight: 600;
      text-decoration: none;
      cursor: pointer;

      &--ghost {
        background: #ffffff;
        color: #2563eb;
        border: 1px solid #e2e8f0;
        &:hover { background: #eff6ff; }
      }

      &--primary {
        background: #2563eb;
        color: #ffffff;
        border: 0;
        &:hover { background: #1d4ed8; }
      }
    }

    // Pills
    .em-pill {
      font-size: 11px;
      font-weight: 600;
      padding: 3px 10px;
      border-radius: 999px;

      &--present {
        background: #dcfce7;
        color: #15803d;
      }
      &--leave {
        background: #ffedd5;
        color: #c2410c;
      }
      &--danger {
        background: #fee2e2;
        color: #b91c1c;
      }
    }
  `,
})
export class HomePage {
  protected readonly auth = inject(AuthService);
  protected readonly transloco = inject(TranslocoService);
  protected readonly theme = inject(ThemeService);
  private readonly api = inject(HomeApi);
  private readonly permissions = inject(PermissionService);

  private readonly lang = activeLang();

  protected readonly isTeacher = computed(() => this.auth.role() === 'TEACHER');

  protected readonly home = rxResource<HomeResponse, string | undefined>({
    params: () => (this.auth.signedIn() ? (this.auth.effectiveSchoolId() ?? 'all') : undefined),
    stream: () => this.api.home(),
  });

  protected readonly logoUrl = computed(() => this.home.value()?.schoolLogoUrl || this.theme.logoUrl() || '');

  protected readonly cards = computed(() =>
    (this.home.value()?.cards ?? []).map((card) => ({ key: card.key ?? '', value: card.value ?? 0 })),
  );

  protected readonly classes = computed<readonly TeacherClassInfo[] | null>(
    () => this.home.value()?.classes ?? null,
  );

  protected readonly weakSkills = computed(() => this.home.value()?.weakSkills ?? null);

  /**
   * MA0: the panel is the Admin's way in too, and until now it offered her six of the *teacher's*
   * links. Her own three are the rail rows she keeps plus the new-lesson wizard — which is why
   * this had to change in the same package that retired All lessons: the wizard's only door was
   * that list's primary button, and taking the list away without this would have left her no way
   * to author a lesson at all.
   *
   * Filtered by `can()`, so a read-only "View as" session is offered no New lesson (it is a write
   * key) and a row whose key she somehow lacks is absent rather than a link onto a guard.
   */
  protected readonly quickActions = computed<readonly QuickActionItem[]>(() =>
    this.isTeacher() ? this.teacherActions : this.adminActions(),
  );

  private readonly adminActions = computed<readonly QuickActionItem[]>(() => {
    this.lang();
    const rows: readonly (QuickActionItem & { readonly key: string })[] = [
      {
        key: 'section.read',
        label: this.t('nav.classes'),
        icon: 'calendar',
        link: '/admin/classes',
        color: 'em-gradient--blue',
        bgColor: 'em-bg--blue',
      },
      {
        key: 'teacher.read',
        label: this.t('nav.teachers'),
        icon: 'user-plus',
        link: '/admin/teachers',
        color: 'em-gradient--purple',
        bgColor: 'em-bg--purple',
      },
      {
        key: 'lesson.write',
        label: this.t('nav.newLesson'),
        icon: 'file-text',
        link: '/admin/lessons/new',
        color: 'em-gradient--pink',
        bgColor: 'em-bg--pink',
      },
    ];
    return rows.filter((row) => this.permissions.can(row.key));
  });

  // EduManage Quick Actions — strictly Homework Quest teacher workflows
  private readonly teacherActions: readonly QuickActionItem[] = [
    {
      label: 'This Week',
      icon: 'calendar',
      link: '/teacher/week',
      color: 'em-gradient--blue',
      bgColor: 'em-bg--blue',
    },
    {
      label: 'My Classes',
      icon: 'user-plus',
      link: '/teacher/classes',
      color: 'em-gradient--purple',
      bgColor: 'em-bg--purple',
    },
    {
      label: 'New Lesson',
      icon: 'file-text',
      link: '/teacher/lessons/new',
      color: 'em-gradient--pink',
      bgColor: 'em-bg--pink',
    },
    {
      label: 'Create Exam',
      icon: 'download',
      link: '/teacher/exams/new',
      color: 'em-gradient--cyan',
      bgColor: 'em-bg--cyan',
    },
    {
      label: 'Attendance',
      icon: 'bell',
      link: '/teacher/classes',
      queryParams: { tab: 'attendance' },
      color: 'em-gradient--green',
      bgColor: 'em-bg--green',
    },
    {
      label: 'Parent Chat',
      icon: 'mail',
      link: '/teacher/chat',
      color: 'em-gradient--orange',
      bgColor: 'em-bg--orange',
    },
  ];

  protected readonly greeting = computed(() => {
    this.lang();
    const name = this.home.value()?.displayName ?? this.auth.displayName();
    return this.transloco.translate<string>('home.greeting', { name });
  });

  /**
   * MA2 (the owner's admin list item 1): the screen a count is the count *of*.
   *
   * `GET /me/home` answers the Admin eight cards — managers, coordinators, teachers, children,
   * classes and workers, plus P3.0's schools and lessons-this-week — and six of them are now rail
   * rows of their own. A number nobody can act on is a number, so each of the six is a link and the
   * other two are not: `schools` needs `multiSchool` to have a screen at all, and
   * `lessonsThisWeek` counts across every class rather than standing for one list.
   *
   * Keyed off the role, because `children` is also a *teacher's* card and `/admin/children` is not
   * hers; and filtered by `can()`, so a card whose screen the router would refuse is drawn flat
   * rather than as a link onto a guard.
   */
  protected cardLink(key: string): string | null {
    if (this.isTeacher()) return null;
    const link = ADMIN_CARD_LINKS[key];
    return link !== undefined && this.permissions.can(link.key) ? link.path : null;
  }

  protected statIconGradient(key: string): string {
    switch (key) {
      case 'playedYesterday':
      case 'schools':
      case 'managers':
        return 'em-gradient--blue';
      case 'lessonsThisWeek':
      case 'children':
      case 'coordinators':
        return 'em-gradient--purple';
      case 'needsReview':
      case 'workers':
        return 'em-gradient--pink';
      case 'classes':
        return 'em-gradient--cyan';
      default:
        return 'em-gradient--green';
    }
  }

  protected readonly needsYou = computed(() => {
    this.lang();
    return (this.home.value()?.needsYou ?? [])
      .map((item) => ({
        kind: item.kind ?? '',
        targetId: item.targetId ?? '',
        href: item.href ?? '',
        text: this.sentence(item),
      }))
      .filter((item) => item.text !== null);
  });

  protected cardLabel(key: string): string {
    return this.translateOrEmpty(`home.card.${key}`);
  }

  protected classTitle(klass: TeacherClassInfo): string {
    return this.transloco.translate('home.classTitle', {
      curriculum: this.translateOrEmpty(`curriculum.${klass.curriculum ?? ''}`) || (klass.curriculum ?? ''),
      grade: klass.grade ?? '',
      subject: this.translateOrEmpty(`subject.${klass.subject ?? ''}`) || (klass.subject ?? ''),
    });
  }

  protected classStatus(klass: TeacherClassInfo): string {
    return this.transloco.translate(klass.todayLessonId ? 'home.todayPublished' : 'home.todayMissing');
  }

  protected newLessonParams(klass: TeacherClassInfo): Record<string, string> {
    return {
      classId: klass.classId ?? '',
      curriculum: klass.curriculum ?? '',
      grade: String(klass.grade ?? ''),
      subject: klass.subject ?? '',
    };
  }

  private sentence(item: NeedsYouItem): string | null {
    const key = `home.needs.${item.kind ?? ''}`;
    const text = this.transloco.translate<string>(key, this.readable(item.params ?? {}));
    return text === key || text === '' ? null : text;
  }

  private readable(params: Record<string, string>): Record<string, string> {
    const out = { ...params };
    for (const field of ['curriculum', 'subject'] as const) {
      const value = out[field];
      if (value) out[field] = this.translateOrEmpty(`${field}.${value}`) || value;
    }
    return out;
  }

  private t(key: string): string {
    return this.transloco.translate<string>(key);
  }

  private translateOrEmpty(key: string): string {
    const text = this.transloco.translate<string>(key);
    return text === key ? '' : text;
  }
}
