import { describe, expect, it } from "vitest";
import {
  FONT_SCALE_PRESETS,
  PC_TYPOGRAPHY_BASELINES,
  computeTieredFontScales,
  nearestFontPreset,
} from "../lib/typographyScale";
import {
  buildSeenHistorySearchQuery,
  filterAndMergeHistoryItems,
  type SearchableHistoryItem,
} from "../lib/historySearch";
import {
  computeTailNewPostIds,
  mergeIncrementalTailPosts,
} from "../lib/topicTailRefresh";
import type { PostItemModel, TopicDetailModel } from "../lib/types";

function makePost(overrides: Partial<PostItemModel> & { id: number; postNumber: number }): PostItemModel {
  return {
    topicId: 100,
    username: `user${overrides.postNumber}`,
    displayName: `User ${overrides.postNumber}`,
    avatarTemplate: "/a.png",
    cookedHtml: `<p>post ${overrides.postNumber}</p>`,
    createdAt: "2026-11-20T09:00:00Z",
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
    ...overrides,
  };
}

describe("Typography best-practice scale & hierarchy", () => {
  it("exposes unified 5-step presets (0.88 .. 1.28) with 1.00 as 标准", () => {
    expect(FONT_SCALE_PRESETS.map((p) => p.label)).toEqual(["最小", "小", "标准", "大", "最大"]);
    expect(FONT_SCALE_PRESETS.map((p) => p.scale)).toEqual([0.88, 0.94, 1.0, 1.12, 1.28]);
    expect(nearestFontPreset(1.0).label).toBe("标准");
    expect(nearestFontPreset(0.85).label).toBe("最小");
    expect(nearestFontPreset(1.2).scale).toBe(1.12);
    expect(nearestFontPreset(1.25).scale).toBe(1.28);
  });

  it("enforces non-inverted feed-vs-body hierarchy at standard 1.0x", () => {
    expect(PC_TYPOGRAPHY_BASELINES.detailTitlePx).toBeGreaterThanOrEqual(21);
    expect(PC_TYPOGRAPHY_BASELINES.feedLooseTitlePx).toBeGreaterThan(PC_TYPOGRAPHY_BASELINES.postBodyPx);
    // Compact feed headline must NOT shrink below post body (was 13.5px vs 15px!)
    expect(PC_TYPOGRAPHY_BASELINES.feedCompactTitlePx).toBeGreaterThanOrEqual(
      PC_TYPOGRAPHY_BASELINES.postBodyPx
    );
    expect(PC_TYPOGRAPHY_BASELINES.compactMetaPx).toBeGreaterThanOrEqual(12);
  });

  it("applies 3-tier clamped non-linear scaling so chrome & meta never break at max scale", () => {
    const atMax = computeTieredFontScales(1.28);
    expect(atMax.reading).toBeCloseTo(1.28, 3);
    expect(atMax.meta).toBeLessThanOrEqual(1.15);
    expect(atMax.meta).toBeGreaterThan(1.1);
    expect(atMax.chrome).toBeLessThanOrEqual(1.08);
    expect(atMax.chrome).toBeGreaterThan(1.0);

    const atMin = computeTieredFontScales(0.88);
    expect(atMin.reading).toBeCloseTo(0.88, 3);
    expect(atMin.meta).toBeGreaterThanOrEqual(0.94);
    expect(atMin.chrome).toBeGreaterThanOrEqual(0.96);

    const atStd = computeTieredFontScales(1.0);
    expect(atStd).toEqual({ reading: 1, meta: 1, chrome: 1 });
  });
});

