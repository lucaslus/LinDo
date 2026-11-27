import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { openUrl } from "@tauri-apps/plugin-opener";
import { isImeComposingEvent } from "../lib/imeGuard";
import { resolveEffectiveViewedFloor } from "../lib/topicReadState";
import {
  LinDoCategoryBadge,
  LinDoIcons,
  LinDoTopicTagChip,
} from "./LinDoIcons";
import type {
  ComposerLaunchRequest,
  CustomEmojiItem,
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
  canShowPostBoostAction,
  classifyBoostSubmitFailure,
  deletePostBoost,
  deletePostFloor,
  desktopScreenTrack,
  fetchPollVoters,
  fetchPostByNumber,
  fetchPostLocationById,
  fetchPostReplies,
  fetchPostsByIds,
  fetchSiteCustomEmojis,
  fetchTopicAiSummary,
  fetchTopicTailNewPosts,
  flagPost,
  handleTitlebarMouseDown,
  markManualRetryIntent,
  removePollVote,
  sendPostBoost,
  setTopicNotificationLevel,
  SiteError,
  togglePostBookmark,
  togglePostReaction,
  useRateLimitAutoRecovery,
  voteInPoll,
} from "../lib/api";
import {
  classifyExternalUrl,
  type ExternalUrlAssessment,
  extractPostReplyPreview,
  parseInternalForumLink,
  prepareCookedHtml,
  prepareCookedHtmlCached,
  rememberTrustedExternalHost,
} from "../lib/cooked";
import { observeCookedForumImages, useForumImageSrc } from "../lib/forumMedia";
import { observeCookedRichContent } from "../lib/richDecorators";
import {
  computeFilterScrollTarget,
  describeRepliesFilterBar,
} from "../lib/topicTransition";
import { mergeIncrementalTailPosts } from "../lib/topicTailRefresh";
import {
  BookmarkReminderModal,
  extractPostHeadings,
  InTopicFindBar,
  PmParticipantsBar,
  PostRevisionsModal,
  ReactionUsersModal,
  type TopicHeadingItem,
  TopicTocPopover,
} from "./ReaderEnhancements";
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
  isFilterSwitching?: boolean;
  filterSettledNonce?: number;
  errorMessage: string | null;
  targetFloor: number | null;
  highlightTargetFloor?: boolean;
  targetJumpNonce: number;
  onlyOpFilter: boolean;
  summaryFilter?: boolean;
  isAuthorized: boolean;
  currentUser: UserProfile | null;
  confirmedReadFloor?: number;
  showBackButton: boolean;
  canGoBackHistory?: boolean;
  previousTopicTitle?: string | null;
  onGoBackHistory?: () => void;
  feedCollapsed?: boolean;
  onToggleFeedCollapse?: () => void;
  hideQuickReplyBar?: boolean;
  emptyPlaceholderText?: string;
  onBack: () => void;
  onRefresh: (opts?: {
    currentFloor?: number;
    inPlace?: boolean;
  }) => Promise<{ addedReplies?: number; error?: string } | void> | void;
  onToggleOnlyOp: () => void;
  onToggleSummaryFilter?: () => void;
  onAppendPosts: (posts: PostItemModel[], position: "before" | "after" | "replace") => void;
  onUpdateDetail?: (updater: (prev: TopicDetailModel) => TopicDetailModel) => void;
  onRemovePost?: (postId: number) => void;
  onOpenComposer: (req: ComposerLaunchRequest) => void;
  onInspectUser: (username: string) => void;
  onInspectBadge?: (badge: GrantedBadge) => void;
  onOpenImageLightbox: (src: string, gallery?: { images: string[]; index: number }) => void;
  onNavigateTopicLink: (topicId: number, postNumber?: number, title?: string, fromFloor?: number) => void;
  onSelectCategory?: (categoryId?: number, slug?: string) => void;
  onSelectTag?: (tag: string) => void;
  onOpenInAppUrl?: (url: string) => void;
  onPmArchiveChanged?: () => void;
  onRequestGuestLogin: (title: string, subtitle: string) => void;
  onTriggerVerify: () => void;
  cfAttentionNeeded?: boolean;
  onDismissCfAttention?: () => void;
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

/**
 * Computes unloaded post stream IDs strictly between two consecutive rendered posts (`P1-11`),
 * enabling non-contiguous floor preservation and inline gap expansion.
 */
export function computeMiddlePostGapIds(
  streamIds: number[],
  prevPostId: number,
  nextPostId: number
): number[] {
  if (!streamIds || streamIds.length === 0) return [];
  const prevIdx = streamIds.indexOf(prevPostId);
  const nextIdx = streamIds.indexOf(nextPostId);
  if (prevIdx < 0 || nextIdx <= prevIdx + 1) return [];
  return streamIds.slice(prevIdx + 1, nextIdx);
}

function resolveReactionFallbackImg(
  reactionId: string,
  customMap?: Record<string, string>
): string {
  const clean = reactionId.trim().replace(/^:|:$/g, "").toLowerCase();
  if (customMap && customMap[clean]) {
    return customMap[clean];
  }
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

const ReplyThumbPreview = React.memo<{
  url: string;
  onOpenLightbox: (src: string) => void;
}>(({ url, onOpenLightbox }) => {
  const src = useForumImageSrc(url);
  if (!src) return null;
  return (
    <span
      role="button"
      tabIndex={0}
      title="点击预览原楼配图"
      onClick={(e) => {
        e.stopPropagation();
        onOpenLightbox(url);
      }}
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          e.stopPropagation();
          onOpenLightbox(url);
        }
      }}
      className="ml-2 h-9 w-9 shrink-0 overflow-hidden rounded-lg transition-opacity hover:opacity-85 cursor-pointer"
      style={{
        backgroundColor: "var(--subtle-surface)",
        border: "0.5px solid var(--hairline)",
      }}
    >
      <img
        src={src}
        alt=""
        loading="lazy"
        decoding="async"
        className="h-full w-full object-cover"
      />
    </span>
  );
});

/**
 * Memoized post HTML body so scrolling, visible-floor updates, or quote popovers NEVER re-run
 * `prepareCookedHtml` or `dangerouslySetInnerHTML`, keeping native text selection 100% intact & 120fps smooth.
 */
const MemoizedCookedBody = React.memo<{
  rawHtml: string;
  stripPolls: boolean;
  currentTopicId?: number;
  onCookedClick: (e: React.MouseEvent<HTMLDivElement>) => void;
  className?: string;
}>(
  ({ rawHtml, stripPolls, currentTopicId, onCookedClick, className = "lindo-cooked mt-3" }) => {
    const bodyRef = useRef<HTMLDivElement | null>(null);
    const sanitized = useMemo(
      () => prepareCookedHtml(rawHtml, { stripPolls, currentTopicId }),
      [rawHtml, stripPolls, currentTopicId]
    );
    useEffect(() => {
      const cleanupImgs = observeCookedForumImages(bodyRef.current);
      const cleanupRich = observeCookedRichContent(bodyRef.current);
      return () => {
        cleanupImgs();
        cleanupRich();
      };
    }, [sanitized]);
    return (
      <div
        ref={bodyRef}
        className={className}
        onClick={onCookedClick}
        dangerouslySetInnerHTML={{ __html: sanitized }}
      />
    );
  },
  (prev, next) =>
    prev.rawHtml === next.rawHtml &&
    prev.stripPolls === next.stripPolls &&
    prev.currentTopicId === next.currentTopicId &&
    prev.onCookedClick === next.onCookedClick &&
    prev.className === next.className
);

