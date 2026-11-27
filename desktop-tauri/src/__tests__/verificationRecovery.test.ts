import React, { act } from "react";
import { createRoot } from "react-dom/client";
import { expect, it, vi } from "vitest";
import { App } from "../App";
import { initialVerificationStatus, verificationStatus } from "../lib/verificationStatus";

const { listeners, invoke } = vi.hoisted(() => ({
  listeners: new Map<string, (event: { payload: unknown }) => void>(),
  invoke: vi.fn(),
}));

vi.mock("@tauri-apps/api/core", () => ({ invoke }));
vi.mock("@tauri-apps/api/event", () => ({
  emit: vi.fn().mockResolvedValue(undefined),
  listen: async (name: string, callback: (event: { payload: unknown }) => void) => {
    listeners.set(name, callback);
    return () => listeners.delete(name);
  },
}));
vi.mock("@tauri-apps/api/window", () => ({ getCurrentWindow: () => ({}) }));
vi.mock("@tauri-apps/api/webview", () => ({
  getCurrentWebview: () => ({ onDragDropEvent: async () => () => {} }),
}));
vi.mock("@tauri-apps/plugin-opener", () => ({ openUrl: vi.fn() }));

it.each(["sidebar", "settings-pending", "settings-idle", "automatic"])(
  "%s verification dismisses the shield while staying in Settings, before the channel is ready",
  async entry => {
    vi.stubGlobal("IS_REACT_ACT_ENVIRONMENT", true);
    let finish!: (ready: boolean) => void;
    const pending = new Promise<boolean>(resolve => { finish = resolve; });
    invoke.mockReset().mockImplementation(async (cmd: string) => {
      if (cmd === "bootstrap_state") return { platform: "macos", isAuthorized: false, state: { expiredUsername: "tester" } };
      if (cmd === "site_verify") return pending;
      if (cmd === "site_request") return { status: 200, body: "{}" };
      return {};
    });
    vi.stubGlobal("IntersectionObserver", class { observe() {} unobserve() {} disconnect() {} });
    const node = document.body.appendChild(document.createElement("div"));
    const root = createRoot(node);
    const button = (label: string) => [...node.querySelectorAll("button")].find(el => el.textContent?.includes(label));
    const shield = () => node.querySelector<HTMLButtonElement>('[data-testid="siderail-shield-verify"]');
    async function event(name: string, payload: unknown) {
      const listener = listeners.get("lindo://" + name);
      expect(listener).toBeDefined();
      await act(async () => listener!({ payload }));
    }
    try {
      await act(async () => root.render(React.createElement(App)));
      expect(button("我的")).toBeDefined();
      await act(async () => button("我的")!.click());
      if (entry !== "settings-idle") {
        await event("cf-attention-needed", true);
        expect(button("站点安全验证")?.textContent).toContain("待验证");
        expect(shield()).toBeTruthy();
      }
      if (entry !== "automatic") {
        await act(async () => (entry === "sidebar" ? shield() : button("站点安全验证"))!.click());
        expect(invoke).toHaveBeenCalledWith("site_verify", expect.objectContaining({ forceRefresh: entry === "settings-idle" }));
      }
      if (entry === "automatic") {
        await event("verify-pill", { round: 7, stage: "checking", visible: true });
        await event("verify-pill", { round: 7, stage: "panel", visible: false });
      }
      await event("verify-pill", { round: 7, stage: "recovering", visible: false });
      await event("cf-attention-needed", true);
      expect(shield()).toBeNull();
      expect(button("站点安全验证")?.textContent).toContain("网页已通过，正在恢复连接");
      expect(button("站点安全验证")?.disabled).toBe(true);
      await act(async () => button("站点安全验证")!.click());
      expect(invoke.mock.calls.filter(([cmd]) => cmd === "site_verify")).toHaveLength(entry === "automatic" ? 0 : 1);
      await event("verify-pill", { round: 7, stage: "done", visible: false });
      await event("verified", undefined);
      await act(async () => finish(true));
      expect(shield()).toBeNull();
      expect(button("站点安全验证")?.textContent).toContain("刚刚已验证");
      expect(button("站点安全验证")?.disabled).toBe(false);
    } finally {
      await act(async () => { finish(false); root.unmount(); });
      node.remove();
      vi.unstubAllGlobals();
    }
  },
);

it("shows a new site challenge after an unsuccessful reconnect", () => {
  const recovering = verificationStatus(initialVerificationStatus, {
    type: "progress", event: { round: 7, stage: "recovering", visible: false },
  });
  const offline = verificationStatus(recovering, {
    type: "progress", event: { round: 7, stage: "unavailable", visible: false },
  });
  expect(offline.attentionNeeded).toBe(false);
  expect(verificationStatus(offline, {
    type: "progress", event: { round: 6, stage: "panel", visible: true },
  })).toEqual(offline);
  expect(verificationStatus(offline, { type: "attention", needed: true }).attentionNeeded).toBe(true);
});
