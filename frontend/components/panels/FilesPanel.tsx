'use client'
import type { CollabFile } from '@/hooks/useCollab'
import { toast } from '../ui/Toast'

const EXT_ICON: Record<string, string> = {
  js: '⬡', ts: '⬡', py: '🐍', java: '☕', cpp: 'C++', c: 'C',
  html: 'H', css: 'S', json: '{}', md: '📝', txt: '📄',
}
function fileIcon(name: string) {
  const ext = (name.split('.').pop() ?? '').toLowerCase()
  return EXT_ICON[ext] ?? '📄'
}

interface Props {
  files: CollabFile[]
  activeFileId: string | null
  role: string
  onOpen: (id: string) => void
  onRename: (id: string, newName: string) => boolean
  onDelete: (id: string) => boolean
  onCreate: (name: string) => string | null
}

export default function FilesPanel({ files, activeFileId, role, onOpen, onRename, onDelete, onCreate }: Props) {
  const canEdit = role !== 'VIEWER'

  function handleCreate() {
    if (!canEdit) { toast('Viewers cannot create files', 'warning'); return }
    const name = prompt('New file name (e.g. utils.js, helper.py):')
    if (!name?.trim()) return
    if (!onCreate(name.trim())) toast('Invalid or duplicate file name', 'error')
  }

  function handleRename(file: CollabFile) {
    const newName = prompt('Rename file:', file.name)
    if (!newName?.trim() || newName === file.name) return
    if (!onRename(file.id, newName.trim())) toast('A file with that name already exists', 'error')
  }

  function handleDelete(file: CollabFile) {
    if (files.length <= 1) { toast('A room must have at least one file', 'warning'); return }
    if (confirm(`Delete "${file.name}"? This affects everyone.`)) onDelete(file.id)
  }

  return (
    <div className="flex flex-col h-full">
      <div className="flex items-center justify-between px-3 py-2 border-b border-white/5">
        <span className="text-xs text-muted font-medium uppercase tracking-wider">Project files</span>
        {canEdit && (
          <button onClick={handleCreate}
            className="text-xs text-primary hover:text-primary/80 font-medium transition-colors">
            + New
          </button>
        )}
      </div>
      <div className="flex-1 overflow-y-auto p-2 space-y-0.5">
        {files.map(file => (
          <div key={file.id}
            onClick={() => onOpen(file.id)}
            className={`group flex items-center gap-2 px-3 py-2 rounded-lg cursor-pointer transition-colors
              ${file.id === activeFileId ? 'bg-primary/15 text-white' : 'hover:bg-elevated text-white/80'}`}
          >
            <span className="text-xs w-5 text-center flex-shrink-0 font-mono opacity-60">
              {fileIcon(file.name)}
            </span>
            <span className="flex-1 text-sm truncate font-medium">{file.name}</span>
            {canEdit && (
              <div className="hidden group-hover:flex items-center gap-1">
                <button
                  onClick={e => { e.stopPropagation(); handleRename(file) }}
                  className="text-muted hover:text-primary text-[11px] px-1 transition-colors"
                  title="Rename">✎</button>
                <button
                  onClick={e => { e.stopPropagation(); handleDelete(file) }}
                  className="text-muted hover:text-red-400 text-[11px] px-1 transition-colors"
                  title="Delete">✕</button>
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  )
}
