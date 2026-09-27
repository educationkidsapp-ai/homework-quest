import { describe, expect, it } from 'vitest';
import {
  type BroadcastDraft,
  type ComposeContext,
  EMPTY_DRAFT,
  canPost,
  composeErrors,
  endOfDayIn,
  isSunday,
  kindsFor,
  requestOf,
  sundayOf,
  weekOptions,
} from './broadcast.rules';

/**
 * RM3b. Every rule here is one the server answers a 400 to (`BroadcastService`), so each test is
 * really "would this post have come back as a red band".
 */
describe('the broadcast composer rules', () => {
  const SECTIONS = [
    { classId: 'c-1', curriculum: 'british' },
    { classId: 'c-2', curriculum: 'british' },
    { classId: 'c-3', curriculum: 'american' },
  ];

  /** Muscat, GMT+4 all year: the school's zone is what "the end of that day" is measured in. */
  const ZONE = 'Asia/Muscat';

  const manager = (departments: readonly string[]): ComposeContext => ({
    role: 'manager',
    departments,
    sections: SECTIONS,
    today: '2026-09-30',
    zone: ZONE,
  });

  const coordinator: ComposeContext = {
    role: 'coordinator',
    departments: [],
    sections: SECTIONS,
    today: '2026-09-30',
    zone: ZONE,
  };

  const plan = (over: Partial<BroadcastDraft> = {}): BroadcastDraft => ({
    ...EMPTY_DRAFT,
    kind: 'weekly_plan',
    title: 'Week of subtraction',
    bodyEn: 'Subtraction all week; swimming on Thursday.',
    weekStart: '2026-09-27',
    ...over,
  });

  it('takes a Sunday for a weekly plan and nothing else', () => {
    // 2026-09-27 is a Sunday, 2026-09-28 the Monday after it.
    expect(isSunday('2026-09-27')).toBe(true);
    expect(isSunday('2026-09-28')).toBe(false);
    expect(sundayOf('2026-09-30')).toBe('2026-09-27');

    const ctx = manager(['british']);
    expect(canPost(plan(), ctx)).toBe(true);
    expect(composeErrors(plan({ weekStart: '2026-09-28' }), ctx).weekStart).toBe(
      'broadcasts.errors.weekNotSunday',
    );
    expect(composeErrors(plan({ weekStart: '' }), ctx).weekStart).toBe('broadcasts.errors.weekRequired');

    // The picker can only offer Sundays, which is what makes the typed case above the only one.
    expect(weekOptions('2026-09-30', 3)).toEqual(['2026-09-27', '2026-10-04', '2026-10-11']);
  });

  it('drops weekStart from the body when the kind is not a plan', () => {
    // `weekStart belongs to a weekly_plan only` — 400 on the other two kinds, even though the
    // writer may well have picked a week before changing her mind about what she is writing.
    const body = requestOf(plan({ kind: 'event' }), manager(['british']));
    expect(body.weekStart).toBeUndefined();
    expect(body.kind).toBe('event');
  });

  it('makes a two-department manager choose, and says so on the wire as her sections', () => {
    const both = manager(['british', 'american']);
    expect(composeErrors(plan(), both).department).toBe('broadcasts.errors.departmentRequired');
    expect(canPost(plan(), both)).toBe(false);

    const chosen = plan({ department: 'british' });
    expect(composeErrors(chosen, both).department).toBeNull();
    // There is no `curriculum` field: naming the track's sections is how the row gets one, and
    // `oneTrack` on the server then reads it back off them.
    expect(requestOf(chosen, both).sectionIds).toEqual(['c-1', 'c-2']);

    // One department needs no choice, and names no section — the server reads that as all of it.
    expect(requestOf(plan(), manager(['british'])).sectionIds).toBeUndefined();
  });

  it('keeps her own class subset when she narrows it, whichever way she got there', () => {
    const both = manager(['british', 'american']);
    const narrowed = plan({ department: 'british', sectionIds: ['c-2'] });
    expect(requestOf(narrowed, both).sectionIds).toEqual(['c-2']);
  });

  it('gives the coordinator no weekly plan and no audience to pick', () => {
    expect(kindsFor('coordinator')).toEqual(['announcement', 'event']);
    expect(kindsFor('manager')).toContain('weekly_plan');

    const note: BroadcastDraft = {
      ...EMPTY_DRAFT,
      title: 'Reading week',
      bodyEn: 'Reading week starts on Sunday.',
      // Even if something put a wider audience on the draft, her request must not carry one: the
      // server sends hers to the parents of her classes and ignores the field.
      audience: ['parents', 'teachers'],
    };
    const body = requestOf(note, coordinator);
    expect(body.audience).toBeUndefined();
    expect('audience' in body).toBe(false);
    // And her own empty audience is not an error the way a manager's is.
    expect(composeErrors({ ...note, audience: [] }, coordinator).audience).toBeNull();
    expect(composeErrors({ ...note, audience: [] }, manager(['british'])).audience).toBe(
      'broadcasts.errors.audienceRequired',
    );
  });

  it('refuses an expiry in the past and sends the end of the chosen day', () => {
    const ctx = manager(['british']);
    expect(composeErrors(plan({ expires: '2026-09-29' }), ctx).expires).toBe(
      'broadcasts.errors.expiredAlready',
    );
    expect(composeErrors(plan({ expires: '2026-09-30' }), ctx).expires).toBeNull();
    // The end of that day **in the school's zone**, not in Greenwich: 23:59:59 in Muscat is
    // 19:59:59 UTC, and a row that expired at the wrong hour is a plan that vanishes mid-morning.
    expect(requestOf(plan({ expires: '2026-10-05' }), ctx).expiresAt).toBe(
      Date.parse('2026-10-05T19:59:59Z'),
    );
    expect(endOfDayIn('2026-10-05', 'UTC')).toBe(Date.parse('2026-10-05T23:59:59Z'));
    // A zone with DST, on a date inside it: New York is GMT-4 in October.
    expect(endOfDayIn('2026-10-05', 'America/New_York')).toBe(Date.parse('2026-10-06T03:59:59Z'));
    expect(requestOf(plan(), ctx).expiresAt).toBeUndefined();
  });

  it('wants a title and a body, within the lengths the server stores', () => {
    const ctx = manager(['british']);
    expect(composeErrors(plan({ title: '  ' }), ctx).title).toBe('broadcasts.errors.titleRequired');
    expect(composeErrors(plan({ title: 'x'.repeat(121) }), ctx).title).toBe('broadcasts.errors.titleTooLong');
    expect(composeErrors(plan({ bodyEn: '' }), ctx).bodyEn).toBe('broadcasts.errors.bodyRequired');
    expect(composeErrors(plan({ bodyAr: 'ب'.repeat(1001) }), ctx).bodyAr).toBe(
      'broadcasts.errors.bodyTooLong',
    );
    // An empty Arabic body is absent rather than `""`, which the server would store and show.
    expect(requestOf(plan(), ctx).bodyAr).toBeUndefined();
    expect(requestOf(plan({ bodyAr: 'الطرح' }), ctx).bodyAr).toBe('الطرح');
  });
});
