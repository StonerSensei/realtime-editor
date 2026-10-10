'use client'
import { useState, useEffect, FormEvent } from 'react'
import { useRouter } from 'next/navigation'
import { Auth } from '@/lib/auth'
import { toast } from '@/components/ui/Toast'

const API_BASE = process.env.NEXT_PUBLIC_API_URL?.trim() ?? ''

type Tab = 'login' | 'register'

export default function LoginPage() {
  const router = useRouter()
  const [mounted,   setMounted]   = useState(false)
  const [tab,       setTab]       = useState<Tab>('login')
  const [loading,   setLoading]   = useState(false)
  const [loginUser, setLoginUser] = useState('')
  const [loginPass, setLoginPass] = useState('')
  const [loginErr,  setLoginErr]  = useState('')
  const [regUser,   setRegUser]   = useState('')
  const [regEmail,  setRegEmail]  = useState('')
  const [regPass,   setRegPass]   = useState('')
  const [regErr,    setRegErr]    = useState('')

  useEffect(() => {
    setMounted(true)
    if (Auth.isAuthenticated()) router.replace('/')
  }, [router])

  if (!mounted) return null

  async function handleLogin(e: FormEvent) {
    e.preventDefault(); setLoginErr(''); setLoading(true)
    try {
      const res = await fetch(API_BASE + '/api/auth/login', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: loginUser, password: loginPass }),
      })
      const data = await res.json()
      if (!res.ok) { setLoginErr(data.message ?? 'Login failed'); return }
      Auth.setCredentials(data.token, data.username, data.refreshToken)
      router.push('/')
    } catch { setLoginErr('Connection error. Please try again.') }
    finally { setLoading(false) }
  }

  async function handleRegister(e: FormEvent) {
    e.preventDefault(); setRegErr(''); setLoading(true)
    try {
      const res = await fetch(API_BASE + '/api/auth/register', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: regUser, email: regEmail, password: regPass }),
      })
      const data = await res.json()
      if (!res.ok) {
        const msg = data.validationErrors
          ? Object.values(data.validationErrors as Record<string, string>).join('. ')
          : (data.message ?? 'Registration failed')
        setRegErr(msg); return
      }
      Auth.setCredentials(data.token, data.username, data.refreshToken)
      toast('Account created!', 'success')
      router.push('/')
    } catch { setRegErr('Connection error. Please try again.') }
    finally { setLoading(false) }
  }

  return (
    <div style={{
      minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '1rem',
      backgroundImage: `radial-gradient(circle at 25% 25%, rgba(139,233,253,0.08) 0%, transparent 50%),
                        radial-gradient(circle at 75% 75%, rgba(189,147,249,0.08) 0%, transparent 50%)`,
    }}>
      <div className="card" style={{ width: '100%', maxWidth: 440, padding: '2rem' }}>
        <h1 style={{ color: 'var(--accent)', fontSize: '2rem', textAlign: 'center', marginBottom: '0.3rem', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 10 }}>
          <i className="fas fa-code" /> CollabIDE
        </h1>
        <p style={{ textAlign: 'center', color: 'rgba(248,248,242,0.5)', marginBottom: '1.75rem', fontSize: '0.88rem' }}>
          Sign in to start collaborating
        </p>

        {/* Tabs */}
        <div style={{ display: 'flex', marginBottom: '1.5rem', borderRadius: 'var(--radius)', overflow: 'hidden', border: '1px solid var(--border)' }}>
          {(['login', 'register'] as Tab[]).map(t => (
            <button key={t} onClick={() => setTab(t)} style={{
              flex: 1, padding: '0.7rem', border: 'none', cursor: 'pointer',
              fontSize: '0.95rem', fontWeight: 600, fontFamily: 'inherit',
              textTransform: 'capitalize', transition: 'all 0.2s',
              background: tab === t ? 'var(--accent)' : 'var(--elevated)',
              color:      tab === t ? 'var(--bg)'     : 'var(--text)',
            }}>{t}</button>
          ))}
        </div>

        {tab === 'login' && (
          <form onSubmit={handleLogin}>
            <div style={{ marginBottom: '1.25rem' }}>
              <label><i className="fas fa-user" style={{ marginRight: 6 }} />Username</label>
              <input type="text" required placeholder="Enter username"
                value={loginUser} onChange={e => setLoginUser(e.target.value)} />
            </div>
            <div style={{ marginBottom: '1.25rem' }}>
              <label><i className="fas fa-lock" style={{ marginRight: 6 }} />Password</label>
              <input type="password" required placeholder="Enter password"
                value={loginPass} onChange={e => setLoginPass(e.target.value)} />
            </div>
            {loginErr && <p style={{ color: 'var(--error)', fontSize: '0.84rem', textAlign: 'center', marginBottom: '0.75rem' }}>{loginErr}</p>}
            <button type="submit" className="btn btn-primary" disabled={loading} style={{ width: '100%' }}>
              <i className="fas fa-sign-in-alt" />
              {loading ? 'Signing in…' : 'Login'}
            </button>
          </form>
        )}

        {tab === 'register' && (
          <form onSubmit={handleRegister}>
            <div style={{ marginBottom: '1.25rem' }}>
              <label><i className="fas fa-user" style={{ marginRight: 6 }} />Username</label>
              <input type="text" required minLength={3} maxLength={30} placeholder="Choose a username"
                value={regUser} onChange={e => setRegUser(e.target.value)} />
            </div>
            <div style={{ marginBottom: '1.25rem' }}>
              <label><i className="fas fa-envelope" style={{ marginRight: 6 }} />Email</label>
              <input type="email" required placeholder="Enter your email"
                value={regEmail} onChange={e => setRegEmail(e.target.value)} />
            </div>
            <div style={{ marginBottom: '1.25rem' }}>
              <label><i className="fas fa-lock" style={{ marginRight: 6 }} />Password</label>
              <input type="password" required minLength={6} placeholder="Min 6 characters"
                value={regPass} onChange={e => setRegPass(e.target.value)} />
            </div>
            {regErr && <p style={{ color: 'var(--error)', fontSize: '0.84rem', textAlign: 'center', marginBottom: '0.75rem' }}>{regErr}</p>}
            <button type="submit" className="btn btn-primary" disabled={loading} style={{ width: '100%' }}>
              <i className="fas fa-user-plus" />
              {loading ? 'Creating account…' : 'Register'}
            </button>
          </form>
        )}
      </div>
    </div>
  )
}