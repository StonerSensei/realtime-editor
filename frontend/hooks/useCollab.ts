'use client'
import { useEffect, useRef, useState, useCallback } from 'react'
import * as Y from 'yjs'
import * as awarenessProtocol from 'y-protocols/awareness'
import { getWsBaseUrl } from '@/lib/constants'
import { API } from '@/lib/api'
import { defaultFileName, extToLang, STARTER_CODE, USER_COLORS } from '@/lib/utils'

const TYPE_SYNC_REQUEST    = 0
const TYPE_SYNC_UPDATE     = 1
const TYPE_AWARENESS       = 2
const TYPE_AWARENESS_QUERY = 3
const TYPE_PRESENCE        = 4
const TYPE_ROSTER          = 5
const CLOSE_CODE_KICKED    = 4001
const MAX_RECONNECT        = 10
// If the project is still empty this long after connecting, seed it ourselves.
const SEED_FALLBACK_MS     = 1500

function uid()         { return 'f-' + Math.random().toString(36).slice(2, 10) }
function randomColor() { return USER_COLORS[Math.floor(Math.random() * USER_COLORS.length)] }

export interface CollabFile { id: string; name: string }
export type CollabStatus = 'connecting' | 'connected' | 'disconnected' | 'reconnecting' | 'error'
export interface Participant { name: string; color: string }

export interface UseCollabReturn {
  files: CollabFile[]
  activeFileId: string | null
  activeYText: Y.Text | null
  awareness: awarenessProtocol.Awareness | null
  participants: Participant[]
  roster: string[]
  status: CollabStatus
  reconnectAttempt: number
  openFile: (id: string) => void
  createFile: (name: string) => string | null
  renameFile: (id: string, newName: string) => boolean
  deleteFile: (id: string) => boolean
  getProjectFiles: () => Record<string, string>
  replaceProject: (files: Record<string, string>) => void
  replaceContent: (text: string) => void
  getActiveFileName: () => string | null
  getActiveLanguage: () => string | null
}

interface Options {
  roomId: string
  language: string
  token: string
  username: string
  /** false for viewers: the server drops their edits, so they must never seed. */
  canSeed: boolean
  onKicked?: () => void
}

