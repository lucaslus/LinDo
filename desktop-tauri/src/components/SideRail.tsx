import React from "react";
import {
  Compass,
  Bell,
  User,
  Plus,
  Sun,
  Moon,
} from "@phosphor-icons/react";
import type { MainTab } from "../lib/types";
import { handleTitlebarMouseDown } from "../lib/api";

interface SideRailProps {
  currentTab: MainTab;
  isDark: boolean;
  unreadCount: number;
  hasUpdate?: boolean;
  isMacos: boolean;
  onSelectTab: (tab: MainTab) => void;
  onPublishClick: () => void;
  onToggleTheme: () => void;
}

export const SideRail: React.FC<SideRailProps> = ({
  currentTab,
  isDark,
  unreadCount,
  hasUpdate = false,
  isMacos,
  onSelectTab,
  onPublishClick,
  onToggleTheme,
}) => {
  const renderTab = (
    tab: MainTab,
    label: string,
    iconNode: (selected: boolean) => React.ReactNode,
    badge?: number,
    showDot?: boolean
  ) => {
    const selected = currentTab === tab;
    const badgeText = badge && badge > 0 ? (badge > 99 ? "99+" : String(badge)) : null;

    return (
      <button
        key={tab}
        type="button"
        onClick={() => onSelectTab(tab)}
        className="lindo-no-drag group flex w-full flex-col items-center py-[9px] cursor-pointer"
      >
        <div className="relative flex h-[30px] w-[52px] items-center justify-center">
          <div
            className="flex h-full w-full items-center justify-center rounded-full transition-colors duration-150"
            style={{
              backgroundColor: selected ? "var(--active-chip-bg)" : "transparent",
            }}
          >
            <div
              className="flex items-center justify-center transition-opacity"
              style={{
                color: selected ? "var(--active-chip-text)" : "var(--ink-secondary)",
              }}
            >
              {iconNode(selected)}
            </div>
          </div>
          {badgeText ? (
            <span
              className="absolute -top-[3px] right-[2px] flex h-[15px] min-w-[15px] items-center justify-center rounded-full px-1 text-[9px] font-bold leading-none tabular-nums"
              style={{
                backgroundColor: "var(--brand-gold)",
                color: isDark ? "#101217" : "#FFFFFF",
              }}
            >
              {badgeText}
            </span>
          ) : showDot ? (
            <span
              className="absolute top-[1px] right-[8px] h-[7px] w-[7px] rounded-full"
              style={{ backgroundColor: "var(--brand-gold)" }}
            />
          ) : null}
        </div>
        <span
          className="mt-[3px] text-[11px] leading-tight transition-colors"
          style={{
            fontWeight: selected ? 600 : 500,
            color: selected ? "var(--brand-gold)" : "var(--ink-secondary)",
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
      className="lindo-hairline-r flex h-full shrink-0 flex-col items-center justify-between select-none"
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

        <div className="flex w-full flex-col items-center gap-0.5">
          {renderTab("HOME", "首页", (selected) => (
            <img
              src="/brand/lindo-mark.svg"
              alt="首页"
              className="h-[22px] w-[22px] object-contain pointer-events-none"
              style={{ opacity: selected ? 1 : 0.78 }}
            />
          ))}

          {renderTab("DISCOVER", "发现", (selected) => (
            <Compass size={20} weight={selected ? "fill" : "regular"} />
          ))}

          {/* 发布新话题按钮 */}
          <button
            type="button"
            onClick={onPublishClick}
            className="lindo-no-drag group flex w-full flex-col items-center py-[9px] cursor-pointer"
          >
            <div
              className="flex h-[30px] w-[52px] items-center justify-center rounded-full transition-transform duration-150 group-hover:scale-[1.03] group-active:scale-[0.97]"
              style={{
                backgroundColor: "var(--tonal-pill)",
                color: "var(--brand-gold)",
              }}
            >
              <Plus size={18} weight="bold" />
            </div>
            <span
              className="mt-[3px] text-[11px] font-medium leading-tight"
              style={{ color: "var(--ink-secondary)" }}
            >
              发布
            </span>
          </button>

          {renderTab(
            "MESSAGES",
            "消息",
            (selected) => <Bell size={20} weight={selected ? "fill" : "regular"} />,
            unreadCount
          )}

          {renderTab(
            "PROFILE",
            "我的",
            (selected) => <User size={20} weight={selected ? "fill" : "regular"} />,
            undefined,
            hasUpdate
          )}
        </div>
      </div>

      {/* 底部明暗外观快速切换 */}
      <button
        type="button"
        onClick={onToggleTheme}
        title={isDark ? "切换为浅色外观" : "切换为深色外观"}
        className="lindo-no-drag lindo-interactive flex h-9 w-9 items-center justify-center rounded-full"
        style={{ color: "var(--ink-secondary)" }}
      >
        {isDark ? <Sun size={18} weight="regular" /> : <Moon size={18} weight="regular" />}
      </button>
    </aside>
  );
};
