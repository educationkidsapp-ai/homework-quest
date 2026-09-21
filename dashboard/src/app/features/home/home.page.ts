/* hq-flag: none (shell) — every role's Home. A flag can empty a section of it; it cannot
   take away the screen `/` redirects to. */
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
 * the school's logo, three numbers that count up, and a list of what needs them. The role
 * only decides which of the optional sections are present — `classes` and `weakSkills` are
 * TEACHER-only and arrive as `null`, not as `[]`, so this screen can tell "no classes" from
 * "not a teacher" and draws neither for the other two roles.
 *
 * **No prose crosses the API boundary.** A card carries `key`, a row carries `kind` and
 * `params`; both resolve against the Transloco catalogue as `home.card.<key>` and
 * `home.needs.<kind>`. That is what lets the EN/AR toggle flip the whole screen without a
 * reload and without the server knowing which language the tab is in (`HomeDto`'s javadoc).
 *
 * **An unknown key renders as nothing.** A later phase will add rows this build has no string
 * for, and a bare message id on a Home is worse than one fewer row.
 */
@Component({
  selector: 'hq-home-page',
  imports: [
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
    <hq-page [title]="greeting()" [subtitle]="home.value()?.schoolName ?? null">
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

    // One column of sections, one rhythm.
    .home {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-32);
    }

    // §2 Grids, metric row — at 24 px grid gap.
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

    // §3 Metric card: icon tile over label and count number.
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

    // §1: 30/38/700 is the size numbers are read at.
    .home__number {
      font-size: var(--hq-text-title-sm);
      line-height: calc(var(--hq-text-title-sm-line) / var(--hq-text-title-sm));
      font-weight: var(--hq-text-weight-bold);
      color: var(--hq-color-ink);
      font-variant-numeric: tabular-nums;
    }

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

    // §2 Grids, "card gallery".
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

  protected readonly logoUrl = computed(() => this.home.value()?.schoolLogoUrl || this.theme.logoUrl() || '');
  protected readonly cards = computed(() =>
    (this.home.value()?.cards ?? []).map((card) => ({ key: card.key ?? '', value: card.value ?? 0 })),
  );
  protected readonly classes = computed<readonly TeacherClassInfo[] | null>(
    () => this.home.value()?.classes ?? null,
  );
  protected readonly weakSkills = computed(() => this.home.value()?.weakSkills ?? null);

  protected readonly greeting = computed(() => {
    this.lang();
    const name = this.home.value()?.displayName ?? this.auth.displayName();
    return this.transloco.translate<string>('home.greeting', { name });
  });

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
