import type { ChildComment, ChildLevel, ChildReport, ChildTrendPoint, ChildWork } from '../../api';
import { bandOf, type Band } from './results.models';
import type { Trend } from './gradebook.models';

/**
 * The child page's data (`docs/teacher-flow.md` §4 step 9), including the geometry of its chart.
 *
 * **Why the chart is arithmetic and not a library.** One score per lesson over a term is a
 * dozen bars; `ngx-echarts` is 300 kB to draw them, and a teacher on a school laptop pays that
 * on every child she opens. The bars are laid out here, drawn as inline SVG from the same
 * tokens as everything else, and the numbers appear under the chart in a real table — which is
 * also the only version a screen reader, a printer and a photocopier can all read.
 */

// ---------------------------------------------------------------- levels

export interface LevelRow {
  readonly subject: string;
  readonly band: Band | null;
  readonly levelScore: number | null;
  readonly trend: Trend;
  readonly lessons: number;
}

export function levelsOf(report: ChildReport): readonly LevelRow[] {
  return (report.levels ?? []).map((level: ChildLevel) => ({
    subject: level.subject ?? '',
    band: bandOf(level.band),
    levelScore: level.levelScore ?? null,
    trend: trendOf(level.trend),
    lessons: level.lessons ?? 0,
  }));
}

function trendOf(value: string | null | undefined): Trend {
  return value === 'up' || value === 'flat' || value === 'down' ? value : null;
}

// ---------------------------------------------------------------- the chart

/** The plot's geometry, in the units of the `viewBox`. */
export const CHART = {
  height: 240,
  slot: 48,
  bar: 28,
  paddingInline: 40,
  paddingBlock: 12,
  minWidth: 320,
} as const;

/** §7's band boundaries, drawn as the chart's gridlines: a score is read against them. */
export const BAND_LINES = [40, 60, 85] as const;

export interface Bar {
  readonly lessonId: string;
  readonly title: string;
  readonly date: string;
  readonly score: number;
  readonly band: Band | null;
  readonly released: boolean;
  readonly x: number;
  readonly y: number;
  readonly width: number;
  readonly height: number;
  /** Where a label under the bar is centred. */
  readonly centre: number;
}

export interface Chart {
  readonly width: number;
  readonly height: number;
  readonly baseline: number;
  readonly bars: readonly Bar[];
  readonly lines: readonly { readonly score: number; readonly y: number }[];
}

/**
 * One bar per released score, oldest first, laid out left to right.
 *
 * A point with no score is dropped rather than drawn as zero: a lesson a child never opened is
 * not a lesson she failed, and a chart that says otherwise is the one thing a parents' evening
 * cannot recover from.
 */
export function chartOf(points: readonly ChildTrendPoint[]): Chart {
  const scored = points.filter(
    (point): point is ChildTrendPoint & { score: number } => typeof point.score === 'number',
  );
  const plot = CHART.height;
  const width = Math.max(CHART.minWidth, CHART.paddingInline * 2 + scored.length * CHART.slot);
  const baseline = plot + CHART.paddingBlock;

  const bars = scored.map<Bar>((point, index) => {
    const value = Math.max(0, Math.min(100, point.score));
    const height = Math.round((value / 100) * plot);
    const centre = CHART.paddingInline + index * CHART.slot + CHART.slot / 2;
    return {
      lessonId: point.lessonId ?? '',
      title: point.title ?? '',
      date: point.date ?? '',
      score: point.score,
      band: bandOf(point.band),
      released: point.released === true,
      x: centre - CHART.bar / 2,
      y: baseline - height,
      width: CHART.bar,
      height,
      centre,
    };
  });

  return {
    width,
    height: baseline + CHART.paddingBlock,
    baseline,
    bars,
    lines: BAND_LINES.map((score) => ({ score, y: baseline - (score / 100) * plot })),
  };
}

// ---------------------------------------------------------------- comments and work

export interface CommentRow {
  readonly lessonId: string;
  readonly lessonTitle: string;
  readonly stopId: string | null;
  readonly stars: number | null;
  readonly comment: string;
  readonly markedAt: number;
  /** A comment with no stop is the one the parent reads; a stop's is the teacher's own note. */
  readonly forParent: boolean;
}

export function commentsOf(report: ChildReport): readonly CommentRow[] {
  return (report.comments ?? [])
    .map((comment: ChildComment) => ({
      lessonId: comment.lessonId ?? '',
      lessonTitle: comment.lessonTitle ?? '',
      stopId: comment.stopId ?? null,
      stars: comment.stars ?? null,
      comment: (comment.comment ?? '').trim(),
      markedAt: comment.markedAt ?? 0,
      forParent: (comment.stopId ?? null) === null,
    }))
    .filter((row) => row.comment !== '')
    .sort((a, b) => b.markedAt - a.markedAt);
}

export interface WorkItem {
  readonly id: string;
  readonly url: string;
  readonly kind: string;
  readonly stopId: string | null;
  readonly createdAt: number;
  /** A recording is listened to; everything else is looked at. */
  readonly audio: boolean;
}

const AUDIO_KINDS = ['recording', 'retell', 'audio'];

export function workOf(report: ChildReport): readonly WorkItem[] {
  return (report.work ?? [])
    .map((work: ChildWork) => ({
      id: work.id ?? '',
      url: work.url ?? '',
      kind: (work.kind ?? '').toLowerCase(),
      stopId: work.stopId ?? null,
      createdAt: work.createdAt ?? 0,
      audio: AUDIO_KINDS.includes((work.kind ?? '').toLowerCase()),
    }))
    .filter((item) => item.url !== '');
}
