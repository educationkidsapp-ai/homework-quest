/* hq-flag: none (shell) — every role's Home. A flag can empty a section of it; it cannot
   take away the screen `/` redirects to. */
import { NgClass } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { HomeApi, HomeResponse, NeedsYouItem, TeacherClassInfo } from '../../api';
import { AuthService } from '../../core/auth/auth.service';
import { activeLang } from '../../core/i18n/active-lang';
import { ThemeService } from '../../core/theme/theme.service';
import {
  BandComponent,
  ButtonComponent,
  CountUpDirective,
  PageComponent,
  SkeletonComponent,
} from '../../ui';

interface QuickActionItem {
  readonly label: string;
  readonly icon: string;
  readonly link: string;
  readonly queryParams?: Record<string, string>;
  readonly color: string;
  readonly bgColor: string;
}

interface TeacherStaffItem {
  readonly name: string;
  readonly subject: string;
  readonly email: string;
  readonly phone: string;
  readonly status: 'Present' | 'On Leave';
  readonly avatar: string;
  readonly gradient: string;
}


/**
 * Home, for all three roles — matching EduManage School Management System Dashboard
 * from https://cork-flap-52975231.figma.site/
 */
@Component({
  selector: 'hq-home-page',
  imports: [
    NgClass,
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
          <!-- ================= 1. Four Hero Stats Cards ================= -->
          <section class="em-stats-grid" [attr.aria-label]="'home.cardsLabel' | transloco" data-hq-tour="cards">
            @for (card of cards(); track card.key) {
              <div class="em-stat-card">
                <div class="em-stat-info">
                  <p class="em-stat-label">{{ cardLabel(card.key) }}</p>
                  <h3 class="em-stat-value"><span [hqCountUp]="card.value"></span></h3>
                  <div class="em-stat-trend em-stat-trend--up">
                    <svg viewBox="0 0 16 16" fill="currentColor"><path d="M8 3.5l4.5 4.5h-3v4.5h-3V8H3.5L8 3.5z"/></svg>
                    <span>+12.5%</span>
                    <span class="em-stat-trend-sub">vs last month</span>
                  </div>
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
                    @default {
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14" />
                        <polyline points="22 4 12 14.01 9 11.01" />
                      </svg>
                    }
                  }
                </div>
              </div>
            }
            @if (isTeacher()) {
              <div class="em-stat-card">
                <div class="em-stat-info">
                  <p class="em-stat-label">{{ 'home.attendance.weeklyTitle' | transloco }}</p>
                  <h3 class="em-stat-value">94.2%</h3>
                  <div class="em-stat-trend em-stat-trend--up">
                    <svg viewBox="0 0 16 16" fill="currentColor"><path d="M8 3.5l4.5 4.5h-3v4.5h-3V8H3.5L8 3.5z"/></svg>
                    <span>+2.4%</span>
                    <span class="em-stat-trend-sub">vs last month</span>
                  </div>
                </div>
                <div class="em-stat-icon-tile em-gradient--green">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14" />
                    <polyline points="22 4 12 14.01 9 11.01" />
                  </svg>
                </div>
              </div>
            }
          </section>

          <!-- ================= 2. Quick Actions Panel ================= -->
          <section class="em-card em-quick-actions-card" [attr.aria-label]="'home.quickActions.title' | transloco">
            <div class="em-card-header">
              <div>
                <h2 class="em-card-title">{{ 'home.quickActions.title' | transloco }}</h2>
                <p class="em-card-subtitle">{{ 'home.quickActions.subtitle' | transloco }}</p>
              </div>
            </div>
            <div class="em-quick-actions-grid">
              @for (act of quickActions; track act.label) {
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

          <!-- ================= 3. Main Split Grid (2fr Left : 1fr Right) ================= -->
          <div class="em-main-split">
            <!-- Left Column -->
            <div class="em-split-col em-split-col--left">
              <!-- Weekly Attendance Chart Card -->
              <section class="em-card">
                <div class="em-card-header">
                  <div>
                    <h2 class="em-card-title">{{ 'home.attendance.weeklyTitle' | transloco }}</h2>
                    <p class="em-card-subtitle">Student and teacher attendance overview</p>
                  </div>
                  <div class="em-legend-group">
                    <div class="em-legend-item">
                      <span class="em-legend-dot em-legend-dot--blue"></span>
                      <span class="em-legend-label">Students</span>
                    </div>
                    <div class="em-legend-item">
                      <span class="em-legend-dot em-legend-dot--purple"></span>
                      <span class="em-legend-label">Teachers</span>
                    </div>
                  </div>
                </div>

                <div class="em-chart-container">
                  <div class="em-chart-bars">
                    @for (item of attendanceDays; track item.day) {
                      <div class="em-chart-col">
                        <div class="em-chart-tracks">
                          <div class="em-chart-track em-chart-track--students" [style.height.%]="item.studentPct"></div>
                          <div class="em-chart-track em-chart-track--teachers" [style.height.%]="item.teacherPct"></div>
                        </div>
                        <span class="em-chart-day">{{ item.day }}</span>
                      </div>
                    }
                  </div>
                </div>
              </section>

              <!-- Classes / Teachers Directory Card -->
              <section class="em-card">
                <div class="em-card-header">
                  <div>
                    <h2 class="em-card-title">{{ isTeacher() ? ('home.myClasses' | transloco) : 'Teachers' }}</h2>
                    <p class="em-card-subtitle">{{ isTeacher() ? ('home.activeClasses' | transloco) : 'Current staff members' }}</p>
                  </div>
                  <a class="em-link-action" routerLink="/teacher/classes">View All</a>
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

                <!-- Staff members list from Figma -->
                <div class="em-list-group em-list-group--staff">
                  @for (teacher of staffList; track teacher.email) {
                    <div class="em-list-item">
                      <div class="em-item-avatar" [ngClass]="teacher.gradient">
                        {{ teacher.avatar }}
                      </div>
                      <div class="em-item-details">
                        <h3 class="em-item-title">{{ teacher.name }}</h3>
                        <p class="em-item-subtitle">{{ teacher.subject }}</p>
                      </div>
                      <div class="em-item-meta">
                        <span class="em-meta-text">{{ teacher.email }}</span>
                        <span class="em-meta-text">{{ teacher.phone }}</span>
                      </div>
                      <div class="em-item-status">
                        <span
                          class="em-pill"
                          [class.em-pill--present]="teacher.status === 'Present'"
                          [class.em-pill--leave]="teacher.status === 'On Leave'"
                        >
                          {{ teacher.status }}
                        </span>
                      </div>
                    </div>
                  }
                </div>
              </section>

              <!-- Weak Skills section (preserved for curriculum diagnostics) -->
              @if (weakSkills(); as skills) {
                @if (skills.length > 0) {
                  <section class="em-card">
                    <div class="em-card-header">
                      <div>
                        <h2 class="em-card-title">{{ 'home.weakSkills' | transloco }}</h2>
                        <p class="em-card-subtitle">Identified focus areas</p>
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

            <!-- Right Column -->
            <div class="em-split-col em-split-col--right">
              <!-- Schedule Gaps & Action Items Card -->
              <section class="em-card">
                <div class="em-card-header">
                  <div>
                    <h2 class="em-card-title">{{ 'nav.thisWeek' | transloco }}</h2>
                    <p class="em-card-subtitle">Schedule gaps & action items</p>
                  </div>
                  <svg class="em-header-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
                  </svg>
                </div>

                <div class="em-events-list">
                  <div class="em-event-item em-event--orange">
                    <h3 class="em-event-title">Class 1B · Math: Missing Lesson</h3>
                    <div class="em-event-details">
                      <div class="em-event-line">
                        <span>No lesson scheduled for Tuesday</span>
                      </div>
                    </div>
                  </div>
                  <div class="em-event-item em-event--purple">
                    <h3 class="em-event-title">Grade 2C · Math Exam: Closing Soon</h3>
                    <div class="em-event-details">
                      <div class="em-event-line">
                        <span>4 submissions received • Closes in 2 days</span>
                      </div>
                    </div>
                  </div>
                  <div class="em-event-item em-event--pink">
                    <h3 class="em-event-title">Grade 1A · Fractions: 3 Open Stops</h3>
                    <div class="em-event-details">
                      <div class="em-event-line">
                        <span>Waiting for teacher oral retell marks</span>
                      </div>
                    </div>
                  </div>
                </div>
              </section>

              <!-- Exams & Homework Tests Card -->
              <section class="em-card">
                <div class="em-card-header">
                  <div>
                    <h2 class="em-card-title">{{ 'nav.exams' | transloco }}</h2>
                    <p class="em-card-subtitle">{{ 'home.activeClasses' | transloco }}</p>
                  </div>
                  <svg class="em-header-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/>
                  </svg>
                </div>

                <div class="em-fees-overview">
                  <div class="em-fee-block">
                    <div class="em-fee-top">
                      <span class="em-fee-label">Active Exams</span>
                      <span class="em-fee-change em-fee-change--success">Scheduled</span>
                    </div>
                    <h3 class="em-fee-value">3</h3>
                  </div>
                  <div class="em-fee-block">
                    <div class="em-fee-top">
                      <span class="em-fee-label">Needs Grading</span>
                      <span class="em-fee-change em-fee-change--warning">Pending</span>
                    </div>
                    <h3 class="em-fee-value">12</h3>
                  </div>
                </div>

                <a routerLink="/teacher/exams/new" class="em-btn-gradient">
                  {{ 'action.create' | transloco }}
                </a>
              </section>
            </div>
          </div>
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

    .em-stat-trend {
      display: flex;
      align-items: center;
      gap: 4px;
      font-size: 11px;
      font-weight: 600;

      svg {
        inline-size: 12px;
        block-size: 12px;
      }

      &--up {
        color: #10b981;
      }
    }

    .em-stat-trend-sub {
      color: #94a3b8;
      font-weight: 400;
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

    .em-header-icon {
      inline-size: 20px;
      block-size: 20px;
      color: #94a3b8;
    }

    .em-link-action {
      font-size: 13px;
      font-weight: 600;
      color: #2563eb;
      text-decoration: none;
      &:hover { text-decoration: underline; }
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

    // --- 3. Split Grid ---
    .em-main-split {
      display: grid;
      grid-template-columns: 2fr 1fr;
      gap: 24px;
      align-items: start;

      @include m.below(1024px) {
        grid-template-columns: 1fr;
      }
    }

    .em-split-col {
      display: flex;
      flex-direction: column;
      gap: 24px;
    }

    // Chart
    .em-legend-group {
      display: flex;
      align-items: center;
      gap: 16px;
    }

    .em-legend-item {
      display: flex;
      align-items: center;
      gap: 6px;
    }

    .em-legend-dot {
      inline-size: 10px;
      block-size: 10px;
      border-radius: 50%;
      &--blue { background: #3b82f6; }
      &--purple { background: #a855f7; }
    }

    .em-legend-label {
      font-size: 12px;
      color: #64748b;
    }

    .em-chart-container {
      padding-block-start: 12px;
    }

    .em-chart-bars {
      display: flex;
      align-items: flex-end;
      justify-content: space-between;
      block-size: 160px;
      padding-inline: 12px;
      border-bottom: 1px solid #f1f5f9;
      gap: 12px;
    }

    .em-chart-col {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 8px;
      flex: 1;
    }

    .em-chart-tracks {
      display: flex;
      align-items: flex-end;
      gap: 4px;
      block-size: 130px;
      inline-size: 100%;
      max-inline-size: 32px;
    }

    .em-chart-track {
      flex: 1;
      border-radius: 6px 6px 0 0;
      transition: height 0.3s ease;

      &--students {
        background: linear-gradient(180deg, #3b82f6 0%, #93c5fd 100%);
      }
      &--teachers {
        background: linear-gradient(180deg, #a855f7 0%, #d8b4fe 100%);
      }
    }

    .em-chart-day {
      font-size: 11px;
      font-weight: 500;
      color: #94a3b8;
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

    .em-item-meta {
      display: flex;
      flex-direction: column;
      gap: 2px;
      text-align: end;

      @include m.below(768px) {
        display: none;
      }
    }

    .em-meta-text {
      font-size: 11px;
      color: #94a3b8;
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

    // Events List
    .em-events-list {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }

    .em-event-item {
      padding: 14px 16px;
      border-radius: 16px;
      border-inline-start: 4px solid;
      display: flex;
      flex-direction: column;
      gap: 8px;
      transition: transform 0.15s ease, box-shadow 0.15s ease;

      &:hover {
        transform: translateX(3px);
        box-shadow: 0 4px 12px -2px rgba(0, 0, 0, 0.06);
      }
    }

    .em-event--blue { border-color: #3b82f6; background: #eff6ff; }
    .em-event--purple { border-color: #a855f7; background: #faf5ff; }
    .em-event--pink { border-color: #ec4899; background: #fdf2f8; }
    .em-event--green { border-color: #22c55e; background: #f0fdf4; }
    .em-event--orange { border-color: #f97316; background: #fff7ed; }

    .em-event-title {
      font-size: 13px;
      font-weight: 700;
      color: #1e293b;
      margin: 0;
    }

    .em-event-details {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }

    .em-event-line {
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: 11px;
      color: #64748b;

      svg {
        inline-size: 12px;
        block-size: 12px;
        color: #94a3b8;
      }
    }

    // Fees Card
    .em-fees-overview {
      display: flex;
      flex-direction: column;
      gap: 10px;
    }

    .em-fee-block {
      padding: 12px 14px;
      background: #f8fafc;
      border-radius: 16px;
      display: flex;
      flex-direction: column;
      gap: 4px;
    }

    .em-fee-top {
      display: flex;
      align-items: center;
      justify-content: space-between;
    }

    .em-fee-label {
      font-size: 12px;
      color: #64748b;
    }

    .em-fee-change {
      font-size: 11px;
      font-weight: 600;
      &--success { color: #16a34a; }
      &--warning { color: #d97706; }
      &--danger { color: #dc2626; }
    }

    .em-fee-value {
      font-size: 18px;
      font-weight: 700;
      color: #1e293b;
      margin: 0;
    }

    .em-section-micro-title {
      font-size: 13px;
      font-weight: 600;
      color: #64748b;
      margin: 12px 0 8px 0;
    }

    .em-payments-list {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }

    .em-payment-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 8px 12px;
      background: #f8fafc;
      border-radius: 12px;
    }

    .em-payment-student {
      font-size: 13px;
      font-weight: 600;
      color: #1e293b;
      margin: 0;
    }

    .em-payment-class {
      font-size: 11px;
      color: #94a3b8;
      margin: 0;
    }

    .em-payment-meta {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .em-payment-amount {
      font-size: 13px;
      font-weight: 700;
      color: #1e293b;
      margin: 0;
    }

    .em-btn-gradient {
      inline-size: 100%;
      padding-block: 12px;
      border: 0;
      border-radius: 16px;
      background: linear-gradient(to right, #3b82f6, #9333ea);
      color: #ffffff;
      font-size: 13px;
      font-weight: 600;
      cursor: pointer;
      box-shadow: 0 4px 14px -2px rgba(59, 130, 246, 0.4);
      transition: transform 0.15s ease, box-shadow 0.15s ease;

      &:hover {
        transform: translateY(-1px);
        box-shadow: 0 6px 20px -2px rgba(59, 130, 246, 0.5);
      }
    }
  `,
})
export class HomePage {
  protected readonly auth = inject(AuthService);
  protected readonly transloco = inject(TranslocoService);
  protected readonly theme = inject(ThemeService);
  private readonly api = inject(HomeApi);

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

  // EduManage Quick Actions — strictly Homework Quest teacher workflows
  protected readonly quickActions: readonly QuickActionItem[] = [
    { label: 'This Week', icon: 'calendar', link: '/teacher/week', color: 'em-gradient--blue', bgColor: 'em-bg--blue' },
    { label: 'My Classes', icon: 'user-plus', link: '/teacher/classes', color: 'em-gradient--purple', bgColor: 'em-bg--purple' },
    { label: 'New Lesson', icon: 'file-text', link: '/teacher/lessons/new', color: 'em-gradient--pink', bgColor: 'em-bg--pink' },
    { label: 'Create Exam', icon: 'download', link: '/teacher/exams/new', color: 'em-gradient--cyan', bgColor: 'em-bg--cyan' },
    { label: 'Attendance', icon: 'bell', link: '/teacher/classes', queryParams: { tab: 'attendance' }, color: 'em-gradient--green', bgColor: 'em-bg--green' },
    { label: 'Parent Chat', icon: 'mail', link: '/teacher/chat', color: 'em-gradient--orange', bgColor: 'em-bg--orange' },
  ];

  // EduManage Weekly Attendance Data
  protected readonly attendanceDays = [
    { day: 'Mon', studentPct: 93, teacherPct: 95 },
    { day: 'Tue', studentPct: 95, teacherPct: 97 },
    { day: 'Wed', studentPct: 94, teacherPct: 96 },
    { day: 'Thu', studentPct: 96, teacherPct: 99 },
    { day: 'Fri', studentPct: 95, teacherPct: 96 },
    { day: 'Sat', studentPct: 84, teacherPct: 85 },
  ];

  // EduManage Staff Directory Data
  protected readonly staffList: readonly TeacherStaffItem[] = [
    { name: 'Sarah Johnson', subject: 'Mathematics', email: 'sarah.j@school.edu', phone: '+1 234-567-8901', status: 'Present', avatar: 'SJ', gradient: 'em-gradient--blue' },
    { name: 'Michael Chen', subject: 'Science', email: 'michael.c@school.edu', phone: '+1 234-567-8902', status: 'Present', avatar: 'MC', gradient: 'em-gradient--purple' },
    { name: 'Emily Davis', subject: 'English', email: 'emily.d@school.edu', phone: '+1 234-567-8903', status: 'On Leave', avatar: 'ED', gradient: 'em-gradient--pink' },
    { name: 'David Wilson', subject: 'History', email: 'david.w@school.edu', phone: '+1 234-567-8904', status: 'Present', avatar: 'DW', gradient: 'em-gradient--green' },
    { name: 'Lisa Anderson', subject: 'Arts', email: 'lisa.a@school.edu', phone: '+1 234-567-8905', status: 'Present', avatar: 'LA', gradient: 'em-gradient--orange' },
  ];

  protected readonly greeting = computed(() => {
    this.lang();
    const name = this.home.value()?.displayName ?? this.auth.displayName();
    return this.transloco.translate<string>('home.greeting', { name });
  });

  protected statIconGradient(key: string): string {
    switch (key) {
      case 'playedYesterday':
      case 'schools':
        return 'em-gradient--blue';
      case 'lessonsThisWeek':
      case 'children':
        return 'em-gradient--purple';
      case 'needsReview':
        return 'em-gradient--pink';
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

  private translateOrEmpty(key: string): string {
    const text = this.transloco.translate<string>(key);
    return text === key ? '' : text;
  }
}
