'use client'
import { useState, useEffect, useRef } from 'react'
import { API } from '@/lib/api'
import { Auth } from '@/lib/auth'
import type { Participant } from '@/hooks/useCollab'
import { toast } from '../ui/Toast'

interface Member { username: string; role: string }
interface Props {
  roomId: string; role: string; owner: string
  participants: Participant[]; roster: string[]
}

export default function ParticipantsPanel({ roomId, role, owner, participants, roster }: Props) {
  const [members,       setMembers]       = useState<Record<string, string>>({})
  const [joinCode,      setJoinCode]      = useState('')
  const [inviteQuery,   setInviteQuery]   = useState('')
  const [searchResults, setSearchResults] = useState<string[]>([])
  const [selectedUser,  setSelectedUser]  = useState<string | null>(null)
  const searchTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const username = Auth.getUsername() ?? ''
  const isOwner  = role === 'OWNER'

  useEffect(() => {
    API.get<{ members: Member[]; joinCode?: string }>(`/api/rooms/${roomId}`)
      .then(data => {
        const map: Record<string, string> = {}
        ;(data?.members ?? []).forEach(m => { map[m.username] = m.role })
        setMembers(map)
        if (isOwner && data?.joinCode) setJoinCode(data.joinCode)
      })
      .catch(() => {})
  }, [roomId, isOwner])

  async function changeRole(user: string, newRole: string) {
    try {
      const data = await API.put<{ members: Member[] }>(
        `/api/rooms/${roomId}/members/${encodeURIComponent(user)}/role`, { role: newRole })
      const map: Record<string, string> = {}
      ;(data?.members ?? []).forEach(m => { map[m.username] = m.role })
      setMembers(map); toast(`${user} is now ${newRole}`, 'success')
    } catch (err) { toast((err as Error).message, 'error') }
  }

  async function kick(user: string) {
    if (!confirm(`Remove ${user} from the room?`)) return
    try {
      await API.del(`/api/rooms/${roomId}/members/${encodeURIComponent(user)}`)
      setMembers(prev => { const n = { ...prev }; delete n[user]; return n })
      toast(`${user} removed`, 'info')
    } catch (err) { toast((err as Error).message, 'error') }
  }

  async function regenerateCode() {
    if (!confirm('Generate a new join code? The old code will stop working.')) return
    try {
      const data = await API.post<{ joinCode: string }>(`/api/rooms/${roomId}/regenerate-code`, {})
      setJoinCode(data.joinCode); toast('New code generated', 'success')
    } catch (err) { toast((err as Error).message, 'error') }
  }

  function handleInviteInput(q: string) {
    setInviteQuery(q); setSelectedUser(null)
    if (searchTimer.current) clearTimeout(searchTimer.current)
    if (q.trim().length < 2) { setSearchResults([]); return }
    searchTimer.current = setTimeout(async () => {
      try {
        const users = await API.get<string[]>(`/api/rooms/users/search?q=${encodeURIComponent(q)}`)
        setSearchResults((users ?? []).filter(u => u !== username && !members[u]))
      } catch { setSearchResults([]) }
    }, 300)
  }

  async function sendInvite() {
    if (!selectedUser) return
    try {
      await API.post(`/api/rooms/${roomId}/invite`, { username: selectedUser })
      toast(`Invited ${selectedUser}`, 'success')
      setInviteQuery(''); setSelectedUser(null); setSearchResults([])
    } catch (err) { toast((err as Error).message, 'error') }
  }

  const onlineSet = new Set(roster.length ? roster : participants.map(p => p.name))
  const names     = [...new Set([...onlineSet, ...Object.keys(members)])].sort()

  const roleBadge = (r: string) => {
    if (r === 'OWNER')  return { background: 'rgba(255,184,108,0.15)', color: 'var(--warning)' }
    if (r === 'EDITOR') return { background: 'rgba(189,147,249,0.15)', color: 'var(--accent)' }
    return { background: 'rgba(98,114,164,0.15)', color: 'var(--muted)' }
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>

      {/* Join Code — owner only */}
      {isOwner && joinCode && (
        <div style={{ padding: '0.8rem', borderBottom: '1px solid var(--border)' }}>
          <div style={{ fontSize: '0.72rem', color: 'var(--muted)', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: 6 }}>
            Join Code
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ fontFamily: 'monospace', fontSize: '1.5rem', letterSpacing: '0.3em', color: 'var(--accent)', fontWeight: 700 }}>
              {joinCode}
            </span>
            <button className="btn btn-ghost" style={{ padding: '3px 8px', fontSize: '0.75rem' }}
              onClick={() => { navigator.clipboard.writeText(joinCode); toast('Code copied!', 'success') }}>
              <i className="fas fa-copy" />
            </button>
            <button className="btn btn-ghost" style={{ padding: '3px 8px', fontSize: '0.75rem' }}
              onClick={regenerateCode} title="Regenerate code">
              <i className="fas fa-sync-alt" />
            </button>
          </div>
        </div>
      )}

      {/* Invite — owner only */}
      {isOwner && (
        <div style={{ padding: '0.8rem', borderBottom: '1px solid var(--border)', position: 'relative' }}>
          <div style={{ fontSize: '0.72rem', color: 'var(--muted)', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: 6 }}>
            <i className="fas fa-user-plus" style={{ marginRight: 5 }} />Invite
          </div>
          <div style={{ display: 'flex', gap: 6 }}>
            <input value={inviteQuery} onChange={e => handleInviteInput(e.target.value)}
              onKeyDown={e => e.key === 'Enter' && selectedUser && sendInvite()}
              placeholder="Search username…"
              style={{ flex: 1, fontSize: '0.82rem', padding: '5px 8px' }} />
            <button className="btn btn-primary" disabled={!selectedUser}
              style={{ padding: '5px 10px', fontSize: '0.82rem' }} onClick={sendInvite}>
              Invite
            </button>
          </div>
          {searchResults.length > 0 && (
            <div style={{
              position: 'absolute', left: '0.8rem', right: '0.8rem',
              background: 'var(--elevated)', border: '1px solid var(--border)',
              borderRadius: 'var(--radius)', zIndex: 10, marginTop: 2, top: '100%',
              boxShadow: '0 4px 12px rgba(0,0,0,0.3)',
            }}>
              {searchResults.map(u => (
                <div key={u}
                  onClick={() => { setInviteQuery(u); setSelectedUser(u); setSearchResults([]) }}
                  style={{ padding: '7px 12px', cursor: 'pointer', fontSize: '0.85rem', transition: 'background 0.1s' }}
                  onMouseEnter={e => (e.currentTarget.style.background = 'var(--secondary)')}
                  onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}>
                  <i className="fas fa-user" style={{ marginRight: 7, color: 'var(--muted)', fontSize: '0.78rem' }} />
                  {u}
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {/* Participant list */}
      <div style={{ flex: 1, overflow: 'auto', padding: '0.5rem' }}>
        {names.length === 0 && (
          <p className="hint">No participants yet</p>
        )}
        {names.map(name => {
          const memberRole = members[name] ?? 'GUEST'
          const isOnline   = onlineSet.has(name)
          const color      = participants.find(p => p.name === name)?.color ?? '#6272a4'
          return (
            <div key={name} style={{
              display: 'flex', alignItems: 'center', gap: 8,
              padding: '6px 8px', borderRadius: 6, marginBottom: 2,
            }}>
              <span style={{
                width: 8, height: 8, borderRadius: '50%', flexShrink: 0,
                background: isOnline ? color : '#4b5563',
                boxShadow: isOnline ? `0 0 7px ${color}` : 'none',
                transition: 'all 0.3s',
              }} />
              <span style={{ flex: 1, fontSize: '0.88rem', fontWeight: 500 }}>{name}</span>
              <span style={{ fontSize: '0.7rem', padding: '2px 7px', borderRadius: 4, fontWeight: 700, ...roleBadge(memberRole) }}>
                {memberRole}
              </span>
              {isOwner && name !== owner && (
                <>
                  <select value={memberRole} onChange={e => changeRole(name, e.target.value)}
                    style={{ width: 'auto', fontSize: '0.75rem', padding: '2px 4px' }}>
                    <option value="EDITOR">Editor</option>
                    <option value="VIEWER">Viewer</option>
                  </select>
                  <button onClick={() => kick(name)}
                    style={{ background: 'none', border: 'none', color: 'var(--error)', cursor: 'pointer', padding: 2, fontSize: '0.85rem' }}
                    title="Remove">
                    <i className="fas fa-user-slash" />
                  </button>
                </>
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}