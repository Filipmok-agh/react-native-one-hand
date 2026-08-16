const js = require('@eslint/js');
const tseslint = require('typescript-eslint');
const reactHooks = require('eslint-plugin-react-hooks');
const prettier = require('eslint-config-prettier/flat');

module.exports = tseslint.config(
  js.configs.recommended,
  tseslint.configs.recommended,
  {
    plugins: { 'react-hooks': reactHooks },
    rules: {
      // The reason DockedInsets is split into two components: the outer one returns early
      // when the optional peer dependency is missing, so it must not call hooks. This rule
      // is what keeps that structure from silently regressing.
      ...reactHooks.configs.recommended.rules,
      // `require` of an optional peer dependency inside try/catch is the documented way to
      // make Metro treat it as optional — see DockedInsets.
      '@typescript-eslint/no-require-imports': 'off',
      // Leading underscore = deliberately unused, the convention already used in this
      // codebase (e.g. `catch (_: Throwable)` on the Kotlin side).
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
    },
  },
  {
    files: ['src/__tests__/**', 'src/__mocks__/**'],
    languageOptions: {
      globals: {
        jest: 'readonly',
        describe: 'readonly',
        it: 'readonly',
        expect: 'readonly',
        beforeEach: 'readonly',
        afterEach: 'readonly',
      },
    },
  },
  // Formatting is Prettier's job.
  prettier,
);
