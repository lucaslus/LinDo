"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { IconClose, IconDownload, IconGitHub, IconMenu, IconMoon, IconSun } from "@/components/icons";
import { LingduHead, Wordmark, type Expression } from "@/components/brand";
import { originFromEvent, useThemeSwitch } from "@/components/theme";
import { SITE } from "@/lib/site";

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
      className="flex h-9 items-center gap-1.5 rounded-full py-1 pr-2.5 pl-1.5 transition-colors hover:bg-[var(--hover)]"
      aria-label={label}
      title={label}
    >
      <LingduHead size={24} expression={expression} preloadAll />
      <span className="inline-flex" style={{ color: "var(--ink-2)" }}>
        <span className="dark:hidden">
          <IconSun size={14.5} />
        </span>
        <span className="hidden dark:inline-flex">
          <IconMoon size={14.5} />
        </span>
      </span>
    </button>
  );
}

const NAV = [
  { href: "/#screens", label: "界面" },
  { href: "/#highlights", label: "功能" },
  { href: "/changelog", label: "更新日志" },
  { href: "/docs", label: "文档" },
];

export function SiteNav() {
  const [menuOpen, setMenuOpen] = useState(false);
  const headerRef = useRef<HTMLElement>(null);

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

  return (
    <header ref={headerRef} className="site-nav-bar">
      <div className="mx-auto flex h-[58px] max-w-[1120px] items-center gap-6 px-5">
        <Link href="/" className="flex items-center gap-2" aria-label="LinDo 首页" onClick={() => setMenuOpen(false)}>
          <LingduHead size={28} />
          <Wordmark size={18} />
        </Link>
        <nav className="hidden items-center gap-0.5 md:flex" aria-label="主导航">
          {NAV.map((n) => (
            <Link
              key={n.href}
              href={n.href}
              className="rounded-lg px-3 py-1.5 text-[14px] font-medium transition-colors hover:bg-[var(--hover)] hover:text-[var(--ink-1)]"
              style={{ color: "var(--ink-2)" }}
            >
              {n.label}
            </Link>
          ))}
        </nav>
        <div className="ml-auto flex items-center gap-1.5">
          <a
            href={SITE.repo}
            target="_blank"
            rel="noreferrer"
            className="grid h-9 w-9 place-items-center rounded-full transition-colors hover:bg-[var(--hover)] hover:text-[var(--ink-1)]"
            style={{ color: "var(--ink-2)" }}
            aria-label="GitHub 仓库"
          >
            <IconGitHub size={18.5} />
          </a>
          <MascotThemeToggle />
          <Link href="/#download" className="btn-primary ml-1 hidden !h-[34px] !px-4 !text-[13.5px] sm:inline-flex">
            下载
          </Link>
          <button
            type="button"
            onClick={() => setMenuOpen((v) => !v)}
            className="grid h-9 w-9 place-items-center rounded-full transition-colors hover:bg-[var(--hover)] md:hidden"
            style={{ color: "var(--ink-2)" }}
            aria-label={menuOpen ? "收起导航菜单" : "展开导航菜单"}
            aria-expanded={menuOpen}
            aria-controls="mobile-site-nav"
          >
            {menuOpen ? <IconClose size={19} /> : <IconMenu size={19} />}
          </button>
        </div>
      </div>

      {menuOpen && (
        <nav
          id="mobile-site-nav"
          aria-label="移动端导航"
          className="mx-auto max-w-[1120px] px-5 pt-2 pb-4 md:hidden"
          style={{ borderTop: "1px solid var(--hairline)" }}
        >
          <div className="flex flex-col gap-1">
            {NAV.map((n) => (
              <Link
                key={n.href}
                href={n.href}
                onClick={() => setMenuOpen(false)}
                className="flex h-10 items-center rounded-lg px-3 text-[14.5px] font-medium transition-colors hover:bg-[var(--hover)]"
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
              <IconDownload size={16} strokeWidth={2} />
              下载 LinDo
            </Link>
          </div>
        </nav>
      )}
    </header>
  );
}
