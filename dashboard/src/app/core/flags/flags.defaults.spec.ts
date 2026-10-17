import { readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { DEFAULT_FLAGS } from './flags.defaults';

/**
 * `DEFAULT_FLAGS` is hand-copied three times now (the migrations, `shared-api`'s
 * `quest.api.DEFAULT_FLAGS`, this file) — this spec is what keeps this copy from drifting from
 * them, the one place the other two already check themselves against
 * (`FlagAdminTest.the_fourteen_flags_are_seeded_exactly_as_the_code_lists_them`).
 *
 * `V5__flags_themes.sql` seeds §4's fourteen and `V7__sections.sql` the seven the one-school build
 * adds; every feature since adds its own switch in its own migration. All use the same
 * `SELECT '<key>', '<description>', TRUE|FALSE` shape, so one pattern reads them all.
 *
 * The list of migrations is *discovered*, not written down: a hand-kept list silently ignored
 * whatever it omitted, and did — `V36__staff_mobile_app.sql` and `V50__parent_evening_card.sql`
 * both seeded a flag this map never grew (the post-merge review of features 23–30). `ROWS` only
 * pins how many rows each file is expected to yield, so a change to the regex, or to a migration's
 * shape, cannot quietly match nothing; a newly discovered file with no entry fails here too, which
 * is the reminder to add its key below.
 */
const MIGRATION_DIR = resolve(process.cwd(), '../server/src/main/resources/db/migration/');

const ROWS: Readonly<Record<string, number>> = {
  'V5__flags_themes.sql': 14,
  'V7__sections.sql': 7,
  'V15__chat.sql': 1,
  'V36__staff_mobile_app.sql': 1,
  'V37__attendance_settings.sql': 1,
  'V41__chat_office_hours.sql': 1,
  'V42__floors.sql': 1,
  'V43__today_home.sql': 1,
  'V44__voice_announcement.sql': 1,
  'V46__remote_study_days.sql': 1,
  'V47__morning_brief.sql': 1,
  'V48__document_reader.sql': 1,
  'V49__complaint_triage.sql': 1,
  'V50__parent_evening_card.sql': 1,
  'V51__language_bridge.sql': 1,
  'V52__smart_hints.sql': 1,
  'V53__teaching_materials.sql': 1,
};

/**
 * The blanket flips: a migration that does not seed a key but rewrites `default_on` across the
 * board. `V54__flags_on_by_default.sql` (D30) is the first — it turns every switch on except the
 * keys it names — and each is applied, in Flyway order, over the keys the seeds above produced.
 * The value pinned here is how many keys that file excepts, for the same reason `ROWS` pins row
 * counts: a change to the SQL's shape must fail here rather than quietly match nothing.
 */
const FLIPS: Readonly<Record<string, number>> = {
  'V54__flags_on_by_default.sql': 1,
};

/** Every migration that touches `feature_flags.default_on`, in Flyway's own order. */
function flagMigrations(): readonly string[] {
  return readdirSync(MIGRATION_DIR)
    .filter((file) => file.endsWith('.sql'))
    .filter((file) => {
      const sql = readFileSync(resolve(MIGRATION_DIR, file), 'utf8');
      return sql.includes('INTO feature_flags') || /UPDATE feature_flags SET default_on/.test(sql);
    })
    .sort((a, b) => Number(/^V(\d+)__/.exec(a)?.[1]) - Number(/^V(\d+)__/.exec(b)?.[1]));
}

/**
 * A blanket `UPDATE feature_flags SET default_on = TRUE WHERE flag_key <> '…' …` — the new value
 * and the keys it leaves alone. Returns null for a file that has no such statement.
 */
function blanketFlip(file: string): { readonly value: boolean; readonly except: readonly string[] } | null {
  const sql = readFileSync(resolve(MIGRATION_DIR, file), 'utf8');
  const statement = /UPDATE feature_flags SET default_on = (TRUE|FALSE)\s*([^;]*);/.exec(sql);
  if (!statement) return null;
  const except = [...(statement[2] ?? '').matchAll(/flag_key <> '([^']+)'/g)]
    .map((m) => m[1])
    .filter((key): key is string => key !== undefined);
  return { value: statement[1] === 'TRUE', except };
}

function seededBy(file: string): Record<string, boolean> {
  const sql = readFileSync(resolve(MIGRATION_DIR, file), 'utf8');

  // Each seeded row is `SELECT '<key>' [AS flag_key], '<description>' [AS description],
  // TRUE|FALSE [AS default_on], ...` — the first row spells out the AS aliases, the rest are
  // positional, so both are optional here.
  //
  // A description may hold an apostrophe, which SQL doubles (`her floor''s`, V42) — hence
  // `'[^']*(?:''[^']*)*'` rather than `'[^']*'`: the simpler class stopped at the first half of
  // the pair and matched the row not at all, which is what the row count below catches.
  const rowPattern =
    /SELECT\s+'([^']+)'(?:\s+AS flag_key)?\s*,\s*'[^']*(?:''[^']*)*'(?:\s+AS description)?\s*,\s*(TRUE|FALSE)\s+AS default_on|SELECT\s+'([^']+)'\s*,\s*'[^']*(?:''[^']*)*'\s*,\s*(TRUE|FALSE)\s*,/g;
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
    for (const file of flagMigrations()) {
      const flip = blanketFlip(file);
      if (flip) {
        expect(`${file}:${flip.except.length}`).toBe(`${file}:${FLIPS[file]}`);
        for (const key of Object.keys(seeded)) {
          if (!flip.except.includes(key)) seeded[key] = flip.value;
        }
        continue;
      }
      const rows = seededBy(file);
      // A change to the regex, or to a migration's shape, must not quietly match nothing — and a
      // migration nobody listed here must not be skipped, which is how two keys went missing.
      expect(`${file}:${Object.keys(rows).length}`).toBe(`${file}:${ROWS[file]}`);
      Object.assign(seeded, rows);
    }

    expect(seeded).toEqual(DEFAULT_FLAGS);
  });

  /** D13: one school until this is flipped, so the dashboard must not assume otherwise offline. */
  it('leaves multiSchool off', () => {
    expect(DEFAULT_FLAGS['multiSchool']).toBe(false);
  });

  /** D30: every other switch is on, so a school that bought the platform sees all of it. */
  it('has every other switch on', () => {
    const off = Object.entries(DEFAULT_FLAGS)
      .filter(([, on]) => !on)
      .map(([key]) => key);
    expect(off).toEqual(['multiSchool']);
  });
});
