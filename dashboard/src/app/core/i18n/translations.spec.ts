import { describe, expect, it } from 'vitest';
import ar from '../../../assets/i18n/ar.json';
import en from '../../../assets/i18n/en.json';
import { OTHER_GROUP_ID, SWITCH_GROUPS } from '../../features/admin/switches.catalogue';
import { AREAS } from '../nav/screens';

/** Every leaf key, as a dotted path. */
function keys(value: unknown, prefix = ''): string[] {
  if (typeof value !== 'object' || value === null) return [prefix];
  return Object.entries(value).flatMap(([key, child]) => keys(child, prefix ? `${prefix}.${key}` : key));
}

describe('translations', () => {
  it('has the same key set in English and Arabic', () => {
    const english = keys(en).sort();
    const arabic = keys(ar).sort();

    expect(arabic).toEqual(english);
  });

  it('leaves no value empty', () => {
    const empty = [en, ar].flatMap((bundle, index) =>
      keys(bundle)
        .filter((key) => {
          const value = key
            .split('.')
            .reduce<unknown>((node, part) => (node as Record<string, unknown>)[part], bundle);
          return typeof value !== 'string' || value.trim().length === 0;
        })
        .map((key) => `${index === 0 ? 'en' : 'ar'}:${key}`),
    );

    expect(empty).toEqual([]);
  });

  /**
   * MA0: a rail label whose row is gone is a key nothing reads, in two files, and the next person
   * to read `nav.` cannot tell it from a label that is merely hidden by a flag.
   *
   * Asserted against the table rather than against a hand-written list, so the same check catches
   * the next retired row: every `nav.*` key the rail can still ask for is one of these rows'
   * `labelKey`s, plus the handful the shell asks for by name (the brand, the role badge, and the
   * two header-menu items, which are not rail rows at all). One of the four MA0 removed being
   * re-added to the bundle without a row to show it fails here.
   */
  it('keeps no rail label whose row was retired', () => {
    const labels = new Set(
      Object.values(AREAS).flatMap((area) =>
        area.screens.flatMap((screen) => (screen.labelKey === undefined ? [] : [screen.labelKey])),
      ),
    );
    // Asked for by name rather than through a row: the rail's brand and role badge, the header's
    // chat icon, and the two titles the lesson screens set themselves.
    const BY_NAME = ['nav.brand', 'nav.label', 'nav.chat', 'nav.myLessons', 'nav.newLesson'];

    const orphans = Object.keys(en.nav)
      .map((key) => `nav.${key}`)
      .filter((key) => !labels.has(key) && !BY_NAME.includes(key));

    expect(orphans).toEqual([]);
    // And the four MA0 took out are gone from both bundles, not only from the table.
    for (const bundle of [en.nav, ar.nav])
      expect(
        Object.keys(bundle).filter((key) => ['flags', 'platformUsage', 'platformSettings'].includes(key)),
      ).toEqual([]);
  });

  /**
   * MA0 follow-up: the Feature switches page says what each switch turns on in plain language, in
   * both languages. The server's own description is the fallback for a key nothing has words for
   * yet, and that fallback is English-only — so a switch the catalogue *does* name and the bundle
   * does not is a sentence the Arabic Admin would never see. Asserted against the catalogue rather
   * than a list, so adding an entry without its words fails here instead of on screen.
   */
  it('gives every named feature switch a name and a sentence in both languages', () => {
    const paths = SWITCH_GROUPS.flatMap((group) =>
      group.entries.flatMap((entry) => [
        `admin.switches.keys.${entry.id}.name`,
        `admin.switches.keys.${entry.id}.what`,
        ...(entry.noteKey === undefined ? [] : [`admin.switches.notes.${entry.noteKey}`]),
      ]),
    );
    const groupTitles = [...SWITCH_GROUPS.map((group) => group.id), OTHER_GROUP_ID].map(
      (id) => `admin.switches.groups.${id}`,
    );

    for (const bundle of [en, ar]) {
      const present = new Set(keys(bundle));
      expect([...paths, ...groupTitles, 'nav.featureSwitches'].filter((key) => !present.has(key))).toEqual(
        [],
      );
    }
  });
});
