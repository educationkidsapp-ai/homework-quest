/**
 * Schema validation of a stop document, for the specs that hold the templates and the structured
 * stops to the contract.
 *
 * `Play.schema.json` — the file the server itself validates against — defines one self-contained
 * branch per stop type under `$defs.stop_<type>`: `additionalProperties: false` and every field
 * that type needs, nothing shared with the other twenty-one. Validating against only the declared
 * type's branch, instead of the whole `oneOf`, is what makes the errors readable.
 *
 * **The validators are compiled at build time**, by `pnpm schemas` (see `tools/schemas.mjs`).
 * Nothing on a screen imports this module since the Admin's Raw JSON panel went (the owner's list
 * of 2026-10-01), so the 605 kB generated module is in no bundle at all.
 */
import type { StopType } from '../../ui/phone-preview';
import type { StopSchemaError, StopSchemaValidator } from './stop-validators.generated';

export interface StopValidationResult {
  readonly valid: boolean;
  /** `"<field path> <message>"`, e.g. `"/options must NOT have fewer than 2 items"`. */
  readonly errors: readonly string[];
}

let validatorsPromise: Promise<Readonly<Record<string, StopSchemaValidator>>> | null = null;

async function loadValidators(): Promise<Readonly<Record<string, StopSchemaValidator>>> {
  validatorsPromise ??= import('./stop-validators.generated').then((module) => module.STOP_VALIDATORS);
  return validatorsPromise;
}

/**
 * Ajv's `additionalProperties` message names the object but not the offending key — "must NOT
 * have additional properties" over a 40-line stop is a puzzle, so the key is appended here.
 */
function describe(error: StopSchemaError): string {
  const extra = error.params?.additionalProperty;
  const message = extra ? `${error.message ?? ''} (${extra})` : (error.message ?? '');
  return `${error.instancePath || '/'} ${message}`.trim();
}

/** The same check over an already-parsed value — what the templates' spec asserts with. */
export async function validateStopValue(type: StopType, value: unknown): Promise<StopValidationResult> {
  let validate: StopSchemaValidator | undefined;
  try {
    validate = (await loadValidators())[type];
  } catch (error) {
    // Never fall through to "valid": a validator that quietly stopped working is worse than one
    // that is plainly broken. Forgetting the module lets the next call fetch it again.
    validatorsPromise = null;
    return { valid: false, errors: [error instanceof Error ? error.message : 'The validators could not load.'] };
  }
  if (!validate) return { valid: false, errors: [`No schema for stop type "${type}".`] };

  const valid = validate(value) === true;
  return { valid, errors: (validate.errors ?? []).map(describe) };
}
