import { flushSync } from "react-dom";

export type BrandThemeKey =
  | "OFFICIAL_GOLD"
  | "GEEK_BLUE"
  | "BAMBOO_GREEN"
  | "IDEAL_RED"
  | "SOFT_PURPLE"
  | "CLEAR_TEAL"
  | "WARM_ORANGE";

export interface BrandThemeSpec {
  key: BrandThemeKey;
  label: string;
  swatchColor: string;
  logoDoColor: string;
  tabIndicatorColor: string;
  lightChipBg: string;
  lightChipText: string;
  darkChipBg: string;
  darkChipText: string;
}

export const BRAND_THEMES: BrandThemeSpec[] = [
  {
    key: "OFFICIAL_GOLD",
    label: "金",
    swatchColor: "#F59E0B",
    logoDoColor: "#F59E0B",
    tabIndicatorColor: "#F59E0B",
    lightChipBg: "#FEF3C7",
    lightChipText: "#B45309",
    darkChipBg: "#3F2E05",
    darkChipText: "#FCD34D",
  },
  {
    key: "GEEK_BLUE",
    label: "蓝",
    swatchColor: "#2563EB",
    logoDoColor: "#2563EB",
    tabIndicatorColor: "#2563EB",
    lightChipBg: "#EFF6FF",
    lightChipText: "#1D4ED8",
    darkChipBg: "#172554",
    darkChipText: "#60A5FA",
  },
  {
    key: "BAMBOO_GREEN",
    label: "绿",
    swatchColor: "#059669",
    logoDoColor: "#059669",
    tabIndicatorColor: "#059669",
    lightChipBg: "#ECFDF5",
    lightChipText: "#047857",
    darkChipBg: "#064E3B",
    darkChipText: "#34D399",
  },
  {
    key: "IDEAL_RED",
    label: "红",
    swatchColor: "#E03E3E",
    logoDoColor: "#E03E3E",
    tabIndicatorColor: "#E03E3E",
    lightChipBg: "#FDF2F2",
    lightChipText: "#B91C1C",
    darkChipBg: "#3F1515",
    darkChipText: "#F87171",
  },
  {
    key: "SOFT_PURPLE",
    label: "紫",
    swatchColor: "#8B5CF6",
    logoDoColor: "#8B5CF6",
    tabIndicatorColor: "#8B5CF6",
    lightChipBg: "#F3E8FF",
    lightChipText: "#6D28D9",
    darkChipBg: "#30204B",
    darkChipText: "#C4B5FD",
  },
  {
    key: "CLEAR_TEAL",
    label: "青",
    swatchColor: "#0891B2",
    logoDoColor: "#0891B2",
    tabIndicatorColor: "#0891B2",
    lightChipBg: "#CFFAFE",
    lightChipText: "#0E7490",
    darkChipBg: "#153C46",
    darkChipText: "#67E8F9",
  },
  {
    key: "WARM_ORANGE",
    label: "橙",
    swatchColor: "#F97316",
    logoDoColor: "#F97316",
    tabIndicatorColor: "#F97316",
    lightChipBg: "#FFEDD5",
    lightChipText: "#9A3412",
    darkChipBg: "#482913",
    darkChipText: "#FDBA74",
  },
];

// Linux.do exclusive site rules (1:1 aligned with LinuxDoSiteRules.kt)
const SQUARE_AVATAR_USERNAMES = new Set(["neo"]);
const BLUE_GLOW_USERNAMES = new Set([
  "neo",
  "kevin",
  "delph1s",
  "king-huiwen-of-qin",
  "nulluser",
  "dos.do",
  "musifei",
  "smnet",
  "syokatu",
]);
const HOLOGRAPHIC_TITLES = new Set(["种子用户", "破界者", "premium"]);

