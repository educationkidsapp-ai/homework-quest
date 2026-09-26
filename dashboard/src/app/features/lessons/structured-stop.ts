/**
 * E4b: the five most-used stop types, written out in fields and saved without a model call.
 *
 * E4a made the assistant non-blocking; it did not make it unnecessary. A multiple-choice question
 * is four short strings and a mark against one of them — there is nothing to interpret, and asking
 * Prompt D to turn those back into exactly that document costs a model call, a wait, and a 422
 * whenever the wording confuses it. So for `choice`, `trueFalse`, `writeSentence` (fill in the
 * blank), `readPage` and `exitTicket` the Add-question sheet asks for the fields themselves and
 * this file builds the finished stop: one `POST …/plays/{id}/stops` with a schema-valid document,
 * no `from-text`, no draft row. The assistant stays one click away on the same five, and the other
 * seventeen types are unchanged.
 *
 * **Every bound here is `Play.schema.json`'s**, per branch, so the teacher is stopped at the field
 * rather than by a 400 on a document she cannot see; `structured-stop.spec.ts` validates what this
 * builds against that schema, so a drift in either fails there. **Whatever she does not type comes
 * from the template** (`stop-templates.ts`): the ingredient, the hint, the standard parent tip,
 * `pageNumber` — required by the schema, and not what she is thinking about.
 */
import type { StopType } from '../../ui/phone-preview';

/** The five types the sheet can build by itself. The other seventeen go to the assistant. */
export const STRUCTURED_TYPES = ['choice', 'trueFalse', 'writeSentence', 'readPage', 'exitTicket'] as const;

export type StructuredType = (typeof STRUCTURED_TYPES)[number];

export function isStructured(type: StopType | ''): type is StructuredType {
  return (STRUCTURED_TYPES as readonly string[]).includes(type);
}

/** `Play.schema.json` per branch — see the file comment. */
export const CAPS = {
  question: 90,
  option: 40,
  statement: 90,
  frame: 90,
  /** `answer` allows 20, but the same word is also an option label (16) when there are choices. */
  answer: 16,
  sentence: 90,
  sentences: 7,
} as const;

/** Four slots, four stable ids: a blank slot is dropped, so the ids can be sparse. */
const OPTION_IDS = ['a', 'b', 'c', 'd'] as const;

/** A question with answers to choose from — `choice`, and each of an exit ticket's three. */
export interface ChoiceValue {
  readonly question: string;
  /** Four slots. Blank ones are not saved; at least two must be filled. */
  readonly options: readonly string[];
  /** Which slot holds the right answer. */
  readonly correct: number;
}

/**
 * Everything the five forms can hold, in one object.
 *
 * One shape rather than five keeps the sheet's state a single signal and the builder a single
 * pure function; only the selected type's part is read.
 */
export interface StructuredValue {
  readonly choice: ChoiceValue;
  readonly statement: string;
  readonly answer: boolean;
  readonly frame: string;
  readonly blankAnswer: string;
  /** Wrong words offered beside the answer. Both blank ⇒ the child writes the word herself. */
  readonly distractors: readonly string[];
  /** The page, one sentence per line. */
  readonly page: string;
  readonly ticket: readonly ChoiceValue[];
}

const EMPTY_CHOICE: ChoiceValue = { question: '', options: ['', '', '', ''], correct: 0 };

export const EMPTY_STRUCTURED: StructuredValue = {
  choice: EMPTY_CHOICE,
  statement: '',
  answer: true,
  frame: '',
  blankAnswer: '',
  distractors: ['', ''],
  page: '',
  ticket: [EMPTY_CHOICE, EMPTY_CHOICE, EMPTY_CHOICE],
};

/** True once she has typed anything into the fields of the selected type. */
export function structuredDirty(type: StructuredType, value: StructuredValue): boolean {
  switch (type) {
    case 'choice':
      return choiceDirty(value.choice);
    case 'trueFalse':
      return value.statement !== '';
    case 'writeSentence':
      return value.frame !== '' || value.blankAnswer !== '' || value.distractors.some((d) => d !== '');
    case 'readPage':
      return value.page !== '';
    case 'exitTicket':
      return value.ticket.some(choiceDirty);
  }
}

function choiceDirty(choice: ChoiceValue): boolean {
  return choice.question !== '' || choice.options.some((option) => option !== '');
}

/** The page as the schema wants it: one sentence per non-blank line. */
export function pageSentences(page: string): readonly string[] {
  return page
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line !== '');
}

/**
 * Every problem the selected type's fields have, keyed by the field it belongs under.
 *
 * The same shape as the sheet's own `problems`: a map the field reads, so a message appears under
 * the input it is about. An exit ticket's three questions are prefixed `q1.`…`q3.`.
 */
