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

export const MANAGER_KINDS: readonly BroadcastKind[] = ['weekly_plan', 'announcement', 'event'];
/** DR6: the weekly plan is the department's, so `weekly_plan` is 400 on her route. */
export const COORDINATOR_KINDS: readonly BroadcastKind[] = ['announcement', 'event'];
export const AUDIENCE_ROLES: readonly AudienceRole[] = ['parents', 'teachers', 'coordinators'];

export const MAX_TITLE = 120;
export const MAX_BODY = 1000;

export interface BroadcastDraft {
  readonly kind: BroadcastKind;
  /** `YYYY-MM-DD`, a Sunday, and only for a plan. */
  readonly weekStart: string;
  readonly title: string;
  readonly bodyEn: string;
  readonly bodyAr: string;
  /** The manager's choice; a coordinator's audience is always the parents of her classes. */
  readonly audience: readonly AudienceRole[];
  /** The department a two-department manager is writing for; `''` when she holds only one. */
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
}

export interface ComposeContext {
  readonly role: ComposerRole;
  /** Her departments, as `GET /management/me` spells them. Empty for a coordinator. */
  readonly departments: readonly string[];
  readonly sections: readonly ComposableSection[];
  /** Today in the **school's** timezone, `YYYY-MM-DD`. */
  readonly today: string;
}

/** One translation key per field, or `null` while that field is fine. */
export interface ComposeErrors {
  readonly weekStart: string | null;
  readonly title: string | null;
  readonly bodyEn: string | null;
  readonly bodyAr: string | null;
  readonly audience: string | null;
  readonly department: string | null;
  readonly expires: string | null;
}

export const EMPTY_DRAFT: BroadcastDraft = {
  kind: 'announcement',
  weekStart: '',
  title: '',
  bodyEn: '',
  bodyAr: '',
  audience: ['parents'],
  department: '',
  sectionIds: [],
  expires: '',
};

export function kindsFor(role: ComposerRole): readonly BroadcastKind[] {
  return role === 'manager' ? MANAGER_KINDS : COORDINATOR_KINDS;
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

export function composeErrors(draft: BroadcastDraft, ctx: ComposeContext): ComposeErrors {
  const plan = draft.kind === 'weekly_plan';
  const title = draft.title.trim();
  const bodyEn = draft.bodyEn.trim();
  const department = departmentOf(draft, ctx);
  return {
    weekStart: !plan
      ? null
      : draft.weekStart === ''
        ? 'broadcasts.errors.weekRequired'
        : isSunday(draft.weekStart)
          ? null
          : 'broadcasts.errors.weekNotSunday',
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
    expires: draft.expires !== '' && draft.expires < ctx.today ? 'broadcasts.errors.expiredAlready' : null,
  };
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
  const plan = draft.kind === 'weekly_plan';
  const bodyAr = draft.bodyAr.trim();
  const expiresAt = draft.expires === '' ? NaN : Date.parse(`${draft.expires}T23:59:59Z`);
  const sectionIds = sectionsOf(draft, ctx);
  return {
    kind: draft.kind,
    title: draft.title.trim(),
    bodyEn: draft.bodyEn.trim(),
    ...(bodyAr === '' ? {} : { bodyAr }),
    // `weekStart` belongs to a plan and is 400 on the other two kinds, so it is dropped rather
    // than sent empty when the writer changes her mind about the kind with a week still picked.
    ...(plan ? { weekStart: draft.weekStart } : {}),
    // A coordinator's audience is the parents of her classes and the server does not read the
    // field at all; sending one would be a promise the screen cannot keep.
    ...(ctx.role === 'manager' ? { audience: [...draft.audience] } : {}),
    ...(sectionIds.length === 0 ? {} : { sectionIds }),
    ...(Number.isNaN(expiresAt) ? {} : { expiresAt }),
  };
}

/**
 * The sections the row names: the writer's own subset when she picked one, otherwise the chosen
 * department's — which is how a two-department manager says "the British track" on a wire that
 * has no field for it. One department and no subset names nothing, and the server reads that as
 * the whole of it.
 */
function sectionsOf(draft: BroadcastDraft, ctx: ComposeContext): string[] {
  if (draft.sectionIds.length > 0) return [...draft.sectionIds];
  const department = departmentOf(draft, ctx);
  if (department === null || ctx.departments.length <= 1) return [];
  return ctx.sections.filter((row) => row.curriculum === department).map((row) => row.classId);
}
