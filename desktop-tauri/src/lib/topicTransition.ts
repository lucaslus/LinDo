import type { PostItemModel, TopicDetailModel } from "./types";

function samePostVisualSnapshot(a: PostItemModel, b: PostItemModel): boolean {
  return (
    a.id === b.id &&
    a.postNumber === b.postNumber &&
    a.updatedAt === b.updatedAt &&
    a.cookedHtml === b.cookedHtml &&
    a.likeCount === b.likeCount &&
    a.liked === b.liked &&
    a.bookmarked === b.bookmarked &&
    a.replyCount === b.replyCount &&
    a.reactions.length === b.reactions.length &&
    a.boosts.length === b.boosts.length
  );
}

/**
 * Seamlessly swaps a topic's filtered replies (全部 / 热门回复 / 只看楼主) in place:
 * - Preserves Post #1's exact object reference whenever unchanged so React & MemoizedCookedBody
 *   perform zero DOM teardown, zero image reload, and zero visual flicker on Post #1.
 * - Strictly replaces replies (`postNumber > 1`) and `streamIds` from `fresh` so stale unfiltered
 *   replies never leak into a filtered stream.
 */
export function applyTopicFilterResult(
  prev: TopicDetailModel | null,
  fresh: TopicDetailModel
): TopicDetailModel {
  if (!prev || prev.id !== fresh.id) return fresh;
  const prevPost1 = prev.posts.find((p) => p.postNumber === 1);
  const freshPost1 = fresh.posts.find((p) => p.postNumber === 1);
  const nextReplies = fresh.posts
    .filter((p) => p.postNumber !== 1)
    .sort((a, b) => a.postNumber - b.postNumber);

  let mergedPost1: PostItemModel | undefined;
  if (prevPost1 && freshPost1) {
    if (samePostVisualSnapshot(prevPost1, freshPost1)) {
      mergedPost1 = prevPost1;
    } else if (
      (!freshPost1.authorBadges?.length && prevPost1.authorBadges?.length) ||
      (!freshPost1.badgesGranted?.length && prevPost1.badgesGranted?.length)
    ) {
      mergedPost1 = {
        ...freshPost1,
        authorBadges: freshPost1.authorBadges?.length
          ? freshPost1.authorBadges
          : prevPost1.authorBadges,
        badgesGranted: freshPost1.badgesGranted?.length
          ? freshPost1.badgesGranted
          : prevPost1.badgesGranted,
      };
    } else {
      mergedPost1 = freshPost1;
    }
  } else {
    mergedPost1 = freshPost1 ?? prevPost1;
  }

  const nextPosts = mergedPost1 ? [mergedPost1, ...nextReplies] : nextReplies;
  return {
    ...fresh,
    hasSummary: fresh.hasSummary || prev.hasSummary,
    posts: nextPosts,
  };
}

export interface RepliesFilterBarDescription {
  label: string;
  filteredReplyCount: number;
}

export function describeRepliesFilterBar(
  detail: Pick<TopicDetailModel, "postsCount" | "streamIds" | "posts">,
  mode: { onlyOp: boolean; summary: boolean }
): RepliesFilterBarDescription {
  const totalReplies = Math.max(0, (detail.postsCount || detail.posts.length || 1) - 1);
  if (!mode.onlyOp && !mode.summary) {
    return {
      label: `${totalReplies} 条回复`,
      filteredReplyCount: totalReplies,
    };
  }
  const post1Id = detail.posts.find((p) => p.postNumber === 1)?.id;
  const streamReplyCount =
    detail.streamIds.length > 0
      ? detail.streamIds.filter((id) => id !== post1Id).length
      : detail.posts.filter((p) => p.postNumber > 1).length;
  const loadedReplyCount = detail.posts.filter((p) => p.postNumber > 1).length;
  const filteredReplyCount = Math.max(streamReplyCount, loadedReplyCount);

  if (mode.onlyOp) {
    return {
      label: filteredReplyCount > 0 ? `楼主发言 · ${filteredReplyCount} 条` : "楼主发言",
      filteredReplyCount,
    };
  }
  return {
    label: filteredReplyCount > 0 ? `热门精选 · ${filteredReplyCount} 条` : "热门精选回复",
    filteredReplyCount,
  };
}

export interface FilterScrollAdjustment {
  shouldScroll: boolean;
  targetScrollTop: number;
  behavior: ScrollBehavior;
}

/**
 * Decides whether and where to smoothly scroll after toggling 全部 / 热门回复 / 只看楼主:
 * - If the replies divider is already comfortably visible (within `[0, clientHeight - 160]`),
 *   keep the exact scroll position with zero jump!
 * - If the user was scrolled past the divider (deep in replies) or the divider sits near/below
 *   the bottom edge of the viewport, smoothly glide right to `dividerOffsetTop - 12`.
 */
export function computeFilterScrollTarget(params: {
  scrollTop: number;
  clientHeight: number;
  dividerOffsetTop: number;
}): FilterScrollAdjustment {
  const { scrollTop, clientHeight, dividerOffsetTop } = params;
  const targetScrollTop = Math.max(0, Math.round(dividerOffsetTop - 12));
  const relativeTop = dividerOffsetTop - scrollTop;
  const comfortableBottom = Math.max(120, clientHeight - 160);
  if (relativeTop >= 0 && relativeTop <= comfortableBottom) {
    return {
      shouldScroll: false,
      targetScrollTop: scrollTop,
      behavior: "auto",
    };
  }
  return {
    shouldScroll: true,
    targetScrollTop,
    behavior: "smooth",
  };
}
