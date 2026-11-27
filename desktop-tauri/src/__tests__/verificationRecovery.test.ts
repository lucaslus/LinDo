import React, { act } from "react";
import { createRoot } from "react-dom/client";
import { expect, it, vi } from "vitest";
import { App } from "../App";
import { publishReply, publishTopic, siteRequest } from "../lib/api";
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

it.each(["sidebar", "settings-pending", "settings-idle", "automatic", "reconnect-sidebar", "reconnect-settings"])(
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
    const reconnect = () => node.querySelector<HTMLButtonElement>('[data-testid="siderail-reconnect"]');
    const delayed = entry.startsWith("reconnect-");
    const automatic = entry === "automatic" || delayed;
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
      if (!automatic) {
        await act(async () => (entry === "sidebar" ? shield() : button("站点安全验证"))!.click());
        expect(invoke).toHaveBeenCalledWith("site_verify", expect.objectContaining({ forceRefresh: entry === "settings-idle" }));
      }
      if (automatic) {
        await event("verify-pill", { round: 7, stage: "checking", visible: true });
        await event("verify-pill", { round: 7, stage: "panel", visible: false });
      }
      await event("verify-pill", { round: 7, stage: "recovering", visible: false });
      await event("cf-attention-needed", true);
      expect(shield()).toBeNull();
      expect(button("站点安全验证")?.textContent).toContain("网页验证已通过，正在读取论坛数据");
      expect(button("站点安全验证")?.disabled).toBe(true);
      await act(async () => button("站点安全验证")!.click());
      expect(invoke.mock.calls.filter(([cmd]) => cmd === "site_verify")).toHaveLength(automatic ? 0 : 1);
      if (delayed) {
        await event("verify-pill", { round: 7, stage: "unavailable", visible: false });
        await event("cf-attention-needed", true);
        expect(shield()).toBeNull();
        expect(button("站点安全验证")?.textContent).toContain("重试连接");
        expect(reconnect()?.getAttribute("aria-label")).toBe("重试论坛连接");
        expect(invoke.mock.calls.filter(([cmd]) => cmd === "site_verify")).toHaveLength(0);
        const retry = entry === "reconnect-sidebar" ? reconnect() : button("站点安全验证");
        await act(async () => { retry!.click(); retry!.click(); });
        expect(invoke.mock.calls.filter(([cmd]) => cmd === "site_verify")).toHaveLength(1);
        expect(invoke).toHaveBeenCalledWith("site_verify", expect.objectContaining({ manual: true, forceRefresh: false }));
        await event("verify-pill", { round: 7, stage: "recovering", visible: false });
      }
      await event("verify-pill", { round: 7, stage: "done", visible: false });
      await event("verified", undefined);
      await act(async () => finish(true));
      expect(shield()).toBeNull();
      expect(reconnect()).toBeNull();
      expect(button("站点安全验证")?.textContent).toContain("刚刚已验证");
      expect(button("站点安全验证")?.disabled).toBe(false);
      if (entry !== "automatic") {
        const notice = delayed ? "论坛连接已恢复" : entry === "settings-idle" ? "安全验证已刷新" : "连接验证正常";
        expect(node.textContent).toContain(notice);
      }
    } finally {
      await act(async () => { finish(false); root.unmount(); });
      node.remove();
      vi.unstubAllGlobals();
    }
  },
);

it("ignores old attention while reconnecting and accepts a new verification round", () => {
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
  expect(verificationStatus(offline, { type: "attention", needed: true })).toEqual(offline);
  const next = verificationStatus(offline, {
    type: "progress", event: { round: 8, stage: "checking", visible: true },
  });
  expect(verificationStatus(next, { type: "attention", needed: true }).attentionNeeded).toBe(true);
});

it("reports unavailable reads as waiting for connection and never opens verification on their behalf", async () => {
  invoke.mockReset().mockRejectedValue("论坛连接尚未恢复");
  await expect(siteRequest("https://linux.do/latest.json")).rejects.toMatchObject({
    message: "论坛连接尚未恢复", retryReadAfterVerification: true, isVerification: false,
  });
  expect(invoke.mock.calls.map(([cmd]) => cmd)).toEqual(["site_request"]);
});

it.each(["topic", "reply"])("returns the %s submission to the author after verification without posting twice", async type => {
  const text = "回复内容保留在编辑器里";
  const submit = () => type === "topic"
    ? publishTopic({ title: "讨论", raw: text, categoryId: 4, tags: [] })
    : publishReply({ topicId: 482293, raw: text, replyToPostNumber: 2 });
  invoke.mockReset().mockRejectedValueOnce("论坛连接已恢复，请确认后重新提交");
  await expect(submit()).rejects.toThrow("请确认后重新提交");
  expect(invoke.mock.calls).toHaveLength(1);
  expect(invoke).toHaveBeenCalledWith("site_request", expect.objectContaining({
    method: "POST", form: expect.objectContaining({ raw: text }),
  }));
  invoke.mockResolvedValueOnce({ status: 200, body: '{"action":"enqueued"}' });
  await expect(submit()).resolves.toMatchObject({ pendingReview: true });
  expect(invoke.mock.calls).toHaveLength(2);
});
