import React, { useEffect, useMemo, useState } from "react";
import {
  Fire,
  ArrowRight,
  ArrowLeft,
  ArrowClockwise,
  Lock,
  Check,
  EnvelopeSimple,
  Bell,
  PaperPlaneTilt,
  SignOut,
  Plus,
  MagnifyingGlass,
  X,
  BookmarkSimple,
  Palette,
  Sun,
  Moon,
  Desktop,
  TextAa,
  Trash,
  ShieldCheck,
  Gift,
  Coins,
  Globe,
  User,
  ClockCounterClockwise,
  FileText,
  CalendarBlank,
  Trophy,
  Users,
  Sparkle,
  Info,
  CaretRight,
  CaretDown,
  PencilSimple,
  Rows,
  ListBullets,
  BookOpen,
  Cloud,
  Heart,
  ChatCircle,
  RocketLaunch,
  At,
  UserPlus,
  ArrowBendUpLeft,
  Quotes,
  ThumbsUp,
  Smiley,
  MagnifyingGlassPlus,
  MagnifyingGlassMinus,
  ArrowSquareOut,
  LinkSimple,
  DownloadSimple,
  Copy,
  Archive,
  Warning,
} from "@phosphor-icons/react";
import { openUrl } from "@tauri-apps/plugin-opener";
import type { ExternalUrlAssessment } from "../lib/cooked";
import { useForumImageSrc } from "../lib/forumMedia";
import {
  buildDiagReport,
  clearDiagEntries,
  type DiagCategory,
  exportDiagLogToFile,
  getDiagEntries,
  subscribeDiagEntries,
} from "../lib/diagnostics";
import type {
  CategoryMeta,
  CloudDraftItem,
  ComposerLaunchRequest,
  ConnectTrustLevelReport,
  DesktopUpdateInfo,
  FlairInfo,
  FollowUserEntry,
  GrantedBadge,
  LocalDraftRecord,
  LocalHistoryItem,
  NotificationItem,
  SavedAccountEntry,
  TopicSummary,
  UpdateProgressPayload,
  UserAccountPreferencesSnapshot,
  UserPreferences,
  UserProfile,
} from "../lib/types";
import {
  type CommunitySearchHit,
  archivePrivateMessage,
  clearLocalTopicHistory,
  clearReadHistoryInRust,
  deleteBookmarkById,
  deleteCloudDraft,
  deleteLocalDraftInRust,
  fetchCloudDrafts,
  fetchConnectTrustLevelReport,
  fetchNotifications,
  fetchPrivateMessages,
  fetchReadHistoryTopics,
  fetchSiteBadges,
  fetchTopicFeed,
  fetchUserAccountPreferences,
  fetchUserActions,
  fetchUserBadges,
  fetchUserBookmarks,
  fetchUserCardDetail,
  fetchUserFollowList,
  getLocalTopicHistory,
  handleTitlebarMouseDown,
  importManualCredentialInRust,
  markAllNotificationsRead,
  markSingleNotificationRead,
  refreshCurrentUserSession,
  searchCommunity,
  sendPrivateMessage,
  SiteError,
  toggleFollowUser,
  updateUserProfileInfo,
} from "../lib/api";
import {
  badgeTierMeta,
  BRAND_THEMES,
  formatCompactCount,
  formatRelativeTime,
  getAvatarClassName,
  isHolographicTitle,
  resolveAvatarUrl,
  resolveBadgeArtwork,
  resolveCategoryVisual,
  resolveEmojiShortcodeUrl,
  resolveFlairVisual,
} from "../lib/palette";

function trustLevelLabel(level?: number): string {
  switch (level ?? 1) {
    case 0:
      return "Lv.0 新用户";
    case 1:
      return "Lv.1 基本用户";
    case 2:
      return "Lv.2 成员";
    case 3:
      return "Lv.3 活跃用户";
    case 4:
      return "Lv.4 领袖";
    default:
      return `Lv.${level}`;
  }
}

function notificationFallbackVerb(type: number): string {
  switch (type) {
    case 1:
    case 15:
    case 29:
    case 32:
      return "提及了你";
    case 2:
    case 9:
    case 17:
    case 37:
    case 801:
    case 802:
      return "回复了你";
    case 3:
    case 33:
      return "引用了你";
    case 5:
    case 19:
    case 25:
      return "赞同了你的帖子";
    case 6:
    case 7:
    case 16:
      return "发来站内私信";
    case 12:
      return "获得社区徽章";
    case 36:
    case 800:
      return "关注了你";
    case 38:
    case 40:
    case 43:
    case 803:
      return "为你的帖子助力";
    default:
      return "社区通知";
  }
}

function resolveNotificationCornerIcon(n: NotificationItem): React.ReactNode {
  const rx = (n.reactionIcon || "").toLowerCase();
  if (rx === "rocket" || [38, 40, 43, 803].includes(n.notificationType)) {
    return <RocketLaunch size={10.5} weight="fill" />;
  }
  if (["+1", "thumbsup", "clap", "tieba_087"].includes(rx)) {
    return <ThumbsUp size={10.5} weight="fill" />;
  }
  if (["fire", "flame"].includes(rx)) {
    return <Fire size={10.5} weight="fill" />;
  }
  if (["tada", "confetti_ball", "sparkles"].includes(rx)) {
    return <Sparkle size={10.5} weight="fill" />;
  }
  if (["laughing", "joy", "open_mouth", "hugs", "distorted_face"].includes(rx)) {
    return <Smiley size={10.5} weight="fill" />;
  }
  if ([5, 19, 25].includes(n.notificationType)) {
    return <Heart size={10.5} weight="fill" />;
  }
  if (n.isConsolidatedReplies || [2, 9, 17, 37, 801, 802].includes(n.notificationType)) {
    return <ArrowBendUpLeft size={10.5} weight="bold" />;
  }
  if ([3, 33].includes(n.notificationType)) {
    return <Quotes size={10.5} weight="fill" />;
  }
  if ([1, 15, 29, 32].includes(n.notificationType)) {
    return <At size={10.5} weight="bold" />;
  }
  if ([6, 7, 16].includes(n.notificationType)) {
    return <EnvelopeSimple size={10.5} weight="fill" />;
  }
  if (n.notificationType === 12 || Boolean(n.badgeId && n.badgeId > 0)) {
    return <Trophy size={10.5} weight="fill" />;
  }
  if ([36, 800].includes(n.notificationType)) {
    return <UserPlus size={10.5} weight="fill" />;
  }
  return <Bell size={10.5} weight="fill" />;
}

/* ==================== 100% 几何同心双弧刷新图标（零偏心晃动，120Hz 顺滑旋转） ==================== */
export const SmoothRefreshIcon: React.FC<{
  spinning?: boolean;
  size?: number;
  className?: string;
}> = ({ spinning = false, size = 16, className = "" }) => {
  const px = Math.round(size);
  if (spinning) {
    // 100% 轴对称纯圆环轨道 + 90° 圆弧指示器：无任何外凸箭头尖角，几何包络与视觉质心恒定为 (12, 12)，144Hz 旋转 0.00px 浮动！
    return (
      <svg
        width={px}
        height={px}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth={2.1}
        strokeLinecap="round"
        className={`block shrink-0 lindo-spin-concentric ${className}`}
      >
        <circle cx="12" cy="12" r="8" strokeOpacity={0.22} />
        <path d="M12 4a8 8 0 0 1 8 8" />
      </svg>
    );
  }
  return (
    <svg
      width={px}
      height={px}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.95}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={`block shrink-0 ${className}`}
    >
      <path d="M20 12a8 8 0 1 1-2.34-5.66L20 8.5" />
      <path d="M20 4.5v4h-4" />
    </svg>
  );
};

export const MascotExpression: React.FC<{
  expression: "surprised" | "wink" | "sleepy" | "happy";
  sizePx?: number;
  className?: string;
}> = ({ expression, sizePx = 52, className = "" }) => (
  <span
    className={`relative inline-block shrink-0 select-none pointer-events-none ${className}`}
    style={{ width: sizePx, height: sizePx }}
  >
    <img
      src="/brand/lindo-mark.svg"
      alt=""
      className="h-full w-full object-contain"
    />
    <img
      src={`/brand/expressions/${expression}.svg`}
      alt=""
      className="absolute inset-0 h-full w-full object-contain"
    />
  </span>
);

export const StatusEmojiIcon: React.FC<{
  emoji?: string | null;
  size?: number;
  className?: string;
}> = ({ emoji, size = 14, className = "" }) => {
  const [imgFailed, setImgFailed] = useState(false);
  useEffect(() => {
    setImgFailed(false);
  }, [emoji]);

  if (!emoji) return null;
  const shortcodeUrl = resolveEmojiShortcodeUrl(emoji);
  if (!shortcodeUrl) {
    return (
      <span
        className={`inline-flex shrink-0 items-center leading-none ${className}`}
        style={{ fontSize: size }}
      >
        {emoji}
      </span>
    );
  }
  if (imgFailed) {
    return null;
  }
  return (
    <img
      src={shortcodeUrl}
      alt=""
      decoding="async"
      onError={() => setImgFailed(true)}
      style={{ width: size, height: size }}
      className={`inline-block shrink-0 object-contain align-[-2px] ${className}`}
    />
  );
};

/* ==================== 通用官方徽章图标 / 头像 Flair 角标 / 徽章详情弹窗 ==================== */
export const OfficialBadgeIcon: React.FC<{
  badge: {
    id?: number;
    name?: string;
    badgeTypeId?: number;
    badge_type_id?: number;
    icon?: string | null;
    imageUrl?: string | null;
    image_url?: string | null;
  };
  size?: number;
  title?: string;
  className?: string;
  onClick?: (e: React.MouseEvent) => void;
}> = ({ badge, size = 14, title, className = "", onClick }) => {
  const [imgFailed, setImgFailed] = useState(false);
  const typeId = badge.badgeTypeId ?? badge.badge_type_id ?? 3;
  const tier = badgeTierMeta(typeId);
  const art = resolveBadgeArtwork({
    id: badge.id,
    name: badge.name,
    icon: badge.icon,
    imageUrl: badge.imageUrl ?? badge.image_url,
  });
  const customBadgeSrc = useForumImageSrc(art.isCustomImage ? art.url : null);

  const content =
    art.isCustomImage && !imgFailed ? (
      <img
        src={customBadgeSrc}
        alt={badge.name || ""}
        onError={() => setImgFailed(true)}
        style={{ width: size, height: size }}
        className="shrink-0 object-contain"
      />
    ) : (
      <span
        aria-label={badge.name || ""}
        className="inline-block shrink-0"
        style={{
          width: size,
          height: size,
          backgroundColor: tier.color,
          WebkitMaskImage: `url("${art.isCustomImage ? "/brand/badges/certificate.svg" : art.url}")`,
          maskImage: `url("${art.isCustomImage ? "/brand/badges/certificate.svg" : art.url}")`,
          WebkitMaskSize: "contain",
          maskSize: "contain",
          WebkitMaskRepeat: "no-repeat",
          maskRepeat: "no-repeat",
          WebkitMaskPosition: "center",
          maskPosition: "center",
        }}
      />
    );

  if (onClick) {
    return (
      <button
        type="button"
        title={title ?? badge.name}
        onClick={onClick}
        className={`inline-flex shrink-0 items-center justify-center rounded-full transition-transform hover:scale-110 cursor-pointer ${className}`}
      >
        {content}
      </button>
    );
  }

  return (
    <span title={title ?? badge.name} className={`inline-flex shrink-0 items-center ${className}`}>
      {content}
    </span>
  );
};

export const AvatarWithFlair: React.FC<{
  username: string;
  avatarTemplate?: string | null;
  animatedAvatar?: string | null;
  primaryGroupName?: string | null;
  flair?: FlairInfo | null;
  sizePx?: number;
  className?: string;
}> = ({
  username,
  avatarTemplate,
  animatedAvatar,
  primaryGroupName,
  flair,
  sizePx = 36,
  className = "",
}) => {
  const flairVis = sizePx >= 28 ? resolveFlairVisual(flair) : null;
  // Unify <=48px CSS avatars onto Discourse's 96px bucket (~3KB, 2.6x Retina) so Feed, Notifications, and Topic floors hit 100% RAM cache!
  const targetBucket = sizePx <= 48 ? 96 : sizePx <= 96 ? 144 : 288;
  const staticAvatarSrc = resolveAvatarUrl(avatarTemplate, targetBucket);
  const rawAnimUrl =
    animatedAvatar && animatedAvatar !== avatarTemplate
      ? resolveAvatarUrl(animatedAvatar, targetBucket)
      : null;
  const animatedSrc = useForumImageSrc(rawAnimUrl, "");
  const flairImgSrc = useForumImageSrc(flairVis?.isImage ? flairVis.url : null);
  const badgeSize = sizePx >= 50 ? 21 : sizePx >= 38 ? 17 : 15;
  const innerSize = flairVis?.isImage
    ? flairVis.bg
      ? Math.round(badgeSize * 0.76)
      : badgeSize
    : flairVis?.bg
      ? Math.round(badgeSize * 0.66)
      : Math.round(badgeSize * 0.8);

  const shapeAndGlow = getAvatarClassName("h-full w-full", username, primaryGroupName, avatarTemplate);

  return (
    <div
      className={`relative inline-flex shrink-0 ${className}`}
      style={{ width: sizePx, height: sizePx }}
    >
      {/* Base layer: instant ~3KB static edge CDN avatar (never waits for multi-MB GIF download!) */}
      <img
        src={staticAvatarSrc}
        alt={username}
        decoding="async"
        className={shapeAndGlow}
        style={{ backgroundColor: "var(--subtle-surface)" }}
      />
      {/* Overlay layer: seamlessly mounts animated GIF/WebP once bridged & ready */}
      {animatedSrc && animatedSrc !== staticAvatarSrc && (
        <img
          src={animatedSrc}
          alt=""
          decoding="async"
          className={`absolute inset-0 ${shapeAndGlow}`}
        />
      )}
      {flairVis && (
        <span
          title={flairVis.name || primaryGroupName || undefined}
          className="pointer-events-none absolute -right-[3px] -bottom-[2px] flex items-center justify-center rounded-full"
          style={{
            width: badgeSize,
            height: badgeSize,
            backgroundColor: flairVis.bg || "transparent",
          }}
        >
          {flairVis.isImage ? (
            <img
              src={flairImgSrc}
              alt={flairVis.name || ""}
              style={{ width: innerSize, height: innerSize }}
              className="object-contain"
            />
          ) : (
            <span
              className="inline-block"
              style={{
                width: innerSize,
                height: innerSize,
                backgroundColor: flairVis.fg || "var(--ink-primary)",
                WebkitMaskImage: `url("${flairVis.url}")`,
                maskImage: `url("${flairVis.url}")`,
                WebkitMaskSize: "contain",
                maskSize: "contain",
                WebkitMaskRepeat: "no-repeat",
                maskRepeat: "no-repeat",
                WebkitMaskPosition: "center",
                maskPosition: "center",
              }}
            />
          )}
        </span>
      )}
    </div>
  );
};

