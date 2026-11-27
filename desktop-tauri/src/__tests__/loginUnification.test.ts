import fs from "node:fs";
import path from "node:path";
import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  buildExpiredSessionGuestPrompt,
  buildRestrictedCategoryGuestPrompt,
  resolveAccountManagementTarget,
  resolveRestrictedCategorySelection,
} from "../lib/guestLoginFlow";
import type { CategoryMeta, SavedAccountEntry, UserProfile } from "../lib/types";

vi.mock("@tauri-apps/api/core", () => ({
  invoke: vi.fn().mockResolvedValue({}),
}));

vi.mock("@tauri-apps/api/webview", () => ({
  getCurrentWebview: () => ({
    onDragDropEvent: vi.fn().mockResolvedValue(() => {}),
  }),
}));

vi.mock("@tauri-apps/api/window", () => ({
  getCurrentWindow: () => ({
    startDragging: vi.fn(),
  }),
}));

vi.mock("@tauri-apps/plugin-opener", () => ({
  openUrl: vi.fn(),
}));

import {
  AuthModal,
  GuestLoginGuideModal,
  QrLoginModal,
  WelcomeLoginScreen,
} from "../components/SecondaryScreens";
import { HomeFeedPane } from "../components/HomeFeedPane";

(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;

let activeRoot: Root | null = null;
let activeContainer: HTMLDivElement | null = null;

function mountElement(node: React.ReactElement): HTMLDivElement {
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

afterEach(() => {
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
});

const SAMPLE_CATEGORIES: CategoryMeta[] = [
  {
    id: 4,
    name: "开发调优",
    displayName: "开发调优",
    slug: "develop",
    colorHex: "3AB54A",
    description: "",
    topicCount: 100,
    readRestricted: false,
  },
  {
    id: 34,
    name: "搞七捻三",
    displayName: "搞七捻三",
    slug: "gossip",
    colorHex: "B3B5B4",
    description: "",
    topicCount: 80,
    readRestricted: true,
  },
];

const SAMPLE_USER: UserProfile = {
  userId: 101,
  username: "lucas",
  displayName: "Lucas",
  avatarTemplate: "/user_avatar/linux.do/lucas/{size}/1.png",
  trustLevel: 3,
};

describe("Guest login flow decision helpers", () => {
  it("guards restricted categories for guests and allows them for authorized users", () => {
    const blocked = resolveRestrictedCategorySelection(34, SAMPLE_CATEGORIES, false);
    expect(blocked).toEqual({
      blocked: true,
      prompt: buildRestrictedCategoryGuestPrompt("搞七捻三"),
    });

    expect(resolveRestrictedCategorySelection(4, SAMPLE_CATEGORIES, false)).toEqual({
      blocked: false,
      categoryId: 4,
    });
    expect(resolveRestrictedCategorySelection(null, SAMPLE_CATEGORIES, false)).toEqual({
      blocked: false,
      categoryId: null,
    });
    expect(resolveRestrictedCategorySelection(34, SAMPLE_CATEGORIES, true)).toEqual({
      blocked: false,
      categoryId: 34,
    });
  });

  it("routes account management to GuestLoginGuideModal only when guest has 0 saved accounts", () => {
    expect(resolveAccountManagementTarget(false, 0)).toBe("guest_guide");
    expect(resolveAccountManagementTarget(false, 1)).toBe("auth_modal");
    expect(resolveAccountManagementTarget(true, 0)).toBe("auth_modal");
    expect(resolveAccountManagementTarget(true, 2)).toBe("auth_modal");
  });

  it("formats expired-session guest login prompt cleanly with username context", () => {
    expect(buildExpiredSessionGuestPrompt("lucas")).toEqual({
      title: "重新登录",
      subtitle: "@lucas 登录已过期，请选择登录方式",
    });
    expect(buildExpiredSessionGuestPrompt(null)).toEqual({
      title: "重新登录",
      subtitle: "登录已过期，请选择登录方式",
    });
  });
});

describe("GuestLoginGuideModal & WelcomeLoginScreen clean 3-method login UX", () => {
  it("renders all 3 standard login methods and saved-account switcher with zero Passkey footnotes or manual-login UI", () => {
    const onWeb = vi.fn();
    const onBrowser = vi.fn();
    const onQr = vi.fn();
    const onSaved = vi.fn();
    const onDismiss = vi.fn();

    const el = mountElement(
      React.createElement(GuestLoginGuideModal, {
        title: "登录 LINUX DO",
        subtitle: "选择登录方式",
        hasSavedAccounts: true,
        onPrimaryWebLogin: onWeb,
        onBrowserOneClickAuth: onBrowser,
        onOpenQrLogin: onQr,
        onOpenSavedAccounts: onSaved,
        onDismiss,
      })
    );

    const webBtn = findButtonByText(el, "使用 Linuxdo 登录");
    const browserBtn = findButtonByText(el, "系统浏览器授权");
    const qrBtn = findButtonByText(el, "二维码 / 口令登录");
    const savedBtn = findButtonByText(el, "切换已保存账号");
    const dismissBtn = findButtonByText(el, "继续以游客浏览");

    expect(webBtn).toBeTruthy();
    expect(browserBtn).toBeTruthy();
    expect(qrBtn).toBeTruthy();
    expect(savedBtn).toBeTruthy();
    expect(dismissBtn).toBeTruthy();

    expect(el.textContent).not.toContain("应用内网页登录");
    expect(el.textContent).not.toContain("Passkey");
    expect(el.textContent).not.toContain("手动凭证");
    expect(el.textContent).not.toContain("手动登录");
    expect(el.textContent).not.toContain("cURL");

    act(() => {
      webBtn!.click();
      browserBtn!.click();
      qrBtn!.click();
      savedBtn!.click();
      dismissBtn!.click();
    });

    expect(onWeb).toHaveBeenCalledTimes(1);
    expect(onBrowser).toHaveBeenCalledTimes(1);
    expect(onQr).toHaveBeenCalledTimes(1);
    expect(onSaved).toHaveBeenCalledTimes(1);
    expect(onDismiss).toHaveBeenCalledTimes(1);
  });

  it("hides saved-account shortcut when no saved accounts exist", () => {
    const el = mountElement(
      React.createElement(GuestLoginGuideModal, {
        title: "登录 LINUX DO",
        subtitle: "选择登录方式",
        hasSavedAccounts: false,
        onPrimaryWebLogin: vi.fn(),
        onBrowserOneClickAuth: vi.fn(),
        onOpenQrLogin: vi.fn(),
        onDismiss: vi.fn(),
      })
    );

    expect(findButtonByText(el, "切换已保存账号")).toBeUndefined();
    expect(findButtonByText(el, "手动凭证恢复")).toBeUndefined();
  });

  it("renders WelcomeLoginScreen cleanly without Passkey small print or manual paste inputs", () => {
    const el = mountElement(
      React.createElement(WelcomeLoginScreen, {
        isDark: false,
        hasSavedAccounts: false,
        statusMessage: "已在系统浏览器打开授权页，点击「授权」后返回即可",
        onPrimaryWebLogin: vi.fn(),
        onBrowserOneClickAuth: vi.fn(),
        onOpenQrLogin: vi.fn(),
        onGuestBrowse: vi.fn(),
      })
    );

    expect(findButtonByText(el, "使用 Linuxdo 登录")).toBeTruthy();
    expect(findButtonByText(el, "重新打开浏览器授权")).toBeTruthy();
    expect(findButtonByText(el, "二维码 / 口令登录")).toBeTruthy();
    expect(el.textContent).not.toContain("应用内网页登录");
    expect(el.textContent).not.toContain("Passkey");
    expect(el.textContent).not.toContain("粘贴回调链接");
    expect(el.textContent).not.toContain("_t");
    expect(el.querySelector("input")).toBeNull();
  });
});

describe("QrLoginModal & AuthModal parity and clean account management", () => {
  it("opens QrLoginModal redeem mode in locked-height 2-tab layout defaulting to 口令登录 with multi-line textarea and switching cleanly to 识图登录", async () => {
    const { invoke } = await import("@tauri-apps/api/core");
    const invokeMock = vi.mocked(invoke);
    invokeMock.mockClear();
    invokeMock.mockResolvedValue(true);

    const onImported = vi.fn();
    const onClose = vi.fn();
    const el = mountElement(
      React.createElement(QrLoginModal, {
        isAuthorized: false,
        isDark: true,
        currentUser: null,
        initialMode: "redeem",
        onImported,
        onClose,
      })
    );

    expect(el.textContent).toContain("在已登录设备的「我的 → 登录二维码」复制口令，或识别二维码图片登录。");

    const tokenTab = findButtonByText(el, "口令登录");
    const imageTab = findButtonByText(el, "识图登录");
    expect(tokenTab?.getAttribute("aria-selected")).toBe("true");
    expect(imageTab?.getAttribute("aria-selected")).toBe("false");

    const tabpanel = el.querySelector('[role="tabpanel"]') as HTMLElement | null;
    expect(tabpanel).toBeTruthy();
    expect(tabpanel!.className).toContain("h-[264px]");
    expect(tabpanel!.className).toContain("min-h-[264px]");
    const initialPanelClass = tabpanel!.className;

    // Default tab renders spacious multi-line textarea + reserved error slot + 粘贴口令 + 确认登录
    const textarea = el.querySelector(
      'textarea[placeholder*="lindo://qr-login"]'
    ) as HTMLTextAreaElement | null;
    expect(textarea).toBeTruthy();
    expect(textarea!.getAttribute("rows")).toBe("4");
    expect(textarea!.className).toContain("resize-none");
    expect(findButtonByText(el, "粘贴口令")).toBeTruthy();
    const confirmBtn = findButtonByText(el, "确认登录");
    expect(confirmBtn).toBeTruthy();
    expect(confirmBtn!.disabled).toBe(true);
    expect(el.textContent).not.toContain("选择或拖入二维码截图");
    const alertSlot = tabpanel!.querySelector('[role="alert"]') as HTMLElement | null;
    expect(alertSlot).toBeTruthy();
    expect(alertSlot!.className).toContain("h-[24px]");

    // Invalid token shows error inside reserved slot without altering locked tabpanel height
    const setTextareaValue = (node: HTMLTextAreaElement, value: string) => {
      const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, "value")?.set;
      setter?.call(node, value);
      node.dispatchEvent(new Event("input", { bubbles: true }));
    };
    act(() => {
      setTextareaValue(textarea!, "https://example.com/not-qr");
    });
    expect(confirmBtn!.disabled).toBe(false);
    await act(async () => {
      confirmBtn!.click();
    });
    expect(alertSlot!.textContent).toContain("不是 Lindo 登录二维码");
    expect(tabpanel!.className).toBe(initialPanelClass);

    // Switching to 识图登录 keeps exact same locked-height tabpanel container & clears error
    act(() => {
      imageTab!.click();
    });
    expect(tokenTab?.getAttribute("aria-selected")).toBe("false");
    expect(imageTab?.getAttribute("aria-selected")).toBe("true");
    expect(el.querySelector('[role="tabpanel"]')).toBe(tabpanel);
    expect(tabpanel!.className).toBe(initialPanelClass);
    expect(el.textContent).toContain("选择或拖入二维码截图");
    expect(findButtonByText(el, "从剪贴板识图")).toBeTruthy();
    expect(el.querySelector('textarea[placeholder*="lindo://qr-login"]')).toBeNull();
    const imageAlertSlot = tabpanel!.querySelector('[role="alert"]') as HTMLElement | null;
    expect(imageAlertSlot).toBeTruthy();
    expect(imageAlertSlot!.className).toContain("h-[24px]");
    expect(imageAlertSlot!.textContent).toBe("");

    // Switching back to 口令登录 and submitting a valid QR URI invokes credential import
    act(() => {
      tokenTab!.click();
    });
    const restoredTextarea = el.querySelector(
      'textarea[placeholder*="lindo://qr-login"]'
    ) as HTMLTextAreaElement | null;
    expect(restoredTextarea).toBeTruthy();
    const validUri = "lindo://qr-login?v=2&k=test_key_64&o=test_otp_64&u=lucas&exp=0";
    act(() => {
      setTextareaValue(restoredTextarea!, validUri);
    });
    const submitBtn = findButtonByText(el, "确认登录");
    await act(async () => {
      submitBtn!.click();
    });
    expect(invokeMock).toHaveBeenCalledWith(
      "import_manual_credential",
      expect.objectContaining({ rawInput: validUri })
    );
    expect(onImported).toHaveBeenCalledTimes(1);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("omits manual-login F12/cURL UI in AuthModal adding mode while keeping all 3 standard login methods", () => {
    const el = mountElement(
      React.createElement(AuthModal, {
        isAuthorized: false,
        isDark: true,
        currentUser: null,
        savedAccounts: [],
        initialAdding: true,
        onWebLogin: vi.fn(),
        onAddAccountWebLogin: vi.fn(),
        onBrowserOneClick: vi.fn(),
        onOpenQrLogin: vi.fn(),
        onSwitchAccount: vi.fn(),
        onRemoveAccount: vi.fn(),
        onLogout: vi.fn(),
        onClose: vi.fn(),
      })
    );

    expect(findButtonByText(el, "使用 Linuxdo 登录")).toBeTruthy();
    expect(findButtonByText(el, "系统浏览器授权")).toBeTruthy();
    expect(findButtonByText(el, "二维码 / 口令登录")).toBeTruthy();
    expect(el.textContent).not.toContain("应用内网页登录");
    expect(el.textContent).not.toContain("手动登录");
    expect(el.textContent).not.toContain("F12");
    expect(el.textContent).not.toContain("cURL");
    expect(el.querySelector("textarea")).toBeNull();
  });

  it("exposes 登录二维码 entry in logged-in AuthModal and routes guest add-account to GuestLoginGuideModal", () => {
    const saved: SavedAccountEntry[] = [
      { profile: SAMPLE_USER, cookies: "_t=abc", needsLogin: false, readFloors: {} },
      {
        profile: { ...SAMPLE_USER, userId: 102, username: "expired_u", displayName: "Expired" },
        cookies: "",
        needsLogin: true,
        readFloors: {},
      },
    ];
    const onOpenQrGenerate = vi.fn();
    const onClose = vi.fn();

    const authedEl = mountElement(
      React.createElement(AuthModal, {
        isAuthorized: true,
        isDark: true,
        currentUser: SAMPLE_USER,
        savedAccounts: saved,
        onWebLogin: vi.fn(),
        onAddAccountWebLogin: vi.fn(),
        onBrowserOneClick: vi.fn(),
        onOpenQrLogin: vi.fn(),
        onOpenQrGenerate,
        onSwitchAccount: vi.fn(),
        onRemoveAccount: vi.fn(),
        onLogout: vi.fn(),
        onClose,
      })
    );

    const qrGenBtn = findButtonByText(authedEl, "登录二维码");
    expect(qrGenBtn).toBeTruthy();
    expect(qrGenBtn!.textContent).toContain("扫码或复制口令登录其他设备");
    act(() => {
      qrGenBtn!.click();
    });
    expect(onOpenQrGenerate).toHaveBeenCalledTimes(1);
    expect(onClose).toHaveBeenCalledTimes(1);

    act(() => {
      activeRoot!.unmount();
    });
    activeRoot = null;
    activeContainer!.remove();
    activeContainer = null;

    const onRequestGuestLogin = vi.fn();
    const onSwitchAccount = vi.fn();
    const guestEl = mountElement(
      React.createElement(AuthModal, {
        isAuthorized: false,
        isDark: true,
        currentUser: null,
        savedAccounts: saved,
        onWebLogin: vi.fn(),
        onAddAccountWebLogin: vi.fn(),
        onBrowserOneClick: vi.fn(),
        onOpenQrLogin: vi.fn(),
        onRequestGuestLogin,
        onSwitchAccount,
        onRemoveAccount: vi.fn(),
        onLogout: vi.fn(),
        onClose: vi.fn(),
      })
    );

    expect(findButtonByText(guestEl, "登录二维码")).toBeUndefined();
    const expiredRow = findButtonByText(guestEl, "@expired_u");
    const activeRow = findButtonByText(guestEl, "@lucas");
    const addBtn = findButtonByText(guestEl, "添加账号");

    act(() => {
      expiredRow!.click();
      addBtn!.click();
      activeRow!.click();
    });
    expect(onRequestGuestLogin).toHaveBeenCalledTimes(2);
    expect(onSwitchAccount).toHaveBeenCalledWith("lucas");
  });

  it("HomeFeedPane delegates restricted category clicks to onSelectCategory without breaking guest guard", () => {
    const onSelectCategory = vi.fn();
    const el = mountElement(
      React.createElement(HomeFeedPane, {
        topics: [],
        categories: SAMPLE_CATEGORIES,
        currentSort: "latest",
        topPeriod: "daily",
        selectedCategoryId: null,
        selectedTag: null,
        selectedTopicId: null,
        isAuthorized: false,
        currentUser: null,
        unreadNotificationCount: 0,
        pendingNewCount: 0,
        isLoading: false,
        isLoadingMore: false,
        hasMore: false,
        errorMessage: null,
        loadMoreError: null,
        isCompact: true,
        readFloors: {},
        onSelectSort: vi.fn(),
        onSelectTopPeriod: vi.fn(),
        onSelectCategory,
        onSelectTag: vi.fn(),
        onSelectTopic: vi.fn(),
        onRefresh: vi.fn(),
        onApplyPendingNew: vi.fn(),
        onLoadMore: vi.fn(),
        onToggleCompact: vi.fn(),
        onOpenSearch: vi.fn(),
        onOpenNotifications: vi.fn(),
        onOpenAuth: vi.fn(),
        onTriggerVerify: vi.fn(),
      })
    );

    const catToggle = findButtonByText(el, "分类");
    expect(catToggle).toBeTruthy();
    act(() => {
      catToggle!.click();
    });

    const restrictedBtn = findButtonByText(el, "搞七捻三");
    expect(restrictedBtn).toBeTruthy();
    act(() => {
      restrictedBtn!.click();
    });
    expect(onSelectCategory).toHaveBeenCalledWith(34);
  });
});

describe("App.tsx login unification, narrow-screen guard & Phase 3 copy regressions", () => {
  const appSrc = fs.readFileSync(path.resolve(__dirname, "../App.tsx"), "utf8");
  const secondarySrc = fs.readFileSync(
    path.resolve(__dirname, "../components/SecondaryScreens.tsx"),
    "utf8"
  );
  const readerSrc = fs.readFileSync(
    path.resolve(__dirname, "../components/TopicReaderPane.tsx"),
    "utf8"
  );

  it("reuses handleSelectHomeCategory on BOTH wide and narrow HomeFeedPane", () => {
    const matches = appSrc.match(/onSelectCategory=\{handleSelectHomeCategory\}/g) ?? [];
    expect(matches.length).toBe(2);
    expect(appSrc).not.toContain("onSelectCategory={setSelectedCategoryId}");
  });

  it("routes wide & narrow Home, wide & narrow Messages, Profile, expired banner, and 0-account management to GuestLoginGuideModal", () => {
    const homeAuthMatches = appSrc.match(/onOpenAuth=\{\(\) => requestGuestLogin\(\)\}/g) ?? [];
    expect(homeAuthMatches.length).toBe(2);

    const msgAuthMatches = appSrc.match(/MESSAGES_GUEST_LOGIN_PROMPT\.title/g) ?? [];
    expect(msgAuthMatches.length).toBe(2);

    expect(appSrc).toContain("onRequestLogin={() =>");
    expect(appSrc).toContain("PROFILE_GUEST_LOGIN_PROMPT.title");
    expect(appSrc).toContain("onOpenAuthSheet={openAccountManagement}");
    expect(appSrc).toContain("buildExpiredSessionGuestPrompt(expiredUsername)");
    expect(appSrc).toContain("onOpenQrLogin={openQrLoginRedeem}");

    expect(secondarySrc).not.toContain("onPrimaryWebLogin={handlePrimaryWebLogin}");
    expect(appSrc).not.toContain("onOpenAuth={() => setShowAuthModal(true)}");
  });

  it("enforces zero 应用内网页登录, zero Passkey footnotes, zero manual-login F12/cURL UI, and humanized short-comment Boost copy", () => {
    for (const src of [appSrc, secondarySrc, readerSrc]) {
      expect(src).not.toContain("应用内网页登录");
      expect(src).not.toContain("Passkey");
      expect(src).not.toContain("手动登录");
      expect(src).not.toContain("手动凭证恢复");
      expect(src).not.toContain("Copy as cURL");
      expect(src).not.toContain("按 F12");
    }
    expect(secondarySrc).toContain("使用 Linuxdo 登录");

    // QR login internal redemption MUST stay intact
    expect(secondarySrc).toContain("importManualCredentialInRust(trimmed, isDark)");

    // Boost copy aligned 100% with Android (zero awkward 助力 UI copy)
    expect(readerSrc).toContain('placeholder="说点什么…"');
    expect(readerSrc).toContain('title="发送短评"');
    expect(readerSrc).toContain('title="收起短评"');
    expect(readerSrc).toContain('title="删除短评"');
    expect(readerSrc).toContain('title="发短评"');
    expect(secondarySrc).toContain('return "给你的帖子发了短评"');

    expect(readerSrc).not.toContain("助力…");
    expect(readerSrc).not.toContain("撤回助力");
    expect(readerSrc).not.toContain("小火箭助力");
    expect(readerSrc).not.toContain("助力发送失败");
    expect(secondarySrc).not.toContain("为你的帖子助力");
  });
});
