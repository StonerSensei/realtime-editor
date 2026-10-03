'use client'
import { useEffect, useRef, useState, useCallback } from 'react'
import dynamic from 'next/dynamic'
import { useRouter, useParams, useSearchParams } from 'next/navigation'
import { Auth } from '@/lib/auth'
import { API } from '@/lib/api'
import { getWsBaseUrl } from '@/lib/constants'
import { toast } from '@/components/ui/Toast'
import { useCollab } from '@/hooks/useCollab'
import ChatPanel from '@/components/panels/ChatPanel'
import ParticipantsPanel from '@/components/panels/ParticipantsPanel'
import HistoryPanel from '@/components/panels/HistoryPanel'
import FilesPanel from '@/components/panels/FilesPanel'
import type { EditorView } from '@codemirror/view'

const CodeEditor = dynamic(() => import('@/components/CodeEditor'), { ssr: false })

type Drawer = 'files' | 'chat' | 'participants' | 'history' | null

export default function EditorPage() {
  const router = useRouter()
  const params = useParams()
  const searchParams = useSearchParams()
  const roomId   = params.roomId as string
  const language = searchParams.get('lang') ?? 'javascript'

  const [ready,    setReady]    = useState(false)
  const [token,    setToken]    = useState('')
  const [username, setUsername] = useState('Guest')

  useEffect(() => {
    if (!Auth.isAuthenticated()) { router.replace('/login'); return }
    setToken(Auth.getToken() ?? '')
    setUsername(Auth.getUsername() ?? 'Guest')
    setReady(true)
  }, [router])

  if (!ready) return (
    <div style={{ height: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'var(--bg)' }}>
      <span style={{ color: 'var(--muted)' }}>Connecting…</span>
    </div>
  )

  return <EditorContent roomId={roomId} language={language} token={token} username={username} />
}

