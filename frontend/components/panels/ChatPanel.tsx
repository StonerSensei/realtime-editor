'use client'
import { useEffect, useRef, useState } from 'react'
import { API } from '@/lib/api'
import { Auth } from '@/lib/auth'
import { getWsBaseUrl } from '@/lib/constants'
import { escapeHtml, formatTime } from '@/lib/utils'
import { toast } from '../ui/Toast'

interface ChatMsg { username: string; content: string; timestamp: string }

export default function ChatPanel({ roomId }: { roomId: string }) {
  const [messages, setMessages] = useState<ChatMsg[]>([])
  const [input, setInput] = useState('')
  const wsRef = useRef<WebSocket | null>(null)
  const bottomRef = useRef<HTMLDivElement>(null)
  const username = Auth.getUsername() ?? ''
  const leavingRef = useRef(false)

  useEffect(() => {
    // Load history
    API.get<ChatMsg[]>(`/api/chat/${roomId}`)
      .then(msgs => setMessages(msgs ?? []))
      .catch(() => {})

    // Live chat
    function connect() {
      const ws = new WebSocket(
        `${getWsBaseUrl()}/ws/chat/${roomId}?token=${encodeURIComponent(Auth.getToken() ?? '')}`
      )
      wsRef.current = ws
      ws.onmessage = e => {
        try { setMessages(prev => [...prev, JSON.parse(e.data)]) } catch (_) {}
      }
      ws.onclose = () => {
        if (!leavingRef.current) setTimeout(connect, 2000)
      }
    }
    connect()

    return () => {
      leavingRef.current = true
      wsRef.current?.close()
    }
  }, [roomId])

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  function send() {
    const content = input.trim()
    if (!content) return
    const ws = wsRef.current
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ content }))
      setInput('')
    } else {
      toast('Chat not connected', 'warning')
    }
  }

  return (
    <div className="flex flex-col h-full">
      <div className="flex-1 overflow-y-auto p-3 space-y-2">
        {messages.length === 0 && (
          <p className="text-center text-muted text-xs mt-6">No messages yet. Say hello!</p>
        )}
        {messages.map((msg, i) => {
          const mine = msg.username === username
          return (
            <div key={i} className={`flex flex-col ${mine ? 'items-end' : 'items-start'}`}>
              <div className="flex items-center gap-1.5 mb-0.5">
                <span className="text-[11px] font-semibold text-primary">{escapeHtml(msg.username)}</span>
                <span className="text-[10px] text-muted">{formatTime(msg.timestamp)}</span>
              </div>
              <div className={`text-sm px-3 py-1.5 rounded-xl max-w-[85%] break-words
                ${mine ? 'bg-primary/20 text-white rounded-br-sm' : 'bg-elevated text-white/90 rounded-bl-sm'}`}>
                {escapeHtml(msg.content)}
              </div>
            </div>
          )
        })}
        <div ref={bottomRef} />
      </div>

      <div className="p-3 border-t border-white/5 flex gap-2">
        <input
          className="flex-1 bg-elevated border border-white/8 rounded-lg px-3 py-1.5 text-sm text-white
            placeholder-muted outline-none focus:border-primary/50 transition-colors"
          placeholder="Type a message…"
          value={input}
          onChange={e => setInput(e.target.value)}
          onKeyDown={e => e.key === 'Enter' && send()}
        />
        <button
          onClick={send}
          className="px-3 py-1.5 bg-primary/90 hover:bg-primary text-white rounded-lg text-sm font-medium transition-colors"
        >
          Send
        </button>
      </div>
    </div>
  )
}
