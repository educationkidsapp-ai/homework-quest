import { describe, expect, it } from 'vitest';
import type { MaterialRow } from '../../api';
import {
  MATERIAL_COUNTS,
  canReplace,
  canReview,
  countOf,
  csvName,
  isReviewed,
  materialRefusalOf,
  rowKey,
  sortedBoardRows,
  statusOf,
  weekOf,
  weekPlus,
  withBom,
} from './materials.models';

function row(extra: Partial<MaterialRow> = {}): MaterialRow {
  return { classId: 'c-1', className: '1A British', subject: 'math', ...extra };
}

function file(name: string, type: string, size: number): File {
  const picked = new File(['x'], name, { type });
  Object.defineProperty(picked, 'size', { value: size });
  return picked;
}

describe('feature 31 — the teaching material rules, without Angular', () => {
  it('reads an absent or unknown status as nothing handed in', () => {
    expect(statusOf(row())).toBe('MISSING');
    expect(statusOf(row({ status: 'on_time' }))).toBe('ON_TIME');
    expect(statusOf(row({ status: 'HANDED' }))).toBe('MISSING');
    expect(statusOf(row({ status: 'LATE' }))).toBe('LATE');
  });

  /** The one sentence both screens are built on: a review ends the teacher's side of the row. */
  it('lets her replace until it is reviewed, and never after', () => {
    const handedIn = row({ status: 'ON_TIME', submissionId: 's-1' });
    expect([canReplace(row()), canReplace(handedIn)]).toEqual([true, true]);
    const reviewed = row({ status: 'REVIEWED', submissionId: 's-1' });
    expect([isReviewed(reviewed), canReplace(reviewed)]).toEqual([true, false]);
  });

  it('offers a review only for a row that was handed in and not yet reviewed', () => {
    expect(canReview(row())).toBe(false);
    expect(canReview(row({ status: 'LATE', submissionId: 's-1' }))).toBe(true);
    expect(canReview(row({ status: 'REVIEWED', submissionId: 's-1' }))).toBe(false);
  });

  it('counts what the server counted, and zero for a count it did not send', () => {
    const counts = { onTime: 3, late: 1, missing: 2, reviewed: 1 };
    expect(MATERIAL_COUNTS.map((card) => countOf(counts, card))).toEqual([3, 1, 2, 1]);
    expect(countOf(undefined, 'missing')).toBe(0);
  });

  /** A submission id is absent until she hands it in, so the key is the pair she was assigned. */
  it('keys a row by its class and subject', () => {
    expect(rowKey(row())).toBe('c-1:math');
    expect(rowKey(row({ classId: 'c-1', subject: 'english' }))).not.toBe(rowKey(row()));
  });

  /**
   * Seven days on from a week start always lands inside the next week, whatever day a school's
   * week begins on — and the arithmetic is in UTC, so it does not move a day in another zone.
   */
  it('walks a week forwards and backwards as calendar dates', () => {
    expect(weekPlus('2026-10-18', 1)).toBe('2026-10-25');
    expect(weekPlus('2026-10-18', -1)).toBe('2026-10-11');
    // Across a month, a year, and the end of February.
    expect(weekPlus('2026-12-28', 1)).toBe('2027-01-04');
    expect(weekPlus('2028-02-21', 1)).toBe('2028-02-28');
    expect(weekPlus('not-a-date', 1)).toBe('not-a-date');
  });

  it('accepts only a plain date as a week', () => {
    expect(weekOf('2026-10-18')).toBe('2026-10-18');
    expect(weekOf(' 2026-10-18 ')).toBe('2026-10-18');
    expect(weekOf('2026-10')).toBe('');
    expect(weekOf(null)).toBe('');
  });

  /** The chat's own limits (`core/chat/chat-attachments.ts`): the server takes exactly these. */
  it('refuses a file the upload would refuse, and never says "too many" for one file', () => {
    expect(materialRefusalOf(file('plan.pdf', 'application/pdf', 1024))).toBeNull();
    expect(materialRefusalOf(file('plan.png', 'image/png', 1024))).toBeNull();
    expect(materialRefusalOf(file('plan.docx', 'application/msword', 1024))).toBe('type');
    expect(materialRefusalOf(file('plan.png', 'image/png', 6 * 1024 * 1024))).toBe('imageTooBig');
    expect(materialRefusalOf(file('plan.pdf', 'application/pdf', 11 * 1024 * 1024))).toBe('pdfTooBig');
  });

  it('puts the teachers with something missing at the top of the board', () => {
    const rows = [
      { teacherId: 't-1', fullName: 'Amy', counts: { missing: 0 } },
      { teacherId: 't-2', fullName: 'تالا', counts: { missing: 3 } },
      { teacherId: 't-3', fullName: 'Lina', counts: { missing: 1 } },
    ];
    expect(sortedBoardRows(rows).map((teacher) => teacher.teacherId)).toEqual(['t-2', 't-3', 't-1']);
  });

  it('puts the byte-order mark back on the CSV so Excel reads Arabic names', () => {
    expect(withBom('المعلمة,الصف\n').charCodeAt(0)).toBe(0xfeff);
    expect(withBom('﻿x')).toBe('﻿x');
    expect(csvName('2026-10-18')).toBe('teaching-material-2026-10-18.csv');
  });
});
