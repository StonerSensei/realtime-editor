'use client'
import { useEffect, useState } from 'react'
import { API } from '@/lib/api'
import { formatTime } from '@/lib/utils'
import { toast } from '../ui/Toast'

interface Snapshot {
  id: string; timestamp: string; savedBy?: string
  code?: string; files?: Record<string, string>
}

interface Props {
  roomId: string
  role: string
  onReplaceProject: (files: Record<string, string>) => void
  onReplaceContent: (text: string) => void
}

export default function HistoryPanel({ roomId, role, onReplaceProject, onReplaceContent }: Props) {
  const [snapshots, setSnapshots] = useState<Snapshot[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    API.get<Snapshot[]>(`/api/snapshots/${roomId}`)
      .then(data => setSnapshots(data ?? []))
      .catch(() => setSnapshots([]))
      .finally(() => setLoading(false))
  }, [roomId])

  async function restore(id: string) {
    if (role === 'VIEWER') { toast('Viewers cannot restore versions', 'warning'); return }
    if (!confirm('Restore this version? Current content will be replaced for everyone.')) return
    try {
      const snap = await API.get<Snapshot>(`/api/snapshots/get/${id}`)
      if (snap?.files && Object.keys(snap.files).length) {
        onReplaceProject(snap.files)
        toast('Project version restored', 'success')
      } else if (snap?.code != null) {
        onReplaceContent(snap.code)
        toast('Version restored', 'success')
      }
    } catch (err) { toast((err as Error).message, 'error') }
  }

  if (loading) return <p className="text-xs text-muted text-center mt-6">Loading…</p>
  if (!snapshots.length) return <p className="text-xs text-muted text-center mt-6">No saved versions yet.</p>

  return (
    <div className="p-3 space-y-2">
      {snapshots.map(snap => {
        const preview = snap.files
          ? `${Object.keys(snap.files).length} file(s): ${Object.keys(snap.files).slice(0, 2).join(', ')}`
          : (snap.code ?? '').split('\n')[0].slice(0, 40)

        return (
          <div key={snap.id}
            className="p-3 bg-elevated rounded-lg border border-white/5 space-y-1.5">
            <div className="flex items-center justify-between">
              <span className="text-xs text-muted">{formatTime(snap.timestamp)}</span>
              <span className="text-[11px] text-primary font-medium">{snap.savedBy ?? 'unknown'}</span>
            </div>
            <p className="text-xs text-white/70 font-mono truncate">{preview || '—'}</p>
            <button
              onClick={() => restore(snap.id)}
              disabled={role === 'VIEWER'}
              className="w-full mt-1 py-1 text-xs bg-primary/20 hover:bg-primary/30 text-primary
                rounded-md transition-colors disabled:opacity-40 disabled:cursor-not-allowed font-medium"
            >
              ↩ Restore
            </button>
          </div>
        )
      })}
    </div>
  )
}
