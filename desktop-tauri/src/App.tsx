import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { listen } from "@tauri-apps/api/event";
import { openUrl } from "@tauri-apps/plugin-opener";
import { X } from "@phosphor-icons/react";
import type {
  CategoryMeta,
  ComposerLaunchRequest,
  DesktopUpdateInfo,
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
  checkAppUpdateInRust,
  clearExpiredNoticeInRust,
  downloadAndInstallUpdateInRust,
  fetchSiteCategories,
  fetchTopicDetail,
  fetchTopicFeed,
  getCachedCategories,
  importManualCredentialInRust,
  logoutAccount,
  openInAppForumPanel,
  recordLocalTopicVisit,
  recordReadFloorInRust,
  refreshCurrentUserSession,
  removeSavedAccountInRust,
  savePreferencesToRust,
  setUnreadBadgeInRust,
  siteVerify,
  startBrowserOneClickAuth,
  switchSavedAccount,
} from "./lib/api";
import { applyPaletteToDocument } from "./lib/palette";
import { diagLog, installGlobalDiagHooks } from "./lib/diagnostics";
import { installSmoothWheelAndScrollOptimizer } from "./lib/smoothWheel";
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
  WelcomeLoginScreen,
} from "./components/SecondaryScreens";

const DEFAULT_PREFS: UserPreferences = {
  appearance: "dark",
  themeOption: "OFFICIAL_GOLD",
  fontScale: 1.0,
  splitFraction: 0.42,
  compactFeed: false,
  defaultSearchExpanded: true,
  enableAnimations: true,
};

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
  const [feedError, setFeedError] = useState<string | null>(null);
  const [loadMoreError, setLoadMoreError] = useState<string | null>(null);
  const [pendingNewTopics, setPendingNewTopics] = useState<TopicSummary[] | null>(null);

  // Home topic reader state
  const [activeSummary, setActiveSummary] = useState<TopicSummary | null>(null);
  const [activeDetail, setActiveDetail] = useState<TopicDetailModel | null>(null);
  const [readerLoading, setReaderLoading] = useState(false);
  const [readerError, setReaderError] = useState<string | null>(null);
  const [targetFloor, setTargetFloor] = useState<number | null>(null);
  const [targetJumpNonce, setTargetJumpNonce] = useState(0);
  const [onlyOpFilter, setOnlyOpFilter] = useState(false);

  // Messages split-pane reader state (independent from Home reader!)
  const [msgSummary, setMsgSummary] = useState<TopicSummary | null>(null);
  const [msgDetail, setMsgDetail] = useState<TopicDetailModel | null>(null);
  const [msgReaderLoading, setMsgReaderLoading] = useState(false);
  const [msgReaderError, setMsgReaderError] = useState<string | null>(null);
  const [msgTargetFloor, setMsgTargetFloor] = useState<number | null>(null);
  const [msgJumpNonce, setMsgJumpNonce] = useState(0);
  const [msgOnlyOp, setMsgOnlyOp] = useState(false);
  const [pendingPmRecipient, setPendingPmRecipient] = useState<string | null>(null);

  // Overlays & modals
  const [showAuthModal, setShowAuthModal] = useState(false);
  const [authModalStartAdding, setAuthModalStartAdding] = useState(false);
  const [authStatusHint, setAuthStatusHint] = useState<string | null>(null);
  const [authCompleting, setAuthCompleting] = useState(false);
  const [switchingBusy, setSwitchingBusy] = useState(false);
  const [showLogoutConfirm, setShowLogoutConfirm] = useState(false);
  const [guestPrompt, setGuestPrompt] = useState<{ title: string; subtitle: string } | null>(null);
  const [showGlobalSearch, setShowGlobalSearch] = useState(false);
  const [composerReq, setComposerReq] = useState<ComposerLaunchRequest | null>(null);
  const [composerForceModal, setComposerForceModal] = useState(false);
  const [composerAppendQuote, setComposerAppendQuote] = useState<{
    text: string;
    nonce: number;
  } | null>(null);
  const [inspectedUsername, setInspectedUsername] = useState<string | null>(null);
  const [inspectedBadge, setInspectedBadge] = useState<GrantedBadge | null>(null);
  const [showConnectModal, setShowConnectModal] = useState(false);
  const [pmRefreshNonce, setPmRefreshNonce] = useState(0);
  const [lightboxSrc, setLightboxSrc] = useState<string | null>(null);
  const [globalNotice, setGlobalNotice] = useState<string | null>(null);

  // Smooth Check-for-Updates & One-Click Upgrade state
  const [updateInfo, setUpdateInfo] = useState<DesktopUpdateInfo | null>(null);
  const [checkingUpdate, setCheckingUpdate] = useState(false);
  const [showUpdateModal, setShowUpdateModal] = useState(false);
  const [installingUpdate, setInstallingUpdate] = useState(false);
  const [updateProgress, setUpdateProgress] = useState<UpdateProgressPayload | null>(null);
  const [updateError, setUpdateError] = useState<string | null>(null);

  const isDark = useMemo(() => {
    if (preferences.appearance === "light") return false;
    if (preferences.appearance === "dark") return true;
    return systemPrefersDark;
  }, [preferences.appearance, systemPrefersDark]);

  const isWideLayout = windowWidth >= 860;
  const inMainApp = isAuthorized || isGuestMode;

  useEffect(() => {
    applyPaletteToDocument(isDark, preferences.themeOption, preferences.fontScale);
  }, [isDark, preferences.themeOption, preferences.fontScale]);

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

  const syncFromRustState = useCallback(async () => {
    const boot = await bootstrapApp();
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
      const sort = opts?.sort ?? currentSort;
      const period = opts?.period ?? topPeriod;
      const catId = opts?.categoryId !== undefined ? opts.categoryId : selectedCategoryId;
      const tg = opts?.tag !== undefined ? opts.tag : selectedTag;

      if (!opts?.silent) {
        setFeedLoading(true);
      }
      setFeedError(null);
      setLoadMoreError(null);
      setPendingNewTopics(null);

      try {
        const res = await fetchTopicFeed({
          sort,
          page: 0,
          categoryId: catId,
          tag: tg,
          topPeriod: period,
          username: currentUser?.username,
        });
        setTopics(res.topics);
        setFeedPage(0);
        setHasMoreTopics(res.hasMore);
      } catch (err: any) {
        setFeedError(err?.message || "加载动态失败，请重试");
      } finally {
        setFeedLoading(false);
      }
    },
    [currentSort, topPeriod, selectedCategoryId, selectedTag, currentUser?.username]
  );

  const launchOrAppendComposer = useCallback((req: ComposerLaunchRequest) => {
    setComposerReq((prev) => {
      if (
        prev &&
        prev.action === "REPLY" &&
        req.action === "REPLY" &&
        prev.topicId &&
        prev.topicId === req.topicId
      ) {
        if (req.initialRaw) {
          setComposerAppendQuote({
            text: req.initialRaw,
            nonce: Date.now() + Math.random(),
          });
        }
        return {
          ...prev,
          replyToPostNumber: req.replyToPostNumber ?? prev.replyToPostNumber,
          replyToUsername: req.replyToUsername ?? prev.replyToUsername,
          replyToSnippet: req.replyToSnippet ?? prev.replyToSnippet,
        };
      }
      setComposerForceModal(false);
      setComposerAppendQuote(null);
      return req;
    });
  }, []);

  const openTopic = useCallback(
    async (
      topic: TopicSummary,
      opts?: { floor?: number | null; onlyOp?: boolean; forceRefresh?: boolean; trackVisit?: boolean }
    ) => {
      setComposerReq((prev) =>
        prev && prev.action !== "NEW_TOPIC" && prev.topicId && prev.topicId !== topic.id
          ? null
          : prev
      );
      const nextOnlyOp = opts?.onlyOp ?? false;
      const desiredFloor = opts?.floor ?? null;
      const shouldTrack = opts?.trackVisit !== false;
      setActiveSummary(topic);
      setOnlyOpFilter(nextOnlyOp);
      setTargetFloor(desiredFloor);
      setNarrowShowingReader(true);
      setReaderError(null);
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

      if (opts?.forceRefresh || activeDetail?.id !== topic.id) {
        setActiveDetail(null);
      }
      setReaderLoading(true);

      try {
        const detail = await fetchTopicDetail(topic.id, {
          targetPostNumber: desiredFloor,
          onlyOpUsername: nextOnlyOp ? topic.originalPoster.username : null,
          trackVisit: shouldTrack,
        });
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
        setActiveDetail(detail);
        setTargetJumpNonce((n) => n + 1);
      } catch (err: any) {
        setReaderError(err?.message || "帖子加载失败，请重试");
      } finally {
        setReaderLoading(false);
      }
    },
    [activeDetail?.id]
  );

  const openMessageTopic = useCallback(
    async (
      topic: TopicSummary,
      opts?: { floor?: number | null; onlyOp?: boolean; forceRefresh?: boolean }
    ) => {
      setComposerReq((prev) =>
        prev && prev.action !== "NEW_TOPIC" && prev.topicId && prev.topicId !== topic.id
          ? null
          : prev
      );
      const nextOnlyOp = opts?.onlyOp ?? false;
      const desiredFloor = opts?.floor ?? null;
      setMsgSummary(topic);
      setMsgOnlyOp(nextOnlyOp);
      setMsgTargetFloor(desiredFloor);
      setNarrowShowingReader(true);
      setMsgReaderError(null);
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

      if (opts?.forceRefresh || msgDetail?.id !== topic.id) {
        setMsgDetail(null);
      }
      setMsgReaderLoading(true);

      try {
        const detail = await fetchTopicDetail(topic.id, {
          targetPostNumber: desiredFloor,
          onlyOpUsername: nextOnlyOp ? topic.originalPoster.username : null,
          trackVisit: true,
        });
        const opPost = detail.posts.find((p) => p.postNumber === 1) ?? detail.posts[0];
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
        setMsgDetail(detail);
        setMsgJumpNonce((n) => n + 1);
      } catch (err: any) {
        setMsgReaderError(err?.message || "消息内容加载失败，请重试");
      } finally {
        setMsgReaderLoading(false);
      }
    },
    [msgDetail?.id]
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
      openTopic(summary, { floor: postNumber ?? null, forceRefresh });
    },
    [makeSummaryById, openTopic]
  );

  const openMessageTopicById = useCallback(
    (topicId: number, fallbackTitle: string, postNumber?: number, forceRefresh = true) => {
      const summary = makeSummaryById(topicId, fallbackTitle, postNumber);
      if (!isWideLayout) {
        setCurrentTab("HOME");
        openTopic(summary, { floor: postNumber ?? null, forceRefresh });
      } else {
        openMessageTopic(summary, { floor: postNumber ?? null, forceRefresh });
      }
    },
    [makeSummaryById, isWideLayout, openTopic, openMessageTopic]
  );

  const handleDecrementUnread = useCallback(() => {
    setUnreadCount((prev) => {
      const next = Math.max(0, prev - 1);
      setUnreadBadgeInRust(next).catch(() => {});
      return next;
    });
  }, []);

  // Initial bootstrap & Tauri event listeners (await listener registration BEFORE syncFromRustState so cold-start deep links never race)
  const initializedRef = useRef(false);
  useEffect(() => {
    if (initializedRef.current) return;
    initializedRef.current = true;
    installGlobalDiagHooks("1.0.45");
    installSmoothWheelAndScrollOptimizer();

    let cancelled = false;
    const unsubs: Array<() => void> = [];

    (async () => {
      const registered = await Promise.all([
        listen("lindo://state-updated", () => {
          syncFromRustState().then((b) => {
            if (b.isAuthorized) {
              setAuthCompleting(false);
              setAuthStatusHint(null);
              setShowAuthModal(false);
              setGuestPrompt(null);
              fetchSiteCategories(true)
                .then(setCategories)
                .catch(() => {});
              loadFeed({ silent: true });
            }
          });
        }),
        listen("lindo://verified", () => {
          setFeedError(null);
          setLoadMoreError(null);
          setReaderError(null);
          setMsgReaderError(null);
          syncFromRustState().then((b) => {
            fetchSiteCategories()
              .then(setCategories)
              .catch(() => {});
            if (b.isAuthorized || isGuestMode) {
              loadFeed({ silent: true });
            }
          });
        }),
        listen<{ level?: string; category?: string; tag?: string; summary?: string; detail?: string }>(
          "lindo://diag-event",
          (ev) => {
            const p = ev.payload;
            if (p?.summary) {
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
        listen<string>("lindo://auth-notice", (ev) => {
          const msg = String(ev.payload || "").trim();
          setAuthStatusHint(msg || null);
          if (msg) setGlobalNotice(msg);
        }),
        listen<string>("lindo://tray-command", (ev) => {
          if (ev.payload === "new_topic") {
            launchOrAppendComposer({ action: "NEW_TOPIC" });
          } else if (ev.payload === "refresh") {
            loadFeed();
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

      const boot = await syncFromRustState();
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

      // Silent background check for updates 3.5s after launch
      setTimeout(() => {
        if (cancelled) return;
        checkAppUpdateInRust()
          .then((info) => {
            if (!cancelled) setUpdateInfo(info);
          })
          .catch(() => {});
      }, 3500);
    })();

    return () => {
      cancelled = true;
      unsubs.forEach((u) => u());
    };
  }, [syncFromRustState, loadFeed, isGuestMode]);

  // Load site category catalog once on entering main app (single-flight + 5-min TTL inside api.ts)
  useEffect(() => {
    if (!bootstrapped || !inMainApp) return;
    fetchSiteCategories()
      .then(setCategories)
      .catch(() => {});
  }, [bootstrapped, inMainApp, isAuthorized]);

  // Reload feed when in main app and filter/sort changes
  useEffect(() => {
    if (!bootstrapped || !inMainApp) return;
    loadFeed();
  }, [bootstrapped, inMainApp, currentSort, topPeriod, selectedCategoryId, selectedTag]);

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

  // Background poll for new topics (`N 条新动态` capsule) & unread notifications (calm 3-min cadence to avoid Cloudflare 429)
  useEffect(() => {
    if (!inMainApp) return;
    const timer = setInterval(async () => {
      if (document.hidden || currentTab !== "HOME" || currentSort !== "latest") return;
      if (feedError || readerError) return;
      try {
        const latest = await fetchTopicFeed({
          sort: "latest",
          page: 0,
          categoryId: selectedCategoryId,
          tag: selectedTag,
          background: true,
        });
        if (topics.length > 0 && latest.topics.length > 0) {
          const currentIds = new Set(
            topics.slice(0, 30).map((t) => `${t.id}:${t.highestPostNumber}`)
          );
          const changed = latest.topics.filter(
            (t) => !t.pinned && !currentIds.has(`${t.id}:${t.highestPostNumber}`)
          );
          if (changed.length > 0) {
            setPendingNewTopics(latest.topics);
          }
        }
        if (isAuthorized) {
          const me = await refreshCurrentUserSession();
          if (me.profile) setUnreadCount(me.unread);
        }
      } catch {
        // silent background check
      }
    }, 180000);
    return () => clearInterval(timer);
  }, [inMainApp, currentTab, currentSort, selectedCategoryId, selectedTag, topics, isAuthorized, feedError, readerError]);

  // Global keyboard shortcuts
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      const mod = e.metaKey || e.ctrlKey;
      if (mod && e.key.toLowerCase() === "k" && inMainApp) {
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
          setGuestPrompt({
            title: "登录后发布话题",
            subtitle: "登录 LINUX DO 账号，与佬友分享你的发现与见解",
          });
      } else if (mod && e.key.toLowerCase() === "r" && inMainApp) {
        e.preventDefault();
        loadFeed();
        if (currentTab === "HOME" && activeSummary) {
          openTopic(activeSummary, { floor: targetFloor, forceRefresh: true });
        } else if (currentTab === "MESSAGES" && msgSummary) {
          openMessageTopic(msgSummary, { floor: msgTargetFloor, forceRefresh: true });
        }
      } else if (e.key === "Escape") {
        if (lightboxSrc) setLightboxSrc(null);
        else if (inspectedBadge) setInspectedBadge(null);
        else if (inspectedUsername) setInspectedUsername(null);
        else if (showUpdateModal && !installingUpdate) setShowUpdateModal(false);
        else if (showGlobalSearch) setShowGlobalSearch(false);
        else if (guestPrompt) setGuestPrompt(null);
        else if (showLogoutConfirm) setShowLogoutConfirm(false);
        else if (showAuthModal) setShowAuthModal(false);
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
    lightboxSrc,
    inspectedBadge,
    inspectedUsername,
    showUpdateModal,
    installingUpdate,
    showGlobalSearch,
    guestPrompt,
    showLogoutConfirm,
    showAuthModal,
  ]);

  const handlePrimaryWebLogin = useCallback(async () => {
    setGuestPrompt(null);
    setShowAuthModal(false);
    setAuthStatusHint(null);
    try {
      const ok = await siteVerify(true, isDark);
      if (ok) {
        await syncFromRustState();
        fetchSiteCategories(true)
          .then(setCategories)
          .catch(() => {});
        loadFeed();
      } else {
        setAuthStatusHint("登录尚未完成，请重试。");
      }
    } catch {
      setAuthStatusHint("暂时无法打开登录，请重试或使用浏览器授权。");
    }
  }, [isDark, syncFromRustState, loadFeed]);

  const handleAddAccountWebLogin = useCallback(async () => {
    setGuestPrompt(null);
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
        loadFeed();
      }
    } catch {
      setGlobalNotice("添加账号未完成，请重试");
    }
  }, [isDark, syncFromRustState, loadFeed]);

  const handleBrowserOneClickAuth = useCallback(async () => {
    setGuestPrompt(null);
    setShowAuthModal(false);
    try {
      await startBrowserOneClickAuth();
      setAuthStatusHint("已在系统浏览器打开授权页，点击「批准」后将自动完成登录");
      setGlobalNotice("已在系统浏览器打开授权页，点击「批准」即可自动返回");
    } catch (err: any) {
      setAuthStatusHint(err?.message || "无法打开系统浏览器授权");
      setShowAuthModal(true);
    }
  }, []);

  const handleSwitchSavedAccount = useCallback(
    async (uname: string) => {
      setSwitchingBusy(true);
      try {
        const ok = await switchSavedAccount(uname);
        if (ok) {
          setActiveSummary(null);
          setActiveDetail(null);
          setMsgSummary(null);
          setMsgDetail(null);
          await syncFromRustState();
          fetchSiteCategories(true)
            .then(setCategories)
            .catch(() => {});
          loadFeed();
          setGlobalNotice(`已切换至 @${uname}`);
        } else {
          setAuthStatusHint(`@${uname} 登录凭证已过期，请重新登录`);
          setAuthModalStartAdding(true);
          setShowAuthModal(true);
        }
      } finally {
        setSwitchingBusy(false);
      }
    },
    [syncFromRustState, loadFeed]
  );

  const handleTriggerVerify = useCallback(async () => {
    try {
      const ok = await siteVerify(false, isDark);
      if (ok) {
        setFeedError(null);
        setLoadMoreError(null);
        setReaderError(null);
        setMsgReaderError(null);
        fetchSiteCategories(true)
          .then(setCategories)
          .catch(() => {});
        loadFeed();
        if (activeSummary && readerError) {
          openTopic(activeSummary, { floor: targetFloor, forceRefresh: true });
        }
        if (msgSummary && msgReaderError) {
          openMessageTopic(msgSummary, { floor: msgTargetFloor, forceRefresh: true });
        }
      }
    } catch {
      setGlobalNotice("连接未完成，请重试");
    }
  }, [
    isDark,
    loadFeed,
    activeSummary,
    readerError,
    openTopic,
    targetFloor,
    msgSummary,
    msgReaderError,
    openMessageTopic,
    msgTargetFloor,
  ]);

  const handleLoadMoreTopics = useCallback(async () => {
    if (loadingMoreTopics || !hasMoreTopics || feedLoading) return;
    setLoadingMoreTopics(true);
    setLoadMoreError(null);
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
      setTopics((prev) => {
        const existing = new Set(prev.map((t) => t.id));
        const unique = res.topics.filter((t) => !existing.has(t.id));
        return [...prev, ...unique];
      });
      setFeedPage(nextPage);
      setHasMoreTopics(res.hasMore && res.topics.length > 0);
    } catch (err: any) {
      setLoadMoreError(err?.message || "加载更多失败");
    } finally {
      setLoadingMoreTopics(false);
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

  const updatePreferences = useCallback((next: UserPreferences) => {
    setPreferences(next);
    savePreferencesToRust(next).catch(() => {});
  }, []);

  const toggleDarkPref = useCallback(() => {
    updatePreferences({
      ...preferences,
      appearance: isDark ? "light" : "dark",
    });
  }, [preferences, isDark, updatePreferences]);

  const handleFloorSeen = useCallback((tid: number, floor: number) => {
    setReadFloors((prev) => {
      const old = prev[String(tid)] ?? 0;
      if (floor <= old) return prev;
      recordReadFloorInRust(tid, floor).catch(() => {});
      return { ...prev, [String(tid)]: floor };
    });
  }, []);

  const handleManualCheckUpdate = useCallback(async () => {
    if (checkingUpdate) return;
    setCheckingUpdate(true);
    setUpdateError(null);
    try {
      const info = await checkAppUpdateInRust();
      setUpdateInfo(info);
      diagLog(
        "INFO",
        "SYSTEM",
        "GitHubUpdate",
        info.hasUpdate
          ? `发现 GitHub Release 新版本 v${info.latestVersion}`
          : `GitHub Release 检查完成，当前已是最新 v${info.currentVersion}`
      );
      if (info.hasUpdate) {
        setShowUpdateModal(true);
      } else {
        setGlobalNotice(`当前已是最新版本 v${info.currentVersion}`);
      }
    } catch (err: any) {
      diagLog("WARN", "SYSTEM", "GitHubUpdate", "检查 GitHub Release 失败", String(err?.message || err));
      setGlobalNotice(err?.message || "检查更新失败，请稍后重试");
    } finally {
      setCheckingUpdate(false);
    }
  }, [checkingUpdate]);

  const handleStartInstallUpdate = useCallback(async () => {
    if (!updateInfo || installingUpdate) return;
    setInstallingUpdate(true);
    setUpdateError(null);
    setUpdateProgress({
      stage: "downloading",
      downloadedBytes: 0,
      totalBytes: 0,
      percent: 1,
      message: "正在连接更新服务器...",
    });
    try {
      const result = await downloadAndInstallUpdateInRust(updateInfo);
      if (result === "OPENED_DMG") {
        setInstallingUpdate(false);
        setShowUpdateModal(false);
        setGlobalNotice("已挂载新版安装包，请拖入「应用程序」完成升级");
      }
    } catch (err: any) {
      setInstallingUpdate(false);
      setUpdateError(err?.message || "自动升级失败，可点击左下角手动下载安装包");
    }
  }, [updateInfo, installingUpdate]);

  // 120fps DOM-direct resizable split divider drag (ZERO React re-renders during mousemove!)
  const splitContainerRef = useRef<HTMLDivElement | null>(null);
  const leftPaneRef = useRef<HTMLDivElement | null>(null);
  const startSplitDrag = (e: React.MouseEvent) => {
    e.preventDefault();
    const container = splitContainerRef.current;
    const leftEl = leftPaneRef.current;
    if (!container || !leftEl) return;
    const rect = container.getBoundingClientRect();
    let latestFraction = preferences.splitFraction;
    let rafId = 0;

    document.body.style.cursor = "col-resize";

    const onMove = (ev: MouseEvent) => {
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
      document.body.style.cursor = "";
      updatePreferences({ ...preferences, splitFraction: latestFraction });
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
  const activeRightNonce = currentTab === "MESSAGES" ? msgJumpNonce : targetJumpNonce;
  const activeRightOnlyOp = currentTab === "MESSAGES" ? msgOnlyOp : onlyOpFilter;

  const mergePostPreservingBadges = (existing: PostItemModel | undefined, next: PostItemModel): PostItemModel => {
    if (!existing) return next;
    return {
      ...next,
      authorBadges:
        next.authorBadges && next.authorBadges.length > 0
          ? next.authorBadges
          : existing.authorBadges,
      badgesGranted:
        next.badgesGranted && next.badgesGranted.length > 0
          ? next.badgesGranted
          : existing.badgesGranted,
    };
  };

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

  const canDockInRightColumn = Boolean(
    composerReq &&
      isWideLayout &&
      (currentTab === "HOME" || currentTab === "MESSAGES") &&
      activeRightSummary &&
      composerReq.topicId === activeRightSummary.id &&
      (composerReq.action === "REPLY" ||
        (composerReq.action === "EDIT" && (composerReq.postNumber ?? 2) > 1))
  );
  const isDrawerComposer = canDockInRightColumn && !composerForceModal;

  const renderComposerInstance = (
    mode: "drawer" | "modal",
    allowToggle: boolean
  ): React.ReactNode => {
    if (!composerReq) return null;
    return (
      <ComposerModal
        key={`${composerReq.action}_${composerReq.topicId || 0}_${composerReq.postId || 0}`}
        request={composerReq}
        categories={categories}
        presentation={mode}
        onTogglePresentation={
          allowToggle ? () => setComposerForceModal((v) => !v) : undefined
        }
        appendQuote={composerAppendQuote}
        savedDraft={
          composerReq.draftKeyOverride
            ? drafts[composerReq.draftKeyOverride]
            : composerReq.action === "NEW_TOPIC"
              ? drafts["new_topic"]
              : composerReq.action === "REPLY" && composerReq.topicId
                ? drafts[`reply_${composerReq.topicId}`]
                : undefined
        }
        onClose={() => {
          setComposerReq(null);
          setComposerForceModal(false);
          setComposerAppendQuote(null);
          syncFromRustState();
        }}
        onPublished={({ topicId, post, pendingReview }) => {
          const act = composerReq.action;
          setComposerReq(null);
          setComposerForceModal(false);
          setComposerAppendQuote(null);
          syncFromRustState();
          if (pendingReview) {
            setGlobalNotice("内容已提交审核");
            return;
          }
          if (act === "NEW_TOPIC" && topicId) {
            loadFeed();
            openTopicById(topicId, "新发布话题", 1, true);
          } else if (post && activeDetail && activeDetail.id === topicId) {
            setActiveDetail((prev) => {
              if (!prev) return prev;
              const map = new Map(prev.posts.map((p) => [p.id, p]));
              map.set(post.id, post);
              return {
                ...prev,
                postsCount: act === "REPLY" ? prev.postsCount + 1 : prev.postsCount,
                posts: Array.from(map.values()).sort((a, b) => a.postNumber - b.postNumber),
              };
            });
            setTargetFloor(post.postNumber);
            setTargetJumpNonce((n) => n + 1);
          } else if (post && msgDetail && msgDetail.id === topicId) {
            setMsgDetail((prev) => {
              if (!prev) return prev;
              const map = new Map(prev.posts.map((p) => [p.id, p]));
              map.set(post.id, post);
              return {
                ...prev,
                postsCount: act === "REPLY" ? prev.postsCount + 1 : prev.postsCount,
                posts: Array.from(map.values()).sort((a, b) => a.postNumber - b.postNumber),
              };
            });
            setMsgTargetFloor(post.postNumber);
            setMsgJumpNonce((n) => n + 1);
          } else if (currentTab === "MESSAGES" && msgSummary) {
            openMessageTopic(msgSummary, { forceRefresh: true });
          } else if (activeSummary) {
            openTopic(activeSummary, { forceRefresh: true });
          }
        }}
        onTriggerVerify={handleTriggerVerify}
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
          onImportAuthCallback={async (raw) => {
            const ok = await importManualCredentialInRust(raw, isDark);
            if (ok) {
              await syncFromRustState();
              fetchSiteCategories(true)
                .then(setCategories)
                .catch(() => {});
              loadFeed();
            }
            return ok;
          }}
          onOpenSavedAccounts={() => {
            setAuthStatusHint(null);
            setShowAuthModal(true);
          }}
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
            onSelectTab={(tab) => {
              setCurrentTab(tab);
              setNarrowShowingReader(false);
            }}
            onPublishClick={() => {
              if (!isAuthorized) {
                setGuestPrompt({
                  title: "登录后发布话题",
                  subtitle: "登录 LINUX DO 账号，与佬友分享你的发现与见解",
                });
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
                  const targetCat = categories.find((c) => c.id === catId);
                  if (targetCat?.readRestricted && !isAuthorized) {
                    setGuestPrompt({
                      title: `登录后查看「${targetCat.name}」`,
                      subtitle: "该版块仅对登录会员开放，登录后即可浏览对应等级内容",
                    });
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
                    setGuestPrompt({ title: gTitle, subtitle: gSub });
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
                onPrimaryWebLogin={handlePrimaryWebLogin}
                onOpenAuthSheet={() => {
                  setAuthStatusHint(null);
                  setShowAuthModal(true);
                }}
                onRequestGuestLogin={(title, subtitle) => setGuestPrompt({ title, subtitle })}
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
                checkingUpdate={checkingUpdate}
                onCheckUpdate={handleManualCheckUpdate}
                onOpenUpdateModal={() => {
                  setUpdateError(null);
                  setShowUpdateModal(true);
                }}
              />
            ) : isWideLayout ? (
              <>
                {/* 宽屏左栏：首页话题流 或 消息中心 */}
                <div
                  ref={leftPaneRef}
                  className="relative h-full shrink-0"
                  style={{ width: `${Math.round(preferences.splitFraction * 1000) / 10}%` }}
                >
                  {currentTab === "MESSAGES" ? (
                    <MessagesView
                      isAuthorized={isAuthorized}
                      isDark={isDark}
                      currentUser={currentUser}
                      selectedTopicId={msgSummary?.id ?? null}
                      pmRefreshNonce={pmRefreshNonce}
                      initialComposeRecipient={pendingPmRecipient}
                      onConsumeInitialRecipient={() => setPendingPmRecipient(null)}
                      onOpenNotificationTopic={(tid, title, postNum) => {
                        openMessageTopicById(tid, title, postNum, true);
                      }}
                      onUnreadCleared={() => {
                        setUnreadCount(0);
                        setUnreadBadgeInRust(0).catch(() => {});
                      }}
                      onDecrementUnread={handleDecrementUnread}
                      onInspectUser={(uname) => setInspectedUsername(uname)}
                      onInspectBadge={(b) => setInspectedBadge(b)}
                      onOpenAuth={() => {
                        setAuthStatusHint(null);
                        setShowAuthModal(true);
                      }}
                      onOpenChat={() => {
                        openInAppForumPanel("https://linux.do/chat", isDark).catch(() =>
                          openUrl("https://linux.do/chat")
                        );
                      }}
                      onTriggerVerify={handleTriggerVerify}
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
                      pendingNewCount={
                        pendingNewTopics
                          ? Math.max(
                              1,
                              pendingNewTopics.filter(
                                (nt) =>
                                  !nt.pinned &&
                                  !topics.some(
                                    (t) =>
                                      t.id === nt.id &&
                                      t.highestPostNumber === nt.highestPostNumber
                                  )
                              ).length
                            )
                          : 0
                      }
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
                      onSelectCategory={(catId) => {
                        if (catId !== null) {
                          const cat = categories.find((c) => c.id === catId);
                          if (cat?.readRestricted && !isAuthorized) {
                            setGuestPrompt({
                              title: `登录后查看「${cat.name}」`,
                              subtitle: "该版块仅对登录会员开放，登录后即可浏览对应等级内容",
                            });
                            return;
                          }
                        }
                        setSelectedCategoryId(catId);
                      }}
                      onSelectTag={setSelectedTag}
                      onSelectTopic={(t, resumeFloor) => openTopic(t, { floor: resumeFloor ?? null })}
                      onRefresh={() => loadFeed()}
                      onApplyPendingNew={() => {
                        if (pendingNewTopics) {
                          setTopics(pendingNewTopics);
                          setPendingNewTopics(null);
                        } else {
                          loadFeed();
                        }
                      }}
                      onLoadMore={handleLoadMoreTopics}
                      onToggleCompact={() =>
                        updatePreferences({
                          ...preferences,
                          compactFeed: !preferences.compactFeed,
                        })
                      }
                      onOpenSearch={() => setShowGlobalSearch(true)}
                      onOpenNotifications={() => setCurrentTab("MESSAGES")}
                      onOpenAuth={() => {
                        setAuthStatusHint(null);
                        if (isAuthorized || savedAccounts.length > 0) {
                          setShowAuthModal(true);
                        } else {
                          handlePrimaryWebLogin();
                        }
                      }}
                      onTriggerVerify={handleTriggerVerify}
                    />
                  )}

                  {/* 分栏可拖拽分隔手柄（双击恢复默认比例） */}
                  <div
                    onMouseDown={startSplitDrag}
                    onDoubleClick={() =>
                      updatePreferences({ ...preferences, splitFraction: 0.42 })
                    }
                    className="group absolute top-0 -right-[3px] z-30 h-full w-[6px] cursor-col-resize"
                  >
                    <div
                      className="mx-auto h-full w-[1px] opacity-0 transition-opacity group-hover:opacity-100"
                      style={{ backgroundColor: "var(--brand-gold)" }}
                    />
                  </div>
                </div>

                {/* 宽屏右栏：上方可滚动阅读器 + 下方可拖拽半屏回复抽屉（上下同步联动、零遮挡） */}
                <div className="relative flex flex-1 min-w-0 h-full flex-col">
                  <div className="relative flex flex-1 min-h-0 flex-col">
                    <TopicReaderPane
                      summary={activeRightSummary}
                      detail={activeRightDetail}
                      isLoading={activeRightLoading}
                      errorMessage={activeRightError}
                      targetFloor={activeRightFloor}
                      targetJumpNonce={activeRightNonce}
                      onlyOpFilter={activeRightOnlyOp}
                      isAuthorized={isAuthorized}
                      currentUser={currentUser}
                      confirmedReadFloor={Math.max(
                        activeRightDetail?.lastReadPostNumber ?? 0,
                        activeRightSummary ? readFloors[String(activeRightSummary.id)] ?? 0 : 0
                      )}
                      showBackButton={false}
                      hideQuickReplyBar={isDrawerComposer}
                      emptyPlaceholderText={
                        currentTab === "MESSAGES"
                          ? "选择左侧消息开始阅读"
                          : "选择左侧话题开始阅读"
                      }
                      onBack={() => {}}
                      onRefresh={() => {
                        if (currentTab === "MESSAGES" && msgSummary) {
                          openMessageTopic(msgSummary, {
                            floor: msgTargetFloor,
                            onlyOp: msgOnlyOp,
                            forceRefresh: true,
                          });
                        } else if (activeSummary) {
                          openTopic(activeSummary, {
                            floor: targetFloor,
                            onlyOp: onlyOpFilter,
                            forceRefresh: true,
                          });
                        }
                      }}
                      onToggleOnlyOp={() => {
                        if (currentTab === "MESSAGES" && msgSummary) {
                          openMessageTopic(msgSummary, {
                            onlyOp: !msgOnlyOp,
                            forceRefresh: true,
                          });
                        } else if (activeSummary) {
                          openTopic(activeSummary, {
                            onlyOp: !onlyOpFilter,
                            forceRefresh: true,
                          });
                        }
                      }}
                      onAppendPosts={appendPostsToActiveReader}
                      onRemovePost={removePostFromActiveReader}
                      onOpenComposer={(req) => launchOrAppendComposer(req)}
                      onInspectUser={(uname) => setInspectedUsername(uname)}
                      onInspectBadge={(b) => setInspectedBadge(b)}
                      onOpenImageLightbox={(src) => setLightboxSrc(src)}
                      onNavigateTopicLink={(tid, floor) =>
                        currentTab === "MESSAGES"
                          ? openMessageTopicById(tid, `话题 #${tid}`, floor, false)
                          : openTopicById(tid, `话题 #${tid}`, floor, false)
                      }
                      onOpenInAppUrl={(url) => {
                        if (/^https?:\/\/connect\.linux\.do\/?$/i.test(url.trim()) && currentUser) {
                          setShowConnectModal(true);
                        } else {
                          openInAppForumPanel(url, isDark).catch(() => openUrl(url));
                        }
                      }}
                      onPmArchiveChanged={() => setPmRefreshNonce((n) => n + 1)}
                      onRequestGuestLogin={(title, subtitle) => setGuestPrompt({ title, subtitle })}
                      onTriggerVerify={handleTriggerVerify}
                      onFloorSeen={handleFloorSeen}
                    />
                  </div>
                  {canDockInRightColumn &&
                    renderComposerInstance(isDrawerComposer ? "drawer" : "modal", true)}
                </div>
              </>
            ) : currentTab === "MESSAGES" ? (
              <MessagesView
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
                onInspectUser={(uname) => setInspectedUsername(uname)}
                onInspectBadge={(b) => setInspectedBadge(b)}
                onOpenAuth={() => setShowAuthModal(true)}
                onOpenChat={() => {
                  openInAppForumPanel("https://linux.do/chat", isDark).catch(() =>
                    openUrl("https://linux.do/chat")
                  );
                }}
                onTriggerVerify={handleTriggerVerify}
              />
            ) : narrowShowingReader && activeSummary ? (
              <TopicReaderPane
                summary={activeSummary}
                detail={activeDetail}
                isLoading={readerLoading}
                errorMessage={readerError}
                targetFloor={targetFloor}
                targetJumpNonce={targetJumpNonce}
                onlyOpFilter={onlyOpFilter}
                isAuthorized={isAuthorized}
                currentUser={currentUser}
                confirmedReadFloor={Math.max(
                  activeDetail?.lastReadPostNumber ?? 0,
                  activeSummary ? readFloors[String(activeSummary.id)] ?? 0 : 0
                )}
                showBackButton={true}
                onBack={() => setNarrowShowingReader(false)}
                onRefresh={() =>
                  openTopic(activeSummary, {
                    floor: targetFloor,
                    onlyOp: onlyOpFilter,
                    forceRefresh: true,
                  })
                }
                onToggleOnlyOp={() =>
                  openTopic(activeSummary, {
                    onlyOp: !onlyOpFilter,
                    forceRefresh: true,
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
                onOpenImageLightbox={(src) => setLightboxSrc(src)}
                onNavigateTopicLink={(tid, floor) =>
                  openTopicById(tid, `话题 #${tid}`, floor, false)
                }
                onOpenInAppUrl={(url) => {
                  if (/^https?:\/\/connect\.linux\.do\/?$/i.test(url.trim()) && currentUser) {
                    setShowConnectModal(true);
                  } else {
                    openInAppForumPanel(url, isDark).catch(() => openUrl(url));
                  }
                }}
                onPmArchiveChanged={() => setPmRefreshNonce((n) => n + 1)}
                onRequestGuestLogin={(title, subtitle) => setGuestPrompt({ title, subtitle })}
                onTriggerVerify={handleTriggerVerify}
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
                pendingNewCount={0}
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
                onSelectCategory={setSelectedCategoryId}
                onSelectTag={setSelectedTag}
                onSelectTopic={(t, resumeFloor) => openTopic(t, { floor: resumeFloor ?? null })}
                onRefresh={() => loadFeed()}
                onApplyPendingNew={() => loadFeed()}
                onLoadMore={handleLoadMoreTopics}
                onToggleCompact={() =>
                  updatePreferences({ ...preferences, compactFeed: !preferences.compactFeed })
                }
                onOpenSearch={() => setShowGlobalSearch(true)}
                onOpenNotifications={() => setCurrentTab("MESSAGES")}
                onOpenAuth={() => {
                  setAuthStatusHint(null);
                  if (isAuthorized || savedAccounts.length > 0) setShowAuthModal(true);
                  else handlePrimaryWebLogin();
                }}
                onTriggerVerify={handleTriggerVerify}
              />
            )}
          </div>
        </>
      )}

      {/* 被动过期温和提醒胶囊 */}
      {inMainApp && !isAuthorized && expiredUsername && !showAuthModal && !guestPrompt && (
        <div
          className="fixed bottom-6 left-1/2 z-40 flex -translate-x-1/2 items-center gap-2.5 rounded-full pl-4 pr-2 py-1.5 shadow-2xl"
          style={{
            backgroundColor: isDark ? "#1B202A" : "var(--card-surface)",
            border: "0.5px solid color-mix(in srgb, var(--brand-gold) 38%, transparent)",
          }}
        >
          <span className="text-[12.8px] font-medium" style={{ color: "var(--ink-primary)" }}>
            @{expiredUsername} 登录已过期，当前为访客模式
          </span>
          <button
            type="button"
            onClick={() => {
              setAuthStatusHint(null);
              setAuthModalStartAdding(true);
              setShowAuthModal(true);
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
            <X size={13.5} />
          </button>
        </div>
      )}

      {/* 游客受限操作引导弹窗 */}
      {guestPrompt && (
        <GuestLoginGuideModal
          title={guestPrompt.title}
          subtitle={guestPrompt.subtitle}
          onPrimaryWebLogin={handlePrimaryWebLogin}
          onBrowserOneClickAuth={handleBrowserOneClickAuth}
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
          onSwitchAccount={handleSwitchSavedAccount}
          onRemoveAccount={async (uname) => {
            await removeSavedAccountInRust(uname);
            await syncFromRustState();
          }}
          onManualImported={async () => {
            await syncFromRustState();
            fetchSiteCategories(true)
              .then(setCategories)
              .catch(() => {});
            loadFeed();
          }}
          onLogout={() => setShowLogoutConfirm(true)}
          onClose={() => {
            setAuthModalStartAdding(false);
            setShowAuthModal(false);
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
          onSelectHit={(tid, title, floor) => {
            openTopicById(tid, title, floor, false);
          }}
          onClose={() => setShowGlobalSearch(false)}
        />
      )}

      {/* 创作工作台（新话题 / 编辑首楼 / 窄屏居中圆角弹窗） */}
      {composerReq && !canDockInRightColumn && renderComposerInstance("modal", false)}

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
          onInspectBadge={(b) => setInspectedBadge(b)}
          onClose={() => setInspectedUsername(null)}
        />
      )}

      {/* 荣誉徽章详情弹窗 */}
      {inspectedBadge && (
        <BadgeDetailModal badge={inspectedBadge} onClose={() => setInspectedBadge(null)} />
      )}

      {/* 原生 Connect 保级与成长面板 */}
      {showConnectModal && currentUser && (
        <ConnectTrustLevelModal
          username={currentUser.username}
          onOpenWebConnect={() => {
            openInAppForumPanel("https://connect.linux.do/", isDark).catch(() =>
              openUrl("https://connect.linux.do/")
            );
          }}
          onClose={() => setShowConnectModal(false)}
        />
      )}

      {/* 客户端新版本下载升级弹窗 */}
      {showUpdateModal && updateInfo && (
        <DesktopUpdateModal
          info={updateInfo}
          installing={installingUpdate}
          progress={updateProgress}
          error={updateError}
          onStartInstall={handleStartInstallUpdate}
          onClose={() => {
            if (!installingUpdate) setShowUpdateModal(false);
          }}
        />
      )}

      {/* 大图灯箱 */}
      {lightboxSrc && (
        <ImageLightboxModal src={lightboxSrc} onClose={() => setLightboxSrc(null)} />
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
