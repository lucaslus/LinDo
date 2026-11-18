"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import clsx from "clsx";
import Link from "next/link";
import { GithubLogo } from "@phosphor-icons/react";
import { IconClose, IconDownload, IconMenu, IconMoon, IconSun } from "@/components/icons";
import { LingduHead, Wordmark, type Expression } from "@/components/brand";
import { originFromEvent, useThemeSwitch } from "@/components/theme";
import { SITE } from "@/lib/site";

export function Reveal({ children, className, delay = 0 }: { children: ReactNode; className?: string; delay?: number }) {
  const ref = useRef<HTMLDivElement>(null);
  const [inView, setInView] = useState(false);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const io = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          setInView(true);
          io.disconnect();
        }
      },
      { rootMargin: "0px 0px -8% 0px" },
    );
    io.observe(el);
    return () => io.disconnect();
  }, []);
  return (
    <div ref={ref} className={clsx("reveal", inView && "is-in", className)} style={{ transitionDelay: `${delay}ms` }}>
      {children}
    </div>
  );
}

/** 顶栏主题按钮：默认由 CSS `.dark` 在首帧决定开心/犯困与日月图标，避免深色水合闪烁 */
export function MascotThemeToggle() {
  const { isDark, mounted, toggle } = useThemeSwitch();
  const [surprised, setSurprised] = useState(false);
  const [hover, setHover] = useState(false);
  const timerRef = useRef<number | undefined>(undefined);

  useEffect(() => {
    return () => {
      if (timerRef.current !== undefined) window.clearTimeout(timerRef.current);
    };
  }, []);

  const expression: Expression = surprised ? "surprised" : hover ? "wink" : "theme";
  const label = mounted ? (isDark ? "切换为浅色" : "切换为深色") : "切换深浅主题";

  return (
    <button
      type="button"
      onClick={(e) => {
        setSurprised(true);
        if (timerRef.current !== undefined) window.clearTimeout(timerRef.current);
        timerRef.current = window.setTimeout(() => setSurprised(false), 650);
        toggle(originFromEvent(e));
      }}
      onPointerEnter={() => setHover(true)}
      onPointerLeave={() => setHover(false)}
      className="flex h-10 items-center gap-1.5 rounded-full py-1 pr-3 pl-1 transition-colors hover:bg-[var(--hover)]"
      aria-label={label}
      title={label}
    >
      <LingduHead size={32} expression={expression} preloadAll />
      <span className="inline-flex" style={{ color: "var(--ink-2)" }}>
        <span className="dark:hidden">
          <IconSun size={16} />
        </span>
        <span className="hidden dark:inline-flex">
          <IconMoon size={16} />
        </span>
      </span>
    </button>
  );
}

const NAV = [
  { href: "/#highlights", label: "亮点" },
  { href: "/#security", label: "安全与隐私" },
  { href: "/changelog", label: "更新日志" },
  { href: "/docs", label: "文档" },
  { href: "/#download", label: "下载" },
];

export function SiteNav() {
  const [scrolled, setScrolled] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const headerRef = useRef<HTMLElement>(null);

  useEffect(() => {
    const on = () => {
      const next = window.scrollY > 8;
      setScrolled((prev) => (prev === next ? prev : next));
    };
    on();
    window.addEventListener("scroll", on, { passive: true });
    return () => window.removeEventListener("scroll", on);
  }, []);

  useEffect(() => {
    if (!menuOpen) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setMenuOpen(false);
    };
    const onPointerDown = (e: PointerEvent) => {
      if (headerRef.current && !headerRef.current.contains(e.target as Node)) {
        setMenuOpen(false);
      }
    };
    window.addEventListener("keydown", onKey);
    window.addEventListener("pointerdown", onPointerDown);
    return () => {
      window.removeEventListener("keydown", onKey);
      window.removeEventListener("pointerdown", onPointerDown);
    };
  }, [menuOpen]);

  const showSurface = scrolled || menuOpen;

  return (
    <header
      ref={headerRef}
      className="sticky top-0 z-50 transition-[background-color,border-color] duration-200"
      style={{
        background: showSurface ? "var(--nav-surface)" : "transparent",
        backdropFilter: showSurface ? "blur(14px) saturate(1.35)" : "none",
        WebkitBackdropFilter: showSurface ? "blur(14px) saturate(1.35)" : "none",
        borderBottom: `0.5px solid ${showSurface ? "var(--hairline)" : "transparent"}`,
      }}
    >
      <div className="mx-auto flex h-16 max-w-[1200px] items-center gap-6 px-5 sm:px-6">
        <Link href="/" className="flex items-center gap-2" aria-label="LinDo 首页" onClick={() => setMenuOpen(false)}>
          <LingduHead size={30} />
          <Wordmark size={20} />
        </Link>
        <nav className="hidden items-center gap-1 md:flex" aria-label="主导航">
          {NAV.map((n) => (
            <Link
              key={n.href}
              href={n.href}
              className="rounded-lg px-3 py-2 text-[14.5px] transition-colors hover:bg-[var(--hover)]"
              style={{ color: "var(--ink-2)" }}
            >
              {n.label}
            </Link>
          ))}
        </nav>
        <div className="ml-auto flex items-center gap-1">
          <a
            href={SITE.repo}
            target="_blank"
            rel="noreferrer"
            className="grid h-10 w-10 place-items-center rounded-full transition-colors hover:bg-[var(--hover)]"
            aria-label="GitHub 仓库"
          >
            <GithubLogo size={20} />
          </a>
          <MascotThemeToggle />
          <Link href="/#download" className="btn-primary ml-1.5 hidden !h-9 !px-4 !text-[14px] sm:inline-flex">
            下载
          </Link>
          <button
            type="button"
            onClick={() => setMenuOpen((v) => !v)}
            className="grid h-10 w-10 place-items-center rounded-full transition-colors hover:bg-[var(--hover)] md:hidden"
            aria-label={menuOpen ? "收起导航菜单" : "展开导航菜单"}
            aria-expanded={menuOpen}
            aria-controls="mobile-site-nav"
          >
            {menuOpen ? <IconClose size={20} /> : <IconMenu size={20} />}
          </button>
        </div>
      </div>

      {menuOpen && (
        <nav
          id="mobile-site-nav"
          aria-label="移动端导航"
          className="mx-auto max-w-[1200px] px-5 pt-1 pb-4 md:hidden"
          style={{ borderTop: "0.5px solid var(--hairline)" }}
        >
          <div className="flex flex-col gap-1">
            {NAV.map((n) => (
              <Link
                key={n.href}
                href={n.href}
                onClick={() => setMenuOpen(false)}
                className="flex h-11 items-center rounded-xl px-3 text-[15px] font-medium transition-colors hover:bg-[var(--hover)]"
                style={{ color: "var(--ink-1)" }}
              >
                {n.label}
              </Link>
            ))}
            <Link
              href="/#download"
              onClick={() => setMenuOpen(false)}
              className="btn-primary mt-2 justify-center sm:hidden"
            >
              <IconDownload size={17} strokeWidth={2} />
              免费下载 LinDo
            </Link>
          </div>
        </nav>
      )}
    </header>
  );
}
