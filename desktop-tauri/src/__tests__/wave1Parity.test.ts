import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type {
  GrantedBadge,
  NotificationItem,
  UserPreferences,
  UserProfile,
} from "../lib/types";

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

import { clearLocalCachesInRust, mapSiteBadgeList } from "../lib/api";
import {
  ProfileSettingsView,
  PublicUserCardModal,
  SiteBadgesModal,
  classifyNotificationCategory,
  notificationFallbackVerb,
  resolveNotificationCornerIcon,
} from "../components/SecondaryScreens";
import { LinDoIcons } from "../components/LinDoIcons";

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

async function flushPromises(ticks = 6): Promise<void> {
  for (let i = 0; i < ticks; i += 1) {
    await act(async () => {
      await Promise.resolve();
    });
  }
}

function findButtonByText(container: HTMLElement, text: string): HTMLButtonElement | undefined {
  return Array.from(container.querySelectorAll("button")).find((btn) =>
    (btn.textContent || "").includes(text)
  );
}

function jsonResponse(body: any, status = 200) {
  return {
    status,
    body: JSON.stringify(body),
    isChallenge: false,
    isUnauthenticated: false,
  };
}

const DEFAULT_PREFS: UserPreferences = {
  appearance: "dark",
  themeOption: "gold",
  fontScale: 1.0,
  splitFraction: 0.42,
  compactFeed: false,
  defaultSearchExpanded: false,
  enableAnimations: true,
  echRoutingMode: "smart_auto",
};

const CURRENT_USER: UserProfile = {
  userId: 101,
  username: "lucas",
  displayName: "Lucas",
  avatarTemplate: "/user_avatar/linux.do/lucas/{size}/1.png",
  trustLevel: 3,
  userTitle: "社区贡献者",
};

beforeEach(() => {
  invokeMock.mockReset();
  openUrlMock.mockReset();
  localStorage.clear();
});

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

describe("P1-1: Notification Type 38 & DiscourseNotificationKind classifier alignment", () => {
  it("classifies type 38 (FOLLOWING_REPLIED) & type 3 (QUOTED) as REPLY, never Boost/Reaction", () => {
    expect(classifyNotificationCategory({ notificationType: 38 })).toBe("REPLY");
    expect(classifyNotificationCategory({ notificationType: 802 })).toBe("REPLY");
    expect(classifyNotificationCategory({ notificationType: 3 })).toBe("REPLY");
    expect(classifyNotificationCategory({ notificationType: 11 })).toBe("REPLY");
    expect(
      classifyNotificationCategory({ notificationType: 5, isConsolidatedReplies: true })
    ).toBe("REPLY");

    expect(notificationFallbackVerb(38)).toBe("发表了新回复");
    expect(notificationFallbackVerb(802)).toBe("发表了新回复");
    expect(notificationFallbackVerb(40)).toBe("给你的帖子发了短评");
    expect(notificationFallbackVerb(43)).toBe("给你的帖子发了短评");
    expect(notificationFallbackVerb(803)).toBe("给你的帖子发了短评");

    const n38: NotificationItem = {
      id: 1,
      notificationType: 38,
      read: false,
      createdAt: "2026-11-26T00:00:00Z",
      actorUsername: "alice",
      title: "某话题",
      excerpt: "",
    };
    const icon38 = resolveNotificationCornerIcon(n38) as React.ReactElement;
    expect(icon38.type).toBe(LinDoIcons.Reply);
    expect(icon38.type).not.toBe(LinDoIcons.BoostRocket);

    const n43: NotificationItem = { ...n38, id: 2, notificationType: 43 };
    const icon43 = resolveNotificationCornerIcon(n43) as React.ReactElement;
    expect(icon43.type).toBe(LinDoIcons.BoostRocket);
  });

  it("aligns SYSTEM, REACTION, MENTION buckets and extended verbs 1:1 with Android", () => {
    for (const t of [17, 37, 801, 24, 27, 34, 12, 36, 800]) {
      expect(classifyNotificationCategory({ notificationType: t })).toBe("SYSTEM");
    }
    for (const t of [5, 19, 25, 39, 40, 43, 803, 900]) {
      expect(classifyNotificationCategory({ notificationType: t })).toBe("REACTION");
    }
    for (const t of [1, 15, 29, 32, 33]) {
      expect(classifyNotificationCategory({ notificationType: t })).toBe("MENTION");
    }

    expect(notificationFallbackVerb(11)).toBe("关联了你的帖子");
    expect(notificationFallbackVerb(17)).toBe("发布了新话题");
    expect(notificationFallbackVerb(37)).toBe("发布了新话题");
    expect(notificationFallbackVerb(801)).toBe("发布了新话题");
    expect(notificationFallbackVerb(24)).toBe("书签提醒");
    expect(notificationFallbackVerb(27)).toBe("活动提醒");
    expect(notificationFallbackVerb(34)).toBe("分配了任务给你");
    expect(notificationFallbackVerb(39)).toBe("赞同了你的帖子");
    expect(notificationFallbackVerb(900)).toBe("赞同了你的帖子");
  });
});

