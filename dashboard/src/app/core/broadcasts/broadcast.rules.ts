import type { CreateBroadcastRequest } from '../../api';

/**
 * **What a composer may write, and what the server would refuse.**
 *
 * RM3b. The compose sheet is the one place in `/coordinator/**` and `/management/**` that writes
 * something a parent reads, and `BroadcastService` on the server answers six different 400s to
 * six different mistakes. Those rules live here as pure functions rather than inside the sheet so
 * they can be tested without a fixture, and so the *one* place that turns a draft into a body is
 * the same place that decides whether the button is enabled — a sheet that validates in one
 * expression and posts from another drifts, and the drift shows up as a red band.
 */
export type BroadcastKind = 'weekly_plan' | 'announcement' | 'event';
export type AudienceRole = 'parents' | 'teachers' | 'coordinators';

/** Which composer this is: the two have different kinds, different audiences and one scope each. */
export type ComposerRole = 'coordinator' | 'manager';

/**
 * MH2 item 5: **neither composer writes a plan here any more.**
 *
 * A weekly plan is a grade, a week and an image (`plan-rules.ts`), which shares no field with the
 * title-and-body sheet this file validates; the manager writes it on her Weekly plans screen, and
 * `weekly_plan` is 400 on a coordinator's route anyway (DR6). `BroadcastKind` keeps the value
 * because `BroadcastView.kind` still answers it.
 */
export const MANAGER_KINDS: readonly BroadcastKind[] = ['announcement', 'event'];
export const COORDINATOR_KINDS: readonly BroadcastKind[] = ['announcement', 'event'];
export const AUDIENCE_ROLES: readonly AudienceRole[] = ['parents', 'teachers', 'coordinators'];

export const MAX_TITLE = 120;
export const MAX_BODY = 1000;

export interface BroadcastDraft {
  readonly kind: BroadcastKind;
  readonly title: string;
  readonly bodyEn: string;
  readonly bodyAr: string;
  /** The manager's choice; a coordinator's audience is always the parents of her classes. */
  readonly audience: readonly AudienceRole[];
  /**
   * MG2b: one grade of the department she is writing for, or `null` for every grade of it.
   *
   * **Two ways on the wire, one question on the sheet** (feature 22). The server takes `grade`
   * only on a row that names no section, and it refuses a row with no sections at all from an
   * author who spans two departments (`BroadcastService.one` — "name the sections this is for").
   * So one grade of one department is sent as `grade` by an author who holds that department
   * alone, and as **that grade's `sectionIds`** by one who holds more than it — which is the same
   * audience said the only way the server will accept it. {@link requestOf} is where that is
   * decided, and it is the reason the Admin (whose scope is every academy) can name a grade at all.
   */
  readonly grade: number | null;
  /** The department a multi-department author is writing for; `''` when she holds only one. */
  readonly department: string;
  /** An optional narrowing to some of her sections. Empty is "all of them". */
  readonly sectionIds: readonly string[];
  /** `YYYY-MM-DD` or `''`. */
  readonly expires: string;
}

/** A section the composer may name, with the track it is in. */
export interface ComposableSection {
  readonly classId: string;
  readonly curriculum: string;
  readonly grade: number;
}

export interface ComposeContext {
  readonly role: ComposerRole;
  /** Her departments, as `GET /management/me` spells them. Empty for a coordinator. */
  readonly departments: readonly string[];
  readonly sections: readonly ComposableSection[];
  /** Today in the **school's** timezone, `YYYY-MM-DD`. */
  readonly today: string;
  /** The school's IANA timezone: what "the end of that day" means to the people reading this. */
  readonly zone: string;
}

/** One translation key per field, or `null` while that field is fine. */
export interface ComposeErrors {
  readonly title: string | null;
  readonly bodyEn: string | null;
  readonly bodyAr: string | null;
  readonly audience: string | null;
  readonly department: string | null;
  readonly grade: string | null;
  readonly expires: string | null;
}

export const EMPTY_DRAFT: BroadcastDraft = {
  kind: 'announcement',
  title: '',
  bodyEn: '',
  bodyAr: '',
  audience: ['parents'],
  department: '',
  grade: null,
  sectionIds: [],
  expires: '',
};

export function kindsFor(role: ComposerRole): readonly BroadcastKind[] {
  return role === 'manager' ? MANAGER_KINDS : COORDINATOR_KINDS;
}

/**
 * The last second of `day` **in the school's zone**, as epoch millis.
 *
 * `T23:59:59Z` would be the end of the day in Greenwich, which in Muscat is 03:59 the next
 * morning and in Los Angeles is four in the afternoon of the same one. `today` is already read in
 * the school's zone (`broadcasts.page.ts`), so an expiry that was not would be the one date on the
 * sheet that meant somewhere else's day.
 */
