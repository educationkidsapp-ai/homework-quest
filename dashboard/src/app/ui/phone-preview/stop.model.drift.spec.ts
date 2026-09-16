import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { STOP_TYPES, type StopType } from './stop.model';

/**
 * `stop.model.ts` is hand-written next to `../../../../shared-api/src/commonMain/resources/schemas/Play.schema.json`
 * — the JSON Schema the server actually validates a stop against (`quest.api.validation.SchemaValidator`).
 * Nothing builds the TypeScript from the schema, so nothing catches a stop type added on one side and
 * not the other except this: read the schema from disk (not a copy, not an import — the source
 * `SchemaValidator` reads) and check `STOP_TYPES` and the required fields below against it.
 */

interface StopSchemaDef {
  readonly required: readonly string[];
  readonly properties?: Readonly<Record<string, unknown>>;
}

interface PlaySchema {
  readonly $defs: Readonly<Record<string, StopSchemaDef & { readonly oneOf?: readonly { readonly $ref: string }[] }>>;
}

const schema: PlaySchema = JSON.parse(
  readFileSync(resolve(process.cwd(), '../shared-api/src/commonMain/resources/schemas/Play.schema.json'), 'utf8'),
) as PlaySchema;

/** Fields every stop carries (`StopBase` in `stop.model.ts`) — excluded before comparing the rest. */
const COMMON_FIELDS = ['id', 'type', 'title', 'speak', 'ingredient', 'parentTip'];

/**
 * The required fields `stop.model.ts` declares beyond `StopBase`, one row per type — i.e. the
 * interface's properties that are *not* suffixed `?`. Kept by hand because TypeScript has no
 * runtime reflection to read this back off the interfaces themselves; a type added to the union
 * without a row here fails the "every STOP_TYPES entry has a row" check below.
 */
const REQUIRED_FIELDS: Readonly<Record<StopType, readonly string[]>> = {
  readPage: ['pageNumber', 'sentences'],
  storyPieces: ['cards'],
  wordCards: ['words'],
  move: ['actions'],
  explain: ['skillId', 'explanation', 'workedExamples'],
  choice: ['hint', 'question', 'options', 'correctOptionId'],
  trueFalse: ['hint', 'statement', 'answer'],
  sequence: ['hint', 'chips', 'options', 'correctOptionId', 'numberLine'],
  count: ['hint', 'objectKey', 'groupSizes', 'options', 'correctOptionId', 'numberLine'],
  compare: ['hint', 'left', 'right', 'options', 'correctOptionId', 'numberLine'],
  sound: ['hint', 'illustrationKey', 'options', 'correctOptionId'],
  word: ['hint', 'spokenWord', 'options', 'correctOptionId'],
  readTap: ['hint', 'word', 'options', 'correctOptionId'],
  multiSelect: ['prompt', 'options', 'correctIds', 'pick'],
  selectAll: ['prompt', 'options', 'correctIds'],
  match: ['prompt', 'pairs'],
  order: ['prompt', 'items', 'correctOrder'],
  trace: ['text', 'hint'],
  retell: ['prompt', 'cues', 'modelAnswer'],
  openAnswer: ['prompt', 'mode', 'modelAnswer'],
  writeSentence: ['frame', 'answer'],
  exitTicket: ['questions'],
};

/** `$defs.stop.oneOf` lists `{ $ref: "#/$defs/stop_<type>" }` — the schema's own type roster. */
function schemaStopTypes(): readonly string[] {
  const refs = schema.$defs['stop']?.oneOf ?? [];
  return refs.map((ref) => ref.$ref.replace('#/$defs/stop_', ''));
}

function schemaDef(type: string): StopSchemaDef {
  const def = schema.$defs[`stop_${type}`];
  if (!def) throw new Error(`Play.schema.json has no $defs.stop_${type}`);
  return def;
}

describe('stop.model.ts against Play.schema.json', () => {
  it('declares exactly the schema stop types in STOP_TYPES', () => {
    expect(new Set(STOP_TYPES)).toEqual(new Set(schemaStopTypes()));
  });

  it('has a REQUIRED_FIELDS row for every STOP_TYPES entry', () => {
    expect(Object.keys(REQUIRED_FIELDS).sort()).toEqual([...STOP_TYPES].sort());
  });

  it.each(STOP_TYPES)('matches the schema’s required fields for "%s"', (type) => {
    const schemaRequired = schemaDef(type).required.filter((field) => !COMMON_FIELDS.includes(field));
    expect(new Set(REQUIRED_FIELDS[type])).toEqual(new Set(schemaRequired));
  });
});