describe("P0-3: SiteBadgesModal snake_case -> GrantedBadge camelCase mapping", () => {
  it("maps raw Discourse /badges.json fields and displays grant counts & passes camelCase badge to onInspectBadge", async () => {
    const rawBadges = [
      {
        id: 77,
        name: "金徽章测试",
        description: "累计获得 50 次精选赞同",
        long_description: "完整长说明：社区杰出贡献",
        badge_type_id: 1,
        icon: "fa-certificate",
        image_url: "/uploads/badge_gold.png",
        grant_count: 1520,
        allow_title: true,
        slug: "gold-tester",
      },
    ];

    const mapped = mapSiteBadgeList(rawBadges, false);
    expect(mapped).toHaveLength(1);
    expect(mapped[0]).toMatchObject({
      id: 77,
      name: "金徽章测试",
      longDescription: "完整长说明：社区杰出贡献",
      badgeTypeId: 1,
      imageUrl: "/uploads/badge_gold.png",
      grantCount: 1520,
      allowTitle: true,
      isEarned: false,
    });

    invokeMock.mockImplementation(async (cmd: string, args: any) => {
      if (cmd === "site_request" && String(args?.url).includes("/badges.json")) {
        return jsonResponse({ badges: rawBadges });
      }
      return jsonResponse({});
    });

    const onInspectBadge = vi.fn();
    const el = mountElement(
      React.createElement(SiteBadgesModal, {
        onInspectBadge,
        onClose: vi.fn(),
      })
    );
    await flushPromises();

    expect(el.textContent).toContain("金徽章测试");
    expect(el.textContent).toContain("1.5k 人已获得");

    const badgeBtn = findButtonByText(el, "金徽章测试");
    expect(badgeBtn).toBeTruthy();
    act(() => {
      badgeBtn!.click();
    });

    expect(onInspectBadge).toHaveBeenCalledTimes(1);
    const passed: GrantedBadge = onInspectBadge.mock.calls[0][0];
    expect(passed.badgeTypeId).toBe(1);
    expect(passed.grantCount).toBe(1520);
    expect(passed.imageUrl).toBe("/uploads/badge_gold.png");
    expect(passed.longDescription).toBe("完整长说明：社区杰出贡献");
    expect(passed.allowTitle).toBe(true);
    expect(passed.isEarned).toBe(false);
  });
});

describe("P0-2: Real clear_local_caches command & ProfileSettingsView UI wiring", () => {
  it("invokes Rust clear_local_caches and displays freed size notice after confirmation", async () => {
    invokeMock.mockImplementation(async (cmd: string) => {
      if (cmd === "clear_local_caches") {
        return { freedBytes: 2 * 1024 * 1024, clearedEntries: 14 };
      }
      return jsonResponse({});
    });

    const direct = await clearLocalCachesInRust();
    expect(direct).toEqual({ freedBytes: 2 * 1024 * 1024, clearedEntries: 14 });
    expect(invokeMock).toHaveBeenCalledWith("clear_local_caches");

    invokeMock.mockClear();
    const el = mountElement(
      React.createElement(ProfileSettingsView, {
        isAuthorized: true,
        isDark: true,
        currentUser: CURRENT_USER,
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
      })
    );

    const rowBtn = findButtonByText(el, "清理本地缓存");
    expect(rowBtn).toBeTruthy();
    act(() => {
      rowBtn!.click();
    });
    expect(el.textContent).toContain("清理缓存？");

    const confirmBtn = Array.from(el.querySelectorAll("button")).find(
      (btn) => (btn.textContent || "").trim() === "清理"
    );
    expect(confirmBtn).toBeTruthy();
    await act(async () => {
      confirmBtn!.click();
      await Promise.resolve();
    });
    await flushPromises();

    expect(invokeMock).toHaveBeenCalledWith("clear_local_caches");
    expect(el.textContent).toContain("已清理 2.0 MB");
  });
});