export const BadgeDetailModal: React.FC<{
  badge: GrantedBadge;
  onClose: () => void;
}> = ({ badge, onClose }) => {
  const tier = badgeTierMeta(badge.badgeTypeId);
  const descHtml = badge.longDescription || badge.description || "由 LINUX DO 社区颁发的荣誉徽章。";

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/62 p-4"
      onClick={onClose}
    >
      <div
        className="w-[380px] max-w-full rounded-2xl p-5 shadow-2xl"
        style={{
          backgroundColor: "var(--card-surface)",
          border: "0.5px solid var(--hairline)",
        }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between">
          <span
            className="inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-[11.5px] font-semibold"
            style={{
              backgroundColor: `${tier.color}1C`,
              color: tier.color,
            }}
          >
            <span className="h-2 w-2 rounded-full" style={{ backgroundColor: tier.color }} />
            <span>{tier.label}</span>
          </span>
          <button
            type="button"
            onClick={onClose}
            className="lindo-interactive flex h-7 w-7 items-center justify-center rounded-full"
            style={{ color: "var(--ink-secondary)" }}
          >
            <X size={15} />
          </button>
        </div>

        <div className="mt-4 flex flex-col items-center text-center">
          <div
            className="flex h-16 w-16 items-center justify-center rounded-2xl"
            style={{
              backgroundColor: `${tier.color}16`,
              border: `0.5px solid ${tier.color}40`,
            }}
          >
            <OfficialBadgeIcon badge={badge} size={34} />
          </div>

          <h3 className="mt-3 text-[17px] font-bold" style={{ color: "var(--ink-primary)" }}>
            {badge.name}
          </h3>

          <div
            className="lindo-cooked mt-2 text-[13px] leading-relaxed"
            style={{ color: "var(--ink-secondary)" }}
            onClick={(e) => {
              const anchor = (e.target as HTMLElement).closest("a");
              if (anchor?.href) {
                e.preventDefault();
                openUrl(anchor.href);
              }
            }}
            dangerouslySetInnerHTML={{ __html: descHtml }}
          />

          <div
            className="mt-4 flex flex-wrap items-center justify-center gap-2 text-[11.5px]"
            style={{ color: "var(--ink-tertiary)" }}
          >
            {Boolean(badge.grantCount && badge.grantCount > 0) && (
              <span
                className="rounded-full px-2.5 py-0.5 tabular-nums"
                style={{ backgroundColor: "var(--subtle-surface)" }}
              >
                共 {formatCompactCount(badge.grantCount)} 人获得
              </span>
            )}
            {badge.isFirstTimeGrant && (
              <span
                className="rounded-full px-2.5 py-0.5 font-medium"
                style={{
                  backgroundColor: "var(--active-chip-bg)",
                  color: "var(--brand-gold)",
                }}
              >
                首次于此楼解锁
              </span>
            )}
            {badge.count && badge.count > 1 && (
              <span
                className="rounded-full px-2.5 py-0.5 font-semibold tabular-nums"
                style={{ backgroundColor: "var(--subtle-surface)", color: tier.color }}
              >
                已获 ×{badge.count}
              </span>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};

const FONT_SCALE_PRESETS = [
  { label: "最小", scale: 0.85 },
  { label: "小", scale: 0.92 },
  { label: "标准", scale: 1.0 },
  { label: "大", scale: 1.1 },
  { label: "最大", scale: 1.2 },
];

function nearestFontPreset(scale: number) {
  return FONT_SCALE_PRESETS.reduce((best, cur) =>
    Math.abs(cur.scale - scale) < Math.abs(best.scale - scale) ? cur : best
  );
}

/* ==================== 0. 欢迎首屏 (LinDoWelcomeScreen 1:1 对齐) ==================== */
export const WelcomeLoginScreen: React.FC<{
  isDark: boolean;
  isMacos?: boolean;
  showHeader?: boolean;
  statusMessage?: string | null;
  hasSavedAccounts?: boolean;
  onPrimaryWebLogin: () => void;
  onBrowserOneClickAuth: () => void;
  onImportAuthCallback?: (raw: string) => Promise<boolean>;
  onOpenSavedAccounts?: () => void;
  onGuestBrowse?: () => void;
  onToggleDark?: () => void;
}> = ({
  isDark,
  isMacos = false,
  showHeader = true,
  statusMessage,
  hasSavedAccounts = false,
  onPrimaryWebLogin,
  onBrowserOneClickAuth,
  onImportAuthCallback,
  onOpenSavedAccounts,
  onGuestBrowse,
  onToggleDark,
}) => {
  const [showPasteBox, setShowPasteBox] = useState(false);
  const [pasteVal, setPasteVal] = useState("");
  const [pasteBusy, setPasteBusy] = useState(false);
  const [pasteErr, setPasteErr] = useState<string | null>(null);
  const isWaitingBrowserCallback = Boolean(
    statusMessage && statusMessage.includes("浏览器")
  );
  return (
    <div
      className="flex h-full w-full flex-col items-center justify-between select-none"
      style={{ backgroundColor: "var(--canvas)", color: "var(--ink-primary)" }}
    >
      {showHeader && (
        <header
          onMouseDown={handleTitlebarMouseDown}
          className={`flex h-12 w-full items-center justify-between pr-4 ${
            isMacos ? "pl-[80px]" : "pl-5"
          }`}
        >
          <div className="flex items-center gap-2">
            <img src="/brand/lindo-mark.svg" alt="Lindo" className="h-7 w-7 object-contain" />
            <span className="text-[19px] font-extrabold tracking-tight">
              <span>Lin</span>
              <span style={{ color: "var(--logo-do)" }}>do</span>
            </span>
            <span className="ml-1 text-[11.5px]" style={{ color: "var(--ink-tertiary)" }}>
              真诚 · 友善 · 团结 · 专业
            </span>
          </div>
          {onToggleDark && (
            <button
              type="button"
              onClick={onToggleDark}
              title={isDark ? "切换至浅色模式" : "切换至深色模式"}
              className="lindo-no-drag lindo-interactive flex h-9 w-9 items-center justify-center rounded-full"
              style={{ color: "var(--ink-secondary)" }}
            >
              {isDark ? <Sun size={19} /> : <Moon size={19} />}
            </button>
          )}
        </header>
      )}

      <div className="flex w-full max-w-[440px] flex-1 flex-col justify-between px-6 py-5">
        <div className="flex flex-1 flex-col items-center justify-center py-4 text-center">
          <img
            src="/brand/lingdu-welcome.svg"
            alt="零度娘向你挥手"
            className="h-36 w-36 object-contain"
          />
          <h1
            className="mt-4 text-[24px] font-semibold tracking-tight"
            style={{ color: "var(--ink-primary)" }}
          >
            登录 Lindo
          </h1>
          {statusMessage && (
            <p className="mt-3 text-[13px] leading-5" style={{ color: "var(--ink-secondary)" }}>
              {statusMessage}
            </p>
          )}
        </div>

        <div className="w-full space-y-2.5">
          <button
            type="button"
            onClick={onPrimaryWebLogin}
            className="flex min-h-[52px] w-full items-center justify-center gap-2.5 rounded-2xl px-5 py-3.5 text-[15px] font-bold transition-opacity hover:opacity-92 cursor-pointer"
            style={{
              backgroundColor: "var(--publish-btn-bg)",
              color: "var(--publish-btn-fg)",
            }}
          >
            <span>使用 LINUX DO 登录</span>
            <ArrowRight size={18} weight="bold" />
          </button>

          <button
            type="button"
            onClick={onBrowserOneClickAuth}
            className="lindo-interactive flex min-h-[50px] w-full items-center justify-center gap-2 rounded-2xl px-5 py-3 text-[14.5px] font-medium"
            style={{
              border: "0.5px solid var(--hairline)",
              color: "var(--ink-primary)",
            }}
          >
            <Globe size={17} style={{ color: "var(--ink-secondary)" }} />
            <span>浏览器授权（推荐 · 支持 Passkey）</span>
          </button>

          {onImportAuthCallback && (isWaitingBrowserCallback || showPasteBox) && (
            <div className="pt-0.5">
              {!showPasteBox ? (
                <button
                  type="button"
                  onClick={() => setShowPasteBox(true)}
                  className="mx-auto block py-1 text-[12px] hover:underline cursor-pointer"
                  style={{ color: "var(--ink-tertiary)" }}
                >
                  浏览器未自动跳回？粘贴回调链接或 _t 完成登录
                </button>
              ) : (
                <div
                  className="space-y-2 rounded-xl p-3"
                  style={{
                    backgroundColor: "var(--subtle-surface)",
                    border: "0.5px solid var(--hairline)",
                  }}
                >
                  <div className="flex items-center gap-2">
                    <input
                      type="text"
                      value={pasteVal}
                      onChange={(e) => {
                        setPasteVal(e.target.value);
                        if (pasteErr) setPasteErr(null);
                      }}
                      placeholder="粘贴 discourse://auth_redirect?... 或 _t Cookie"
                      className="h-8 flex-1 rounded-lg bg-transparent px-2.5 text-[12px] outline-none"
                      style={{
                        border: "0.5px solid var(--hairline)",
                        color: "var(--ink-primary)",
                      }}
                    />
                    <button
                      type="button"
                      disabled={pasteBusy}
                      onClick={async () => {
                        if (!pasteVal.trim()) return;
                        setPasteBusy(true);
                        setPasteErr(null);
                        try {
                          const ok = await onImportAuthCallback(pasteVal.trim());
                          if (!ok) setPasteErr("未能解析凭证，请使用最新打开的授权页");
                        } catch (e: any) {
                          setPasteErr(e?.message || "凭证导入失败");
                        } finally {
                          setPasteBusy(false);
                        }
                      }}
                      className="shrink-0 rounded-lg px-3 py-1.5 text-[12px] font-semibold cursor-pointer disabled:opacity-50"
                      style={{
                        backgroundColor: "var(--publish-btn-bg)",
                        color: "var(--publish-btn-fg)",
                      }}
                    >
                      {pasteBusy ? "验证中" : "完成"}
                    </button>
                  </div>
                  {pasteErr && (
                    <p className="text-[11.5px]" style={{ color: "var(--error-fg)" }}>
                      {pasteErr}
                    </p>
                  )}
                </div>
              )}
            </div>
          )}

          {hasSavedAccounts && onOpenSavedAccounts && (
            <button
              type="button"
              onClick={onOpenSavedAccounts}
              className="lindo-interactive flex min-h-[42px] w-full items-center justify-center rounded-xl text-[14px] font-medium"
              style={{ color: "var(--ink-secondary)" }}
            >
              切换账号
            </button>
          )}

          {onGuestBrowse && (
            <button
              type="button"
              onClick={onGuestBrowse}
              className="lindo-interactive flex min-h-[44px] w-full items-center justify-center rounded-xl text-[14px]"
              style={{ color: "var(--ink-secondary)" }}
            >
              先逛逛
            </button>
          )}

          {showHeader && (
            <div
              className="pt-2 text-center text-[10.5px] tracking-wider"
              style={{ color: "var(--ink-tertiary)" }}
            >
              LINUX DO 第三方客户端
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

/* ==================== 1. 发现页 (CommunityDiscoverScreen 1:1 对齐) ==================== */
export const DiscoverView: React.FC<{
  categories: CategoryMeta[];
  isAuthorized: boolean;
  onSelectTopic: (topic: TopicSummary) => void;
  onSelectCategoryInHome: (categoryId: number) => void;
  onSelectTagInHome: (tag: string) => void;
  onOpenGlobalSearch: () => void;
  onOpenPortalUrl: (
    url: string,
    requiresAuth: boolean,
    guestTitle: string,
    guestSubtitle: string
  ) => void;
}> = ({
  categories,
  isAuthorized,
  onSelectTopic,
  onSelectCategoryInHome,
  onSelectTagInHome,
  onOpenGlobalSearch,
  onOpenPortalUrl,
}) => {
  const [hotPeriod, setHotPeriod] = useState<"daily" | "weekly">("daily");
  const [hotTopics, setHotTopics] = useState<TopicSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [expandedParentId, setExpandedParentId] = useState<number | null>(null);

  const loadHot = (period: "daily" | "weekly" = hotPeriod) => {
    setLoading(true);
    fetchTopicFeed({ sort: "top", topPeriod: period, background: true })
      .then((res) => setHotTopics(res.topics.filter((t) => !t.pinned).slice(0, 10)))
      .catch(() => {})
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    loadHot(hotPeriod);
  }, [hotPeriod]);

  const popularTags = [
    "人工智能",
    "OpenAI",
    "Claude",
    "Docker",
    "Linux",
    "开源项目",
    "Cursor",
    "DeepSeek",
    "VPS",
    "自建服务",
    "福利",
    "抽奖",
  ];

  const ecoPortals = [
    {
      title: "Connect 中心",
      sub: "保级 / 邮箱 / DeepLX",
      url: "https://connect.linux.do/",
      color: "#3B82F6",
      requiresAuth: true,
      guestTitle: "登录后访问 Connect 中心",
      guestSub: "查看信任等级进度、保级指标与专属社区权益",
      icon: <ShieldCheck size={19} weight="duotone" />,
    },
    {
      title: "全站徽章墙",
      sub: "荣誉徽章与头衔规则",
      url: "https://linux.do/badges",
      color: "#EAB308",
      requiresAuth: false,
      guestTitle: "",
      guestSub: "",
      icon: <Trophy size={19} weight="duotone" />,
    },
    {
      title: "Credit 积分",
      sub: "积分流转与服务认证",
      url: "https://credit.linux.do/",
      color: "#F59E0B",
      requiresAuth: true,
      guestTitle: "登录后访问 Credit 积分",
      guestSub: "积分流转与服务认证需通过 LINUX DO 账号授权",
      icon: <Coins size={19} weight="duotone" />,
    },
    {
      title: "CDK 分发",
      sub: "社区福利领取与发布",
      url: "https://cdk.linux.do/",
      color: "#EC4899",
      requiresAuth: true,
      guestTitle: "登录后访问 CDK 分发",
      guestSub: "领取或发布社区福利需登录 LINUX DO 账号",
      icon: <Gift size={19} weight="duotone" />,
    },
    {
      title: "LD Hub",
      sub: "生态 AI 与聚合中心",
      url: "https://hub.linux.do/",
      color: "#8B5CF6",
      requiresAuth: true,
      guestTitle: "登录后访问 LD Hub",
      guestSub: "访问生态 AI 与聚合中心需登录 LINUX DO 账号",
      icon: <Sparkle size={19} weight="duotone" />,
    },
    {
      title: "社区 Wiki",
      sub: "共建指南与文档库",
      url: "https://wiki.linux.do/",
      color: "#10B981",
      requiresAuth: false,
      guestTitle: "",
      guestSub: "",
      icon: <BookOpen size={19} weight="duotone" />,
    },
    {
      title: "IDC Flare",
      sub: "官方云与主机生态站",
      url: "https://idcflare.com/",
      color: "#06B6D4",
      requiresAuth: false,
      guestTitle: "",
      guestSub: "",
      icon: <Cloud size={19} weight="duotone" />,
    },
    {
      title: "Lottery 抽奖",
      sub: "官方楼层开奖工具",
      url: "https://lottery.linux.do/",
      color: "#F97316",
      requiresAuth: true,
      guestTitle: "登录后访问 Lottery 抽奖",
      guestSub: "使用官方楼层开奖与抽奖工具需登录 LINUX DO 账号",
      icon: <Trophy size={19} weight="duotone" />,
    },
  ];

  const parentCats = useMemo(() => categories.filter((c) => !c.parentId), [categories]);

  return (
    <div
      className="flex h-full w-full flex-col"
      style={{ backgroundColor: "var(--card-surface)" }}
    >
      <header
        onMouseDown={handleTitlebarMouseDown}
        className="lindo-hairline-b flex h-[50px] shrink-0 items-center justify-between px-5"
      >
        <h1
          className="text-[19px] font-bold tracking-tight"
          style={{ color: "var(--ink-primary)" }}
        >
          发现
        </h1>
        <button
          type="button"
          onClick={onOpenGlobalSearch}
          title="全站搜索"
          className="lindo-no-drag lindo-interactive flex h-8 w-8 items-center justify-center rounded-full"
          style={{ color: "var(--ink-primary)" }}
        >
          <MagnifyingGlass size={18} />
        </button>
      </header>

      <div className="lindo-scroll flex-1 min-h-0 px-5 py-5 space-y-6 max-w-5xl">
        {/* 1. 社区版块 */}
        <section>
          <div
            className="mb-2.5 text-[13px] font-semibold"
            style={{ color: "var(--ink-secondary)" }}
          >
            社区版块
          </div>
          <div className="grid grid-cols-2 gap-2.5 sm:grid-cols-3">
            {parentCats.map((cat) => {
              const vis = resolveCategoryVisual(cat.name, cat.colorHex);
              const subs = categories.filter((s) => s.parentId === cat.id);
              const isExpanded = expandedParentId === cat.id;
              return (
                <div
                  key={cat.id}
                  className="rounded-xl overflow-hidden"
                  style={{
                    backgroundColor: "var(--subtle-surface)",
                    border: "0.5px solid var(--hairline)",
                  }}
                >
                  <div className="flex items-center justify-between px-3.5 py-3">
                    <button
                      type="button"
                      onClick={() => onSelectCategoryInHome(cat.id)}
                      className="flex min-w-0 flex-1 items-center gap-2 text-left cursor-pointer"
                    >
                      <span
                        className="h-2.5 w-2.5 shrink-0 rounded-full"
                        style={{ backgroundColor: vis.dotColor }}
                      />
                      <span
                        className="truncate text-[13.8px] font-semibold"
                        style={{ color: "var(--ink-primary)" }}
                      >
                        {cat.name}
                      </span>
                      {cat.readRestricted && (
                        <Lock size={11.5} style={{ color: "var(--ink-tertiary)" }} />
                      )}
                    </button>
                    {subs.length > 0 && (
                      <button
                        type="button"
                        onClick={() => setExpandedParentId(isExpanded ? null : cat.id)}
                        className="lindo-interactive ml-1 rounded-md px-1.5 py-0.5 text-[11px]"
                        style={{ color: "var(--ink-tertiary)" }}
                      >
                        {subs.length} 子项 {isExpanded ? "▴" : "▾"}
                      </button>
                    )}
                  </div>
                  {isExpanded && subs.length > 0 && (
                    <div
                      className="lindo-hairline-t flex flex-wrap gap-1.5 px-3 py-2"
                      style={{ backgroundColor: "var(--card-surface)" }}
                    >
                      {subs.map((sub) => (
                        <button
                          key={sub.id}
                          type="button"
                          onClick={() => onSelectCategoryInHome(sub.id)}
                          className="lindo-interactive inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-[11.5px]"
                          style={{
                            border: "0.5px solid var(--hairline)",
                            color: "var(--ink-secondary)",
                          }}
                        >
                          {sub.readRestricted && <Lock size={9.5} />}
                          <span>{sub.subLabel || sub.name}</span>
                        </button>
                      ))}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </section>

        {/* 2. 热门标签 */}
        <section>
          <div
            className="mb-2.5 text-[13px] font-semibold"
            style={{ color: "var(--ink-secondary)" }}
          >
            热门标签
          </div>
          <div className="flex flex-wrap gap-2">
            {popularTags.map((tg) => (
              <button
                key={tg}
                type="button"
                onClick={() => onSelectTagInHome(tg)}
                className="lindo-interactive rounded-full px-3 py-1 text-[12.5px] font-medium"
                style={{
                  backgroundColor: "var(--subtle-surface)",
                  border: "0.5px solid var(--hairline)",
                  color: "var(--ink-primary)",
                }}
              >
                # {tg}
              </button>
            ))}
          </div>
        </section>

        {/* 3. 热门话题榜（支持 今日 / 本周 切换） */}
        <section>
          <div className="mb-2.5 flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div
                className="flex items-center gap-1.5 text-[13px] font-semibold"
                style={{ color: "var(--ink-secondary)" }}
              >
                <Fire size={15} weight="fill" style={{ color: "#F59E0B" }} />
                <span>热门话题</span>
              </div>
              <div className="flex items-center gap-1">
                {[
                  { key: "daily", label: "今日" },
                  { key: "weekly", label: "本周" },
                ].map((p) => (
                  <button
                    key={p.key}
                    type="button"
                    onClick={() => setHotPeriod(p.key as any)}
                    className="rounded-full px-2.5 py-0.5 text-[11px] font-medium cursor-pointer"
                    style={{
                      backgroundColor:
                        hotPeriod === p.key ? "var(--active-chip-bg)" : "var(--subtle-surface)",
                      color:
                        hotPeriod === p.key ? "var(--active-chip-text)" : "var(--ink-secondary)",
                    }}
                  >
                    {p.label}
                  </button>
                ))}
              </div>
            </div>
            <button
              type="button"
              onClick={() => loadHot(hotPeriod)}
              className="lindo-interactive flex h-7 w-7 items-center justify-center rounded-full"
              style={{ color: "var(--ink-tertiary)" }}
              title="刷新热门"
            >
              <SmoothRefreshIcon spinning={loading} size={15} />
            </button>
          </div>
          <div
            className="divide-y rounded-2xl overflow-hidden"
            style={{
              backgroundColor: "var(--subtle-surface)",
              border: "0.5px solid var(--hairline)",
            }}
          >
            {loading ? (
              <div className="space-y-3 p-4">
                {Array.from({ length: 5 }).map((_, i) => (
                  <div key={i} className="lindo-shimmer h-5 rounded" />
                ))}
              </div>
            ) : (
              hotTopics.map((t, idx) => (
                <button
                  key={t.id}
                  type="button"
                  onClick={() => onSelectTopic(t)}
                  className="lindo-interactive flex w-full items-center gap-3 px-4 py-3 text-left"
                >
                  <span
                    className="w-5 shrink-0 text-center text-[13px] font-extrabold tabular-nums"
                    style={{
                      color: idx < 3 ? "var(--brand-gold)" : "var(--ink-tertiary)",
                    }}
                  >
                    {idx + 1}
                  </span>
                  <span
                    className="flex-1 truncate text-[13.8px] font-medium"
                    style={{ color: "var(--ink-primary)" }}
                  >
                    {t.title}
                  </span>
                  <span
                    className="shrink-0 text-[11.5px] tabular-nums"
                    style={{ color: "var(--ink-tertiary)" }}
                  >
                    {formatCompactCount(t.replyCount)} 回复 · {formatCompactCount(t.views)} 浏览
                  </span>
                </button>
              ))
            )}
          </div>
        </section>

        {/* 4. 官方生态 */}
        <section>
          <div
            className="mb-2.5 text-[13px] font-semibold"
            style={{ color: "var(--ink-secondary)" }}
          >
            官方生态
          </div>
          <div className="grid grid-cols-2 gap-2.5 sm:grid-cols-4">
            {ecoPortals.map((p) => (
              <button
                key={p.title}
                type="button"
                onClick={() =>
                  onOpenPortalUrl(p.url, p.requiresAuth, p.guestTitle, p.guestSub)
                }
                className="lindo-interactive flex flex-col justify-between rounded-2xl p-3.5 text-left"
                style={{
                  backgroundColor: "var(--subtle-surface)",
                  border: "0.5px solid var(--hairline)",
                }}
              >
                <div className="mb-2 flex items-center justify-between">
                  <span
                    className="inline-flex rounded-xl p-2"
                    style={{
                      backgroundColor: `${p.color}1F`,
                      color: p.color,
                    }}
                  >
                    {p.icon}
                  </span>
                  <CaretRight size={13} style={{ color: "var(--ink-tertiary)" }} />
                </div>
                <div>
                  <div className="text-[13.5px] font-bold" style={{ color: "var(--ink-primary)" }}>
                    {p.title}
                  </div>
                  <div className="mt-0.5 text-[11.5px]" style={{ color: "var(--ink-secondary)" }}>
                    {p.sub}
                  </div>
                </div>
              </button>
            ))}
          </div>
        </section>
      </div>
    </div>
  );
};

/* ==================== 2. 消息中心 (CommunityMessagesScreen 1:1 对齐) ==================== */
export const MessagesView: React.FC<{
  isAuthorized: boolean;
  isDark?: boolean;
  currentUser: UserProfile | null;
  selectedTopicId?: number | null;
  pmRefreshNonce?: number;
  initialComposeRecipient?: string | null;
  onConsumeInitialRecipient?: () => void;
  onOpenNotificationTopic: (topicId: number, title: string, postNumber?: number) => void;
  onUnreadCleared: () => void;
  onDecrementUnread?: () => void;
  onInspectUser?: (username: string) => void;
  onInspectBadge?: (badge: GrantedBadge) => void;
  onOpenAuth: () => void;
  onOpenChat: () => void;
  onTriggerVerify?: () => void;
}> = ({
  isAuthorized,
  isDark = true,
  currentUser,
  selectedTopicId,
  pmRefreshNonce = 0,
  initialComposeRecipient,
  onConsumeInitialRecipient,
  onOpenNotificationTopic,
  onUnreadCleared,
  onDecrementUnread,
  onInspectUser,
  onInspectBadge,
  onOpenAuth,
  onOpenChat,
  onTriggerVerify,
}) => {
  const [subTab, setSubTab] = useState<"NOTIFICATIONS" | "PM" | "COMPOSE_PM">("NOTIFICATIONS");
  const [catFilter, setCatFilter] = useState<
    "ALL" | "UNREAD" | "REPLY" | "REACTION" | "MENTION" | "SYSTEM"
  >("ALL");
  const [pmBox, setPmBox] = useState<"inbox" | "sent" | "archive">("inbox");

  const [items, setItems] = useState<NotificationItem[]>([]);
  const [hasMoreNotifs, setHasMoreNotifs] = useState(false);
  const [loadingMoreNotifs, setLoadingMoreNotifs] = useState(false);
  const [pmList, setPmList] = useState<TopicSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [pmRecipient, setPmRecipient] = useState("");
  const [pmTitle, setPmTitle] = useState("");
  const [pmBody, setPmBody] = useState("");
  const [pmAttempted, setPmAttempted] = useState(false);
  const [pmSending, setPmSending] = useState(false);
  const [pmFeedback, setPmFeedback] = useState<string | null>(null);

  useEffect(() => {
    if (initialComposeRecipient) {
      setPmRecipient(initialComposeRecipient);
      setSubTab("COMPOSE_PM");
      onConsumeInitialRecipient?.();
    }
  }, [initialComposeRecipient, onConsumeInitialRecipient]);

  const loadNotifications = async () => {
    if (!isAuthorized) return;
    setLoading(true);
    setLoadError(null);
    try {
      const list = await fetchNotifications(0);
      setItems(list);
      setHasMoreNotifs(list.length >= 55);
    } catch (err: any) {
      setLoadError(err?.message || "通知加载失败，请重试");
    } finally {
      setLoading(false);
    }
  };

  const loadMoreNotifications = async () => {
    if (!isAuthorized || loadingMoreNotifs || !hasMoreNotifs) return;
    setLoadingMoreNotifs(true);
    try {
      const more = await fetchNotifications(items.length);
      setItems((prev) => {
        const seen = new Set(prev.map((x) => x.id));
        return [...prev, ...more.filter((x) => !seen.has(x.id))];
      });
      setHasMoreNotifs(more.length >= 55);
    } catch {
      setHasMoreNotifs(false);
    } finally {
      setLoadingMoreNotifs(false);
    }
  };

  const loadPms = async (box: "inbox" | "sent" | "archive" = pmBox) => {
    if (!isAuthorized || !currentUser?.username) return;
    setLoading(true);
    setLoadError(null);
    try {
      const list = await fetchPrivateMessages(currentUser.username, box, 0);
      setPmList(list);
    } catch (err: any) {
      setLoadError(err?.message || "私信加载失败，请重试");
    } finally {
      setLoading(false);
    }
  };

  const [pmActionNotice, setPmActionNotice] = useState<string | null>(null);

  useEffect(() => {
    if (!pmActionNotice) return;
    const timer = setTimeout(() => setPmActionNotice(null), 2200);
    return () => clearTimeout(timer);
  }, [pmActionNotice]);

  useEffect(() => {
    if (subTab === "PM") loadPms(pmBox);
    else if (subTab === "NOTIFICATIONS") loadNotifications();
  }, [isAuthorized, subTab, pmBox, currentUser?.username, pmRefreshNonce]);

  const filteredItems = items.filter((n) => {
    if (catFilter === "UNREAD") return !n.read;
    if (catFilter === "REPLY")
      return Boolean(n.isConsolidatedReplies) || [2, 9, 17, 37, 801, 802].includes(n.notificationType);
    if (catFilter === "MENTION") return [1, 3, 15, 29, 32, 33].includes(n.notificationType);
    if (catFilter === "REACTION") return [5, 19, 25, 38, 40, 43, 803].includes(n.notificationType);
    if (catFilter === "SYSTEM")
      return (
        !n.isConsolidatedReplies &&
        ![1, 2, 3, 5, 9, 15, 17, 19, 25, 29, 32, 33, 37, 38, 40, 43, 801, 802, 803].includes(
          n.notificationType
        )
      );
    return true;
  });

  return (
    <div
      className="lindo-hairline-r flex h-full w-full flex-col"
      style={{ backgroundColor: "var(--card-surface)" }}
    >
      <div
        onMouseDown={handleTitlebarMouseDown}
        className="lindo-hairline-b shrink-0 px-4 pt-3 pb-0"
      >
        <div className="flex h-9 items-center justify-between">
          <h2
            className="text-[19px] font-bold tracking-tight"
            style={{ color: "var(--ink-primary)" }}
          >
            消息
          </h2>
          <div className="lindo-no-drag flex items-center gap-1">
            {!isAuthorized ? (
              <button
                type="button"
                onClick={onOpenAuth}
                className="lindo-interactive rounded-lg px-2.5 py-1 text-[13.5px] font-semibold"
                style={{ color: "var(--active-chip-text)" }}
              >
                登录
              </button>
            ) : subTab === "NOTIFICATIONS" ? (
              <>
                <button
                  type="button"
                  onClick={async () => {
                    await markAllNotificationsRead().catch(() => {});
                    setItems((prev) => prev.map((x) => ({ ...x, read: true })));
                    onUnreadCleared();
                  }}
                  title="全部标为已读"
                  className="lindo-interactive flex items-center gap-1 rounded-lg px-2 py-1 text-[12px] font-medium"
                  style={{ color: "var(--ink-secondary)" }}
                >
                  <Check size={14} />
                  <span>全部标为已读</span>
                </button>
                <button
                  type="button"
                  onClick={loadNotifications}
                  title="刷新通知"
                  className="lindo-interactive flex h-7 w-7 items-center justify-center rounded-full"
                  style={{ color: "var(--ink-secondary)" }}
                >
                  <SmoothRefreshIcon spinning={loading} size={15} />
                </button>
              </>
            ) : (
              <button
                type="button"
                onClick={() => {
                  setPmAttempted(false);
                  setPmFeedback(null);
                  setSubTab(subTab === "COMPOSE_PM" ? "PM" : "COMPOSE_PM");
                }}
                className="lindo-interactive flex items-center gap-1 rounded-lg px-2.5 py-1 text-[12.5px] font-semibold"
                style={{ color: "var(--active-chip-text)" }}
              >
                {subTab === "COMPOSE_PM" ? (
                  <span>返回私信箱</span>
                ) : (
                  <>
                    <Plus size={13.5} />
                    <span>写私信</span>
                  </>
                )}
              </button>
            )}
          </div>
        </div>

        {isAuthorized && (
          <div className="lindo-no-drag mt-2 flex items-center gap-5 text-[13.5px]">
            {[
              { key: "NOTIFICATIONS", label: "通知" },
              { key: "PM", label: "私信" },
              { key: "CHAT", label: "聊天" },
            ].map((t) => {
              const active =
                t.key === "PM" ? subTab === "PM" || subTab === "COMPOSE_PM" : subTab === t.key;
              return (
                <button
                  key={t.key}
                  type="button"
                  onClick={() => {
                    if (t.key === "CHAT") onOpenChat();
                    else setSubTab(t.key as any);
                  }}
                  className="relative pb-2 cursor-pointer"
                  style={{
                    color: active ? "var(--ink-primary)" : "var(--ink-secondary)",
                    fontWeight: active ? 600 : 400,
                  }}
                >
                  <span>{t.label}</span>
                  {active && (
                    <span
                      className="absolute inset-x-0.5 bottom-0 h-[2.5px] rounded-full"
                      style={{ backgroundColor: "var(--tab-indicator)" }}
                    />
                  )}
                </button>
              );
            })}
          </div>
        )}
      </div>

      {/* 通知筛选胶囊条：no-scrollbar 彻底消灭水平黑色滚动槽！ */}
      {isAuthorized && subTab === "NOTIFICATIONS" && (
        <div className="lindo-hairline-b no-scrollbar flex shrink-0 items-center gap-1.5 overflow-x-auto px-3.5 py-2">
          {[
            { key: "ALL", label: "全部" },
            { key: "UNREAD", label: "未读" },
            { key: "REPLY", label: "回复" },
            { key: "REACTION", label: "表态" },
            { key: "MENTION", label: "提及" },
            { key: "SYSTEM", label: "系统" },
          ].map((f) => {
            const selected = catFilter === f.key;
            return (
              <button
                key={f.key}
                type="button"
                onClick={() => setCatFilter(f.key as any)}
                className="shrink-0 rounded-full px-3 py-1 text-[12px] font-medium transition-colors cursor-pointer"
                style={{
                  backgroundColor: selected ? "var(--active-chip-bg)" : "var(--subtle-surface)",
                  color: selected ? "var(--active-chip-text)" : "var(--ink-secondary)",
                }}
              >
                {f.label}
              </button>
            );
          })}
        </div>
      )}

      {/* 私信箱三级切换：收件箱 | 已发送 | 归档 */}
      {isAuthorized && subTab === "PM" && (
        <div className="lindo-hairline-b no-scrollbar flex shrink-0 items-center gap-1.5 overflow-x-auto px-3.5 py-2">
          {[
            { key: "inbox", label: "收件箱" },
            { key: "sent", label: "已发送" },
            { key: "archive", label: "已归档" },
          ].map((b) => {
            const selected = pmBox === b.key;
            return (
              <button
                key={b.key}
                type="button"
                onClick={() => setPmBox(b.key as any)}
                className="shrink-0 rounded-full px-3 py-1 text-[12px] font-medium transition-colors cursor-pointer"
                style={{
                  backgroundColor: selected ? "var(--active-chip-bg)" : "var(--subtle-surface)",
                  color: selected ? "var(--active-chip-text)" : "var(--ink-secondary)",
                }}
              >
                {b.label}
              </button>
            );
          })}
        </div>
      )}

      {!isAuthorized ? (
        <div className="flex flex-1 flex-col items-center justify-center px-6 text-center">
          <div
            className="mb-3 flex h-11 w-11 items-center justify-center rounded-full"
            style={{ backgroundColor: "var(--subtle-surface)", color: "var(--ink-secondary)" }}
          >
            <Lock size={20} />
          </div>
          <p className="text-[13.5px]" style={{ color: "var(--ink-secondary)" }}>
            登录后查看社区通知与站内私信
          </p>
          <button
            type="button"
            onClick={onOpenAuth}
            className="mt-3.5 rounded-full px-5 py-1.5 text-[13px] font-semibold cursor-pointer"
            style={{
              backgroundColor: "var(--publish-btn-bg)",
              color: "var(--publish-btn-fg)",
            }}
          >
            去登录
          </button>
        </div>
      ) : (
        <div className="lindo-scroll flex-1 min-h-0">
          {subTab === "COMPOSE_PM" ? (
            <div className="space-y-3 p-4">
              <div className="text-[14.5px] font-bold" style={{ color: "var(--ink-primary)" }}>
                站内私信
              </div>
              <div
                className="flex items-center gap-2 py-2"
                style={{
                  borderBottom:
                    pmAttempted && !pmRecipient.trim()
                      ? "0.5px solid var(--error-border)"
                      : "0.5px solid var(--hairline)",
                }}
              >
                <input
                  type="text"
                  value={pmRecipient}
                  onChange={(e) => setPmRecipient(e.target.value)}
                  placeholder="接收人（用户名，多人用逗号分隔）"
                  className="flex-1 bg-transparent text-[13.5px] outline-none"
                  style={{ color: "var(--ink-primary)" }}
                />
                {pmAttempted && !pmRecipient.trim() && (
                  <span className="text-[11.5px] font-medium" style={{ color: "var(--error-fg)" }}>
                    必填
                  </span>
                )}
              </div>

              <div
                className="flex items-center gap-2 py-2"
                style={{
                  borderBottom:
                    pmAttempted && !pmTitle.trim()
                      ? "0.5px solid var(--error-border)"
                      : "0.5px solid var(--hairline)",
                }}
              >
                <input
                  type="text"
                  value={pmTitle}
                  onChange={(e) => setPmTitle(e.target.value)}
                  placeholder="私信主题"
                  className="flex-1 bg-transparent text-[14px] font-semibold outline-none"
                  style={{ color: "var(--ink-primary)" }}
                />
                {pmAttempted && !pmTitle.trim() && (
                  <span className="text-[11.5px] font-medium" style={{ color: "var(--error-fg)" }}>
                    必填
                  </span>
                )}
              </div>

              <div className="relative">
                <textarea
                  value={pmBody}
                  onChange={(e) => setPmBody(e.target.value)}
                  placeholder="写下私信内容（支持 Markdown）..."
                  rows={6}
                  className="w-full rounded-xl bg-transparent p-3 text-[13.5px] outline-none resize-none"
                  style={{
                    backgroundColor: "var(--subtle-surface)",
                    border:
                      pmAttempted && !pmBody.trim()
                        ? "0.5px solid var(--error-border)"
                        : "0.5px solid var(--hairline)",
                    color: "var(--ink-primary)",
                  }}
                />
              </div>

              {pmFeedback && (
                <p className="text-[12.5px]" style={{ color: "var(--error-fg)" }}>
                  {pmFeedback}
                </p>
              )}

              <button
                type="button"
                disabled={pmSending}
                onClick={async () => {
                  if (!pmRecipient.trim() || !pmTitle.trim() || !pmBody.trim()) {
                    setPmAttempted(true);
                    return;
                  }
                  setPmSending(true);
                  setPmFeedback(null);
                  try {
                    const tid = await sendPrivateMessage({
                      recipients: pmRecipient,
                      title: pmTitle,
                      raw: pmBody,
                    });
                    setPmRecipient("");
                    setPmTitle("");
                    setPmBody("");
                    setSubTab("PM");
                    if (tid > 0) onOpenNotificationTopic(tid, pmTitle, 1);
                  } catch (err: any) {
                    if (err instanceof SiteError && err.isVerification && onTriggerVerify) {
                      onTriggerVerify();
                    }
                    setPmFeedback(err?.message || "发送失败，内容已保留");
                  } finally {
                    setPmSending(false);
                  }
                }}
                className="inline-flex items-center gap-1.5 rounded-full px-5 py-2 text-[13px] font-bold cursor-pointer disabled:opacity-50"
                style={{
                  backgroundColor: "var(--publish-btn-bg)",
                  color: "var(--publish-btn-fg)",
                }}
              >
                <PaperPlaneTilt size={15} weight="fill" />
                <span>{pmSending ? "发送中..." : "发送私信"}</span>
              </button>
            </div>
          ) : loading ? (
            /* 温润线性流光骨架屏 + 0.5px 内缩细线 */
            <div>
              {Array.from({ length: 6 }).map((_, idx) => (
                <React.Fragment key={idx}>
                  <div className="flex items-start gap-3 px-4 py-3.5">
                    <div className="lindo-shimmer h-8 w-8 shrink-0 rounded-full" />
                    <div className="min-w-0 flex-1 space-y-2">
                      <div className="lindo-shimmer h-3.5 w-28 rounded" />
                      <div className="lindo-shimmer h-4 w-4/5 rounded" />
                    </div>
                  </div>
                  <div className="lindo-divider-inset" />
                </React.Fragment>
              ))}
            </div>
          ) : loadError ? (
            <div className="flex flex-col items-center justify-center px-6 py-16 text-center">
              <MascotExpression expression="surprised" sizePx={54} className="mb-3" />
              <p className="text-[13px]" style={{ color: "var(--ink-secondary)" }}>
                {loadError}
              </p>
              <button
                type="button"
                onClick={() => {
                  if (loadError.includes("验证") && onTriggerVerify) onTriggerVerify();
                  else if (subTab === "PM") loadPms(pmBox);
                  else loadNotifications();
                }}
                className="mt-3 rounded-full px-4 py-1.5 text-[12px] font-semibold cursor-pointer"
                style={{
                  backgroundColor: "var(--publish-btn-bg)",
                  color: "var(--publish-btn-fg)",
                }}
              >
                {loadError.includes("验证") ? "去验证" : "重试"}
              </button>
            </div>
          ) : subTab === "PM" ? (
            pmList.length === 0 ? (
              <div className="p-12 text-center text-[13px]" style={{ color: "var(--ink-tertiary)" }}>
                {pmBox === "sent"
                  ? "暂无已发送私信"
                  : pmBox === "archive"
                    ? "暂无已归档私信"
                    : "收件箱暂无私信"}
              </div>
            ) : (
              pmList.map((pm) => {
                const selected = selectedTopicId === pm.id;
                const canArchiveToggle = pmBox === "inbox" || pmBox === "archive";
                return (
                  <React.Fragment key={pm.id}>
                    <div
                      className="group relative flex w-full items-center"
                      style={{
                        backgroundColor: selected ? "var(--subtle-surface)" : "transparent",
                      }}
                    >
                      <button
                        type="button"
                        onClick={() =>
                          onOpenNotificationTopic(pm.id, pm.title, pm.highestPostNumber)
                        }
                        className="lindo-interactive flex min-w-0 flex-1 items-center gap-3 px-4 py-3 text-left"
                      >
                        <img
                          src={resolveAvatarUrl(pm.originalPoster.avatarTemplate, 96)}
                          alt={pm.originalPoster.username}
                          className="h-9 w-9 shrink-0 rounded-full object-cover"
                        />
                        <div className="min-w-0 flex-1">
                          <div className="flex items-center justify-between gap-2">
                            <span
                              className="truncate text-[13.6px] font-semibold"
                              style={{ color: "var(--ink-primary)" }}
                            >
                              {pm.title}
                            </span>
                            {pm.unreadPosts > 0 && (
                              <span
                                className="h-2 w-2 shrink-0 rounded-full"
                                style={{ backgroundColor: "var(--brand-gold)" }}
                              />
                            )}
                          </div>
                          <div
                            className="mt-0.5 flex items-center justify-between text-[11.5px]"
                            style={{ color: "var(--ink-tertiary)" }}
                          >
                            <span>@{pm.originalPoster.username}</span>
                            <span>
                              {pm.postsCount} 条对话 · {formatRelativeTime(pm.lastPostedAt)}
                            </span>
                          </div>
                        </div>
                      </button>

                      {canArchiveToggle && (
                        <button
                          type="button"
                          title={pmBox === "inbox" ? "归档私信" : "移回收件箱"}
                          onClick={async (e) => {
                            e.stopPropagation();
                            const targetArchive = pmBox === "inbox";
                            const prevList = pmList;
                            setPmList((cur) => cur.filter((x) => x.id !== pm.id));
                            try {
                              await archivePrivateMessage(pm.id, targetArchive);
                              setPmActionNotice(
                                targetArchive ? "私信已归档" : "已移回收件箱"
                              );
                            } catch (err: any) {
                              setPmList(prevList);
                              if (
                                err instanceof SiteError &&
                                err.isVerification &&
                                onTriggerVerify
                              ) {
                                onTriggerVerify();
                              } else {
                                setPmActionNotice(err?.message || "操作未成功");
                              }
                            }
                          }}
                          className="lindo-interactive mr-2.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-full opacity-0 transition-opacity group-hover:opacity-100 focus:opacity-100"
                          style={{ color: "var(--ink-secondary)" }}
                        >
                          {pmBox === "inbox" ? (
                            <Archive size={15} />
                          ) : (
                            <ArrowBendUpLeft size={15} />
                          )}
                        </button>
                      )}
                    </div>
                    <div className="lindo-divider-inset" />
                  </React.Fragment>
                );
              })
            )
          ) : filteredItems.length === 0 ? (
            <div className="p-12 text-center text-[13px]" style={{ color: "var(--ink-tertiary)" }}>
              {catFilter === "UNREAD" ? "太棒了，所有通知均已读" : "暂无该类别通知"}
            </div>
          ) : (
            <div>
              {filteredItems.map((n) => {
                const selected = Boolean(n.topicId && selectedTopicId === n.topicId);
                const useCommunityMark =
                  Boolean(n.isConsolidatedReplies) ||
                  n.actorUsername === "系统" ||
                  !n.actorAvatarTemplate;
                const cornerIcon = resolveNotificationCornerIcon(n);

                const primaryLine = n.isConsolidatedReplies
                  ? `${n.count && n.count > 1 ? n.count : 2} 个回复`
                  : n.notificationType === 12
                    ? "获得徽章"
                    : n.count && n.count > 1 && [5, 19].includes(n.notificationType)
                      ? `${n.actorUsername} 等 ${n.count} 人`
                      : n.actorUsername;

                const cleanTitle =
                  n.title && n.title !== "社区动态通知" ? n.title.trim() : "";
                const secondaryLine =
                  n.notificationType === 12
                    ? n.badgeName || cleanTitle || "授予社区徽章"
                    : [36, 800].includes(n.notificationType) && !cleanTitle
                      ? "关注了你"
                      : cleanTitle || n.excerpt || notificationFallbackVerb(n.notificationType);

                const rowBg = selected
                  ? !n.read
                    ? isDark
                      ? "rgba(14, 165, 233, 0.24)"
                      : "rgba(14, 165, 233, 0.14)"
                    : "var(--subtle-surface)"
                  : !n.read
                    ? isDark
                      ? "rgba(14, 116, 144, 0.22)"
                      : "rgba(14, 165, 233, 0.08)"
                    : "transparent";

                const cutoutRingColor = !n.read
                  ? isDark
                    ? "#122B35"
                    : "#E6F4FE"
                  : selected
                    ? "var(--subtle-surface)"
                    : "var(--card-surface)";

                return (
                  <React.Fragment key={n.id}>
                    <button
                      type="button"
                      onClick={() => {
                        if (!n.read) {
                          setItems((prev) =>
                            prev.map((item) =>
                              item.id === n.id ? { ...item, read: true } : item
                            )
                          );
                          markSingleNotificationRead(n.id).catch(() => {});
                          onDecrementUnread?.();
                        }
                        if (n.topicId) {
                          onOpenNotificationTopic(n.topicId, n.title, n.postNumber);
                        } else if (n.badgeId && n.badgeId > 0 && onInspectBadge) {
                          onInspectBadge({
                            id: n.badgeId,
                            name: n.badgeName || cleanTitle || "社区徽章",
                            description: n.excerpt || "",
                            badgeTypeId: 3,
                            isEarned: true,
                          });
                        } else if (n.chatChannelId) {
                          onOpenChat();
                        } else if ([6, 7, 16].includes(n.notificationType)) {
                          setSubTab("PM");
                        } else if (
                          n.actorUsername &&
                          n.actorUsername !== "系统" &&
                          !n.isConsolidatedReplies &&
                          onInspectUser
                        ) {
                          onInspectUser(n.actorUsername);
                        }
                      }}
                      className="lindo-interactive flex w-full items-start gap-3 px-4 py-3 text-left transition-colors"
                      style={{ backgroundColor: rowBg }}
                    >
                      {/* 左侧 38px 圆形头像（或 Linux.do 三色圆标）+ 右上角镂空语义动作角标 */}
                      <div className="relative mt-0.5 h-[38px] w-[38px] shrink-0">
                        {useCommunityMark ? (
                          <img
                            src="/brand/linuxdo-mark.svg"
                            alt="LINUX DO"
                            className="h-[38px] w-[38px] rounded-full object-cover"
                            style={{ backgroundColor: "var(--subtle-surface)" }}
                          />
                        ) : (
                          <img
                            src={resolveAvatarUrl(n.actorAvatarTemplate, 144)}
                            alt={n.actorUsername}
                            loading="lazy"
                            decoding="async"
                            className={getAvatarClassName(
                              "h-[38px] w-[38px]",
                              n.actorUsername,
                              undefined,
                              n.actorAvatarTemplate
                            )}
                            style={{ backgroundColor: "var(--subtle-surface)" }}
                          />
                        )}
                        <span
                          className="pointer-events-none absolute -top-1 -right-1 flex h-[18px] w-[18px] items-center justify-center rounded-full"
                          style={{
                            backgroundColor: !n.read ? "#0EA5E9" : cutoutRingColor,
                            color: !n.read
                              ? "#FFFFFF"
                              : isDark
                                ? "#E5E7EB"
                                : "#4B5563",
                            boxShadow: `0 0 0 1.5px ${cutoutRingColor}`,
                          }}
                        >
                          {cornerIcon}
                        </span>
                      </div>

                      {/* 右侧极简直观双层结构：第一行粗体发送者 / N个回复 + 时间；第二行双行标题/摘要 */}
                      <div className="min-w-0 flex-1">
                        <div className="flex items-baseline justify-between gap-2">
                          <span
                            className="truncate text-[13.8px] font-bold leading-snug"
                            style={{ color: "var(--ink-primary)" }}
                          >
                            {primaryLine}
                          </span>
                          <span
                            className="shrink-0 text-[11px] tabular-nums"
                            style={{ color: "var(--ink-tertiary)" }}
                          >
                            {formatRelativeTime(n.createdAt)}
                          </span>
                        </div>

                        <div
                          className="mt-0.5 line-clamp-2 text-[13.5px] leading-[1.42]"
                          style={{
                            color: !n.read ? "var(--ink-primary)" : "var(--ink-secondary)",
                            fontWeight: !n.read ? 500 : 400,
                          }}
                        >
                          {secondaryLine}
                        </div>
                      </div>
                    </button>
                    <div className="lindo-divider-inset" />
                  </React.Fragment>
                );
              })}

              {hasMoreNotifs && catFilter === "ALL" && (
                <div className="py-3 text-center">
                  <button
                    type="button"
                    disabled={loadingMoreNotifs}
                    onClick={loadMoreNotifications}
                    className="lindo-interactive rounded-full px-4 py-1 text-[12px] font-medium"
                    style={{ color: "var(--active-chip-text)" }}
                  >
                    {loadingMoreNotifs ? "正在加载更多..." : "加载更早通知"}
                  </button>
                </div>
              )}
            </div>
          )}
        </div>
      )}

      {pmActionNotice && (
        <div
          className="pointer-events-none fixed bottom-7 left-1/2 z-50 -translate-x-1/2 rounded-full px-4 py-1.5 text-[12.5px] font-medium shadow-xl"
          style={{
            backgroundColor: "var(--subtle-surface)",
            border: "0.5px solid var(--hairline)",
            color: "var(--ink-primary)",
          }}
        >
          {pmActionNotice}
        </div>
      )}
    </div>
  );
};

/* ==================== 3. 个人中心与设置 (CommunityProfileScreen 1:1 对齐) ==================== */
export const ProfileSettingsView: React.FC<{
  isAuthorized: boolean;
  isDark: boolean;
  currentUser: UserProfile | null;
  savedAccounts: SavedAccountEntry[];
  preferences: UserPreferences;
  readFloors: Record<string, number>;
  drafts: Record<string, LocalDraftRecord>;
  knownTopics: TopicSummary[];
  onUpdatePreferences: (next: UserPreferences) => void;
  onToggleDark: () => void;
  onPrimaryWebLogin: () => void;
  onOpenAuthSheet: () => void;
  onRequestGuestLogin: (title: string, subtitle: string) => void;
  onOpenInAppUrl: (url: string) => void;
  onRequestLogout: () => void;
  onOpenTopicById: (topicId: number, title: string, postNumber?: number) => void;
  onResumeComposerDraft: (req: ComposerLaunchRequest) => void;
  onInspectUser: (username: string) => void;
  onInspectBadge?: (badge: GrantedBadge) => void;
  onHistoryCleared: () => void;
  onDraftsChanged: () => void;
  onProfileUpdated: () => void;
  updateInfo?: DesktopUpdateInfo | null;
  checkingUpdate?: boolean;
  onCheckUpdate?: () => void;
  onOpenUpdateModal?: () => void;
}> = ({
  isAuthorized,
  isDark,
  currentUser,
  savedAccounts,
  preferences,
  readFloors,
  drafts,
  knownTopics,
  onUpdatePreferences,
  onToggleDark,
  onPrimaryWebLogin,
  onOpenAuthSheet,
  onRequestGuestLogin,
  onOpenInAppUrl,
  onRequestLogout,
  onOpenTopicById,
  onResumeComposerDraft,
  onInspectUser,
  onInspectBadge,
  onHistoryCleared,
  onDraftsChanged,
  onProfileUpdated,
  updateInfo,
  checkingUpdate = false,
  onCheckUpdate,
  onOpenUpdateModal,
}) => {
  const [expandedRow, setExpandedRow] = useState<
    "appearance" | "theme" | "density" | "font" | null
  >(null);
  const [subSheet, setSubSheet] = useState<
    | "bookmarks"
    | "history"
    | "drafts"
    | "activity"
    | "follows"
    | "badges"
    | "profile_edit"
    | "diagnostics"
    | "about"
    | null
  >(null);
  const [diagFilter, setDiagFilter] = useState<DiagCategory | "ALL">("ALL");
  const [diagTick, setDiagTick] = useState(0);
  const [expandedDiagId, setExpandedDiagId] = useState<number | null>(null);

  useEffect(() => {
    if (subSheet !== "diagnostics") return;
    return subscribeDiagEntries(() => setDiagTick((n) => n + 1));
  }, [subSheet]);

  const diagEntries = useMemo(
    () => (subSheet === "diagnostics" ? getDiagEntries(diagFilter) : []),
    [subSheet, diagFilter, diagTick]
  );
  const [showClearCacheConfirm, setShowClearCacheConfirm] = useState(false);
  const [cacheClearedNotice, setCacheClearedNotice] = useState<string | null>(null);

  const [bookmarks, setBookmarks] = useState<any[]>([]);
  const [historyTopics, setHistoryTopics] = useState<TopicSummary[]>([]);
  const [localHistory, setLocalHistory] = useState<LocalHistoryItem[]>([]);
  const [historyPage, setHistoryPage] = useState(0);
  const [historyHasMore, setHistoryHasMore] = useState(false);
  const [loadingMoreHistory, setLoadingMoreHistory] = useState(false);
  const [cloudDrafts, setCloudDrafts] = useState<CloudDraftItem[]>([]);
  const [activityFilter, setActivityFilter] = useState<"1,4,5" | "4" | "5" | "1">("1,4,5");
  const [activities, setActivities] = useState<any[]>([]);
  const [followTab, setFollowTab] = useState<"following" | "followers">("following");
  const [followUsers, setFollowUsers] = useState<FollowUserEntry[]>([]);
  const [badgeTab, setBadgeTab] = useState<"mine" | "site">("mine");
  const [badges, setBadges] = useState<any[]>([]);
  const [subLoading, setSubLoading] = useState(false);
  const [toast, setToast] = useState<string | null>(null);

  // Native profile & preferences editor state
  const [acctSnap, setAcctSnap] = useState<UserAccountPreferencesSnapshot | null>(null);
  const [editDisplayName, setEditDisplayName] = useState("");
  const [editTitle, setEditTitle] = useState("");
  const [editBio, setEditBio] = useState("");
  const [editLocation, setEditLocation] = useState("");
  const [editWebsite, setEditWebsite] = useState("");
  const [editHidePresence, setEditHidePresence] = useState(false);
  const [editAllowPm, setEditAllowPm] = useState(true);
  const [savingProfile, setSavingProfile] = useState(false);

  useEffect(() => {
    if (!cacheClearedNotice) return;
    const t = setTimeout(() => setCacheClearedNotice(null), 2600);
    return () => clearTimeout(t);
  }, [cacheClearedNotice]);

  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(null), 2600);
    return () => clearTimeout(t);
  }, [toast]);

  useEffect(() => {
    if (!subSheet) return;
    if (subSheet === "bookmarks" && isAuthorized && currentUser?.username) {
      setSubLoading(true);
      fetchUserBookmarks(currentUser.username)
        .then(setBookmarks)
        .catch(() => {})
        .finally(() => setSubLoading(false));
    } else if (subSheet === "history") {
      const localSnap = getLocalTopicHistory();
      setLocalHistory(localSnap);
      setHistoryPage(0);
      if (isAuthorized) {
        setSubLoading(localSnap.length === 0);
        fetchReadHistoryTopics(0)
          .then((res) => {
            setHistoryTopics(res.topics);
            setHistoryHasMore(res.hasMore);
          })
          .catch(() => {
            setHistoryTopics([]);
            setHistoryHasMore(false);
          })
          .finally(() => setSubLoading(false));
      } else {
        setHistoryTopics([]);
        setHistoryHasMore(false);
        setSubLoading(false);
      }
    } else if (subSheet === "drafts" && isAuthorized) {
      setSubLoading(true);
      fetchCloudDrafts()
        .then(setCloudDrafts)
        .catch(() => setCloudDrafts([]))
        .finally(() => setSubLoading(false));
    } else if (subSheet === "activity" && isAuthorized && currentUser?.username) {
      setSubLoading(true);
      fetchUserActions(currentUser.username, activityFilter)
        .then(setActivities)
        .catch(() => {})
        .finally(() => setSubLoading(false));
    } else if (subSheet === "follows" && isAuthorized && currentUser?.username) {
      setSubLoading(true);
      fetchUserFollowList(currentUser.username, followTab === "followers")
        .then(setFollowUsers)
        .catch(() => setFollowUsers([]))
        .finally(() => setSubLoading(false));
    } else if (subSheet === "badges") {
      setSubLoading(true);
      const p =
        badgeTab === "mine" && isAuthorized && currentUser?.username
          ? fetchUserBadges(currentUser.username)
          : fetchSiteBadges();
      p.then(setBadges)
        .catch(() => {})
        .finally(() => setSubLoading(false));
    } else if (subSheet === "profile_edit" && isAuthorized && currentUser?.username) {
      setSubLoading(true);
      fetchUserAccountPreferences(currentUser.username)
        .then((snap) => {
          setAcctSnap(snap);
          setEditDisplayName(snap.displayName);
          setEditTitle(snap.title);
          setEditBio(snap.bioRaw);
          setEditLocation(snap.location);
          setEditWebsite(snap.website);
          setEditHidePresence(snap.hideProfileAndPresence);
          setEditAllowPm(snap.allowPrivateMessages);
        })
        .catch(() => {})
        .finally(() => setSubLoading(false));
    }
  }, [subSheet, isAuthorized, currentUser?.username, activityFilter, followTab, badgeTab]);

  const currentThemeSpec =
    BRAND_THEMES.find((t) => t.key === preferences.themeOption) ?? BRAND_THEMES[0];
  const currentFontPreset = nearestFontPreset(preferences.fontScale);
  const appearanceLabel =
    preferences.appearance === "light"
      ? "浅色"
      : preferences.appearance === "dark"
        ? "深色"
        : "跟随系统";

  const localDraftList = Object.values(drafts);

  return (
    <div
      className="flex h-full w-full flex-col"
      style={{ backgroundColor: "var(--card-surface)" }}
    >
      {/* 顶栏：我的 + 昼夜切换 */}
      <header
        onMouseDown={handleTitlebarMouseDown}
        className="lindo-hairline-b flex h-[50px] shrink-0 items-center justify-between px-5"
      >
        <h1
          className="text-[19px] font-bold tracking-tight"
          style={{ color: "var(--ink-primary)" }}
        >
          我的
        </h1>
        <button
          type="button"
          onClick={onToggleDark}
          title={isDark ? "切换浅色模式" : "切换深色模式"}
          className="lindo-no-drag lindo-interactive flex h-8.5 w-8.5 items-center justify-center rounded-full"
          style={{ color: "var(--ink-primary)" }}
        >
          {isDark ? <Sun size={18} /> : <Moon size={18} />}
        </button>
      </header>

      <div className="lindo-scroll flex-1 min-h-0">
        <div className="mx-auto max-w-3xl pb-16">
          {/* 1. 个人信息 + 4 项线性图标快捷栏 */}
          <div className="lindo-hairline-b px-5 py-4">
            <div className="flex items-center justify-between gap-3">
              <div className="flex min-w-0 items-center gap-3.5">
                {isAuthorized && currentUser ? (
                  <img
                    src={resolveAvatarUrl(
                      currentUser.animatedAvatar || currentUser.avatarTemplate,
                      144
                    )}
                    alt={currentUser.username}
                    className={getAvatarClassName(
                      "h-[52px] w-[52px] shrink-0",
                      currentUser.username,
                      undefined,
                      currentUser.avatarTemplate
                    )}
                  />
                ) : (
                  <button
                    type="button"
                    onClick={() =>
                      onRequestGuestLogin(
                        "欢迎登录 LINUX DO",
                        "登录后自动同步个人资料、Connect 保级、收藏与通知"
                      )
                    }
                    className="flex h-[52px] w-[52px] shrink-0 items-center justify-center rounded-full cursor-pointer"
                    style={{
                      backgroundColor: "var(--subtle-surface)",
                      border: "0.5px solid var(--hairline)",
                      color: "var(--ink-secondary)",
                    }}
                  >
                    <User size={22} />
                  </button>
                )}

                <div className="min-w-0">
                  {isAuthorized && currentUser ? (
                    <>
                      <div className="flex items-center gap-1.5">
                        <span
                          className="truncate text-[18px] font-bold"
                          style={{ color: "var(--ink-primary)" }}
                        >
                          {currentUser.displayName || currentUser.username}
                        </span>
                        <span className="text-[12.5px]" style={{ color: "var(--ink-secondary)" }}>
                          @{currentUser.username}
                        </span>
                      </div>
                      <div className="mt-1 flex items-center gap-2">
                        <span
                          className="text-[12px] font-bold"
                          style={{ color: "var(--brand-gold)" }}
                        >
                          {trustLevelLabel(currentUser.trustLevel)}
                        </span>
                        {currentUser.userTitle && (
                          <span
                            className={
                              isHolographicTitle(currentUser.userTitle)
                                ? "lindo-title-holo text-[12px]"
                                : "rounded-full px-2 py-0.5 text-[11px] font-medium"
                            }
                            style={
                              isHolographicTitle(currentUser.userTitle)
                                ? undefined
                                : {
                                    backgroundColor: "var(--subtle-surface)",
                                    color: "var(--ink-secondary)",
                                  }
                            }
                          >
                            {currentUser.userTitle}
                          </span>
                        )}
                      </div>
                    </>
                  ) : (
                    <>
                      <div className="text-[18px] font-bold" style={{ color: "var(--ink-primary)" }}>
                        未登录游客
                      </div>
                      <div className="mt-0.5 text-[12.5px]" style={{ color: "var(--ink-secondary)" }}>
                        登录后同步个人资料、收藏与通知
                      </div>
                    </>
                  )}
                </div>
              </div>

              {isAuthorized ? (
                <button
                  type="button"
                  onClick={() => setSubSheet("profile_edit")}
                  title="编辑个人资料与头衔"
                  className="lindo-interactive flex h-8 w-8 items-center justify-center rounded-full"
                  style={{ color: "var(--ink-secondary)" }}
                >
                  <PencilSimple size={17} />
                </button>
              ) : (
                <div className="flex items-center gap-1.5">
                  {savedAccounts.length > 0 && (
                    <button
                      type="button"
                      onClick={onOpenAuthSheet}
                      className="lindo-interactive rounded-lg px-2.5 py-1.5 text-[13.5px] font-medium"
                      style={{ color: "var(--ink-secondary)" }}
                    >
                      切换账号
                    </button>
                  )}
                  <button
                    type="button"
                    onClick={onPrimaryWebLogin}
                    className="lindo-interactive rounded-lg px-2.5 py-1.5 text-[14px] font-semibold"
                    style={{ color: "var(--active-chip-text)" }}
                  >
                    登录
                  </button>
                </div>
              )}
            </div>

            {/* 顶部 4 项线性图标快捷栏：收藏 | 历史 | 草稿 | 活动 */}
            <div className="mt-4 grid grid-cols-4 gap-2">
              {[
                {
                  key: "bookmarks",
                  label: "收藏",
                  icon: <BookmarkSimple size={20} />,
                  guestTitle: "登录后使用我的收藏",
                  guestSub: "随时收藏干货好帖，跨端同步书签记录",
                },
                {
                  key: "history",
                  label: "历史",
                  icon: <ClockCounterClockwise size={20} />,
                  guestTitle: "",
                  guestSub: "",
                },
                {
                  key: "drafts",
                  label: "草稿",
                  icon: <FileText size={20} />,
                  guestTitle: "",
                  guestSub: "",
                },
                {
                  key: "activity",
                  label: "活动",
                  icon: <CalendarBlank size={20} />,
                  guestTitle: "登录后查看个人动态",
                  guestSub: "登录 LINUX DO 账号后可查看发帖、回复与点赞记录",
                },
              ].map((item) => (
                <button
                  key={item.key}
                  type="button"
                  onClick={() => {
                    if ((item.key === "bookmarks" || item.key === "activity") && !isAuthorized) {
                      onRequestGuestLogin(item.guestTitle, item.guestSub);
                    } else {
                      setSubSheet(item.key as any);
                    }
                  }}
                  className="lindo-interactive flex flex-col items-center justify-center rounded-xl py-2"
                >
                  <span style={{ color: "var(--ink-primary)" }}>{item.icon}</span>
                  <span className="mt-1 text-[12px]" style={{ color: "var(--ink-secondary)" }}>
                    {item.label}
                  </span>
                </button>
              ))}
            </div>
          </div>

          {/* 2. 社区与账号 */}
          <div className="lindo-hairline-b">
            <GroupHeader title="社区与账号" />
            <SettingRow
              icon={<Trophy size={18} />}
              title="社区徽章墙"
              onClick={() => {
                setBadgeTab(isAuthorized ? "mine" : "site");
                setSubSheet("badges");
              }}
            />
            <SettingRow
              icon={<Users size={18} />}
              title="关注与粉丝"
              onClick={() => {
                if (isAuthorized && currentUser) {
                  setSubSheet("follows");
                } else {
                  onRequestGuestLogin(
                    "登录后查看关注与粉丝",
                    "关注感兴趣的佬友，订阅他们的最新动态"
                  );
                }
              }}
            />
            <SettingRow
              icon={<Sparkle size={18} />}
              title="Connect 保级与成长"
              onClick={() => {
                if (isAuthorized) onOpenInAppUrl("https://connect.linux.do/");
                else
                  onRequestGuestLogin(
                    "登录后访问 Connect 中心",
                    "查看信任等级进度、保级指标与专属社区权益"
                  );
              }}
            />
            <SettingRow
              icon={<ShieldCheck size={18} />}
              title="账号与偏好设置"
              onClick={() => {
                if (isAuthorized) setSubSheet("profile_edit");
                else
                  onRequestGuestLogin(
                    "登录后管理账号设置",
                    "自定义个人主页、头衔徽章与隐私偏好"
                  );
              }}
            />
            <SettingRow
              icon={<Globe size={18} />}
              title="账号管理"
              value={
                isAuthorized && currentUser
                  ? `@${currentUser.username}`
                  : savedAccounts.length > 0
                    ? `已保存 ${savedAccounts.length} 个账号`
                    : "登录 / 切换账号"
              }
              hideDivider
              onClick={onOpenAuthSheet}
            />
          </div>

          {/* 3. 外观 */}
          <div className="lindo-hairline-b">
            <GroupHeader title="外观" />
            <SettingRow
              icon={<Sun size={18} />}
              title="外观"
              value={appearanceLabel}
              expanded={expandedRow === "appearance"}
              onClick={() =>
                setExpandedRow(expandedRow === "appearance" ? null : "appearance")
              }
            />
            {expandedRow === "appearance" && (
              <>
                <div className="flex flex-wrap items-center gap-2 px-5 pt-2.5 pb-4">
                  {[
                    { key: "light", label: "浅色", icon: <Sun size={14} /> },
                    { key: "dark", label: "深色", icon: <Moon size={14} /> },
                    { key: "system", label: "跟随系统", icon: <Desktop size={14} /> },
                  ].map((opt) => {
                    const selected = preferences.appearance === opt.key;
                    return (
                      <button
                        key={opt.key}
                        type="button"
                        onClick={() =>
                          onUpdatePreferences({ ...preferences, appearance: opt.key as any })
                        }
                        className="inline-flex items-center gap-1.5 rounded-full px-3.5 py-1.5 text-[12.5px] font-medium cursor-pointer"
                        style={{
                          backgroundColor: selected
                            ? "var(--active-chip-bg)"
                            : "var(--subtle-surface)",
                          border: selected
                            ? "0.5px solid var(--brand-gold)"
                            : "0.5px solid var(--hairline)",
                          color: selected ? "var(--active-chip-text)" : "var(--ink-primary)",
                        }}
                      >
                        {opt.icon}
                        <span>{opt.label}</span>
                      </button>
                    );
                  })}
                </div>
                <div className="mx-5 h-[0.5px]" style={{ backgroundColor: "var(--hairline)" }} />
              </>
            )}

            <SettingRow
              icon={<Palette size={18} />}
              title="主题色"
              value={currentThemeSpec.label}
              leadingValueDot={currentThemeSpec.swatchColor}
              hideDivider
              expanded={expandedRow === "theme"}
              onClick={() => setExpandedRow(expandedRow === "theme" ? null : "theme")}
            />
            {expandedRow === "theme" && (
              <div className="flex flex-wrap gap-2 px-5 pt-2.5 pb-4">
                {BRAND_THEMES.map((theme) => {
                  const selected = preferences.themeOption === theme.key;
                  return (
                    <button
                      key={theme.key}
                      type="button"
                      onClick={() =>
                        onUpdatePreferences({ ...preferences, themeOption: theme.key })
                      }
                      className="inline-flex items-center gap-2 rounded-full px-3 py-1 text-[12.5px] font-medium cursor-pointer"
                      style={{
                        backgroundColor: selected
                          ? "var(--active-chip-bg)"
                          : "var(--subtle-surface)",
                        border: selected
                          ? `0.5px solid ${theme.swatchColor}`
                          : "0.5px solid var(--hairline)",
                        color: selected ? "var(--active-chip-text)" : "var(--ink-primary)",
                      }}
                    >
                      <span
                        className="h-3 w-3 rounded-full"
                        style={{ backgroundColor: theme.swatchColor }}
                      />
                      <span>{theme.label}</span>
                    </button>
                  );
                })}
              </div>
            )}
          </div>

          {/* 4. 阅读（PC 端首页常驻搜索框，移除冗余搜索框开关） */}
          <div className="lindo-hairline-b">
            <GroupHeader title="阅读" />
            <SettingRow
              icon={preferences.compactFeed ? <ListBullets size={18} /> : <Rows size={18} />}
              title="首页列表视图"
              value={preferences.compactFeed ? "紧凑" : "宽松"}
              expanded={expandedRow === "density"}
              onClick={() => setExpandedRow(expandedRow === "density" ? null : "density")}
            />
            {expandedRow === "density" && (
              <>
                <div className="flex items-center gap-2 px-5 pt-2.5 pb-4">
                  {[
                    { compact: false, label: "宽松", icon: <Rows size={14} /> },
                    { compact: true, label: "紧凑", icon: <ListBullets size={14} /> },
                  ].map((opt) => {
                    const selected = preferences.compactFeed === opt.compact;
                    return (
                      <button
                        key={opt.label}
                        type="button"
                        onClick={() =>
                          onUpdatePreferences({ ...preferences, compactFeed: opt.compact })
                        }
                        className="inline-flex items-center gap-1.5 rounded-full px-3.5 py-1.5 text-[12.5px] font-medium cursor-pointer"
                        style={{
                          backgroundColor: selected
                            ? "var(--active-chip-bg)"
                            : "var(--subtle-surface)",
                          border: selected
                            ? "0.5px solid var(--brand-gold)"
                            : "0.5px solid var(--hairline)",
                          color: selected ? "var(--active-chip-text)" : "var(--ink-primary)",
                        }}
                      >
                        {opt.icon}
                        <span>{opt.label}</span>
                      </button>
                    );
                  })}
                </div>
                <div className="mx-5 h-[0.5px]" style={{ backgroundColor: "var(--hairline)" }} />
              </>
            )}

            <SettingRow
              icon={<TextAa size={18} />}
              title="阅读字号"
              value={`${currentFontPreset.label} (${Math.round(currentFontPreset.scale * 100)}%)`}
              hideDivider
              expanded={expandedRow === "font"}
              onClick={() => setExpandedRow(expandedRow === "font" ? null : "font")}
            />
            {expandedRow === "font" && (
              <div className="space-y-2.5 px-5 pt-2.5 pb-4">
                <div className="flex flex-wrap items-center gap-2">
                  {FONT_SCALE_PRESETS.map((p) => {
                    const selected = currentFontPreset.label === p.label;
                    return (
                      <button
                        key={p.label}
                        type="button"
                        onClick={() =>
                          onUpdatePreferences({ ...preferences, fontScale: p.scale })
                        }
                        className="rounded-full px-3.5 py-1 text-[12.5px] font-medium cursor-pointer"
                        style={{
                          backgroundColor: selected
                            ? "var(--active-chip-bg)"
                            : "var(--subtle-surface)",
                          border: selected
                            ? "0.5px solid var(--brand-gold)"
                            : "0.5px solid var(--hairline)",
                          color: selected ? "var(--active-chip-text)" : "var(--ink-primary)",
                        }}
                      >
                        {p.label}
                      </button>
                    );
                  })}
                </div>
                <div
                  className="rounded-xl px-3.5 py-2.5"
                  style={{
                    backgroundColor: "var(--subtle-surface)",
                    border: "0.5px solid var(--hairline)",
                  }}
                >
                  <div className="text-[15px] font-semibold" style={{ color: "var(--ink-primary)" }}>
                    真诚 · 友善 · 团结 · 专业
                  </div>
                  <div className="mt-0.5 text-[13px]" style={{ color: "var(--ink-secondary)" }}>
                    话题列表、楼层标题、正文与消息通知字号将同步按 {Math.round(currentFontPreset.scale * 100)}% 缩放。
                  </div>
                </div>
              </div>
            )}
          </div>

          {/* 5. 通用 */}
          <div className="lindo-hairline-b">
            <GroupHeader title="通用" />
            <ToggleSettingRow
              icon={<Sparkle size={18} />}
              title="界面与头像动效"
              checked={preferences.enableAnimations}
              onChange={(v) => onUpdatePreferences({ ...preferences, enableAnimations: v })}
            />
            <SettingRow
              icon={<Trash size={18} />}
              title="清理本地缓存"
              value={cacheClearedNotice ?? ""}
              onClick={() => setShowClearCacheConfirm(true)}
            />
            <SettingRow
              icon={<SmoothRefreshIcon spinning={checkingUpdate} size={18} />}
              title="检查更新"
              badgeText={
                updateInfo?.hasUpdate ? `发现新版本 v${updateInfo.latestVersion}` : undefined
              }
              value={
                checkingUpdate
                  ? "正在检查..."
                  : !updateInfo?.hasUpdate
                    ? `当前 v${updateInfo?.currentVersion || "1.0.45"}`
                    : undefined
              }
              onClick={() => {
                if (updateInfo?.hasUpdate && onOpenUpdateModal) {
                  onOpenUpdateModal();
                } else {
                  onCheckUpdate?.();
                }
              }}
            />
            <SettingRow
              icon={<FileText size={18} />}
              title="诊断日志与导出"
              onClick={() => setSubSheet("diagnostics")}
            />
            <SettingRow
              icon={<Info size={18} />}
              title="关于 Lindo"
              hideDivider
              onClick={() => setSubSheet("about")}
            />
          </div>

          {/* 6. 退出当前账号 */}
          {isAuthorized && (
            <button
              type="button"
              onClick={onRequestLogout}
              className="lindo-interactive lindo-hairline-b flex h-[50px] w-full items-center justify-center gap-2 text-[14px] font-medium"
              style={{ color: "#EF4444" }}
            >
              <SignOut size={17} />
              <span>退出当前账号</span>
            </button>
          )}
        </div>
      </div>

      {/* 清理缓存确认框 */}
      {showClearCacheConfirm && (
        <ConfirmDialog
          title="清理缓存？"
          detail="将清理本地图片与解析缓存，不会退出已登录账号。"
          confirmLabel="清理"
          onCancel={() => setShowClearCacheConfirm(false)}
          onConfirm={() => {
            setShowClearCacheConfirm(false);
            setCacheClearedNotice("已清理");
          }}
        />
      )}

      {/* 快捷子抽屉：收藏 / 历史 / 草稿 / 活动 / 关注与粉丝 / 徽章 / 编辑资料 / 关于（恒定尺寸零压缩抖动） */}
      {subSheet && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/62 p-4"
          onClick={() => setSubSheet(null)}
        >
          <div
            className={`flex flex-col rounded-2xl shadow-2xl overflow-hidden ${
              subSheet === "about"
                ? "w-[420px]"
                : subSheet === "profile_edit"
                  ? "max-h-[84vh] w-[580px]"
                  : "h-[min(640px,82vh)] w-[640px] max-w-full"
            }`}
            style={{
              backgroundColor: "var(--card-surface)",
              border: "0.5px solid var(--hairline)",
            }}
            onClick={(e) => e.stopPropagation()}
          >
            <div className="lindo-hairline-b flex h-12 shrink-0 items-center justify-between px-4">
              <div className="flex items-baseline gap-2">
                <span className="text-[15px] font-semibold" style={{ color: "var(--ink-primary)" }}>
                  {subSheet === "bookmarks"
                    ? "我的书签与收藏"
                    : subSheet === "history"
                      ? "阅读历史"
                      : subSheet === "drafts"
                        ? "草稿箱（云端 + 本地）"
                        : subSheet === "activity"
                          ? "我的社区活动"
                          : subSheet === "follows"
                            ? "关注与粉丝"
                            : subSheet === "badges"
                              ? "社区徽章墙"
                              : subSheet === "profile_edit"
                                ? "个人资料与偏好设置"
                                : subSheet === "diagnostics"
                                  ? "运行日志"
                                  : "关于 Lindo"}
                </span>
                {subSheet === "diagnostics" && (
                  <span className="text-[11.5px] tabular-nums" style={{ color: "var(--ink-tertiary)" }}>
                    {diagEntries.length} 条 · 已脱敏
                  </span>
                )}
              </div>

              <div className="flex items-center gap-1">
                {subSheet === "diagnostics" && (
                  <>
                    <button
                      type="button"
                      onClick={() => {
                        const report = buildDiagReport(
                          updateInfo?.currentVersion || "1.0.45",
                          isAuthorized,
                          diagFilter
                        );
                        navigator.clipboard.writeText(report);
                        setToast("已复制诊断日志");
                      }}
                      className="lindo-interactive flex h-7 items-center gap-1 rounded-md px-2 text-[12px]"
                      style={{ color: "var(--ink-secondary)" }}
                    >
                      <Copy size={13.5} />
                      <span>复制</span>
                    </button>
                    <button
                      type="button"
                      onClick={async () => {
                        try {
                          const savedPath = await exportDiagLogToFile(
                            updateInfo?.currentVersion || "1.0.45",
                            isAuthorized,
                            diagFilter
                          );
                          const base = savedPath.split(/[/\\]/).pop() || savedPath;
                          setToast(`已导出：${base}`);
                        } catch (e: any) {
                          setToast(e?.message || "导出日志失败");
                        }
                      }}
                      className="lindo-interactive flex h-7 items-center gap-1 rounded-md px-2 text-[12px]"
                      style={{ color: "var(--ink-secondary)" }}
                    >
                      <DownloadSimple size={13.5} />
                      <span>导出</span>
                    </button>
                    {diagEntries.length > 0 && (
                      <button
                        type="button"
                        title="清空日志"
                        onClick={() => {
                          clearDiagEntries();
                          setToast("已清空本地日志");
                        }}
                        className="lindo-interactive flex h-7 items-center gap-1 rounded-md px-2 text-[12px]"
                        style={{ color: "var(--ink-tertiary)" }}
                      >
                        <Trash size={13.5} />
                        <span>清空</span>
                      </button>
                    )}
                    <span
                      className="mx-1 h-3.5 w-[0.5px]"
                      style={{ backgroundColor: "var(--hairline)" }}
                    />
                  </>
                )}
                {subSheet === "history" && localHistory.length > 0 && (
                  <button
                    type="button"
                    onClick={async () => {
                      clearLocalTopicHistory();
                      setLocalHistory([]);
                      await clearReadHistoryInRust().catch(() => {});
                      onHistoryCleared();
                      setToast("已清空本地阅读记录");
                    }}
                    className="lindo-interactive rounded-lg px-2.5 py-1 text-[12px]"
                    style={{ color: "var(--ink-secondary)" }}
                  >
                    清空本地记录
                  </button>
                )}
                <button
                  type="button"
                  onClick={() => setSubSheet(null)}
                  className="lindo-interactive rounded-full p-1"
                >
                  <X size={16} />
                </button>
              </div>
            </div>

            {/* 二级切换标签栏 */}
            {subSheet === "activity" && (
              <div className="lindo-hairline-b no-scrollbar flex shrink-0 items-center gap-1.5 overflow-x-auto px-4 py-2">
                {[
                  { key: "1,4,5", label: "全部动态" },
                  { key: "4", label: "发布主帖" },
                  { key: "5", label: "我的回复" },
                  { key: "1", label: "送出的赞" },
                ].map((tab) => (
                  <button
                    key={tab.key}
                    type="button"
                    onClick={() => setActivityFilter(tab.key as any)}
                    className="shrink-0 rounded-full px-3 py-1 text-[12px] font-medium cursor-pointer"
                    style={{
                      backgroundColor:
                        activityFilter === tab.key
                          ? "var(--active-chip-bg)"
                          : "var(--subtle-surface)",
                      color:
                        activityFilter === tab.key
                          ? "var(--active-chip-text)"
                          : "var(--ink-secondary)",
                    }}
                  >
                    {tab.label}
                  </button>
                ))}
              </div>
            )}

            {subSheet === "follows" && (
              <div className="lindo-hairline-b flex shrink-0 items-center gap-2 px-4 py-2">
                {[
                  { key: "following", label: "正在关注" },
                  { key: "followers", label: "我的粉丝" },
                ].map((tab) => (
                  <button
                    key={tab.key}
                    type="button"
                    onClick={() => setFollowTab(tab.key as any)}
                    className="rounded-full px-3.5 py-1 text-[12px] font-medium cursor-pointer"
                    style={{
                      backgroundColor:
                        followTab === tab.key ? "var(--active-chip-bg)" : "var(--subtle-surface)",
                      color:
                        followTab === tab.key
                          ? "var(--active-chip-text)"
                          : "var(--ink-secondary)",
                    }}
                  >
                    {tab.label}
                  </button>
                ))}
              </div>
            )}

            {subSheet === "badges" && isAuthorized && (
              <div className="lindo-hairline-b flex shrink-0 items-center gap-2 px-4 py-2">
                {[
                  { key: "mine", label: "已获徽章" },
                  { key: "site", label: "全站徽章" },
                ].map((tab) => (
                  <button
                    key={tab.key}
                    type="button"
                    onClick={() => setBadgeTab(tab.key as any)}
                    className="rounded-full px-3.5 py-1 text-[12px] font-medium cursor-pointer"
                    style={{
                      backgroundColor:
                        badgeTab === tab.key ? "var(--active-chip-bg)" : "var(--subtle-surface)",
                      color:
                        badgeTab === tab.key
                          ? "var(--active-chip-text)"
                          : "var(--ink-secondary)",
                    }}
                  >
                    {tab.label}
                  </button>
                ))}
              </div>
            )}

            <div className="lindo-scroll flex-1 divide-y">
              {subLoading ? (
                subSheet === "badges" ? (
                  <div className="grid grid-cols-2 gap-2.5 p-4 border-b-0">
                    {Array.from({ length: 8 }).map((_, i) => (
                      <div
                        key={i}
                        className="rounded-xl p-3 space-y-2.5"
                        style={{
                          backgroundColor: "var(--subtle-surface)",
                          border: "0.5px solid var(--hairline)",
                        }}
                      >
                        <div className="flex items-center justify-between gap-2">
                          <div className="flex items-center gap-2">
                            <div className="lindo-shimmer h-4 w-4 rounded-full" />
                            <div className="lindo-shimmer h-4 w-24 rounded" />
                          </div>
                          <div className="lindo-shimmer h-3.5 w-12 rounded" />
                        </div>
                        <div className="space-y-1.5">
                          <div className="lindo-shimmer h-3 w-full rounded" />
                          <div className="lindo-shimmer h-3 w-3/4 rounded" />
                        </div>
                      </div>
                    ))}
                  </div>
                ) : (
                  <div>
                    {Array.from({ length: 7 }).map((_, i) => (
                      <div key={i} className="flex items-center gap-3 px-4 py-3.5">
                        <div className="lindo-shimmer h-9 w-9 shrink-0 rounded-full" />
                        <div className="min-w-0 flex-1 space-y-2">
                          <div className="lindo-shimmer h-4 w-3/4 rounded" />
                          <div className="lindo-shimmer h-3 w-2/5 rounded" />
                        </div>
                      </div>
                    ))}
                  </div>
                )
              ) : subSheet === "bookmarks" ? (
                bookmarks.length === 0 ? (
                  <div className="p-10 text-center text-[13px]" style={{ color: "var(--ink-tertiary)" }}>
                    暂无收藏的书签
                  </div>
                ) : (
                  bookmarks.map((bm: any) => (
                    <div
                      key={bm.id}
                      className="flex items-center justify-between gap-2 px-4 py-3"
                    >
                      <button
                        type="button"
                        onClick={() => {
                          setSubSheet(null);
                          onOpenTopicById(
                            Number(bm.topic_id),
                            String(bm.title || bm.fancy_title || "收藏话题"),
                            Number(bm.linked_post_number || bm.post_number || 1)
                          );
                        }}
                        className="min-w-0 flex-1 text-left cursor-pointer"
                      >
                        <div
                          className="truncate text-[13.6px] font-medium"
                          style={{ color: "var(--ink-primary)" }}
                        >
                          {bm.title || "收藏话题"}
                        </div>
                        <div className="mt-0.5 text-[11.5px]" style={{ color: "var(--ink-tertiary)" }}>
                          #{bm.linked_post_number || bm.post_number || 1} 楼
                        </div>
                      </button>
                      <button
                        type="button"
                        title="移除书签"
                        onClick={async () => {
                          try {
                            await deleteBookmarkById(Number(bm.id));
                            setBookmarks((prev) => prev.filter((x) => x.id !== bm.id));
                            setToast("已移除书签");
                          } catch {
                            setToast("移除失败");
                          }
                        }}
                        className="lindo-interactive rounded-full p-1.5"
                        style={{ color: "var(--ink-tertiary)" }}
                      >
                        <Trash size={14} />
                      </button>
                    </div>
                  ))
                )
              ) : subSheet === "history" ? (
                (() => {
                  const mergedMap = new Map<
                    number,
                    {
                      topicId: number;
                      title: string;
                      categoryName?: string;
                      categoryColorHex?: string;
                      authorUsername: string;
                      authorName?: string;
                      authorAvatar: string;
                      replyCount: number;
                      timeIso: string;
                      resumeFloor?: number;
                    }
                  >();

                  // 1. 优先展示本次/本地真实访问记录（含完整标题、作者、分类、时间）
                  for (const loc of localHistory) {
                    mergedMap.set(loc.topicId, {
                      topicId: loc.topicId,
                      title: loc.title,
                      categoryName: loc.categoryName,
                      categoryColorHex: loc.categoryColorHex,
                      authorUsername: loc.authorUsername,
                      authorName: loc.authorName,
                      authorAvatar: loc.authorAvatar,
                      replyCount: loc.replyCount,
                      timeIso: loc.visitedAt,
                      resumeFloor: readFloors[String(loc.topicId)] || undefined,
                    });
                  }

                  // 2. 合并服务端跨设备 `/read.json` 完整阅读历史
                  for (const st of historyTopics) {
                    if (!mergedMap.has(st.id)) {
                      mergedMap.set(st.id, {
                        topicId: st.id,
                        title: st.title,
                        categoryName: st.categoryName,
                        categoryColorHex: st.categoryColorHex,
                        authorUsername: st.originalPoster.username,
                        authorName: st.originalPoster.name,
                        authorAvatar:
                          st.originalPoster.animatedAvatar || st.originalPoster.avatarTemplate,
                        replyCount: st.replyCount,
                        timeIso: st.lastPostedAt || st.createdAt,
                        resumeFloor:
                          readFloors[String(st.id)] || st.lastReadPostNumber || undefined,
                      });
                    }
                  }

                  // 3. 仅补全当前已知真实标题的条目（绝不展示无意义的「话题 #3187333」裸编号！）
                  for (const [tidStr, floor] of Object.entries(readFloors).reverse()) {
                    const tid = Number(tidStr);
                    if (!tid || mergedMap.has(tid)) continue;
                    const matched = knownTopics.find((t) => t.id === tid);
                    if (matched && matched.title && !/^话题\s*#\d+$/.test(matched.title)) {
                      mergedMap.set(tid, {
                        topicId: tid,
                        title: matched.title,
                        categoryName: matched.categoryName,
                        categoryColorHex: matched.categoryColorHex,
                        authorUsername: matched.originalPoster.username,
                        authorName: matched.originalPoster.name,
                        authorAvatar:
                          matched.originalPoster.animatedAvatar ||
                          matched.originalPoster.avatarTemplate,
                        replyCount: matched.replyCount,
                        timeIso: matched.lastPostedAt || matched.createdAt,
                        resumeFloor: floor,
                      });
                    }
                  }

                  const displayHistory = Array.from(mergedMap.values());
                  if (displayHistory.length === 0) {
                    return (
                      <div
                        className="flex h-full flex-col items-center justify-center p-10 text-center text-[13px]"
                        style={{ color: "var(--ink-tertiary)" }}
                      >
                        暂无浏览记录
                      </div>
                    );
                  }

                  return (
                    <>
                      {displayHistory.map((h) => {
                        const cv = resolveCategoryVisual(
                          h.categoryName || "",
                          h.categoryColorHex
                        );
                        return (
                          <button
                            key={h.topicId}
                            type="button"
                            onClick={() => {
                              setSubSheet(null);
                              onOpenTopicById(h.topicId, h.title, h.resumeFloor);
                            }}
                            className="lindo-interactive flex w-full items-start gap-3 px-4 py-3 text-left"
                          >
                            <AvatarWithFlair
                              username={h.authorUsername}
                              avatarTemplate={h.authorAvatar}
                              sizePx={36}
                              className="mt-0.5"
                            />
                            <div className="min-w-0 flex-1">
                              <div className="flex items-baseline justify-between gap-3">
                                <span
                                  className="line-clamp-1 text-[13.8px] font-semibold leading-snug"
                                  style={{ color: "var(--ink-primary)" }}
                                >
                                  {h.title}
                                </span>
                                {h.timeIso && (
                                  <span
                                    className="shrink-0 text-[11px] tabular-nums"
                                    style={{ color: "var(--ink-tertiary)" }}
                                  >
                                    {formatRelativeTime(h.timeIso)}
                                  </span>
                                )}
                              </div>
                              <div
                                className="mt-1 flex flex-wrap items-center gap-1.5 text-[11.5px]"
                                style={{ color: "var(--ink-secondary)" }}
                              >
                                {h.categoryName && (
                                  <>
                                    <span className="inline-flex items-center gap-1">
                                      <span
                                        className="h-1.5 w-1.5 rounded-full"
                                        style={{ backgroundColor: cv.dotColor }}
                                      />
                                      <span>{h.categoryName}</span>
                                    </span>
                                    <span style={{ color: "var(--ink-tertiary)" }}>·</span>
                                  </>
                                )}
                                <span>{h.authorName || `@${h.authorUsername}`}</span>
                                {h.replyCount > 0 && (
                                  <>
                                    <span style={{ color: "var(--ink-tertiary)" }}>·</span>
                                    <span style={{ color: "var(--ink-tertiary)" }}>
                                      {formatCompactCount(h.replyCount)} 条回复
                                    </span>
                                  </>
                                )}
                              </div>
                            </div>
                          </button>
                        );
                      })}

                      {isAuthorized && historyHasMore && (
                        <div className="py-3 text-center">
                          <button
                            type="button"
                            disabled={loadingMoreHistory}
                            onClick={async () => {
                              if (loadingMoreHistory) return;
                              const nextP = historyPage + 1;
                              setLoadingMoreHistory(true);
                              try {
                                const more = await fetchReadHistoryTopics(nextP);
                                setHistoryTopics((prev) => {
                                  const seen = new Set(prev.map((x) => x.id));
                                  return [
                                    ...prev,
                                    ...more.topics.filter((x) => !seen.has(x.id)),
                                  ];
                                });
                                setHistoryPage(nextP);
                                setHistoryHasMore(more.hasMore);
                              } catch {
                                setHistoryHasMore(false);
                              } finally {
                                setLoadingMoreHistory(false);
                              }
                            }}
                            className="lindo-interactive rounded-full px-4 py-1 text-[12px] font-medium"
                            style={{ color: "var(--active-chip-text)" }}
                          >
                            {loadingMoreHistory ? "正在加载..." : "加载更早浏览记录"}
                          </button>
                        </div>
                      )}
                    </>
                  );
                })()
              ) : subSheet === "drafts" ? (
                localDraftList.length === 0 && cloudDrafts.length === 0 ? (
                  <div className="p-10 text-center text-[13px]" style={{ color: "var(--ink-tertiary)" }}>
                    暂无草稿记录
                  </div>
                ) : (
                  <>
                    {localDraftList.map((d) => (
                      <div
                        key={`local_${d.key}`}
                        className="flex items-center justify-between gap-3 px-4 py-3"
                      >
                        <button
                          type="button"
                          onClick={() => {
                            setSubSheet(null);
                            onResumeComposerDraft({
                              action: d.action,
                              topicId: d.topicId ?? undefined,
                              postId: d.postId ?? undefined,
                              replyToPostNumber: d.replyToPostNumber ?? undefined,
                              initialCategoryId: d.categoryId ?? undefined,
                              initialTitle: d.title,
                              initialRaw: d.raw,
                              initialTags: d.tags,
                              draftKeyOverride: d.key,
                            });
                          }}
                          className="min-w-0 flex-1 text-left cursor-pointer"
                        >
                          <div className="flex items-center gap-2">
                            <span
                              className="rounded px-1.5 py-[1px] text-[10px] font-semibold"
                              style={{
                                backgroundColor: "var(--tonal-pill)",
                                color: "var(--ink-secondary)",
                              }}
                            >
                              本地
                            </span>
                            <span
                              className="truncate text-[13.5px] font-semibold"
                              style={{ color: "var(--ink-primary)" }}
                            >
                              {d.title || (d.topicId ? `回复话题 #${d.topicId}` : "未命名新话题")}
                            </span>
                          </div>
                          <p
                            className="mt-1 line-clamp-2 text-[12px]"
                            style={{ color: "var(--ink-secondary)" }}
                          >
                            {d.raw}
                          </p>
                        </button>
                        <button
                          type="button"
                          title="删除本地草稿"
                          onClick={async () => {
                            await deleteLocalDraftInRust(d.key).catch(() => {});
                            onDraftsChanged();
                          }}
                          className="lindo-interactive rounded-full p-1.5"
                          style={{ color: "var(--ink-tertiary)" }}
                        >
                          <Trash size={14} />
                        </button>
                      </div>
                    ))}

                    {cloudDrafts.map((cd) => (
                      <div
                        key={`cloud_${cd.draftKey}`}
                        className="flex items-center justify-between gap-3 px-4 py-3"
                      >
                        <button
                          type="button"
                          onClick={() => {
                            setSubSheet(null);
                            onResumeComposerDraft({
                              action: cd.action,
                              topicId: cd.topicId ?? undefined,
                              postId: cd.postId ?? undefined,
                              replyToPostNumber: cd.replyToPostNumber ?? undefined,
                              initialCategoryId: cd.categoryId ?? undefined,
                              initialTitle: cd.title,
                              initialRaw: cd.raw,
                              initialTags: cd.tags,
                            });
                          }}
                          className="min-w-0 flex-1 text-left cursor-pointer"
                        >
                          <div className="flex items-center gap-2">
                            <span
                              className="rounded px-1.5 py-[1px] text-[10px] font-semibold"
                              style={{
                                backgroundColor: "var(--active-chip-bg)",
                                color: "var(--brand-gold)",
                              }}
                            >
                              云端
                            </span>
                            <span
                              className="truncate text-[13.5px] font-semibold"
                              style={{ color: "var(--ink-primary)" }}
                            >
                              {cd.title ||
                                (cd.topicId ? `回复话题 #${cd.topicId}` : "云端新话题草稿")}
                            </span>
                          </div>
                          <p
                            className="mt-1 line-clamp-2 text-[12px]"
                            style={{ color: "var(--ink-secondary)" }}
                          >
                            {cd.raw}
                          </p>
                        </button>
                        <button
                          type="button"
                          title="删除云端草稿"
                          onClick={async () => {
                            try {
                              await deleteCloudDraft(cd.draftKey, cd.sequence);
                              setCloudDrafts((prev) =>
                                prev.filter((x) => x.draftKey !== cd.draftKey)
                              );
                            } catch {
                              setToast("删除云端草稿失败");
                            }
                          }}
                          className="lindo-interactive rounded-full p-1.5"
                          style={{ color: "var(--ink-tertiary)" }}
                        >
                          <Trash size={14} />
                        </button>
                      </div>
                    ))}
                  </>
                )
              ) : subSheet === "activity" ? (
                activities.length === 0 ? (
                  <div className="p-10 text-center text-[13px]" style={{ color: "var(--ink-tertiary)" }}>
                    暂无相关社区动态
                  </div>
                ) : (
                  activities.map((act: any, idx: number) => (
                    <button
                      key={`${act.topic_id}_${act.post_number}_${idx}`}
                      type="button"
                      onClick={() => {
                        setSubSheet(null);
                        onOpenTopicById(
                          Number(act.topic_id),
                          String(act.title || "社区动态"),
                          Number(act.post_number || 1)
                        );
                      }}
                      className="lindo-interactive flex w-full flex-col px-4 py-3 text-left"
                    >
                      <div className="flex items-center justify-between gap-2">
                        <span
                          className="truncate text-[13.6px] font-semibold"
                          style={{ color: "var(--ink-primary)" }}
                        >
                          {act.title}
                        </span>
                        <span
                          className="shrink-0 text-[11px] tabular-nums"
                          style={{ color: "var(--ink-tertiary)" }}
                        >
                          {formatRelativeTime(act.created_at)}
                        </span>
                      </div>
                      {act.excerpt && (
                        <span
                          className="mt-1 line-clamp-2 text-[12px]"
                          style={{ color: "var(--ink-secondary)" }}
                          dangerouslySetInnerHTML={{ __html: act.excerpt }}
                        />
                      )}
                    </button>
                  ))
                )
              ) : subSheet === "follows" ? (
                followUsers.length === 0 ? (
                  <div className="p-10 text-center text-[13px]" style={{ color: "var(--ink-tertiary)" }}>
                    {followTab === "following" ? "暂未关注任何社区成员" : "暂无粉丝记录"}
                  </div>
                ) : (
                  followUsers.map((fu) => (
                    <div
                      key={fu.username}
                      className="flex items-center justify-between gap-3 px-4 py-3"
                    >
                      <button
                        type="button"
                        onClick={() => {
                          setSubSheet(null);
                          onInspectUser(fu.username);
                        }}
                        className="flex min-w-0 flex-1 items-center gap-3 text-left cursor-pointer"
                      >
                        <img
                          src={resolveAvatarUrl(fu.animatedAvatar || fu.avatarTemplate, 96)}
                          alt={fu.username}
                          className={getAvatarClassName(
                            "h-9 w-9 shrink-0",
                            fu.username,
                            undefined,
                            fu.avatarTemplate
                          )}
                        />
                        <div className="min-w-0 flex-1">
                          <div
                            className="truncate text-[13.5px] font-semibold"
                            style={{ color: "var(--ink-primary)" }}
                          >
                            {fu.displayName}
                            <span
                              className="ml-1.5 text-[11.5px] font-normal"
                              style={{ color: "var(--ink-tertiary)" }}
                            >
                              @{fu.username}
                            </span>
                          </div>
                          {fu.bio && (
                            <div
                              className="mt-0.5 truncate text-[11.5px]"
                              style={{ color: "var(--ink-secondary)" }}
                            >
                              {fu.bio}
                            </div>
                          )}
                        </div>
                      </button>

                      <button
                        type="button"
                        onClick={async () => {
                          const nextState = !fu.isFollowing;
                          try {
                            await toggleFollowUser(fu.username, nextState);
                            setFollowUsers((prev) =>
                              prev.map((x) =>
                                x.username === fu.username
                                  ? { ...x, isFollowing: nextState }
                                  : x
                              )
                            );
                          } catch {
                            setToast("关注状态更新失败");
                          }
                        }}
                        className="shrink-0 rounded-full px-3 py-1 text-[12px] font-semibold cursor-pointer"
                        style={{
                          backgroundColor: fu.isFollowing
                            ? "var(--subtle-surface)"
                            : "var(--publish-btn-bg)",
                          border: "0.5px solid var(--hairline)",
                          color: fu.isFollowing
                            ? "var(--ink-secondary)"
                            : "var(--publish-btn-fg)",
                        }}
                      >
                        {fu.isFollowing ? "已关注" : "回关"}
                      </button>
                    </div>
                  ))
                )
              ) : subSheet === "badges" ? (
                badges.length === 0 ? (
                  <div className="p-10 text-center text-[13px]" style={{ color: "var(--ink-tertiary)" }}>
                    暂无徽章数据
                  </div>
                ) : (
                  <div className="grid grid-cols-2 gap-2.5 p-4 border-b-0">
                    {badges.map((b: any) => {
                      const isCurrentTitle =
                        Boolean(currentUser?.userTitle) && currentUser?.userTitle === b.name;
                      const canWearTitle = Boolean(badgeTab === "mine" && b.allow_title && currentUser);
                      const mappedBadge: GrantedBadge = {
                        id: Number(b.id ?? 0),
                        name: String(b.name || ""),
                        description: String(b.description || ""),
                        longDescription: b.long_description ? String(b.long_description) : undefined,
                        badgeTypeId: Number(b.badge_type_id ?? 3),
                        icon: b.icon ?? null,
                        imageUrl: b.image_url ?? b.image ?? null,
                        grantCount: Number(b.grant_count ?? 0),
                        allowTitle: Boolean(b.allow_title),
                        count: Number(b.count ?? 1),
                        isEarned: badgeTab === "mine",
                      };
                      return (
                        <div
                          key={`${b.id}_${b.user_badge_id || 0}`}
                          onClick={() => onInspectBadge?.(mappedBadge)}
                          className="lindo-interactive group flex flex-col justify-between rounded-xl p-3 cursor-pointer"
                          style={{
                            backgroundColor: "var(--subtle-surface)",
                            border: isCurrentTitle
                              ? "0.5px solid color-mix(in srgb, var(--brand-gold) 55%, var(--hairline))"
                              : "0.5px solid var(--hairline)",
                          }}
                        >
                          {/* 顶栏：左侧徽章图标+名称，右侧紧凑「佩戴中 / 设为头衔」胶囊 + 获取人数（绝不撑高底栏！） */}
                          <div className="flex items-center justify-between gap-2">
                            <span
                              className="flex min-w-0 items-center gap-1.5 text-[13.5px] font-bold"
                              style={{ color: "var(--ink-primary)" }}
                            >
                              <OfficialBadgeIcon badge={mappedBadge} size={16} />
                              <span className="truncate">{b.name}</span>
                            </span>

                            <div className="flex shrink-0 items-center gap-1.5">
                              {canWearTitle && (
                                <button
                                  type="button"
                                  onClick={async (e) => {
                                    e.stopPropagation();
                                    if (!currentUser) return;
                                    try {
                                      await updateUserProfileInfo(currentUser.username, {
                                        title: isCurrentTitle ? "" : String(b.name),
                                      });
                                      await refreshCurrentUserSession().catch(() => {});
                                      onProfileUpdated();
                                      setToast(
                                        isCurrentTitle ? "已取消佩戴头衔" : `已佩戴头衔「${b.name}」`
                                      );
                                    } catch {
                                      setToast("设置头衔失败");
                                    }
                                  }}
                                  className="rounded-full px-2 py-[1px] text-[10.5px] font-semibold transition-opacity cursor-pointer"
                                  style={{
                                    backgroundColor: isCurrentTitle
                                      ? "var(--active-chip-bg)"
                                      : "var(--card-surface)",
                                    color: isCurrentTitle
                                      ? "var(--active-chip-text)"
                                      : "var(--ink-secondary)",
                                    border: "0.5px solid var(--hairline)",
                                  }}
                                >
                                  {isCurrentTitle ? "佩戴中" : "佩戴头衔"}
                                </button>
                              )}
                              {b.grant_count > 0 && (
                                <span
                                  className="text-[10.5px] tabular-nums"
                                  style={{ color: "var(--ink-tertiary)" }}
                                >
                                  {formatCompactCount(b.grant_count)} 人
                                </span>
                              )}
                            </div>
                          </div>

                          <div
                            className="mt-1.5 line-clamp-2 min-h-[34px] text-[11.5px] leading-[1.45]"
                            style={{ color: "var(--ink-secondary)" }}
                            dangerouslySetInnerHTML={{ __html: b.description || "社区荣誉徽章" }}
                          />
                        </div>
                      );
                    })}
                  </div>
                )
              ) : subSheet === "profile_edit" ? (
                <div className="space-y-3.5 p-5">
                  <div>
                    <label className="text-[12px] font-semibold" style={{ color: "var(--ink-secondary)" }}>
                      显示昵称
                    </label>
                    <input
                      type="text"
                      value={editDisplayName}
                      onChange={(e) => setEditDisplayName(e.target.value)}
                      className="mt-1 h-9 w-full rounded-lg bg-transparent px-3 text-[13.5px] outline-none"
                      style={{
                        backgroundColor: "var(--subtle-surface)",
                        border: "0.5px solid var(--hairline)",
                        color: "var(--ink-primary)",
                      }}
                    />
                  </div>

                  {acctSnap && acctSnap.availableTitles.length > 0 && (
                    <div>
                      <label
                        className="text-[12px] font-semibold"
                        style={{ color: "var(--ink-secondary)" }}
                      >
                        社区头衔
                      </label>
                      <div className="mt-1.5 flex flex-wrap gap-1.5">
                        <button
                          type="button"
                          onClick={() => setEditTitle("")}
                          className="rounded-full px-3 py-1 text-[12px] cursor-pointer"
                          style={{
                            backgroundColor: !editTitle
                              ? "var(--active-chip-bg)"
                              : "var(--subtle-surface)",
                            color: !editTitle ? "var(--active-chip-text)" : "var(--ink-secondary)",
                          }}
                        >
                          无头衔
                        </button>
                        {acctSnap.availableTitles.map((tl) => (
                          <button
                            key={tl}
                            type="button"
                            onClick={() => setEditTitle(tl)}
                            className="rounded-full px-3 py-1 text-[12px] font-medium cursor-pointer"
                            style={{
                              backgroundColor:
                                editTitle === tl
                                  ? "var(--active-chip-bg)"
                                  : "var(--subtle-surface)",
                              color:
                                editTitle === tl
                                  ? "var(--active-chip-text)"
                                  : "var(--ink-primary)",
                            }}
                          >
                            {tl}
                          </button>
                        ))}
                      </div>
                    </div>
                  )}

                  <div>
                    <label className="text-[12px] font-semibold" style={{ color: "var(--ink-secondary)" }}>
                      个人简介（支持 Markdown）
                    </label>
                    <textarea
                      rows={3}
                      value={editBio}
                      onChange={(e) => setEditBio(e.target.value)}
                      className="mt-1 w-full rounded-lg bg-transparent p-3 text-[13px] outline-none resize-none"
                      style={{
                        backgroundColor: "var(--subtle-surface)",
                        border: "0.5px solid var(--hairline)",
                        color: "var(--ink-primary)",
                      }}
                    />
                  </div>

                  <div className="grid grid-cols-2 gap-3">
                    <div>
                      <label
                        className="text-[12px] font-semibold"
                        style={{ color: "var(--ink-secondary)" }}
                      >
                        所在地
                      </label>
                      <input
                        type="text"
                        value={editLocation}
                        onChange={(e) => setEditLocation(e.target.value)}
                        className="mt-1 h-9 w-full rounded-lg bg-transparent px-3 text-[13px] outline-none"
                        style={{
                          backgroundColor: "var(--subtle-surface)",
                          border: "0.5px solid var(--hairline)",
                          color: "var(--ink-primary)",
                        }}
                      />
                    </div>
                    <div>
                      <label
                        className="text-[12px] font-semibold"
                        style={{ color: "var(--ink-secondary)" }}
                      >
                        个人网站
                      </label>
                      <input
                        type="text"
                        value={editWebsite}
                        onChange={(e) => setEditWebsite(e.target.value)}
                        className="mt-1 h-9 w-full rounded-lg bg-transparent px-3 text-[13px] outline-none"
                        style={{
                          backgroundColor: "var(--subtle-surface)",
                          border: "0.5px solid var(--hairline)",
                          color: "var(--ink-primary)",
                        }}
                      />
                    </div>
                  </div>

                  <div className="flex items-center justify-between pt-1">
                    <span className="text-[13px]" style={{ color: "var(--ink-primary)" }}>
                      隐藏个人公开资料与在线状态
                    </span>
                    <input
                      type="checkbox"
                      checked={editHidePresence}
                      onChange={(e) => setEditHidePresence(e.target.checked)}
                      className="accent-amber-400 cursor-pointer"
                    />
                  </div>

                  <div className="flex items-center justify-between">
                    <span className="text-[13px]" style={{ color: "var(--ink-primary)" }}>
                      允许其他社区成员向我发送私信
                    </span>
                    <input
                      type="checkbox"
                      checked={editAllowPm}
                      onChange={(e) => setEditAllowPm(e.target.checked)}
                      className="accent-amber-400 cursor-pointer"
                    />
                  </div>

                  <div className="flex items-center justify-between pt-3 lindo-hairline-t">
                    <button
                      type="button"
                      onClick={() => {
                        const uname = currentUser?.username;
                        setSubSheet(null);
                        onOpenInAppUrl(
                          uname
                            ? `https://linux.do/u/${encodeURIComponent(uname)}/preferences/security`
                            : "https://linux.do/my/preferences/security"
                        );
                      }}
                      className="text-[12px] hover:underline cursor-pointer"
                      style={{ color: "var(--ink-secondary)" }}
                    >
                      打开高级安全设置（2FA / 通行密钥 / 头像）→
                    </button>

                    <button
                      type="button"
                      disabled={savingProfile || !currentUser}
                      onClick={async () => {
                        if (!currentUser) return;
                        setSavingProfile(true);
                        try {
                          await updateUserProfileInfo(currentUser.username, {
                            displayName: editDisplayName,
                            title: editTitle,
                            bioRaw: editBio,
                            location: editLocation,
                            website: editWebsite,
                            hidePresence: editHidePresence,
                            allowPrivateMessages: editAllowPm,
                          });
                          await refreshCurrentUserSession().catch(() => {});
                          onProfileUpdated();
                          setToast("个人资料已保存");
                          setSubSheet(null);
                        } catch (e: any) {
                          setToast(e?.message || "保存失败");
                        } finally {
                          setSavingProfile(false);
                        }
                      }}
                      className="rounded-full px-5 py-1.5 text-[13px] font-bold cursor-pointer disabled:opacity-50"
                      style={{
                        backgroundColor: "var(--publish-btn-bg)",
                        color: "var(--publish-btn-fg)",
                      }}
                    >
                      {savingProfile ? "保存中..." : "保存修改"}
                    </button>
                  </div>
                </div>
              ) : subSheet === "diagnostics" ? (
                <div className="flex h-full flex-col">
                  {/* 克制极简底线标签栏（与首页 Feed Tab 视觉语言 1:1 统一） */}
                  <div className="lindo-hairline-b flex h-[38px] shrink-0 items-center justify-between px-4">
                    <div className="flex items-center gap-5">
                      {(
                        [
                          ["ALL", "全部"],
                          ["NETWORK", "网络"],
                          ["AUTH", "登录与验证"],
                          ["ERROR", "仅告警"],
                          ["SYSTEM", "系统"],
                        ] as const
                      ).map(([k, label]) => {
                        const active = diagFilter === k;
                        return (
                          <button
                            key={k}
                            type="button"
                            onClick={() => setDiagFilter(k)}
                            className="relative flex h-[38px] items-center text-[13px] transition-colors cursor-pointer"
                            style={{
                              color: active ? "var(--ink-primary)" : "var(--ink-secondary)",
                              fontWeight: active ? 600 : 400,
                            }}
                          >
                            <span>{label}</span>
                            {active && (
                              <span
                                className="absolute inset-x-0 bottom-0 mx-auto h-[2px] w-[14px] rounded-full"
                                style={{ backgroundColor: "var(--tab-indicator)" }}
                              />
                            )}
                          </button>
                        );
                      })}
                    </div>

                    <span
                      className="inline-flex items-center gap-1.5 text-[11.5px]"
                      style={{ color: "var(--ink-tertiary)" }}
                    >
                      <span
                        className="h-[5.5px] w-[5.5px] rounded-full"
                        style={{
                          backgroundColor: isAuthorized ? "var(--brand-gold)" : "var(--ink-tertiary)",
                        }}
                      />
                      <span>{isAuthorized ? "已登录" : "访客"}</span>
                    </span>
                  </div>

                  <div className="lindo-scroll flex-1 min-h-0">
                    {diagEntries.length === 0 ? (
                      <div
                        className="flex h-full flex-col items-center justify-center gap-2 py-20 text-[13px]"
                        style={{ color: "var(--ink-tertiary)" }}
                      >
                        <MascotExpression expression="sleepy" sizePx={42} className="opacity-80" />
                        <span>当前筛选下暂无记录</span>
                      </div>
                    ) : (
                      <div>
                        {diagEntries.map((entry) => {
                          const isExpanded = expandedDiagId === entry.id;
                          const dotColor =
                            entry.level === "ERROR"
                              ? "var(--error-fg)"
                              : entry.level === "WARN"
                                ? "#F59E0B"
                                : "color-mix(in srgb, var(--ink-tertiary) 58%, transparent)";
                          const categoryLabel =
                            entry.category === "NETWORK"
                              ? "网络"
                              : entry.category === "AUTH"
                                ? "认证"
                                : entry.category === "ERROR"
                                  ? "运行"
                                  : "系统";
                          let timeText = entry.timestampIso;
                          try {
                            const d = new Date(entry.timestampIso);
                            if (!Number.isNaN(d.getTime())) {
                              const pad = (n: number) => String(n).padStart(2, "0");
                              timeText = `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(
                                d.getHours()
                              )}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
                            }
                          } catch {}
                          return (
                            <div
                              key={entry.id}
                              onClick={() =>
                                entry.detail
                                  ? setExpandedDiagId((prev) =>
                                      prev === entry.id ? null : entry.id
                                    )
                                  : undefined
                              }
                              className={`lindo-hairline-b px-4 py-2.5 transition-colors ${
                                entry.detail ? "lindo-interactive" : ""
                              }`}
                            >
                              <div className="flex items-start justify-between gap-3">
                                <div className="flex min-w-0 flex-1 items-start gap-2.5">
                                  <span
                                    className="mt-[6.5px] h-[6px] w-[6px] shrink-0 rounded-full"
                                    style={{ backgroundColor: dotColor }}
                                  />
                                  <span
                                    className="mt-[1px] shrink-0 text-[11.5px] font-medium"
                                    style={{ color: "var(--ink-tertiary)" }}
                                  >
                                    {categoryLabel}
                                  </span>
                                  <span
                                    className="lindo-selectable min-w-0 flex-1 text-[12.8px] leading-[1.45] break-all"
                                    style={{
                                      color:
                                        entry.level === "ERROR"
                                          ? "var(--error-fg)"
                                          : "var(--ink-primary)",
                                    }}
                                  >
                                    {entry.summary}
                                  </span>
                                </div>

                                <div
                                  className="flex shrink-0 items-center gap-1 pt-[1px] text-[11px] tabular-nums"
                                  style={{
                                    color: "var(--ink-tertiary)",
                                    fontFamily: "var(--font-mono)",
                                  }}
                                >
                                  <span>{timeText}</span>
                                  {entry.detail &&
                                    (isExpanded ? (
                                      <CaretDown size={11} />
                                    ) : (
                                      <CaretRight size={11} />
                                    ))}
                                </div>
                              </div>

                              {entry.detail && isExpanded && (
                                <pre
                                  className="lindo-selectable mt-2 ml-[42px] overflow-x-auto rounded-lg px-3 py-2 text-[11.2px] leading-relaxed whitespace-pre-wrap break-all"
                                  style={{
                                    fontFamily: "var(--font-mono)",
                                    backgroundColor: "var(--subtle-surface)",
                                    border: "0.5px solid var(--hairline)",
                                    color: "var(--ink-secondary)",
                                  }}
                                >
                                  {entry.detail}
                                </pre>
                              )}
                            </div>
                          );
                        })}
                      </div>
                    )}
                  </div>
                </div>
              ) : (
                <div className="space-y-3 p-6 text-center">
                  <img src="/brand/lindo-mark.svg" alt="Lindo" className="mx-auto h-14 w-14" />
                  <div className="text-[17px] font-bold" style={{ color: "var(--ink-primary)" }}>
                    Lindo Desktop v{updateInfo?.currentVersion || "1.0.45"}
                  </div>
                  <p className="text-[12.5px]" style={{ color: "var(--ink-secondary)" }}>
                    LINUX DO 第三方原生桌面客户端 · 真诚 · 友善 · 团结 · 专业
                  </p>
                  <div className="flex flex-wrap items-center justify-center gap-2 pt-2">
                    <button
                      type="button"
                      onClick={() => {
                        if (updateInfo?.hasUpdate && onOpenUpdateModal) {
                          setSubSheet(null);
                          onOpenUpdateModal();
                        } else {
                          onCheckUpdate?.();
                        }
                      }}
                      className="rounded-full px-4 py-1.5 text-[12.5px] font-semibold cursor-pointer"
                      style={{
                        backgroundColor: updateInfo?.hasUpdate
                          ? "var(--publish-btn-bg)"
                          : "var(--subtle-surface)",
                        border: "0.5px solid var(--hairline)",
                        color: updateInfo?.hasUpdate
                          ? "var(--publish-btn-fg)"
                          : "var(--ink-primary)",
                      }}
                    >
                      {checkingUpdate
                        ? "检查中..."
                        : updateInfo?.hasUpdate
                          ? `升级至 v${updateInfo.latestVersion}`
                          : "检查更新"}
                    </button>
                    <button
                      type="button"
                      onClick={() => setSubSheet("diagnostics")}
                      className="lindo-interactive rounded-full px-4 py-1.5 text-[12.5px]"
                      style={{
                        border: "0.5px solid var(--hairline)",
                        color: "var(--ink-primary)",
                      }}
                    >
                      诊断与导出日志
                    </button>
                    <button
                      type="button"
                      onClick={() => openUrl("https://github.com/lucaslus/LinDo")}
                      className="lindo-interactive rounded-full px-4 py-1.5 text-[12.5px]"
                      style={{
                        border: "0.5px solid var(--hairline)",
                        color: "var(--ink-primary)",
                      }}
                    >
                      GitHub 主页
                    </button>
                  </div>
                </div>
              )}
            </div>
          </div>
        </div>
      )}

      {toast && (
        <div
          className="pointer-events-none fixed bottom-8 left-1/2 z-[70] -translate-x-1/2 rounded-full px-4 py-1.5 text-[12.5px] font-medium shadow-xl"
          style={{
            backgroundColor: "var(--subtle-surface)",
            border: "0.5px solid var(--hairline)",
            color: "var(--ink-primary)",
          }}
        >
          {toast}
        </div>
      )}
    </div>
  );
};

const GroupHeader: React.FC<{ title: string }> = ({ title }) => (
  <div
    className="px-5 pt-4 pb-1.5 text-[12px] font-semibold"
    style={{ color: "var(--ink-tertiary)" }}
  >
    {title}
  </div>
);

const SettingRow: React.FC<{
  icon: React.ReactNode;
  title: string;
  value?: string;
  badgeText?: string;
  leadingValueDot?: string;
  expanded?: boolean;
  hideDivider?: boolean;
  onClick: () => void;
}> = ({ icon, title, value, badgeText, leadingValueDot, expanded, hideDivider, onClick }) => (
  <div>
    <button
      type="button"
      onClick={onClick}
      className="lindo-interactive flex min-h-[48px] w-full items-center justify-between py-2.5 px-5 text-left"
    >
      <span className="flex items-center gap-3 text-[14px]" style={{ color: "var(--ink-primary)" }}>
        <span style={{ color: "var(--ink-secondary)" }}>{icon}</span>
        <span>{title}</span>
      </span>
      <span
        className="flex items-center gap-2 text-[13px]"
        style={{ color: "var(--ink-tertiary)" }}
      >
        {badgeText && (
          <span
            className="rounded-full px-2.5 py-0.5 text-[11px] font-semibold"
            style={{
              backgroundColor: "var(--active-chip-bg)",
              color: "var(--active-chip-text)",
            }}
          >
            {badgeText}
          </span>
        )}
        {leadingValueDot && (
          <span className="h-3 w-3 rounded-full" style={{ backgroundColor: leadingValueDot }} />
        )}
        {value && <span style={{ color: "var(--ink-secondary)" }}>{value}</span>}
        {expanded ? <CaretDown size={14} /> : <CaretRight size={14} />}
      </span>
    </button>
    {!hideDivider && !expanded && (
      <div className="mx-5 h-[0.5px]" style={{ backgroundColor: "var(--hairline)" }} />
    )}
  </div>
);

const ToggleSettingRow: React.FC<{
  icon: React.ReactNode;
  title: string;
  checked: boolean;
  onChange: (next: boolean) => void;
}> = ({ icon, title, checked, onChange }) => (
  <div>
    <div className="flex h-[48px] w-full items-center justify-between px-5">
      <span className="flex items-center gap-3 text-[14px]" style={{ color: "var(--ink-primary)" }}>
        <span style={{ color: "var(--ink-secondary)" }}>{icon}</span>
        <span>{title}</span>
      </span>
      <button
        type="button"
        onClick={() => onChange(!checked)}
        className="h-6 w-11 rounded-full p-0.5 transition-colors cursor-pointer"
        style={{
          backgroundColor: checked ? "var(--brand-gold)" : "var(--tonal-pill)",
        }}
      >
        <div
          className="h-5 w-5 rounded-full bg-white transition-transform"
          style={{
            transform: checked ? "translateX(20px)" : "translateX(0)",
          }}
        />
      </button>
    </div>
    <div className="mx-5 h-[0.5px]" style={{ backgroundColor: "var(--hairline)" }} />
  </div>
);

/* ==================== 4. 账号管理 / 切换账号 / 添加账号 ==================== */
export const AuthModal: React.FC<{
  isAuthorized: boolean;
  isDark: boolean;
  currentUser: UserProfile | null;
  savedAccounts: SavedAccountEntry[];
  statusHint?: string | null;
  initialAdding?: boolean;
  onWebLogin: () => void;
  onAddAccountWebLogin: () => void;
  onBrowserOneClick: () => void;
  onSwitchAccount: (username: string) => void;
  onRemoveAccount: (username: string) => void;
  onManualImported: () => void;
  onLogout: () => void;
  onClose: () => void;
}> = ({
  isAuthorized,
  isDark,
  currentUser,
  savedAccounts,
  statusHint,
  initialAdding,
  onWebLogin,
  onAddAccountWebLogin,
  onBrowserOneClick,
  onSwitchAccount,
  onRemoveAccount,
  onManualImported,
  onLogout,
  onClose,
}) => {
  const [managing, setManaging] = useState(false);
  const [adding, setAdding] = useState(Boolean(initialAdding));
  const [removingUsername, setRemovingUsername] = useState<string | null>(null);
  const [manualExpanded, setManualExpanded] = useState(false);
  const [manualInput, setManualInput] = useState("");
  const [manualBusy, setManualBusy] = useState(false);
  const [manualError, setManualError] = useState<string | null>(null);

  useEffect(() => {
    if (initialAdding) {
      setAdding(true);
      setManaging(false);
    }
  }, [initialAdding]);

  const effectiveAccounts = useMemo(() => {
    const list = [...savedAccounts];
    if (
      isAuthorized &&
      currentUser &&
      !list.some((a) => a.profile.username.toLowerCase() === currentUser.username.toLowerCase())
    ) {
      list.unshift({
        profile: currentUser,
        cookies: "",
        needsLogin: false,
        readFloors: {},
      });
    }
    return list;
  }, [savedAccounts, isAuthorized, currentUser]);

  const showWelcomeBody = adding || (effectiveAccounts.length === 0 && !isAuthorized);

  const handleBack = () => {
    if (adding) {
      setAdding(false);
      setManualError(null);
    } else if (managing) {
      setManaging(false);
    } else {
      onClose();
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/62"
      onClick={onClose}
    >
      <div
        className="flex max-h-[85vh] w-[440px] flex-col rounded-2xl shadow-2xl overflow-hidden"
        style={{
          backgroundColor: "var(--card-surface)",
          border: "0.5px solid var(--hairline)",
        }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="lindo-hairline-b flex h-12 shrink-0 items-center justify-between px-3">
          <div className="flex items-center gap-1.5">
            <button
              type="button"
              onClick={handleBack}
              className="lindo-interactive flex h-8 w-8 items-center justify-center rounded-full"
              style={{ color: "var(--ink-primary)" }}
            >
              {adding || managing ? <ArrowLeft size={17} /> : <X size={17} />}
            </button>
            <span className="text-[16px] font-bold" style={{ color: "var(--ink-primary)" }}>
              {adding ? "添加账号" : "账号管理"}
            </span>
          </div>

          {!showWelcomeBody && effectiveAccounts.length > 0 && (
            <button
              type="button"
              onClick={() => setManaging((v) => !v)}
              className="lindo-interactive rounded-lg px-2.5 py-1 text-[13px] font-medium"
              style={{ color: "var(--ink-secondary)" }}
            >
              {managing ? "完成" : "管理"}
            </button>
          )}
        </div>

        <div className="lindo-scroll flex-1 min-h-0">
          {showWelcomeBody ? (
            <div className="pb-5">
              <WelcomeLoginScreen
                isDark={isDark}
                showHeader={false}
                statusMessage={statusHint}
                onPrimaryWebLogin={() => {
                  onClose();
                  if (adding && isAuthorized) onAddAccountWebLogin();
                  else onWebLogin();
                }}
                onBrowserOneClickAuth={() => {
                  onClose();
                  onBrowserOneClick();
                }}
              />

              <div className="px-6 pt-1">
                <button
                  type="button"
                  onClick={() => setManualExpanded((v) => !v)}
                  className="flex w-full items-center justify-center gap-1 py-1.5 text-[12.5px] cursor-pointer"
                  style={{ color: "var(--ink-tertiary)" }}
                >
                  <span>{manualExpanded ? "收起手动登录" : "展开手动登录"}</span>
                  {manualExpanded ? <CaretDown size={12} /> : <CaretRight size={12} />}
                </button>

                {manualExpanded && (
                  <div
                    className="mt-2 space-y-2.5 rounded-xl p-3.5"
                    style={{
                      backgroundColor: "var(--subtle-surface)",
                      border: "0.5px solid var(--hairline)",
                    }}
                  >
                    <p
                      className="text-[11.5px] leading-relaxed"
                      style={{ color: "var(--ink-secondary)" }}
                    >
                      若系统拦截协议回调，可在浏览器按 F12 从 Application 复制 _t，或粘贴
                      discourse:// 授权回调链接与 Copy as cURL。
                    </p>
                    <textarea
                      value={manualInput}
                      onChange={(e) => {
                        setManualInput(e.target.value);
                        if (manualError) setManualError(null);
                      }}
                      rows={3}
                      placeholder="粘贴 discourse:// 授权回调链接、_t Cookie 值，或浏览器 DevTools Copy as cURL..."
                      className="w-full rounded-lg bg-transparent p-2.5 text-[12.5px] outline-none resize-none"
                      style={{
                        border: manualError
                          ? "0.5px solid var(--error-border)"
                          : "0.5px solid var(--hairline)",
                        color: "var(--ink-primary)",
                      }}
                    />
                    {manualError && (
                      <p className="text-[12px]" style={{ color: "var(--error-fg)" }}>
                        {manualError}
                      </p>
                    )}
                    <button
                      type="button"
                      disabled={manualBusy}
                      onClick={async () => {
                        if (!manualInput.trim()) {
                          setManualError("请输入授权回调链接、_t Cookie 或 cURL 命令");
                          return;
                        }
                        setManualBusy(true);
                        setManualError(null);
                        try {
                          const ok = await importManualCredentialInRust(manualInput, isDark);
                          if (ok) {
                            onManualImported();
                            onClose();
                          } else {
                            setManualError(
                              "凭证校验失败，请确认粘贴了有效的回调链接、_t Cookie 或 cURL"
                            );
                          }
                        } catch (err: any) {
                          setManualError(String(err?.message || err || "凭证校验失败"));
                        } finally {
                          setManualBusy(false);
                        }
                      }}
                      className="w-full rounded-xl py-2 text-[13px] font-bold cursor-pointer disabled:opacity-50"
                      style={{
                        backgroundColor: "var(--publish-btn-bg)",
                        color: "var(--publish-btn-fg)",
                      }}
                    >
                      {manualBusy ? "验证登录中" : "验证并登录"}
                    </button>
                  </div>
                )}
              </div>
            </div>
          ) : (
            <div className="py-2">
              {effectiveAccounts.map((acc) => {
                const current =
                  isAuthorized &&
                  currentUser?.username.toLowerCase() === acc.profile.username.toLowerCase();
                return (
                  <div key={acc.profile.username}>
                    <div className="flex min-h-[72px] items-center px-4">
                      <button
                        type="button"
                        onClick={() => {
                          if (acc.needsLogin) {
                            setManaging(false);
                            setAdding(true);
                          } else if (!current) {
                            onSwitchAccount(acc.profile.username);
                            onClose();
                          }
                        }}
                        className="lindo-interactive flex flex-1 items-center gap-3 rounded-xl px-2 py-2.5 text-left"
                      >
                        <img
                          src={resolveAvatarUrl(
                            acc.profile.animatedAvatar || acc.profile.avatarTemplate,
                            96
                          )}
                          alt={acc.profile.username}
                          className={getAvatarClassName(
                            "h-10 w-10 shrink-0",
                            acc.profile.username,
                            undefined,
                            acc.profile.avatarTemplate
                          )}
                        />
                        <div className="min-w-0 flex-1">
                          <div
                            className="truncate text-[15.5px]"
                            style={{
                              fontWeight: current ? 600 : 500,
                              color: current ? "var(--brand-gold)" : "var(--ink-primary)",
                            }}
                          >
                            {acc.profile.displayName || acc.profile.username}
                          </div>
                          <div className="text-[12px]" style={{ color: "var(--ink-secondary)" }}>
                            @{acc.profile.username}
                          </div>
                          {acc.needsLogin && (
                            <div className="text-[12px]" style={{ color: "var(--ink-tertiary)" }}>
                              需登录
                            </div>
                          )}
                        </div>
                        {current && (
                          <Check size={19} weight="bold" style={{ color: "var(--brand-gold)" }} />
                        )}
                      </button>

                      {!current && managing && (
                        <button
                          type="button"
                          onClick={() => setRemovingUsername(acc.profile.username)}
                          className="lindo-interactive ml-2 rounded-lg px-2.5 py-1.5 text-[12px]"
                          style={{ color: "var(--ink-tertiary)" }}
                        >
                          移除
                        </button>
                      )}
                    </div>
                    <div className="mx-4 h-[0.5px]" style={{ backgroundColor: "var(--hairline)" }} />
                  </div>
                );
              })}

              {statusHint && (
                <p className="px-5 py-3 text-[13px]" style={{ color: "var(--ink-secondary)" }}>
                  {statusHint}
                </p>
              )}

              <button
                type="button"
                onClick={() => {
                  setManaging(false);
                  setAdding(true);
                }}
                className="lindo-interactive flex h-[48px] w-full items-center justify-between px-5 text-left text-[14px]"
                style={{ color: "var(--ink-primary)" }}
              >
                <span className="flex items-center gap-2.5">
                  <Plus size={16} style={{ color: "var(--ink-secondary)" }} />
                  <span>添加账号</span>
                </span>
                <CaretRight size={14} style={{ color: "var(--ink-tertiary)" }} />
              </button>

              {isAuthorized && (
                <>
                  <div className="my-2 h-[0.5px]" style={{ backgroundColor: "var(--hairline)" }} />
                  <button
                    type="button"
                    onClick={() => {
                      onClose();
                      onLogout();
                    }}
                    className="lindo-interactive flex h-[48px] w-full items-center justify-between px-5 text-left text-[14px] font-medium"
                    style={{ color: "#EF4444" }}
                  >
                    <span>退出登录</span>
                    <SignOut size={16} />
                  </button>
                </>
              )}
            </div>
          )}
        </div>
      </div>

      {removingUsername && (
        <ConfirmDialog
          title={`移除 @${removingUsername}？`}
          detail="仅移除此设备保存的账号，不会删除社区账号。"
          confirmLabel="移除"
          destructive
          onCancel={() => setRemovingUsername(null)}
          onConfirm={() => {
            onRemoveAccount(removingUsername);
            setRemovingUsername(null);
          }}
        />
      )}
    </div>
  );
};

/* ==================== 5. 游客登录引导弹窗 ==================== */
export const GuestLoginGuideModal: React.FC<{
  title: string;
  subtitle: string;
  onPrimaryWebLogin: () => void;
  onBrowserOneClickAuth: () => void;
  onDismiss: () => void;
}> = ({ title, subtitle, onPrimaryWebLogin, onBrowserOneClickAuth, onDismiss }) => (
  <div
    className="fixed inset-0 z-50 flex items-center justify-center bg-black/62"
    onClick={onDismiss}
  >
    <div
      className="w-[380px] rounded-2xl p-6 text-center shadow-2xl"
      style={{
        backgroundColor: "var(--card-surface)",
        border: "0.5px solid var(--hairline)",
      }}
      onClick={(e) => e.stopPropagation()}
    >
      <img src="/brand/lindo-mark.svg" alt="Lindo" className="mx-auto h-[52px] w-[52px]" />
      <h3
        className="mt-3.5 text-[18.5px] font-bold tracking-tight"
        style={{ color: "var(--ink-primary)" }}
      >
        {title}
      </h3>
      <p
        className="mt-1.5 text-[13.2px] leading-relaxed"
        style={{ color: "var(--ink-secondary)" }}
      >
        {subtitle}
      </p>

      <div className="mt-5 space-y-2.5">
        <button
          type="button"
          onClick={onPrimaryWebLogin}
          className="flex h-[46px] w-full items-center justify-center rounded-xl text-[14.5px] font-bold cursor-pointer"
          style={{
            backgroundColor: "var(--publish-btn-bg)",
            color: "var(--publish-btn-fg)",
          }}
        >
          使用 LINUX DO 登录
        </button>

        <button
          type="button"
          onClick={onBrowserOneClickAuth}
          className="lindo-interactive flex h-[46px] w-full items-center justify-center gap-2 rounded-xl text-[14px] font-semibold"
          style={{
            backgroundColor: "var(--subtle-surface)",
            color: "var(--ink-primary)",
          }}
        >
          <Globe size={16} />
          <span>通过系统浏览器免密授权</span>
        </button>

        <button
          type="button"
          onClick={onDismiss}
          className="lindo-interactive inline-flex rounded-lg px-4 py-1.5 text-[13px] font-medium"
          style={{ color: "var(--ink-tertiary)" }}
        >
          继续以游客浏览
        </button>
      </div>
    </div>
  </div>
);

/* ==================== 6. 通用确认对话框 ==================== */
export const ConfirmDialog: React.FC<{
  title: string;
  detail?: string;
  confirmLabel: string;
  destructive?: boolean;
  alternateLabel?: string;
  onAlternate?: () => void;
  onCancel: () => void;
  onConfirm: () => void;
}> = ({
  title,
  detail,
  confirmLabel,
  destructive,
  alternateLabel,
  onAlternate,
  onCancel,
  onConfirm,
}) => (
  <div
    className="fixed inset-0 z-[60] flex items-center justify-center bg-black/62"
    onClick={onCancel}
  >
    <div
      className="w-[340px] rounded-2xl p-5 shadow-2xl"
      style={{
        backgroundColor: "var(--card-surface)",
        border: "0.5px solid var(--hairline)",
      }}
      onClick={(e) => e.stopPropagation()}
    >
      <div className="text-[16px] font-bold" style={{ color: "var(--ink-primary)" }}>
        {title}
      </div>
      {detail && (
        <p
          className="mt-1.5 text-[13px] leading-relaxed"
          style={{ color: "var(--ink-secondary)" }}
        >
          {detail}
        </p>
      )}
      <div className="mt-5 flex items-center justify-end gap-2">
        {alternateLabel && onAlternate && (
          <button
            type="button"
            onClick={onAlternate}
            className="lindo-interactive mr-auto rounded-xl px-3 py-1.5 text-[13px] font-medium cursor-pointer"
            style={{ color: "#EF4444" }}
          >
            {alternateLabel}
          </button>
        )}
        <button
          type="button"
          onClick={onCancel}
          className="lindo-interactive rounded-xl px-4 py-1.5 text-[13px] font-medium"
          style={{ color: "var(--ink-secondary)" }}
        >
          取消
        </button>
        <button
          type="button"
          onClick={onConfirm}
          className="rounded-xl px-4 py-1.5 text-[13px] font-semibold cursor-pointer"
          style={{
            backgroundColor: destructive ? "#EF4444" : "var(--publish-btn-bg)",
            color: destructive ? "#FFFFFF" : "var(--publish-btn-fg)",
          }}
        >
          {confirmLabel}
        </button>
      </div>
    </div>
  </div>
);

/* ==================== 7. 全站搜索（支持 相关性/最新/最多赞/最多浏览 排序与分页） ==================== */
export const GlobalSearchModal: React.FC<{
  onSelectHit: (topicId: number, title: string, postNumber?: number) => void;
  onClose: () => void;
}> = ({ onSelectHit, onClose }) => {
  const [query, setQuery] = useState("");
  const [order, setOrder] = useState<"" | "latest" | "likes" | "views">("");
  const [hits, setHits] = useState<CommunitySearchHit[]>([]);
  const [page, setPage] = useState(1);
  const [hasMore, setHasMore] = useState(false);
  const [searching, setSearching] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const q = query.trim();
    if (q.length < 2) {
      setHits([]);
      setHasMore(false);
      setError(null);
      return;
    }
    const timer = setTimeout(() => {
      setSearching(true);
      setError(null);
      setPage(1);
      searchCommunity(q, 1, order || null)
        .then((res) => {
          setHits(res.posts);
          setHasMore(res.hasMore);
        })
        .catch((err: any) => setError(err?.message || "搜索失败，请稍后重试"))
        .finally(() => setSearching(false));
    }, 280);
    return () => clearTimeout(timer);
  }, [query, order]);

  const handleLoadMore = async () => {
    if (loadingMore || !hasMore) return;
    const nextPage = page + 1;
    setLoadingMore(true);
    try {
      const res = await searchCommunity(query.trim(), nextPage, order || null);
      setHits((prev) => [...prev, ...res.posts]);
      setPage(nextPage);
      setHasMore(res.hasMore);
    } catch {
      setHasMore(false);
    } finally {
      setLoadingMore(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-center bg-black/62 pt-14 px-4"
      onClick={onClose}
    >
      <div
        className="flex h-[min(580px,78vh)] w-[640px] max-w-full flex-col rounded-2xl shadow-2xl overflow-hidden"
        style={{
          backgroundColor: "var(--card-surface)",
          border: "0.5px solid var(--hairline)",
        }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="lindo-hairline-b flex items-center gap-2.5 px-4 py-3">
          <MagnifyingGlass size={17} style={{ color: "var(--brand-gold)" }} />
          <input
            type="text"
            autoFocus
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="搜索话题、#标签 或 @用户..."
            className="flex-1 bg-transparent text-[14.5px] outline-none"
            style={{ color: "var(--ink-primary)" }}
          />
          {query && (
            <button
              type="button"
              onClick={() => setQuery("")}
              className="lindo-interactive rounded-full p-1"
            >
              <X size={13.5} style={{ color: "var(--ink-tertiary)" }} />
            </button>
          )}
          <button
            type="button"
            onClick={onClose}
            className="lindo-interactive rounded-full p-1"
          >
            <X size={15} />
          </button>
        </div>

        {/* 搜索排序切换条 */}
        <div className="lindo-hairline-b no-scrollbar flex items-center gap-1.5 overflow-x-auto px-4 py-2">
          {[
            { key: "", label: "相关性" },
            { key: "latest", label: "最新发帖" },
            { key: "likes", label: "最多获赞" },
            { key: "views", label: "最多浏览" },
          ].map((opt) => (
            <button
              key={opt.key}
              type="button"
              onClick={() => setOrder(opt.key as any)}
              className="shrink-0 rounded-full px-3 py-0.5 text-[11.8px] font-medium cursor-pointer"
              style={{
                backgroundColor:
                  order === opt.key ? "var(--active-chip-bg)" : "var(--subtle-surface)",
                color:
                  order === opt.key ? "var(--active-chip-text)" : "var(--ink-secondary)",
              }}
            >
              {opt.label}
            </button>
          ))}
        </div>

        <div className="lindo-scroll flex-1 divide-y">
          {searching ? (
            <div>
              {Array.from({ length: 6 }).map((_, i) => (
                <div key={i} className="flex items-start gap-3 px-4 py-3.5">
                  <div className="lindo-shimmer mt-0.5 h-8 w-8 shrink-0 rounded-full" />
                  <div className="min-w-0 flex-1 space-y-2">
                    <div className="lindo-shimmer h-4 w-3/4 rounded" />
                    <div className="lindo-shimmer h-3 w-full rounded" />
                    <div className="lindo-shimmer h-3 w-1/3 rounded" />
                  </div>
                </div>
              ))}
            </div>
          ) : error ? (
            <div className="p-8 text-center text-[12.5px]" style={{ color: "var(--error-fg)" }}>
              {error}
            </div>
          ) : hits.length === 0 ? (
            <div className="p-8 text-center text-[12.5px]" style={{ color: "var(--ink-tertiary)" }}>
              {query.trim().length < 2 ? "输入至少 2 个字符开始全站检索" : "未找到匹配结果，试试其他关键词"}
            </div>
          ) : (
            <>
              {hits.map((h) => {
                const cv = resolveCategoryVisual(h.categoryName || "", h.categoryColorHex);
                return (
                  <button
                    key={h.id}
                    type="button"
                    onClick={() => {
                      onSelectHit(h.topicId, h.title, h.postNumber);
                      onClose();
                    }}
                    className="lindo-interactive flex w-full items-start gap-3 px-4 py-3 text-left"
                  >
                    <img
                      src={resolveAvatarUrl(h.avatarTemplate, 96)}
                      alt={h.username}
                      className="mt-0.5 h-8 w-8 shrink-0 rounded-full object-cover"
                    />
                    <div className="min-w-0 flex-1">
                      <div
                        className="text-[13.8px] font-semibold"
                        style={{ color: "var(--ink-primary)" }}
                      >
                        {h.title}
                        {h.postNumber > 1 && (
                          <span
                            className="ml-1.5 text-[11.5px]"
                            style={{ color: "var(--brand-gold)" }}
                          >
                            #{h.postNumber} 楼
                          </span>
                        )}
                      </div>
                      {h.blurb && (
                        <p
                          className="mt-0.5 line-clamp-2 text-[12px]"
                          style={{ color: "var(--ink-secondary)" }}
                        >
                          {h.blurb}
                        </p>
                      )}
                      <div
                        className="mt-1 flex flex-wrap items-center gap-2 text-[11px]"
                        style={{ color: "var(--ink-tertiary)" }}
                      >
                        {h.categoryName && (
                          <span className="inline-flex items-center gap-1">
                            <span
                              className="h-1.5 w-1.5 rounded-full"
                              style={{ backgroundColor: cv.dotColor }}
                            />
                            <span>{h.categoryName}</span>
                          </span>
                        )}
                        <span>@{h.username}</span>
                        <span>·</span>
                        <span>{formatRelativeTime(h.createdAt)}</span>
                        {h.likeCount > 0 && <span>· {h.likeCount} 赞</span>}
                      </div>
                    </div>
                  </button>
                );
              })}

              {hasMore && (
                <div className="py-3 text-center">
                  <button
                    type="button"
                    disabled={loadingMore}
                    onClick={handleLoadMore}
                    className="lindo-interactive rounded-full px-4 py-1 text-[12px] font-medium"
                    style={{ color: "var(--active-chip-text)" }}
                  >
                    {loadingMore ? "加载中..." : "加载更多搜索结果"}
                  </button>
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
};

/* ==================== 8. 公开用户名片（支持 关注/取消关注、发私信 与 近期发言） ==================== */
export const PublicUserCardModal: React.FC<{
  username: string;
  isAuthorized: boolean;
  currentUsername?: string | null;
  onStartPm: (recipientUsername: string) => void;
  onOpenTopic: (topicId: number, title: string, postNumber?: number) => void;
  onInspectBadge?: (badge: GrantedBadge) => void;
  onClose: () => void;
}> = ({
  username,
  isAuthorized,
  currentUsername,
  onStartPm,
  onOpenTopic,
  onInspectBadge,
  onClose,
}) => {
  const [userObj, setUserObj] = useState<any | null>(null);
  const [cardBadges, setCardBadges] = useState<GrantedBadge[]>([]);
  const [following, setFollowing] = useState(false);
  const [recentActions, setRecentActions] = useState<any[]>([]);
  const [showRecent, setShowRecent] = useState(false);
  const [loadingRecent, setLoadingRecent] = useState(false);

  useEffect(() => {
    fetchUserCardDetail(username)
      .then((d) => {
        const u = d?.user ?? null;
        setUserObj(u);
        setFollowing(Boolean(u?.is_followed ?? u?.following));
        if (Array.isArray(d?.badges)) {
          setCardBadges(
            d.badges
              .filter((b: any) => Number(b?.id ?? 0) > 0)
              .slice(0, 8)
              .map((b: any) => ({
                id: Number(b.id),
                name: String(b.name || ""),
                description: String(b.description || ""),
                longDescription: b.long_description ? String(b.long_description) : undefined,
                badgeTypeId: Number(b.badge_type_id ?? 3),
                icon: b.icon ?? null,
                imageUrl: b.image_url ?? b.image ?? null,
                grantCount: Number(b.grant_count ?? 0),
                isEarned: true,
              }))
          );
        }
      })
      .catch(() => {});
  }, [username]);

  const isSelf =
    Boolean(currentUsername) && currentUsername!.toLowerCase() === username.toLowerCase();

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/62 p-4"
      onClick={onClose}
    >
      <div
        className="w-[410px] max-w-full max-h-[82vh] flex flex-col rounded-2xl p-5 shadow-2xl overflow-hidden"
        style={{
          backgroundColor: "var(--card-surface)",
          border: "0.5px solid var(--hairline)",
        }}
        onClick={(e) => e.stopPropagation()}
      >
        {!userObj ? (
          <div className="space-y-4">
            <div className="flex items-center justify-between gap-3">
              <div className="flex items-center gap-3">
                <div className="lindo-shimmer h-14 w-14 shrink-0 rounded-full" />
                <div className="space-y-2">
                  <div className="lindo-shimmer h-4 w-28 rounded" />
                  <div className="lindo-shimmer h-3 w-36 rounded" />
                </div>
              </div>
              <button
                type="button"
                onClick={onClose}
                className="lindo-interactive rounded-full p-1"
              >
                <X size={16} />
              </button>
            </div>
            <div className="space-y-2">
              <div className="lindo-shimmer h-3.5 w-full rounded" />
              <div className="lindo-shimmer h-3.5 w-4/5 rounded" />
            </div>
            <div className="grid grid-cols-3 gap-2 pt-1">
              <div className="lindo-shimmer h-8 rounded-xl" />
              <div className="lindo-shimmer h-8 rounded-xl" />
              <div className="lindo-shimmer h-8 rounded-xl" />
            </div>
          </div>
        ) : (
          <div className="flex flex-col min-h-0">
            <div className="flex items-start justify-between gap-2">
              <div className="flex items-center gap-3 min-w-0">
                <AvatarWithFlair
                  username={userObj.username}
                  avatarTemplate={userObj.avatar_template}
                  animatedAvatar={userObj.animated_avatar}
                  primaryGroupName={userObj.primary_group_name}
                  flair={
                    userObj.flair_url
                      ? {
                          url: userObj.flair_url,
                          name: userObj.flair_name,
                          bgColorHex: userObj.flair_bg_color,
                          fgColorHex: userObj.flair_color,
                        }
                      : null
                  }
                  sizePx={56}
                />
                <div className="min-w-0">
                  <div className="flex items-center gap-1.5 min-w-0">
                    <span
                      className="truncate text-[16px] font-bold"
                      style={{ color: "var(--ink-primary)" }}
                    >
                      {userObj.name || userObj.username}
                    </span>
                    {userObj.status?.emoji && (
                      <span
                        title={userObj.status?.description || ""}
                        className="inline-flex shrink-0 items-center"
                      >
                        <StatusEmojiIcon emoji={userObj.status.emoji} size={14} />
                      </span>
                    )}
                  </div>
                  <div className="text-[12px]" style={{ color: "var(--ink-secondary)" }}>
                    @{userObj.username} · {trustLevelLabel(userObj.trust_level ?? 1)}
                  </div>
                  {userObj.title && (
                    <span
                      className={
                        isHolographicTitle(userObj.title)
                          ? "lindo-title-holo mt-1 inline-block text-[12px]"
                          : "mt-1 inline-block rounded-full px-2 py-0.5 text-[11px] font-medium"
                      }
                      style={
                        isHolographicTitle(userObj.title)
                          ? undefined
                          : {
                              backgroundColor: "var(--tonal-pill)",
                              color: "var(--brand-gold)",
                            }
                      }
                    >
                      {userObj.title}
                    </span>
                  )}
                </div>
              </div>
              <button
                type="button"
                onClick={onClose}
                className="lindo-interactive rounded-full p-1"
              >
                <X size={16} />
              </button>
            </div>

            {cardBadges.length > 0 && (
              <div className="mt-2.5 flex flex-wrap items-center gap-1.5">
                {cardBadges.map((cb) => (
                  <button
                    key={cb.id}
                    type="button"
                    onClick={() => onInspectBadge?.(cb)}
                    className="inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-[11px] font-medium cursor-pointer transition-opacity hover:opacity-85"
                    style={{
                      backgroundColor: "var(--subtle-surface)",
                      border: "0.5px solid var(--hairline)",
                      color: "var(--ink-primary)",
                    }}
                  >
                    <OfficialBadgeIcon badge={cb} size={13} />
                    <span>{cb.name}</span>
                  </button>
                ))}
              </div>
            )}

            {userObj.bio_excerpt && (
              <div
                className="lindo-cooked mt-3 line-clamp-3 text-[12.8px]"
                style={{ color: "var(--ink-secondary)" }}
                dangerouslySetInnerHTML={{ __html: userObj.bio_excerpt }}
              />
            )}

            {/* 互动按钮栏 */}
            <div className="mt-4 flex items-center gap-2">
              {isAuthorized && !isSelf && userObj.can_follow !== false && (
                <button
                  type="button"
                  onClick={async () => {
                    const next = !following;
                    try {
                      await toggleFollowUser(userObj.username, next);
                      setFollowing(next);
                    } catch {}
                  }}
                  className="flex-1 rounded-xl py-1.5 text-[12.8px] font-bold cursor-pointer"
                  style={{
                    backgroundColor: following
                      ? "var(--subtle-surface)"
                      : "var(--publish-btn-bg)",
                    border: "0.5px solid var(--hairline)",
                    color: following ? "var(--ink-secondary)" : "var(--publish-btn-fg)",
                  }}
                >
                  {following ? "已关注" : "+ 关注"}
                </button>
              )}

              {isAuthorized && !isSelf && (
                <button
                  type="button"
                  onClick={() => {
                    onClose();
                    onStartPm(userObj.username);
                  }}
                  className="lindo-interactive flex flex-1 items-center justify-center gap-1.5 rounded-xl py-1.5 text-[12.8px] font-medium"
                  style={{
                    backgroundColor: "var(--subtle-surface)",
                    border: "0.5px solid var(--hairline)",
                    color: "var(--ink-primary)",
                  }}
                >
                  <EnvelopeSimple size={14.5} />
                  <span>发私信</span>
                </button>
              )}

              <button
                type="button"
                onClick={() => {
                  const next = !showRecent;
                  setShowRecent(next);
                  if (next && recentActions.length === 0) {
                    setLoadingRecent(true);
                    fetchUserActions(userObj.username, "4,5")
                      .then((acts) => setRecentActions(acts.slice(0, 10)))
                      .catch(() => {})
                      .finally(() => setLoadingRecent(false));
                  }
                }}
                className="lindo-interactive rounded-xl px-3 py-1.5 text-[12.5px] font-medium"
                style={{
                  backgroundColor: "var(--subtle-surface)",
                  border: "0.5px solid var(--hairline)",
                  color: "var(--ink-secondary)",
                }}
              >
                {showRecent ? "收起动态" : "近期发言"}
              </button>
            </div>

            {showRecent && (
              <div
                className="mt-3 max-h-[210px] rounded-xl overflow-hidden lindo-scroll divide-y"
                style={{
                  backgroundColor: "var(--subtle-surface)",
                  border: "0.5px solid var(--hairline)",
                }}
              >
                {loadingRecent ? (
                  <div className="p-4 text-center text-[12px]" style={{ color: "var(--ink-tertiary)" }}>
                    加载中...
                  </div>
                ) : recentActions.length === 0 ? (
                  <div className="p-4 text-center text-[12px]" style={{ color: "var(--ink-tertiary)" }}>
                    暂无近期公开动态
                  </div>
                ) : (
                  recentActions.map((act, i) => (
                    <button
                      key={`${act.topic_id}_${i}`}
                      type="button"
                      onClick={() => {
                        onClose();
                        onOpenTopic(
                          Number(act.topic_id),
                          String(act.title || "社区话题"),
                          Number(act.post_number || 1)
                        );
                      }}
                      className="lindo-interactive flex w-full flex-col px-3 py-2 text-left"
                    >
                      <span
                        className="truncate text-[12.5px] font-semibold"
                        style={{ color: "var(--ink-primary)" }}
                      >
                        {act.title}
                      </span>
                      {act.excerpt && (
                        <span
                          className="mt-0.5 line-clamp-1 text-[11.5px]"
                          style={{ color: "var(--ink-secondary)" }}
                          dangerouslySetInnerHTML={{ __html: act.excerpt }}
                        />
                      )}
                    </button>
                  ))
                )}
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
};

/* ==================== 9. 增强型大图灯箱（支持 缩放 / 复制链接 / 原图打开） ==================== */
export const ImageLightboxModal: React.FC<{
  src: string;
  onClose: () => void;
}> = ({ src, onClose }) => {
  const [zoom, setZoom] = useState(1);
  const [copied, setCopied] = useState(false);
  const displaySrc = useForumImageSrc(src);

  return (
    <div
      className="fixed inset-0 z-50 flex flex-col items-center justify-center bg-black/88 p-8 select-none"
      onClick={onClose}
    >
      <div
        className="flex flex-1 items-center justify-center overflow-auto w-full"
        onClick={onClose}
      >
        <img
          src={displaySrc}
          alt=""
          onClick={(e) => e.stopPropagation()}
          style={{ transform: `scale(${zoom})`, transition: "transform 150ms ease" }}
          className="max-h-[82vh] max-w-[88vw] rounded-lg object-contain shadow-2xl"
        />
      </div>

      <div
        className="mt-4 flex items-center gap-2 rounded-full px-4 py-1.5 shadow-2xl"
        style={{
          backgroundColor: "rgba(24, 27, 34, 0.92)",
          border: "0.5px solid rgba(255,255,255,0.14)",
          color: "#F3F4F6",
        }}
        onClick={(e) => e.stopPropagation()}
      >
        <button
          type="button"
          onClick={() => setZoom((z) => Math.max(0.5, Number((z - 0.25).toFixed(2))))}
          className="rounded-full p-1 hover:bg-white/10 cursor-pointer"
          title="缩小"
        >
          <MagnifyingGlassMinus size={16} />
        </button>
        <button
          type="button"
          onClick={() => setZoom(1)}
          className="px-1.5 text-[12px] tabular-nums cursor-pointer"
          title="重置缩放"
        >
          {Math.round(zoom * 100)}%
        </button>
        <button
          type="button"
          onClick={() => setZoom((z) => Math.min(3, Number((z + 0.25).toFixed(2))))}
          className="rounded-full p-1 hover:bg-white/10 cursor-pointer"
          title="放大"
        >
          <MagnifyingGlassPlus size={16} />
        </button>
        <span className="h-3.5 w-[0.5px] bg-white/20" />
        <button
          type="button"
          onClick={() => {
            navigator.clipboard.writeText(src);
            setCopied(true);
            setTimeout(() => setCopied(false), 1500);
          }}
          className="flex items-center gap-1 rounded-full px-2 py-0.5 text-[12px] hover:bg-white/10 cursor-pointer"
        >
          {copied ? <Check size={14} /> : <LinkSimple size={14} />}
          <span>{copied ? "已复制" : "复制链接"}</span>
        </button>
        <button
          type="button"
          onClick={() => openUrl(src)}
          className="flex items-center gap-1 rounded-full px-2 py-0.5 text-[12px] hover:bg-white/10 cursor-pointer"
        >
          <ArrowSquareOut size={14} />
          <span>原图</span>
        </button>
        <button
          type="button"
          onClick={onClose}
          className="ml-1 rounded-full p-1 hover:bg-white/10 cursor-pointer"
          title="关闭 (Esc)"
        >
          <X size={16} />
        </button>
      </div>
    </div>
  );
};

/* ==================== 10. 客户端平滑检查与一键下载升级弹窗 ==================== */
export const DesktopUpdateModal: React.FC<{
  info: DesktopUpdateInfo;
  installing: boolean;
  progress: UpdateProgressPayload | null;
  error: string | null;
  onStartInstall: () => void;
  onClose: () => void;
}> = ({ info, installing, progress, error, onStartInstall, onClose }) => {
  const formatMb = (bytes: number) => `${(bytes / 1048576).toFixed(1)} MB`;
  const archLabel = info.arch.startsWith("windows")
    ? `Windows (${info.arch.replace("windows-", "")})`
    : info.arch === "x64"
      ? "Intel (x86_64)"
      : "Apple Silicon (arm64)";

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/62 p-4"
      onClick={() => {
        if (!installing) onClose();
      }}
    >
      <div
        className="w-[440px] max-w-full rounded-2xl p-5 shadow-2xl"
        style={{
          backgroundColor: "var(--card-surface)",
          border: "0.5px solid var(--hairline)",
        }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-3">
          <div className="flex items-center gap-3">
            <img src="/brand/lindo-mark.svg" alt="Lindo" className="h-11 w-11 shrink-0" />
            <div>
              <div className="flex items-center gap-2">
                <span className="text-[16px] font-bold" style={{ color: "var(--ink-primary)" }}>
                  发现新版本 v{info.latestVersion}
                </span>
                <span
                  className="rounded-full px-2 py-0.5 text-[10.5px] font-semibold"
                  style={{
                    backgroundColor: "var(--active-chip-bg)",
                    color: "var(--active-chip-text)",
                  }}
                >
                  {archLabel}
                </span>
              </div>
              <div className="mt-0.5 text-[12px]" style={{ color: "var(--ink-tertiary)" }}>
                当前版本 v{info.currentVersion}
                {info.updatedAt ? ` · ${info.updatedAt} 发布` : ""}
              </div>
            </div>
          </div>

          {!installing && (
            <button
              type="button"
              onClick={onClose}
              className="lindo-interactive flex h-7 w-7 items-center justify-center rounded-full"
              style={{ color: "var(--ink-secondary)" }}
            >
              <X size={15} />
            </button>
          )}
        </div>

        {info.notes.length > 0 && (
          <div
            className="lindo-scroll mt-4 max-h-[210px] rounded-xl p-3.5 text-[12.5px] leading-relaxed space-y-1.5"
            style={{
              backgroundColor: "var(--subtle-surface)",
              border: "0.5px solid var(--hairline)",
              color: "var(--ink-secondary)",
            }}
          >
            {info.notes.map((line, idx) => (
              <div key={idx} className="flex items-start gap-2">
                <span className="mt-[7px] h-1.5 w-1.5 shrink-0 rounded-full" style={{ backgroundColor: "var(--brand-gold)" }} />
                <span>{line}</span>
              </div>
            ))}
          </div>
        )}

        {installing && (
          <div className="mt-4 space-y-2">
            <div className="flex items-center justify-between text-[12px]">
              <span style={{ color: "var(--ink-primary)" }}>
                {progress?.message || "正在准备下载更新..."}
              </span>
              <span className="tabular-nums font-semibold" style={{ color: "var(--brand-gold)" }}>
                {progress?.percent ?? 1}%
              </span>
            </div>
            <div
              className="h-2 w-full overflow-hidden rounded-full"
              style={{ backgroundColor: "var(--subtle-surface)" }}
            >
              <div
                className="h-full rounded-full transition-[width] duration-150 ease-out"
                style={{
                  width: `${Math.max(3, Math.min(100, progress?.percent ?? 3))}%`,
                  backgroundColor: "var(--brand-gold)",
                }}
              />
            </div>
            {progress && progress.totalBytes > 0 && (
              <div className="text-right text-[11px] tabular-nums" style={{ color: "var(--ink-tertiary)" }}>
                {formatMb(progress.downloadedBytes)} / {formatMb(progress.totalBytes)}
              </div>
            )}
          </div>
        )}

        {error && (
          <div
            className="mt-3 rounded-xl px-3 py-2 text-[12px]"
            style={{
              backgroundColor: "var(--error-bg)",
              border: "0.5px solid var(--error-border)",
              color: "var(--error-fg)",
            }}
          >
            {error}
          </div>
        )}

        <div className="mt-5 flex items-center justify-between gap-2">
          <button
            type="button"
            onClick={() =>
              openUrl(
                info.dmgUrl ||
                  info.zipUrl ||
                  "https://github.com/lucaslus/LinDo/releases"
              )
            }
            className="text-[12px] hover:underline cursor-pointer"
            style={{ color: "var(--ink-tertiary)" }}
          >
            前往 GitHub Release 下载
          </button>

          <div className="flex items-center gap-2">
            {!installing && (
              <button
                type="button"
                onClick={onClose}
                className="lindo-interactive rounded-full px-4 py-1.5 text-[12.5px] font-medium"
                style={{ color: "var(--ink-secondary)" }}
              >
                稍后
              </button>
            )}
            <button
              type="button"
              disabled={installing}
              onClick={onStartInstall}
              className="inline-flex items-center gap-1.5 rounded-full px-4 py-1.5 text-[12.8px] font-bold cursor-pointer disabled:opacity-50"
              style={{
                backgroundColor: "var(--publish-btn-bg)",
                color: "var(--publish-btn-fg)",
              }}
            >
              <DownloadSimple size={15} weight="bold" />
              <span>{installing ? "正在升级..." : "立即更新并重启"}</span>
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

/* ==================== 外链安全风险分级确认弹窗（对齐官方 Web Theme #128） ==================== */
export const ExternalLinkSafetyModal: React.FC<{
  assessment: ExternalUrlAssessment;
  onConfirm: (rememberHost: boolean) => void;
  onCancel: () => void;
}> = ({ assessment, onConfirm, onCancel }) => {
  const [rememberHost, setRememberHost] = useState(false);
  const [copied, setCopied] = useState(false);
  const isRisky = assessment.tier === "RISKY";

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/62 p-4"
      onClick={onCancel}
    >
      <div
        className="w-[440px] max-w-full rounded-2xl p-5 shadow-2xl"
        style={{
          backgroundColor: "var(--card-surface)",
          border: "0.5px solid var(--hairline)",
        }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-3">
          <div className="flex items-center gap-2.5">
            <div
              className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl"
              style={{
                backgroundColor: isRisky
                  ? "rgba(245, 158, 11, 0.14)"
                  : "var(--subtle-surface)",
                color: isRisky ? "#F59E0B" : "var(--ink-primary)",
              }}
            >
              {isRisky ? <Warning size={19} weight="fill" /> : <Globe size={19} />}
            </div>
            <div>
              <div className="text-[15px] font-semibold" style={{ color: "var(--ink-primary)" }}>
                {isRisky ? "注意外部链接风险" : "即将离开 LINUX DO"}
              </div>
              <div className="mt-0.5 text-[12px]" style={{ color: "var(--ink-tertiary)" }}>
                目标域名：
                <span className="ml-1 font-semibold" style={{ color: "var(--ink-primary)" }}>
                  {assessment.hostname || "外部站点"}
                </span>
              </div>
            </div>
          </div>
          <button
            type="button"
            onClick={onCancel}
            className="lindo-interactive flex h-7 w-7 items-center justify-center rounded-full"
            style={{ color: "var(--ink-secondary)" }}
          >
            <X size={15} />
          </button>
        </div>

        {isRisky && assessment.riskReason && (
          <div
            className="mt-3.5 rounded-xl px-3 py-2.5 text-[12.2px] leading-relaxed"
            style={{
              backgroundColor: "rgba(245, 158, 11, 0.10)",
              border: "0.5px solid rgba(245, 158, 11, 0.35)",
              color: "var(--ink-primary)",
            }}
          >
            {assessment.riskReason}
          </div>
        )}

        <div
          className="mt-3.5 flex items-start justify-between gap-2 rounded-xl px-3 py-2.5"
          style={{
            backgroundColor: "var(--subtle-surface)",
            border: "0.5px solid var(--hairline)",
          }}
        >
          <div
            className="lindo-selectable max-h-[76px] flex-1 overflow-y-auto break-all font-mono text-[11.8px] leading-relaxed"
            style={{ color: "var(--ink-secondary)" }}
          >
            {assessment.normalizedUrl}
          </div>
          <button
            type="button"
            onClick={() => {
              navigator.clipboard.writeText(assessment.normalizedUrl);
              setCopied(true);
              setTimeout(() => setCopied(false), 1600);
            }}
            className="lindo-interactive flex h-6 shrink-0 items-center gap-1 rounded-md px-2 text-[11.5px]"
            style={{ color: copied ? "var(--brand-gold)" : "var(--ink-secondary)" }}
          >
            {copied ? <Check size={12.5} /> : <Copy size={12.5} />}
            <span>{copied ? "已复制" : "复制"}</span>
          </button>
        </div>

        {assessment.canRememberHost && !isRisky && (
          <label
            className="mt-3.5 flex cursor-pointer items-center gap-2 text-[12.3px] select-none"
            style={{ color: "var(--ink-secondary)" }}
          >
            <input
              type="checkbox"
              checked={rememberHost}
              onChange={(e) => setRememberHost(e.target.checked)}
              className="h-3.5 w-3.5 rounded accent-amber-400 cursor-pointer"
            />
            <span>信任 {assessment.hostname}，下次直接打开</span>
          </label>
        )}

        <div className="mt-5 flex items-center justify-end gap-2">
          <button
            type="button"
            onClick={onCancel}
            className="lindo-interactive rounded-full px-4 py-1.5 text-[12.5px] font-medium"
            style={{ color: "var(--ink-secondary)" }}
          >
            取消
          </button>
          <button
            type="button"
            onClick={() => onConfirm(rememberHost)}
            className="inline-flex items-center gap-1.5 rounded-full px-4 py-1.5 text-[12.8px] font-semibold cursor-pointer"
            style={{
              backgroundColor: "var(--publish-btn-bg)",
              color: "var(--publish-btn-fg)",
            }}
          >
            <span>继续访问</span>
            <ArrowSquareOut size={14} weight="bold" />
          </button>
        </div>
      </div>
    </div>
  );
};

/* ==================== 原生 Connect 保级与成长面板（1:1 对齐移动端 ConnectTrustLevelSheet） ==================== */
export const ConnectTrustLevelModal: React.FC<{
  username: string;
  onOpenWebConnect: () => void;
  onClose: () => void;
}> = ({ username, onOpenWebConnect, onClose }) => {
  const [report, setReport] = useState<ConnectTrustLevelReport | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadReport = async () => {
    setLoading(true);
    setError(null);
    try {
      const r = await fetchConnectTrustLevelReport(username);
      setReport(r);
    } catch (err: any) {
      setError(err?.message || "Connect 数据读取未完成，请稍后重试");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadReport();
  }, [username]);

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/62 p-4"
      onClick={onClose}
    >
      <div
        className="flex max-h-[82vh] w-[540px] max-w-full flex-col overflow-hidden rounded-2xl shadow-2xl"
        style={{
          backgroundColor: "var(--card-surface)",
          border: "0.5px solid var(--hairline)",
        }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="lindo-hairline-b flex h-12 shrink-0 items-center justify-between px-4">
          <div className="flex items-center gap-2">
            <ShieldCheck size={17} weight="duotone" style={{ color: "var(--brand-gold)" }} />
            <span className="text-[15px] font-semibold" style={{ color: "var(--ink-primary)" }}>
              Connect 保级与成长
            </span>
          </div>

          <div className="flex items-center gap-1.5">
            <button
              type="button"
              disabled={loading}
              onClick={loadReport}
              title="刷新指标"
              className="lindo-interactive flex h-7 w-7 items-center justify-center rounded-full disabled:opacity-45"
              style={{ color: "var(--ink-secondary)" }}
            >
              <ArrowClockwise size={14.5} className={loading ? "animate-spin" : ""} />
            </button>
            <button
              type="button"
              onClick={() => {
                onClose();
                onOpenWebConnect();
              }}
              className="lindo-interactive inline-flex h-7 items-center gap-1 rounded-full px-2.5 text-[12px] font-medium"
              style={{
                backgroundColor: "var(--subtle-surface)",
                border: "0.5px solid var(--hairline)",
                color: "var(--ink-primary)",
              }}
            >
              <span>网页版 Connect</span>
              <ArrowSquareOut size={13} />
            </button>
            <button
              type="button"
              onClick={onClose}
              className="lindo-interactive flex h-7 w-7 items-center justify-center rounded-full"
              style={{ color: "var(--ink-secondary)" }}
            >
              <X size={15} />
            </button>
          </div>
        </div>

        <div className="lindo-scroll flex-1 min-h-0 px-5 py-4">
          {loading && !report ? (
            <div className="space-y-3.5 py-2">
              <div className="lindo-shimmer h-14 w-full rounded-xl" />
              {Array.from({ length: 6 }).map((_, idx) => (
                <div key={idx} className="space-y-2">
                  <div className="flex items-center justify-between">
                    <div className="lindo-shimmer h-3.5 w-28 rounded" />
                    <div className="lindo-shimmer h-3.5 w-16 rounded" />
                  </div>
                  <div className="lindo-shimmer h-1.5 w-full rounded-full" />
                </div>
              ))}
            </div>
          ) : error && !report ? (
            <div className="flex flex-col items-center justify-center py-12 text-center">
              <p className="text-[13px]" style={{ color: "var(--ink-secondary)" }}>
                {error}
              </p>
              <div className="mt-3 flex items-center gap-2">
                <button
                  type="button"
                  onClick={loadReport}
                  className="rounded-full px-4 py-1.5 text-[12.2px] font-semibold cursor-pointer"
                  style={{
                    backgroundColor: "var(--publish-btn-bg)",
                    color: "var(--publish-btn-fg)",
                  }}
                >
                  重试
                </button>
                <button
                  type="button"
                  onClick={() => {
                    onClose();
                    onOpenWebConnect();
                  }}
                  className="lindo-interactive rounded-full px-3.5 py-1.5 text-[12.2px]"
                  style={{ color: "var(--ink-secondary)" }}
                >
                  打开网页版 Connect
                </button>
              </div>
            </div>
          ) : report ? (
            <div>
              <div
                className="rounded-xl p-3.5"
                style={{
                  backgroundColor: "var(--subtle-surface)",
                  border: "0.5px solid var(--hairline)",
                }}
              >
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <span className="text-[14.5px] font-bold" style={{ color: "var(--ink-primary)" }}>
                    {report.title}
                  </span>
                  <span
                    className="rounded-full px-2.5 py-0.5 text-[11.2px] font-semibold"
                    style={
                      report.isLiveConnectData
                        ? report.isQualified
                          ? {
                              backgroundColor: "rgba(16, 185, 129, 0.14)",
                              color: "#10B981",
                            }
                          : {
                              backgroundColor: "rgba(245, 158, 11, 0.15)",
                              color: "#F59E0B",
                            }
                        : {
                            backgroundColor: "var(--active-chip-bg)",
                            color: "var(--active-chip-text)",
                          }
                    }
                  >
                    {report.statusBadgeText}
                  </span>
                </div>
                <p
                  className="mt-1.5 text-[12px] leading-relaxed"
                  style={{ color: "var(--ink-secondary)" }}
                >
                  {report.subtitle}
                </p>
              </div>

              <div className="mt-3 divide-y divide-[var(--hairline)]">
                {report.metrics.map((m, idx) => (
                  <div key={`${m.label}_${idx}`} className="py-2.5">
                    <div className="flex items-center justify-between gap-3">
                      <span
                        className="text-[13px] font-medium"
                        style={{ color: "var(--ink-primary)" }}
                      >
                        {m.label}
                      </span>
                      <span
                        className="shrink-0 text-[12.2px] font-semibold tabular-nums"
                        style={{
                          color:
                            report.isLiveConnectData && m.targetValue > 0
                              ? m.isMet
                                ? "var(--ink-primary)"
                                : "#F59E0B"
                              : "var(--ink-secondary)",
                        }}
                      >
                        {report.isLiveConnectData && m.targetValue > 0
                          ? `${m.displayCurrent} / ${m.displayTarget}`
                          : m.displayCurrent}
                      </span>
                    </div>
                    {report.isLiveConnectData && m.targetValue > 0 && (
                      <div
                        className="mt-1.5 h-[5px] w-full overflow-hidden rounded-full"
                        style={{ backgroundColor: "var(--subtle-surface)" }}
                      >
                        <div
                          className="h-full rounded-full transition-[width] duration-300"
                          style={{
                            width: `${Math.round(m.progress * 100)}%`,
                            backgroundColor: m.isMet ? "var(--brand-gold)" : "#F59E0B",
                          }}
                        />
                      </div>
                    )}
                  </div>
                ))}
              </div>

              <div
                className="mt-3 text-[11.5px] leading-relaxed"
                style={{ color: "var(--ink-tertiary)" }}
              >
                {report.isLiveConnectData
                  ? `实时同步自 connect.linux.do（近 ${report.timePeriodDays} 天滚动周期）· 邮箱、DeepLX Key 与邀请码管理请点击右上角「网页版 Connect」`
                  : "如需管理社区邮箱、DeepLX API Key、邀请码或完成首次 Connect OAuth 绑定，请点击右上角「网页版 Connect」"}
              </div>
            </div>
          ) : null}
        </div>
      </div>
    </div>
  );
};