export function extractUsernameFromAvatarUrl(avatarUrl?: string | null): string | null {
  if (!avatarUrl) return null;
  const m = avatarUrl.match(/\/user_avatar\/[^/]+\/([^/]+)\//);
  return m?.[1]?.trim() || null;
}

export function isSquareAvatar(username?: string | null, avatarUrl?: string | null): boolean {
  const clean = (username || "").trim().replace(/^@/, "").toLowerCase();
  if (clean && SQUARE_AVATAR_USERNAMES.has(clean)) return true;
  const extracted = extractUsernameFromAvatarUrl(avatarUrl)?.toLowerCase();
  return Boolean(extracted && SQUARE_AVATAR_USERNAMES.has(extracted));
}

export function resolveAvatarGlowClass(
  username?: string | null,
  primaryGroupName?: string | null,
  avatarUrl?: string | null
): string {
  const clean = (
    (username || "").trim().replace(/^@/, "") ||
    extractUsernameFromAvatarUrl(avatarUrl) ||
    ""
  ).toLowerCase();
  if (clean && BLUE_GLOW_USERNAMES.has(clean)) {
    return "lindo-glow-blue";
  }
  const grp = (primaryGroupName || "").trim().toLowerCase();
  if (grp === "g-merchant" || grp === "g-merchant2") {
    return "lindo-glow-gold";
  }
  return "";
}

export function getAvatarClassName(
  baseSizeClass: string,
  username?: string | null,
  primaryGroupName?: string | null,
  avatarUrl?: string | null
): string {
  const shape = isSquareAvatar(username, avatarUrl) ? "lindo-avatar-square" : "rounded-full";
  const glow = resolveAvatarGlowClass(username, primaryGroupName, avatarUrl);
  return `${baseSizeClass} ${shape} ${glow} object-cover`.trim();
}

export function isHolographicTitle(userTitle?: string | null): boolean {
  if (!userTitle) return false;
  return HOLOGRAPHIC_TITLES.has(userTitle.trim().toLowerCase());
}

/**
 * Resolves Discourse `user_status.emoji` shortcode (e.g. `bullseye`, `speech_balloon`, `:seedling:`)
 * to official Linux.do Twemoji CDN URL, or returns `null` when `raw` is already a Unicode emoji glyph.
 */
export function resolveEmojiShortcodeUrl(raw?: string | null): string | null {
  if (!raw) return null;
  const trimmed = raw.trim();
  if (!trimmed) return null;
  for (let i = 0; i < trimmed.length; i++) {
    if ((trimmed.codePointAt(i) ?? 0) > 127) {
      return null;
    }
  }
  const clean = trimmed
    .replace(/^:+|:+$/g, "")
    .replace(/:t[2-6]$/i, "")
    .trim()
    .toLowerCase();
  if (!clean || !/^[a-z0-9_+-]+$/.test(clean)) {
    return null;
  }
  const normalized = clean === "+1" ? "thumbsup" : clean === "-1" ? "thumbsdown" : clean;
  return `https://cdn.ldstatic.com/images/emoji/twemoji/${encodeURIComponent(normalized)}.png?v=12`;
}

export function applyPaletteToDocument(isDark: boolean, optionKey: string, fontScale: number): void {
  const opt = BRAND_THEMES.find((t) => t.key === optionKey) ?? BRAND_THEMES[0];
  const root = document.documentElement;
  root.classList.toggle("dark", isDark);
  root.style.colorScheme = isDark ? "dark" : "light";
  root.style.setProperty("--font-scale", String(fontScale || 1));

  if (isDark) {
    root.style.setProperty("--canvas", "#161A23");
    root.style.setProperty("--grouped-canvas", "#161A23");
    root.style.setProperty("--card-surface", "#161A23");
    root.style.setProperty("--feed-surface", "#11141B");
    root.style.setProperty("--selected-card-bg", "#1C212E");
    root.style.setProperty(
      "--selected-card-border",
      `color-mix(in srgb, ${opt.darkChipText} 22%, rgba(255, 255, 255, 0.05))`
    );
    root.style.setProperty("--subtle-surface", "#1B202B");
    root.style.setProperty("--nav-bar-surface", "#0C0E13");
    root.style.setProperty("--tonal-pill", "#212735");
    root.style.setProperty("--on-tonal-pill", "#F1F3F7");
    root.style.setProperty("--ink-primary", "#F1F3F7");
    root.style.setProperty("--ink-secondary", "#9BA3B2");
    root.style.setProperty("--ink-tertiary", "#6E7787");
    root.style.setProperty("--hairline", "rgba(255, 255, 255, 0.058)");
    root.style.setProperty("--hairline-soft", "rgba(255, 255, 255, 0.042)");
    root.style.setProperty("--accent", opt.darkChipText);
    root.style.setProperty("--brand-gold", opt.darkChipText);
    root.style.setProperty("--logo-do", "#F59E0B");
    root.style.setProperty("--tab-indicator", opt.darkChipText);
    root.style.setProperty("--active-chip-bg", opt.darkChipBg);
    root.style.setProperty("--active-chip-text", opt.darkChipText);
    root.style.setProperty("--publish-btn-bg", opt.darkChipText);
    root.style.setProperty("--publish-btn-fg", "#111827");
    root.style.setProperty("--hover-overlay", "rgba(241, 243, 247, 0.046)");
    root.style.setProperty("--press-overlay", "rgba(241, 243, 247, 0.082)");
  } else {
    root.style.setProperty("--canvas", "#FFFFFF");
    root.style.setProperty("--grouped-canvas", "#FFFFFF");
    root.style.setProperty("--card-surface", "#FFFFFF");
    root.style.setProperty("--feed-surface", "#F6F7FA");
    root.style.setProperty("--selected-card-bg", "#FFFFFF");
    root.style.setProperty(
      "--selected-card-border",
      `color-mix(in srgb, ${opt.lightChipText} 24%, rgba(15, 23, 42, 0.06))`
    );
    root.style.setProperty("--subtle-surface", "#EEF1F6");
    root.style.setProperty("--nav-bar-surface", "#EEF1F6");
    root.style.setProperty("--tonal-pill", "#EAECEF");
    root.style.setProperty("--on-tonal-pill", "#161920");
    root.style.setProperty("--ink-primary", "#161920");
    root.style.setProperty("--ink-secondary", "#5E6675");
    root.style.setProperty("--ink-tertiary", "#8A93A3");
    root.style.setProperty("--hairline", "rgba(15, 23, 42, 0.072)");
    root.style.setProperty("--hairline-soft", "rgba(15, 23, 42, 0.05)");
    root.style.setProperty("--accent", opt.lightChipText);
    root.style.setProperty("--brand-gold", opt.key === "OFFICIAL_GOLD" ? "#B45309" : opt.lightChipText);
    root.style.setProperty("--logo-do", "#E58E09");
    root.style.setProperty("--tab-indicator", opt.lightChipText);
    root.style.setProperty("--active-chip-bg", opt.lightChipBg);
    root.style.setProperty("--active-chip-text", opt.lightChipText);
    root.style.setProperty("--publish-btn-bg", opt.key === "OFFICIAL_GOLD" ? opt.swatchColor : opt.lightChipText);
    root.style.setProperty("--publish-btn-fg", opt.key === "OFFICIAL_GOLD" ? "#111827" : "#FFFFFF");
    root.style.setProperty("--hover-overlay", "rgba(15, 23, 42, 0.038)");
    root.style.setProperty("--press-overlay", "rgba(15, 23, 42, 0.068)");
  }
}

let themeSwitchCleanupRaf = 0;

/**
 * Executes a 100% atomic, zero-desync light/dark or accent theme switch:
 * 1. Freezes all per-element CSS hover transitions (`html.lindo-theme-switching`) BEFORE mutating variables or React state
 *    so search bars, quote headers, sort tabs, and quick-reply pills never lag 110–150ms behind the main canvas;
 * 2. When WebKit/WebView2 supports `document.startViewTransition` and animations are enabled, performs a 155ms GPU
 *    full-frame texture crossfade (`::view-transition-old/new(root)`) at native 120/144Hz.
 */
export function performSmoothThemeSwitch(
  commitThemeChange: () => void,
  enableAnimations = true
): void {
  const root = document.documentElement;
  if (themeSwitchCleanupRaf) {
    cancelAnimationFrame(themeSwitchCleanupRaf);
    themeSwitchCleanupRaf = 0;
  }
  root.classList.add("lindo-theme-switching");

  const releaseTransitions = () => {
    themeSwitchCleanupRaf = requestAnimationFrame(() => {
      themeSwitchCleanupRaf = requestAnimationFrame(() => {
        themeSwitchCleanupRaf = 0;
        root.classList.remove("lindo-theme-switching");
      });
    });
  };

  const docWithVt = document as Document & {
    startViewTransition?: (cb: () => void) => { finished: Promise<unknown> };
  };

  if (enableAnimations && typeof docWithVt.startViewTransition === "function") {
    try {
      const vt = docWithVt.startViewTransition(() => {
        flushSync(() => {
          commitThemeChange();
        });
      });
      vt.finished.finally(releaseTransitions);
      return;
    } catch {
      // Fall through to atomic single-frame switch
    }
  }

  flushSync(() => {
    commitThemeChange();
  });
  releaseTransitions();
}

export function resolveCategoryVisual(name: string, colorHex?: string): { dotColor: string; bgAlpha: string } {
  const cleanHex = (colorHex || "").replace("#", "").trim();
  if (/^[0-9a-fA-F]{6}$/.test(cleanHex)) {
    return {
      dotColor: `#${cleanHex}`,
      bgAlpha: `#${cleanHex}22`,
    };
  }
  if (name.includes("开发") || name.includes("快讯") || name.includes("读书")) {
    return { dotColor: "#60A5FA", bgAlpha: "rgba(96,165,250,0.14)" };
  }
  if (name.includes("资源") || name.includes("文档") || name.includes("扬帆")) {
    return { dotColor: "#34D399", bgAlpha: "rgba(52,211,153,0.14)" };
  }
  if (name.includes("搞七") || name.includes("福利") || name.includes("市场")) {
    return { dotColor: "#FBBF24", bgAlpha: "rgba(251,191,36,0.14)" };
  }
  return { dotColor: "#94A3B8", bgAlpha: "rgba(148,163,184,0.14)" };
}

function normalizeAvatarSize(sizePx: number): number {
  if (sizePx <= 24) return 24;
  if (sizePx <= 48) return 48;
  if (sizePx <= 96) return 96;
  if (sizePx <= 144) return 144;
  return 288;
}

/**
 * Resolves static/animated avatar URLs via official Linux.do edge CDNs (`cdn.ldstatic.com` & `avatars.discourse-cdn.com`)
 * with normalized pre-generated Discourse sizes (`24/48/96/144/288`) for zero 302 redirects and instant load.
 */
export function resolveAvatarUrl(template?: string | null, size = 96): string {
  if (!template) return "/brand/lindo-mark.svg";
  const trimmed = template.trim();
  if (!trimmed) return "/brand/lindo-mark.svg";
  if (trimmed.startsWith("/brand/") || trimmed.startsWith("data:") || trimmed.startsWith("blob:")) {
    return trimmed;
  }

  // Animated original WebP/GIF URLs do not contain `{size}`
  const targetSize = String(normalizeAvatarSize(size));
  const sized = trimmed.includes("{size}")
    ? trimmed.replace("{size}", targetSize)
    : trimmed
        .replace(/(\/user_avatar\/[^/]+\/[^/]+\/)\d+(\/.*)/, `$1${targetSize}$2`)
        .replace(/(\/(?:letter_avatar_proxy\/)?v\d+\/letter\/[^/]+\/[^/]+\/)\d+(\.[a-zA-Z0-9]+.*)/, `$1${targetSize}$2`);

  const isGif = sized.split("?")[0].toLowerCase().endsWith(".gif");
  if (!isGif && sized.startsWith("/user_avatar/linux.do/")) {
    return `https://cdn.ldstatic.com${sized}`;
  }
  if (!isGif && sized.startsWith("https://linux.do/user_avatar/linux.do/")) {
    return sized.replace("https://linux.do", "https://cdn.ldstatic.com");
  }
  if (!isGif && sized.startsWith("http://linux.do/user_avatar/linux.do/")) {
    return sized.replace("http://linux.do", "https://cdn.ldstatic.com");
  }
  if (sized.startsWith("/letter_avatar_proxy/")) {
    return `https://avatars.discourse-cdn.com/${sized.replace(/^\/letter_avatar_proxy\//, "")}`;
  }
  if (sized.startsWith("https://linux.do/letter_avatar_proxy/")) {
    return sized.replace("https://linux.do/letter_avatar_proxy", "https://avatars.discourse-cdn.com");
  }
  if (sized.startsWith("http://linux.do/letter_avatar_proxy/")) {
    return sized.replace("http://linux.do/letter_avatar_proxy", "https://avatars.discourse-cdn.com");
  }
  if (sized.startsWith("http://") || sized.startsWith("https://")) {
    return sized;
  }
  if (sized.startsWith("//")) {
    return `https:${sized}`;
  }
  return `https://linux.do${sized.startsWith("/") ? "" : "/"}${sized}`;
}

export function resolveForumAssetUrl(raw?: string | null): string {
  if (!raw) return "";
  const trimmed = raw.trim();
  if (!trimmed) return "";
  if (trimmed.startsWith("data:") || trimmed.startsWith("blob:") || trimmed.startsWith("/brand/")) {
    return trimmed;
  }

  let normalized = trimmed;
  if (normalized.startsWith("//")) {
    normalized = `https:${normalized}`;
  } else if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
    normalized = `https://linux.do${normalized.startsWith("/") ? "" : "/"}${normalized}`;
  }

  const pathPart = normalized.split("?")[0].toLowerCase();
  const isGif = pathPart.endsWith(".gif");

  // Offload immutable public avatars & emojis to official edge CDNs (`cdn.ldstatic.com` & `avatars.discourse-cdn.com`)
  if (!isGif && normalized.startsWith("https://linux.do/user_avatar/linux.do/")) {
    return resolveAvatarUrl(normalized, 144);
  }
  if (normalized.startsWith("https://linux.do/letter_avatar_proxy/")) {
    return normalized.replace("https://linux.do/letter_avatar_proxy", "https://avatars.discourse-cdn.com");
  }
  if (normalized.startsWith("https://linux.do/images/emoji/")) {
    return normalized.replace("https://linux.do", "https://cdn.ldstatic.com");
  }
  return normalized;
}

