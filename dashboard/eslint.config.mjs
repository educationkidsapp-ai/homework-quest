// @ts-check
import eslint from '@eslint/js';
import tseslint from 'typescript-eslint';
import angular from 'angular-eslint';
import prettier from 'eslint-config-prettier/flat';
import { hqPlugin } from './eslint/index.mjs';

export default tseslint.config(
  {
    ignores: [
      'dist/**',
      '.angular/**',
      'node_modules/**',
      'src/app/api/generated/**',
      'coverage/**',
      'e2e/.output/**',
    ],
  },
  // Application code: typed linting plus the Angular rules.
  {
    files: ['src/**/*.ts'],
    extends: [
      eslint.configs.recommended,
      ...tseslint.configs.recommendedTypeChecked,
      ...tseslint.configs.stylistic,
      ...angular.configs.tsRecommended,
    ],
    languageOptions: {
      parserOptions: { projectService: true, tsconfigRootDir: import.meta.dirname },
    },
    processor: angular.processInlineTemplates,
    rules: {
      '@angular-eslint/directive-selector': [
        'error',
        { type: 'attribute', prefix: 'hq', style: 'camelCase' },
      ],
      '@angular-eslint/component-selector': ['error', { type: 'element', prefix: 'hq', style: 'kebab-case' }],
      // Signals and `inject()` only — a constructor parameter dependency is a v14 habit.
      '@angular-eslint/prefer-inject': 'error',
      '@angular-eslint/prefer-standalone': 'error',
      '@angular-eslint/prefer-on-push-component-change-detection': 'error',
      '@typescript-eslint/consistent-type-definitions': ['error', 'interface'],
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
    },
  },
  {
    files: ['**/*.html'],
    extends: [...angular.configs.templateRecommended, ...angular.configs.templateAccessibility],
  },
  // The product name is platform data, never a literal — except in tests and translations.
  {
    files: ['src/**/*.ts', 'src/**/*.html', 'e2e/**/*.ts'],
    ignores: ['src/**/*.spec.ts'],
    plugins: { hq: hqPlugin },
    rules: { 'hq/no-product-name-literal': 'error' },
  },
  // Every screen and feature route says which flag gates it (or opts out explicitly).
  {
    files: ['src/app/features/**/*.routes.ts', 'src/**/*.page.ts'],
    plugins: { hq: hqPlugin },
    rules: { 'hq/feature-flag-reference': 'error' },
  },
  {
    files: ['src/**/*.spec.ts'],
    rules: {
      '@typescript-eslint/no-non-null-assertion': 'off',
      '@typescript-eslint/unbound-method': 'off',
    },
  },
  // Playwright specs: typed, but without the Angular rules.
  {
    files: ['e2e/**/*.ts', 'playwright.config.ts'],
    extends: [eslint.configs.recommended, ...tseslint.configs.recommendedTypeChecked],
    languageOptions: {
      parserOptions: { projectService: true, tsconfigRootDir: import.meta.dirname },
    },
  },
  // Node scripts.
  {
    files: ['tools/**/*.mjs', 'eslint/**/*.mjs', '*.mjs'],
    extends: [eslint.configs.recommended],
    languageOptions: {
      globals: { process: 'readonly', console: 'readonly' },
    },
  },
  prettier,
);
