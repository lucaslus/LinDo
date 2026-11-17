import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import {
  ArrowLeft,
  BookmarkSimple,
  DotsThree,
  Sparkle,
  Heart,
  Smiley,
  LinkSimple,
  Quotes,
  ArrowBendUpLeft,
  PencilSimple,
  RocketLaunch,
  User,
  Bell,
  ArrowSquareOut,
  CaretRight,
  CaretDown,
  CaretUp,
  PaperPlaneRight,
  Trash,
  Check,
  ShieldCheck,
  CheckCircle,
  ChartBar,
  ChatCircle,
  Lock,
  AppleLogo,
  PushPin,
  Archive,
  Flag,
  X,
} from "@phosphor-icons/react";
import { openUrl } from "@tauri-apps/plugin-opener";
import type {
  ComposerLaunchRequest,
  GrantedBadge,
  PostItemModel,
  PostPollModel,
  TopicDetailModel,
  TopicSummary,
  UserProfile,
} from "../lib/types";
import {
  acceptPostSolution,
  archivePrivateMessage,
  deletePostBoost,
  deletePostFloor,
  fetchPostReplies,
  fetchPostsByIds,
  fetchTopicAiSummary,
  flagPost,
  handleTitlebarMouseDown,
  removePollVote,
  reportTopicTimings,
  sendPostBoost,
  setTopicNotificationLevel,
  SiteError,
  togglePostBookmark,
  togglePostReaction,
  voteInPoll,
} from "../lib/api";
import {
  classifyExternalUrl,
  type ExternalUrlAssessment,
  parseInternalTopicLink,
  prepareCookedHtml,
  rememberTrustedExternalHost,
} from "../lib/cooked";
import { observeCookedForumImages } from "../lib/forumMedia";
import {
  computeBoostStats,
  formatCompactCount,
  formatRelativeTime,
  isHolographicTitle,
  resolveAvatarUrl,
  resolveCategoryVisual,
} from "../lib/palette";
import {
  AvatarWithFlair,
  ConfirmDialog,
  ExternalLinkSafetyModal,
  MascotExpression,
  OfficialBadgeIcon,
  SmoothRefreshIcon,
  StatusEmojiIcon,
} from "./SecondaryScreens";

interface TopicReaderPaneProps {
  summary: TopicSummary | null;
  detail: TopicDetailModel | null;
  isLoading: boolean;
  errorMessage: string | null;
  targetFloor: number | null;
  targetJumpNonce: number;
  onlyOpFilter: boolean;
  isAuthorized: boolean;
  currentUser: UserProfile | null;
  confirmedReadFloor?: number;
  showBackButton: boolean;
  hideQuickReplyBar?: boolean;
  emptyPlaceholderText?: string;
  onBack: () => void;
  onRefresh: () => void;
  onToggleOnlyOp: () => void;
  onAppendPosts: (posts: PostItemModel[], position: "before" | "after" | "replace") => void;
  onRemovePost?: (postId: number) => void;
  onOpenComposer: (req: ComposerLaunchRequest) => void;
  onInspectUser: (username: string) => void;
  onInspectBadge?: (badge: GrantedBadge) => void;
  onOpenImageLightbox: (src: string) => void;
  onNavigateTopicLink: (topicId: number, postNumber?: number) => void;
  onOpenInAppUrl?: (url: string) => void;
  onPmArchiveChanged?: () => void;
  onRequestGuestLogin: (title: string, subtitle: string) => void;
  onTriggerVerify: () => void;
  onFloorSeen: (topicId: number, floor: number) => void;
}

const REACTION_CHOICES: Array<{ id: string; emoji: string; label: string }> = [
  { id: "heart", emoji: "❤️", label: "赞" },
  { id: "+1", emoji: "👍", label: "支持" },
  { id: "laughing", emoji: "😆", label: "欢乐" },
  { id: "open_mouth", emoji: "😮", label: "惊讶" },
  { id: "clap", emoji: "👏", label: "鼓掌" },
  { id: "confetti_ball", emoji: "🎉", label: "庆祝" },
  { id: "hugs", emoji: "🤗", label: "抱抱" },
  { id: "distorted_face", emoji: "🫠", label: "融化" },
];

const REACTION_EMOJI_MAP: Record<string, string> = {
  heart: "❤️",
  "+1": "👍",
  thumbsup: "👍",
  "-1": "👎",
  laughing: "😆",
  joy: "😂",
  rofl: "🤣",
  open_mouth: "😮",
  clap: "👏",
  confetti_ball: "🎉",
  tada: "🎉",
  hugs: "🤗",
  distorted_face: "🫠",
  saluting_face: "🫡",
  rocket: "🚀",
  fire: "🔥",
  sparkles: "✨",
  "100": "💯",
  pray: "🙏",
  eyes: "👀",
  thinking: "🤔",
  sob: "😭",
};

const BOOST_QUICK_EMOJIS = [
  "🚀",
  "🎉",
  "👍",
  "❤️",
  "🔥",
  "👏",
  "😂",
  "🤣",
  "😮",
  "🤗",
  "🫠",
  "🫡",
  "✨",
  "💯",
  "🙏",
  "💡",
];

const BOOST_QUICK_PHRASES = [
  "✋ 俺也一样",
  "🚀 前排支持",
  "🎉 感谢分享",
  "🔥 太强了",
  "❤️ 学到了",
  "🫡 瑞思拜",
];

function resolveReactionFallbackImg(reactionId: string): string {
  const clean = reactionId.trim().replace(/^:|:$/g, "").toLowerCase();
  const normalized = ["+1", "thumbsup", "thumb_up"].includes(clean)
    ? "+1"
    : ["-1", "thumbsdown"].includes(clean)
      ? "-1"
      : clean;
  return `https://cdn.ldstatic.com/images/emoji/twemoji/${normalized}.png?v=12`;
}

const NOTIFICATION_LEVELS: Array<{ level: number; label: string; desc: string }> = [
  { level: 3, label: "关注", desc: "每条新回复均通知" },
  { level: 2, label: "跟踪", desc: "显示新回复数量" },
  { level: 1, label: "常规", desc: "仅被 @ 或回复时通知" },
  { level: 0, label: "免扰", desc: "屏蔽此话题通知" },
];

/**
 * Memoized post HTML body so scrolling, visible-floor updates, or quote popovers NEVER re-run
 * `prepareCookedHtml` or `dangerouslySetInnerHTML`, keeping native text selection 100% intact & 120fps smooth.
 */
const MemoizedCookedBody = React.memo<{
  rawHtml: string;
  stripPolls: boolean;
  onCookedClick: (e: React.MouseEvent<HTMLDivElement>) => void;
}>(
  ({ rawHtml, stripPolls, onCookedClick }) => {
    const bodyRef = useRef<HTMLDivElement | null>(null);
    const sanitized = useMemo(
      () => prepareCookedHtml(rawHtml, { stripPolls }),
      [rawHtml, stripPolls]
    );
    useEffect(() => {
      return observeCookedForumImages(bodyRef.current);
    }, [sanitized]);
    return (
      <div
        ref={bodyRef}
        className="lindo-cooked mt-3"
        onClick={onCookedClick}
        dangerouslySetInnerHTML={{ __html: sanitized }}
      />
    );
  },
  (prev, next) => prev.rawHtml === next.rawHtml && prev.stripPolls === next.stripPolls
);

