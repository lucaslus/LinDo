"use client";

import { useLayoutEffect, useRef, useState, type CSSProperties, type ReactNode } from "react";
import clsx from "clsx";

/** 以固定设计尺寸渲染内容，按容器宽度等比缩放（让 App 渲染图在任何屏幕上保持原始比例） */
export function Scaled({
  width,
  height,
  children,
  className,
  style,
  maxScale = 1,
}: {
  width: number;
  height: number;
  children: ReactNode;
  className?: string;
  style?: CSSProperties;
  maxScale?: number;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const [scale, setScale] = useState<number | null>(null);

  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const update = () => setScale(Math.min(maxScale, el.clientWidth / width));
    update();
    const ro = new ResizeObserver(update);
    ro.observe(el);
    return () => ro.disconnect();
  }, [width, maxScale]);

  return (
    <div
      ref={ref}
      className={clsx("relative", className)}
      style={{ aspectRatio: `${width} / ${height}`, maxWidth: width * maxScale, ...style }}
    >
      <div
        style={{
          position: "absolute",
          left: 0,
          top: 0,
          width,
          height,
          transformOrigin: "0 0",
          transform: `scale(${scale ?? 0.0001})`,
          opacity: scale === null ? 0 : 1,
        }}
      >
        {children}
      </div>
    </div>
  );
}

export const PHONE_W = 390;
export const PHONE_H = 820;

export function PhoneDevice({ children, className, screenRadius, bare }: { children: ReactNode; className?: string; screenRadius?: number; bare?: boolean }) {
  return (
    <Scaled width={PHONE_W} height={PHONE_H} className={className}>
      <div className="device-phone h-full w-full">
        <div className="device-screen" style={screenRadius ? { borderRadius: screenRadius } : undefined}>
          {children}
          {!bare && (
            <>
          {/* 打孔摄像头 */}
          <span className="absolute top-[13px] left-1/2 h-[12px] w-[12px] -translate-x-1/2 rounded-full" style={{ background: "#07080a" }} />
          {/* 手势条 */}
          <span
            className="absolute bottom-[6px] left-1/2 h-[4px] w-[120px] -translate-x-1/2 rounded-full"
            style={{ background: "var(--ink-1)", opacity: 0.85 }}
          />
            </>
          )}
        </div>
      </div>
    </Scaled>
  );
}

export type WindowPlatform = "mac" | "win";

export function WindowChrome({ platform, title }: { platform: WindowPlatform; title: string }) {
  return (
    <div
      className="flex h-[38px] shrink-0 items-center px-3.5"
      style={{ background: "var(--window-chrome)", borderBottom: "0.5px solid var(--hairline)" }}
    >
      {platform === "mac" ? (
        <span className="flex gap-2">
          <span className="h-3 w-3 rounded-full" style={{ background: "#ff5f57" }} />
          <span className="h-3 w-3 rounded-full" style={{ background: "#febc2e" }} />
          <span className="h-3 w-3 rounded-full" style={{ background: "#28c840" }} />
        </span>
      ) : (
        <span className="text-[12px]" style={{ color: "var(--ink-2)" }}>
          {title}
        </span>
      )}
      {platform === "mac" && (
        <span className="flex-1 text-center text-[12.5px] font-medium" style={{ color: "var(--ink-2)" }}>
          {title}
        </span>
      )}
      {platform === "win" ? (
        <span className="ml-auto flex h-full items-stretch" style={{ color: "var(--ink-2)" }}>
          {["min", "max", "close"].map((k) => (
            <span key={k} className="grid w-[44px] place-items-center">
              {k === "min" && <span className="h-px w-[10px]" style={{ background: "currentColor" }} />}
              {k === "max" && <span className="h-[10px] w-[10px] rounded-[1px] border" style={{ borderColor: "currentColor" }} />}
              {k === "close" && (
                <svg width="10" height="10" viewBox="0 0 10 10" stroke="currentColor" strokeWidth="1">
                  <path d="M0 0L10 10M10 0L0 10" />
                </svg>
              )}
            </span>
          ))}
        </span>
      ) : (
        <span className="w-[52px]" />
      )}
    </div>
  );
}
