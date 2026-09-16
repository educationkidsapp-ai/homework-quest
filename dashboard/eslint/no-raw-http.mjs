/**
 * `hq/no-raw-http`
 *
 * Every call to the API goes through the client generated from `server/openapi.json`
 * (`pnpm gen:api`). A hand-written `HttpClient` call is a second, undeclared copy of the
 * contract: it keeps compiling after the server renames a field, and it is invisible to the
 * drift check that keeps the two in step.
 *
 * So `HttpClient` may only be named in:
 *   - `src/app/api/**`        the generated client and the providers that configure it;
 *   - `src/app/core/http/**`  the interceptors, which act on every request by definition;
 *   - `src/app/core/i18n/**`  the Transloco loader, which fetches a static asset from the
 *                             bundle rather than the API.
 *
 * Specs may name it freely — `provideHttpClientTesting` is how an interceptor is tested.
 */
/**
 * Anchored to a path segment rather than a substring, and tolerant of both the absolute path
 * ESLint reports and the relative one a unit test passes in.
 */
const ALLOWED = [/(^|\/)src\/app\/api\//, /(^|\/)src\/app\/core\/http\//, /(^|\/)src\/app\/core\/i18n\//];

/** @type {import('eslint').Rule.RuleModule} */
export const noRawHttp = {
  meta: {
    type: 'problem',
    docs: {
      description:
        'Disallow HttpClient outside the generated API client and the interceptors; call the generated services instead.',
    },
    schema: [],
    messages: {
      forbidden:
        'Do not call the API by hand. Inject a generated service from `src/app/api/generated` ' +
        '(regenerate with `pnpm gen:api`) instead of `HttpClient`.',
    },
  },
  create(context) {
    const filename = context.filename.replace(/\\/g, '/');
    if (filename.endsWith('.spec.ts')) return {};
    if (ALLOWED.some((allowed) => allowed.test(filename))) return {};

    return {
      ImportDeclaration(node) {
        if (node.source.value !== '@angular/common/http') return;
        for (const specifier of node.specifiers)
          if (specifier.type === 'ImportSpecifier' && specifier.imported.name === 'HttpClient')
            context.report({ node: specifier, messageId: 'forbidden' });
      },
      "CallExpression[callee.name='inject'] > Identifier[name='HttpClient']"(node) {
        context.report({ node, messageId: 'forbidden' });
      },
    };
  },
};
