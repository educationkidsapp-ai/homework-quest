import { ESLint, Linter } from 'eslint';
import { describe, expect, it } from 'vitest';
// @ts-expect-error — the local plugin is plain ESM and ships no types; it is also the
// entry point eslint.config.mjs uses, so there is nothing else to import here.
import { hqPlugin } from '../../eslint/index.mjs';

const linter = new Linter();

/** Fixtures are written so espree can parse them — these rules are text-level, not type-aware. */
function lint(code: string, rule: string, filename = 'file.ts'): Linter.LintMessage[] {
  const config = [
    {
      // Flat config matches by filename, so a `.ts` fixture needs an entry that claims it.
      files: ['**/*.ts'],
      plugins: { hq: hqPlugin as unknown as ESLint.Plugin },
      rules: { [`hq/${rule}`]: 'error' as const },
    },
  ];
  return linter.verify(code, config, filename);
}

// Assembled at runtime so these specs do not themselves contain the banned literal.
const PRODUCT = ['Schools', 'Dashboard'].join(' ');
const OTHER_PRODUCT = ['Homework', 'Quest'].join(' ');

describe('hq/no-product-name-literal', () => {
  it('fails on the product name in a string literal', () => {
    const messages = lint(`const title = '${PRODUCT}';`, 'no-product-name-literal');

    expect(messages).toHaveLength(1);
    expect(messages[0]?.message).toContain('Do not hard-code the product name');
  });

  it('fails on the product name inside a template literal', () => {
    const messages = lint(`const t = \`Welcome to ${OTHER_PRODUCT}\`;`, 'no-product-name-literal');

    expect(messages).toHaveLength(1);
  });

  it('passes when the name comes from platform settings', () => {
    expect(lint(`const title = settings.name;`, 'no-product-name-literal')).toHaveLength(0);
  });
});

describe('hq/feature-flag-reference', () => {
  it('fails a page that gates nothing', () => {
    const messages = lint(`export class LessonsPage {}`, 'feature-flag-reference', 'lessons.page.ts');

    expect(messages).toHaveLength(1);
    expect(messages[0]?.message).toContain('must reference a feature flag');
  });

  it('passes a route file that uses featureGuard', () => {
    const code = `export const routes = [{ path: '', canActivate: [featureGuard('complaints')] }];`;

    expect(lint(code, 'feature-flag-reference', 'complaints.routes.ts')).toHaveLength(0);
  });

  it('passes a page whose template uses the hqFeature directive', () => {
    const code = `export const template = '<div *hqFeature="complaints"></div>';`;

    expect(lint(code, 'feature-flag-reference', 'p.page.ts')).toHaveLength(0);
  });

  it('passes a shell page that opts out explicitly', () => {
    const code = `/* hq-flag: none (shell) — the sign-in screen predates any flag. */\nexport class SignInPage {}`;

    expect(lint(code, 'feature-flag-reference', 'sign-in.page.ts')).toHaveLength(0);
  });
});

describe('hq/no-raw-http', () => {
  const IMPORT = `import { HttpClient } from '@angular/common/http';`;

  it('fails on HttpClient imported into a feature', () => {
    const messages = lint(IMPORT, 'no-raw-http', 'src/app/features/home/home.page.ts');

    expect(messages).toHaveLength(1);
    expect(messages[0]?.message).toContain('generated service');
  });

  it('fails on HttpClient injected into a service', () => {
    const code = `const http = inject(HttpClient);`;

    expect(lint(code, 'no-raw-http', 'src/app/core/theme/theme.service.ts')).toHaveLength(1);
  });

  it('passes inside the generated client and the interceptors', () => {
    expect(lint(IMPORT, 'no-raw-http', 'src/app/api/api-error.ts')).toHaveLength(0);
    expect(lint(IMPORT, 'no-raw-http', 'src/app/core/http/auth.interceptor.ts')).toHaveLength(0);
    // The Transloco loader fetches a file out of the bundle, not the API.
    expect(lint(IMPORT, 'no-raw-http', 'src/app/core/i18n/transloco-loader.ts')).toHaveLength(0);
  });

  it('passes in a spec — that is how an interceptor is tested', () => {
    expect(lint(IMPORT, 'no-raw-http', 'src/app/core/http/interceptors.spec.ts')).toHaveLength(0);
  });

  it('leaves the other HTTP symbols alone', () => {
    const code = `import { HttpErrorResponse, HttpContext } from '@angular/common/http';`;

    expect(lint(code, 'no-raw-http', 'src/app/features/home/home.page.ts')).toHaveLength(0);
  });
});