export const TopicReaderPane: React.FC<TopicReaderPaneProps> = ({
  summary,
  detail,
  isLoading,
  errorMessage,
  targetFloor,
  targetJumpNonce,
  onlyOpFilter,
  isAuthorized,
  currentUser,
  confirmedReadFloor = 0,
  showBackButton,
  hideQuickReplyBar = false,
  emptyPlaceholderText = "选择左侧话题开始阅读",
  onBack,
  onRefresh,
  onToggleOnlyOp,
  onAppendPosts,
  onRemovePost,
  onOpenComposer,
  onInspectUser,
  onInspectBadge,
  onOpenImageLightbox,
  onNavigateTopicLink,
  onOpenInAppUrl,
  onPmArchiveChanged,
  onRequestGuestLogin,
  onTriggerVerify,
  onFloorSeen,
}) => {
  const scrollContainerRef = useRef<HTMLDivElement | null>(null);
  const [moreMenuOpen, setMoreMenuOpen] = useState(false);
  const [notifMenuOpen, setNotifMenuOpen] = useState(false);
  const [copiedLink, setCopiedLink] = useState(false);
  const [aiSummaryOpen, setAiSummaryOpen] = useState(false);
  const [aiSummaryText, setAiSummaryText] = useState<string | null>(null);
  const [aiSummaryLoading, setAiSummaryLoading] = useState(false);
  const [highlightedFloor, setHighlightedFloor] = useState<number | null>(null);
  const [currentVisibleFloor, setCurrentVisibleFloor] = useState(1);
  const [floorNavOpen, setFloorNavOpen] = useState(false);
  const [floorInputVal, setFloorInputVal] = useState("1");
  const [loadingMoreBottom, setLoadingMoreBottom] = useState(false);
  const [loadingEarlier, setLoadingEarlier] = useState(false);
  const [activeReactionPickerPostId, setActiveReactionPickerPostId] = useState<number | null>(null);
  const [activeBoostComposerPostId, setActiveBoostComposerPostId] = useState<number | null>(null);
  const [boostDraftText, setBoostDraftText] = useState("");
  const [boostSubmitting, setBoostSubmitting] = useState(false);
  const [expandedBoostId, setExpandedBoostId] = useState<number | null>(null);
  const [expandedStatusPostId, setExpandedStatusPostId] = useState<number | null>(null);
  const [copiedFloorId, setCopiedFloorId] = useState<number | null>(null);
  const [deletingPost, setDeletingPost] = useState<PostItemModel | null>(null);
  const [flaggingPost, setFlaggingPost] = useState<PostItemModel | null>(null);
  const [pendingExternalLink, setPendingExternalLink] =
    useState<ExternalUrlAssessment | null>(null);
  const [expandedRepliesMap, setExpandedRepliesMap] = useState<Record<number, PostItemModel[]>>({});
  const [loadingRepliesPostId, setLoadingRepliesPostId] = useState<number | null>(null);
  const [toastMsg, setToastMsg] = useState<string | null>(null);
  const [locallyViewedFloors, setLocallyViewedFloors] = useState<Set<number>>(() => new Set());
  const locallyViewedRef = useRef<Set<number>>(new Set());
  const confirmedReadFloorRef = useRef(confirmedReadFloor);
  confirmedReadFloorRef.current = confirmedReadFloor;

  const knownAuthorBadges = useMemo(() => {
    const map = new Map<number, GrantedBadge[]>();
    for (const p of detail?.posts ?? []) {
      if (p.userId && p.authorBadges && p.authorBadges.length > 0 && !map.has(p.userId)) {
        map.set(p.userId, p.authorBadges);
      }
    }
    return map;
  }, [detail?.posts]);

  // Floating selection "引用并回复" state
  const [selectionQuote, setSelectionQuote] = useState<{
    text: string;
    post: PostItemModel;
    x: number;
    y: number;
  } | null>(null);

  const visibleFloorRef = useRef(1);
  const floorBadgeTextRef = useRef<HTMLSpanElement | null>(null);
  const selectionQuoteRef = useRef(selectionQuote);
  selectionQuoteRef.current = selectionQuote;
  const detailRef = useRef(detail);
  detailRef.current = detail;
  const loadingMoreBottomRef = useRef(false);
  loadingMoreBottomRef.current = loadingMoreBottom;
  const knownAuthorBadgesRef = useRef(knownAuthorBadges);
  knownAuthorBadgesRef.current = knownAuthorBadges;

  const handleCookedClick = useCallback(
    (e: React.MouseEvent<HTMLDivElement>) => {
      const target = e.target as HTMLElement;
      const copyBtn = target.closest<HTMLButtonElement>("[data-copy-code]");
      if (copyBtn) {
        e.preventDefault();
        const origLabel = copyBtn.textContent || "复制";
        const codeText = copyBtn.parentElement?.querySelector("code")?.textContent || "";
        navigator.clipboard.writeText(codeText);
        copyBtn.textContent = "已复制";
        setTimeout(() => {
          copyBtn.textContent = origLabel;
        }, 1500);
        return;
      }
      if (
        target instanceof HTMLImageElement &&
        !target.classList.contains("emoji") &&
        !target.classList.contains("avatar")
      ) {
        e.preventDefault();
        const fullSrc =
          target.getAttribute("data-full-src") ||
          target.getAttribute("data-canonical-src") ||
          target.src;
        if (fullSrc) onOpenImageLightbox(fullSrc);
        return;
      }
      if (target.classList.contains("spoiled")) {
        target.classList.toggle("revealed");
        return;
      }
      const anchor = target.closest("a");
      if (anchor && anchor.href) {
        e.preventDefault();
        if (anchor.classList.contains("mention") && anchor.textContent?.startsWith("@")) {
          onInspectUser(anchor.textContent.slice(1).trim());
          return;
        }
        const internal = parseInternalTopicLink(anchor.href);
        if (internal) {
          onNavigateTopicLink(internal.topicId, internal.postNumber);
          return;
        }
        // Internal user profile links `/u/{username}` open native PublicUserCardModal
        try {
          const u = new URL(anchor.href, "https://linux.do");
          if (u.hostname === "linux.do") {
            const um = u.pathname.match(/^\/u\/([^/?#]+)\/?$/i);
            if (um && um[1] && um[1].toLowerCase() !== "account-created") {
              onInspectUser(decodeURIComponent(um[1]));
              return;
            }
          }
        } catch {
          // fall through to safety classifier
        }

        const assessment = classifyExternalUrl(anchor.href, anchor.textContent);
        if (assessment.tier === "BLOCKED") {
          setToastMsg(assessment.riskReason || "已拦截不安全的跳转链接");
          return;
        }
        if (assessment.tier === "INTERNAL") {
          if (assessment.hostname === "mailto") {
            openUrl(assessment.normalizedUrl);
          } else if (onOpenInAppUrl) {
            onOpenInAppUrl(assessment.normalizedUrl);
          } else {
            openUrl(assessment.normalizedUrl);
          }
          return;
        }
        if (assessment.tier === "TRUSTED") {
          openUrl(assessment.normalizedUrl);
          return;
        }
        setPendingExternalLink(assessment);
      }
    },
    [onOpenImageLightbox, onInspectUser, onNavigateTopicLink, onOpenInAppUrl]
  );

  useEffect(() => {
    setMoreMenuOpen(false);
    setNotifMenuOpen(false);
    setAiSummaryOpen(false);
    setAiSummaryText(null);
    setFloorNavOpen(false);
    setSelectionQuote(null);
    setExpandedRepliesMap({});
    locallyViewedRef.current = new Set();
    setLocallyViewedFloors(new Set());
  }, [summary?.id]);

  useEffect(() => {
    if (!toastMsg) return;
    const t = setTimeout(() => setToastMsg(null), 2600);
    return () => clearTimeout(t);
  }, [toastMsg]);

  // Zero-flash synchronous layout jump BEFORE browser paint when opening a specific floor!
  useLayoutEffect(() => {
    const container = scrollContainerRef.current;
    if (!container || !detail || detail.posts.length === 0) return;
    if (targetFloor && targetFloor > 1) {
      const el = container.querySelector<HTMLElement>(`[data-floor="${targetFloor}"]`);
      if (el) {
        el.scrollIntoView({ block: "center", behavior: "instant" as ScrollBehavior });
        visibleFloorRef.current = targetFloor;
        setCurrentVisibleFloor(targetFloor);
        setHighlightedFloor(targetFloor);
        const timer = setTimeout(() => setHighlightedFloor(null), 2200);
        return () => clearTimeout(timer);
      }
    } else if (!targetFloor) {
      container.scrollTop = 0;
      const initialFloor = detail.posts[0]?.postNumber ?? 1;
      visibleFloorRef.current = initialFloor;
      setCurrentVisibleFloor(initialFloor);
    }
  }, [detail?.id, targetFloor, targetJumpNonce]);

  // 144Hz zero-jank scroll tracking: ZERO React re-renders during active scrolling (updates floor badge via direct DOM ref, settles React state & unread dwell only on scroll idle!)
  useEffect(() => {
    const container = scrollContainerRef.current;
    if (!container || !detail?.id) return;
    let rafId = 0;
    let settleTimer: ReturnType<typeof setTimeout> | null = null;
    let dwellTimer: ReturnType<typeof setTimeout> | null = null;
    let reportTimer: ReturnType<typeof setTimeout> | null = null;
    let lastMeasureMs = 0;
    let lastSettleArmMs = 0;
    const pendingDwell = new Set<number>();
    const seenServerBatch = new Set<number>();

    // Cache floor elements once per post-list mutation so we NEVER run querySelectorAll during 144Hz scroll frames!
    const floorNodes = Array.from(container.querySelectorAll<HTMLElement>("[data-floor]"));
    const floorNumbers = floorNodes.map((n) => Number(n.dataset.floor || 1));

    const scheduleDwellFlush = () => {
      if (dwellTimer) clearTimeout(dwellTimer);
      dwellTimer = setTimeout(() => {
        const curDetail = detailRef.current;
        if (!curDetail || pendingDwell.size === 0) return;

        let mutated = false;
        let maxFloor = visibleFloorRef.current;
        pendingDwell.forEach((f) => {
          if (f > maxFloor) maxFloor = f;
          if (!locallyViewedRef.current.has(f)) {
            locallyViewedRef.current.add(f);
            mutated = true;
          }
          seenServerBatch.add(f);
        });
        pendingDwell.clear();

        if (mutated) {
          setLocallyViewedFloors(new Set(locallyViewedRef.current));
        }
        onFloorSeen(curDetail.id, maxFloor);

        if (reportTimer) clearTimeout(reportTimer);
        reportTimer = setTimeout(() => {
          if (isAuthorized && seenServerBatch.size > 0 && detailRef.current) {
            const batch = Array.from(seenServerBatch);
            seenServerBatch.clear();
            reportTopicTimings(detailRef.current.id, batch);
          }
        }, 650);
      }, 720);
    };

    const collectVisibleFloors = (commitReactState: boolean) => {
      const curDetail = detailRef.current;
      if (!curDetail) return;

      const viewTop = container.scrollTop;
      const viewHeight = container.clientHeight;
      const viewMid = viewTop + viewHeight * 0.38;
      const viewBottom = viewTop + viewHeight;

      let bestFloor = visibleFloorRef.current;
      for (let i = 0; i < floorNodes.length; i++) {
        const node = floorNodes[i];
        const top = node.offsetTop;
        const f = floorNumbers[i];
        if (top <= viewMid) {
          bestFloor = f;
        }
        if (commitReactState) {
          const bottom = top + node.offsetHeight;
          if (top < viewBottom - 16 && bottom > viewTop + 16) {
            pendingDwell.add(f);
          }
        }
      }

      if (bestFloor !== visibleFloorRef.current) {
        visibleFloorRef.current = bestFloor;
        if (floorBadgeTextRef.current) {
          const total = Math.max(curDetail.postsCount ?? 1, 1);
          floorBadgeTextRef.current.textContent = `${bestFloor} / ${total} 楼`;
        }
      }

      if (commitReactState) {
        setCurrentVisibleFloor((prev) =>
          prev === visibleFloorRef.current ? prev : visibleFloorRef.current
        );
        if (pendingDwell.size > 0) {
          scheduleDwellFlush();
        }
      }

      // Auto-load next post stream chunk near bottom
      const distBottom = container.scrollHeight - viewBottom;
      if (distBottom < 460 && !loadingMoreBottomRef.current && !onlyOpFilter) {
        const loadedIds = new Set(curDetail.posts.map((p) => p.id));
        const lastPost = curDetail.posts[curDetail.posts.length - 1];
        if (lastPost) {
          const lastIdx = curDetail.streamIds.indexOf(lastPost.id);
          if (lastIdx >= 0 && lastIdx < curDetail.streamIds.length - 1) {
            const nextIds = curDetail.streamIds
              .slice(lastIdx + 1, lastIdx + 21)
              .filter((id) => !loadedIds.has(id));
            if (nextIds.length > 0) {
              loadingMoreBottomRef.current = true;
              setLoadingMoreBottom(true);
              const effLastRead = Math.max(
                curDetail.lastReadPostNumber ?? 0,
                confirmedReadFloorRef.current
              );
              fetchPostsByIds(
                curDetail.id,
                nextIds,
                knownAuthorBadgesRef.current,
                effLastRead > 0 ? effLastRead : curDetail.lastReadPostNumber
              )
                .then((more) => {
                  if (more.length > 0) onAppendPosts(more, "after");
                })
                .catch(() => {})
                .finally(() => {
                  loadingMoreBottomRef.current = false;
                  setLoadingMoreBottom(false);
                });
            }
          }
        }
      }
    };

    const handleScroll = () => {
      if (selectionQuoteRef.current !== null) {
        setSelectionQuote(null);
      }
      const now = performance.now();
      // Low-frequency DOM-only floor label & prefetch check (at most ~7 times/sec, ZERO React state updates!)
      if (now - lastMeasureMs >= 135 && rafId === 0) {
        lastMeasureMs = now;
        rafId = requestAnimationFrame(() => {
          rafId = 0;
          collectVisibleFloors(false);
        });
      }
      // Settle React state + unread-dot dwell timer only after scrolling pauses
      if (now - lastSettleArmMs >= 55) {
        lastSettleArmMs = now;
        if (settleTimer) clearTimeout(settleTimer);
        settleTimer = setTimeout(() => {
          settleTimer = null;
          collectVisibleFloors(true);
        }, 160);
      }
    };

    // Immediately inspect initial viewport so visible unread dots fade out smoothly even without scrolling!
    const initRaf = requestAnimationFrame(() => collectVisibleFloors(true));

    container.addEventListener("scroll", handleScroll, { passive: true });
    return () => {
      container.removeEventListener("scroll", handleScroll);
      cancelAnimationFrame(initRaf);
      if (rafId !== 0) cancelAnimationFrame(rafId);
      if (settleTimer) clearTimeout(settleTimer);
      if (dwellTimer) clearTimeout(dwellTimer);
      if (reportTimer) clearTimeout(reportTimer);
    };
  }, [detail?.id, detail?.posts.length, isAuthorized, onlyOpFilter, onAppendPosts, onFloorSeen]);

  // Passive, zero-interference text selection quote (`引用并回复`): never runs while pointer is dragging!
  useEffect(() => {
    const container = scrollContainerRef.current;
    if (!container || !summary?.id) return;

    let pointerDown = false;
    let quoteTimer: ReturnType<typeof setTimeout> | null = null;

    const onPointerDown = (ev: PointerEvent) => {
      pointerDown = true;
      if (quoteTimer) {
        clearTimeout(quoteTimer);
        quoteTimer = null;
      }
      const target = ev.target as HTMLElement | null;
      if (selectionQuoteRef.current && !target?.closest("[data-selection-quote-popover]")) {
        setSelectionQuote(null);
      }
    };

    const onPointerUp = () => {
      pointerDown = false;
      if (quoteTimer) clearTimeout(quoteTimer);
      quoteTimer = setTimeout(() => {
        const sel = window.getSelection();
        if (!sel || sel.isCollapsed || sel.rangeCount === 0) {
          if (selectionQuoteRef.current) setSelectionQuote(null);
          return;
        }
        const text = sel.toString().trim();
        if (text.length < 2 || text.length > 1200) {
          if (selectionQuoteRef.current) setSelectionQuote(null);
          return;
        }
        const anchorEl =
          sel.anchorNode instanceof HTMLElement
            ? sel.anchorNode
            : sel.anchorNode?.parentElement ?? null;
        const focusEl =
          sel.focusNode instanceof HTMLElement
            ? sel.focusNode
            : sel.focusNode?.parentElement ?? null;
        if (
          !anchorEl ||
          !focusEl ||
          !container.contains(anchorEl) ||
          !container.contains(focusEl) ||
          !anchorEl.closest(".lindo-cooked")
        ) {
          if (selectionQuoteRef.current) setSelectionQuote(null);
          return;
        }
        const floorEl = anchorEl.closest<HTMLElement>("[data-floor]");
        const floorNum = floorEl ? Number(floorEl.dataset.floor || 0) : 0;
        const matchedPost = detailRef.current?.posts.find((p) => p.postNumber === floorNum);
        if (!matchedPost) return;

        const rect = sel.getRangeAt(0).getBoundingClientRect();
        if (rect && rect.width > 0) {
          setSelectionQuote({
            text,
            post: matchedPost,
            x: Math.max(90, Math.min(window.innerWidth - 110, rect.left + rect.width / 2)),
            y: Math.max(52, rect.top - 40),
          });
        }
      }, 75);
    };

    const onSelectionChange = () => {
      if (pointerDown || !selectionQuoteRef.current) return;
      const sel = window.getSelection();
      if (!sel || sel.isCollapsed) {
        setSelectionQuote(null);
      }
    };

    container.addEventListener("pointerdown", onPointerDown, { passive: true });
    window.addEventListener("pointerup", onPointerUp, { passive: true });
    document.addEventListener("selectionchange", onSelectionChange, { passive: true });

    return () => {
      container.removeEventListener("pointerdown", onPointerDown);
      window.removeEventListener("pointerup", onPointerUp);
      document.removeEventListener("selectionchange", onSelectionChange);
      if (quoteTimer) clearTimeout(quoteTimer);
    };
  }, [summary?.id]);

  const jumpToFloor = async (floorNum: number) => {
    if (!detail) return;
    const container = scrollContainerRef.current;
    const existing = container?.querySelector<HTMLElement>(`[data-floor="${floorNum}"]`);
    if (existing) {
      existing.scrollIntoView({ behavior: "smooth", block: "center" });
      setHighlightedFloor(floorNum);
      setTimeout(() => setHighlightedFloor(null), 2000);
      return;
    }
    const targetStreamIdx = Math.max(0, Math.min(detail.streamIds.length - 1, floorNum - 1));
    const start = Math.max(0, targetStreamIdx - 5);
    const slice = detail.streamIds.slice(start, start + 20);
    if (slice.length > 0) {
      try {
        const effLastRead = Math.max(detail.lastReadPostNumber ?? 0, confirmedReadFloor);
        const fetched = await fetchPostsByIds(
          detail.id,
          slice,
          knownAuthorBadges,
          effLastRead > 0 ? effLastRead : detail.lastReadPostNumber
        );
        if (fetched.length > 0) {
          onAppendPosts(fetched, "replace");
          requestAnimationFrame(() => {
            const el = scrollContainerRef.current?.querySelector<HTMLElement>(
              `[data-floor="${floorNum}"]`
            );
            el?.scrollIntoView({ block: "center" });
            setHighlightedFloor(floorNum);
            setTimeout(() => setHighlightedFloor(null), 2000);
          });
        }
      } catch (e: any) {
        setToastMsg(e?.message || "楼层加载失败");
      }
    }
  };

  const missingEarlierIds = useMemo(() => {
    if (!detail || detail.posts.length === 0 || onlyOpFilter) return [];
    const firstId = detail.posts[0].id;
    const idx = detail.streamIds.indexOf(firstId);
    if (idx <= 0) return [];
    return detail.streamIds.slice(Math.max(0, idx - 20), idx);
  }, [detail, onlyOpFilter]);

  const handleLoadEarlier = async () => {
    if (!detail || missingEarlierIds.length === 0 || loadingEarlier) return;
    const container = scrollContainerRef.current;
    const prevHeight = container?.scrollHeight ?? 0;
    setLoadingEarlier(true);
    try {
      const effLastRead = Math.max(detail.lastReadPostNumber ?? 0, confirmedReadFloor);
      const earlier = await fetchPostsByIds(
        detail.id,
        missingEarlierIds,
        knownAuthorBadges,
        effLastRead > 0 ? effLastRead : detail.lastReadPostNumber
      );
      if (earlier.length > 0) {
        onAppendPosts(earlier, "before");
        requestAnimationFrame(() => {
          if (container) {
            container.scrollTop += container.scrollHeight - prevHeight;
          }
        });
      }
    } finally {
      setLoadingEarlier(false);
    }
  };

  const handleToggleInlineReplies = async (post: PostItemModel) => {
    if (!summary) return;
    if (expandedRepliesMap[post.id]) {
      setExpandedRepliesMap((prev) => {
        const next = { ...prev };
        delete next[post.id];
        return next;
      });
      return;
    }
    setLoadingRepliesPostId(post.id);
    try {
      const replies = await fetchPostReplies(post.id, summary.id);
      setExpandedRepliesMap((prev) => ({ ...prev, [post.id]: replies }));
    } catch (err: any) {
      setToastMsg(err?.message || "加载回复列表失败");
    } finally {
      setLoadingRepliesPostId(null);
    }
  };

  if (!summary) {
    return (
      <main
        className="relative flex h-full flex-1 flex-col items-center justify-center select-none"
        style={{ backgroundColor: "var(--canvas)" }}
      >
        <div
          onMouseDown={handleTitlebarMouseDown}
          className="absolute inset-x-0 top-0 h-[50px]"
        />
        <img
          src="/brand/lingdu-welcome.svg"
          alt="Lindo"
          className="mb-3 h-20 w-20 opacity-80 pointer-events-none"
        />
        <p className="text-[13.5px]" style={{ color: "var(--ink-tertiary)" }}>
          {emptyPlaceholderText}
        </p>
      </main>
    );
  }

  const totalFloors = Math.max(detail?.postsCount ?? summary.postsCount, 1);
  const catVis = resolveCategoryVisual(
    detail?.categoryName || summary.categoryName || "",
    detail?.categoryColorHex || summary.categoryColorHex
  );
  const firstPost = detail?.posts.find((p) => p.postNumber === 1) ?? detail?.posts[0];
  const currentNotifLabel =
    NOTIFICATION_LEVELS.find((l) => l.level === (detail?.notificationLevel ?? 1))?.label ?? "常规";

  return (
    <main
      className="relative flex h-full flex-1 flex-col min-w-0"
      style={{ backgroundColor: "var(--canvas)" }}
    >
      {/* 1. 阅读器沉浸式顶栏（仅顶栏 50px 响应窗口拖拽，绝不干扰正文拖拽与截图） */}
      <header
        onMouseDown={handleTitlebarMouseDown}
        className="lindo-hairline-b flex h-[50px] shrink-0 items-center justify-between px-4"
        style={{ backgroundColor: "var(--card-surface)" }}
      >
        <div className="flex min-w-0 items-center gap-2">
          {showBackButton && (
            <button
              type="button"
              onClick={onBack}
              className="lindo-no-drag lindo-interactive flex h-8 w-8 items-center justify-center rounded-full"
              style={{ color: "var(--ink-primary)" }}
            >
              <ArrowLeft size={18} />
            </button>
          )}
          <span className="text-[14.5px] font-semibold" style={{ color: "var(--ink-primary)" }}>
            话题
          </span>
          {onlyOpFilter && (
            <button
              type="button"
              onClick={onToggleOnlyOp}
              className="lindo-no-drag rounded-full px-2 py-0.5 text-[11px] font-semibold cursor-pointer"
              style={{
                backgroundColor: "var(--active-chip-bg)",
                color: "var(--active-chip-text)",
              }}
            >
              只看楼主 ×
            </button>
          )}
          {detail?.acceptedAnswerPostNumber && (
            <button
              type="button"
              onClick={() => jumpToFloor(detail.acceptedAnswerPostNumber!)}
              className="lindo-no-drag inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-semibold cursor-pointer"
              style={{
                backgroundColor: "rgba(16, 185, 129, 0.14)",
                color: "#10B981",
              }}
            >
              <CheckCircle size={12} weight="fill" />
              <span>已解答 · #{detail.acceptedAnswerPostNumber}</span>
            </button>
          )}
        </div>

        <div className="lindo-no-drag flex items-center gap-1">
          {detail?.hasSummary && (
            <button
              type="button"
              onClick={async () => {
                const next = !aiSummaryOpen;
                setAiSummaryOpen(next);
                if (next && !aiSummaryText && !aiSummaryLoading) {
                  setAiSummaryLoading(true);
                  try {
                    const txt = await fetchTopicAiSummary(summary.id);
                    setAiSummaryText(txt);
                  } catch (e: any) {
                    setAiSummaryText(e?.message || "总结暂时不可用");
                  } finally {
                    setAiSummaryLoading(false);
                  }
                }
              }}
              title="AI 总结"
              className="lindo-interactive flex h-8 items-center gap-1 rounded-full px-2.5 text-[12.5px] font-medium"
              style={{
                color: aiSummaryOpen ? "var(--brand-gold)" : "var(--ink-secondary)",
              }}
            >
              <Sparkle size={15} weight={aiSummaryOpen ? "fill" : "regular"} />
              <span>总结</span>
            </button>
          )}

          <button
            type="button"
            onClick={async () => {
              if (!isAuthorized) {
                onRequestGuestLogin("登录后收藏话题", "收藏后可在「我的 · 收藏」随时回看");
                return;
              }
              if (!firstPost) return;
              try {
                const nextId = await togglePostBookmark(firstPost.id, firstPost.bookmarkId);
                onAppendPosts(
                  [{ ...firstPost, bookmarked: Boolean(nextId), bookmarkId: nextId }],
                  "after"
                );
                setToastMsg(nextId ? "已加入书签" : "已移除书签");
              } catch (e: any) {
                setToastMsg(e?.message || "操作未成功");
              }
            }}
            title="收藏主帖"
            className="lindo-interactive flex h-9 w-9 items-center justify-center rounded-full"
            style={{
              color: firstPost?.bookmarked ? "var(--brand-gold)" : "var(--ink-secondary)",
            }}
          >
            <BookmarkSimple size={18.5} weight={firstPost?.bookmarked ? "fill" : "regular"} />
          </button>

          <button
            type="button"
            onClick={() => setMoreMenuOpen((v) => !v)}
            title="更多操作"
            className="lindo-interactive flex h-9 w-9 items-center justify-center rounded-full"
            style={{
              color: moreMenuOpen ? "var(--brand-gold)" : "var(--ink-secondary)",
            }}
          >
            <DotsThree size={21} weight="bold" />
          </button>
        </div>
      </header>

      {/* 二级更多操作条（no-scrollbar 绝无水平黑滚动槽！） */}
      {moreMenuOpen && (
        <div
          className="lindo-hairline-b no-scrollbar flex shrink-0 items-center gap-3 overflow-x-auto px-4 py-2 text-[12.2px] font-medium"
          style={{ backgroundColor: "var(--subtle-surface)" }}
        >
          <button
            type="button"
            onClick={() => {
              setMoreMenuOpen(false);
              onToggleOnlyOp();
            }}
            className="lindo-interactive flex shrink-0 items-center gap-1.5 rounded-md px-2 py-1"
            style={{ color: onlyOpFilter ? "var(--brand-gold)" : "var(--ink-primary)" }}
          >
            <User size={14} />
            <span>{onlyOpFilter ? "查看全部楼层" : "只看楼主"}</span>
          </button>

          {/* 4 级通知订阅状态切换 */}
          <div className="relative flex shrink-0 items-center">
            <button
              type="button"
              onClick={() => {
                if (!isAuthorized) {
                  setMoreMenuOpen(false);
                  onRequestGuestLogin(
                    "登录后设置话题订阅状态",
                    "跟踪或关注话题可第一时间收到新回复通知"
                  );
                  return;
                }
                setNotifMenuOpen((v) => !v);
              }}
              className="lindo-interactive flex items-center gap-1.5 rounded-md px-2 py-1"
              style={{
                color:
                  (detail?.notificationLevel ?? 1) >= 2
                    ? "var(--brand-gold)"
                    : "var(--ink-primary)",
              }}
            >
              <Bell size={14} />
              <span>订阅：{currentNotifLabel}</span>
              <CaretDown size={11} />
            </button>
          </div>

          {notifMenuOpen && detail && (
            <div className="flex shrink-0 items-center gap-1 rounded-full px-1.5 py-0.5" style={{ backgroundColor: "var(--card-surface)" }}>
              {NOTIFICATION_LEVELS.map((nl) => {
                const active = detail.notificationLevel === nl.level;
                return (
                  <button
                    key={nl.level}
                    type="button"
                    title={nl.desc}
                    onClick={async () => {
                      setNotifMenuOpen(false);
                      try {
                        await setTopicNotificationLevel(detail.id, nl.level);
                        setToastMsg(`已切换为「${nl.label}」`);
                        onRefresh();
                      } catch (e: any) {
                        setToastMsg(e?.message || "更新失败");
                      }
                    }}
                    className="rounded-full px-2 py-0.5 text-[11.5px] cursor-pointer"
                    style={{
                      backgroundColor: active ? "var(--active-chip-bg)" : "transparent",
                      color: active ? "var(--active-chip-text)" : "var(--ink-secondary)",
                    }}
                  >
                    {nl.label}
                  </button>
                );
              })}
            </div>
          )}

          {detail?.archetype === "private_message" && isAuthorized && (
            <button
              type="button"
              onClick={async () => {
                setMoreMenuOpen(false);
                const nextArchived = !detail.archived;
                try {
                  await archivePrivateMessage(detail.id, nextArchived);
                  setToastMsg(nextArchived ? "私信已归档" : "已移回收件箱");
                  onPmArchiveChanged?.();
                  onRefresh();
                } catch (e: any) {
                  if (e instanceof SiteError && e.isVerification) onTriggerVerify();
                  else setToastMsg(e?.message || "操作失败");
                }
              }}
              className="lindo-interactive flex shrink-0 items-center gap-1.5 rounded-md px-2 py-1"
              style={{ color: "var(--ink-primary)" }}
            >
              {detail.archived ? <ArrowBendUpLeft size={14} /> : <Archive size={14} />}
              <span>{detail.archived ? "移回收件箱" : "归档私信"}</span>
            </button>
          )}

          <button
            type="button"
            onClick={() => {
              navigator.clipboard.writeText(`https://linux.do/t/topic/${summary.id}`);
              setCopiedLink(true);
              setTimeout(() => setCopiedLink(false), 1800);
            }}
            className="lindo-interactive flex shrink-0 items-center gap-1.5 rounded-md px-2 py-1"
            style={{ color: copiedLink ? "var(--brand-gold)" : "var(--ink-primary)" }}
          >
            {copiedLink ? <Check size={14} /> : <LinkSimple size={14} />}
            <span>{copiedLink ? "已复制链接" : "复制链接"}</span>
          </button>

          <button
            type="button"
            onClick={() => {
              setMoreMenuOpen(false);
              openUrl(`https://linux.do/t/topic/${summary.id}`);
            }}
            className="lindo-interactive flex shrink-0 items-center gap-1.5 rounded-md px-2 py-1"
            style={{ color: "var(--ink-primary)" }}
          >
            <ArrowSquareOut size={14} />
            <span>浏览器打开</span>
          </button>

          <button
            type="button"
            onClick={() => {
              setMoreMenuOpen(false);
              onRefresh();
            }}
            className="lindo-interactive flex shrink-0 items-center gap-1.5 rounded-md px-2 py-1"
            style={{ color: "var(--ink-primary)" }}
          >
            <SmoothRefreshIcon spinning={isLoading} size={14} />
            <span>刷新</span>
          </button>
        </div>
      )}

      {/* 2. 主阅读滚动视口 */}
      <div
        ref={scrollContainerRef}
        className={`lindo-scroll relative flex-1 min-h-0 ${hideQuickReplyBar ? "pb-12" : "pb-20"}`}
      >
        {/* 话题大标题与元数据区 */}
        <div className="lindo-hairline-b px-6 pt-5 pb-4">
          <h1
            className="lindo-selectable select-text text-[20px] font-semibold leading-[1.4]"
            style={{ color: "var(--ink-primary)" }}
          >
            {(detail?.closed ?? summary.closed) && (
              <Lock
                size={16}
                className="mr-1.5 inline-block align-[-2px]"
                style={{ color: "var(--ink-tertiary)" }}
              />
            )}
            {detail?.title || summary.title}
          </h1>

          <div className="mt-2.5 flex flex-wrap items-center gap-2">
            {(detail?.categoryName || summary.categoryName) && (
              <span
                className="inline-flex items-center gap-1.5 rounded-md px-2 py-0.5 text-[12px] font-medium"
                style={{
                  backgroundColor: "var(--subtle-surface)",
                  color: "var(--ink-secondary)",
                }}
              >
                <span className="h-2 w-2 rounded-full" style={{ backgroundColor: catVis.dotColor }} />
                <span>{detail?.categoryName || summary.categoryName}</span>
              </span>
            )}
            {(detail?.tags ?? summary.tags).map((tg) => (
              <span
                key={tg}
                className="rounded-md px-2 py-0.5 text-[11.5px] font-medium"
                style={{
                  backgroundColor: "var(--tonal-pill)",
                  color: "var(--ink-secondary)",
                }}
              >
                #{tg}
              </span>
            ))}
          </div>

          <div
            className="mt-2.5 flex items-center gap-2 text-[12px] tabular-nums"
            style={{ color: "var(--ink-tertiary)" }}
          >
            <span>{formatCompactCount(detail?.views ?? summary.views)} 次浏览</span>
            <span>·</span>
            <span>{Math.max(0, totalFloors - 1)} 条回复</span>
            {(detail?.likeCount ?? summary.likeCount) > 0 && (
              <>
                <span>·</span>
                <span>{formatCompactCount(detail?.likeCount ?? summary.likeCount)} 次赞</span>
              </>
            )}
          </div>

          {/* AI 总结卡片 */}
          {aiSummaryOpen && (
            <div
              className="lindo-selectable mt-3.5 rounded-xl p-3.5 text-[13.5px] leading-relaxed select-text"
              style={{
                backgroundColor: "var(--subtle-surface)",
                border: "0.5px solid color-mix(in srgb, var(--brand-gold) 35%, var(--hairline))",
              }}
            >
              <div
                className="mb-1.5 flex items-center gap-1.5 text-[12px] font-semibold"
                style={{ color: "var(--brand-gold)" }}
              >
                <Sparkle size={14} weight="fill" />
                <span>AI 话题总结</span>
              </div>
              <p className="whitespace-pre-wrap" style={{ color: "var(--ink-primary)" }}>
                {aiSummaryLoading ? "正在生成话题摘要..." : aiSummaryText}
              </p>
            </div>
          )}
        </div>

        {/* 加载态 / 错误态 / 楼层流 */}
        {isLoading && (!detail || detail.posts.length === 0) ? (
          <div className="space-y-6 px-6 py-6">
            {Array.from({ length: 4 }).map((_, i) => (
              <div key={i} className="space-y-3">
                <div className="flex items-center gap-3">
                  <div className="lindo-shimmer h-9 w-9 rounded-full" />
                  <div className="lindo-shimmer h-4 w-32 rounded" />
                </div>
                <div className="lindo-shimmer h-4 w-full rounded" />
                <div className="lindo-shimmer h-4 w-4/5 rounded" />
              </div>
            ))}
          </div>
        ) : errorMessage && (!detail || detail.posts.length === 0) ? (
          <div className="flex flex-col items-center justify-center py-20 px-6 text-center">
            <MascotExpression expression="surprised" sizePx={58} className="mb-3" />
            <p className="text-[13.5px]" style={{ color: "var(--ink-secondary)" }}>
              {errorMessage}
            </p>
            <button
              type="button"
              onClick={errorMessage.includes("验证") ? onTriggerVerify : onRefresh}
              className="mt-3.5 rounded-full px-4 py-1.5 text-[12.5px] font-semibold cursor-pointer"
              style={{
                backgroundColor: "var(--publish-btn-bg)",
                color: "var(--publish-btn-fg)",
              }}
            >
              {errorMessage.includes("验证") ? "去验证" : "重试"}
            </button>
          </div>
        ) : (
          <div>
            {/* 向上加载更早楼层按钮 */}
            {missingEarlierIds.length > 0 && (
              <div className="lindo-hairline-b px-6 py-2.5">
                <button
                  type="button"
                  onClick={handleLoadEarlier}
                  disabled={loadingEarlier}
                  className="lindo-interactive flex w-full items-center justify-center gap-1.5 rounded-lg py-1.5 text-[12.5px] font-medium"
                  style={{
                    backgroundColor: "var(--subtle-surface)",
                    color: "var(--active-chip-text)",
                  }}
                >
                  <CaretUp size={13} />
                  <span>
                    {loadingEarlier
                      ? "正在加载更早楼层..."
                      : `向上加载前序楼层（当前从 ${detail?.posts[0]?.postNumber ?? 1} 楼开始）`}
                  </span>
                </button>
              </div>
            )}

            {detail?.posts.map((post) => {
              const isTargetHighlight = highlightedFloor === post.postNumber;

              if (post.isDeletedTombstone) {
                return (
                  <div
                    key={post.id}
                    data-floor={post.postNumber}
                    className="lindo-hairline-b flex items-center justify-between gap-3 px-6 py-3 text-[12.5px]"
                    style={{ color: "var(--ink-tertiary)" }}
                  >
                    <div className="flex min-w-0 items-center gap-2">
                      <AvatarWithFlair
                        username={post.username}
                        avatarTemplate={post.avatarTemplate}
                        sizePx={22}
                      />
                      <span className="truncate font-medium">
                        {post.displayName || post.username}
                      </span>
                      <span>（帖子已被作者删除）</span>
                    </div>
                    <span className="shrink-0 tabular-nums">
                      {formatRelativeTime(post.createdAt)} · #{post.postNumber}
                    </span>
                  </div>
                );
              }

              if (post.isSmallAction) {
                const actCode = (post.actionCode || "").toLowerCase();
                const smallIcon = actCode.includes("close") || actCode.includes("open") ? (
                  <Lock size={13.5} />
                ) : actCode.includes("pin") || actCode.includes("banner") ? (
                  <PushPin size={13.5} />
                ) : actCode.includes("archive") ? (
                  <Archive size={13.5} />
                ) : (
                  <Bell size={13.5} />
                );
                return (
                  <div
                    key={post.id}
                    data-floor={post.postNumber}
                    className="lindo-hairline-b flex items-center justify-between gap-3 px-6 py-2.5 text-[12.5px]"
                    style={{
                      backgroundColor: "color-mix(in srgb, var(--subtle-surface) 45%, transparent)",
                      color: "var(--ink-secondary)",
                    }}
                  >
                    <div className="flex min-w-0 items-center gap-2">
                      <span style={{ color: "var(--ink-tertiary)" }}>{smallIcon}</span>
                      <AvatarWithFlair
                        username={post.username}
                        avatarTemplate={post.avatarTemplate}
                        sizePx={20}
                      />
                      <button
                        type="button"
                        onClick={() => onInspectUser(post.username)}
                        className="shrink-0 font-semibold hover:underline cursor-pointer"
                        style={{ color: "var(--ink-primary)" }}
                      >
                        {post.displayName || post.username}
                      </button>
                      <span
                        className="lindo-cooked truncate"
                        dangerouslySetInnerHTML={{
                          __html: prepareCookedHtml(post.cookedHtml || post.actionCode || ""),
                        }}
                      />
                    </div>
                    <span
                      className="shrink-0 text-[11.5px] tabular-nums"
                      style={{ color: "var(--ink-tertiary)" }}
                    >
                      {formatRelativeTime(post.createdAt)}
                    </span>
                  </div>
                );
              }

              const isOp = post.username.toLowerCase() === detail.opUsername.toLowerCase();
              const hasPolls = Boolean(post.polls && post.polls.length > 0);
              const holoTitle = isHolographicTitle(post.userTitle);
              const isMerchantGroup = post.primaryGroupName === "g-merchant";
              const showDistinctHandle =
                Boolean(post.displayName) &&
                post.displayName.trim().toLowerCase() !== post.username.trim().toLowerCase();
              const firstTimeBadges = (post.badgesGranted ?? []).filter(
                (b) => b.isFirstTimeGrant !== false
              );
              const hasSubMetaRow =
                showDistinctHandle ||
                Boolean(post.userTitle) ||
                Boolean(post.iosDeviceName) ||
                firstTimeBadges.length > 0 ||
                Boolean(post.isTodayBirthday) ||
                Boolean(post.isTodayCakeday);
              const canDeleteThisFloor =
                post.postNumber > 1 &&
                (post.canDelete ||
                  Boolean(
                    currentUser &&
                      post.username.toLowerCase() === currentUser.username.toLowerCase() &&
                      post.canDelete
                  ));
              const inlineReplies = expandedRepliesMap[post.id];
              const boostStats =
                activeBoostComposerPostId === post.id ? computeBoostStats(boostDraftText) : null;

              const submitBoostNow = async (rawText: string) => {
                const stats = computeBoostStats(rawText);
                if (!stats.canSubmit || boostSubmitting) return;
                setBoostSubmitting(true);
                try {
                  const created = await sendPostBoost(post.id, rawText.trim());
                  if (created) {
                    onAppendPosts([{ ...post, boosts: [...post.boosts, created] }], "after");
                  } else {
                    onRefresh();
                  }
                  setBoostDraftText("");
                  setActiveBoostComposerPostId(null);
                } catch (err: any) {
                  if (err instanceof SiteError && err.isVerification) onTriggerVerify();
                  else setToastMsg(err?.message || "助力发送失败");
                } finally {
                  setBoostSubmitting(false);
                }
              };

              return (
                <article
                  key={post.id}
                  data-floor={post.postNumber}
                  className={`lindo-hairline-b px-6 py-5 ${
                    isTargetHighlight ? "transition-colors duration-700" : ""
                  }`}
                  style={{
                    backgroundColor: isTargetHighlight
                      ? "color-mix(in srgb, var(--brand-gold) 10%, transparent)"
                      : "transparent",
                  }}
                >
                  {/* 楼层作者双层头部（1:1 对齐移动端：头像+Flair / 第一行昵称+作者徽章+状态+楼主+时间楼层 / 第二行 @账号+头衔+Apple机型+首次解锁徽章+生辰纪念日） */}
                  <div className="flex items-start gap-3">
                    <button
                      type="button"
                      onClick={() => onInspectUser(post.username)}
                      className="mt-0.5 shrink-0 cursor-pointer transition-transform hover:scale-[1.04]"
                    >
                      <AvatarWithFlair
                        username={post.username}
                        avatarTemplate={post.avatarTemplate}
                        animatedAvatar={post.animatedAvatar}
                        primaryGroupName={post.primaryGroupName}
                        flair={post.flair}
                        sizePx={38}
                      />
                    </button>

                    <div className="min-w-0 flex-1">
                      {/* 第一行：昵称 + 管理盾牌 + 作者荣誉徽章 + 状态 Emoji + 楼主 + 最佳解答 | 右侧楼层时间 */}
                      <div className="flex items-center justify-between gap-2">
                        <div className="flex min-w-0 flex-wrap items-center gap-1.5">
                          <button
                            type="button"
                            onClick={() => onInspectUser(post.username)}
                            className="truncate text-[14px] font-semibold leading-snug hover:underline cursor-pointer"
                            style={{ color: "var(--ink-primary)" }}
                          >
                            {post.displayName || post.username}
                          </button>

                          {(post.isAdmin || post.isModerator || post.isGroupModerator) && (
                            <span title={post.isAdmin ? "管理员" : "版主"}>
                              <ShieldCheck
                                size={14}
                                weight="fill"
                                style={{
                                  color:
                                    post.isAdmin || post.isModerator
                                      ? "var(--brand-gold)"
                                      : "var(--ink-secondary)",
                                }}
                              />
                            </span>
                          )}

                          {(post.authorBadges ?? []).map((ab) => (
                            <OfficialBadgeIcon
                              key={`ab_${post.id}_${ab.id}`}
                              badge={ab}
                              size={13.5}
                              title={`${ab.name}（点击查看徽章详情）`}
                              onClick={() => onInspectBadge?.(ab)}
                            />
                          ))}

                          {post.userStatus?.emoji && (
                            <button
                              type="button"
                              title={post.userStatus.description || "查看个人状态"}
                              onClick={() =>
                                setExpandedStatusPostId((prev) =>
                                  prev === post.id ? null : post.id
                                )
                              }
                              className="inline-flex items-center rounded px-0.5 leading-none transition-transform hover:scale-110 cursor-pointer"
                            >
                              <StatusEmojiIcon emoji={post.userStatus.emoji} size={14} />
                            </button>
                          )}

                          {isOp && (
                            <span
                              className="rounded px-1.5 py-[1px] text-[10.5px] font-semibold"
                              style={{
                                backgroundColor: "var(--active-chip-bg)",
                                color: "var(--brand-gold)",
                              }}
                            >
                              楼主
                            </span>
                          )}

                          {post.acceptedAnswer && (
                            <span
                              className="inline-flex items-center gap-0.5 rounded-full px-2 py-[1px] text-[10.5px] font-bold"
                              style={{
                                backgroundColor: "rgba(16, 185, 129, 0.14)",
                                color: "#10B981",
                              }}
                            >
                              <CheckCircle size={11.5} weight="fill" />
                              <span>最佳解答</span>
                            </span>
                          )}
                        </div>

                        <div
                          className="flex shrink-0 items-center gap-1.5 text-[11.8px] tabular-nums"
                          style={{ color: "var(--ink-tertiary)" }}
                        >
                          {isAuthorized &&
                            post.isRead === false &&
                            post.postNumber > confirmedReadFloor && (
                              <span
                                title="未读新楼层"
                                className={`h-[5.5px] w-[5.5px] rounded-full transition-opacity duration-500 ease-out ${
                                  locallyViewedFloors.has(post.postNumber)
                                    ? "opacity-0 pointer-events-none"
                                    : "opacity-100"
                                }`}
                                style={{ backgroundColor: "var(--brand-gold)" }}
                              />
                            )}
                          <span>{formatRelativeTime(post.createdAt)}</span>
                          <span>#{post.postNumber}</span>
                        </div>
                      </div>

                      {/* 第二行：@handle + 社区头衔 +  Apple 设备机型 + 本楼首次解锁徽章（如 Only Apple can do）+ 生辰/纪念日 */}
                      {hasSubMetaRow && (
                        <div className="mt-0.5 flex flex-wrap items-center gap-x-1.5 gap-y-1 text-[11.8px] leading-tight">
                          {showDistinctHandle && (
                            <span
                              className="truncate"
                              style={{ color: "var(--ink-tertiary)" }}
                            >
                              @{post.username}
                            </span>
                          )}

                          {post.userTitle && (
                            <span
                              className={`text-[11.8px] ${
                                holoTitle ? "lindo-title-holo" : "font-medium"
                              }`}
                              style={
                                holoTitle
                                  ? undefined
                                  : {
                                      color: isMerchantGroup
                                        ? "#F5BF03"
                                        : "var(--ink-secondary)",
                                    }
                              }
                            >
                              {post.userTitle}
                            </span>
                          )}

                          {post.iosDeviceName && (
                            <span
                              title={`发自 ${post.iosDeviceName}`}
                              className="inline-flex items-center gap-0.5 font-medium"
                              style={{ color: "var(--ink-tertiary)" }}
                            >
                              {(showDistinctHandle || post.userTitle) && (
                                <span className="mr-0.5 opacity-60">·</span>
                              )}
                              <AppleLogo size={12.5} weight="fill" className="shrink-0" />
                              <span>{post.iosDeviceName}</span>
                            </span>
                          )}

                          {firstTimeBadges.map((gb) => (
                            <button
                              key={`gb_${post.id}_${gb.id}`}
                              type="button"
                              title={`首次获得徽章：${gb.name}`}
                              onClick={() => onInspectBadge?.(gb)}
                              className="inline-flex items-center gap-1 rounded-md px-1.5 py-[1.5px] text-[11px] font-medium transition-opacity hover:opacity-85 cursor-pointer"
                              style={{
                                backgroundColor: "var(--subtle-surface)",
                                border: "0.5px solid var(--hairline)",
                                color: "var(--ink-primary)",
                              }}
                            >
                              <OfficialBadgeIcon badge={gb} size={12.5} />
                              <span>{gb.name}</span>
                            </button>
                          ))}

                          {post.isTodayBirthday && (
                            <span title="今天是作者生日！" className="text-[12px]">
                              🎂
                            </span>
                          )}
                          {post.isTodayCakeday && (
                            <span title="今天是作者入站纪念日！" className="text-[12px]">
                              🍰
                            </span>
                          )}
                        </div>
                      )}

                      {expandedStatusPostId === post.id && post.userStatus?.description && (
                        <div
                          className="mt-1.5 inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1 text-[11.5px]"
                          style={{
                            backgroundColor: "var(--subtle-surface)",
                            border: "0.5px solid var(--hairline)",
                            color: "var(--ink-secondary)",
                          }}
                        >
                          <StatusEmojiIcon emoji={post.userStatus.emoji} size={13.5} />
                          <span>{post.userStatus.description}</span>
                        </div>
                      )}
                    </div>
                  </div>

                  {/* 回复目标楼层跳转条 */}
                  {post.replyToPostNumber && post.replyToPostNumber > 0 && (
                    <button
                      type="button"
                      onClick={() => jumpToFloor(post.replyToPostNumber!)}
                      className="lindo-interactive mt-2.5 flex w-full items-center justify-between rounded-r-lg border-l-2 pl-2.5 pr-2 py-1 text-left text-[12px]"
                      style={{
                        borderColor: "var(--brand-gold)",
                        backgroundColor: "color-mix(in srgb, var(--subtle-surface) 70%, transparent)",
                        color: "var(--ink-secondary)",
                      }}
                    >
                      <span className="flex items-center gap-1.5 truncate">
                        <img
                          src={resolveAvatarUrl(post.replyToAvatarTemplate, 96)}
                          alt=""
                          className="h-4 w-4 rounded-full object-cover"
                        />
                        <span>回复</span>
                        <span className="font-medium" style={{ color: "var(--ink-primary)" }}>
                          {post.replyToDisplayName || post.replyToUsername || ""}
                        </span>
                        <span style={{ color: "var(--ink-tertiary)" }}>
                          · #{post.replyToPostNumber} 楼
                        </span>
                      </span>
                      <CaretRight size={12} style={{ color: "var(--ink-tertiary)" }} />
                    </button>
                  )}

                  {/* 正文富文本渲染（独立 Memo 隔离，滚动与悬浮引用绝不触发重绘与选区闪烁） */}
                  <MemoizedCookedBody
                    rawHtml={post.cookedHtml}
                    stripPolls={hasPolls}
                    onCookedClick={handleCookedClick}
                  />

                  {/* 原生可交互投票卡片 (Polls) */}
                  {hasPolls && (
                    <div className="mt-3.5 space-y-2.5">
                      {post.polls.map((poll) => (
                        <InteractivePollCard
                          key={poll.name}
                          postId={post.id}
                          poll={poll}
                          isAuthorized={isAuthorized}
                          onRequestLogin={() =>
                            onRequestGuestLogin("登录后参与投票", "登录后即可参与社区话题投票")
                          }
                          onPollUpdated={(updatedPoll) => {
                            onAppendPosts(
                              [
                                {
                                  ...post,
                                  polls: post.polls.map((pl) =>
                                    pl.name === updatedPoll.name ? updatedPoll : pl
                                  ),
                                },
                              ],
                              "after"
                            );
                          }}
                          onError={(msg) => setToastMsg(msg)}
                        />
                      ))}
                    </div>
                  )}

                  {/* 小火箭助力 (Boosts) 展示胶囊区（点击展开助力者账号与撤回入口，对齐移动端） */}
                  {post.boosts.length > 0 && (
                    <div className="mt-3 flex flex-wrap items-center gap-1.5">
                      {post.boosts.map((boost) => {
                        const isBoostExpanded = expandedBoostId === boost.id;
                        const canRemoveBoost =
                          Boolean(boost.canDelete) ||
                          Boolean(
                            currentUser?.username &&
                              boost.username.toLowerCase() === currentUser.username.toLowerCase()
                          );
                        return (
                          <div
                            key={boost.id}
                            onClick={() =>
                              setExpandedBoostId((prev) => (prev === boost.id ? null : boost.id))
                            }
                            role="button"
                            tabIndex={0}
                            onKeyDown={(e) => {
                              if (e.key === "Enter" || e.key === " ") {
                                e.preventDefault();
                                setExpandedBoostId((prev) =>
                                  prev === boost.id ? null : boost.id
                                );
                              }
                            }}
                            className="lindo-interactive inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[12px] select-none"
                            style={{
                              backgroundColor: "var(--subtle-surface)",
                              border: "0.5px solid var(--hairline)",
                            }}
                          >
                            <img
                              src={resolveAvatarUrl(boost.avatarTemplate, 96)}
                              alt={boost.username}
                              className="h-4 w-4 rounded-full object-cover"
                            />
                            <span
                              className="lindo-cooked text-[12px] leading-none"
                              dangerouslySetInnerHTML={{ __html: prepareCookedHtml(boost.cooked) }}
                            />
                            {isBoostExpanded && (
                              <button
                                type="button"
                                onClick={(e) => {
                                  e.stopPropagation();
                                  onInspectUser(boost.username);
                                }}
                                className="ml-0.5 text-[11px] font-medium hover:underline cursor-pointer"
                                style={{ color: "var(--ink-secondary)" }}
                              >
                                @{boost.username}
                              </button>
                            )}
                            {canRemoveBoost && (
                              <button
                                type="button"
                                title="撤回助力"
                                onClick={async (e) => {
                                  e.stopPropagation();
                                  try {
                                    await deletePostBoost(boost.id);
                                    onAppendPosts(
                                      [
                                        {
                                          ...post,
                                          boosts: post.boosts.filter((b) => b.id !== boost.id),
                                        },
                                      ],
                                      "after"
                                    );
                                  } catch (err: any) {
                                    setToastMsg(err?.message || "撤回助力失败");
                                  }
                                }}
                                className="ml-0.5 inline-flex items-center gap-0.5 rounded-full px-1 py-0.5 text-[10.5px] opacity-75 hover:opacity-100 cursor-pointer"
                                style={{ color: "#EF4444" }}
                              >
                                <Trash size={11.5} />
                                {isBoostExpanded && <span>删除</span>}
                              </button>
                            )}
                          </div>
                        );
                      })}
                    </div>
                  )}

                  {/* 双层小火箭助力面板（第一行全宽表情+快捷短语，第二行宽敞输入框+字数/表情校验+发送） */}
                  {activeBoostComposerPostId === post.id && boostStats && (
                    <div
                      className="mt-3 rounded-2xl p-2.5 space-y-2"
                      style={{
                        backgroundColor: "var(--subtle-surface)",
                        border: "0.5px solid var(--hairline)",
                      }}
                    >
                      {/* 第一行：全部放到上一行的丰富表情矩阵 + 一键快捷助力短语 */}
                      <div className="flex flex-wrap items-center justify-between gap-y-1.5 gap-x-2 pb-1.5 lindo-hairline-b">
                        <div className="flex flex-wrap items-center gap-0.5">
                          {BOOST_QUICK_EMOJIS.map((em) => (
                            <button
                              key={em}
                              type="button"
                              disabled={!boostStats.canAddEmoji}
                              onClick={() =>
                                setBoostDraftText((prev) => `${prev}${em}`)
                              }
                              className="flex h-7 w-7 items-center justify-center rounded-lg text-[15px] transition-transform hover:scale-115 hover:bg-white/5 disabled:opacity-35 cursor-pointer"
                            >
                              {em}
                            </button>
                          ))}
                        </div>

                        <div className="flex flex-wrap items-center gap-1">
                          {BOOST_QUICK_PHRASES.map((phrase) => (
                            <button
                              key={phrase}
                              type="button"
                              onClick={() => setBoostDraftText(phrase)}
                              className="lindo-interactive rounded-full px-2 py-0.5 text-[11px] font-medium"
                              style={{
                                backgroundColor: "var(--card-surface)",
                                border: "0.5px solid var(--hairline)",
                                color: "var(--ink-secondary)",
                              }}
                            >
                              {phrase}
                            </button>
                          ))}
                        </div>
                      </div>

                      {/* 第二行：全宽输入框 + 精确可见长度/表情上限提示 + 发送 + 关闭 */}
                      <div className="flex items-center gap-2 pl-1.5 pr-0.5">
                        <input
                          type="text"
                          autoFocus
                          value={boostDraftText}
                          onChange={(e) => setBoostDraftText(e.target.value)}
                          placeholder={`给 @${post.username} 助力一句（最多 16 字 / 5 个表情）...`}
                          className="flex-1 bg-transparent text-[13px] outline-none"
                          style={{ color: "var(--ink-primary)" }}
                          onKeyDown={(e) => {
                            if (e.key === "Enter") {
                              e.preventDefault();
                              submitBoostNow(boostDraftText);
                            } else if (e.key === "Escape") {
                              setActiveBoostComposerPostId(null);
                            }
                          }}
                        />

                        {boostStats.exceedsEmojiLimit && (
                          <span className="shrink-0 text-[11px] font-medium" style={{ color: "#EF4444" }}>
                            最多 5 个表情
                          </span>
                        )}

                        <span
                          className="shrink-0 text-[11.5px] tabular-nums"
                          style={{
                            color:
                              boostStats.exceedsLength || boostStats.exceedsEmojiLimit
                                ? "#EF4444"
                                : "var(--ink-tertiary)",
                          }}
                        >
                          {boostStats.visibleLength}/{boostStats.maxVisibleLength}
                        </span>

                        <button
                          type="button"
                          disabled={!boostStats.canSubmit || boostSubmitting}
                          onClick={() => submitBoostNow(boostDraftText)}
                          title="发送助力"
                          className="flex h-7 px-2.5 items-center justify-center gap-1 rounded-full text-[11.5px] font-semibold cursor-pointer disabled:opacity-40"
                          style={{
                            backgroundColor: "var(--publish-btn-bg)",
                            color: "var(--publish-btn-fg)",
                          }}
                        >
                          <PaperPlaneRight size={12.5} weight="fill" />
                          <span>助力</span>
                        </button>

                        <button
                          type="button"
                          title="收起助力面板"
                          onClick={() => setActiveBoostComposerPostId(null)}
                          className="lindo-interactive flex h-7 w-7 items-center justify-center rounded-full"
                          style={{ color: "var(--ink-tertiary)" }}
                        >
                          <X size={13.5} />
                        </button>
                      </div>
                    </div>
                  )}

                  {/* 底部操作栏：表情胶囊 + 展开子回复 + 点赞/表情/小火箭/收藏/采纳/编辑/删除/回复 */}
                  <div className="relative mt-3.5 flex flex-wrap items-center justify-between gap-2">
                    <div className="flex flex-wrap items-center gap-1.5">
                      {post.reactions.map((r) => {
                        const mine = post.currentUserReaction === r.id;
                        const mappedEmoji = REACTION_EMOJI_MAP[r.id];
                        return (
                          <button
                            key={r.id}
                            type="button"
                            title={`:${r.id}:`}
                            onClick={async () => {
                              if (!isAuthorized) {
                                onRequestGuestLogin(
                                  "登录后参与互动",
                                  "登录后即可为帖子点赞或发送表情回应"
                                );
                                return;
                              }
                              try {
                                const updated = await togglePostReaction(post.id, r.id);
                                if (updated) onAppendPosts([updated], "after");
                              } catch (err: any) {
                                if (err instanceof SiteError && err.isVerification)
                                  onTriggerVerify();
                                else setToastMsg(err?.message || "操作失败");
                              }
                            }}
                            className="inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11.5px] tabular-nums transition-colors cursor-pointer"
                            style={{
                              backgroundColor: mine
                                ? "var(--active-chip-bg)"
                                : "var(--subtle-surface)",
                              border: mine
                                ? "0.5px solid var(--brand-gold)"
                                : "0.5px solid var(--hairline)",
                              color: mine ? "var(--active-chip-text)" : "var(--ink-secondary)",
                            }}
                          >
                            {mappedEmoji ? (
                              <span>{mappedEmoji}</span>
                            ) : (
                              <img
                                src={resolveReactionFallbackImg(r.id)}
                                alt={r.id}
                                className="h-3.5 w-3.5 object-contain"
                                onError={(e) => {
                                  (e.currentTarget as HTMLImageElement).style.display = "none";
                                }}
                              />
                            )}
                            <span>{r.count}</span>
                          </button>
                        );
                      })}

                      {/* 展开 N 条关联回复按钮 */}
                      {post.replyCount > 0 && (
                        <button
                          type="button"
                          onClick={() => handleToggleInlineReplies(post)}
                          className="lindo-interactive inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-[11.8px] font-medium"
                          style={{ color: "var(--ink-secondary)" }}
                        >
                          <ChatCircle size={13} />
                          <span>
                            {loadingRepliesPostId === post.id
                              ? "加载中..."
                              : inlineReplies
                                ? `收起 ${post.replyCount} 条回复`
                                : `${post.replyCount} 条回复`}
                          </span>
                          {inlineReplies ? <CaretUp size={11} /> : <CaretDown size={11} />}
                        </button>
                      )}
                    </div>

                    <div
                      className="flex items-center gap-0.5"
                      style={{ color: "var(--ink-secondary)" }}
                    >
                      {/* 点赞 */}
                      <button
                        type="button"
                        title="点赞"
                        onClick={async () => {
                          if (!isAuthorized) {
                            onRequestGuestLogin("登录后点赞", "登录后可为喜欢的回复送出赞同");
                            return;
                          }
                          try {
                            const updated = await togglePostReaction(post.id, "heart");
                            if (updated) onAppendPosts([updated], "after");
                          } catch (err: any) {
                            if (err instanceof SiteError && err.isVerification) onTriggerVerify();
                            else setToastMsg(err?.message || "点赞未完成");
                          }
                        }}
                        className="lindo-interactive flex h-7 items-center gap-1 rounded-full px-2 text-[12px] tabular-nums"
                        style={{
                          color: post.liked ? "#F43F5E" : "var(--ink-secondary)",
                        }}
                      >
                        <Heart size={15} weight={post.liked ? "fill" : "regular"} />
                        {post.likeCount > 0 && post.reactions.length === 0 && (
                          <span>{post.likeCount}</span>
                        )}
                      </button>

                      {/* 表情回应选择器 */}
                      <div className="relative">
                        <button
                          type="button"
                          title="表情回应"
                          onClick={() => {
                            if (!isAuthorized) {
                              onRequestGuestLogin(
                                "登录后发送表情回应",
                                "登录后可使用全部社区表情回应"
                              );
                              return;
                            }
                            setActiveReactionPickerPostId((prev) =>
                              prev === post.id ? null : post.id
                            );
                          }}
                          className="lindo-interactive flex h-7 w-7 items-center justify-center rounded-full"
                        >
                          <Smiley size={15.5} />
                        </button>
                        {activeReactionPickerPostId === post.id && (
                          <div
                            className="absolute bottom-8 right-0 z-30 flex items-center gap-1 rounded-full px-2.5 py-1 shadow-xl"
                            style={{
                              backgroundColor: "var(--subtle-surface)",
                              border: "0.5px solid var(--hairline)",
                            }}
                          >
                            {REACTION_CHOICES.map((rc) => (
                              <button
                                key={rc.id}
                                type="button"
                                title={rc.label}
                                onClick={async () => {
                                  setActiveReactionPickerPostId(null);
                                  try {
                                    const updated = await togglePostReaction(post.id, rc.id);
                                    if (updated) onAppendPosts([updated], "after");
                                  } catch (err: any) {
                                    if (err instanceof SiteError && err.isVerification)
                                      onTriggerVerify();
                                    else setToastMsg(err?.message || "回应未成功");
                                  }
                                }}
                                className="rounded-full p-1 text-[15px] transition-transform hover:scale-125 cursor-pointer"
                              >
                                {rc.emoji}
                              </button>
                            ))}
                          </div>
                        )}
                      </div>

                      {/* 小火箭助力按钮 */}
                      <button
                        type="button"
                        title="发送小火箭助力"
                        onClick={() => {
                          if (!isAuthorized) {
                            onRequestGuestLogin(
                              "登录后发送小火箭助力",
                              "用一句话或表情为这层回复助力"
                            );
                            return;
                          }
                          setActiveBoostComposerPostId((prev) =>
                            prev === post.id ? null : post.id
                          );
                          setBoostDraftText("");
                        }}
                        className="lindo-interactive flex h-7 w-7 items-center justify-center rounded-full"
                        style={{
                          color:
                            activeBoostComposerPostId === post.id
                              ? "var(--brand-gold)"
                              : "var(--ink-secondary)",
                        }}
                      >
                        <RocketLaunch size={15} />
                      </button>

                      {/* 收藏当前楼层书签 */}
                      {post.postNumber > 1 && (
                        <button
                          type="button"
                          title={post.bookmarked ? "取消收藏本楼" : "收藏本楼"}
                          onClick={async () => {
                            if (!isAuthorized) {
                              onRequestGuestLogin("登录后收藏回复", "可在我的书签随时直达该楼层");
                              return;
                            }
                            try {
                              const nextId = await togglePostBookmark(post.id, post.bookmarkId);
                              onAppendPosts(
                                [{ ...post, bookmarked: Boolean(nextId), bookmarkId: nextId }],
                                "after"
                              );
                              setToastMsg(nextId ? "已收藏该楼层" : "已移除书签");
                            } catch (e: any) {
                              setToastMsg(e?.message || "书签操作失败");
                            }
                          }}
                          className="lindo-interactive flex h-7 w-7 items-center justify-center rounded-full"
                          style={{
                            color: post.bookmarked ? "var(--brand-gold)" : "var(--ink-secondary)",
                          }}
                        >
                          <BookmarkSimple
                            size={14.5}
                            weight={post.bookmarked ? "fill" : "regular"}
                          />
                        </button>
                      )}

                      {/* 采纳最佳解答 */}
                      {post.canAcceptAnswer && post.postNumber > 1 && (
                        <button
                          type="button"
                          title={post.acceptedAnswer ? "取消采纳解答" : "采纳为最佳解答"}
                          onClick={async () => {
                            try {
                              await acceptPostSolution(post.id, !post.acceptedAnswer);
                              setToastMsg(post.acceptedAnswer ? "已取消采纳" : "已采纳为最佳解答");
                              onRefresh();
                            } catch (e: any) {
                              setToastMsg(e?.message || "操作失败");
                            }
                          }}
                          className="lindo-interactive flex h-7 w-7 items-center justify-center rounded-full"
                          style={{
                            color: post.acceptedAnswer ? "#10B981" : "var(--ink-secondary)",
                          }}
                        >
                          <CheckCircle size={15} weight={post.acceptedAnswer ? "fill" : "regular"} />
                        </button>
                      )}

                      {/* 复制楼层链接 */}
                      <button
                        type="button"
                        title="复制本楼链接"
                        onClick={() => {
                          navigator.clipboard.writeText(
                            `https://linux.do/t/topic/${summary.id}/${post.postNumber}`
                          );
                          setCopiedFloorId(post.id);
                          setTimeout(() => setCopiedFloorId(null), 1500);
                        }}
                        className="lindo-interactive flex h-7 w-7 items-center justify-center rounded-full"
                        style={{
                          color:
                            copiedFloorId === post.id
                              ? "var(--brand-gold)"
                              : "var(--ink-secondary)",
                        }}
                      >
                        {copiedFloorId === post.id ? (
                          <Check size={14} />
                        ) : (
                          <LinkSimple size={15} />
                        )}
                      </button>

                      {/* 引用回复 */}
                      <button
                        type="button"
                        title="引用本楼回复"
                        onClick={() => {
                          if (!isAuthorized) {
                            onRequestGuestLogin("登录后回复话题", "登录后可引用楼层参与讨论");
                            return;
                          }
                          const plain = post.cookedHtml
                            .replace(/<[^>]+>/g, " ")
                            .replace(/\s+/g, " ")
                            .trim()
                            .slice(0, 180);
                          onOpenComposer({
                            action: "REPLY",
                            topicId: summary.id,
                            topicTitle: detail?.title || summary.title,
                            replyToPostNumber: post.postNumber,
                            replyToUsername: post.username,
                            replyToSnippet: plain,
                            initialRaw: `[quote="${post.username}, post:${post.postNumber}, topic:${summary.id}"]\n${plain}\n[/quote]\n\n`,
                          });
                        }}
                        className="lindo-interactive flex h-7 w-7 items-center justify-center rounded-full"
                      >
                        <Quotes size={15} />
                      </button>

                      {/* 编辑本人帖子 */}
                      {(post.canEdit || post.username === currentUser?.username) && (
                        <button
                          type="button"
                          title="编辑楼层"
                          onClick={() =>
                            onOpenComposer({
                              action: "EDIT",
                              topicId: summary.id,
                              topicTitle: detail?.title || summary.title,
                              postId: post.id,
                              postNumber: post.postNumber,
                              initialCategoryId: detail?.categoryId ?? summary.categoryId,
                              initialTitle:
                                post.postNumber === 1
                                  ? detail?.title || summary.title
                                  : undefined,
                              initialTags:
                                post.postNumber === 1
                                  ? detail?.tags || summary.tags
                                  : undefined,
                            })
                          }
                          className="lindo-interactive flex h-7 w-7 items-center justify-center rounded-full"
                        >
                          <PencilSimple size={15} />
                        </button>
                      )}

                      {/* 删除本人回复楼层 */}
                      {canDeleteThisFloor && (
                        <button
                          type="button"
                          title="删除回复"
                          onClick={() => setDeletingPost(post)}
                          className="lindo-interactive flex h-7 w-7 items-center justify-center rounded-full"
                        >
                          <Trash size={14.5} />
                        </button>
                      )}

                      {/* 举报违规楼层（非本人且未删除楼层） */}
                      {(!currentUser ||
                        post.username.toLowerCase() !== currentUser.username.toLowerCase()) &&
                        !post.isDeletedTombstone &&
                        !post.isSmallAction && (
                          <button
                            type="button"
                            title="举报此楼"
                            onClick={() => {
                              if (!isAuthorized) {
                                onRequestGuestLogin(
                                  "登录后举报违规发言",
                                  "登录后可向社区版主反馈垃圾广告或不当内容"
                                );
                                return;
                              }
                              setFlaggingPost(post);
                            }}
                            className="lindo-interactive flex h-7 w-7 items-center justify-center rounded-full"
                          >
                            <Flag size={14.5} />
                          </button>
                        )}

                      {/* 回复此楼 */}
                      <button
                        type="button"
                        title={`回复 #${post.postNumber} 楼`}
                        onClick={() => {
                          if (!isAuthorized) {
                            onRequestGuestLogin(
                              "登录后参与回复",
                              "登录后即可回复楼主或任意楼层"
                            );
                            return;
                          }
                          onOpenComposer({
                            action: "REPLY",
                            topicId: summary.id,
                            topicTitle: detail?.title || summary.title,
                            replyToPostNumber: post.postNumber > 1 ? post.postNumber : undefined,
                            replyToUsername: post.username,
                            replyToSnippet: post.cookedHtml
                              .replace(/<[^>]+>/g, " ")
                              .replace(/\s+/g, " ")
                              .trim()
                              .slice(0, 120),
                          });
                        }}
                        className="lindo-interactive flex h-7 w-7 items-center justify-center rounded-full"
                      >
                        <ArrowBendUpLeft size={15} />
                      </button>
                    </div>
                  </div>

                  {/* 内联展开的子回复列表 */}
                  {inlineReplies && inlineReplies.length > 0 && (
                    <div
                      className="mt-3 space-y-2.5 rounded-xl p-3"
                      style={{
                        backgroundColor: "var(--subtle-surface)",
                        border: "0.5px solid var(--hairline)",
                      }}
                    >
                      {inlineReplies.map((rep) => (
                        <div key={rep.id} className="text-[13px]">
                          <div className="flex items-center justify-between gap-2">
                            <div className="flex items-center gap-2">
                              <img
                                src={resolveAvatarUrl(rep.avatarTemplate, 96)}
                                alt={rep.username}
                                className="h-5 w-5 rounded-full object-cover"
                              />
                              <span
                                className="font-semibold"
                                style={{ color: "var(--ink-primary)" }}
                              >
                                {rep.displayName || rep.username}
                              </span>
                            </div>
                            <button
                              type="button"
                              onClick={() => jumpToFloor(rep.postNumber)}
                              className="text-[11.5px] hover:underline cursor-pointer"
                              style={{ color: "var(--brand-gold)" }}
                            >
                              跳转 #{rep.postNumber} 楼
                            </button>
                          </div>
                          <div
                            className="lindo-cooked mt-1 pl-7 text-[13px]"
                            dangerouslySetInnerHTML={{
                              __html: prepareCookedHtml(rep.cookedHtml),
                            }}
                          />
                        </div>
                      ))}
                    </div>
                  )}
                </article>
              );
            })}

            {loadingMoreBottom && (
              <div
                className="flex items-center justify-center py-4 text-[12px]"
                style={{ color: "var(--ink-secondary)" }}
              >
                <span>正在加载后续楼层...</span>
              </div>
            )}
          </div>
        )}
      </div>

      {/* 划词悬浮「引用并回复」胶囊 */}
      {selectionQuote && (
        <div
          data-selection-quote-popover="true"
          className="fixed z-50 -translate-x-1/2 rounded-full px-3 py-1.5 shadow-xl"
          style={{
            left: selectionQuote.x,
            top: selectionQuote.y,
            backgroundColor: "#1E222B",
            border: "0.5px solid rgba(255,255,255,0.15)",
          }}
        >
          <button
            type="button"
            onMouseDown={(e) => {
              e.preventDefault();
              e.stopPropagation();
              const captured = selectionQuote;
              setSelectionQuote(null);
              window.getSelection()?.removeAllRanges();
              if (!isAuthorized) {
                onRequestGuestLogin("登录后引用并回复", "登录后即可摘录帖子段落并发表见解");
                return;
              }
              onOpenComposer({
                action: "REPLY",
                topicId: summary.id,
                topicTitle: detail?.title || summary.title,
                replyToPostNumber: captured.post.postNumber,
                replyToUsername: captured.post.username,
                initialRaw: `[quote="${captured.post.username}, post:${captured.post.postNumber}, topic:${summary.id}"]\n${captured.text}\n[/quote]\n\n`,
              });
            }}
            className="flex items-center gap-1.5 text-[12px] font-medium text-white cursor-pointer"
          >
            <Quotes size={13.5} style={{ color: "#FCD34D" }} weight="fill" />
            <span>引用并回复</span>
          </button>
        </div>
      )}

      {/* 底部快捷回复栏 + 楼层进度导航器（抽屉打开时自动隐藏快捷回复条，仅保留悬浮楼层导航胶囊） */}
      <div
        className={
          hideQuickReplyBar
            ? "pointer-events-none absolute inset-x-0 bottom-2.5 z-20 flex items-center justify-end px-5"
            : "lindo-hairline-t absolute inset-x-0 bottom-0 z-20 flex items-center justify-between gap-3 px-5 py-2.5"
        }
        style={
          hideQuickReplyBar
            ? undefined
            : {
                backgroundColor: "var(--card-surface)",
              }
        }
      >
        {!hideQuickReplyBar && (
          <button
            type="button"
            disabled={Boolean(detail?.closed)}
            onClick={() => {
              if (!isAuthorized) {
                onRequestGuestLogin("登录后回复话题", "登录后即可参与社区讨论");
                return;
              }
              onOpenComposer({
                action: "REPLY",
                topicId: summary.id,
                topicTitle: detail?.title || summary.title,
              });
            }}
            className="flex h-[34px] max-w-[360px] flex-1 items-center gap-2 rounded-full px-4 text-[13px] transition-colors cursor-pointer disabled:opacity-55"
            style={{
              backgroundColor: "var(--subtle-surface)",
              border: "0.5px solid var(--hairline)",
              color: "var(--ink-tertiary)",
            }}
          >
            {detail?.closed ? <Lock size={14} /> : <PencilSimple size={14} />}
            <span>{detail?.closed ? "话题已关闭回复" : "回复话题"}</span>
          </button>
        )}

        {/* 楼层导航胶囊 */}
        <div className="pointer-events-auto relative">
          <button
            type="button"
            onClick={() => {
              const cur = visibleFloorRef.current;
              setCurrentVisibleFloor(cur);
              setFloorInputVal(String(cur));
              setFloorNavOpen((v) => !v);
            }}
            className="lindo-interactive flex h-[30px] items-center gap-1 rounded-full px-3 text-[12px] font-medium tabular-nums"
            style={
              hideQuickReplyBar
                ? {
                    backgroundColor: "color-mix(in srgb, var(--subtle-surface) 92%, transparent)",
                    border: "0.5px solid var(--hairline)",
                    color: "var(--ink-secondary)",
                  }
                : { color: "var(--ink-secondary)" }
            }
          >
            <span ref={floorBadgeTextRef}>
              {currentVisibleFloor} / {totalFloors} 楼
            </span>
            <CaretUp size={11.5} />
          </button>

          {floorNavOpen && (
            <div
              className="absolute bottom-10 right-0 z-30 w-[256px] rounded-2xl p-3.5 shadow-2xl"
              style={{
                backgroundColor: "var(--subtle-surface)",
                border: "0.5px solid var(--hairline)",
              }}
            >
              <div
                className="flex items-center justify-between text-[12px] font-semibold"
                style={{ color: "var(--ink-primary)" }}
              >
                <span>跳转楼层</span>
                <span className="tabular-nums" style={{ color: "var(--brand-gold)" }}>
                  #{floorInputVal} / {totalFloors}
                </span>
              </div>
              <input
                type="range"
                min={1}
                max={totalFloors}
                value={Number(floorInputVal) || 1}
                onChange={(e) => setFloorInputVal(e.target.value)}
                onMouseUp={() => {
                  jumpToFloor(Number(floorInputVal) || 1);
                  setFloorNavOpen(false);
                }}
                className="mt-2.5 w-full accent-amber-400 cursor-pointer"
              />
              <div className="mt-2.5 flex items-center gap-2">
                <button
                  type="button"
                  onClick={() => {
                    jumpToFloor(1);
                    setFloorNavOpen(false);
                  }}
                  className="lindo-interactive rounded-lg px-2.5 py-1 text-[11.5px]"
                  style={{
                    backgroundColor: "var(--tonal-pill)",
                    color: "var(--ink-secondary)",
                  }}
                >
                  首楼
                </button>
                <input
                  type="number"
                  min={1}
                  max={totalFloors}
                  value={floorInputVal}
                  onChange={(e) => setFloorInputVal(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") {
                      const f = Math.max(1, Math.min(totalFloors, Number(floorInputVal) || 1));
                      jumpToFloor(f);
                      setFloorNavOpen(false);
                    }
                  }}
                  className="w-16 rounded-lg bg-transparent px-2 py-1 text-center text-[12px] tabular-nums outline-none"
                  style={{
                    border: "0.5px solid var(--hairline)",
                    color: "var(--ink-primary)",
                  }}
                />
                <button
                  type="button"
                  onClick={() => {
                    jumpToFloor(totalFloors);
                    setFloorNavOpen(false);
                  }}
                  className="lindo-interactive ml-auto rounded-lg px-2.5 py-1 text-[11.5px]"
                  style={{
                    backgroundColor: "var(--tonal-pill)",
                    color: "var(--ink-secondary)",
                  }}
                >
                  末楼
                </button>
              </div>
            </div>
          )}
        </div>
      </div>

      {/* 删除回复二次确认 */}
      {deletingPost && (
        <ConfirmDialog
          title={`删除 #${deletingPost.postNumber} 楼回复？`}
          detail="删除后将无法恢复该楼层内容。"
          confirmLabel="删除"
          destructive
          onCancel={() => setDeletingPost(null)}
          onConfirm={async () => {
            const target = deletingPost;
            setDeletingPost(null);
            try {
              await deletePostFloor(target.id);
              onRemovePost?.(target.id);
              setToastMsg("已删除该楼层");
            } catch (e: any) {
              setToastMsg(e?.message || "删除失败");
            }
          }}
        />
      )}

      {/* 外链安全风险分级确认弹窗 */}
      {pendingExternalLink && (
        <ExternalLinkSafetyModal
          assessment={pendingExternalLink}
          onCancel={() => setPendingExternalLink(null)}
          onConfirm={(rememberHost) => {
            const target = pendingExternalLink;
            setPendingExternalLink(null);
            if (rememberHost && target.canRememberHost && target.hostname) {
              rememberTrustedExternalHost(target.hostname);
            }
            openUrl(target.normalizedUrl);
          }}
        />
      )}

      {/* 举报楼层原因选择弹窗 */}
      {flaggingPost && (
        <FlagPostModal
          post={flaggingPost}
          onClose={() => setFlaggingPost(null)}
          onSubmitted={() => {
            setFlaggingPost(null);
            setToastMsg("感谢反馈，举报已提交社区版主处理");
          }}
          onTriggerVerify={onTriggerVerify}
        />
      )}

      {/* 轻提示 Toast */}
      {toastMsg && (
        <div
          className="pointer-events-none absolute bottom-16 left-1/2 z-40 -translate-x-1/2 rounded-full px-4 py-1.5 text-[12.5px] font-medium shadow-xl"
          style={{
            backgroundColor: "var(--subtle-surface)",
            border: "0.5px solid var(--hairline)",
            color: "var(--ink-primary)",
          }}
        >
          {toastMsg}
        </div>
      )}
    </main>
  );
};

/* ==================== 原生可交互投票卡片组件 ==================== */
const InteractivePollCard: React.FC<{
  postId: number;
  poll: PostPollModel;
  isAuthorized: boolean;
  onRequestLogin: () => void;
  onPollUpdated: (next: PostPollModel) => void;
  onError: (msg: string) => void;
}> = ({ postId, poll, isAuthorized, onRequestLogin, onPollUpdated, onError }) => {
  const isMulti = poll.type === "multiple";
  const hasVoted = (poll.myVotes?.length ?? 0) > 0;
  const isClosed = poll.status === "closed";
  const [selectedIds, setSelectedIds] = useState<string[]>(poll.myVotes ?? []);
  const [showResults, setShowResults] = useState(
    hasVoted || isClosed || poll.results === "always"
  );
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setSelectedIds(poll.myVotes ?? []);
    if ((poll.myVotes?.length ?? 0) > 0 || poll.status === "closed") {
      setShowResults(true);
    }
  }, [poll.myVotes, poll.status]);

  const totalVotes = Math.max(
    1,
    poll.voters || poll.options.reduce((acc, o) => acc + o.votes, 0)
  );

  const toggleOption = (id: string) => {
    if (isClosed || busy) return;
    if (isMulti) {
      setSelectedIds((prev) =>
        prev.includes(id)
          ? prev.filter((x) => x !== id)
          : poll.max && prev.length >= poll.max
            ? prev
            : [...prev, id]
      );
    } else {
      setSelectedIds([id]);
    }
  };

  return (
    <div
      className="rounded-xl p-3.5"
      style={{
        backgroundColor: "var(--subtle-surface)",
        border: "0.5px solid var(--hairline)",
      }}
    >
      <div className="mb-2.5 flex items-center justify-between text-[12.5px]">
        <span
          className="flex items-center gap-1.5 font-semibold"
          style={{ color: "var(--ink-primary)" }}
        >
          <ChartBar size={15} style={{ color: "var(--brand-gold)" }} />
          <span>{poll.title || (isMulti ? "多选投票" : "单选投票")}</span>
        </span>
        <span className="text-[11.5px] tabular-nums" style={{ color: "var(--ink-tertiary)" }}>
          {poll.voters} 人参与 {isClosed ? "· 已截止" : ""}
        </span>
      </div>

      <div className="space-y-2">
        {poll.options.map((opt) => {
          const checked = selectedIds.includes(opt.id);
          const pct = Math.min(100, Math.round((opt.votes / totalVotes) * 100));
          return (
            <button
              key={opt.id}
              type="button"
              disabled={isClosed || busy}
              onClick={() => toggleOption(opt.id)}
              className="relative flex w-full items-center justify-between overflow-hidden rounded-lg px-3 py-2 text-left text-[13px] transition-colors cursor-pointer disabled:cursor-default"
              style={{
                backgroundColor: "var(--card-surface)",
                border: checked
                  ? "0.5px solid var(--brand-gold)"
                  : "0.5px solid var(--hairline)",
              }}
            >
              {showResults && (
                <div
                  className="pointer-events-none absolute inset-y-0 left-0 transition-all duration-300"
                  style={{
                    width: `${pct}%`,
                    backgroundColor: checked
                      ? "color-mix(in srgb, var(--brand-gold) 18%, transparent)"
                      : "color-mix(in srgb, var(--ink-tertiary) 12%, transparent)",
                  }}
                />
              )}
              <span
                className="relative z-10 flex items-center gap-2 font-medium"
                style={{ color: "var(--ink-primary)" }}
              >
                {checked && (
                  <Check size={13.5} weight="bold" style={{ color: "var(--brand-gold)" }} />
                )}
                <span dangerouslySetInnerHTML={{ __html: prepareCookedHtml(opt.html) }} />
              </span>
              {showResults && (
                <span
                  className="relative z-10 ml-2 shrink-0 text-[11.5px] font-semibold tabular-nums"
                  style={{ color: checked ? "var(--brand-gold)" : "var(--ink-secondary)" }}
                >
                  {pct}% ({opt.votes})
                </span>
              )}
            </button>
          );
        })}
      </div>

      <div className="mt-3 flex items-center justify-between">
        <div className="flex items-center gap-2">
          {!isClosed && (
            <button
              type="button"
              disabled={busy || selectedIds.length === 0}
              onClick={async () => {
                if (!isAuthorized) {
                  onRequestLogin();
                  return;
                }
                setBusy(true);
                try {
                  const res = await voteInPoll(postId, poll.name, selectedIds);
                  if (res.poll) onPollUpdated(res.poll);
                  setShowResults(true);
                } catch (e: any) {
                  onError(e?.message || "投票失败");
                } finally {
                  setBusy(false);
                }
              }}
              className="rounded-full px-3.5 py-1 text-[12px] font-bold cursor-pointer disabled:opacity-45"
              style={{
                backgroundColor: "var(--publish-btn-bg)",
                color: "var(--publish-btn-fg)",
              }}
            >
              {busy ? "提交中..." : hasVoted ? "更新投票" : "提交投票"}
            </button>
          )}
          {!isClosed && hasVoted && (
            <button
              type="button"
              disabled={busy}
              onClick={async () => {
                setBusy(true);
                try {
                  const next = await removePollVote(postId, poll.name);
                  if (next) onPollUpdated(next);
                  setSelectedIds([]);
                } catch (e: any) {
                  onError(e?.message || "撤回投票失败");
                } finally {
                  setBusy(false);
                }
              }}
              className="lindo-interactive rounded-full px-2.5 py-1 text-[12px]"
              style={{ color: "var(--ink-secondary)" }}
            >
              撤回投票
            </button>
          )}
        </div>

        {poll.results === "always" && !isClosed && (
          <button
            type="button"
            onClick={() => setShowResults((v) => !v)}
            className="text-[11.8px] hover:underline cursor-pointer"
            style={{ color: "var(--ink-tertiary)" }}
          >
            {showResults ? "隐藏结果" : "查看结果"}
          </button>
        )}
      </div>
    </div>
  );
};

