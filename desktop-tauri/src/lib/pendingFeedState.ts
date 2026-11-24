/**
 * 新动态快照保鲜窗口（90 秒），与移动端 `PendingFeedStateMachine.kt` 1:1 严格对齐：
 * - `< 90s` 内点击提示条：直接 0ms 应用内存快照；
 * - `>= 90s`（如锁屏、切后台、休眠或等待 1 小时后点击）：强制触发实时 `page = 0` 网络拉取，杜绝展示过期列表。
 */
export const STALE_PENDING_SNAPSHOT_MS = 90_000;

/**
 * 跨轮询周期去重累计的最大话题水位数量（展示上限 `99+`，保留 150 条余量供分屏阅读实时消解扣减）。
 */
export const MAX_TRACKED_PENDING_TOPICS = 150;

const TOP_PAGE_ANCHOR_WINDOW = 30;

export interface PendingFeedTopicItem {
  id: number;
  pinned: boolean;
  highestPostNumber: number;
  postsCount: number;
  replyCount: number;
  views: number;
  likeCount: number;
  unseen: boolean;
  unreadPosts: number;
  lastReadPostNumber?: number;
}

export interface PendingTopicWatermark {
  highestPostNumber: number;
  postsCount: number;
}

export interface PendingFeedSnapshot<T extends PendingFeedTopicItem> {
  freshPage0: T[];
  seenChanges: ReadonlyMap<number, PendingTopicWatermark>;
  fetchedAtMs: number;
}

export type PendingFeedApplyDecision<T extends PendingFeedTopicItem> =
  | { kind: "fetch_live" }
  | { kind: "apply_instant"; nextTopics: T[]; resetPageToZero: boolean };

export function createEmptyPendingFeedSnapshot<
  T extends PendingFeedTopicItem,
>(): PendingFeedSnapshot<T> {
  return {
    freshPage0: [],
    seenChanges: new Map(),
    fetchedAtMs: 0,
  };
}

/**
 * 格式化新动态提示条数字：
 * - `<= 0` -> `""`
 * - `1..99` -> 精确数字（消除旧逻辑 `24 -> 20+` 倒挂）
 * - `>= 100` -> `"99+"`
 */
export function formatPendingNewCount(count: number): string {
  if (count <= 0) return "";
  if (count > 99) return "99+";
  return String(count);
}

function isWatermarkAheadOfTopic<T extends PendingFeedTopicItem>(
  watermark: PendingTopicWatermark,
  current: T | undefined
): boolean {
  if (!current) return true;
  return (
    watermark.highestPostNumber > current.highestPostNumber ||
    watermark.postsCount > current.postsCount
  );
}

function isTopicAheadOfCurrent<T extends PendingFeedTopicItem>(
  fresh: T,
  current: T | undefined
): boolean {
  if (!current) return true;
  return (
    fresh.highestPostNumber > current.highestPostNumber ||
    fresh.postsCount > current.postsCount
  );
}

/**
 * 结合当前列表实时计算仍未消解的新动态话题数。
 * 若用户在分屏中已直接点开某条有更新的帖子并同步了最新楼层，计数立即扣减，归零时横幅自动隐藏。
 */
export function countEffectivePendingTopics<T extends PendingFeedTopicItem>(
  snapshot: PendingFeedSnapshot<T>,
  currentTopics: readonly T[]
): number {
  if (
    currentTopics.length === 0 ||
    snapshot.freshPage0.length === 0 ||
    snapshot.seenChanges.size === 0
  ) {
    return 0;
  }
  const currentById = new Map<number, T>();
  for (const item of currentTopics) {
    currentById.set(item.id, item);
  }
  let count = 0;
  for (const [id, watermark] of snapshot.seenChanges.entries()) {
    if (isWatermarkAheadOfTopic(watermark, currentById.get(id))) {
      count += 1;
    }
  }
  return count;
}

/**
 * 评估一次后台静默轮询 `page = 0` 的结果，按唯一 `topic.id` 跨轮次去重累加活跃水位。
 */
export function evaluatePendingFeedPoll<T extends PendingFeedTopicItem>(
  prev: PendingFeedSnapshot<T>,
  currentTopics: readonly T[],
  freshPage0: readonly T[],
  nowMs: number
): PendingFeedSnapshot<T> {
  if (currentTopics.length === 0 || freshPage0.length === 0) {
    return createEmptyPendingFeedSnapshot();
  }
  const currentById = new Map<number, T>();
  for (const item of currentTopics) {
    currentById.set(item.id, item);
  }
  const freshById = new Map<number, T>();
  for (const item of freshPage0) {
    freshById.set(item.id, item);
  }

  const changedInFresh = freshPage0.filter(
    (item) => !item.pinned && isTopicAheadOfCurrent(item, currentById.get(item.id))
  );
  if (changedInFresh.length === 0) {
    return createEmptyPendingFeedSnapshot();
  }

  const nextSeen = new Map<number, PendingTopicWatermark>();
  for (const [id, prevWatermark] of prev.seenChanges.entries()) {
    if (!freshById.has(id) && isWatermarkAheadOfTopic(prevWatermark, currentById.get(id))) {
      nextSeen.set(id, prevWatermark);
    }
  }
  for (const item of changedInFresh) {
    nextSeen.delete(item.id);
    nextSeen.set(item.id, {
      highestPostNumber: item.highestPostNumber,
      postsCount: item.postsCount,
    });
  }
  while (nextSeen.size > MAX_TRACKED_PENDING_TOPICS) {
    const eldestKey = nextSeen.keys().next().value;
    if (eldestKey === undefined) break;
    nextSeen.delete(eldestKey);
  }

  return {
    freshPage0: [...freshPage0],
    seenChanges: nextSeen,
    fetchedAtMs: nowMs,
  };
}

