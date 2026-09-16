import { Bilingual, Ingredient, Play, Stop, StopType, Theme, WordCardsStop } from './stop.model';

/**
 * One stop of every type, transcribed from the seed plays in
 * `shared-api/src/commonMain/kotlin/quest/api/samples` — "Hot Soup for Mummy" (story),
 * "Counting by 2s" (math) and "The sh sound" (phonics).
 *
 * Real content rather than lorem ipsum: a preview of a made-up stop tells you nothing about
 * whether the screen works, and these are the exact stops the app, the seeded QA lessons and the
 * screenshot tests all use. The spec renders every one of them, so a stop type that stops
 * rendering fails the build.
 */

const ing = (emoji: string, name: string): Ingredient => ({ emoji, name });
const tip = (en: string, ar: string): Bilingual => ({ en, ar });

// --- information stops -------------------------------------------------------

const readPage: Stop = {
  type: 'readPage',
  id: 'hs1-page2',
  title: 'Read: The fridge',
  speak: 'Read the page, then tap the vegetables in the fridge.',
  ingredient: ing('🫛', 'Peas'),
  parentTip: tip('Name each vegetable in the fridge out loud.', 'سمِّ كل خضار في الثلاجة بصوت عالٍ.'),
  pageNumber: 2,
  sentences: [
    'Alan and Daddy look in the fridge.',
    'They find a carrot, a potato and an onion.',
    'They find peas too!',
  ],
  pictureDescription: 'an open fridge full of vegetables',
  illustrationKey: 'fridge',
  tapTask: {
    prompt: 'Tap the vegetables in the fridge.',
    hotspots: [
      { id: 'h-carrot', label: 'carrot', x: 0.08, y: 0.2, w: 0.26, h: 0.26 },
      { id: 'h-potato', label: 'potato', x: 0.38, y: 0.2, w: 0.26, h: 0.26 },
      { id: 'h-milk', label: 'milk', x: 0.68, y: 0.2, w: 0.26, h: 0.26 },
      { id: 'h-onion', label: 'onion', x: 0.08, y: 0.56, w: 0.26, h: 0.26 },
      { id: 'h-egg', label: 'egg', x: 0.38, y: 0.56, w: 0.26, h: 0.26 },
      { id: 'h-peas', label: 'peas', x: 0.68, y: 0.56, w: 0.26, h: 0.26 },
    ],
    correctIds: ['h-carrot', 'h-potato', 'h-onion', 'h-peas'],
  },
};

const storyPieces: Stop = {
  type: 'storyPieces',
  id: 'hs1-pieces',
  title: 'Meet the story pieces',
  speak: 'Tap each card to hear what it means.',
  ingredient: ing('🥔', 'Potato'),
  parentTip: tip('Ask: who is in the story? Where does it happen?', 'اسأل: من في القصة؟ أين تحدث؟'),
  cards: [
    { piece: 'title', definition: 'The name of the story.', answer: 'Hot Soup for Mummy' },
    {
      piece: 'genre',
      definition: 'Real or fantasy? Could it really happen?',
      answer: 'Real. A family really could make soup.',
    },
    { piece: 'characters', definition: 'The people in the story.', answer: 'Alan, Daddy and Mummy' },
    {
      piece: 'setting',
      definition: 'Where and when the story happens.',
      answer: "At home: the kitchen and Mummy's bedroom",
    },
    {
      piece: 'plot',
      definition: 'What happens in the story.',
      answer: 'Alan and Daddy make hot soup for Mummy.',
    },
    {
      piece: 'problem',
      definition: 'The trouble that needs fixing.',
      answer: 'Mummy has a cold and cannot get up.',
    },
  ],
};