export function endOfDayIn(day: string, zone: string): number {
  const asUtc = Date.parse(`${day}T23:59:59Z`);
  if (Number.isNaN(asUtc)) return NaN;
  return asUtc - zoneOffset(asUtc, zone);
}

/**
 * The inverse of {@link endOfDayIn}: the calendar day an instant falls on **in the school's
 * zone**, as `YYYY-MM-DD` — which is what the date input on the sheet speaks.
 *
 * Feature 22: the server answers a suggested expiry as epoch millis (the end of a day in the
 * school's zone), and `new Date(ms).toISOString().slice(0, 10)` would read that back as the day it
 * was in Greenwich — one day early for every evening in Riyadh. `en-CA` is the one locale that
 * formats as `YYYY-MM-DD`, which is also how `announcements.page.ts` reads today.
 */
export function dayIn(at: number, zone: string): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: zone }).format(new Date(at));
}

/** The zone's offset from UTC at that instant, in millis — DST included, because `Intl` knows. */
function zoneOffset(at: number, zone: string): number {
  const named = new Intl.DateTimeFormat('en-US', { timeZone: zone, timeZoneName: 'longOffset' })
    .formatToParts(new Date(at))
    .find((part) => part.type === 'timeZoneName')?.value;
  const parsed = /GMT([+-])(\d{2}):(\d{2})/.exec(named ?? '');
  if (parsed === null) return 0;
  const minutes = Number(parsed[2]) * 60 + Number(parsed[3]);
  return (parsed[1] === '-' ? -minutes : minutes) * 60_000;
}

/** `YYYY-MM-DD` parsed as a calendar day, never as a local instant — `Date.parse` of a bare date is UTC. */
function dayOf(iso: string): Date {
  return new Date(`${iso}T00:00:00Z`);
}

export function isSunday(iso: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(iso)) return false;
  const day = dayOf(iso);
  return !Number.isNaN(day.getTime()) && day.getUTCDay() === 0;
}

/** The Sunday of the school week `iso` falls in — the day the server snaps `weekStart` back to. */
export function sundayOf(iso: string): string {
  const day = dayOf(iso);
  day.setUTCDate(day.getUTCDate() - day.getUTCDay());
  return day.toISOString().slice(0, 10);
}

/**
 * The weeks a plan may be posted for: this one and the next `count - 1`.
 *
 * A list of Sundays rather than a date input, because "any date in the week, snapped back" is a
 * rule the writer cannot see and "this week or next" is the choice she actually makes.
 */
export function weekOptions(today: string, count = 5): readonly string[] {
  const first = sundayOf(today);
  return Array.from({ length: count }, (_, index) => {
    const day = dayOf(first);
    day.setUTCDate(day.getUTCDate() + index * 7);
    return day.toISOString().slice(0, 10);
  });
}

/**
 * The department a row will carry, or `null` when the request would leave it unset.
 *
 * `CreateBroadcastRequest` has **no `curriculum` field**: the server reads the track off the
 * sections the row names, or off her one department when it names none (`BroadcastService.one`).
 * So "choose a department" in the sheet is "name that department's sections" on the wire, and
 * this is the function both the validation and the body go through.
 */
export function departmentOf(draft: BroadcastDraft, ctx: ComposeContext): string | null {
  if (ctx.role === 'coordinator') return null;
  if (ctx.departments.length === 1) return ctx.departments[0] ?? null;
  return draft.department === '' ? null : draft.department;
}

/**
 * The grades a manager may name, in her chosen department — sorted, distinct, from her own classes.
 *
 * Her sections rather than 1…12: `grade(…)` on the server refuses a grade she manages no class in
 * with a 400, so a list built from anything else is a list of buttons that answer a red band.
 */
export function gradeOptions(draft: BroadcastDraft, ctx: ComposeContext): readonly number[] {
  const department = departmentOf(draft, ctx);
  const grades = ctx.sections
    .filter((row) => department === null || row.curriculum === department)
    .map((row) => row.grade);
  return [...new Set(grades)].sort((a, b) => a - b);
}

/**
 * Whether a grade may be named at all: the manager's composer, and not the coordinator's — whose
 * classes *are* her audience, which is the same question one level down.
 *
 * Feature 22 widened this. It used to be the manager of **one** department only, because `grade`
 * on the wire is refused beside named sections and a two-department row must name sections; the
 * answer to that is to say the grade *as* those sections ({@link requestOf}), not to take the
 * choice away. Without it the Admin — whose scope is every academy of the school — could not name
 * a grade at all, and the audience the voice assistant suggests could not be followed.
 */
export function canChooseGrade(ctx: ComposeContext): boolean {
  return ctx.role === 'manager';
}

