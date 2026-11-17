import React, { useEffect, useMemo, useRef, useState } from "react";
import {
  MagnifyingGlass,
  Bell,
  CaretDown,
  CaretUp,
  Rows,
  ListBullets,
  Heart,
  ChatCircle,
  Eye,
  PushPin,
  Lock,
  Tag,
  X,
  CheckCircle,
} from "@phosphor-icons/react";
import type { CategoryMeta, FeedSort, TopPeriod, TopicSummary, UserProfile } from "../lib/types";
import { handleTitlebarMouseDown } from "../lib/api";
import { useForumImageSrc } from "../lib/forumMedia";
import {
  formatCompactCount,
  formatRelativeTime,
  getAvatarClassName,
  resolveAvatarUrl,
  resolveCategoryVisual,
} from "../lib/palette";
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

export const HomeFeedPane: React.FC<HomeFeedPaneProps> = ({
  topics,
  categories,
  currentSort,
  topPeriod,
  selectedCategoryId,
  selectedTag,
  selectedTopicId,
  isAuthorized,
  currentUser,
  unreadNotificationCount,
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
  onOpenNotifications,
  onOpenAuth,
  onTriggerVerify,
}) => {
  const [catDrawerOpen, setCatDrawerOpen] = useState(false);
  const [focusedParentCatId, setFocusedParentCatId] = useState<number | null>(null);
  const [pinnedExpanded, setPinnedExpanded] = useState(false);
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const sentinelRef = useRef<HTMLDivElement | null>(null);
  const topUserAvatarFallback = resolveAvatarUrl(currentUser?.avatarTemplate, 96);
  const topUserAvatarSrc = useForumImageSrc(
    resolveAvatarUrl(currentUser?.animatedAvatar || currentUser?.avatarTemplate, 96),
    topUserAvatarFallback
  );

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

  const parentCategories = useMemo(
    () => categories.filter((c) => !c.parentId),
    [categories]
  );

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

  const visiblePinnedTopics = useMemo(
    () => (pinnedExpanded ? pinnedTopics : pinnedTopics.slice(0, 2)),
    [pinnedTopics, pinnedExpanded]
  );

  const isVerifyError = (msg: string | null) =>
    Boolean(msg && (msg.includes("验证") || msg.includes("challenge")));

  return (
    <section
      className="lindo-hairline-r flex h-full w-full flex-col"
      style={{ backgroundColor: "var(--card-surface)" }}
    >
      {/* 1. 顶部品牌栏（仅顶部标题行响应窗口拖拽，绝不干扰下方操作与截图） */}
      <div className="lindo-hairline-b shrink-0 px-4 pt-3 pb-2">
        <div
          onMouseDown={handleTitlebarMouseDown}
          className="flex items-center justify-between gap-2"
        >
          <div className="flex min-w-0 items-center gap-2">
            <img
              src="/brand/lindo-mark.svg"
              alt="Lindo"
              className="h-7 w-7 shrink-0 object-contain pointer-events-none"
            />
            <div className="flex min-w-0 items-baseline gap-2">
              <span className="text-[19px] font-extrabold tracking-tight leading-none">
                <span style={{ color: "var(--ink-primary)" }}>Lin</span>
                <span style={{ color: "var(--logo-do)" }}>do</span>
              </span>
              <span
                className="truncate text-[11px] font-normal tracking-[0.04em]"
                style={{ color: "var(--ink-tertiary)" }}
              >
                真诚 · 友善 · 团结 · 专业
              </span>
            </div>
          </div>

          <div className="lindo-no-drag flex shrink-0 items-center gap-1">
            {isAuthorized ? (
              <>
                <button
                  type="button"
                  onClick={onOpenNotifications}
                  title="通知提醒"
                  className="lindo-interactive relative flex h-8 w-8 items-center justify-center rounded-full"
                  style={{ color: "var(--ink-primary)" }}
                >
                  <Bell size={18} />
                  {unreadNotificationCount > 0 && (
                    <span
                      className="absolute top-[5px] right-[5px] h-[6.5px] w-[6.5px] rounded-full"
                      style={{ backgroundColor: "var(--tab-indicator)" }}
                    />
                  )}
                </button>
                <button
                  type="button"
                  onClick={onOpenAuth}
                  title={`@${currentUser?.username ?? ""} · 账号切换与管理`}
                  className="ml-1 flex h-8 w-8 items-center justify-center overflow-hidden transition-transform hover:scale-[1.03] cursor-pointer"
                >
                  <img
                    src={topUserAvatarSrc}
                    alt={currentUser?.username || "me"}
                    className={getAvatarClassName(
                      "h-full w-full",
                      currentUser?.username,
                      undefined,
                      currentUser?.avatarTemplate
                    )}
                  />
                </button>
              </>
            ) : (
              <button
                type="button"
                onClick={onOpenAuth}
                className="lindo-interactive rounded-full px-3 py-1 text-[13px] font-semibold"
                style={{ color: "var(--active-chip-text)" }}
              >
                登录
              </button>
            )}
          </div>
        </div>

        {/* 2. 常驻搜索胶囊入口（PC 端直接常驻展示，无需设置切换） */}
        <div className="lindo-no-drag mt-2.5">
          <button
            type="button"
            onClick={onOpenSearch}
            className="flex h-[35px] w-full items-center justify-between rounded-full px-3.5 text-left transition-colors cursor-pointer"
            style={{
              backgroundColor: "var(--subtle-surface)",
              border: "0.5px solid var(--hairline)",
            }}
          >
            <span className="flex items-center gap-2 min-w-0">
              <MagnifyingGlass size={14.5} style={{ color: "var(--ink-tertiary)" }} />
              <span className="truncate text-[13px]" style={{ color: "var(--ink-tertiary)" }}>
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

        {/* 3. 排序标签栏与分类/刷新/视图切换 */}
        <div className="lindo-no-drag mt-2 flex items-center justify-between gap-2">
          <div className="no-scrollbar flex flex-1 items-center gap-4 overflow-x-auto">
            {visibleSorts.map((tab) => {
              const selected = currentSort === tab.key;
              return (
                <button
                  key={tab.key}
                  type="button"
                  onClick={() => onSelectSort(tab.key)}
                  className="relative flex h-[30px] shrink-0 items-center justify-center text-[14px] cursor-pointer transition-colors"
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

          <div className="flex shrink-0 items-center gap-0.5">
            <button
              type="button"
              onClick={() => {
                setFocusedParentCatId(activeCategory?.parentId ?? activeCategory?.id ?? null);
                setCatDrawerOpen((v) => !v);
              }}
              className="lindo-interactive flex h-[28px] items-center gap-1 rounded-md px-2 text-[13px]"
              style={{
                color:
                  catDrawerOpen || selectedCategoryId
                    ? "var(--active-chip-text)"
                    : "var(--ink-secondary)",
                fontWeight: catDrawerOpen || selectedCategoryId ? 600 : 400,
              }}
            >
              <span className="max-w-[92px] truncate">
                {activeCategory
                  ? activeCategory.subLabel
                    ? `${parentCategories.find((p) => p.id === activeCategory.parentId)?.name ?? ""} · ${activeCategory.subLabel}`
                    : activeCategory.name
                  : "分类"}
              </span>
              {catDrawerOpen ? <CaretUp size={11.5} /> : <CaretDown size={11.5} />}
            </button>

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
              title={isCompact ? "切换舒适卡片视图" : "切换紧凑列表视图"}
              className="lindo-interactive flex h-7 w-7 items-center justify-center rounded-full"
              style={{ color: "var(--ink-secondary)" }}
            >
              {isCompact ? <Rows size={15} /> : <ListBullets size={15} />}
            </button>
          </div>
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
            {activeCategory && (
              <span
                className="inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-[11.5px] font-medium"
                style={{
                  backgroundColor: "var(--active-chip-bg)",
                  color: "var(--active-chip-text)",
                }}
              >
                <span
                  className="h-2 w-2 rounded-full"
                  style={{ backgroundColor: `#${activeCategory.colorHex.replace("#", "")}` }}
                />
                <span>{activeCategory.displayName}</span>
                <button
                  type="button"
                  onClick={() => onSelectCategory(null)}
                  className="ml-0.5 rounded-full p-0.5 hover:opacity-75 cursor-pointer"
                >
                  <X size={10.5} weight="bold" />
                </button>
              </span>
            )}
            {selectedTag && (
              <span
                className="inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-[11.5px] font-medium"
                style={{
                  backgroundColor: "var(--tonal-pill)",
                  color: "var(--ink-primary)",
                }}
              >
                <Tag size={11} />
                <span>{selectedTag}</span>
                <button
                  type="button"
                  onClick={() => onSelectTag(null)}
                  className="ml-0.5 rounded-full p-0.5 hover:opacity-75 cursor-pointer"
                >
                  <X size={10.5} weight="bold" />
                </button>
              </span>
            )}
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
                className="rounded-full px-2.5 py-1 text-[12px] font-medium transition-colors cursor-pointer"
                style={{
                  backgroundColor:
                    selectedCategoryId === null ? "var(--active-chip-bg)" : "var(--card-surface)",
                  color:
                    selectedCategoryId === null ? "var(--active-chip-text)" : "var(--ink-secondary)",
                }}
              >
                全部分类
              </button>
              {parentCategories.map((cat) => {
                const selected = effectiveParentId === cat.id;
                const vis = resolveCategoryVisual(cat.name, cat.colorHex);
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
                    className="inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[12px] font-medium transition-colors cursor-pointer"
                    style={{
                      backgroundColor: selected ? "var(--active-chip-bg)" : "var(--card-surface)",
                      color: selected ? "var(--active-chip-text)" : "var(--ink-primary)",
                    }}
                  >
                    <span className="h-2 w-2 rounded-full" style={{ backgroundColor: vis.dotColor }} />
                    {cat.readRestricted && <Lock size={10.5} style={{ color: "var(--ink-tertiary)" }} />}
                    <span>{cat.name}</span>
                  </button>
                );
              })}
            </div>

            {subCategories.length > 0 && effectiveParentId && (
              <div className="lindo-hairline-t no-scrollbar mt-2 flex items-center gap-2 overflow-x-auto pt-2">
                <button
                  type="button"
                  onClick={() => {
                    onSelectCategory(effectiveParentId);
                    setCatDrawerOpen(false);
                  }}
                  className="shrink-0 rounded-md px-2 py-0.5 text-[11.8px] font-medium cursor-pointer"
                  style={{
                    backgroundColor:
                      selectedCategoryId === effectiveParentId ? "var(--active-chip-bg)" : "transparent",
                    color:
                      selectedCategoryId === effectiveParentId
                        ? "var(--active-chip-text)"
                        : "var(--ink-secondary)",
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
                      className="inline-flex shrink-0 items-center gap-1 rounded-md px-2 py-0.5 text-[11.8px] font-medium transition-colors cursor-pointer"
                      style={{
                        backgroundColor: selected ? "var(--active-chip-bg)" : "transparent",
                        color: selected ? "var(--active-chip-text)" : "var(--ink-secondary)",
                      }}
                    >
                      {sub.readRestricted && <Lock size={10} />}
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
                "linear-gradient(to bottom, var(--card-surface) 0%, color-mix(in srgb, var(--card-surface) 82%, transparent) 55%, transparent 100%)",
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
              <span>{pendingNewCount} 条新动态</span>
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
            <div>
              {/* 置顶话题精简区（默认展示前 2 条，多于 2 条可展开，全部 0.5px 内缩细线） */}
              {pinnedTopics.length > 0 && (
                <div>
                  {visiblePinnedTopics.map((pinned) => {
                    const selected = selectedTopicId === pinned.id;
                    return (
                      <React.Fragment key={pinned.id}>
                        <button
                          type="button"
                          onClick={() => onSelectTopic(pinned)}
                          className="lindo-interactive flex w-full items-center gap-2 px-4 py-2.5 text-left"
                          style={{
                            backgroundColor: selected ? "var(--subtle-surface)" : "transparent",
                          }}
                        >
                          <span
                            className="inline-flex shrink-0 items-center gap-0.5 rounded px-1.5 py-[1px] text-[10.5px] font-bold"
                            style={{
                              backgroundColor: "var(--active-chip-bg)",
                              color: "var(--brand-gold)",
                            }}
                          >
                            <PushPin size={10.5} weight="fill" />
                            <span>置顶</span>
                          </span>
                          <span
                            className="flex-1 truncate text-[13.3px] font-medium"
                            style={{ color: "var(--ink-primary)" }}
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
                        <div className="lindo-divider-inset" />
                      </React.Fragment>
                    );
                  })}
                  {pinnedTopics.length > 2 && (
                    <>
                      <button
                        type="button"
                        onClick={() => setPinnedExpanded((v) => !v)}
                        className="lindo-interactive flex w-full items-center justify-center gap-1 py-1.5 text-[11.5px]"
                        style={{ color: "var(--ink-tertiary)" }}
                      >
                        <span>
                          {pinnedExpanded
                            ? "收起置顶话题"
                            : `展开其余 ${pinnedTopics.length - 2} 条置顶`}
                        </span>
                        {pinnedExpanded ? <CaretUp size={11.5} /> : <CaretDown size={11.5} />}
                      </button>
                      <div className="lindo-divider-inset" />
                    </>
                  )}
                </div>
              )}

              {/* 主话题流列表 */}
              {regularTopics.map((topic) => {
                const selected = topic.id === selectedTopicId;
                const confirmedFloor = readFloors[String(topic.id)] ?? topic.lastReadPostNumber ?? 0;
                const isUnread =
                  (topic.unseen || topic.unreadPosts > 0) && confirmedFloor < topic.highestPostNumber;
                const catVis = resolveCategoryVisual(
                  topic.categoryName || "",
                  topic.categoryColorHex
                );
                const authorName =
                  topic.originalPoster.name?.trim() || topic.originalPoster.username;

                return (
                  <React.Fragment key={topic.id}>
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
                      className={`lindo-interactive flex px-4 text-left ${
                        isCompact ? "items-center gap-2.5 py-2" : "items-start gap-3 py-3.5"
                      }`}
                      style={{
                        backgroundColor: selected ? "var(--subtle-surface)" : "transparent",
                      }}
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
                        /* 紧凑模式：左侧小头像 + 单行省略号标题 + 单行浓缩元信息，屏效密度大幅提升 */
                        <div className="min-w-0 flex-1">
                          <div className="flex items-center justify-between gap-2">
                            <div
                              className="flex min-w-0 flex-1 items-center gap-1 text-[14px] font-semibold leading-[1.36]"
                              style={{
                                color:
                                  confirmedFloor >= topic.highestPostNumber && !selected
                                    ? "color-mix(in srgb, var(--ink-primary) 80%, var(--ink-secondary))"
                                    : "var(--ink-primary)",
                              }}
                            >
                              {topic.closed && (
                                <Lock
                                  size={12}
                                  className="shrink-0"
                                  style={{ color: "var(--ink-tertiary)" }}
                                />
                              )}
                              <span className="truncate">{topic.title}</span>
                              {topic.hasAcceptedAnswer && (
                                <span title="已采纳最佳解答" className="shrink-0">
                                  <CheckCircle size={13} weight="fill" style={{ color: "#10B981" }} />
                                </span>
                              )}
                              {isUnread && (
                                <span
                                  className="ml-0.5 h-[5.5px] w-[5.5px] shrink-0 rounded-full"
                                  style={{ backgroundColor: "var(--brand-gold)" }}
                                />
                              )}
                            </div>

                            {confirmedFloor > 1 && (
                              <button
                                type="button"
                                title="点击直达上次阅读楼层"
                                onClick={(e) => {
                                  e.stopPropagation();
                                  onSelectTopic(topic, confirmedFloor);
                                }}
                                className="shrink-0 rounded px-1.5 py-[0.5px] text-[10px] font-medium tabular-nums hover:opacity-85 cursor-pointer"
                                style={{
                                  backgroundColor:
                                    "color-mix(in srgb, var(--brand-gold) 12%, transparent)",
                                  color: "var(--brand-gold)",
                                }}
                              >
                                #{confirmedFloor}
                              </button>
                            )}
                          </div>

                          <div
                            className="mt-1 flex items-center justify-between gap-2 text-[11.2px] tabular-nums leading-none"
                            style={{ color: "var(--ink-tertiary)" }}
                          >
                            <span className="flex min-w-0 items-center gap-1.5 truncate">
                              {topic.categoryName && (
                                <span
                                  className="inline-flex shrink-0 items-center gap-1 font-medium"
                                  style={{ color: "var(--ink-secondary)" }}
                                >
                                  <span
                                    className="h-[6px] w-[6px] rounded-full"
                                    style={{ backgroundColor: catVis.dotColor }}
                                  />
                                  <span>{topic.categoryName}</span>
                                </span>
                              )}
                              {topic.tags[0] && (
                                <span
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    onSelectTag(topic.tags[0]);
                                  }}
                                  className="shrink-0 hover:opacity-80"
                                  style={{ color: "var(--ink-secondary)" }}
                                >
                                  #{topic.tags[0]}
                                </span>
                              )}
                              <span className="truncate">
                                {(topic.categoryName || topic.tags[0]) ? "· " : ""}
                                {authorName} · {formatRelativeTime(topic.lastPostedAt)}
                              </span>
                            </span>

                            <span className="flex shrink-0 items-center gap-2">
                              {topic.likeCount > 0 && (
                                <span className="inline-flex items-center gap-0.5">
                                  <Heart size={11.5} />
                                  {formatCompactCount(topic.likeCount)}
                                </span>
                              )}
                              <span className="inline-flex items-center gap-0.5">
                                <ChatCircle size={11.5} />
                                {formatCompactCount(topic.replyCount)}
                              </span>
                              <span className="inline-flex items-center gap-0.5">
                                <Eye size={11.5} />
                                {formatCompactCount(topic.views)}
                              </span>
                            </span>
                          </div>
                        </div>
                      ) : (
                        /* 宽松模式：左侧 38px 头像 + 最多两行完整标题 + 独立分类标签行 + 作者统计行 */
                        <div className="min-w-0 flex-1">
                          <div
                            className="line-clamp-2 text-[15px] font-semibold leading-[1.46]"
                            style={{
                              color:
                                confirmedFloor >= topic.highestPostNumber && !selected
                                  ? "color-mix(in srgb, var(--ink-primary) 82%, var(--ink-secondary))"
                                  : "var(--ink-primary)",
                            }}
                          >
                            {topic.closed && (
                              <Lock
                                size={12.5}
                                className="mr-1 inline-block align-[-1.5px]"
                                style={{ color: "var(--ink-tertiary)" }}
                              />
                            )}
                            <span>{topic.title}</span>
                            {topic.hasAcceptedAnswer && (
                              <span title="已采纳最佳解答" className="ml-1 inline-block align-[-2px]">
                                <CheckCircle
                                  size={13.5}
                                  weight="fill"
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

                          {(topic.categoryName || topic.tags.length > 0 || confirmedFloor > 1) && (
                            <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
                              {topic.categoryName && (
                                <span
                                  className="inline-flex items-center gap-1 text-[11.5px] font-medium"
                                  style={{ color: "var(--ink-secondary)" }}
                                >
                                  <span
                                    className="h-[7px] w-[7px] rounded-full"
                                    style={{ backgroundColor: catVis.dotColor }}
                                  />
                                  <span>{topic.categoryName}</span>
                                </span>
                              )}
                              {topic.tags.slice(0, 3).map((tg) => (
                                <span
                                  key={tg}
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    onSelectTag(tg);
                                  }}
                                  className="inline-flex items-center gap-0.5 rounded px-1.5 py-[1px] text-[11px] font-medium hover:opacity-80"
                                  style={{
                                    backgroundColor: "var(--tonal-pill)",
                                    color: "var(--ink-secondary)",
                                  }}
                                >
                                  <span>#{tg}</span>
                                </span>
                              ))}
                              {confirmedFloor > 1 && (
                                <button
                                  type="button"
                                  title="点击直达上次阅读楼层"
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    onSelectTopic(topic, confirmedFloor);
                                  }}
                                  className="rounded px-1.5 py-[1px] text-[10.5px] font-medium tabular-nums hover:opacity-85 cursor-pointer"
                                  style={{
                                    backgroundColor:
                                      "color-mix(in srgb, var(--brand-gold) 12%, transparent)",
                                    color: "var(--brand-gold)",
                                  }}
                                >
                                  读至 #{confirmedFloor}
                                </button>
                              )}
                            </div>
                          )}

                          {currentSort === "follow" && topic.excerpt && (
                            <p
                              className="mt-1 line-clamp-2 text-[12.5px] leading-relaxed"
                              style={{ color: "var(--ink-secondary)" }}
                            >
                              {topic.excerpt}
                            </p>
                          )}

                          <div
                            className="mt-1.5 flex items-center justify-between gap-2 text-[11.5px] tabular-nums"
                            style={{ color: "var(--ink-tertiary)" }}
                          >
                            <span className="truncate">
                              {authorName} · {formatRelativeTime(topic.lastPostedAt)}
                            </span>
                            <span className="flex shrink-0 items-center gap-2.5">
                              {topic.likeCount > 0 && (
                                <span className="inline-flex items-center gap-0.5">
                                  <Heart size={12} />
                                  {formatCompactCount(topic.likeCount)}
                                </span>
                              )}
                              <span className="inline-flex items-center gap-0.5">
                                <ChatCircle size={12} />
                                {formatCompactCount(topic.replyCount)}
                              </span>
                              <span className="inline-flex items-center gap-0.5">
                                <Eye size={12} />
                                {formatCompactCount(topic.views)}
                              </span>
                            </span>
                          </div>
                        </div>
                      )}
                    </div>
                    <div className="lindo-divider-inset" />
                  </React.Fragment>
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
