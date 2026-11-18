"use client";

import { useState } from "react";
import clsx from "clsx";

/** 零度娘头像，支持叠加四种表情（与 App 启动器同一组路径） */
export type Expression = "none" | "happy" | "sleepy" | "surprised" | "wink";

export function LingduHead({ size = 32, expression = "none", className }: { size?: number; expression?: Expression; className?: string }) {
  return (
    <span className={clsx("relative inline-block shrink-0", className)} style={{ width: size, height: size }}>
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src="/brand/lindo-mark.svg" alt="" width={size} height={size} className="absolute inset-0 h-full w-full" draggable={false} />
      {(["happy", "sleepy", "surprised", "wink"] as const).map((e) => (
        // eslint-disable-next-line @next/next/no-img-element
        <img
          key={e}
          src={`/brand/expressions/${e}.svg`}
          alt=""
          className="absolute inset-0 h-full w-full transition-opacity duration-200"
          style={{ opacity: expression === e ? 1 : 0 }}
          draggable={false}
        />
      ))}
    </span>
  );
}

export function Wordmark({ size = 20, className }: { size?: number; className?: string }) {
  return (
    <span className={clsx("font-semibold tracking-tight", className)} style={{ fontSize: size, lineHeight: 1 }}>
      Lin<span style={{ color: "var(--logo-do)" }}>Do</span>
    </span>
  );
}

/** 可点击切换表情的零度娘：悬停眨眼，按下惊讶 */
export function InteractiveHead({ size, base }: { size: number; base: Expression }) {
  const [hover, setHover] = useState(false);
  const [down, setDown] = useState(false);
  const expression: Expression = down ? "surprised" : hover ? "wink" : base;
  return (
    <span
      onPointerEnter={() => setHover(true)}
      onPointerLeave={() => {
        setHover(false);
        setDown(false);
      }}
      onPointerDown={() => setDown(true)}
      onPointerUp={() => setDown(false)}
    >
      <LingduHead size={size} expression={expression} />
    </span>
  );
}
