/**
 * The "+ Add stop" menu's twenty-two entries — a straight port of `StopTemplates.kt`, which is
 * what the Compose admin panel this screen replaces offered (dev prompt §5/§6).
 *
 * Each template is the smallest **schema-valid** example of its type, with neutral placeholder
 * copy a teacher overwrites: "First / Second / Third", not a line from a seeded story. That is
 * deliberate, and it is why these are written out here rather than taken from `SEED_STOPS` —
 * the fixtures are one particular lesson ("Why do Alan and Daddy make soup?"), which reads as
 * content a teacher has to hunt down and delete rather than as a blank to fill in.
 * `stop-templates.spec.ts` validates all twenty-two against `Play.schema.json`, so a template
 * that drifted from the contract fails before anyone can post it.
 */
import type { Stop, StopType } from '../../ui/phone-preview';
import type { Subject } from './lessons.models';

export type StopTemplateGroup = 'information' | 'oneAnswer' | 'severalAnswers' | 'openAnswer' | 'exit';

/** `StopTemplates.kt`'s five headings, in its order. */
export const STOP_TEMPLATE_GROUPS: readonly StopTemplateGroup[] = [
  'information',
  'oneAnswer',
  'severalAnswers',
  'openAnswer',
  'exit',
];

export interface StopTemplate {
  readonly type: StopType;
  readonly group: StopTemplateGroup;
  /** A fresh, schema-valid stop of this type — a new id every call, so two adds never collide. */
  make(subject: Subject): Stop;
}

const TIP = { en: 'Read the question aloud together first.', ar: 'اقرآ السؤال معًا بصوت عالٍ أولًا.' } as const;

const INGREDIENT: Record<Subject, { readonly emoji: string; readonly name: string }> = {
  math: { emoji: '🥕', name: 'carrot' },
  english: { emoji: '🍅', name: 'tomato' },
  french: { emoji: '🥐', name: 'croissant' },
  science: { emoji: '🧪', name: 'flask' },
  religion: { emoji: '🕊️', name: 'dove' },
  arabic: { emoji: '🌴', name: 'palm' },
};

function newStopId(): string {
  return `new-${crypto.randomUUID().slice(0, 8)}`;
}

type Make = (id: string, ingredient: { readonly emoji: string; readonly name: string }) => Stop;