describe("P0-1: PublicUserCardModal full tabbed profile, safety guards, follow rollback & retry", () => {
  function mockFullAliceEndpoints(options?: {
    followingList?: Array<{ username: string }>;
    failToggleFollow?: boolean;
    topicPageCount?: number;
  }) {
    const topicPageCount = options?.topicPageCount ?? 20;
    const topicPage0 = Array.from({ length: topicPageCount }, (_, idx) => ({
      topic_id: 5000 + idx,
      post_number: 1,
      title: `主题标题 #${idx + 1}`,
      excerpt: `主题摘要 ${idx + 1}`,
      action_type: 4,
      created_at: "2026-11-25T10:00:00Z",
      category_id: 4,
    }));
    const topicPage1 = [
      {
        topic_id: 6001,
        post_number: 1,
        title: "第二页加载的主题",
        excerpt: "分页摘要",
        action_type: 4,
        created_at: "2026-11-20T10:00:00Z",
        category_id: 4,
      },
    ];

    invokeMock.mockImplementation(async (cmd: string, args: any) => {
      if (cmd !== "site_request") return {};
      const url = String(args?.url || "");
      const method = String(args?.method || "GET");

      if (url.endsWith("/u/alice.json")) {
        return jsonResponse({
          user: {
            id: 42,
            username: "alice",
            name: "Alice Lin",
            avatar_template: "/user_avatar/linux.do/alice/{size}/1.png",
            trust_level: 3,
            moderator: true,
            title: "核心维护者",
            bio_excerpt:
              '欢迎访问 <a href="https://example.org/blog">我的博客</a> 和 <a href="https://linux.do/t/topic/8888/3">精华帖</a>',
            primary_group_name: "devs",
            created_at: "2024-03-15T08:00:00Z",
            last_seen_at: "2026-11-25T12:00:00Z",
            location: "上海",
            website: "https://alice.dev",
            website_name: "alice.dev",
            badge_count: 2,
            can_follow: true,
            status: { emoji: "☕", description: "写代码中" },
          },
          badges: [
            {
              id: 10,
              name: "精选徽章",
              description: "简介",
              badge_type_id: 2,
              grant_count: 88,
            },
          ],
        });
      }
      if (url.endsWith("/u/alice/summary.json")) {
        return jsonResponse({
          user_summary: {
            likes_received: 1280,
            likes_given: 340,
            days_visited: 265,
            time_read: 72000,
            topics_entered: 980,
            posts_read_count: 15400,
            topic_count: 12,
            post_count: 45,
            solved_count: 18,
          },
        });
      }
      if (url.includes("/u/lucas/follow/following.json")) {
        return jsonResponse(options?.followingList ?? []);
      }
      if (url.includes("/user_actions.json") && url.includes("filter=4") && url.includes("offset=20")) {
        return jsonResponse({ user_actions: topicPage1 });
      }
      if (url.includes("/user_actions.json") && url.includes("filter=4")) {
        return jsonResponse({ user_actions: topicPage0 });
      }
      if (url.includes("/user_actions.json")) {
        return jsonResponse({
          user_actions: [
            {
              topic_id: 7001,
              post_number: 5,
              title: "最新一条回复动态",
              excerpt: "动态正文摘录",
              action_type: 5,
              created_at: "2026-11-26T01:00:00Z",
              category_id: 4,
            },
          ],
        });
      }
      if (url.includes("/user-badges/alice.json")) {
        return jsonResponse({
          badges: [
            {
              id: 10,
              name: "精选徽章",
              description: "优质回复获得",
              badge_type_id: 2,
              grant_count: 88,
            },
            {
              id: 20,
              name: "常驻老友",
              description: "连续活跃",
              badge_type_id: 1,
              grant_count: 15,
            },
          ],
          user_badges: [
            { id: 901, badge_id: 10, count: 3, granted_at: "2025-08-01T00:00:00Z" },
            { id: 902, badge_id: 20, count: 1, granted_at: "2025-09-12T00:00:00Z" },
          ],
        });
      }
      if (url.includes("/follow/alice.json") && (method === "PUT" || method === "DELETE")) {
        if (options?.failToggleFollow) {
          return jsonResponse({ errors: ["操作过于频繁"] }, 422);
        }
        return jsonResponse({ ok: true });
      }
      return jsonResponse({});
    });
  }

  it("renders full 540px profile with status emoji+text, 6 metadata items, 3+5 stats, bio link guards, pagination & lazy badges", async () => {
    mockFullAliceEndpoints();
    const onOpenTopic = vi.fn();
    const onOpenSiteBadges = vi.fn();
    const onInspectBadge = vi.fn();
    const onClose = vi.fn();

    const el = mountElement(
      React.createElement(PublicUserCardModal, {
        username: "alice",
        isAuthorized: true,
        currentUsername: "lucas",
        onStartPm: vi.fn(),
        onOpenTopic,
        onInspectBadge,
        onOpenSiteBadges,
        onClose,
      })
    );
    await flushPromises();

    // 540px modal shell + identity header + moderator shield + inline status emoji & description
    expect(el.innerHTML).toContain("w-[540px]");
    expect(el.textContent).toContain("Alice Lin");
    expect(el.textContent).toContain("@alice · Lv.3 活跃用户");
    expect(el.querySelector('[aria-label="版主"]')).toBeTruthy();
    expect(el.textContent).toContain("☕");
    expect(el.textContent).toContain("写代码中");

    // 6 metadata items
    expect(el.textContent).toContain("加入于 2024-03-15");
    expect(el.textContent).toContain("最近活跃");
    expect(el.textContent).toContain("主用户组 devs");
    expect(el.textContent).toContain("所在地 上海");
    expect(el.textContent).toContain("alice.dev");
    expect(el.textContent).toContain("已解答 18");

    // 3+5 community stats toggle
    expect(el.textContent).toContain("获赞");
    expect(el.textContent).toContain("访问天数");
    expect(el.textContent).toContain("阅读时长");
    expect(el.textContent).not.toContain("浏览话题");
    const expandStatsBtn = findButtonByText(el, "社区数据");
    expect(expandStatsBtn).toBeTruthy();
    act(() => {
      expandStatsBtn!.click();
    });
    expect(el.textContent).toContain("浏览话题");
    expect(el.textContent).toContain("阅读帖子");
    expect(el.textContent).toContain("送出赞");
    expect(el.textContent).toContain("发表主题");
    expect(el.textContent).toContain("发表回复");

    // Bio external link -> opens 5-tier ExternalLinkSafetyModal
    const blogAnchor = Array.from(el.querySelectorAll("a")).find((a) =>
      (a.textContent || "").includes("我的博客")
    );
    expect(blogAnchor).toBeTruthy();
    act(() => {
      blogAnchor!.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true }));
    });
    expect(el.textContent).toContain("即将离开 LINUX DO");
    expect(el.textContent).toContain("example.org");
    const cancelExtBtn = findButtonByText(el, "取消");
    act(() => {
      cancelExtBtn!.click();
    });

    // Website metadata button -> opens ExternalLinkSafetyModal for alice.dev
    const siteBtn = findButtonByText(el, "alice.dev");
    act(() => {
      siteBtn!.click();
    });
    expect(el.textContent).toContain("alice.dev");
    expect(el.textContent).toContain("即将离开 LINUX DO");
    act(() => {
      findButtonByText(el, "取消")!.click();
    });

    // Bio internal forum topic link -> routes directly to onOpenTopic
    const internalAnchor = Array.from(el.querySelectorAll("a")).find((a) =>
      (a.textContent || "").includes("精华帖")
    );
    act(() => {
      internalAnchor!.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true }));
    });
    expect(onOpenTopic).toHaveBeenCalledWith(8888, "社区话题", 3);

    // 4 sticky tabs + topic tab offset pagination
    const topicsTab = findButtonByText(el, "主题 (12)");
    expect(topicsTab).toBeTruthy();
    await act(async () => {
      topicsTab!.click();
      await Promise.resolve();
    });
    await flushPromises();
    expect(el.textContent).toContain("主题标题 #1");

    const loadMoreBtn = findButtonByText(el, "加载更多");
    expect(loadMoreBtn).toBeTruthy();
    await act(async () => {
      loadMoreBtn!.click();
      await Promise.resolve();
    });
    await flushPromises();
    expect(el.textContent).toContain("第二页加载的主题");

    // Badges tab lazily calls /user-badges/alice.json, shows ×3 + grant date + site badges CTA
    const badgesTab = findButtonByText(el, "徽章 (");
    expect(badgesTab).toBeTruthy();
    await act(async () => {
      badgesTab!.click();
      await Promise.resolve();
    });
    await flushPromises();
    expect(el.textContent).toContain("常驻老友");
    expect(el.textContent).toContain("×3");
    expect(el.textContent).toContain("2025-08-01");

    const siteWallBtn = findButtonByText(el, "查看全站徽章墙");
    expect(siteWallBtn).toBeTruthy();
    act(() => {
      siteWallBtn!.click();
    });
    expect(onOpenSiteBadges).toHaveBeenCalledTimes(1);
  });

  it("gates guest Follow/PM to onRequestGuestLogin, cross-checks following list, rolls back on follow error, and offers Retry on load failure", async () => {
    mockFullAliceEndpoints();
    const onRequestGuestLogin = vi.fn();
    const onStartPm = vi.fn();

    // 1) Guest gating
    const guestEl = mountElement(
      React.createElement(PublicUserCardModal, {
        username: "alice",
        isAuthorized: false,
        onStartPm,
        onOpenTopic: vi.fn(),
        onRequestGuestLogin,
        onClose: vi.fn(),
      })
    );
    await flushPromises();

    act(() => {
      findButtonByText(guestEl, "+ 关注")!.click();
      findButtonByText(guestEl, "发私信")!.click();
    });
    expect(onRequestGuestLogin).toHaveBeenCalledTimes(2);
    expect(onStartPm).not.toHaveBeenCalled();
    unmountActive();

    // 2) Authorized follow authoritative state + rollback on error
    mockFullAliceEndpoints({ followingList: [{ username: "alice" }], failToggleFollow: true });
    const authEl = mountElement(
      React.createElement(PublicUserCardModal, {
        username: "alice",
        isAuthorized: true,
        currentUsername: "lucas",
        onStartPm: vi.fn(),
        onOpenTopic: vi.fn(),
        onClose: vi.fn(),
      })
    );
    await flushPromises();
    expect(findButtonByText(authEl, "已关注")).toBeTruthy();

    await act(async () => {
      findButtonByText(authEl, "已关注")!.click();
      await Promise.resolve();
    });
    await flushPromises();

    expect(findButtonByText(authEl, "已关注")).toBeTruthy();
    expect(authEl.querySelector('[role="alert"]')?.textContent).toContain("操作过于频繁");
    unmountActive();

    // 3) Load failure -> error + Retry button recovers cleanly
    let shouldFail = true;
    invokeMock.mockImplementation(async (cmd: string, args: any) => {
      if (cmd !== "site_request") return {};
      const url = String(args?.url || "");
      if (shouldFail && (url.includes("/u/broken.json") || url.includes("/u/broken/card.json"))) {
        return jsonResponse({ errors: ["暂时无法访问"] }, 500);
      }
      if (url.includes("/u/broken.json")) {
        return jsonResponse({
          user: { id: 9, username: "broken", name: "Recovered User", trust_level: 2 },
        });
      }
      return jsonResponse({});
    });

    const errEl = mountElement(
      React.createElement(PublicUserCardModal, {
        username: "broken",
        isAuthorized: false,
        onStartPm: vi.fn(),
        onOpenTopic: vi.fn(),
        onClose: vi.fn(),
      })
    );
    await flushPromises();
    expect(errEl.textContent).toContain("重试");
    expect(errEl.textContent).not.toContain("Recovered User");

    shouldFail = false;
    await act(async () => {
      findButtonByText(errEl, "重试")!.click();
      await Promise.resolve();
    });
    await flushPromises();
    expect(errEl.textContent).toContain("Recovered User");
  });
});

