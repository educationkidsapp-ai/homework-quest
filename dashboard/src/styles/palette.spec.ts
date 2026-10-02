import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

// D3. The palette is the logo's, `_theme.scss` is where it is written down, and
// `docs/brand/palette.md` is where the mobile app reads it from. This spec resolves the roles
// statically — the same arithmetic as the server's `Contrast` (WCAG 2.x relative luminance) —
// so a ramp step cannot move without the pairs that depend on it being measured again, and the
// published table cannot drift from the stylesheet.

const theme = readFileSync(resolve(process.cwd(), 'src/styles/_theme.scss'), 'utf8');
const published = readFileSync(resolve(process.cwd(), '../docs/brand/palette.md'), 'utf8');

type Rgb = readonly [number, number, number];
type Rgba = readonly [number, number, number, number];

/** The declarations of one top-level block, e.g. `:root` or `html.dark`. */
function block(selector: string): Map<string, string> {
  const start = theme.indexOf(`\n${selector} {`);
  expect(start, `${selector} is missing from _theme.scss`).toBeGreaterThan(-1);
  const end = theme.indexOf('\n}', start);
  const out = new Map<string, string>();
  const body = theme.slice(start, end).replace(/\/\/[^\n]*/g, '');
  for (const match of body.matchAll(/(--hq-[\w-]+):\s*([^;]+);/g)) {
    out.set(match[1]!, match[2]!.replace(/\s*!important/, '').replace(/\s+/g, ' ').trim());
  }
  return out;
}

const light = block(':root');
const dark = new Map([...light, ...block('html.dark')]);

function hex(value: string): Rgba {
  const h = value.slice(1);
  return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16), 1];
}

/** Splits `a, b` at the top level of a function's arguments. */
function args(inner: string): string[] {
  const out: string[] = [];
  let depth = 0;
  let current = '';
  for (const char of inner) {
    if (char === '(') depth++;
    if (char === ')') depth--;
    if (char === ',' && depth === 0) {
      out.push(current.trim());
      current = '';
    } else current += char;
  }
  out.push(current.trim());
  return out;
}

