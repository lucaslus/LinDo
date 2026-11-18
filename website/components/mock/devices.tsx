"use client";

import { useEffect, useRef, type CSSProperties, type ReactNode } from "react";
import clsx from "clsx";

/**
 * 响应式等比舞台容器：
 * - 首帧通过 CSS 媒体查询预定精确宽高比（桌面 1200×740、窄屏 640×740），0 CLS 布局跳动、0 DOM 重挂载；
 * - 现代浏览器利用 CSS Container Query (`100cqw`) 在 SSR 首帧直接完成缩放，不等待 JS 水合；
 * - 同时提供 ResizeObserver 写入 `--stage-scale` 兜底旧内核。
 */
export function StageViewport({
  children,
  className,
  style,
}: {
  children: ReactNode;
  className?: string;
  style?: CSSProperties;
}) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const update = () => {
      const compact = window.innerWidth <= 767;
      const baseW = compact ? 640 : 1200;
      const s = Math.min(1, el.clientWidth / baseW);
      el.style.setProperty("--stage-scale", s.toFixed(5));
    };
    update();
    const ro = new ResizeObserver(update);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  return (
    <div ref={ref} className={clsx("dv-viewport", className)} style={style}>
      <div className="dv-canvas">{children}</div>
    </div>
  );
}
