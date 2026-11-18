"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import clsx from "clsx";
import { GithubLogo } from "@phosphor-icons/react";
import { IconMoon, IconSun } from "@/components/icons";
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
      { rootMargin: "0px 0px -10% 0px" },
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

/** 顶栏主题按钮：零度娘浅色时开心，深色时犯困；切换瞬间惊讶 */
export function MascotThemeToggle() {
  const { isDark, toggle } = useThemeSwitch();
  const [surprised, setSurprised] = useState(false);
  const [hover, setHover] = useState(false);
  const expression: Expression = surprised ? "surprised" : hover ? "wink" : isDark ? "sleepy" : "happy";

  return (
    <button
      type="button"
      onClick={(e) => {
        setSurprised(true);
        window.setTimeout(() => setSurprised(false), 650);
        toggle(originFromEvent(e));
      }}
      onPointerEnter={() => setHover(true)}
      onPointerLeave={() => setHover(false)}
      className="flex h-10 items-center gap-1.5 rounded-full py-1 pr-3 pl-1 transition-colors hover:bg-[var(--hover)]"
      aria-label={isDark ? "切换为浅色" : "切换为深色"}
      title={isDark ? "切换为浅色" : "切换为深色"}
    >
      <LingduHead size={32} expression={expression} />
      <span style={{ color: "var(--ink-2)" }}>{isDark ? <IconMoon size={16} /> : <IconSun size={16} />}</span>
    </button>
  );
}

const NAV = [
  { href: "/#highlights", label: "亮点" },
  { href: "/changelog", label: "更新日志" },
  { href: "/docs", label: "文档" },
  { href: "/#download", label: "下载" },
];

export function SiteNav() {
  const [scrolled, setScrolled] = useState(false);
  useEffect(() => {
    const on = () => setScrolled(window.scrollY > 8);
    on();
    window.addEventListener("scroll", on, { passive: true });
    return () => window.removeEventListener("scroll", on);
  }, []);
  return (
    <header
      className="sticky top-0 z-50 transition-[background-color,border-color] duration-300"
      style={{
        background: scrolled ? "var(--nav-surface)" : "transparent",
        backdropFilter: scrolled ? "blur(16px) saturate(1.4)" : undefined,
        WebkitBackdropFilter: scrolled ? "blur(16px) saturate(1.4)" : undefined,
        borderBottom: `0.5px solid ${scrolled ? "var(--hairline)" : "transparent"}`,
      }}
    >
      <div className="mx-auto flex h-16 max-w-[1200px] items-center gap-6 px-6">
        <a href="/" className="flex items-center gap-2" aria-label="LinDo 首页">
          <LingduHead size={30} />
          <Wordmark size={20} />
        </a>
        <nav className="hidden items-center gap-1 md:flex">
          {NAV.map((n) => (
            <a
              key={n.href}
              href={n.href}
              className="rounded-lg px-3 py-2 text-[14.5px] transition-colors hover:bg-[var(--hover)]"
              style={{ color: "var(--ink-2)" }}
            >
              {n.label}
            </a>
          ))}
        </nav>
        <div className="ml-auto flex items-center gap-1">
          <a
            href={SITE.repo}
            target="_blank"
            rel="noreferrer"
            className="grid h-10 w-10 place-items-center rounded-full transition-colors hover:bg-[var(--hover)]"
            aria-label="GitHub"
          >
            <GithubLogo size={20} />
          </a>
          <MascotThemeToggle />
          <a href="/#download" className="btn-primary ml-2 hidden !h-9 !px-4 !text-[14px] sm:inline-flex">
            下载
          </a>
        </div>
      </div>
    </header>
  );
}
