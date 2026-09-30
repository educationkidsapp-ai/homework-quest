import type { CreateBroadcastRequest } from '../../api';
import { isSunday } from './broadcast.rules';

/**
 * **What a weekly plan is, and what the server would refuse** (MH2 item 4).
 *
 * MH1 redefined it: a plan is a **grade, a week and an image**, and nothing else —
 * `BroadcastService.managerPost` answers 400 to a plan with no grade ("A weekly plan is for one
 * grade"), to one with no `attachmentId` ("A weekly plan is an image") and to one it cannot pin to
 * a single department. There is no title and no body on the wire at all.
 *
 * That shares no field with the announcement sheet, so it has its own rules file rather than four
 * more branches in `broadcast.rules.ts`. Pure functions, for the same two reasons as there: they
 * can be tested without a fixture, and the one place that turns a draft into a body is the same
 * place that decides whether the button is enabled.
 */

/** What `POST /media/attachments` accepts (MH1): a picture of a printed plan, in a web format. */
export const PLAN_IMAGE_TYPES: readonly string[] = ['image/jpeg', 'image/png', 'image/webp'];

/** The server's own cap. Checked here too, so a 6 MB scan is a sentence rather than a red band. */
export const PLAN_IMAGE_MAX_BYTES = 5 * 1024 * 1024;

export interface PlanDraft {
  /** `YYYY-MM-DD`, and a Sunday. */
  readonly weekStart: string;
  /** One grade of her department. Never `null` on a valid draft — there is no all-grades plan. */
  readonly grade: number | null;
  /** The picked file, before it is uploaded. `null` until she picks one. */
  readonly file: File | null;
}

export const EMPTY_PLAN_DRAFT: PlanDraft = { weekStart: '', grade: null, file: null };

export interface PlanContext {
  /** The grades she manages, from her own classes — the only grades the server will accept. */
  readonly grades: readonly number[];
  /** Her departments. More than one and she cannot post a plan at all — see {@link planBlocked}. */
  readonly departments: readonly string[];
}

export interface PlanErrors {
  readonly weekStart: string | null;
  readonly grade: string | null;
  readonly file: string | null;
}

/**
 * A manager of two departments cannot post a plan, and the sheet says so instead of offering one.
 *
 * Not a preference: the server needs the row pinned to one track, which it reads off the sections a
 * row names — and a row that names sections may not carry the `grade` a plan requires
 * (`BroadcastService.grade`). The two rules together leave her no valid request to send.
 */
export function planBlocked(ctx: PlanContext): boolean {
  return ctx.departments.length > 1;
}

/** Why this file cannot be a plan, or `null`. The two checks the server makes, made first. */
export function imageError(file: File | null): string | null {
  if (file === null) return 'plans.errors.imageRequired';
  if (!PLAN_IMAGE_TYPES.includes(file.type)) return 'plans.errors.imageType';
  return file.size > PLAN_IMAGE_MAX_BYTES ? 'plans.errors.imageTooBig' : null;
}

export function planErrors(draft: PlanDraft, ctx: PlanContext): PlanErrors {
  return {
    weekStart:
      draft.weekStart === ''
        ? 'broadcasts.errors.weekRequired'
        : isSunday(draft.weekStart)
          ? null
          : 'broadcasts.errors.weekNotSunday',
    grade:
      draft.grade === null
        ? 'plans.errors.gradeRequired'
        : ctx.grades.includes(draft.grade)
          ? null
          : 'broadcasts.errors.gradeUnmanaged',
    file: imageError(draft.file),
  };
}

export function canPostPlan(draft: PlanDraft, ctx: PlanContext): boolean {
  if (planBlocked(ctx)) return false;
  return Object.values(planErrors(draft, ctx)).every((error) => error === null);
}

/**
 * The body, once the image has been uploaded and its id is known.
 *
 * No `audience`: the server gives a plan its own — the parents, the teachers and the coordinators
 * of that grade (DR6) — and would ignore anything sent. No `sectionIds` either: they are what would
 * make the `grade` a 400.
 */
export function planRequestOf(draft: PlanDraft, attachmentId: string): CreateBroadcastRequest {
  return {
    kind: 'weekly_plan',
    weekStart: draft.weekStart,
    ...(draft.grade === null ? {} : { grade: draft.grade }),
    attachmentId,
  };
}
