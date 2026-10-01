import { describe, expect, it } from 'vitest';
import {
  type PlanContext,
  type PlanDraft,
  EMPTY_PLAN_DRAFT,
  PLAN_IMAGE_MAX_BYTES,
  PLAN_PDF_MAX_BYTES,
  canPostPlan,
  isPdfFile,
  planBlocked,
  planErrors,
  planFileError,
  planRequestOf,
} from './plan-rules';

/**
 * MH2 item 4. Each of these is a 400 `BroadcastService.managerPost` or `MediaController` would
 * answer, so each test is "would this plan have come back as a red band".
 */
describe('the weekly-plan composer rules', () => {
  const one: PlanContext = { grades: [1, 3], departments: ['british'] };
  const both: PlanContext = { grades: [1, 3, 5], departments: ['british', 'american'] };

  /** A file of a given type and size, without allocating five megabytes to prove a cap. */
  function file(type: string, size = 1024): File {
    const it = new File(['x'], 'plan.png', { type });
    Object.defineProperty(it, 'size', { value: size });
    return it;
  }

  const draft = (over: Partial<PlanDraft> = {}): PlanDraft => ({
    ...EMPTY_PLAN_DRAFT,
    weekStart: '2026-09-27',
    grade: 3,
    file: file('image/png'),
    ...over,
  });

  it('wants a grade, a Sunday and an image — all three', () => {
    expect(canPostPlan(draft(), one)).toBe(true);

    // MH1: "A weekly plan is for one grade." There is no all-grades plan to fall back to.
    expect(planErrors(draft({ grade: null }), one).grade).toBe('plans.errors.gradeRequired');
    expect(planErrors(draft({ grade: 9 }), one).grade).toBe('broadcasts.errors.gradeUnmanaged');

    expect(planErrors(draft({ weekStart: '2026-09-28' }), one).weekStart).toBe(
      'broadcasts.errors.weekNotSunday',
    );
    expect(planErrors(draft({ weekStart: '' }), one).weekStart).toBe('broadcasts.errors.weekRequired');

    // "A weekly plan is an image: upload it to /media/attachments first and send attachmentId."
    expect(planErrors(draft({ file: null }), one).file).toBe('plans.errors.imageRequired');
  });

  it('refuses a file the upload route would refuse, before it is uploaded', () => {
    // The two rules `MediaController` enforces, enforced here first: a 6 MB scan that uploads for
    // twenty seconds and then answers 413 has wasted the twenty seconds.
    expect(planFileError(file('image/gif'))).toBe('plans.errors.imageType');
    expect(planFileError(file('image/png', PLAN_IMAGE_MAX_BYTES + 1))).toBe('plans.errors.imageTooBig');
    expect(planFileError(file('image/png', PLAN_IMAGE_MAX_BYTES))).toBeNull();
    expect(planFileError(file('image/jpeg'))).toBeNull();
    expect(planFileError(file('image/webp'))).toBeNull();
    expect(canPostPlan(draft({ file: file('image/gif') }), one)).toBe(false);
  });

  it('accepts a PDF up to its own, larger cap, and tells one from a picture by type then by name', () => {
    // List 3 (D2): `POST /media/attachments` takes `application/pdf` up to 10 MB.
    expect(planFileError(file('application/pdf'))).toBeNull();
    expect(planFileError(file('application/pdf', PLAN_IMAGE_MAX_BYTES + 1))).toBeNull();
    expect(planFileError(file('application/pdf', PLAN_PDF_MAX_BYTES))).toBeNull();
    expect(planFileError(file('application/pdf', PLAN_PDF_MAX_BYTES + 1))).toBe('plans.errors.pdfTooBig');
    expect(canPostPlan(draft({ file: file('application/pdf') }), one)).toBe(true);

    expect(isPdfFile('application/pdf', 'plan.png')).toBe(true);
    expect(isPdfFile('image/png', 'plan.pdf')).toBe(false);
    // A row with no type on it still has the file's own name.
    expect(isPdfFile('', 'Grade 3.PDF')).toBe(true);
    expect(isPdfFile(undefined, 'grade-3.jpg')).toBe(false);
  });

  it('cannot be posted at all by a manager of two departments', () => {
    // `grade` is required and is refused beside the `sectionIds` that would say which track the row
    // is for — so there is no request she could send, and the sheet says so instead of offering one.
    expect(planBlocked(both)).toBe(true);
    expect(planBlocked(one)).toBe(false);
    expect(canPostPlan(draft(), both)).toBe(false);
  });

  it('sends the grade, the week and the attachment, and nothing else', () => {
    const body = planRequestOf(draft(), 'att-7');
    expect(body).toEqual({
      kind: 'weekly_plan',
      weekStart: '2026-09-27',
      grade: 3,
      attachmentId: 'att-7',
    });
    // No title, no body, no audience (the server gives a plan its own) and no sectionIds, which
    // would be exactly what makes the grade a 400.
    expect('title' in body).toBe(false);
    expect('audience' in body).toBe(false);
    expect('sectionIds' in body).toBe(false);
  });
});
