import type { Metadata } from 'next'
import { ToastContainer } from '@/components/ui/Toast'
import './globals.css'

export const metadata: Metadata = {
  title: 'CollabIDE',
  description: 'Real-time collaborative code editor',
}

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" suppressHydrationWarning>
      <head>
        <link
          rel="stylesheet"
          href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.4.0/css/all.min.css"
        />
      </head>
      <body suppressHydrationWarning>
        {children}
        <ToastContainer />
      </body>
    </html>
  )
}