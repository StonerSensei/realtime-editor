'use client'
/**
 * CodeEditor — CodeMirror 6 editor with Yjs CRDT binding.
 *
 * Must be loaded with dynamic import + ssr:false because CodeMirror needs the DOM.
 * When activeYText changes (file switch), a Compartment swaps out the yCollab extension
 * so the editor stays mounted and bound to the new file's Y.Text.
 */

import { useEffect, useRef } from 'react'
import * as Y from 'yjs'
import type { Awareness } from 'y-protocols/awareness'
import { yCollab } from 'y-codemirror.next'

import { EditorView, keymap, lineNumbers, highlightActiveLineGutter,
  highlightSpecialChars, drawSelection, highlightActiveLine, dropCursor,
  rectangularSelection, crosshairCursor } from '@codemirror/view'
import { EditorState, Compartment, Transaction } from '@codemirror/state'
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands'
import { foldGutter, indentOnInput, syntaxHighlighting, defaultHighlightStyle,
  bracketMatching, foldKeymap } from '@codemirror/language'
import { oneDark } from '@codemirror/theme-one-dark'
import { javascript } from '@codemirror/lang-javascript'
import { python } from '@codemirror/lang-python'
import { cpp } from '@codemirror/lang-cpp'
import { java } from '@codemirror/lang-java'

function langExtension(lang: string) {
  switch (lang) {
    case 'javascript': return javascript()
    case 'python':     return python()
    case 'cpp':
    case 'c':          return cpp()
    case 'java':       return java()
    default:           return javascript()
  }
}

interface Props {
  activeYText: Y.Text | null
  awareness: Awareness | null
  language: string
  readOnly: boolean
  onReady?: (view: EditorView) => void
}

export default function CodeEditor({ activeYText, awareness, language, readOnly, onReady }: Props) {
  const containerRef = useRef<HTMLDivElement>(null)
  const viewRef      = useRef<EditorView | null>(null)
  const yjsCompartment = useRef(new Compartment())
  const roCompartment  = useRef(new Compartment())
  const langCompartment = useRef(new Compartment())

  // ── Create editor once ────────────────────────────────────────────────────
  useEffect(() => {
    if (!containerRef.current) return

    const view = new EditorView({
      state: EditorState.create({
        extensions: [
          lineNumbers(),
          highlightActiveLineGutter(),
          highlightSpecialChars(),
          history(),
          foldGutter(),
          drawSelection(),
          dropCursor(),
          EditorState.allowMultipleSelections.of(true),
          indentOnInput(),
          syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
          bracketMatching(),
          rectangularSelection(),
          crosshairCursor(),
          highlightActiveLine(),
          keymap.of([...defaultKeymap, ...historyKeymap, ...foldKeymap]),
          oneDark,
          langCompartment.current.of(langExtension(language)),
          roCompartment.current.of(EditorState.readOnly.of(readOnly)),
          yjsCompartment.current.of([]),   // Yjs not bound until activeYText arrives
          EditorView.theme({
            '&': { height: '100%', fontSize: '14px', fontFamily: "'JetBrains Mono', 'Fira Code', monospace" },
            '.cm-scroller': { overflow: 'auto' },
          }),
        ],
      }),
      parent: containerRef.current,
    })

    viewRef.current = view
    onReady?.(view)

    return () => {
      view.destroy()
      viewRef.current = null
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []) // Init once — changes handled below via compartments

  // ── Swap Yjs binding when activeYText changes ─────────────────────────────
  // ── Swap Yjs binding when activeYText changes ─────────────────────────────
  useEffect(() => {
    const view = viewRef.current
    if (!view) return

    // 1. Detach the old binding so the next step isn't treated as an edit.
    view.dispatch({ effects: yjsCompartment.current.reconfigure([]) })

    if (!activeYText || !awareness) return

    // 2. Load the file's current content. yCollab only syncs future changes,
    //    so without this the editor stays empty after a refresh or file switch.
    view.dispatch({
      changes: { from: 0, to: view.state.doc.length, insert: activeYText.toString() },
      annotations: Transaction.addToHistory.of(false),
    })

    // 3. Attach the binding to the new file.
    view.dispatch({
      effects: yjsCompartment.current.reconfigure(yCollab(activeYText, awareness)),
    })
  }, [activeYText, awareness])

  
  // ── Language ──────────────────────────────────────────────────────────────
  useEffect(() => {
    const view = viewRef.current
    if (!view) return
    view.dispatch({ effects: langCompartment.current.reconfigure(langExtension(language)) })
  }, [language])

  // ── Read-only ─────────────────────────────────────────────────────────────
  useEffect(() => {
    const view = viewRef.current
    if (!view) return
    view.dispatch({ effects: roCompartment.current.reconfigure(EditorState.readOnly.of(readOnly)) })
  }, [readOnly])

  return (
    <div
      ref={containerRef}
      className="h-full w-full overflow-hidden"
      style={{ background: '#282a36' }}
    />
  )
}
