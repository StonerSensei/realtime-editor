import { Auth } from './auth'

const API_BASE = process.env.NEXT_PUBLIC_API_URL?.trim() ?? ''

async function request<T = unknown>(
  method: string,
  url: string,
  body?: unknown,
  _retried = false,
): Promise<T> {
  const opts: RequestInit = { method, headers: Auth.headers() }
  if (body !== undefined) opts.body = JSON.stringify(body)

  const res = await fetch(API_BASE + url, opts)

  if (res.status === 401 && !_retried) {
    const refreshed = await Auth.refreshAccessToken()
    if (refreshed) return request<T>(method, url, body, true)
    Auth.clear()
    window.location.href = '/login'
    throw new Error('Session expired')
  }

  const data = await res.json().catch(() => null) as Record<string, unknown> | null

  if (!res.ok) {
    const msg = (data?.message ?? data?.error ?? 'Request failed') as string
    // Only log unexpected errors, not 404 (used for "not found" checks)
    if (res.status !== 404) {
      console.error(`API ${method} ${url} →`, res.status, data)
    }
    throw new Error(msg)
  }
  return data as T
}

export const API = {
  get:  <T = unknown>(url: string)                => request<T>('GET',    url),
  post: <T = unknown>(url: string, body: unknown)  => request<T>('POST',   url, body),
  put:  <T = unknown>(url: string, body: unknown)  => request<T>('PUT',    url, body),
  del:  <T = unknown>(url: string)                => request<T>('DELETE', url),
}