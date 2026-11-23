import type React from "react";

const WEBKIT_POST_COMPOSITION_GRACE_MS = 80;

type KeyEventLike =
  | KeyboardEvent
  | Pick<React.KeyboardEvent<unknown>, "key" | "keyCode" | "nativeEvent" | "target">;

function unwrapNativeKeyEvent(e: KeyEventLike): KeyboardEvent {
  if ("nativeEvent" in e && e.nativeEvent) {
    return e.nativeEvent as KeyboardEvent;
  }
  return e as KeyboardEvent;
}

export interface ImeCompositionGuard {
  onCompositionStart(target?: EventTarget | null): void;
  onCompositionEnd(target?: EventTarget | null, now?: number): void;
  isComposingEvent(e: KeyEventLike, now?: number): boolean;
  reset(): void;
}

/**
 * 跨平台 IME 组合态守卫（macOS WKWebView / Safari / Windows WebView2 / Chromium）。
 *
 * 重点解决两个真实场景：
 * 1. 标准组合态：`e.isComposing === true` / `keyCode === 229` / `key === "Process" | "Dead"`。
 * 2. macOS WebKit Bug #165004：拼音输入法输入英文（如 `gemini`）并按 Enter 上屏或按 Esc 取消候选框时，
 *    WKWebView 会先派发 `compositionend`，几毫秒后再派发同一个物理按键的 `keydown(Enter/Escape, isComposing=false)`。
 *    通过一次性消费的 80ms post-compositionend 窗口 + `WeakSet<Event>` 绑定单次事件对象，
 *    既能让同一次事件在 input 冒泡到 window 全部被安全拦截，又绝不误吞紧随其后的第二次真实回车。
 */
export function createImeCompositionGuard(): ImeCompositionGuard {
  let composing = false;
  let pendingPostCommitUntil = 0;
  let pendingTarget: EventTarget | null = null;
  const absorbedEvents = new WeakSet<object>();

  return {
    onCompositionStart(target?: EventTarget | null) {
      composing = true;
      pendingPostCommitUntil = 0;
      pendingTarget = target ?? null;
    },

    onCompositionEnd(target?: EventTarget | null, now: number = performance.now()) {
      composing = false;
      pendingPostCommitUntil = now + WEBKIT_POST_COMPOSITION_GRACE_MS;
      pendingTarget = target ?? null;
    },

    isComposingEvent(e: KeyEventLike, now: number = performance.now()): boolean {
      const native = unwrapNativeKeyEvent(e);
      if (absorbedEvents.has(native)) {
        return true;
      }

      const key = native.key ?? e.key;
      const keyCode = native.keyCode ?? native.which ?? e.keyCode ?? 0;

      if (
        composing ||
        native.isComposing === true ||
        keyCode === 229 ||
        key === "Process" ||
        key === "Dead"
      ) {
        return true;
      }

      if ((key === "Enter" || key === "Escape") && pendingPostCommitUntil > 0) {
        if (now <= pendingPostCommitUntil) {
          const evTarget = native.target ?? e.target ?? null;
          if (!pendingTarget || !evTarget || pendingTarget === evTarget) {
            pendingPostCommitUntil = 0;
            absorbedEvents.add(native);
            return true;
          }
        } else {
          pendingPostCommitUntil = 0;
        }
      }

      return false;
    },

    reset() {
      composing = false;
      pendingPostCommitUntil = 0;
      pendingTarget = null;
    },
  };
}

const globalImeGuard = createImeCompositionGuard();
let globalListenersAttached = false;

export function ensureGlobalImeTracking(): void {
  if (globalListenersAttached || typeof window === "undefined") return;
  globalListenersAttached = true;
  window.addEventListener(
    "compositionstart",
    (ev) => globalImeGuard.onCompositionStart(ev.target),
    true
  );
  window.addEventListener(
    "compositionend",
    (ev) => globalImeGuard.onCompositionEnd(ev.target),
    true
  );
  window.addEventListener("blur", () => globalImeGuard.reset());
}

/**
 * 在所有输入框 / 弹窗 / 快捷键 `onKeyDown` 处理器顶部调用：
 * 返回 `true` 说明该按键属于中文/日文/韩文输入法上屏或取消候选框操作，业务逻辑必须忽略。
 */
export function isImeComposingEvent(e: KeyEventLike): boolean {
  ensureGlobalImeTracking();
  return globalImeGuard.isComposingEvent(e);
}
