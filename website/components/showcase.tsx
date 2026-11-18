"use client";

import { useCallback, useEffect, useRef, useState, type CSSProperties } from "react";
import clsx from "clsx";
import { IconDeviceDesktop, IconDeviceFold, IconDevicePhone, IconDeviceTablet } from "@/components/icons";
import { Scaled } from "@/components/mock/devices";

export type DeviceKey = "phone" | "fold" | "tablet" | "desktop";

const DEVICES: { key: DeviceKey; label: string }[] = [
  { key: "phone", label: "手机" },
  { key: "fold", label: "折叠屏" },
  { key: "tablet", label: "平板" },
  { key: "desktop", label: "电脑" },
];

function DeviceIcon({ k }: { k: DeviceKey }) {
  if (k === "phone") return <IconDevicePhone size={17} />;
  if (k === "fold") return <IconDeviceFold size={17} />;
  if (k === "tablet") return <IconDeviceTablet size={17} />;
  return <IconDeviceDesktop size={17} />;
}

/** 成对的真实运行截图：常驻解码，随官网深浅色同步交叉淡入 */
function Shot({ name, alt, className, style }: { name: string; alt: string; className?: string; style?: CSSProperties }) {
  return (
    <span className={clsx("shot", className)} style={style}>
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src={`/shots/${name}-light.webp`} alt={alt ? `${alt}（浅色）` : ""} className="shot-light" draggable={false} decoding="sync" />
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src={`/shots/${name}-dark.webp`} alt={alt ? `${alt}（深色）` : ""} className="shot-dark" draggable={false} decoding="sync" />
    </span>
  );
}

/* ───────── 舞台与四类硬件外框（设计基准 1200 × 740） ───────── */

const STAGE_H = 740;

function useCompact() {
  const [compact, setCompact] = useState(false);
  useEffect(() => {
    const mq = window.matchMedia("(max-width: 767px)");
    const on = () => setCompact(mq.matches);
    on();
    mq.addEventListener("change", on);
    return () => mq.removeEventListener("change", on);
  }, []);
  return compact;
}

function PhoneFrame() {
  return (
    <div className="dv-phone">
      <div className="dv-phone-screen">
        <Shot name="phone" alt="LinDo Android 手机端" />
      </div>
    </div>
  );
}

/**
 * 折叠屏（阔折叠横向内屏）：
 * 初始合拢居中显示外屏，随后左翼屏幕绕中轴从前方（+Z）向左侧自然缓慢展开，
 * 落平后切换为整块无缝单层内屏。
 */
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

/* ───────── 展示区（克制冷静，与 App 交互气质一致） ───────── */

const AUTOPLAY_MS = 7600;

export function DeviceShowcase({ devices = ["phone", "fold", "tablet", "desktop"] }: { devices?: DeviceKey[] }) {
  const LIST = DEVICES.filter((d) => devices.includes(d.key));
  const [active, setActive] = useState<DeviceKey>("phone");
  const [autoplay, setAutoplay] = useState(true);
  const [paused, setPaused] = useState(false);
  const [inView, setInView] = useState(true);
  const tabsRef = useRef<HTMLDivElement>(null);
  const [ind, setInd] = useState<{ x: number; w: number } | null>(null);
  const stageRef = useRef<HTMLDivElement>(null);
  const compact = useCompact();
  const STAGE_W = compact ? 640 : 1200;

  const select = useCallback((key: DeviceKey, fromUser: boolean) => {
    if (fromUser) setAutoplay(false);
    setActive(key);
  }, []);

  useEffect(() => {
    if (!autoplay || paused || !inView) return;
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    const t = window.setTimeout(() => {
      const i = LIST.findIndex((d) => d.key === active);
      select(LIST[(i + 1) % LIST.length].key, false);
    }, AUTOPLAY_MS);
    return () => window.clearTimeout(t);
  }, [LIST, active, autoplay, paused, inView, select]);

  useEffect(() => {
    const el = stageRef.current;
    if (!el) return;
    const io = new IntersectionObserver(([e]) => setInView(e.isIntersecting), { threshold: 0.35 });
    io.observe(el);
    return () => io.disconnect();
  }, []);

  useEffect(() => {
    const update = () => {
      const el = tabsRef.current?.querySelector<HTMLElement>(`[data-key="${active}"]`);
      if (el) setInd({ x: el.offsetLeft, w: el.offsetWidth });
    };
    update();
    window.addEventListener("resize", update);
    return () => window.removeEventListener("resize", update);
  }, [active]);

  return (
    <div className="relative">
      <div className="flex justify-center">
        <div
          ref={tabsRef}
          role="tablist"
          aria-label="选择终端形态"
          className="dv-tabs"
          onKeyDown={(e) => {
            const i = LIST.findIndex((d) => d.key === active);
            if (e.key === "ArrowRight") select(LIST[(i + 1) % LIST.length].key, true);
            if (e.key === "ArrowLeft") select(LIST[(i + LIST.length - 1) % LIST.length].key, true);
          }}
        >
          {ind && <span className="dv-tabs-ind" style={{ transform: `translateX(${ind.x}px)`, width: ind.w }} />}
          {LIST.map(({ key, label }) => (
            <button
              key={key}
              data-key={key}
              type="button"
              role="tab"
              aria-selected={active === key}
              tabIndex={active === key ? 0 : -1}
              onClick={() => select(key, true)}
              className="dv-tab"
            >
              <DeviceIcon k={key} />
              <span>{label}</span>
            </button>
          ))}
        </div>
      </div>

      <div
        ref={stageRef}
        className="relative mx-auto mt-8 w-full max-w-[1200px] sm:mt-10"
        onPointerEnter={() => setPaused(true)}
        onPointerLeave={() => setPaused(false)}
      >
        <div aria-hidden className="dv-glow" />
        <Scaled key={STAGE_W} width={STAGE_W} height={STAGE_H}>
          <div className={clsx("dv-stage", compact && "is-compact")}>
            {LIST.map(({ key }) => (
              <div
                key={key}
                role="tabpanel"
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
        </Scaled>
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src="/brand/lingdu-welcome.svg" alt="" aria-hidden className={clsx("dv-mascot", `is-${active}`)} draggable={false} />
      </div>
    </div>
  );
}