export function useCollab({ roomId, language, token, username, canSeed, onKicked }: Options): UseCollabReturn {
  const [files,            setFiles]            = useState<CollabFile[]>([])
  const [activeFileId,     setActiveFileId]     = useState<string | null>(null)
  const [activeYText,      setActiveYText]      = useState<Y.Text | null>(null)
  const [awareness,        setAwareness]        = useState<awarenessProtocol.Awareness | null>(null)
  const [participants,     setParticipants]     = useState<Participant[]>([])
  const [roster,           setRoster]           = useState<string[]>([])
  const [status,           setStatus]           = useState<CollabStatus>('connecting')
  const [reconnectAttempt, setReconnectAttempt] = useState(0)

  const docRef          = useRef<Y.Doc | null>(null)
  const filesMapRef     = useRef<Y.Map<Y.Map<unknown>> | null>(null)
  const wsRef           = useRef<WebSocket | null>(null)
  const reconnectTimer  = useRef<ReturnType<typeof setTimeout> | null>(null)
  const activeFileIdRef = useRef<string | null>(null)

  // Latest option values, readable from inside long-lived closures
  const roomIdRef  = useRef(roomId)
  const tokenRef   = useRef(token)
  const langRef    = useRef(language)
  const canSeedRef = useRef(canSeed)
  const kickedRef  = useRef(onKicked)
  useEffect(() => { roomIdRef.current  = roomId },   [roomId])
  useEffect(() => { tokenRef.current   = token },    [token])
  useEffect(() => { langRef.current    = language }, [language])
  useEffect(() => { canSeedRef.current = canSeed },  [canSeed])
  useEffect(() => { kickedRef.current  = onKicked }, [onKicked])

  // ── File helpers ───────────────────────────────────────────────────────
  const getFiles = useCallback((): CollabFile[] => {
    const fm = filesMapRef.current; if (!fm) return []
    return Array.from(fm.keys())
      .map(id => ({ id, name: fm.get(id)!.get('name') as string }))
      .sort((a, b) => a.name.localeCompare(b.name))
  }, [])

  const firstFileId = useCallback(() => {
    const fs = getFiles(); return fs.length ? fs[0].id : null
  }, [getFiles])

  /** Creates a file. Pass a fixed id when seeding so concurrent seeds merge. */
  const createFileInternal = useCallback((name: string, content?: string, fixedId?: string): string => {
    const doc = docRef.current!; const fm = filesMapRef.current!
    const id = fixedId ?? uid()
    doc.transact(() => {
      const m = new Y.Map(); fm.set(id, m as Y.Map<unknown>)
      m.set('name', name)
      const t = new Y.Text(); m.set('content', t)
      if (content) t.insert(0, content)
    })
    return id
  }, [])

  const openFile = useCallback((id: string) => {
    const fm = filesMapRef.current; if (!fm) return
    const fileMap = fm.get(id); if (!fileMap) return
    activeFileIdRef.current = id
    setActiveFileId(id)
    setActiveYText(fileMap.get('content') as Y.Text)
  }, [])

  const createFile = useCallback((name: string) => {
    name = name.trim(); if (!name) return null
    if (getFiles().some(f => f.name.toLowerCase() === name.toLowerCase())) return null
    const id = createFileInternal(name, ''); openFile(id); return id
  }, [getFiles, createFileInternal, openFile])

  const renameFile = useCallback((id: string, newName: string) => {
    newName = newName.trim()
    const fm = filesMapRef.current; if (!fm || !newName) return false
    const m = fm.get(id); if (!m) return false
    if (getFiles().some(f => f.id !== id && f.name.toLowerCase() === newName.toLowerCase())) return false
    m.set('name', newName); return true
  }, [getFiles])

  const deleteFile = useCallback((id: string) => {
    const fm = filesMapRef.current; if (!fm || fm.size <= 1) return false
    const wasActive = id === activeFileIdRef.current
    fm.delete(id)
    if (wasActive) { const f = firstFileId(); if (f) openFile(f) }
    return true
  }, [firstFileId, openFile])

  const getProjectFiles = useCallback(() => {
    const fm = filesMapRef.current; if (!fm) return {}
    const out: Record<string, string> = {}
    fm.forEach(m => { out[m.get('name') as string] = (m.get('content') as Y.Text).toString() })
    return out
  }, [])

  const replaceProject = useCallback((filesObj: Record<string, string>) => {
    const doc = docRef.current; const fm = filesMapRef.current; if (!doc || !fm) return
    doc.transact(() => { Array.from(fm.keys()).forEach(k => fm.delete(k)) })
    Object.entries(filesObj ?? {}).forEach(([n, c]) => createFileInternal(n, c))
    const f = firstFileId(); if (f) openFile(f)
  }, [createFileInternal, firstFileId, openFile])

  const replaceContent = useCallback((text: string) => {
    const doc = docRef.current; const fm = filesMapRef.current; const id = activeFileIdRef.current
    if (!doc || !fm || !id) return
    const m = fm.get(id); if (!m) return
    const yt = m.get('content') as Y.Text
    doc.transact(() => { yt.delete(0, yt.length); if (text) yt.insert(0, text) })
  }, [])

  const getActiveFileName = useCallback(() => {
    const fm = filesMapRef.current; const id = activeFileIdRef.current
    if (!fm || !id) return null
    return (fm.get(id)?.get('name') as string) ?? null
  }, [])

  const getActiveLanguage = useCallback(() => {
    const n = getActiveFileName(); return n ? extToLang(n) : null
  }, [getActiveFileName])

  // ── Main lifecycle ─────────────────────────────────────────────────────
  useEffect(() => {
    // Everything below belongs to THIS mount only. If the component is
    // unmounted (or Strict Mode throws this mount away), `disposed` stops
    // this mount's socket from reconnecting or touching shared state.
    let disposed = false
    let seeding = false
    let reconnectCount = 0
    let seedFallbackTimer: ReturnType<typeof setTimeout> | null = null

    const doc      = new Y.Doc()
    const filesMap = doc.getMap<Y.Map<unknown>>('files')
    const aw       = new awarenessProtocol.Awareness(doc)
    aw.setLocalStateField('user', { name: username, color: randomColor() })

    docRef.current      = doc
    filesMapRef.current = filesMap
    setAwareness(aw)

    let ws: WebSocket | null = null

    function frame(type: number, payload?: Uint8Array): Uint8Array {
      const body = payload ?? new Uint8Array(0)
      const msg  = new Uint8Array(1 + body.length)
      msg[0] = type; msg.set(body, 1); return msg
    }
    function send(type: number, payload?: Uint8Array) {
      if (ws?.readyState === WebSocket.OPEN) ws.send(frame(type, payload))
    }

    /**
     * Creates the project files if the shared document is empty.
     * Seeded files use fixed ids ("seed:main.js") so that if two editors
     * seed at the same moment, Yjs merges them into one file.
     */
    async function seedIfEmpty() {
      if (disposed || seeding || !canSeedRef.current || filesMap.size > 0) return
      seeding = true

      let snap: { files?: Record<string, string>; code?: string } | null = null
      try { snap = await API.get(`/api/snapshots/latest/${roomIdRef.current}`) } catch { /* none yet */ }

      // Peers may have synced their files to us while we were fetching.
      if (disposed || filesMap.size > 0) { seeding = false; return }

      const lang = langRef.current
      if (snap?.files && Object.keys(snap.files).length) {
        Object.entries(snap.files).forEach(([n, c]) => createFileInternal(n, c, `seed:${n}`))
      } else {
        const name = defaultFileName(lang)
        const content = snap?.code?.trim() ? snap.code : (STARTER_CODE[lang] ?? '')
        createFileInternal(name, content, `seed:${name}`)
      }
      seeding = false
      const f = firstFileId(); if (f) openFile(f)
    }

    function connect() {
      if (disposed) return
      if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return

      const socket = new WebSocket(
        `${getWsBaseUrl()}/yjs/${roomIdRef.current}?token=${encodeURIComponent(tokenRef.current)}`)
      socket.binaryType = 'arraybuffer'
      ws = socket
      wsRef.current = socket

      socket.onopen = () => {
        if (disposed) { socket.close(1000); return }
        reconnectCount = 0; setReconnectAttempt(0); setStatus('connected')
        send(TYPE_SYNC_REQUEST,    Y.encodeStateVector(doc))
        send(TYPE_SYNC_UPDATE,     Y.encodeStateAsUpdate(doc))
        send(TYPE_AWARENESS_QUERY)
        send(TYPE_AWARENESS, awarenessProtocol.encodeAwarenessUpdate(aw, [doc.clientID]))

        // Fallback: if nobody has given us any files by now, the "first peer"
        // signal was lost (e.g. it went to a socket that was thrown away).
        if (seedFallbackTimer) clearTimeout(seedFallbackTimer)
        seedFallbackTimer = setTimeout(seedIfEmpty, SEED_FALLBACK_MS)
      }

      socket.onmessage = (e: MessageEvent) => {
        if (disposed) return
        try {
          const data = new Uint8Array(e.data as ArrayBuffer)
          const payload = data.subarray(1)
          switch (data[0]) {
            case TYPE_SYNC_REQUEST:
              send(TYPE_SYNC_UPDATE, Y.encodeStateAsUpdate(doc, payload)); break
            case TYPE_SYNC_UPDATE:
              Y.applyUpdate(doc, payload, 'remote'); break
            case TYPE_AWARENESS:
              awarenessProtocol.applyAwarenessUpdate(aw, payload, 'remote'); break
            case TYPE_AWARENESS_QUERY:
              send(TYPE_AWARENESS, awarenessProtocol.encodeAwarenessUpdate(aw, Array.from(aw.getStates().keys()))); break
            case TYPE_PRESENCE:
              // Server says we're first: seed right away instead of waiting.
              if (payload[0] === 1) seedIfEmpty()
              break
            case TYPE_ROSTER:
              try { setRoster(JSON.parse(new TextDecoder().decode(payload))) } catch { /* ignore */ }
              break
          }
        } catch (err) { console.error('Collab msg error:', err) }
      }

      socket.onerror = () => { if (!disposed) setStatus('error') }

      socket.onclose = (e: CloseEvent) => {
        if (disposed) return
        if (e.code === CLOSE_CODE_KICKED) { disposed = true; kickedRef.current?.(); return }
        setStatus('disconnected')
        if (reconnectCount < MAX_RECONNECT) {
          const n = reconnectCount++
          setReconnectAttempt(n + 1); setStatus('reconnecting')
          reconnectTimer.current = setTimeout(connect, Math.min(1000 * 2 ** n, 30000))
        }
      }
    }

    function handleDocUpdate(update: Uint8Array, origin: unknown) {
      if (origin !== 'remote') send(TYPE_SYNC_UPDATE, update)
    }
    doc.on('update', handleDocUpdate)

    function handleAwareness({ added, updated, removed }: { added: number[]; updated: number[]; removed: number[] }) {
      send(TYPE_AWARENESS, awarenessProtocol.encodeAwarenessUpdate(aw, added.concat(updated, removed)))
      setParticipants(
        Array.from(aw.getStates().values())
          .map(s => (s as { user?: Participant }).user).filter(Boolean) as Participant[])
    }
    aw.on('update', handleAwareness)

    filesMap.observeDeep(events => {
      const structural = events.some(e =>
        e.target === filesMap || (e as Y.YMapEvent<unknown>).keysChanged?.has('name'))
      if (!structural) return
      setFiles(
        Array.from(filesMap.keys())
          .map(id => ({ id, name: filesMap.get(id)!.get('name') as string }))
          .sort((a, b) => a.name.localeCompare(b.name)))
      const cur = activeFileIdRef.current
      if (!cur || !filesMap.has(cur)) {
        const first = Array.from(filesMap.keys())
          .sort((a, b) => (filesMap.get(a)!.get('name') as string)
            .localeCompare(filesMap.get(b)!.get('name') as string))[0]
        if (first) openFile(first)
      }
    })

    // Tell peers we're leaving the moment the tab closes or refreshes.
    function handleUnload() {
      disposed = true
      awarenessProtocol.removeAwarenessStates(aw, [doc.clientID], 'unload')
      ws?.close(1000)
    }
    window.addEventListener('beforeunload', handleUnload)

    connect()

    return () => {
      window.removeEventListener('beforeunload', handleUnload)
      disposed = true
      if (seedFallbackTimer) clearTimeout(seedFallbackTimer)
      if (reconnectTimer.current) clearTimeout(reconnectTimer.current)
      awarenessProtocol.removeAwarenessStates(aw, [doc.clientID], 'exit')
      doc.off('update', handleDocUpdate)
      aw.off('update', handleAwareness)
      ws?.close(1000)
      doc.destroy()
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return {
    files, activeFileId, activeYText, awareness, participants, roster, status, reconnectAttempt,
    openFile, createFile, renameFile, deleteFile,
    getProjectFiles, replaceProject, replaceContent, getActiveFileName, getActiveLanguage,
  }
}