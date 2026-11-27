"use client";

import { assetPath } from "@/lib/site";

import clsx from "clsx";

/**
 * 零度娘头像，支持叠加四种表情（与 App 启动器同一组路径）。
 * `expression="theme"` 时由 CSS `.dark` 在首帧直接决定开心/犯困，彻底消除深色水合闪烁。
 */
export type Expression = "none" | "happy" | "sleepy" | "surprised" | "wink" | "theme";

const ALL_EXPRESSIONS = ["happy", "sleepy", "surprised", "wink"] as const;

export function LingduHead({
  size = 32,
  expression = "none",
  preloadAll = false,
  className,
}: {
  size?: number;
  expression?: Expression;
  preloadAll?: boolean;
  className?: string;
}) {
  return (
    <span className={clsx("relative inline-block shrink-0", className)} style={{ width: size, height: size }}>
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img
        src={assetPath("/brand/lindo-mark.svg")}
        alt=""
        width={size}
        height={size}
        className="absolute inset-0 h-full w-full"
        draggable={false}
      />
      {preloadAll ? (
        ALL_EXPRESSIONS.map((e) => {
          const isThemeDriven = expression === "theme" && (e === "happy" || e === "sleepy");
          return (
            // eslint-disable-next-line @next/next/no-img-element
            <img
              key={e}
              src={assetPath(`/brand/expressions/${e}.svg`)}
              alt=""
              width={size}
              height={size}
              className={clsx(
                "absolute inset-0 h-full w-full transition-opacity duration-200",
                isThemeDriven && e === "happy" && "opacity-100 dark:opacity-0",
                isThemeDriven && e === "sleepy" && "opacity-0 dark:opacity-100",
                !isThemeDriven && (expression === e ? "opacity-100" : "opacity-0"),
              )}
              draggable={false}
            />
          );
        })
      ) : expression !== "none" && expression !== "theme" ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img
          src={assetPath(`/brand/expressions/${expression}.svg`)}
          alt=""
          width={size}
          height={size}
          className="absolute inset-0 h-full w-full"
          draggable={false}
        />
      ) : null}
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
