"use client";

import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { flushSync } from "react-dom";
import { useTheme } from "next-themes";
import type { AccentKey } from "@/lib/site";

const ACCENT_STORAGE = "lindo-accent";

type AccentCtx = { accent: AccentKey; setAccent: (a: AccentKey) => void };
const AccentContext = createContext<AccentCtx>({ accent: "gold", setAccent: () => {} });

/** 官网固定使用品牌金：清掉早期版本留在本地的主题色设置 */
export function AccentProvider({ children }: { children: ReactNode }) {
  useEffect(() => {
    try {
      localStorage.removeItem(ACCENT_STORAGE);
    } catch {}
    delete document.documentElement.dataset.accent;
  }, []);
  return <AccentContext.Provider value={{ accent: "gold", setAccent: () => {} }}>{children}</AccentContext.Provider>;
}

export const useAccent = () => useContext(AccentContext);


type Origin = { x: number; y: number } | null;

/**
 * 黑白主题切换：官网与所有 App 渲染图共用同一套 CSS 变量，
 * 因此一次切换会同时改变页面、手机、折叠屏和桌面窗口。
 * 支持 View Transitions 时从点击位置圆形展开。
 */
export function useThemeSwitch() {
  const { theme, resolvedTheme, setTheme } = useTheme();
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);

  const isDark = mounted ? resolvedTheme === "dark" : false;
  const mode = (mounted ? theme : "system") as "light" | "dark" | "system";

  const set = useCallback(
    (nextMode: "light" | "dark" | "system", origin: Origin = null) => {
      const systemDark = window.matchMedia("(prefers-color-scheme: dark)").matches;
      const next = nextMode === "system" ? (systemDark ? "dark" : "light") : nextMode;
      const root = document.documentElement;
      const apply = () => {
        root.classList.toggle("dark", next === "dark");
        root.style.colorScheme = next;
        flushSync(() => setTheme(nextMode));
      };
      if (next === resolvedTheme) {
        apply();
        return;
      }

      const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
      const doc = document as Document & { startViewTransition?: (cb: () => void) => { ready: Promise<void> } };

      if (!doc.startViewTransition || reduce) {
        if (!reduce) {
          root.classList.add("theme-fade");
          window.setTimeout(() => root.classList.remove("theme-fade"), 420);
        }
        apply();
        return;
      }

      const x = origin?.x ?? window.innerWidth - 40;
      const y = origin?.y ?? 32;
      const r = Math.hypot(Math.max(x, window.innerWidth - x), Math.max(y, window.innerHeight - y));
      const vt = doc.startViewTransition(apply);
      vt.ready.then(() => {
        root.animate(
          { clipPath: [`circle(0px at ${x}px ${y}px)`, `circle(${r}px at ${x}px ${y}px)`] },
          { duration: 620, easing: "cubic-bezier(0.65, 0, 0.35, 1)", pseudoElement: "::view-transition-new(root)" },
        );
      });
    },
    [resolvedTheme, setTheme],
  );

  const toggle = useCallback(
    (origin: Origin = null) => set(resolvedTheme === "dark" ? "light" : "dark", origin),
    [resolvedTheme, set],
  );

  return { isDark, mounted, mode, set, toggle };
}

export function originFromEvent(e: { clientX: number; clientY: number; currentTarget: EventTarget }) {
  const el = e.currentTarget as HTMLElement;
  if (e.clientX === 0 && e.clientY === 0 && el?.getBoundingClientRect) {
    const r = el.getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  }
  return { x: e.clientX, y: e.clientY };
}
