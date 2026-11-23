/**
 * High-Refresh-Rate (120Hz / 144Hz / 240Hz) Smooth Mouse Wheel Engine & Scroll Paint Optimizer.
 *
 * Why this exists:
 * 1. On macOS, `WKWebView` natively animates Trackpad momentum (`hasPreciseScrollingDeltas == YES`),
 *    but applies ZERO smooth interpolation to discrete physical mouse wheel notches (`hasPreciseScrollingDeltas == NO`),
 *    causing visible staircase jumps on 144Hz displays compared with Chromium (Chrome / Edge / PC WeChat).
 * 2. Our native AppKit monitor (`macos_webkit_bridge.m`) unlocks `WKWebView` from 60/72fps to full native
 *    display refresh rate (e.g. 144.1fps) and forwards ONLY discrete mouse wheel impulses to `window.__lindoSmoothWheel`,
 *    keeping Trackpad / Magic Mouse 100% on WebKit's native C++ compositor with ZERO `{ passive: false }` DOM wheel listeners.
 */

import { invoke } from "@tauri-apps/api/core";
import { diagLog } from "./diagnostics";

const STRICT_CLICKABLE_SELECTOR = [
  "button:not(:disabled)",
  "a[href]",
  '[role="button"]:not([aria-disabled="true"])',
  "[data-internal-onebox-href]",
  "summary",
  "select:not(:disabled)",
  'input[type="range"]:not(:disabled)',
  'input[type="checkbox"]:not(:disabled)',
  'input[type="radio"]:not(:disabled)',
  ".lindo-interactive:not(:disabled)",
  ".cursor-pointer:not(:disabled)",
  ".lindo-cooked img:not(.emoji):not(.avatar):not(.site-icon)",
  ".lindo-cooked .spoiled",
  ".lindo-cooked span.spoiler",
].join(", ");

const DISABLED_CONTROL_SELECTOR =
  'button:disabled, [aria-disabled="true"], input:disabled, select:disabled';

function isStrictPointerTarget(target: Element | null): boolean {
  if (!target) return false;
  if (target.closest(DISABLED_CONTROL_SELECTOR)) return false;
  return Boolean(target.closest(STRICT_CLICKABLE_SELECTOR));
}

interface AxisState {
  remaining: number;
  velocity: number;
  secondsLeft: number;
  pos: number;
  maxScroll: number;
  lastWritten: number;
  synced: boolean;
}

interface ElementWheelAnim {
  x: AxisState;
  y: AxisState;
}

declare global {
  interface Window {
    __lindoSmoothWheel?: (clientX: number, clientY: number, dx: number, dy: number) => void;
  }
}

const DURATION_SECONDS = 0.165; // ~24 frames at 144Hz — matches Chromium Impulse scroll feel
const MAX_ACCUMULATED_PX = 2800;

function createAxis(): AxisState {
  return {
    remaining: 0,
    velocity: 0,
    secondsLeft: 0,
    pos: 0,
    maxScroll: 0,
    lastWritten: -1,
    synced: false,
  };
}

function resetAxis(a: AxisState) {
  a.remaining = 0;
  a.velocity = 0;
  a.secondsLeft = 0;
  a.synced = false;
}

function canScrollY(el: HTMLElement, dy: number): boolean {
  const maxScroll = el.scrollHeight - el.clientHeight;
  if (maxScroll <= 1) return false;
  if (el.classList.contains("lindo-scroll") || el.tagName === "TEXTAREA") {
    return dy > 0 ? el.scrollTop < maxScroll - 0.5 : el.scrollTop > 0.5;
  }
  const oy = window.getComputedStyle(el).overflowY;
  if (oy !== "auto" && oy !== "scroll" && oy !== "overlay") return false;
  return dy > 0 ? el.scrollTop < maxScroll - 0.5 : el.scrollTop > 0.5;
}

