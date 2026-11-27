import { clearMocks, mockIPC, mockWindows } from "@tauri-apps/api/mocks";
import React, { act } from "react";
import { createRoot } from "react-dom/client";
import { expect, it, vi } from "vitest";
import { App } from "../App";

it("leaves ordinary pointer movement, clicks and wheel input in the WebView", async () => {
  vi.stubGlobal("IS_REACT_ACT_ENVIRONMENT", true);
  const commands: string[] = [];
  mockWindows("main");
  mockIPC(cmd => {
    commands.push(cmd);
    if (cmd === "bootstrap_state") return { platform: "macos", isAuthorized: false, state: {} };
    return {};
  }, { shouldMockEvents: true });

  const container = document.body.appendChild(document.createElement("div"));
  const root = createRoot(container);
  try {
    await act(async () => root.render(React.createElement(App)));
    const button = container.querySelector("button")!;
    expect(button).toBeTruthy();
    commands.length = 0;
    for (const target of [container, button, container]) {
      target.dispatchEvent(new PointerEvent("pointermove", { bubbles: true }));
    }
    button.dispatchEvent(new PointerEvent("pointerdown", { bubbles: true, buttons: 1 }));
    button.dispatchEvent(new PointerEvent("pointerup", { bubbles: true }));
    expect(container.dispatchEvent(new WheelEvent("wheel", { bubbles: true, cancelable: true, deltaY: 60 }))).toBe(true);
    expect(commands).toEqual([]);
    expect(window).not.toHaveProperty("__lindoSmoothWheel");
  } finally {
    await act(async () => root.unmount());
    container.remove();
    clearMocks();
    vi.unstubAllGlobals();
  }
});
