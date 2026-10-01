import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { SEED_STOPS, STOP_TYPES } from '../../ui/phone-preview';
import { validateStopValue } from './stop-validator';
import { STOP_VALIDATORS } from './stop-validators.generated';

describe('pnpm schemas', () => {
  /**
   * `stop-validators.generated.js` is written by `postinstall`, so it cannot be stale in a fresh
   * checkout — but it can be stale *here*, and CI's `dashboard` job is not triggered by a
   * `shared-api/**` change (its paths filter lists only `dashboard/**`, `design/tokens.json`,
   * `server/openapi.json` and `permissions.json`). This reads the schema off disk and checks the
   * generated module covers exactly the branches it declares.
   */
  it('compiles one validator per stop branch the schema declares', () => {
    const schema = JSON.parse(
      readFileSync(resolve(process.cwd(), '../shared-api/src/commonMain/resources/schemas/Play.schema.json'), 'utf8'),
    ) as { $defs: Record<string, { properties?: { type?: { const?: string } } }> };

    const declared = Object.entries(schema.$defs)
      .filter(([name]) => name.startsWith('stop_'))
      .map(([, def]) => def.properties?.type?.const)
      .filter((type): type is string => typeof type === 'string');

    expect(new Set(declared)).toEqual(new Set(STOP_TYPES));
    expect(Object.keys(STOP_VALIDATORS).sort()).toEqual([...declared].sort());
  });
});

describe('validateStopValue', () => {
  it('accepts a stop that matches its own branch', async () => {
    const result = await validateStopValue('choice', SEED_STOPS['choice']);
    expect(result).toEqual({ valid: true, errors: [] });
  });

  /**
   * The point of validating one branch rather than the whole `oneOf`: a `choice` missing its
   * `question` says so, and says nothing about `statement`, `chips` or the other twenty
   * branches' required fields.
   */
  it('reports only the declared type branch errors', async () => {
    const { question, ...withoutQuestion } = SEED_STOPS['choice'] as unknown as Record<string, unknown>;
    expect(question).toBeDefined();
    const result = await validateStopValue('choice', withoutQuestion);

    expect(result.valid).toBe(false);
    expect(result.errors.join('\n')).toContain('question');
    expect(result.errors.join('\n')).not.toContain('statement');
    expect(result.errors.join('\n')).not.toContain('chips');
  });

  it('rejects a property no branch declares (additionalProperties: false)', async () => {
    const result = await validateStopValue('trueFalse', { ...SEED_STOPS['trueFalse'], nonsense: 1 });
    expect(result.valid).toBe(false);
    expect(result.errors.join('\n')).toContain('nonsense');
  });
});
