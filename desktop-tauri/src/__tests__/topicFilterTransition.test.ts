import { describe, expect, it } from "vitest";
import fs from "node:fs";
import path from "node:path";
import type { PostItemModel, TopicDetailModel } from "../lib/types";
import {
  applyTopicFilterResult,
  computeFilterScrollTarget,
  describeRepliesFilterBar,
} from "../lib/topicTransition";

function makePost(postNumber: number, overrides: Partial<PostItemModel> = {}): PostItemModel {
  return {
    id: 1000 + postNumber,
    topicId: 3155790,
    postNumber,
    username: postNumber === 1 ? "RelayRouter" : `user${postNumber}`,
    displayName: postNumber === 1 ? "RelayRouter" : `User ${postNumber}`,
    avatarTemplate: "/user_avatar/linux.do/u/{size}/1.png",
    trustLevel: 2,
    isModerator: false,
    isAdmin: false,
    createdAt: "2026-11-11T00:00:00Z",
    updatedAt: "2026-11-11T00:00:00Z",
    cookedHtml: `<p>Floor ${postNumber}</p>`,
    likeCount: postNumber === 2 ? 12 : 0,
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
    ...overrides,
  };
}

function makeDetail(posts: PostItemModel[], streamIds?: number[]): TopicDetailModel {
  return {
    id: 3155790,
    title: "Gemini 4 Carbon",
    archetype: "regular",
    tags: [],
    views: 2600,
    postsCount: 403,
    likeCount: 133,
    bookmarked: false,
    closed: false,
    archived: false,
    notificationLevel: 1,
    opUsername: "RelayRouter",
    streamIds: streamIds ?? posts.map((p) => p.id),
    posts,
    hasSummary: true,
  };
}

describe("topic filter transition (全部 / 热门回复 / 只看楼主)", () => {
  it("preserves Post #1 object reference while strictly replacing replies on summary switch", () => {
    const post1 = makePost(1, { cookedHtml: "<p>Main topic body</p>" });
    const prev = makeDetail(
      [post1, makePost(2), makePost(3), makePost(4), makePost(5)],
      [1001, 1002, 1003, 1004, 1005, 1006, 1007]
    );

    const freshPost1 = makePost(1, { cookedHtml: "<p>Main topic body</p>" });
    const fresh = makeDetail(
      [freshPost1, makePost(18, { likeCount: 35 }), makePost(42, { likeCount: 28 })],
      [1001, 1018, 1042, 1099]
    );

    const next = applyTopicFilterResult(prev, fresh);

    // Post #1 reference MUST be preserved so React + MemoizedCookedBody never flicker or re-decorate!
    expect(next.posts[0]).toBe(post1);
    // Stale unfiltered replies (#2, #3, #4, #5) MUST NOT leak into filtered summary view
    expect(next.posts.map((p) => p.postNumber)).toEqual([1, 18, 42]);
    expect(next.streamIds).toEqual([1001, 1018, 1042, 1099]);
  });

  it("replaces Post #1 if the author edited Post #1 between filter switches", () => {
    const post1Old = makePost(1, { updatedAt: "2026-11-11T00:00:00Z", cookedHtml: "<p>v1</p>" });
    const prev = makeDetail([post1Old, makePost(2)]);
    const post1Edited = makePost(1, { updatedAt: "2026-11-12T00:00:00Z", cookedHtml: "<p>v2</p>" });
    const fresh = makeDetail([post1Edited, makePost(4, { username: "RelayRouter" })]);

    const next = applyTopicFilterResult(prev, fresh);
    expect(next.posts[0]).toBe(post1Edited);
    expect(next.posts[0].cookedHtml).toBe("<p>v2</p>");
  });

  it("computes clear filtered reply counts for summary and only-op modes", () => {
    const summaryDetail = makeDetail(
      [makePost(1), makePost(18), makePost(42)],
      [1001, 1018, 1042, 1099, 1120]
    );

    const summaryBar = describeRepliesFilterBar(summaryDetail, {
      onlyOp: false,
      summary: true,
    });
    expect(summaryBar.filteredReplyCount).toBe(4);
    expect(summaryBar.label).toContain("4");
    expect(summaryBar.label).toContain("热门");

    const opBar = describeRepliesFilterBar(summaryDetail, {
      onlyOp: true,
      summary: false,
    });
    expect(opBar.filteredReplyCount).toBe(4);
    expect(opBar.label).toContain("楼主");

    const allBar = describeRepliesFilterBar(summaryDetail, {
      onlyOp: false,
      summary: false,
    });
    expect(allBar.label).toBe("402 条回复");
  });

  it("keeps scroll steady when replies divider is already comfortably in view", () => {
    // Viewport height = 800, user is scrolled to 1200, replies divider sits at offsetTop 1360 (160px from top)
    const decision = computeFilterScrollTarget({
      scrollTop: 1200,
      clientHeight: 800,
      dividerOffsetTop: 1360,
    });
    expect(decision.shouldScroll).toBe(false);
  });

  it("smoothly aligns to replies divider when user was deep inside replies (divider scrolled above viewport)", () => {
    // User read down to floor #80 (scrollTop = 6400), divider is back up at 1360
    const decision = computeFilterScrollTarget({
      scrollTop: 6400,
      clientHeight: 800,
      dividerOffsetTop: 1360,
    });
    expect(decision.shouldScroll).toBe(true);
    expect(decision.behavior).toBe("smooth");
    expect(decision.targetScrollTop).toBe(1348); // dividerOffsetTop - 12
  });

  it("smoothly brings replies into view when divider is near bottom edge or below viewport", () => {
    // User is near top of Post #1 (scrollTop = 0), divider sits at 1400 (below viewport 800)
    const decision = computeFilterScrollTarget({
      scrollTop: 0,
      clientHeight: 800,
      dividerOffsetTop: 1400,
    });
    expect(decision.shouldScroll).toBe(true);
    expect(decision.behavior).toBe("smooth");
    expect(decision.targetScrollTop).toBe(1388);
  });

  it("App.tsx uses filterSwitch mode for onToggleSummaryFilter and onToggleOnlyOp instead of wiping detail", () => {
    const appSource = fs.readFileSync(
      path.resolve(__dirname, "../App.tsx"),
      "utf8"
    );
    expect(appSource).toMatch(/onToggleSummaryFilter[\s\S]{0,400}filterSwitch:\s*true/);
    expect(appSource).toMatch(/onToggleOnlyOp[\s\S]{0,400}filterSwitch:\s*true/);
  });

  it("HomeFeedPane compact mode uses an enlarged readable avatar (>= 34px)", () => {
    const feedSource = fs.readFileSync(
      path.resolve(__dirname, "../components/HomeFeedPane.tsx"),
      "utf8"
    );
    const match = feedSource.match(/sizePx=\{isCompact \? (\d+) : 38\}/);
    expect(match).not.toBeNull();
    expect(Number(match![1])).toBeGreaterThanOrEqual(34);
  });
});