export function formatRelativeTime(iso?: string | null): string {
  if (!iso) return "";
  const ts = Date.parse(iso);
  if (Number.isNaN(ts)) return iso;
  const diffSec = Math.max(1, Math.floor((Date.now() - ts) / 1000));
  if (diffSec < 60) return `${diffSec}s`;
  const min = Math.floor(diffSec / 60);
  if (min < 60) return `${min}m`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr}h`;
  const day = Math.floor(hr / 24);
  if (day < 30) return `${day}d`;
  const d = new Date(ts);
  return `${d.getMonth() + 1}/${d.getDate()}`;
}

export function formatCompactCount(n?: number | null): string {
  const val = n ?? 0;
  if (val >= 10000) return `${(val / 10000).toFixed(1).replace(/\.0$/, "")}w`;
  if (val >= 1000) return `${(val / 1000).toFixed(1).replace(/\.0$/, "")}k`;
  return String(val);
}

/* ==================== Universal Badge & Flair Engine (1:1 对齐 UniversalBadgeEngine.kt) ==================== */

export const OFFICIAL_SVG_ICON_BASE = "/brand/badges/";

const BUNDLED_BADGE_ICON_SET = new Set([
  "angles-up",
  "at",
  "baby",
  "book-open-reader",
  "bullhorn",
  "certificate",
  "cube",
  "dragon",
  "envelope",
  "fab-apple",
  "fab-git-alt",
  "face-smile",
  "far-eye",
  "far-pen-to-square",
  "file-lines",
  "file-signature",
  "flag",
  "hammer",
  "heart",
  "link",
  "medal",
  "pen",
  "pencil",
  "quote-right",
  "reply",
  "seedling",
  "share-nodes",
  "shield-halved",
  "square-check",
  "stamp",
  "user",
  "user-pen",
  "user-plus",
  "water",
]);

export function badgeTierMeta(badgeTypeId?: number): { color: string; label: string } {
  switch (badgeTypeId) {
    case 1:
      return { color: "#E7C300", label: "金徽章" };
    case 2:
      return { color: "#C0C0C0", label: "银徽章" };
    default:
      return { color: "#CD7F32", label: "铜徽章" };
  }
}

export function resolveBadgeIconUrl(rawIcon?: string | null): string | null {
  const clean = (rawIcon || "").trim();
  if (!clean) return null;
  if (clean.startsWith("/brand/badges/")) return clean;
  if (clean.startsWith("http://") || clean.startsWith("https://") || clean.startsWith("/")) {
    return resolveForumAssetUrl(clean);
  }
  const tokens = clean.split(/\s+/).filter(Boolean);
  if (tokens.length === 0) return null;
  const last = tokens[tokens.length - 1].replace(/^d-icon-/, "").replace(/^fa-/, "");
  if (!/^[a-zA-Z0-9_-]+$/.test(last)) return null;
  let prefix = "";
  if (tokens[0] === "fab" || tokens[0] === "fa-brands") prefix = "fab-";
  else if (tokens[0] === "far" || tokens[0] === "fa-regular") prefix = "far-";
  const slug = `${prefix}${last}`;
  const matched = BUNDLED_BADGE_ICON_SET.has(slug)
    ? slug
    : BUNDLED_BADGE_ICON_SET.has(last)
      ? last
      : "certificate";
  return `${OFFICIAL_SVG_ICON_BASE}${matched}.svg`;
}

export function resolveBadgeArtwork(badge: {
  id?: number;
  name?: string;
  icon?: string | null;
  imageUrl?: string | null;
}): { url: string; isCustomImage: boolean } {
  if (badge.imageUrl && badge.imageUrl.trim()) {
    return { url: resolveForumAssetUrl(badge.imageUrl.trim()), isCustomImage: true };
  }
  const fromIcon = resolveBadgeIconUrl(badge.icon);
  if (fromIcon) {
    return { url: fromIcon, isCustomImage: false };
  }
  const n = badge.name || "";
  const id = badge.id || 0;
  let iconName = "certificate";
  if ((id >= 45 && id <= 47) || /全年不落|狂热|迷人/.test(n)) iconName = "far-eye";
  else if (/apple|ios|ipad|iphone|mac/i.test(n)) iconName = "fab-apple";
  else if (/受欢迎|喜爱|感激|赞|秋波|博爱/.test(n)) iconName = "heart";
  else if (/善解人意|表情/.test(n)) iconName = "face-smile";
  else if (id === 102 || n.includes("种子")) iconName = "seedling";
  else if (/阅读|读者/.test(n)) iconName = "book-open-reader";
  else if (n.includes("编辑")) iconName = "pen";
  else if (/链接|分享/.test(n)) iconName = "share-nodes";
  else if (/解决方案|解答/.test(n)) iconName = "square-check";
  return { url: `${OFFICIAL_SVG_ICON_BASE}${iconName}.svg`, isCustomImage: false };
}

export function resolveFlairVisual(flair?: {
  url?: string | null;
  name?: string | null;
  bgColorHex?: string | null;
  fgColorHex?: string | null;
} | null): {
  url: string;
  isImage: boolean;
  bg?: string;
  fg?: string;
  name?: string;
} | null {
  const raw = flair?.url?.trim();
  if (!raw) return null;
  const isImg =
    raw.startsWith("http://") ||
    raw.startsWith("https://") ||
    raw.startsWith("/") ||
    /\.(png|webp|gif|jpe?g|svg)(\?|$)/i.test(raw);
  const url = isImg ? resolveForumAssetUrl(raw) : resolveBadgeIconUrl(raw) || "";
  if (!url) return null;
  const cleanHex = (h?: string | null) => {
    const s = (h || "").replace(/^#/, "").trim();
    return /^[0-9a-fA-F]{3,8}$/.test(s) ? `#${s}` : undefined;
  };
  return {
    url,
    isImage: isImg && !url.startsWith(OFFICIAL_SVG_ICON_BASE),
    bg: cleanHex(flair?.bgColorHex),
    fg: cleanHex(flair?.fgColorHex),
    name: flair?.name || undefined,
  };
}

