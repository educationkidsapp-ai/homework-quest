import { describe, expect, it } from 'vitest';
import {
  type AdminLesson,
  type LessonStatusView,
  LessonFileStatusConvertStatusEnum,
  LessonStatusViewStatusEnum,
  LessonStepInfoStatusEnum,
  LessonStepInfoStepEnum,
} from '../../api';
import { lessonSignature, statusSignature } from './lesson-status';

function view(over: Partial<LessonStatusView> = {}): LessonStatusView {
  return {
    status: LessonStatusViewStatusEnum.GENERATING,
    files: [{ id: 'f-1', convertStatus: LessonFileStatusConvertStatusEnum.READY }],
    plays: [{ level: 1, variant: 0, stops: 7 }],
    panel: false,
    steps: [
      {
        step: LessonStepInfoStepEnum.GENERATE_L1,
        status: LessonStepInfoStatusEnum.RUNNING,
        attempt: 1,
        updatedAt: 1,
      },
      {
        step: LessonStepInfoStepEnum.GENERATE_L2,
        status: LessonStepInfoStatusEnum.RUNNING,
        attempt: 1,
        updatedAt: 1,
      },
      {
        step: LessonStepInfoStepEnum.GENERATE_L3,
        status: LessonStepInfoStatusEnum.RUNNING,
        attempt: 1,
        updatedAt: 1,
      },
    ],
    updatedAt: 1,
    tokenUsage: 0,
    ...over,
  };
}

/**
 * E3's reload rule. The poll is cheap; the reload is not, so what counts as "changed" is
 * written down once and tested here rather than being an `if` in the page nobody can find.
 */
describe('statusSignature', () => {
  it('ignores the noise a poll makes: a new timestamp, an attempt, a reshuffled list', () => {
    const before = statusSignature(view());
    expect(statusSignature(view({ updatedAt: 9_999 }))).toBe(before);
    expect(
      statusSignature(
        view({
          steps: [...view().steps].reverse().map((step) => ({ ...step, attempt: 3, updatedAt: 42 })),
        }),
      ),
    ).toBe(before);
  });

  it('moves when a step of the parallel batch finishes — the other two still running', () => {
    const done = view({
      steps: view().steps.map((step, index) =>
        index === 1 ? { ...step, status: LessonStepInfoStatusEnum.DONE } : step,
      ),
    });
    expect(statusSignature(done)).not.toBe(statusSignature(view()));
  });

  it('moves when a play appears, when one grows, and when the panel arrives', () => {
    const base = statusSignature(view());
    expect(statusSignature(view({ plays: [...view().plays, { level: 2, variant: 0, stops: 7 }] }))).not.toBe(
      base,
    );
    expect(statusSignature(view({ plays: [{ level: 1, variant: 0, stops: 8 }] }))).not.toBe(base);
    expect(statusSignature(view({ panel: true }))).not.toBe(base);
  });

  it('moves when the lesson leaves running, and when a file finishes converting', () => {
    const base = statusSignature(view());
    expect(statusSignature(view({ status: LessonStatusViewStatusEnum.REVIEW }))).not.toBe(base);
    expect(
      statusSignature(
        view({ files: [{ id: 'f-1', convertStatus: LessonFileStatusConvertStatusEnum.CONVERTING }] }),
      ),
    ).not.toBe(base);
  });
});

/**
 * The baseline. The page compares the first poll against the lesson it already holds, so the
 * two readings of the same lesson have to come out identical — otherwise every load would pay
 * for one heavy re-read it did not need.
 */
describe('lessonSignature', () => {
  /** The lesson `view()` describes, as the heavy `GET …/lessons/{id}` carries it. */
  function lesson(over: Partial<AdminLesson> = {}): AdminLesson {
    return {
      id: 'l-1',
      analyzedBefore: false,
      course: { curriculum: 'british', grade: 1 },
      createdAt: 0,
      date: '2026-09-10',
      files: [
        {
          id: 'f-1',
          convertStatus: 'ready',
          deleted: false,
          cacheHit: false,
          fileHash: 'h',
          fileName: 'a.pdf',
          pageCount: 1,
        },
      ],
      images: [],
      plays: [
        {
          id: 'p-1',
          level: 1,
          variant: 0,
          generatedAt: 0,
          promptVersion: 'v1',
          play: {
            id: 'p-1',
            kind: 'gallery',
            level: 1,
            variant: 0,
            stops: Array.from({ length: 7 }, (_, i) => ({ id: `st-${i}` })),
          },
        },
      ],
      skills: [],
      source: 'pdf',
      status: 'generating',
      steps: view().steps,
      subject: 'math',
      tokenUsage: 0,
      tokensSaved: 0,
      type: 'lesson',
      version: 1,
      ...over,
    } as unknown as AdminLesson;
  }

  it('reads the heavy body to the same signature as the status body of the same lesson', () => {
    expect(lessonSignature(lesson())).toBe(statusSignature(view()));
  });

  it('leaves out a deleted file, which the status body does not list either', () => {
    const deleted = { ...lesson().files[0]!, id: 'f-2', deleted: true };
    expect(lessonSignature(lesson({ files: [...lesson().files, deleted] }))).toBe(statusSignature(view()));
  });

  it('moves with the lesson: a status that settled, a panel that arrived', () => {
    const base = lessonSignature(lesson());
    expect(lessonSignature(lesson({ status: 'review' as AdminLesson['status'] }))).not.toBe(base);
    expect(lessonSignature(lesson({ parentPanel: {} as AdminLesson['parentPanel'] }))).not.toBe(base);
  });
});
