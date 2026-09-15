/**
 * `hq/no-product-name-literal`
 *
 * The product name is platform data (`PlatformSettings.name`, seeded server-side), not a
 * constant: a white-labelled deployment renames it without a rebuild. Hard-coding it in a
 * component or a route title is the one change that cannot be undone from the Admin panel,
 * so it fails the build here instead.
 *
 * Allowed in `*.spec.ts` (a test may assert on the seeded value) and in
 * `src/assets/i18n/**` (translations hold whatever the platform is called).
 */
const FORBIDDEN = ['Homework Quest', 'Schools Dashboard'];

/** @type {import('eslint').Rule.RuleModule} */
export const noProductNameLiteral = {
  meta: {
    type: 'problem',
    docs: {
      description:
        'Disallow the product name as a string literal; read it from PlatformSettings or a translation key.',
    },
    schema: [],
    messages: {
      forbidden:
        'Do not hard-code the product name "{{name}}". Read it from PlatformSettings (or a translation key) so a white-labelled deployment can rename it.',
    },
  },
  create(context) {
    const report = (node, value) => {
      if (typeof value !== 'string') return;
      const name = FORBIDDEN.find((candidate) => value.includes(candidate));
      if (name) context.report({ node, messageId: 'forbidden', data: { name } });
    };

    return {
      Literal(node) {
        report(node, node.value);
      },
      TemplateElement(node) {
        report(node, node.value.cooked ?? node.value.raw);
      },
    };
  },
};