/* ==================== 原生楼层举报弹窗（对齐官方 Discourse POST /post_actions.json） ==================== */
const FLAG_REASON_OPTIONS: Array<{
  id: 8 | 4 | 3 | 7;
  title: string;
  desc: string;
}> = [
  {
    id: 8,
    title: "垃圾广告",
    desc: "商业硬广、推广引流、无意义刷屏或疑似机器人账号",
  },
  {
    id: 4,
    title: "不当内容",
    desc: "不友善言论、人身攻击、恶意引战或违反社区准则",
  },
  {
    id: 3,
    title: "偏离主题",
    desc: "与当前话题讨论主旨无关或灌水偏楼",
  },
  {
    id: 7,
    title: "其他问题（上报版主）",
    desc: "需要社区版主介入审核的其他违规情况（请填写简要说明）",
  },
];

const FlagPostModal: React.FC<{
  post: PostItemModel;
  onClose: () => void;
  onSubmitted: () => void;
  onTriggerVerify: () => void;
}> = ({ post, onClose, onSubmitted, onTriggerVerify }) => {
  const [selectedType, setSelectedType] = useState<8 | 4 | 3 | 7>(4);
  const [customMessage, setCustomMessage] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const needsDetail = selectedType === 7;
  const canSubmit = !submitting && (!needsDetail || customMessage.trim().length >= 10);

  const handleSubmit = async () => {
    if (!canSubmit) return;
    setSubmitting(true);
    setErrorMsg(null);
    try {
      await flagPost(
        post.id,
        selectedType,
        needsDetail ? customMessage.trim() : undefined
      );
      onSubmitted();
    } catch (err: any) {
      if (err instanceof SiteError && err.isVerification) {
        onClose();
        onTriggerVerify();
        return;
      }
      setErrorMsg(err?.message || "举报提交未成功，请稍后重试");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/62 p-4"
      onClick={() => {
        if (!submitting) onClose();
      }}
    >
      <div
        className="w-[430px] max-w-full rounded-2xl p-5 shadow-2xl"
        style={{
          backgroundColor: "var(--card-surface)",
          border: "0.5px solid var(--hairline)",
        }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between">
          <div>
            <div className="text-[15px] font-semibold" style={{ color: "var(--ink-primary)" }}>
              举报 #{post.postNumber} 楼
            </div>
            <div className="mt-0.5 text-[12px]" style={{ color: "var(--ink-tertiary)" }}>
              发言人 @{post.username} · 举报将匿名提交至社区版主队列
            </div>
          </div>
          <button
            type="button"
            disabled={submitting}
            onClick={onClose}
            className="lindo-interactive flex h-7 w-7 items-center justify-center rounded-full"
            style={{ color: "var(--ink-secondary)" }}
          >
            <X size={15} />
          </button>
        </div>

        <div className="mt-3.5 space-y-2">
          {FLAG_REASON_OPTIONS.map((opt) => {
            const active = selectedType === opt.id;
            return (
              <button
                key={opt.id}
                type="button"
                onClick={() => {
                  setSelectedType(opt.id);
                  setErrorMsg(null);
                }}
                className="flex w-full items-start gap-2.5 rounded-xl px-3 py-2.5 text-left transition-colors cursor-pointer"
                style={{
                  backgroundColor: active ? "var(--active-chip-bg)" : "var(--subtle-surface)",
                  border: active
                    ? "0.5px solid var(--brand-gold)"
                    : "0.5px solid var(--hairline)",
                }}
              >
                <span
                  className="mt-1 flex h-3.5 w-3.5 shrink-0 items-center justify-center rounded-full"
                  style={{
                    border: active
                      ? "4px solid var(--brand-gold)"
                      : "1.5px solid var(--ink-tertiary)",
                  }}
                />
                <div className="min-w-0 flex-1">
                  <div
                    className="text-[13px] font-semibold"
                    style={{ color: "var(--ink-primary)" }}
                  >
                    {opt.title}
                  </div>
                  <div
                    className="mt-0.5 text-[11.5px] leading-snug"
                    style={{ color: "var(--ink-secondary)" }}
                  >
                    {opt.desc}
                  </div>
                </div>
              </button>
            );
          })}
        </div>

        {needsDetail && (
          <div className="mt-3">
            <textarea
              rows={3}
              value={customMessage}
              onChange={(e) => setCustomMessage(e.target.value)}
              placeholder="请补充具体原因（至少 10 个字，方便版主快速核实）..."
              className="w-full resize-none rounded-xl bg-transparent px-3 py-2 text-[12.5px] outline-none"
              style={{
                backgroundColor: "var(--subtle-surface)",
                border: "0.5px solid var(--hairline)",
                color: "var(--ink-primary)",
              }}
            />
            <div className="mt-1 text-right text-[11px] tabular-nums" style={{ color: "var(--ink-tertiary)" }}>
              {customMessage.trim().length} / 至少 10 字
            </div>
          </div>
        )}

        {errorMsg && (
          <div
            className="mt-3 rounded-xl px-3 py-2 text-[12px]"
            style={{
              backgroundColor: "var(--error-bg)",
              border: "0.5px solid var(--error-border)",
              color: "var(--error-fg)",
            }}
          >
            {errorMsg}
          </div>
        )}

        <div className="mt-4 flex items-center justify-end gap-2">
          <button
            type="button"
            disabled={submitting}
            onClick={onClose}
            className="lindo-interactive rounded-full px-4 py-1.5 text-[12.5px] font-medium"
            style={{ color: "var(--ink-secondary)" }}
          >
            取消
          </button>
          <button
            type="button"
            disabled={!canSubmit}
            onClick={handleSubmit}
            className="rounded-full px-4 py-1.5 text-[12.8px] font-semibold cursor-pointer disabled:opacity-45"
            style={{
              backgroundColor: "var(--publish-btn-bg)",
              color: "var(--publish-btn-fg)",
            }}
          >
            {submitting ? "提交中..." : "提交举报"}
          </button>
        </div>
      </div>
    </div>
  );
};
