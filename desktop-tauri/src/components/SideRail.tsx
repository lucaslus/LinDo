import React from "react";
import type { MainTab } from "../lib/types";
import { handleTitlebarMouseDown } from "../lib/api";
import { LinDoIcons } from "./LinDoIcons";

interface SideRailProps {
  currentTab: MainTab;
  isDark: boolean;
  unreadCount: number;
  hasUpdate?: boolean;
  isMacos: boolean;
  showFeedToggle?: boolean;
  feedCollapsed?: boolean;
  onSelectTab: (tab: MainTab) => void;
  onPublishClick: () => void;
  onToggleTheme: () => void;
  onToggleFeedCollapse?: () => void;
}

export const SideRail: React.FC<SideRailProps> = ({
  currentTab,
  isDark,
  unreadCount,
  hasUpdate = false,
  isMacos,
  showFeedToggle = false,
  feedCollapsed = false,
  onSelectTab,
  onPublishClick,
  onToggleTheme,
  onToggleFeedCollapse,
}) => {
  const [earHovered, setEarHovered] = React.useState(false);
  const hasOrganicEar = Boolean(showFeedToggle && onToggleFeedCollapse);

  const renderTab = (
    tab: MainTab,
    label: string,
    iconNode: (selected: boolean) => React.ReactNode,
    badge?: number,
    showDot?: boolean
  ) => {
    const selected = currentTab === tab;
    const isHomeMascot = tab === "HOME";
    const badgeText = badge && badge > 0 ? (badge > 99 ? "99+" : String(badge)) : null;

    return (
      <button
        key={tab}
        type="button"
        onClick={() => onSelectTab(tab)}
        className={`lindo-no-drag group relative flex ${
          isHomeMascot ? "h-[56px]" : "h-[52px]"
        } w-full flex-col items-center justify-center gap-[3px] cursor-pointer`}
      >
        {selected && (
          <span
            className="pointer-events-none absolute left-0 top-1/2 h-[22px] w-[3px] -translate-y-1/2 rounded-r-full"
            style={{
              backgroundColor: "var(--brand-gold)",
              boxShadow: "0 0 8px color-mix(in srgb, var(--brand-gold) 42%, transparent)",
            }}
          />
        )}
        <div
          className={`relative flex items-center justify-center ${
            isHomeMascot ? "h-[30px] w-[32px]" : "h-[24px] w-[28px]"
          }`}
        >
          <div
            className={`flex items-center justify-center transition-all duration-150 group-hover:scale-[1.06] ${
              isHomeMascot ? "h-[29px] w-[29px]" : "h-[22px] w-[22px]"
            }`}
            style={{
              color: selected ? "var(--brand-gold)" : "var(--ink-secondary)",
              opacity: isHomeMascot ? 1 : selected ? 1 : 0.82,
            }}
          >
            {iconNode(selected)}
          </div>
          {badgeText ? (
            <span
              className="absolute -top-[4px] -right-[8px] flex h-[15px] min-w-[15px] items-center justify-center rounded-full px-1 text-[9px] font-bold leading-none tabular-nums"
              style={{
                backgroundColor: "var(--brand-gold)",
                color: isDark ? "#101217" : "#FFFFFF",
              }}
            >
              {badgeText}
            </span>
          ) : showDot ? (
            <span
              className="absolute -top-[1px] -right-[2px] h-[7px] w-[7px] rounded-full"
              style={{ backgroundColor: "var(--brand-gold)" }}
            />
          ) : null}
        </div>
        <span
          className="text-[10.5px] leading-tight transition-colors duration-150"
          style={{
            fontWeight: selected ? 600 : 500,
            color: selected ? "var(--brand-gold)" : "var(--ink-secondary)",
            opacity: selected ? 1 : 0.86,
          }}
        >
          {label}
        </span>
      </button>
    );
  };

  return (
    <aside
      data-fixed-scale="1"
      className={`relative z-20 flex h-full shrink-0 flex-col items-center justify-between select-none ${
        hasOrganicEar ? "" : "lindo-hairline-r"
      }`}
      style={{
        width: isMacos ? 76 : 72,
        backgroundColor: "var(--nav-bar-surface)",
        paddingBottom: 14,
      }}
    >
      <div className="flex w-full flex-col items-center">
        {/* 仅顶部窗口控制栏留白区支持拖拽窗口，绝不拦截下方导航与截图拖选 */}
        <div
          onMouseDown={handleTitlebarMouseDown}
          className="w-full shrink-0"
          style={{ height: isMacos ? 38 : 14 }}
        />

        <div className="flex w-full flex-col items-center gap-[2px]">
          {renderTab("HOME", "首页", (selected) => (
            <img
              src="/brand/lindo-mark.svg"
              alt="首页"
              className="h-[29px] w-[29px] object-contain pointer-events-none"
              style={{ opacity: selected ? 1 : 0.85 }}
            />
          ))}

          {renderTab("DISCOVER", "发现", (selected) =>
            selected ? <LinDoIcons.CompassSelected size={19.5} /> : <LinDoIcons.Compass size={19.5} />
          )}

          {/* 发布新话题按钮（38px 正圆微描边，与上下 56×52 圆角方块导航形成清晰语义区分） */}
          <button
            type="button"
            onClick={onPublishClick}
            title="发布新话题"
            className="lindo-no-drag group flex w-full flex-col items-center py-[6px] cursor-pointer"
          >
            <div
              className="flex h-[38px] w-[38px] items-center justify-center rounded-full transition-all duration-150 group-hover:scale-[1.04] group-active:scale-[0.96]"
              style={{
                backgroundColor: "var(--tonal-pill)",
                color: "var(--brand-gold)",
                border: "0.5px solid var(--hairline)",
              }}
            >
              <LinDoIcons.Plus size={18.5} />
            </div>
            <span
              className="mt-[4px] text-[10.5px] font-medium leading-tight transition-colors group-hover:opacity-95"
              style={{ color: "var(--ink-secondary)" }}
            >
              发布
            </span>
          </button>

          {renderTab(
            "MESSAGES",
            "消息",
            (selected) =>
              selected ? <LinDoIcons.BellSelected size={19.5} /> : <LinDoIcons.Bell size={19.5} />,
            unreadCount
          )}

          {renderTab(
            "PROFILE",
            "我的",
            (selected) =>
              selected ? <LinDoIcons.UserSelected size={19.5} /> : <LinDoIcons.User size={19.5} />,
            undefined,
            hasUpdate
          )}

          {/* 明暗外观快速切换（上移至「我的」正下方，为底部留出与评论框平行的收折耳扣区） */}
          <div
            className="my-1.5 h-[0.5px] w-5 rounded-full"
            style={{ backgroundColor: "var(--hairline)" }}
          />
          <button
            type="button"
            onClick={onToggleTheme}
            title={isDark ? "切换为浅色外观" : "切换为深色外观"}
            className="lindo-no-drag lindo-interactive flex h-9 w-9 items-center justify-center rounded-full"
            style={{ color: "var(--ink-secondary)" }}
          >
            {isDark ? <LinDoIcons.Sun size={18} /> : <LinDoIcons.Moon size={18} />}
          </button>
        </div>
      </div>

      {/* 从右侧 Feed 栏边缘自然“生长”出来的流线型收折耳扣：
          下移至侧栏底部边缘，中心点距窗口底部恰好 27px（3px 底线 + 48px 耳高的一半 24px），
          与右侧阅读器底部「✎ 回复话题」输入框（py-2.5 10px + 34px 高的一半 17px = 27px）像素级水平平行！ */}
      {hasOrganicEar && (
        <div className="pointer-events-none absolute inset-y-0 right-0 flex w-[22px] flex-col">
          <div
            className="ml-auto w-[0.5px] flex-1"
            style={{ backgroundColor: "var(--hairline)" }}
          />
          <button
            type="button"
            onClick={onToggleFeedCollapse}
            onMouseEnter={() => setEarHovered(true)}
            onMouseLeave={() => setEarHovered(false)}
            title={feedCollapsed ? "展开话题流 (⌘\\)" : "收起话题流，整屏阅读 (⌘\\)"}
            aria-label={feedCollapsed ? "展开话题流" : "收起话题流"}
            className="lindo-no-drag pointer-events-auto relative h-[48px] w-[22px] shrink-0 cursor-pointer focus:outline-none"
          >
            <svg
              width="22"
              height="48"
              viewBox="0 0 22 48"
              className="block h-full w-full overflow-visible"
            >
              <defs>
                <linearGradient id="lindo-feed-ear-fill" x1="0" y1="0" x2="1" y2="0">
                  <stop
                    offset="0%"
                    stopColor={
                      earHovered
                        ? "color-mix(in srgb, var(--subtle-surface) 88%, var(--brand-gold) 14%)"
                        : feedCollapsed
                          ? "color-mix(in srgb, var(--subtle-surface) 62%, var(--canvas) 38%)"
                          : "color-mix(in srgb, var(--subtle-surface) 44%, var(--feed-surface) 56%)"
                    }
                  />
                  <stop
                    offset="100%"
                    stopColor={feedCollapsed ? "var(--canvas)" : "var(--feed-surface)"}
                  />
                </linearGradient>
                <linearGradient id="lindo-feed-ear-stroke" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="var(--hairline)" />
                  <stop
                    offset="22%"
                    stopColor={
                      earHovered
                        ? "color-mix(in srgb, var(--brand-gold) 62%, var(--hairline))"
                        : feedCollapsed
                          ? "color-mix(in srgb, var(--brand-gold) 40%, var(--hairline))"
                          : "color-mix(in srgb, var(--ink-tertiary) 34%, var(--hairline))"
                    }
                  />
                  <stop
                    offset="78%"
                    stopColor={
                      earHovered
                        ? "color-mix(in srgb, var(--brand-gold) 62%, var(--hairline))"
                        : feedCollapsed
                          ? "color-mix(in srgb, var(--brand-gold) 40%, var(--hairline))"
                          : "color-mix(in srgb, var(--ink-tertiary) 34%, var(--hairline))"
                    }
                  />
                  <stop offset="100%" stopColor="var(--hairline)" />
                </linearGradient>
              </defs>
              <path
                d="M 22.5 0 L 21.75 0 C 21.75 7, 17.2 9.6, 10 12.4 C 4.8 14.5, 3.2 17.4, 3.2 24 C 3.2 30.6, 4.8 33.5, 10 35.6 C 17.2 38.4, 21.75 41, 21.75 48 L 22.5 48 Z"
                fill="url(#lindo-feed-ear-fill)"
              />
              <path
                d="M 21.75 0 C 21.75 7, 17.2 9.6, 10 12.4 C 4.8 14.5, 3.2 17.4, 3.2 24 C 3.2 30.6, 4.8 33.5, 10 35.6 C 17.2 38.4, 21.75 41, 21.75 48"
                fill="none"
                stroke="url(#lindo-feed-ear-stroke)"
                strokeWidth="0.85"
              />
            </svg>
            <span
              className="pointer-events-none absolute inset-0 flex items-center pl-[7px] transition-transform duration-150 ease-out"
              style={{
                color:
                  earHovered || feedCollapsed
                    ? "var(--brand-gold)"
                    : "var(--ink-tertiary)",
                transform: earHovered
                  ? feedCollapsed
                    ? "translateX(1px)"
                    : "translateX(-1px)"
                  : "translateX(0)",
              }}
            >
              {feedCollapsed ? (
                <LinDoIcons.ChevronRight size={12} />
              ) : (
                <LinDoIcons.ChevronLeft size={12} />
              )}
            </span>
          </button>
          <div
            className="ml-auto h-[3px] w-[0.5px] shrink-0"
            style={{ backgroundColor: "var(--hairline)" }}
          />
        </div>
      )}
    </aside>
  );
};
