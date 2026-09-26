/**
 * Live validation for the JSON stop editor (dev prompt §4.4, §6 screen 8/13).
 *
 * `Play.schema.json` — the file the server itself validates against — defines one self-contained
 * branch per stop type under `$defs.stop_<type>`: `additionalProperties: false` and every field
 * that type needs, nothing shared with the other twenty-one. Validating against only the declared
 * type's branch, instead of the whole `oneOf`, is what makes the errors readable: a teacher
 * editing a `choice` stop never sees "`statement` is required", which belongs to `trueFalse`,
 * among twenty-two simultaneous failures.
 *
 * **The validators are compiled at build time**, by `pnpm schemas` (see `tools/schemas.mjs`).
 * Ajv's normal path builds them with `new Function`, which the server's `default-src 'self'` CSP
 * refuses — an `EvalError` in the browser that no jsdom test would ever see. The generated module
 * is plain JavaScript and imported lazily, so it is a chunk the lesson route pays for only once a
 * stop is open for editing, and never part of the initial bundle.
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
 * Whether the 605 kB chunk has been asked for yet.
 *
 * E4a made that a rule rather than an accident: the module is the Raw JSON panel's, so a teacher
 * — who cannot open that panel — must never download it, and `stop-editor-chunk.spec.ts` asserts
 * on this rather than on a network log a jsdom test does not have.
 */
export function validatorsRequested(): boolean {
  return validatorsPromise !== null;
}

/**
 * Forget the module, so the next validation fetches it again.
 *
 * The recovery path for a load that failed — a deploy mid-session replaces the hashed chunk, and
 * the import 404s — and what lets the chunk spec start from a clean slate whatever ran before it
 * in the same module registry.
 */
export function forgetValidators(): void {
  validatorsPromise = null;
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

/** Parses and validates `text` against `type`'s branch only. A JSON parse error is one error. */
export async function validateStop(type: StopType, text: string): Promise<StopValidationResult> {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch (error) {
    return { valid: false, errors: [error instanceof Error ? error.message : 'Invalid JSON.'] };
  }
  return validateStopValue(type, parsed);
}

/** The same check over an already-parsed value — what the templates' spec asserts with. */
export async function validateStopValue(type: StopType, value: unknown): Promise<StopValidationResult> {
  let validate: StopSchemaValidator | undefined;
  try {
    validate = (await loadValidators())[type];
  } catch (error) {
    // Never fall through to "valid": Save stays off and the reason is on screen, because a
    // validator that quietly stopped working is worse than one that is plainly broken.
    forgetValidators();
    return { valid: false, errors: [error instanceof Error ? error.message : 'The validators could not load.'] };
  }
  if (!validate) return { valid: false, errors: [`No schema for stop type "${type}".`] };

  const valid = validate(value) === true;
  return { valid, errors: (validate.errors ?? []).map(describe) };
}

/** The `type` a stop document declares, or `null` when it is missing or not one of the 22. */
export function declaredStopType(text: string, known: readonly StopType[]): StopType | null {
  try {
    const parsed: unknown = JSON.parse(text);
    if (typeof parsed !== 'object' || parsed === null) return null;
    const type = (parsed as { type?: unknown }).type;
    return typeof type === 'string' && (known as readonly string[]).includes(type) ? (type as StopType) : null;
  } catch {
    return null;
  }
}
