import pluginVue from 'eslint-plugin-vue'
import vueParser from 'vue-eslint-parser'
import tsParser from '@typescript-eslint/parser'

/**
 * WBS-2.4.9 H9 ESLint flat config（ESLint 9 稳定线）：
 * - .vue 文件：vue-eslint-parser + @typescript-eslint/parser 解析 TS，vue 规则集；
 * - .ts 文件：@typescript-eslint/parser 做语法级解析（骨架期有意未启用 eslint:recommended /
 *   typescript-eslint 规则集，TS 质量由 vue-tsc build 类型检查把关；后续可按需纳入推荐规则集）；
 * - `<script setup lang="ts">` 的 TS 解析依赖 @typescript-eslint/parser（Vue+TS 工程标准配套）。
 */
export default [
  {
    ignores: ['node_modules/**', 'dist/**'],
  },
  {
    files: ['src/**/*.vue'],
    languageOptions: {
      parser: vueParser,
      parserOptions: {
        ecmaVersion: 'latest',
        sourceType: 'module',
        parser: tsParser,
      },
    },
    plugins: {
      vue: pluginVue,
    },
    rules: {
      ...pluginVue.configs['flat/essential'].rules,
      'vue/multi-word-component-names': 'off',
    },
  },
  {
    files: ['src/**/*.ts', 'e2e/**/*.ts'],
    languageOptions: {
      parser: tsParser,
      parserOptions: {
        ecmaVersion: 'latest',
        sourceType: 'module',
      },
    },
    rules: {
      // TS 项目关闭 no-undef（DOM/ES 全局由 TS 编译器与 lib 声明负责，@typescript-eslint 官方建议）
      'no-undef': 'off',
      'no-unused-vars': 'off',
    },
  },
]
