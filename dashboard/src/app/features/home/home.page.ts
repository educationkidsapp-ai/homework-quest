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
      } @else {
        <div class="home">
          <section class="home__cards" [attr.aria-label]="'home.cardsLabel' | transloco" data-hq-tour="cards">
            @for (card of cards(); track card.key) {
              <hq-card [eyebrow]="cardLabel(card.key)">
                <p class="home__number"><span [hqCountUp]="card.value"></span></p>
              </hq-card>
            }
          </section>

          <section
            class="home__needs"
            [attr.aria-label]="'home.needsYou' | transloco"
            data-hq-tour="needsYou"
          >
            <h2 class="home__heading">{{ 'home.needsYou' | transloco }}</h2>
            @if (needsYou().length > 0) {
              <ul class="home__list" hqListStagger>
                @for (item of needsYou(); track item.kind + item.targetId) {
                  <li class="home__row">
                    <a [routerLink]="item.href">{{ item.text }}</a>
                  </li>
                }
              </ul>
            } @else {
              <hq-empty-state [message]="'home.allClear' | transloco" />
            }
          </section>

          @if (classes(); as teacherClasses) {
            <section
              class="home__classes"
              [attr.aria-label]="'home.classes' | transloco"
              data-hq-tour="classes"
            >
              <h2 class="home__heading">{{ 'home.classes' | transloco }}</h2>
              @if (teacherClasses.length > 0) {
                <ul class="home__class-grid" hqListStagger>
                  @for (klass of teacherClasses; track klass.classId) {
                    <li>
                      <hq-card [title]="classTitle(klass)" [eyebrow]="classStatus(klass)">
                        @if (!klass.todayLessonId) {
                          <a [routerLink]="'/teacher/lessons/new'" [queryParams]="newLessonParams(klass)">
                            {{ 'home.addToday' | transloco }}
                          </a>
                        }
                      </hq-card>
                    </li>
                  }
                </ul>
              } @else {
                <hq-empty-state [message]="'home.noClasses' | transloco" />
              }
            </section>
          }

          @if (weakSkills(); as skills) {
            @if (skills.length > 0) {
              <section class="home__skills" [attr.aria-label]="'home.weakSkills' | transloco">
                <h2 class="home__heading">{{ 'home.weakSkills' | transloco }}</h2>
                <ul class="home__list" hqListStagger>
                  @for (skill of skills; track skill.skillId) {
                    <li class="home__row">
                      {{ skill.name }}
                      <span class="home__band">{{ 'band.level.' + skill.band | transloco }}</span>
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

    // One column of sections, one rhythm. The page frame spaces its header, body and footer; what
    // is inside the body is this screen's to space.
    .home {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-32);
    }

    .home__cards {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: var(--hq-space-16);
    }

    .home__number {
      @include m.title;
      font-variant-numeric: tabular-nums;
    }

    .home__heading {
      @include m.label;
      color: var(--hq-color-ink-soft);
      margin-block-end: var(--hq-space-8);
    }

    .home__list {
      @include m.surface;
    }

    .home__row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--hq-space-16);
      min-block-size: var(--hq-size-row-height);
      padding-inline: var(--hq-space-16);
      border-block-end: var(--hq-size-rule-thin) solid var(--hq-color-rule);

      &:last-child {
        border-block-end: 0;
      }
    }

    .home__band {
      font-size: var(--hq-font-label-size);
      color: var(--hq-color-ink-soft);
    }

    .home__class-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(var(--hq-size-stop-list-width), 1fr));
      gap: var(--hq-space-16);
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