describe("Reading history search (instant local + cloud in:seen merge)", () => {
  const sampleHistory: SearchableHistoryItem[] = [
    {
      topicId: 3211140,
      title: "antigravity缓存大调查",
      categoryName: "开发调优",
      authorUsername: "zhenzhu",
      authorName: "丁真珍珠",
      authorAvatar: "/a.png",
      replyCount: 65,
      timeIso: "2026-11-20T09:00:00Z",
    },
    {
      topicId: 3211101,
      title: "请教Codex子Agent并发上限",
      categoryName: "开发调优",
      authorUsername: "qyqj",
      authorName: "QyQj",
      authorAvatar: "/b.png",
      replyCount: 4,
      timeIso: "2026-11-20T08:30:00Z",
    },
    {
      topicId: 3210900,
      title: "今天清仓了，入场两年 亏损60%",
      categoryName: "搞七捻三",
      authorUsername: "syskey",
      authorName: "syskey",
      authorAvatar: "/c.png",
      replyCount: 53,
      timeIso: "2026-11-20T08:20:00Z",
    },
  ];

  it("supports multi-token case-insensitive AND filtering across title, author, category, and topicId", () => {
    const byMultiToken = filterAndMergeHistoryItems(sampleHistory, [], "codex 并发");
    expect(byMultiToken.map((x) => x.topicId)).toEqual([3211101]);

    const byAuthorAndCategory = filterAndMergeHistoryItems(sampleHistory, [], "搞七捻三 syskey");
    expect(byAuthorAndCategory.map((x) => x.topicId)).toEqual([3210900]);

    const byTopicId = filterAndMergeHistoryItems(sampleHistory, [], "#3211140");
    expect(byTopicId.map((x) => x.topicId)).toEqual([3211140]);
  });

  it("merges cloud in:seen search hits deduplicated after local matches and tags them", () => {
    const cloudHits: SearchableHistoryItem[] = [
      {
        ...sampleHistory[1],
        fromCloudSearch: true,
      },
      {
        topicId: 2999001,
        title: "三个月前的 Codex MCP 实战笔记",
        categoryName: "开发调优",
        authorUsername: "olddev",
        authorName: "OldDev",
        authorAvatar: "/d.png",
        replyCount: 19,
        timeIso: "2026-08-10T08:00:00Z",
        fromCloudSearch: true,
      },
    ];
    const merged = filterAndMergeHistoryItems(sampleHistory, cloudHits, "codex");
    expect(merged.map((x) => x.topicId)).toEqual([3211101, 2999001]);
    expect(merged[0].fromCloudSearch).toBeFalsy();
    expect(merged[1].fromCloudSearch).toBe(true);
  });

  it("builds valid Discourse in:seen search query without duplicate qualifiers", () => {
    expect(buildSeenHistorySearchQuery("   ")).toBeNull();
    expect(buildSeenHistorySearchQuery("codex 并发")).toBe("codex 并发 in:seen");
    expect(buildSeenHistorySearchQuery("codex in:seen")).toBe("codex in:seen");
  });
});

describe("Incremental topic tail refresh (append only new comments without full reload)", () => {
  const baseDetail: TopicDetailModel = {
    id: 500,
    title: "测试帖子",
    postsCount: 3,
    views: 99,
    likeCount: 5,
    bookmarked: false,
    closed: false,
    archived: false,
    notificationLevel: 1,
    opUsername: "user1",
    hasSummary: false,
    tags: [],
    streamIds: [10, 11, 12],
    posts: [
      makePost({ id: 10, postNumber: 1, userId: 1, authorBadges: [{ id: 1, name: "Badge", description: "", badgeTypeId: 1 }] }),
      makePost({ id: 11, postNumber: 2, userId: 2 }),
      makePost({ id: 12, postNumber: 3, userId: 3 }),
    ],
  };

  it("extracts strictly unloaded tail post IDs in order", () => {
    const newIds = computeTailNewPostIds(baseDetail.posts, [12, 13, 14, 13]);
    expect(newIds).toEqual([13, 14]);
  });

  it("appends only new tail posts while preserving Post #1 badges and existing post references", () => {
    const freshPosts = [
      makePost({ id: 13, postNumber: 4, userId: 4 }),
      makePost({ id: 14, postNumber: 5, userId: 1 }),
    ];
    const res = mergeIncrementalTailPosts(baseDetail, [13, 14], freshPosts, {
      onlyOpFilter: false,
    });
    expect(res.addedCount).toBe(2);
    expect(res.detail.posts.map((p) => p.postNumber)).toEqual([1, 2, 3, 4, 5]);
    expect(res.detail.streamIds).toEqual([10, 11, 12, 13, 14]);
    expect(res.detail.postsCount).toBe(5);
    expect(res.detail.posts[0]).toBe(baseDetail.posts[0]);
    expect(res.detail.posts[0].authorBadges?.length).toBe(1);
  });

  it("respects onlyOpFilter during tail refresh and reports addedCount=0 when no new OP posts", () => {
    const nonOp = [makePost({ id: 13, postNumber: 4, userId: 99, username: "other" })];
    const resNoOp = mergeIncrementalTailPosts(baseDetail, [13], nonOp, {
      onlyOpFilter: true,
    });
    expect(resNoOp.addedCount).toBe(0);
    expect(resNoOp.detail.posts.map((p) => p.id)).toEqual([10, 11, 12]);
  });
});
