// Reference ESLint flat config of ostrom-web and ostrom-app (docs/CONVENTIONS.md section 13.2).
// Needs: eslint 10, typescript-eslint 8.72+, typescript 6.0.x, eslint-plugin-react-hooks 7, globals.
import js from "@eslint/js"
import reactHooks from "eslint-plugin-react-hooks"
import globals from "globals"
import tseslint from "typescript-eslint"

export default tseslint.config(
    {
        ignores: ["dist/**", "build/**", "coverage/**", ".expo/**", "android/**", "ios/**", "**/*.d.ts"],
    },
    js.configs.recommended,
    ...tseslint.configs.strictTypeChecked,
    ...tseslint.configs.stylisticTypeChecked,
    reactHooks.configs.flat.recommended,
    {
        languageOptions: {
            globals: { ...globals.browser },
            parserOptions: {
                projectService: true,
                tsconfigRootDir: import.meta.dirname,
            },
        },
        // As in prod-app: no eslint-disable comment can silence a rule. An exception goes here, with its reason.
        linterOptions: {
            noInlineConfig: true,
            reportUnusedDisableDirectives: "error",
        },
        rules: {
            "@typescript-eslint/no-explicit-any": "error",
            "@typescript-eslint/no-non-null-assertion": "error",
            "@typescript-eslint/ban-ts-comment": ["error", {
                "ts-ignore": true,
                "ts-expect-error": true,
                "ts-nocheck": true,
                "ts-check": false,
            }],
            // Only `as const`: data from outside is parsed with Zod, never asserted.
            "@typescript-eslint/consistent-type-assertions": ["error", { assertionStyle: "never" }],
            "@typescript-eslint/consistent-type-imports": "error",
            "@typescript-eslint/consistent-type-definitions": ["error", "interface"],
            "@typescript-eslint/explicit-function-return-type": ["error", { allowExpressions: true }],
            "@typescript-eslint/switch-exhaustiveness-check": "error",
            "@typescript-eslint/no-floating-promises": "error",
            "@typescript-eslint/strict-boolean-expressions": "error",
            "eqeqeq": ["error", "always"],
            "no-console": ["error", { allow: ["warn", "error"] }],
        },
    },
    {
        files: ["**/*.test.ts", "**/*.test.tsx"],
        languageOptions: { globals: { ...globals.node } },
    },
    {
        files: ["*.config.js", "*.config.ts", "*.config.mjs"],
        ...tseslint.configs.disableTypeChecked,
    },
)