export function structuredProblems(
  type: StructuredType,
  value: StructuredValue,
): ReadonlyMap<string, string> {
  const problems = new Map<string, string>();
  switch (type) {
    case 'choice':
      choiceProblems(value.choice, '', problems);
      break;
    case 'trueFalse':
      check(problems, 'statement', value.statement, CAPS.statement, 'statementRequired');
      break;
    case 'writeSentence': {
      const frame = value.frame.trim();
      if (!check(problems, 'frame', value.frame, CAPS.frame, 'frameRequired') && !frame.includes('___')) {
        problems.set('frame', key('frameBlank'));
      }
      check(problems, 'answer', value.blankAnswer, CAPS.answer, 'answerRequired');
      const extra = value.distractors.filter((word) => word.trim() !== '');
      if (extra.length === 1) problems.set('distractors', key('distractorsHalf'));
      if (extra.some((word) => word.trim().length > CAPS.answer)) {
        problems.set('distractors', key('tooLong'));
      }
      break;
    }
    case 'readPage': {
      const sentences = pageSentences(value.page);
      if (sentences.length === 0) problems.set('page', key('pageRequired'));
      else if (sentences.length > CAPS.sentences) problems.set('page', key('pageTooMany'));
      else if (sentences.some((line) => line.length > CAPS.sentence)) {
        problems.set('page', key('sentenceTooLong'));
      }
      break;
    }
    case 'exitTicket':
      value.ticket.forEach((question, index) => choiceProblems(question, `q${index + 1}.`, problems));
      break;
  }
  return problems;
}

function choiceProblems(choice: ChoiceValue, prefix: string, into: Map<string, string>): void {
  check(into, `${prefix}question`, choice.question, CAPS.question, 'questionRequired');
  const filled = choice.options.map((option) => option.trim());
  if (filled.filter((option) => option !== '').length < 2) {
    into.set(`${prefix}options`, key('optionsTooFew'));
  } else if (filled.some((option) => option.length > CAPS.option)) {
    into.set(`${prefix}options`, key('tooLong'));
  }
  // Exactly one right answer: the mark is a single choice, so the only way to get this wrong is
  // to mark a slot that has nothing in it.
  if ((filled[choice.correct] ?? '') === '') into.set(`${prefix}correct`, key('correctRequired'));
}

/** Required-and-bounded, the three lines every text field repeats. Returns true when it failed. */
function check(
  into: Map<string, string>,
  field: string,
  raw: string,
  max: number,
  requiredKey: string,
): boolean {
  const text = raw.trim();
  if (text === '') into.set(field, key(requiredKey));
  else if (text.length > max) into.set(field, key('tooLong'));
  else return false;
  return true;
}

function key(name: string): string {
  return `lessons.detail.addStop.fields.${name}`;
}

/**
 * The finished stop: the template with the typed fields written over it.
 *
 * `base` is the chosen type's template already carrying her title, her picture and her parent tip
 * — see `AddStopComponent.submit`. Everything below replaces the template's placeholder content
 * for the fields she filled in, and leaves the rest (ingredient, hint, `pageNumber`) alone.
 */
export function buildStructuredStop(
  type: StructuredType,
  value: StructuredValue,
  base: Record<string, unknown>,
): Record<string, unknown> {
  const stop: Record<string, unknown> = { ...base };
  switch (type) {
    case 'choice':
      return { ...stop, ...choiceParts(value.choice) };
    case 'trueFalse': {
      const statement = value.statement.trim();
      return { ...stop, statement, answer: value.answer, speak: statement };
    }
    case 'writeSentence': {
      const answer = value.blankAnswer.trim();
      stop['frame'] = value.frame.trim();
      stop['answer'] = answer;
      const extra = value.distractors.map((word) => word.trim()).filter((word) => word !== '');
      if (extra.length >= 2) {
        stop['options'] = [answer, ...extra].slice(0, 4);
        delete stop['free'];
      } else {
        // No words to choose from: `free` is the schema's own way of saying the child writes it.
        delete stop['options'];
        stop['free'] = true;
      }
      return stop;
    }
    case 'readPage':
      return { ...stop, sentences: pageSentences(value.page) };
    case 'exitTicket': {
      const pattern = (base['questions'] as readonly Record<string, unknown>[] | undefined)?.[0] ?? {};
      return {
        ...stop,
        questions: value.ticket.map((question, index) => ({
          ...pattern,
          id: `${String(base['id'])}-q${index + 1}`,
          title: `Question ${index + 1}`,
          ...choiceParts(question),
        })),
      };
    }
  }
}

/** A choice question's four fields, shared by `choice` and each of an exit ticket's three. */
function choiceParts(choice: ChoiceValue): Record<string, unknown> {
  const question = choice.question.trim();
  const options = choice.options
    .map((label, index) => ({ id: OPTION_IDS[index]!, label: label.trim() }))
    .filter((option) => option.label !== '');
  return { question, speak: question, options, correctOptionId: OPTION_IDS[choice.correct]! };
}