function canScrollX(el: HTMLElement, dx: number): boolean {
  const maxScroll = el.scrollWidth - el.clientWidth;
  if (maxScroll <= 1) return false;
  const ox = window.getComputedStyle(el).overflowX;
  if (ox !== "auto" && ox !== "scroll" && ox !== "overlay") return false;
  return dx > 0 ? el.scrollLeft < maxScroll - 0.5 : el.scrollLeft > 0.5;
}

function isScrollableContainerY(el: HTMLElement): boolean {
  if (el.scrollHeight - el.clientHeight <= 1) return false;
  if (el.classList.contains("lindo-scroll") || el.tagName === "TEXTAREA") return true;
  const oy = window.getComputedStyle(el).overflowY;
  return oy === "auto" || oy === "scroll" || oy === "overlay";
}

function findScrollTarget(
  clientX: number,
  clientY: number,
  dx: number,
  dy: number
): HTMLElement | null {
  const hit = document.elementFromPoint(clientX, clientY) as HTMLElement | null;
  if (!hit) return null;

  const preferVertical = Math.abs(dy) >= Math.abs(dx);
  let fallbackScrollable: HTMLElement | null = null;
  let cur: HTMLElement | null = hit;

  while (cur && cur !== document.body && cur !== document.documentElement) {
    if (preferVertical) {
      if (canScrollY(cur, dy)) return cur;
      if (!fallbackScrollable && isScrollableContainerY(cur)) {
        fallbackScrollable = cur;
      }
    } else if (canScrollX(cur, dx)) {
      return cur;
    }
    cur = cur.parentElement;
  }

  return fallbackScrollable;
}

function addAxisImpulse(axis: AxisState, delta: number): number {
  if (!Number.isFinite(delta) || Math.abs(delta) < 0.01) return 0;
  // Instant reversal if user flicks the wheel in the opposite direction
  if (axis.remaining !== 0 && Math.sign(axis.remaining) !== Math.sign(delta)) {
    resetAxis(axis);
  }
  const starting = Math.abs(axis.remaining) < 0.5;
  axis.remaining = Math.max(
    -MAX_ACCUMULATED_PX,
    Math.min(MAX_ACCUMULATED_PX, axis.remaining + delta)
  );
  axis.secondsLeft = DURATION_SECONDS;

  if (starting) {
    // Immediate first-frame lead eliminates startup latency while interpolating the remaining 86%
    const lead = Math.sign(axis.remaining) * Math.min(Math.abs(axis.remaining) * 0.14, 11);
    axis.remaining -= lead;
    axis.velocity = (axis.remaining * 1.9) / DURATION_SECONDS;
    return lead;
  }

  // Sustain continuous cruise velocity across rapid multi-notch spins without ease-out pulsing
  const targetVel = (axis.remaining * 1.85) / DURATION_SECONDS;
  axis.velocity =
    Math.sign(axis.remaining) * Math.max(Math.abs(axis.velocity) * 0.78, Math.abs(targetVel));
  return 0;
}

function stepAxisHermite(axis: AxisState, dt: number): number {
  if (Math.abs(axis.remaining) <= 0.08 || axis.secondsLeft <= 0.0005) {
    const tail = axis.remaining;
    resetAxis(axis);
    return tail;
  }
  const T = axis.secondsLeft;
  const stepDt = Math.min(dt, T);
  const t = stepDt / T;
  const d = axis.remaining;

  // Monotone Cubic Hermite: smoothly brings current velocity to 0 at finite endpoint T
  const maxV = (3.0 * Math.abs(d)) / T;
  const v = Math.sign(d) * Math.min(Math.abs(axis.velocity), maxV);
  const t2 = t * t;
  const t3 = t2 * t;

  const step = d * (3 * t2 - 2 * t3) + v * T * (t3 - 2 * t2 + t);
  axis.velocity = (d * (6 * t - 6 * t2)) / T + v * (3 * t2 - 4 * t + 1);
  axis.remaining = d - step;
  axis.secondsLeft = T - stepDt;

  if (axis.secondsLeft <= 0.0005 || Math.abs(axis.remaining) <= 0.08) {
    const leftover = axis.remaining;
    resetAxis(axis);
    return step + leftover;
  }
  return step;
}

