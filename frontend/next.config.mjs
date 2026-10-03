/** @type {import('next').NextConfig} */
const nextConfig = {
  /**
   * In development, Next.js proxies /api/* calls to the Spring Boot backend.
   * This avoids CORS issues for REST calls during local development.
   *
   * NOTE: WebSocket connections (/yjs/*, /ws/*) cannot be proxied by Next.js rewrites.
   * The browser connects to those directly using NEXT_PUBLIC_API_URL (see lib/constants.ts).
   * That's why CORS must also be configured on the Spring Boot side.
   */
  async rewrites() {
    const backendUrl = process.env.BACKEND_URL ?? 'http://localhost:8080'
    return [
      {
        source: '/api/:path*',
        destination: `${backendUrl}/api/:path*`,
      },
    ]
  },
}

export default nextConfig
