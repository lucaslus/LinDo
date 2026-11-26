import fs from "node:fs";
import path from "node:path";
import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { UserPreferences } from "../lib/types";

const invokeMock = vi.fn();
const openUrlMock = vi.fn();

vi.mock("@tauri-apps/api/core", () => ({
  invoke: (...args: any[]) => invokeMock(...args),
}));

vi.mock("@tauri-apps/api/webview", () => ({
  getCurrentWebview: () => ({
    onDragDropEvent: vi.fn().mockResolvedValue(() => {}),
  }),
}));

vi.mock("@tauri-apps/api/window", () => ({
  getCurrentWindow: () => ({
    startDragging: vi.fn(),
    toggleMaximize: vi.fn(),
  }),
}));

vi.mock("@tauri-apps/plugin-opener", () => ({
  openUrl: (...args: any[]) => openUrlMock(...args),
}));

import { SideRail } from "../components/SideRail";
import {
  ProfileSettingsView,
  formatSiteVerifyStatusLabel,
} from "../components/SecondaryScreens";
import { triggerInteractiveVerifyOnce } from "../lib/api";
import { prepareCookedHtml } from "../lib/cooked";
import {
  clearForumMediaBlobCache,
  fetchForumMediaBlobUrl,
  observeCookedForumImages,
} from "../lib/forumMedia";

