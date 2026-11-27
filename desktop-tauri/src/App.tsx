import React, { useCallback, useEffect, useLayoutEffect, useMemo, useReducer, useRef, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { emit, listen } from "@tauri-apps/api/event";
import { openUrl } from "@tauri-apps/plugin-opener";
import { useUpdatePrompt } from "./lib/useUpdatePrompt";
import { initialVerificationStatus, verificationStatus, type VerifyPillEvent } from "./lib/verificationStatus";
import { LinDoIcons } from "./components/LinDoIcons";
import { ensureGlobalImeTracking, isImeComposingEvent } from "./lib/imeGuard";
import type {
  CategoryMeta,
  ComposerLaunchRequest,
  EchDesktopSnapshot,
  FeedSort,
  GrantedBadge,
  LocalDraftRecord,
  MainTab,
  PostItemModel,
  SavedAccountEntry,
  TopPeriod,
  TopicDetailModel,
  TopicSummary,
  UpdateProgressPayload,
  UserPreferences,
  UserProfile,
} from "./lib/types";
import {
  addAccountWebLoginInRust,
  bootstrapApp,
  clearExpiredNoticeInRust,
  downloadAndInstallUpdateInRust,
  drainPendingMarkReads,
  fetchSiteCategories,
  fetchTopicDetail,
  fetchTopicFeed,
  flushTopicTimingsNow,
  getCachedCategories,
  getCachedTopicDetail,
  getEchStatus,
  invalidateNotificationShortCache,
  logoutAccount,
  markManualRetryIntent,
  openInAppForumPanel,
  pauseAndDropPendingTimings,
  putCachedTopicDetail,
  recordConfirmedTopicFloorForNotifications,
  recordLocalTopicVisit,
  recordReadFloorInRust,
  refreshCurrentUserSession,
  removeSavedAccountInRust,
  resumeTimingsAfterVerify,
  savePreferencesToRust,
  setApiDarkTheme,
  setUnreadBadgeInRust,
  siteVerify,
  SiteError,
  type InteractiveVerifyOptions,
  startBrowserOneClickAuth,
  switchSavedAccount,
  triggerInteractiveVerifyOnce,
} from "./lib/api";
import { applyPaletteToDocument, performSmoothThemeSwitch } from "./lib/palette";
import {
  DEFAULT_GUEST_LOGIN_PROMPT,
  MESSAGES_GUEST_LOGIN_PROMPT,
  PROFILE_GUEST_LOGIN_PROMPT,
  buildExpiredSessionGuestPrompt,
  resolveAccountManagementTarget,
  resolveRestrictedCategorySelection,
} from "./lib/guestLoginFlow";
import { parseInternalTopicLink } from "./lib/cooked";
import { applyTopicFilterResult } from "./lib/topicTransition";
import {
  type PendingFeedSnapshot,
  countEffectivePendingTopics,
  createEmptyPendingFeedSnapshot,
  evaluatePendingFeedPoll,
  resolvePendingFeedApply,
} from "./lib/pendingFeedState";
import { DESKTOP_APP_VERSION, diagLog, installGlobalDiagHooks } from "./lib/diagnostics";
import { measureDisplayRefresh, observeScrollActivity } from "./lib/scrollActivity";
import { discourseMessageBus } from "./lib/messageBus";
import { SideRail } from "./components/SideRail";
import { HomeFeedPane } from "./components/HomeFeedPane";
import { TopicReaderPane } from "./components/TopicReaderPane";
import { ComposerModal } from "./components/ComposerModal";
import {
  AuthModal,
  BadgeDetailModal,
  ConfirmDialog,
  ConnectTrustLevelModal,
  DesktopUpdateModal,
  DiscoverView,
  GlobalSearchModal,
  GuestLoginGuideModal,
  ImageLightboxModal,
  MessagesView,
  ProfileSettingsView,
  PublicUserCardModal,
  QrLoginModal,
  SiteBadgesModal,
  WelcomeLoginScreen,
} from "./components/SecondaryScreens";

const DEFAULT_PREFS: UserPreferences = {
  appearance: "dark",
  themeOption: "WARM_ORANGE",
  fontScale: 1.0,
  splitFraction: 0.42,
  compactFeed: true,
  defaultSearchExpanded: true,
  enableAnimations: true,
  echRoutingMode: "smart_auto",
};

export function mergePostPreservingBadges(
  existing: PostItemModel | undefined,
  next: PostItemModel
): PostItemModel {
  if (!existing) return next;
  const preserveBoosts = next.hasBoostsField === false;
  const preserveCanBoost = next.hasCanBoostField === false;
  return {
    ...next,
    boosts: preserveBoosts ? existing.boosts : next.boosts,
    canBoost: preserveCanBoost ? existing.canBoost : next.canBoost,
    hasBoostsField: preserveBoosts ? existing.hasBoostsField : next.hasBoostsField,
    hasCanBoostField: preserveCanBoost ? existing.hasCanBoostField : next.hasCanBoostField,
    authorBadges:
      next.authorBadges && next.authorBadges.length > 0
        ? next.authorBadges
        : existing.authorBadges,
    badgesGranted:
      next.badgesGranted && next.badgesGranted.length > 0
        ? next.badgesGranted
        : existing.badgesGranted,
  };
}

function mergeTopicDetailInPlace(
  prev: TopicDetailModel | null,
  fresh: TopicDetailModel,
  onlyOp: boolean
): TopicDetailModel {
  if (!prev || prev.id !== fresh.id || onlyOp) return fresh;
  const validStreamSet = fresh.streamIds.length > 0 ? new Set(fresh.streamIds) : null;
  const map = new Map<number, PostItemModel>();
  for (const existing of prev.posts) {
    if (!validStreamSet || validStreamSet.has(existing.id)) {
      map.set(existing.id, existing);
    }
  }
  for (const incoming of fresh.posts) {
    map.set(incoming.id, mergePostPreservingBadges(map.get(incoming.id), incoming));
  }
  const merged: TopicDetailModel = {
    ...fresh,
    posts: Array.from(map.values()).sort((a, b) => a.postNumber - b.postNumber),
  };
  if (merged.posts[0]?.postNumber === 1) {
    putCachedTopicDetail(merged);
  }
  return merged;
}

export const App: React.FC = () => {
  const [bootstrapped, setBootstrapped] = useState(false);
  const [platform, setPlatform] = useState<"macos" | "windows" | "linux">("macos");
  const [isAuthorized, setIsAuthorized] = useState(false);
  const [isGuestMode, setIsGuestMode] = useState(false);
  const [currentUser, setCurrentUser] = useState<UserProfile | null>(null);
  const [expiredUsername, setExpiredUsername] = useState<string | null>(null);
  const [savedAccounts, setSavedAccounts] = useState<SavedAccountEntry[]>([]);
  const [preferences, setPreferences] = useState<UserPreferences>(DEFAULT_PREFS);
  const [readFloors, setReadFloors] = useState<Record<string, number>>({});
  const [drafts, setDrafts] = useState<Record<string, LocalDraftRecord>>({});
  const [unreadCount, setUnreadCount] = useState(0);
  const [echStatus, setEchStatus] = useState<EchDesktopSnapshot | null>(null);
  const [systemPrefersDark, setSystemPrefersDark] = useState(
    () => window.matchMedia?.("(prefers-color-scheme: dark)").matches ?? true
  );

  // Layout & navigation
  const [currentTab, setCurrentTab] = useState<MainTab>("HOME");
  const [windowWidth, setWindowWidth] = useState(() => window.innerWidth);
  const [narrowShowingReader, setNarrowShowingReader] = useState(false);

  // Feed state — initialized immediately with canonical Chinese categories so "Develop" NEVER flashes!
  const [categories, setCategories] = useState<CategoryMeta[]>(() => getCachedCategories());
  const [currentSort, setCurrentSort] = useState<FeedSort>("latest");
  const [topPeriod, setTopPeriod] = useState<TopPeriod>("daily");
  const [selectedCategoryId, setSelectedCategoryId] = useState<number | null>(null);
  const [selectedTag, setSelectedTag] = useState<string | null>(null);
  const [topics, setTopics] = useState<TopicSummary[]>([]);
  const [feedPage, setFeedPage] = useState(0);
  const [hasMoreTopics, setHasMoreTopics] = useState(true);
  const [feedLoading, setFeedLoading] = useState(false);
  const [loadingMoreTopics, setLoadingMoreTopics] = useState(false);
  const verificationBlocked = useRef({ feed: false, more: false, reader: false, message: false });
  const [feedError, setFeedError] = useState<string | null>(null);
  const [loadMoreError, setLoadMoreError] = useState<string | null>(null);
  const [pendingFeed, setPendingFeed] = useState<PendingFeedSnapshot<TopicSummary>>(() =>
    createEmptyPendingFeedSnapshot()
  );
  const pendingFeedRef = useRef(pendingFeed);
  pendingFeedRef.current = pendingFeed;
  const feedGenerationRef = useRef(0);
  const pendingNewCount = useMemo(
    () => countEffectivePendingTopics(pendingFeed, topics),
    [pendingFeed, topics]
  );

  // Home topic reader state
  const [activeSummary, setActiveSummary] = useState<TopicSummary | null>(null);
  const [activeDetail, setActiveDetail] = useState<TopicDetailModel | null>(null);
  const activeDetailRef = useRef<TopicDetailModel | null>(null);
  activeDetailRef.current = activeDetail;
  const [readerLoading, setReaderLoading] = useState(false);
  const [readerFilterLoading, setReaderFilterLoading] = useState(false);
  const [readerError, setReaderError] = useState<string | null>(null);
  const [targetFloor, setTargetFloor] = useState<number | null>(null);
  const [targetFloorHighlight, setTargetFloorHighlight] = useState(false);
  const [targetJumpNonce, setTargetJumpNonce] = useState(0);
  const [filterSettledNonce, setFilterSettledNonce] = useState(0);
  const [onlyOpFilter, setOnlyOpFilter] = useState(false);
  const [summaryFilter, setSummaryFilter] = useState(false);
  const [homeReaderHistory, setHomeReaderHistory] = useState<
    Array<{ summary: TopicSummary; floor: number }>
  >([]);

  // Messages split-pane reader state (independent from Home reader!)
  const [msgSummary, setMsgSummary] = useState<TopicSummary | null>(null);
  const [msgDetail, setMsgDetail] = useState<TopicDetailModel | null>(null);
  const msgDetailRef = useRef<TopicDetailModel | null>(null);
  msgDetailRef.current = msgDetail;
  const [msgReaderLoading, setMsgReaderLoading] = useState(false);
  const [msgFilterLoading, setMsgFilterLoading] = useState(false);
  const [msgReaderError, setMsgReaderError] = useState<string | null>(null);
  const [msgTargetFloor, setMsgTargetFloor] = useState<number | null>(null);
  const [msgTargetFloorHighlight, setMsgTargetFloorHighlight] = useState(false);
  const [msgJumpNonce, setMsgJumpNonce] = useState(0);
  const [msgFilterSettledNonce, setMsgFilterSettledNonce] = useState(0);
  const [msgOnlyOp, setMsgOnlyOp] = useState(false);
  const [msgSummaryFilter, setMsgSummaryFilter] = useState(false);
  const [msgReaderHistory, setMsgReaderHistory] = useState<
    Array<{ summary: TopicSummary; floor: number }>
  >([]);
  const [pendingPmRecipient, setPendingPmRecipient] = useState<string | null>(null);

  // Overlays & modals
  const [showAuthModal, setShowAuthModal] = useState(false);
  const [authModalStartAdding, setAuthModalStartAdding] = useState(false);
  const [showQrLoginModal, setShowQrLoginModal] = useState(false);
  const [qrLoginInitialMode, setQrLoginInitialMode] = useState<"generate" | "redeem" | undefined>(
    undefined
  );
  const [authStatusHint, setAuthStatusHint] = useState<string | null>(null);
  const [authCompleting, setAuthCompleting] = useState(false);
  const browserAuthWatchdogRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [switchingBusy, setSwitchingBusy] = useState(false);
  const [showLogoutConfirm, setShowLogoutConfirm] = useState(false);
  const [guestPrompt, setGuestPrompt] = useState<{ title: string; subtitle: string } | null>(null);
  const [showGlobalSearch, setShowGlobalSearch] = useState(false);
  const [composerReq, setComposerReq] = useState<ComposerLaunchRequest | null>(null);
  const [composerAppendQuote, setComposerAppendQuote] = useState<{
    text: string;
    nonce: number;
  } | null>(null);
  const composerReqRef = useRef<ComposerLaunchRequest | null>(null);
  composerReqRef.current = composerReq;
  const composerExitGuardRef = useRef<((proceed: () => void) => void) | null>(null);
  const topicReqSeqRef = useRef(0);
  const msgReqSeqRef = useRef(0);
  const lastTopicClickMsRef = useRef(0);
  const activeSummaryRef = useRef<TopicSummary | null>(null);
  activeSummaryRef.current = activeSummary;
  const targetFloorRef = useRef<number | null>(null);
  targetFloorRef.current = targetFloor;
  const msgSummaryRef = useRef<TopicSummary | null>(null);
  msgSummaryRef.current = msgSummary;
  const msgTargetFloorRef = useRef<number | null>(null);
  msgTargetFloorRef.current = msgTargetFloor;
  const [inspectedUsername, setInspectedUsername] = useState<string | null>(null);
  const [inspectedBadge, setInspectedBadge] = useState<GrantedBadge | null>(null);
  const [showConnectModal, setShowConnectModal] = useState(false);
  const [showSiteBadgesModal, setShowSiteBadgesModal] = useState(false);
  const [pmRefreshNonce, setPmRefreshNonce] = useState(0);
  const [lightboxState, setLightboxState] = useState<{
    src: string;
    images?: string[];
    index?: number;
  } | null>(null);
  const [globalNotice, setGlobalNotice] = useState<string | null>(null);
  const [feedCollapsed, setFeedCollapsed] = useState(false);
  const [isDraggingSplit, setIsDraggingSplit] = useState(false);
  const [verification, dispatchVerification] = useReducer(verificationStatus, initialVerificationStatus);
  const cfAttentionNeeded = verification.attentionNeeded;
  const connectionRecovering = verification.stage === "recovering";
  const connectionPending = verification.stage === "unavailable";
  const verifyActive = verification.pillVisible || verification.manualBusy;
  const pauseTimings = cfAttentionNeeded || verification.pillVisible || verification.stage === "panel" || connectionRecovering || connectionPending;
  useEffect(() => {
    if (pauseTimings) pauseAndDropPendingTimings();
    else resumeTimingsAfterVerify();
  }, [pauseTimings]);
  const [cfAttentionDismissed, setCfAttentionDismissed] = useState(false);
  const [lastVerifiedAtMs, setLastVerifiedAtMs] = useState<number | null>(null);
  const lastFeedSyncedAtRef = useRef<number>(Date.now());
  const backgroundPollInFlightRef = useRef(false);

  const update = useUpdatePrompt(
    bootstrapped,
    !composerReq && !showAuthModal && !showQrLoginModal && !guestPrompt && !authCompleting && !verifyActive,
    setGlobalNotice
  );
  const updateInfo = update.info;
  const [installingUpdate, setInstallingUpdate] = useState(false);
  const [updateProgress, setUpdateProgress] = useState<UpdateProgressPayload | null>(null);
  const [updateError, setUpdateError] = useState<string | null>(null);
  const closeUpdate = useCallback(() => {
    if (installingUpdate) return;
    setUpdateError(null);
    update.close();
  }, [installingUpdate, update.close]);

  const isDark = useMemo(() => {
    if (preferences.appearance === "light") return false;
    if (preferences.appearance === "dark") return true;
    return systemPrefersDark;
  }, [preferences.appearance, systemPrefersDark]);

  const isWideLayout = windowWidth >= 860;
  const inMainApp = isAuthorized || isGuestMode;

  useLayoutEffect(() => {
    setApiDarkTheme(isDark);
    applyPaletteToDocument(isDark, preferences.themeOption, preferences.fontScale);
  }, [isDark, preferences.themeOption, preferences.fontScale]);

  useLayoutEffect(() => {
    document.documentElement.classList.toggle("lindo-reduce-motion", !preferences.enableAnimations);
  }, [preferences.enableAnimations]);

  useEffect(() => {
    const mq = window.matchMedia?.("(prefers-color-scheme: dark)");
    if (!mq) return;
    const onChange = (e: MediaQueryListEvent) => setSystemPrefersDark(e.matches);
    mq.addEventListener("change", onChange);
    return () => mq.removeEventListener("change", onChange);
  }, []);

  useEffect(() => {
    const onResize = () => setWindowWidth(window.innerWidth);
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, []);

  useEffect(() => {
    if (!globalNotice) return;
    const t = setTimeout(() => setGlobalNotice(null), 3200);
    return () => clearTimeout(t);
  }, [globalNotice]);

  const accountOwner = useRef<string | null | undefined>(undefined);
  const syncFromRustState = useCallback(async () => {
    const boot = await bootstrapApp();
    const username = boot.state.currentUser?.username ?? null;
    if (accountOwner.current !== undefined && accountOwner.current !== username) {
      ++feedGenerationRef.current;
      ++topicReqSeqRef.current;
      ++msgReqSeqRef.current;
      setTopics([]);
      setActiveSummary(null);
      setActiveDetail(null);
      setMsgSummary(null);
      setMsgDetail(null);
      setPendingFeed(createEmptyPendingFeedSnapshot());
      setComposerReq(null);
      dispatchVerification({ type: "verified" });
      setLastVerifiedAtMs(null);
      setCfAttentionDismissed(false);
    }
    accountOwner.current = username;
    setPlatform(boot.platform);
    document.documentElement.dataset.platform = boot.platform;
    setIsAuthorized(boot.isAuthorized);
    setCurrentUser(boot.state.currentUser ?? null);
    setExpiredUsername(boot.state.expiredUsername ?? null);
    if (boot.state.expiredUsername && !boot.isAuthorized) {
      setIsGuestMode(true);
    }
    if (boot.isAuthorized) {
      setIsGuestMode(false);
      setAuthStatusHint(null);
    }
    setSavedAccounts(boot.state.savedAccounts ?? []);
    setPreferences(boot.state.preferences ?? DEFAULT_PREFS);
    setReadFloors(boot.state.readFloors ?? {});
    setDrafts(boot.state.drafts ?? {});
    setUnreadCount(boot.state.unreadNotifications ?? 0);
    setBootstrapped(true);
    return boot;
  }, []);

  const loadFeed = useCallback(
    async (opts?: {
      sort?: FeedSort;
      period?: TopPeriod;
      categoryId?: number | null;
      tag?: string | null;
      silent?: boolean;
    }) => {
      const reqGen = ++feedGenerationRef.current;
      setLoadingMoreTopics(false);
      const sort = opts?.sort ?? currentSort;
      const period = opts?.period ?? topPeriod;
      const catId = opts?.categoryId !== undefined ? opts.categoryId : selectedCategoryId;
      const tg = opts?.tag !== undefined ? opts.tag : selectedTag;

      if (!opts?.silent) {
        setFeedLoading(true);
      }
      setFeedError(null);
      setLoadMoreError(null);
      setPendingFeed(createEmptyPendingFeedSnapshot());

      try {
        const res = await fetchTopicFeed({
          sort,
          page: 0,
          categoryId: catId,
          tag: tg,
          topPeriod: period,
          username: currentUser?.username,
        });
        if (reqGen !== feedGenerationRef.current) return;
        setTopics(res.topics);
        setFeedPage(0);
        setHasMoreTopics(res.hasMore);
        lastFeedSyncedAtRef.current = Date.now();
      } catch (err: any) {
        if (reqGen !== feedGenerationRef.current) return;
        verificationBlocked.current.feed = err instanceof SiteError && err.retryReadAfterVerification;
        setFeedError(err?.message || "加载动态失败，请重试");
      } finally {
        if (reqGen === feedGenerationRef.current) {
          setFeedLoading(false);
        }
      }
    },
    [currentSort, topPeriod, selectedCategoryId, selectedTag, currentUser?.username]
  );

  const launchOrAppendComposer = useCallback((req: ComposerLaunchRequest) => {
    setComposerReq((prev) => {
      let enriched = req;
      if ((req.action === "REPLY" || req.action === "EDIT") && req.topicId) {
        const matchDetail =
          activeDetailRef.current?.id === req.topicId
            ? activeDetailRef.current
            : msgDetailRef.current?.id === req.topicId
              ? msgDetailRef.current
              : null;
        const matchSummary =
          activeSummaryRef.current?.id === req.topicId
            ? activeSummaryRef.current
            : msgSummaryRef.current?.id === req.topicId
              ? msgSummaryRef.current
              : null;
        const floor = req.replyToPostNumber ?? req.postNumber ?? 1;
        const target = matchDetail?.posts.find((post) => post.postNumber === floor);
        const poster = floor === 1 ? matchSummary?.originalPoster : undefined;
        const fallbackUsername = target?.username ?? poster?.username;
        const fallbackDisplayName = target?.displayName ?? poster?.name;
        const fallbackAvatar = target?.avatarTemplate ?? poster?.avatarTemplate;
        const fallbackAnim = target?.animatedAvatar ?? poster?.animatedAvatar;
        enriched = {
          ...req,
          topicTitle:
            req.topicTitle || matchDetail?.title || matchSummary?.title || req.initialTitle,
          replyToUsername: req.replyToUsername || fallbackUsername || undefined,
          replyToDisplayName: req.replyToDisplayName || fallbackDisplayName || undefined,
          replyToAvatarTemplate: req.replyToAvatarTemplate || fallbackAvatar || undefined,
          replyToAnimatedAvatar:
            req.replyToAnimatedAvatar !== undefined ? req.replyToAnimatedAvatar : fallbackAnim,
        };
      }
      if (
        prev &&
        prev.action === "REPLY" &&
        enriched.action === "REPLY" &&
        prev.topicId &&
        prev.topicId === enriched.topicId
      ) {
        if (enriched.initialRaw) {
          setComposerAppendQuote({
            text: enriched.initialRaw,
            nonce: Date.now() + Math.random(),
          });
        }
        return {
          ...prev,
          topicTitle: enriched.topicTitle ?? prev.topicTitle,
          replyToPostNumber: enriched.replyToPostNumber,
          replyToUsername: enriched.replyToUsername,
          replyToDisplayName: enriched.replyToDisplayName,
          replyToAvatarTemplate: enriched.replyToAvatarTemplate,
          replyToAnimatedAvatar: enriched.replyToAnimatedAvatar,
          replyToSnippet: enriched.replyToSnippet,
        };
      }
      setComposerAppendQuote(null);
      return enriched;
    });
  }, []);

  const handleFloorSeen = useCallback((tid: number, floor: number) => {
    const cleared = recordConfirmedTopicFloorForNotifications(tid, floor);
    if (cleared > 0) {
      setUnreadCount((prev) => {
        const next = Math.max(0, prev - cleared);
        setUnreadBadgeInRust(next).catch(() => {});
        return next;
      });
    }
    setReadFloors((prev) => {
      const old = prev[String(tid)] ?? 0;
      if (floor <= old) return prev;
      recordReadFloorInRust(tid, floor).catch(() => {});
      return { ...prev, [String(tid)]: floor };
    });
  }, []);

  const openTopic = useCallback(
    async (
      topic: TopicSummary,
      opts?: {
        floor?: number | null;
        highlightFloor?: boolean;
        onlyOp?: boolean;
        summary?: boolean;
        forceRefresh?: boolean;
        inPlace?: boolean;
        filterSwitch?: boolean;
        trackVisit?: boolean;
        bypassGuard?: boolean;
      }
    ): Promise<{ addedReplies?: number; error?: string } | void> => {
      if (
        !opts?.forceRefresh &&
        !opts?.inPlace &&
        !opts?.filterSwitch &&
        opts?.floor == null &&
        opts?.onlyOp == null &&
        opts?.summary == null &&
        isWideLayout &&
        currentTab === "HOME" &&
        activeSummary?.id === topic.id &&
        activeDetail?.id === topic.id &&
        !readerError
      ) {
        return;
      }
      const curComposer = composerReqRef.current;
      if (
        !opts?.bypassGuard &&
        curComposer &&
        curComposer.action !== "NEW_TOPIC" &&
        curComposer.topicId &&
        curComposer.topicId !== topic.id &&
        composerExitGuardRef.current
      ) {
        composerExitGuardRef.current(() => {
          void openTopic(topic, { ...opts, bypassGuard: true });
        });
        return;
      }
      setComposerReq((prev) =>
        prev && prev.action !== "NEW_TOPIC" && prev.topicId && prev.topicId !== topic.id
          ? null
          : prev
      );
      const seq = ++topicReqSeqRef.current;
      const nextOnlyOp = opts?.onlyOp ?? false;
      const nextSummary = !nextOnlyOp && Boolean(opts?.summary);
      const desiredFloor = opts?.floor ?? null;
      const curDetail = activeDetailRef.current;
      const isInPlace = Boolean(
        opts?.inPlace &&
        curDetail &&
        curDetail.id === topic.id &&
        curDetail.posts.length > 0
      );
      const isFilterSwitch = Boolean(
        opts?.filterSwitch &&
        !isInPlace &&
        curDetail &&
        curDetail.id === topic.id &&
        curDetail.posts.length > 0
      );
      const prevOnlyOp = onlyOpFilter;
      const prevSummary = summaryFilter;
      const prevPostsCount = curDetail?.postsCount ?? topic.postsCount ?? 0;
      const shouldTrack = isInPlace || isFilterSwitch ? false : opts?.trackVisit !== false;
      const canHitCache =
        (!opts?.forceRefresh || isFilterSwitch) &&
        !isInPlace &&
        !nextOnlyOp &&
        !nextSummary &&
        (!desiredFloor || desiredFloor <= 1);
      const instantCached = canHitCache ? getCachedTopicDetail(topic.id) : null;

      if (isFilterSwitch) {
        setOnlyOpFilter(nextOnlyOp);
        setSummaryFilter(nextSummary);
        setReaderError(null);
        if (instantCached) {
          setReaderFilterLoading(false);
          setActiveDetail((prev) => applyTopicFilterResult(prev, instantCached));
          setFilterSettledNonce((n) => n + 1);
          return;
        }
        setReaderFilterLoading(true);
      } else if (!isInPlace) {
        setActiveSummary(topic);
        setOnlyOpFilter(nextOnlyOp);
        setSummaryFilter(nextSummary);
        setTargetFloor(desiredFloor);
        setTargetFloorHighlight(Boolean(opts?.highlightFloor && desiredFloor && desiredFloor > 1));
        setNarrowShowingReader(true);
        setReaderError(null);
        setReaderFilterLoading(false);
        if (shouldTrack) {
          recordLocalTopicVisit({
            topicId: topic.id,
            title: topic.title,
            categoryId: topic.categoryId,
            categoryName: topic.categoryName,
            categoryColorHex: topic.categoryColorHex,
            authorUsername: topic.originalPoster.username,
            authorName: topic.originalPoster.name,
            authorAvatar: topic.originalPoster.avatarTemplate,
            replyCount: topic.replyCount,
            views: topic.views,
          });
        }
      }

      if (!isFilterSwitch && instantCached) {
        if (isAuthorized && instantCached.posts.length > 0) {
          const singleFloorAllRead =
            instantCached.posts.length === 1 &&
            (topic.unreadPosts || 0) === 0 &&
            instantCached.posts[0].isRead;
          const initFloor = singleFloorAllRead
            ? Math.max(
                instantCached.lastReadPostNumber ?? 0,
                instantCached.highestPostNumber ?? 0,
                topic.highestPostNumber ?? 0,
                1
              )
            : Math.max(instantCached.lastReadPostNumber ?? 0, 1);
          handleFloorSeen(instantCached.id, initFloor);
        }
        setReaderLoading(false);
        setActiveDetail(instantCached);
        setTargetJumpNonce((n) => n + 1);
        return;
      }

      if (!isInPlace && !isFilterSwitch && (opts?.forceRefresh || activeDetail?.id !== topic.id)) {
        setActiveDetail(null);
      }
      if (!isFilterSwitch) {
        setReaderLoading(true);
      }

      const nowClickMs = Date.now();
      const prevClickMs = lastTopicClickMsRef.current;
      lastTopicClickMsRef.current = nowClickMs;

      try {
        if (!opts?.forceRefresh && !isInPlace && !isFilterSwitch && prevClickMs > 0 && nowClickMs - prevClickMs < 750) {
          // Rapid topic skimming protection: wait 165ms; if user clicked another topic, abort BEFORE firing any network call!
          await new Promise((r) => setTimeout(r, 165));
          if (seq !== topicReqSeqRef.current) return;
        }
        const fetchTargetFloor = isFilterSwitch
          ? null
          : isInPlace
            ? desiredFloor && desiredFloor > 12 && !nextOnlyOp && !nextSummary
              ? desiredFloor
              : null
            : desiredFloor;
        const effectiveOpUsername =
          curDetail?.id === topic.id && curDetail.opUsername
            ? curDetail.opUsername
            : topic.originalPoster.username;
        const detail = await fetchTopicDetail(topic.id, {
          targetPostNumber: fetchTargetFloor,
          onlyOpUsername: nextOnlyOp ? effectiveOpUsername : null,
          filterSummary: nextSummary,
          trackVisit: shouldTrack,
          forceRefresh: opts?.forceRefresh || isInPlace || isFilterSwitch,
        });
        if (seq !== topicReqSeqRef.current) return;
        const opPost = detail.posts.find((p) => p.postNumber === 1) ?? detail.posts[0];
        if (shouldTrack) {
          recordLocalTopicVisit({
            topicId: detail.id,
            title: detail.title,
            categoryId: detail.categoryId,
            categoryName: detail.categoryName,
            categoryColorHex: detail.categoryColorHex,
            authorUsername: detail.opUsername || opPost?.username,
            authorName: opPost?.displayName,
            authorAvatar: opPost?.avatarTemplate,
            replyCount: Math.max(0, detail.postsCount - 1),
            views: detail.views,
          });
        }
        if (isFilterSwitch) {
          setReaderError(null);
          setActiveDetail((prev) => applyTopicFilterResult(prev, detail));
          setFilterSettledNonce((n) => n + 1);
          return;
        }
        if (isInPlace) {
          setReaderError(null);
          setActiveDetail((prev) => mergeTopicDetailInPlace(prev, detail, nextOnlyOp));
          setTopics((prev) =>
            prev.map((t) =>
              t.id === detail.id
                ? {
                    ...t,
                    postsCount: Math.max(t.postsCount, detail.postsCount),
                    highestPostNumber: Math.max(
                      t.highestPostNumber,
                      detail.highestPostNumber ?? 0,
                      detail.postsCount
                    ),
                    replyCount: Math.max(0, detail.postsCount - 1),
                    views: Math.max(t.views, detail.views),
                    likeCount: Math.max(t.likeCount, detail.likeCount),
                  }
                : t
            )
          );
          return { addedReplies: Math.max(0, detail.postsCount - prevPostsCount) };
        }
        if (isAuthorized && detail.posts.length > 0) {
          const singleFloorAllRead =
            detail.posts.length === 1 &&
            (topic.unreadPosts || 0) === 0 &&
            detail.posts[0].isRead;
          const initFloor = singleFloorAllRead
            ? Math.max(
                detail.lastReadPostNumber ?? 0,
                detail.highestPostNumber ?? 0,
                topic.highestPostNumber ?? 0,
                1
              )
            : Math.max(detail.lastReadPostNumber ?? 0, 1);
          handleFloorSeen(detail.id, initFloor);
        }
        setTopics((prev) =>
          prev.map((t) =>
            t.id === detail.id
              ? {
                  ...t,
                  unseen: isAuthorized ? false : t.unseen,
                  postsCount: Math.max(t.postsCount, detail.postsCount),
                  highestPostNumber: Math.max(
                    t.highestPostNumber,
                    detail.highestPostNumber ?? 0,
                    detail.postsCount
                  ),
                }
              : t
          )
        );
        setActiveDetail(detail);
        setTargetJumpNonce((n) => n + 1);
      } catch (err: any) {
        if (seq !== topicReqSeqRef.current) return;
        const msg = err?.message || (isInPlace || isFilterSwitch ? "切换筛选失败，请稍后重试" : "帖子加载失败，请重试");
        if (isFilterSwitch) {
          setOnlyOpFilter(prevOnlyOp);
          setSummaryFilter(prevSummary);
          setGlobalNotice(msg);
          return { error: msg };
        }
        if (isInPlace) {
          return { error: msg };
        }
        verificationBlocked.current.reader = err instanceof SiteError && err.retryReadAfterVerification;
        setReaderError(msg);
      } finally {
        if (seq === topicReqSeqRef.current) {
          setReaderLoading(false);
          setReaderFilterLoading(false);
        }
      }
    },
    [
      activeDetail?.id,
      activeSummary?.id,
      currentTab,
      handleFloorSeen,
      isAuthorized,
      isWideLayout,
      readerError,
      onlyOpFilter,
      summaryFilter,
    ]
  );

  const openMessageTopic = useCallback(
    async (
      topic: TopicSummary,
      opts?: {
        floor?: number | null;
        highlightFloor?: boolean;
        onlyOp?: boolean;
        summary?: boolean;
        forceRefresh?: boolean;
        inPlace?: boolean;
        filterSwitch?: boolean;
        bypassGuard?: boolean;
      }
    ): Promise<{ addedReplies?: number; error?: string } | void> => {
      if (
        !opts?.forceRefresh &&
        !opts?.inPlace &&
        !opts?.filterSwitch &&
        opts?.floor == null &&
        opts?.onlyOp == null &&
        opts?.summary == null &&
        isWideLayout &&
        currentTab === "MESSAGES" &&
        msgSummary?.id === topic.id &&
        msgDetail?.id === topic.id &&
        !msgReaderError
      ) {
        return;
      }
      const curComposer = composerReqRef.current;
      if (
        !opts?.bypassGuard &&
        curComposer &&
        curComposer.action !== "NEW_TOPIC" &&
        curComposer.topicId &&
        curComposer.topicId !== topic.id &&
        composerExitGuardRef.current
      ) {
        composerExitGuardRef.current(() => {
          void openMessageTopic(topic, { ...opts, bypassGuard: true });
        });
        return;
      }
      setComposerReq((prev) =>
        prev && prev.action !== "NEW_TOPIC" && prev.topicId && prev.topicId !== topic.id
          ? null
          : prev
      );
      const seq = ++msgReqSeqRef.current;
      const nextOnlyOp = opts?.onlyOp ?? false;
      const nextSummary = !nextOnlyOp && Boolean(opts?.summary);
      const desiredFloor = opts?.floor ?? null;
      const curMsgDetail = msgDetailRef.current;
      const isInPlace = Boolean(
        opts?.inPlace &&
        curMsgDetail &&
        curMsgDetail.id === topic.id &&
        curMsgDetail.posts.length > 0
      );
      const isFilterSwitch = Boolean(
        opts?.filterSwitch &&
        !isInPlace &&
        curMsgDetail &&
        curMsgDetail.id === topic.id &&
        curMsgDetail.posts.length > 0
      );
      const prevMsgOnlyOp = msgOnlyOp;
      const prevMsgSummary = msgSummaryFilter;
      const prevPostsCount = curMsgDetail?.postsCount ?? topic.postsCount ?? 0;

      if (isFilterSwitch) {
        setMsgOnlyOp(nextOnlyOp);
        setMsgSummaryFilter(nextSummary);
        setMsgReaderError(null);
        const instantCached =
          !nextOnlyOp && !nextSummary ? getCachedTopicDetail(topic.id) : null;
        if (instantCached) {
          setMsgFilterLoading(false);
          setMsgDetail((prev) => applyTopicFilterResult(prev, instantCached));
          setMsgFilterSettledNonce((n) => n + 1);
          return;
        }
        setMsgFilterLoading(true);
      } else if (!isInPlace) {
        setMsgSummary(topic);
        setMsgOnlyOp(nextOnlyOp);
        setMsgSummaryFilter(nextSummary);
        setMsgTargetFloor(desiredFloor);
        setMsgTargetFloorHighlight(Boolean(opts?.highlightFloor && desiredFloor && desiredFloor > 1));
        setNarrowShowingReader(true);
        setMsgReaderError(null);
        setMsgFilterLoading(false);
        recordLocalTopicVisit({
          topicId: topic.id,
          title: topic.title,
          categoryId: topic.categoryId,
          categoryName: topic.categoryName,
          categoryColorHex: topic.categoryColorHex,
          authorUsername: topic.originalPoster.username,
          authorName: topic.originalPoster.name,
          authorAvatar: topic.originalPoster.avatarTemplate,
          replyCount: topic.replyCount,
          views: topic.views,
        });
      }

      if (!isInPlace && !isFilterSwitch && (opts?.forceRefresh || msgDetail?.id !== topic.id)) {
        setMsgDetail(null);
      }
      if (!isFilterSwitch) {
        setMsgReaderLoading(true);
      }

      try {
        const fetchTargetFloor = isFilterSwitch
          ? null
          : isInPlace
            ? desiredFloor && desiredFloor > 12 && !nextOnlyOp && !nextSummary
              ? desiredFloor
              : null
            : desiredFloor;
        const effectiveOpUsername =
          curMsgDetail?.id === topic.id && curMsgDetail.opUsername
            ? curMsgDetail.opUsername
            : topic.originalPoster.username;
        const detail = await fetchTopicDetail(topic.id, {
          targetPostNumber: fetchTargetFloor,
          onlyOpUsername: nextOnlyOp ? effectiveOpUsername : null,
          filterSummary: nextSummary,
          trackVisit: !isInPlace && !isFilterSwitch,
          forceRefresh: opts?.forceRefresh || isInPlace || isFilterSwitch,
        });
        if (seq !== msgReqSeqRef.current) return;
        const opPost = detail.posts.find((p) => p.postNumber === 1) ?? detail.posts[0];
        if (!isInPlace && !isFilterSwitch) {
          recordLocalTopicVisit({
            topicId: detail.id,
            title: detail.title,
            categoryId: detail.categoryId,
            categoryName: detail.categoryName,
            categoryColorHex: detail.categoryColorHex,
            authorUsername: detail.opUsername || opPost?.username,
            authorName: opPost?.displayName,
            authorAvatar: opPost?.avatarTemplate,
            replyCount: Math.max(0, detail.postsCount - 1),
            views: detail.views,
          });
        }
        if (isFilterSwitch) {
          setMsgReaderError(null);
          setMsgDetail((prev) => applyTopicFilterResult(prev, detail));
          setMsgFilterSettledNonce((n) => n + 1);
          return;
        }
        if (isInPlace) {
          setMsgReaderError(null);
          setMsgDetail((prev) => mergeTopicDetailInPlace(prev, detail, nextOnlyOp));
          return { addedReplies: Math.max(0, detail.postsCount - prevPostsCount) };
        }
        setMsgDetail(detail);
        setMsgJumpNonce((n) => n + 1);
      } catch (err: any) {
        if (seq !== msgReqSeqRef.current) return;
        const msg = err?.message || (isInPlace || isFilterSwitch ? "切换筛选失败，请稍后重试" : "消息内容加载失败，请重试");
        if (isFilterSwitch) {
          setMsgOnlyOp(prevMsgOnlyOp);
          setMsgSummaryFilter(prevMsgSummary);
          setGlobalNotice(msg);
          return { error: msg };
        }
        if (isInPlace) {
          return { error: msg };
        }
        verificationBlocked.current.message = err instanceof SiteError && err.retryReadAfterVerification;
        setMsgReaderError(msg);
      } finally {
        if (seq === msgReqSeqRef.current) {
          setMsgReaderLoading(false);
          setMsgFilterLoading(false);
        }
      }
    },
    [msgDetail?.id, msgSummary?.id, currentTab, isWideLayout, msgReaderError, msgOnlyOp, msgSummaryFilter]
  );

  const makeSummaryById = useCallback(
    (topicId: number, fallbackTitle: string, postNumber?: number): TopicSummary => {
      const existing = topics.find((t) => t.id === topicId);
      if (existing) return existing;
      return {
        id: topicId,
        title: fallbackTitle,
        slug: "topic",
        postsCount: postNumber ?? 1,
        replyCount: 0,
        views: 0,
        likeCount: 0,
        highestPostNumber: postNumber ?? 1,
        unreadPosts: 0,
        unseen: false,
        pinned: false,
        closed: false,
        archived: false,
        bookmarked: false,
        tags: [],
        createdAt: "",
        lastPostedAt: "",
        originalPoster: {
          userId: 0,
          username: "linuxdo",
          avatarTemplate: "",
        },
      };
    },
    [topics]
  );

  const openTopicById = useCallback(
    (topicId: number, fallbackTitle: string, postNumber?: number, forceRefresh = false) => {
      const summary = makeSummaryById(topicId, fallbackTitle, postNumber);
      setCurrentTab("HOME");
      openTopic(summary, {
        floor: postNumber ?? null,
        highlightFloor: Boolean(postNumber && postNumber > 1),
        forceRefresh,
      });
    },
    [makeSummaryById, openTopic]
  );

  const openMessageTopicById = useCallback(
    (topicId: number, fallbackTitle: string, postNumber?: number, forceRefresh = true) => {
      const summary = makeSummaryById(topicId, fallbackTitle, postNumber);
      const shouldFlash = Boolean(postNumber && postNumber > 1);
      if (!isWideLayout) {
        setCurrentTab("HOME");
        openTopic(summary, { floor: postNumber ?? null, highlightFloor: shouldFlash, forceRefresh });
      } else {
        openMessageTopic(summary, {
          floor: postNumber ?? null,
          highlightFloor: shouldFlash,
          forceRefresh,
        });
      }
    },
    [makeSummaryById, isWideLayout, openTopic, openMessageTopic]
  );

  const navigateTopicLinkInReader = useCallback(
    (tid: number, floor?: number, title?: string, fromFloor?: number) => {
      const label = title?.trim() || `话题 #${tid}`;
      if (isWideLayout && currentTab === "MESSAGES") {
        const cur = msgSummaryRef.current;
        if (cur && cur.id !== tid) {
          const curTitle = msgDetailRef.current?.title || cur.title;
          setMsgReaderHistory((prev) => [
            ...prev.slice(-19),
            { summary: { ...cur, title: curTitle }, floor: fromFloor ?? msgTargetFloorRef.current ?? 1 },
          ]);
        }
        openMessageTopicById(tid, label, floor, false);
      } else {
        const cur = activeSummaryRef.current;
        if (cur && cur.id !== tid) {
          const curTitle = activeDetailRef.current?.title || cur.title;
          setHomeReaderHistory((prev) => [
            ...prev.slice(-19),
            { summary: { ...cur, title: curTitle }, floor: fromFloor ?? targetFloorRef.current ?? 1 },
          ]);
        }
        openTopicById(tid, label, floor, false);
      }
    },
    [isWideLayout, currentTab, openMessageTopicById, openTopicById]
  );

  const goBackInReaderHistory = useCallback(() => {
    if (isWideLayout && currentTab === "MESSAGES") {
      setMsgReaderHistory((prev) => {
        if (prev.length === 0) return prev;
        const last = prev[prev.length - 1];
        void openMessageTopic(last.summary, {
          floor: last.floor > 1 ? last.floor : null,
          highlightFloor: false,
        });
        return prev.slice(0, -1);
      });
    } else {
      setHomeReaderHistory((prev) => {
        if (prev.length === 0) return prev;
        const last = prev[prev.length - 1];
        void openTopic(last.summary, {
          floor: last.floor > 1 ? last.floor : null,
          highlightFloor: false,
        });
        return prev.slice(0, -1);
      });
    }
  }, [isWideLayout, currentTab, openMessageTopic, openTopic]);

  const requestGuestLogin = useCallback(
    (
      title: string = DEFAULT_GUEST_LOGIN_PROMPT.title,
      subtitle: string = DEFAULT_GUEST_LOGIN_PROMPT.subtitle
    ) => {
      setShowAuthModal(false);
      setAuthModalStartAdding(false);
      setAuthStatusHint(null);
      setGuestPrompt({ title, subtitle });
    },
    []
  );

  const openAccountManagement = useCallback(() => {
    if (resolveAccountManagementTarget(isAuthorized, savedAccounts.length) === "guest_guide") {
      requestGuestLogin();
      return;
    }
    setGuestPrompt(null);
    setAuthStatusHint(null);
    setAuthModalStartAdding(false);
    setShowAuthModal(true);
  }, [isAuthorized, savedAccounts.length, requestGuestLogin]);

  const openQrLoginRedeem = useCallback(() => {
    setGuestPrompt(null);
    setShowAuthModal(false);
    setAuthModalStartAdding(false);
    setAuthStatusHint(null);
    setQrLoginInitialMode("redeem");
    setShowQrLoginModal(true);
  }, []);

  const handleSelectHomeCategory = useCallback(
    (catId: number | null) => {
      const decision = resolveRestrictedCategorySelection(catId, categories, isAuthorized);
      if (decision.blocked) {
        requestGuestLogin(decision.prompt.title, decision.prompt.subtitle);
        return;
      }
      setSelectedCategoryId(decision.categoryId);
    },
    [categories, isAuthorized, requestGuestLogin]
  );

  const handleSelectCategoryFromReader = useCallback(
    (categoryId?: number, slug?: string) => {
      let targetCat =
        categoryId != null ? categories.find((c) => c.id === categoryId) : undefined;
      if (!targetCat && slug) {
        const cleanSlug = slug.trim().toLowerCase();
        targetCat = categories.find(
          (c) => c.slug?.toLowerCase() === cleanSlug || c.name.toLowerCase() === cleanSlug
        );
      }
      const resolvedId = targetCat?.id ?? categoryId;
      if (!resolvedId) return;
      const decision = resolveRestrictedCategorySelection(resolvedId, categories, isAuthorized);
      if (decision.blocked) {
        requestGuestLogin(decision.prompt.title, decision.prompt.subtitle);
        return;
      }
      setSelectedCategoryId(resolvedId);
      setSelectedTag(null);
      setCurrentTab("HOME");
      setNarrowShowingReader(false);
      if (feedCollapsed) setFeedCollapsed(false);
    },
    [categories, isAuthorized, feedCollapsed, requestGuestLogin]
  );

  const handleSelectTagFromReader = useCallback(
    (tag: string) => {
      const clean = tag.trim();
      if (!clean) return;
      setSelectedTag(clean);
      setCurrentTab("HOME");
      setNarrowShowingReader(false);
      if (feedCollapsed) setFeedCollapsed(false);
    },
    [feedCollapsed]
  );

  const handleOpenInAppUrl = useCallback(
    (url: string) => {
      const trimmed = url.trim();
      const internalTopic = parseInternalTopicLink(trimmed);
      if (internalTopic) {
        navigateTopicLinkInReader(internalTopic.topicId, internalTopic.postNumber);
        return;
      }
      if (/^https?:\/\/connect\.linux\.do\/?$/i.test(trimmed) && currentUser) {
        setShowConnectModal(true);
      } else {
        openInAppForumPanel(trimmed, isDark).catch(() => openUrl(trimmed));
      }
    },
    [navigateTopicLinkInReader, currentUser, isDark]
  );

  const handleDecrementUnread = useCallback(() => {
    setUnreadCount((prev) => {
      const next = Math.max(0, prev - 1);
      setUnreadBadgeInRust(next).catch(() => {});
      return next;
    });
  }, []);

  const handleSyncUnreadCount = useCallback((next: number) => {
    const clamped = Math.max(0, next);
    setUnreadCount(clamped);
    setUnreadBadgeInRust(clamped).catch(() => {});
  }, []);

  // Keep latest callbacks in refs so the mount-only Tauri event listener subscription NEVER tears down on filter/tab re-renders!
  const syncFromRustStateRef = useRef(syncFromRustState);
  syncFromRustStateRef.current = syncFromRustState;
  const loadFeedRef = useRef(loadFeed);
  loadFeedRef.current = loadFeed;
  const recoverVerifiedView = useRef<() => Promise<void>>(async () => {});
  recoverVerifiedView.current = async () => {
    if (!isAuthorized && !isGuestMode) return;
    const readerVisible = isWideLayout || narrowShowingReader;
    const blocked = verificationBlocked.current;
    if (isWideLayout && currentTab === "MESSAGES" && blocked.message && msgSummary && msgReaderError && !msgReaderLoading) {
      await openMessageTopic(msgSummary, { floor: msgTargetFloor, inPlace: true, onlyOp: msgOnlyOp, summary: msgSummaryFilter, forceRefresh: true });
    } else if (currentTab === "HOME" && blocked.reader && activeSummary && readerError && !readerLoading && readerVisible) {
      await openTopic(activeSummary, { floor: targetFloor, inPlace: true, onlyOp: onlyOpFilter, summary: summaryFilter, forceRefresh: true, trackVisit: false });
    } else if (currentTab === "HOME" && blocked.feed && feedError && !feedLoading && (isWideLayout || !narrowShowingReader)) {
      await loadFeed({ silent: true });
    } else if (currentTab === "HOME" && blocked.more && loadMoreError && (isWideLayout || !narrowShowingReader)) {
      await handleLoadMoreTopics();
    }
  };
  const bootRanRef = useRef(false);

  // Initial bootstrap & Tauri event listeners (await listener registration BEFORE syncFromRustState so cold-start deep links never race)
  useEffect(() => {
    installGlobalDiagHooks(DESKTOP_APP_VERSION);
    const stopObservingScroll = observeScrollActivity();
    const stopMeasuringRefresh = measureDisplayRefresh();

    let cancelled = false;
    const unsubs: Array<() => void> = [];

    (async () => {
      getEchStatus()
        .then((snap) => {
          if (!cancelled) setEchStatus(snap);
        })
        .catch(() => {});
      const registered = await Promise.all([
        listen<EchDesktopSnapshot>("lindo://ech-updated", (ev) => {
          if (!cancelled && ev.payload) {
            setEchStatus(ev.payload);
          }
        }),
        listen("lindo://state-updated", () => {
          syncFromRustStateRef.current().then((b) => {
            if (b.isAuthorized) {
              if (browserAuthWatchdogRef.current) {
                clearTimeout(browserAuthWatchdogRef.current);
                browserAuthWatchdogRef.current = null;
              }
              setAuthCompleting(false);
              setAuthStatusHint(null);
              setAuthModalStartAdding(false);
              setShowAuthModal(false);
              setShowQrLoginModal(false);
              setQrLoginInitialMode(undefined);
              setGuestPrompt(null);
              if (b.state.currentUser?.username) {
                setGlobalNotice(`已登录 @${b.state.currentUser.username}`);
              }
              fetchSiteCategories(true)
                .then(setCategories)
                .catch(() => {});
            }
          });
        }),
        listen<{ refreshBlocked?: boolean } | null>("lindo://verified", ({ payload }) => {
          dispatchVerification({ type: "verified" });
          setCfAttentionDismissed(false);
          setLastVerifiedAtMs(Date.now());
          resumeTimingsAfterVerify();
          const refreshView = payload?.refreshBlocked === false ? Promise.resolve() : recoverVerifiedView.current();
          void refreshView.then(async () => {
            await drainPendingMarkReads();
            await flushTopicTimingsNow();
          }).catch(() => {});
        }),
        listen<{ level?: string; category?: string; tag?: string; summary?: string; detail?: string }>(
          "lindo://diag-event",
          (ev) => {
            const p = ev.payload;
            if (p?.summary) {
              if (
                browserAuthWatchdogRef.current &&
                (p.summary.includes("收到默认浏览器授权回调") || p.tag === "AuthSuccess")
              ) {
                clearTimeout(browserAuthWatchdogRef.current);
                browserAuthWatchdogRef.current = null;
              }
              diagLog(
                (p.level as any) || "INFO",
                (p.category as any) || "AUTH",
                p.tag || "Native",
                p.summary,
                p.detail
              );
            }
          }
        ),
        listen<boolean>("lindo://auth-completing", (ev) => {
          setAuthCompleting(Boolean(ev.payload));
        }),
        listen<boolean>("lindo://cf-attention-needed", (ev) => {
          const needed = Boolean(ev.payload);
          dispatchVerification({ type: "attention", needed });
          if (needed) setCfAttentionDismissed(false);
        }),
        listen<VerifyPillEvent>("lindo://verify-pill", ({ payload }) => {
          dispatchVerification({ type: "progress", event: payload });
          if (payload.stage === "unavailable") setGlobalNotice("网页验证已通过，论坛连接尚未恢复");
        }),
        listen<string>("lindo://auth-notice", (ev) => {
          const msg = String(ev.payload || "").trim();
          setAuthStatusHint(msg || null);
          if (msg) setGlobalNotice(msg);
        }),
        listen<string>("lindo://tray-command", (ev) => {
          if (ev.payload === "new_topic") {
            launchOrAppendComposer({ action: "NEW_TOPIC" });
          } else if (ev.payload === "refresh") {
            loadFeedRef.current();
          }
        }),
        listen<UpdateProgressPayload>("lindo://update-progress", (ev) => {
          if (ev.payload) {
            setUpdateProgress(ev.payload);
          }
        }),
      ]);

      if (cancelled) {
        registered.forEach((u) => u());
        return;
      }
      unsubs.push(...registered);

      if (!bootRanRef.current) {
        bootRanRef.current = true;
        const boot = await syncFromRustStateRef.current();
        if (boot.isAuthorized) {
          refreshCurrentUserSession()
            .then(({ profile, unread }) => {
              if (profile) {
                setIsAuthorized(true);
                setCurrentUser(profile);
                setUnreadCount(unread);
              }
            })
            .catch(() => {});
        }

      }
    })();

    return () => {
      cancelled = true;
      stopObservingScroll();
      stopMeasuringRefresh();
      if (browserAuthWatchdogRef.current) {
        clearTimeout(browserAuthWatchdogRef.current);
        browserAuthWatchdogRef.current = null;
      }
      unsubs.forEach((u) => u());
    };
  }, []);

  // Sync composer / typing / visibility activity to Rust bridge so proactive CF security confirmation
  // never steals focus while user is typing in ComposerModal or composing IME text.
  useEffect(() => {
    emit("lindo://ui-activity", {
      composerOpen: Boolean(composerReq),
      windowVisible: typeof document !== "undefined" ? !document.hidden : true,
    }).catch(() => {});
  }, [composerReq]);

  useEffect(() => {
    let lastTypingEmitAt = 0;
    const isEditableTarget = (target: EventTarget | null): boolean => {
      const el = target as HTMLElement | null;
      if (!el || typeof el.closest !== "function") return false;
      return Boolean(el.closest('textarea, input, [contenteditable="true"]'));
    };
    const noteTyping = (ev: Event) => {
      if (!isEditableTarget(ev.target)) return;
      const now = Date.now();
      if (now - lastTypingEmitAt < 600) return;
      lastTypingEmitAt = now;
      emit("lindo://ui-activity", { typingActive: true }).catch(() => {});
    };
    const onCompStart = (ev: CompositionEvent) => {
      if (!isEditableTarget(ev.target)) return;
      lastTypingEmitAt = Date.now();
      emit("lindo://ui-activity", { imeComposing: true, typingActive: true }).catch(() => {});
    };
    const onCompEnd = (ev: CompositionEvent) => {
      if (!isEditableTarget(ev.target)) return;
      lastTypingEmitAt = Date.now();
      emit("lindo://ui-activity", { imeComposing: false, typingActive: true }).catch(() => {});
    };
    const onVisibilityOrFocus = () => {
      emit("lindo://ui-activity", {
        windowVisible: typeof document !== "undefined" ? !document.hidden : true,
      }).catch(() => {});
    };
    window.addEventListener("keydown", noteTyping, true);
    window.addEventListener("input", noteTyping, true);
    window.addEventListener("compositionstart", onCompStart, true);
    window.addEventListener("compositionend", onCompEnd, true);
    document.addEventListener("visibilitychange", onVisibilityOrFocus);
    window.addEventListener("focus", onVisibilityOrFocus);
    return () => {
      window.removeEventListener("keydown", noteTyping, true);
      window.removeEventListener("input", noteTyping, true);
      window.removeEventListener("compositionstart", onCompStart, true);
      window.removeEventListener("compositionend", onCompEnd, true);
      document.removeEventListener("visibilitychange", onVisibilityOrFocus);
      window.removeEventListener("focus", onVisibilityOrFocus);
    };
  }, []);

  // Load site category catalog calmly after main feed boots (single-flight + 5-min TTL inside api.ts)
  useEffect(() => {
    if (!bootstrapped || !inMainApp) return;
    const timer = setTimeout(() => {
      fetchSiteCategories()
        .then(setCategories)
        .catch(() => {});
    }, 900);
    return () => clearTimeout(timer);
  }, [bootstrapped, inMainApp, isAuthorized]);

  // The current username is part of the follow feed URL.
  useEffect(() => {
    if (!bootstrapped || !inMainApp) return;
    loadFeed();
  }, [bootstrapped, inMainApp, loadFeed]);

  // Calmly auto-open first regular topic on wide screen HOME without firing cold-start visit-tracking burst
  useEffect(() => {
    if (inMainApp && isWideLayout && !activeSummary && topics.length > 0 && currentTab === "HOME") {
      const first = topics.find((t) => !t.pinned) ?? topics[0];
      if (!first) return;
      const timer = setTimeout(() => {
        openTopic(first, { trackVisit: false });
      }, 150);
      return () => clearTimeout(timer);
    }
  }, [inMainApp, isWideLayout, activeSummary, topics, currentTab, openTopic]);

  const feedPollCtxRef = useRef({
    currentTab,
    currentSort,
    selectedCategoryId,
    selectedTag,
    topics,
    isAuthorized,
    feedError,
    readerError,
    feedLoading,
    loadingMoreTopics,
  });
  feedPollCtxRef.current = {
    currentTab,
    currentSort,
    selectedCategoryId,
    selectedTag,
    topics,
    isAuthorized,
    feedError,
    readerError,
    feedLoading,
    loadingMoreTopics,
  };

  const checkPendingNewFeedSilently = useCallback(async () => {
    if (document.hidden || backgroundPollInFlightRef.current) return;
    const ctx = feedPollCtxRef.current;
    if (
      ctx.currentTab !== "HOME" ||
      (ctx.currentSort !== "latest" &&
        ctx.currentSort !== "new" &&
        ctx.currentSort !== "unread")
    ) {
      return;
    }
    if (
      ctx.feedLoading ||
      ctx.loadingMoreTopics ||
      ctx.feedError ||
      ctx.readerError ||
      ctx.topics.length === 0
    ) {
      return;
    }
    const genBefore = feedGenerationRef.current;
    backgroundPollInFlightRef.current = true;
    try {
      const latest = await fetchTopicFeed({
        sort: ctx.currentSort,
        page: 0,
        categoryId: ctx.selectedCategoryId,
        tag: ctx.selectedTag,
        background: true,
      });
      const latestCtx = feedPollCtxRef.current;
      if (
        genBefore === feedGenerationRef.current &&
        !latestCtx.feedLoading &&
        !latestCtx.loadingMoreTopics &&
        latestCtx.currentSort === ctx.currentSort &&
        latestCtx.selectedCategoryId === ctx.selectedCategoryId &&
        latestCtx.selectedTag === ctx.selectedTag &&
        latestCtx.topics.length > 0 &&
        latest.topics.length > 0
      ) {
        const nowMs = Date.now();
        lastFeedSyncedAtRef.current = nowMs;
        setPendingFeed((prev) =>
          evaluatePendingFeedPoll(prev, latestCtx.topics, latest.topics, nowMs)
        );
      }
      if (latestCtx.isAuthorized) {
        const me = await refreshCurrentUserSession();
        if (me.profile) setUnreadCount(me.unread);
      }
    } catch {
      // silent background check
    } finally {
      backgroundPollInFlightRef.current = false;
    }
  }, []);

  // Disciplined Discourse MessageBus live subscription for near-real-time notifications & active topic updates
  useEffect(() => {
    if (!inMainApp || !isAuthorized || !currentUser?.userId) return;
    const unsubNotif = discourseMessageBus.subscribe(
      `/notification/${currentUser.userId}`,
      (data) => {
        invalidateNotificationShortCache();
        const nextUnread = Number(data?.unread_notifications ?? data?.all_unread_notifications_count ?? -1);
        if (Number.isFinite(nextUnread) && nextUnread >= 0) {
          setUnreadCount(nextUnread);
          setUnreadBadgeInRust(nextUnread).catch(() => {});
        }
      }
    );
    return () => {
      unsubNotif();
    };
  }, [inMainApp, isAuthorized, currentUser?.userId]);

  useEffect(() => {
    if (!inMainApp || !activeSummary?.id || currentTab !== "HOME") return;
    const tid = activeSummary.id;
    const unsubTopic = discourseMessageBus.subscribe(`/topic/${tid}`, (data) => {
      if (data?.type === "created" && activeSummaryRef.current?.id === tid) {
        void openTopic(activeSummaryRef.current, {
          inPlace: true,
          forceRefresh: true,
          onlyOp: onlyOpFilter,
          summary: summaryFilter,
        });
      }
    });
    return () => {
      unsubTopic();
    };
  }, [inMainApp, activeSummary?.id, currentTab, openTopic, onlyOpFilter, summaryFilter]);

  // Stable 75s background poll for `N 条新动态` capsule & unread notifications (never reset by pagination or topic clicks!)
  useEffect(() => {
    if (!inMainApp) return;
    const timer = setInterval(() => {
      void checkPendingNewFeedSilently();
    }, 75000);
    const onVisibility = () => {
      const latestSyncOrPollMs = Math.max(
        lastFeedSyncedAtRef.current,
        pendingFeedRef.current.fetchedAtMs
      );
      if (!document.hidden && Date.now() - latestSyncOrPollMs >= 60000) {
        void checkPendingNewFeedSilently();
      }
    };
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      clearInterval(timer);
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, [inMainApp, checkPendingNewFeedSilently]);

  // Also check quietly when switching back to HOME tab after >= 60s
  useEffect(() => {
    if (!inMainApp || currentTab !== "HOME") return;
    const latestSyncOrPollMs = Math.max(
      lastFeedSyncedAtRef.current,
      pendingFeedRef.current.fetchedAtMs
    );
    if (Date.now() - latestSyncOrPollMs >= 60000) {
      const t = setTimeout(() => {
        void checkPendingNewFeedSilently();
      }, 450);
      return () => clearTimeout(t);
    }
  }, [inMainApp, currentTab, checkPendingNewFeedSilently]);

  const applyPendingNewTopicsNow = useCallback(() => {
    const nowMs = Date.now();
    const snapshot = pendingFeedRef.current;
    setPendingFeed(createEmptyPendingFeedSnapshot());
    const decision = resolvePendingFeedApply(snapshot, feedPollCtxRef.current.topics, nowMs);
    if (decision.kind === "fetch_live") {
      void loadFeed();
      return;
    }
    feedGenerationRef.current += 1;
    setLoadingMoreTopics(false);
    setTopics(decision.nextTopics);
    if (decision.resetPageToZero) {
      setFeedPage(0);
    }
    setHasMoreTopics(true);
    lastFeedSyncedAtRef.current = nowMs;
  }, [loadFeed]);

  const paneAnimTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => {
    return () => {
      if (paneAnimTimerRef.current) clearTimeout(paneAnimTimerRef.current);
      document.documentElement.classList.remove("lindo-pane-animating");
    };
  }, []);

  const toggleFeedCollapse = useCallback(() => {
    if (paneAnimTimerRef.current) clearTimeout(paneAnimTimerRef.current);
    document.documentElement.classList.add("lindo-pane-animating");
    paneAnimTimerRef.current = setTimeout(() => {
      paneAnimTimerRef.current = null;
      document.documentElement.classList.remove("lindo-pane-animating");
    }, 225);

    setFeedCollapsed((prev) => {
      const next = !prev;
      if (next && currentTab === "HOME" && !activeSummary && topics.length > 0) {
        const first = topics.find((t) => !t.pinned) ?? topics[0];
        if (first) void openTopic(first, { trackVisit: false });
      }
      return next;
    });
  }, [currentTab, activeSummary, topics, openTopic]);

  // Global keyboard shortcuts
  useEffect(() => {
    ensureGlobalImeTracking();
    const onKeyDown = (e: KeyboardEvent) => {
      if (isImeComposingEvent(e)) return;
      const mod = e.metaKey || e.ctrlKey;
      if (e.ctrlKey && !e.metaKey && !e.altKey && !e.shiftKey && e.key.toLowerCase() === "q" && platform === "linux") {
        e.preventDefault();
        invoke("quit_app").catch(() => {});
        return;
      }
      if (update.prompt) {
        if (e.key === "Escape") {
          e.preventDefault();
          closeUpdate();
        }
        return;
      }
      if (mod && e.key === "\\" && inMainApp && isWideLayout && (currentTab === "HOME" || currentTab === "MESSAGES")) {
        e.preventDefault();
        toggleFeedCollapse();
      } else if (mod && e.key === "[" && inMainApp && (currentTab === "HOME" || currentTab === "MESSAGES")) {
        const hasHistory =
          isWideLayout && currentTab === "MESSAGES"
            ? msgReaderHistory.length > 0
            : homeReaderHistory.length > 0;
        if (hasHistory) {
          e.preventDefault();
          goBackInReaderHistory();
        }
      } else if (mod && e.key.toLowerCase() === "k" && inMainApp) {
        e.preventDefault();
        setShowGlobalSearch((v) => !v);
      } else if (mod && e.key.toLowerCase() === "n" && inMainApp) {
        e.preventDefault();
        if (isAuthorized)
          launchOrAppendComposer({
            action: "NEW_TOPIC",
            initialCategoryId: selectedCategoryId ?? undefined,
          });
        else
          requestGuestLogin(
            "登录后发布话题",
            "登录 LINUX DO 账号，与佬友分享你的发现与见解"
          );
      } else if (mod && e.key.toLowerCase() === "r" && inMainApp) {
        e.preventDefault();
        loadFeed();
        if (currentTab === "HOME" && activeSummary) {
          openTopic(activeSummary, { floor: targetFloor, forceRefresh: true });
        } else if (currentTab === "MESSAGES" && msgSummary) {
          openMessageTopic(msgSummary, { floor: msgTargetFloor, forceRefresh: true });
        }
      } else if (e.key === "Escape") {
        if (lightboxState) setLightboxState(null);
        else if (inspectedBadge) setInspectedBadge(null);
        else if (showSiteBadgesModal) setShowSiteBadgesModal(false);
        else if (inspectedUsername) setInspectedUsername(null);
        else if (showGlobalSearch) setShowGlobalSearch(false);
        else if (guestPrompt) setGuestPrompt(null);
        else if (showLogoutConfirm) setShowLogoutConfirm(false);
        else if (showQrLoginModal) {
          setShowQrLoginModal(false);
          setQrLoginInitialMode(undefined);
        } else if (showAuthModal) {
          setAuthModalStartAdding(false);
          setShowAuthModal(false);
        }
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [
    inMainApp,
    isAuthorized,
    selectedCategoryId,
    loadFeed,
    currentTab,
    activeSummary,
    openTopic,
    targetFloor,
    msgSummary,
    openMessageTopic,
    msgTargetFloor,
    homeReaderHistory.length,
    msgReaderHistory.length,
    goBackInReaderHistory,
    lightboxState,
    showSiteBadgesModal,
    inspectedBadge,
    inspectedUsername,
    update.prompt,
    closeUpdate,
    showGlobalSearch,
    guestPrompt,
    showLogoutConfirm,
    showQrLoginModal,
    showAuthModal,
    isWideLayout,
    toggleFeedCollapse,
    platform,
    requestGuestLogin,
  ]);

  const handlePrimaryWebLogin = useCallback(async () => {
    if (browserAuthWatchdogRef.current) {
      clearTimeout(browserAuthWatchdogRef.current);
      browserAuthWatchdogRef.current = null;
    }
    setGuestPrompt(null);
    setAuthModalStartAdding(false);
    setShowAuthModal(false);
    setAuthStatusHint(null);
    try {
      const ok = await siteVerify(true, isDark);
      if (ok) {
        await syncFromRustState();
        fetchSiteCategories(true)
          .then(setCategories)
          .catch(() => {});
      } else {
        setAuthStatusHint("登录尚未完成，请重试。");
      }
    } catch {
      setAuthStatusHint("暂时无法打开登录，请重试或使用浏览器授权。");
    }
  }, [isDark, syncFromRustState]);

  const handleAddAccountWebLogin = useCallback(async () => {
    setGuestPrompt(null);
    setAuthModalStartAdding(false);
    setShowAuthModal(false);
    setAuthStatusHint(null);
    try {
      const ok = await addAccountWebLoginInRust(isDark);
      if (ok) {
        setActiveSummary(null);
        setActiveDetail(null);
        setMsgSummary(null);
        setMsgDetail(null);
        await syncFromRustState();
        fetchSiteCategories(true)
          .then(setCategories)
          .catch(() => {});
      }
    } catch {
      setGlobalNotice("添加账号未完成，请重试");
    }
  }, [isDark, syncFromRustState]);

  const handleBrowserOneClickAuth = useCallback(async () => {
    setGuestPrompt(null);
    if (inMainApp) {
      setAuthModalStartAdding(true);
      setShowAuthModal(true);
    }
    if (browserAuthWatchdogRef.current) {
      clearTimeout(browserAuthWatchdogRef.current);
      browserAuthWatchdogRef.current = null;
    }
    try {
      await startBrowserOneClickAuth();
      setAuthStatusHint("已在系统浏览器打开授权页，点击「授权」后返回即可");
      setGlobalNotice("已在系统浏览器打开授权页");
      browserAuthWatchdogRef.current = setTimeout(() => {
        browserAuthWatchdogRef.current = null;
        diagLog(
          "WARN",
          "AUTH",
          "BrowserAuth",
          "超过 45 秒未收到浏览器授权返回（浏览器可能未放行打开应用弹窗）",
          "请在浏览器弹窗点击「打开 Lindo」，或改用「使用 Linuxdo 登录」"
        );
        setAuthStatusHint("未收到浏览器返回，请重试或换一种方式登录");
      }, 45000);
    } catch (err: any) {
      setAuthStatusHint(err?.message || "无法打开系统浏览器授权");
      setAuthModalStartAdding(true);
      setShowAuthModal(true);
    }
  }, [inMainApp]);

  const handleSwitchSavedAccount = useCallback(
    async (uname: string) => {
      setSwitchingBusy(true);
      try {
        const ok = await switchSavedAccount(uname);
        if (ok) {
          await syncFromRustState();
          fetchSiteCategories(true)
            .then(setCategories)
            .catch(() => {});
          setGlobalNotice(`已切换至 @${uname}`);
        } else if (isAuthorized) {
          setAuthStatusHint(`@${uname} 登录已过期，请重新登录`);
          setAuthModalStartAdding(true);
          setShowAuthModal(true);
        } else {
          const prompt = buildExpiredSessionGuestPrompt(uname);
          requestGuestLogin(prompt.title, prompt.subtitle);
        }
      } finally {
        setSwitchingBusy(false);
      }
    },
    [isAuthorized, requestGuestLogin, syncFromRustState]
  );

  const handleTriggerVerify = useCallback(
    async (opts?: InteractiveVerifyOptions) => {
      if (connectionRecovering) return;
      const forceRefresh = Boolean(opts?.forceRefresh);
      dispatchVerification({ type: "manual", busy: true });
      try {
        const ok = await triggerInteractiveVerifyOnce({ manual: true, forceRefresh, source: opts?.source });
        if (ok) {
          dispatchVerification({ type: "verified" });
          setCfAttentionDismissed(false);
          setLastVerifiedAtMs(Date.now());
          setGlobalNotice(connectionPending ? "论坛连接已恢复" : forceRefresh ? "安全验证已刷新" : "连接验证正常");
        }
      } catch {
        setGlobalNotice("连接未完成，请重试");
      } finally {
        dispatchVerification({ type: "manual", busy: false });
      }
    },
    [connectionRecovering, connectionPending]
  );

  // Entry A: Reactive verify (SideRail shield, banners, error cards) -> NEVER force-purges healthy ticket
  const handleReactiveVerify = useCallback(() => {
    void handleTriggerVerify({ forceRefresh: false, source: "page" });
  }, [handleTriggerVerify]);

  // Settings resumes a pending challenge. An idle explicit check requests a fresh verification.
  const handleSettingsVerify = useCallback(() => {
    void handleTriggerVerify({ forceRefresh: !cfAttentionNeeded && !connectionPending, source: "settings" });
  }, [cfAttentionNeeded, connectionPending, handleTriggerVerify]);

  const handleLoadMoreTopics = useCallback(async () => {
    if (loadingMoreTopics || !hasMoreTopics || feedLoading) return;
    setLoadingMoreTopics(true);
    setLoadMoreError(null);
    const generation = feedGenerationRef.current;
    const nextPage = feedPage + 1;
    try {
      const res = await fetchTopicFeed({
        sort: currentSort,
        page: nextPage,
        categoryId: selectedCategoryId,
        tag: selectedTag,
        topPeriod,
        username: currentUser?.username,
      });
      if (generation !== feedGenerationRef.current) return;
      setTopics((prev) => {
        const existing = new Set(prev.map((t) => t.id));
        const unique = res.topics.filter((t) => !existing.has(t.id));
        return [...prev, ...unique];
      });
      setFeedPage(nextPage);
      setHasMoreTopics(res.hasMore);
    } catch (err: any) {
      if (generation !== feedGenerationRef.current) return;
      verificationBlocked.current.more = err instanceof SiteError && err.retryReadAfterVerification;
      setLoadMoreError(err?.message || "加载更多失败");
    } finally {
      if (generation === feedGenerationRef.current) setLoadingMoreTopics(false);
    }
  }, [
    loadingMoreTopics,
    hasMoreTopics,
    feedLoading,
    feedPage,
    currentSort,
    selectedCategoryId,
    selectedTag,
    topPeriod,
    currentUser?.username,
  ]);

  const updatePreferences = useCallback(
    (next: UserPreferences) => {
      const themeChanged =
        next.appearance !== preferences.appearance ||
        next.themeOption !== preferences.themeOption;
      if (themeChanged) {
        performSmoothThemeSwitch(() => {
          const nextDark =
            next.appearance === "light"
              ? false
              : next.appearance === "dark"
                ? true
                : systemPrefersDark;
          applyPaletteToDocument(nextDark, next.themeOption, next.fontScale);
          setPreferences(next);
        }, next.enableAnimations);
      } else {
        setPreferences(next);
      }
      savePreferencesToRust(next).catch(() => {});
    },
    [preferences, systemPrefersDark]
  );

  const toggleDarkPref = useCallback(() => {
    updatePreferences({
      ...preferences,
      appearance: isDark ? "light" : "dark",
    });
  }, [preferences, isDark, updatePreferences]);

  const handleStartInstallUpdate = useCallback(async () => {
    if (!updateInfo || installingUpdate) return;
    setInstallingUpdate(true);
    setUpdateError(null);
    setUpdateProgress({
      stage: "downloading",
      downloadedBytes: 0,
      totalBytes: 0,
      percent: 1,
      message: "正在下载更新...",
    });
    try {
      const result = await downloadAndInstallUpdateInRust(updateInfo);
      update.close();
      setGlobalNotice(result === "opened_dmg" ? "安装包已打开，拖入「应用程序」即可更新" : "更新包已就绪");
    } catch (err: any) {
      setUpdateError(err?.message || "更新失败，请重试");
    } finally {
      setInstallingUpdate(false);
    }
  }, [updateInfo, installingUpdate, update.close]);

  // 120fps DOM-direct resizable split divider drag (ZERO React re-renders during mousemove!)
  const splitContainerRef = useRef<HTMLDivElement | null>(null);
  const leftPaneRef = useRef<HTMLDivElement | null>(null);
  const startSplitDrag = (e: React.MouseEvent) => {
    if (e.button !== 0) return;
    e.preventDefault();
    const container = splitContainerRef.current;
    const leftEl = leftPaneRef.current;
    if (!container || !leftEl) return;
    const startX = e.clientX;
    const rect = container.getBoundingClientRect();
    let latestFraction = preferences.splitFraction;
    let engaged = false;
    let rafId = 0;

    const onMove = (ev: MouseEvent) => {
      if ((ev.buttons & 1) === 0) {
        onUp();
        return;
      }
      if (!engaged) {
        if (Math.abs(ev.clientX - startX) < 3) return;
        engaged = true;
        setIsDraggingSplit(true);
      }
      const raw = (ev.clientX - rect.left) / Math.max(1, rect.width);
      latestFraction = Math.max(0.28, Math.min(0.62, raw));
      if (rafId === 0) {
        rafId = requestAnimationFrame(() => {
          rafId = 0;
          if (leftPaneRef.current) {
            leftPaneRef.current.style.width = `${Math.round(latestFraction * 1000) / 10}%`;
          }
        });
      }
    };
    const onUp = () => {
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
      if (rafId !== 0) cancelAnimationFrame(rafId);
      if (engaged) {
        setIsDraggingSplit(false);
        updatePreferences({ ...preferences, splitFraction: latestFraction });
      }
    };
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
  };

  if (!bootstrapped) {
    return <div className="h-screen w-screen" style={{ backgroundColor: "var(--canvas)" }} />;
  }

  const activeRightSummary = currentTab === "MESSAGES" ? msgSummary : activeSummary;
  const activeRightDetail = currentTab === "MESSAGES" ? msgDetail : activeDetail;
  const activeRightLoading = currentTab === "MESSAGES" ? msgReaderLoading : readerLoading;
  const activeRightError = currentTab === "MESSAGES" ? msgReaderError : readerError;
  const activeRightFloor = currentTab === "MESSAGES" ? msgTargetFloor : targetFloor;
  const activeRightFloorHighlight =
    currentTab === "MESSAGES" ? msgTargetFloorHighlight : targetFloorHighlight;
  const activeRightNonce = currentTab === "MESSAGES" ? msgJumpNonce : targetJumpNonce;
  const activeRightOnlyOp = currentTab === "MESSAGES" ? msgOnlyOp : onlyOpFilter;

  const appendPostsToActiveReader = (
    incoming: PostItemModel[],
    position: "before" | "after" | "replace"
  ) => {
    const updater = (prev: TopicDetailModel | null) => {
      if (!prev) return prev;
      if (position === "replace") return { ...prev, posts: incoming };
      const map = new Map(prev.posts.map((p) => [p.id, p]));
      for (const item of incoming) {
        map.set(item.id, mergePostPreservingBadges(map.get(item.id), item));
      }
      return {
        ...prev,
        posts: Array.from(map.values()).sort((a, b) => a.postNumber - b.postNumber),
      };
    };
    if (currentTab === "MESSAGES") setMsgDetail(updater);
    else setActiveDetail(updater);
  };

  const updateActiveReaderDetail = (
    updater: (prev: TopicDetailModel) => TopicDetailModel
  ) => {
    if (currentTab === "MESSAGES") {
      setMsgDetail((prev) => (prev ? updater(prev) : prev));
    } else {
      setActiveDetail((prev) => (prev ? updater(prev) : prev));
    }
  };

  const removePostFromActiveReader = (postId: number) => {
    const updater = (prev: TopicDetailModel | null) => {
      if (!prev) return prev;
      return {
        ...prev,
        postsCount: Math.max(1, prev.postsCount - 1),
        posts: prev.posts.filter((p) => p.id !== postId),
      };
    };
    if (currentTab === "MESSAGES") setMsgDetail(updater);
    else setActiveDetail(updater);
  };

  const renderComposerInstance = (): React.ReactNode => {
    if (!composerReq) return null;
    const legacyKey = `reply_${composerReq.topicId}`;
    const legacy = drafts[legacyKey];
    const replyKey = legacy && (legacy.replyToPostNumber ?? null) === (composerReq.replyToPostNumber ?? null)
      ? legacyKey : `${legacyKey}_${composerReq.replyToPostNumber ?? 0}`;
    const draftKey = composerReq.draftKeyOverride ?? (composerReq.action === "NEW_TOPIC"
      ? "new_topic" : composerReq.action === "EDIT" ? `edit_${composerReq.postId}` : replyKey);
    return (
      <ComposerModal
        key={`${composerReq.action}_${composerReq.topicId || 0}_${composerReq.postId || 0}`}
        request={composerReq}
        categories={categories}
        presentation="modal"
        appendQuote={composerAppendQuote}
        exitGuardRef={composerExitGuardRef}
        localDraftKey={draftKey}
        savedDraft={drafts[draftKey]}
        onTopicEdited={(topicId, changes) => {
          const category = categories.find((c) => c.id === changes.categoryId);
          const patch = category ? { ...changes, categoryName: category.displayName, categoryColorHex: category.colorHex } : changes;
          const apply = <T extends TopicSummary | TopicDetailModel>(topic: T | null) =>
            topic?.id === topicId ? { ...topic, ...patch } : topic;
          setActiveSummary(apply);
          setMsgSummary(apply);
          setActiveDetail(apply);
          setMsgDetail(apply);
          setTopics((current) => current.map((topic) => topic.id === topicId ? { ...topic, ...patch } : topic));
        }}
        onClose={() => {
          setComposerReq(null);
          setComposerAppendQuote(null);
          syncFromRustState();
        }}
        onPublished={({ topicId, post, pendingReview }) => {
          const act = composerReq.action;
          setComposerReq(null);
          setComposerAppendQuote(null);
          syncFromRustState();
          if (pendingReview) {
            setGlobalNotice("内容已提交审核");
            return;
          }
          if (act === "NEW_TOPIC" && topicId) {
            loadFeed();
            openTopicById(topicId, "新发布话题", 1, true);
          } else if (post && topicId) {
            const update = (prev: TopicDetailModel | null) => {
              if (prev?.id !== topicId) return prev;
              const posts = new Map(prev.posts.map((p) => [p.id, p]));
              const newReply = act === "REPLY" && !prev.streamIds.includes(post.id) && !posts.has(post.id);
              posts.set(post.id, mergePostPreservingBadges(posts.get(post.id), post));
              return {
                ...prev,
                postsCount: prev.postsCount + Number(newReply),
                highestPostNumber: Math.max(prev.highestPostNumber ?? 0, post.postNumber),
                streamIds: newReply ? [...prev.streamIds, post.id] : prev.streamIds,
                posts: Array.from(posts.values()).sort((a, b) => a.postNumber - b.postNumber),
              };
            };
            if (currentTab === "MESSAGES" && msgDetail?.id === topicId) {
              setMsgDetail(update);
              setMsgTargetFloor(post.postNumber);
              setMsgJumpNonce((n) => n + 1);
            } else if (activeDetail?.id === topicId) {
              setActiveDetail(update);
              setTargetFloor(post.postNumber);
              setTargetJumpNonce((n) => n + 1);
            }
          } else if (act === "EDIT" && !post) {
            return;
          } else if (currentTab === "MESSAGES" && msgSummary) {
            openMessageTopic(msgSummary, { forceRefresh: true });
          } else if (activeSummary) {
            openTopic(activeSummary, { forceRefresh: true });
          }
        }}
        onTriggerVerify={handleReactiveVerify}
      />
    );
  };

  return (
    <div
      className="flex h-screen w-screen overflow-hidden"
      style={{ backgroundColor: "var(--canvas)", color: "var(--ink-primary)" }}
    >
      {!inMainApp ? (
        <WelcomeLoginScreen
          isDark={isDark}
          isMacos={platform === "macos"}
          statusMessage={authStatusHint}
          hasSavedAccounts={savedAccounts.length > 0}
          onPrimaryWebLogin={handlePrimaryWebLogin}
          onBrowserOneClickAuth={handleBrowserOneClickAuth}
          onOpenQrLogin={openQrLoginRedeem}
          onOpenSavedAccounts={openAccountManagement}
          onGuestBrowse={() => {
            setIsGuestMode(true);
            setAuthStatusHint(null);
          }}
          onToggleDark={toggleDarkPref}
        />
      ) : (
        <>
          {/* 左侧导航栏 */}
          <SideRail
            currentTab={currentTab}
            isDark={isDark}
            unreadCount={unreadCount}
            hasUpdate={Boolean(updateInfo?.hasUpdate)}
            isMacos={platform === "macos"}
            showFeedToggle={isWideLayout && (currentTab === "HOME" || currentTab === "MESSAGES")}
            feedCollapsed={feedCollapsed}
            cfAttentionNeeded={cfAttentionNeeded && !cfAttentionDismissed}
            connectionPending={connectionPending}
            verifyActive={verifyActive}
            onToggleFeedCollapse={toggleFeedCollapse}
            onTriggerVerify={() => { void handleTriggerVerify({ source: "sidebar" }); }}
            onSelectTab={(tab) => {
              if (tab === currentTab) {
                setNarrowShowingReader(false);
                if (feedCollapsed) setFeedCollapsed(false);
                return;
              }
              setCurrentTab(tab);
              setNarrowShowingReader(false);
            }}
            onPublishClick={() => {
              if (!isAuthorized) {
                requestGuestLogin(
                  "登录后发布话题",
                  "登录 LINUX DO 账号，与佬友分享你的发现与见解"
                );
              } else {
                launchOrAppendComposer({
                  action: "NEW_TOPIC",
                  initialCategoryId: selectedCategoryId ?? undefined,
                });
              }
            }}
            onToggleTheme={toggleDarkPref}
          />

          {/* 右侧主内容分栏容器 */}
          <div ref={splitContainerRef} className="relative flex flex-1 min-w-0 h-full">
            {currentTab === "DISCOVER" ? (
              <DiscoverView
                categories={categories}
                isAuthorized={isAuthorized}
                onSelectTopic={(t) => {
                  setCurrentTab("HOME");
                  openTopic(t);
                }}
                onSelectCategoryInHome={(catId) => {
                  const decision = resolveRestrictedCategorySelection(catId, categories, isAuthorized);
                  if (decision.blocked) {
                    requestGuestLogin(decision.prompt.title, decision.prompt.subtitle);
                    return;
                  }
                  setSelectedCategoryId(catId);
                  setCurrentTab("HOME");
                }}
                onSelectTagInHome={(tg) => {
                  setSelectedTag(tg);
                  setCurrentTab("HOME");
                }}
                onOpenGlobalSearch={() => setShowGlobalSearch(true)}
                onOpenPortalUrl={(url, requiresAuth, gTitle, gSub) => {
                  if (requiresAuth && !isAuthorized) {
                    requestGuestLogin(gTitle, gSub);
                  } else if (/^https?:\/\/linux\.do\/badges\/?$/i.test(url.trim())) {
                    setShowSiteBadgesModal(true);
                  } else if (/^https?:\/\/connect\.linux\.do\/?$/i.test(url.trim()) && currentUser) {
                    setShowConnectModal(true);
                  } else if (url.includes("linux.do")) {
                    openInAppForumPanel(url, isDark).catch(() => openUrl(url));
                  } else {
                    openUrl(url);
                  }
                }}
              />
            ) : currentTab === "PROFILE" ? (
              <ProfileSettingsView
                isAuthorized={isAuthorized}
                isDark={isDark}
                currentUser={currentUser}
                savedAccounts={savedAccounts}
                preferences={preferences}
                readFloors={readFloors}
                drafts={drafts}
                knownTopics={topics}
                onUpdatePreferences={updatePreferences}
                onToggleDark={toggleDarkPref}
                onRequestLogin={() =>
                  requestGuestLogin(
                    PROFILE_GUEST_LOGIN_PROMPT.title,
                    PROFILE_GUEST_LOGIN_PROMPT.subtitle
                  )
                }
                onOpenAuthSheet={openAccountManagement}
                onOpenQrLoginSheet={() => {
                  setQrLoginInitialMode(isAuthorized ? "generate" : "redeem");
                  setShowQrLoginModal(true);
                }}
                onRequestGuestLogin={requestGuestLogin}
                onOpenInAppUrl={(url) => {
                  if (/^https?:\/\/connect\.linux\.do\/?$/i.test(url.trim()) && currentUser) {
                    setShowConnectModal(true);
                  } else {
                    openInAppForumPanel(url, isDark).catch(() => openUrl(url));
                  }
                }}
                onRequestLogout={() => setShowLogoutConfirm(true)}
                onOpenTopicById={(tid, title, floor) => {
                  openTopicById(tid, title, floor, true);
                }}
                onResumeComposerDraft={(req) => launchOrAppendComposer(req)}
                onInspectUser={(uname) => setInspectedUsername(uname)}
                onInspectBadge={(b) => setInspectedBadge(b)}
                onHistoryCleared={() => setReadFloors({})}
                onDraftsChanged={() => syncFromRustState()}
                onProfileUpdated={() => syncFromRustState()}
                updateInfo={updateInfo}
                checkingUpdate={update.checking}
                onCheckUpdate={() => {
                  setUpdateError(null);
                  void update.checkManually();
                }}
                onOpenMessages={() => setCurrentTab("MESSAGES")}
                echStatus={echStatus}
                onRefreshEchStatus={() => {
                  getEchStatus().then(setEchStatus).catch(() => {});
                }}
                cfAttentionNeeded={cfAttentionNeeded}
                connectionRecovering={connectionRecovering}
                connectionPending={connectionPending}
                verifyActive={verifyActive}
                lastVerifiedAtMs={lastVerifiedAtMs}
                onTriggerVerify={handleSettingsVerify}
              />
            ) : isWideLayout ? (
              <>
                {/* 宽屏左栏：首页话题流 或 消息中心（收起时锁定内部像素宽度 + GPU 位移，零文字重排、144Hz 满帧丝滑） */}
                <div
                  ref={leftPaneRef}
                  className={`relative h-full shrink-0 overflow-hidden ${
                    feedCollapsed ? "pointer-events-none" : ""
                  }`}
                  style={{
                    width: feedCollapsed
                      ? "0px"
                      : `${Math.round(preferences.splitFraction * 1000) / 10}%`,
                    opacity: feedCollapsed ? 0 : 1,
                    willChange: isDraggingSplit ? "auto" : "width, opacity",
                    transition: isDraggingSplit
                      ? "none"
                      : "width 205ms cubic-bezier(0.22, 1, 0.36, 1), opacity 165ms ease-out",
                  }}
                >
                  <div
                    className="relative h-full"
                    style={
                      isDraggingSplit
                        ? { width: "100%", minWidth: 320 }
                        : {
                            width: `${Math.max(
                              320,
                              Math.round(
                                (windowWidth - (platform === "macos" ? 76 : 72)) *
                                  preferences.splitFraction
                              )
                            )}px`,
                            transform: feedCollapsed
                              ? "translate3d(-24px, 0, 0)"
                              : "translate3d(0, 0, 0)",
                            transition: "transform 205ms cubic-bezier(0.22, 1, 0.36, 1)",
                            contain: "layout paint",
                          }
                    }
                  >
                  {currentTab === "MESSAGES" ? (
                    <MessagesView
                      key={currentUser?.username ?? "guest"}
                      isAuthorized={isAuthorized}
                      isDark={isDark}
                      currentUser={currentUser}
                      selectedTopicId={msgSummary?.id ?? null}
                      pmRefreshNonce={pmRefreshNonce}
                      initialComposeRecipient={pendingPmRecipient}
                      onConsumeInitialRecipient={() => setPendingPmRecipient(null)}
                      onOpenNotificationTopic={(tid, title, postNum) => {
                        setMsgReaderHistory([]);
                        openMessageTopicById(tid, title, postNum, true);
                      }}
                      onUnreadCleared={() => {
                        setUnreadCount(0);
                        setUnreadBadgeInRust(0).catch(() => {});
                      }}
                      onDecrementUnread={handleDecrementUnread}
                      onSyncUnreadCount={handleSyncUnreadCount}
                      onInspectUser={(uname) => setInspectedUsername(uname)}
                      onInspectBadge={(b) => setInspectedBadge(b)}
                      onOpenAuth={() =>
                        requestGuestLogin(
                          MESSAGES_GUEST_LOGIN_PROMPT.title,
                          MESSAGES_GUEST_LOGIN_PROMPT.subtitle
                        )
                      }
                      onOpenChat={() => {
                        openInAppForumPanel("https://linux.do/chat", isDark).catch(() =>
                          openUrl("https://linux.do/chat")
                        );
                      }}
                      onTriggerVerify={handleReactiveVerify}
                    />
                  ) : (
                    <HomeFeedPane
                      topics={topics}
                      categories={categories}
                      currentSort={currentSort}
                      topPeriod={topPeriod}
                      selectedCategoryId={selectedCategoryId}
                      selectedTag={selectedTag}
                      selectedTopicId={activeSummary?.id ?? null}
                      isAuthorized={isAuthorized}
                      currentUser={currentUser}
                      unreadNotificationCount={unreadCount}
                      pendingNewCount={pendingNewCount}
                      isLoading={feedLoading}
                      isLoadingMore={loadingMoreTopics}
                      hasMore={hasMoreTopics}
                      errorMessage={feedError}
                      loadMoreError={loadMoreError}
                      isCompact={preferences.compactFeed}
                      defaultSearchExpanded={preferences.defaultSearchExpanded}
                      readFloors={readFloors}
                      onSelectSort={setCurrentSort}
                      onSelectTopPeriod={setTopPeriod}
                      onSelectCategory={handleSelectHomeCategory}
                      onSelectTag={setSelectedTag}
                      onSelectTopic={(t, resumeFloor) => {
                        setHomeReaderHistory([]);
                        openTopic(t, { floor: resumeFloor ?? null });
                      }}
                      onRefresh={() => {
                        markManualRetryIntent();
                        loadFeed();
                      }}
                      onApplyPendingNew={applyPendingNewTopicsNow}
                      onLoadMore={handleLoadMoreTopics}
                      onToggleCompact={() =>
                        updatePreferences({
                          ...preferences,
                          compactFeed: !preferences.compactFeed,
                        })
                      }
                      onOpenSearch={() => setShowGlobalSearch(true)}
                      onOpenNotifications={() => setCurrentTab("MESSAGES")}
                      onOpenAuth={() => requestGuestLogin()}
                      onTriggerVerify={handleReactiveVerify}
                    />
                  )}
                  </div>
                </div>

                {/* 分栏拖动热区，双击恢复默认比例 */}
                {!feedCollapsed && (
                  <div className="relative z-30 h-full w-0 shrink-0 select-none">
                    <div
                      role="separator"
                      aria-orientation="vertical"
                      aria-label="左右拖动调整栏宽，双击恢复默认比例"
                      title="左右拖动调整栏宽 · 双击恢复默认"
                      onMouseDown={startSplitDrag}
                      onDoubleClick={() =>
                        updatePreferences({ ...preferences, splitFraction: 0.42 })
                      }
                      className="group absolute inset-y-0 -left-[6px] w-[12px] cursor-col-resize"
                    >
                      <div
                        className={`pointer-events-none mx-auto h-full w-[2px] transition-opacity duration-150 ${
                          isDraggingSplit
                            ? "opacity-100"
                            : "opacity-0 group-hover:opacity-90"
                        }`}
                        style={{ backgroundColor: "var(--brand-gold)" }}
                      />
                      <div
                        className={`absolute left-1/2 -translate-x-1/2 -translate-y-1/2 flex h-[38px] w-[18px] items-center justify-center rounded-full shadow-md transition-[opacity,border-color,background-color] duration-150 ${
                          isDraggingSplit
                            ? "pointer-events-auto opacity-100"
                            : "pointer-events-none opacity-0 group-hover:pointer-events-auto group-hover:opacity-100"
                        }`}
                        style={{
                          top: "50%",
                          backgroundColor: "var(--card-surface)",
                          border: isDraggingSplit
                            ? "1px solid var(--brand-gold)"
                            : "1px solid color-mix(in srgb, var(--brand-gold) 45%, var(--hairline))",
                          color: isDraggingSplit ? "var(--brand-gold)" : "var(--ink-secondary)",
                          boxShadow: isDraggingSplit
                            ? "0 0 0 2px color-mix(in srgb, var(--brand-gold) 22%, transparent), 0 4px 12px rgba(0, 0, 0, 0.28)"
                            : "0 2px 8px rgba(0, 0, 0, 0.22)",
                        }}
                      >
                        <svg width="12" height="16" viewBox="0 0 12 16" fill="none" aria-hidden="true">
                          <path
                            d="M4.2 5.2L1.6 8L4.2 10.8M7.8 5.2L10.4 8L7.8 10.8"
                            stroke="currentColor"
                            strokeWidth="1.45"
                            strokeLinecap="round"
                            strokeLinejoin="round"
                          />
                          <path
                            d="M6 4.2V11.8"
                            stroke="currentColor"
                            strokeWidth="1.35"
                            strokeLinecap="round"
                            opacity="0.65"
                          />
                        </svg>
                      </div>
                    </div>
                  </div>
                )}

                {/* 宽屏右栏：上方可滚动阅读器 + 下方可拖拽半屏回复抽屉（上下同步联动、零遮挡） */}
                <div className="relative flex flex-1 min-w-0 h-full flex-col">
                  <div className="relative flex flex-1 min-h-0 flex-col">
                    <TopicReaderPane
                      summary={activeRightSummary}
                      detail={activeRightDetail}
                      isLoading={activeRightLoading}
                      isFilterSwitching={
                        currentTab === "MESSAGES" ? msgFilterLoading : readerFilterLoading
                      }
                      filterSettledNonce={
                        currentTab === "MESSAGES" ? msgFilterSettledNonce : filterSettledNonce
                      }
                      errorMessage={activeRightError}
                      targetFloor={activeRightFloor}
                      highlightTargetFloor={activeRightFloorHighlight}
                      targetJumpNonce={activeRightNonce}
                      onlyOpFilter={activeRightOnlyOp}
                      summaryFilter={
                        currentTab === "MESSAGES" ? msgSummaryFilter : summaryFilter
                      }
                      isAuthorized={isAuthorized}
                      currentUser={currentUser}
                      confirmedReadFloor={Math.max(
                        activeRightDetail?.lastReadPostNumber ?? 0,
                        activeRightSummary ? readFloors[String(activeRightSummary.id)] ?? 0 : 0
                      )}
                      showBackButton={false}
                      canGoBackHistory={
                        currentTab === "MESSAGES"
                          ? msgReaderHistory.length > 0
                          : homeReaderHistory.length > 0
                      }
                      previousTopicTitle={
                        currentTab === "MESSAGES"
                          ? msgReaderHistory[msgReaderHistory.length - 1]?.summary.title ?? null
                          : homeReaderHistory[homeReaderHistory.length - 1]?.summary.title ?? null
                      }
                      onGoBackHistory={goBackInReaderHistory}
                      emptyPlaceholderText={
                        currentTab === "MESSAGES"
                          ? "选择左侧消息开始阅读"
                          : "选择左侧话题开始阅读"
                      }
                      onBack={() => {}}
                      onRefresh={(opts) => {
                        markManualRetryIntent();
                        const useInPlace = opts?.inPlace ?? true;
                        if (currentTab === "MESSAGES" && msgSummary) {
                          return openMessageTopic(msgSummary, {
                            floor: opts?.currentFloor ?? msgTargetFloor,
                            onlyOp: msgOnlyOp,
                            summary: msgSummaryFilter,
                            forceRefresh: true,
                            inPlace: useInPlace,
                          });
                        } else if (activeSummary) {
                          return openTopic(activeSummary, {
                            floor: opts?.currentFloor ?? targetFloor,
                            onlyOp: onlyOpFilter,
                            summary: summaryFilter,
                            forceRefresh: true,
                            inPlace: useInPlace,
                          });
                        }
                      }}
                      onToggleOnlyOp={() => {
                        if (currentTab === "MESSAGES" && msgSummary) {
                          openMessageTopic(msgSummary, {
                            onlyOp: !msgOnlyOp,
                            summary: false,
                            filterSwitch: true,
                          });
                        } else if (activeSummary) {
                          openTopic(activeSummary, {
                            onlyOp: !onlyOpFilter,
                            summary: false,
                            filterSwitch: true,
                          });
                        }
                      }}
                      onToggleSummaryFilter={() => {
                        if (currentTab === "MESSAGES" && msgSummary) {
                          openMessageTopic(msgSummary, {
                            onlyOp: false,
                            summary: !msgSummaryFilter,
                            filterSwitch: true,
                          });
                        } else if (activeSummary) {
                          openTopic(activeSummary, {
                            onlyOp: false,
                            summary: !summaryFilter,
                            filterSwitch: true,
                          });
                        }
                      }}
                      onAppendPosts={appendPostsToActiveReader}
                      onUpdateDetail={updateActiveReaderDetail}
                      onRemovePost={removePostFromActiveReader}
                      onOpenComposer={(req) => launchOrAppendComposer(req)}
                      onInspectUser={(uname) => setInspectedUsername(uname)}
                      onInspectBadge={(b) => setInspectedBadge(b)}
                      onOpenImageLightbox={(src, gallery) =>
                        setLightboxState({
                          src,
                          images: gallery?.images,
                          index: gallery?.index,
                        })
                      }
                      onNavigateTopicLink={navigateTopicLinkInReader}
                      onSelectCategory={handleSelectCategoryFromReader}
                      onSelectTag={handleSelectTagFromReader}
                      onOpenInAppUrl={handleOpenInAppUrl}
                      onPmArchiveChanged={() => setPmRefreshNonce((n) => n + 1)}
                      onRequestGuestLogin={requestGuestLogin}
                      onTriggerVerify={handleReactiveVerify}
                      cfAttentionNeeded={cfAttentionNeeded && !cfAttentionDismissed}
                      onDismissCfAttention={() => setCfAttentionDismissed(true)}
                      onFloorSeen={handleFloorSeen}
                    />
                  </div>
                </div>
              </>
            ) : currentTab === "MESSAGES" ? (
              <MessagesView
                key={currentUser?.username ?? "guest"}
                isAuthorized={isAuthorized}
                isDark={isDark}
                currentUser={currentUser}
                selectedTopicId={null}
                pmRefreshNonce={pmRefreshNonce}
                initialComposeRecipient={pendingPmRecipient}
                onConsumeInitialRecipient={() => setPendingPmRecipient(null)}
                onOpenNotificationTopic={(tid, title, postNum) => {
                  openTopicById(tid, title, postNum, true);
                }}
                onUnreadCleared={() => {
                  setUnreadCount(0);
                  setUnreadBadgeInRust(0).catch(() => {});
                }}
                onDecrementUnread={handleDecrementUnread}
                onSyncUnreadCount={handleSyncUnreadCount}
                onInspectUser={(uname) => setInspectedUsername(uname)}
                onInspectBadge={(b) => setInspectedBadge(b)}
                onOpenAuth={() =>
                  requestGuestLogin(
                    MESSAGES_GUEST_LOGIN_PROMPT.title,
                    MESSAGES_GUEST_LOGIN_PROMPT.subtitle
                  )
                }
                onOpenChat={() => {
                  openInAppForumPanel("https://linux.do/chat", isDark).catch(() =>
                    openUrl("https://linux.do/chat")
                  );
                }}
                onTriggerVerify={handleReactiveVerify}
              />
            ) : narrowShowingReader && activeSummary ? (
              <TopicReaderPane
                summary={activeSummary}
                detail={activeDetail}
                isLoading={readerLoading}
                isFilterSwitching={readerFilterLoading}
                filterSettledNonce={filterSettledNonce}
                errorMessage={readerError}
                targetFloor={targetFloor}
                highlightTargetFloor={targetFloorHighlight}
                targetJumpNonce={targetJumpNonce}
                onlyOpFilter={onlyOpFilter}
                summaryFilter={summaryFilter}
                isAuthorized={isAuthorized}
                currentUser={currentUser}
                confirmedReadFloor={Math.max(
                  activeDetail?.lastReadPostNumber ?? 0,
                  activeSummary ? readFloors[String(activeSummary.id)] ?? 0 : 0
                )}
                showBackButton={true}
                canGoBackHistory={homeReaderHistory.length > 0}
                previousTopicTitle={
                  homeReaderHistory[homeReaderHistory.length - 1]?.summary.title ?? null
                }
                onGoBackHistory={goBackInReaderHistory}
                onBack={() => setNarrowShowingReader(false)}
                onRefresh={(opts) => {
                  markManualRetryIntent();
                  return openTopic(activeSummary, {
                    floor: opts?.currentFloor ?? targetFloor,
                    onlyOp: onlyOpFilter,
                    summary: summaryFilter,
                    forceRefresh: true,
                    inPlace: opts?.inPlace ?? true,
                  });
                }}
                onToggleOnlyOp={() =>
                  openTopic(activeSummary, {
                    onlyOp: !onlyOpFilter,
                    summary: false,
                    filterSwitch: true,
                  })
                }
                onToggleSummaryFilter={() =>
                  openTopic(activeSummary, {
                    onlyOp: false,
                    summary: !summaryFilter,
                    filterSwitch: true,
                  })
                }
                onAppendPosts={(incoming, position) => {
                  setActiveDetail((prev) => {
                    if (!prev) return prev;
                    if (position === "replace") return { ...prev, posts: incoming };
                    const map = new Map(prev.posts.map((p) => [p.id, p]));
                    for (const item of incoming) {
                      map.set(item.id, mergePostPreservingBadges(map.get(item.id), item));
                    }
                    return {
                      ...prev,
                      posts: Array.from(map.values()).sort(
                        (a, b) => a.postNumber - b.postNumber
                      ),
                    };
                  });
                }}
                onUpdateDetail={(updater) => {
                  setActiveDetail((prev) => (prev ? updater(prev) : prev));
                }}
                onRemovePost={(postId) => {
                  setActiveDetail((prev) =>
                    prev
                      ? {
                          ...prev,
                          postsCount: Math.max(1, prev.postsCount - 1),
                          posts: prev.posts.filter((p) => p.id !== postId),
                        }
                      : prev
                  );
                }}
                onOpenComposer={(req) => launchOrAppendComposer(req)}
                onInspectUser={(uname) => setInspectedUsername(uname)}
                onInspectBadge={(b) => setInspectedBadge(b)}
                onOpenImageLightbox={(src, gallery) =>
                  setLightboxState({
                    src,
                    images: gallery?.images,
                    index: gallery?.index,
                  })
                }
                onNavigateTopicLink={navigateTopicLinkInReader}
                onSelectCategory={handleSelectCategoryFromReader}
                onSelectTag={handleSelectTagFromReader}
                onOpenInAppUrl={handleOpenInAppUrl}
                onPmArchiveChanged={() => setPmRefreshNonce((n) => n + 1)}
                onRequestGuestLogin={requestGuestLogin}
                onTriggerVerify={handleReactiveVerify}
                cfAttentionNeeded={cfAttentionNeeded && !cfAttentionDismissed}
                onDismissCfAttention={() => setCfAttentionDismissed(true)}
                onFloorSeen={handleFloorSeen}
              />
            ) : (
              <HomeFeedPane
                topics={topics}
                categories={categories}
                currentSort={currentSort}
                topPeriod={topPeriod}
                selectedCategoryId={selectedCategoryId}
                selectedTag={selectedTag}
                selectedTopicId={activeSummary?.id ?? null}
                isAuthorized={isAuthorized}
                currentUser={currentUser}
                unreadNotificationCount={unreadCount}
                pendingNewCount={pendingNewCount}
                isLoading={feedLoading}
                isLoadingMore={loadingMoreTopics}
                hasMore={hasMoreTopics}
                errorMessage={feedError}
                loadMoreError={loadMoreError}
                isCompact={preferences.compactFeed}
                defaultSearchExpanded={preferences.defaultSearchExpanded}
                readFloors={readFloors}
                onSelectSort={setCurrentSort}
                onSelectTopPeriod={setTopPeriod}
                onSelectCategory={handleSelectHomeCategory}
                onSelectTag={setSelectedTag}
                onSelectTopic={(t, resumeFloor) => openTopic(t, { floor: resumeFloor ?? null })}
                onRefresh={() => { markManualRetryIntent(); void loadFeed(); }}
                onApplyPendingNew={applyPendingNewTopicsNow}
                onLoadMore={handleLoadMoreTopics}
                onToggleCompact={() =>
                  updatePreferences({ ...preferences, compactFeed: !preferences.compactFeed })
                }
                onOpenSearch={() => setShowGlobalSearch(true)}
                onOpenNotifications={() => setCurrentTab("MESSAGES")}
                onOpenAuth={() => requestGuestLogin()}
                onTriggerVerify={handleReactiveVerify}
              />
            )}
          </div>
        </>
      )}

      {/* 被动过期温和提醒胶囊 */}
      {inMainApp &&
        !isAuthorized &&
        expiredUsername &&
        !showAuthModal &&
        !guestPrompt &&
        !showQrLoginModal && (
        <div
          className="fixed bottom-6 left-1/2 z-40 flex -translate-x-1/2 items-center gap-2.5 rounded-full pl-4 pr-2 py-1.5 shadow-2xl"
          style={{
            backgroundColor: isDark ? "#1B202A" : "var(--card-surface)",
            border: "0.5px solid var(--hairline)",
          }}
        >
          <span className="text-[12.8px] font-medium" style={{ color: "var(--ink-primary)" }}>
            @{expiredUsername} 登录已过期，当前为访客模式
          </span>
          <button
            type="button"
            onClick={() => {
              const prompt = buildExpiredSessionGuestPrompt(expiredUsername);
              requestGuestLogin(prompt.title, prompt.subtitle);
            }}
            className="rounded-full px-3 py-1 text-[12px] font-semibold transition-opacity hover:opacity-90 active:opacity-80 cursor-pointer"
            style={{
              backgroundColor: "var(--publish-btn-bg)",
              color: "var(--publish-btn-fg)",
            }}
          >
            重新登录
          </button>
          <button
            type="button"
            onClick={() => {
              setExpiredUsername(null);
              clearExpiredNoticeInRust().catch(() => {});
            }}
            className="lindo-interactive flex h-6 w-6 items-center justify-center rounded-full"
            style={{ color: "var(--ink-tertiary)" }}
          >
            <LinDoIcons.Close size={13.5} />
          </button>
        </div>
      )}

      {/* 游客受限操作引导弹窗 */}
      {guestPrompt && (
        <GuestLoginGuideModal
          title={guestPrompt.title}
          subtitle={guestPrompt.subtitle}
          hasSavedAccounts={savedAccounts.length > 0}
          onPrimaryWebLogin={handlePrimaryWebLogin}
          onBrowserOneClickAuth={handleBrowserOneClickAuth}
          onOpenQrLogin={openQrLoginRedeem}
          onOpenSavedAccounts={savedAccounts.length > 0 ? openAccountManagement : undefined}
          onDismiss={() => setGuestPrompt(null)}
        />
      )}

      {/* 账号管理 / 切换账号 / 添加账号弹窗 */}
      {showAuthModal && (
        <AuthModal
          isAuthorized={isAuthorized}
          isDark={isDark}
          currentUser={currentUser}
          savedAccounts={savedAccounts}
          statusHint={authStatusHint}
          initialAdding={authModalStartAdding}
          onWebLogin={handlePrimaryWebLogin}
          onAddAccountWebLogin={handleAddAccountWebLogin}
          onBrowserOneClick={handleBrowserOneClickAuth}
          onOpenQrLogin={openQrLoginRedeem}
          onOpenQrGenerate={
            isAuthorized
              ? () => {
                  setQrLoginInitialMode("generate");
                  setShowQrLoginModal(true);
                }
              : undefined
          }
          onRequestGuestLogin={(title, subtitle) => requestGuestLogin(title, subtitle)}
          onSwitchAccount={handleSwitchSavedAccount}
          onRemoveAccount={async (uname) => {
            await removeSavedAccountInRust(uname);
            await syncFromRustState();
          }}
          onLogout={() => setShowLogoutConfirm(true)}
          onClose={() => {
            setAuthModalStartAdding(false);
            setShowAuthModal(false);
          }}
        />
      )}

      {/* 跨端登录二维码弹窗 */}
      {showQrLoginModal && (
        <QrLoginModal
          isAuthorized={isAuthorized}
          isDark={isDark}
          currentUser={currentUser}
          initialMode={qrLoginInitialMode}
          onImported={async () => {
            if (browserAuthWatchdogRef.current) {
              clearTimeout(browserAuthWatchdogRef.current);
              browserAuthWatchdogRef.current = null;
            }
            await syncFromRustState();
            fetchSiteCategories(true)
              .then(setCategories)
              .catch(() => {});
          }}
          onClose={() => {
            setShowQrLoginModal(false);
            setQrLoginInitialMode(undefined);
          }}
        />
      )}

      {/* 退出当前账号确认对话框 */}
      {showLogoutConfirm && (
        <ConfirmDialog
          title="要退出当前账号吗？"
          confirmLabel="退出"
          destructive
          onCancel={() => setShowLogoutConfirm(false)}
          onConfirm={async () => {
            setShowLogoutConfirm(false);
            await logoutAccount(false);
            feedGenerationRef.current += 1;
            setPendingFeed(createEmptyPendingFeedSnapshot());
            setIsGuestMode(false);
            setTopics([]);
            setActiveSummary(null);
            setActiveDetail(null);
            setMsgSummary(null);
            setMsgDetail(null);
            setCurrentTab("HOME");
            await syncFromRustState();
          }}
        />
      )}

      {/* 全局搜索弹窗 */}
      {showGlobalSearch && (
        <GlobalSearchModal
          categories={categories}
          onSelectHit={(tid, title, floor) => {
            openTopicById(tid, title, floor, false);
          }}
          onInspectUser={(uname) => setInspectedUsername(uname)}
          onSelectTag={(tg) => {
            setSelectedTag(tg);
            setCurrentTab("HOME");
          }}
          onClose={() => setShowGlobalSearch(false)}
        />
      )}

      {/* 创作工作台（发帖 / 回复 / 编辑统一居中圆角弹窗） */}
      {composerReq && renderComposerInstance()}

      {/* 公开用户名片 */}
      {inspectedUsername && (
        <PublicUserCardModal
          username={inspectedUsername}
          isAuthorized={isAuthorized}
          currentUsername={currentUser?.username}
          onStartPm={(recipient) => {
            setPendingPmRecipient(recipient);
            setCurrentTab("MESSAGES");
          }}
          onOpenTopic={(tid, title, floor) => openTopicById(tid, title, floor, false)}
          onInspectUser={(uname) => setInspectedUsername(uname)}
          onInspectBadge={(b) => setInspectedBadge(b)}
          onOpenSiteBadges={() => setShowSiteBadgesModal(true)}
          onOpenInAppUrl={handleOpenInAppUrl}
          onRequestGuestLogin={(title, subtitle) => {
            setInspectedUsername(null);
            requestGuestLogin(title, subtitle);
          }}
          onClose={() => setInspectedUsername(null)}
        />
      )}

      {/* 原生全站徽章墙（发现页直开，不再跳 WebView） */}
      {showSiteBadgesModal && (
        <SiteBadgesModal
          onInspectBadge={(b) => setInspectedBadge(b)}
          onClose={() => setShowSiteBadgesModal(false)}
        />
      )}

      {/* 荣誉徽章详情弹窗 */}
      {inspectedBadge && (
        <BadgeDetailModal
          badge={inspectedBadge}
          isAuthorized={isAuthorized}
          currentUser={currentUser}
          onProfileUpdated={() => syncFromRustState()}
          onClose={() => setInspectedBadge(null)}
        />
      )}

      {/* 原生 Connect 保级与成长面板 */}
      {showConnectModal && currentUser && (
        <ConnectTrustLevelModal
          username={currentUser.username}
          isDark={isDark}
          onOpenWebConnect={() => {
            openInAppForumPanel("https://connect.linux.do/", isDark).catch(() =>
              openUrl("https://connect.linux.do/")
            );
          }}
          onClose={() => setShowConnectModal(false)}
        />
      )}

      {/* 客户端新版本下载升级弹窗 */}
      {update.prompt && updateInfo && (
        <DesktopUpdateModal
          info={updateInfo}
          compact={update.prompt === "automatic"}
          installing={installingUpdate}
          progress={updateProgress}
          error={updateError}
          onStartInstall={handleStartInstallUpdate}
          onClose={closeUpdate}
        />
      )}

      {/* 增强型多图大图灯箱 */}
      {lightboxState && (
        <ImageLightboxModal
          src={lightboxState.src}
          images={lightboxState.images}
          initialIndex={lightboxState.index ?? 0}
          onClose={() => setLightboxState(null)}
        />
      )}

      {/* 授权兑换或切换账号遮罩 */}
      {(authCompleting || switchingBusy) && (
        <div
          className="fixed inset-0 z-50 flex flex-col items-center justify-center"
          style={{ backgroundColor: "color-mix(in srgb, var(--canvas) 92%, transparent)" }}
        >
          <img src="/brand/lingdu-connecting.svg" alt="" className="mb-3 h-16 w-16 animate-pulse" />
          <span className="text-[13.5px] font-medium" style={{ color: "var(--ink-primary)" }}>
            {switchingBusy ? "切换中" : "登录中"}
          </span>
        </div>
      )}

      {/* 4 档渐进式安全校验轻胶囊 / 窄屏或欢迎页安全确认入口（宽屏主界面已整合至 SideRail 盾牌指示器，避免双重提示） */}
      {(!inMainApp || !isWideLayout) &&
        (verification.pillVisible ||
          (inMainApp &&
            cfAttentionNeeded &&
            !cfAttentionDismissed &&
            (Boolean(composerReq) ||
              currentTab === "DISCOVER" ||
              currentTab === "PROFILE" ||
              currentTab === "MESSAGES" ||
              !narrowShowingReader ||
              !activeSummary))) &&
        !authCompleting &&
        !switchingBusy && (
          <button
            type="button"
            onMouseDown={(e) => e.preventDefault()}
            onClick={() => {
              if (verification.pillVisible) dispatchVerification({ type: "hidePill" });
              void handleTriggerVerify({ source: "pill" });
            }}
            title="点击展开安全确认面板"
            className="lindo-interactive group fixed bottom-5 right-5 z-[45] flex items-center gap-2 rounded-full px-3.5 py-1.5 shadow-lg transition-[background-color,color,border-color,opacity] duration-150 cursor-pointer"
            style={{
              backgroundColor: "color-mix(in srgb, var(--card-surface) 92%, transparent)",
              backdropFilter: "blur(12px)",
              WebkitBackdropFilter: "blur(12px)",
              border: "0.5px solid var(--hairline)",
              color: "var(--ink-primary)",
            }}
          >
            {verification.pillVisible ? (
              <span
                className="h-2 w-2 rounded-full animate-pulse shrink-0"
                style={{ backgroundColor: "var(--brand-gold)" }}
              />
            ) : (
              <LinDoIcons.ShieldCheck
                size={13.5}
                className="shrink-0"
                style={{ color: "var(--brand-gold)" }}
              />
            )}
            <span className="text-[12px] font-medium" style={{ color: "var(--ink-primary)" }}>
              {verification.pillVisible ? "安全校验中…" : "连接需安全确认 · 去验证"}
            </span>
            <LinDoIcons.ChevronRight
              size={12.5}
              className="opacity-65 group-hover:opacity-100 transition-opacity"
              style={{ color: "var(--ink-tertiary)" }}
            />
          </button>
        )}

      {/* 全局通知 Toast */}
      {globalNotice && (
        <div
          className="pointer-events-none fixed bottom-7 left-1/2 z-50 -translate-x-1/2 rounded-full px-4 py-1.5 text-[12.8px] font-medium shadow-xl"
          style={{
            backgroundColor: "var(--subtle-surface)",
            border: "0.5px solid var(--hairline)",
            color: "var(--ink-primary)",
          }}
        >
          {globalNotice}
        </div>
      )}
    </div>
  );
};
