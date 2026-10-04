/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'standalone',
  reactStrictMode: false,

  // Dev only: proxies /api/* to Spring Boot. In production, Caddy routes
  // /api/* straight to the backend, so this rewrite isn't used.
  async rewrites() {
    const backendUrl = process.env.BACKEND_URL ?? 'http://localhost:8080'
    return [{ source: '/api/:path*', destination: `${backendUrl}/api/:path*` }]
  },
}

export default nextConfig