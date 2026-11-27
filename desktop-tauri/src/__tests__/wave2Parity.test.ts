import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type {
  CategoryMeta,
  LocalDraftRecord,
  PostItemModel,
  TopicDetailModel,
  TopicSummary,
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

import {
  BACKOFF_DELAYS_MS,
  FLUSH_INTERVAL_MS,
  MAX_TRACKING_TIME_PER_POST_MS,
  PAUSE_UNLESS_SCROLLED_MS,
  ScreenTrackController,
  type TimingBatchPayload,
} from "../lib/screenTrack";
import {
  clearLocalCachesInRust,
  fetchCloudDrafts,
  fetchSingleCloudDraft,
  fetchTopicFeed,
  resetNotificationReadState,
  publishReply,
  publishTopic,
  updateTopicMetadata,
  saveBookmarkWithDetails,
  sendPrivateMessage,
  fetchSiteCustomEmojis,
  fetchUserFollowList,
  sendTopicTimingsBatch,
} from "../lib/api";
import { renderDiscourseMarkdownPreview } from "../lib/markdownPreview";
import {
  COMPOSER_CALLOUT_KINDS,
  COMPOSER_CODE_LANGUAGES,
  ComposerModal,
  buildDiscoursePollSnippet,
  buildMarkdownTableSnippet,
} from "../components/ComposerModal";
import {
  BadgeDetailModal,
  GlobalSearchModal,
  HOT_SEARCH_KEYWORDS,
  MessagesView,
  ProfileSettingsView,
  isValidSearchQuery,
} from "../components/SecondaryScreens";
import { HomeFeedPane } from "../components/HomeFeedPane";
import { TopicReaderPane, computeMiddlePostGapIds } from "../components/TopicReaderPane";

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
  userTitle: null,
};

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
    canCreateTopic: true,
  },
];

function makePost(id: number, postNumber: number): PostItemModel {
  return {
    id,
    topicId: 900,
    postNumber,
    username: `user${postNumber}`,
    displayName: `User ${postNumber}`,
    avatarTemplate: "/a.png",
    cookedHtml: `<p>第 ${postNumber} 楼正文内容</p>`,
    createdAt: "2026-11-25T10:00:00Z",
    trustLevel: 2,
    isModerator: false,
    isAdmin: false,
    likeCount: 0,
    liked: false,
    bookmarked: false,
    replyCount: 0,
    reactions: [],
    boosts: [],
    polls: [],
    canEdit: false,
    canDelete: false,
    acceptedAnswer: false,
    canAcceptAnswer: false,
  };
}

function makeTopicSummary(id = 900): TopicSummary {
  return {
    id,
    title: "归档与跳楼测试话题",
    slug: "archive-gap-test",
    postsCount: 5,
    replyCount: 4,
    views: 120,
    likeCount: 6,
    highestPostNumber: 5,
    unreadPosts: 0,
    unseen: false,
    pinned: false,
    closed: false,
    archived: true,
    bookmarked: false,
    tags: [],
    createdAt: "2026-11-25T10:00:00Z",
    lastPostedAt: "2026-11-25T11:00:00Z",
    originalPoster: {
      userId: 1,
      username: "user1",
      name: "User 1",
      avatarTemplate: "/a.png",
    },
  };
}

beforeEach(() => {
  resetNotificationReadState();
  invokeMock.mockReset();
  openUrlMock.mockReset();
  localStorage.clear();
});

afterEach(() => {
  unmountActive();
});

