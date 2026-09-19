import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import tokens from '../../../design/tokens.json';

// Read from disk rather than importing: the point of the spec is that the committed
// artefact matches the source, not that the build can compile it.
const generated = readFileSync(resolve(process.cwd(), 'src/styles/_tokens.generated.scss'), 'utf8');

describe('design tokens', () => {
  // The generator itself, not only its output: `design/tokens.json` is shared with the mobile
  // app, and a token added there without a regeneration here is a property the dashboard reads
  // and gets nothing for.
  it('has no drift between design/tokens.json and the committed stylesheet', () => {
    expect(() =>
      execFileSync(process.execPath, ['tools/tokens.mjs', '--check'], {
        cwd: process.cwd(),
        encoding: 'utf8',
      }),
    ).not.toThrow();
  });

  it('generates a custom property for every colour, keeping the same value', () => {
    for (const [name, token] of Object.entries(tokens.color)) {
      expect(generated).toContain(`--hq-color-${name}: ${token.value};`);
    }
  });

  it('keeps the design language constants the rest of the system is built on', () => {
    expect(generated).toContain('--hq-size-nav-width: 240px;');
    expect(generated).toContain('--hq-size-content-max-width: 1100px;');
    expect(generated).toContain('--hq-size-rule: 2px;');
    expect(generated).toContain('--hq-size-row-height: 56px;');
    expect(generated).toContain('--hq-size-button-height: 44px;');
    expect(generated).toContain('--hq-size-input-height: 48px;');
    expect(generated).toContain('--hq-font-title-size: 30px;');
    expect(generated).toContain('--hq-size-radius: 0px;');
  });

  it('exposes the motion durations and easings the brief fixes', () => {
    expect(generated).toContain('--hq-motion-fast: 150ms;');
    expect(generated).toContain('--hq-motion-base: 250ms;');
    expect(generated).toContain('--hq-motion-slow: 400ms;');
    expect(generated).toContain('--hq-motion-ease: cubic-bezier(0.2, 0, 0, 1);');
    expect(generated).toContain('--hq-motion-ease-emphasised: cubic-bezier(0.05, 0.7, 0.1, 1);');
  });

  it('carries the per-school override placeholders', () => {
    expect(generated).toContain('--hq-world-math-primary:');
    expect(generated).toContain('--hq-world-english-primary:');
    expect(generated).toContain('--hq-mascot-color-body:');
  });
});
