import pluginVue from 'eslint-plugin-vue'
import vueParser from 'vue-eslint-parser'
import tsParser from '@typescript-eslint/parser'

/**
 * WBS-2.4.9 H9 ESLint flat config（ESLint 9 稳定线）：
 * - 仅检查 src 下 .vue 文件；
 * - `<script setup lang="ts">` 需 @typescript-eslint/parser 解析 TS（Vue+TS 工程标准配套，
 *   eslint-plugin-vue 的 peer optional 依赖；TS 类型检查仍由 vue-tsc build 阶段负责）。
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
]
