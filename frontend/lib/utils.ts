export function escapeHtml(str: string | null | undefined): string {
  if (str == null) return ''
  return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

export function formatTime(iso: string): string {
  try {
    return new Date(iso).toLocaleString([], { dateStyle: 'short', timeStyle: 'short' })
  } catch {
    return iso
  }
}

export function extToLang(name: string): string {
  const ext = (name.split('.').pop() ?? '').toLowerCase()
  const map: Record<string, string> = {
    js: 'javascript', ts: 'javascript',
    py: 'python',
    cpp: 'cpp', cc: 'cpp', cxx: 'cpp',
    c: 'c',
    java: 'java',
  }
  return map[ext] ?? 'javascript'
}

export function defaultFileName(language: string): string {
  const map: Record<string, string> = {
    javascript: 'main.js', python: 'main.py',
    cpp: 'main.cpp', c: 'main.c', java: 'Main.java',
  }
  return map[language] ?? 'main.txt'
}

export const STARTER_CODE: Record<string, string> = {
  javascript: '// Welcome to CollabIDE!\n// Start coding collaboratively\n\nconsole.log("Hello, World!");',
  python: '# Welcome to CollabIDE!\n# Start coding collaboratively\n\nprint("Hello, World!")',
  cpp: '// Welcome to CollabIDE!\n\n#include <iostream>\nusing namespace std;\n\nint main() {\n    cout << "Hello, World!" << endl;\n    return 0;\n}',
  c: '// Welcome to CollabIDE!\n\n#include <stdio.h>\n\nint main() {\n    printf("Hello, World!\\n");\n    return 0;\n}',
  java: '// Welcome to CollabIDE!\n\npublic class Main {\n    public static void main(String[] args) {\n        System.out.println("Hello, World!");\n    }\n}',
}

export const USER_COLORS = ['#7C3AED', '#0EA5E9', '#10B981', '#F59E0B', '#EF4444', '#EC4899', '#8B5CF6', '#06B6D4']
