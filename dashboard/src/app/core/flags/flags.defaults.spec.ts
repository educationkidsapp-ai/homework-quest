import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { DEFAULT_FLAGS } from './flags.defaults';

/**
 * `DEFAULT_FLAGS` is hand-copied three times now (the migrations, `shared-api`'s
 * `quest.api.DEFAULT_FLAGS`, this file) — this spec is what keeps this copy from drifting from
 * them, the one place the other two already check themselves against
 * (`FlagAdminTest.the_fourteen_flags_are_seeded_exactly_as_the_code_lists_them`).
 *
 * Two migrations seed flags: `V5__flags_themes.sql` (§4's fourteen) and `V7__sections.sql` (the
 * seven the one-school build adds). Both use the same `SELECT '<key>', '<description>', TRUE|FALSE`
 * shape, so one pattern reads both — and a third migration that seeds flags will be caught by the
 * count, not silently ignored.
 */
const MIGRATIONS: readonly { readonly file: string; readonly rows: number }[] = [
  { file: 'V5__flags_themes.sql', rows: 14 },
  { file: 'V7__sections.sql', rows: 7 },
];

function seededBy(file: string): Record<string, boolean> {
  const sql = readFileSync(
    resolve(process.cwd(), '../server/src/main/resources/db/migration/', file),
    'utf8',
  );

  // Each seeded row is `SELECT '<key>' [AS flag_key], '<description>' [AS description],
  // TRUE|FALSE [AS default_on], ...` — the first row spells out the AS aliases, the rest are
  // positional, so both are optional here.
  const rowPattern =
    /SELECT\s+'([^']+)'(?:\s+AS flag_key)?\s*,\s*'[^']*'(?:\s+AS description)?\s*,\s*(TRUE|FALSE)\s+AS default_on|SELECT\s+'([^']+)'\s*,\s*'[^']*'\s*,\s*(TRUE|FALSE)\s*,/g;
  const seeded: Record<string, boolean> = {};
  for (const match of sql.matchAll(rowPattern)) {
    const key = match[1] ?? match[3];
    const value = match[2] ?? match[4];
    if (key && value) seeded[key] = value === 'TRUE';
  }
  return seeded;
}

describe('DEFAULT_FLAGS', () => {
  it('matches every key and default_on seeded by the migrations', () => {
    const seeded: Record<string, boolean> = {};
    for (const migration of MIGRATIONS) {
      const rows = seededBy(migration.file);
      // A change to the regex, or to a migration's shape, must not quietly match nothing.
      expect(`${migration.file}:${Object.keys(rows).length}`).toBe(`${migration.file}:${migration.rows}`);
      Object.assign(seeded, rows);
    }

    expect(seeded).toEqual(DEFAULT_FLAGS);
  });

  /** D13: one school until this is flipped, so the dashboard must not assume otherwise offline. */
  it('leaves multiSchool off', () => {
    expect(DEFAULT_FLAGS['multiSchool']).toBe(false);
  });
});
