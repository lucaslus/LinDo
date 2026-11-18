import type { Metadata, Viewport } from "next";
import type { ReactNode } from "react";
import { Head } from "nextra/components";
import { Inter } from "next/font/google";
import { ThemeProvider } from "next-themes";
import { AccentProvider } from "@/components/theme";
import { SITE } from "@/lib/site";
import "./globals.css";

const inter = Inter({ subsets: ["latin"], variable: "--font-inter", display: "swap" });

export const metadata: Metadata = {
  metadataBase: new URL(SITE.url),
  title: { default: "LinDo · LINUX DO 第三方客户端", template: "%s · LinDo" },
  description: SITE.description,
  icons: {
    icon: [
      { url: "/favicon.png", sizes: "48x48", type: "image/png" },
      { url: "/icon-192.png", sizes: "192x192", type: "image/png" },
    ],
    apple: [{ url: "/apple-icon.png", sizes: "180x180", type: "image/png" }],
  },
  openGraph: {
    type: "website",
    locale: "zh_CN",
    siteName: "LinDo",
    title: "LinDo · LINUX DO 第三方客户端",
    description: SITE.description,
    url: SITE.url,
    images: [{ url: "/og.png", width: 1200, height: 630, alt: "LinDo · LINUX DO 第三方客户端" }],
  },
  twitter: {
    card: "summary_large_image",
    title: "LinDo · LINUX DO 第三方客户端",
    description: SITE.description,
    images: ["/og.png"],
  },
};

export const viewport: Viewport = {
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#ffffff" },
    { media: "(prefers-color-scheme: dark)", color: "#101217" },
  ],
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="zh-CN" dir="ltr" suppressHydrationWarning className={inter.variable}>
      <Head
        color={{ hue: { light: 33, dark: 45 }, saturation: { light: 100, dark: 95 }, lightness: { light: 30, dark: 64 } }}
        backgroundColor={{ light: "#ffffff", dark: "#101217" }}
      />
      <body>
        <ThemeProvider attribute="class" defaultTheme="system" enableSystem disableTransitionOnChange>
          <AccentProvider>{children}</AccentProvider>
        </ThemeProvider>
      </body>
    </html>
  );
}
