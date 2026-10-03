'use client'
import { useEffect, useState } from 'react'

export type ToastType = 'success' | 'error' | 'warning' | 'info'

interface ToastMsg { id: number; message: string; type: ToastType }

let addToast: ((msg: string, type: ToastType) => void) | null = null

export function toast(message: string, type: ToastType = 'success') {
  addToast?.(message, type)
}

const icons: Record<ToastType, string> = {
  success: '✓', error: '✕', warning: '⚠', info: 'ℹ',
}
const colours: Record<ToastType, string> = {
  success: 'bg-emerald-600 border-emerald-500',
  error:   'bg-red-600 border-red-500',
  warning: 'bg-amber-600 border-amber-500',
  info:    'bg-blue-600 border-blue-500',
}

export function ToastContainer() {
  const [toasts, setToasts] = useState<ToastMsg[]>([])

  useEffect(() => {
    addToast = (message, type) => {
      const id = Date.now()
      setToasts(prev => [...prev, { id, message, type }])
      setTimeout(() => setToasts(prev => prev.filter(t => t.id !== id)), 3200)
    }
    return () => { addToast = null }
  }, [])

  return (
    <div className="fixed bottom-5 right-5 z-50 flex flex-col gap-2 pointer-events-none">
      {toasts.map(t => (
        <div
          key={t.id}
          className={`flex items-center gap-2 px-4 py-3 rounded-lg border text-sm font-medium text-white shadow-xl
            animate-[slideIn_0.2s_ease] ${colours[t.type]}`}
        >
          <span className="text-base leading-none">{icons[t.type]}</span>
          {t.message}
        </div>
      ))}
    </div>
  )
}
