import type { Metadata, Viewport } from "next";
import type { ReactNode } from "react";
import { Head } from "nextra/components";
import { Inter } from "next/font/google";
import { ThemeProvider } from "next-themes";
import { AccentProvider } from "@/components/theme";
import { assetPath, SITE } from "@/lib/site";
import "./globals.css";

const inter = Inter({ subsets: ["latin"], variable: "--font-inter", display: "swap" });

export const metadata: Metadata = {
  metadataBase: new URL(SITE.url),
  title: { default: "LinDo · LINUX DO 第三方客户端", template: "%s · LinDo" },
  description: SITE.description,
  icons: {
    icon: [
      { url: assetPath("/favicon.png"), sizes: "48x48", type: "image/png" },
      { url: assetPath("/icon-192.png"), sizes: "192x192", type: "image/png" },
    ],
    apple: [{ url: assetPath("/apple-icon.png"), sizes: "180x180", type: "image/png" }],
  },
  openGraph: {
    type: "website",
    locale: "zh_CN",
    siteName: "LinDo",
    title: "LinDo · LINUX DO 第三方客户端",
    description: SITE.description,
    url: SITE.url,
    images: [{ url: `${SITE.url}/og.png`, width: 1200, height: 630, alt: "LinDo · LINUX DO 第三方客户端" }],
  },
  twitter: {
    card: "summary_large_image",
    title: "LinDo · LINUX DO 第三方客户端",
    description: SITE.description,
    images: [`${SITE.url}/og.png`],
  },
};

export const viewport: Viewport = {
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#f4f6f8" },
    { media: "(prefers-color-scheme: dark)", color: "#0f1115" },
  ],
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="zh-CN" dir="ltr" suppressHydrationWarning className={inter.variable}>
      <Head
        color={{ hue: { light: 26, dark: 38 }, saturation: { light: 88, dark: 92 }, lightness: { light: 42, dark: 62 } }}
        backgroundColor={{ light: "#f4f6f8", dark: "#0f1115" }}
      />
      <body>
        <ThemeProvider attribute="class" defaultTheme="system" enableSystem disableTransitionOnChange>
          <AccentProvider>{children}</AccentProvider>
        </ThemeProvider>
      </body>
    </html>
  );
}
