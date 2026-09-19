import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const root = process.cwd();

/**
 * The woff2 files are committed, and `pnpm fonts --check` is what makes that safe: it rebuilds
 * each face from its TTF and compares sizes, so a face changed upstream, a subset range edited
 * without a rebuild, or a file dropped from the tree all fail here rather than in the browser.
 *
 * It is a test rather than only a CI step because the failure it catches is invisible: the page
 * still renders, in the fallback, a little wider and a little differently spaced.
 */
describe('fonts', () => {
  it('has committed woff2 files that match the source TTFs', () => {
    expect(() =>
      execFileSync(process.execPath, ['tools/fonts.mjs', '--check'], { cwd: root, encoding: 'utf8' }),
    ).not.toThrow();
  });

  it('ships the OFL licence beside the Outfit TTF it is granted by', () => {
    const licence = readFileSync(resolve(root, 'fonts/OFL.txt'), 'utf8');
    expect(licence).toContain('SIL Open Font License');
    expect(licence).toContain('Outfit Project Authors');
  });

  it('names the Outfit face and the Arabic faces the stylesheet asks for', () => {
    const styles = readFileSync(resolve(root, 'src/styles.scss'), 'utf8');
    expect(styles).toContain("url('assets/fonts/outfit_variable.woff2') format('woff2-variations')");
    expect(styles).toContain("url('assets/fonts/ibmplexsansarabic_regular.woff2')");
    // A font that blocks the first paint is worse than a font that swaps in late.
    expect(styles.match(/font-display: swap;/g)).toHaveLength(3);
  });
});