function syncAxisYBounds(el: HTMLElement, axis: AxisState) {
  const maxTop = Math.max(0, el.scrollHeight - el.clientHeight);
  const actual = el.scrollTop;
  axis.maxScroll = maxTop;
  if (!axis.synced || Math.abs(actual - axis.lastWritten) > 2) {
    axis.pos = actual;
    axis.lastWritten = actual;
    axis.synced = true;
  }
}

function syncAxisXBounds(el: HTMLElement, axis: AxisState) {
  const maxLeft = Math.max(0, el.scrollWidth - el.clientWidth);
  const actual = el.scrollLeft;
  axis.maxScroll = maxLeft;
  if (!axis.synced || Math.abs(actual - axis.lastWritten) > 2) {
    axis.pos = actual;
    axis.lastWritten = actual;
    axis.synced = true;
  }
}

function snapToDevicePixel(value: number): number {
  const dpr = window.devicePixelRatio || 1;
  return dpr > 1 ? Math.round(value * dpr) / dpr : Math.round(value);
}

function applyScrollDeltaY(el: HTMLElement, axis: AxisState, rawDelta: number) {
  if (rawDelta === 0) return;
  if (axis.maxScroll <= 0) {
    resetAxis(axis);
    return;
  }
  const desired = axis.pos + rawDelta;
  const clamped = Math.max(0, Math.min(axis.maxScroll, desired));
  axis.pos = clamped;

  // Hit top or bottom boundary -> clear leftover momentum immediately so reverse wheel responds on tick 1
  if ((rawDelta < 0 && clamped <= 0.5) || (rawDelta > 0 && clamped >= axis.maxScroll - 0.5)) {
    const edge = rawDelta < 0 ? 0 : axis.maxScroll;
    if (edge !== axis.lastWritten) {
      el.scrollTop = edge;
      axis.lastWritten = edge;
    }
    resetAxis(axis);
    return;
  }
  const nextPx = snapToDevicePixel(clamped);
  if (nextPx !== axis.lastWritten) {
    el.scrollTop = nextPx;
    axis.lastWritten = nextPx;
  }
}

function applyScrollDeltaX(el: HTMLElement, axis: AxisState, rawDelta: number) {
  if (rawDelta === 0) return;
  if (axis.maxScroll <= 0) {
    resetAxis(axis);
    return;
  }
  const desired = axis.pos + rawDelta;
  const clamped = Math.max(0, Math.min(axis.maxScroll, desired));
  axis.pos = clamped;

  if ((rawDelta < 0 && clamped <= 0.5) || (rawDelta > 0 && clamped >= axis.maxScroll - 0.5)) {
    const edge = rawDelta < 0 ? 0 : axis.maxScroll;
    if (edge !== axis.lastWritten) {
      el.scrollLeft = edge;
      axis.lastWritten = edge;
    }
    resetAxis(axis);
    return;
  }
  const nextPx = snapToDevicePixel(clamped);
  if (nextPx !== axis.lastWritten) {
    el.scrollLeft = nextPx;
    axis.lastWritten = nextPx;
  }
}

let installed = false;

