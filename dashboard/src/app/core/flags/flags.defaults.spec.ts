import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { DEFAULT_FLAGS } from './flags.defaults';

/**
 * `DEFAULT_FLAGS` is hand-copied three times now (`V5__flags_themes.sql`, `shared-api`'s
 * `quest.api.DEFAULT_FLAGS`, this file) — this spec is what keeps this copy from drifting from
 * the migration, the one place the other two already check themselves against
 * (`FlagAdminTest.the_fourteen_flags_are_seeded_exactly_as_the_code_lists_them`).
 */
describe('DEFAULT_FLAGS', () => {
  it('matches every key and default_on in V5__flags_themes.sql', () => {
    const sqlPath = resolve(process.cwd(), '../server/src/main/resources/db/migration/V5__flags_themes.sql');
    const sql = readFileSync(sqlPath, 'utf8');

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

    // A change to the regex, or to the migration's shape, must not quietly match nothing.
    expect(Object.keys(seeded).length).toBe(14);
    expect(seeded).toEqual(DEFAULT_FLAGS);
  });
});
