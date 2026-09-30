import { describe, expect, it } from 'vitest';
import {
  type BroadcastDraft,
  type ComposeContext,
  EMPTY_DRAFT,
  canChooseGrade,
  canPost,
  composeErrors,
  gradeOptions,
  endOfDayIn,
  isSunday,
  kindsFor,
  requestOf,
  sundayOf,
  weekOptions,
} from './broadcast.rules';

/**
 * RM3b, narrowed by MH2 item 5 — this sheet writes an **announcement or an event**, never a plan.
 * Every rule here is one the server answers a 400 to (`BroadcastService`), so each test is really
 * "would this post have come back as a red band".
 */
describe('the announcement composer rules', () => {
  const SECTIONS = [
    { classId: 'c-1', curriculum: 'british', grade: 1 },
    { classId: 'c-2', curriculum: 'british', grade: 3 },
    { classId: 'c-3', curriculum: 'american', grade: 5 },
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

  const note = (over: Partial<BroadcastDraft> = {}): BroadcastDraft => ({
    ...EMPTY_DRAFT,
    kind: 'announcement',
    title: 'Reading week',
    bodyEn: 'Reading week starts on Sunday.',
    ...over,
  });

  it('offers neither composer a weekly plan any more', () => {
    // MH2 item 5: a plan is a grade, a week and an image, written on the manager's own screen from
    // `plan-rules.ts`. Neither of these two sheets can produce one, so neither offers the kind.
    expect(kindsFor('coordinator')).toEqual(['announcement', 'event']);
    expect(kindsFor('manager')).toEqual(['announcement', 'event']);
  });

  it('still knows what a school week is, for the screen that does write plans', () => {
    // 2026-09-27 is a Sunday, 2026-09-28 the Monday after it.
    expect(isSunday('2026-09-27')).toBe(true);
    expect(isSunday('2026-09-28')).toBe(false);
    expect(sundayOf('2026-09-30')).toBe('2026-09-27');
    expect(weekOptions('2026-09-30', 3)).toEqual(['2026-09-27', '2026-10-04', '2026-10-11']);
  });

  it('makes a two-department manager choose, and says so on the wire as her sections', () => {
    const both = manager(['british', 'american']);
    expect(composeErrors(note(), both).department).toBe('broadcasts.errors.departmentRequired');
    expect(canPost(note(), both)).toBe(false);

    const chosen = note({ department: 'british' });
    expect(composeErrors(chosen, both).department).toBeNull();
    // There is no `curriculum` field: naming the track's sections is how the row gets one, and
    // `oneTrack` on the server then reads it back off them.
    expect(requestOf(chosen, both).sectionIds).toEqual(['c-1', 'c-2']);

    // One department needs no choice, and names no section — the server reads that as all of it.
    expect(requestOf(note(), manager(['british'])).sectionIds).toBeUndefined();
  });

  it('keeps her own class subset when she narrows it, whichever way she got there', () => {
    const both = manager(['british', 'american']);
    const narrowed = note({ department: 'british', sectionIds: ['c-2'] });
    expect(requestOf(narrowed, both).sectionIds).toEqual(['c-2']);
  });

  it('gives the coordinator no audience to pick', () => {
    const hers = note({
      // Even if something put a wider audience on the draft, her request must not carry one: the
      // server sends hers to the parents of her classes and ignores the field.
      audience: ['parents', 'teachers'],
    });
    const body = requestOf(hers, coordinator);
    expect(body.audience).toBeUndefined();
    expect('audience' in body).toBe(false);
    // And her own empty audience is not an error the way a manager's is.
    expect(composeErrors({ ...hers, audience: [] }, coordinator).audience).toBeNull();
    expect(composeErrors({ ...hers, audience: [] }, manager(['british'])).audience).toBe(
      'broadcasts.errors.audienceRequired',
    );
  });

  it('refuses an expiry in the past and sends the end of the chosen day', () => {
    const ctx = manager(['british']);
    expect(composeErrors(note({ expires: '2026-09-29' }), ctx).expires).toBe(
      'broadcasts.errors.expiredAlready',
    );
    expect(composeErrors(note({ expires: '2026-09-30' }), ctx).expires).toBeNull();
    // The end of that day **in the school's zone**, not in Greenwich: 23:59:59 in Muscat is
    // 19:59:59 UTC, and a row that expired at the wrong hour is a note that vanishes mid-morning.
    expect(requestOf(note({ expires: '2026-10-05' }), ctx).expiresAt).toBe(
      Date.parse('2026-10-05T19:59:59Z'),
    );
    expect(endOfDayIn('2026-10-05', 'UTC')).toBe(Date.parse('2026-10-05T23:59:59Z'));
    // A zone with DST, on a date inside it: New York is GMT-4 in October.
    expect(endOfDayIn('2026-10-05', 'America/New_York')).toBe(Date.parse('2026-10-06T03:59:59Z'));
    expect(requestOf(note(), ctx).expiresAt).toBeUndefined();
  });

  it('wants a title and a body, within the lengths the server stores', () => {
    const ctx = manager(['british']);
    expect(composeErrors(note({ title: '  ' }), ctx).title).toBe('broadcasts.errors.titleRequired');
    expect(composeErrors(note({ title: 'x'.repeat(121) }), ctx).title).toBe('broadcasts.errors.titleTooLong');
    expect(composeErrors(note({ bodyEn: '' }), ctx).bodyEn).toBe('broadcasts.errors.bodyRequired');
    expect(composeErrors(note({ bodyAr: 'ب'.repeat(1001) }), ctx).bodyAr).toBe(
      'broadcasts.errors.bodyTooLong',
    );
    // An empty Arabic body is absent rather than `""`, which the server would store and show.
    expect(requestOf(note(), ctx).bodyAr).toBeUndefined();
    expect(requestOf(note({ bodyAr: 'الطرح' }), ctx).bodyAr).toBe('الطرح');
  });

  /**
   * MH2 item 5: **the manager's audience is a grade or the whole department**, which is the one
   * question the grade select and the class checkboxes were asking twice. `grade` is what the server
   * takes, and it takes it on exactly one shape of request.
   */
  it('sends a grade on its own, never beside a class list', () => {
    const ctx = manager(['british']);
    expect(canChooseGrade(ctx)).toBe(true);
    // Her own grades in that department, sorted — a grade she manages no class in is a 400.
    expect(gradeOptions(note(), ctx)).toEqual([1, 3]);

    expect(requestOf(note({ grade: 3 }), ctx).grade).toBe(3);
    // The whole department is the *absence* of the field, not a zero or a null.
    expect('grade' in requestOf(note(), ctx)).toBe(false);

    // The two are mutually exclusive on the wire ("name the sections or the grade, not both").
    expect(composeErrors(note({ grade: 3, sectionIds: ['c-1'] }), ctx).grade).toBe(
      'broadcasts.errors.gradeWithSections',
    );
    expect(composeErrors(note({ grade: 9 }), ctx).grade).toBe('broadcasts.errors.gradeUnmanaged');
  });

  /**
   * **The two-department rule** — the one the sheet cannot post its way around.
   *
   * `BroadcastService.grade` refuses `grade` beside named sections, and `BroadcastService.one`
   * refuses a row with *no* named sections from a manager who holds two departments ("name the
   * sections this is for"). Together: she can write to a whole department by naming its sections,
   * and to no single grade at all.
   */
  it('refuses a grade from a manager of two departments, and still names her department', () => {
    const ctx = manager(['british', 'american']);
    expect(canChooseGrade(ctx)).toBe(false);
    expect(gradeOptions(note({ department: 'british' }), ctx)).toEqual([1, 3]);
    expect(composeErrors(note({ department: 'british', grade: 1 }), ctx).grade).toBe(
      'broadcasts.errors.gradeOneDepartment',
    );
    expect(canPost(note({ department: 'british', grade: 1 }), ctx)).toBe(false);

    const body = requestOf(note({ department: 'british' }), ctx);
    expect(body.sectionIds).toEqual(['c-1', 'c-2']);
    expect('grade' in body).toBe(false);
    expect(canPost(note({ department: 'british' }), ctx)).toBe(true);
  });
});