describe("forum writing and reading", () => {
  it("keeps the draft when switching between the three editor icon buttons", async () => {
    const el = mountElement(React.createElement(ComposerModal, {
      request: { action: "REPLY", topicId: 7, replyToPostNumber: 2, replyToUsername: "alice", replyToSnippet: "所回复的楼层", initialRaw: "切换预览时保留的正文" },
      categories: SAMPLE_CATEGORIES, onTopicEdited: vi.fn(), onPublished: vi.fn(), onClose: vi.fn(), onTriggerVerify: vi.fn(),
    }));
    await flushPromises();
    const header = el.querySelector("header")!;
    for (const name of ["分栏", "预览", "编辑"]) {
      const mode = header.querySelector<HTMLButtonElement>(`button[aria-label="${name}"]`)!;
      expect(mode.textContent).toBe("");
      expect(mode.querySelector("svg")).toBeTruthy();
      act(() => mode.click());
      expect(mode.getAttribute("aria-pressed")).toBe("true");
      expect(header.querySelectorAll('[aria-pressed="true"]')).toHaveLength(1);
      expect(Boolean(el.querySelector("textarea"))).toBe(name !== "预览");
    }
    expect(el.querySelector("textarea")!.value).toBe("切换预览时保留的正文");
    expect(header.querySelector('button[aria-label="发送"] svg')).toBeTruthy();
  });

  it.each(["title", "body"])("submits only the changed first-post %s", async (field) => {
    const published = vi.fn();
    const edited = vi.fn();
    const writes: { url: string; form: Record<string, string> }[] = [];
    invokeMock.mockImplementation(async (cmd: string, args: any) => {
      if (cmd !== "site_request") return {};
      if (args.method === "PUT") {
        writes.push(args);
        return jsonResponse({ post: { id: 9, post_number: 1, topic_id: 7, username: "alice" } });
      }
      return jsonResponse({ results: [] });
    });
    const el = mountElement(React.createElement(ComposerModal, {
      request: { action: "EDIT", topicId: 7, postId: 9, postNumber: 1, initialTitle: field === "body" ? "旧帖" : "原来的话题标题足够长", initialCategoryId: 42, initialRaw: field === "title" ? "旧文" : "原来的帖子正文" },
      categories: SAMPLE_CATEGORIES, onTopicEdited: edited, onPublished: published, onClose: vi.fn(), onTriggerVerify: vi.fn(),
    }));
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 20)); });
    const input = el.querySelector(field === "title" ? 'input[placeholder="话题标题"]' : "textarea")!;
    const value = field === "title" ? "修改后的话题标题足够长" : "修改后的帖子正文";
    act(() => {
      Object.getOwnPropertyDescriptor(Object.getPrototypeOf(input), "value")!.set!.call(input, value);
      input.dispatchEvent(new Event("input", { bubbles: true }));
    });
    await act(async () => { el.querySelector<HTMLButtonElement>('button[aria-label="保存"]')!.click(); });
    expect(writes.map((r) => [new URL(r.url).pathname, r.form])).toEqual(field === "title"
      ? [["/t/-/7.json", { title: value }]] : [["/posts/9.json", { "post[raw]": value }]]);
    expect(published).toHaveBeenCalledOnce();
    if (field === "title") expect(edited).toHaveBeenCalledWith(7, { title: value });
  });

  it("compares a resumed edit draft with the published original and submits its saved text", async () => {
    invokeMock.mockImplementation(async (cmd: string, args: any) => {
      if (cmd !== "site_request") return {};
      if (args.url.endsWith("/posts/9.json")) return jsonResponse({ id: 9, topic_id: 7, post_number: 3, raw: "原来的正文" });
      return jsonResponse({});
    });
    const published = vi.fn();
    const el = mountElement(React.createElement(ComposerModal, {
      request: { action: "EDIT", topicId: 7, postId: 9, postNumber: 3, initialRaw: "上次保存的修改正文", draftKeyOverride: "edit_9" },
      categories: SAMPLE_CATEGORIES, onTopicEdited: vi.fn(), onPublished: published, onClose: vi.fn(), onTriggerVerify: vi.fn(),
    }));
    await flushPromises();
    expect(el.querySelector("textarea")!.value).toBe("上次保存的修改正文");
    await act(async () => { el.querySelector<HTMLButtonElement>('button[aria-label="保存"]')!.click(); });
    expect(invokeMock).toHaveBeenCalledWith("site_request", expect.objectContaining({
      method: "PUT", form: { "post[raw]": "上次保存的修改正文" },
    }));
    expect(published).toHaveBeenCalledOnce();
  });

  it("sends an explicit removal when clearing the last topic tag", async () => {
    invokeMock.mockResolvedValue(jsonResponse({}));
    await updateTopicMetadata(7, { tags: [] });
    expect(invokeMock).toHaveBeenCalledWith("site_request", expect.objectContaining({ form: { tags: "" } }));
  });

  it("keeps unconfirmed writes separate from moderation-queue receipts", async () => {
    const topic = { categoryId: 4, title: "新的话题标题", tags: [], raw: "一段足够长的正文" };
    const reply = { topicId: 7, raw: topic.raw, replyToPostNumber: 3 };
    const message = { recipients: "alice", title: topic.title, raw: topic.raw };
    invokeMock.mockResolvedValue(jsonResponse({}));
    await expect(publishTopic(topic)).rejects.toThrow("回执");
    await expect(publishReply(reply)).rejects.toThrow("回执");
    await expect(sendPrivateMessage(message)).rejects.toThrow("回执");
    await expect(saveBookmarkWithDetails({ postId: 9 })).rejects.toThrow("书签编号");
    invokeMock.mockResolvedValue(jsonResponse({ action: "enqueued", success: true }));
    await expect(publishTopic(topic)).resolves.toMatchObject({ pendingReview: true });
    await expect(publishReply(reply)).resolves.toMatchObject({ pendingReview: true });
    await expect(sendPrivateMessage(message)).resolves.toBe(0);
  });

  it("reads the current server sequence even when the cloud draft is empty", async () => {
    invokeMock.mockResolvedValue(jsonResponse({ draft: null, draft_sequence: 17 }));
    await expect(fetchSingleCloudDraft("new_topic")).resolves.toEqual({ draft: null, sequence: 17 });
    invokeMock.mockResolvedValue(jsonResponse({
      draft: JSON.stringify({ action: "createTopic", reply: "草稿内容", tags: ["kotlin", { id: 7, name: "linux" }] }),
      draft_sequence: 18,
    }));
    await expect(fetchSingleCloudDraft("new_topic")).resolves.toMatchObject({ draft: { tags: ["kotlin", "linux"] }, sequence: 18 });
  });

  it("preserves the badge when marking all notifications read fails", async () => {
    const cleared = vi.fn();
    invokeMock.mockImplementation(async (cmd: string, args: any) => {
      if (cmd !== "site_request") return {};
      if (args.url.includes("mark-read")) return jsonResponse({ errors: ["操作失败"] }, 503);
      return jsonResponse({ notifications: [] });
    });
    const el = mountElement(React.createElement(MessagesView, {
      isAuthorized: true, currentUser: CURRENT_USER, selectedTopicId: null, onOpenNotificationTopic: vi.fn(),
      onUnreadCleared: cleared, onOpenAuth: vi.fn(), onOpenChat: vi.fn(),
    }));
    await flushPromises();
    await act(async () => { findButtonByText(el, "全部标为已读")!.click(); });
    expect(cleared).not.toHaveBeenCalled();
    expect(el.textContent).toContain("操作失败");
  });

  it("sends both category and tag filters for a sorted feed", async () => {
    invokeMock.mockResolvedValue(jsonResponse({ topic_list: { topics: [], more_topics_url: null } }));
    const page = await fetchTopicFeed({ sort: "top", categoryId: 4, tag: "kotlin", topPeriod: "weekly" });
    expect(page.hasMore).toBe(false);
    expect(invokeMock).toHaveBeenCalledWith("site_request", expect.objectContaining({
      url: "https://linux.do/tags/c/develop/4/kotlin/l/top.json?no_definitions=true&period=weekly",
    }));
  });

  it("uses the follow cursor after filtering and lists each topic once", async () => {
    invokeMock.mockImplementation(async (cmd: string, args: any) => {
      if (cmd !== "site_request") return {};
      const more = !new URL(args.url).searchParams.has("created_before");
      const topic = { id: more ? 7 : 9, tags: [more ? "one" : "two"] };
      return jsonResponse({ posts: [4, 3].map((n) => ({ topic, post_number: n, created_at: "2026-11-20" })), extras: { has_more: more } });
    });
    const params = { username: "alice", sort: "follow", tag: "two" };
    await expect(fetchTopicFeed(params)).resolves.toEqual({ topics: [], hasMore: true });
    const next = await fetchTopicFeed({ ...params, page: 1 });
    expect(next.topics.map((item) => item.id)).toEqual([9]);
    expect(next.hasMore).toBe(false);
  });
});