export function composeErrors(draft: BroadcastDraft, ctx: ComposeContext): ComposeErrors {
  const title = draft.title.trim();
  const bodyEn = draft.bodyEn.trim();
  const department = departmentOf(draft, ctx);
  return {
    title:
      title === ''
        ? 'broadcasts.errors.titleRequired'
        : title.length > MAX_TITLE
          ? 'broadcasts.errors.titleTooLong'
          : null,
    bodyEn:
      bodyEn === ''
        ? 'broadcasts.errors.bodyRequired'
        : bodyEn.length > MAX_BODY
          ? 'broadcasts.errors.bodyTooLong'
          : null,
    bodyAr: draft.bodyAr.trim().length > MAX_BODY ? 'broadcasts.errors.bodyTooLong' : null,
    audience:
      ctx.role === 'manager' && draft.audience.length === 0 ? 'broadcasts.errors.audienceRequired' : null,
    // Every kind, not only a plan: a manager of two departments who names no section is
    // "You manage more than one department" whatever she is writing.
    department:
      ctx.role === 'manager' && ctx.departments.length > 1 && department === null
        ? 'broadcasts.errors.departmentRequired'
        : null,
    grade: gradeError(draft, ctx),
    expires: draft.expires !== '' && draft.expires < ctx.today ? 'broadcasts.errors.expiredAlready' : null,
  };
}

/**
 * The three ways a grade is refused, in the order the server refuses them.
 *
 * "A manager of two departments may not name a grade" is no longer one of them: that row names the
 * grade's sections instead ({@link requestOf}), which is the shape the server asked for. What
 * stays is the pair that are genuinely a contradiction — a grade beside a hand-picked class list —
 * and a grade with no class of hers in it, which the server answers 400 to either way.
 */
function gradeError(draft: BroadcastDraft, ctx: ComposeContext): string | null {
  if (draft.grade === null) return null;
  if (ctx.role !== 'manager') return 'broadcasts.errors.gradeManagerOnly';
  if (draft.sectionIds.length > 0) return 'broadcasts.errors.gradeWithSections';
  return gradeOptions(draft, ctx).includes(draft.grade) ? null : 'broadcasts.errors.gradeUnmanaged';
}

export function canPost(draft: BroadcastDraft, ctx: ComposeContext): boolean {
  return Object.values(composeErrors(draft, ctx)).every((error) => error === null);
}

/**
 * The body, built once from the draft this screen holds.
 *
 * Absent rather than empty throughout: the server reads `""` as a body it must store, and an
 * empty `sectionIds` as "no section at all" rather than as "every one of mine".
 */
export function requestOf(draft: BroadcastDraft, ctx: ComposeContext): CreateBroadcastRequest {
  const bodyAr = draft.bodyAr.trim();
  const expiresAt = draft.expires === '' ? NaN : endOfDayIn(draft.expires, ctx.zone);
  const sectionIds = sectionsOf(draft, ctx);
  // "Name the sections or the grade, not both": the sections already say which grade is meant, so
  // a row that names them drops the field rather than sending the same fact twice.
  const named = sectionIds.length > 0;
  return {
    kind: draft.kind,
    title: draft.title.trim(),
    bodyEn: draft.bodyEn.trim(),
    ...(bodyAr === '' ? {} : { bodyAr }),
    // A coordinator's audience is the parents of her classes and the server does not read the
    // field at all; sending one would be a promise the screen cannot keep.
    ...(ctx.role === 'manager' ? { audience: [...draft.audience] } : {}),
    ...(draft.grade === null || named ? {} : { grade: draft.grade }),
    ...(named ? { sectionIds } : {}),
    ...(Number.isNaN(expiresAt) ? {} : { expiresAt }),
  };
}

/**
 * The sections the row names: the writer's own subset when she picked one, otherwise the chosen
 * department's — which is how an author of more than one says "the British track" on a wire that
 * has no field for it, and, with a grade, "grade 3 of the British track".
 *
 * **One department names nothing.** The server reads her scope off her own account and takes
 * `grade` directly, so an empty list is "the whole of it" and the narrower statement stays the
 * field it was (feature 22 changed nothing for her).
 */
function sectionsOf(draft: BroadcastDraft, ctx: ComposeContext): string[] {
  if (draft.sectionIds.length > 0) return [...draft.sectionIds];
  if (ctx.departments.length <= 1) return [];
  const department = departmentOf(draft, ctx);
  // Not yet chosen: `composeErrors` is already asking her which department, so there is nothing
  // this could name that would not be a guess at her audience.
  if (department === null) return [];
  return ctx.sections
    .filter((row) => row.curriculum === department && (draft.grade === null || row.grade === draft.grade))
    .map((row) => row.classId);
}