const wordCards: WordCardsStop = {
  type: 'wordCards',
  id: 'hs1-words',
  title: 'New words',
  speak: 'Tap a word to hear it.',
  ingredient: ing('🌽', 'Corn'),
  parentTip: tip(
    'Use each new word in a sentence about your own kitchen.',
    'استخدم كل كلمة جديدة في جملة عن مطبخكم.',
  ),
  words: [
    {
      word: 'fridge',
      meaning: 'A cold box that keeps food fresh.',
      sentence: 'Alan and Daddy look in the fridge.',
      illustrationKey: 'fridge',
    },
    {
      word: 'pot',
      meaning: 'A big pan for cooking soup.',
      sentence: 'Alan puts them in the pot.',
      illustrationKey: 'pot',
    },
    {
      word: 'stir',
      meaning: 'To move the spoon round and round.',
      sentence: 'Alan stirs and stirs.',
      illustrationKey: 'spoon',
    },
    {
      word: 'sip',
      meaning: 'To drink a tiny bit at a time.',
      sentence: 'Mummy sips the soup and smiles.',
      illustrationKey: 'bowl',
    },
  ],
};

const move: Stop = {
  type: 'move',
  id: 'hs1-move',
  title: 'Move your body',
  speak: "Let's warm up like Alan!",
  ingredient: ing('🥕', 'Carrot'),
  parentTip: tip(
    'Do the actions together; it wakes the body up before reading.',
    'قوما بالحركات معًا؛ فهذا ينشّط الجسم قبل القراءة.',
  ),
  actions: [
    { emoji: '🥾', text: "Stomp like you're on a hike!" },
    { emoji: '🥄', text: 'Stir a big pot of soup!' },
    { emoji: '👃', text: 'Sniff the yummy soup!' },
    { emoji: '🥣', text: 'Carry a bowl very carefully!' },
  ],
};

const explain: Stop = {
  type: 'explain',
  id: 'm1-explain',
  title: 'Counting by 2s',
  speak: 'Counting by 2s means we jump two each time!',
  ingredient: ing('🚃', 'Carriage'),
  parentTip: tip('Point to each jump on the number line.', 'أشر إلى كل قفزة على خط الأعداد.'),
  skillId: 'counting-by-2s',
  explanation: 'Counting by 2s means we jump two each time!',
  workedExamples: [
    {
      prompt: '2, 4, 6, ?',
      steps: ['Start at 6', 'Jump 2 on the number line', 'Land on 8'],
      answer: '8',
    },
    { prompt: '2 pairs of shoes', steps: ['One pair is 2', 'Two pairs: 2, 4'], answer: '4 shoes' },
  ],
};

// --- single-answer stops -----------------------------------------------------

const choice: Stop = {
  type: 'choice',
  id: 'hs2-why',
  title: 'Why?',
  speak: 'Why do Alan and Daddy make soup?',
  ingredient: ing('🧅', 'Onion'),
  parentTip: tip(
    'The answer is not written; the child must connect the cold and the soup.',
    'الجواب ليس مكتوبًا؛ على الطفل أن يربط بين الزكام والحساء.',
  ),
  hint: 'Mummy has a cold. What helps when you are ill?',
  question: 'Why do Alan and Daddy make soup?',
  options: [
    { id: 'a', label: 'To help Mummy feel better', illustrationKey: 'heart' },
    { id: 'b', label: 'Because they are bored', illustrationKey: 'ball' },
    { id: 'c', label: 'For a party', illustrationKey: 'balloon' },
  ],
  correctOptionId: 'a',
};

const trueFalse: Stop = {
  type: 'trueFalse',
  id: 'hs1v-tf1',
  title: 'True or false?',
  speak: 'Daddy chops the vegetables.',
  ingredient: ing('🫛', 'Peas'),
  parentTip: tip('Page 3.', 'الصفحة 3.'),
  hint: 'Who holds the knife on page 3?',
  statement: 'Daddy chops the vegetables.',
  answer: true,
};

const sequence: Stop = {
  type: 'sequence',
  id: 'm1-s1',
  title: 'What number is missing?',
  speak: 'What number is missing?',
  ingredient: ing('🛞', 'Wheel'),
  parentTip: tip('Say the numbers out loud together.', 'قولا الأعداد معًا بصوت عالٍ.'),
  hint: 'Start at 6 and jump 2.',
  chips: [2, 4, 6, null],
  options: [
    { id: 'a', label: '8' },
    { id: 'b', label: '7' },
    { id: 'c', label: '10' },
  ],
  correctOptionId: 'a',
  numberLine: { from: 0, to: 10, step: 1, highlight: [2, 4, 6, 8] },
};

