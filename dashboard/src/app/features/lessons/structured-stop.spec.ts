import { describe, expect, it } from 'vitest';
import { STOP_TEMPLATES } from './stop-templates';
import { validateStopValue } from './stop-validator';
import {
  EMPTY_STRUCTURED,
  STRUCTURED_TYPES,
  type StructuredType,
  type StructuredValue,
  buildStructuredStop,
  isStructured,
  structuredDirty,
  structuredProblems,
} from './structured-stop';

/**
 * E4b: what the sheet builds must be exactly what the server would have accepted from the model.
 *
 * The 605 kB Ajv module is a **test** dependency here, as it is in `stop-templates.spec.ts`: it is
 * the same `Play.schema.json` the server validates against, so a field cap that drifts from the
 * contract fails in this file rather than as a 400 on a teacher's Save. The form itself never
 * loads it (`stop-editor-chunk.spec.ts` keeps that honest) — the per-field rules below are what
 * she sees.
 */
function base(type: StructuredType): Record<string, unknown> {
  const template = STOP_TEMPLATES.find((entry) => entry.type === type)!;
  return { ...(template.make('math') as unknown as Record<string, unknown>), title: 'Written by hand' };
}

function fill(patch: Partial<StructuredValue>): StructuredValue {
  return { ...EMPTY_STRUCTURED, ...patch };
}

const CHOICE = {
  question: 'Which shape has three sides?',
  options: ['Triangle', 'Circle', 'Square', ''],
  correct: 0,
};

const VALUES: Readonly<Record<StructuredType, StructuredValue>> = {
  choice: fill({ choice: CHOICE }),
  trueFalse: fill({ statement: 'A triangle has three sides.', answer: true }),
  writeSentence: fill({ frame: 'The sun is ___.', blankAnswer: 'hot', distractors: ['wet', 'blue'] }),
  readPage: fill({ page: 'The sun is hot.\nThe moon is cold.\n' }),
  exitTicket: fill({ ticket: [CHOICE, { ...CHOICE, correct: 1 }, CHOICE] }),
};

