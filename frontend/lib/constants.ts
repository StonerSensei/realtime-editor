/**
 * Runtime base URLs.
 *
 * REST calls go to /api/* which next.config.mjs rewrites to BACKEND_URL.
 * So for REST you just use relative paths like "/api/auth/login" — no base URL needed.
 *
 * WebSocket connections cannot be proxied by Next.js rewrites, so the browser
 * connects directly to the Spring Boot server using WS_BASE_URL.
 */

function resolveApiUrl(): string {
  if (typeof window === 'undefined') return 'http://localhost:8080'
  const env = process.env.NEXT_PUBLIC_API_URL
  // If env var is set and non-empty, use it. Otherwise fall back to same origin.
  return env && env.trim() ? env.trim() : window.location.origin
}

/** Base URL for WebSocket connections (browser only). Derived from NEXT_PUBLIC_API_URL. */
export function getWsBaseUrl(): string {
  const env = process.env.NEXT_PUBLIC_API_URL
  if (env && env.trim()) return env.trim().replace(/^http/, 'ws')
  // Fallback: always hit the Spring Boot port directly
  if (typeof window !== 'undefined') {
    const host = window.location.hostname
    return `ws://${host}:8080`
  }
  return 'ws://localhost:8080'
}