/* ==================== BoostInputStats (1:1 对齐 LinuxDoModels.kt MAX_VISIBLE_LENGTH=16, MAX_EMOJI=5) ==================== */

const SHORTCODE_RE = /(?<![a-zA-Z0-9:]):[a-zA-Z0-9_+-]{2,36}(?::t\d)?:(?![a-zA-Z0-9:])/g;

function isUnicodeEmojiCluster(cluster: string): boolean {
  for (const ch of cluster) {
    const cp = ch.codePointAt(0) ?? 0;
    if (
      (cp >= 0x1f000 && cp <= 0x1faff) ||
      (cp >= 0x2600 && cp <= 0x27bf) ||
      (cp >= 0x2300 && cp <= 0x23ff) ||
      cp === 0x2b50 ||
      cp === 0x2764 ||
      cp === 0xfe0f
    ) {
      return true;
    }
  }
  return false;
}

export function computeBoostStats(raw: string): {
  visibleLength: number;
  emojiCount: number;
  maxVisibleLength: number;
  maxEmojiCount: number;
  canAddEmoji: boolean;
  exceedsLength: boolean;
  exceedsEmojiLimit: boolean;
  canSubmit: boolean;
} {
  const maxVisibleLength = 16;
  const maxEmojiCount = 5;
  const trimmed = raw.trim();
  if (!trimmed) {
    return {
      visibleLength: 0,
      emojiCount: 0,
      maxVisibleLength,
      maxEmojiCount,
      canAddEmoji: true,
      exceedsLength: false,
      exceedsEmojiLimit: false,
      canSubmit: false,
    };
  }
  let shortcodeCount = 0;
  const replaced = trimmed.replace(SHORTCODE_RE, () => {
    shortcodeCount++;
    return "\uFFFC";
  });
  const segments =
    typeof Intl !== "undefined" && "Segmenter" in Intl
      ? Array.from(new Intl.Segmenter("zh", { granularity: "grapheme" }).segment(replaced)).map(
          (s) => s.segment
        )
      : Array.from(replaced);
  let unicodeEmojiCount = 0;
  for (const seg of segments) {
    if (seg !== "\uFFFC" && isUnicodeEmojiCluster(seg)) {
      unicodeEmojiCount++;
    }
  }
  const visibleLength = segments.length;
  const emojiCount = shortcodeCount + unicodeEmojiCount;
  const exceedsLength = visibleLength > maxVisibleLength;
  const exceedsEmojiLimit = emojiCount > maxEmojiCount;
  return {
    visibleLength,
    emojiCount,
    maxVisibleLength,
    maxEmojiCount,
    canAddEmoji: visibleLength + 1 <= maxVisibleLength && emojiCount < maxEmojiCount,
    exceedsLength,
    exceedsEmojiLimit,
    canSubmit: visibleLength >= 1 && !exceedsLength && !exceedsEmojiLimit,
  };
}
