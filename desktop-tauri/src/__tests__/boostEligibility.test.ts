// @vitest-environment jsdom
import React, { act } from "react";
import { createRoot } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { PostItemModel, TopicDetailModel, TopicSummary, UserProfile } from "../lib/types";
import { mergePostPreservingBadges } from "../App";
import {
  canShowPostBoostAction,
  classifyBoostSubmitFailure,
  parsePostModel,
} from "../lib/api";

const sendPostBoostMock = vi.fn();

vi.mock("@tauri-apps/api/core", () => ({
  invoke: vi.fn(async () => ({})),
}));

vi.mock("@tauri-apps/plugin-opener", () => ({
  openUrl: vi.fn(),
}));

vi.mock("../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../lib/api")>();
  return {
    ...actual,
    sendPostBoost: (...args: any[]) => sendPostBoostMock(...args),
    deletePostBoost: vi.fn(),
    togglePostReaction: vi.fn(),
    fetchSiteCustomEmojis: vi.fn(async () => []),
    useRateLimitAutoRecovery: vi.fn(),
  };
});

import { TopicReaderPane } from "../components/TopicReaderPane";

(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;

function makePost(overrides: Partial<PostItemModel> = {}): PostItemModel {
  return {
    id: 5001,
    topicId: 9001,
    postNumber: 1,
    username: "author",
    displayName: "Author",
    avatarTemplate: "/user_avatar/linux.do/author/{size}/1.png",
    trustLevel: 2,
    isModerator: false,
    isAdmin: false,
    createdAt: "2026-11-26T10:00:00.000Z",
    cookedHtml: "<p>测试楼层正文</p>",
    replyCount: 0,
    likeCount: 0,
    liked: false,
    reactions: [],
    boosts: [],
    canBoost: true,
    hasBoostsField: true,
    hasCanBoostField: true,
    polls: [],
    bookmarked: false,
    canEdit: false,
    canDelete: false,
    acceptedAnswer: false,
    canAcceptAnswer: false,
    ...overrides,
  };
}

function makeSummary(overrides: Partial<TopicSummary> = {}): TopicSummary {
  return {
    id: 9001,
    title: "测试话题",
    slug: "test-topic",
    postsCount: 3,
    replyCount: 2,
    views: 42,
    likeCount: 5,
    highestPostNumber: 3,
    unreadPosts: 0,
    unseen: false,
    categoryId: 4,
    categoryName: "开发调优",
    categoryColorHex: "#3B82F6",
    tags: [],
    createdAt: "2026-11-26T10:00:00.000Z",
    lastPostedAt: "2026-11-26T10:10:00.000Z",
    pinned: false,
    closed: false,
    archived: false,
    bookmarked: false,
    originalPoster: {
      userId: 1,
      username: "author",
      name: "Author",
      avatarTemplate: "/user_avatar/linux.do/author/{size}/1.png",
    },
    ...overrides,
  };
}

function makeDetail(posts: PostItemModel[], overrides: Partial<TopicDetailModel> = {}): TopicDetailModel {
  return {
    id: 9001,
    title: "测试话题",
    categoryId: 4,
    categoryName: "开发调优",
    categoryColorHex: "#3B82F6",
    tags: [],
    postsCount: posts.length,
    views: 42,
    likeCount: 5,
    bookmarked: false,
    closed: false,
    archived: false,
    hasSummary: false,
    streamIds: posts.map((p) => p.id),
    notificationLevel: 2,
    posts,
    opUsername: "author",
    ...overrides,
  };
}

const currentUser: UserProfile = {
  userId: 7,
  username: "lucas",
  displayName: "Lucas",
  avatarTemplate: "/user_avatar/linux.do/lucas/{size}/1.png",
  trustLevel: 3,
};

describe("Boost eligibility & max-limit hiding", () => {
  let container: HTMLDivElement;

  beforeEach(() => {
    container = document.createElement("div");
    document.body.appendChild(container);
    sendPostBoostMock.mockReset();
  });

  afterEach(() => {
    container.remove();
  });

  it("canShowPostBoostAction only returns true when user can actually send a boost", () => {
    const base = {
      isAuthorized: true,
      isOwnPost: false,
      isDeletedTombstone: false,
      isSmallAction: false,
      topicArchived: false,
      canBoost: true,
      hasMyBoost: false,
    };
    expect(canShowPostBoostAction(base)).toBe(true);
    // Max boosts reached or server denied (`can_boost: false`)
    expect(canShowPostBoostAction({ ...base, canBoost: false })).toBe(false);
    // Missing/unknown `can_boost`
    expect(canShowPostBoostAction({ ...base, canBoost: undefined })).toBe(false);
    // Already boosted by current user
    expect(canShowPostBoostAction({ ...base, hasMyBoost: true })).toBe(false);
    // Own post, deleted, small action, archived topic, or guest
    expect(canShowPostBoostAction({ ...base, isOwnPost: true })).toBe(false);
    expect(canShowPostBoostAction({ ...base, isDeletedTombstone: true })).toBe(false);
    expect(canShowPostBoostAction({ ...base, isSmallAction: true })).toBe(false);
    expect(canShowPostBoostAction({ ...base, topicArchived: true })).toBe(false);
    expect(canShowPostBoostAction({ ...base, isAuthorized: false })).toBe(false);
  });

  it("parsePostModel respects server can_boost and mergePostPreservingBadges keeps boosts & canBoost=false across partial reaction responses", () => {
    const maxedPost = parsePostModel(
      {
        id: 101,
        post_number: 1,
        username: "author",
        cooked: "<p>hello</p>",
        yours: false,
        can_boost: false,
        boosts: [
          { id: 1, cooked: "<p>赞同</p>", user: { username: "u1", avatar_template: "" } },
        ],
      },
      9001
    );
    expect(maxedPost.canBoost).toBe(false);
    expect(maxedPost.hasCanBoostField).toBe(true);
    expect(maxedPost.hasBoostsField).toBe(true);
    expect(maxedPost.boosts).toHaveLength(1);

    // Partial response from PUT /discourse-reactions/.../toggle.json omits boosts & can_boost
    const partialReactionPost = parsePostModel(
      {
        id: 101,
        post_number: 1,
        username: "author",
        cooked: "<p>hello</p>",
        yours: false,
        current_user_used_main_reaction: true,
        reactions: [{ id: "heart", count: 1 }],
      },
      9001
    );
    expect(partialReactionPost.hasBoostsField).toBe(false);
    expect(partialReactionPost.hasCanBoostField).toBe(false);
    expect(partialReactionPost.canBoost).toBe(false);

    const merged = mergePostPreservingBadges(maxedPost, partialReactionPost);
    expect(merged.boosts).toHaveLength(1);
    expect(merged.canBoost).toBe(false);
    expect(merged.liked).toBe(true);

    // Also preserves canBoost=true across partial reaction toggle
    const boostablePost = parsePostModel(
      {
        id: 102,
        post_number: 2,
        username: "author",
        cooked: "<p>ok</p>",
        yours: false,
        can_boost: true,
        boosts: [],
      },
      9001
    );
    const mergedBoostable = mergePostPreservingBadges(boostablePost, {
      ...partialReactionPost,
      id: 102,
      postNumber: 2,
    });
    expect(mergedBoostable.canBoost).toBe(true);
  });

  it("classifyBoostSubmitFailure localizes max-boosts error and flags disableCanBoost", () => {
    expect(
      classifyBoostSubmitFailure(new Error("This post has reached the maximum number of boosts."))
    ).toEqual({
      disableCanBoost: true,
      message: "该楼层短评已达上限",
    });
    expect(classifyBoostSubmitFailure(new Error("You have already boosted this post"))).toEqual({
      disableCanBoost: true,
      message: "你已发过短评",
    });
    expect(classifyBoostSubmitFailure(new Error("Network timeout"))).toEqual({
      disableCanBoost: false,
      message: "Network timeout",
    });
  });

  it("hides the boost rocket button in TopicReaderPane when canBoost=false, already boosted, archived, or guest, and self-heals on race-condition limit error", async () => {
    const posts = [
      makePost({ id: 1, postNumber: 1, canBoost: true }),
      makePost({ id: 2, postNumber: 2, canBoost: false }),
      makePost({
        id: 3,
        postNumber: 3,
        canBoost: true,
        boosts: [
          {
            id: 77,
            username: "lucas",
            avatarTemplate: "",
            cooked: "<p>已发</p>",
            canDelete: true,
          },
        ],
      }),
    ];
    let currentDetail = makeDetail(posts);
    const root = createRoot(container);

    const renderPane = (detailToRender: TopicDetailModel, isAuth = true) => {
      root.render(
        React.createElement(TopicReaderPane, {
          summary: makeSummary({ archived: detailToRender.archived }),
          detail: detailToRender,
          isLoading: false,
          errorMessage: null,
          targetFloor: null,
          targetJumpNonce: 0,
          onlyOpFilter: false,
          isAuthorized: isAuth,
          currentUser: isAuth ? currentUser : null,
          onRefresh: vi.fn().mockResolvedValue(undefined),
          onToggleOnlyOp: vi.fn(),
          onAppendPosts: (incoming) => {
            const map = new Map(currentDetail.posts.map((p) => [p.id, p]));
            for (const item of incoming) {
              map.set(item.id, mergePostPreservingBadges(map.get(item.id), item));
            }
            currentDetail = {
              ...currentDetail,
              posts: Array.from(map.values()).sort((a, b) => a.postNumber - b.postNumber),
            };
            renderPane(currentDetail, isAuth);
          },
          onUpdateDetail: vi.fn(),
          onRemovePost: vi.fn(),
          onOpenComposer: vi.fn(),
          onInspectUser: vi.fn(),
          onOpenImageLightbox: vi.fn(),
          onNavigateTopicLink: vi.fn(),
          onRequestGuestLogin: vi.fn(),
          onTriggerVerify: vi.fn(),
          showBackButton: false,
          onBack: vi.fn(),
          onFloorSeen: vi.fn(),
        })
      );
    };

    await act(async () => {
      renderPane(currentDetail, true);
    });

    // Only post #1 (canBoost=true, not boosted yet) renders the rocket button
    const boostButtons = container.querySelectorAll('button[title="发短评"]');
    expect(boostButtons.length).toBe(1);

    // Simulate race condition: open composer on post #1, type, submit -> server returns max boosts error
    sendPostBoostMock.mockRejectedValueOnce(
      new Error("This post has reached the maximum number of boosts.")
    );
    await act(async () => {
      (boostButtons[0] as HTMLButtonElement).click();
    });
    const input = container.querySelector('input[placeholder="说点什么…"]') as HTMLInputElement;
    expect(input).toBeTruthy();

    await act(async () => {
      const nativeSetter = Object.getOwnPropertyDescriptor(
        window.HTMLInputElement.prototype,
        "value"
      )?.set;
      nativeSetter?.call(input, "及时止损");
      input.dispatchEvent(new Event("input", { bubbles: true }));
    });

    const sendBtn = container.querySelector('button[title="发送短评"]') as HTMLButtonElement;
    expect(sendBtn).toBeTruthy();
    await act(async () => {
      sendBtn.click();
    });

    // After max-limit error: composer closes, button disappears, Chinese toast shown
    expect(container.querySelectorAll('button[title="发短评"]').length).toBe(0);
    expect(container.querySelector('input[placeholder="说点什么…"]')).toBeNull();
    expect(container.textContent).toContain("该楼层短评已达上限");

    // Archived topic hides boost button even if post.canBoost=true
    await act(async () => {
      renderPane(makeDetail([makePost({ id: 9, postNumber: 1, canBoost: true })], { archived: true }), true);
    });
    expect(container.querySelectorAll('button[title="发短评"]').length).toBe(0);

    // Guest hides boost button
    await act(async () => {
      renderPane(makeDetail([makePost({ id: 10, postNumber: 1, canBoost: true })]), false);
    });
    expect(container.querySelectorAll('button[title="发短评"]').length).toBe(0);

    await act(async () => {
      root.unmount();
    });
  });
});