describe('structured stops', () => {
  it('knows which five types it can write, and does not claim the others', () => {
    expect(STRUCTURED_TYPES).toHaveLength(5);
    expect(isStructured('choice')).toBe(true);
    expect(isStructured('match')).toBe(false);
    expect(isStructured('')).toBe(false);
  });

  it.each(STRUCTURED_TYPES)('%s builds a schema-valid stop from the fields alone', async (type) => {
    const stop = buildStructuredStop(type, VALUES[type], base(type));

    const result = await validateStopValue(type, stop);
    expect(result.errors).toEqual([]);
    expect(result.valid).toBe(true);
    // Whatever she did not type is the template's, and her title survives.
    expect(stop['title']).toBe('Written by hand');
    expect(stop['ingredient']).toEqual({ emoji: '🥕', name: 'carrot' });
  });

  it('turns a choice question into its options and marks the one she chose', () => {
    const stop = buildStructuredStop('choice', VALUES.choice, base('choice'));

    expect(stop['question']).toBe('Which shape has three sides?');
    // What Pip says is the question: a teacher who wrote it once should not write it twice.
    expect(stop['speak']).toBe('Which shape has three sides?');
    expect(stop['options']).toEqual([
      { id: 'a', label: 'Triangle' },
      { id: 'b', label: 'Circle' },
      { id: 'c', label: 'Square' },
    ]);
    expect(stop['correctOptionId']).toBe('a');
  });

  it('keeps the slot ids stable when a middle answer is left blank', () => {
    const value = fill({ choice: { question: 'Which?', options: ['One', '', '', 'Four'], correct: 3 } });

    const stop = buildStructuredStop('choice', value, base('choice'));

    expect(stop['options']).toEqual([
      { id: 'a', label: 'One' },
      { id: 'd', label: 'Four' },
    ]);
    expect(stop['correctOptionId']).toBe('d');
  });

  it('offers the words to choose from, or lets the child write the word herself', () => {
    const withWords = buildStructuredStop('writeSentence', VALUES.writeSentence, base('writeSentence'));
    expect(withWords['options']).toEqual(['hot', 'wet', 'blue']);
    expect(withWords['free']).toBeUndefined();

    const alone = buildStructuredStop(
      'writeSentence',
      fill({ frame: 'The sun is ___.', blankAnswer: 'hot' }),
      base('writeSentence'),
    );
    expect(alone['options']).toBeUndefined();
    expect(alone['free']).toBe(true);
  });

  it('makes a page out of the lines she typed, and drops the blank ones', () => {
    const stop = buildStructuredStop('readPage', VALUES.readPage, base('readPage'));

    expect(stop['sentences']).toEqual(['The sun is hot.', 'The moon is cold.']);
    expect(stop['pageNumber']).toBe(1);
  });

  it('gives an exit ticket the three questions the schema insists on, with their own ids', async () => {
    const stop = buildStructuredStop('exitTicket', VALUES.exitTicket, base('exitTicket'));
    const questions = stop['questions'] as readonly Record<string, unknown>[];

    expect(questions).toHaveLength(3);
    expect(questions.map((question) => question['type'])).toEqual(['choice', 'choice', 'choice']);
    expect(new Set(questions.map((question) => question['id'])).size).toBe(3);
    expect(questions[1]!['correctOptionId']).toBe('b');
    const result = await validateStopValue('exitTicket', stop);
    expect(result.valid).toBe(true);
  });

  describe('validation', () => {
    it('asks for the question, two answers and a right one', () => {
      const problems = structuredProblems('choice', EMPTY_STRUCTURED);

      expect(problems.get('question')).toBe('lessons.detail.addStop.fields.questionRequired');
      expect(problems.get('options')).toBe('lessons.detail.addStop.fields.optionsTooFew');
      expect(problems.get('correct')).toBe('lessons.detail.addStop.fields.correctRequired');
      expect(structuredProblems('choice', VALUES.choice).size).toBe(0);
    });

    it('refuses a right answer marked on an empty slot', () => {
      const value = fill({ choice: { question: 'Which?', options: ['One', 'Two', '', ''], correct: 2 } });

      expect(structuredProblems('choice', value).get('correct')).toBe(
        'lessons.detail.addStop.fields.correctRequired',
      );
    });

    it('insists on the blank in the sentence, and on both wrong words or neither', () => {
      const noBlank = fill({ frame: 'The sun is hot.', blankAnswer: 'hot' });
      expect(structuredProblems('writeSentence', noBlank).get('frame')).toBe(
        'lessons.detail.addStop.fields.frameBlank',
      );

      const half = fill({ frame: 'The sun is ___.', blankAnswer: 'hot', distractors: ['wet', ''] });
      expect(structuredProblems('writeSentence', half).get('distractors')).toBe(
        'lessons.detail.addStop.fields.distractorsHalf',
      );
      expect(structuredProblems('writeSentence', VALUES.writeSentence).size).toBe(0);
    });

    it('holds a page to one and seven sentences of ninety characters', () => {
      expect(structuredProblems('readPage', EMPTY_STRUCTURED).get('page')).toBe(
        'lessons.detail.addStop.fields.pageRequired',
      );
      expect(structuredProblems('readPage', fill({ page: 'One.\n'.repeat(8) })).get('page')).toBe(
        'lessons.detail.addStop.fields.pageTooMany',
      );
      expect(structuredProblems('readPage', fill({ page: 'x'.repeat(91) })).get('page')).toBe(
        'lessons.detail.addStop.fields.sentenceTooLong',
      );
    });

    it('names the exit ticket question a problem belongs to', () => {
      const value = fill({ ticket: [CHOICE, CHOICE, EMPTY_STRUCTURED.ticket[0]!] });

      const problems = structuredProblems('exitTicket', value);

      expect(problems.get('q3.question')).toBe('lessons.detail.addStop.fields.questionRequired');
      expect(problems.has('q1.question')).toBe(false);
    });

    it('is dirty only once the selected type has something in it', () => {
      expect(structuredDirty('choice', EMPTY_STRUCTURED)).toBe(false);
      expect(structuredDirty('choice', VALUES.choice)).toBe(true);
      // The other four types' fields are in the same object and are none of choice's business.
      expect(structuredDirty('choice', VALUES.readPage)).toBe(false);
      expect(structuredDirty('readPage', VALUES.readPage)).toBe(true);
    });
  });
});
