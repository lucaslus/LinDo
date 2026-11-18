import React, { useEffect, useMemo, useRef, useState } from "react";
import type { CategoryMeta, FeedSort, TopPeriod, TopicSummary, UserProfile } from "../lib/types";
import { handleTitlebarMouseDown } from "../lib/api";
import {
  formatCompactCount,
  formatRelativeTime,
  resolveCategoryVisual,
} from "../lib/palette";
import {
  LinDoCategoryBadge,
  LinDoIcons,
  LinDoTopicTagChip,
  resolveOfficialCategoryVisual,
  resolveOfficialTagVisual,
} from "./LinDoIcons";
import { AvatarWithFlair, MascotExpression, SmoothRefreshIcon } from "./SecondaryScreens";

interface HomeFeedPaneProps {
  topics: TopicSummary[];
  categories: CategoryMeta[];
  currentSort: FeedSort;
  topPeriod: TopPeriod;
  selectedCategoryId: number | null;
  selectedTag: string | null;
  selectedTopicId: number | null;
  isAuthorized: boolean;
  currentUser: UserProfile | null;
  unreadNotificationCount: number;
  pendingNewCount: number;
  isLoading: boolean;
  isLoadingMore: boolean;
  hasMore: boolean;
  errorMessage: string | null;
  loadMoreError: string | null;
  isCompact: boolean;
  defaultSearchExpanded?: boolean;
  readFloors: Record<string, number>;
  onSelectSort: (sort: FeedSort) => void;
  onSelectTopPeriod: (period: TopPeriod) => void;
  onSelectCategory: (categoryId: number | null) => void;
  onSelectTag: (tag: string | null) => void;
  onSelectTopic: (topic: TopicSummary, resumeFloor?: number | null) => void;
  onRefresh: () => void;
  onApplyPendingNew: () => void;
  onLoadMore: () => void;
  onToggleCompact: () => void;
  onOpenSearch: () => void;
  onOpenNotifications: () => void;
  onOpenAuth: () => void;
  onTriggerVerify: () => void;
}

const SORT_TABS: Array<{ key: FeedSort; label: string; authOnly?: boolean }> = [
  { key: "latest", label: "最新" },
  { key: "top", label: "热门" },
  { key: "new", label: "新帖", authOnly: true },
  { key: "unread", label: "未读", authOnly: true },
  { key: "follow", label: "关注", authOnly: true },
];

const TOP_PERIODS: Array<{ key: TopPeriod; label: string }> = [
  { key: "daily", label: "今日" },
  { key: "weekly", label: "本周" },
  { key: "monthly", label: "本月" },
  { key: "yearly", label: "今年" },
  { key: "all", label: "全部" },
];

interface FeedTopicRowProps {
  topic: TopicSummary;
  selected: boolean;
  hideBottomDivider: boolean;
  isCompact: boolean;
  isReadDimmed: boolean;
  isUnread: boolean;
  currentSort: FeedSort;
  onSelectTopic: (topic: TopicSummary) => void;
  onSelectTag: (tag: string | null) => void;
}