function mergeFreshTopicWithCurrent<T extends PendingFeedTopicItem>(
  fresh: T,
  existing: T | undefined
): T {
  if (!existing) return fresh;
  const resolvedUnseen = !existing.unseen ? false : fresh.unseen;
  const resolvedHighest = Math.max(fresh.highestPostNumber, existing.highestPostNumber);
  const resolvedPostsCount = Math.max(fresh.postsCount, existing.postsCount);
  const resolvedReplyCount = Math.max(fresh.replyCount, existing.replyCount);
  const resolvedViews = Math.max(fresh.views, existing.views);
  const resolvedLikeCount = Math.max(fresh.likeCount, existing.likeCount);
  const maxLastRead = Math.max(fresh.lastReadPostNumber ?? 0, existing.lastReadPostNumber ?? 0);
  const resolvedLastRead =
    maxLastRead > 0 ? maxLastRead : (fresh.lastReadPostNumber ?? existing.lastReadPostNumber);
  const resolvedUnreadPosts =
    !existing.unseen &&
    existing.unreadPosts === 0 &&
    fresh.highestPostNumber <= existing.highestPostNumber &&
    fresh.postsCount <= existing.postsCount
      ? 0
      : fresh.unreadPosts;

  return {
    ...fresh,
    unseen: resolvedUnseen,
    unreadPosts: resolvedUnreadPosts,
    highestPostNumber: resolvedHighest,
    postsCount: resolvedPostsCount,
    replyCount: resolvedReplyCount,
    views: resolvedViews,
    likeCount: resolvedLikeCount,
    ...(resolvedLastRead !== undefined ? { lastReadPostNumber: resolvedLastRead } : {}),
  };
}

/**
 * 用户点击「N 条新动态」时的决策：
 * 1. 快照为空或已超过 90 秒保鲜期 -> `fetch_live`（实时从服务端拉取最新 `page = 0`）；
 * 2. 快照新鲜且与当前首屏未顶起锚点话题连续重叠 -> 无缝头插合并，保留当前分页进度；
 * 3. 快照新鲜但新帖已完全洗掉首屏（超过 1 页新帖，无连续锚点） -> 直接替换为 `freshPage0` 并重置 `page = 0`，避免时间断层与分页跳页。
 */
export function resolvePendingFeedApply<T extends PendingFeedTopicItem>(
  snapshot: PendingFeedSnapshot<T>,
  currentTopics: readonly T[],
  nowMs: number
): PendingFeedApplyDecision<T> {
  if (
    snapshot.freshPage0.length === 0 ||
    snapshot.fetchedAtMs <= 0 ||
    nowMs < snapshot.fetchedAtMs ||
    nowMs - snapshot.fetchedAtMs >= STALE_PENDING_SNAPSHOT_MS
  ) {
    return { kind: "fetch_live" };
  }

  const currentById = new Map<number, T>();
  for (const item of currentTopics) {
    currentById.set(item.id, item);
  }

  const mergedFreshPage0 = snapshot.freshPage0.map((fresh) =>
    mergeFreshTopicWithCurrent(fresh, currentById.get(fresh.id))
  );

  const topCurrentRegularIds = new Set<number>();
  for (const item of currentTopics) {
    if (!item.pinned) {
      topCurrentRegularIds.add(item.id);
      if (topCurrentRegularIds.size >= TOP_PAGE_ANCHOR_WINDOW) break;
    }
  }

  const hasUnchangedAnchorInTopPage = snapshot.freshPage0.some(
    (fresh) =>
      !fresh.pinned &&
      topCurrentRegularIds.has(fresh.id) &&
      !snapshot.seenChanges.has(fresh.id) &&
      !isTopicAheadOfCurrent(fresh, currentById.get(fresh.id))
  );

  if (hasUnchangedAnchorInTopPage) {
    const freshIds = new Set(mergedFreshPage0.map((item) => item.id));
    return {
      kind: "apply_instant",
      nextTopics: [
        ...mergedFreshPage0,
        ...currentTopics.filter((item) => !freshIds.has(item.id)),
      ],
      resetPageToZero: false,
    };
  }

  return {
    kind: "apply_instant",
    nextTopics: mergedFreshPage0,
    resetPageToZero: true,
  };
}
