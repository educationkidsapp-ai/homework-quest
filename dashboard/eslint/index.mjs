import { noProductNameLiteral } from './no-product-name-literal.mjs';
import { featureFlagReference } from './feature-flag-reference.mjs';
import { noRawHttp } from './no-raw-http.mjs';

/**
 * The `hq` ESLint plugin: the rules that keep promises the product makes.
 * Local rather than published — they are about this repository's conventions.
 */
export const hqPlugin = {
  meta: { name: 'eslint-plugin-hq', version: '1.1.0' },
  rules: {
    'no-product-name-literal': noProductNameLiteral,
    'feature-flag-reference': featureFlagReference,
    'no-raw-http': noRawHttp,
  },
};

export default hqPlugin;
