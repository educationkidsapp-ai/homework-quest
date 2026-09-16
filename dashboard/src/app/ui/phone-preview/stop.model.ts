/**
 * The lesson content contract — `quest.api.dto.Stop`, `Play` and their parts, in TypeScript.
 *
 * **Why this is hand-written next to the generated client.** Every lesson-pipeline endpoint in
 * `server/openapi.json` declares its body and its response as `"type": "string"`: the controllers
 * hand back kotlinx-serialization output verbatim, because a sealed hierarchy with a `type`
 * discriminator is not something springdoc can describe. The generated services are therefore the
 * only thing that speaks HTTP (`hq/no-raw-http` still holds — nothing here injects `HttpClient`),
 * and this file is the shape their payloads actually have. `pnpm schemas` copies the JSON Schemas
 * the server validates against into `src/assets/schemas/` and fails on drift, so the shape below
 * cannot quietly diverge from the contract without a red build.
 *
 * It lives under `ui/phone-preview/` rather than under `features/lessons/` because the preview is
 * what renders it, and a `ui/` component may not import from a feature.
 */

/** Parent-facing copy is always EN + AR. */
export interface Bilingual {
  readonly en: string;
  readonly ar: string;
}

export interface BilingualList {
  readonly en: readonly string[];
  readonly ar: readonly string[];
}

/** The soup ingredient a stop adds to the pot. */
export interface Ingredient {
  readonly emoji: string;
  readonly name: string;
}

/** A tappable tile: text, an illustration key, or a cropped page image. At least one is set. */
export interface Tile {
  readonly id: string;
  readonly label?: string;
  readonly illustrationKey?: string;
  readonly pageImageId?: string;
}

export interface Hotspot {
  readonly id: string;
  readonly label: string;
  readonly x: number;
  readonly y: number;
  readonly w: number;
  readonly h: number;
}

export interface TapTask {
  readonly prompt: string;
  readonly hotspots: readonly Hotspot[];
  readonly correctIds: readonly string[];
}

export interface StoryCard {
  readonly piece: string;
  readonly definition: string;
  readonly answer: string;
}

export interface WordCard {
  readonly word: string;
  readonly meaning: string;
  readonly sentence: string;
  readonly illustrationKey: string;
}

export interface MoveAction {
  readonly emoji: string;
  readonly text: string;
}

export interface WorkedExample {
  readonly prompt: string;
  readonly steps: readonly string[];
  readonly answer: string;
}

export interface Option {
  readonly id: string;
  readonly label: string;
}

export interface PictureOption {
  readonly id: string;
  readonly illustrationKey: string;
}

export interface NumberLine {
  readonly from: number;
  readonly to: number;
  readonly step: number;
  readonly highlight?: readonly number[];
}

export interface MatchPair {
  readonly id: string;
  readonly left: Tile;
  readonly right: Tile;
}

export interface OrderItem {
  readonly id: string;
  readonly text: string;
  readonly illustrationKey?: string;
}

export interface RetellCue {
  readonly stage: string;
  readonly cue: string;
  readonly illustrationKey?: string;
  readonly pageImageId?: string;
}

/** Common to every stop: `type` is the JSON discriminator. */
interface StopBase {
  readonly id: string;
  readonly title: string;
  readonly speak: string;
  readonly ingredient: Ingredient;
  readonly parentTip: Bilingual;
  /** A page image or an admin-attached picture, shown above the stop. */
  readonly imageId?: string | null;
}

// ---------------------------------------------------------------- information stops

export interface ReadPageStop extends StopBase {
  readonly type: 'readPage';
  readonly pageNumber: number;
  readonly sentences: readonly string[];
  readonly pageImageId?: string | null;
  readonly pictureDescription?: string | null;
  readonly illustrationKey?: string | null;
  readonly tapTask?: TapTask | null;
}

export interface StoryPiecesStop extends StopBase {
  readonly type: 'storyPieces';
  readonly cards: readonly StoryCard[];
}

export interface WordCardsStop extends StopBase {
  readonly type: 'wordCards';
  readonly words: readonly WordCard[];
}

export interface MoveStop extends StopBase {
  readonly type: 'move';
  readonly actions: readonly MoveAction[];
}

export interface ExplainStop extends StopBase {
  readonly type: 'explain';
  readonly skillId: string;
  readonly explanation: string;
  readonly workedExamples: readonly WorkedExample[];
}

// ---------------------------------------------------------------- single-answer stops

export interface ChoiceStop extends StopBase {
  readonly type: 'choice';
  readonly hint: string;
  readonly question: string;
  readonly options: readonly Tile[];
  readonly correctOptionId: string;
}

export interface TrueFalseStop extends StopBase {
  readonly type: 'trueFalse';
  readonly hint: string;
  readonly statement: string;
  readonly answer: boolean;
}

export interface SequenceStop extends StopBase {
  readonly type: 'sequence';
  readonly hint: string;
  /** `null` is the gap the child fills. */
  readonly chips: readonly (number | null)[];
  readonly options: readonly Option[];
  readonly correctOptionId: string;
  readonly numberLine: NumberLine;
}

export interface CountStop extends StopBase {
  readonly type: 'count';
  readonly hint: string;
  readonly objectKey: string;
  readonly groupSizes: readonly number[];
  readonly options: readonly Option[];
  readonly correctOptionId: string;
  readonly numberLine: NumberLine;
}

