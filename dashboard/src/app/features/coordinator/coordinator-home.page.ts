/* hq-flag: none (shell) — a role's Home. It is gated by `coordinator.read`, the key R2 puts on
   `GET /coordinator/me` itself; a flag that could empty it would leave `/` with nowhere to send
   her. Same reasoning as `features/home/home.page.ts`. */
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { activeLang } from '../../core/i18n/active-lang';
import { CardComponent, CountUpDirective, PageComponent, SkeletonComponent } from '../../ui';
import { StatusSquareComponent } from '../week/status-square.component';
import { CoordinatorReadFailedComponent } from './read-failed.component';
import { CoordinatorService } from './coordinator.service';
import { scopeLabel } from './coordinator.labels';

/**
 * The coordinator's Home (R5, `docs/coordinator-flow.md` §2).
 *
 * Four things, in the order she asks them: **what am I responsible for** (the scope chips and
 * the three counts), **what needs me**, and then a preview of the two lists she would otherwise
 * have to open — her teachers and her classes. Every line is a link into a read; there is not
 * one action on this screen, because there is not one thing here she may change (DR2).
 *
 * "What needs you" is deliberately the first card under the counts rather than the last. A
 * coordinator opens this screen to find out whether anything is wrong in six grades, and a
 * screen that makes her scroll past two rosters to learn that has answered the wrong question.
 */
@Component({
  selector: 'hq-coordinator-home-page',
  imports: [
    CardComponent,
    CoordinatorReadFailedComponent,
    CountUpDirective,
    PageComponent,
    RouterLink,
    SkeletonComponent,
    StatusSquareComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-page [title]="greeting()" [subtitle]="scopeLine()">
      @if (co.loading()) {
        <hq-skeleton [loading]="true" [lines]="6" [label]="'home.loading' | transloco" />
      } @else if (co.failed()) {
        <hq-coordinator-read-failed (retry)="co.reload()" />
      } @else {
        <div class="em-dashboard">
          <section
            class="em-stats-grid"
            [attr.aria-label]="'home.cardsLabel' | transloco"
            data-hq-tour="cards"
          >
            @for (card of cards(); track card.key) {
              <div class="em-stat-card">
                <div class="em-stat-info">
                  <p class="em-stat-label">{{ 'coordinator.count.' + card.key | transloco }}</p>
                  <h3 class="em-stat-value"><span [hqCountUp]="card.value"></span></h3>
                </div>
              </div>
            }
          </section>

          <div data-hq-tour="needsYou">
            <hq-card [title]="'home.needsYou' | transloco">
              @if (co.needs().length === 0) {
                <p class="hq-muted">{{ 'home.allClear' | transloco }}</p>
              } @else {
                <ul class="co-list">
                  @for (need of co.needs(); track $index) {
                    <li class="co-list__row">
                      <a [routerLink]="need.link">{{ needLine(need) }}</a>
                      <span class="hq-badge" [class.hq-badge--error]="need.kind === 'error'">
                        {{ 'coordinator.need.' + need.kind | transloco }}
                      </span>
                    </li>
                  }
                </ul>
              }
            </hq-card>
          </div>

          <hq-card [title]="'nav.teachers' | transloco">
            @if (co.teachers().length === 0) {
              <p class="hq-muted">{{ 'coordinator.teachers.empty' | transloco }}</p>
            } @else {
              <ul class="co-list">
                @for (teacher of teacherPreview(); track teacher.userId) {
                  <li class="co-list__row">
                    <span>{{ teacher.displayName }}</span>
                    <span class="hq-muted">{{ teacher.email }}</span>
                  </li>
                }
              </ul>
              <a class="hq-linkbutton" routerLink="/coordinator/teachers">
                {{ 'home.viewAll' | transloco }}
              </a>
            }
          </hq-card>

          <hq-card [title]="'nav.classes' | transloco">
            @if (co.classes().length === 0) {
              <p class="hq-muted">{{ 'coordinator.classes.empty' | transloco }}</p>
            } @else {
              <ul class="co-list">
                @for (row of classPreview(); track row.classId) {
                  <li class="co-list__row">
                    <hq-status-square [status]="row.todayStatus" />
                    <span>{{ row.className }}</span>
                    <span class="hq-muted">{{ row.teacherName }}</span>
                  </li>
                }
              </ul>
              <a class="hq-linkbutton" routerLink="/coordinator/classes">
                {{ 'home.viewAll' | transloco }}
              </a>
            }
          </hq-card>
        </div>
      }
    </hq-page>
  `,
  styles: `
    .co-list {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-8);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .co-list__row {
      display: flex;
      align-items: center;
      gap: var(--hq-space-12);
      min-block-size: var(--hq-size-touch-target);
      border-block-end: var(--hq-size-rule-thin) solid var(--hq-color-divider);
    }
  `,
})
export class CoordinatorHomePage {
  protected readonly co = inject(CoordinatorService);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  protected readonly greeting = computed(() => {
    this.lang();
    return this.transloco.translate<string>('home.greeting', { name: this.co.displayName() });
  });

  /** "Math · British", or "Math · both tracks" when the scope row names no curriculum (DR1). */
  protected readonly scopeLine = computed(() => {
    this.lang();
    const chips = this.co.scopes().map((scope) => scopeLabel(this.transloco, scope));
    return chips.length === 0 ? null : chips.join(' · ');
  });

  protected readonly cards = computed(() => {
    const counts = this.co.counts();
    return [
      { key: 'sections', value: counts.sections },
      { key: 'teachers', value: counts.teachers },
      { key: 'children', value: counts.children },
    ];
  });

  /** A preview, not a list: five rows and the way to the rest. */
  protected readonly teacherPreview = computed(() => this.co.teachers().slice(0, 5));
  protected readonly classPreview = computed(() => this.co.classes().slice(0, 5));

  protected needLine(need: { readonly title: string; readonly className: string }): string {
    this.lang();
    const title = need.title || this.transloco.translate<string>('lessons.untitled');
    return need.className ? `${title} · ${need.className}` : title;
  }
}
