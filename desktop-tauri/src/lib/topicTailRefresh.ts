import type { PostItemModel, TopicDetailModel } from "./types";

/**
 * 提取远端尾部 `post_ids` 中尚未加载的新楼层 ID（去重保序，最多单批 20 条）。
 */
export function computeTailNewPostIds(
  loadedPosts: readonly PostItemModel[],
  candidatePostIds: readonly number[],
  batchLimit = 20
): number[] {
  const loadedIds = new Set(loadedPosts.map((p) => p.id));
  const out: number[] = [];
  const seen = new Set<number>();
  for (const id of candidatePostIds) {
    if (!Number.isFinite(id) || id <= 0) continue;
    if (loadedIds.has(id) || seen.has(id)) continue;
    seen.add(id);
    out.push(id);
    if (out.length >= batchLimit) break;
  }
  return out;
}

export interface TailRefreshMergeOptions {
  onlyOpFilter: boolean;
}

export interface TailRefreshMergeResult {
  detail: TopicDetailModel;
  addedCount: number;
}

/**
 * 原地增量合并尾部新评论：
 * - 绝不替换现有楼层引用（保留 1 楼徽章与渲染态，避免整页闪刷和滚动跳位）
 * - 合并去重 `streamIds`，同步抬高 `postsCount` / `replyCount`
 * - `onlyOpFilter` 模式下仅计入与追加楼主新回复
 */
export function mergeIncrementalTailPosts(
  prevDetail: TopicDetailModel,
  incomingStreamTailIds: readonly number[],
  fetchedPosts: readonly PostItemModel[],
  options: TailRefreshMergeOptions
): TailRefreshMergeResult {
  const opPost = prevDetail.posts.find((p) => p.postNumber === 1);
  const opUserId = opPost?.userId;
  const opUsername = opPost?.username?.toLowerCase();

  const existingStreamSet = new Set(prevDetail.streamIds);
  const mergedStreamIds = [...prevDetail.streamIds];
  for (const id of incomingStreamTailIds) {
    if (id > 0 && !existingStreamSet.has(id)) {
      existingStreamSet.add(id);
      mergedStreamIds.push(id);
    }
  }

  const existingPostIds = new Set(prevDetail.posts.map((p) => p.id));
  const eligibleNew: PostItemModel[] = [];
  for (const post of fetchedPosts) {
    if (existingPostIds.has(post.id)) continue;
    if (options.onlyOpFilter) {
      const isOp =
        (opUserId != null && post.userId != null && post.userId === opUserId) ||
        (!!opUsername && post.username.toLowerCase() === opUsername);
      if (!isOp) continue;
    }
    existingPostIds.add(post.id);
    if (!existingStreamSet.has(post.id)) {
      existingStreamSet.add(post.id);
      mergedStreamIds.push(post.id);
    }
    eligibleNew.push(post);
  }

  if (eligibleNew.length === 0) {
    const updatedStreamOnly =
      mergedStreamIds.length > prevDetail.streamIds.length
        ? {
            ...prevDetail,
            streamIds: mergedStreamIds,
            postsCount: Math.max(prevDetail.postsCount, mergedStreamIds.length),
          }
        : prevDetail;
    return {
      detail: updatedStreamOnly,
      addedCount: 0,
    };
  }

  const mergedPosts = [...prevDetail.posts, ...eligibleNew].sort(
    (a, b) => a.postNumber - b.postNumber
  );
  const highestPostNumber = mergedPosts.reduce(
    (max, p) => Math.max(max, p.postNumber),
    prevDetail.postsCount
  );
  const nextPostsCount = Math.max(prevDetail.postsCount, mergedStreamIds.length, highestPostNumber);

  return {
    detail: {
      ...prevDetail,
      streamIds: mergedStreamIds,
      posts: mergedPosts,
      postsCount: nextPostsCount,
    },
    addedCount: eligibleNew.length,
  };
}
