import { describe, expect, it } from "vitest";
import fs from "node:fs";
import path from "node:path";
import { createImeCompositionGuard } from "../lib/imeGuard";

function makeKeyEvent(
  key: string,
  opts: { isComposing?: boolean; keyCode?: number; target?: EventTarget | null } = {}
): KeyboardEvent {
  const ev = {
    key,
    isComposing: opts.isComposing ?? false,
    keyCode: opts.keyCode ?? (key === "Enter" ? 13 : key === "Escape" ? 27 : 65),
    which: opts.keyCode ?? (key === "Enter" ? 13 : key === "Escape" ? 27 : 65),
    target: opts.target ?? null,
  };
  return ev as unknown as KeyboardEvent;
}

describe("IME composition guard (macOS WKWebView + Windows WebView2)", () => {
  it("blocks Enter and Escape during active IME composition or keyCode 229 / Process", () => {
    const guard = createImeCompositionGuard();
    const target = {} as EventTarget;

    expect(guard.isComposingEvent(makeKeyEvent("Enter", { isComposing: true, target }), 100)).toBe(true);
    expect(guard.isComposingEvent(makeKeyEvent("Enter", { keyCode: 229, target }), 100)).toBe(true);
    expect(guard.isComposingEvent(makeKeyEvent("Process", { target }), 100)).toBe(true);
    expect(guard.isComposingEvent(makeKeyEvent("Escape", { isComposing: true, target }), 100)).toBe(true);

    guard.onCompositionStart(target);
    expect(guard.isComposingEvent(makeKeyEvent("Enter", { isComposing: false, target }), 120)).toBe(true);
    expect(guard.isComposingEvent(makeKeyEvent("Escape", { isComposing: false, target }), 120)).toBe(true);
  });

  it("absorbs macOS WebKit post-compositionend trailing Enter without swallowing the user's next Enter", () => {
    const guard = createImeCompositionGuard();
    const inputEl = {} as EventTarget;

    // User types "gemini" in Chinese Pinyin IME and presses Enter to commit raw English text.
    // macOS WKWebView fires compositionend FIRST, then keydown(Enter, isComposing=false) 8ms later.
    guard.onCompositionStart(inputEl);
    guard.onCompositionEnd(inputEl, 1000);

    const trailingWebKitEnter = makeKeyEvent("Enter", {
      isComposing: false,
      keyCode: 13,
      target: inputEl,
    });

    // Input handler sees true -> does NOT send boost!
    expect(guard.isComposingEvent(trailingWebKitEnter, 1008)).toBe(true);
    // Window-level bubbling listener on the EXACT same event also sees true!
    expect(guard.isComposingEvent(trailingWebKitEnter, 1008)).toBe(true);

    // User then presses Enter a second time to actually submit -> fresh event, MUST NOT be blocked!
    const realSubmitEnter = makeKeyEvent("Enter", {
      isComposing: false,
      keyCode: 13,
      target: inputEl,
    });
    expect(guard.isComposingEvent(realSubmitEnter, 1055)).toBe(false);
  });

  it("absorbs macOS WebKit post-compositionend trailing Escape so modals do not close prematurely", () => {
    const guard = createImeCompositionGuard();
    const inputEl = {} as EventTarget;

    guard.onCompositionStart(inputEl);
    guard.onCompositionEnd(inputEl, 2000);

    const trailingEsc = makeKeyEvent("Escape", { isComposing: false, keyCode: 27, target: inputEl });
    expect(guard.isComposingEvent(trailingEsc, 2010)).toBe(true);
    expect(guard.isComposingEvent(trailingEsc, 2010)).toBe(true);

    // Next real Escape closes modal
    const realEsc = makeKeyEvent("Escape", { isComposing: false, keyCode: 27, target: inputEl });
    expect(guard.isComposingEvent(realEsc, 2120)).toBe(false);
  });

  it("never blocks regular typing or non-IME Enter outside the WebKit grace window", () => {
    const guard = createImeCompositionGuard();
    const inputEl = {} as EventTarget;

    expect(guard.isComposingEvent(makeKeyEvent("Enter", { target: inputEl }), 500)).toBe(false);

    guard.onCompositionStart(inputEl);
    guard.onCompositionEnd(inputEl, 1000);

    // Normal character key right after compositionend is not blocked
    expect(guard.isComposingEvent(makeKeyEvent("a", { target: inputEl }), 1005)).toBe(false);
    // Enter after grace window (> 80ms) is not blocked
    expect(guard.isComposingEvent(makeKeyEvent("Enter", { target: inputEl }), 1120)).toBe(false);
  });

  it("ensures every interactive input Enter handler across desktop-tauri uses isImeComposingEvent", () => {
    const componentsDir = path.resolve(__dirname, "../components");
    const targets = [
      "TopicReaderPane.tsx",
      "ReaderEnhancements.tsx",
      "ComposerModal.tsx",
      "SecondaryScreens.tsx",
    ];
    for (const file of targets) {
      const src = fs.readFileSync(path.join(componentsDir, file), "utf8");
      expect(src).toContain("isImeComposingEvent");
    }
  });
});