const count: Stop = {
  type: 'count',
  id: 'm1-c1',
  title: 'How many?',
  speak: 'How many shoes are there?',
  ingredient: ing('🔔', 'Bell'),
  parentTip: tip('Touch each pair and count 2, 4, 6…', 'المس كل زوج وعدّ 2، 4، 6…'),
  hint: 'Count the shoes two at a time: 2, 4, 6.',
  objectKey: 'shoe',
  groupSizes: [2, 2, 2],
  options: [
    { id: 'a', label: '6' },
    { id: 'b', label: '7' },
    { id: 'c', label: '4' },
  ],
  correctOptionId: 'a',
  numberLine: { from: 0, to: 8, step: 1, highlight: [2, 4, 6] },
};

const compare: Stop = {
  type: 'compare',
  id: 'm1-cmp',
  title: 'Which sign?',
  speak: 'Which sign goes in the middle?',
  ingredient: ing('🚦', 'Signal'),
  parentTip: tip('The open mouth eats the bigger number.', 'الفم المفتوح يأكل العدد الأكبر.'),
  hint: 'Which number is further along the number line?',
  left: 8,
  right: 6,
  options: [
    { id: 'a', label: '<' },
    { id: 'b', label: '>' },
    { id: 'c', label: '=' },
  ],
  correctOptionId: 'b',
  numberLine: { from: 5, to: 9, step: 1, highlight: [8, 6] },
};

const sound: Stop = {
  type: 'sound',
  id: 'p1-s1',
  title: 'What sound?',
  speak: 'What sound does this start with?',
  ingredient: ing('⭐', 'Star'),
  parentTip: tip('Say the word slowly and stretch the first sound.', 'انطق الكلمة ببطء ومدّ الصوت الأول.'),
  hint: 'Say the word slowly. Does it start with shhh?',
  illustrationKey: 'ship',
  options: [
    { id: 'a', label: 'sh' },
    { id: 'b', label: 'ch' },
  ],
  correctOptionId: 'a',
};

const word: Stop = {
  type: 'word',
  id: 'p1-w1',
  title: 'Tap the word',
  speak: 'Tap the word you hear.',
  ingredient: ing('💎', 'Gem'),
  parentTip: tip('Press Listen as many times as needed.', 'اضغط «استمع» كلما احتجت.'),
  hint: 'Listen for the shhh at the start.',
  spokenWord: 'shop',
  options: [
    { id: 'a', label: 'shop' },
    { id: 'b', label: 'chop' },
    { id: 'c', label: 'stop' },
  ],
  correctOptionId: 'a',
};

const readTap: Stop = {
  type: 'readTap',
  id: 'p1-r1',
  title: 'Read and tap',
  speak: 'Tap the picture for sheep.',
  ingredient: ing('🪙', 'Coin'),
  parentTip: tip('Sound it out: s-h-…', 'انطقها صوتًا صوتًا: s-h-…'),
  hint: 'Sh-eep. Which animal says baa?',
  word: 'sheep',
  options: [
    { id: 'a', illustrationKey: 'sheep' },
    { id: 'b', illustrationKey: 'ship' },
    { id: 'c', illustrationKey: 'fish' },
  ],
  correctOptionId: 'a',
};

// --- multi-answer and open stops ---------------------------------------------

const multiSelect: Stop = {
  type: 'multiSelect',
  id: 'hs1-exit-2',
  title: 'Tap two vegetables',
  speak: 'Tap two vegetables from the soup.',
  ingredient: ing('✅', 'Check'),
  parentTip: tip(
    'The soup had a carrot, a potato, an onion and peas.',
    'الحساء فيه جزر وبطاطس وبصل وبازلاء.',
  ),
  prompt: 'Tap two vegetables from the soup.',
  options: [
    { id: 'v1', illustrationKey: 'carrot' },
    { id: 'v2', illustrationKey: 'fish' },
    { id: 'v3', illustrationKey: 'potato' },
    { id: 'v4', illustrationKey: 'cake' },
  ],
  correctIds: ['v1', 'v3'],
  pick: 2,
};

