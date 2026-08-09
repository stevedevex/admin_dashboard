import tseslint from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';

/**
 * Architectural boundaries are enforced here rather than by convention.
 * See ARCHITECTURE.md — the two seams (`src/ui`, `src/api`) only hold if
 * nothing reaches around them.
 */

/** Vendor component libraries may only be imported inside `src/ui`. */
const VENDOR_UI = ['@radix-ui/*', '@uiw/react-codemirror', 'codemirror', '@codemirror/*', 'lucide-react'];

/** Leaf directories may not depend on application layers. */
const APP_LAYERS = ['@/features/*', '@/app/*', '@/config/*'];

const restrict = (patterns, message) => ({
  'no-restricted-imports': ['error', { patterns: patterns.map((group) => ({ group: [group], message })) }],
});

export default tseslint.config(
  { ignores: ['dist/**', 'coverage/**', 'node_modules/**'] },

  ...tseslint.configs.recommended,
  reactHooks.configs.flat.recommended,

  {
    files: ['src/**/*.{ts,tsx}'],
    rules: {
      '@typescript-eslint/consistent-type-imports': ['error', { prefer: 'type-imports' }],
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_', ignoreRestSiblings: true },
      ],
      'no-restricted-syntax': [
        'error',
        {
          selector: "CallExpression[callee.name='fetch']",
          message: 'Network access belongs in src/api only. Import from "@/api" instead.',
        },
      ],
    },
  },

  {
    // The transport is where network access is supposed to live — the ban exists to keep it from
    // spreading anywhere else, not to forbid it at its one legitimate home. Scoped to the single
    // directory so an accidental `fetch` in a feature, a hook, or even elsewhere under `src/api`
    // still fails the build.
    files: ['src/api/http/**/*.ts'],
    rules: { 'no-restricted-syntax': 'off' },
  },

  {
    // Only the design-system seam may touch a component library.
    files: ['src/**/*.{ts,tsx}'],
    ignores: ['src/ui/**'],
    rules: restrict(VENDOR_UI, 'Import components from "@/ui", not from a vendor package.'),
  },

  {
    // `ui` and `lib` are leaves: they know nothing about the application.
    files: ['src/ui/**/*.{ts,tsx}', 'src/lib/**/*.{ts,tsx}'],
    rules: restrict([...APP_LAYERS, '@/api/*'], 'Leaf modules must not depend on application layers.'),
  },

  {
    // Only the data seam performs I/O.
    files: ['src/api/**/*.ts'],
    rules: restrict([...APP_LAYERS, '@/ui/*'], 'The data layer must not depend on UI or features.'),
  },

  {
    // Features are vertical slices — they must not reach into each other.
    files: ['src/features/*/**/*.{ts,tsx}'],
    rules: restrict(
      ['@/features/*/**'],
      'Features must not import from other features. Promote shared code instead.',
    ),
  },
);
