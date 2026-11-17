/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{vue,js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        // 定义“医疗白”风格色板 - 修复版，补全了所有色阶
        'medical': {
          50: 'rgb(var(--medical-50) / <alpha-value>)',
          100: 'rgb(var(--medical-100) / <alpha-value>)',
          200: 'rgb(var(--medical-200) / <alpha-value>)',
          300: 'rgb(var(--medical-300) / <alpha-value>)',
          400: 'rgb(var(--medical-400) / <alpha-value>)',
          500: 'rgb(var(--medical-500) / <alpha-value>)',
          600: 'rgb(var(--medical-600) / <alpha-value>)',
          700: 'rgb(var(--medical-700) / <alpha-value>)',
          800: 'rgb(var(--medical-800) / <alpha-value>)',
          900: 'rgb(var(--medical-900) / <alpha-value>)',
        },
        'accent': {
          DEFAULT: 'rgb(var(--accent) / <alpha-value>)',
          hover: 'rgb(var(--accent-hover) / <alpha-value>)',
          text: 'rgb(var(--accent-text) / <alpha-value>)',
          fg: 'rgb(var(--accent-fg) / <alpha-value>)',
          'on-strong': 'rgb(var(--accent-on-strong) / <alpha-value>)',
        },
        'surface': {
          1: 'rgb(var(--surface-1) / <alpha-value>)',
          2: 'rgb(var(--surface-2) / <alpha-value>)',
          strong: 'rgb(var(--surface-strong) / <alpha-value>)',
          contrast: 'rgb(var(--scrim) / <alpha-value>)',
        },
        'scrim': 'rgb(var(--scrim) / <alpha-value>)',
        'ink': {
          DEFAULT: 'rgb(var(--ink) / <alpha-value>)',
          fg: 'rgb(var(--ink-fg) / <alpha-value>)',
        },
        'danger': {
          DEFAULT: 'rgb(var(--danger) / <alpha-value>)',
          fg: 'rgb(var(--danger-fg) / <alpha-value>)',
          bg: 'rgb(var(--danger-bg) / <alpha-value>)',
          border: 'rgb(var(--danger-border) / <alpha-value>)',
        },
        'success': {
          DEFAULT: 'rgb(var(--success) / <alpha-value>)',
          bg: 'rgb(var(--success-bg) / <alpha-value>)',
          border: 'rgb(var(--success-border) / <alpha-value>)',
        },
        'warn': {
          DEFAULT: 'rgb(var(--warn) / <alpha-value>)',
          bg: 'rgb(var(--warn-bg) / <alpha-value>)',
          border: 'rgb(var(--warn-border) / <alpha-value>)',
        }
      },
      fontFamily: {
        mono: ['"JetBrains Mono"', 'ui-monospace', 'SFMono-Regular', 'Menlo', 'Monaco', 'Consolas', '"Liberation Mono"', '"Courier New"', 'monospace', '"PingFang SC"', '"Microsoft YaHei"'],
        sans: [
          '"Noto Sans SC"',
          '"PingFang SC"',
          '"Hiragino Sans GB"',
          '"Microsoft YaHei"',
          '"微软雅黑"',
          'system-ui',
          '-apple-system',
          'BlinkMacSystemFont',
          '"Segoe UI"',
          'Roboto',
          '"Helvetica Neue"',
          'Arial',
          'sans-serif'
        ],
      },
      transitionTimingFunction: {
        soft: 'cubic-bezier(.22, 1, .36, 1)',
      },
    },
  },
  plugins: [],
}