export function installSmoothWheelAndScrollOptimizer(): void {
  if (installed || typeof window === "undefined") return;
  installed = true;

  const activeAnims = new Map<HTMLElement, ElementWheelAnim>();
  let rafId = 0;
  let lastTs = 0;

  const tick = (ts: number) => {
    const dt =
      lastTs > 0 ? Math.min(0.032, Math.max(0.002, (ts - lastTs) / 1000)) : 1 / 144;
    lastTs = ts;

    activeAnims.forEach((anim, el) => {
      if (!el.isConnected) {
        activeAnims.delete(el);
        return;
      }
      if (anim.y.remaining !== 0) {
        const dy = stepAxisHermite(anim.y, dt);
        applyScrollDeltaY(el, anim.y, dy);
      }
      if (anim.x.remaining !== 0) {
        const dx = stepAxisHermite(anim.x, dt);
        applyScrollDeltaX(el, anim.x, dx);
      }
      if (anim.x.remaining === 0 && anim.y.remaining === 0) {
        activeAnims.delete(el);
      }
    });

    if (activeAnims.size > 0) {
      rafId = requestAnimationFrame(tick);
    } else {
      rafId = 0;
      lastTs = 0;
    }
  };

  const ensureLoop = () => {
    if (rafId === 0) {
      lastTs = 0;
      rafId = requestAnimationFrame(tick);
    }
  };

  window.__lindoSmoothWheel = (clientX: number, clientY: number, dx: number, dy: number) => {
    const target = findScrollTarget(clientX, clientY, dx, dy);
    if (!target) return;

    let anim = activeAnims.get(target);
    if (!anim) {
      anim = { x: createAxis(), y: createAxis() };
      activeAnims.set(target, anim);
    }

    if (Math.abs(dy) >= 0.01) {
      syncAxisYBounds(target, anim.y);
    }
    if (Math.abs(dx) >= 0.01) {
      syncAxisXBounds(target, anim.x);
    }

    const leadY = addAxisImpulse(anim.y, dy);
    const leadX = addAxisImpulse(anim.x, dx);
    if (leadY !== 0) applyScrollDeltaY(target, anim.y, leadY);
    if (leadX !== 0) applyScrollDeltaX(target, anim.x, leadX);

    if (anim.x.remaining !== 0 || anim.y.remaining !== 0) {
      ensureLoop();
    } else {
      activeAnims.delete(target);
    }
  };

  // Strict two-state native cursor synchronizer (normal arrow vs pointing hand):
  // Eliminates macOS WKWebView stale pointer/I-beam glitches across moves, programmatic scroll & modal dismissal.
  let lastMouseX = -1;
  let lastMouseY = -1;
  let hasMousePos = false;
  let isPrimaryButtonDown = false;
  let lastAppliedPointer: boolean | null = null;
  let cursorEvalRaf = 0;
  let lastScrollCursorSyncMs = 0;

  const pushNativeCursor = (pointer: boolean, force = false) => {
    if (!force && lastAppliedPointer === pointer) return;
    lastAppliedPointer = pointer;
    invoke("set_app_cursor", { pointer }).catch(() => {});
  };

  const syncCursorAtLastPoint = (force = false) => {
    if (!hasMousePos || document.hidden) return;
    if (isPrimaryButtonDown) {
      pushNativeCursor(false, force);
      return;
    }
    const el = document.elementFromPoint(lastMouseX, lastMouseY);
    pushNativeCursor(isStrictPointerTarget(el), force);
  };

  const scheduleCursorSync = (force = false) => {
    if (cursorEvalRaf !== 0 && !force) return;
    if (cursorEvalRaf !== 0) cancelAnimationFrame(cursorEvalRaf);
    cursorEvalRaf = requestAnimationFrame(() => {
      cursorEvalRaf = 0;
      syncCursorAtLastPoint(force);
    });
  };

  window.addEventListener(
    "pointermove",
    (ev: PointerEvent) => {
      lastMouseX = ev.clientX;
      lastMouseY = ev.clientY;
      hasMousePos = true;
      isPrimaryButtonDown = (ev.buttons & 1) !== 0;
      if (isPrimaryButtonDown) {
        pushNativeCursor(false);
        return;
      }
      const directTarget = ev.target instanceof Element ? ev.target : null;
      if (directTarget) {
        pushNativeCursor(isStrictPointerTarget(directTarget));
      } else {
        scheduleCursorSync();
      }
    },
    { capture: true, passive: true }
  );

  // Cancel any remaining wheel glide instantly when user clicks/drags & keep cursor clean during drag/selection
  window.addEventListener(
    "pointerdown",
    (ev: PointerEvent) => {
      lastMouseX = ev.clientX;
      lastMouseY = ev.clientY;
      hasMousePos = true;
      if (ev.button === 0) {
        isPrimaryButtonDown = true;
        pushNativeCursor(false);
      }
      if (activeAnims.size > 0) {
        activeAnims.clear();
        if (rafId !== 0) {
          cancelAnimationFrame(rafId);
          rafId = 0;
          lastTs = 0;
        }
      }
    },
    { capture: true, passive: true }
  );

  const onPointerRelease = (ev: PointerEvent) => {
    lastMouseX = ev.clientX;
    lastMouseY = ev.clientY;
    hasMousePos = true;
    isPrimaryButtonDown = (ev.buttons & 1) !== 0;
    scheduleCursorSync(true);
    // Re-check one tick later in case click opened/closed a popover or modal under the stationary cursor
    setTimeout(() => syncCursorAtLastPoint(true), 32);
  };
  window.addEventListener("pointerup", onPointerRelease, { capture: true, passive: true });
  window.addEventListener("pointercancel", onPointerRelease, { capture: true, passive: true });
  window.addEventListener(
    "blur",
    () => {
      isPrimaryButtonDown = false;
      lastAppliedPointer = null;
    },
    { passive: true }
  );
  document.addEventListener(
    "mouseleave",
    () => {
      hasMousePos = false;
      isPrimaryButtonDown = false;
      lastAppliedPointer = null;
    },
    { passive: true }
  );

  // Passive scroll-state flag to suppress stationary-cursor :hover transition paint churn at 4K 144Hz
  let isScrollingFlag = false;
  let scrollIdleTimer: ReturnType<typeof setTimeout> | null = null;
  let lastIdleResetMs = 0;

  const scheduleScrollIdleCleanup = () => {
    if (scrollIdleTimer !== null) clearTimeout(scrollIdleTimer);
    scrollIdleTimer = setTimeout(() => {
      if (activeAnims.size > 0) {
        scheduleScrollIdleCleanup();
        return;
      }
      isScrollingFlag = false;
      scrollIdleTimer = null;
      document.documentElement.classList.remove("lindo-scrolling");
      // Immediately resync cursor against whatever scrolled underneath the stationary pointer!
      syncCursorAtLastPoint(true);
    }, 240);
  };

  window.addEventListener(
    "scroll",
    () => {
      const now = performance.now();
      if (!isScrollingFlag) {
        isScrollingFlag = true;
        document.documentElement.classList.add("lindo-scrolling");
      }
      if (now - lastIdleResetMs > 55) {
        lastIdleResetMs = now;
        scheduleScrollIdleCleanup();
      }
      if (hasMousePos && now - lastScrollCursorSyncMs > 90) {
        lastScrollCursorSyncMs = now;
        syncCursorAtLastPoint(false);
      }
    },
    { capture: true, passive: true }
  );

  // One-shot passive display refresh telemetry after startup so diagnostics can confirm 120/144Hz vs 60Hz
  setTimeout(() => {
    if (document.hidden) return;
    const samples: number[] = [];
    const probe = (ts: number) => {
      samples.push(ts);
      if (samples.length < 38) {
        requestAnimationFrame(probe);
        return;
      }
      const deltas: number[] = [];
      for (let i = 1; i < samples.length; i++) {
        const dt = samples[i] - samples[i - 1];
        if (dt > 1 && dt < 100) deltas.push(dt);
      }
      if (deltas.length >= 10) {
        deltas.sort((a, b) => a - b);
        const med = deltas[Math.floor(deltas.length / 2)];
        const p90 = deltas[Math.floor(deltas.length * 0.9)];
        const fps = 1000 / med;
        diagLog(
          fps < 75 ? "WARN" : "INFO",
          "SYSTEM",
          "DisplayRefresh",
          `渲染刷新率实测 ~${fps.toFixed(1)}Hz (中位 ${med.toFixed(2)}ms · P90 ${p90.toFixed(2)}ms)`
        );
      }
    };
    requestAnimationFrame(probe);
  }, 1600);
}