const selectAll: Stop = {
  type: 'selectAll',
  id: 'p2-all',
  title: 'Tap all the sh words',
  speak: 'Tap every word that starts with sh.',
  ingredient: ing('👑', 'Crown'),
  parentTip: tip('Keep looking until all are found.', 'استمر في البحث حتى تجدها كلها.'),
  prompt: 'Tap every word that starts with sh.',
  options: [
    { id: 'a', label: 'shop' },
    { id: 'b', label: 'cat' },
    { id: 'c', label: 'shell' },
    { id: 'd', label: 'ship' },
    { id: 'e', label: 'sun' },
  ],
  correctIds: ['a', 'c', 'd'],
};

const match: Stop = {
  type: 'match',
  id: 'hs1-match',
  title: 'Match up',
  speak: 'Match each word to its picture.',
  ingredient: ing('🧂', 'Salt'),
  parentTip: tip(
    'If a match is wrong, read the word slowly together.',
    'إذا كان الربط خاطئًا، اقرأا الكلمة ببطء معًا.',
  ),
  prompt: 'Match the word to the picture.',
  pairs: [
    { id: 'm1', left: { id: 'm1l', label: 'carrot' }, right: { id: 'm1r', illustrationKey: 'carrot' } },
    { id: 'm2', left: { id: 'm2l', label: 'pot' }, right: { id: 'm2r', illustrationKey: 'pot' } },
    { id: 'm3', left: { id: 'm3l', label: 'spoon' }, right: { id: 'm3r', illustrationKey: 'spoon' } },
    { id: 'm4', left: { id: 'm4l', label: 'bowl' }, right: { id: 'm4r', illustrationKey: 'bowl' } },
  ],
};

const order: Stop = {
  type: 'order',
  id: 'hs1-order',
  title: 'What happened first?',
  speak: 'Put the story in order.',
  ingredient: ing('💧', 'Water'),
  parentTip: tip("Say 'first, next, then, last' as you go.", 'قل: أولًا، ثم، بعد ذلك، أخيرًا أثناء الترتيب.'),
  prompt: 'Put the story in order.',
  items: [
    { id: 'o1', text: 'Mummy is in bed with a cold.', illustrationKey: 'bed' },
    { id: 'o2', text: 'Alan and Daddy find vegetables.', illustrationKey: 'fridge' },
    { id: 'o3', text: 'The soup cooks in the pot.', illustrationKey: 'pot' },
    { id: 'o4', text: 'Alan carries the soup to Mummy.', illustrationKey: 'bowl' },
  ],
  correctOrder: ['o1', 'o2', 'o3', 'o4'],
};

const trace: Stop = {
  type: 'trace',
  id: 'p1-trace',
  title: 'Trace it',
  speak: 'Trace the letter S.',
  ingredient: ing('👑', 'Crown'),
  parentTip: tip('Start at the top and curve like a snake.', 'ابدأ من الأعلى وانحنِ مثل الأفعى.'),
  text: 'S',
  hint: 'Start at the top and curve like a snake.',
};

const retell: Stop = {
  type: 'retell',
  id: 'hs3-retell',
  title: 'Tell the story',
  speak: 'Tell the story in your own words.',
  ingredient: ing('🥔', 'Potato'),
  parentTip: tip(
    "Listen for beginning, middle and end. A good retell names Mummy's cold, making the soup, and Mummy smiling.",
    'استمع للبداية والوسط والنهاية.',
  ),
  prompt: 'Tell the story in your own words.',
  cues: [
    { stage: 'beginning', cue: 'Mummy is in bed…', illustrationKey: 'bed' },
    { stage: 'middle', cue: 'Alan and Daddy…', illustrationKey: 'pot' },
    { stage: 'end', cue: 'Then Mummy…', illustrationKey: 'mummy' },
  ],
  modelAnswer:
    'Mummy had a cold and stayed in bed. Alan and Daddy found vegetables in the fridge, chopped them and cooked hot soup. Alan carried the soup up to Mummy and she felt better.',
};