export const TopicReaderPane: React.FC<TopicReaderPaneProps> = ({
  summary,
  detail,
  isLoading,
  isFilterSwitching = false,
  filterSettledNonce = 0,
  errorMessage,
  targetFloor,
  highlightTargetFloor = false,
  targetJumpNonce,
  onlyOpFilter,
  summaryFilter = false,
  isAuthorized,
  currentUser,
  confirmedReadFloor = 0,
  showBackButton,
  canGoBackHistory = false,
  previousTopicTitle = null,
  onGoBackHistory,
  feedCollapsed = false,
  onToggleFeedCollapse,
  hideQuickReplyBar = false,
  emptyPlaceholderText = "选择左侧话题开始阅读",
  onBack,
  onRefresh,
  onToggleOnlyOp,
  onToggleSummaryFilter,
  onAppendPosts,
  onUpdateDetail,
  onRemovePost,
  onOpenComposer,
  onInspectUser,
  onInspectBadge,
  onOpenImageLightbox,
  onNavigateTopicLink,
  onSelectCategory,
  onSelectTag,
  onOpenInAppUrl,
  onPmArchiveChanged,
  onRequestGuestLogin,
  onTriggerVerify,
  cfAttentionNeeded = false,
  onDismissCfAttention,
  onFloorSeen,
}) => {
  const scrollContainerRef = useRef<HTMLDivElement | null>(null);
  const [moreMenuOpen, setMoreMenuOpen] = useState(false);
  const [notifMenuOpen, setNotifMenuOpen] = useState(false);
  const [copiedLink, setCopiedLink] = useState(false);
  const [aiSummaryOpen, setAiSummaryOpen] = useState(false);
  const [aiSummaryText, setAiSummaryText] = useState<string | null>(null);
  const [aiSummaryLoading, setAiSummaryLoading] = useState(false);
  const [flashTarget, setFlashTarget] = useState<{ topicId: number; floor: number } | null>(null);
  const flashTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [currentVisibleFloor, setCurrentVisibleFloor] = useState(1);

  const opFloorPost = useMemo(
    () => detail?.posts.find((p) => p.postNumber === 1) ?? null,
    [detail?.posts]
  );
  const resolvedTopicOp = useMemo(() => {
    const summaryOp = summary?.originalPoster;
    const summaryHasRealOp = Boolean(
      summaryOp?.username && (summaryOp.username !== "linuxdo" || summaryOp.avatarTemplate)
    );
    const username =
      opFloorPost?.username ||
      detail?.opUsername ||
      (summaryHasRealOp ? summaryOp?.username : "") ||
      "";
    const displayName =
      opFloorPost?.displayName ||
      detail?.opDisplayName ||
      (summaryHasRealOp ? summaryOp?.name : undefined) ||
      username;
    const avatarTemplate =
      opFloorPost?.avatarTemplate ||
      detail?.opAvatarTemplate ||
      (summaryHasRealOp ? summaryOp?.avatarTemplate : "") ||
      "";
    const animatedAvatar =
      opFloorPost?.animatedAvatar ??
      detail?.opAnimatedAvatar ??
      (summaryHasRealOp ? summaryOp?.animatedAvatar : null) ??
      null;
    return { username, displayName, avatarTemplate, animatedAvatar };
  }, [opFloorPost, detail?.opUsername, detail?.opDisplayName, detail?.opAvatarTemplate, detail?.opAnimatedAvatar, summary?.originalPoster]);

  const clearFloorFlash = useCallback(() => {
    if (flashTimerRef.current) {
      clearTimeout(flashTimerRef.current);
      flashTimerRef.current = null;
    }
    setFlashTarget(null);
  }, []);

  const triggerFloorFlash = useCallback((topicId: number, floor: number) => {
    if (flashTimerRef.current) {
      clearTimeout(flashTimerRef.current);
      flashTimerRef.current = null;
    }
    setFlashTarget({ topicId, floor });
    flashTimerRef.current = setTimeout(() => {
      flashTimerRef.current = null;
      setFlashTarget(null);
    }, 1450);
  }, []);

  useEffect(() => clearFloorFlash, [clearFloorFlash]);
  const [floorNavOpen, setFloorNavOpen] = useState(false);
  const floorNavButtonRef = useRef<HTMLButtonElement>(null);
  const [floorInputVal, setFloorInputVal] = useState("1");
  const [loadingMoreBottom, setLoadingMoreBottom] = useState(false);
  const [refreshingTail, setRefreshingTail] = useState(false);
  const [tailFeedback, setTailFeedback] = useState<string | null>(null);
  const tailFeedbackTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [loadingEarlier, setLoadingEarlier] = useState(false);
  const [loadingGapKey, setLoadingGapKey] = useState<string | null>(null);
  const [activeReactionPickerPostId, setActiveReactionPickerPostId] = useState<number | null>(null);
  const [reactionEmojiSearch, setReactionEmojiSearch] = useState("");
  const [activeFloorMorePostId, setActiveFloorMorePostId] = useState<number | null>(null);
  const pendingReactionPostsRef = useRef<Set<number>>(new Set());
  const [pendingReactionPostIds, setPendingReactionPostIds] = useState<Set<number>>(() => new Set());
  const [activeBoostComposerPostId, setActiveBoostComposerPostId] = useState<number | null>(null);
  const [expandedLinkPostIds, setExpandedLinkPostIds] = useState<Set<number>>(() => new Set());
  const [boostEmojiOpen, setBoostEmojiOpen] = useState(false);
  const [boostEmojiTab, setBoostEmojiTab] = useState<"QUICK" | "CUSTOM">("QUICK");
  const [boostEmojiSearch, setBoostEmojiSearch] = useState("");
  const [siteCustomEmojis, setSiteCustomEmojis] = useState<CustomEmojiItem[]>([]);
  const customEmojiUrlMap = useMemo(() => {
    const map: Record<string, string> = {};
    for (const item of siteCustomEmojis) {
      map[item.name.toLowerCase()] = item.url;
    }
    return map;
  }, [siteCustomEmojis]);

  useEffect(() => {
    if (!boostEmojiOpen && activeReactionPickerPostId === null) return;
    if (siteCustomEmojis.length > 0) return;
    let alive = true;
    fetchSiteCustomEmojis()
      .then((list) => {
        if (alive) setSiteCustomEmojis(list);
      })
      .catch(() => {});
    return () => {
      alive = false;
    };
  }, [boostEmojiOpen, activeReactionPickerPostId, siteCustomEmojis.length]);
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
  const [resolvedParentMap, setResolvedParentMap] = useState<Record<number, PostItemModel>>({});
  const [loadingParentFloors, setLoadingParentFloors] = useState<Set<number>>(() => new Set());
  const [expandedParentPreviewIds, setExpandedParentPreviewIds] = useState<Set<number>>(
    () => new Set()
  );
  const [returnFloor, setReturnFloor] = useState<number | null>(null);
  const attemptedParentFloorsRef = useRef<Set<number>>(new Set());
  const jumpToFloorRef = useRef<(floorNum: number, returnFromFloor?: number, clearReturn?: boolean) => void>(
    () => {}
  );
  const [toastMsg, setToastMsg] = useState<string | null>(null);
  const [locallyViewedFloors, setLocallyViewedFloors] = useState<Set<number>>(() => new Set());
  const locallyViewedRef = useRef<Set<number>>(new Set());
  const confirmedReadFloorRef = useRef(confirmedReadFloor);
  confirmedReadFloorRef.current = confirmedReadFloor;
  // Lock initial read baseline per opened topic so `onFloorSeen` updating `confirmedReadFloor` in the parent
  // NEVER unmounts the unread dot `<span>` before its 700ms GPU opacity/scale fade-out completes!
  const sessionBaselineReadRef = useRef<{ topicId: number; floor: number }>({
    topicId: 0,
    floor: 0,
  });
  if (detail && sessionBaselineReadRef.current.topicId !== detail.id) {
    sessionBaselineReadRef.current = {
      topicId: detail.id,
      floor: Math.max(detail.lastReadPostNumber ?? 0, confirmedReadFloor),
    };
  }
  const baselineReadFloor =
    detail && sessionBaselineReadRef.current.topicId === detail.id
      ? sessionBaselineReadRef.current.floor
      : confirmedReadFloor;

  const knownAuthorBadges = useMemo(() => {
    const map = new Map<number, GrantedBadge[]>();
    for (const p of detail?.posts ?? []) {
      if (p.userId && p.authorBadges && p.authorBadges.length > 0 && !map.has(p.userId)) {
        map.set(p.userId, p.authorBadges);
      }
    }
    return map;
  }, [detail?.posts]);

  const postsByFloor = useMemo(() => {
    const map = new Map<number, PostItemModel>();
    for (const [fStr, p] of Object.entries(resolvedParentMap)) {
      const f = Number(fStr);
      if (f > 0 && p) map.set(f, p);
    }
    for (const p of detail?.posts ?? []) {
      if (p.postNumber > 0) map.set(p.postNumber, p);
    }
    return map;
  }, [detail?.posts, resolvedParentMap]);

  const directChildRepliesByFloor = useMemo(() => {
    const map = new Map<number, PostItemModel[]>();
    for (const p of detail?.posts ?? []) {
      if (p.replyToPostNumber && p.replyToPostNumber > 0 && !p.isDeletedTombstone && !p.isSmallAction) {
        const arr = map.get(p.replyToPostNumber) ?? [];
        arr.push(p);
        map.set(p.replyToPostNumber, arr);
      }
    }
    return map;
  }, [detail?.posts]);

  // Automatically resolve any unloaded parent `replyToPostNumber` floors in a single background batch
  // so every reply floor on PC always shows the parent floor's core content snippet!
  useEffect(() => {
    if (!detail || detail.posts.length === 0) return;
    const loadedSet = new Set<number>();
    for (const p of detail.posts) loadedSet.add(p.postNumber);
    for (const k of Object.keys(resolvedParentMap)) loadedSet.add(Number(k));

    const missingFloors: number[] = [];
    for (const p of detail.posts) {
      const rf = p.replyToPostNumber;
      if (
        rf &&
        rf > 0 &&
        !loadedSet.has(rf) &&
        !attemptedParentFloorsRef.current.has(rf)
      ) {
        missingFloors.push(rf);
      }
    }
    if (missingFloors.length === 0) return;

    const candidateIds = Array.from(
      new Set(
        missingFloors
          .map((f) => detail.streamIds[f - 1])
          .filter((id): id is number => Boolean(id && id > 0))
      )
    ).slice(0, 20);
    if (candidateIds.length === 0) return;

    let cancelled = false;
    // Debounce parent-floor preview batch by 480ms so skimming topics fires ZERO extra requests,
    // and perform at most ONE batch `posts.json` fetch (never `Promise.all` single-floor bursts).
    const debounceTimer = setTimeout(() => {
      if (cancelled) return;
      missingFloors.forEach((f) => attemptedParentFloorsRef.current.add(f));
      setLoadingParentFloors((prev) => {
        const next = new Set(prev);
        missingFloors.forEach((f) => next.add(f));
        return next;
      });

      (async () => {
        const resolvedBatch = new Map<number, PostItemModel>();
        try {
          const fetched = await fetchPostsByIds(
            detail.id,
            candidateIds,
            knownAuthorBadges,
            detail.lastReadPostNumber,
            true
          );
          for (const item of fetched) {
            if (item.postNumber > 0) resolvedBatch.set(item.postNumber, item);
          }
        } catch {
          // ignore background parent preview errors
        } finally {
          if (cancelled) return;
          if (resolvedBatch.size > 0) {
            setResolvedParentMap((prev) => {
              const next = { ...prev };
              resolvedBatch.forEach((val, floor) => {
                next[floor] = val;
              });
              return next;
            });
          }
          setLoadingParentFloors((prev) => {
            const next = new Set(prev);
            missingFloors.forEach((f) => next.delete(f));
            return next;
          });
        }
      })();
    }, 480);

    return () => {
      cancelled = true;
      clearTimeout(debounceTimer);
    };
  }, [detail?.id, detail?.posts, detail?.streamIds, knownAuthorBadges, resolvedParentMap]);

  // Floating selection "引用并回复" state
  const [selectionQuote, setSelectionQuote] = useState<{
    text: string;
    post: PostItemModel;
    x: number;
    y: number;
  } | null>(null);
  const [revisionModal, setRevisionModal] = useState<{ postId: number; postNumber: number } | null>(null);
  const [reactionUsersModal, setReactionUsersModal] = useState<{
    postId: number;
    initialReactionId?: string | null;
  } | null>(null);
  const [bookmarkModalPost, setBookmarkModalPost] = useState<PostItemModel | null>(null);
  const [tocOpen, setTocOpen] = useState(false);
  const [findOpen, setFindOpen] = useState(false);
  const [footnotePopover, setFootnotePopover] = useState<{
    html: string;
    targetId: string;
  } | null>(null);

  const post1Headings = useMemo<TopicHeadingItem[]>(() => {
    const p1 = detail?.posts.find((p) => p.postNumber === 1);
    return p1 ? extractPostHeadings(p1.cookedHtml) : [];
  }, [detail?.posts]);

  useEffect(() => {
    const onFindKey = (e: KeyboardEvent) => {
      if (!summary?.id) return;
      if ((e.metaKey || e.ctrlKey) && !e.shiftKey && !e.altKey && e.key.toLowerCase() === "f") {
        // Don't hijack Find inside an open ComposerModal / input overlay
        const activeTag = (document.activeElement?.tagName || "").toUpperCase();
        if (activeTag === "TEXTAREA") return;
        e.preventDefault();
        setFindOpen(true);
      }
    };
    window.addEventListener("keydown", onFindKey);
    return () => window.removeEventListener("keydown", onFindKey);
  }, [summary?.id]);

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

  useEffect(() => {
    setRefreshingTail(false);
    setTailFeedback(null);
    if (tailFeedbackTimerRef.current) {
      clearTimeout(tailFeedbackTimerRef.current);
      tailFeedbackTimerRef.current = null;
    }
  }, [detail?.id, onlyOpFilter, summaryFilter]);

  useEffect(() => {
    return () => {
      if (tailFeedbackTimerRef.current) clearTimeout(tailFeedbackTimerRef.current);
    };
  }, []);

  const showTransientTailFeedback = useCallback((msg: string) => {
    if (tailFeedbackTimerRef.current) clearTimeout(tailFeedbackTimerRef.current);
    setTailFeedback(msg);
    tailFeedbackTimerRef.current = setTimeout(() => {
      tailFeedbackTimerRef.current = null;
      setTailFeedback(null);
    }, 2400);
  }, []);

  const handleTailIncrementalRefresh = useCallback(async () => {
    if (!detail || refreshingTail || loadingMoreBottom || isLoading) return;
    setRefreshingTail(true);
    setTailFeedback(null);
    markManualRetryIntent();
    try {
      if (summaryFilter) {
        const res = await onRefresh({
          currentFloor: visibleFloorRef.current,
          inPlace: true,
        });
        if (res && typeof res === "object" && typeof res.addedReplies === "number" && res.addedReplies > 0) {
          showTransientTailFeedback(`已加载 ${res.addedReplies} 条新回复`);
        } else if (res && typeof res === "object" && res.error) {
          showTransientTailFeedback(res.error);
        } else {
          showTransientTailFeedback("已是最新精选回复");
        }
        return;
      }

      const lastPost = detail.posts[detail.posts.length - 1];
      const anchorFloor = lastPost?.postNumber || 1;
      const { incomingStreamIds, fetchedPosts } = await fetchTopicTailNewPosts(
        detail.id,
        anchorFloor,
        detail.posts,
        knownAuthorBadgesRef.current,
        detail.lastReadPostNumber
      );

      let addedCount = 0;
      if (onUpdateDetail) {
        onUpdateDetail((prev) => {
          const merged = mergeIncrementalTailPosts(prev, incomingStreamIds, fetchedPosts, {
            onlyOpFilter,
          });
          addedCount = merged.addedCount;
          return merged.detail;
        });
      } else {
        const merged = mergeIncrementalTailPosts(detail, incomingStreamIds, fetchedPosts, {
          onlyOpFilter,
        });
        addedCount = merged.addedCount;
        if (merged.addedCount > 0) {
          const newOnly = merged.detail.posts.filter(
            (p) => !detail.posts.some((existing) => existing.id === p.id)
          );
          if (newOnly.length > 0) onAppendPosts(newOnly, "after");
        }
      }

      if (addedCount > 0) {
        showTransientTailFeedback(`已加载 ${addedCount} 条新回复`);
      } else {
        showTransientTailFeedback("已是最新回复");
      }
    } catch (err) {
      if (err instanceof SiteError && err.isVerification) {
        onTriggerVerify();
        showTransientTailFeedback("需完成站点安全验证");
      } else {
        showTransientTailFeedback(err instanceof Error ? err.message : "刷新失败，请重试");
      }
    } finally {
      setRefreshingTail(false);
    }
  }, [
    detail,
    isLoading,
    loadingMoreBottom,
    onAppendPosts,
    onRefresh,
    onTriggerVerify,
    onUpdateDetail,
    onlyOpFilter,
    refreshingTail,
    showTransientTailFeedback,
    summaryFilter,
  ]);

  const handleCookedClick = useCallback(
    (e: React.MouseEvent<HTMLDivElement>) => {
      const target = e.target as HTMLElement;
      const currentArticle = target.closest<HTMLElement>("[data-floor]");
      const fromFloor = Number(currentArticle?.getAttribute("data-floor") || 0) || visibleFloorRef.current || undefined;

      const navigateInternalTarget = (
        href: string,
        anchorEl?: HTMLAnchorElement | null,
        hintTitle?: string
      ): boolean => {
        const parsed = parseInternalForumLink(href, anchorEl);
        if (!parsed) return false;
        const cleanTitle = (hintTitle || anchorEl?.textContent || "")
          .replace(/\s+/g, " ")
          .trim()
          .slice(0, 120);
        if (parsed.kind === "topic") {
          if (parsed.topicId === summary?.id && parsed.postNumber && parsed.postNumber > 0) {
            jumpToFloorRef.current(parsed.postNumber, fromFloor);
          } else {
            onNavigateTopicLink(
              parsed.topicId,
              parsed.postNumber,
              cleanTitle && !/^https?:\/\//i.test(cleanTitle) ? cleanTitle : undefined,
              fromFloor
            );
          }
          return true;
        }
        if (parsed.kind === "post") {
          void fetchPostLocationById(parsed.postId).then((loc) => {
            if (loc) {
              if (loc.topicId === summary?.id && loc.postNumber > 0) {
                jumpToFloorRef.current(loc.postNumber, fromFloor);
              } else {
                onNavigateTopicLink(
                  loc.topicId,
                  loc.postNumber,
                  cleanTitle && !/^https?:\/\//i.test(cleanTitle) ? cleanTitle : loc.title,
                  fromFloor
                );
              }
            } else if (onOpenInAppUrl) {
              onOpenInAppUrl(href);
            } else {
              openUrl(href);
            }
          });
          return true;
        }
        if (parsed.kind === "user") {
          onInspectUser(parsed.username);
          return true;
        }
        if (parsed.kind === "category" && onSelectCategory) {
          onSelectCategory(parsed.categoryId, parsed.slug);
          return true;
        }
        if (parsed.kind === "tag" && onSelectTag) {
          onSelectTag(parsed.tag);
          return true;
        }
        return false;
      };

      const quoteExpandBtn = target.closest<HTMLButtonElement>("[data-quote-expand]");
      if (quoteExpandBtn) {
        e.preventDefault();
        e.stopPropagation();
        const aside = quoteExpandBtn.closest<HTMLElement>("aside.quote");
        const bq = aside?.querySelector<HTMLElement>("blockquote");
        if (!aside || !bq) return;

        const isExpanded = quoteExpandBtn.getAttribute("aria-expanded") === "true";
        if (isExpanded) {
          const orig = bq.dataset.lindoQuoteExcerptHtml;
          if (orig !== undefined) {
            bq.innerHTML = orig;
            observeCookedForumImages(bq);
            observeCookedRichContent(bq);
          }
          quoteExpandBtn.setAttribute("aria-expanded", "false");
          quoteExpandBtn.setAttribute("title", "展开完整引用");
          return;
        }

        const cachedFull = bq.dataset.lindoQuoteFullHtml;
        if (cachedFull !== undefined) {
          if (bq.dataset.lindoQuoteExcerptHtml === undefined) {
            bq.dataset.lindoQuoteExcerptHtml = bq.innerHTML;
          }
          bq.innerHTML = cachedFull;
          observeCookedForumImages(bq);
          observeCookedRichContent(bq);
          quoteExpandBtn.setAttribute("aria-expanded", "true");
          quoteExpandBtn.setAttribute("title", "收起完整引用");
          return;
        }

        const qTopicId = Number(quoteExpandBtn.getAttribute("data-quote-topic") || 0);
        const qPostNum = Number(quoteExpandBtn.getAttribute("data-quote-post") || 0);
        if (qTopicId <= 0 || qPostNum <= 0 || quoteExpandBtn.dataset.loading === "1") return;

        quoteExpandBtn.dataset.loading = "1";
        quoteExpandBtn.style.opacity = "0.45";
        void fetchPostByNumber(qTopicId, qPostNum)
          .then((loadedPost) => {
            if (!loadedPost?.cookedHtml || !bq.isConnected) {
              setToastMsg("无法加载完整引用内容");
              return;
            }
            if (bq.dataset.lindoQuoteExcerptHtml === undefined) {
              bq.dataset.lindoQuoteExcerptHtml = bq.innerHTML;
            }
            const fullPrepared = prepareCookedHtml(loadedPost.cookedHtml, {
              stripPolls: false,
              currentTopicId: qTopicId,
            });
            bq.dataset.lindoQuoteFullHtml = fullPrepared;
            bq.innerHTML = fullPrepared;
            observeCookedForumImages(bq);
            observeCookedRichContent(bq);
            quoteExpandBtn.setAttribute("aria-expanded", "true");
            quoteExpandBtn.setAttribute("title", "收起完整引用");
          })
          .catch(() => {
            setToastMsg("无法加载完整引用内容");
          })
          .finally(() => {
            delete quoteExpandBtn.dataset.loading;
            quoteExpandBtn.style.opacity = "";
          });
        return;
      }

      const quoteJumpBtn = target.closest<HTMLButtonElement>("[data-quote-jump-floor]");
      if (quoteJumpBtn) {
        e.preventDefault();
        const targetPostNum = Number(quoteJumpBtn.getAttribute("data-quote-jump-floor") || 0);
        const quoteTopicId = Number(quoteJumpBtn.getAttribute("data-quote-jump-topic") || 0);
        const quoteTitle = quoteJumpBtn.getAttribute("data-quote-jump-title") || undefined;
        if (targetPostNum > 0) {
          if (quoteTopicId > 0 && summary?.id && quoteTopicId !== summary.id) {
            onNavigateTopicLink(quoteTopicId, targetPostNum, quoteTitle, fromFloor);
          } else {
            jumpToFloorRef.current(targetPostNum, fromFloor);
          }
        }
        return;
      }
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
        !target.classList.contains("avatar") &&
        !target.classList.contains("site-icon")
      ) {
        e.preventDefault();
        const fullSrc =
          target.getAttribute("data-full-src") ||
          target.getAttribute("data-canonical-src") ||
          target.src;
        if (fullSrc) {
          // Collect all gallery images from the current post (or entire loaded topic if post has only 1 image)
          const postScope = target.closest<HTMLElement>("[data-floor]") || scrollContainerRef.current;
          const collectFrom = (scope: HTMLElement | null) => {
            if (!scope) return [] as string[];
            const list: string[] = [];
            scope
              .querySelectorAll<HTMLImageElement>(
                ".lindo-cooked img:not(.emoji):not(.avatar):not(.site-icon)"
              )
              .forEach((im) => {
                const s =
                  im.getAttribute("data-full-src") ||
                  im.getAttribute("data-canonical-src") ||
                  im.src;
                if (s && !list.includes(s)) list.push(s);
              });
            return list;
          };
          let galleryImages = collectFrom(postScope);
          if (galleryImages.length <= 1 && scrollContainerRef.current) {
            const topicImages = collectFrom(scrollContainerRef.current);
            if (topicImages.length > galleryImages.length) {
              galleryImages = topicImages;
            }
          }
          const idx = Math.max(0, galleryImages.indexOf(fullSrc));
          onOpenImageLightbox(
            fullSrc,
            galleryImages.length > 1 ? { images: galleryImages, index: idx } : undefined
          );
        }
        return;
      }
      if (target.classList.contains("spoiled")) {
        target.classList.toggle("revealed");
        return;
      }
      const anchor = target.closest("a");
      if (anchor && (anchor.getAttribute("href") || anchor.href)) {
        e.preventDefault();
        if (anchor.classList.contains("lightbox") || anchor.closest(".lightbox-wrapper")) {
          return;
        }
        // Clicks directly on a content `<img>` are handled above (`target instanceof HTMLImageElement`).
        // Any click reaching here on a textless image-only anchor landed in blank layout continuation
        // space beside the image and must never open a browser or navigate.
        if (
          !anchor.textContent?.trim() &&
          anchor.querySelector("img:not(.emoji):not(.avatar):not(.site-icon)")
        ) {
          return;
        }
        if (anchor.classList.contains("mention") && anchor.textContent?.startsWith("@")) {
          onInspectUser(anchor.textContent.slice(1).trim());
          return;
        }
        const rawHref = (anchor.getAttribute("href") || anchor.href || "").trim();

        // In-page footnote & heading anchor handling (NEVER open a WebView window for `#...` anchors!)
        if (rawHref.startsWith("#")) {
          const anchorId = decodeURIComponent(rawHref.slice(1));
          const container = scrollContainerRef.current;
          if (anchorId && container) {
            const escapedId =
              typeof CSS !== "undefined" && typeof CSS.escape === "function"
                ? CSS.escape(anchorId)
                : anchorId.replace(/["\\]/g, "\\$&");
            const localTarget =
              currentArticle?.querySelector<HTMLElement>(`#${escapedId}, [name="${escapedId}"]`) ||
              container.querySelector<HTMLElement>(`#${escapedId}, [name="${escapedId}"]`);
            if (localTarget) {
              const isFootnoteRef =
                anchor.classList.contains("footnote-ref") ||
                Boolean(anchor.closest("sup.footnote-ref")) ||
                /^footnote-\d+/i.test(anchorId);
              if (isFootnoteRef && localTarget.tagName === "LI") {
                const clone = localTarget.cloneNode(true) as HTMLElement;
                clone.querySelectorAll("a.footnote-backref").forEach((b) => b.remove());
                setFootnotePopover({
                  html: clone.innerHTML,
                  targetId: anchorId,
                });
              } else {
                localTarget.scrollIntoView({ behavior: "smooth", block: "center" });
              }
            }
          }
          return;
        }

        if (navigateInternalTarget(rawHref, anchor)) {
          return;
        }

        const assessment = classifyExternalUrl(rawHref, anchor.textContent);
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
        return;
      }

      // Clicking anywhere on an internal linux.do topic/post Onebox card (when not selecting text) opens natively in Lindo PC
      const internalOnebox = target.closest<HTMLElement>("[data-internal-onebox-href]");
      if (internalOnebox) {
        const sel = window.getSelection()?.toString() || "";
        if (!sel.trim()) {
          const obHref = internalOnebox.getAttribute("data-internal-onebox-href") || "";
          const obTitle =
            internalOnebox.querySelector("h3, h4")?.textContent?.trim() || undefined;
          if (obHref && navigateInternalTarget(obHref, null, obTitle)) {
            e.preventDefault();
            return;
          }
        }
      }
    },
    [
      onOpenImageLightbox,
      onInspectUser,
      onNavigateTopicLink,
      onSelectCategory,
      onSelectTag,
      onOpenInAppUrl,
      summary?.id,
    ]
  );

  useEffect(() => {
    setMoreMenuOpen(false);
    setNotifMenuOpen(false);
    setAiSummaryOpen(false);
    setAiSummaryText(null);
    setFloorNavOpen(false);
    setSelectionQuote(null);
    setActiveReactionPickerPostId(null);
    setActiveFloorMorePostId(null);
    setExpandedRepliesMap({});
    setExpandedLinkPostIds(new Set());
    setResolvedParentMap({});
    setLoadingParentFloors(new Set());
    setExpandedParentPreviewIds(new Set());
    setReturnFloor(null);
    setTocOpen(false);
    setFindOpen(false);
    setFootnotePopover(null);
    setRevisionModal(null);
    setReactionUsersModal(null);
    setBookmarkModalPost(null);
    visibleFloorRef.current = 1;
    setCurrentVisibleFloor(1);
    attemptedParentFloorsRef.current = new Set();
    locallyViewedRef.current = new Set();
    setLocallyViewedFloors(new Set());
  }, [summary?.id]);

  useEffect(() => {
    if (activeFloorMorePostId === null && activeReactionPickerPostId === null && !aiSummaryOpen && !floorNavOpen) return;
    const onDocDown = (ev: MouseEvent) => {
      const target = ev.target as HTMLElement | null;
      if (floorNavOpen && !target?.closest("[data-floor-navigation]")) setFloorNavOpen(false);
      if (target?.closest("[data-floor-popover]")) return;
      if (target?.closest("[data-ai-summary-popover]")) return;
      setActiveFloorMorePostId(null);
      setActiveReactionPickerPostId(null);
      setAiSummaryOpen(false);
    };
    const onKeyDown = (ev: KeyboardEvent) => {
      if (isImeComposingEvent(ev)) return;
      if (ev.key === "Escape") {
        if (floorNavOpen) {
          setFloorNavOpen(false);
          floorNavButtonRef.current?.focus();
        }
        setActiveFloorMorePostId(null);
        setActiveReactionPickerPostId(null);
        setAiSummaryOpen(false);
      }
    };
    document.addEventListener("mousedown", onDocDown);
    window.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("mousedown", onDocDown);
      window.removeEventListener("keydown", onKeyDown);
    };
  }, [activeFloorMorePostId, activeReactionPickerPostId, aiSummaryOpen, floorNavOpen]);

  const executeToggleReaction = useCallback(
    async (postId: number, reactionId: string, fallbackErrMsg: string) => {
      if (pendingReactionPostsRef.current.has(postId)) return;
      pendingReactionPostsRef.current.add(postId);
      setPendingReactionPostIds(new Set(pendingReactionPostsRef.current));
      setActiveReactionPickerPostId(null);
      try {
        const updated = await togglePostReaction(postId, reactionId);
        if (updated) onAppendPosts([updated], "after");
      } catch (err: any) {
        if (!(err instanceof SiteError && err.isVerification)) {
          setToastMsg(err?.message || fallbackErrMsg);
        }
      } finally {
        pendingReactionPostsRef.current.delete(postId);
        setPendingReactionPostIds(new Set(pendingReactionPostsRef.current));
      }
    },
    [onAppendPosts]
  );

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
        if (highlightTargetFloor) {
          triggerFloorFlash(detail.id, targetFloor);
        } else {
          clearFloorFlash();
        }
      }
    } else if (!targetFloor) {
      clearFloorFlash();
      container.scrollTop = 0;
      const initialFloor = detail.posts[0]?.postNumber ?? 1;
      visibleFloorRef.current = initialFloor;
      setCurrentVisibleFloor(initialFloor);
    }
  }, [detail?.id, targetFloor, highlightTargetFloor, targetJumpNonce, triggerFloorFlash, clearFloorFlash]);

  // Smooth in-place scroll alignment when toggling 全部 / 热门回复 / 只看楼主 (NEVER resets scrollTop to 0!)
  useEffect(() => {
    if (!filterSettledNonce) return;
    const container = scrollContainerRef.current;
    if (!container || !detail?.id) return;
    const dividerEl = container.querySelector<HTMLElement>('[data-replies-divider="1"]');
    if (!dividerEl) return;
    const decision = computeFilterScrollTarget({
      scrollTop: container.scrollTop,
      clientHeight: container.clientHeight,
      dividerOffsetTop: dividerEl.offsetTop,
    });
    if (decision.shouldScroll) {
      container.scrollTo({
        top: decision.targetScrollTop,
        behavior: decision.behavior,
      });
    }
  }, [filterSettledNonce, detail?.id]);

  // Keep imperative 144Hz floor badge text strictly synchronized with topic switches, jumps & refreshes
  useLayoutEffect(() => {
    if (!floorBadgeTextRef.current) return;
    const total = Math.max(detail?.postsCount ?? summary?.postsCount ?? 1, 1);
    const cur = Math.min(Math.max(1, visibleFloorRef.current || currentVisibleFloor || 1), total);
    floorBadgeTextRef.current.textContent = `${cur} / ${total} 楼`;
  }, [currentVisibleFloor, detail?.postsCount, summary?.postsCount, detail?.id, summary?.id]);

  // 144Hz zero-jank scroll tracking: ZERO React re-renders during active scrolling (updates floor badge via direct DOM ref, settles React state & unread dwell only on scroll idle!)
  useEffect(() => {
    const container = scrollContainerRef.current;
    if (!container || !detail?.id) return;
    let rafId = 0;
    let settleTimer: ReturnType<typeof setTimeout> | null = null;
    let dwellTimer: ReturnType<typeof setTimeout> | null = null;
    let lastMeasureMs = 0;
    let lastSettleArmMs = 0;
    const topicEnteredAtMs = performance.now();
    const pendingDwell = new Set<number>();
    const seenServerBatch = new Set<number>();

    // Cache floor elements once per post-list mutation so we NEVER run querySelectorAll during 144Hz scroll frames!
    const floorNodes = Array.from(container.querySelectorAll<HTMLElement>("[data-floor]"));
    const floorNumbers = floorNodes.map((n) => Number(n.dataset.floor || 1));

    const computeEffectiveFloor = (curDetail: TopicDetailModel, rawMaxFloor: number) =>
      resolveEffectiveViewedFloor({
        rawMaxFloor,
        posts: curDetail.posts,
        streamIds: curDetail.streamIds,
        onlyOpFilter,
        summaryFilter,
        topicHighestPostNumber: summary?.highestPostNumber,
        detailHighestPostNumber: curDetail.highestPostNumber,
        detailPostsCount: curDetail.postsCount,
      });

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
        onFloorSeen(curDetail.id, computeEffectiveFloor(curDetail, maxFloor));
      }, 1000);
    };

    const collectVisibleFloors = (commitReactState: boolean) => {
      const curDetail = detailRef.current;
      if (!curDetail) return;

      const viewTop = container.scrollTop;
      const viewHeight = container.clientHeight;
      const viewMid = viewTop + viewHeight * 0.38;
      const viewBottom = viewTop + viewHeight;

      let bestFloor = visibleFloorRef.current;
      const visibleNow: number[] = [];
      for (let i = 0; i < floorNodes.length; i++) {
        const node = floorNodes[i];
        const top = node.offsetTop;
        const f = floorNumbers[i];
        if (top <= viewMid) {
          bestFloor = f;
        }
        const bottom = top + node.offsetHeight;
        if (top < viewBottom - 16 && bottom > viewTop + 16) {
          visibleNow.push(f);
          if (commitReactState) {
            pendingDwell.add(f);
          }
        }
      }
      if (isAuthorized && visibleNow.length > 0) {
        desktopScreenTrack.updateVisiblePosts(visibleNow, confirmedReadFloorRef.current);
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
      if (isAuthorized) {
        desktopScreenTrack.markUserActivity();
      }
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
    const initRaf = requestAnimationFrame(() => {
      if (targetFloor && targetFloor > 0 && floorNumbers.includes(targetFloor)) {
        pendingDwell.add(targetFloor);
      }
      collectVisibleFloors(true);
    });

    container.addEventListener("scroll", handleScroll, { passive: true });
    return () => {
      container.removeEventListener("scroll", handleScroll);
      cancelAnimationFrame(initRaf);
      if (rafId !== 0) cancelAnimationFrame(rafId);
      if (settleTimer) clearTimeout(settleTimer);
      if (dwellTimer) clearTimeout(dwellTimer);
      if (pendingDwell.size > 0 && detailRef.current && performance.now() - topicEnteredAtMs >= 450) {
        const curDetail = detailRef.current;
        let maxFloor = visibleFloorRef.current;
        pendingDwell.forEach((f) => {
          if (f > maxFloor) maxFloor = f;
          locallyViewedRef.current.add(f);
          seenServerBatch.add(f);
        });
        pendingDwell.clear();
        onFloorSeen(curDetail.id, computeEffectiveFloor(curDetail, maxFloor));
      }
      if (isAuthorized && seenServerBatch.size > 0 && detailRef.current) {
        seenServerBatch.clear();
      }
    };
  }, [detail?.id, detail?.posts.length, isAuthorized, onlyOpFilter, summaryFilter, summary?.highestPostNumber, onAppendPosts, onFloorSeen]);

  // Real 1s dwell-time ScreenTrack lifecycle (`P1-2`): pauses on idle (>3m) or hidden tab, flushes on leave/hide
  useEffect(() => {
    const topicId = detail?.id;
    if (!isAuthorized || !topicId) return;
    const initialFloor = targetFloor && targetFloor > 0 ? targetFloor : detail?.posts[0]?.postNumber ?? 1;
    desktopScreenTrack.onTopicEntered(
      topicId,
      [initialFloor],
      Math.max(detail?.lastReadPostNumber ?? 0, confirmedReadFloorRef.current)
    );
    desktopScreenTrack.setDocumentVisible(
      typeof document === "undefined" ? true : !document.hidden
    );

    const onVisibility = () => {
      desktopScreenTrack.setDocumentVisible(!document.hidden);
    };
    const onUserActivity = () => {
      desktopScreenTrack.markUserActivity();
    };
    const tickId = setInterval(() => {
      desktopScreenTrack.tick();
    }, 1000);

    document.addEventListener("visibilitychange", onVisibility);
    window.addEventListener("pointerdown", onUserActivity, { passive: true });
    window.addEventListener("keydown", onUserActivity, { passive: true });
    return () => {
      clearInterval(tickId);
      document.removeEventListener("visibilitychange", onVisibility);
      window.removeEventListener("pointerdown", onUserActivity);
      window.removeEventListener("keydown", onUserActivity);
      void desktopScreenTrack.onTopicExited(topicId);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [detail?.id, isAuthorized]);

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

  const jumpToFloor = async (
    floorNum: number,
    returnFromFloor?: number,
    clearReturn = false
  ) => {
    if (!detail) return;
    if (clearReturn) {
      setReturnFloor(null);
    } else if (returnFromFloor && returnFromFloor > 0 && returnFromFloor !== floorNum) {
      setReturnFloor(returnFromFloor);
    }
    const container = scrollContainerRef.current;
    const existing = container?.querySelector<HTMLElement>(`[data-floor="${floorNum}"]`);
    if (existing) {
      existing.scrollIntoView({ behavior: "smooth", block: "center" });
      triggerFloorFlash(detail.id, floorNum);
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
          // Preserve non-contiguous loaded slices (`P1-11`) instead of wiping previous floors!
          onAppendPosts(fetched, "after");
          requestAnimationFrame(() => {
            const el = scrollContainerRef.current?.querySelector<HTMLElement>(
              `[data-floor="${floorNum}"]`
            );
            el?.scrollIntoView({ block: "center" });
            triggerFloorFlash(detail.id, floorNum);
          });
        }
      } catch (e: any) {
        setToastMsg(e?.message || "楼层加载失败");
      }
    }
  };
  jumpToFloorRef.current = jumpToFloor;

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

  const handleLoadMiddleGap = async (gapKey: string, gapIds: number[]) => {
    if (!detail || gapIds.length === 0 || loadingGapKey) return;
    setLoadingGapKey(gapKey);
    try {
      const effLastRead = Math.max(detail.lastReadPostNumber ?? 0, confirmedReadFloor);
      const loaded = await fetchPostsByIds(
        detail.id,
        gapIds.slice(0, 20),
        knownAuthorBadges,
        effLastRead > 0 ? effLastRead : detail.lastReadPostNumber
      );
      if (loaded.length > 0) {
        onAppendPosts(loaded, "after");
      }
    } catch (e: any) {
      setToastMsg(e?.message || "展开中间楼层失败，请重试");
    } finally {
      setLoadingGapKey(null);
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
    const localDirect = directChildRepliesByFloor.get(post.postNumber) ?? [];
    if (localDirect.length > 0 && localDirect.length >= post.replyCount) {
      setExpandedRepliesMap((prev) => ({ ...prev, [post.id]: localDirect }));
      return;
    }
    if (localDirect.length > 0) {
      setExpandedRepliesMap((prev) => ({ ...prev, [post.id]: localDirect }));
    }
    setLoadingRepliesPostId(post.id);
    try {
      const replies = await fetchPostReplies(post.id, summary.id);
      const mergedMap = new Map<number, PostItemModel>();
      for (const r of localDirect) mergedMap.set(r.id, r);
      for (const r of replies) mergedMap.set(r.id, r);
      const merged = Array.from(mergedMap.values()).sort(
        (a, b) => a.postNumber - b.postNumber
      );
      setExpandedRepliesMap((prev) => ({ ...prev, [post.id]: merged }));
    } catch (err: any) {
      if (localDirect.length === 0) {
        setToastMsg(err?.message || "加载回复列表失败");
      }
    } finally {
      setLoadingRepliesPostId(null);
    }
  };

  const readerRetrySecs = useRateLimitAutoRecovery(
    !detail || detail.posts.length === 0 ? errorMessage : null,
    onRefresh
  );

  if (!summary) {
    return (
      <main
        className="relative flex h-full flex-1 flex-col select-none"
        style={{ backgroundColor: "var(--canvas)" }}
      >
        <div
          onMouseDown={handleTitlebarMouseDown}
          className="h-[48px] w-full shrink-0"
        />
        {cfAttentionNeeded && (
          <div
            className="lindo-no-drag flex h-[32px] shrink-0 items-center justify-between px-6"
            style={{
              backgroundColor: "var(--canvas)",
              borderTop: "0.5px solid var(--hairline-soft)",
              borderBottom: "0.5px solid var(--hairline-soft)",
            }}
          >
            <div className="flex items-center gap-1.5">
              <LinDoIcons.ShieldCheck size={14} style={{ color: "var(--brand-gold)" }} />
              <span className="text-[12.2px] font-normal" style={{ color: "var(--ink-secondary)" }}>
                连接需安全确认
              </span>
            </div>
            <div className="flex items-center gap-2.5">
              <button
                type="button"
                onClick={onTriggerVerify}
                className="inline-flex items-center gap-0.5 text-[12px] font-medium hover:opacity-85 cursor-pointer"
                style={{ color: "var(--brand-gold)" }}
              >
                <span>去验证</span>
                <span className="text-[13px] leading-none">›</span>
              </button>
              {onDismissCfAttention && (
                <button
                  type="button"
                  onClick={onDismissCfAttention}
                  className="flex h-5 w-5 items-center justify-center rounded hover:opacity-80 cursor-pointer"
                  style={{ color: "var(--ink-tertiary)" }}
                >
                  <LinDoIcons.Close size={11.5} />
                </button>
              )}
            </div>
          </div>
        )}
        <div className="flex flex-1 flex-col items-center justify-center">
          <img
            src="/brand/lingdu-welcome.svg"
            alt="Lindo"
            className="mb-3 h-20 w-20 opacity-80 pointer-events-none"
          />
          <p className="text-[13.5px]" style={{ color: "var(--ink-tertiary)" }}>
            {emptyPlaceholderText}
          </p>
        </div>
      </main>
    );
  }

  const totalFloors = Math.max(detail?.postsCount ?? summary.postsCount, 1);
  function selectFloor(floor: number) {
    setFloorNavOpen(false);
    floorNavButtonRef.current?.focus({ preventScroll: true });
    void jumpToFloor(Math.max(1, Math.min(totalFloors, floor || 1)));
  }
  const archived = detail?.archived ?? summary.archived;
  const closed = detail?.closed ?? summary.closed;
  const isReplyLocked = (archived || closed) && !(currentUser?.admin || currentUser?.moderator);
  const replyLabel = isReplyLocked
    ? (archived ? "话题已归档，无法回复" : "话题已关闭回复")
    : (isAuthorized ? "回复话题" : "登录后回复话题");
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
      {/* 1. 阅读器轻量面包屑顶栏（去掉生硬「话题」二字，展示分类面包屑与 #ID；仅顶栏 48px 响应窗口拖拽） */}
      <header
        onMouseDown={handleTitlebarMouseDown}
        className="flex h-[46px] shrink-0 items-center justify-between px-5"
        style={{
          backgroundColor: "var(--canvas)",
          borderBottom: "0.5px solid var(--hairline-soft)",
        }}
      >
        <div className="flex min-w-0 items-center gap-2">
          {canGoBackHistory && onGoBackHistory ? (
            <button
              type="button"
              onClick={onGoBackHistory}
              title={previousTopicTitle ? `返回：${previousTopicTitle} (⌘[)` : "返回上一话题 (⌘[)"}
              className="lindo-no-drag lindo-interactive -ml-1.5 inline-flex h-7 shrink-0 items-center gap-1 rounded-full px-2.5 text-[12px] font-medium cursor-pointer"
              style={{
                backgroundColor: "var(--subtle-surface)",
                color: "var(--ink-primary)",
              }}
            >
              <LinDoIcons.ArrowLeft size={14} />
              <span>返回</span>
            </button>
          ) : showBackButton ? (
            <button
              type="button"
              onClick={onBack}
              className="lindo-no-drag lindo-interactive -ml-1.5 flex h-8 w-8 items-center justify-center rounded-full"
              style={{ color: "var(--ink-primary)" }}
            >
              <LinDoIcons.ArrowLeft size={18} />
            </button>
          ) : null}

          {detail?.archetype === "private_message" ? (
            <span
              className="inline-flex shrink-0 items-center gap-1.5 text-[12.5px] font-medium"
              style={{ color: "var(--ink-secondary)" }}
            >
              <LinDoIcons.Mail size={13.5} />
              <span>私信</span>
            </span>
          ) : detail?.categoryName || summary.categoryName ? (
            <LinDoCategoryBadge
              name={detail?.categoryName || summary.categoryName}
              fallbackColor={catVis.dotColor}
              iconSize={12.5}
              className="text-[12.5px]"
            />
          ) : (
            <span
              className="text-[12.5px] font-medium"
              style={{ color: "var(--ink-secondary)" }}
            >
              话题
            </span>
          )}

          <span
            className="text-[12px] opacity-40 select-none"
            style={{ color: "var(--ink-tertiary)" }}
          >
            /
          </span>
          <span
            className="text-[12.2px] tabular-nums"
            style={{ color: "var(--ink-tertiary)" }}
          >
            #{summary.id}
          </span>

          {onlyOpFilter && (
            <button
              type="button"
              onClick={onToggleOnlyOp}
              className="lindo-no-drag ml-1 rounded-full px-2 py-0.5 text-[11px] font-semibold cursor-pointer"
              style={{
                backgroundColor: "var(--active-chip-bg)",
                color: "var(--active-chip-text)",
              }}
            >
              只看楼主 ×
            </button>
          )}
          {summaryFilter && onToggleSummaryFilter && (
            <button
              type="button"
              onClick={onToggleSummaryFilter}
              className="lindo-no-drag ml-1 rounded-full px-2 py-0.5 text-[11px] font-semibold cursor-pointer"
              style={{
                backgroundColor: "var(--active-chip-bg)",
                color: "var(--active-chip-text)",
              }}
            >
              热门回复 ×
            </button>
          )}
          {detail?.acceptedAnswerPostNumber && (
            <button
              type="button"
              onClick={() => jumpToFloor(detail.acceptedAnswerPostNumber!)}
              className="lindo-no-drag ml-1 inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-semibold tabular-nums cursor-pointer"
              style={{
                backgroundColor: "rgba(16, 185, 129, 0.14)",
                color: "#10B981",
              }}
            >
              <LinDoIcons.CheckCircleFilled size={12} />
              <span>已解答 · #{detail.acceptedAnswerPostNumber}</span>
            </button>
          )}
        </div>

        <div className="lindo-no-drag flex items-center gap-1" data-ai-summary-popover="1">
          {post1Headings.length >= 3 && (
            <button
              type="button"
              onClick={() => {
                setAiSummaryOpen(false);
                setTocOpen((v) => !v);
              }}
              title="正文目录"
              aria-label="正文目录"
              className="lindo-interactive flex h-8 w-8 items-center justify-center rounded-full"
              style={{
                color: tocOpen ? "var(--brand-gold)" : "var(--ink-secondary)",
              }}
            >
              <LinDoIcons.ListBullet size={16} />
            </button>
          )}
          <button
            type="button"
            onClick={() => setFindOpen((v) => !v)}
            title="帖内查找 (⌘F)"
            aria-label="帖内查找"
            className="lindo-interactive flex h-8 w-8 items-center justify-center rounded-full"
            style={{
              color: findOpen ? "var(--brand-gold)" : "var(--ink-secondary)",
            }}
          >
            <LinDoIcons.Search size={15.5} />
          </button>
          {(detail?.summarizable || detail?.hasSummary) && (
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
              title="AI 话题总结"
              aria-label="AI 话题总结"
              className="lindo-interactive flex h-8 w-8 items-center justify-center rounded-full"
              style={{
                color: aiSummaryOpen ? "var(--brand-gold)" : "var(--ink-secondary)",
              }}
            >
              <LinDoIcons.Sparkles
                size={16}
                style={{ color: "var(--brand-gold)", opacity: aiSummaryOpen ? 1 : 0.85 }}
              />
            </button>
          )}

          <button
            type="button"
            disabled={isLoading}
            onClick={async () => {
              const res = await onRefresh({
                currentFloor: visibleFloorRef.current,
                inPlace: true,
              });
              if (res && typeof res === "object") {
                if (res.error) {
                  setToastMsg(res.error);
                } else if (res.addedReplies && res.addedReplies > 0) {
                  setToastMsg(`已更新，新增 ${res.addedReplies} 条回复`);
                }
              }
            }}
            title="刷新当前话题"
            className="lindo-interactive flex h-8 w-8 items-center justify-center rounded-full disabled:opacity-70"
            style={{
              color: isLoading ? "var(--brand-gold)" : "var(--ink-secondary)",
            }}
          >
            <SmoothRefreshIcon spinning={isLoading} size={16.5} />
          </button>

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
            className="lindo-interactive flex h-8 w-8 items-center justify-center rounded-full"
            style={{
              color: firstPost?.bookmarked ? "var(--brand-gold)" : "var(--ink-secondary)",
            }}
          >
            {firstPost?.bookmarked ? (
              <LinDoIcons.BookmarkFilled size={17.5} />
            ) : (
              <LinDoIcons.Bookmark size={17.5} />
            )}
          </button>

          <button
            type="button"
            onClick={() => setMoreMenuOpen((v) => !v)}
            title="更多操作"
            className="lindo-interactive flex h-8 w-8 items-center justify-center rounded-full"
            style={{
              color: moreMenuOpen ? "var(--brand-gold)" : "var(--ink-secondary)",
            }}
          >
            <LinDoIcons.MoreHoriz size={18.5} />
          </button>
        </div>
      </header>

      {/* 极简后台安全确认细条（无背景色渲染、无多余解释文案、自然下推正文 32px） */}
      {cfAttentionNeeded && (
        <div
          className="lindo-no-drag flex h-[32px] shrink-0 items-center justify-between px-6"
          style={{
            backgroundColor: "var(--canvas)",
            borderBottom: "0.5px solid var(--hairline-soft)",
          }}
        >
          <div className="flex items-center gap-1.5">
            <LinDoIcons.ShieldCheck size={14} style={{ color: "var(--brand-gold)" }} />
            <span className="text-[12.2px] font-normal" style={{ color: "var(--ink-secondary)" }}>
              连接需安全确认
            </span>
          </div>
          <div className="flex items-center gap-2.5">
            <button
              type="button"
              onClick={onTriggerVerify}
              className="inline-flex items-center gap-0.5 text-[12px] font-medium hover:opacity-85 cursor-pointer"
              style={{ color: "var(--brand-gold)" }}
            >
              <span>去验证</span>
              <span className="text-[13px] leading-none">›</span>
            </button>
            {onDismissCfAttention && (
              <button
                type="button"
                onClick={onDismissCfAttention}
                className="flex h-5 w-5 items-center justify-center rounded hover:opacity-80 cursor-pointer"
                style={{ color: "var(--ink-tertiary)" }}
              >
                <LinDoIcons.Close size={11.5} />
              </button>
            )}
          </div>
        </div>
      )}

      {/* 帖内查找悬浮条 (⌘F) */}
      {findOpen && (
        <InTopicFindBar
          containerRef={scrollContainerRef}
          onClose={() => setFindOpen(false)}
        />
      )}

      {/* 长文目录大纲悬浮面板 */}
      {tocOpen && post1Headings.length >= 3 && (
        <TopicTocPopover
          headings={post1Headings}
          onSelect={(item) => {
            const container = scrollContainerRef.current;
            if (!container) return;
            const post1El = container.querySelector<HTMLElement>('[data-floor="1"] .lindo-cooked');
            if (!post1El) return;
            let targetHeading: HTMLElement | null = null;
            if (item.id) {
              const esc =
                typeof CSS !== "undefined" && typeof CSS.escape === "function"
                  ? CSS.escape(item.id)
                  : item.id;
              targetHeading = post1El.querySelector<HTMLElement>(`#${esc}`);
            }
            if (!targetHeading) {
              const all = Array.from(post1El.querySelectorAll<HTMLElement>("h1, h2, h3")).filter(
                (el) => !el.closest("aside.quote, aside.onebox")
              );
              targetHeading = all[item.index] ?? null;
            }
            targetHeading?.scrollIntoView({ behavior: "smooth", block: "start" });
          }}
          onClose={() => setTocOpen(false)}
        />
      )}

      {/* 右上角紧贴弹出的 AI 话题总结浮层（无论在第 1 楼还是第 1280 楼均可即点即看，无 header 分割线） */}
      {aiSummaryOpen && (
        <div
          data-ai-summary-popover="1"
          className="lindo-no-drag lindo-selectable absolute right-5 top-[52px] z-30 flex w-[388px] max-w-[calc(100%-32px)] flex-col rounded-2xl p-4 shadow-2xl select-text"
          style={{
            backgroundColor: "var(--card-surface)",
            border: "0.5px solid var(--hairline)",
          }}
        >
          <div className="mb-2 flex items-center justify-between">
            <div
              className="flex items-center gap-1.5 text-[13px] font-semibold"
              style={{ color: "var(--ink-primary)" }}
            >
              <LinDoIcons.Sparkles size={14} style={{ color: "var(--brand-gold)" }} />
              <span>AI 话题总结</span>
            </div>
            <div className="flex items-center gap-1">
              <button
                type="button"
                title="重新获取总结"
                disabled={aiSummaryLoading}
                onClick={async () => {
                  setAiSummaryLoading(true);
                  try {
                    const txt = await fetchTopicAiSummary(summary.id);
                    setAiSummaryText(txt);
                  } catch (e: any) {
                    setAiSummaryText(e?.message || "总结暂时不可用");
                  } finally {
                    setAiSummaryLoading(false);
                  }
                }}
                className="lindo-interactive flex h-6 w-6 items-center justify-center rounded-full"
                style={{ color: "var(--ink-tertiary)" }}
              >
                <SmoothRefreshIcon spinning={aiSummaryLoading} size={13} />
              </button>
              <button
                type="button"
                onClick={() => setAiSummaryOpen(false)}
                className="lindo-interactive flex h-6 w-6 items-center justify-center rounded-full"
                style={{ color: "var(--ink-tertiary)" }}
              >
                <LinDoIcons.Close size={13.5} />
              </button>
            </div>
          </div>
          <div
            className="lindo-scroll max-h-[min(360px,62vh)] overflow-y-auto text-[13.2px] leading-[1.68] whitespace-pre-wrap"
            style={{ color: aiSummaryLoading ? "var(--ink-secondary)" : "var(--ink-body)" }}
          >
            {aiSummaryLoading ? "正在生成话题摘要…" : aiSummaryText || "暂无可用总结"}
          </div>
        </div>
      )}

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
            <LinDoIcons.User size={14} />
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
              <LinDoIcons.Bell size={14} />
              <span>订阅：{currentNotifLabel}</span>
              <LinDoIcons.ChevronDown size={11.5} />
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
                  if (!(e instanceof SiteError && e.isVerification)) {
                    setToastMsg(e?.message || "操作失败");
                  }
                }
              }}
              className="lindo-interactive flex shrink-0 items-center gap-1.5 rounded-md px-2 py-1"
              style={{ color: "var(--ink-primary)" }}
            >
              {detail.archived ? <LinDoIcons.Reply size={14} /> : <LinDoIcons.Archive size={14} />}
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
            {copiedLink ? <LinDoIcons.Check size={14} /> : <LinDoIcons.Link size={14} />}
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
            <LinDoIcons.ExternalLink size={14} />
            <span>浏览器打开</span>
          </button>
        </div>
      )}

      {/* 2. 主阅读滚动视口（全宽滚轮响应 + 900px 居中高信息密度舒适阅读版心） */}
      <div
        ref={scrollContainerRef}
        className="lindo-scroll lindo-reader-scroll relative flex-1 min-h-0"
      >
        <div className="mx-auto w-full max-w-[900px] px-5">
        {/* 话题大标题与元数据区 */}
        <div
          className="pt-3.5 pb-3"
          style={{ borderBottom: "0.5px solid var(--hairline)" }}
        >
          <h1
            className="lindo-selectable lindo-topic-headline select-text text-[21px] font-semibold leading-[1.38]"
            style={{ color: "var(--ink-primary)" }}
          >
            {((detail?.closed ?? summary.closed) || (detail?.archived ?? summary.archived)) && (
              <LinDoIcons.Lock
                size={15.5}
                className="mr-1.5 inline-block align-[-2px]"
                style={{ color: "var(--ink-tertiary)" }}
              />
            )}
            {detail?.title || summary.title}
          </h1>

          <div className="mt-2 flex flex-wrap items-center justify-between gap-2.5">
            <div className="flex flex-wrap items-center gap-2">
              {(detail?.categoryName || summary.categoryName) &&
                (onSelectCategory && (detail?.categoryId || summary.categoryId) ? (
                  <button
                    type="button"
                    onClick={() => onSelectCategory(detail?.categoryId ?? summary.categoryId)}
                    title={`浏览「${detail?.categoryName || summary.categoryName}」版块`}
                    className="inline-flex items-center hover:opacity-80 transition-opacity cursor-pointer"
                  >
                    <LinDoCategoryBadge
                      name={detail?.categoryName || summary.categoryName}
                      fallbackColor={catVis.dotColor}
                      iconSize={12.5}
                      className="mr-0.5 text-[12.2px]"
                    />
                  </button>
                ) : (
                  <LinDoCategoryBadge
                    name={detail?.categoryName || summary.categoryName}
                    fallbackColor={catVis.dotColor}
                    iconSize={12.5}
                    className="mr-0.5 text-[12.2px]"
                  />
                ))}
              {(detail?.tags ?? summary.tags).map((tg) =>
                onSelectTag ? (
                  <button
                    key={tg}
                    type="button"
                    onClick={() => onSelectTag(tg)}
                    title={`筛选标签 #${tg}`}
                    className="inline-flex items-center hover:opacity-80 transition-opacity cursor-pointer"
                  >
                    <LinDoTopicTagChip tag={tg} />
                  </button>
                ) : (
                  <LinDoTopicTagChip key={tg} tag={tg} />
                )
              )}
            </div>

            <div
              className="flex shrink-0 items-center gap-1.5 text-[12px] tabular-nums"
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
          </div>

          {detail?.archetype === "private_message" && (
            <PmParticipantsBar
              topicId={detail.id}
              participants={detail.allowedUsers ?? []}
              currentUsername={currentUser?.username}
              canInvite={Boolean(detail.canInviteTo && isAuthorized)}
              canRemove={Boolean(detail.canRemoveAllowedUsers && isAuthorized)}
              onInspectUser={onInspectUser}
              onChanged={() => void onRefresh({ inPlace: true })}
              onNotice={(msg) => setToastMsg(msg)}
            />
          )}
        </div>

        {/* 加载态 / 错误态 / 楼层流 */}
        {isLoading && (!detail || detail.posts.length === 0) ? (
          <div className="space-y-6 py-6">
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
          <div className="flex flex-col items-center justify-center py-20 text-center">
            <MascotExpression expression="surprised" sizePx={58} className="mb-3" />
            <p className="text-[13.5px]" style={{ color: "var(--ink-secondary)" }}>
              {readerRetrySecs > 0
                ? `请求较频繁，${readerRetrySecs} 秒后自动恢复…`
                : errorMessage}
            </p>
            <button
              type="button"
              onClick={() => {
                if (errorMessage.includes("验证")) {
                  onTriggerVerify();
                } else if (!isAuthorized && errorMessage.includes("登录")) {
                  onRequestGuestLogin(
                    "该话题需要登录后查看",
                    "登录 LINUX DO 账号后即可访问专属或受限话题"
                  );
                } else {
                  markManualRetryIntent();
                  onRefresh();
                }
              }}
              className="mt-3.5 rounded-full px-4 py-1.5 text-[12.5px] font-semibold cursor-pointer"
              style={{
                backgroundColor: "var(--publish-btn-bg)",
                color: "var(--publish-btn-fg)",
              }}
            >
              {errorMessage.includes("验证")
                ? "去验证"
                : !isAuthorized && errorMessage.includes("登录")
                  ? "去登录"
                  : "重试"}
            </button>
          </div>
        ) : (
          <div>
            {/* 向上加载更早楼层按钮 */}
            {missingEarlierIds.length > 0 && (
              <div className="py-2.5" style={{ borderBottom: "0.5px solid var(--hairline-soft)" }}>
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
                  <LinDoIcons.ChevronUp size={13} />
                  <span>
                    {loadingEarlier
                      ? "正在加载更早楼层..."
                      : `向上加载前序楼层（当前从 ${detail?.posts[0]?.postNumber ?? 1} 楼开始）`}
                  </span>
                </button>
              </div>
            )}

            {detail?.posts.map((post, postIdx) => {
              const prevPost = postIdx > 0 ? detail.posts[postIdx - 1] : null;
              const middleGapIds =
                prevPost && !onlyOpFilter && !summaryFilter
                  ? computeMiddlePostGapIds(detail.streamIds, prevPost.id, post.id)
                  : [];
              const middleGapKey = prevPost ? `${prevPost.id}_${post.id}` : "";
              const middleGapBanner =
                middleGapIds.length > 0 ? (
                  <div
                    key={`gap_${middleGapKey}`}
                    className="py-2.5"
                    style={{ borderBottom: "0.5px solid var(--hairline-soft)" }}
                  >
                    <button
                      type="button"
                      disabled={loadingGapKey === middleGapKey}
                      onClick={() => void handleLoadMiddleGap(middleGapKey, middleGapIds)}
                      className="lindo-interactive flex w-full items-center justify-center gap-1.5 rounded-lg py-1.5 text-[12.5px] font-medium disabled:opacity-55"
                      style={{
                        backgroundColor: "var(--subtle-surface)",
                        color: "var(--active-chip-text)",
                      }}
                    >
                      <LinDoIcons.ChevronDown size={13} />
                      <span>
                        {loadingGapKey === middleGapKey
                          ? "正在展开中间楼层..."
                          : `展开中间 ${middleGapIds.length} 条楼层`}
                      </span>
                    </button>
                  </div>
                ) : null;

              if (post.isDeletedTombstone) {
                return (
                  <React.Fragment key={post.id}>
                    {middleGapBanner}
                    <div
                      data-floor={post.postNumber}
                      className="flex items-center justify-between gap-3 py-2 text-[12.2px]"
                      style={{
                        color: "var(--ink-tertiary)",
                        borderBottom: "0.5px solid var(--hairline-soft)",
                      }}
                    >
                      <div className="flex min-w-0 items-center gap-2">
                        <AvatarWithFlair
                          username={post.username}
                          avatarTemplate={post.avatarTemplate}
                          sizePx={20}
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
                  </React.Fragment>
                );
              }

              if (post.isSmallAction) {
                const actCode = (post.actionCode || "").toLowerCase();
                const smallIcon = actCode.includes("close") || actCode.includes("open") ? (
                  <LinDoIcons.Lock size={13} />
                ) : actCode.includes("pin") || actCode.includes("banner") ? (
                  <LinDoIcons.Pin size={13} />
                ) : actCode.includes("archive") ? (
                  <LinDoIcons.Archive size={13} />
                ) : (
                  <LinDoIcons.Bell size={13} />
                );
                return (
                  <React.Fragment key={post.id}>
                    {middleGapBanner}
                    <div
                      data-floor={post.postNumber}
                      className="my-0.5 flex items-center justify-between gap-3 rounded-lg px-2.5 py-1.5 text-[12.2px]"
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
                            __html: prepareCookedHtmlCached(post.cookedHtml || post.actionCode || ""),
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
                  </React.Fragment>
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
              const localChildReplies = directChildRepliesByFloor.get(post.postNumber) ?? [];
              const effectiveReplyCount = Math.max(post.replyCount, localChildReplies.length);
              const targetReplyFloor =
                post.replyToPostNumber && post.replyToPostNumber > 0 ? post.replyToPostNumber : null;
              const repliedPost = targetReplyFloor ? postsByFloor.get(targetReplyFloor) : undefined;
              const selfPreviewMeta = targetReplyFloor
                ? extractPostReplyPreview(post.id, post.cookedHtml)
                : null;
              const alreadyQuotedSameFloor = Boolean(
                targetReplyFloor &&
                  selfPreviewMeta?.leadingQuotedFloor === targetReplyFloor &&
                  (!selfPreviewMeta.leadingQuotedTopicId ||
                    selfPreviewMeta.leadingQuotedTopicId === summary.id)
              );
              const parentPreviewMeta = repliedPost
                ? extractPostReplyPreview(repliedPost.id, repliedPost.cookedHtml)
                : null;
              const isParentPreviewLoading = Boolean(
                targetReplyFloor && !repliedPost && loadingParentFloors.has(targetReplyFloor)
              );
              const isParentExpanded = expandedParentPreviewIds.has(post.id);
              const canExpandParent = Boolean(repliedPost && parentPreviewMeta?.isLongOrRich);
              const targetReplyUsername =
                repliedPost?.username || post.replyToUsername || "";
              const targetReplyDisplayName =
                repliedPost?.displayName?.trim() ||
                post.replyToDisplayName?.trim() ||
                targetReplyUsername;
              const targetReplyAvatar =
                repliedPost?.avatarTemplate || post.replyToAvatarTemplate || "";
              const targetReplyIsOp = Boolean(
                targetReplyUsername &&
                  detail.opUsername &&
                  targetReplyUsername.toLowerCase() === detail.opUsername.toLowerCase()
              );
              const isOwnPost = Boolean(
                currentUser?.username &&
                  post.username.toLowerCase() === currentUser.username.toLowerCase()
              );
              const myBoost = currentUser?.username
                ? post.boosts.find(
                    (b) => b.username.toLowerCase() === currentUser.username.toLowerCase()
                  )
                : undefined;
              const isTopicArchived = Boolean(detail?.archived ?? summary.archived);
              const canComposeBoost = canShowPostBoostAction({
                isAuthorized,
                isOwnPost,
                isDeletedTombstone: post.isDeletedTombstone,
                isSmallAction: post.isSmallAction,
                topicArchived: isTopicArchived,
                canBoost: post.canBoost,
                hasMyBoost: Boolean(myBoost),
              });
              const boostStats =
                canComposeBoost && activeBoostComposerPostId === post.id
                  ? computeBoostStats(boostDraftText)
                  : null;

              const submitBoostNow = async (rawText: string) => {
                if (!canComposeBoost) return;
                const stats = computeBoostStats(rawText);
                if (!stats.canSubmit || boostSubmitting) return;
                setBoostSubmitting(true);
                try {
                  const created = await sendPostBoost(post.id, rawText.trim());
                  if (created) {
                    onAppendPosts(
                      [
                        {
                          ...post,
                          boosts: [...post.boosts, created],
                          canBoost: false,
                          hasBoostsField: true,
                          hasCanBoostField: true,
                        },
                      ],
                      "after"
                    );
                  } else {
                    onRefresh();
                  }
                  setBoostDraftText("");
                  setBoostEmojiOpen(false);
                  setActiveBoostComposerPostId(null);
                } catch (err: any) {
                  if (err instanceof SiteError && err.isVerification) {
                    // User dismissed the already-presented compact security card
                  } else if (err instanceof SiteError && err.isAuthRequired) {
                    onRequestGuestLogin(
                      "登录已过期",
                      "重新登录后即可继续互动"
                    );
                  } else {
                    const classified = classifyBoostSubmitFailure(err);
                    if (classified.disableCanBoost) {
                      onAppendPosts(
                        [
                          {
                            ...post,
                            canBoost: false,
                            hasCanBoostField: true,
                          },
                        ],
                        "after"
                      );
                      setBoostEmojiOpen(false);
                      setActiveBoostComposerPostId(null);
                    }
                    setToastMsg(classified.message);
                  }
                } finally {
                  setBoostSubmitting(false);
                }
              };

              const isReplyFloor = post.postNumber > 1;
              const isLastRenderedPost = postIdx === (detail?.posts.length ?? 1) - 1;

              const authorAvatarButton = (
                <button
                  type="button"
                  onClick={() => onInspectUser(post.username)}
                  className="mt-0.5 shrink-0 cursor-pointer transition-opacity hover:opacity-85"
                >
                  <AvatarWithFlair
                    username={post.username}
                    avatarTemplate={post.avatarTemplate}
                    animatedAvatar={post.animatedAvatar}
                    primaryGroupName={post.primaryGroupName}
                    flair={post.flair}
                    sizePx={isReplyFloor ? 34 : 38}
                  />
                </button>
              );

              const authorHeaderMeta = (
                <>
                  {/* 第一行：昵称 + 管理盾牌 + 作者荣誉徽章 + 状态 Emoji + 楼主 + 最佳解答 | 右侧楼层时间 */}
                  <div className="flex items-center justify-between gap-2">
                    <div className="flex min-w-0 flex-wrap items-center gap-1.5">
                      <button
                        type="button"
                        onClick={() => onInspectUser(post.username)}
                        className="truncate text-[13.8px] font-semibold leading-snug hover:underline cursor-pointer"
                        style={{ color: "var(--ink-primary)" }}
                      >
                        {post.displayName || post.username}
                      </button>

                      {(post.isAdmin || post.isModerator || post.isGroupModerator) && (
                        <span title={post.isAdmin ? "管理员" : "版主"}>
                          <LinDoIcons.FaShieldHalved
                            size={12.5}
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
                          size={13}
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
                          className="lindo-interactive inline-flex items-center rounded px-0.5 leading-none cursor-pointer"
                        >
                          <StatusEmojiIcon emoji={post.userStatus.emoji} size={13.5} />
                        </button>
                      )}

                      {isOp && (
                        <span
                          className="rounded px-1.5 py-[0.5px] text-[10.5px] font-semibold"
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
                          className="inline-flex items-center gap-0.5 rounded-full px-2 py-[0.5px] text-[10.5px] font-bold"
                          style={{
                            backgroundColor: "rgba(16, 185, 129, 0.14)",
                            color: "#10B981",
                          }}
                        >
                          <LinDoIcons.CheckCircleFilled size={11.5} />
                          <span>最佳解答</span>
                        </span>
                      )}
                    </div>

                    <div
                      className="flex shrink-0 items-center gap-1.5 text-[11.5px] tabular-nums"
                      style={{ color: "var(--ink-tertiary)" }}
                    >
                      {isAuthorized &&
                        post.isRead === false &&
                        post.postNumber > baselineReadFloor && (
                          <span
                            title="未读新楼层"
                            className="h-[5px] w-[5px] rounded-full pointer-events-none"
                            style={{
                              backgroundColor:
                                "color-mix(in srgb, var(--brand-gold) 76%, var(--ink-secondary))",
                              opacity:
                                locallyViewedFloors.has(post.postNumber) ||
                                post.postNumber <= confirmedReadFloor
                                  ? 0
                                  : 0.58,
                              transition: "opacity 2600ms cubic-bezier(0.42, 0, 0.2, 1)",
                            }}
                          />
                        )}
                      {(post.version ?? 1) > 1 && post.canViewEditHistory !== false && (
                        <button
                          type="button"
                          onClick={() =>
                            setRevisionModal({ postId: post.id, postNumber: post.postNumber })
                          }
                          title={`查看编辑历史（已编辑 ${(post.version ?? 2) - 1} 次）`}
                          className="lindo-interactive inline-flex items-center gap-0.5 rounded px-1 py-0.5 text-[11px]"
                          style={{ color: "var(--ink-tertiary)" }}
                        >
                          <LinDoIcons.Edit size={11} />
                          <span>{(post.version ?? 2) - 1}</span>
                        </button>
                      )}
                      <span>{formatRelativeTime(post.createdAt)}</span>
                      <span>#{post.postNumber}</span>
                    </div>
                  </div>

                  {/* 第二行：@handle + 社区头衔 +  Apple 设备机型 + 本楼首次解锁徽章 + 生辰/纪念日 */}
                  {hasSubMetaRow && (
                    <div className="mt-[1px] flex flex-wrap items-center gap-x-1.5 gap-y-0.5 text-[11.5px] leading-tight">
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
                          className={`text-[11.5px] ${
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
                          <LinDoIcons.AppleLogo size={12} className="shrink-0" />
                          <span>{post.iosDeviceName}</span>
                        </span>
                      )}

                      {firstTimeBadges.map((gb) => (
                        <button
                          key={`gb_${post.id}_${gb.id}`}
                          type="button"
                          title={`首次获得徽章：${gb.name}`}
                          onClick={() => onInspectBadge?.(gb)}
                          className="inline-flex items-center gap-1 rounded-md px-1.5 py-[1px] text-[10.8px] font-medium transition-opacity hover:opacity-85 cursor-pointer"
                          style={{
                            backgroundColor: "var(--subtle-surface)",
                            border: "0.5px solid var(--hairline)",
                            color: "var(--ink-primary)",
                          }}
                        >
                          <OfficialBadgeIcon badge={gb} size={12} />
                          <span>{gb.name}</span>
                        </button>
                      ))}

                      {post.isTodayBirthday && (
                        <span title="今天是作者生日！" className="text-[11.5px]">
                          🎂
                        </span>
                      )}
                      {post.isTodayCakeday && (
                        <span title="今天是作者入站纪念日！" className="text-[11.5px]">
                          🍰
                        </span>
                      )}
                    </div>
                  )}

                  {expandedStatusPostId === post.id && post.userStatus?.description && (
                    <div
                      className="mt-1 inline-flex items-center gap-1.5 rounded-lg px-2 py-0.5 text-[11.5px]"
                      style={{
                        backgroundColor: "var(--subtle-surface)",
                        border: "0.5px solid var(--hairline)",
                        color: "var(--ink-secondary)",
                      }}
                    >
                      <StatusEmojiIcon emoji={post.userStatus.emoji} size={13} />
                      <span>{post.userStatus.description}</span>
                    </div>
                  )}
                </>
              );

              const floorContentBody = (
                <>
                  {/* 回复目标楼层上下文预览卡片（若正文首段已直接引用同楼层，则由单张引用卡统一承担，避免双重头部堆叠） */}
                  {targetReplyFloor && !alreadyQuotedSameFloor && (
                    <div
                      onClick={() => {
                        if (!isParentExpanded) {
                          void jumpToFloor(targetReplyFloor, post.postNumber);
                        }
                      }}
                      role={isParentExpanded ? undefined : "button"}
                      tabIndex={isParentExpanded ? undefined : 0}
                      onKeyDown={(e) => {
                        if (!isParentExpanded && (e.key === "Enter" || e.key === " ")) {
                          e.preventDefault();
                          void jumpToFloor(targetReplyFloor, post.postNumber);
                        }
                      }}
                      className={`mt-1.5 rounded-r-lg border-l-2 pl-2.5 pr-2 py-1.5 ${
                        isParentExpanded ? "" : "lindo-interactive cursor-pointer"
                      }`}
                      style={{
                        borderLeftColor: "color-mix(in srgb, var(--brand-gold) 65%, var(--hairline))",
                        backgroundColor: "color-mix(in srgb, var(--subtle-surface) 64%, transparent)",
                      }}
                    >
                      <div className="flex items-center justify-between gap-2 text-[11.8px]">
                        <div className="flex min-w-0 items-center gap-1.5">
                          <AvatarWithFlair
                            username={targetReplyUsername || "linuxdo"}
                            avatarTemplate={targetReplyAvatar}
                            animatedAvatar={repliedPost?.animatedAvatar}
                            primaryGroupName={repliedPost?.primaryGroupName}
                            flair={repliedPost?.flair}
                            sizePx={18}
                            className="shrink-0"
                          />
                          <span className="shrink-0" style={{ color: "var(--ink-tertiary)" }}>
                            回复
                          </span>
                          <span
                            className="truncate font-semibold"
                            style={{ color: "var(--ink-primary)" }}
                          >
                            {targetReplyDisplayName}
                          </span>
                          {targetReplyIsOp && (
                            <span
                              className="shrink-0 rounded px-1 py-[0.5px] text-[10px] font-semibold"
                              style={{
                                backgroundColor: "var(--active-chip-bg)",
                                color: "var(--brand-gold)",
                              }}
                            >
                              楼主
                            </span>
                          )}
                          <span
                            className="shrink-0 tabular-nums"
                            style={{ color: "var(--ink-tertiary)" }}
                          >
                            · #{targetReplyFloor} 楼
                          </span>
                        </div>

                        <div className="flex shrink-0 items-center gap-0.5">
                          {canExpandParent && (
                            <button
                              type="button"
                              onClick={(e) => {
                                e.stopPropagation();
                                setExpandedParentPreviewIds((prev) => {
                                  const next = new Set(prev);
                                  if (next.has(post.id)) next.delete(post.id);
                                  else next.add(post.id);
                                  return next;
                                });
                              }}
                              title={isParentExpanded ? "收起原楼" : "展开原楼"}
                              aria-label={isParentExpanded ? "收起原楼" : "展开原楼"}
                              className="lindo-interactive flex h-6 w-6 items-center justify-center rounded-full"
                              style={{ color: "var(--ink-tertiary)" }}
                            >
                              {isParentExpanded ? (
                                <LinDoIcons.ChevronUp size={13.5} />
                              ) : (
                                <LinDoIcons.ChevronDown size={13.5} />
                              )}
                            </button>
                          )}
                          <button
                            type="button"
                            onClick={(e) => {
                              e.stopPropagation();
                              void jumpToFloor(targetReplyFloor, post.postNumber);
                            }}
                            title={`定位至 #${targetReplyFloor} 楼`}
                            aria-label={`定位至 #${targetReplyFloor} 楼`}
                            className="lindo-interactive flex h-6 w-6 items-center justify-center rounded-full"
                            style={{ color: "var(--ink-tertiary)" }}
                          >
                            <LinDoIcons.ChevronRight size={13.5} />
                          </button>
                        </div>
                      </div>

                      {isParentExpanded && repliedPost ? (
                        <MemoizedCookedBody
                          rawHtml={repliedPost.cookedHtml}
                          stripPolls={false}
                          currentTopicId={summary.id}
                          onCookedClick={handleCookedClick}
                          className="lindo-cooked lindo-cooked-reply lindo-hairline-t mt-1.5 pt-1.5 text-[13.5px]"
                        />
                      ) : parentPreviewMeta?.snippet ||
                        parentPreviewMeta?.firstImageUrl ||
                        isParentPreviewLoading ? (
                        <div className="mt-0.5 flex items-center justify-between gap-2 pl-[24px]">
                          <p
                            className="min-w-0 flex-1 line-clamp-2 text-[12.5px] leading-[1.48]"
                            style={{ color: "var(--ink-secondary)" }}
                          >
                            {isParentPreviewLoading
                              ? "正在加载原楼内容…"
                              : parentPreviewMeta?.snippet || "[图片]"}
                          </p>
                          {parentPreviewMeta?.firstImageUrl && (
                            <ReplyThumbPreview
                              url={parentPreviewMeta.firstImageUrl}
                              onOpenLightbox={onOpenImageLightbox}
                            />
                          )}
                        </div>
                      ) : null}
                    </div>
                  )}

                  {/* 正文富文本渲染（独立 Memo 隔离，零楼层悬浮重绘，紧凑清晰阅读节奏） */}
                  <MemoizedCookedBody
                    rawHtml={post.cookedHtml}
                    stripPolls={hasPolls}
                    currentTopicId={summary.id}
                    onCookedClick={handleCookedClick}
                    className={
                      isReplyFloor
                        ? "lindo-cooked lindo-cooked-reply mt-1.5"
                        : "lindo-cooked mt-2.5"
                    }
                  />

                  {/* 原生可交互投票卡片 (Polls) */}
                  {hasPolls && (
                    <div className="mt-2.5 space-y-2">
                      {post.polls.map((poll) => (
                        <InteractivePollCard
                          key={poll.name}
                          postId={post.id}
                          poll={poll}
                          isAuthorized={isAuthorized}
                          onRequestLogin={() =>
                            onRequestGuestLogin("登录后参与投票", "登录后即可参与社区话题投票")
                          }
                          onInspectUser={onInspectUser}
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

                  {/* 短评 (Boosts) 展示胶囊区（点击展开作者账号与删除入口，对齐移动端） */}
                  {post.boosts.length > 0 && (
                    <div className="mt-2 flex flex-wrap items-center gap-1.5">
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
                            <AvatarWithFlair
                              username={boost.username}
                              avatarTemplate={boost.avatarTemplate}
                              sizePx={16}
                            />
                            <span
                              className="lindo-cooked text-[12px] leading-none"
                              dangerouslySetInnerHTML={{ __html: prepareCookedHtmlCached(boost.cooked) }}
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
                                title="删除短评"
                                onClick={async (e) => {
                                  e.stopPropagation();
                                  try {
                                    await deletePostBoost(boost.id);
                                    const isDeletingOwnBoost = Boolean(
                                      currentUser?.username &&
                                        boost.username.toLowerCase() ===
                                          currentUser.username.toLowerCase()
                                    );
                                    onAppendPosts(
                                      [
                                        {
                                          ...post,
                                          boosts: post.boosts.filter((b) => b.id !== boost.id),
                                          canBoost: isDeletingOwnBoost
                                            ? !isOwnPost &&
                                              !post.isDeletedTombstone &&
                                              !post.isSmallAction &&
                                              !isTopicArchived
                                            : post.canBoost,
                                          hasBoostsField: true,
                                          hasCanBoostField: true,
                                        },
                                      ],
                                      "after"
                                    );
                                  } catch (err: any) {
                                    setToastMsg(err?.message || "删除短评失败");
                                  }
                                }}
                                className="ml-0.5 inline-flex items-center gap-0.5 rounded-full px-1 py-0.5 text-[10.5px] opacity-75 hover:opacity-100 cursor-pointer"
                                style={{ color: "#EF4444" }}
                              >
                                <LinDoIcons.Trash size={11.5} />
                                {isBoostExpanded && <span>删除</span>}
                              </button>
                            )}
                          </div>
                        );
                      })}
                    </div>
                  )}

                  {/* 独立极简「小火箭 Boost」单行输入条（1:1 对齐移动端：默认仅单行清爽输入 + 左侧纯矢量表情按钮按需展开抽屉） */}
                  {canComposeBoost && activeBoostComposerPostId === post.id && boostStats && (() => {
                    const counterOverLimit =
                      boostStats.exceedsLength || boostStats.exceedsEmojiLimit;
                    const canSendNow = boostStats.canSubmit && !boostSubmitting;
                    return (
                      <div className="mt-2 space-y-1.5">
                        {counterOverLimit && (
                          <div className="px-1 text-[11.8px] font-medium" style={{ color: "#EF4444" }}>
                            {boostStats.exceedsEmojiLimit
                              ? `最多添加 ${boostStats.maxEmojiCount} 个表情`
                              : `最多输入 ${boostStats.maxVisibleLength} 个字符`}
                          </div>
                        )}

                        <div
                          className="flex h-[38px] items-center gap-1.5 rounded-[10px] px-1.5"
                          style={{
                            backgroundColor: "var(--subtle-surface)",
                            border: counterOverLimit
                              ? "0.5px solid var(--error-border)"
                              : "0.5px solid var(--hairline)",
                          }}
                        >
                          {/* 左侧：纯矢量表情与快捷短语抽屉切换按钮 */}
                          <button
                            type="button"
                            title={boostEmojiOpen ? "收起表情与快捷短语" : "选择表情或快捷短语"}
                            onClick={() => setBoostEmojiOpen((v) => !v)}
                            className="lindo-interactive flex h-[30px] w-[30px] shrink-0 items-center justify-center rounded-lg"
                            style={{
                              backgroundColor: boostEmojiOpen
                                ? "var(--active-chip-bg)"
                                : "transparent",
                              color: boostEmojiOpen
                                ? "var(--brand-gold)"
                                : "var(--ink-secondary)",
                            }}
                          >
                            <LinDoIcons.Smile size={17} />
                          </button>

                          <input
                            type="text"
                            autoFocus
                            value={boostDraftText}
                            onChange={(e) =>
                              setBoostDraftText(e.target.value.replace(/\n/g, " "))
                            }
                            placeholder="说点什么…"
                            className="flex-1 bg-transparent px-1 text-[13px] outline-none"
                            style={{ color: "var(--ink-primary)" }}
                            onKeyDown={(e) => {
                              if (isImeComposingEvent(e)) return;
                              if (e.key === "Enter") {
                                e.preventDefault();
                                submitBoostNow(boostDraftText);
                              } else if (e.key === "Escape") {
                                setBoostEmojiOpen(false);
                                setActiveBoostComposerPostId(null);
                              }
                            }}
                          />

                          {/* 右侧：精简字数提示 + 纯矢量发送图标 + 关闭按钮 */}
                          <span
                            className="shrink-0 text-[11.8px] font-medium tabular-nums"
                            style={{
                              color: counterOverLimit ? "#EF4444" : "var(--ink-tertiary)",
                            }}
                          >
                            {boostStats.visibleLength}/{boostStats.maxVisibleLength}
                          </span>

                          <button
                            type="button"
                            disabled={!canSendNow}
                            onClick={() => submitBoostNow(boostDraftText)}
                            title="发送短评"
                            className="flex h-[30px] w-[30px] shrink-0 items-center justify-center rounded-lg transition-colors cursor-pointer disabled:cursor-default"
                            style={{
                              backgroundColor: canSendNow
                                ? "var(--active-chip-bg)"
                                : "transparent",
                              color: canSendNow
                                ? "var(--brand-gold)"
                                : "color-mix(in srgb, var(--ink-tertiary) 45%, transparent)",
                            }}
                          >
                            <LinDoIcons.Send size={15.5} />
                          </button>

                          <button
                            type="button"
                            title="收起短评"
                            onClick={() => {
                              setBoostEmojiOpen(false);
                              setActiveBoostComposerPostId(null);
                            }}
                            className="lindo-interactive flex h-7 w-7 shrink-0 items-center justify-center rounded-full"
                            style={{ color: "var(--ink-tertiary)" }}
                          >
                            <LinDoIcons.Close size={13.5} />
                          </button>
                        </div>

                        {/* 按需展开的表情与快捷短语抽屉（含搜索 + `/emojis.json` 站内自定义表情, `P1-6`） */}
                        {boostEmojiOpen && (() => {
                          const bq = boostEmojiSearch.trim().toLowerCase();
                          const filteredBoostCustom = siteCustomEmojis.filter(
                            (ce) => !bq || ce.name.toLowerCase().includes(bq) || ce.group.toLowerCase().includes(bq)
                          );
                          return (
                            <div
                              className="rounded-xl p-2.5 space-y-2"
                              style={{
                                backgroundColor: "var(--subtle-surface)",
                                border: "0.5px solid var(--hairline-soft)",
                              }}
                            >
                              <div className="flex flex-wrap items-center gap-1">
                                {BOOST_QUICK_PHRASES.map((phrase) => (
                                  <button
                                    key={phrase}
                                    type="button"
                                    onClick={() => setBoostDraftText(phrase)}
                                    className="lindo-interactive rounded-full px-2.5 py-0.5 text-[11.2px] font-medium"
                                    style={{
                                      backgroundColor: "var(--canvas)",
                                      border: "0.5px solid var(--hairline)",
                                      color: "var(--ink-secondary)",
                                    }}
                                  >
                                    {phrase}
                                  </button>
                                ))}
                              </div>
                              <div
                                className="flex flex-wrap items-center gap-1.5 pt-1.5"
                                style={{ borderTop: "0.5px solid var(--hairline-soft)" }}
                              >
                                {[
                                  { key: "QUICK", label: "常用" },
                                  { key: "CUSTOM", label: `站内自定义 (${siteCustomEmojis.length})` },
                                ].map((t) => (
                                  <button
                                    key={t.key}
                                    type="button"
                                    onClick={() => setBoostEmojiTab(t.key as "QUICK" | "CUSTOM")}
                                    className="rounded-full px-2 py-0.5 text-[11px] font-medium cursor-pointer"
                                    style={{
                                      backgroundColor:
                                        boostEmojiTab === t.key ? "var(--active-chip-bg)" : "var(--canvas)",
                                      color:
                                        boostEmojiTab === t.key
                                          ? "var(--active-chip-text)"
                                          : "var(--ink-secondary)",
                                    }}
                                  >
                                    {t.label}
                                  </button>
                                ))}
                                {boostEmojiTab === "CUSTOM" && (
                                  <input
                                    type="text"
                                    value={boostEmojiSearch}
                                    onChange={(e) => setBoostEmojiSearch(e.target.value)}
                                    placeholder="搜索站内表情..."
                                    className="ml-auto h-6 w-36 rounded-md px-2 text-[11.5px] outline-none"
                                    style={{
                                      backgroundColor: "var(--canvas)",
                                      border: "0.5px solid var(--hairline)",
                                      color: "var(--ink-primary)",
                                    }}
                                  />
                                )}
                              </div>
                              {boostEmojiTab === "QUICK" ? (
                                <div className="flex flex-wrap items-center gap-0.5">
                                  {BOOST_QUICK_EMOJIS.map((em) => (
                                    <button
                                      key={em}
                                      type="button"
                                      disabled={!boostStats.canAddEmoji}
                                      onClick={() =>
                                        setBoostDraftText((prev) => `${prev}${em}`)
                                      }
                                      className="lindo-interactive flex h-7 w-7 items-center justify-center rounded-lg text-[15px] disabled:opacity-35 cursor-pointer"
                                    >
                                      {em}
                                    </button>
                                  ))}
                                </div>
                              ) : (
                                <div className="max-h-[110px] flex flex-wrap items-center gap-1 overflow-y-auto lindo-scroll">
                                  {filteredBoostCustom.slice(0, 36).map((ce) => (
                                    <button
                                      key={ce.name}
                                      type="button"
                                      title={`:${ce.name}:`}
                                      disabled={!boostStats.canAddEmoji}
                                      onClick={() =>
                                        setBoostDraftText((prev) => `${prev}:${ce.name}: `)
                                      }
                                      className="lindo-interactive inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-[11px] disabled:opacity-35"
                                      style={{
                                        backgroundColor: "var(--canvas)",
                                        border: "0.5px solid var(--hairline)",
                                        color: "var(--ink-secondary)",
                                      }}
                                    >
                                      <img
                                        src={ce.url}
                                        alt={`:${ce.name}:`}
                                        className="h-3.5 w-3.5 object-contain"
                                      />
                                      <span>:{ce.name}:</span>
                                    </button>
                                  ))}
                                  {filteredBoostCustom.length === 0 && (
                                    <span className="text-[11.5px]" style={{ color: "var(--ink-tertiary)" }}>
                                      未找到匹配的站内表情
                                    </span>
                                  )}
                                </div>
                              )}
                            </div>
                          );
                        })()}
                      </div>
                    );
                  })()}

                  {/* 底部操作栏：固定基线高度（0 表态与 >=1 表态高度 0px 跳动）+ 表情胶囊 + 展开子回复 + 点赞/表情/小火箭/更多/回复 */}
                  <div
                    data-floor-actions="1"
                    className={`relative flex min-h-[28px] flex-wrap items-center justify-between gap-2 select-none ${
                      isReplyFloor ? "mt-1.5" : "mt-2.5"
                    }`}
                  >
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
                              await executeToggleReaction(post.id, r.id, "操作失败");
                            }}
                            className="box-border inline-flex h-[24px] items-center gap-1 rounded-full px-2 text-[11.2px] leading-none tabular-nums transition-colors cursor-pointer select-none"
                            style={{
                              backgroundColor: mine
                                ? "var(--active-chip-bg)"
                                : "var(--subtle-surface)",
                              border: "0.5px solid var(--hairline)",
                              color: mine ? "var(--active-chip-text)" : "var(--ink-secondary)",
                            }}
                          >
                            {mappedEmoji ? (
                              <span className="inline-flex items-center justify-center text-[12.5px] leading-none">
                                {mappedEmoji}
                              </span>
                            ) : (
                              <img
                                src={resolveReactionFallbackImg(r.id, customEmojiUrlMap)}
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

                      {/* 展开 N 条关联回复按钮（1:1 对齐移动端柔和无框设计，锁定 24px 高度零撑高） */}
                      {effectiveReplyCount > 0 && (
                        <button
                          type="button"
                          onClick={() => handleToggleInlineReplies(post)}
                          className="lindo-interactive inline-flex h-[24px] items-center gap-1 rounded-md px-1.5 text-[11.8px] font-medium leading-none"
                          style={{
                            color: inlineReplies ? "var(--brand-gold)" : "var(--ink-secondary)",
                          }}
                        >
                          <span className="tabular-nums">
                            {loadingRepliesPostId === post.id && !inlineReplies
                              ? "加载中..."
                              : `${effectiveReplyCount} 条回复`}
                          </span>
                          {inlineReplies ? (
                            <LinDoIcons.ChevronUp size={12} />
                          ) : (
                            <LinDoIcons.ChevronDown size={12} />
                          )}
                        </button>
                      )}
                    </div>

                    {/* 右侧：克制紧凑 4 键簇（♡ 点赞 · 🚀 小火箭[仅可发短评时展示] · ⋯ 更多 · ↩ 回复；零悬浮透明空洞、零父级重绘） */}
                    {(() => {
                      const activeReactionId =
                        post.currentUserReaction || (post.liked ? "heart" : null);
                      const showReactionBar =
                        !isOwnPost && activeReactionPickerPostId === post.id;
                      const isReactionBusy = pendingReactionPostIds.has(post.id);
                      const isMoreOpen = activeFloorMorePostId === post.id;
                      const btnHCls = isReplyFloor ? "h-[26px]" : "h-[28px]";
                      const btnSqCls = isReplyFloor ? "h-[26px] w-[26px]" : "h-[28px] w-[28px]";
                      const mutedIdleCls = "opacity-75 hover:opacity-100 focus-visible:opacity-100";

                      const quoteThisFloor = () => {
                        if (!isAuthorized) {
                          onRequestGuestLogin("登录后回复话题", "登录后可引用楼层参与讨论");
                          return;
                        }
                        const capturedSel =
                          selectionQuote?.post.id === post.id ? selectionQuote.text : null;
                        if (capturedSel) {
                          setSelectionQuote(null);
                          window.getSelection()?.removeAllRanges();
                        }
                        const plain =
                          capturedSel ||
                          extractPostReplyPreview(post.id, post.cookedHtml).snippet.slice(0, 180) ||
                          post.cookedHtml
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
                          replyToDisplayName: post.displayName,
                          replyToAvatarTemplate: post.avatarTemplate,
                          replyToAnimatedAvatar: post.animatedAvatar,
                          replyToSnippet: plain,
                          initialRaw: `[quote="${post.username}, post:${post.postNumber}, topic:${summary.id}"]\n${plain}\n[/quote]\n\n`,
                        });
                      };

                      const toggleBookmarkFloor = async () => {
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
                      };

                      return (
                        <div
                          className="flex items-center gap-0.5"
                          style={{ color: "var(--ink-tertiary)" }}
                        >
                          {/* 1. 点赞（纯心心按钮，仅点赞/取消赞）+ 独立表情选择入口按钮 */}
                          <div
                            data-floor-popover="true"
                            className="relative flex items-center gap-0.5"
                          >
                            {(() => {
                              const isHeartActive =
                                activeReactionId === "heart" || (!activeReactionId && post.liked);
                              const customReactionId =
                                activeReactionId && activeReactionId !== "heart"
                                  ? activeReactionId
                                  : null;
                              return (
                                <>
                                  <button
                                    type="button"
                                    disabled={isReactionBusy}
                                    title={
                                      isOwnPost
                                        ? "自己的发言"
                                        : isHeartActive
                                          ? "取消赞"
                                          : "点赞"
                                    }
                                    onClick={async () => {
                                      if (isOwnPost || isReactionBusy) return;
                                      if (!isAuthorized) {
                                        onRequestGuestLogin(
                                          "登录后点赞",
                                          "登录后可为喜欢的回复送出赞同"
                                        );
                                        return;
                                      }
                                      await executeToggleReaction(post.id, "heart", "点赞未完成");
                                    }}
                                    className={`flex ${btnHCls} ${
                                      post.likeCount > 0 && post.reactions.length === 0
                                        ? "px-1.5"
                                        : isReplyFloor
                                          ? "w-[26px]"
                                          : "w-[28px]"
                                    } items-center justify-center gap-1 rounded-full text-[11.5px] leading-none tabular-nums ${
                                      isOwnPost
                                        ? "cursor-default opacity-45"
                                        : `lindo-interactive cursor-pointer ${
                                            isReactionBusy
                                              ? "opacity-55"
                                              : isHeartActive
                                                ? ""
                                                : mutedIdleCls
                                          }`
                                    }`}
                                    style={{
                                      color: isHeartActive ? "#E11D48" : "var(--ink-tertiary)",
                                    }}
                                  >
                                    <span className="inline-flex items-center justify-center">
                                      {isHeartActive ? (
                                        <LinDoIcons.HeartFilled size={15} />
                                      ) : (
                                        <LinDoIcons.Heart size={15} />
                                      )}
                                    </span>
                                    {post.likeCount > 0 && post.reactions.length === 0 && (
                                      <span>{post.likeCount}</span>
                                    )}
                                  </button>

                                  {!isOwnPost && !post.isDeletedTombstone && !post.isSmallAction && (
                                    <button
                                      type="button"
                                      disabled={isReactionBusy}
                                      title={customReactionId ? "切换或取消表情回应" : "选择表情回应"}
                                      onClick={() => {
                                        if (!isAuthorized) {
                                          onRequestGuestLogin(
                                            "登录后表情回应",
                                            "登录后可为喜欢的回复送出表情回应"
                                          );
                                          return;
                                        }
                                        setActiveFloorMorePostId(null);
                                        setActiveReactionPickerPostId((prev) =>
                                          prev === post.id ? null : post.id
                                        );
                                      }}
                                      className={`lindo-interactive flex ${btnSqCls} items-center justify-center rounded-full ${
                                        showReactionBar || customReactionId ? "" : mutedIdleCls
                                      } cursor-pointer`}
                                      style={{
                                        color: showReactionBar
                                          ? "var(--brand-gold)"
                                          : "var(--ink-tertiary)",
                                      }}
                                    >
                                      {customReactionId ? (
                                        <span className="text-[14px] leading-none">
                                          {REACTION_EMOJI_MAP[customReactionId] || "😊"}
                                        </span>
                                      ) : (
                                        <LinDoIcons.Smile size={15} />
                                      )}
                                    </button>
                                  )}
                                </>
                              );
                            })()}

                            {showReactionBar && (() => {
                              const rq = reactionEmojiSearch.trim().toLowerCase();
                              const customHits = siteCustomEmojis.filter(
                                (ce) => !rq || ce.name.toLowerCase().includes(rq) || ce.group.toLowerCase().includes(rq)
                              );
                              return (
                                <div
                                  className="absolute bottom-9 right-0 z-30 w-[268px] rounded-2xl p-2 shadow-xl space-y-1.5"
                                  style={{
                                    backgroundColor: "var(--subtle-surface)",
                                    border: "0.5px solid var(--hairline)",
                                  }}
                                >
                                  <div className="flex items-center justify-between gap-0.5">
                                    {REACTION_CHOICES.map((rc) => {
                                      const isSelected = activeReactionId === rc.id;
                                      return (
                                        <button
                                          key={rc.id}
                                          type="button"
                                          disabled={isReactionBusy}
                                          title={rc.label}
                                          onClick={() => {
                                            void executeToggleReaction(post.id, rc.id, "回应未成功");
                                          }}
                                          className="lindo-interactive rounded-full p-1 text-[15px] leading-none cursor-pointer"
                                          style={
                                            isSelected
                                              ? {
                                                  backgroundColor: "var(--active-chip-bg)",
                                                }
                                              : undefined
                                          }
                                        >
                                          {rc.emoji}
                                        </button>
                                      );
                                    })}
                                  </div>
                                  {siteCustomEmojis.length > 0 && (
                                    <div
                                      className="space-y-1.5 pt-1.5"
                                      style={{ borderTop: "0.5px solid var(--hairline-soft)" }}
                                    >
                                      <input
                                        type="text"
                                        value={reactionEmojiSearch}
                                        onChange={(e) => setReactionEmojiSearch(e.target.value)}
                                        placeholder="搜索站内表情回应..."
                                        className="h-6 w-full rounded-md px-2 text-[11px] outline-none"
                                        style={{
                                          backgroundColor: "var(--canvas)",
                                          border: "0.5px solid var(--hairline)",
                                          color: "var(--ink-primary)",
                                        }}
                                      />
                                      <div className="max-h-[84px] flex flex-wrap gap-1 overflow-y-auto lindo-scroll">
                                        {customHits.slice(0, 24).map((ce) => {
                                          const isSelected = activeReactionId === ce.name;
                                          return (
                                            <button
                                              key={ce.name}
                                              type="button"
                                              disabled={isReactionBusy}
                                              title={`:${ce.name}:`}
                                              onClick={() => {
                                                void executeToggleReaction(post.id, ce.name, "回应未成功");
                                              }}
                                              className="lindo-interactive flex h-6 w-6 items-center justify-center rounded-md cursor-pointer"
                                              style={
                                                isSelected
                                                  ? { backgroundColor: "var(--active-chip-bg)" }
                                                  : undefined
                                              }
                                            >
                                              <img
                                                src={ce.url}
                                                alt={`:${ce.name}:`}
                                                className="h-4 w-4 object-contain"
                                              />
                                            </button>
                                          );
                                        })}
                                      </div>
                                    </div>
                                  )}
                                </div>
                              );
                            })()}
                          </div>

                          {/* 2. 短评按钮（仅当服务端允许且当前可正常发送时展示；达上限/已发过/本人/归档/访客一律隐藏） */}
                          {canComposeBoost && (
                            <button
                              type="button"
                              title="发短评"
                              onClick={() => {
                                setBoostEmojiOpen(false);
                                setActiveBoostComposerPostId((prev) =>
                                  prev === post.id ? null : post.id
                                );
                                setBoostDraftText("");
                              }}
                              className={`lindo-interactive flex ${btnSqCls} items-center justify-center rounded-full ${
                                activeBoostComposerPostId === post.id ? "" : mutedIdleCls
                              }`}
                              style={{
                                color:
                                  activeBoostComposerPostId === post.id
                                    ? "var(--brand-gold)"
                                    : "var(--ink-tertiary)",
                              }}
                            >
                              <LinDoIcons.BoostRocket size={15} />
                            </button>
                          )}

                          {/* 已收藏楼层显性金色书签标识（未收藏时收纳进 ⋯ 菜单，保持 99% 楼层极简清爽） */}
                          {post.bookmarked && post.postNumber > 1 && (
                            <button
                              type="button"
                              title="已收藏本楼（点击取消收藏）"
                              onClick={() => void toggleBookmarkFloor()}
                              className={`lindo-interactive flex ${btnSqCls} items-center justify-center rounded-full`}
                              style={{ color: "var(--brand-gold)" }}
                            >
                              <LinDoIcons.BookmarkFilled size={14} />
                            </button>
                          )}

                          {/* 3. 更多操作 (`⋯`)：收纳表情回应、引用、收藏、复制链接、采纳、编辑、举报与删除 */}
                          <div data-floor-popover="true" className="relative">
                            <button
                              type="button"
                              title="更多楼层操作"
                              onClick={() => {
                                setActiveReactionPickerPostId(null);
                                setActiveFloorMorePostId((prev) =>
                                  prev === post.id ? null : post.id
                                );
                              }}
                              className={`lindo-interactive flex ${btnSqCls} items-center justify-center rounded-full ${
                                isMoreOpen || copiedFloorId === post.id ? "" : mutedIdleCls
                              }`}
                              style={{
                                color:
                                  isMoreOpen || copiedFloorId === post.id
                                    ? "var(--brand-gold)"
                                    : "var(--ink-tertiary)",
                              }}
                            >
                              {copiedFloorId === post.id ? (
                                <LinDoIcons.Check size={14.5} />
                              ) : (
                                <LinDoIcons.MoreHoriz size={15.5} />
                              )}
                            </button>

                            {isMoreOpen && (
                              <div
                                className="absolute bottom-8 right-0 z-30 w-[164px] rounded-xl p-1.5 shadow-2xl"
                                style={{
                                  backgroundColor: "var(--card-surface)",
                                  border: "0.5px solid var(--hairline)",
                                }}
                              >
                                {!isOwnPost && !post.isDeletedTombstone && (
                                  <button
                                    type="button"
                                    onClick={() => {
                                      setActiveFloorMorePostId(null);
                                      if (!isAuthorized) {
                                        onRequestGuestLogin(
                                          "登录后发送表情回应",
                                          "登录后可使用全部社区表情回应"
                                        );
                                        return;
                                      }
                                      setActiveReactionPickerPostId(post.id);
                                    }}
                                    className="lindo-interactive flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-[12.8px]"
                                    style={{ color: "var(--ink-primary)" }}
                                  >
                                    <LinDoIcons.Smile
                                      size={15}
                                      style={{ color: "var(--ink-secondary)" }}
                                    />
                                    <span>表情回应</span>
                                  </button>
                                )}

                                <button
                                  type="button"
                                  onClick={() => {
                                    setActiveFloorMorePostId(null);
                                    quoteThisFloor();
                                  }}
                                  className="lindo-interactive flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-[12.8px]"
                                  style={{ color: "var(--ink-primary)" }}
                                >
                                  <LinDoIcons.Quote
                                    size={15}
                                    style={{ color: "var(--ink-secondary)" }}
                                  />
                                  <span>引用本楼</span>
                                </button>

                                  <button
                                    type="button"
                                    onClick={() => {
                                      setActiveFloorMorePostId(null);
                                      if (!isAuthorized) {
                                        onRequestGuestLogin(
                                          "登录后收藏回复",
                                          "可在我的书签随时直达该楼层并设置提醒"
                                        );
                                        return;
                                      }
                                      setBookmarkModalPost(post);
                                    }}
                                    className="lindo-interactive flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-[12.8px]"
                                    style={{ color: "var(--ink-primary)" }}
                                  >
                                    {post.bookmarked ? (
                                      <LinDoIcons.BookmarkFilled
                                        size={15}
                                        style={{ color: "var(--brand-gold)" }}
                                      />
                                    ) : (
                                      <LinDoIcons.Bookmark
                                        size={15}
                                        style={{ color: "var(--ink-secondary)" }}
                                      />
                                    )}
                                    <span>{post.bookmarked ? "编辑书签提醒" : "收藏并设提醒"}</span>
                                  </button>

                                  {(post.reactions.length > 0 || post.likeCount > 0) && (
                                    <button
                                      type="button"
                                      onClick={() => {
                                        setActiveFloorMorePostId(null);
                                        setReactionUsersModal({
                                          postId: post.id,
                                          initialReactionId: post.reactions[0]?.id ?? "heart",
                                        });
                                      }}
                                      className="lindo-interactive flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-[12.8px]"
                                      style={{ color: "var(--ink-primary)" }}
                                    >
                                      <LinDoIcons.Users
                                        size={15}
                                        style={{ color: "var(--ink-secondary)" }}
                                      />
                                      <span>查看表态成员</span>
                                    </button>
                                  )}

                                <button
                                  type="button"
                                  onClick={() => {
                                    setActiveFloorMorePostId(null);
                                    navigator.clipboard.writeText(
                                      `https://linux.do/t/topic/${summary.id}/${post.postNumber}`
                                    );
                                    setCopiedFloorId(post.id);
                                    setTimeout(() => setCopiedFloorId(null), 1500);
                                  }}
                                  className="lindo-interactive flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-[12.8px]"
                                  style={{ color: "var(--ink-primary)" }}
                                >
                                  <LinDoIcons.Link
                                    size={15}
                                    style={{ color: "var(--ink-secondary)" }}
                                  />
                                  <span>复制链接</span>
                                </button>

                                {post.canAcceptAnswer && post.postNumber > 1 && (
                                  <button
                                    type="button"
                                    onClick={async () => {
                                      setActiveFloorMorePostId(null);
                                      try {
                                        await acceptPostSolution(post.id, !post.acceptedAnswer);
                                        setToastMsg(
                                          post.acceptedAnswer
                                            ? "已取消采纳"
                                            : "已采纳为最佳解答"
                                        );
                                        onRefresh();
                                      } catch (e: any) {
                                        setToastMsg(e?.message || "操作失败");
                                      }
                                    }}
                                    className="lindo-interactive flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-[12.8px]"
                                    style={{
                                      color: post.acceptedAnswer
                                        ? "#10B981"
                                        : "var(--ink-primary)",
                                    }}
                                  >
                                    {post.acceptedAnswer ? (
                                      <LinDoIcons.CheckCircleFilled
                                        size={15}
                                        style={{ color: "#10B981" }}
                                      />
                                    ) : (
                                      <LinDoIcons.CheckCircle
                                        size={15}
                                        style={{ color: "var(--ink-secondary)" }}
                                      />
                                    )}
                                    <span>
                                      {post.acceptedAnswer ? "取消采纳解答" : "采纳最佳解答"}
                                    </span>
                                  </button>
                                )}

                                {(post.canEdit || isOwnPost) && post.canEdit && (
                                  <button
                                    type="button"
                                    onClick={() => {
                                      setActiveFloorMorePostId(null);
                                      onOpenComposer({
                                        action: "EDIT",
                                        topicId: summary.id,
                                        topicTitle: detail?.title || summary.title,
                                        postId: post.id,
                                        postNumber: post.postNumber,
                                        initialCategoryId:
                                          detail?.categoryId ?? summary.categoryId,
                                        initialTitle:
                                          post.postNumber === 1
                                            ? detail?.title || summary.title
                                            : undefined,
                                        initialTags:
                                          post.postNumber === 1
                                            ? detail?.tags || summary.tags
                                            : undefined,
                                      });
                                    }}
                                    className="lindo-interactive flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-[12.8px]"
                                    style={{ color: "var(--ink-primary)" }}
                                  >
                                    <LinDoIcons.Edit
                                      size={15}
                                      style={{ color: "var(--ink-secondary)" }}
                                    />
                                    <span>{post.postNumber === 1 ? "编辑话题" : "编辑回复"}</span>
                                  </button>
                                )}

                                {!isOwnPost && !post.isDeletedTombstone && !post.isSmallAction && (
                                  <button
                                    type="button"
                                    onClick={() => {
                                      setActiveFloorMorePostId(null);
                                      if (!isAuthorized) {
                                        onRequestGuestLogin(
                                          "登录后举报违规发言",
                                          "登录后可向社区版主反馈垃圾广告或不当内容"
                                        );
                                        return;
                                      }
                                      setFlaggingPost(post);
                                    }}
                                    className="lindo-interactive flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-[12.8px]"
                                    style={{ color: "var(--ink-primary)" }}
                                  >
                                    <LinDoIcons.Flag
                                      size={15}
                                      style={{ color: "var(--ink-secondary)" }}
                                    />
                                    <span>举报此楼</span>
                                  </button>
                                )}

                                {canDeleteThisFloor && (
                                  <button
                                    type="button"
                                    onClick={() => {
                                      setActiveFloorMorePostId(null);
                                      setDeletingPost(post);
                                    }}
                                    className="lindo-interactive flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-[12.8px]"
                                    style={{ color: "#EF4444" }}
                                  >
                                    <LinDoIcons.Trash size={15} style={{ color: "#EF4444" }} />
                                    <span>删除回复</span>
                                  </button>
                                )}
                              </div>
                            )}
                          </div>

                          {/* 4. 回复本楼 (`↩` 纯图标，选中正文时自动带引用回复，1:1 对齐移动端) */}
                          <button
                            type="button"
                            title={
                              selectionQuote?.post.id === post.id
                                ? "引用所选内容并回复"
                                : `回复 #${post.postNumber} 楼`
                            }
                            onClick={() => {
                              if (selectionQuote?.post.id === post.id) {
                                quoteThisFloor();
                                return;
                              }
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
                                replyToPostNumber:
                                  post.postNumber > 1 ? post.postNumber : undefined,
                                replyToUsername: post.username,
                                replyToDisplayName: post.displayName,
                                replyToAvatarTemplate: post.avatarTemplate,
                                replyToAnimatedAvatar: post.animatedAvatar,
                                replyToSnippet: extractPostReplyPreview(
                                  post.id,
                                  post.cookedHtml
                                ).snippet.slice(0, 180),
                              });
                            }}
                            className={`lindo-interactive flex ${btnSqCls} items-center justify-center rounded-full ${mutedIdleCls}`}
                            style={{ color: "var(--ink-tertiary)" }}
                          >
                            <LinDoIcons.Reply size={15} />
                          </button>
                        </div>
                      );
                    })()}
                  </div>

                  {/* 内联展开的本楼下属回复线程（左侧竖向导引线 + AvatarWithFlair + 紧凑间距） */}
                  {inlineReplies && inlineReplies.length > 0 && (
                    <div
                      className="mt-2.5 ml-2 border-l-[1.5px] pl-3.5 space-y-2.5"
                      style={{
                        borderColor: "color-mix(in srgb, var(--ink-tertiary) 28%, var(--hairline))",
                      }}
                    >
                      {inlineReplies.map((rep, rIdx) => {
                        const repIsOp =
                          Boolean(detail.opUsername) &&
                          rep.username.toLowerCase() === detail.opUsername.toLowerCase();
                        return (
                          <div
                            key={rep.id}
                            className={rIdx > 0 ? "lindo-hairline-t pt-2.5" : ""}
                          >
                            <div className="flex items-start gap-2.5">
                              <button
                                type="button"
                                onClick={() => onInspectUser(rep.username)}
                                className="mt-0.5 shrink-0 cursor-pointer transition-opacity hover:opacity-85"
                              >
                                <AvatarWithFlair
                                  username={rep.username}
                                  avatarTemplate={rep.avatarTemplate}
                                  animatedAvatar={rep.animatedAvatar}
                                  primaryGroupName={rep.primaryGroupName}
                                  flair={rep.flair}
                                  sizePx={24}
                                />
                              </button>

                              <div className="min-w-0 flex-1">
                                <div className="flex items-center justify-between gap-2">
                                  <div className="flex min-w-0 items-center gap-1.5">
                                    <button
                                      type="button"
                                      onClick={() => onInspectUser(rep.username)}
                                      className="truncate text-[12.8px] font-semibold hover:underline cursor-pointer"
                                      style={{ color: "var(--ink-primary)" }}
                                    >
                                      {rep.displayName || rep.username}
                                    </button>
                                    {repIsOp && (
                                      <span
                                        className="shrink-0 rounded px-1.5 py-[0.5px] text-[10px] font-semibold"
                                        style={{
                                          backgroundColor: "var(--active-chip-bg)",
                                          color: "var(--brand-gold)",
                                        }}
                                      >
                                        楼主
                                      </span>
                                    )}
                                  </div>

                                  <button
                                    type="button"
                                    onClick={() => void jumpToFloor(rep.postNumber, post.postNumber)}
                                    title={`定位至 #${rep.postNumber} 楼`}
                                    className="lindo-interactive inline-flex shrink-0 items-center gap-0.5 rounded px-1.5 py-0.5 text-[11.5px] tabular-nums cursor-pointer"
                                    style={{ color: "var(--ink-tertiary)" }}
                                  >
                                    <span>
                                      {formatRelativeTime(rep.createdAt)} · #{rep.postNumber}
                                    </span>
                                    <LinDoIcons.ChevronRight size={11.5} className="opacity-70" />
                                  </button>
                                </div>

                                <MemoizedCookedBody
                                  rawHtml={rep.cookedHtml}
                                  stripPolls={false}
                                  currentTopicId={summary.id}
                                  onCookedClick={handleCookedClick}
                                  className="lindo-cooked lindo-cooked-reply mt-1 text-[13.5px]"
                                />

                                <div className="mt-1.5 flex items-center justify-end gap-2">
                                  <button
                                    type="button"
                                    title={rep.liked ? "取消赞" : "赞"}
                                    disabled={pendingReactionPostIds.has(rep.id)}
                                    onClick={async () => {
                                      if (!isAuthorized) {
                                        onRequestGuestLogin("登录后表达喜欢", "登录后即可为回复点赞");
                                        return;
                                      }
                                      if (pendingReactionPostsRef.current.has(rep.id)) return;
                                      pendingReactionPostsRef.current.add(rep.id);
                                      setPendingReactionPostIds((prev) => new Set(prev).add(rep.id));
                                      const prevLiked = rep.liked;
                                      const nextLiked = !prevLiked;
                                      const delta = nextLiked ? 1 : -1;
                                      setExpandedRepliesMap((prev) => ({
                                        ...prev,
                                        [post.id]: (prev[post.id] ?? []).map((item) =>
                                          item.id === rep.id
                                            ? {
                                                ...item,
                                                liked: nextLiked,
                                                likeCount: Math.max(0, item.likeCount + delta),
                                              }
                                            : item
                                        ),
                                      }));
                                      try {
                                        const updated = await togglePostReaction(rep.id, "heart");
                                        if (updated) {
                                          onAppendPosts([updated], "after");
                                          setExpandedRepliesMap((prev) => ({
                                            ...prev,
                                            [post.id]: (prev[post.id] ?? []).map((item) =>
                                              item.id === rep.id ? updated : item
                                            ),
                                          }));
                                        }
                                      } catch (err) {
                                        setExpandedRepliesMap((prev) => ({
                                          ...prev,
                                          [post.id]: (prev[post.id] ?? []).map((item) =>
                                            item.id === rep.id
                                              ? {
                                                  ...item,
                                                  liked: prevLiked,
                                                  likeCount: Math.max(0, item.likeCount - delta),
                                                }
                                              : item
                                          ),
                                        }));
                                        setToastMsg(
                                          err instanceof Error ? err.message : "操作失败，请稍后重试"
                                        );
                                      } finally {
                                        pendingReactionPostsRef.current.delete(rep.id);
                                        setPendingReactionPostIds((prev) => {
                                          const next = new Set(prev);
                                          next.delete(rep.id);
                                          return next;
                                        });
                                      }
                                    }}
                                    className="lindo-interactive inline-flex h-6 items-center gap-1 rounded-full px-1.5 text-[11.5px] tabular-nums cursor-pointer"
                                    style={{
                                      color: rep.liked ? "#F43F5E" : "var(--ink-tertiary)",
                                    }}
                                  >
                                    {rep.liked ? (
                                      <LinDoIcons.HeartFilled size={13} />
                                    ) : (
                                      <LinDoIcons.Heart size={13} />
                                    )}
                                    {rep.likeCount > 0 && <span>{rep.likeCount}</span>}
                                  </button>

                                  <button
                                    type="button"
                                    title={`回复 ${rep.displayName || rep.username} · #${rep.postNumber} 楼`}
                                    onClick={() => {
                                      if (!isAuthorized) {
                                        onRequestGuestLogin(
                                          "登录后参与回复",
                                          "登录后即可回复楼主或任意楼层"
                                        );
                                        return;
                                      }
                                      const repMeta = extractPostReplyPreview(rep.id, rep.cookedHtml);
                                      onOpenComposer({
                                        action: "REPLY",
                                        topicId: summary.id,
                                        topicTitle: detail?.title || summary.title,
                                        replyToPostNumber: rep.postNumber,
                                        replyToUsername: rep.username,
                                        replyToDisplayName: rep.displayName,
                                        replyToAvatarTemplate: rep.avatarTemplate,
                                        replyToAnimatedAvatar: rep.animatedAvatar,
                                        replyToSnippet: repMeta.snippet.slice(0, 180),
                                      });
                                    }}
                                    className="lindo-interactive inline-flex h-6 w-6 items-center justify-center rounded-full cursor-pointer"
                                    style={{ color: "var(--ink-tertiary)" }}
                                  >
                                    <LinDoIcons.Reply size={13.5} />
                                  </button>
                                </div>
                              </div>
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  )}

                  {/* 站内引用 / 反向关联话题列表（对齐 Web 端 post.link_counts，点击直接在客户端内打开话题） */}
                  {(() => {
                    const reflectionLinks = (post.linkCounts ?? []).filter(
                      (l) => l.internal && l.reflection && l.title && l.topicId && l.topicId !== detail?.id
                    );
                    if (reflectionLinks.length === 0) return null;
                    const isLinksExpanded = expandedLinkPostIds.has(post.id);
                    const visibleLinks = isLinksExpanded
                      ? reflectionLinks
                      : reflectionLinks.slice(0, 5);
                    const hiddenCount = reflectionLinks.length - visibleLinks.length;
                    return (
                      <div
                        className="mt-2.5 pt-2 space-y-1"
                        style={{ borderTop: "0.5px solid var(--hairline-soft)" }}
                      >
                        {visibleLinks.map((link) => (
                          <button
                            key={`${link.topicId}:${link.postNumber ?? 1}`}
                            type="button"
                            onClick={() =>
                              onNavigateTopicLink(
                                link.topicId!,
                                link.postNumber,
                                link.title,
                                post.postNumber
                              )
                            }
                            className="lindo-interactive group flex w-full items-center gap-1.5 rounded-md px-1.5 py-1 text-left text-[12.5px] transition-colors cursor-pointer"
                            style={{ color: "var(--ink-secondary)" }}
                          >
                            <LinDoIcons.Link
                              size={12.5}
                              className="shrink-0 opacity-60 group-hover:opacity-100"
                              style={{ color: "var(--ink-tertiary)" }}
                            />
                            <span className="min-w-0 truncate group-hover:text-[var(--active-chip-text)]">
                              {link.title}
                            </span>
                            {link.clicks > 0 && (
                              <span
                                className="shrink-0 rounded-full px-1.5 py-[0.5px] text-[10.8px] tabular-nums"
                                style={{
                                  backgroundColor: "var(--subtle-surface)",
                                  color: "var(--ink-tertiary)",
                                }}
                              >
                                {formatCompactCount(link.clicks)}
                              </span>
                            )}
                          </button>
                        ))}
                        {reflectionLinks.length > 5 && (
                          <button
                            type="button"
                            onClick={() =>
                              setExpandedLinkPostIds((prev) => {
                                const next = new Set(prev);
                                if (next.has(post.id)) next.delete(post.id);
                                else next.add(post.id);
                                return next;
                              })
                            }
                            className="px-1.5 py-0.5 text-[11.8px] font-medium hover:underline cursor-pointer"
                            style={{ color: "var(--ink-tertiary)" }}
                          >
                            {isLinksExpanded
                              ? "收起关联话题"
                              : `展开其余 ${hiddenCount} 个关联话题`}
                          </button>
                        )}
                      </div>
                    );
                  })()}
                </>
              );

              return (
                <React.Fragment key={post.id}>
                  {middleGapBanner}
                  <article
                    data-floor={post.postNumber}
                    className={`${isReplyFloor ? "py-2.5 transition-opacity duration-150" : "py-3.5"}${
                      Math.abs(post.postNumber - currentVisibleFloor) > 3
                        ? " lindo-offscreen-floor"
                        : ""
                    }${
                      flashTarget !== null &&
                      flashTarget.topicId === detail?.id &&
                      flashTarget.floor === post.postNumber
                        ? " lindo-floor-flash -mx-2 rounded-lg px-2"
                        : ""
                    }`}
                    style={
                      isReplyFloor && isFilterSwitching
                        ? { opacity: 0.45, pointerEvents: "none" }
                        : undefined
                    }
                  >
                    {isReplyFloor ? (
                      <div className="flex items-start gap-3">
                        {authorAvatarButton}
                        <div className="min-w-0 flex-1">
                          {authorHeaderMeta}
                          {floorContentBody}
                        </div>
                      </div>
                    ) : (
                      <>
                        <div className="flex items-start gap-3">
                          {authorAvatarButton}
                          <div className="min-w-0 flex-1">{authorHeaderMeta}</div>
                        </div>
                        {floorContentBody}
                      </>
                    )}
                  </article>

                  {isReplyFloor && !isLastRenderedPost && (
                    <div
                      className="ml-[46px] h-[0.5px]"
                      style={{ backgroundColor: "var(--hairline-soft)" }}
                    />
                  )}

                  {post.postNumber === 1 && (totalFloors > 1 || onlyOpFilter || summaryFilter) ? (
                    <div
                      data-replies-divider="1"
                      className="flex items-center justify-between py-2"
                      style={{
                        borderTop: "0.5px solid var(--hairline)",
                        borderBottom: "0.5px solid var(--hairline-soft)",
                      }}
                    >
                    <div className="flex items-center gap-2">
                      <span
                        className="text-[13px] font-semibold tabular-nums"
                        style={{ color: "var(--ink-secondary)" }}
                      >
                        {describeRepliesFilterBar(detail, {
                          onlyOp: onlyOpFilter,
                          summary: summaryFilter,
                        }).label}
                      </span>
                      {isFilterSwitching && (
                        <span
                          className="inline-flex items-center gap-1 text-[11.2px] font-medium"
                          style={{ color: "var(--ink-tertiary)" }}
                        >
                          <SmoothRefreshIcon spinning={true} size={11.5} />
                          <span>切换中…</span>
                        </span>
                      )}
                    </div>
                    <div
                      className="inline-flex select-none items-center rounded-lg p-[2px] text-[11.5px]"
                      style={{ backgroundColor: "var(--subtle-surface)" }}
                    >
                      <button
                        type="button"
                        onClick={() => {
                          if (onlyOpFilter) onToggleOnlyOp();
                          else if (summaryFilter && onToggleSummaryFilter) onToggleSummaryFilter();
                        }}
                        className="rounded-[6px] px-2.5 py-0.5 transition-colors cursor-pointer"
                        style={{
                          backgroundColor:
                            !onlyOpFilter && !summaryFilter ? "var(--canvas)" : "transparent",
                          color:
                            !onlyOpFilter && !summaryFilter
                              ? "var(--ink-primary)"
                              : "var(--ink-tertiary)",
                          fontWeight: !onlyOpFilter && !summaryFilter ? 600 : 400,
                        }}
                      >
                        <span className="lindo-tab-label" data-label="全部">
                          <span>全部</span>
                        </span>
                      </button>
                      {onToggleSummaryFilter && (detail.hasSummary || summaryFilter) && (
                        <button
                          type="button"
                          onClick={() => {
                            if (!summaryFilter) onToggleSummaryFilter();
                          }}
                          className="rounded-[6px] px-2.5 py-0.5 transition-colors cursor-pointer"
                          style={{
                            backgroundColor: summaryFilter ? "var(--canvas)" : "transparent",
                            color: summaryFilter ? "var(--active-chip-text)" : "var(--ink-tertiary)",
                            fontWeight: summaryFilter ? 600 : 400,
                          }}
                        >
                          <span className="lindo-tab-label" data-label="热门回复">
                            <span>热门回复</span>
                          </span>
                        </button>
                      )}
                      <button
                        type="button"
                        onClick={() => {
                          if (!onlyOpFilter) onToggleOnlyOp();
                        }}
                        className="rounded-[6px] px-2.5 py-0.5 transition-colors cursor-pointer"
                        style={{
                          backgroundColor: onlyOpFilter ? "var(--canvas)" : "transparent",
                          color: onlyOpFilter ? "var(--active-chip-text)" : "var(--ink-tertiary)",
                          fontWeight: onlyOpFilter ? 600 : 400,
                        }}
                      >
                        <span className="lindo-tab-label" data-label="只看楼主">
                          <span>只看楼主</span>
                        </span>
                      </button>
                    </div>
                  </div>
                  ) : post.postNumber === 1 ? (
                    <div
                      className="h-[0.5px] w-full"
                      style={{ backgroundColor: "var(--hairline)" }}
                    />
                  ) : null}
                </React.Fragment>
              );
            })}

            {!isLoading &&
              !isFilterSwitching &&
              !loadingMoreBottom &&
              !errorMessage &&
              detail &&
              detail.posts.length === 1 &&
              detail.posts[0].postNumber === 1 &&
              (totalFloors <= 1 || onlyOpFilter || summaryFilter) && (
                <div
                  className="flex items-center justify-center gap-2 py-12 text-[12.5px] select-none"
                  style={{ color: "var(--ink-tertiary)" }}
                >
                  {onlyOpFilter ? (
                    <>
                      <MascotExpression
                        expression="sleepy"
                        sizePx={20}
                        className="opacity-80"
                      />
                      <span>楼主暂无更多发言</span>
                      <span className="opacity-45">·</span>
                      <button
                        type="button"
                        onClick={onToggleOnlyOp}
                        className="font-medium opacity-90 transition-opacity hover:opacity-100 hover:underline cursor-pointer"
                        style={{ color: "var(--active-chip-text)" }}
                      >
                        查看全部
                      </button>
                    </>
                  ) : summaryFilter && onToggleSummaryFilter ? (
                    <>
                      <MascotExpression
                        expression="sleepy"
                        sizePx={20}
                        className="opacity-80"
                      />
                      <span>暂无精选热门回复</span>
                      <span className="opacity-45">·</span>
                      <button
                        type="button"
                        onClick={onToggleSummaryFilter}
                        className="font-medium opacity-90 transition-opacity hover:opacity-100 hover:underline cursor-pointer"
                        style={{ color: "var(--active-chip-text)" }}
                      >
                        查看全部
                      </button>
                    </>
                  ) : detail.closed || detail.archived ? (
                    <>
                      <MascotExpression
                        expression="sleepy"
                        sizePx={20}
                        className="opacity-75"
                      />
                      <span>暂无回复 · 话题已关闭</span>
                    </>
                  ) : (
                    <>
                      <MascotExpression
                        expression="happy"
                        sizePx={20}
                        className="opacity-80"
                      />
                      <span>暂无回复</span>
                      <span className="opacity-45">·</span>
                      <button
                        type="button"
                        onClick={() => {
                          if (!isAuthorized) {
                            onRequestGuestLogin("登录后回复话题", "登录后即可参与社区讨论");
                            return;
                          }
                          onOpenComposer({
                            action: "REPLY",
                            topicId: summary.id,
                            topicTitle: detail.title || summary.title,
                            replyToUsername: resolvedTopicOp.username || undefined,
                            replyToDisplayName: resolvedTopicOp.displayName || undefined,
                            replyToAvatarTemplate: resolvedTopicOp.avatarTemplate || undefined,
                            replyToAnimatedAvatar: resolvedTopicOp.animatedAvatar,
                          });
                        }}
                        className="font-medium opacity-90 transition-opacity hover:opacity-100 hover:underline cursor-pointer"
                        style={{ color: "var(--active-chip-text)" }}
                      >
                        写下第一条回复
                      </button>
                    </>
                  )}
                </div>
              )}

            {loadingMoreBottom && (
              <div
                className="flex items-center justify-center py-4 text-[12px]"
                style={{ color: "var(--ink-secondary)" }}
              >
                <span>正在加载后续楼层...</span>
              </div>
            )}

            {/* 底部零度娘「没有更多了」收尾提示 + 原地增量续拉评论刷新图标（绝不整页重载或跳位） */}
            {!isLoading &&
              !isFilterSwitching &&
              !loadingMoreBottom &&
              !errorMessage &&
              detail &&
              (detail.posts.length > 1 || (totalFloors > 1 && !onlyOpFilter && !summaryFilter)) &&
              (() => {
                const lastPost = detail.posts[detail.posts.length - 1];
                const lastIdx = lastPost ? detail.streamIds.indexOf(lastPost.id) : -1;
                const atEnd =
                  detail.streamIds.length === 0 ||
                  (lastIdx >= 0 && lastIdx >= detail.streamIds.length - 1);
                if (!atEnd) return null;
                return (
                  <div
                    className="flex items-center justify-center gap-2.5 py-7 text-[12.5px] select-none"
                    style={{ color: "var(--ink-tertiary)" }}
                  >
                    <span
                      className="h-[0.5px] w-7 shrink-0"
                      style={{ backgroundColor: "var(--hairline)" }}
                    />
                    <MascotExpression
                      expression={refreshingTail ? "happy" : "sleepy"}
                      sizePx={22}
                      className="shrink-0 opacity-85"
                    />
                    <span>
                      {refreshingTail
                        ? "正在检查新回复…"
                        : tailFeedback || "没有更多了"}
                    </span>
                    <button
                      type="button"
                      disabled={refreshingTail}
                      onClick={() => void handleTailIncrementalRefresh()}
                      title="检查并加载最新回复（不刷新整页）"
                      aria-label="检查并加载最新回复"
                      className="lindo-interactive inline-flex h-6 w-6 items-center justify-center rounded-full cursor-pointer disabled:opacity-50"
                      style={{
                        backgroundColor: "var(--subtle-surface)",
                        color: refreshingTail ? "var(--brand-gold)" : "var(--ink-secondary)",
                      }}
                    >
                      <LinDoIcons.Refresh
                        size={12.5}
                        className={refreshingTail ? "animate-spin" : ""}
                      />
                    </button>
                    <span
                      className="h-[0.5px] w-7 shrink-0"
                      style={{ backgroundColor: "var(--hairline)" }}
                    />
                  </div>
                );
              })()}

            {/* 底部相关推荐话题（所有回复加载完成后展示，点击直接在客户端内阅读） */}
            {!isLoading &&
              !loadingMoreBottom &&
              !onlyOpFilter &&
              detail &&
              detail.archetype !== "private_message" &&
              detail.suggestedTopics &&
              detail.suggestedTopics.length > 0 &&
              (() => {
                const lastPost = detail.posts[detail.posts.length - 1];
                const lastIdx = lastPost ? detail.streamIds.indexOf(lastPost.id) : -1;
                const atEnd =
                  detail.streamIds.length === 0 ||
                  (lastIdx >= 0 && lastIdx >= detail.streamIds.length - 1);
                if (!atEnd) return null;
                return (
                  <div
                    className="mt-6 pt-4 pb-2"
                    style={{ borderTop: "0.5px solid var(--hairline)" }}
                  >
                    <div
                      className="mb-2.5 text-[12.2px] font-semibold"
                      style={{ color: "var(--ink-tertiary)" }}
                    >
                      相关话题
                    </div>
                    <div className="space-y-1">
                      {detail.suggestedTopics.map((st) => {
                        const sCat = resolveCategoryVisual(
                          st.categoryName || "",
                          st.categoryColorHex
                        );
                        return (
                          <button
                            key={st.id}
                            type="button"
                            onClick={() =>
                              onNavigateTopicLink(st.id, 1, st.title, visibleFloorRef.current)
                            }
                            className="lindo-interactive flex w-full items-center justify-between gap-2.5 rounded-lg px-2.5 py-2 text-left cursor-pointer"
                          >
                            <div className="shrink-0">
                              <AvatarWithFlair
                                username={st.originalPoster.username}
                                avatarTemplate={st.originalPoster.avatarTemplate}
                                animatedAvatar={st.originalPoster.animatedAvatar}
                                primaryGroupName={st.originalPoster.primaryGroupName}
                                flair={st.originalPoster.flair}
                                sizePx={28}
                              />
                            </div>
                            <div className="min-w-0 flex-1">
                              <div
                                className="truncate text-[13.2px] font-medium"
                                style={{ color: "var(--ink-primary)" }}
                              >
                                {st.title}
                              </div>
                              <div className="mt-0.5 flex items-center gap-2 text-[11.2px]">
                                {st.categoryName && (
                                  <LinDoCategoryBadge
                                    name={st.categoryName}
                                    fallbackColor={sCat.dotColor}
                                    iconSize={11}
                                    className="text-[11px]"
                                  />
                                )}
                                <span
                                  className="tabular-nums"
                                  style={{ color: "var(--ink-tertiary)" }}
                                >
                                  {st.replyCount} 回复 · {formatCompactCount(st.views)} 浏览
                                </span>
                              </div>
                            </div>
                            <LinDoIcons.ChevronRight
                              size={13}
                              className="shrink-0 opacity-50"
                              style={{ color: "var(--ink-tertiary)" }}
                            />
                          </button>
                        );
                      })}
                    </div>
                  </div>
                );
              })()}
          </div>
        )}
        </div>
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
                replyToDisplayName: captured.post.displayName,
                replyToAvatarTemplate: captured.post.avatarTemplate,
                replyToAnimatedAvatar: captured.post.animatedAvatar,
                replyToSnippet: captured.text.slice(0, 180),
                initialRaw: `[quote="${captured.post.username}, post:${captured.post.postNumber}, topic:${summary.id}"]\n${captured.text}\n[/quote]\n\n`,
              });
            }}
            className="flex items-center gap-1.5 text-[12px] font-medium text-white cursor-pointer"
          >
            <LinDoIcons.Quote size={13.5} style={{ color: "#FCD34D" }} />
            <span>引用并回复</span>
          </button>
        </div>
      )}

      <div className="pointer-events-none absolute inset-x-4 bottom-3 z-20 mx-auto flex max-w-[900px] items-center justify-end gap-2">
        {returnFloor !== null && returnFloor !== currentVisibleFloor && (
          <button
            type="button"
            onClick={() => void jumpToFloor(returnFloor, undefined, true)}
            className="lindo-reader-glass gap-1 px-3 text-[12px] font-medium tabular-nums"
          >
            <LinDoIcons.Reply size={12.5} />
            <span>返回 #{returnFloor} 楼</span>
          </button>
        )}
        <button
          type="button"
          onClick={() => {
            const cur = visibleFloorRef.current;
            setCurrentVisibleFloor(cur);
            setFloorInputVal(String(cur));
            setFloorNavOpen((v) => !v);
          }}
          ref={floorNavButtonRef}
          data-floor-navigation=""
          title="楼层导航"
          aria-expanded={floorNavOpen}
          className="lindo-reader-glass gap-1 px-3 text-[12px] font-medium tabular-nums"
        >
          <span className="sr-only">楼层导航，</span>
          <span ref={floorBadgeTextRef} />
          <LinDoIcons.ChevronUp size={12} className={floorNavOpen ? "rotate-180" : undefined} />
        </button>

        {floorNavOpen && (
          <div
            data-floor-navigation=""
            className="pointer-events-auto absolute bottom-[calc(100%+8px)] right-0 z-30 w-[256px] max-w-full rounded-2xl p-3.5 shadow-2xl"
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
              aria-label="选择楼层"
              min={1}
              max={totalFloors}
              value={Number(floorInputVal) || 1}
              onChange={(e) => setFloorInputVal(e.target.value)}
              onPointerUp={(e) => selectFloor(e.currentTarget.valueAsNumber)}
              onKeyDown={(e) => {
                if (e.key === "Enter") selectFloor(e.currentTarget.valueAsNumber);
              }}
              className="mt-2.5 w-full accent-amber-400 cursor-pointer"
            />
            <div className="mt-2.5 flex items-center gap-2">
              <button
                type="button"
                onClick={() => selectFloor(1)}
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
                aria-label="楼层号"
                min={1}
                max={totalFloors}
                value={floorInputVal}
                onChange={(e) => setFloorInputVal(e.target.value)}
                onKeyDown={(e) => {
                  if (isImeComposingEvent(e)) return;
                  if (e.key === "Enter") selectFloor(e.currentTarget.valueAsNumber);
                }}
                className="w-16 rounded-lg bg-transparent px-2 py-1 text-center text-[12px] tabular-nums outline-none"
                style={{
                  border: "0.5px solid var(--hairline)",
                  color: "var(--ink-primary)",
                }}
              />
              <button
                type="button"
                onClick={() => selectFloor(totalFloors)}
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
        {!hideQuickReplyBar && (
          <button
            type="button"
            disabled={isReplyLocked}
            onClick={() => {
              if (!isAuthorized) {
                onRequestGuestLogin("登录后回复话题", "登录后即可参与社区讨论");
                return;
              }
              onOpenComposer({
                action: "REPLY",
                topicId: summary.id,
                topicTitle: detail?.title || summary.title,
                replyToUsername: resolvedTopicOp.username || undefined,
                replyToDisplayName: resolvedTopicOp.displayName || undefined,
                replyToAvatarTemplate: resolvedTopicOp.avatarTemplate || undefined,
                replyToAnimatedAvatar: resolvedTopicOp.animatedAvatar,
              });
            }}
            title={replyLabel}
            aria-label={replyLabel}
            className="lindo-reader-glass aspect-square"
          >
            {isReplyLocked ? <LinDoIcons.Lock size={16} /> : <LinDoIcons.Edit size={18} />}
          </button>
        )}
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

      {/* 脚注内联浮层（点击正文中 [1] 脚注引用时直接展示，绝不跳 WebView） */}
      {footnotePopover && (
        <div
          className="fixed inset-0 z-40 flex items-end justify-center bg-black/30 p-6 sm:items-center"
          onMouseDown={() => setFootnotePopover(null)}
        >
          <div
            className="w-[min(480px,92vw)] rounded-2xl p-4 shadow-2xl"
            style={{
              backgroundColor: "var(--card-surface)",
              border: "0.5px solid var(--hairline)",
            }}
            onMouseDown={(e) => e.stopPropagation()}
          >
            <div className="mb-2 flex items-center justify-between">
              <span className="text-[12px] font-semibold" style={{ color: "var(--ink-secondary)" }}>
                脚注说明
              </span>
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onClick={() => {
                    const id = footnotePopover.targetId;
                    setFootnotePopover(null);
                    const esc =
                      typeof CSS !== "undefined" && typeof CSS.escape === "function"
                        ? CSS.escape(id)
                        : id;
                    scrollContainerRef.current
                      ?.querySelector<HTMLElement>(`#${esc}`)
                      ?.scrollIntoView({ behavior: "smooth", block: "center" });
                  }}
                  className="text-[11.5px] font-medium hover:underline cursor-pointer"
                  style={{ color: "var(--active-chip-text)" }}
                >
                  定位至文末
                </button>
                <button
                  type="button"
                  onClick={() => setFootnotePopover(null)}
                  className="lindo-interactive flex h-6 w-6 items-center justify-center rounded-full"
                  style={{ color: "var(--ink-tertiary)" }}
                >
                  <LinDoIcons.Close size={13} />
                </button>
              </div>
            </div>
            <div
              className="lindo-cooked text-[13.5px]"
              onClick={handleCookedClick}
              dangerouslySetInnerHTML={{ __html: footnotePopover.html }}
            />
          </div>
        </div>
      )}

      {/* 帖子编辑历史弹窗 */}
      {revisionModal && (
        <PostRevisionsModal
          postId={revisionModal.postId}
          postNumber={revisionModal.postNumber}
          onInspectUser={onInspectUser}
          onClose={() => setRevisionModal(null)}
        />
      )}

      {/* 点赞与表态成员弹窗 */}
      {reactionUsersModal && (
        <ReactionUsersModal
          postId={reactionUsersModal.postId}
          initialReactionId={reactionUsersModal.initialReactionId}
          onInspectUser={onInspectUser}
          onClose={() => setReactionUsersModal(null)}
        />
      )}

      {/* 书签备注与定时提醒弹窗 */}
      {bookmarkModalPost && (
        <BookmarkReminderModal
          postId={bookmarkModalPost.id}
          postNumber={bookmarkModalPost.postNumber}
          existingBookmarkId={bookmarkModalPost.bookmarkId}
          initialName={bookmarkModalPost.bookmarkName}
          initialReminderAt={bookmarkModalPost.bookmarkReminderAt}
          onSaved={({ id, name, reminderAt }) => {
            onAppendPosts(
              [
                {
                  ...bookmarkModalPost,
                  bookmarked: Boolean(id),
                  bookmarkId: id,
                  bookmarkName: name,
                  bookmarkReminderAt: reminderAt,
                },
              ],
              "after"
            );
            setToastMsg(id ? (reminderAt ? "已保存书签并设置提醒" : "已保存书签") : "已移除书签");
          }}
          onClose={() => setBookmarkModalPost(null)}
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
  onInspectUser: (username: string) => void;
  onPollUpdated: (next: PostPollModel) => void;
  onError: (msg: string) => void;
}> = ({ postId, poll, isAuthorized, onRequestLogin, onInspectUser, onPollUpdated, onError }) => {
  const isMulti = poll.type === "multiple";
  const hasVoted = (poll.myVotes?.length ?? 0) > 0;
  const isClosed = poll.status === "closed";
  const [selectedIds, setSelectedIds] = useState<string[]>(poll.myVotes ?? []);
  const [showResults, setShowResults] = useState(
    hasVoted || isClosed || poll.results === "always"
  );
  const [busy, setBusy] = useState(false);
  const [votersByOpt, setVotersByOpt] = useState<
    Record<string, Array<{ username: string; name?: string; avatarTemplate: string }>>
  >({});
  const [loadingVotersOpt, setLoadingVotersOpt] = useState<string | null>(null);
  const [expandedVotersOpt, setExpandedVotersOpt] = useState<string | null>(null);

  const toggleOptionVoters = async (optId: string) => {
    if (expandedVotersOpt === optId) {
      setExpandedVotersOpt(null);
      return;
    }
    setExpandedVotersOpt(optId);
    if (votersByOpt[optId]) return;
    setLoadingVotersOpt(optId);
    try {
      const list = await fetchPollVoters(postId, poll.name, optId, 1);
      setVotersByOpt((prev) => ({ ...prev, [optId]: list }));
    } catch {
      setVotersByOpt((prev) => ({ ...prev, [optId]: [] }));
    } finally {
      setLoadingVotersOpt(null);
    }
  };

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
          <LinDoIcons.BarChartPoll size={15} style={{ color: "var(--brand-gold)" }} />
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
          const isVotersExpanded = expandedVotersOpt === opt.id;
          const optVoters = votersByOpt[opt.id] || [];
          return (
            <div key={opt.id} className="space-y-1">
              <button
                type="button"
                disabled={isClosed || busy}
                onClick={() => toggleOption(opt.id)}
                className="relative flex w-full items-center justify-between overflow-hidden rounded-lg px-3 py-2 text-left text-[13px] transition-colors cursor-pointer disabled:cursor-default"
                style={{
                  backgroundColor: "var(--card-surface)",
                  border: "0.5px solid var(--hairline)",
                }}
              >
                {showResults && (
                  <div
                    className="pointer-events-none absolute inset-y-0 left-0 transition-[width] duration-300"
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
                    <LinDoIcons.Check size={13.5} style={{ color: "var(--brand-gold)" }} />
                  )}
                  <span dangerouslySetInnerHTML={{ __html: prepareCookedHtmlCached(opt.html) }} />
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
              {showResults && poll.isPublic && opt.votes > 0 && (
                <div className="px-1">
                  <button
                    type="button"
                    onClick={() => void toggleOptionVoters(opt.id)}
                    className="text-[11px] tabular-nums hover:underline cursor-pointer"
                    style={{ color: "var(--ink-tertiary)" }}
                  >
                    {isVotersExpanded ? "收起投票名单" : `查看 ${opt.votes} 位投票成员`}
                  </button>
                  {isVotersExpanded && (
                    <div className="mt-1 flex flex-wrap items-center gap-1.5 pb-1">
                      {loadingVotersOpt === opt.id ? (
                        <span className="text-[11px]" style={{ color: "var(--ink-tertiary)" }}>
                          加载中...
                        </span>
                      ) : optVoters.length === 0 ? (
                        <span className="text-[11px]" style={{ color: "var(--ink-tertiary)" }}>
                          暂无公开投票名单
                        </span>
                      ) : (
                        optVoters.map((v) => (
                          <button
                            key={v.username}
                            type="button"
                            onClick={() => onInspectUser(v.username)}
                            className="inline-flex items-center gap-1 rounded-full py-0.5 pl-1 pr-2 text-[11px] hover:underline cursor-pointer"
                            style={{
                              backgroundColor: "var(--card-surface)",
                              border: "0.5px solid var(--hairline)",
                              color: "var(--ink-secondary)",
                            }}
                          >
                            <AvatarWithFlair
                              username={v.username}
                              avatarTemplate={v.avatarTemplate}
                              sizePx={15}
                            />
                            <span>{v.username}</span>
                          </button>
                        ))
                      )}
                    </div>
                  )}
                </div>
              )}
            </div>
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
            <LinDoIcons.Close size={15} />
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
                  border: "0.5px solid var(--hairline)",
                }}
              >
                <span
                  className="mt-1 box-border flex h-3.5 w-3.5 shrink-0 items-center justify-center rounded-full transition-colors"
                  style={{
                    border: active
                      ? "1.5px solid var(--brand-gold)"
                      : "1.5px solid var(--ink-tertiary)",
                  }}
                >
                  {active && (
                    <span
                      className="h-1.5 w-1.5 rounded-full"
                      style={{ backgroundColor: "var(--brand-gold)" }}
                    />
                  )}
                </span>
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