const TEMPLATES: readonly { readonly type: StopType; readonly group: StopTemplateGroup; readonly make: Make }[] = [
  {
    type: 'readPage',
    group: 'information',
    make: (id, ingredient) => ({
      id,
      type: 'readPage',
      title: 'Read the page',
      speak: "Let's read this page together.",
      ingredient,
      parentTip: TIP,
      pageNumber: 1,
      sentences: ['Write the first sentence here.', 'And a second one.'],
    }),
  },
  {
    type: 'storyPieces',
    group: 'information',
    make: (id, ingredient) => ({
      id,
      type: 'storyPieces',
      title: 'Meet the story pieces',
      speak: 'Tap each card to hear what it means.',
      ingredient,
      parentTip: TIP,
      cards: [
        { piece: 'title', definition: 'The name of the story', answer: 'Our story' },
        { piece: 'genre', definition: 'What kind of story', answer: 'Real' },
        { piece: 'characters', definition: 'Who is in it', answer: 'The children' },
        { piece: 'setting', definition: 'Where it happens', answer: 'At school' },
        { piece: 'plot', definition: 'What happens', answer: 'They learn something new' },
        { piece: 'problem', definition: 'The tricky part', answer: 'Something goes wrong' },
      ],
    }),
  },
  {
    type: 'wordCards',
    group: 'information',
    make: (id, ingredient) => ({
      id,
      type: 'wordCards',
      title: 'New words',
      speak: 'Tap a word to hear it.',
      ingredient,
      parentTip: TIP,
      words: [
        { word: 'sun', meaning: 'The bright star in the sky.', sentence: 'The sun is hot.', illustrationKey: 'sun' },
        { word: 'tree', meaning: 'A tall plant with leaves.', sentence: 'The tree is green.', illustrationKey: 'tree' },
      ],
    }),
  },
  {
    type: 'move',
    group: 'information',
    make: (id, ingredient) => ({
      id,
      type: 'move',
      title: 'Move your body',
      speak: "Let's warm up!",
      ingredient,
      parentTip: TIP,
      actions: [
        { emoji: '🙌', text: 'Reach up high!' },
        { emoji: '🐸', text: 'Hop like a frog!' },
        { emoji: '🧘', text: 'Sit down slowly.' },
      ],
    }),
  },
  {
    type: 'explain',
    group: 'information',
    make: (id, ingredient) => ({
      id,
      type: 'explain',
      title: 'How it works',
      speak: 'Watch how we do it.',
      ingredient,
      parentTip: TIP,
      skillId: 'skill',
      explanation: 'Say the rule here in one short sentence.',
      // Two, not the one `StopTemplates.kt` writes: `stop_explain.workedExamples` has
      // `minItems: 2`, so the Kotlin template posts something the server refuses. Flagged for
      // the planner — `StopTemplates.kt` should gain the second example too.
      workedExamples: [
        { prompt: '2 + 2', steps: ['Start at 2', 'Count two more'], answer: '4' },
        { prompt: '3 + 1', steps: ['Start at 3', 'Count one more'], answer: '4' },
      ],
    }),
  },
  {
    type: 'choice',
    group: 'oneAnswer',
    make: (id, ingredient) => ({
      id,
      type: 'choice',
      title: 'Pick the answer',
      speak: 'Which one is right?',
      ingredient,
      parentTip: TIP,
      hint: 'Think about the page.',
      question: 'Which one is right?',
      options: [
        { id: 'a', label: 'First' },
        { id: 'b', label: 'Second' },
        { id: 'c', label: 'Third' },
      ],
      correctOptionId: 'a',
    }),
  },
  {
    type: 'trueFalse',
    group: 'oneAnswer',
    make: (id, ingredient) => ({
      id,
      type: 'trueFalse',
      title: 'True or false',
      speak: 'Is this true?',
      ingredient,
      parentTip: TIP,
      hint: 'Look at the picture again.',
      statement: 'The sun is cold.',
      answer: false,
    }),
  },
  {
    type: 'sequence',
    group: 'oneAnswer',
    make: (id, ingredient) => ({
      id,
      type: 'sequence',
      title: 'What comes next?',
      speak: 'Fill the gap.',
      ingredient,
      parentTip: TIP,
      hint: 'Jump two each time.',
      chips: [2, 4, 6, null],
      options: [
        { id: 'a', label: '8' },
        { id: 'b', label: '7' },
        { id: 'c', label: '9' },
      ],
      correctOptionId: 'a',
      numberLine: { from: 0, to: 10, step: 1, highlight: [2, 4, 6] },
    }),
  },
  {
    type: 'count',
    group: 'oneAnswer',
    make: (id, ingredient) => ({
      id,
      type: 'count',
      title: 'How many?',
      speak: 'Count them all.',
      ingredient,
      parentTip: TIP,
      hint: 'Count one group, then the next.',
      objectKey: 'apple',
      groupSizes: [2, 2],
      options: [
        { id: 'a', label: '4' },
        { id: 'b', label: '3' },
        { id: 'c', label: '5' },
      ],
      correctOptionId: 'a',
      numberLine: { from: 0, to: 10, step: 1 },
    }),
  },
  {
    type: 'compare',
    group: 'oneAnswer',
    make: (id, ingredient) => ({
      id,
      type: 'compare',
      title: 'Which is bigger?',
      speak: 'Compare the two numbers.',
      ingredient,
      parentTip: TIP,
      hint: 'Find both on the line.',
      left: 8,
      right: 6,
      options: [
        { id: 'a', label: '<' },
        { id: 'b', label: '>' },
        { id: 'c', label: '=' },
      ],
      correctOptionId: 'b',
      numberLine: { from: 0, to: 10, step: 1, highlight: [6, 8] },
    }),
  },
  {
    type: 'sound',
    group: 'oneAnswer',
    make: (id, ingredient) => ({
      id,
      type: 'sound',
      title: 'Which sound?',
      speak: 'Which sound starts the word?',
      ingredient,
      parentTip: TIP,
      hint: 'Say it slowly.',
      illustrationKey: 'ship',
      options: [
        { id: 'a', label: 'sh' },
        { id: 'b', label: 'ch' },
      ],
      correctOptionId: 'a',
    }),
  },
  {
    type: 'word',
    group: 'oneAnswer',
    make: (id, ingredient) => ({
      id,
      type: 'word',
      title: 'Find the word',
      speak: 'Listen, then tap the word.',
      ingredient,
      parentTip: TIP,
      hint: 'Listen again.',
      spokenWord: 'shop',
      options: [
        { id: 'a', label: 'shop' },
        { id: 'b', label: 'ship' },
        { id: 'c', label: 'chip' },
      ],
      correctOptionId: 'a',
    }),
  },
  {
    type: 'readTap',
    group: 'oneAnswer',
    make: (id, ingredient) => ({
      id,
      type: 'readTap',
      title: 'Read and tap',
      speak: 'Read the word, tap the picture.',
      ingredient,
      parentTip: TIP,
      hint: 'Sound it out.',
      word: 'fish',
      options: [
        { id: 'a', illustrationKey: 'fish' },
        { id: 'b', illustrationKey: 'ship' },
        { id: 'c', illustrationKey: 'sun' },
      ],
      correctOptionId: 'a',
    }),
  },
  {
    type: 'writeSentence',
    group: 'oneAnswer',
    make: (id, ingredient) => ({
      id,
      type: 'writeSentence',
      title: 'Finish the sentence',
      speak: 'Pick the missing word.',
      ingredient,
      parentTip: TIP,
      frame: 'The sun is ___.',
      answer: 'hot',
      options: ['hot', 'wet', 'blue'],
    }),
  },
  {
    type: 'multiSelect',
    group: 'severalAnswers',
    make: (id, ingredient) => ({
      id,
      type: 'multiSelect',
      title: 'Tap two',
      speak: 'Tap two things from the page.',
      ingredient,
      parentTip: TIP,
      prompt: 'Tap two things from the page.',
      options: [
        { id: 'a', label: 'First' },
        { id: 'b', label: 'Second' },
        { id: 'c', label: 'Third' },
        { id: 'd', label: 'Fourth' },
      ],
      correctIds: ['a', 'b'],
      pick: 2,
    }),
  },
  {
    type: 'selectAll',
    group: 'severalAnswers',
    make: (id, ingredient) => ({
      id,
      type: 'selectAll',
      title: 'Find them all',
      speak: 'Tap every one that fits.',
      ingredient,
      parentTip: TIP,
      prompt: 'Tap every one that fits.',
      options: [
        { id: 'a', label: 'First' },
        { id: 'b', label: 'Second' },
        { id: 'c', label: 'Third' },
      ],
      correctIds: ['a', 'c'],
    }),
  },
  {
    type: 'match',
    group: 'severalAnswers',
    make: (id, ingredient) => ({
      id,
      type: 'match',
      title: 'Match the pairs',
      speak: 'Match each word to its picture.',
      ingredient,
      parentTip: TIP,
      prompt: 'Match each word to its picture.',
      pairs: [
        { id: 'p1', left: { id: 'l1', label: 'sun' }, right: { id: 'r1', illustrationKey: 'sun' } },
        { id: 'p2', left: { id: 'l2', label: 'tree' }, right: { id: 'r2', illustrationKey: 'tree' } },
        { id: 'p3', left: { id: 'l3', label: 'fish' }, right: { id: 'r3', illustrationKey: 'fish' } },
      ],
    }),
  },
  {
    type: 'order',
    group: 'severalAnswers',
    make: (id, ingredient) => ({
      id,
      type: 'order',
      title: 'Put the story in order',
      speak: 'Put the story in order.',
      ingredient,
      parentTip: TIP,
      prompt: 'Put the story in order.',
      items: [
        { id: 'a', text: 'First this happened.' },
        { id: 'b', text: 'Then this.' },
        { id: 'c', text: 'In the end, this.' },
      ],
      correctOrder: ['a', 'b', 'c'],
    }),
  },
  {
    type: 'trace',
    group: 'severalAnswers',
    make: (id, ingredient) => ({
      id,
      type: 'trace',
      title: 'Trace the letter',
      speak: 'Trace the letter with your finger.',
      ingredient,
      parentTip: TIP,
      text: 'S',
      hint: 'Start at the top.',
    }),
  },
  {
    type: 'retell',
    group: 'openAnswer',
    make: (id, ingredient) => ({
      id,
      type: 'retell',
      title: 'Tell the story',
      speak: 'Tell the story in your own words.',
      ingredient,
      parentTip: TIP,
      prompt: 'Tell the story in your own words.',
      cues: [
        { stage: 'beginning', cue: 'First…' },
        { stage: 'middle', cue: 'Then…' },
        { stage: 'end', cue: 'In the end…' },
      ],
      modelAnswer: 'A good retelling names who, what happened and how it ended.',
    }),
  },
  {
    type: 'openAnswer',
    group: 'openAnswer',
    make: (id, ingredient) => ({
      id,
      type: 'openAnswer',
      title: 'Your idea',
      speak: 'What do you think?',
      ingredient,
      parentTip: TIP,
      prompt: 'What would you do?',
      mode: 'speak',
      modelAnswer: 'Any answer with a reason is a good one.',
    }),
  },
  {
    type: 'exitTicket',
    group: 'exit',
    make: (id, ingredient) => ({
      id,
      type: 'exitTicket',
      title: 'Three last questions',
      speak: 'Three last questions!',
      ingredient,
      parentTip: TIP,
      questions: [
        {
          id: `${id}-q1`,
          type: 'choice',
          title: 'Question 1',
          speak: 'Who is in the story?',
          ingredient,
          parentTip: TIP,
          hint: 'Think about the page.',
          question: 'Who is in the story?',
          options: [
            { id: 'a', label: 'First' },
            { id: 'b', label: 'Second' },
            { id: 'c', label: 'Third' },
          ],
          correctOptionId: 'a',
        },
        {
          id: `${id}-q2`,
          type: 'multiSelect',
          title: 'Question 2',
          speak: 'Tap two.',
          ingredient,
          parentTip: TIP,
          prompt: 'Tap two things from the page.',
          options: [
            { id: 'a', label: 'First' },
            { id: 'b', label: 'Second' },
            { id: 'c', label: 'Third' },
            { id: 'd', label: 'Fourth' },
          ],
          correctIds: ['a', 'b'],
          pick: 2,
        },
        {
          id: `${id}-q3`,
          type: 'trueFalse',
          title: 'Question 3',
          speak: 'True or false?',
          ingredient,
          parentTip: TIP,
          hint: 'Look again.',
          statement: 'The story is about the sea.',
          answer: false,
        },
      ],
    }),
  },
];

/** One entry per `StopType`, grouped as the "+ Add stop" menu shows them. */
export const STOP_TEMPLATES: readonly StopTemplate[] = TEMPLATES.map((entry) => ({
  type: entry.type,
  group: entry.group,
  make: (subject: Subject): Stop => entry.make(newStopId(), INGREDIENT[subject]),
}));

export function templatesByGroup(group: StopTemplateGroup): readonly StopTemplate[] {
  return STOP_TEMPLATES.filter((entry) => entry.group === group);
}