(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;

let activeRoot: Root | null = null;
let activeContainer: HTMLDivElement | null = null;

function unmountActive(): void {
  if (activeRoot) {
    act(() => {
      activeRoot!.unmount();
    });
    activeRoot = null;
  }
  if (activeContainer) {
    activeContainer.remove();
    activeContainer = null;
  }
}

function mountElement(node: React.ReactElement): HTMLDivElement {
  unmountActive();
  activeContainer = document.createElement("div");
  document.body.appendChild(activeContainer);
  activeRoot = createRoot(activeContainer);
  act(() => {
    activeRoot!.render(node);
  });
  return activeContainer;
}

function findButtonByText(container: HTMLElement, text: string): HTMLButtonElement | undefined {
  return Array.from(container.querySelectorAll("button")).find((btn) =>
    (btn.textContent || "").includes(text)
  );
}

const DEFAULT_PREFS: UserPreferences = {
  appearance: "dark",
  themeOption: "gold",
  compactFeed: false,
  fontScale: 1,
  enableAnimations: true,
  splitFraction: 0.34,
  defaultSearchExpanded: false,
  echRoutingMode: "smart_auto",
};

describe("SideRail shield indicator, Settings manual verify, and zero-CLS image skeletons", () => {
  beforeEach(() => {
    invokeMock.mockReset();
    openUrlMock.mockReset();
    clearForumMediaBlobCache();
  });

  afterEach(() => {
    unmountActive();
    vi.restoreAllMocks();
  });

  it("renders SideRail shield indicator directly below Sun/Moon toggle ONLY when cfAttentionNeeded or verifyActive", () => {
    const onTriggerVerify = vi.fn();

    // 1. Hidden when neither flag is active
    const idleContainer = mountElement(
      React.createElement(SideRail, {
        currentTab: "HOME",
        isDark: true,
        unreadCount: 0,
        isMacos: true,
        cfAttentionNeeded: false,
        verifyActive: false,
        onSelectTab: vi.fn(),
        onPublishClick: vi.fn(),
        onToggleTheme: vi.fn(),
        onTriggerVerify,
      })
    );
    expect(idleContainer.querySelector('[data-testid="siderail-shield-verify"]')).toBeNull();

    // 2. Visible right below theme toggle when cfAttentionNeeded is true
    const attnContainer = mountElement(
      React.createElement(SideRail, {
        currentTab: "HOME",
        isDark: true,
        unreadCount: 0,
        isMacos: true,
        cfAttentionNeeded: true,
        verifyActive: false,
        onSelectTab: vi.fn(),
        onPublishClick: vi.fn(),
        onToggleTheme: vi.fn(),
        onTriggerVerify,
      })
    );
    const themeBtn = attnContainer.querySelector('button[title="切换为浅色外观"]');
    const shieldBtn = attnContainer.querySelector<HTMLButtonElement>(
      '[data-testid="siderail-shield-verify"]'
    );
    expect(themeBtn).not.toBeNull();
    expect(shieldBtn).not.toBeNull();
    expect(themeBtn?.nextElementSibling).toBe(shieldBtn);
    expect(shieldBtn?.getAttribute("title")).toBe("需要站点安全确认 · 点击验证");
    expect(shieldBtn?.getAttribute("aria-label")).toContain("安全确认");

    act(() => {
      shieldBtn?.click();
    });
    expect(onTriggerVerify).toHaveBeenCalledTimes(1);

    // 3. Shows active tooltip when verifyActive is true
    const activeContainer = mountElement(
      React.createElement(SideRail, {
        currentTab: "HOME",
        isDark: false,
        unreadCount: 0,
        isMacos: true,
        cfAttentionNeeded: false,
        verifyActive: true,
        onSelectTab: vi.fn(),
        onPublishClick: vi.fn(),
        onToggleTheme: vi.fn(),
        onTriggerVerify,
      })
    );
    const activeShield = activeContainer.querySelector<HTMLButtonElement>(
      '[data-testid="siderail-shield-verify"]'
    );
    expect(activeShield).not.toBeNull();
    expect(activeShield?.getAttribute("title")).toBe("安全校验中…");
  });

  it("renders manual 站点安全验证 row in ProfileSettingsView with live status and triggers verification", () => {
    const onTriggerVerify = vi.fn();
    const baseProps = {
      isAuthorized: false,
      isDark: true,
      currentUser: null,
      savedAccounts: [],
      preferences: DEFAULT_PREFS,
      readFloors: {},
      drafts: {},
      knownTopics: [],
      onUpdatePreferences: vi.fn(),
      onToggleDark: vi.fn(),
      onRequestLogin: vi.fn(),
      onOpenAuthSheet: vi.fn(),
      onOpenQrLoginSheet: vi.fn(),
      onRequestGuestLogin: vi.fn(),
      onOpenInAppUrl: vi.fn(),
      onRequestLogout: vi.fn(),
      onOpenTopicById: vi.fn(),
      onResumeComposerDraft: vi.fn(),
      onInspectUser: vi.fn(),
      onHistoryCleared: vi.fn(),
      onDraftsChanged: vi.fn(),
      onProfileUpdated: vi.fn(),
      onTriggerVerify,
    };

    // 1. Default idle state -> 刷新验证票据
    const idleDom = mountElement(
      React.createElement(ProfileSettingsView, {
        ...baseProps,
        cfAttentionNeeded: false,
        verifyActive: false,
      })
    );
    const idleRow = findButtonByText(idleDom, "站点安全验证");
    expect(idleRow).toBeDefined();
    expect(idleRow?.textContent).toContain("刷新验证票据");
    act(() => {
      idleRow?.click();
    });
    expect(onTriggerVerify).toHaveBeenCalledTimes(1);

    // 2. Attention needed state -> 待验证
    const attnDom = mountElement(
      React.createElement(ProfileSettingsView, {
        ...baseProps,
        cfAttentionNeeded: true,
        verifyActive: false,
      })
    );
    const attnRow = findButtonByText(attnDom, "站点安全验证");
    expect(attnRow?.textContent).toContain("待验证");
    act(() => {
      attnRow?.click();
    });
    expect(onTriggerVerify).toHaveBeenCalledTimes(2);

    // 3. Busy state -> 验证中… (does not double-trigger)
    const busyDom = mountElement(
      React.createElement(ProfileSettingsView, {
        ...baseProps,
        cfAttentionNeeded: true,
        verifyActive: true,
      })
    );
    const busyRow = findButtonByText(busyDom, "站点安全验证");
    expect(busyRow?.textContent).toContain("验证中…");
    act(() => {
      busyRow?.click();
    });
    expect(onTriggerVerify).toHaveBeenCalledTimes(2);
  });

  it("annotates cooked content images for zero-CLS skeletons while skipping emojis, avatars, tiny icons, and cached hits", async () => {
    // 1. Dimensioned content image, percentage-scaled image, dimensionless image, lightbox meta image, emoji, avatar, tiny icon
    const html = prepareCookedHtml(`
      <p><img class="content-a" src="https://cdn.example.com/photo.jpg" width="800" height="450" alt="photo" /></p>
      <p><img class="content-pct" src="https://cdn.example.com/half.jpg" width="800" height="400" style="width: 50%;" /></p>
      <p><img class="content-nodims" src="https://cdn.example.com/unknown.png" /></p>
      <div class="lightbox-wrapper">
        <a class="lightbox" href="https://cdn.example.com/lb.png">
          <img class="content-lb" src="https://cdn.example.com/lb.png" />
          <div class="meta"><span class="informations">1200×800 180 KB</span></div>
        </a>
      </div>
      <p><img class="emoji" src="https://linux.do/images/emoji/twemoji/smile.png" width="20" height="20" /></p>
      <p><img class="avatar" src="https://linux.do/user_avatar/linux.do/neo/96/1.png" width="48" height="48" /></p>
      <p><img class="tiny-badge" src="https://cdn.example.com/badge.png" width="24" height="24" /></p>
    `);

    const wrap = document.createElement("div");
    wrap.innerHTML = html;

    const imgA = wrap.querySelector<HTMLImageElement>("img.content-a")!;
    expect(imgA.getAttribute("decoding")).toBe("async");
    expect(imgA.classList.contains("lindo-img-loading")).toBe(true);
    expect(imgA.classList.contains("lindo-img-loading-fallback")).toBe(false);
    expect(imgA.getAttribute("data-lindo-loading")).toBe("1");
    expect(imgA.getAttribute("style")).toContain("--lindo-img-w:800px");
    expect(imgA.getAttribute("style")).toContain("aspect-ratio:800 / 450");

    const imgPct = wrap.querySelector<HTMLImageElement>("img.content-pct")!;
    expect(imgPct.classList.contains("lindo-img-loading")).toBe(true);
    expect(imgPct.getAttribute("style")).toContain("width: 50%");
    expect(imgPct.getAttribute("style")).toContain("aspect-ratio:800 / 400");
    expect(imgPct.getAttribute("style")).not.toContain("--lindo-img-w:");

    const imgNoDims = wrap.querySelector<HTMLImageElement>("img.content-nodims")!;
    expect(imgNoDims.classList.contains("lindo-img-loading")).toBe(true);
    expect(imgNoDims.classList.contains("lindo-img-loading-fallback")).toBe(true);
    expect(imgNoDims.getAttribute("data-lindo-loading")).toBe("1");

    const imgLb = wrap.querySelector<HTMLImageElement>("img.content-lb")!;
    expect(imgLb.classList.contains("lindo-img-loading")).toBe(true);
    expect(imgLb.classList.contains("lindo-img-loading-fallback")).toBe(false);
    expect(imgLb.getAttribute("style")).toContain("--lindo-img-w:1200px");
    expect(imgLb.getAttribute("style")).toContain("aspect-ratio:1200 / 800");

    const emoji = wrap.querySelector<HTMLImageElement>("img.emoji")!;
    expect(emoji.classList.contains("lindo-img-loading")).toBe(false);
    expect(emoji.getAttribute("data-lindo-loading")).toBeNull();

    const avatar = wrap.querySelector<HTMLImageElement>("img.avatar")!;
    expect(avatar.classList.contains("lindo-img-loading")).toBe(false);

    const tiny = wrap.querySelector<HTMLImageElement>("img.tiny-badge")!;
    expect(tiny.classList.contains("lindo-img-loading")).toBe(false);

    // 2. Pre-cached same-origin upload renders immediately without skeleton flicker
    const uploadUrl = "https://linux.do/uploads/default/original/4X/a/b/c/cached.png";
    const createObjUrlSpy = vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:cached-test");
    invokeMock.mockResolvedValueOnce({
      status: 200,
      contentType: "image/png",
      dataBase64: "iVBORw0KGgo=",
    });
    const blob = await fetchForumMediaBlobUrl(uploadUrl);
    expect(blob).toBe("blob:cached-test");
    expect(createObjUrlSpy).toHaveBeenCalledTimes(1);

    const cachedHtml = prepareCookedHtml(
      `<p><img class="cached-upload" src="${uploadUrl}" width="640" height="360" /></p>`
    );
    const cachedWrap = document.createElement("div");
    cachedWrap.innerHTML = cachedHtml;
    const cachedImg = cachedWrap.querySelector<HTMLImageElement>("img.cached-upload")!;
    expect(cachedImg.getAttribute("src")).toBe("blob:cached-test");
    expect(cachedImg.classList.contains("lindo-img-loading")).toBe(false);
    expect(cachedImg.getAttribute("data-lindo-loading")).toBeNull();
  });

  it("clears skeleton classes on load/cached-complete and marks error fallback cleanly on failure", async () => {
    const origIO = (globalThis as any).IntersectionObserver;
    (globalThis as any).IntersectionObserver = undefined;
    try {
      const html = prepareCookedHtml(`
        <p><img class="ext-ok" src="https://cdn.example.com/ok.jpg" width="600" height="400" /></p>
        <p><img class="ext-cached" src="https://cdn.example.com/cached.jpg" width="600" height="400" /></p>
        <p><img class="ext-err" src="https://cdn.example.com/broken.jpg" /></p>
        <p><img class="bridge-err" src="https://linux.do/uploads/default/original/4X/x/y/z/fail.png" width="500" height="300" /></p>
      `);
      const container = document.createElement("div");
      container.innerHTML = html;

      const extOk = container.querySelector<HTMLImageElement>("img.ext-ok")!;
      const extCached = container.querySelector<HTMLImageElement>("img.ext-cached")!;
      const extErr = container.querySelector<HTMLImageElement>("img.ext-err")!;
      const bridgeErr = container.querySelector<HTMLImageElement>("img.bridge-err")!;

      Object.defineProperty(extOk, "complete", { value: false, configurable: true });
      Object.defineProperty(extErr, "complete", { value: false, configurable: true });
      Object.defineProperty(extCached, "complete", { value: true, configurable: true });
      Object.defineProperty(extCached, "naturalWidth", { value: 600, configurable: true });

      invokeMock.mockResolvedValueOnce({ status: 404, contentType: "", dataBase64: "" });

      const cleanup = observeCookedForumImages(container);

      // Already-complete browser-cached image clears synchronously
      expect(extCached.classList.contains("lindo-img-loading")).toBe(false);
      expect(extCached.classList.contains("lindo-img-loaded")).toBe(true);
      expect(extCached.getAttribute("data-lindo-loading")).toBeNull();

      // External load clears skeleton cleanly
      extOk.dispatchEvent(new Event("load"));
      expect(extOk.classList.contains("lindo-img-loading")).toBe(false);
      expect(extOk.classList.contains("lindo-img-loaded")).toBe(true);
      expect(extOk.getAttribute("data-lindo-loading")).toBeNull();

      // External error swaps to subtle error state (no endless shimmer)
      extErr.dispatchEvent(new Event("error"));
      expect(extErr.classList.contains("lindo-img-loading")).toBe(false);
      expect(extErr.classList.contains("lindo-img-loading-fallback")).toBe(false);
      expect(extErr.classList.contains("lindo-img-error")).toBe(true);
      expect(extErr.getAttribute("data-lindo-loading")).toBeNull();

      // Failed bridged upload marks error cleanly instead of hanging in shimmer
      await Promise.resolve();
      await Promise.resolve();
      await Promise.resolve();
      expect(bridgeErr.classList.contains("lindo-img-loading")).toBe(false);
      expect(bridgeErr.classList.contains("lindo-img-error")).toBe(true);
      expect(bridgeErr.getAttribute("data-lindo-loading")).toBeNull();

      cleanup();
    } finally {
      (globalThis as any).IntersectionObserver = origIO;
    }
  });

  it("verifies CSS skeleton tokens, overflow-anchor, and wide-layout floating pill consolidation", () => {
    const srcRoot = path.resolve(__dirname, "..");
    const css = fs.readFileSync(path.join(srcRoot, "styles/app.css"), "utf8");
    const app = fs.readFileSync(path.join(srcRoot, "App.tsx"), "utf8");
    const reader = fs.readFileSync(path.join(srcRoot, "components/TopicReaderPane.tsx"), "utf8");

    expect(css).toContain(".lindo-reader-scroll");
    expect(css).toContain("overflow-anchor: auto");
    expect(css).toContain("#e7e9ed");
    expect(css).toContain("#eef1f5");
    expect(css).toContain("#1a1e27");
    expect(css).toContain("#232836");
    expect(css).toContain(".lindo-cooked img.lindo-img-loading.lindo-img-loading-fallback");
    expect(css).toContain(".lindo-cooked img.lindo-img-error");
    expect(reader).toContain("lindo-reader-scroll");

    expect(app).toContain('setGlobalNotice(forceRefresh ? "安全验证已刷新" : "连接验证正常")');
    expect(app).toContain("(!inMainApp || !isWideLayout)");
    expect(app).toContain("onTriggerVerify={handleForceRefreshVerify}");
    expect(app).toContain("onTriggerVerify={handleReactiveVerify}");
    expect(app).toContain("handleTriggerVerify({ forceRefresh: true })");
    expect(app).toContain("handleTriggerVerify({ forceRefresh: false })");
  });

  it("enforces single-shot forceRefresh separation in triggerInteractiveVerifyOnce and live lastVerifiedAtMs labels", async () => {
    // 1. Settings forceRefresh = true -> sends forceRefresh: true
    invokeMock.mockResolvedValueOnce(true);
    const okForce = await triggerInteractiveVerifyOnce({ manual: true, forceRefresh: true });
    expect(okForce).toBe(true);
    expect(invokeMock).toHaveBeenLastCalledWith(
      "site_verify",
      expect.objectContaining({ login: false, manual: true, forceRefresh: true })
    );

    // 2. Reactive SideRail / banner verify -> sends forceRefresh: false
    invokeMock.mockResolvedValueOnce(true);
    const okReactive = await triggerInteractiveVerifyOnce({ manual: true, forceRefresh: false });
    expect(okReactive).toBe(true);
    expect(invokeMock).toHaveBeenLastCalledWith(
      "site_verify",
      expect.objectContaining({ login: false, manual: true, forceRefresh: false })
    );

    // 3. Non-manual verify MUST clamp forceRefresh to false even if requested
    invokeMock.mockResolvedValueOnce(true);
    await triggerInteractiveVerifyOnce({ manual: false, forceRefresh: true });
    expect(invokeMock).toHaveBeenLastCalledWith(
      "site_verify",
      expect.objectContaining({ login: false, manual: false, forceRefresh: false })
    );

    // 4. Live status labels for lastVerifiedAtMs / cfAttentionNeeded / verifyActive
    const now = 1_760_000_000_000;
    expect(
      formatSiteVerifyStatusLabel({ cfAttentionNeeded: false, verifyActive: true, lastVerifiedAtMs: now - 5_000, nowMs: now })
    ).toBe("验证中…");
    expect(
      formatSiteVerifyStatusLabel({ cfAttentionNeeded: true, verifyActive: false, lastVerifiedAtMs: now - 5_000, nowMs: now })
    ).toBe("待验证");
    expect(
      formatSiteVerifyStatusLabel({ cfAttentionNeeded: false, verifyActive: false, lastVerifiedAtMs: now - 15_000, nowMs: now })
    ).toBe("刚刚已验证");
    expect(
      formatSiteVerifyStatusLabel({ cfAttentionNeeded: false, verifyActive: false, lastVerifiedAtMs: now - 7 * 60_000, nowMs: now })
    ).toBe("7 分钟前已验证");
    expect(
      formatSiteVerifyStatusLabel({ cfAttentionNeeded: false, verifyActive: false, lastVerifiedAtMs: now - 65 * 60_000, nowMs: now })
    ).toBe("刷新验证票据");
    expect(
      formatSiteVerifyStatusLabel({ cfAttentionNeeded: false, verifyActive: false, lastVerifiedAtMs: null, nowMs: now })
    ).toBe("刷新验证票据");

    // 5. Rendered ProfileSettingsView reflects lastVerifiedAtMs ("刚刚已验证")
    const rendered = mountElement(
      React.createElement(ProfileSettingsView, {
        isAuthorized: false,
        isDark: true,
        currentUser: null,
        savedAccounts: [],
        preferences: DEFAULT_PREFS,
        readFloors: {},
        drafts: {},
        knownTopics: [],
        onUpdatePreferences: vi.fn(),
        onToggleDark: vi.fn(),
        onRequestLogin: vi.fn(),
        onOpenAuthSheet: vi.fn(),
        onOpenQrLoginSheet: vi.fn(),
        onRequestGuestLogin: vi.fn(),
        onOpenInAppUrl: vi.fn(),
        onRequestLogout: vi.fn(),
        onOpenTopicById: vi.fn(),
        onResumeComposerDraft: vi.fn(),
        onInspectUser: vi.fn(),
        onHistoryCleared: vi.fn(),
        onDraftsChanged: vi.fn(),
        onProfileUpdated: vi.fn(),
        cfAttentionNeeded: false,
        verifyActive: false,
        lastVerifiedAtMs: Date.now() - 5_000,
        onTriggerVerify: vi.fn(),
      })
    );
    const verifyRow = findButtonByText(rendered, "站点安全验证");
    expect(verifyRow?.textContent).toContain("刚刚已验证");
  });
});
