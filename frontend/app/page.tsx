'use client'
import { useState, useEffect, useRef, FormEvent } from 'react'
import { useRouter } from 'next/navigation'
import { Auth } from '@/lib/auth'
import { API } from '@/lib/api'
import { toast } from '@/components/ui/Toast'

const ADJECTIVES = ['quick','lazy','happy','sleepy','noisy','brave','calm','bold']
const NOUNS      = ['fox','dog','cat','panda','tiger','eagle','wolf','bear']
function randomRoomId() {
  const a = ADJECTIVES[Math.floor(Math.random() * ADJECTIVES.length)]
  const n = NOUNS[Math.floor(Math.random() * NOUNS.length)]
  return `${a}-${n}-${Math.floor(1000 + Math.random() * 9000)}`
}

interface Invitation { roomId: string; owner: string; language: string }

export default function LobbyPage() {
  const router = useRouter()
  const [mounted,     setMounted]     = useState(false)
  const [username,    setUsername]    = useState('Guest')
  const [roomId,      setRoomId]      = useState('')
  const [joinCode,    setJoinCode]    = useState('')
  const [language,    setLanguage]    = useState('javascript')
  const [invitations, setInvitations] = useState<Invitation[]>([])

   useEffect(() => {
    if (!Auth.isAuthenticated()) { router.replace('/login'); return }
    setUsername(Auth.getUsername() ?? 'Guest')
    setMounted(true)
  }, [router])

    // Check for invitations every 5 seconds so new ones appear without a refresh.
  const knownInvites = useRef<Set<string> | null>(null)

  useEffect(() => {
    if (!mounted) return

    async function loadInvitations() {
      try {
        const data = (await API.get<Invitation[]>('/api/rooms/invitations')) ?? []

        // Toast only for invitations that arrived after the first load.
        const prev = knownInvites.current
        if (prev) {
          data
            .filter(inv => !prev.has(inv.roomId))
            .forEach(inv => toast(`${inv.owner} invited you to "${inv.roomId}"`, 'info'))
        }
        knownInvites.current = new Set(data.map(inv => inv.roomId))
        setInvitations(data)
      } catch { /* ignore transient failures */ }
    }

    loadInvitations()
    const id = setInterval(loadInvitations, 5000)
    return () => clearInterval(id)
  }, [mounted])

  if (!mounted) return null

  async function joinRoom(e: FormEvent) {
    e.preventDefault()
    if (!roomId.trim()) { toast('Enter a Room ID to join', 'warning'); return }
    try {
      const body = joinCode.trim() ? { joinCode: joinCode.trim() } : {}
      const data = await API.post<{ language: string }>(`/api/rooms/${roomId.trim()}/join`, body)
      router.push(`/editor/${roomId.trim()}?lang=${data.language ?? language}`)
    } catch (err) { toast((err as Error).message, 'error') }
  }

  async function createRoom() {
    const id = roomId.trim() || randomRoomId()
    try {
      const data = await API.post<{ roomId: string; joinCode: string; language: string }>(
        '/api/rooms/host', { roomId: id, language })
      toast(`Room "${data.roomId}" created! Join code: ${data.joinCode}`, 'success')
      router.push(`/editor/${data.roomId}?lang=${data.language}`)
    } catch (err) { toast((err as Error).message, 'error') }
  }

  async function acceptInvitation(inv: Invitation) {
    try {
      await API.post(`/api/rooms/${inv.roomId}/join`, {})
      router.push(`/editor/${inv.roomId}?lang=${inv.language}`)
    } catch (err) { toast((err as Error).message, 'error') }
  }

  return (
    <div style={{
      minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center',
      backgroundImage: `radial-gradient(circle at 25% 25%, rgba(139,233,253,0.08) 0%, transparent 50%),
                        radial-gradient(circle at 75% 75%, rgba(189,147,249,0.08) 0%, transparent 50%)`,
    }}>
      <div className="card" style={{ width: '100%', maxWidth: 600, padding: '2rem', margin: '1rem' }}>

        <h1 style={{ color: 'var(--accent)', marginBottom: '1rem', fontSize: '2.1rem', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 12 }}>
          <i className="fas fa-code" /> CollabIDE
        </h1>
        <p style={{ color: 'rgba(248,248,242,0.6)', marginBottom: '1.5rem', lineHeight: 1.6, textAlign: 'center', fontSize: '0.9rem' }}>
          Real-time collaborative code editing with live execution.
        </p>

        {/* User info */}
        <div style={{ fontSize: '0.85rem', color: 'var(--info)', marginBottom: '2rem', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 14 }}>
          <span><i className="fas fa-user" style={{ marginRight: 6 }} />Logged in as <strong>{username}</strong></span>
          <span onClick={() => Auth.logout()}
            style={{ color: 'var(--error)', cursor: 'pointer', transition: 'opacity 0.2s' }}
            onMouseEnter={e => (e.currentTarget.style.opacity = '0.7')}
            onMouseLeave={e => (e.currentTarget.style.opacity = '1')}>
            <i className="fas fa-sign-out-alt" style={{ marginRight: 4 }} />Logout
          </span>
        </div>

        {/* Room ID */}
        <div style={{ marginBottom: '1.5rem' }}>
          <label><i className="fas fa-door-open" style={{ marginRight: 6 }} />Room ID</label>
          <input type="text" placeholder="Enter room ID or leave blank to generate"
            value={roomId} onChange={e => setRoomId(e.target.value)}
            onKeyDown={e => e.key === 'Enter' && joinRoom(e as unknown as FormEvent)} />
        </div>

        {/* Join Code */}
        <div style={{ marginBottom: '1.5rem' }}>
          <label>
            <i className="fas fa-key" style={{ marginRight: 6 }} />
            Join Code <span style={{ opacity: 0.6, fontWeight: 400 }}>(required for new rooms)</span>
          </label>
          <input type="text" placeholder="6-character code from the room owner"
            maxLength={6}
            value={joinCode}
            onChange={e => setJoinCode(e.target.value.toUpperCase())}
            onKeyDown={e => e.key === 'Enter' && joinRoom(e as unknown as FormEvent)}
            style={{ textTransform: 'uppercase', letterSpacing: '3px', fontSize: '1.05rem', textAlign: 'center' }} />
        </div>

        {/* Language */}
        <div style={{ marginBottom: '2rem' }}>
          <label><i className="fas fa-code" style={{ marginRight: 6 }} />Programming Language</label>
          <select value={language} onChange={e => setLanguage(e.target.value)}>
            <option value="javascript">JavaScript</option>
            <option value="python">Python</option>
            <option value="cpp">C++</option>
            <option value="c">C</option>
            <option value="java">Java</option>
          </select>
        </div>

        {/* Buttons */}
        <div style={{ display: 'flex', gap: '1rem' }}>
          <button className="btn btn-info" style={{ flex: 1 }} onClick={joinRoom as unknown as () => void}>
            <i className="fas fa-sign-in-alt" /> Join Room
          </button>
          <button className="btn btn-success" style={{ flex: 1 }} onClick={createRoom}>
            <i className="fas fa-plus" /> Create Room
          </button>
        </div>

        {/* Invitations */}
        {invitations.length > 0 && (
          <div style={{ marginTop: '2rem' }}>
            <h3 style={{ color: 'var(--accent)', fontSize: '0.95rem', marginBottom: '0.8rem' }}>
              <i className="fas fa-envelope" style={{ marginRight: 8 }} />Invitations
            </h3>
            {invitations.map(inv => (
              <div key={inv.roomId} style={{
                display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                padding: '0.7rem 1rem', background: 'var(--elevated)',
                borderRadius: 6, marginBottom: '0.5rem',
              }}>
                <div>
                  <div style={{ fontWeight: 600 }}>{inv.roomId}</div>
                  <div style={{ fontSize: '0.78rem', opacity: 0.6, marginTop: 2 }}>
                    from {inv.owner} · {inv.language}
                  </div>
                </div>
                <button className="btn btn-success" style={{ padding: '6px 14px', fontSize: '0.83rem' }}
                  onClick={() => acceptInvitation(inv)}>
                  <i className="fas fa-check" /> Join
                </button>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}