const FeedTopicRow: React.FC<FeedTopicRowProps> = React.memo(
  ({
    topic,
    selected,
    hideBottomDivider,
    isCompact,
    isReadDimmed,
    isUnread,
    currentSort,
    onSelectTopic,
    onSelectTag,
  }) => {
    const catVis = resolveCategoryVisual(topic.categoryName || "", topic.categoryColorHex);
    const authorName = topic.originalPoster.name?.trim() || topic.originalPoster.username;
    const catLen = (topic.categoryName || "").length;
    const firstTwoTagsLen = (topic.tags[0]?.length ?? 0) + (topic.tags[1]?.length ?? 0);
    const maxLooseTags = catLen >= 7 || firstTwoTagsLen >= 8 ? 2 : 3;
    const visibleLooseTags = topic.tags.slice(0, maxLooseTags);
    const hiddenLooseTagCount = Math.max(0, topic.tags.length - visibleLooseTags.length);

    return (
      <>
        <div
          onClick={() => onSelectTopic(topic)}
          role="button"
          tabIndex={0}
          onKeyDown={(e) => {
            if (e.key === "Enter" || e.key === " ") {
              e.preventDefault();
              onSelectTopic(topic);
            }
          }}
          className={`lindo-interactive flex text-left ${
            isCompact
              ? "mx-2 my-[1px] items-center gap-2.5 rounded-lg px-2.5 py-2"
              : "mx-2.5 my-[2px] items-start gap-3 rounded-xl px-3 py-[13px]"
          }`}
          style={
            selected
              ? {
                  backgroundColor: "var(--selected-card-bg)",
                  boxShadow: "inset 0 0 0 0.5px var(--selected-card-border)",
                }
              : undefined
          }
        >
          <AvatarWithFlair
            username={topic.originalPoster.username}
            avatarTemplate={topic.originalPoster.avatarTemplate}
            animatedAvatar={topic.originalPoster.animatedAvatar}
            primaryGroupName={topic.originalPoster.primaryGroupName}
            flair={topic.originalPoster.flair}
            sizePx={isCompact ? 26 : 38}
            className={isCompact ? "shrink-0" : "mt-0.5 shrink-0"}
          />

          {isCompact ? (
            /* 紧凑模式：1:1 对齐移动端，首行标题+行内未读圆点，次行官方版块矢量图标+带图标标签胶囊 + 右侧评论数与时间 */
            <div className="min-w-0 flex-1">
              <div className="flex items-center justify-between gap-2">
                <div
                  className="flex min-w-0 flex-1 items-center gap-1 text-[13.5px] font-normal leading-[1.38]"
                  style={{
                    color:
                      isReadDimmed && !selected
                        ? "color-mix(in srgb, var(--ink-primary) 72%, var(--ink-tertiary))"
                        : "var(--ink-primary)",
                  }}
                >
                  {topic.closed && (
                    <LinDoIcons.Lock
                      size={12}
                      className="shrink-0"
                      style={{ color: "var(--ink-tertiary)" }}
                    />
                  )}
                  <span className="truncate">{topic.title}</span>
                  {topic.hasAcceptedAnswer && (
                    <span title="已采纳最佳解答" className="shrink-0">
                      <LinDoIcons.CheckCircleFilled size={13} style={{ color: "#10B981" }} />
                    </span>
                  )}
                  {isUnread && (
                    <span
                      className="ml-0.5 h-[5.5px] w-[5.5px] shrink-0 rounded-full"
                      style={{ backgroundColor: "var(--brand-gold)" }}
                    />
                  )}
                </div>
              </div>

              <div
                className="mt-1.5 flex items-center justify-between gap-2 text-[11.5px] tabular-nums leading-none"
                style={{ color: "var(--ink-tertiary)" }}
              >
                <span className="flex min-w-0 items-center gap-1.5 overflow-hidden">
                  {topic.categoryName && (
                    <LinDoCategoryBadge
                      name={topic.categoryName}
                      fallbackColor={catVis.dotColor}
                      iconSize={11.5}
                      className="text-[11.5px]"
                    />
                  )}
                  {topic.tags.slice(0, 2).map((tg) => (
                    <LinDoTopicTagChip
                      key={tg}
                      tag={tg}
                      compact
                      onClick={(e) => {
                        e.stopPropagation();
                        onSelectTag(tg);
                      }}
                    />
                  ))}
                  {topic.tags.length > 2 && (
                    <span className="shrink-0 text-[10.8px] font-medium">
                      +{topic.tags.length - 2}
                    </span>
                  )}
                </span>

                <span className="flex shrink-0 items-center gap-2">
                  <span className="inline-flex items-center gap-[3px]">
                    <LinDoIcons.MessageCircle size={13} />
                    <span>{formatCompactCount(topic.replyCount)}</span>
                  </span>
                  {topic.lastPostedAt && (
                    <span>{formatRelativeTime(topic.lastPostedAt)}</span>
                  )}
                </span>
              </div>
            </div>
          ) : (
            /* 宽松模式：1:1 对齐移动端四层视觉韵律（标题 -> 官方版块矢量图标 + 带图标标签胶囊 -> 关注摘要 -> 作者·时间 | 点赞·回复·浏览） */
            <div className="min-w-0 flex-1">
              <div
                className="line-clamp-2 text-[15px] font-semibold leading-[1.46]"
                style={{
                  color:
                    isReadDimmed && !selected
                      ? "color-mix(in srgb, var(--ink-primary) 82%, var(--ink-secondary))"
                      : "var(--ink-primary)",
                }}
              >
                {topic.closed && (
                  <LinDoIcons.Lock
                    size={13}
                    className="mr-1 inline-block align-[-1.5px]"
                    style={{ color: "var(--ink-tertiary)" }}
                  />
                )}
                <span>{topic.title}</span>
                {topic.hasAcceptedAnswer && (
                  <span title="已采纳最佳解答" className="ml-1 inline-block align-[-2px]">
                    <LinDoIcons.CheckCircleFilled
                      size={13.5}
                      style={{ color: "#10B981" }}
                    />
                  </span>
                )}
                {isUnread && (
                  <span
                    className="ml-1.5 inline-block h-[6px] w-[6px] rounded-full align-middle"
                    style={{ backgroundColor: "var(--brand-gold)" }}
                  />
                )}
              </div>

              {(topic.categoryName || topic.tags.length > 0) && (
                <div className="mt-1.5 flex items-center gap-1.5 overflow-hidden whitespace-nowrap">
                  {topic.categoryName && (
                    <LinDoCategoryBadge
                      name={topic.categoryName}
                      fallbackColor={catVis.dotColor}
                      iconSize={12}
                      className="mr-0.5 shrink-0 text-[11.8px]"
                    />
                  )}
                  {visibleLooseTags.map((tg) => (
                    <LinDoTopicTagChip
                      key={tg}
                      tag={tg}
                      onClick={(e) => {
                        e.stopPropagation();
                        onSelectTag(tg);
                      }}
                    />
                  ))}
                  {hiddenLooseTagCount > 0 && (
                    <span
                      className="shrink-0 text-[11px] font-medium"
                      style={{ color: "var(--ink-tertiary)" }}
                    >
                      +{hiddenLooseTagCount}
                    </span>
                  )}
                </div>
              )}

              {currentSort === "follow" && topic.excerpt && (
                <p
                  className="mt-1.5 line-clamp-2 text-[12.8px] leading-relaxed"
                  style={{ color: "var(--ink-secondary)" }}
                >
                  {topic.excerpt}
                </p>
              )}

              <div
                className="mt-2 flex items-center justify-between gap-2 text-[12px] tabular-nums"
                style={{ color: "var(--ink-tertiary)" }}
              >
                <span className="truncate">
                  <span className="font-medium">{authorName}</span>
                  {topic.lastPostedAt ? ` · ${formatRelativeTime(topic.lastPostedAt)}` : ""}
                </span>
                <span className="flex shrink-0 items-center gap-3">
                  <span className="inline-flex items-center gap-[3px]">
                    <LinDoIcons.Heart size={13.5} />
                    <span>{formatCompactCount(topic.likeCount)}</span>
                  </span>
                  <span className="inline-flex items-center gap-[3px]">
                    <LinDoIcons.MessageCircle size={13.5} />
                    <span>{formatCompactCount(topic.replyCount)}</span>
                  </span>
                  {topic.views > 0 && (
                    <span className="inline-flex items-center gap-[3px]">
                      <LinDoIcons.Eye size={13.5} />
                      <span>{formatCompactCount(topic.views)}</span>
                    </span>
                  )}
                </span>
              </div>
            </div>
          )}
        </div>
        {!hideBottomDivider && (
          <div className={isCompact ? "lindo-divider-feed-compact" : "lindo-divider-feed"} />
        )}
      </>
    );
  }
);

