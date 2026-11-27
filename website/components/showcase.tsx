"use client";

import { useRef, useState } from "react";
import { IconMoon, IconSun } from "@/components/icons";
import { originFromEvent, useThemeSwitch } from "@/components/theme";
import { APP_SCREENSHOTS, ThemeScreenshot, type ScreenshotKey } from "@/components/theme-screenshot";

const DEVICES = [
  { id: "desktop", label: "桌面端" },
  { id: "phone", label: "手机" },
  { id: "fold", label: "折叠屏" },
  { id: "tablet", label: "平板" },
] as const satisfies ReadonlyArray<{ id: ScreenshotKey; label: string }>;

export function DeviceShowcase() {
  const { isDark, mounted, set } = useThemeSwitch();
  const [active, setActive] = useState<ScreenshotKey>("desktop");
  const dialog = useRef<HTMLDialogElement>(null);

  return (
    <div id="screens" className="hero-stage">
      <div className="device-family-viewport">
        <ul className="device-family-row" aria-label="各端界面">
          {DEVICES.map(({ id, label }) => {
            const shot = APP_SCREENSHOTS[id];
            return (
              <li key={id} style={{ flexGrow: shot.width / shot.height }}>
                <button
                  type="button"
                  className="device-shot-trigger"
                  aria-label={`放大${label}截图`}
                  aria-haspopup="dialog"
                  onClick={() => { setActive(id); dialog.current?.showModal(); }}
                >
                  <ThemeScreenshot shot={shot} />
                </button>
              </li>
            );
          })}
        </ul>
      </div>

      <div className="stage-meta">
        <div className="stage-theme-switch" role="group" aria-label="切换预览主题">
          <button type="button" className="stage-theme-btn stage-theme-light"
            aria-pressed={mounted ? !isDark : undefined} onClick={(e) => set("light", originFromEvent(e))}>
            <IconSun size={13} />浅色
          </button>
          <button type="button" className="stage-theme-btn stage-theme-dark"
            aria-pressed={mounted ? isDark : undefined} onClick={(e) => set("dark", originFromEvent(e))}>
            <IconMoon size={13} />深色
          </button>
        </div>
      </div>

      <dialog ref={dialog} className="shot-lightbox" aria-label="界面大图"
        onClick={(e) => { if (e.target === e.currentTarget) dialog.current?.close(); }}>
        <div className="shot-lightbox-inner">
          <div className="shot-lightbox-bar">
            <div className="shot-lightbox-tabs" role="group" aria-label="选择设备">
              {DEVICES.map(({ id, label }) => (
                <button key={id} type="button" aria-pressed={active === id} onClick={() => setActive(id)}>
                  {label}
                </button>
              ))}
            </div>
            <button type="button" className="shot-lightbox-close" onClick={() => dialog.current?.close()}>关闭</button>
          </div>
          <div className="shot-lightbox-frame"><ThemeScreenshot shot={APP_SCREENSHOTS[active]} /></div>
        </div>
      </dialog>
    </div>
  );
}
