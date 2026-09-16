/**
 * `hq/feature-flag-reference`
 *
 * Every screen and every feature route file must say which flag gates it. Without this,
 * a feature ships enabled for every school the moment it merges, and turning it off means
 * a deploy rather than a toggle in the Admin panel.
 *
 * A file satisfies the rule when it mentions `featureGuard(` or `hqFeature`, or carries an
 * explicit allow-list comment for the parts of the shell that genuinely have no flag:
 *
 *     /* hq-flag: none (shell) — why this screen is not gated *\/
 *
 * Applied to `src/app/features/ ** /*.routes.ts` and every `*.page.ts`. The features folder
 * is empty in P1.0 on purpose: the rule is in place before the first feature lands in P3.
 */
/**
 * `areaRoutes(` counts because it *is* the flag reference: it builds a role's routes from the
 * one table in `core/nav/screens.ts`, attaching `featureGuard` to every screen that declares a
 * flag. A route file that delegates to it has said where its gating lives — and cannot drift
 * from the rail, which is built from the same rows. Re-listing the guards beside it would be
 * the drift this rule exists to prevent.
 */
const FLAG_REFERENCES = ['featureGuard(', 'hqFeature', 'areaRoutes('];
const ALLOW_LIST = /\/\*\s*hq-flag:\s*none\s*\(shell\)/;

/** @type {import('eslint').Rule.RuleModule} */
export const featureFlagReference = {
  meta: {
    type: 'problem',
    docs: {
      description:
        'Require every feature route file and page component to reference a feature flag or carry the shell allow-list comment.',
    },
    schema: [],
    messages: {
      missing:
        'This file must reference a feature flag (`featureGuard(\'key\')` or `*hqFeature="key"`), or declare itself part of the shell with a `/* hq-flag: none (shell) — reason */` comment.',
    },
  },
  create(context) {
    return {
      'Program:exit'(node) {
        const source = context.sourceCode.getText();
        if (FLAG_REFERENCES.some((reference) => source.includes(reference))) return;
        if (ALLOW_LIST.test(source)) return;
        context.report({ node, messageId: 'missing' });
      },
    };
  },
};