export interface CompareStop extends StopBase {
  readonly type: 'compare';
  readonly hint: string;
  readonly left: number;
  readonly right: number;
  readonly options: readonly Option[];
  readonly correctOptionId: string;
  readonly numberLine: NumberLine;
}

export interface SoundStop extends StopBase {
  readonly type: 'sound';
  readonly hint: string;
  readonly illustrationKey: string;
  readonly options: readonly Option[];
  readonly correctOptionId: string;
}

export interface WordStop extends StopBase {
  readonly type: 'word';
  readonly hint: string;
  readonly spokenWord: string;
  readonly options: readonly Option[];
  readonly correctOptionId: string;
}

export interface ReadTapStop extends StopBase {
  readonly type: 'readTap';
  readonly hint: string;
  readonly word: string;
  readonly options: readonly PictureOption[];
  readonly correctOptionId: string;
}

// ---------------------------------------------------------------- multi-answer and open stops

export interface MultiSelectStop extends StopBase {
  readonly type: 'multiSelect';
  readonly prompt: string;
  readonly options: readonly Tile[];
  readonly correctIds: readonly string[];
  readonly pick: number;
}

export interface SelectAllStop extends StopBase {
  readonly type: 'selectAll';
  readonly prompt: string;
  readonly options: readonly Tile[];
  readonly correctIds: readonly string[];
}

export interface MatchStop extends StopBase {
  readonly type: 'match';
  readonly prompt: string;
  readonly pairs: readonly MatchPair[];
}

export interface OrderStop extends StopBase {
  readonly type: 'order';
  readonly prompt: string;
  readonly items: readonly OrderItem[];
  readonly correctOrder: readonly string[];
}

export interface TraceStop extends StopBase {
  readonly type: 'trace';
  readonly text: string;
  readonly hint: string;
}

export interface RetellStop extends StopBase {
  readonly type: 'retell';
  readonly prompt: string;
  readonly cues: readonly RetellCue[];
  readonly modelAnswer: string;
  readonly record?: boolean;
}

export interface OpenAnswerStop extends StopBase {
  readonly type: 'openAnswer';
  readonly prompt: string;
  /** `speak` or `draw`. */
  readonly mode: string;
  readonly modelAnswer: string;
}

export interface WriteSentenceStop extends StopBase {
  readonly type: 'writeSentence';
  readonly frame: string;
  readonly answer: string;
  readonly options?: readonly string[] | null;
  readonly free?: boolean;
}

export interface ExitTicketStop extends StopBase {
  readonly type: 'exitTicket';
  readonly questions: readonly Stop[];
}

/** One stop on the journey (§5). Twenty-two types, discriminated by `type`. */
export type Stop =
  | ReadPageStop
  | StoryPiecesStop
  | WordCardsStop
  | MoveStop
  | ExplainStop
  | ChoiceStop
  | TrueFalseStop
  | SequenceStop
  | CountStop
  | CompareStop
  | SoundStop
  | WordStop
  | ReadTapStop
  | MultiSelectStop
  | SelectAllStop
  | MatchStop
  | OrderStop
  | TraceStop
  | RetellStop
  | OpenAnswerStop
  | WriteSentenceStop
  | ExitTicketStop;

export type StopType = Stop['type'];

/** How a stop is answered, which drives the player and the scoring. */
export type StopCategory = 'INFO' | 'SINGLE' | 'MULTI' | 'OPEN' | 'EXIT';

const CATEGORY_OF: Readonly<Record<StopType, StopCategory>> = {
  readPage: 'INFO',
  storyPieces: 'INFO',
  wordCards: 'INFO',
  move: 'INFO',
  explain: 'INFO',
  choice: 'SINGLE',
  trueFalse: 'SINGLE',
  sequence: 'SINGLE',
  count: 'SINGLE',
  compare: 'SINGLE',
  sound: 'SINGLE',
  word: 'SINGLE',
  readTap: 'SINGLE',
  multiSelect: 'MULTI',
  selectAll: 'MULTI',
  match: 'MULTI',
  order: 'MULTI',
  trace: 'MULTI',
  retell: 'OPEN',
  openAnswer: 'OPEN',
  writeSentence: 'SINGLE',
  exitTicket: 'EXIT',
};

/** Every stop type, in the order §5 lists them — the source of the "+ Add stop" menu. */
export const STOP_TYPES: readonly StopType[] = Object.keys(CATEGORY_OF) as StopType[];

/** `writeSentence` is open when `free`, single otherwise — every other type is fixed. */
export function categoryOf(stop: Stop): StopCategory {
  if (stop.type === 'writeSentence') return stop.free === true ? 'OPEN' : 'SINGLE';
  return CATEGORY_OF[stop.type];
}

/** The hint a wrong answer shows, for the stops that have one. */
export function hintOf(stop: Stop): string | null {
  return 'hint' in stop && typeof stop.hint === 'string' ? stop.hint : null;
}

export interface Theme {
  readonly potName: string;
  readonly dishName: string;
  readonly potEmoji: string;
  readonly servedText: string;
}

export type SourceKind = 'story' | 'informational' | 'math' | 'phonics' | 'vocabulary' | 'mixed';

/** One level of a lesson. `variant` 0 = main, 1 = the "Again" variant of Level 1. */
export interface Play {
  readonly level: number;
  readonly variant: number;
  readonly kind: SourceKind;
  readonly theme: Theme;
  readonly stops: readonly Stop[];
  readonly id?: string | null;
}

/** A picture the preview can resolve a `imageId` against. */
export interface PreviewImage {
  readonly id: string;
  readonly url: string;
  readonly description?: string;
}
