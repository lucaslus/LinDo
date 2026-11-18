"use client";

import { useCallback, useEffect, useRef, useState, type CSSProperties } from "react";
import clsx from "clsx";
import { IconDeviceDesktop, IconDeviceFold, IconDevicePhone, IconDeviceTablet } from "@/components/icons";
import { StageViewport } from "@/components/mock/devices";

export type DeviceKey = "phone" | "fold" | "tablet" | "desktop";

const DEVICES: { key: DeviceKey; label: string }[] = [
  { key: "phone", label: "手机" },
  { key: "fold", label: "折叠屏" },
  { key: "tablet", label: "平板" },
  { key: "desktop", label: "电脑" },
];

const SHOT_META: Record<string, { w: number; h: number }> = {
  phone: { w: 750, h: 1625 },
  "fold-cover": { w: 800, h: 1298 },
  fold: { w: 1560, h: 1258 },
  tablet: { w: 1760, h: 1100 },
  desktop: { w: 1880, h: 1245 },
};

const OTHER_SHOT_NAMES = ["fold-cover", "fold", "tablet", "desktop"] as const;

function DeviceIcon({ k }: { k: DeviceKey }) {
  if (k === "phone") return <IconDevicePhone size={17} />;
  if (k === "fold") return <IconDeviceFold size={17} />;
  if (k === "tablet") return <IconDeviceTablet size={17} />;
  return <IconDeviceDesktop size={17} />;
}

/** 成对的真实运行截图：首帧仅高优加载当前手机图，其余截图空闲分级预热，随官网深浅色同步交叉淡入 */
function Shot({
  name,
  alt,
  priority = false,
  className,
  style,
}: {
  name: keyof typeof SHOT_META;
  alt: string;
  priority?: boolean;
  className?: string;
  style?: CSSProperties;
}) {
  const { w, h } = SHOT_META[name];
  return (
    <span className={clsx("shot", className)} style={style}>
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img
        src={`/shots/${name}-light.webp`}
        alt={alt ? `${alt}（浅色）` : ""}
        width={w}
        height={h}
        loading={priority ? "eager" : "lazy"}
        fetchPriority={priority ? "high" : "auto"}
        className="shot-light"
        draggable={false}
        decoding="async"
      />
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img
        src={`/shots/${name}-dark.webp`}
        alt={alt ? `${alt}（深色）` : ""}
        width={w}
        height={h}
        loading={priority ? "eager" : "lazy"}
        fetchPriority="auto"
        className="shot-dark"
        draggable={false}
        decoding="async"
      />
    </span>
  );
}

/* ───────── 四类硬件外框（桌面基准 1200×740，窄屏基准 640×740） ───────── */

function PhoneFrame() {
  return (
    <div className="dv-phone">
      <div className="dv-phone-screen">
        <Shot name="phone" alt="LinDo Android 手机端" priority />
      </div>
    </div>
  );
}

function FoldFrame() {
  return (
    <div className="dv-fold">
      <div className="dv-fold-body">
        <div className="dv-fold-half dv-fold-right">
          <div className="dv-fold-screen">
            <Shot name="fold" alt="LinDo 折叠屏内屏分栏" className="shot-right" />
          </div>
          <span className="dv-fold-shade-r" aria-hidden />
        </div>

        <div className="dv-fold-half dv-fold-left">
          <div className="dv-fold-face dv-fold-front">
            <div className="dv-fold-screen">
              <Shot name="fold" alt="" className="shot-left" />
            </div>
            <span className="dv-fold-shade-l" aria-hidden />
          </div>
          <div className="dv-fold-face dv-fold-back">
            <div className="dv-fold-cover">
              <Shot name="fold-cover" alt="LinDo 折叠屏外屏" />
            </div>
          </div>
        </div>
      </div>
      <div className="dv-fold-full" aria-hidden>
        <div className="dv-fold-full-screen">
          <Shot name="fold" alt="" />
        </div>
      </div>
    </div>
  );
}

function TabletFrame() {
  return (
    <div className="dv-tablet">
      <div className="dv-tablet-screen">
        <Shot name="tablet" alt="LinDo Android 平板端" />
      </div>
    </div>
  );
}

function LaptopFrame() {
  return (
    <div className="dv-laptop">
      <div className="dv-laptop-lid">
        <div className="dv-laptop-screen">
          <Shot name="desktop" alt="LinDo 桌面端" />
        </div>
        <span className="dv-laptop-notch" />
      </div>
      <div className="dv-laptop-base">
        <span />
      </div>
    </div>
  );
}

