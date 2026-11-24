export interface TopicReadResolution {
  effectiveUnseen: boolean;
  effectiveUnreadPosts: number;
  isUnread: boolean;
  isReadDimmed: boolean;
}

/**
 * 严格对齐 Discourse 官方话题列表已读/未读模型（与移动端 FloorReadState.kt 1:1 同一数学定义）：
 * 1. `unseen`（从未访问过的新话题）：只要已访问过该话题（`effectiveReadFloor >= 1`），`unseen` 立即清除；
 * 2. `unreadPosts`（正在跟踪的话题产生的新回复）：`remaining = min(unreadPosts, max(0, highest - effectiveReadFloor))`，读完所有可见楼层后清零；
 * 3. `isUnread = effectiveUnseen || effectiveUnreadPosts > 0`；
 * 4. `isReadDimmed = !isUnread && hasVisited`（对齐 Discourse Web `.topic-list-item.visited`）。
 */
export function resolveDiscourseTopicUnread(params: {
  unseen: boolean;
  unreadPosts: number;
  highestPostNumber: number;
  postsCount: number;
  lastReadPostNumber?: number | null;
  localReadFloor?: number;
}): TopicReadResolution {
  const effectiveReadFloor = Math.max(
    params.localReadFloor ?? 0,
    params.lastReadPostNumber ?? 0
  );
  const hasVisited = effectiveReadFloor >= 1;
  const effectiveUnseen = Boolean(params.unseen) && !hasVisited;
  const highest = Math.max(params.highestPostNumber || 1, params.postsCount || 1, 1);
  const rawUnread = Math.max(0, params.unreadPosts || 0);
  const effectiveUnreadPosts =
    rawUnread > 0
      ? Math.min(rawUnread, Math.max(0, highest - Math.max(0, effectiveReadFloor)))
      : 0;
  const isUnread = effectiveUnseen || effectiveUnreadPosts > 0;
  const isReadDimmed = !isUnread && hasVisited;
  return {
    effectiveUnseen,
    effectiveUnreadPosts,
    isUnread,
    isReadDimmed,
  };
}

/**
 * 当已阅读至话题最后一个实际可见楼层（`lastLoaded.id === lastStreamId`）时，
 * 自动对齐推进至 `highestPostNumber`，消除因末尾删帖或版主隐藏楼层导致的楼层号差值卡死。
 */
export function resolveEffectiveViewedFloor(params: {
  rawMaxFloor: number;
  posts: ReadonlyArray<{ id: number; postNumber: number; isRead?: boolean }>;
  streamIds: ReadonlyArray<number>;
  onlyOpFilter?: boolean;
  summaryFilter?: boolean;
  topicHighestPostNumber?: number;
  detailHighestPostNumber?: number | null;
  detailPostsCount?: number;
}): number {
  const base = Math.max(0, params.rawMaxFloor);
  if (params.onlyOpFilter || params.summaryFilter || params.posts.length === 0) {
    return base;
  }
  const lastLoaded = params.posts[params.posts.length - 1];
  const lastStreamId =
    params.streamIds.length > 0
      ? params.streamIds[params.streamIds.length - 1]
      : lastLoaded.id;
  if (lastLoaded.id === lastStreamId && base >= lastLoaded.postNumber) {
    return Math.max(
      base,
      params.topicHighestPostNumber ?? 0,
      params.detailHighestPostNumber ?? 0,
      params.detailPostsCount ?? 0
    );
  }
  return base;
}