export const HomeFeedPane: React.FC<HomeFeedPaneProps> = ({
  topics,
  categories,
  currentSort,
  topPeriod,
  selectedCategoryId,
  selectedTag,
  selectedTopicId,
  isAuthorized,
  pendingNewCount,
  isLoading,
  isLoadingMore,
  hasMore,
  errorMessage,
  loadMoreError,
  isCompact,
  readFloors,
  onSelectSort,
  onSelectTopPeriod,
  onSelectCategory,
  onSelectTag,
  onSelectTopic,
  onRefresh,
  onApplyPendingNew,
  onLoadMore,
  onToggleCompact,
  onOpenSearch,
  onOpenAuth,
  onTriggerVerify,
}) => {
  const [catDrawerOpen, setCatDrawerOpen] = useState(false);
  const [focusedParentCatId, setFocusedParentCatId] = useState<number | null>(null);
  const [pinnedExpanded, setPinnedExpanded] = useState(false);
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const sentinelRef = useRef<HTMLDivElement | null>(null);

  // Infinite scroll sentinel
  useEffect(() => {
    const sentinel = sentinelRef.current;
    const root = scrollRef.current;
    if (!sentinel || !root || !hasMore || isLoading || isLoadingMore || loadMoreError) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting) {
          onLoadMore();
        }
      },
      { root, rootMargin: "260px" }
    );
    observer.observe(sentinel);
    return () => observer.disconnect();
  }, [hasMore, isLoading, isLoadingMore, loadMoreError, onLoadMore, topics.length]);

  const visibleSorts = useMemo(
    () => SORT_TABS.filter((s) => !s.authOnly || isAuthorized),
    [isAuthorized]
  );

  const parentCategories = useMemo(() => {
    const seenNames = new Set<string>();
    const deduped: typeof categories = [];
    for (const c of categories) {
      if (c.parentId) continue;
      const key = c.name.trim();
      if (seenNames.has(key)) continue;
      seenNames.add(key);
      deduped.push(c);
    }
    return deduped;
  }, [categories]);

  const activeCategory = useMemo(
    () => categories.find((c) => c.id === selectedCategoryId) ?? null,
    [categories, selectedCategoryId]
  );

  const effectiveParentId =
    focusedParentCatId ?? activeCategory?.parentId ?? activeCategory?.id ?? null;

  const subCategories = useMemo(
    () => (effectiveParentId ? categories.filter((c) => c.parentId === effectiveParentId) : []),
    [categories, effectiveParentId]
  );

  const { pinnedTopics, regularTopics } = useMemo(() => {
    const pinned: TopicSummary[] = [];
    const regular: TopicSummary[] = [];
    for (const t of topics) {
      if (t.pinned && !selectedCategoryId && !selectedTag && currentSort === "latest") {
        pinned.push(t);
      } else {
        regular.push(t);
      }
    }
    return { pinnedTopics: pinned, regularTopics: regular };
  }, [topics, selectedCategoryId, selectedTag, currentSort]);

  const isVerifyError = (msg: string | null) =>
    Boolean(msg && (msg.includes("验证") || msg.includes("challenge")));

  return (
    <section
      className="lindo-hairline-r flex h-full w-full flex-col"
      style={{ backgroundColor: "var(--feed-surface)" }}
    >
      {/* 1. 精简左栏顶部品牌栏（移除与侧栏重复的铃铛/头像，上移刷新与视图切换；释放下方排序标签栏全宽，仅顶行响应窗口拖拽） */}
      <div className="lindo-hairline-b shrink-0 px-4 pt-3 pb-2">
        <div
          onMouseDown={handleTitlebarMouseDown}
          className="flex items-center justify-between gap-2"
        >
          <div className="flex min-w-0 items-center gap-2">
            <img
              src="/brand/lindo-mark.svg"
              alt="Lindo"
              className="h-[26px] w-[26px] shrink-0 object-contain pointer-events-none"
            />
            <div className="flex min-w-0 items-baseline gap-2">
              <span className="text-[18.5px] font-extrabold tracking-tight leading-none">
                <span style={{ color: "var(--ink-primary)" }}>Lin</span>
                <span style={{ color: "var(--logo-do)" }}>do</span>
              </span>
              <span
                className="truncate text-[11px] font-normal tracking-[0.03em]"
                style={{ color: "var(--ink-tertiary)" }}
              >
                真诚 · 友善 · 团结 · 专业
              </span>
            </div>
          </div>

          <div className="lindo-no-drag flex shrink-0 items-center gap-0.5">
            {!isAuthorized && (
              <button
                type="button"
                onClick={onOpenAuth}
                className="lindo-interactive mr-1 rounded-full px-2.5 py-0.5 text-[12.5px] font-semibold"
                style={{ color: "var(--active-chip-text)" }}
              >
                登录
              </button>
            )}

            <button
              type="button"
              onClick={() => {
                scrollRef.current?.scrollTo({ top: 0, behavior: "smooth" });
                onRefresh();
              }}
              title="刷新列表 (⌘R)"
              className="lindo-interactive flex h-7 w-7 items-center justify-center rounded-full"
              style={{ color: "var(--ink-secondary)" }}
            >
              <SmoothRefreshIcon spinning={isLoading} size={15} />
            </button>

            <button
              type="button"
              onClick={onToggleCompact}
              title={isCompact ? "切换宽松列表视图" : "切换紧凑列表视图"}
              className="lindo-interactive flex h-7 w-7 items-center justify-center rounded-full"
              style={{ color: "var(--ink-secondary)" }}
            >
              {isCompact ? (
                <LinDoIcons.LayoutCards size={16} />
              ) : (
                <LinDoIcons.LayoutCompact size={16} />
              )}
            </button>
          </div>
        </div>

        {/* 2. 常驻搜索胶囊入口 */}
        <div className="lindo-no-drag mt-2.5">
          <button
            type="button"
            onClick={onOpenSearch}
            className="flex h-[34px] w-full items-center justify-between rounded-[10px] px-3 text-left transition-colors cursor-pointer"
            style={{
              backgroundColor: "var(--subtle-surface)",
              border: "0.5px solid var(--hairline)",
            }}
          >
            <span className="flex items-center gap-2 min-w-0">
              <LinDoIcons.Search size={14} style={{ color: "var(--ink-tertiary)" }} />
              <span className="truncate text-[12.8px]" style={{ color: "var(--ink-tertiary)" }}>
                搜索话题、标签或用户
              </span>
            </span>
            <kbd
              className="rounded px-1.5 py-0.5 text-[10.5px] font-medium"
              style={{
                backgroundColor: "var(--tonal-pill)",
                color: "var(--ink-tertiary)",
              }}
            >
              ⌘K
            </kbd>
          </button>
        </div>

        {/* 3. 排序标签栏（完整展示「最新 · 热门 · 新帖 · 未读 · 关注」，绝不截断）与分类切换 */}
        <div className="lindo-no-drag mt-2 flex items-center justify-between gap-2">
          <div className="no-scrollbar flex flex-1 items-center gap-[18px] overflow-x-auto">
            {visibleSorts.map((tab) => {
              const selected = currentSort === tab.key;
              return (
                <button
                  key={tab.key}
                  type="button"
                  onClick={() => onSelectSort(tab.key)}
                  className="relative flex h-[28px] shrink-0 items-center justify-center text-[13.5px] cursor-pointer transition-colors"
                  style={{
                    color: selected ? "var(--ink-primary)" : "var(--ink-secondary)",
                    fontWeight: selected ? 600 : 400,
                  }}
                >
                  <span className="hover:opacity-90">{tab.label}</span>
                  {selected && (
                    <span
                      className="absolute bottom-0 h-[2.5px] w-[14px] rounded-full"
                      style={{ backgroundColor: "var(--tab-indicator)" }}
                    />
                  )}
                </button>
              );
            })}
          </div>

          <button
            type="button"
            onClick={() => {
              setFocusedParentCatId(activeCategory?.parentId ?? activeCategory?.id ?? null);
              setCatDrawerOpen((v) => !v);
            }}
            className="lindo-interactive flex h-[28px] shrink-0 items-center gap-1 rounded-md px-1.5 text-[12.8px]"
            style={{
              color:
                catDrawerOpen || selectedCategoryId
                  ? "var(--active-chip-text)"
                  : "var(--ink-secondary)",
              fontWeight: catDrawerOpen || selectedCategoryId ? 600 : 400,
            }}
          >
            <span className="max-w-[96px] truncate">
              {activeCategory
                ? activeCategory.subLabel
                  ? `${parentCategories.find((p) => p.id === activeCategory.parentId)?.name ?? ""} · ${activeCategory.subLabel}`
                  : activeCategory.name
                : "分类"}
            </span>
            {catDrawerOpen ? <LinDoIcons.ChevronUp size={12} /> : <LinDoIcons.ChevronDown size={12} />}
          </button>
        </div>

        {/* 热门二级周期胶囊 */}
        {currentSort === "top" && (
          <div className="lindo-no-drag no-scrollbar mt-2 flex items-center gap-1.5 overflow-x-auto pt-0.5">
            {TOP_PERIODS.map((p) => {
              const active = topPeriod === p.key;
              return (
                <button
                  key={p.key}
                  type="button"
                  onClick={() => onSelectTopPeriod(p.key)}
                  className="shrink-0 rounded-full px-2.5 py-0.5 text-[11.5px] font-medium transition-colors cursor-pointer"
                  style={{
                    backgroundColor: active ? "var(--active-chip-bg)" : "var(--subtle-surface)",
                    color: active ? "var(--active-chip-text)" : "var(--ink-secondary)",
                  }}
                >
                  {p.label}
                </button>
              );
            })}
          </div>
        )}

        {/* 当前激活的分类 / 标签筛选条 */}
        {(activeCategory || selectedTag) && !catDrawerOpen && (
          <div className="lindo-no-drag mt-2 flex flex-wrap items-center gap-1.5">
            {activeCategory && (() => {
              const activeVis = resolveOfficialCategoryVisual(
                activeCategory.name,
                `#${activeCategory.colorHex.replace("#", "")}`,
                undefined,
                activeCategory.readRestricted
              );
              const ActiveCatIcon = activeVis.Icon;
              return (
                <span
                  className="inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-[11.5px] font-medium"
                  style={{
                    backgroundColor: "var(--active-chip-bg)",
                    color: "var(--active-chip-text)",
                  }}
                >
                  <ActiveCatIcon size={11.5} style={{ color: activeVis.color }} />
                  <span>{activeCategory.displayName}</span>
                  <button
                    type="button"
                    onClick={() => onSelectCategory(null)}
                    className="ml-0.5 rounded-full p-0.5 hover:opacity-75 cursor-pointer"
                  >
                    <LinDoIcons.Close size={11} />
                  </button>
                </span>
              );
            })()}
            {selectedTag && (() => {
              const activeTagVis = resolveOfficialTagVisual(selectedTag);
              const ActiveTagIcon = activeTagVis ? activeTagVis.Icon : LinDoIcons.FaTag;
              return (
                <span
                  className="inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-[11.5px] font-medium"
                  style={{
                    backgroundColor: "var(--tonal-pill)",
                    color: "var(--ink-primary)",
                  }}
                >
                  <ActiveTagIcon
                    size={10.5}
                    style={{ color: activeTagVis ? activeTagVis.color : "var(--ink-tertiary)" }}
                  />
                  <span>{selectedTag}</span>
                  <button
                    type="button"
                    onClick={() => onSelectTag(null)}
                    className="ml-0.5 rounded-full p-0.5 hover:opacity-75 cursor-pointer"
                  >
                    <LinDoIcons.Close size={11} />
                  </button>
                </span>
              );
            })()}
          </div>
        )}

        {/* 可展开分类 Bento 面板（支持一级主版块 + Lv1–Lv3 / 网盘二级切换） */}
        {catDrawerOpen && (
          <div
            className="lindo-no-drag mt-2.5 rounded-xl p-2.5"
            style={{
              backgroundColor: "var(--subtle-surface)",
              border: "0.5px solid var(--hairline)",
            }}
          >
            <div className="flex flex-wrap gap-1.5 max-h-[196px] lindo-scroll">
              <button
                type="button"
                onClick={() => {
                  setFocusedParentCatId(null);
                  onSelectCategory(null);
                  setCatDrawerOpen(false);
                }}
                className="rounded-full px-2.5 py-1 text-[12px] transition-colors cursor-pointer"
                style={{
                  backgroundColor:
                    selectedCategoryId === null ? "var(--active-chip-bg)" : "var(--card-surface)",
                  color:
                    selectedCategoryId === null ? "var(--active-chip-text)" : "var(--ink-secondary)",
                  fontWeight: selectedCategoryId === null ? 500 : 400,
                }}
              >
                全部分类
              </button>
              {parentCategories.map((cat) => {
                const selected = effectiveParentId === cat.id;
                const vis = resolveCategoryVisual(cat.name, cat.colorHex);
                const offVis = resolveOfficialCategoryVisual(
                  cat.name,
                  vis.dotColor,
                  undefined,
                  cat.readRestricted
                );
                const CatIcon = offVis.Icon;
                const hasSubs = categories.some((s) => s.parentId === cat.id);
                return (
                  <button
                    key={cat.id}
                    type="button"
                    onClick={() => {
                      setFocusedParentCatId(cat.id);
                      onSelectCategory(cat.id);
                      if (!hasSubs) setCatDrawerOpen(false);
                    }}
                    className="inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[12px] transition-colors cursor-pointer"
                    style={{
                      backgroundColor: selected ? "var(--active-chip-bg)" : "var(--card-surface)",
                      color: selected ? "var(--active-chip-text)" : "var(--ink-secondary)",
                      fontWeight: selected ? 500 : 400,
                    }}
                  >
                    <CatIcon size={11.5} style={{ color: offVis.color }} />
                    {cat.readRestricted && (
                      <LinDoIcons.FaLock size={9.5} style={{ color: "var(--ink-tertiary)" }} />
                    )}
                    <span>{cat.name}</span>
                  </button>
                );
              })}
            </div>

            {subCategories.length > 0 && effectiveParentId && (
              <div className="no-scrollbar mt-2 flex items-center gap-2 overflow-x-auto pt-1.5">
                <button
                  type="button"
                  onClick={() => {
                    onSelectCategory(effectiveParentId);
                    setCatDrawerOpen(false);
                  }}
                  className="shrink-0 rounded-md px-2 py-0.5 text-[11.8px] cursor-pointer"
                  style={{
                    backgroundColor:
                      selectedCategoryId === effectiveParentId ? "var(--active-chip-bg)" : "transparent",
                    color:
                      selectedCategoryId === effectiveParentId
                        ? "var(--active-chip-text)"
                        : "var(--ink-secondary)",
                    fontWeight: selectedCategoryId === effectiveParentId ? 500 : 400,
                  }}
                >
                  主版块
                </button>
                {subCategories.map((sub) => {
                  const selected = selectedCategoryId === sub.id;
                  return (
                    <button
                      key={sub.id}
                      type="button"
                      onClick={() => {
                        onSelectCategory(sub.id);
                        setCatDrawerOpen(false);
                      }}
                      className="inline-flex shrink-0 items-center gap-1 rounded-md px-2 py-0.5 text-[11.8px] transition-colors cursor-pointer"
                      style={{
                        backgroundColor: selected ? "var(--active-chip-bg)" : "transparent",
                        color: selected ? "var(--active-chip-text)" : "var(--ink-secondary)",
                        fontWeight: selected ? 500 : 400,
                      }}
                    >
                      {sub.readRestricted && <LinDoIcons.FaLock size={9.5} />}
                      <span>{sub.subLabel || sub.name}</span>
                    </button>
                  );
                })}
              </div>
            )}
          </div>
        )}
      </div>

      {/* 4. 主话题滚动容器（原生 120Hz WebKit 合成器滚动 + 0.5px 内缩细分隔线） */}
      <div className="relative flex-1 min-h-0">
        {/* 顶部液态毛玻璃「N 条新动态」悬浮胶囊 */}
        {pendingNewCount > 0 && !isLoading && (
          <div
            className="pointer-events-none absolute inset-x-0 top-0 z-20 flex justify-center pt-2 pb-3"
            style={{
              background:
                "linear-gradient(to bottom, var(--feed-surface) 0%, color-mix(in srgb, var(--feed-surface) 82%, transparent) 55%, transparent 100%)",
            }}
          >
            <button
              type="button"
              onClick={() => {
                scrollRef.current?.scrollTo({ top: 0, behavior: "smooth" });
                onApplyPendingNew();
              }}
              className="pointer-events-auto flex h-[30px] items-center gap-1.5 rounded-full px-3.5 text-[12px] font-medium shadow-lg transition-transform hover:scale-[1.02] active:scale-[0.98] cursor-pointer"
              style={{
                backgroundColor: "var(--subtle-surface)",
                border: "0.75px solid color-mix(in srgb, var(--brand-gold) 38%, transparent)",
                color: "var(--brand-gold)",
              }}
            >
              <SmoothRefreshIcon spinning={false} size={13} />
              <span>{pendingNewCount >= 25 ? "20+" : pendingNewCount} 条新动态</span>
            </button>
          </div>
        )}

        <div ref={scrollRef} className="lindo-scroll h-full w-full">
          {isLoading && topics.length === 0 ? (
            /* 无缝 GPU 流光骨架屏（适配宽松双行 / 紧凑单行双模式，循环首尾 100% 无跳帧） */
            <div>
              {Array.from({ length: isCompact ? 11 : 8 }).map((_, idx) => (
                <React.Fragment key={idx}>
                  <div className={`flex ${isCompact ? "gap-2.5 px-4 py-2.5" : "gap-3 px-4 py-3.5"}`}>
                    <div
                      className={`lindo-shimmer mt-0.5 shrink-0 rounded-full ${
                        isCompact ? "h-[26px] w-[26px]" : "h-[38px] w-[38px]"
                      }`}
                    />
                    <div className={`min-w-0 flex-1 ${isCompact ? "space-y-1.5" : "space-y-2"}`}>
                      <div
                        className={`lindo-shimmer rounded-md ${isCompact ? "h-[14px]" : "h-[15px]"}`}
                        style={{ width: idx % 2 === 0 ? "82%" : "68%" }}
                      />
                      {!isCompact && idx % 3 !== 2 && (
                        <div className="lindo-shimmer h-[13px] w-[48%] rounded-md" />
                      )}
                      <div className="flex items-center justify-between pt-0.5">
                        <div className="flex items-center gap-1.5">
                          <div className="lindo-shimmer h-[13px] w-14 rounded" />
                          {!isCompact && <div className="lindo-shimmer h-[13px] w-11 rounded" />}
                        </div>
                        <div className="lindo-shimmer h-[12px] w-20 rounded" />
                      </div>
                    </div>
                  </div>
                  <div className="lindo-divider-inset" />
                </React.Fragment>
              ))}
            </div>
          ) : errorMessage && topics.length === 0 ? (
            <div className="flex h-full flex-col items-center justify-center px-6 text-center">
              <MascotExpression expression="surprised" sizePx={58} className="mb-3 opacity-95" />
              <p className="text-[13.5px]" style={{ color: "var(--ink-secondary)" }}>
                {errorMessage}
              </p>
              <button
                type="button"
                onClick={isVerifyError(errorMessage) ? onTriggerVerify : onRefresh}
                className="mt-3.5 rounded-full px-4 py-1.5 text-[12.5px] font-semibold cursor-pointer transition-opacity hover:opacity-90"
                style={{
                  backgroundColor: "var(--publish-btn-bg)",
                  color: "var(--publish-btn-fg)",
                }}
              >
                {isVerifyError(errorMessage) ? "去验证" : "重试"}
              </button>
            </div>
          ) : (
            <div className="py-1">
              {/* 置顶单行静默收纳胶囊（默认浓缩为 1 行，点击标题直达首条置顶，点击右侧展开全部置顶） */}
              {pinnedTopics.length > 0 && (
                <div className="mx-3 mt-1.5 mb-1.5">
                  {!pinnedExpanded ? (
                    <div
                      className="flex items-center gap-2 rounded-[9px] px-2.5 py-[7px]"
                      style={{
                        backgroundColor: "var(--subtle-surface)",
                        border: "0.5px solid var(--hairline-soft)",
                      }}
                    >
                      <button
                        type="button"
                        onClick={() =>
                          pinnedTopics.length > 1
                            ? setPinnedExpanded(true)
                            : onSelectTopic(pinnedTopics[0])
                        }
                        className="inline-flex shrink-0 items-center gap-1 rounded-[5px] px-1.5 py-[1.5px] text-[10.8px] font-semibold cursor-pointer"
                        style={{
                          backgroundColor: "var(--active-chip-bg)",
                          color: "var(--active-chip-text)",
                        }}
                      >
                        <LinDoIcons.PinFilled size={10} />
                        <span>
                          {pinnedTopics.length > 1 ? `${pinnedTopics.length} 置顶` : "置顶"}
                        </span>
                      </button>

                      <button
                        type="button"
                        onClick={() => onSelectTopic(pinnedTopics[0])}
                        className="min-w-0 flex-1 truncate text-left text-[12.3px] transition-opacity hover:opacity-85 cursor-pointer"
                        style={{
                          color:
                            selectedTopicId === pinnedTopics[0].id
                              ? "var(--ink-primary)"
                              : "var(--ink-secondary)",
                          fontWeight: selectedTopicId === pinnedTopics[0].id ? 600 : 400,
                        }}
                      >
                        {pinnedTopics[0].title}
                      </button>

                      {pinnedTopics.length > 1 && (
                        <button
                          type="button"
                          onClick={() => setPinnedExpanded(true)}
                          className="inline-flex shrink-0 items-center gap-0.5 rounded px-1 py-0.5 text-[11px] transition-opacity hover:opacity-85 cursor-pointer"
                          style={{ color: "var(--ink-tertiary)" }}
                        >
                          <span>展开</span>
                          <LinDoIcons.ChevronDown size={11} />
                        </button>
                      )}
                    </div>
                  ) : (
                    <div
                      className="overflow-hidden rounded-[10px]"
                      style={{
                        backgroundColor: "var(--subtle-surface)",
                        border: "0.5px solid var(--hairline-soft)",
                      }}
                    >
                      {pinnedTopics.map((pinned, pIdx) => {
                        const selected = selectedTopicId === pinned.id;
                        return (
                          <React.Fragment key={pinned.id}>
                            {pIdx > 0 && (
                              <div
                                className="mx-3 h-[0.5px]"
                                style={{ backgroundColor: "var(--hairline-soft)" }}
                              />
                            )}
                            <button
                              type="button"
                              onClick={() => onSelectTopic(pinned)}
                              className="lindo-interactive flex w-full items-center gap-2 px-3 py-2 text-left"
                              style={{
                                backgroundColor: selected
                                  ? "var(--selected-card-bg)"
                                  : "transparent",
                              }}
                            >
                              <span
                                className="inline-flex shrink-0 items-center gap-0.5 rounded px-1.5 py-[1px] text-[10.5px] font-semibold"
                                style={{
                                  backgroundColor: "var(--active-chip-bg)",
                                  color: "var(--active-chip-text)",
                                }}
                              >
                                <LinDoIcons.PinFilled size={10} />
                                <span>置顶</span>
                              </span>
                              <span
                                className="flex-1 truncate text-[12.8px]"
                                style={{
                                  color: "var(--ink-primary)",
                                  fontWeight: selected ? 600 : 500,
                                }}
                              >
                                {pinned.title}
                              </span>
                              <span
                                className="shrink-0 text-[11px] tabular-nums"
                                style={{ color: "var(--ink-tertiary)" }}
                              >
                                {formatCompactCount(pinned.replyCount)} 回复
                              </span>
                            </button>
                          </React.Fragment>
                        );
                      })}
                      <div
                        className="h-[0.5px]"
                        style={{ backgroundColor: "var(--hairline-soft)" }}
                      />
                      <button
                        type="button"
                        onClick={() => setPinnedExpanded(false)}
                        className="lindo-interactive flex w-full items-center justify-center gap-1 py-1.5 text-[11.2px]"
                        style={{ color: "var(--ink-tertiary)" }}
                      >
                        <span>收起置顶</span>
                        <LinDoIcons.ChevronUp size={11.5} />
                      </button>
                    </div>
                  )}
                </div>
              )}

              {/* 主话题流列表（单行 React.memo 隔离：悬浮圆角选中卡 + 72px 文本对齐细分割线） */}
              {regularTopics.map((topic, idx) => {
                const selected = topic.id === selectedTopicId;
                const nextTopic = regularTopics[idx + 1];
                const nextSelected = Boolean(nextTopic && nextTopic.id === selectedTopicId);
                const hideBottomDivider =
                  selected || nextSelected || idx === regularTopics.length - 1;
                const confirmedFloor = readFloors[String(topic.id)] ?? topic.lastReadPostNumber ?? 0;
                const isReadDimmed = confirmedFloor >= topic.highestPostNumber;
                const isUnread =
                  (topic.unseen || topic.unreadPosts > 0) && !isReadDimmed;
                return (
                  <FeedTopicRow
                    key={topic.id}
                    topic={topic}
                    selected={selected}
                    hideBottomDivider={hideBottomDivider}
                    isCompact={isCompact}
                    isReadDimmed={isReadDimmed}
                    isUnread={isUnread}
                    currentSort={currentSort}
                    onSelectTopic={onSelectTopic}
                    onSelectTag={onSelectTag}
                  />
                );
              })}

              {/* 底部触底加载栏 / 错误与站点验证恢复栏 */}
              <div ref={sentinelRef} className="flex min-h-[52px] items-center justify-center px-4 py-3">
                {loadMoreError ? (
                  <button
                    type="button"
                    onClick={isVerifyError(loadMoreError) ? onTriggerVerify : onLoadMore}
                    className="lindo-interactive flex items-center gap-2 rounded-full px-3.5 py-1.5 text-[12px]"
                  >
                    <MascotExpression expression="surprised" sizePx={22} />
                    <span style={{ color: "var(--ink-secondary)" }}>{loadMoreError}</span>
                    <span className="font-semibold" style={{ color: "var(--active-chip-text)" }}>
                      {isVerifyError(loadMoreError) ? "去验证" : "重试"}
                    </span>
                  </button>
                ) : isLoadingMore ? (
                  <span className="flex items-center gap-2 text-[12px]" style={{ color: "var(--ink-secondary)" }}>
                    <MascotExpression expression="wink" sizePx={22} className="animate-bounce" />
                    <span>加载更多...</span>
                  </span>
                ) : !hasMore && regularTopics.length > 0 ? (
                  <span className="flex items-center gap-2 text-[11.5px]" style={{ color: "var(--ink-tertiary)" }}>
                    <span className="h-[0.5px] w-6" style={{ backgroundColor: "var(--hairline)" }} />
                    <MascotExpression expression="sleepy" sizePx={18} className="opacity-85" />
                    <span>没有更多了</span>
                    <span className="h-[0.5px] w-6" style={{ backgroundColor: "var(--hairline)" }} />
                  </span>
                ) : null}
              </div>
            </div>
          )}
        </div>
      </div>
    </section>
  );
};
