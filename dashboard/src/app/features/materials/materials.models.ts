import type { MaterialBoardRow, MaterialCounts, MaterialRow } from '../../api';
import { type ChatFileRefusal, refusalOf } from '../../core/chat/chat-attachments';

/**
 * Feature 31's teaching material, the part with no Angular in it: what a row's status is, what a
 * count card counts, which week a tab asks for, and whether a picked file may be handed in.
 *
 * The teacher's page and the leaders' board both read the same rows (`MaterialRow`), so every
 * sentence about a row — reviewed means read-only, missing means nothing handed in — is written
 * once here and asserted once in `materials.models.spec.ts`.
 */

/** The four statuses the server computes. `MISSING` is what an un-handed-in row reads as. */
export const MATERIAL_STATUSES = ['ON_TIME', 'LATE', 'MISSING', 'REVIEWED'] as const;
export type MaterialStatus = (typeof MATERIAL_STATUSES)[number];

/** The four count cards, in the order the page draws them. */
export const MATERIAL_COUNTS = ['onTime', 'late', 'missing', 'reviewed'] as const;
export type MaterialCount = (typeof MATERIAL_COUNTS)[number];

/** A row's status, with an absent or unknown value read as "nothing handed in". */
export function statusOf(row: MaterialRow): MaterialStatus {
  const status = (row.status ?? '').trim().toUpperCase();
  return (MATERIAL_STATUSES as readonly string[]).includes(status) ? (status as MaterialStatus) : 'MISSING';
}

/** A leader has reviewed this row: it is a decision, so the teacher may not replace it any more. */
export function isReviewed(row: MaterialRow): boolean {
  return statusOf(row) === 'REVIEWED';
}

/** Whether the teacher may still hand this row in, or hand a different file in over it. */
export function canReplace(row: MaterialRow): boolean {
  return !isReviewed(row);
}

/** Whether a leader may still review it: something was handed in, and nobody has reviewed it. */
export function canReview(row: MaterialRow): boolean {
  return !isReviewed(row) && (row.submissionId ?? '') !== '';
}

/** The card's number as the server counted it. */
export function countOf(counts: MaterialCounts | undefined, card: MaterialCount): number {
  return counts?.[card] ?? 0;
}

/**
 * `(classId, subject)` is what identifies a row — a submission id is absent until she hands it
 * in, so it cannot be the key the table tracks by or the expansion keys its open row on.
 */
export function rowKey(row: MaterialRow): string {
  return `${row.classId ?? ''}:${row.subject ?? ''}`;
}

/**
 * The week start `weeks` weeks after `weekStart`, as `YYYY-MM-DD`.
 *
 * Read and written in UTC on purpose: the string is a calendar date, and `new Date('2026-10-18')`
 * is already midnight UTC — doing the arithmetic in the browser's zone would move it a day in
 * half the world. The server normalises any day of a week to that week's first teaching day, so
 * seven days on from one week start always names the next week whatever day a school starts on.
 */
export function weekPlus(weekStart: string, weeks: number): string {
  const at = Date.parse(`${weekStart}T00:00:00Z`);
  if (Number.isNaN(at)) return weekStart;
  return new Date(at + weeks * 7 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
}

/** `2026-10-18` out of a `?week=` that may be absent, blank or something else entirely. */
export function weekOf(value: string | null | undefined): string {
  const week = (value ?? '').trim();
  return /^\d{4}-\d{2}-\d{2}$/.test(week) ? week : '';
}

/**
 * Why a picked file cannot be handed in, or `null` when it can.
 *
 * **The chat's own rule** (`core/chat/chat-attachments.ts`): the three web image formats up to
 * 5 MB and a PDF up to 10 MB, which is exactly what `POST /teacher/materials/attachments`
 * accepts. One file at a time, so `tooMany` is never the answer — a second file replaces the
 * first rather than joining it.
 */
export function materialRefusalOf(file: File): Exclude<ChatFileRefusal, 'tooMany'> | null {
  const refusal = refusalOf(file, 0);
  return refusal === 'tooMany' ? null : refusal;
}

/** A teacher's row of the board, with the rows she has not handed in first. */
export function sortedBoardRows(rows: readonly MaterialBoardRow[]): readonly MaterialBoardRow[] {
  return [...rows].sort((a, b) => (b.counts?.missing ?? 0) - (a.counts?.missing ?? 0));
}

const BOM = '﻿';

/**
 * The CSV as the server wrote it, with its byte-order mark put back — feature 17's reason: the
 * client reads the body as text, which drops the BOM, and without it Excel reads Arabic names
 * as the local code page.
 */
export function withBom(csv: string): string {
  return csv.startsWith(BOM) ? csv : `${BOM}${csv}`;
}

/** `teaching-material-2026-10-18.csv`. */
export function csvName(week: string): string {
  return `teaching-material-${week || 'week'}.csv`;
}