describe("P1-2: Real dwell-time ScreenTrack engine & official Discourse headers", () => {
  it("tracks real dwell ms, rushes unread posts >=1s, pauses on 3m idle & document hidden, caps at 6m, and retries failed batches with backoff", async () => {
    let now = 1_000_000;
    const sent: TimingBatchPayload[] = [];
    const confirmed: Array<[number, number]> = [];
    let failFirstSend = false;

    const tracker = new ScreenTrackController({
      now: () => now,
      sendBatch: async (payload) => {
        sent.push(payload);
        if (failFirstSend) {
          failFirstSend = false;
          return "retryable";
        }
        return "ok";
      },
      onConfirmed: (tid, floor) => confirmed.push([tid, floor]),
    });

    // 1) Unread post rushes after >= 1s real dwell
    tracker.onTopicEntered(500, [1, 2], 0, now);
    now += 1200;
    tracker.tick(now);
    await flushPromises(3);
    expect(sent).toHaveLength(1);
    expect(sent[0].topicId).toBe(500);
    expect(sent[0].timings[1]).toBe(1200);
    expect(sent[0].timings[2]).toBe(1200);
    expect(sent[0].isPresent).toBe(true);
    expect(confirmed).toEqual([[500, 2]]);

    // 2) Document hidden pauses dwell accumulation
    tracker.setDocumentVisible(false, now);
    now += 4000;
    tracker.tick(now);
    expect(tracker.getCurrentTimingsSnapshot()).toEqual({});
    tracker.setDocumentVisible(true, now);

    // 3) Idle > 3 minutes pauses dwell accumulation
    now += PAUSE_UNLESS_SCROLLED_MS + 5000;
    tracker.tick(now);
    expect(tracker.getCurrentTimingsSnapshot()).toEqual({});

    // 4) Active again: already-read posts batch-flush at 60s & cap at 6 min per post
    tracker.markUserActivity(now);
    for (let i = 0; i < 60; i += 1) {
      now += 1000;
      tracker.markUserActivity(now);
      tracker.tick(now);
    }
    await flushPromises(3);
    expect(sent.length).toBeGreaterThanOrEqual(2);
    expect(sent[1].topicTimeMs).toBeGreaterThanOrEqual(FLUSH_INTERVAL_MS);

    // Verify 6-minute total cap per post
    for (let i = 0; i < 400; i += 1) {
      now += 1000;
      tracker.markUserActivity(now);
      tracker.tick(now);
    }
    expect(tracker.getTotalTimingsSnapshot()[1]).toBeLessThanOrEqual(MAX_TRACKING_TIME_PER_POST_MS);
    await tracker.flushNow(now);
    await flushPromises(3);
    expect(tracker.getPendingQueueLength()).toBe(0);

    // 5) Retry queue & exponential backoff on transient failure (never silently dropped!)
    failFirstSend = true;
    tracker.onTopicEntered(501, [1], 0, now);
    now += 1500;
    tracker.tick(now);
    await flushPromises(3);
    expect(tracker.getPendingQueueLength()).toBe(1);
    expect(tracker.getBlockSendingUntilMs()).toBe(now + BACKOFF_DELAYS_MS[0]);

    // Before backoff expires -> still queued
    now += 2000;
    tracker.tick(now);
    await flushPromises(2);
    expect(tracker.getPendingQueueLength()).toBe(1);

    // After backoff expires -> succeeds and drains queue
    now += BACKOFF_DELAYS_MS[0];
    await tracker.flushNow(now);
    await flushPromises(3);
    expect(tracker.getPendingQueueLength()).toBe(0);
    expect(confirmed[confirmed.length - 1]).toEqual([501, 1]);
  });

  it("sendTopicTimingsBatch sends real dwell ms & X-SILENCE-LOGGER / Discourse-Present / Discourse-Logged-In headers", async () => {
    invokeMock.mockResolvedValue(jsonResponse({ ok: true }));
    const outcome = await sendTopicTimingsBatch({
      topicId: 888,
      topicTimeMs: 4320,
      timings: { 1: 4320, 2: 2150 },
      isPresent: true,
    });
    expect(outcome).toBe("ok");
    const call = invokeMock.mock.calls.find((c) => c[0] === "site_request")!;
    expect(call[1].url).toBe("https://linux.do/topics/timings");
    expect(call[1].form).toEqual({
      topic_id: "888",
      topic_time: "4320",
      "timings[1]": "4320",
      "timings[2]": "2150",
    });
    expect(call[1].headers).toMatchObject({
      "X-SILENCE-LOGGER": "true",
      "Discourse-Present": "true",
      "Discourse-Logged-In": "true",
      "Discourse-Background": "true",
    });
  });
});

