/**
 * Auth utilities — mirrors the original auth.js behaviour exactly.
 * All localStorage access is guarded so this module is safe to import in SSR context.
 */

const API_BASE = process.env.NEXT_PUBLIC_API_URL?.trim() ?? ''

function store(key: string, value: string) {
  if (typeof window !== 'undefined') localStorage.setItem(key, value)
}
function load(key: string): string | null {
  if (typeof window === 'undefined') return null
  return localStorage.getItem(key)
}
function drop(key: string) {
  if (typeof window !== 'undefined') localStorage.removeItem(key)
}

export const Auth = {
  getToken: ()        => load('token'),
  getRefreshToken: () => load('refreshToken'),
  getUsername: ()     => load('username'),

  setCredentials(token: string, username?: string, refreshToken?: string) {
    store('token', token)
    if (username)     store('username', username)
    if (refreshToken) store('refreshToken', refreshToken)
  },

  clear() {
    drop('token')
    drop('refreshToken')
    drop('username')
  },

  isAuthenticated: () => !!load('token'),

  headers(contentType = 'application/json'): Record<string, string> {
    return { 'Content-Type': contentType, Authorization: 'Bearer ' + load('token') }
  },

  // Deduped refresh: multiple concurrent 401s share one in-flight request.
  _refreshing: null as Promise<boolean> | null,

  async refreshAccessToken(): Promise<boolean> {
    const refreshToken = this.getRefreshToken()
    if (!refreshToken) return false

    if (!this._refreshing) {
      this._refreshing = fetch(API_BASE + '/api/auth/refresh', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken }),
      })
        .then(res => (res.ok ? res.json() : null))
        .then((data: { token?: string; username?: string; refreshToken?: string } | null) => {
          if (data?.token) {
            this.setCredentials(data.token, data.username, data.refreshToken)
            return true
          }
          return false
        })
        .catch(() => false)
        .finally(() => { this._refreshing = null })
    }
    return this._refreshing
  },

  _autoRefreshTimer: null as ReturnType<typeof setInterval> | null,

  startAutoRefresh(intervalMs = 20 * 60 * 1000) {
    if (this._autoRefreshTimer) return
    this._autoRefreshTimer = setInterval(() => {
      if (this.isAuthenticated()) this.refreshAccessToken()
    }, intervalMs)
  },

  async logout() {
    const refreshToken = this.getRefreshToken()
    if (refreshToken) {
      try {
        await fetch(API_BASE + '/api/auth/logout', {
          method: 'POST',
          headers: this.headers(),
          body: JSON.stringify({ refreshToken }),
        })
      } catch (_) { /* best-effort */ }
    }
    this.clear()
    window.location.href = '/login'
  },
}

// Keep the access token fresh in background (no-op on server).
if (typeof window !== 'undefined' && Auth.isAuthenticated()) {
  Auth.startAutoRefresh()
}
