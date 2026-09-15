import { describe, expect, it } from 'vitest';
import ar from '../../../assets/i18n/ar.json';
import en from '../../../assets/i18n/en.json';

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
});
