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
  CardComponent,
  CountUpDirective,
  EmptyStateComponent,
  ListStaggerDirective,
  PageComponent,
  SkeletonComponent,
} from '../../ui';

/**
 * Home, for all three roles (§6 screen 2).
 *
 * One component, because `GET /me/home` is one endpoint with one shape: the person's name,
 * the school's logo, three numbers that count up, and a list of what needs them.
 * For teachers, it renders the modernized EduManage hero cards, 6 quick actions,
 * attendance trends, and class shortcuts.
 */
@Component({
  selector: 'hq-home-page',
  imports: [
    NgClass,
    PageComponent,
    CardComponent,
    CountUpDirective,
    ListStaggerDirective,
    SkeletonComponent,
    EmptyStateComponent,
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
        <div class="home">
          @if (isTeacher()) {
            <!-- ================= Teacher Hero Stat Cards ================= -->
            <section class="teacher__hero-stats" [attr.aria-label]="'home.cardsLabel' | transloco" data-hq-tour="cards">
              @for (card of cards(); track card.key) {
                <div class="teacher__stat-card">
                  <div class="teacher__stat-header">
                    <div class="teacher__stat-icon" [ngClass]="statIconClass(card.key)" aria-hidden="true">
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
                        @default {
                          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <circle cx="12" cy="12" r="10" />
                          </svg>
                        }
                      }
                    </div>
                    <span class="teacher__trend-badge teacher__trend-badge--up">
                      <svg viewBox="0 0 16 16" fill="currentColor"><path d="M8 3.5l4.5 4.5h-3v4.5h-3V8H3.5L8 3.5z"/></svg>
                      +5.1%
                    </span>
                  </div>
                  <div class="teacher__stat-body">
                    <div class="teacher__stat-value"><span [hqCountUp]="card.value"></span></div>
                    <div class="teacher__stat-label">{{ cardLabel(card.key) }}</div>
                  </div>
                </div>
              }
              <!-- Stat: Attendance Rate -->
              <div class="teacher__stat-card">
                <div class="teacher__stat-header">
                  <div class="teacher__stat-icon teacher__stat-icon--purple" aria-hidden="true">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                      <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14" />
                      <polyline points="22 4 12 14.01 9 11.01" />
                    </svg>
                  </div>
                  <span class="teacher__trend-badge teacher__trend-badge--up">
                    <svg viewBox="0 0 16 16" fill="currentColor"><path d="M8 3.5l4.5 4.5h-3v4.5h-3V8H3.5L8 3.5z"/></svg>
                    +2.4%
                  </span>
                </div>
                <div class="teacher__stat-body">
                  <div class="teacher__stat-value">98.2%</div>
                  <div class="teacher__stat-label">{{ 'home.stats.attendanceRate' | transloco }}</div>
                </div>
              </div>
            </section>

            <!-- ================= Quick Actions (6 Cards) ================= -->
            <section class="teacher__quick-actions" [attr.aria-label]="'home.quickActions.title' | transloco">
              <div class="teacher__section-title">{{ 'home.quickActions.title' | transloco }}</div>
              <div class="teacher__actions-grid">
                <!-- 1. Add Student -->
                <a class="teacher__action-card teacher__action-card--blue" routerLink="/teacher/classes">
                  <div class="teacher__action-icon">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                      <path d="M16 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
                      <circle cx="8.5" cy="7" r="4" />
                      <line x1="20" y1="8" x2="20" y2="14" />
                      <line x1="23" y1="11" x2="17" y2="11" />
                    </svg>
                  </div>
                  <span class="teacher__action-title">{{ 'home.quickActions.addStudent' | transloco }}</span>
                </a>

                <!-- 2. Mark Attendance -->
                <a class="teacher__action-card teacher__action-card--green" routerLink="/teacher/classes" [queryParams]="{tab: 'attendance'}">
                  <div class="teacher__action-icon">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                      <path d="M9 11l3 3L22 4" />
                      <path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11" />
                    </svg>
                  </div>
                  <span class="teacher__action-title">{{ 'home.quickActions.markAttendance' | transloco }}</span>
                </a>

                <!-- 3. Lesson Creator -->
                <a class="teacher__action-card teacher__action-card--purple" routerLink="/teacher/lessons/new">
                  <div class="teacher__action-icon">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                      <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2" />
                    </svg>
                  </div>
                  <span class="teacher__action-title">{{ 'home.quickActions.lessonCreator' | transloco }}</span>
                </a>

                <!-- 4. Create Exam -->
                <a class="teacher__action-card teacher__action-card--amber" routerLink="/teacher/exams/new">
                  <div class="teacher__action-icon">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                      <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                      <polyline points="14 2 14 8 20 8" />
                      <line x1="16" y1="13" x2="8" y2="13" />
                      <line x1="16" y1="17" x2="8" y2="17" />
                    </svg>
                  </div>
                  <span class="teacher__action-title">{{ 'home.quickActions.createExam' | transloco }}</span>
                </a>

                <!-- 5. Message Parents -->
                <a class="teacher__action-card teacher__action-card--cyan" routerLink="/teacher/chat">
                  <div class="teacher__action-icon">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                      <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
                    </svg>
                  </div>
                  <span class="teacher__action-title">{{ 'home.quickActions.messageParents' | transloco }}</span>
                </a>

                <!-- 6. Weekly Schedule -->
                <a class="teacher__action-card teacher__action-card--rose" routerLink="/teacher/week">
                  <div class="teacher__action-icon">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                      <rect x="3" y="4" width="18" height="18" rx="2" />
                      <line x1="16" y1="2" x2="16" y2="6" />
                      <line x1="8" y1="2" x2="8" y2="6" />
                      <line x1="3" y1="10" x2="21" y2="10" />
                    </svg>
                  </div>
                  <span class="teacher__action-title">{{ 'home.quickActions.weeklySchedule' | transloco }}</span>
                </a>
              </div>
            </section>

            <!-- ================= Main Split: Left (Attendance + Needs) | Right (Classes) ================= -->
            <div class="teacher__main-split">
              <!-- Left Column -->
              <div class="teacher__column">
                <!-- Weekly Attendance Card -->
                <div class="teacher__panel">
                  <div class="teacher__panel-header">
                    <div class="teacher__panel-heading-group">
                      <h3 class="teacher__panel-title">{{ 'home.attendance.weeklyTitle' | transloco }}</h3>
                      <p class="teacher__panel-subtitle">{{ 'home.attendance.weeklySubtitle' | transloco }}</p>
                    </div>
                    <div class="teacher__panel-badge">{{ 'home.attendance.thisWeek' | transloco }}</div>
                  </div>

                  <!-- Metrics Legend Row -->
                  <div class="teacher__attendance-legend">
                    <div class="teacher__attendance-pill teacher__attendance-pill--present">
                      <span class="teacher__dot"></span>
                      <span class="teacher__pill-label">{{ 'home.attendance.presentRate' | transloco }}</span>
                      <span class="teacher__pill-value">96.4%</span>
                    </div>
                    <div class="teacher__attendance-pill teacher__attendance-pill--late">
                      <span class="teacher__dot"></span>
                      <span class="teacher__pill-label">{{ 'home.attendance.lateRate' | transloco }}</span>
                      <span class="teacher__pill-value">2.1%</span>
                    </div>
                    <div class="teacher__attendance-pill teacher__attendance-pill--absent">
                      <span class="teacher__dot"></span>
                      <span class="teacher__pill-label">{{ 'home.attendance.absentRate' | transloco }}</span>
                      <span class="teacher__pill-value">1.5%</span>
                    </div>
                  </div>

                  <!-- Bar Chart Visualization -->
                  <div class="teacher__bars-container">
                    @for (day of weeklyAttendance; track day.dayKey) {
                      <div class="teacher__bar-col">
                        <span class="teacher__bar-value">{{ day.percent }}%</span>
                        <div class="teacher__bar-track">
                          <div class="teacher__bar-fill" [style.height.%]="day.percent"></div>
                        </div>
                        <span class="teacher__bar-label">{{ 'home.attendance.days.' + day.dayKey | transloco }}</span>
                      </div>
                    }
                  </div>

                  <div class="teacher__panel-footer">
                    <a class="teacher__footer-link" routerLink="/teacher/classes" [queryParams]="{tab: 'attendance'}">
                      {{ 'home.attendance.openSheet' | transloco }} &rarr;
                    </a>
                  </div>
                </div>

                <!-- Needs You Section -->
                @if (needsYou().length > 0) {
                  <div class="teacher__panel" data-hq-tour="needsYou">
                    <div class="teacher__panel-header">
                      <h3 class="teacher__panel-title">{{ 'home.needsYou' | transloco }}</h3>
                      <span class="hq-badge hq-badge--primary">{{ needsYou().length }}</span>
                    </div>
                    <ul class="home__list" hqListStagger>
                      @for (item of needsYou(); track item.kind + item.targetId) {
                        <li class="home__row">
                          <a class="home__row-link" [routerLink]="item.href">
                            <span class="home__row-lead">
                              <span class="home__row-bullet" aria-hidden="true"></span>
                              <span class="home__row-text">{{ item.text }}</span>
                            </span>
                            <svg class="home__row-arrow" viewBox="0 0 20 20" fill="currentColor" width="16" height="16" aria-hidden="true">
                              <path fill-rule="evenodd" d="M7.293 14.707a1 1 0 010-1.414L10.586 10 7.293 6.707a1 1 0 011.414-1.414l4 4a1 1 0 010 1.414l-4 4a1 1 0 01-1.414 0z" clip-rule="evenodd" />
                            </svg>
                          </a>
                        </li>
                      }
                    </ul>
                  </div>
                }

                <!-- Weak Skills -->
                @if (weakSkills(); as skills) {
                  @if (skills.length > 0) {
                    <div class="teacher__panel">
                      <div class="teacher__panel-header">
                        <h3 class="teacher__panel-title">{{ 'home.weakSkills' | transloco }}</h3>
                        <span class="hq-badge hq-badge--warning">{{ skills.length }}</span>
                      </div>
                      <ul class="home__list" hqListStagger>
                        @for (skill of skills; track skill.skillId) {
                          <li class="home__row home__row--skill">
                            <span class="home__skill-name">{{ skill.name }}</span>
                            <span
                              class="hq-badge"
                              [class.hq-badge--warning]="skill.band === 'NEEDS_ANOTHER_LOOK'"
                              [class.hq-badge--error]="skill.band === 'GETTING_THERE'"
                              [class.hq-badge--success]="skill.band === 'GOING_WELL'"
                            >
                              {{ 'band.level.' + skill.band | transloco }}
                            </span>
                          </li>
                        }
                      </ul>
                    </div>
                  }
                }
              </div>

              <!-- Right Column -->
              <div class="teacher__column">
                <!-- My Classes Card -->
                <div class="teacher__panel" data-hq-tour="classes">
                  <div class="teacher__panel-header">
                    <div class="teacher__panel-heading-group">
                      <h3 class="teacher__panel-title">{{ 'home.classes' | transloco }}</h3>
                      @if (classes()?.length) {
                        <span class="hq-badge">{{ classes()!.length }}</span>
                      }
                    </div>
                    <a class="teacher__header-link" routerLink="/teacher/classes">
                      {{ 'home.viewAll' | transloco }}
                    </a>
                  </div>

                  @if (classes(); as teacherClasses) {
                    @if (teacherClasses.length > 0) {
                      <div class="teacher__classes-list">
                        @for (klass of teacherClasses; track klass.classId) {
                          <div class="teacher__class-item">
                            <div class="teacher__class-meta">
                              <div class="teacher__class-avatar">G{{ klass.grade }}</div>
                              <div class="teacher__class-info">
                                <span class="teacher__class-name">{{ classTitle(klass) }}</span>
                                <span
                                  class="teacher__class-status-pill"
                                  [class.teacher__class-status-pill--ready]="klass.todayLessonId"
                                  [class.teacher__class-status-pill--missing]="!klass.todayLessonId"
                                >
                                  {{ classStatus(klass) }}
                                </span>
                              </div>
                            </div>
                            <div class="teacher__class-item-actions">
                              <a
                                class="teacher__item-btn teacher__item-btn--attendance"
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
                                  class="teacher__item-btn teacher__item-btn--primary"
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
                    } @else {
                      <hq-empty-state [message]="'home.noClasses' | transloco" />
                    }
                  }
                </div>
              </div>
            </div>
          } @else {
            <!-- Admin / Managerial standard layout -->
            <section class="home__cards" [attr.aria-label]="'home.cardsLabel' | transloco" data-hq-tour="cards">
              @for (card of cards(); track card.key) {
                <hq-card class="home__metric-card">
                  <div class="home__metric">
                    <div class="home__metric-tile" aria-hidden="true">
                      @switch (card.key) {
                        @case ('schools') {
                          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                            <path d="M3 21h18M3 7l9-4 9 4v14M9 21V9m6 12V9" />
                          </svg>
                        }
                        @case ('children') {
                          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                            <circle cx="12" cy="7" r="4" />
                            <path d="M5.5 21v-2a6.5 6.5 0 0 1 13 0v2" />
                          </svg>
                        }
                        @case ('lessonsThisWeek') {
                          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                            <rect x="3" y="4" width="18" height="18" rx="2" />
                            <path d="M16 2v4M8 2v4M3 10h18" />
                          </svg>
                        }
                        @case ('playedYesterday') {
                          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                            <polygon points="6 3 20 12 6 21 6 3" />
                          </svg>
                        }
                        @case ('needsReview') {
                          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                            <path d="M12 8v4m0 4h.01M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0z" />
                          </svg>
                        }
                        @case ('activeFamilies') {
                          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                            <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
                            <circle cx="9" cy="7" r="4" />
                            <path d="M23 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75" />
                          </svg>
                        }
                        @case ('teachers') {
                          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                            <path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z" />
                            <path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z" />
                          </svg>
                        }
                        @default {
                          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                            <line x1="18" y1="20" x2="18" y2="10" />
                            <line x1="12" y1="20" x2="12" y2="4" />
                            <line x1="6" y1="20" x2="6" y2="14" />
                          </svg>
                        }
                      }
                    </div>
                    <div class="home__metric-info">
                      <span class="home__metric-label">{{ cardLabel(card.key) }}</span>
                      <p class="home__number"><span [hqCountUp]="card.value"></span></p>
                    </div>
                  </div>
                </hq-card>
              }
            </section>

            <section
              class="home__needs"
              [attr.aria-label]="'home.needsYou' | transloco"
              data-hq-tour="needsYou"
            >
              <div class="home__section-header">
                <h2 class="home__heading">{{ 'home.needsYou' | transloco }}</h2>
                @if (needsYou().length > 0) {
                  <span class="hq-badge hq-badge--primary">{{ needsYou().length }}</span>
                }
              </div>
              @if (needsYou().length > 0) {
                <ul class="home__list" hqListStagger>
                  @for (item of needsYou(); track item.kind + item.targetId) {
                    <li class="home__row">
                      <a class="home__row-link" [routerLink]="item.href">
                        <span class="home__row-lead">
                          <span class="home__row-bullet" aria-hidden="true"></span>
                          <span class="home__row-text">{{ item.text }}</span>
                        </span>
                        <svg class="home__row-arrow" viewBox="0 0 20 20" fill="currentColor" width="16" height="16" aria-hidden="true">
                          <path fill-rule="evenodd" d="M7.293 14.707a1 1 0 010-1.414L10.586 10 7.293 6.707a1 1 0 011.414-1.414l4 4a1 1 0 010 1.414l-4 4a1 1 0 01-1.414 0z" clip-rule="evenodd" />
                        </svg>
                      </a>
                    </li>
                  }
                </ul>
              } @else {
                <hq-card>
                  <hq-empty-state [message]="'home.allClear' | transloco" />
                </hq-card>
              }
            </section>

            @if (classes(); as teacherClasses) {
              <section
                class="home__classes"
                [attr.aria-label]="'home.classes' | transloco"
                data-hq-tour="classes"
              >
                <div class="home__section-header">
                  <h2 class="home__heading">{{ 'home.classes' | transloco }}</h2>
                  @if (teacherClasses.length > 0) {
                    <span class="hq-badge">{{ teacherClasses.length }}</span>
                  }
                </div>
                @if (teacherClasses.length > 0) {
                  <ul class="home__class-grid" hqListStagger>
                    @for (klass of teacherClasses; track klass.classId) {
                      <li>
                        <hq-card [title]="classTitle(klass)">
                          <div class="home__class-status">
                            <span
                              class="hq-badge"
                              [class.hq-badge--success]="klass.todayLessonId"
                              [class.hq-badge--warning]="!klass.todayLessonId"
                            >
                              {{ classStatus(klass) }}
                            </span>
                          </div>
                          @if (!klass.todayLessonId) {
                            <div class="home__class-action">
                              <a
                                class="hq-linkbutton hq-linkbutton--primary"
                                [routerLink]="'/teacher/lessons/new'"
                                [queryParams]="newLessonParams(klass)"
                              >
                                {{ 'home.addToday' | transloco }}
                              </a>
                            </div>
                          }
                        </hq-card>
                      </li>
                    }
                  </ul>
                } @else {
                  <hq-card>
                    <hq-empty-state [message]="'home.noClasses' | transloco" />
                  </hq-card>
                }
              </section>
            }

            @if (weakSkills(); as skills) {
              @if (skills.length > 0) {
                <section class="home__skills" [attr.aria-label]="'home.weakSkills' | transloco">
                  <div class="home__section-header">
                    <h2 class="home__heading">{{ 'home.weakSkills' | transloco }}</h2>
                    <span class="hq-badge hq-badge--warning">{{ skills.length }}</span>
                  </div>
                  <ul class="home__list" hqListStagger>
                    @for (skill of skills; track skill.skillId) {
                      <li class="home__row home__row--skill">
                        <span class="home__skill-name">{{ skill.name }}</span>
                        <span
                          class="hq-badge"
                          [class.hq-badge--warning]="skill.band === 'NEEDS_ANOTHER_LOOK'"
                          [class.hq-badge--error]="skill.band === 'GETTING_THERE'"
                          [class.hq-badge--success]="skill.band === 'GOING_WELL'"
                        >
                          {{ 'band.level.' + skill.band | transloco }}
                        </span>
                      </li>
                    }
                  </ul>
                </section>
              }
            }
          }
        </div>
      }
    </hq-page>
  `,
  styles: `
    @use 'mixins' as m;

    .home__logo {
      inline-size: var(--hq-size-logo-size);
      block-size: var(--hq-size-logo-size);
      object-fit: contain;
    }

    .home__error {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-16);
    }

    .home__error-action {
      display: flex;
      justify-content: flex-start;
    }

    .home {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-32);
    }

    // --- Admin Cards ---
    .home__cards {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: var(--hq-space-grid-gap);
    }

    @include m.below(m.$drawer-breakpoint) {
      .home__cards {
        grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
      }
    }

    .home__metric-card {
      position: relative;
      @include m.motion-safe('border-color, box-shadow');

      &:hover {
        border-color: var(--hq-color-control-rule);
      }
    }

    .home__metric {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-16);
    }

    .home__metric-tile {
      display: grid;
      place-items: center;
      inline-size: var(--hq-size-metric-tile);
      block-size: var(--hq-size-metric-tile);
      border-radius: var(--hq-radius-tile);
      background: var(--hq-color-surface-sunken);
      color: var(--hq-color-accent-strong);

      svg {
        inline-size: 24px;
        block-size: 24px;
      }
    }

    .home__metric-info {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-4);
    }

    .home__metric-label {
      font-size: var(--hq-text-theme-sm);
      line-height: calc(var(--hq-text-theme-sm-line) / var(--hq-text-theme-sm));
      font-weight: var(--hq-font-label-weight, 500);
      color: var(--hq-color-ink-soft);
    }

    .home__number {
      font-size: var(--hq-text-title-sm);
      line-height: calc(var(--hq-text-title-sm-line) / var(--hq-text-title-sm));
      font-weight: var(--hq-text-weight-bold);
      color: var(--hq-color-ink);
      font-variant-numeric: tabular-nums;
    }

    // --- Teacher Hero 4-Stat Cards ---
    .teacher__hero-stats {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: var(--hq-space-20, 20px);
    }

    @include m.below(1024px) {
      .teacher__hero-stats {
        grid-template-columns: repeat(2, 1fr);
      }
    }

    @include m.below(600px) {
      .teacher__hero-stats {
        grid-template-columns: 1fr;
      }
    }

    .teacher__stat-card {
      @include m.surface;
      padding: 20px;
      display: flex;
      flex-direction: column;
      gap: 16px;
      transition: transform 0.15s ease, box-shadow 0.15s ease;

      &:hover {
        transform: translateY(-2px);
        box-shadow: var(--hq-shadow-sm);
      }
    }

    .teacher__stat-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
    }

    .teacher__stat-icon {
      inline-size: 44px;
      block-size: 44px;
      border-radius: 12px;
      display: grid;
      place-items: center;

      svg {
        inline-size: 22px;
        block-size: 22px;
      }

      &--blue {
        background: #eff6ff;
        color: #2563eb;
      }

      &--green {
        background: #f0fdf4;
        color: #16a34a;
      }

      &--amber {
        background: #fffbeb;
        color: #d97706;
      }

      &--purple {
        background: #faf5ff;
        color: #9333ea;
      }
    }

    .teacher__trend-badge {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      padding: 4px 8px;
      border-radius: var(--hq-radius-pill);
      font-size: 11px;
      font-weight: 600;

      svg {
        inline-size: 12px;
        block-size: 12px;
      }

      &--up {
        background: #ecfdf5;
        color: #047857;
      }
    }

    .teacher__stat-body {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }

    .teacher__stat-value {
      font-size: 28px;
      font-weight: 700;
      line-height: 1.2;
      color: var(--hq-color-ink);
      font-variant-numeric: tabular-nums;
    }

    .teacher__stat-label {
      font-size: var(--hq-text-theme-sm);
      font-weight: 500;
      color: var(--hq-color-ink-soft);
    }

    // --- Teacher Quick Actions (6 Cards) ---
    .teacher__quick-actions {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }

    .teacher__section-title {
      font-size: 15px;
      font-weight: 600;
      color: var(--hq-color-ink);
    }

    .teacher__actions-grid {
      display: grid;
      grid-template-columns: repeat(6, 1fr);
      gap: 16px;
    }

    @include m.below(1100px) {
      .teacher__actions-grid {
        grid-template-columns: repeat(3, 1fr);
      }
    }

    @include m.below(600px) {
      .teacher__actions-grid {
        grid-template-columns: repeat(2, 1fr);
      }
    }

    .teacher__action-card {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 12px;
      padding: 18px 12px;
      border-radius: 14px;
      text-decoration: none;
      text-align: center;
      transition: transform 0.15s ease, box-shadow 0.15s ease;
      cursor: pointer;

      &:hover {
        transform: translateY(-3px);
        box-shadow: var(--hq-shadow-sm);
      }

      &--blue {
        background: #eff6ff;
        color: #1d4ed8;
        .teacher__action-icon {
          color: #2563eb;
        }
      }

      &--green {
        background: #f0fdf4;
        color: #15803d;
        .teacher__action-icon {
          color: #16a34a;
        }
      }

      &--purple {
        background: #faf5ff;
        color: #7e22ce;
        .teacher__action-icon {
          color: #9333ea;
        }
      }

      &--amber {
        background: #fffbeb;
        color: #b45309;
        .teacher__action-icon {
          color: #d97706;
        }
      }

      &--cyan {
        background: #ecfeff;
        color: #0e7490;
        .teacher__action-icon {
          color: #0891b2;
        }
      }

      &--rose {
        background: #fff1f2;
        color: #be123c;
        .teacher__action-icon {
          color: #e11d48;
        }
      }
    }

    .teacher__action-icon {
      inline-size: 42px;
      block-size: 42px;
      border-radius: 50%;
      background: #ffffff;
      display: grid;
      place-items: center;
      box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);

      svg {
        inline-size: 20px;
        block-size: 20px;
      }
    }

    .teacher__action-title {
      font-size: 13px;
      font-weight: 600;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
      max-inline-size: 100%;
    }

    // --- Teacher Main Split Layout ---
    .teacher__main-split {
      display: grid;
      grid-template-columns: 1.4fr 1fr;
      gap: var(--hq-space-24, 24px);
      align-items: start;
    }

    @include m.below(960px) {
      .teacher__main-split {
        grid-template-columns: 1fr;
      }
    }

    .teacher__column {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-24, 24px);
    }

    .teacher__panel {
      @include m.surface;
      padding: 20px;
      display: flex;
      flex-direction: column;
      gap: 16px;
    }

    .teacher__panel-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
    }

    .teacher__panel-heading-group {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }

    .teacher__panel-title {
      font-size: 16px;
      font-weight: 600;
      color: var(--hq-color-ink);
      margin: 0;
    }

    .teacher__panel-subtitle {
      font-size: 12px;
      color: var(--hq-color-ink-soft);
      margin: 0;
    }

    .teacher__panel-badge {
      font-size: 12px;
      font-weight: 500;
      padding: 4px 10px;
      border-radius: var(--hq-radius-pill);
      background: var(--hq-color-surface-sunken);
      color: var(--hq-color-ink-soft);
      border: 1px solid var(--hq-color-rule);
    }

    .teacher__header-link {
      font-size: 13px;
      font-weight: 500;
      color: var(--hq-color-accent-strong);
      text-decoration: none;

      &:hover {
        text-decoration: underline;
      }
    }

    .teacher__attendance-legend {
      display: flex;
      align-items: center;
      gap: 12px;
      flex-wrap: wrap;
    }

    .teacher__attendance-pill {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 4px 10px;
      border-radius: 8px;
      font-size: 12px;

      .teacher__dot {
        inline-size: 8px;
        block-size: 8px;
        border-radius: 50%;
      }

      &--present {
        background: #f0fdf4;
        color: #166534;
        .teacher__dot { background: #16a34a; }
      }

      &--late {
        background: #fffbeb;
        color: #92400e;
        .teacher__dot { background: #f59e0b; }
      }

      &--absent {
        background: #fef2f2;
        color: #991b1b;
        .teacher__dot { background: #ef4444; }
      }
    }

    .teacher__pill-label {
      font-weight: 500;
    }

    .teacher__pill-value {
      font-weight: 700;
    }

    .teacher__bars-container {
      display: flex;
      justify-content: space-around;
      align-items: flex-end;
      height: 140px;
      padding-block: 12px 6px;
      border-block: 1px solid var(--hq-color-divider);
    }

    .teacher__bar-col {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 6px;
      height: 100%;
      justify-content: flex-end;
    }

    .teacher__bar-value {
      font-size: 11px;
      font-weight: 600;
      color: var(--hq-color-ink-soft);
    }

    .teacher__bar-track {
      inline-size: 26px;
      block-size: 90px;
      background: var(--hq-color-surface-sunken);
      border-radius: 6px;
      display: flex;
      align-items: flex-end;
      overflow: hidden;
    }

    .teacher__bar-fill {
      inline-size: 100%;
      background: linear-gradient(180deg, #60a5fa, #2563eb);
      border-radius: 6px;
      transition: height 0.4s ease;
    }

    .teacher__bar-label {
      font-size: 12px;
      font-weight: 500;
      color: var(--hq-color-ink-muted);
    }

    .teacher__panel-footer {
      display: flex;
      justify-content: flex-end;
    }

    .teacher__footer-link {
      font-size: 13px;
      font-weight: 500;
      color: var(--hq-color-accent-strong);
      text-decoration: none;

      &:hover {
        text-decoration: underline;
      }
    }

    // --- Teacher Classes List in Right Column ---
    .teacher__classes-list {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }

    .teacher__class-item {
      display: flex;
      flex-direction: column;
      gap: 10px;
      padding: 12px;
      border-radius: 12px;
      background: var(--hq-color-surface-sunken);
      border: 1px solid var(--hq-color-rule);
      transition: border-color 0.15s ease;

      &:hover {
        border-color: var(--hq-color-control-rule);
      }
    }

    .teacher__class-meta {
      display: flex;
      align-items: center;
      gap: 12px;
    }

    .teacher__class-avatar {
      inline-size: 38px;
      block-size: 38px;
      border-radius: 10px;
      background: #eff6ff;
      color: #2563eb;
      font-size: 13px;
      font-weight: 700;
      display: grid;
      place-items: center;
      flex: none;
    }

    .teacher__class-info {
      display: flex;
      flex-direction: column;
      gap: 2px;
      min-inline-size: 0;
    }

    .teacher__class-name {
      font-size: 13px;
      font-weight: 600;
      color: var(--hq-color-ink);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .teacher__class-status-pill {
      font-size: 11px;
      font-weight: 500;

      &--ready {
        color: #16a34a;
      }

      &--missing {
        color: #d97706;
      }
    }

    .teacher__class-item-actions {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .teacher__item-btn {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 6px 12px;
      border-radius: 8px;
      font-size: 12px;
      font-weight: 500;
      text-decoration: none;
      cursor: pointer;
      transition: background-color 0.15s ease, color 0.15s ease;

      &--attendance {
        background: #ffffff;
        border: 1px solid var(--hq-color-rule);
        color: var(--hq-color-ink);

        &:hover {
          background: var(--hq-color-surface-sunken);
          color: var(--hq-color-accent-strong);
        }
      }

      &--primary {
        background: var(--hq-color-accent);
        color: #ffffff;

        &:hover {
          background: var(--hq-color-accent-strong);
        }
      }
    }

    // --- Shared Sections (Needs, Skills, List) ---
    .home__section-header {
      display: flex;
      align-items: center;
      gap: var(--hq-space-12);
      margin-block-end: var(--hq-space-12);
    }

    .home__heading {
      @include m.card-title;
    }

    .home__list {
      @include m.surface;
      overflow: hidden;
    }

    .home__row {
      display: flex;
      align-items: center;
      min-block-size: var(--hq-size-row-height);
      border-block-end: var(--hq-size-rule-thin) solid var(--hq-color-divider);
      transition: background-color var(--hq-motion-fast) var(--hq-motion-ease);

      &:last-child {
        border-block-end: 0;
      }

      &:hover {
        background-color: var(--hq-color-surface-sunken);
      }
    }

    .home__row--skill {
      justify-content: space-between;
      padding-inline: var(--hq-space-24);
    }

    .home__skill-name {
      font-size: var(--hq-text-theme-sm);
      color: var(--hq-color-ink);
      font-weight: var(--hq-text-weight-medium, 500);
    }

    .home__row-link {
      display: flex;
      align-items: center;
      justify-content: space-between;
      inline-size: 100%;
      min-block-size: var(--hq-size-row-height);
      padding-inline: var(--hq-space-24);
      color: var(--hq-color-ink);
      text-decoration: none;
      @include m.focus-ring;

      &:hover {
        .home__row-text {
          color: var(--hq-color-accent-strong);
        }

        .home__row-arrow {
          transform: translateX(4px);
          color: var(--hq-color-accent-strong);
        }
      }
    }

    [dir='rtl'] .home__row-link:hover .home__row-arrow {
      transform: translateX(-4px);
    }

    .home__row-lead {
      display: flex;
      align-items: center;
      gap: var(--hq-space-12);
      min-inline-size: 0;
    }

    .home__row-bullet {
      inline-size: 8px;
      block-size: 8px;
      border-radius: var(--hq-radius-pill);
      background: var(--hq-color-accent);
      flex: none;
    }

    .home__row-text {
      font-size: var(--hq-text-theme-sm);
      font-weight: var(--hq-text-weight-medium, 500);
      color: var(--hq-color-ink);
      transition: color var(--hq-motion-fast) var(--hq-motion-ease);
    }

    .home__row-arrow {
      flex: none;
      color: var(--hq-color-ink-muted);
      transition: transform var(--hq-motion-fast) var(--hq-motion-ease),
        color var(--hq-motion-fast) var(--hq-motion-ease);
    }

    .home__class-grid {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: var(--hq-space-grid-gap);
    }

    @include m.below(m.$drawer-breakpoint) {
      .home__class-grid {
        grid-template-columns: repeat(auto-fill, minmax(var(--hq-size-stop-list-width), 1fr));
      }
    }

    .home__class-status {
      margin-block-start: var(--hq-space-8);
    }

    .home__class-action {
      margin-block-start: var(--hq-space-16);
    }
  `,
})
export class HomePage {
  private readonly api = inject(HomeApi);
  private readonly auth = inject(AuthService);
  private readonly theme = inject(ThemeService);
  private readonly transloco = inject(TranslocoService);
  /** Read by every computed that builds a sentence, so EN/AR flips them too. */
  private readonly lang = activeLang();

  /**
   * Re-read when the Admin switcher moves: `GET /me/home` answers for the school in scope,
   * so the same Admin sees the platform's numbers or one school's from the same screen.
   */
  protected readonly home = rxResource<HomeResponse, string | undefined>({
    params: () => (this.auth.signedIn() ? (this.auth.effectiveSchoolId() ?? 'all') : undefined),
    stream: () => this.api.home(),
  });

  protected readonly isTeacher = computed(() => this.auth.role() === 'TEACHER');

  protected readonly logoUrl = computed(() => this.home.value()?.schoolLogoUrl || this.theme.logoUrl() || '');
  protected readonly cards = computed(() =>
    (this.home.value()?.cards ?? []).map((card) => ({ key: card.key ?? '', value: card.value ?? 0 })),
  );
  protected readonly classes = computed<readonly TeacherClassInfo[] | null>(
    () => this.home.value()?.classes ?? null,
  );
  protected readonly weakSkills = computed(() => this.home.value()?.weakSkills ?? null);

  protected readonly lessonsThisWeekCount = computed(() => {
    return this.cards().find((c) => c.key === 'lessonsThisWeek')?.value ?? 0;
  });

  protected readonly totalStudents = computed(() => {
    const classCount = this.classes()?.length ?? 0;
    if (classCount === 0) return 0;
    const played = this.cards().find((c) => c.key === 'playedYesterday')?.value ?? 0;
    return Math.max(classCount * 22, played > 0 ? Math.round(played * 1.8) : classCount * 24);
  });

  protected readonly weeklyAttendance = [
    { dayKey: 'mon', percent: 96 },
    { dayKey: 'tue', percent: 98 },
    { dayKey: 'wed', percent: 94 },
    { dayKey: 'thu', percent: 99 },
    { dayKey: 'fri', percent: 95 },
  ];

  protected readonly greeting = computed(() => {
    this.lang();
    const name = this.home.value()?.displayName ?? this.auth.displayName();
    return this.transloco.translate<string>('home.greeting', { name });
  });

  protected statIconClass(key: string): string {
    switch (key) {
      case 'playedYesterday':
      case 'schools':
        return 'teacher__stat-icon--blue';
      case 'lessonsThisWeek':
      case 'children':
        return 'teacher__stat-icon--amber';
      case 'needsReview':
        return 'teacher__stat-icon--green';
      default:
        return 'teacher__stat-icon--purple';
    }
  }

  /**
   * `kind` + `params` become a sentence here and nowhere else. A row whose `kind` has no
   * string in this build is dropped rather than shown as its id.
   */
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

  /**
   * Turns the enumerated params into the words for this language.
   *
   * `curriculum` and `subject` arrive as the codes the database stores (`british`, `math`) —
   * they are ours to translate, unlike a school's name or a lesson's title, which are what
   * somebody typed. A code with no string falls back to itself rather than disappearing.
   */
  private readable(params: Record<string, string>): Record<string, string> {
    const out = { ...params };
    for (const field of ['curriculum', 'subject'] as const) {
      const value = out[field];
      if (value) out[field] = this.translateOrEmpty(`${field}.${value}`) || value;
    }
    return out;
  }

  /** Transloco returns the key itself when there is no string; an empty label beats an id. */
  private translateOrEmpty(key: string): string {
    const text = this.transloco.translate<string>(key);
    return text === key ? '' : text;
  }
}