describe("P0-6: Native 账号与偏好 modal (with Edit Profile strictly hidden)", () => {
  it("loads account prefs, toggles privacy switches with rollback safety, routes web links, and exposes zero profile-edit inputs", async () => {
    const putCalls: Array<{ url: string; form: any }> = [];
    let failNextPut = false;

    invokeMock.mockImplementation(async (cmd: string, args: any) => {
      if (cmd !== "site_request") return {};
      const url = String(args?.url || "");
      const method = String(args?.method || "GET");
      if (method === "PUT" && url.includes("/u/lucas.json")) {
        putCalls.push({ url, form: args?.form });
        if (failNextPut) {
          return jsonResponse({ errors: ["网络异常"] }, 500);
        }
        return jsonResponse({ user: { username: "lucas" } });
      }
      if (url.endsWith("/u/lucas.json")) {
        return jsonResponse({
          user: {
            username: "lucas",
            name: "Lucas",
            email: "lucas@example.com",
            trust_level: 3,
            title: "社区贡献者",
            created_at: "2023-06-01T00:00:00Z",
            second_factor_enabled: true,
            user_option: {
              hide_profile_and_presence: false,
              allow_private_messages: true,
            },
            user_auth_tokens: [{ id: 1 }, { id: 2 }, { id: 3 }],
            associated_accounts: [
              { name: "google_oauth2", description: "lucas@gmail.com" },
              { name: "github", description: "lucas-dev" },
            ],
          },
        });
      }
      if (url.includes("/user-badges/lucas.json")) {
        return jsonResponse({ badges: [], user_badges: [] });
      }
      return jsonResponse({});
    });

    const onOpenInAppUrl = vi.fn();
    const onProfileUpdated = vi.fn();
    const el = mountElement(
      React.createElement(ProfileSettingsView, {
        isAuthorized: true,
        isDark: true,
        currentUser: CURRENT_USER,
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
        onOpenInAppUrl,
        onRequestLogout: vi.fn(),
        onOpenTopicById: vi.fn(),
        onResumeComposerDraft: vi.fn(),
        onInspectUser: vi.fn(),
        onHistoryCleared: vi.fn(),
        onDraftsChanged: vi.fn(),
        onProfileUpdated,
      })
    );

    const entryBtn = findButtonByText(el, "账号与偏好");
    expect(entryBtn).toBeTruthy();
    await act(async () => {
      entryBtn!.click();
      await Promise.resolve();
    });
    await flushPromises();

    // Displays email, 2FA, active sessions, and associated OAuth accounts
    expect(el.textContent).toContain("lucas@example.com");
    expect(el.textContent).toContain("已开启");
    expect(el.textContent).toContain("3 个登录会话");
    expect(el.textContent).toContain("Google 账号");
    expect(el.textContent).toContain("GitHub");

    // Strictly NO native Edit Profile inputs/textareas inside the modal
    expect(el.querySelectorAll("textarea")).toHaveLength(0);
    expect(el.querySelectorAll('input[type="text"]')).toHaveLength(0);

    // Privacy switch toggles via PUT /u/lucas.json
    const hidePresenceSwitch = Array.from(
      el.querySelectorAll<HTMLButtonElement>('button[role="switch"]')
    ).find((b) => (b.textContent || "").includes("隐藏公开资料与在线状态"))!;
    const allowPmSwitch = Array.from(
      el.querySelectorAll<HTMLButtonElement>('button[role="switch"]')
    ).find((b) => (b.textContent || "").includes("允许站内私信"))!;
    expect(hidePresenceSwitch).toBeTruthy();
    expect(allowPmSwitch).toBeTruthy();
    expect(hidePresenceSwitch.getAttribute("aria-checked")).toBe("false");
    expect(allowPmSwitch.getAttribute("aria-checked")).toBe("true");

    await act(async () => {
      hidePresenceSwitch.click();
      await Promise.resolve();
    });
    await flushPromises();

    expect(putCalls).toHaveLength(1);
    expect(putCalls[0].form).toMatchObject({
      hide_profile_and_presence: "true",
      allow_private_messages: "true",
    });
    expect(hidePresenceSwitch.getAttribute("aria-checked")).toBe("true");
    expect(onProfileUpdated).toHaveBeenCalledTimes(1);

    // Rollback on failure
    failNextPut = true;
    await act(async () => {
      allowPmSwitch.click();
      await Promise.resolve();
    });
    await flushPromises();
    expect(allowPmSwitch.getAttribute("aria-checked")).toBe("true");
    expect(el.textContent).toContain("网络异常");

    // Web preference shortcut routes cleanly via onOpenInAppUrl
    const notifPrefRow = findButtonByText(el, "回复、提及与互动提醒");
    expect(notifPrefRow).toBeTruthy();
    act(() => {
      notifPrefRow!.click();
    });
    expect(onOpenInAppUrl).toHaveBeenCalledWith("https://linux.do/my/preferences/notifications");
  });
});