describe("P1-4, P1-5, P1-6: Composer drafts/conflicts, interactive builders, [bgcolor], and /emojis.json sync", () => {
  it("classifies PRIVATE_MESSAGE cloud drafts, builds tables/polls/16-lang code/5 callouts, and renders [bgcolor=...]", async () => {
    invokeMock.mockImplementation(async (cmd: string, args: any) => {
      if (cmd === "site_request" && String(args?.url).includes("/drafts.json")) {
        return jsonResponse({
          drafts: [
            {
              draft_key: "new_private_message",
              sequence: 4,
              data: JSON.stringify({
                action: "privateMessage",
                title: "私信草稿标题",
                reply: "私信草稿正文",
                recipients: "alice",
              }),
            },
          ],
        });
      }
      return jsonResponse({});
    });

    const drafts = await fetchCloudDrafts();
    expect(drafts).toHaveLength(1);
    expect(drafts[0].action).toBe("PRIVATE_MESSAGE");
    expect(drafts[0].sequence).toBe(4);
    expect(drafts[0].recipients).toBe("alice");

    expect(COMPOSER_CODE_LANGUAGES).toHaveLength(16);
    expect(COMPOSER_CALLOUT_KINDS.map((k) => k.kind)).toEqual([
      "note",
      "tip",
      "info",
      "warning",
      "danger",
    ]);

    const tableMd = buildMarkdownTableSnippet(3, 4);
    expect(tableMd.split("\n")).toHaveLength(4);
    expect(tableMd).toContain("| 列 1 | 列 2 | 列 3 | 列 4 |");

    const pollMd = buildDiscoursePollSnippet({
      type: "multiple",
      results: "on_vote",
      publicVoters: true,
      options: ["Rust", "Kotlin", "TypeScript"],
    });
    expect(pollMd).toContain("[poll type=multiple results=on_vote public=true chartType=bar min=1 max=3]");
    expect(pollMd).toContain("* Rust");

    const bgHtml = renderDiscourseMarkdownPreview("[bgcolor=#FFF3CD]重点高亮[/bgcolor]");
    expect(bgHtml).toContain("background-color:#FFF3CD");
    expect(bgHtml).toContain("重点高亮");
    const badBgHtml = renderDiscourseMarkdownPreview("[bgcolor=url(evil)]safe[/bgcolor]");
    expect(badBgHtml).not.toContain("url(evil)");
  });

  it("syncs /emojis.json, surfaces cloud draft conflict & delete-on-discard, and inserts builder snippets in ComposerModal", async () => {
    const deleteCalls: Array<{ cmd: string; args: any }> = [];
    invokeMock.mockImplementation(async (cmd: string, args: any) => {
      if (cmd === "delete_local_draft") {
        deleteCalls.push({ cmd, args });
        return {};
      }
      if (cmd === "site_request") {
        const url = String(args?.url || "");
        const method = String(args?.method || "GET");
        if (url.includes("/emojis.json")) {
          return jsonResponse({
            custom_linuxdo: [
              { name: "lindo_cat", url: "/uploads/emojis/lindo_cat.png" },
            ],
          });
        }
        if (method === "GET" && url.includes("/drafts/new_topic.json")) {
          return jsonResponse({
            draft: JSON.stringify({
              title: "云端冲突标题",
              reply: "这是来自云端的新正文",
              categoryId: 4,
              tags: ["Claude"],
            }),
            draft_sequence: 7,
          });
        }
        if (method === "DELETE" && url.includes("/drafts/new_topic.json")) {
          deleteCalls.push({ cmd: "delete_cloud_draft", args });
          return jsonResponse({ ok: true });
        }
        if (url.includes("/tags/filter/search.json")) {
          return jsonResponse({ results: [] });
        }
      }
      return jsonResponse({});
    });

    const emojis = await fetchSiteCustomEmojis(true);
    expect(emojis.some((e) => e.name === "lindo_cat" && e.url === "https://linux.do/uploads/emojis/lindo_cat.png")).toBe(true);
    expect(renderDiscourseMarkdownPreview("你好 :lindo_cat:")).toContain("lindo_cat.png");

    const savedDraft: LocalDraftRecord = {
      key: "new_topic",
      action: "NEW_TOPIC",
      categoryId: 4,
      title: "本地标题",
      raw: "本地正文内容",
      tags: [],
      sequence: 2,
      updatedAt: Date.now(),
    };

    vi.useFakeTimers();
    const el = mountElement(
      React.createElement(ComposerModal, {
        onTopicEdited: vi.fn(),
        request: { action: "NEW_TOPIC" },
        categories: SAMPLE_CATEGORIES,
        savedDraft,
        onClose: vi.fn(),
        onPublished: vi.fn(),
        onTriggerVerify: vi.fn(),
      })
    );
    await flushPromises();

    // Cloud draft conflict bar appears with both options
    expect(el.textContent).toContain("检测到云端草稿与本地内容不同");
    try {
      await act(async () => { await vi.advanceTimersByTimeAsync(3500); });
      expect(invokeMock.mock.calls.some(([cmd, args]) =>
        cmd === "site_request" && args.method === "POST" && args.url.endsWith("/drafts.json"))).toBe(false);
    } finally {
      vi.useRealTimers();
    }
    act(() => {
      findButtonByText(el, "使用云端草稿")!.click();
    });
    const ta = el.querySelector("textarea") as HTMLTextAreaElement;
    expect(ta.value).toContain("这是来自云端的新正文");

    // Interactive builders: Callout & Poll Builder
    act(() => {
      findButtonByText(el, "提示框")!.click();
    });
    expect(el.textContent).toContain("技巧 (tip)");
    act(() => {
      findButtonByText(el, "技巧 (tip)")!.click();
    });
    expect(ta.value).toContain("> [!tip]+ 技巧");

    const pollToolbarBtn = el.querySelector<HTMLButtonElement>('button[title="可视化投票构造器"]')!;
    act(() => {
      pollToolbarBtn.click();
    });
    expect(el.textContent).toContain("构造社区投票");
    act(() => {
      findButtonByText(el, "插入投票")!.click();
    });
    expect(ta.value).toContain("[poll type=regular");

    // Emoji picker shows synced /emojis.json custom tab
    const emojiBtn = el.querySelector<HTMLButtonElement>('button[title="插入表情与社区热梗"]')!;
    act(() => {
      emojiBtn.click();
    });
    expect(el.textContent).toContain("站内自定义");

    // Discard ("不保存") deletes BOTH local AND cloud draft!
    const closeBtn = el.querySelector<HTMLButtonElement>('button[title="关闭"]')!;
    act(() => {
      closeBtn.click();
    });
    act(() => {
      closeBtn.click();
    });
    expect(el.textContent).toContain("保存草稿？");
    await act(async () => {
      findButtonByText(el, "不保存")!.click();
      await Promise.resolve();
    });
    await flushPromises();
    expect(deleteCalls.some((c) => c.cmd === "delete_local_draft")).toBe(true);
    expect(deleteCalls.some((c) => c.cmd === "delete_cloud_draft")).toBe(true);

    // Cross-action EDIT guard: a REPLY cloud draft on topic 777 must NOT trigger a conflict banner in EDIT mode!
    invokeMock.mockImplementation(async (cmd: string, args: any) => {
      if (cmd !== "site_request") return {};
      const url = String(args?.url || "");
      if (url.includes("/drafts/topic_777.json")) {
        return jsonResponse({
          draft: JSON.stringify({ action: "reply", reply: "之前写到一半的回复草稿" }),
          draft_sequence: 5,
        });
      }
      return jsonResponse({});
    });
    const editEl = mountElement(
      React.createElement(ComposerModal, {
        onTopicEdited: vi.fn(),
        request: {
          action: "EDIT",
          topicId: 777,
          postId: 9901,
          postNumber: 2,
          initialRaw: "原帖正文内容",
        },
        categories: SAMPLE_CATEGORIES,
        onClose: vi.fn(),
        onPublished: vi.fn(),
        onTriggerVerify: vi.fn(),
      })
    );
    await flushPromises();
    expect(editEl.textContent).not.toContain("检测到云端草稿与本地内容不同");
    expect((editEl.querySelector("textarea") as HTMLTextAreaElement).value).toBe("原帖正文内容");
  });
});