/* ───────── 自动轮播展示区（手机 → 折叠屏 → 平板 → 电脑） ───────── */

const SLIDE_DURATION_MS: Record<DeviceKey, number> = {
  phone: 4200,
  fold: 4800,
  tablet: 4200,
  desktop: 4400,
};

export function DeviceShowcase({ devices = ["phone", "fold", "tablet", "desktop"] }: { devices?: DeviceKey[] }) {
  const LIST = DEVICES.filter((d) => devices.includes(d.key));
  const [active, setActive] = useState<DeviceKey>("phone");
  const [cycleNonce, setCycleNonce] = useState(0);
  const [paused, setPaused] = useState(false);
  const [docHidden, setDocHidden] = useState(false);
  const [inView, setInView] = useState(true);
  const [reduceMotion, setReduceMotion] = useState(false);
  const tabsRef = useRef<HTMLDivElement>(null);
  const [ind, setInd] = useState<{ x: number; w: number } | null>(null);
  const stageRef = useRef<HTMLDivElement>(null);
  const swipeStart = useRef<{ x: number; y: number } | null>(null);

  // 首屏稳定后按优先级在空闲时段顺序预解码其余截图，避免抢占 LCP 带宽
  useEffect(() => {
    let cancelled = false;
    const warmQueue = async () => {
      const dark = document.documentElement.classList.contains("dark");
      const primarySuffix = dark ? "dark" : "light";
      const secondarySuffix = dark ? "light" : "dark";
      const orderedUrls = [
        ...OTHER_SHOT_NAMES.map((n) => `/shots/${n}-${primarySuffix}.webp`),
        `/shots/phone-${secondarySuffix}.webp`,
        ...OTHER_SHOT_NAMES.map((n) => `/shots/${n}-${secondarySuffix}.webp`),
      ];
      for (const src of orderedUrls) {
        if (cancelled) return;
        await new Promise<void>((resolve) => {
          const img = new Image();
          img.decoding = "async";
          img.src = src;
          if (img.decode) {
            img.decode().then(() => resolve(), () => resolve());
          } else {
            img.onload = () => resolve();
            img.onerror = () => resolve();
          }
        });
      }
    };

    const ric = (window as Window & { requestIdleCallback?: (cb: () => void, opts?: { timeout: number }) => number })
      .requestIdleCallback;
    const cic = (window as Window & { cancelIdleCallback?: (id: number) => void }).cancelIdleCallback;

    let timerId: number | undefined;
    let idleId: number | undefined;

    const schedule = () => {
      if (ric) {
        idleId = ric(() => void warmQueue(), { timeout: 1500 });
      } else {
        timerId = window.setTimeout(() => void warmQueue(), 450);
      }
    };

    if (document.readyState === "complete") {
      schedule();
    } else {
      window.addEventListener("load", schedule, { once: true });
    }

    return () => {
      cancelled = true;
      window.removeEventListener("load", schedule);
      if (timerId !== undefined) window.clearTimeout(timerId);
      if (idleId !== undefined && cic) cic(idleId);
    };
  }, []);

  useEffect(() => {
    const mq = window.matchMedia("(prefers-reduced-motion: reduce)");
    const onMotion = () => setReduceMotion(mq.matches);
    onMotion();
    mq.addEventListener("change", onMotion);
    const onVis = () => setDocHidden(document.hidden);
    onVis();
    document.addEventListener("visibilitychange", onVis);
    return () => {
      mq.removeEventListener("change", onMotion);
      document.removeEventListener("visibilitychange", onVis);
    };
  }, []);

  const select = useCallback((key: DeviceKey) => {
    setActive(key);
    setCycleNonce((n) => n + 1);
  }, []);

  const stepBy = useCallback(
    (delta: number) => {
      if (LIST.length <= 1) return;
      const i = LIST.findIndex((d) => d.key === active);
      select(LIST[(i + delta + LIST.length) % LIST.length].key);
    },
    [LIST, active, select],
  );

  const durationMs = SLIDE_DURATION_MS[active] ?? 4200;
  const isPlaying = !paused && !docHidden && inView && !reduceMotion && LIST.length > 1;

  useEffect(() => {
    if (!isPlaying) return;
    const t = window.setTimeout(() => {
      stepBy(1);
    }, durationMs);
    return () => window.clearTimeout(t);
  }, [active, cycleNonce, durationMs, isPlaying, stepBy]);

  useEffect(() => {
    const el = stageRef.current;
    if (!el) return;
    const io = new IntersectionObserver(([e]) => setInView(e.isIntersecting), { threshold: 0.25 });
    io.observe(el);
    return () => io.disconnect();
  }, []);

  useEffect(() => {
    const tabsEl = tabsRef.current;
    if (!tabsEl) return;
    const update = () => {
      const el = tabsEl.querySelector<HTMLElement>(`[data-key="${active}"]`);
      if (el) setInd({ x: el.offsetLeft, w: el.offsetWidth });
    };
    update();
    const ro = new ResizeObserver(update);
    ro.observe(tabsEl);
    return () => ro.disconnect();
  }, [active]);

  return (
    <div className="relative">
      <div className="flex justify-center">
        <div
          ref={tabsRef}
          role="tablist"
          aria-label="选择终端形态"
          className="dv-tabs"
          onPointerEnter={() => setPaused(true)}
          onPointerLeave={() => setPaused(false)}
          onKeyDown={(e) => {
            if (e.key === "ArrowRight") {
              e.preventDefault();
              stepBy(1);
            }
            if (e.key === "ArrowLeft") {
              e.preventDefault();
              stepBy(-1);
            }
          }}
        >
          {ind && <span className="dv-tabs-ind" style={{ transform: `translate3d(${ind.x}px, 0, 0)`, width: ind.w }} />}
          {LIST.map(({ key, label }) => {
            const isSelected = active === key;
            return (
              <button
                key={key}
                id={`dv-tab-${key}`}
                data-key={key}
                type="button"
                role="tab"
                aria-selected={isSelected}
                aria-controls={`dv-panel-${key}`}
                tabIndex={isSelected ? 0 : -1}
                onClick={() => select(key)}
                className="dv-tab"
              >
                <DeviceIcon k={key} />
                <span>{label}</span>
                {isSelected && !reduceMotion && LIST.length > 1 && (
                  <span
                    key={`${key}-${cycleNonce}`}
                    aria-hidden
                    className="dv-tab-progress"
                    style={{
                      animationDuration: `${durationMs}ms`,
                      animationPlayState: isPlaying ? "running" : "paused",
                    }}
                  />
                )}
              </button>
            );
          })}
        </div>
      </div>

      <div
        ref={stageRef}
        className="relative mx-auto mt-8 w-full max-w-[1200px] touch-pan-y select-none sm:mt-10"
        onPointerEnter={() => setPaused(true)}
        onPointerLeave={() => setPaused(false)}
        onPointerDown={(e) => {
          swipeStart.current = { x: e.clientX, y: e.clientY };
        }}
        onPointerUp={(e) => {
          const start = swipeStart.current;
          swipeStart.current = null;
          if (!start) return;
          const dx = e.clientX - start.x;
          const dy = e.clientY - start.y;
          if (Math.abs(dx) > 42 && Math.abs(dx) > Math.abs(dy) * 1.25) {
            stepBy(dx < 0 ? 1 : -1);
          }
        }}
        onPointerCancel={() => {
          swipeStart.current = null;
        }}
      >
        <div aria-hidden className="dv-glow" />
        <StageViewport>
          <div className="dv-stage">
            {LIST.map(({ key }) => (
              <div
                key={key}
                id={`dv-panel-${key}`}
                role="tabpanel"
                aria-labelledby={`dv-tab-${key}`}
                aria-hidden={active !== key}
                className={clsx("dv-slot", `dv-slot-${key}`, active === key && "is-active")}
              >
                {key === "phone" && <PhoneFrame />}
                {key === "fold" && <FoldFrame />}
                {key === "tablet" && <TabletFrame />}
                {key === "desktop" && <LaptopFrame />}
              </div>
            ))}
            <div aria-hidden className="dv-shadow" data-device={active} />
          </div>
        </StageViewport>
        <div aria-hidden className={clsx("dv-mascot-wrap", `is-${active}`)}>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            src="/brand/lingdu-welcome.webp"
            alt=""
            width={480}
            height={384}
            className="dv-mascot"
            draggable={false}
            decoding="async"
          />
        </div>
      </div>
    </div>
  );
}
