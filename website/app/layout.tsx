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
  metadataBase: new URL("https://lindo.app"),
  title: { default: "LinDo · LINUX DO 第三方客户端", template: "%s · LinDo" },
  description: SITE.description,
  icons: { icon: "/icon.png", apple: "/icon.png" },
  openGraph: { title: "LinDo", description: SITE.description, images: ["/brand/lingdu-welcome.svg"] },
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