function EditorContent({ roomId, language, token, username }: { roomId: string; language: string; token: string; username: string }) {
  const router = useRouter()

  const [role,         setRole]        = useState('EDITOR')
  const [owner,        setOwner]       = useState('')
  const [roomReady,    setRoomReady]   = useState(false)
  const [drawer,       setDrawer]      = useState<Drawer>(null)
  const [output,       setOutput]      = useState('Ready to execute code...')
  const [stdin,        setStdin]       = useState('')
  const [joinCode,     setJoinCode]    = useState('')
  const [showJoinCode, setShowJoinCode] = useState(false)

  const editorViewRef = useRef<EditorView | null>(null)
  const lastSavedRef  = useRef('')

  const roleRef = useRef(role)
  useEffect(() => { roleRef.current = role }, [role])

    const collab = useCollab({
    roomId, language, token, username,
    canSeed: role !== 'VIEWER',
    onKicked: () => {
      toast('You were removed from the room by the owner', 'error')
      setTimeout(() => router.push('/'), 1800)
    },
  })
  useEffect(() => {
    API.post<{ role?: string; owner?: string }>(`/api/rooms/${roomId}/join`, {})
      .then(data => {
        const myRole = data?.role ?? 'EDITOR'
        setRole(myRole); setOwner(data?.owner ?? '')
        if (myRole === 'OWNER') {
          API.get<{ joinCode?: string }>(`/api/rooms/${roomId}`)
            .then(r => { if (r?.joinCode) { setJoinCode(r.joinCode); setShowJoinCode(true) } })
            .catch(() => {})
        }
      })
            .catch(err => {
        toast((err as Error).message, 'error')
        setTimeout(() => router.push('/'), 1500)
      })
      .finally(() => setRoomReady(true))
  }, [roomId])

    // Poll our role so promotions/demotions by the owner apply live.
  useEffect(() => {
    const id = setInterval(async () => {
      try {
        const d = await API.get<{ role?: string }>(`/api/rooms/${roomId}`)
        const newRole = d?.role
        if (!newRole || newRole === roleRef.current) return

        toast(`Your role is now ${newRole}`, 'info')
        setRole(newRole)

        // When demoted to viewer, any text typed in the last few seconds was
        // dropped by the server, so this tab's copy may differ from everyone
        // else's. Reloading pulls a clean copy from the editors.
        if (newRole === 'VIEWER') {
          setTimeout(() => window.location.reload(), 1200)
        }
      } catch { /* ignore transient failures */ }
    }, 5000)
    return () => clearInterval(id)
  }, [roomId])

useEffect(() => {
  const id = setInterval(() => {
    if (role === 'VIEWER') return
    const files = collab.getProjectFiles()
    if (!Object.keys(files).length) return
    const s = JSON.stringify(files)
    if (s === lastSavedRef.current) return
    const lang = collab.getActiveLanguage() ?? language
    if (!lang) return  // never send null language
    API.post('/api/snapshots/save', { roomId, files, language: lang })
      .then(() => { lastSavedRef.current = s })
      .catch(() => { /* auto-save failure is silent */ })
  }, 10_000)
  return () => clearInterval(id)
// eslint-disable-next-line react-hooks/exhaustive-deps
}, [roomId, language, role])

  function runCode() {
    const view = editorViewRef.current; if (!view) return
    const code    = view.state.doc.toString()
    const runLang = collab.getActiveLanguage() ?? language
    if (!code.trim()) { toast('No code to execute', 'warning'); return }
    setOutput(`Executing ${collab.getActiveFileName() ?? 'code'}...\n`)
    toast('Executing code...', 'info')
    const ws = new WebSocket(`${getWsBaseUrl()}/ws/exec?token=${encodeURIComponent(token)}`)
    ws.onopen    = () => ws.send(JSON.stringify({ language: runLang, code, input: stdin }))
    ws.onmessage = e => setOutput(prev => prev + e.data + '\n')
    ws.onerror   = () => { setOutput(prev => prev + 'Execution error\n'); toast('Execution error', 'error') }
    ws.onclose   = () => { setOutput(prev => prev + '\n--- Finished ---\n'); toast('Execution completed', 'success') }
  }

  function downloadFile() {
    const view = editorViewRef.current; if (!view) return
    const name = collab.getActiveFileName() ?? `main.${language}`
    const a = document.createElement('a')
    a.href = URL.createObjectURL(new Blob([view.state.doc.toString()], { type: 'text/plain' }))
    a.download = name; a.click()
    toast(`Downloaded: ${name}`, 'success')
  }

  const toggleDrawer = useCallback((w: Drawer) => setDrawer(p => p === w ? null : w), [])

  const statusColor = {
    connected: 'var(--success)', disconnected: 'var(--error)',
    reconnecting: 'var(--warning)', error: 'var(--error)', connecting: 'var(--muted)',
  }[collab.status]

  const onlineCount = collab.roster.length
    ? new Set(collab.roster).size
    : Math.max(new Set(collab.participants.map(p => p.name)).size, 1)

  const drawerTitles = { files: 'Files', chat: 'Chat', participants: 'Participants', history: 'Version History' }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh', overflow: 'hidden', background: 'var(--bg)' }}>

      {/* ── Header ── */}
      <div style={{
        height: 48, background: 'var(--secondary)', borderBottom: '1px solid var(--border)',
        display: 'flex', alignItems: 'center', padding: '0 16px', gap: 12, flexShrink: 0,
      }}>
        <div style={{ color: 'var(--accent)', fontWeight: 700, fontSize: '1.05rem', display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer' }}
          onClick={() => router.push('/')}>
          <i className="fas fa-code" /> CollabIDE
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flex: 1, flexWrap: 'nowrap', overflow: 'hidden' }}>
          <span className="room-badge">
            <i className="fas fa-door-open" />{roomId}
          </span>
          <span className="room-badge">
            <i className="fas fa-user-tag" />{role}
          </span>
          <span className="room-badge">
            <span style={{ width: 7, height: 7, borderRadius: '50%', background: statusColor, display: 'inline-block' }} />
            {collab.status === 'reconnecting' ? `Reconnecting (${collab.reconnectAttempt})…` : collab.status}
          </span>
          <span className="room-badge">
            <i className="fas fa-users" />{onlineCount} online
          </span>
          {showJoinCode && (
            <span className="room-badge" style={{ color: 'var(--accent)' }}>
              <i className="fas fa-key" />
              <span style={{ fontFamily: 'monospace', letterSpacing: 3 }}>{joinCode}</span>
              <i className="fas fa-copy" style={{ cursor: 'pointer', marginLeft: 4 }}
                onClick={() => { navigator.clipboard.writeText(joinCode); toast('Copied!', 'success') }} />
            </span>
          )}
        </div>

        {role === 'VIEWER' && (
          <span style={{ fontSize: '0.78rem', color: 'var(--warning)', background: 'rgba(255,184,108,0.1)', padding: '3px 10px', borderRadius: 20 }}>
            <i className="fas fa-eye" style={{ marginRight: 5 }} />View-only
          </span>
        )}
      </div>

      {/* ── Main ── */}
      <div style={{ display: 'flex', flex: 1, overflow: 'hidden' }}>

        {/* Sidebar */}
        <div style={{
          width: 50, background: 'var(--secondary)', borderRight: '1px solid var(--border)',
          display: 'flex', flexDirection: 'column', alignItems: 'center',
          padding: '8px 0', gap: 4, flexShrink: 0,
        }}>
          {([
            { key: 'files',        icon: 'fas fa-folder' },
            { key: 'chat',         icon: 'fas fa-comments' },
            { key: 'participants', icon: 'fas fa-users' },
            { key: 'history',      icon: 'fas fa-history' },
          ] as const).map(({ key, icon }) => (
            <button key={key} className={`sidebar-btn${drawer === key ? ' active' : ''}`}
              title={key} onClick={() => toggleDrawer(key)}>
              <i className={icon} />
            </button>
          ))}
        </div>

        {/* Drawer */}
        {drawer && (
          <div style={{
            width: 300, background: 'var(--secondary)', borderRight: '1px solid var(--border)',
            display: 'flex', flexDirection: 'column', flexShrink: 0, overflow: 'hidden',
          }}>
            <div style={{
              display: 'flex', alignItems: 'center', justifyContent: 'space-between',
              padding: '10px 14px', borderBottom: '1px solid var(--border)', flexShrink: 0,
            }}>
              <span style={{ fontWeight: 600, fontSize: '0.9rem' }}>{drawerTitles[drawer]}</span>
              <button onClick={() => setDrawer(null)}
                style={{ background: 'none', border: 'none', color: 'var(--muted)', cursor: 'pointer', fontSize: '1rem' }}>
                <i className="fas fa-times" />
              </button>
            </div>
            <div style={{ flex: 1, overflow: 'auto' }}>
              {drawer === 'files' && (
                <FilesPanel files={collab.files} activeFileId={collab.activeFileId} role={role}
                  onOpen={collab.openFile} onRename={collab.renameFile}
                  onDelete={collab.deleteFile} onCreate={collab.createFile} />
              )}
              {drawer === 'chat' && <ChatPanel roomId={roomId} />}
              {drawer === 'participants' && (
                <ParticipantsPanel roomId={roomId} role={role} owner={owner}
                  participants={collab.participants} roster={collab.roster} />
              )}
              {drawer === 'history' && (
                <HistoryPanel roomId={roomId} role={role}
                  onReplaceProject={collab.replaceProject} onReplaceContent={collab.replaceContent} />
              )}
            </div>
          </div>
        )}

        {/* Editor area */}
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>

          {/* Editor header: file tab + action buttons */}
          <div style={{
            background: 'var(--elevated)', borderBottom: '1px solid var(--border)',
            display: 'flex', alignItems: 'center', justifyContent: 'space-between',
            flexShrink: 0, height: 40,
          }}>
            <div style={{ display: 'flex', height: '100%' }}>
              <div className="file-tab">
                <i className="fas fa-file-code" />
                <span>{collab.getActiveFileName() ?? `main.${language}`}</span>
              </div>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, paddingRight: 10 }}>
              <button className="action-btn run" disabled={role === 'VIEWER'} onClick={runCode}>
                <i className="fas fa-play" /> Run
              </button>
              <button className="action-btn save" onClick={downloadFile}>
                <i className="fas fa-download" /> Save
              </button>
              <button className="action-btn exit" onClick={() => {
                toast('Leaving room...', 'info')
                setTimeout(() => router.push('/'), 700)
              }}>
                <i className="fas fa-sign-out-alt" /> Exit
              </button>
            </div>
          </div>

          {/* Read-only banner */}
          {role === 'VIEWER' && (
            <div className="readonly-banner">
              <i className="fas fa-eye" /> View-only mode — you don&apos;t have edit permission in this room.
            </div>
          )}

          {/* CodeMirror */}
          <div style={{ flex: 1, overflow: 'hidden' }}>
            {roomReady
              ? <CodeEditor activeYText={collab.activeYText} awareness={collab.awareness}
                  language={collab.getActiveLanguage() ?? language} readOnly={role === 'VIEWER'}
                  onReady={view => { editorViewRef.current = view }} />
              : <div style={{ height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--muted)' }}>
                  Joining room…
                </div>
            }
          </div>

          {/* Bottom panel */}
          <div style={{
            height: 200, background: 'var(--secondary)', borderTop: '2px solid var(--border)',
            flexShrink: 0, display: 'flex', flexDirection: 'column',
          }}>
            <div style={{
              display: 'flex', alignItems: 'center', justifyContent: 'space-between',
              padding: '6px 14px', borderBottom: '1px solid var(--border)', flexShrink: 0,
            }}>
              <span style={{ fontSize: '0.8rem', fontWeight: 600, color: 'var(--info)', display: 'flex', alignItems: 'center', gap: 6 }}>
                <i className="fas fa-terminal" /> Output
              </span>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <textarea
                  value={stdin} onChange={e => setStdin(e.target.value)}
                  placeholder="stdin (optional)..."
                  rows={1}
                  style={{ width: 180, resize: 'none', fontSize: '0.8rem', padding: '3px 8px', height: 28 }}
                />
                <button onClick={() => setOutput('Ready to execute code...')}
                  style={{ background: 'none', border: 'none', color: 'var(--muted)', cursor: 'pointer', fontSize: '0.85rem' }}
                  title="Clear">
                  <i className="fas fa-trash" />
                </button>
              </div>
            </div>
            <pre style={{
              flex: 1, padding: '10px 14px', fontFamily: "'JetBrains Mono', monospace",
              fontSize: '0.8rem', color: 'rgba(248,248,242,0.85)', overflow: 'auto', lineHeight: 1.55,
            }}>
              {output}
            </pre>
          </div>
        </div>
      </div>
    </div>
  )
}