describe("P1-7 & P1-8: PM composer/pagination + filtered Notifications, Bookmarks, Activity & Drafts dedup", () => {
  it("supports PM pagination, PM toolbar/preview/unsaved guard, and filtered notifications pagination in MessagesView", async () => {
    const pmPage0 = Array.from({ length: 25 }, (_, i) => ({
      id: 3000 + i,
      title: `私信对话 #${i + 1}`,
      posts_count: 2,
      highest_post_number: 2,
      last_posted_at: "2026-11-26T00:00:00Z",
      posters: [{ user_id: 1 }],
    }));
    const pmPage1 = [
      {
        id: 3999,
        title: "第二页私信对话",
        posts_count: 1,
        highest_post_number: 1,
        last_posted_at: "2026-11-20T00:00:00Z",
        posters: [{ user_id: 1 }],
      },
    ];
    const notifsPage0 = Array.from({ length: 55 }, (_, i) => ({
      id: 8000 + i,
      notification_type: 2,
      read: false,
      created_at: "2026-11-26T00:00:00Z",
      topic_id: 100 + i,
      post_number: 2,
      data: { display_username: "alice", topic_title: `回复通知 #${i + 1}` },
    }));

    invokeMock.mockImplementation(async (cmd: string, args: any) => {
      if (cmd !== "site_request") return {};
      const url = String(args?.url || "");
      if (url.includes("/notifications.json") && url.includes("offset=55")) {
        return jsonResponse({
          notifications: [
            {
              id: 8999,
              notification_type: 1,
              read: false,
              created_at: "2026-11-24T00:00:00Z",
              topic_id: 999,
              post_number: 3,
              data: { display_username: "bob", topic_title: "更早一页的提及通知" },
            },
          ],
        });
      }
      if (url.includes("/notifications.json")) {
        return jsonResponse({ notifications: notifsPage0 });
      }
      if (url.includes("private-messages/lucas.json?page=1")) {
        return jsonResponse({ topic_list: { topics: pmPage1 }, users: [{ id: 1, username: "alice" }] });
      }
      if (url.includes("private-messages/lucas.json?page=0")) {
        return jsonResponse({ topic_list: { topics: pmPage0 }, users: [{ id: 1, username: "alice" }] });
      }
      return jsonResponse({});
    });

    const el = mountElement(
      React.createElement(MessagesView, {
        isAuthorized: true,
        isDark: true,
        currentUser: CURRENT_USER,
        selectedTopicId: null,
        onOpenNotificationTopic: vi.fn(),
        onUnreadCleared: vi.fn(),
        onOpenAuth: vi.fn(),
        onOpenChat: vi.fn(),
      })
    );
    await flushPromises();

    // Filtered notifications STILL show 加载更早通知 (`P1-8`) — both populated AND empty-page sparse filters!
    act(() => {
      findButtonByText(el, "回复")!.click();
    });
    expect(findButtonByText(el, "加载更早通知")).toBeTruthy();

    act(() => {
      findButtonByText(el, "提及")!.click();
    });
    expect(el.textContent).toContain("暂无该类别通知");
    const sparseLoadMore = findButtonByText(el, "加载更早通知");
    expect(sparseLoadMore).toBeTruthy();
    await act(async () => {
      sparseLoadMore!.click();
      await Promise.resolve();
    });
    await flushPromises();
    expect(el.textContent).toContain("更早一页的提及通知");

    // PM tab pagination (`P1-7`)
    await act(async () => {
      findButtonByText(el, "私信")!.click();
      await Promise.resolve();
    });
    await flushPromises();
    const morePmBtn = findButtonByText(el, "加载更多私信");
    expect(morePmBtn).toBeTruthy();
    await act(async () => {
      morePmBtn!.click();
      await Promise.resolve();
    });
    await flushPromises();
    expect(el.textContent).toContain("第二页私信对话");

    // PM composer toolbar + preview + unsaved guard (`P1-7`)
    act(() => {
      findButtonByText(el, "写私信")!.click();
    });
    expect(el.querySelector('button[title="粗体"]')).toBeTruthy();
    act(() => {
      el.querySelector<HTMLButtonElement>('button[title="粗体"]')!.click();
    });
    act(() => {
      findButtonByText(el, "预览")!.click();
    });
    expect(el.innerHTML).toContain("<strong>粗体文字</strong>");
    act(() => {
      findButtonByText(el, "返回私信箱")!.click();
    });
    expect(el.textContent).toContain("放弃未发送的私信？");
  });

  it("renders rich bookmark note/reminder + pagination, deduplicated drafts + clear-all, and badge tier filters in ProfileSettingsView", async () => {
    const deletedLocalKeys: string[] = [];
    const deletedCloudKeys: string[] = [];

    invokeMock.mockImplementation(async (cmd: string, args: any) => {
      if (cmd === "delete_local_draft") {
        deletedLocalKeys.push(String(args?.key || ""));
        return {};
      }
      if (cmd !== "site_request") return {};
      const url = String(args?.url || "");
      const method = String(args?.method || "GET");
      if (url.includes("/bookmarks.json?page=1")) {
        return jsonResponse({
          user_bookmark_list: {
            bookmarks: [{ id: 2, topic_id: 202, title: "第二页书签", post_number: 4 }],
          },
        });
      }
      if (url.includes("/bookmarks.json")) {
        return jsonResponse({
          user_bookmark_list: {
            more_bookmarks_url: "/u/lucas/bookmarks.json?page=1",
            bookmarks: [
              {
                id: 1,
                topic_id: 201,
                title: "带备注和提醒的书签",
                linked_post_number: 7,
                name: "周末细读",
                reminder_at: "2026-12-01T09:00:00Z",
                excerpt: "这是一段书签摘录",
                category_id: 4,
                updated_at: "2026-11-25T10:00:00Z",
              },
            ],
          },
        });
      }
      if (url.includes("/drafts.json")) {
        return jsonResponse({
          drafts: [
            {
              draft_key: "topic_555",
              sequence: 3,
              topic_id: 555,
              data: JSON.stringify({ action: "reply", reply: "云端同步回复" }),
            },
            {
              draft_key: "new_private_message",
              sequence: 1,
              data: JSON.stringify({
                action: "privateMessage",
                title: "给 alice 的私信",
                reply: "你好",
                recipients: "alice",
              }),
            },
          ],
        });
      }
      if (method === "DELETE" && url.includes("/drafts/")) {
        deletedCloudKeys.push(url);
        return jsonResponse({ ok: true });
      }
      if (url.includes("/user-badges/lucas.json")) {
        return jsonResponse({
          badges: [
            { id: 1, name: "黄金贡献", description: "金徽章", badge_type_id: 1, allow_title: true, grant_count: 10 },
            { id: 2, name: "白银活跃", description: "银徽章", badge_type_id: 2, allow_title: false, grant_count: 50 },
          ],
          user_badges: [
            { id: 11, badge_id: 1, count: 1 },
            { id: 12, badge_id: 2, count: 1 },
          ],
        });
      }
      return jsonResponse({});
    });

    const el = mountElement(
      React.createElement(ProfileSettingsView, {
        isAuthorized: true,
        isDark: true,
        currentUser: CURRENT_USER,
        savedAccounts: [],
        preferences: DEFAULT_PREFS,
        readFloors: {},
        drafts: {
          reply_555: {
            key: "reply_555",
            action: "REPLY",
            topicId: 555,
            title: "本地回复标题",
            raw: "本地回复正文",
            tags: [],
            sequence: 3,
            updatedAt: Date.now(),
          },
        },
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

    // 1) Bookmarks rich metadata + pagination (`P1-8`)
    await act(async () => {
      findButtonByText(el, "收藏")!.click();
      await Promise.resolve();
    });
    await flushPromises();
    expect(el.textContent).toContain("备注：周末细读");
    expect(el.textContent).toContain("提醒：2026-12-01 09:00");
    await act(async () => {
      findButtonByText(el, "加载更多书签")!.click();
      await Promise.resolve();
    });
    await flushPromises();
    expect(el.textContent).toContain("第二页书签");

    // 2) Drafts deduplication + PM kind + Clear All (`P1-4`)
    await act(async () => {
      findButtonByText(el, "草稿")!.click();
      await Promise.resolve();
    });
    await flushPromises();
    expect(el.textContent).toContain("本地 + 云端");
    expect(el.textContent).toContain("私信");
    act(() => {
      findButtonByText(el, "清空全部草稿")!.click();
    });
    await act(async () => {
      findButtonByText(el, "全部清空")!.click();
      await Promise.resolve();
    });
    await flushPromises();
    expect(deletedLocalKeys).toContain("reply_555");
    expect(deletedCloudKeys.length).toBeGreaterThanOrEqual(2);

    // 3) Profile Badges tier filter pills (`P1-9`)
    await act(async () => {
      findButtonByText(el, "社区徽章墙")!.click();
      await Promise.resolve();
    });
    await flushPromises();
    expect(el.textContent).toContain("黄金贡献");
    expect(el.textContent).toContain("白银活跃");
    const goldPill = Array.from(el.querySelectorAll("button")).find(
      (b) => (b.textContent || "").trim() === "金"
    )!;
    act(() => {
      goldPill.click();
    });
    expect(el.textContent).toContain("黄金贡献");
    expect(el.textContent).not.toContain("白银活跃");
  });
});

describe("P1-9: BadgeDetailModal /badges/{id}.json + title equip & mutual-follow cross-check", () => {
  it("fetches /badges/{id}.json, equips/unequips title, and cross-checks followers against /following.json", async () => {
    const putForms: any[] = [];
    invokeMock.mockImplementation(async (cmd: string, args: any) => {
      if (cmd !== "site_request") return {};
      const url = String(args?.url || "");
      const method = String(args?.method || "GET");
      if (url.endsWith("/badges/55.json")) {
        return jsonResponse({
          badge: {
            id: 55,
            name: "核心建设者",
            description: "短说明",
            long_description: "详细解锁规则：累计提交 10 次优质贡献",
            badge_type_id: 1,
            grant_count: 320,
            allow_title: true,
          },
        });
      }
      if (method === "PUT" && url.includes("/u/lucas.json")) {
        putForms.push(args?.form);
        return jsonResponse({ user: { username: "lucas" } });
      }
      if (url.includes("/u/lucas/follow/followers.json")) {
        return jsonResponse([
          { username: "mutual_friend", name: "Mutual", trust_level: 3, title: "热心佬友", is_followed: false },
          { username: "fan_only", name: "Fan", trust_level: 1, is_followed: false },
        ]);
      }
      if (url.includes("/u/lucas/follow/following.json")) {
        return jsonResponse([{ username: "mutual_friend" }]);
      }
      return jsonResponse({});
    });

    // Mutual-follow cross-check
    const followers = await fetchUserFollowList("lucas", true, "lucas");
    expect(followers).toHaveLength(2);
    expect(followers[0]).toMatchObject({
      username: "mutual_friend",
      trustLevel: 3,
      title: "热心佬友",
      isFollowing: true,
    });
    expect(followers[1].isFollowing).toBe(false);

    // BadgeDetailModal fetch + equip/unequip title
    const onProfileUpdated = vi.fn();
    const el = mountElement(
      React.createElement(BadgeDetailModal, {
        badge: {
          id: 55,
          name: "核心建设者",
          description: "短说明",
          badgeTypeId: 1,
          isEarned: true,
        },
        isAuthorized: true,
        currentUser: CURRENT_USER,
        onProfileUpdated,
        onClose: vi.fn(),
      })
    );
    await flushPromises();
    expect(el.textContent).toContain("详细解锁规则：累计提交 10 次优质贡献");
    expect(el.textContent).toContain("共 320 人获得");
    const equipBtn = findButtonByText(el, "设为个人头衔");
    expect(equipBtn).toBeTruthy();

    await act(async () => {
      equipBtn!.click();
      await Promise.resolve();
    });
    await flushPromises();
    expect(putForms[0]).toMatchObject({ title: "核心建设者" });
    expect(findButtonByText(el, "取消头衔")).toBeTruthy();
    expect(onProfileUpdated).toHaveBeenCalledTimes(1);
  });
});

describe("P1-10: Search 1-char CJK, hot keywords, retryable pagination & Home cached error banner", () => {
  it("allows 1-char CJK queries, shows hot keywords & postsCount, retries failed pagination, and shows Home inline error banner over cached topics", async () => {
    expect(isValidSearchQuery("猫")).toBe(true);
    expect(isValidSearchQuery("a")).toBe(false);
    expect(isValidSearchQuery("go")).toBe(true);
    expect(HOT_SEARCH_KEYWORDS.length).toBeGreaterThanOrEqual(6);

    let failPage2 = true;
    invokeMock.mockImplementation(async (cmd: string, args: any) => {
      if (cmd !== "site_request") return {};
      const url = String(args?.url || "");
      if (url.includes("/search.json") && url.includes("page=2")) {
        if (failPage2) {
          return jsonResponse({ errors: ["临时波动"] }, 500);
        }
        return jsonResponse({
          posts: [
            {
              id: 202,
              topic_id: 9002,
              post_number: 1,
              username: "bob",
              blurb: "第二页命中",
              created_at: "2026-11-25T00:00:00Z",
            },
          ],
          topics: [{ id: 9002, title: "猫猫第二页话题", posts_count: 18 }],
          grouped_search_result: { more_full_page_results: false },
        });
      }
      if (url.includes("/search.json")) {
        return jsonResponse({
          posts: [
            {
              id: 201,
              topic_id: 9001,
              post_number: 1,
              username: "alice",
              blurb: "单字中文搜索命中",
              created_at: "2026-11-26T00:00:00Z",
            },
          ],
          topics: [{ id: 9001, title: "关于猫的讨论", posts_count: 42 }],
          grouped_search_result: { more_full_page_results: true },
        });
      }
      return jsonResponse({});
    });

    const el = mountElement(
      React.createElement(GlobalSearchModal, {
        categories: SAMPLE_CATEGORIES,
        onSelectHit: vi.fn(),
        onClose: vi.fn(),
      })
    );
    expect(el.textContent).toContain("热门搜索");

    // Click hot keyword or trigger 1-char CJK
    const input = el.querySelector<HTMLInputElement>('input[type="text"]')!;
    act(() => {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!;
      setter.call(input, "猫");
      input.dispatchEvent(new Event("input", { bubbles: true }));
    });
    act(() => {
      input.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));
    });
    await act(async () => {
      await new Promise((r) => setTimeout(r, 30));
    });
    await flushPromises(6);
    expect(el.textContent).toContain("关于猫的讨论");
    expect(el.textContent).toContain("42 帖");

    // Page 2 error keeps hasMore retryable (`重试加载更多`), never locking permanently
    await act(async () => {
      findButtonByText(el, "加载更多搜索结果")!.click();
      await Promise.resolve();
    });
    await flushPromises();
    const retryMoreBtn = findButtonByText(el, "重试加载更多");
    expect(retryMoreBtn).toBeTruthy();

    failPage2 = false;
    await act(async () => {
      retryMoreBtn!.click();
      await Promise.resolve();
    });
    await flushPromises();
    expect(el.textContent).toContain("猫猫第二页话题");

    // HomeFeedPane cached inline error banner (`P1-10`)
    const onRefresh = vi.fn();
    const homeEl = mountElement(
      React.createElement(HomeFeedPane, {
        topics: [makeTopicSummary(701)],
        categories: SAMPLE_CATEGORIES,
        currentSort: "latest",
        topPeriod: "weekly",
        selectedCategoryId: null,
        selectedTag: null,
        selectedTopicId: null,
        isAuthorized: true,
        currentUser: CURRENT_USER,
        unreadNotificationCount: 0,
        pendingNewCount: 0,
        isLoading: false,
        isLoadingMore: false,
        hasMore: false,
        errorMessage: "网络波动，刷新失败",
        loadMoreError: null,
        isCompact: false,
        defaultSearchExpanded: false,
        readFloors: {},
        onSelectSort: vi.fn(),
        onSelectTopPeriod: vi.fn(),
        onSelectCategory: vi.fn(),
        onSelectTag: vi.fn(),
        onSelectTopic: vi.fn(),
        onRefresh,
        onApplyPendingNew: vi.fn(),
        onLoadMore: vi.fn(),
        onToggleCompact: vi.fn(),
        onOpenSearch: vi.fn(),
        onOpenNotifications: vi.fn(),
        onOpenAuth: vi.fn(),
        onTriggerVerify: vi.fn(),
      })
    );
    const alertBanner = homeEl.querySelector('[role="alert"]');
    expect(alertBanner?.textContent).toContain("网络波动，刷新失败");
    expect(homeEl.textContent).toContain("归档与跳楼测试话题");
    act(() => {
      findButtonByText(alertBanner as HTMLElement, "重试")!.click();
    });
    expect(onRefresh).toHaveBeenCalledTimes(1);
  });
});

describe("P1-11: Reader archived reply lock & non-contiguous middle-gap floor loader", () => {
  it("locks archived topic reply for regular users, unlocks for staff, and renders inline middle-gap expansion button", async () => {
    expect(computeMiddlePostGapIds([10, 11, 12, 13, 14], 10, 14)).toEqual([11, 12, 13]);
    expect(computeMiddlePostGapIds([10, 11, 12], 10, 11)).toEqual([]);

    const summary = makeTopicSummary(900);
    const detail: TopicDetailModel = {
      id: 900,
      title: summary.title,
      tags: [],
      views: 120,
      postsCount: 5,
      likeCount: 6,
      bookmarked: false,
      closed: false,
      archived: true,
      notificationLevel: 1,
      opUsername: "user1",
      hasSummary: false,
      streamIds: [10, 11, 12, 13, 14],
      posts: [makePost(10, 1), makePost(14, 5)],
    };

    invokeMock.mockImplementation(async (cmd: string, args: any) => {
      if (cmd === "site_request" && String(args?.url).includes("/t/900/posts.json")) {
        return jsonResponse({
          post_stream: {
            posts: [
              { id: 11, post_number: 2, username: "user2", cooked: "<p>补全楼层 2</p>", created_at: "2026-11-25T10:05:00Z" },
              { id: 12, post_number: 3, username: "user3", cooked: "<p>补全楼层 3</p>", created_at: "2026-11-25T10:06:00Z" },
              { id: 13, post_number: 4, username: "user4", cooked: "<p>补全楼层 4</p>", created_at: "2026-11-25T10:07:00Z" },
            ],
          },
        });
      }
      return jsonResponse({});
    });

    const onAppendPosts = vi.fn();
    const el = mountElement(
      React.createElement(TopicReaderPane, {
        summary,
        detail,
        isLoading: false,
        errorMessage: null,
        targetFloor: null,
        targetJumpNonce: 0,
        onlyOpFilter: false,
        isAuthorized: true,
        currentUser: CURRENT_USER,
        showBackButton: false,
        onBack: vi.fn(),
        onRefresh: vi.fn(),
        onToggleOnlyOp: vi.fn(),
        onAppendPosts,
        onOpenComposer: vi.fn(),
        onInspectUser: vi.fn(),
        onOpenImageLightbox: vi.fn(),
        onNavigateTopicLink: vi.fn(),
        onRequestGuestLogin: vi.fn(),
        onTriggerVerify: vi.fn(),
        onFloorSeen: vi.fn(),
      })
    );

    // 1) Archived reply button is disabled for regular user
    const replyBtn = el.querySelector<HTMLButtonElement>('button[aria-label="话题已归档，无法回复"]');
    expect(replyBtn).toBeTruthy();
    expect(replyBtn!.disabled).toBe(true);

    // 2) Inline middle-gap button renders between post #1 (id 10) and post #5 (id 14)
    const gapBtn = findButtonByText(el, "展开中间 3 条楼层");
    expect(gapBtn).toBeTruthy();
    await act(async () => {
      gapBtn!.click();
      await Promise.resolve();
    });
    await flushPromises();
    expect(onAppendPosts).toHaveBeenCalledTimes(1);
    expect(onAppendPosts.mock.calls[0][1]).toBe("after");
    expect(onAppendPosts.mock.calls[0][0].map((p: PostItemModel) => p.id)).toEqual([11, 12, 13]);

    // 3) Staff user (`moderator: true`) can still reply on archived topic
    const staffEl = mountElement(
      React.createElement(TopicReaderPane, {
        summary,
        detail,
        isLoading: false,
        errorMessage: null,
        targetFloor: null,
        targetJumpNonce: 0,
        onlyOpFilter: false,
        isAuthorized: true,
        currentUser: { ...CURRENT_USER, moderator: true },
        showBackButton: false,
        onBack: vi.fn(),
        onRefresh: vi.fn(),
        onToggleOnlyOp: vi.fn(),
        onAppendPosts: vi.fn(),
        onOpenComposer: vi.fn(),
        onInspectUser: vi.fn(),
        onOpenImageLightbox: vi.fn(),
        onNavigateTopicLink: vi.fn(),
        onRequestGuestLogin: vi.fn(),
        onTriggerVerify: vi.fn(),
        onFloorSeen: vi.fn(),
      })
    );
    const staffReplyBtn = staffEl.querySelector<HTMLButtonElement>('button[aria-label="回复话题"]');
    expect(staffReplyBtn).toBeTruthy();
    expect(staffReplyBtn!.disabled).toBe(false);
  });
});

describe("Composer Link Picker default tab (引用社区帖子 default + recents + 1-char CJK + 外部链接)", () => {
  it("opens ComposerModal Link Picker on 引用社区帖子 by default with recent topics & 1-char CJK search, supports 外部链接, and resets default on reopen/Cmd+K", async () => {
    invokeMock.mockImplementation(async (cmd: string, args: any) => {
      if (cmd !== "site_request") return {};
      const url = String(args?.url || "");
      if (url.includes("/latest.json")) {
        return jsonResponse({
          users: [{ id: 1, username: "alice", name: "Alice", avatar_template: "/a.png" }],
          topic_list: {
            topics: [
              {
                id: 8101,
                title: "近期预加载社区帖子一",
                slug: "recent-1",
                posts_count: 12,
                views: 200,
                like_count: 5,
                created_at: "2026-11-26T00:00:00Z",
                last_posted_at: "2026-11-26T01:00:00Z",
                posters: [{ user_id: 1 }],
              },
            ],
          },
        });
      }
      if (url.includes("/search/query.json")) {
        return jsonResponse({
          posts: [
            {
              id: 501,
              topic_id: 8202,
              post_number: 1,
              username: "catlover",
              blurb: "单字中文命中",
              created_at: "2026-11-26T00:00:00Z",
            },
          ],
          topics: [{ id: 8202, title: "关于猫的精选帖子", posts_count: 8 }],
        });
      }
      if (url.includes("/tags/filter/search.json")) {
        return jsonResponse({ results: [] });
      }
      return jsonResponse({});
    });

    const el = mountElement(
      React.createElement(ComposerModal, {
        onTopicEdited: vi.fn(),
        request: { action: "REPLY", topicId: 404, topicTitle: "当前话题" },
        categories: SAMPLE_CATEGORIES,
        onClose: vi.fn(),
        onPublished: vi.fn(),
        onTriggerVerify: vi.fn(),
      })
    );
    await flushPromises();

    // 1) Click link toolbar button -> defaults to 引用社区帖子 (first tab) and pre-loads recent topics
    const linkToolbarBtn = el.querySelector<HTMLButtonElement>(
      'button[title*="引用社区帖子或插入链接"]'
    )!;
    expect(linkToolbarBtn).toBeTruthy();
    await act(async () => {
      linkToolbarBtn.click();
      await Promise.resolve();
    });
    await flushPromises();

    const tabs = Array.from(el.querySelectorAll<HTMLButtonElement>('div[role="tablist"] button[role="tab"]'));
    expect(tabs.map((t) => (t.textContent || "").trim())).toEqual(["引用社区帖子", "外部链接"]);
    expect(tabs[0].getAttribute("aria-selected")).toBe("true");
    expect(tabs[1].getAttribute("aria-selected")).toBe("false");
    expect(el.textContent).toContain("近期社区话题");
    expect(el.textContent).toContain("近期预加载社区帖子一");

    // 2) 1-char CJK search works without being blocked by q.length < 2
    const searchInput = el.querySelector<HTMLInputElement>(
      'input[placeholder*="搜索要引用的社区帖子"]'
    )!;
    expect(searchInput).toBeTruthy();
    act(() => {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!;
      setter.call(searchInput, "猫");
      searchInput.dispatchEvent(new Event("input", { bubbles: true }));
    });
    await act(async () => {
      await new Promise((r) => setTimeout(r, 290));
    });
    await flushPromises();
    expect(el.textContent).toContain("关于猫的精选帖子");

    // 3) Switch to 外部链接 tab & insert external URL cleanly
    act(() => {
      tabs[1].click();
    });
    expect(tabs[1].getAttribute("aria-selected")).toBe("true");
    const urlInput = el.querySelector<HTMLInputElement>('input[placeholder="https://..."]')!;
    const labelInput = el.querySelector<HTMLInputElement>('input[placeholder="显示文本（选填）"]')!;
    act(() => {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!;
      setter.call(urlInput, "https://example.org/docs");
      urlInput.dispatchEvent(new Event("input", { bubbles: true }));
      setter.call(labelInput, "官方文档");
      labelInput.dispatchEvent(new Event("input", { bubbles: true }));
    });
    act(() => {
      findButtonByText(el, "插入链接")!.click();
    });
    const ta = el.querySelector("textarea") as HTMLTextAreaElement;
    expect(ta.value).toContain("[官方文档](<https://example.org/docs>)");

    // 4) Re-opening via toolbar or Cmd+K ALWAYS defaults back to 引用社区帖子
    act(() => {
      ta.dispatchEvent(new KeyboardEvent("keydown", { key: "k", metaKey: true, bubbles: true }));
    });
    await flushPromises();
    const tabsReopen = Array.from(
      el.querySelectorAll<HTMLButtonElement>('div[role="tablist"] button[role="tab"]')
    );
    expect(tabsReopen[0].getAttribute("aria-selected")).toBe("true");
    expect(tabsReopen[1].getAttribute("aria-selected")).toBe("false");
  });

  it("opens PM composer Link Picker on 引用社区帖子 by default and allows switching to 外部链接", async () => {
    await clearLocalCachesInRust();
    invokeMock.mockImplementation(async (cmd: string, args: any) => {
      if (cmd !== "site_request") return {};
      const url = String(args?.url || "");
      if (url.includes("/notifications.json")) return jsonResponse({ notifications: [] });
      if (url.includes("private-messages/")) return jsonResponse({ topic_list: { topics: [] }, users: [] });
      if (url.includes("/latest.json")) {
        return jsonResponse({
          users: [{ id: 1, username: "alice", avatar_template: "/a.png" }],
          topic_list: {
            topics: [
              {
                id: 8303,
                title: "私信引用预加载帖子",
                slug: "pm-recent",
                posts_count: 3,
                views: 50,
                like_count: 1,
                created_at: "2026-11-26T00:00:00Z",
                last_posted_at: "2026-11-26T01:00:00Z",
                posters: [{ user_id: 1 }],
              },
            ],
          },
        });
      }
      return jsonResponse({});
    });

    const el = mountElement(
      React.createElement(MessagesView, {
        isAuthorized: true,
        isDark: true,
        currentUser: CURRENT_USER,
        selectedTopicId: null,
        initialComposeRecipient: "alice",
        onOpenNotificationTopic: vi.fn(),
        onUnreadCleared: vi.fn(),
        onOpenAuth: vi.fn(),
        onOpenChat: vi.fn(),
      })
    );
    await flushPromises();

    const pmLinkBtn = el.querySelector<HTMLButtonElement>('button[title="引用社区帖子或插入链接"]')!;
    expect(pmLinkBtn).toBeTruthy();
    await act(async () => {
      pmLinkBtn.click();
      await Promise.resolve();
    });
    await flushPromises();

    const tabs = Array.from(
      el.querySelectorAll<HTMLButtonElement>('div[aria-label="私信链接模式"] button[role="tab"]')
    );
    expect(tabs.map((t) => (t.textContent || "").trim())).toEqual(["引用社区帖子", "外部链接"]);
    expect(tabs[0].getAttribute("aria-selected")).toBe("true");
    expect(el.textContent).toContain("私信引用预加载帖子");

    // Switching to 外部链接 works cleanly in PM composer too
    act(() => {
      tabs[1].click();
    });
    expect(tabs[1].getAttribute("aria-selected")).toBe("true");
    const pmUrlInput = el.querySelector<HTMLInputElement>('input[placeholder="https://..."]')!;
    act(() => {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!;
      setter.call(pmUrlInput, "https://linux.do/about");
      pmUrlInput.dispatchEvent(new Event("input", { bubbles: true }));
    });
    act(() => {
      findButtonByText(el, "插入链接")!.click();
    });
    expect((el.querySelector("textarea") as HTMLTextAreaElement).value).toContain(
      "https://linux.do/about"
    );
  });
});