const openAnswer: Stop = {
  type: 'openAnswer',
  id: 'hs3-apply',
  title: 'Your idea',
  speak: 'Think of another way Alan could help Mummy.',
  ingredient: ing('🧅', 'Onion'),
  parentTip: tip(
    "Any caring idea counts: a blanket, a drink, reading to her. Ask 'why would that help?'",
    'أي فكرة تدل على الاهتمام تُقبل: بطانية، مشروب، القراءة لها.',
  ),
  prompt: 'Think of another way Alan could help Mummy.',
  mode: 'both',
  modelAnswer:
    'Alan could bring Mummy a warm blanket, make her a cup of tea, or read her a story so she can rest.',
};

const writeSentence: Stop = {
  type: 'writeSentence',
  id: 'hs2-write',
  title: 'Finish the sentence',
  speak: 'Tap the word that fits.',
  ingredient: ing('🎫', 'Ticket'),
  parentTip: tip('Read the whole sentence back together.', 'اقرأا الجملة كاملة معًا.'),
  frame: 'Alan and Daddy make ___ for Mummy.',
  answer: 'soup',
  options: ['soup', 'cake', 'tea'],
};

const exitTicket: Stop = {
  type: 'exitTicket',
  id: 'hs1-exit',
  title: 'Exit ticket',
  speak: 'Three last questions!',
  ingredient: ing('🌿', 'Herbs'),
  parentTip: tip('Three quick checks of what was read today.', 'ثلاثة أسئلة سريعة عمّا قُرئ اليوم.'),
  questions: [
    {
      type: 'choice',
      id: 'hs1-exit-1',
      title: 'Who has a cold?',
      speak: 'Who has a cold?',
      ingredient: ing('✅', 'Check'),
      parentTip: tip('Look at page 1.', 'انظر إلى الصفحة 1.'),
      hint: 'Who is in bed on page 1?',
      question: 'Who has a cold?',
      options: [
        { id: 'a', label: 'Mummy', illustrationKey: 'mummy' },
        { id: 'b', label: 'Daddy', illustrationKey: 'daddy' },
        { id: 'c', label: 'Alan', illustrationKey: 'boy' },
      ],
      correctOptionId: 'a',
    },
    multiSelect,
    {
      type: 'trueFalse',
      id: 'hs1-exit-3',
      title: 'True or false?',
      speak: 'Alan carries the bowl up the stairs.',
      ingredient: ing('✅', 'Check'),
      parentTip: tip('Page 5 tells us.', 'الصفحة 5 تخبرنا.'),
      hint: 'Remember the last page: Alan walks slowly with the bowl.',
      statement: 'Alan carries the bowl up the stairs.',
      answer: true,
    },
  ],
};

/** One stop per type. Exhaustive by construction: a new type in the contract fails to compile. */
export const SEED_STOPS: Readonly<Record<StopType, Stop>> = {
  readPage,
  storyPieces,
  wordCards,
  move,
  explain,
  choice,
  trueFalse,
  sequence,
  count,
  compare,
  sound,
  word,
  readTap,
  multiSelect,
  selectAll,
  match,
  order,
  trace,
  retell,
  openAnswer,
  writeSentence,
  exitTicket,
};

/** The pot and dish of "Hot Soup for Mummy" — what the map header names. */
export const SEED_THEME: Theme = {
  potName: 'Soup pot',
  dishName: 'Hot soup for Mummy',
  potEmoji: '🍲',
  servedText: 'Mummy sips the hot soup and smiles!',
};

/** A seven-stop play in the order the seed lists them, for previewing a whole level. */
export const SEED_PLAY: Play = {
  level: 1,
  variant: 0,
  kind: 'story',
  theme: SEED_THEME,
  stops: [move, storyPieces, readPage, wordCards, match, order, exitTicket],
};
