import type { Config } from 'tailwindcss'

const config: Config = {
  content: [
    './pages/**/*.{js,ts,jsx,tsx,mdx}',
    './components/**/*.{js,ts,jsx,tsx,mdx}',
    './app/**/*.{js,ts,jsx,tsx,mdx}',
  ],
  theme: {
    extend: {
      colors: {
        bg:       'var(--bg)',
        surface:  'var(--surface)',
        elevated: 'var(--elevated)',
        primary:  'var(--primary)',
        violet:   'var(--violet)',
        success:  'var(--success)',
        danger:   'var(--danger)',
        warning:  'var(--warning)',
        muted:    'var(--muted)',
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
        mono: ['JetBrains Mono', 'Fira Code', 'monospace'],
      },
    },
  },
  plugins: [],
}

export default config
