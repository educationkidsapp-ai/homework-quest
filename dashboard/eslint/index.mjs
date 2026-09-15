import { noProductNameLiteral } from './no-product-name-literal.mjs';
import { featureFlagReference } from './feature-flag-reference.mjs';

/**
 * The `hq` ESLint plugin: the two rules that keep a promise the product makes.
 * Local rather than published — they are about this repository's conventions.
 */
export const hqPlugin = {
  meta: { name: 'eslint-plugin-hq', version: '1.0.0' },
  rules: {
    'no-product-name-literal': noProductNameLiteral,
    'feature-flag-reference': featureFlagReference,
  },
};

export default hqPlugin;