function resolveColour(value: string, scheme: Map<string, string>): Rgba {
  const v = value.trim();
  if (v === 'transparent') return [0, 0, 0, 0];
  if (/^#[0-9a-f]{6}$/i.test(v)) return hex(v);
  if (v.startsWith('var(')) {
    const name = v.slice(4, -1).trim();
    const next = scheme.get(name);
    if (next === undefined) throw new Error(`${name} is not declared`);
    return resolveColour(next, scheme);
  }
  if (v.startsWith('rgb(')) {
    const parts = v.slice(4, -1).split(/[\s/]+/).map(Number);
    return [parts[0]!, parts[1]!, parts[2]!, parts[3] ?? 1];
  }
  if (v.startsWith('color-mix(')) {
    const [, first, second] = args(v.slice('color-mix('.length, -1));
    const share = /^(.*)\s+([\d.]+)%$/.exec(first!);
    if (!share) throw new Error(`cannot read ${v}`);
    const p = Number(share[2]) / 100;
    const a = resolveColour(share[1]!, scheme);
    const b = resolveColour(second!, scheme);
    // Premultiplied, as the specification mixes: a share of `transparent` is an alpha.
    const alpha = a[3] * p + b[3] * (1 - p);
    const mix = (i: 0 | 1 | 2): number =>
      alpha === 0 ? 0 : (a[i] * a[3] * p + b[i] * b[3] * (1 - p)) / alpha;
    return [mix(0), mix(1), mix(2), alpha];
  }
  throw new Error(`cannot read ${v}`);
}

function over(top: Rgba, bottom: Rgb): Rgb {
  return [0, 1, 2].map((i) => top[3] * top[i]! + (1 - top[3]) * bottom[i]!) as unknown as Rgb;
}

function luminance(rgb: Rgb): number {
  const channel = (value: number): number => {
    const c = value / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * channel(rgb[0]) + 0.7152 * channel(rgb[1]) + 0.0722 * channel(rgb[2]);
}

/** Text role on a background role, the background composited on the scheme's card surface. */
function contrast(scheme: Map<string, string>, text: string, background: string): number {
  const card = over(resolveColour('var(--hq-color-surface)', scheme), [255, 255, 255]);
  const under = over(resolveColour(background, scheme), card);
  const above = over(resolveColour(text, scheme), under);
  const [hi, lo] = [luminance(above), luminance(under)].sort((x, y) => y - x) as [number, number];
  return (hi + 0.05) / (lo + 0.05);
}

const role = (name: string): string => `var(--hq-color-${name})`;
const WHITE = '#ffffff';

/** Every pair that is set as text somewhere: `[text, background]`. */
const TEXT_PAIRS: readonly (readonly [string, string])[] = [
  [role('ink'), role('surface')],
  [role('ink'), role('bg')],
  [role('ink-soft'), role('surface')],
  [role('ink-soft'), role('bg')],
  [role('ink-strong'), role('surface')],
  [role('accent-ink'), role('surface')],
  [role('accent-ink'), role('bg')],
  [role('accent-on-soft'), role('accent-soft')],
  [role('brand-ink'), role('surface')],
  [role('brand-ink'), role('bg')],
  [role('info-ink'), role('info-soft')],
  [role('secondary-ink'), role('surface')],
  [role('secondary-ink'), role('secondary-soft')],
  [role('tertiary-ink'), role('surface')],
  [role('tertiary-ink'), role('tertiary-soft')],
  [role('success-ink'), role('success-soft')],
  [role('warning-ink'), role('warning-soft')],
  [role('error-ink'), role('error-soft')],
  [role('neutral-ink'), role('neutral-soft')],
  // White labels on the fills that carry them.
  [WHITE, role('accent')],
  [WHITE, role('brand-fill')],
  [WHITE, role('brand-fill-hover')],
  [WHITE, 'var(--hq-gradient-brand-fill-from)'],
  [WHITE, 'var(--hq-gradient-brand-to)'],
  [WHITE, role('secondary')],
  // Pairs written in feature styles rather than as a role pair (review of #189).
  [role('on-warning'), role('warning-500')], // attendance "late", the amber tile
  [role('on-warning'), role('warning-600')],
  [role('brand-ink'), role('info-soft')], // count badges, quiet buttons, the Word chip
  [role('warning-700'), role('warning-100')], // `.em-pill--warning` / `--leave`
];

/** Pairs that only exist under `html.dark`. */
const DARK_PAIRS: readonly (readonly [string, string])[] = [
  [role('brand-300'), 'color-mix(in srgb, var(--hq-color-brand-500) 20%, transparent)'], // info pill, emoji tab
  [role('brand-300'), 'color-mix(in srgb, var(--hq-color-brand-500) 25%, transparent)'],
  [role('warning-300'), 'color-mix(in srgb, var(--hq-color-warning-500) 20%, transparent)'],
];

/** Glyphs and ticks, which need 3:1 rather than 4.5:1 — measured on both ends of a gradient. */
const GLYPH_PAIRS: readonly (readonly [string, string])[] = [
  [role('brand-100'), 'var(--hq-gradient-brand-fill-from)'], // the chat bubble's read tick
  [role('brand-100'), 'var(--hq-gradient-brand-to)'],
  [WHITE, role('orange-600')], // `--hq-gradient-orange`, first stop
  [WHITE, role('gray-500')], // `--hq-gradient-neutral`, first stop
  [WHITE, role('brand-400')], // `--hq-gradient-blue`
  [WHITE, role('magenta-400')], // `--hq-gradient-magenta`
];

describe('logo palette', () => {
  it('keeps the four colours sampled from the logo', () => {
    expect(light.get('--hq-color-brand-400')).toBe('#089cdf');
    expect(light.get('--hq-color-brand-700')).toBe('#0754b7');
    expect(light.get('--hq-color-magenta-600')).toBe('#b8047a');
    expect(light.get('--hq-color-orange-400')).toBe('#fb9b0a');
  });

  it('runs the brand gradient from the diamond’s light blue to its deep blue', () => {
    expect(light.get('--hq-gradient-angle')).toBe('135deg');
    expect(light.get('--hq-gradient-brand-from')).toBe('var(--hq-color-brand-400)');
    expect(light.get('--hq-gradient-brand-to')).toBe('var(--hq-color-brand-700)');
  });

  for (const [name, scheme] of [
    ['light', light],
    ['dark', dark],
  ] as const) {
    it(`clears AA (4.5:1) for every text/background pair, ${name}`, () => {
      const failures = TEXT_PAIRS.map(([text, background]) => ({
        pair: `${text} on ${background}`,
        ratio: contrast(scheme, text, background),
      })).filter((entry) => entry.ratio < 4.5);
      expect(failures).toEqual([]);
    });

    it(`keeps the focus ring at 3:1 or better on the page and the card, ${name}`, () => {
      for (const background of [role('bg'), role('surface'), role('surface-raised')]) {
        expect(contrast(scheme, role('focus'), background), background).toBeGreaterThanOrEqual(3);
      }
    });

    // A ring is drawn 2 px clear of its control, so what it has to stand apart from is the
    // fill beside it as well as the page: blue on blue would be the one that vanishes.
    it(`keeps the three hues apart as fills (3:1 against the surface), ${name}`, () => {
      for (const fill of [role('accent'), role('secondary')]) {
        expect(contrast(scheme, fill, role('surface')), fill).toBeGreaterThanOrEqual(3);
      }
    });
  }

  it('clears AA for the pairs that only the dark scheme draws', () => {
    for (const [text, background] of DARK_PAIRS) {
      expect(contrast(dark, text, background), `${text} on ${background}`).toBeGreaterThanOrEqual(4.5);
    }
  });

  it('keeps glyphs and ticks at 3:1 on the first and last stop of their gradient', () => {
    for (const scheme of [light, dark]) {
      for (const [glyph, fill] of GLYPH_PAIRS) {
        expect(contrast(scheme, glyph, fill), `${glyph} on ${fill}`).toBeGreaterThanOrEqual(3);
      }
    }
    expect(light.get('--hq-gradient-orange')).toContain('var(--hq-color-orange-600) 0%');
    expect(light.get('--hq-gradient-neutral')).toContain('var(--hq-color-gray-500) 0%');
  });

  it('keeps warning and error clear of the orange and the magenta', () => {
    const hue = (name: string): number => {
      const [r, g, b] = resolveColour(role(name), light).map((c) => c / 255) as [number, number, number];
      const max = Math.max(r, g, b);
      const d = max - Math.min(r, g, b);
      const h = max === r ? ((g - b) / d) % 6 : max === g ? (b - r) / d + 2 : (r - g) / d + 4;
      return (h * 60 + 360) % 360;
    };
    const apart = (a: string, b: string): number => {
      const d = Math.abs(hue(a) - hue(b));
      return Math.min(d, 360 - d);
    };
    expect(apart('warning-500', 'orange-400')).toBeGreaterThanOrEqual(8);
    expect(apart('error-500', 'magenta-600')).toBeGreaterThanOrEqual(30);
  });

  it('publishes every ramp step in docs/brand/palette.md with the value the stylesheet has', () => {
    const steps = [...light].filter(([name]) => /^--hq-color-(brand|magenta|orange|warning)-\d+$/.test(name));
    expect(steps.length).toBeGreaterThan(30);
    for (const [name, value] of steps) {
      expect(published, `${name} is not in docs/brand/palette.md`).toContain(
        `| \`${name.replace('--hq-color-', '')}\` | \`${value.toUpperCase()}\` |`,
      );
    }
  });
});
