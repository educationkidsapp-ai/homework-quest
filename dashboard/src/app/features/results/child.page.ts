import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { ResultsAndGradebookApi } from '../../api';
import { FLAGS } from '../../core/flags/flag.service';
import { FeatureDirective } from '../../core/flags/feature.directive';
import { activeLang } from '../../core/i18n/active-lang';
import {
  CardComponent,
  EmptyStateComponent,
  PageComponent,
  SkeletonComponent,
  type Breadcrumb,
} from '../../ui';
import { ChildWorkComponent } from './child-work.component';
import { commentsOf, levelsOf, workOf, type LevelRow } from './child.models';
import { trendGlyph } from './gradebook.models';
import { LevelBandComponent } from './level-band.component';
import { ScoreChartComponent } from './score-chart.component';
import { scoreLabel } from './results.models';

/**
 * **The child page** (`docs/teacher-flow.md` §4 step 9) — one child, everything the teacher has
 * on her, in the order a parents' evening asks for it.
 *
 * Band and trend per subject first, because that is the sentence she has to say out loud; then
 * the scores over time as a chart *and* as a table; then the comments she has written, newest
 * first; then the work the child saved, which is the only part a parent ever wants to see twice.
 *
 * **What is not here.** §4 also asks for "skills going well / needing another look".
 * `ChildReport` carries no skills — the DTO has levels, a trend, comments and work — so rather
 * than invent them from scores, the page leaves them out and the package reports the gap.
 *
 * Behind `gradebook` (`*hqFeature` on the way in, and `featureGuard` on the route from
 * `core/nav/screens.ts`) and `results.read`.
 */
@Component({
  selector: 'hq-child-page',
  imports: [
    PageComponent,
    CardComponent,
    SkeletonComponent,
    EmptyStateComponent,
    LevelBandComponent,
    ScoreChartComponent,
    ChildWorkComponent,
    FeatureDirective,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './child.page.html',
  styleUrl: './child.page.scss',
})
export class ChildPage {
  private readonly api = inject(ResultsAndGradebookApi);
  private readonly route = inject(ActivatedRoute);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  /** The flag the page's own contents carry, beside the route's `featureGuard`. */
  protected readonly gradebookFlag = FLAGS.gradebook;

  private readonly path = toSignal(this.route.paramMap, { initialValue: this.route.snapshot.paramMap });
  protected readonly childId = computed(() => this.path().get('childId') ?? '');

  protected readonly report = rxResource({
    params: () => this.childId(),
    stream: ({ params }) => this.api.childReport(params),
    defaultValue: {},
  });

  protected readonly name = computed(() => this.report.value().name ?? '');
  protected readonly className = computed(() => this.report.value().className ?? '');
  protected readonly levels = computed(() => levelsOf(this.report.value()));
  protected readonly trend = computed(() => this.report.value().trend ?? []);
  protected readonly comments = computed(() => commentsOf(this.report.value()));
  protected readonly work = computed(() => workOf(this.report.value()));

  protected readonly breadcrumbs = computed<readonly Breadcrumb[]>(() => {
    this.lang();
    const classId = this.report.value().classId ?? '';
    return [
      { label: this.t('classes.title'), link: '/teacher/classes' },
      ...(classId ? [{ label: this.className(), link: `/teacher/classes/${classId}?tab=gradebook` }] : []),
      { label: this.name() },
    ];
  });

  protected subjectLabel(level: LevelRow): string {
    this.lang();
    const word = this.t(`subject.${level.subject}`);
    return word === `subject.${level.subject}` ? level.subject : word;
  }

  protected trendGlyph(level: LevelRow): string {
    return trendGlyph(level.trend);
  }

  protected trendLabel(level: LevelRow): string {
    this.lang();
    return level.trend ? this.t(`results.gradebook.trend.${level.trend}`) : '';
  }

  protected score(value: number | null): string {
    return scoreLabel(value);
  }

  protected when(epochMillis: number): string {
    this.lang();
    if (!epochMillis) return '';
    return new Intl.DateTimeFormat(this.lang(), { day: 'numeric', month: 'short' }).format(
      new Date(epochMillis),
    );
  }

  protected stars(count: number | null): string {
    return count && count > 0 ? '★'.repeat(count) : '';
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}
