import type { BoardCounts, BoardRow } from '../../api';

/**
 * Feature 17's board, the part with no Angular in it: which rows a card or a filter shows. The
 * server answers the counts; these functions answer the table, and the spec checks that a card's
 * number and the rows it filters to are the same thing.
 */

/** How often the Today view asks again — the spec's "within 30 seconds at most". */
export const REFRESH_MS = 30_000;

/** The five cards, in the spec's order. `noCheckOut` is a flag a present row can also carry. */
export const CARDS = ['present', 'late', 'notArrived', 'remote', 'noCheckOut'] as const;
export type Card = (typeof CARDS)[number];

/** Every status a row can have, for the status filter. */
export const STATUSES = [
  'PRESENT',
  'LATE',
  'NOT_ARRIVED',
  'REMOTE',
  'ABSENT',
  'UPCOMING',
  'OFF',
  'HOLIDAY',
] as const;
export type Status = (typeof STATUSES)[number];

const CARD_STATUS: Readonly<Record<Exclude<Card, 'noCheckOut'>, Status>> = {
  present: 'PRESENT',
  late: 'LATE',
  notArrived: 'NOT_ARRIVED',
  remote: 'REMOTE',
};

export interface BoardFilter {
  readonly card: Card | null;
  readonly search: string;
  readonly status: string;
  readonly academy: string;
  readonly category: string;
}

export const NO_FILTER: BoardFilter = { card: null, search: '', status: '', academy: '', category: '' };

export function cardMatches(card: Card, row: BoardRow): boolean {
  return card === 'noCheckOut' ? row.noCheckOut === true : row.status === CARD_STATUS[card];
}

/** The card's number as the server counted it. */
export function countOf(counts: BoardCounts | undefined, card: Card): number {
  return counts?.[card] ?? 0;
}

/** Search is by name, case- and accent-insensitive enough for Latin names and exact for Arabic. */
export function filterRows(rows: readonly BoardRow[], filter: BoardFilter): readonly BoardRow[] {
  const needle = filter.search.trim().toLocaleLowerCase();
  return rows.filter(
    (row) =>
      (filter.card === null || cardMatches(filter.card, row)) &&
      (needle === '' || (row.fullName ?? '').toLocaleLowerCase().includes(needle)) &&
      (filter.status === '' || row.status === filter.status) &&
      (filter.academy === '' || (row.academies ?? []).includes(filter.academy)) &&
      (filter.category === '' || row.category === filter.category),
  );
}

/** The academies present on the board, in name order, for the academy filter. */
export function academiesOf(rows: readonly { academies?: string[] }[]): readonly string[] {
  return [...new Set(rows.flatMap((row) => row.academies ?? []))].sort();
}

/**
 * Feature 21: the card a `?status=` in the link stands for.
 *
 * The "What needs me today?" Home links each of its four attendance numbers here, and the card
 * *is* the filter — so the link does not need a filter language of its own. `noCheckOut` is not
 * offered: it is a flag a present row also carries, and no card on that Home counts it.
 */
export function cardFromStatus(status: string | null | undefined): Card | null {
  const card = (status ?? '').trim();
  return card !== 'noCheckOut' && (CARDS as readonly string[]).includes(card) ? (card as Card) : null;
}

/** Tapping the card that is already on turns the filter off. */
export function toggleCard(filter: BoardFilter, card: Card): BoardFilter {
  return { ...filter, card: filter.card === card ? null : card };
}

const BOM = '\uFEFF';

/**
 * The CSV as the server wrote it, with its byte-order mark put back: the client reads the body
 * as text, which drops the BOM, and without it Excel reads Arabic names as the local code page.
 */
export function withBom(csv: string): string {
  return csv.startsWith(BOM) ? csv : `${BOM}${csv}`;
}
