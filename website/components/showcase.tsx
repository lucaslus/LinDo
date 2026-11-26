"use client";

import { useId, useState, type KeyboardEvent } from "react";
import { IconMoon, IconSun } from "@/components/icons";
import { originFromEvent, useThemeSwitch } from "@/components/theme";
import { APP_SCREENSHOTS, ThemeScreenshot, type ScreenshotKey } from "@/components/theme-screenshot";

const SCREENS = [
  { id: "desktop", label: "macOS", caption: "macOS 桌面端", shots: ["desktop"] },
  { id: "phone", label: "Android", caption: "Android 手机端", shots: ["phone"] },
  { id: "fold", label: "折叠屏", caption: "折叠屏内外屏", shots: ["foldCover", "foldOpen"] },
  { id: "icon", label: "启动图标", caption: "Android 启动图标", shots: ["icon"] },
] as const;

export function DeviceShowcase({ available }: { available: ScreenshotKey[] }) {
  const id = useId();
  const [active, setActive] = useState(0);
  const { isDark, mounted, set } = useThemeSwitch();
  const screens = SCREENS.filter(({ shots }) => shots.every((shot) => available.includes(shot)));
  const screen = screens[active];
  if (!screen) return null;

  function onTabKey(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    let next: number;
    switch (event.key) {
      case "ArrowLeft": next = (index + screens.length - 1) % screens.length; break;
      case "ArrowRight": next = (index + 1) % screens.length; break;
      case "Home": next = 0; break;
      case "End": next = screens.length - 1; break;
      default: return;
    }
    event.preventDefault();
    setActive(next);
    document.getElementById(`${id}-tab-${next}`)?.focus();
  }

  return (
    <div id="screens" className="hero-stage">
      {screens.length > 1 && (
        <div className="stage-controls">
          <div className="shot-tabs" role="tablist" aria-label="界面预览">
            {screens.map(({ id: key, label }, index) => (
              <button
                key={key}
                id={`${id}-tab-${index}`}
                type="button"
                role="tab"
                aria-controls={`${id}-panel-${index}`}
                aria-selected={index === active}
                tabIndex={index === active ? 0 : -1}
                onClick={() => setActive(index)}
                onKeyDown={(event) => onTabKey(event, index)}
              >
                {label}
              </button>
            ))}
          </div>
        </div>
      )}

      {screens.map(({ id: key }, index) => (
        <div
          key={key}
          id={`${id}-panel-${index}`}
          role={screens.length > 1 ? "tabpanel" : undefined}
          aria-labelledby={screens.length > 1 ? `${id}-tab-${index}` : undefined}
          hidden={index !== active}
        >
          {index === active && (
            <div className={`shot-stage shot-stage-${screen.id}`}>
              {screen.shots.map((shot) => (
                <ThemeScreenshot
                  key={shot}
                  shot={APP_SCREENSHOTS[shot]}
                  link
                  priority={shot === "desktop" && index === 0}
                />
              ))}
            </div>
          )}
        </div>
      ))}

      <div className="stage-meta">
        <span>{screen.caption} · 随站点主题切换</span>
        <div className="stage-theme-switch" role="group" aria-label="切换预览主题">
          <button
            type="button"
            className="stage-theme-btn stage-theme-light"
            aria-pressed={mounted ? !isDark : undefined}
            onClick={(e) => set("light", originFromEvent(e))}
          >
            <IconSun size={13} />
            浅色
          </button>
          <button
            type="button"
            className="stage-theme-btn stage-theme-dark"
            aria-pressed={mounted ? isDark : undefined}
            onClick={(e) => set("dark", originFromEvent(e))}
          >
            <IconMoon size={13} />
            深色
          </button>
        </div>
      </div>
    </div>
  );
}
