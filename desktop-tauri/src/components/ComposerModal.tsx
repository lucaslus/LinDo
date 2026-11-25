import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { getCurrentWebview } from "@tauri-apps/api/webview";
import { isImeComposingEvent } from "../lib/imeGuard";
import {
  LinDoIcons,
  resolveOfficialCategoryVisual,
  resolveOfficialTagVisual,
} from "./LinDoIcons";
import type {
  CategoryMeta,
  ComposerLaunchRequest,
  LocalDraftRecord,
  MentionUserCandidate,
  PostItemModel,
  TagChoiceOption,
  TagChoiceResult,
} from "../lib/types";
import {
  type CommunitySearchHit,
  deleteLocalDraftInRust,
  fetchCategoryAllowedTags,
  fetchPostRawMarkdown,
  publishReply,
  publishTopic,
  saveCloudDraft,
  saveLocalDraftInRust,
  searchTopicsForLinkPicker,
  searchUsersForMention,
  SiteError,
  updateExistingPost,
  uploadMediaToSite,
} from "../lib/api";
import {
  buildUploadPlaceholder,
  extractSyncClipboardFiles,
  fileToPreparedUpload,
  formatUploadedMarkdown,
  handleComposerTextareaPaste,
  prepareDroppedPaths,
  type PreparedUploadFile,
} from "../lib/composerMedia";
import { optimizePanguSpacing, prepareCookedHtml } from "../lib/cooked";
import { renderDiscourseMarkdownPreview } from "../lib/markdownPreview";
import { observeCookedForumImages } from "../lib/forumMedia";
import { observeCookedRichContent } from "../lib/richDecorators";
import { formatCompactCount, resolveCategoryVisual } from "../lib/palette";
import { AvatarWithFlair, ConfirmDialog } from "./SecondaryScreens";

interface ComposerModalProps {
  request: ComposerLaunchRequest;
  categories: CategoryMeta[];
  savedDraft?: LocalDraftRecord;
  presentation?: "modal" | "drawer";
  onTogglePresentation?: () => void;
  appendQuote?: { text: string; nonce: number } | null;
  exitGuardRef?: React.MutableRefObject<((proceed: () => void) => void) | null>;
  onClose: () => void;
  onPublished: (result: {
    topicId?: number;
    post?: PostItemModel | null;
    pendingReview?: boolean;
  }) => void;
  onTriggerVerify: () => void;
}

const QUICK_EMOJIS = [
  "😀", "😂", "🤣", "😊", "😍", "🤔", "😎", "🥳",
  "👍", "👏", "🙏", "🎉", "🔥", "❤️", "🚀", "💯",
  "😭", "😅", "🫡", "🤝", "✨", "💡", "⚠️", "✅",
  "🎉", "🙌", "💪", "🎯", "🧠", "⚡", "🐳", "🐧",
];

const SITE_SHORTCODE_EMOJIS: Array<{ code: string; label: string }> = [
  { code: ":tieba_087:", label: "滑稽赞" },
  { code: ":distorted_face:", label: "融化" },
  { code: ":tada:", label: "庆祝" },
  { code: ":rocket:", label: "火箭" },
  { code: ":fire:", label: "火热" },
  { code: ":eyes:", label: "围观" },
  { code: ":thinking:", label: "思考" },
  { code: ":saluting_face:", label: "致敬" },
  { code: ":partying_face:", label: "撒花" },
  { code: ":100:", label: "满分" },
  { code: ":heart:", label: "爱心" },
  { code: ":pray:", label: "感谢" },
];

const MIN_TITLE_LEN = 5;
const MIN_BODY_LEN = 5;
const MAX_TAGS = 5;

export const ComposerModal: React.FC<ComposerModalProps> = ({
  request,
  categories,
  savedDraft,
  presentation = "modal",
  onTogglePresentation,
  appendQuote,
  exitGuardRef,
  onClose,
  onPublished,
  onTriggerVerify,
}) => {
  const isDrawer = presentation === "drawer";
  const isNonMac =
    typeof document !== "undefined" &&
    (document.documentElement.dataset.platform === "windows" ||
      document.documentElement.dataset.platform === "linux");
  const submitShortcutLabel = isNonMac ? "Ctrl+Enter" : "⌘+Enter";
  const isNewTopic = request.action === "NEW_TOPIC";
  const isEditingOp = request.action === "EDIT" && request.postNumber === 1;
  const hasMetaControls = isNewTopic || isEditingOp;

  const draftKey = useMemo(() => {
    if (request.draftKeyOverride) return request.draftKeyOverride;
    if (request.action === "NEW_TOPIC") return "new_topic";
    if (request.action === "EDIT") return `edit_${request.postId || 0}`;
    return `reply_${request.topicId || 0}`;
  }, [request]);

  // Filter strictly to postable categories (aligned 1:1 with ComposerPickers.kt!)
  const postableTopCategories = useMemo(() => {
    const tops = categories.filter((c) => !c.parentId && c.canCreateTopic !== false);
    return tops.length > 0 ? tops : categories.filter((c) => !c.parentId);
  }, [categories]);

  const getPostableSubsForParent = useCallback(
    (parentId: number) =>
      categories.filter((c) => c.parentId === parentId && c.canCreateTopic !== false),
    [categories]
  );

  const defaultCatId = useMemo(() => {
    const candidate = savedDraft?.categoryId ?? request.initialCategoryId;
    if (candidate) {
      const found = categories.find((c) => c.id === candidate && c.canCreateTopic !== false);
      if (found) return found.id;
    }
    return (
      postableTopCategories.find((c) => c.id === 4)?.id ??
      postableTopCategories.find((c) => !c.readRestricted)?.id ??
      postableTopCategories[0]?.id ??
      4
    );
  }, [savedDraft?.categoryId, request.initialCategoryId, categories, postableTopCategories]);

  const [categoryId, setCategoryId] = useState<number>(defaultCatId);
  const [focusedParentId, setFocusedParentId] = useState<number>(() => {
    const cur = categories.find((c) => c.id === defaultCatId);
    return cur?.parentId ?? cur?.id ?? 4;
  });
  const [title, setTitle] = useState<string>(savedDraft?.title ?? request.initialTitle ?? "");
  const [raw, setRaw] = useState<string>(savedDraft?.raw ?? request.initialRaw ?? "");
  const [lastAppliedTemplate, setLastAppliedTemplate] = useState<string>("");
  const [tags, setTags] = useState<string[]>(savedDraft?.tags ?? request.initialTags ?? []);
  const [viewMode, setViewMode] = useState<"SOURCE" | "SPLIT" | "PREVIEW">("SOURCE");
  const [replyQuoteExpanded, setReplyQuoteExpanded] = useState(false);
  const [drawerHeight, setDrawerHeight] = useState<number>(() => {
    try {
      const saved = Number(localStorage.getItem("lindo_composer_drawer_h") || 0);
      if (saved >= 220 && saved <= 760) return saved;
    } catch {
      // ignore
    }
    return 320;
  });
  const [activePicker, setActivePicker] = useState<
    "NONE" | "CATEGORY" | "TAG" | "EMOJI" | "MENTION" | "LINK"
  >("NONE");

  // Tag picker state
  const [tagQuery, setTagQuery] = useState("");
  const [tagChoices, setTagChoices] = useState<TagChoiceResult>({ options: [] });
  const [tagsLoading, setTagsLoading] = useState(false);
  const [tagsError, setTagsError] = useState<string | null>(null);
  const [tagsRetryNonce, setTagsRetryNonce] = useState(0);

  // @Mention picker state
  const [mentionQuery, setMentionQuery] = useState("");
  const [mentionHits, setMentionHits] = useState<MentionUserCandidate[]>([]);
  const [mentionLoading, setMentionLoading] = useState(false);

  // Insert link / quote community topic state
  const [linkMode, setLinkMode] = useState<"URL" | "TOPIC">("URL");
  const [linkUrl, setLinkUrl] = useState("");
  const [linkText, setLinkText] = useState("");
  const [linkAttemptedError, setLinkAttemptedError] = useState(false);
  const [topicSearchQuery, setTopicSearchQuery] = useState("");
  const [topicSearchHits, setTopicSearchHits] = useState<CommunitySearchHit[]>([]);
  const [topicSearchLoading, setTopicSearchLoading] = useState(false);

  // Lottery 20-point confirmation state
  const [showLotteryConfirm, setShowLotteryConfirm] = useState(false);
  const [showExitConfirm, setShowExitConfirm] = useState(false);
  const [originalEditRaw, setOriginalEditRaw] = useState<string>(request.initialRaw ?? "");
  const pendingProceedRef = useRef<(() => void) | null>(null);

  // Submit-time validation state
  const [submitAttempted, setSubmitAttempted] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [uploadingCount, setUploadingCount] = useState(0);
  const [uploadProgressLabel, setUploadProgressLabel] = useState<string>("");
  const [isDragActive, setIsDragActive] = useState(false);
  const [uploadedPreviewMap, setUploadedPreviewMap] = useState<Record<string, string>>({});
  const uploading = uploadingCount > 0;
  const [uploadedImg, setUploadedImg] = useState<{
    filename: string;
    shortUrl: string;
    width: number;
    height: number;
    snippet: string;
    scale: "100%" | "75%" | "50%";
  } | null>(null);
  const [draftSavedAt, setDraftSavedAt] = useState<number | null>(null);
  const [cloudSyncedAt, setCloudSyncedAt] = useState<number | null>(null);
  const cloudDraftSeqRef = useRef<number>(savedDraft?.sequence ?? 0);
  const [editReason, setEditReason] = useState<string>("");
  const [inlineSuggest, setInlineSuggest] = useState<
    | { kind: "MENTION"; query: string; startIdx: number; endIdx: number; items: MentionUserCandidate[]; active: number }
    | { kind: "EMOJI"; query: string; startIdx: number; endIdx: number; items: Array<{ code: string; label: string }>; active: number }
    | null
  >(null);
  const [loadingRawEdit, setLoadingRawEdit] = useState(
    request.action === "EDIT" && !request.initialRaw
  );

  const titleInputRef = useRef<HTMLInputElement | null>(null);
  const bodyTextareaRef = useRef<HTMLTextAreaElement | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const drawerRef = useRef<HTMLDivElement | null>(null);
  const previewContainerRef = useRef<HTMLDivElement | null>(null);
  const rawRef = useRef<string>(raw);
  rawRef.current = raw;
  const uploadSeqRef = useRef<number>(0);
  const createdBlobUrlsRef = useRef<string[]>([]);
  const lastAppendedNonceRef = useRef<number>(0);
  const composerOpenedAtRef = useRef<number>(Date.now());
  const firstTypedAtRef = useRef<number | null>(null);

  // Revoke locally created preview blob URLs when composer closes
  useEffect(() => {
    return () => {
      for (const url of createdBlobUrlsRef.current) {
        try {
          URL.revokeObjectURL(url);
        } catch {
          // ignore
        }
      }
      createdBlobUrlsRef.current = [];
    };
  }, []);

  // Append newly selected reader quote directly into the active composer without resetting draft!
  useEffect(() => {
    if (!appendQuote || !appendQuote.text || appendQuote.nonce === lastAppendedNonceRef.current) {
      return;
    }
    lastAppendedNonceRef.current = appendQuote.nonce;
    setRaw((prev) => {
      const prefix = prev.trim().length > 0 ? `${prev.replace(/\s+$/, "")}\n\n` : "";
      return `${prefix}${appendQuote.text}`;
    });
    if (viewMode === "PREVIEW") {
      setViewMode("SOURCE");
    }
    requestAnimationFrame(() => {
      const el = bodyTextareaRef.current;
      if (el) {
        el.focus();
        el.setSelectionRange(el.value.length, el.value.length);
        el.scrollTop = el.scrollHeight;
      }
    });
  }, [appendQuote, viewMode]);

  // 120fps DOM-direct vertical resize handle for bottom half-screen reply drawer
  const startDrawerResize = (e: React.MouseEvent) => {
    e.preventDefault();
    const el = drawerRef.current;
    if (!el) return;
    const startY = e.clientY;
    const startH = el.getBoundingClientRect().height;
    const maxH = Math.max(280, Math.round(window.innerHeight * 0.76));
    let latestH = startH;
    let rafId = 0;

    const onMove = (ev: MouseEvent) => {
      const delta = startY - ev.clientY;
      latestH = Math.round(Math.max(220, Math.min(maxH, startH + delta)));
      if (rafId === 0) {
        rafId = requestAnimationFrame(() => {
          rafId = 0;
          if (drawerRef.current) {
            drawerRef.current.style.height = `${latestH}px`;
          }
        });
      }
    };

    const onUp = () => {
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
      if (rafId !== 0) cancelAnimationFrame(rafId);
      setDrawerHeight(latestH);
      try {
        localStorage.setItem("lindo_composer_drawer_h", String(latestH));
      } catch {
        // ignore
      }
    };

    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
  };

  // Load raw markdown when editing a post
  useEffect(() => {
    if (request.action === "EDIT" && request.postId && !request.initialRaw) {
      setLoadingRawEdit(true);
      fetchPostRawMarkdown(request.postId)
        .then((loadedRaw) => {
          setOriginalEditRaw(loadedRaw);
          setRaw(loadedRaw);
        })
        .catch((err: any) => setSubmitError(err?.message || "无法读取原帖内容"))
        .finally(() => setLoadingRawEdit(false));
    }
  }, [request]);

  // Apply category topic_template automatically when switching categories on a new topic if raw is blank or still equals previous template
  useEffect(() => {
    if (!isNewTopic) return;
    const cat = categories.find((c) => c.id === categoryId);
    const tpl = (cat?.topicTemplate || "").trim();
    if (!tpl) return;
    setRaw((prev) => {
      const trimmed = prev.trim();
      if (!trimmed || (lastAppliedTemplate && trimmed === lastAppliedTemplate.trim())) {
        setLastAppliedTemplate(tpl);
        return tpl;
      }
      return prev;
    });
  }, [isNewTopic, categoryId, categories, lastAppliedTemplate]);

  useEffect(() => {
    setSubmitAttempted(false);
  }, [categoryId]);

  // Fetch allowed tags & required_tag_group for current category (re-query on selected tags only when requiredGroup is active)
  const requiredGroupSelectedKey = tagChoices.requiredGroup ? tags.join(",") : "";
  useEffect(() => {
    if (!hasMetaControls || !categoryId) return;
    let cancelled = false;
    const timer = setTimeout(
      () => {
        setTagsLoading(true);
        setTagsError(null);
        fetchCategoryAllowedTags(categoryId, tagQuery, tags)
          .then((res) => {
            if (!cancelled) setTagChoices(res);
          })
          .catch((err: any) => {
            if (!cancelled) {
              setTagsError(err?.message || "标签加载失败，请重试");
            }
          })
          .finally(() => {
            if (!cancelled) setTagsLoading(false);
          });
      },
      tagQuery.trim() ? 220 : 0
    );
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [hasMetaControls, categoryId, tagQuery, requiredGroupSelectedKey, tagsRetryNonce]);

  // Live @mention search
  useEffect(() => {
    if (activePicker !== "MENTION") return;
    let cancelled = false;
    const timer = setTimeout(() => {
      setMentionLoading(true);
      searchUsersForMention(mentionQuery)
        .then((list) => {
          if (!cancelled) setMentionHits(list);
        })
        .catch(() => {
          if (!cancelled) setMentionHits([]);
        })
        .finally(() => {
          if (!cancelled) setMentionLoading(false);
        });
    }, 200);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [activePicker, mentionQuery]);

  // Live topic search for link picker
  useEffect(() => {
    if (activePicker !== "LINK" || linkMode !== "TOPIC") return;
    const q = topicSearchQuery.trim();
    if (q.length < 2) {
      setTopicSearchHits([]);
      return;
    }
    let cancelled = false;
    const timer = setTimeout(() => {
      setTopicSearchLoading(true);
      searchTopicsForLinkPicker(q)
        .then((list) => {
          if (!cancelled) setTopicSearchHits(list);
        })
        .catch(() => {
          if (!cancelled) setTopicSearchHits([]);
        })
        .finally(() => {
          if (!cancelled) setTopicSearchLoading(false);
        });
    }, 260);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [activePicker, linkMode, topicSearchQuery]);

  // Persist local draft quietly on edit
  useEffect(() => {
    if (request.action === "EDIT") return;
    if (!title.trim() && !raw.trim() && tags.length === 0) return;
    const timer = setTimeout(() => {
      saveLocalDraftInRust({
        key: draftKey,
        action: request.action,
        topicId: request.topicId ?? null,
        postId: request.postId ?? null,
        replyToPostNumber: request.replyToPostNumber ?? null,
        categoryId,
        title,
        raw,
        tags,
        sequence: 0,
        updatedAt: Date.now(),
      })
        .then(() => setDraftSavedAt(Date.now()))
        .catch(() => {});
    }, 450);
    return () => clearTimeout(timer);
  }, [draftKey, request, categoryId, title, raw, tags]);

  // Best-effort bidirectional Discourse cloud draft sync (debounced 2.8s, strictly non-blocking)
  useEffect(() => {
    if (request.action === "EDIT") return;
    if (!title.trim() && !raw.trim()) return;
    const cloudKey =
      request.action === "NEW_TOPIC"
        ? "new_topic"
        : request.topicId
          ? `topic_${request.topicId}`
          : null;
    if (!cloudKey) return;
    const timer = setTimeout(() => {
      const payload =
        request.action === "NEW_TOPIC"
          ? {
              reply: raw,
              action: "createTopic",
              title,
              categoryId,
              tags,
              archetypeId: "regular",
              metaData: null,
              composerTime: Date.now() - composerOpenedAtRef.current,
              typingTime: firstTypedAtRef.current ? Date.now() - firstTypedAtRef.current : 0,
            }
          : {
              reply: raw,
              action: "reply",
              replyToPostNumber: request.replyToPostNumber ?? null,
              composerTime: Date.now() - composerOpenedAtRef.current,
              typingTime: firstTypedAtRef.current ? Date.now() - firstTypedAtRef.current : 0,
            };
      saveCloudDraft({
        draftKey: cloudKey,
        sequence: cloudDraftSeqRef.current,
        data: payload,
      })
        .then((nextSeq) => {
          cloudDraftSeqRef.current = nextSeq;
          setCloudSyncedAt(Date.now());
        })
        .catch(() => {
          // silent fallback: local Rust draft is always authoritative
        });
    }, 2800);
    return () => clearTimeout(timer);
  }, [request, categoryId, title, raw, tags]);

  // Live inline `@` mention fetch when user types `@term` directly in the editor
  useEffect(() => {
    if (!inlineSuggest || inlineSuggest.kind !== "MENTION") return;
    const q = inlineSuggest.query;
    let alive = true;
    const timer = setTimeout(() => {
      searchUsersForMention(q)
        .then((list) => {
          if (!alive) return;
          setInlineSuggest((prev) =>
            prev && prev.kind === "MENTION" && prev.query === q
              ? { ...prev, items: list.slice(0, 6), active: 0 }
              : prev
          );
        })
        .catch(() => {});
    }, 180);
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, [inlineSuggest?.kind, inlineSuggest?.query]);

  const activeCategory = useMemo(
    () => categories.find((c) => c.id === categoryId) ?? postableTopCategories[0],
    [categories, categoryId, postableTopCategories]
  );

  const focusedParent = useMemo(
    () =>
      postableTopCategories.find((c) => c.id === focusedParentId) ??
      postableTopCategories[0] ??
      null,
    [postableTopCategories, focusedParentId]
  );

  const focusedSubCategories = useMemo(
    () => (focusedParent ? getPostableSubsForParent(focusedParent.id) : []),
    [focusedParent, getPostableSubsForParent]
  );

  // Validation computations (displayed ONLY after submitAttempted === true)
  const titleTrimmed = title.trim();
  const titleRemaining = MIN_TITLE_LEN - Array.from(titleTrimmed).length;
  const titleErrorMsg = !hasMetaControls
    ? null
    : titleTrimmed.length === 0
      ? "请填写标题"
      : titleRemaining > 0
        ? `标题还差 ${titleRemaining} 个字`
        : null;

  const tagErrorMsg =
    !hasMetaControls || tagsLoading
      ? null
      : tagChoices.forbiddenMessage
        ? tagChoices.forbiddenMessage
        : tagChoices.requiredGroup
          ? `需选标签：${tagChoices.requiredGroup}`
          : null;

  const bodyTrimmed = raw.trim();
  const bodyRemaining = MIN_BODY_LEN - Array.from(bodyTrimmed).length;
  const bodyErrorMsg =
    bodyTrimmed.length === 0
      ? request.action === "REPLY"
        ? "请填写回复"
        : "请填写正文"
      : bodyRemaining > 0
        ? `还差 ${bodyRemaining} 个字`
        : null;

  const isUntouchedTemplate =
    isNewTopic &&
    Boolean(lastAppliedTemplate) &&
    raw.trim() === lastAppliedTemplate.trim() &&
    title.trim().length === 0 &&
    tags.length === 0;

  const hasChanges = useMemo(() => {
    if (loadingRawEdit || isUntouchedTemplate) return false;
    if (request.action === "EDIT") {
      if (raw !== originalEditRaw) return true;
      if (isEditingOp) {
        if (title.trim() !== (request.initialTitle ?? "").trim()) return true;
        if (request.initialCategoryId && categoryId !== request.initialCategoryId) return true;
        if (tags.join(",") !== (request.initialTags ?? []).join(",")) return true;
      }
      return false;
    }
    return raw.trim().length > 0 || title.trim().length > 0;
  }, [
    loadingRawEdit,
    isUntouchedTemplate,
    request.action,
    request.initialTitle,
    request.initialCategoryId,
    request.initialTags,
    raw,
    originalEditRaw,
    isEditingOp,
    title,
    categoryId,
    tags,
  ]);

  const requestClose = useCallback(
    (afterProceed?: () => void) => {
      if (submitting || uploading) return;
      if (!afterProceed && activePicker !== "NONE") {
        setActivePicker("NONE");
        return;
      }
      if (hasChanges) {
        pendingProceedRef.current = afterProceed ?? null;
        setShowExitConfirm(true);
      } else {
        pendingProceedRef.current = null;
        onClose();
        afterProceed?.();
      }
    },
    [submitting, uploading, activePicker, hasChanges, onClose]
  );

  useEffect(() => {
    if (isDrawer || showLotteryConfirm || showExitConfirm) return;
    const onWinKeyDown = (e: KeyboardEvent) => {
      if (isImeComposingEvent(e)) return;
      if (e.key === "Escape") {
        e.preventDefault();
        if (inlineSuggest) {
          setInlineSuggest(null);
          return;
        }
        requestClose();
      }
    };
    window.addEventListener("keydown", onWinKeyDown);
    return () => window.removeEventListener("keydown", onWinKeyDown);
  }, [isDrawer, showLotteryConfirm, showExitConfirm, inlineSuggest, requestClose]);

  useEffect(() => {
    if (!exitGuardRef) return;
    const handler = (proceed: () => void) => requestClose(proceed);
    exitGuardRef.current = handler;
    return () => {
      if (exitGuardRef.current === handler) {
        exitGuardRef.current = null;
      }
    };
  }, [exitGuardRef, requestClose]);

  const showTitleError = submitAttempted && Boolean(titleErrorMsg);
  const showTagError = submitAttempted && Boolean(tagErrorMsg);
  const showBodyError = submitAttempted && Boolean(bodyErrorMsg);

  // Auto-dismiss recent-upload scale bar immediately when the image markdown is deleted from the editor
  const activeUploadedImg = useMemo(() => {
    if (!uploadedImg) return null;
    if (raw.includes(uploadedImg.shortUrl) || raw.includes(uploadedImg.snippet)) {
      return uploadedImg;
    }
    return null;
  }, [uploadedImg, raw]);

  useEffect(() => {
    if (uploadedImg && !activeUploadedImg) {
      setUploadedImg(null);
    }
  }, [uploadedImg, activeUploadedImg]);

  const insertAroundSelection = useCallback((before: string, after = "", placeholder = "") => {
    const el = bodyTextareaRef.current;
    const cur = rawRef.current;
    if (!el) {
      const inner = placeholder || "";
      const next = `${cur}${before}${inner}${after}`;
      rawRef.current = next;
      setRaw(next);
      return;
    }
    const start = el.selectionStart ?? cur.length;
    const end = el.selectionEnd ?? cur.length;
    const selected = cur.slice(start, end);
    const bodyText = selected || placeholder;
    const next = `${cur.slice(0, start)}${before}${bodyText}${after}${cur.slice(end)}`;
    rawRef.current = next;
    setRaw(next);
    requestAnimationFrame(() => {
      el.focus();
      if (!selected && placeholder) {
        el.setSelectionRange(start + before.length, start + before.length + placeholder.length);
      } else {
        const cursor = start + before.length + bodyText.length;
        el.setSelectionRange(cursor, cursor);
      }
    });
  }, []);

  const insertBlockSnippet = useCallback((block: string, selectToken?: string) => {
    const el = bodyTextareaRef.current;
    const cur = rawRef.current;
    const start = el ? (el.selectionStart ?? cur.length) : cur.length;
    const end = el ? (el.selectionEnd ?? cur.length) : cur.length;
    const before = cur.slice(0, start);
    const after = cur.slice(end);
    const lead =
      before.length === 0
        ? ""
        : before.endsWith("\n\n")
          ? ""
          : before.endsWith("\n")
            ? "\n"
            : "\n\n";
    const trail = after.length === 0 || after.startsWith("\n") ? "\n" : "\n\n";
    const inserted = `${lead}${block}${trail}`;
    const next = `${before}${inserted}${after}`;
    rawRef.current = next;
    setRaw(next);
    if (el) {
      requestAnimationFrame(() => {
        el.focus();
        if (selectToken) {
          const idx = inserted.indexOf(selectToken);
          if (idx >= 0) {
            el.setSelectionRange(start + idx, start + idx + selectToken.length);
            return;
          }
        }
        const cursor = start + inserted.length;
        el.setSelectionRange(cursor, cursor);
      });
    }
  }, []);

  const prefixSelectedLines = useCallback((prefix: string, placeholder: string) => {
    const el = bodyTextareaRef.current;
    const cur = rawRef.current;
    if (!el) {
      const lead = cur.length > 0 && !cur.endsWith("\n") ? "\n" : "";
      const next = `${cur}${lead}${prefix}${placeholder}`;
      rawRef.current = next;
      setRaw(next);
      return;
    }
    const start = el.selectionStart ?? cur.length;
    const end = el.selectionEnd ?? cur.length;
    if (end > start) {
      const lineStart = cur.lastIndexOf("\n", Math.max(0, start - 1)) + 1;
      const selectedBlock = cur.slice(lineStart, end);
      const prefixed = selectedBlock
        .split("\n")
        .map((line) => `${prefix}${line}`)
        .join("\n");
      const next = `${cur.slice(0, lineStart)}${prefixed}${cur.slice(end)}`;
      rawRef.current = next;
      setRaw(next);
      requestAnimationFrame(() => {
        el.focus();
        el.setSelectionRange(lineStart + prefixed.length, lineStart + prefixed.length);
      });
      return;
    }
    const before = cur.slice(0, start);
    const needNewline = before.length > 0 && !before.endsWith("\n");
    const inserted = `${needNewline ? "\n" : ""}${prefix}${placeholder}`;
    const next = `${before}${inserted}${cur.slice(end)}`;
    rawRef.current = next;
    setRaw(next);
    requestAnimationFrame(() => {
      el.focus();
      const phStart = start + inserted.length - placeholder.length;
      el.setSelectionRange(phStart, phStart + placeholder.length);
    });
  }, []);

  const insertTextAtCursor = useCallback((text: string) => {
    if (!text) return;
    const el = bodyTextareaRef.current;
    const cur = rawRef.current;
    if (!el) {
      const next = `${cur}${text}`;
      rawRef.current = next;
      setRaw(next);
      return;
    }
    const start = el.selectionStart ?? cur.length;
    const end = el.selectionEnd ?? cur.length;
    const next = `${cur.slice(0, start)}${text}${cur.slice(end)}`;
    rawRef.current = next;
    setRaw(next);
    requestAnimationFrame(() => {
      el.focus();
      const cursor = start + text.length;
      el.setSelectionRange(cursor, cursor);
    });
  }, []);

  const wrapSelectionWithUrl = useCallback((url: string): boolean => {
    const el = bodyTextareaRef.current;
    const cur = rawRef.current;
    if (!el) return false;
    const start = el.selectionStart ?? 0;
    const end = el.selectionEnd ?? 0;
    if (end <= start) return false;
    const selected = cur.slice(start, end);
    if (!selected.trim() || /^https?:\/\/\S+$/i.test(selected.trim()) || selected.includes("\n")) {
      return false;
    }
    const wrapped = `[${selected}](${url})`;
    const next = `${cur.slice(0, start)}${wrapped}${cur.slice(end)}`;
    rawRef.current = next;
    setRaw(next);
    requestAnimationFrame(() => {
      el.focus();
      const cursor = start + wrapped.length;
      el.setSelectionRange(cursor, cursor);
    });
    return true;
  }, []);

  const uploadPreparedFiles = useCallback(async (files: PreparedUploadFile[]) => {
    if (files.length === 0) return;
    setSubmitError(null);

    // 1. Insert instant inline placeholders at the exact cursor position (official Discourse UX)
    const entries = files.map((file) => {
      uploadSeqRef.current += 1;
      const tag = `uploading-${uploadSeqRef.current}`;
      const placeholder = buildUploadPlaceholder(file.filename, file.isImage, tag);
      if (file.localPreviewUrl) {
        createdBlobUrlsRef.current.push(file.localPreviewUrl);
      }
      return { file, placeholder };
    });

    const el = bodyTextareaRef.current;
    const cur = rawRef.current;
    const start = el ? (el.selectionStart ?? cur.length) : cur.length;
    const end = el ? (el.selectionEnd ?? cur.length) : cur.length;
    const before = cur.slice(0, start);
    const after = cur.slice(end);
    const needLeadNl = before.length > 0 && !before.endsWith("\n");
    const needTrailNl = after.length > 0 && !after.startsWith("\n");
    const block = `${needLeadNl ? "\n" : ""}${entries.map((e) => e.placeholder).join("\n")}${needTrailNl ? "\n" : "\n"}`;
    const nextWithPlaceholders = `${before}${block}${after}`;
    rawRef.current = nextWithPlaceholders;
    setRaw(nextWithPlaceholders);

    if (el) {
      requestAnimationFrame(() => {
        el.focus();
        const cursor = start + block.length;
        el.setSelectionRange(cursor, cursor);
      });
    }

    setUploadingCount((c) => c + entries.length);
    const total = entries.length;
    let completed = 0;

    try {
      for (const { file, placeholder } of entries) {
        completed += 1;
        setUploadProgressLabel(
          total > 1 ? `正在上传 (${completed}/${total})...` : "正在上传..."
        );
        try {
          const uploaded = await uploadMediaToSite(
            file.filename,
            file.mime || "application/octet-stream",
            file.dataBase64
          );
          const snippet = formatUploadedMarkdown(uploaded, file.isImage);
          const safeName = uploaded.originalFilename.replace(/[[\]|]/g, "_");
          if (file.isImage && file.localPreviewUrl) {
            const previewUrl = file.localPreviewUrl;
            setUploadedPreviewMap((prev) => ({
              ...prev,
              [uploaded.shortUrl]: previewUrl,
              ...(uploaded.url ? { [uploaded.url]: previewUrl } : {}),
            }));
          }
          setRaw((prev) => {
            const next = prev.includes(placeholder)
              ? prev.replace(placeholder, snippet)
              : `${prev}${prev.endsWith("\n") || !prev ? "" : "\n"}${snippet}\n`;
            rawRef.current = next;
            return next;
          });
          if (file.isImage && uploaded.width > 0) {
            setUploadedImg({
              filename: safeName,
              shortUrl: uploaded.shortUrl,
              width: uploaded.width,
              height: uploaded.height,
              snippet,
              scale: "100%",
            });
          }
        } catch (err: any) {
          setRaw((prev) => {
            const next = prev
              .replace(`\n${placeholder}\n`, "\n")
              .replace(`${placeholder}\n`, "")
              .replace(`\n${placeholder}`, "")
              .replace(placeholder, "");
            rawRef.current = next;
            return next;
          });
          if (err instanceof SiteError && err.isVerification) {
            setSubmitError("安全确认未完成，图片未上传");
          } else {
            setSubmitError(err?.message || "上传失败，请重试");
          }
        } finally {
          setUploadingCount((c) => Math.max(0, c - 1));
        }
      }
    } finally {
      setUploadProgressLabel("");
    }
  }, []);

  // Listen for native OS file drag-and-drop (Finder / Desktop / Explorer) via Tauri 2 webview dragDropEvent
  useEffect(() => {
    let active = true;
    let unlisten: (() => void) | null = null;
    void (async () => {
      try {
        const webview = getCurrentWebview();
        const off = await webview.onDragDropEvent((event) => {
          if (!active) return;
          const payload = event.payload;
          if (payload.type === "enter" || payload.type === "over") {
            setIsDragActive(true);
          } else if (payload.type === "leave") {
            setIsDragActive(false);
          } else if (payload.type === "drop") {
            setIsDragActive(false);
            if (payload.paths && payload.paths.length > 0) {
              void prepareDroppedPaths(payload.paths).then((prepared) => {
                if (active && prepared.length > 0) {
                  void uploadPreparedFiles(prepared);
                }
              });
            }
          }
        });
        if (active) {
          unlisten = off;
        } else {
          off();
        }
      } catch {
        // Non-Tauri environment fallback uses HTML5 onDrop
      }
    })();
    return () => {
      active = false;
      unlisten?.();
    };
  }, [uploadPreparedFiles]);

  const handleFileChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const fileList = e.target.files ? Array.from(e.target.files) : [];
    e.target.value = "";
    if (fileList.length === 0) return;
    const prepared = await Promise.all(fileList.map((f, idx) => fileToPreparedUpload(f, idx)));
    void uploadPreparedFiles(prepared);
  };

  const handleComposerDragOver = (e: React.DragEvent) => {
    if (e.dataTransfer?.types?.includes("Files")) {
      e.preventDefault();
      e.dataTransfer.dropEffect = "copy";
      if (!isDragActive) setIsDragActive(true);
    }
  };

  const handleComposerDragLeave = (e: React.DragEvent) => {
    if (e.currentTarget === e.target) {
      setIsDragActive(false);
    }
  };

  const handleComposerDrop = (e: React.DragEvent) => {
    const files = extractSyncClipboardFiles(e.dataTransfer);
    setIsDragActive(false);
    if (files.length === 0) return;
    e.preventDefault();
    void Promise.all(files.map((f, idx) => fileToPreparedUpload(f, idx))).then((prepared) => {
      if (prepared.length > 0) {
        void uploadPreparedFiles(prepared);
      }
    });
  };

  const previewHtml = useMemo(() => {
    if (viewMode === "SOURCE" || !raw.trim()) return "";
    return prepareCookedHtml(renderDiscourseMarkdownPreview(raw, uploadedPreviewMap));
  }, [viewMode, raw, uploadedPreviewMap]);

  useEffect(() => {
    if (viewMode === "SOURCE" || !previewHtml) return;
    const cleanupImgs = observeCookedForumImages(previewContainerRef.current);
    const cleanupRich = observeCookedRichContent(previewContainerRef.current);
    return () => {
      cleanupImgs();
      cleanupRich();
    };
  }, [viewMode, previewHtml]);

  const executePublish = async () => {
    setSubmitting(true);
    setSubmitError(null);
    const now = Date.now();
    const composerOpenDurationMs = Math.max(1000, now - composerOpenedAtRef.current);
    const typingDurationMs = firstTypedAtRef.current
      ? Math.max(800, Math.min(composerOpenDurationMs, now - firstTypedAtRef.current))
      : Math.max(800, Math.min(composerOpenDurationMs, raw.length * 45));
    try {
      if (request.action === "NEW_TOPIC") {
        const res = await publishTopic({
          title,
          raw,
          categoryId,
          tags,
          typingDurationMs,
          composerOpenDurationMs,
        });
        await deleteLocalDraftInRust(draftKey).catch(() => {});
        onPublished({ topicId: res.topicId, pendingReview: res.pendingReview });
      } else if (request.action === "REPLY" && request.topicId) {
        const res = await publishReply({
          topicId: request.topicId,
          raw,
          replyToPostNumber: request.replyToPostNumber,
          typingDurationMs,
          composerOpenDurationMs,
        });
        await deleteLocalDraftInRust(draftKey).catch(() => {});
        onPublished({
          topicId: request.topicId,
          post: res.post,
          pendingReview: res.pendingReview,
        });
      } else if (request.action === "EDIT" && request.postId && request.topicId) {
        const updated = await updateExistingPost({
          postId: request.postId,
          topicId: request.topicId,
          raw,
          title: isEditingOp ? title : undefined,
          categoryId: isEditingOp ? categoryId : undefined,
          tags: isEditingOp ? tags : undefined,
          editReason: editReason.trim() || undefined,
        });
        onPublished({ topicId: request.topicId, post: updated });
      }
    } catch (err: any) {
      if (
        !(err instanceof SiteError && err.isVerification) ||
        (err.message && (err.message.includes("冷却") || err.message.includes("拦截")))
      ) {
        setSubmitError(err?.message || "提交未完成，内容已保留在本地");
      }
    } finally {
      setSubmitting(false);
    }
  };

  const handleAttemptSubmit = async () => {
    if (submitting || uploading || loadingRawEdit || tagsLoading) return;
    if (titleErrorMsg || tagErrorMsg || bodyErrorMsg) {
      setSubmitAttempted(true);
      if (titleErrorMsg) {
        titleInputRef.current?.focus();
      } else if (tagErrorMsg) {
        setActivePicker("TAG");
      } else if (bodyErrorMsg) {
        setViewMode("SOURCE");
        bodyTextareaRef.current?.focus();
      }
      return;
    }

    // Linux.do site rule: publishing a new topic with `抽奖` tag costs 20 community points
    if (
      isNewTopic &&
      tags.some((t) => t.trim().toLowerCase() === "抽奖") &&
      !showLotteryConfirm
    ) {
      setShowLotteryConfirm(true);
      return;
    }

    await executePublish();
  };

  // Tag options combined with selected tags + search filtering (1:1 aligned with ComposerTagPicker in ComposerPickers.kt)
  const cleanTagQuery = tagQuery.trim().replace(/^#/, "").trim();
  const mergedTagOptions: TagChoiceOption[] = useMemo(() => {
    const map = new Map<string, TagChoiceOption>();
    for (const name of tags) {
      const match = tagChoices.options.find((o) => o.name.toLowerCase() === name.toLowerCase());
      map.set(name.toLowerCase(), match ?? { name, count: 0 });
    }
    for (const opt of tagChoices.options) {
      if (!map.has(opt.name.toLowerCase())) {
        map.set(opt.name.toLowerCase(), opt);
      }
    }
    const base = Array.from(map.values());
    if (!cleanTagQuery) return base;
    const qLower = cleanTagQuery.toLowerCase();
    return base
      .filter((o) => o.name.toLowerCase().includes(qLower))
      .sort((a, b) => {
        const exactA = a.name.toLowerCase() === qLower ? 1 : 0;
        const exactB = b.name.toLowerCase() === qLower ? 1 : 0;
        if (exactA !== exactB) return exactB - exactA;
        const startA = a.name.toLowerCase().startsWith(qLower) ? 1 : 0;
        const startB = b.name.toLowerCase().startsWith(qLower) ? 1 : 0;
        if (startA !== startB) return startB - startA;
        return b.count - a.count;
      });
  }, [tags, tagChoices.options, cleanTagQuery]);

  const canCreateNewTag =
    Boolean(tagChoices.canCreateTag) &&
    cleanTagQuery.length > 0 &&
    !mergedTagOptions.some((o) => o.name.toLowerCase() === cleanTagQuery.toLowerCase()) &&
    !tagChoices.forbiddenMessage &&
    !tagChoices.requiredGroup &&
    tags.length < MAX_TAGS;

  const categoryRows = useMemo(() => {
    const rows: CategoryMeta[][] = [];
    for (let i = 0; i < postableTopCategories.length; i += 3) {
      rows.push(postableTopCategories.slice(i, i + 3));
    }
    return rows;
  }, [postableTopCategories]);

  const tagRows = useMemo(() => {
    const rows: TagChoiceOption[][] = [];
    for (let i = 0; i < mergedTagOptions.length; i += 3) {
      rows.push(mergedTagOptions.slice(i, i + 3));
    }
    return rows;
  }, [mergedTagOptions]);

  const headerTitle =
    request.action === "NEW_TOPIC"
      ? "新话题"
      : isEditingOp
        ? "编辑话题"
        : request.action === "EDIT"
          ? `编辑回复 · #${request.postNumber || 1} 楼`
          : request.replyToPostNumber
            ? isDrawer
              ? `回复 ${request.replyToDisplayName || `@${request.replyToUsername || ""}`} · #${request.replyToPostNumber} 楼`
              : "回复"
            : "回复话题";

  const actionLabel =
    request.action === "NEW_TOPIC" ? "发布" : request.action === "EDIT" ? "保存" : "发送";

  const catVis = resolveCategoryVisual(activeCategory?.name || "", activeCategory?.colorHex);

  const editorCore = (
    <>
      {/* 抽屉模式顶部 120fps 可拖拽调节高度手柄（双击恢复默认高度） */}
      {isDrawer && (
        <div
          onMouseDown={startDrawerResize}
          onDoubleClick={() => {
            setDrawerHeight(320);
            if (drawerRef.current) drawerRef.current.style.height = "320px";
            try {
              localStorage.setItem("lindo_composer_drawer_h", "320");
            } catch {
              // ignore
            }
          }}
          title="上下拖拽调节回复区高度（双击恢复默认）"
          className="group absolute inset-x-0 -top-[3px] z-30 flex h-[7px] items-center justify-center"
        >
          <div
            className="h-[1px] w-full opacity-0 transition-opacity group-hover:opacity-100"
            style={{ backgroundColor: "var(--brand-gold)" }}
          />
          <div
            className="pointer-events-none absolute top-[2.5px] h-[3px] w-9 rounded-full opacity-55 transition-opacity group-hover:opacity-100"
            style={{ backgroundColor: "var(--ink-tertiary)" }}
          />
        </div>
      )}

      {/* 1. 顶部操作栏（弹窗与半屏抽屉统一清爽无红绿灯占位） */}
      <header
        className={`flex shrink-0 items-center justify-between gap-3 ${
          isDrawer ? "lindo-hairline-b h-[44px] px-4" : "h-[50px] px-5"
        }`}
      >
        <div className="flex min-w-0 items-center gap-2">
          {!isDrawer && (
            <button
              type="button"
              onClick={() => requestClose()}
              disabled={submitting}
              title="关闭"
              className="lindo-interactive flex h-7 w-7 shrink-0 items-center justify-center rounded-full"
              style={{ color: "var(--ink-secondary)" }}
            >
              <LinDoIcons.Close size={16} />
            </button>
          )}
          <span
            className={`truncate font-semibold ${isDrawer ? "text-[14px]" : "text-[15px]"}`}
            style={{ color: "var(--ink-primary)" }}
          >
            {headerTitle}
          </span>
          {isDrawer && request.replyToSnippet && (
            <span
              className="hidden sm:inline-block max-w-[260px] truncate text-[12px]"
              style={{ color: "var(--ink-tertiary)" }}
            >
              · {request.replyToSnippet}
            </span>
          )}
        </div>

        <div className="flex shrink-0 items-center gap-1.5">
          {/* 编辑 / 分栏 / 预览 切换胶囊 */}
          <div
            className="flex items-center rounded-lg p-0.5 text-[12px]"
            style={{
              backgroundColor: "var(--subtle-surface)",
              border: "0.5px solid var(--hairline)",
            }}
          >
            {(
              [
                { key: "SOURCE", label: "编辑" },
                { key: "SPLIT", label: "分栏" },
                { key: "PREVIEW", label: "预览" },
              ] as const
            ).map((mode) => {
              const active = viewMode === mode.key;
              return (
                <button
                  key={mode.key}
                  type="button"
                  onClick={() => setViewMode(mode.key)}
                  className="flex items-center gap-1 rounded-md px-2.5 py-1 font-medium transition-colors cursor-pointer"
                  style={{
                    backgroundColor: active ? "var(--card-surface)" : "transparent",
                    color: active ? "var(--ink-primary)" : "var(--ink-tertiary)",
                  }}
                >
                  {mode.key === "SPLIT" && <LinDoIcons.ColumnsSplit size={12.5} />}
                  <span>{mode.label}</span>
                </button>
              );
            })}
          </div>

          {onTogglePresentation && (
            <button
              type="button"
              onClick={onTogglePresentation}
              title={isDrawer ? "展开为居中弹窗" : "停靠为底部半屏抽屉"}
              className="lindo-interactive flex h-7 w-7 items-center justify-center rounded-full"
              style={{ color: "var(--ink-secondary)" }}
            >
              {isDrawer ? <LinDoIcons.Maximize size={15} /> : <LinDoIcons.Minimize size={15} />}
            </button>
          )}

          {isDrawer && (
            <button
              type="button"
              onClick={() => requestClose()}
              disabled={submitting}
              title="收起并保留草稿"
              className="lindo-interactive flex h-7 w-7 items-center justify-center rounded-full"
              style={{ color: "var(--ink-secondary)" }}
            >
              <LinDoIcons.Close size={15} />
            </button>
          )}

          <button
            type="button"
            disabled={submitting || uploading || loadingRawEdit}
            onClick={handleAttemptSubmit}
            className="ml-0.5 rounded-full px-4 py-1 text-[13px] font-semibold transition-opacity hover:opacity-90 cursor-pointer disabled:opacity-45"
            style={{
              backgroundColor: "var(--publish-btn-bg)",
              color: "var(--publish-btn-fg)",
            }}
          >
            {submitting ? "提交中..." : actionLabel}
          </button>
        </div>
      </header>

      {/* 回复 / 编辑回复上下文条（居中弹窗下清晰展示所属话题与引用摘要） */}
      {!isDrawer &&
        (request.action === "REPLY" || (request.action === "EDIT" && !isEditingOp)) &&
        (request.replyToPostNumber || request.topicTitle) && (() => {
          const isFloorReply = request.action === "REPLY" && Boolean(request.replyToPostNumber);
          const replyDisplay =
            request.replyToDisplayName?.trim() ||
            (request.replyToUsername ? `@${request.replyToUsername}` : "");
          const quoteText = isFloorReply
            ? (request.replyToSnippet || "").trim() || (request.topicTitle || "")
            : (request.topicTitle || "");
          const canExpandQuote = isFloorReply && quoteText.length > 68;
          return (
            <div className="lindo-hairline-b shrink-0 px-5 pb-3 pt-0.5">
              <div
                role={canExpandQuote ? "button" : undefined}
                tabIndex={canExpandQuote ? 0 : undefined}
                onClick={() => {
                  if (canExpandQuote) setReplyQuoteExpanded((v) => !v);
                }}
                onKeyDown={(e) => {
                  if (canExpandQuote && (e.key === "Enter" || e.key === " ")) {
                    e.preventDefault();
                    setReplyQuoteExpanded((v) => !v);
                  }
                }}
                className={`rounded-lg border-l-[2.5px] px-3 py-2.5 transition-colors ${
                  canExpandQuote ? "cursor-pointer" : ""
                }`}
                style={{
                  backgroundColor: "var(--subtle-surface)",
                  borderLeftColor: "var(--brand-gold)",
                }}
              >
                <div className="flex items-center justify-between gap-2 text-[12px]">
                  <div className="flex min-w-0 items-center gap-1.5">
                    {isFloorReply && request.replyToUsername ? (
                      <>
                        <AvatarWithFlair
                          username={request.replyToUsername}
                          avatarTemplate={request.replyToAvatarTemplate || ""}
                          animatedAvatar={request.replyToAnimatedAvatar}
                          sizePx={19}
                          className="shrink-0"
                        />
                        <span className="shrink-0" style={{ color: "var(--ink-tertiary)" }}>
                          回复
                        </span>
                        <span
                          className="truncate font-semibold"
                          style={{ color: "var(--ink-primary)" }}
                        >
                          {replyDisplay}
                        </span>
                        <span
                          className="shrink-0 tabular-nums"
                          style={{ color: "var(--ink-tertiary)" }}
                        >
                          · #{request.replyToPostNumber} 楼
                        </span>
                      </>
                    ) : (
                      <>
                        <span
                          className="shrink-0 font-medium"
                          style={{ color: "var(--ink-tertiary)" }}
                        >
                          {request.action === "EDIT" ? "所属话题" : "回复话题"}
                        </span>
                        {request.topicTitle && (
                          <span
                            className="truncate font-semibold"
                            style={{ color: "var(--ink-primary)" }}
                          >
                            {request.topicTitle}
                          </span>
                        )}
                      </>
                    )}
                  </div>
                  {canExpandQuote && (
                    <span
                      className="inline-flex shrink-0 items-center gap-0.5 text-[11px]"
                      style={{ color: "var(--ink-tertiary)" }}
                    >
                      {replyQuoteExpanded ? (
                        <LinDoIcons.ChevronUp size={13} />
                      ) : (
                        <LinDoIcons.ChevronDown size={13} />
                      )}
                    </span>
                  )}
                </div>
                {isFloorReply && quoteText && (
                  <p
                    className={`mt-1.5 pl-[25px] text-[12.5px] leading-[1.52] ${
                      replyQuoteExpanded ? "line-clamp-6" : "line-clamp-2"
                    }`}
                    style={{ color: "var(--ink-secondary)" }}
                  >
                    {quoteText}
                  </p>
                )}
              </div>
            </div>
          );
        })()}

      {/* 2. 新建/编辑话题元数据单行栏 */}
      {hasMetaControls && (
        <>
          <div className="lindo-hairline-b flex shrink-0 flex-wrap items-center gap-2.5 px-5 py-2.5">
            {/* 版块选择器触发（1:1 对齐移动端 ComposerSheet 官方矢量版块图标） */}
            {(() => {
              const activeOffVis = resolveOfficialCategoryVisual(
                activeCategory?.name || "",
                catVis.dotColor,
                undefined,
                activeCategory?.readRestricted
              );
              const ActiveCatIcon = activeOffVis.Icon;
              return (
                <button
                  type="button"
                  onClick={() => {
                    setFocusedParentId(activeCategory?.parentId ?? activeCategory?.id ?? 4);
                    setActivePicker((p) => (p === "CATEGORY" ? "NONE" : "CATEGORY"));
                  }}
                  className="lindo-interactive flex items-center gap-1.5 rounded-md px-2 py-1 text-[13.5px] font-semibold"
                  style={{ color: "var(--ink-primary)" }}
                >
                  <ActiveCatIcon size={13} style={{ color: activeOffVis.color }} />
                  {activeCategory?.readRestricted && (
                    <LinDoIcons.FaLock size={10.5} style={{ color: "var(--ink-secondary)" }} />
                  )}
                  <span>{activeCategory?.displayName || "选择版块"}</span>
                  <LinDoIcons.ChevronDown size={12.5} style={{ color: "var(--ink-secondary)" }} />
                </button>
              );
            })()}

            <span className="h-3.5 w-[0.5px]" style={{ backgroundColor: "var(--hairline)" }} />

            {/* 已选标签（1:1 对齐移动端官方矢量标签图标） */}
            {tags.map((tg) => {
              const tVis = resolveOfficialTagVisual(tg);
              const TagIcon = tVis ? tVis.Icon : LinDoIcons.FaTag;
              return (
                <button
                  key={tg}
                  type="button"
                  onClick={() => setTags((prev) => prev.filter((x) => x !== tg))}
                  className="lindo-interactive flex items-center gap-1.5 rounded-md px-2 py-0.5 text-[12px] font-medium"
                  style={{
                    backgroundColor: "var(--tonal-pill)",
                    color: "var(--ink-primary)",
                  }}
                >
                  <TagIcon
                    size={10.5}
                    style={{ color: tVis ? tVis.color : "var(--ink-tertiary)" }}
                  />
                  <span>{tg}</span>
                  <LinDoIcons.Close size={11} style={{ color: "var(--ink-tertiary)" }} />
                </button>
              );
            })}

            {/* 标签触发器胶囊 */}
            <button
              type="button"
              onClick={() => setActivePicker((p) => (p === "TAG" ? "NONE" : "TAG"))}
              className="lindo-interactive flex items-center gap-1 rounded-md px-2.5 py-1 text-[13px]"
              style={{
                backgroundColor: showTagError ? "var(--error-bg)" : "transparent",
                border: showTagError ? "0.5px solid var(--error-border)" : "0.5px solid transparent",
                color: showTagError ? "var(--error-fg)" : "var(--ink-secondary)",
                fontWeight: showTagError ? 600 : 500,
              }}
            >
              <LinDoIcons.Plus size={13} />
              <span>
                {showTagError
                  ? tagErrorMsg
                  : tags.length === 0
                    ? "选择标签"
                    : `管理标签 (${tags.length}/${MAX_TAGS})`}
              </span>
            </button>
          </div>

          {/* 展开版块 Bento 3 列选择面板（1:1 对齐 ComposerPickers.kt：官方矢量图标 + 中文正统名称 + 主版块/Lv1-Lv3 切换条） */}
          {activePicker === "CATEGORY" && (
            <div
              className="lindo-hairline-b shrink-0 px-5 py-3"
              style={{ backgroundColor: "var(--subtle-surface)" }}
            >
              <div className="mb-2 flex items-center justify-between">
                <span className="text-[14px] font-bold" style={{ color: "var(--ink-primary)" }}>
                  选择发布版块
                </span>
                <button
                  type="button"
                  onClick={() => setActivePicker("NONE")}
                  className="lindo-interactive rounded-full p-1"
                  style={{ color: "var(--ink-secondary)" }}
                >
                  <LinDoIcons.Close size={15} />
                </button>
              </div>

              <div className="lindo-divider" />

              {/* 二级子版块 / 信任等级单行内联选择条 */}
              {focusedParent && focusedSubCategories.length > 0 && (
                <>
                  <div className="no-scrollbar flex items-center gap-4 overflow-x-auto py-2">
                    <span
                      className="shrink-0 text-[12px] font-medium"
                      style={{ color: "var(--ink-secondary)" }}
                    >
                      {focusedParent.name}：
                    </span>
                    <button
                      type="button"
                      onClick={() => {
                        if (focusedParent.id !== categoryId) {
                          setCategoryId(focusedParent.id);
                          setTags([]);
                        }
                        setActivePicker("NONE");
                      }}
                      className="relative flex shrink-0 flex-col items-center px-1.5 py-0.5 cursor-pointer"
                    >
                      <span
                        className="flex items-center gap-1 text-[12.5px]"
                        style={{
                          fontWeight: categoryId === focusedParent.id ? 700 : 500,
                          color:
                            categoryId === focusedParent.id
                              ? "var(--active-chip-text)"
                              : "var(--ink-primary)",
                        }}
                      >
                        {focusedParent.readRestricted && <LinDoIcons.FaLock size={10} />}
                        <span>主版块</span>
                      </span>
                      <span
                        className="mt-0.5 h-[2px] w-3.5 rounded-full"
                        style={{
                          backgroundColor:
                            categoryId === focusedParent.id ? "var(--tab-indicator)" : "transparent",
                        }}
                      />
                    </button>

                    {focusedSubCategories.map((sub) => {
                      const subSelected = categoryId === sub.id;
                      return (
                        <button
                          key={sub.id}
                          type="button"
                          onClick={() => {
                            if (sub.id !== categoryId) {
                              setCategoryId(sub.id);
                              setTags([]);
                            }
                            setActivePicker("NONE");
                          }}
                          className="relative flex shrink-0 flex-col items-center px-1.5 py-0.5 cursor-pointer"
                        >
                          <span
                            className="flex items-center gap-1 text-[12.5px]"
                            style={{
                              fontWeight: subSelected ? 700 : 500,
                              color: subSelected ? "var(--active-chip-text)" : "var(--ink-primary)",
                            }}
                          >
                            {(sub.readRestricted || sub.trustLevel != null) && (
                              <LinDoIcons.FaLock size={10} />
                            )}
                            <span>{sub.subLabel || sub.name}</span>
                          </span>
                          <span
                            className="mt-0.5 h-[2px] w-3.5 rounded-full"
                            style={{
                              backgroundColor: subSelected ? "var(--tab-indicator)" : "transparent",
                            }}
                          />
                        </button>
                      );
                    })}
                  </div>
                  <div className="lindo-divider" />
                </>
              )}

              {/* 3 列 Bento 网格 + 0.5px 精细分隔线 */}
              <div
                className="mt-2 max-h-[248px] rounded-xl overflow-hidden lindo-scroll"
                style={{
                  backgroundColor: "var(--card-surface)",
                  border: "0.5px solid var(--hairline)",
                }}
              >
                {categoryRows.map((row, rowIdx) => (
                  <React.Fragment key={rowIdx}>
                    <div className="grid grid-cols-3">
                      {row.map((cat, colIdx) => {
                        const subs = getPostableSubsForParent(cat.id);
                        const activeSub = subs.find((s) => s.id === categoryId);
                        const selected = categoryId === cat.id || Boolean(activeSub);
                        const cv = resolveCategoryVisual(cat.name, cat.colorHex);
                        const offVis = resolveOfficialCategoryVisual(
                          cat.name,
                          cv.dotColor,
                          undefined,
                          cat.readRestricted
                        );
                        const CatIcon = offVis.Icon;
                        const lvs = subs
                          .map((s) => s.trustLevel)
                          .filter((n): n is number => typeof n === "number")
                          .sort((a, b) => a - b);
                        const subHint =
                          lvs.length >= 2
                            ? `Lv${lvs[0]}–Lv${lvs[lvs.length - 1]}`
                            : subs.length > 0
                              ? subs.map((s) => s.subLabel || s.name).join("/")
                              : null;

                        return (
                          <div key={cat.id} className="relative flex items-center">
                            <button
                              type="button"
                              onClick={() => {
                                if (subs.length > 0 && focusedParentId !== cat.id) {
                                  setFocusedParentId(cat.id);
                                  if (cat.id !== categoryId) {
                                    setCategoryId(cat.id);
                                    setTags([]);
                                  }
                                } else {
                                  if (cat.id !== categoryId) {
                                    setCategoryId(cat.id);
                                    setTags([]);
                                  }
                                  setActivePicker("NONE");
                                }
                              }}
                              className="lindo-interactive flex h-[44px] w-full items-center justify-between gap-2 px-3.5 text-left"
                              style={{
                                backgroundColor: selected
                                  ? "color-mix(in srgb, var(--brand-gold) 8%, transparent)"
                                  : "transparent",
                              }}
                            >
                              <span className="flex min-w-0 items-center gap-2">
                                <CatIcon size={12.5} style={{ color: offVis.color }} />
                                {cat.readRestricted && (
                                  <LinDoIcons.FaLock
                                    size={10}
                                    style={{ color: "var(--ink-tertiary)" }}
                                  />
                                )}
                                <span
                                  className="truncate text-[13.5px]"
                                  style={{
                                    fontWeight: selected ? 700 : 500,
                                    color: selected ? "var(--active-chip-text)" : "var(--ink-primary)",
                                  }}
                                >
                                  {cat.name}
                                </span>
                              </span>

                              {activeSub ? (
                                <span
                                  className="flex shrink-0 items-center gap-0.5 text-[11px] font-bold"
                                  style={{ color: "var(--active-chip-text)" }}
                                >
                                  {activeSub.readRestricted && <LinDoIcons.FaLock size={9} />}
                                  <span>{activeSub.subLabel || activeSub.name}</span>
                                </span>
                              ) : subHint ? (
                                <span
                                  className="shrink-0 text-[10.8px]"
                                  style={{
                                    color: selected ? "var(--active-chip-text)" : "var(--ink-tertiary)",
                                  }}
                                >
                                  {subHint}
                                </span>
                              ) : categoryId === cat.id ? (
                                <LinDoIcons.Check
                                  size={14}
                                  style={{ color: "var(--active-chip-text)" }}
                                />
                              ) : null}
                            </button>
                            {colIdx < 2 && (
                              <span
                                className="h-5 w-[0.5px] shrink-0"
                                style={{ backgroundColor: "var(--hairline)" }}
                              />
                            )}
                          </div>
                        );
                      })}
                    </div>
                    {rowIdx < categoryRows.length - 1 && <div className="lindo-divider" />}
                  </React.Fragment>
                ))}
              </div>
            </div>
          )}

          {/* 展开标签 Bento 3 列选择面板（1:1 对齐 ComposerTagPicker） */}
          {activePicker === "TAG" && (
            <div
              className="lindo-hairline-b shrink-0 px-5 py-3 flex flex-col"
              style={{ backgroundColor: "var(--subtle-surface)" }}
            >
              <div className="mb-2 flex items-center justify-between gap-2">
                <div className="flex min-w-0 items-center gap-2 text-[13px]">
                  <span className="text-[14px] font-bold" style={{ color: "var(--ink-primary)" }}>
                    选择话题标签
                  </span>
                  <span style={{ color: "var(--ink-secondary)" }}>
                    已选 {tags.length}/{MAX_TAGS}
                  </span>
                  {(tagChoices.forbiddenMessage || tagChoices.requiredGroup) && (
                    <span className="truncate font-medium" style={{ color: "var(--brand-gold)" }}>
                      ·{" "}
                      {tagChoices.forbiddenMessage ||
                        `需选标签组：${tagChoices.requiredGroup}`}
                    </span>
                  )}
                </div>
                <button
                  type="button"
                  onClick={() => setActivePicker("NONE")}
                  className="lindo-interactive rounded-full p-1"
                  style={{ color: "var(--ink-secondary)" }}
                >
                  <LinDoIcons.Close size={15} />
                </button>
              </div>

              <div
                className="mb-2.5 flex h-[36px] items-center gap-2 rounded-lg px-3"
                style={{
                  backgroundColor: "var(--card-surface)",
                  border: "0.5px solid var(--hairline)",
                }}
              >
                <LinDoIcons.Search size={14.5} style={{ color: "var(--ink-tertiary)" }} />
                <input
                  type="text"
                  value={tagQuery}
                  onChange={(e) => setTagQuery(e.target.value)}
                  placeholder="搜索标签..."
                  className="flex-1 bg-transparent text-[13px] outline-none"
                  style={{ color: "var(--ink-primary)" }}
                />
                {tagQuery && (
                  <button
                    type="button"
                    onClick={() => setTagQuery("")}
                    className="rounded-full p-0.5 opacity-70 hover:opacity-100 cursor-pointer"
                  >
                    <LinDoIcons.Close size={12.5} style={{ color: "var(--ink-tertiary)" }} />
                  </button>
                )}
              </div>

              <div
                className="max-h-[220px] rounded-xl overflow-hidden lindo-scroll"
                style={{
                  backgroundColor: "var(--card-surface)",
                  border: "0.5px solid var(--hairline)",
                }}
              >
                {tagsLoading ? (
                  <div
                    className="py-8 text-center text-[12.5px]"
                    style={{ color: "var(--ink-secondary)" }}
                  >
                    正在加载当前版块可用标签...
                  </div>
                ) : tagsError ? (
                  <div className="flex flex-col items-center justify-center gap-2 py-7 text-center">
                    <span className="text-[12.5px]" style={{ color: "var(--ink-secondary)" }}>
                      {tagsError}
                    </span>
                    <button
                      type="button"
                      onClick={() => {
                        if (tagsError.includes("验证")) onTriggerVerify();
                        else setTagsRetryNonce((n) => n + 1);
                      }}
                      className="inline-flex items-center gap-1 rounded-full px-3.5 py-1 text-[12px] font-semibold cursor-pointer"
                      style={{
                        backgroundColor: "var(--publish-btn-bg)",
                        color: "var(--publish-btn-fg)",
                      }}
                    >
                      <LinDoIcons.Refresh size={12.5} />
                      <span>{tagsError.includes("验证") ? "去验证" : "重试"}</span>
                    </button>
                  </div>
                ) : (
                  <>
                    {canCreateNewTag && (
                      <>
                        <button
                          type="button"
                          onClick={() => {
                            const newTag = cleanTagQuery.slice(0, 20);
                            setTags((prev) => [...prev, newTag]);
                            setTagQuery("");
                          }}
                          className="lindo-interactive flex h-[42px] w-full items-center gap-2 px-3.5 text-left text-[13px] font-semibold"
                          style={{ color: "var(--brand-gold)" }}
                        >
                          <LinDoIcons.Plus size={14} />
                          <span>创建新标签「{cleanTagQuery.slice(0, 20)}」</span>
                        </button>
                        <div className="lindo-divider" />
                      </>
                    )}

                    {tagRows.length === 0 && !canCreateNewTag ? (
                      <div
                        className="py-8 text-center text-[12.5px]"
                        style={{ color: "var(--ink-tertiary)" }}
                      >
                        {cleanTagQuery
                          ? `未找到匹配「${cleanTagQuery}」的可选标签`
                          : "当前版块暂无可选标签"}
                      </div>
                    ) : (
                      tagRows.map((row, rIdx) => (
                        <React.Fragment key={rIdx}>
                          <div className="grid grid-cols-3">
                            {row.map((opt, cIdx) => {
                              const checked = tags.includes(opt.name);
                              const canClick =
                                checked || (!opt.disabled && tags.length < MAX_TAGS);
                              const tVis = resolveOfficialTagVisual(opt.name);
                              const TagIcon = tVis ? tVis.Icon : LinDoIcons.FaTag;
                              return (
                                <div key={opt.name} className="relative flex items-center">
                                  <button
                                    type="button"
                                    disabled={!canClick}
                                    title={opt.reason || opt.name}
                                    onClick={() => {
                                      if (checked) {
                                        setTags((prev) => prev.filter((x) => x !== opt.name));
                                      } else if (tags.length < MAX_TAGS) {
                                        setTags((prev) => [...prev, opt.name]);
                                      }
                                    }}
                                    className="lindo-interactive flex h-[42px] w-full items-center justify-between gap-2 px-3.5 text-left disabled:opacity-40"
                                    style={{
                                      backgroundColor: checked
                                        ? "color-mix(in srgb, var(--brand-gold) 10%, transparent)"
                                        : "transparent",
                                    }}
                                  >
                                    <span className="flex min-w-0 items-center gap-1.5">
                                      <TagIcon
                                        size={11.5}
                                        style={{
                                          color: checked
                                            ? "var(--brand-gold)"
                                            : tVis
                                              ? tVis.color
                                              : "var(--ink-tertiary)",
                                        }}
                                      />
                                      <span
                                        className="truncate text-[13px]"
                                        style={{
                                          fontWeight: checked ? 700 : 500,
                                          color: checked
                                            ? "var(--brand-gold)"
                                            : "var(--ink-primary)",
                                        }}
                                      >
                                        {opt.name}
                                      </span>
                                    </span>

                                    {checked ? (
                                      <LinDoIcons.Check
                                        size={14}
                                        style={{ color: "var(--brand-gold)" }}
                                      />
                                    ) : opt.count > 0 ? (
                                      <span
                                        className="shrink-0 text-[11px] tabular-nums"
                                        style={{ color: "var(--ink-tertiary)" }}
                                      >
                                        {formatCompactCount(opt.count)}
                                      </span>
                                    ) : null}
                                  </button>
                                  {cIdx < 2 && (
                                    <span
                                      className="h-5 w-[0.5px] shrink-0"
                                      style={{ backgroundColor: "var(--hairline)" }}
                                    />
                                  )}
                                </div>
                              );
                            })}
                          </div>
                          {rIdx < tagRows.length - 1 && <div className="lindo-divider" />}
                        </React.Fragment>
                      ))
                    )}
                  </>
                )}
              </div>
            </div>
          )}

          {/* 3. 无边框话题标题栏 */}
          <div
            className="flex shrink-0 items-center gap-3 px-5 py-2.5 transition-colors"
            style={{
              borderBottom: showTitleError
                ? "0.5px solid var(--error-border)"
                : "0.5px solid var(--hairline)",
            }}
          >
            <input
              ref={titleInputRef}
              type="text"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              onKeyDown={(e) => {
                if (isImeComposingEvent(e)) return;
                if ((e.metaKey || e.ctrlKey) && e.key === "Enter") {
                  e.preventDefault();
                  handleAttemptSubmit();
                } else if (e.key === "Enter") {
                  e.preventDefault();
                  if (viewMode === "PREVIEW") setViewMode("SOURCE");
                  bodyTextareaRef.current?.focus();
                }
              }}
              placeholder="话题标题"
              autoFocus={isNewTopic}
              className="flex-1 bg-transparent text-[18px] font-bold outline-none"
              style={{ color: "var(--ink-primary)" }}
            />
            {showTitleError && (
              <span className="shrink-0 text-[12px] font-medium" style={{ color: "var(--error-fg)" }}>
                {titleErrorMsg}
              </span>
            )}
          </div>
        </>
      )}

      {/* 4. Markdown 创作工具栏（编辑与分栏模式均可用，no-scrollbar 绝不出现黑底滚动条！） */}
      {viewMode !== "PREVIEW" && (
        <div
          className={`flex shrink-0 items-center justify-between transition-colors ${
            isDrawer ? "px-3.5 py-1" : "px-4 py-1.5"
          }`}
          style={{
            borderBottom: showBodyError
              ? "0.5px solid var(--error-border)"
              : "0.5px solid var(--hairline)",
          }}
        >
          <div className="no-scrollbar flex items-center gap-0.5 overflow-x-auto">
            <input
              ref={fileInputRef}
              type="file"
              accept="image/*,*/*"
              multiple
              className="hidden"
              onChange={handleFileChange}
            />
            <button
              type="button"
              title="上传图片或附件（支持粘贴 / 拖拽）"
              onClick={() => fileInputRef.current?.click()}
              className="lindo-interactive flex h-9 w-9 shrink-0 items-center justify-center rounded-full"
              style={{ color: uploading ? "var(--brand-gold)" : "var(--ink-secondary)" }}
            >
              <LinDoIcons.Image size={17.5} />
            </button>
            <button
              type="button"
              title="插入表情与社区热梗"
              onClick={() => setActivePicker((p) => (p === "EMOJI" ? "NONE" : "EMOJI"))}
              className="lindo-interactive flex h-9 w-9 shrink-0 items-center justify-center rounded-full"
              style={{ color: activePicker === "EMOJI" ? "var(--brand-gold)" : "var(--ink-secondary)" }}
            >
              <LinDoIcons.Smile size={17.5} />
            </button>
            <button
              type="button"
              title="搜索并提及 @用户"
              onClick={() => setActivePicker((p) => (p === "MENTION" ? "NONE" : "MENTION"))}
              className="lindo-interactive flex h-9 w-9 shrink-0 items-center justify-center rounded-full"
              style={{
                color: activePicker === "MENTION" ? "var(--brand-gold)" : "var(--ink-secondary)",
              }}
            >
              <LinDoIcons.AtSign size={17.5} />
            </button>
            <button
              type="button"
              title={isNonMac ? "插入链接或引用帖子 (Ctrl+K)" : "插入链接或引用帖子 (⌘+K)"}
              onClick={() => setActivePicker((p) => (p === "LINK" ? "NONE" : "LINK"))}
              className="lindo-interactive flex h-9 w-9 shrink-0 items-center justify-center rounded-full"
              style={{ color: activePicker === "LINK" ? "var(--brand-gold)" : "var(--ink-secondary)" }}
            >
              <LinDoIcons.Link size={17.5} />
            </button>
            <button
              type="button"
              title={isNonMac ? "粗体 (Ctrl+B)" : "粗体 (⌘+B)"}
              onClick={() => insertAroundSelection("**", "**", "粗体文字")}
              className="lindo-interactive flex h-9 w-9 shrink-0 items-center justify-center rounded-full"
              style={{ color: "var(--ink-secondary)" }}
            >
              <LinDoIcons.Bold size={17.5} />
            </button>
            <button
              type="button"
              title={isNonMac ? "斜体 (Ctrl+I)" : "斜体 (⌘+I)"}
              onClick={() => insertAroundSelection("*", "*", "斜体文字")}
              className="lindo-interactive flex h-9 w-9 shrink-0 items-center justify-center rounded-full"
              style={{ color: "var(--ink-secondary)" }}
            >
              <LinDoIcons.Italic size={17.5} />
            </button>
            <button
              type="button"
              title="删除线"
              onClick={() => insertAroundSelection("~~", "~~", "删除文字")}
              className="lindo-interactive flex h-9 shrink-0 items-center justify-center rounded-lg px-2 text-[12.5px] font-medium line-through"
              style={{ color: "var(--ink-secondary)" }}
            >
              S
            </button>
            <button
              type="button"
              title="二级标题"
              onClick={() => prefixSelectedLines("## ", "小节标题")}
              className="lindo-interactive flex h-9 shrink-0 items-center justify-center rounded-lg px-2 text-[12px] font-semibold"
              style={{ color: "var(--ink-secondary)" }}
            >
              H2
            </button>
            <button
              type="button"
              title={isNonMac ? "代码 / 代码块 (Ctrl+E)" : "代码 / 代码块 (⌘+E)"}
              onClick={() => {
                const el = bodyTextareaRef.current;
                const sel =
                  el && el.selectionEnd > el.selectionStart
                    ? rawRef.current.slice(el.selectionStart, el.selectionEnd)
                    : "";
                if (sel && !sel.includes("\n")) {
                  insertAroundSelection("`", "`");
                } else if (sel) {
                  insertBlockSnippet(`\`\`\`\n${sel}\n\`\`\``);
                } else {
                  insertBlockSnippet("```\n代码内容\n```", "代码内容");
                }
              }}
              className="lindo-interactive flex h-9 w-9 shrink-0 items-center justify-center rounded-full"
              style={{ color: "var(--ink-secondary)" }}
            >
              <LinDoIcons.Code size={17.5} />
            </button>
            <button
              type="button"
              title="引用块"
              onClick={() => prefixSelectedLines("> ", "引用内容")}
              className="lindo-interactive flex h-9 w-9 shrink-0 items-center justify-center rounded-full"
              style={{ color: "var(--ink-secondary)" }}
            >
              <LinDoIcons.Quote size={17.5} />
            </button>
            <button
              type="button"
              title="无序列表"
              onClick={() => prefixSelectedLines("- ", "列表项")}
              className="lindo-interactive flex h-9 w-9 shrink-0 items-center justify-center rounded-full"
              style={{ color: "var(--ink-secondary)" }}
            >
              <LinDoIcons.ListBullet size={17.5} />
            </button>
            <button
              type="button"
              title="有序列表"
              onClick={() => prefixSelectedLines("1. ", "步骤内容")}
              className="lindo-interactive flex h-9 shrink-0 items-center justify-center rounded-lg px-2 text-[12px] font-medium tabular-nums"
              style={{ color: "var(--ink-secondary)" }}
            >
              1.
            </button>
            <button
              type="button"
              title="任务清单"
              onClick={() => prefixSelectedLines("- [ ] ", "待办事项")}
              className="lindo-interactive flex h-9 shrink-0 items-center justify-center rounded-lg px-2 text-[12px] font-medium"
              style={{ color: "var(--ink-secondary)" }}
            >
              ☑
            </button>
            <button
              type="button"
              title="插入提示卡片 (Obsidian Callout)"
              onClick={() =>
                insertBlockSnippet("> [!Note]+ 提示标题\n> 在此输入提示内容", "提示标题")
              }
              className="lindo-interactive flex h-9 shrink-0 items-center justify-center rounded-lg px-2 text-[12px] font-medium"
              style={{ color: "var(--ink-secondary)" }}
            >
              提示框
            </button>
            <button
              type="button"
              title="插入表格"
              onClick={() =>
                insertBlockSnippet(
                  "| 列一 | 列二 |\n| --- | --- |\n| 内容 | 内容 |",
                  "列一"
                )
              }
              className="lindo-interactive flex h-9 w-9 shrink-0 items-center justify-center rounded-full"
              style={{ color: "var(--ink-secondary)" }}
            >
              <LinDoIcons.Table size={17.5} />
            </button>
            <button
              type="button"
              title="插入投票"
              onClick={() =>
                insertBlockSnippet(
                  "[poll type=regular results=always public=true chartType=bar]\n* 选项一\n* 选项二\n[/poll]",
                  "选项一"
                )
              }
              className="lindo-interactive flex h-9 w-9 shrink-0 items-center justify-center rounded-full"
              style={{ color: "var(--ink-secondary)" }}
            >
              <LinDoIcons.BarChartPoll size={17.5} />
            </button>
            <button
              type="button"
              title="折叠详情"
              onClick={() =>
                insertBlockSnippet(
                  '[details="摘要"]\n详细内容\n[/details]',
                  "摘要"
                )
              }
              className="lindo-interactive flex h-9 w-9 shrink-0 items-center justify-center rounded-full"
              style={{ color: "var(--ink-secondary)" }}
            >
              <LinDoIcons.ChevronDown size={16.5} />
            </button>
            <button
              type="button"
              title="剧透模糊"
              onClick={() => insertAroundSelection("[spoiler]", "[/spoiler]", "剧透内容")}
              className="lindo-interactive flex h-9 w-9 shrink-0 items-center justify-center rounded-full"
              style={{ color: "var(--ink-secondary)" }}
            >
              <LinDoIcons.EyeOff size={17.5} />
            </button>
            <button
              type="button"
              title="盘古之白 · 自动优化中英数字空格"
              onClick={() => setRaw((prev) => optimizePanguSpacing(prev))}
              className="lindo-interactive flex h-9 shrink-0 items-center gap-1 rounded-lg px-2 text-[12px] font-medium"
              style={{ color: "var(--ink-secondary)" }}
            >
              <LinDoIcons.TypeAa size={16} />
              <span>中英空格</span>
            </button>
          </div>

          <div className="flex shrink-0 items-center gap-2 pl-2">
            {uploading && (
              <span className="text-[12px] tabular-nums" style={{ color: "var(--brand-gold)" }}>
                {uploadProgressLabel || "正在上传..."}
              </span>
            )}
            {showBodyError && (
              <span className="text-[12px] font-medium" style={{ color: "var(--error-fg)" }}>
                {bodyErrorMsg}
              </span>
            )}
          </div>
        </div>
      )}

      {/* 表情与社区热梗快捷面板 */}
      {activePicker === "EMOJI" && (
        <div
          className="lindo-hairline-b shrink-0 space-y-2 px-5 py-2.5"
          style={{ backgroundColor: "var(--subtle-surface)" }}
        >
          <div className="flex flex-wrap gap-1">
            {QUICK_EMOJIS.map((em, idx) => (
              <button
                key={`${em}_${idx}`}
                type="button"
                onClick={() => {
                  insertAroundSelection(em);
                  setActivePicker("NONE");
                }}
                className="rounded-lg p-1.5 text-[18px] hover:scale-125 transition-transform cursor-pointer"
              >
                {em}
              </button>
            ))}
          </div>
          <div className="lindo-divider" />
          <div className="flex flex-wrap items-center gap-1.5 pt-0.5">
            {SITE_SHORTCODE_EMOJIS.map((sc) => (
              <button
                key={sc.code}
                type="button"
                onClick={() => {
                  insertAroundSelection(` ${sc.code} `);
                  setActivePicker("NONE");
                }}
                className="lindo-interactive rounded-md px-2 py-1 text-[11.8px]"
                style={{
                  backgroundColor: "var(--card-surface)",
                  color: "var(--ink-secondary)",
                  border: "0.5px solid var(--hairline)",
                }}
              >
                {sc.label} <span style={{ color: "var(--ink-tertiary)" }}>{sc.code}</span>
              </button>
            ))}
          </div>
        </div>
      )}

      {/* @提及用户实时搜索面板 */}
      {activePicker === "MENTION" && (
        <div
          className="lindo-hairline-b shrink-0 space-y-2 px-5 py-2.5"
          style={{ backgroundColor: "var(--subtle-surface)" }}
        >
          <div
            className="flex h-[34px] max-w-sm items-center gap-2 rounded-lg px-2.5"
            style={{
              backgroundColor: "var(--card-surface)",
              border: "0.5px solid var(--hairline)",
            }}
          >
            <LinDoIcons.AtSign size={14} style={{ color: "var(--brand-gold)" }} />
            <input
              type="text"
              autoFocus
              value={mentionQuery}
              onChange={(e) => setMentionQuery(e.target.value)}
              placeholder="输入用户名或昵称搜索..."
              className="flex-1 bg-transparent text-[12.8px] outline-none"
              style={{ color: "var(--ink-primary)" }}
            />
          </div>
          <div className="no-scrollbar flex items-center gap-2 overflow-x-auto py-0.5">
            {mentionLoading ? (
              <span className="text-[12px]" style={{ color: "var(--ink-tertiary)" }}>
                搜索用户中...
              </span>
            ) : mentionHits.length === 0 ? (
              <span className="text-[12px]" style={{ color: "var(--ink-tertiary)" }}>
                输入关键词查找要 @ 的社区成员
              </span>
            ) : (
              mentionHits.map((u) => (
                <button
                  key={u.username}
                  type="button"
                  onClick={() => {
                    insertAroundSelection(`@${u.username} `);
                    setMentionQuery("");
                    setActivePicker("NONE");
                  }}
                  className="lindo-interactive flex shrink-0 items-center gap-2 rounded-full px-2.5 py-1 text-[12px]"
                  style={{
                    backgroundColor: "var(--card-surface)",
                    border: "0.5px solid var(--hairline)",
                    color: "var(--ink-primary)",
                  }}
                >
                  {u.isGroup ? (
                    <LinDoIcons.Users size={14} style={{ color: "var(--brand-gold)" }} />
                  ) : (
                    <AvatarWithFlair
                      username={u.username}
                      avatarTemplate={u.avatarTemplate}
                      sizePx={18}
                    />
                  )}
                  <span className="font-semibold">@{u.username}</span>
                  {u.name && <span style={{ color: "var(--ink-tertiary)" }}>{u.name}</span>}
                </button>
              ))
            )}
          </div>
        </div>
      )}

      {/* 插入外部链接 / 搜索并引用社区帖子双模式面板 */}
      {activePicker === "LINK" && (
        <div
          className="lindo-hairline-b shrink-0 space-y-2.5 px-5 py-2.5"
          style={{ backgroundColor: "var(--subtle-surface)" }}
        >
          <div className="flex items-center gap-2 text-[12px]">
            {[
              { key: "URL", label: "外部链接" },
              { key: "TOPIC", label: "引用社区帖子" },
            ].map((m) => (
              <button
                key={m.key}
                type="button"
                onClick={() => setLinkMode(m.key as any)}
                className="rounded-full px-3 py-0.5 font-medium cursor-pointer"
                style={{
                  backgroundColor:
                    linkMode === m.key ? "var(--active-chip-bg)" : "var(--card-surface)",
                  color:
                    linkMode === m.key ? "var(--active-chip-text)" : "var(--ink-secondary)",
                }}
              >
                {m.label}
              </button>
            ))}
          </div>

          {linkMode === "URL" ? (
            <div className="flex flex-wrap items-center gap-2.5">
              <input
                type="text"
                value={linkUrl}
                onChange={(e) => {
                  setLinkUrl(e.target.value);
                  setLinkAttemptedError(false);
                }}
                placeholder="https://..."
                className="h-8 w-64 rounded-lg bg-transparent px-2.5 text-[12.5px] outline-none"
                style={{
                  backgroundColor: "var(--card-surface)",
                  border: linkAttemptedError
                    ? "0.5px solid var(--error-border)"
                    : "0.5px solid var(--hairline)",
                  color: "var(--ink-primary)",
                }}
              />
              <input
                type="text"
                value={linkText}
                onChange={(e) => setLinkText(e.target.value)}
                placeholder="显示文本（选填）"
                className="h-8 w-44 rounded-lg bg-transparent px-2.5 text-[12.5px] outline-none"
                style={{
                  backgroundColor: "var(--card-surface)",
                  border: "0.5px solid var(--hairline)",
                  color: "var(--ink-primary)",
                }}
              />
              <button
                type="button"
                onClick={() => {
                  const addr = linkUrl.trim();
                  const normalized =
                    addr && !/^https?:\/\//i.test(addr) ? `https://${addr}` : addr;
                  try {
                    const u = new URL(normalized);
                    if (!u.hostname) throw new Error("invalid");
                    const label = linkText.trim() || normalized;
                    insertAroundSelection(`[${label}](<${normalized}>) `);
                    setLinkUrl("");
                    setLinkText("");
                    setActivePicker("NONE");
                  } catch {
                    setLinkAttemptedError(true);
                  }
                }}
                className="rounded-lg px-3 py-1.5 text-[12.5px] font-semibold cursor-pointer"
                style={{
                  backgroundColor: "var(--publish-btn-bg)",
                  color: "var(--publish-btn-fg)",
                }}
              >
                插入链接
              </button>
              {linkAttemptedError && (
                <span className="text-[12px]" style={{ color: "var(--error-fg)" }}>
                  请输入有效链接地址
                </span>
              )}
            </div>
          ) : (
            <div className="space-y-2">
              <div
                className="flex h-[34px] max-w-md items-center gap-2 rounded-lg px-2.5"
                style={{
                  backgroundColor: "var(--card-surface)",
                  border: "0.5px solid var(--hairline)",
                }}
              >
                <LinDoIcons.Search size={14} style={{ color: "var(--ink-tertiary)" }} />
                <input
                  type="text"
                  autoFocus
                  value={topicSearchQuery}
                  onChange={(e) => setTopicSearchQuery(e.target.value)}
                  placeholder="输入关键词搜索要引用的社区话题..."
                  className="flex-1 bg-transparent text-[12.5px] outline-none"
                  style={{ color: "var(--ink-primary)" }}
                />
              </div>
              {topicSearchLoading ? (
                <div className="text-[12px]" style={{ color: "var(--ink-tertiary)" }}>
                  正在检索话题...
                </div>
              ) : (
                topicSearchHits.length > 0 && (
                  <div
                    className="max-h-[150px] rounded-lg overflow-hidden lindo-scroll divide-y"
                    style={{
                      backgroundColor: "var(--card-surface)",
                      border: "0.5px solid var(--hairline)",
                    }}
                  >
                    {topicSearchHits.map((hit) => (
                      <button
                        key={hit.id}
                        type="button"
                        onClick={() => {
                          const targetUrl =
                            hit.postNumber > 1
                              ? `https://linux.do/t/topic/${hit.topicId}/${hit.postNumber}`
                              : `https://linux.do/t/topic/${hit.topicId}`;
                          insertAroundSelection(`[${hit.title}](${targetUrl}) `);
                          setTopicSearchQuery("");
                          setActivePicker("NONE");
                        }}
                        className="lindo-interactive flex w-full items-center justify-between gap-2 px-3 py-2 text-left text-[12.5px]"
                      >
                        <span className="truncate font-medium" style={{ color: "var(--ink-primary)" }}>
                          {hit.title}
                        </span>
                        <span className="shrink-0 text-[11px]" style={{ color: "var(--ink-tertiary)" }}>
                          @{hit.username}
                        </span>
                      </button>
                    ))}
                  </div>
                )
              )}
            </div>
          )}
        </div>
      )}

      {/* 最近上传图片缩放快捷条 (100% / 75% / 50%) — 图片 markdown 被删除后自动立即隐藏 */}
      {activeUploadedImg && (
        <div
          className="lindo-hairline-b flex shrink-0 items-center justify-between px-5 py-1.5 text-[12px]"
          style={{ backgroundColor: "var(--subtle-surface)" }}
        >
          <span className="flex min-w-0 items-center gap-1.5" style={{ color: "var(--ink-secondary)" }}>
            <LinDoIcons.Check size={13.5} className="shrink-0" style={{ color: "var(--brand-gold)" }} />
            <span className="truncate">已插入图片 {activeUploadedImg.filename}</span>
          </span>
          <div className="flex shrink-0 items-center gap-1.5">
            {(["100%", "75%", "50%"] as const).map((pct) => {
              const isSelectedScale = activeUploadedImg.scale === pct;
              return (
                <button
                  key={pct}
                  type="button"
                  onClick={() => {
                    const nextTag =
                      pct === "100%"
                        ? `![${activeUploadedImg.filename}|${activeUploadedImg.width}x${activeUploadedImg.height}](${activeUploadedImg.shortUrl})`
                        : `![${activeUploadedImg.filename}|${activeUploadedImg.width}x${activeUploadedImg.height}, ${pct}](${activeUploadedImg.shortUrl})`;
                    const escapedUrl = activeUploadedImg.shortUrl.replace(
                      /[.*+?^${}()|[\]\\]/g,
                      "\\$&"
                    );
                    const imgTagRe = new RegExp(`!\\[[^\\]]*\\]\\(${escapedUrl}\\)`);
                    setRaw((prev) => {
                      const next = prev.includes(activeUploadedImg.snippet)
                        ? prev.replace(activeUploadedImg.snippet, nextTag)
                        : prev.replace(imgTagRe, nextTag);
                      rawRef.current = next;
                      return next;
                    });
                    setUploadedImg({ ...activeUploadedImg, snippet: nextTag, scale: pct });
                  }}
                  className="lindo-interactive rounded px-2 py-0.5 font-medium cursor-pointer"
                  style={{
                    backgroundColor: isSelectedScale
                      ? "var(--active-chip-bg)"
                      : "var(--tonal-pill)",
                    color: isSelectedScale
                      ? "var(--active-chip-text)"
                      : "var(--ink-secondary)",
                  }}
                >
                  {pct}
                </button>
              );
            })}
            <button
              type="button"
              onClick={() => setUploadedImg(null)}
              className="p-0.5 opacity-60 hover:opacity-100 cursor-pointer"
            >
              <LinDoIcons.Close size={13} />
            </button>
          </div>
        </div>
      )}

      {/* 提交错误反馈条 */}
      {submitError && (
        <div
          className="shrink-0 px-5 py-2 text-[12.5px]"
          style={{
            backgroundColor: "var(--error-bg)",
            borderBottom: "0.5px solid var(--error-border)",
            color: "var(--error-fg)",
          }}
        >
          {submitError}
        </div>
      )}

      {/* 5. 编辑画布 / 双栏实时对照预览 / 纯预览 */}
      <div
        className="relative flex flex-1 min-h-0"
        onDragOver={handleComposerDragOver}
        onDragLeave={handleComposerDragLeave}
        onDrop={handleComposerDrop}
      >
        {isDragActive && (
          <div
            className="pointer-events-none absolute inset-2 z-20 flex flex-col items-center justify-center gap-2 rounded-xl"
            style={{
              backgroundColor: "color-mix(in srgb, var(--card-surface) 90%, transparent)",
              border: "1.5px dashed var(--brand-gold)",
            }}
          >
            <LinDoIcons.Image size={24} style={{ color: "var(--brand-gold)" }} />
            <span className="text-[13px] font-medium" style={{ color: "var(--ink-primary)" }}>
              松开鼠标上传图片或附件
            </span>
          </div>
        )}
        {loadingRawEdit ? (
          <div
            className="flex h-full w-full items-center justify-center text-[13px]"
            style={{ color: "var(--ink-secondary)" }}
          >
            正在读取原帖源码...
          </div>
        ) : (
          <>
            {viewMode !== "PREVIEW" && (
              <textarea
                ref={bodyTextareaRef}
                value={raw}
                onChange={(e) => {
                  if (firstTypedAtRef.current === null) {
                    firstTypedAtRef.current = Date.now();
                  }
                  const val = e.target.value;
                  setRaw(val);
                  const caret = e.target.selectionStart ?? val.length;
                  const beforeCaret = val.slice(0, caret);
                  const mentionMatch = beforeCaret.match(/(?:^|[\s(（【,，])@([A-Za-z0-9_.-]{1,24})$/);
                  if (mentionMatch) {
                    const q = mentionMatch[1];
                    const startIdx = caret - q.length - 1;
                    setInlineSuggest((prev) => ({
                      kind: "MENTION",
                      query: q,
                      startIdx,
                      endIdx: caret,
                      items: prev?.kind === "MENTION" ? prev.items : [],
                      active: 0,
                    }));
                    return;
                  }
                  const emojiMatch = beforeCaret.match(/(?:^|[\s(（【]):([a-z0-9_+-]{2,20})$/i);
                  if (emojiMatch) {
                    const q = emojiMatch[1].toLowerCase();
                    const matches = SITE_SHORTCODE_EMOJIS.filter(
                      (sc) => sc.code.toLowerCase().includes(q) || sc.label.includes(q)
                    ).slice(0, 6);
                    if (matches.length > 0) {
                      setInlineSuggest({
                        kind: "EMOJI",
                        query: q,
                        startIdx: caret - emojiMatch[1].length - 1,
                        endIdx: caret,
                        items: matches,
                        active: 0,
                      });
                      return;
                    }
                  }
                  setInlineSuggest(null);
                }}
                onScroll={(e) => {
                  if (viewMode !== "SPLIT") return;
                  const ta = e.currentTarget;
                  const pv = previewContainerRef.current;
                  if (!pv) return;
                  const taMax = ta.scrollHeight - ta.clientHeight;
                  const pvMax = pv.scrollHeight - pv.clientHeight;
                  if (taMax > 0 && pvMax > 0) {
                    pv.scrollTop = (ta.scrollTop / taMax) * pvMax;
                  }
                }}
                onPaste={(e) =>
                  handleComposerTextareaPaste(e, {
                    textarea: bodyTextareaRef.current,
                    value: rawRef.current,
                    onUploadFiles: (files) => {
                      void uploadPreparedFiles(files);
                    },
                    onInsertText: insertTextAtCursor,
                    onWrapSelectionWithUrl: wrapSelectionWithUrl,
                  })
                }
                placeholder={
                  request.action === "NEW_TOPIC"
                    ? `写下正文（支持粘贴或拖拽图片、键入 @提及 / :表情、Markdown 与 Discourse 语法，${submitShortcutLabel} 快速提交）`
                    : request.action === "EDIT"
                      ? `修改正文（支持粘贴或拖拽图片，${submitShortcutLabel} 快速保存）`
                      : `写下回复（支持粘贴或拖拽图片、键入 @提及 / :表情，${submitShortcutLabel} 快速发送）`
                }
                autoFocus={!isNewTopic}
                onKeyDown={(e) => {
                  if (isImeComposingEvent(e)) return;
                  if (inlineSuggest && inlineSuggest.items.length > 0) {
                    if (e.key === "ArrowDown") {
                      e.preventDefault();
                      setInlineSuggest((prev) =>
                        prev ? { ...prev, active: (prev.active + 1) % prev.items.length } : null
                      );
                      return;
                    }
                    if (e.key === "ArrowUp") {
                      e.preventDefault();
                      setInlineSuggest((prev) =>
                        prev
                          ? { ...prev, active: (prev.active - 1 + prev.items.length) % prev.items.length }
                          : null
                      );
                      return;
                    }
                    if (e.key === "Enter" || e.key === "Tab") {
                      e.preventDefault();
                      const chosen = inlineSuggest.items[inlineSuggest.active];
                      if (chosen) {
                        const replacement =
                          inlineSuggest.kind === "MENTION"
                            ? `@${(chosen as MentionUserCandidate).username} `
                            : `${(chosen as { code: string }).code} `;
                        const cur = rawRef.current;
                        const next = `${cur.slice(0, inlineSuggest.startIdx)}${replacement}${cur.slice(inlineSuggest.endIdx)}`;
                        const nextCaret = inlineSuggest.startIdx + replacement.length;
                        rawRef.current = next;
                        setRaw(next);
                        setInlineSuggest(null);
                        requestAnimationFrame(() => {
                          bodyTextareaRef.current?.focus();
                          bodyTextareaRef.current?.setSelectionRange(nextCaret, nextCaret);
                        });
                      }
                      return;
                    }
                    if (e.key === "Escape") {
                      e.preventDefault();
                      e.stopPropagation();
                      setInlineSuggest(null);
                      return;
                    }
                  }
                  const mod = e.metaKey || e.ctrlKey;
                  if (mod && e.key === "Enter") {
                    e.preventDefault();
                    handleAttemptSubmit();
                    return;
                  }
                  if (mod && !e.altKey) {
                    const keyLower = e.key.toLowerCase();
                    if (keyLower === "b") {
                      e.preventDefault();
                      insertAroundSelection("**", "**");
                      return;
                    }
                    if (keyLower === "i") {
                      e.preventDefault();
                      insertAroundSelection("*", "*");
                      return;
                    }
                    if (keyLower === "k") {
                      e.preventDefault();
                      const el = bodyTextareaRef.current;
                      const sel =
                        el && el.selectionEnd > el.selectionStart
                          ? rawRef.current.slice(el.selectionStart, el.selectionEnd)
                          : "";
                      if (sel && !sel.includes("\n")) {
                        setLinkText(sel);
                      }
                      setLinkMode("URL");
                      setActivePicker("LINK");
                      return;
                    }
                    if (keyLower === "e") {
                      e.preventDefault();
                      const el = bodyTextareaRef.current;
                      const sel =
                        el && el.selectionEnd > el.selectionStart
                          ? rawRef.current.slice(el.selectionStart, el.selectionEnd)
                          : "";
                      if (sel.includes("\n")) {
                        insertAroundSelection("\n```\n", "\n```\n");
                      } else {
                        insertAroundSelection("`", "`");
                      }
                      return;
                    }
                    if (e.shiftKey && keyLower === "c") {
                      e.preventDefault();
                      insertAroundSelection("\n```\n", "\n```\n");
                      return;
                    }
                  }
                  if (e.key === "Tab") {
                    e.preventDefault();
                    const el = bodyTextareaRef.current;
                    if (!el) return;
                    const cur = rawRef.current;
                    const start = el.selectionStart ?? 0;
                    const end = el.selectionEnd ?? 0;
                    if (e.shiftKey) {
                      const lineStart = cur.lastIndexOf("\n", Math.max(0, start - 1)) + 1;
                      const linePrefix = cur.slice(lineStart, lineStart + 2);
                      if (linePrefix === "  " || linePrefix.startsWith("\t")) {
                        const removeLen = linePrefix.startsWith("\t") ? 1 : 2;
                        const next = `${cur.slice(0, lineStart)}${cur.slice(lineStart + removeLen)}`;
                        rawRef.current = next;
                        setRaw(next);
                        requestAnimationFrame(() => {
                          const s = Math.max(lineStart, start - removeLen);
                          const en = Math.max(s, end - removeLen);
                          el.setSelectionRange(s, en);
                        });
                      }
                    } else {
                      insertTextAtCursor("  ");
                    }
                    return;
                  }
                  if (e.key === "Enter" && !e.shiftKey && !mod && !e.altKey) {
                    const el = bodyTextareaRef.current;
                    if (el && el.selectionStart === el.selectionEnd) {
                      const pos = el.selectionStart ?? 0;
                      const cur = rawRef.current;
                      const lineStart = cur.lastIndexOf("\n", Math.max(0, pos - 1)) + 1;
                      const currentLine = cur.slice(lineStart, pos);
                      const ulMatch = currentLine.match(/^(\s*)([-*+])\s(.*)$/);
                      const olMatch = currentLine.match(/^(\s*)(\d+)\.\s(.*)$/);
                      if (ulMatch) {
                        e.preventDefault();
                        const [, indent, marker, rest] = ulMatch;
                        if (!rest.trim()) {
                          const next = `${cur.slice(0, lineStart)}${cur.slice(pos)}`;
                          rawRef.current = next;
                          setRaw(next);
                          requestAnimationFrame(() => el.setSelectionRange(lineStart, lineStart));
                        } else {
                          insertTextAtCursor(`\n${indent}${marker} `);
                        }
                        return;
                      }
                      if (olMatch) {
                        e.preventDefault();
                        const [, indent, numStr, rest] = olMatch;
                        if (!rest.trim()) {
                          const next = `${cur.slice(0, lineStart)}${cur.slice(pos)}`;
                          rawRef.current = next;
                          setRaw(next);
                          requestAnimationFrame(() => el.setSelectionRange(lineStart, lineStart));
                        } else {
                          insertTextAtCursor(`\n${indent}${Number(numStr) + 1}. `);
                        }
                        return;
                      }
                    }
                  }
                }}
                className={`lindo-scroll h-full resize-none bg-transparent text-[15px] leading-[1.72] outline-none select-text ${
                  viewMode === "SPLIT" ? "w-1/2 lindo-hairline-r" : "w-full"
                } ${isDrawer ? "px-4 py-3" : "px-5 py-4"}`}
                style={{ color: "var(--ink-body)" }}
              />
            )}

            {viewMode !== "SOURCE" && (
              <div
                ref={previewContainerRef}
                onClick={(e) => {
                  const target = e.target as HTMLElement;
                  const spoiled = target.closest<HTMLElement>(".spoiled");
                  if (spoiled) {
                    spoiled.classList.toggle("revealed");
                    return;
                  }
                  if (target.closest("a")) {
                    e.preventDefault();
                  }
                }}
                className={`lindo-scroll h-full ${
                  viewMode === "SPLIT" ? "w-1/2" : "w-full"
                } ${isDrawer ? "px-4 py-3" : "px-5 py-4"}`}
                style={
                  viewMode === "SPLIT"
                    ? { backgroundColor: "color-mix(in srgb, var(--subtle-surface) 38%, transparent)" }
                    : undefined
                }
              >
                {previewHtml ? (
                  <div
                    className="lindo-cooked"
                    dangerouslySetInnerHTML={{
                      __html: previewHtml,
                    }}
                  />
                ) : (
                  <p className="text-[13px]" style={{ color: "var(--ink-tertiary)" }}>
                    {viewMode === "SPLIT"
                      ? "右侧将实时渲染 Markdown 预览..."
                      : "暂无内容可预览"}
                  </p>
                )}
              </div>
            )}
          </>
        )}
      </div>

      {/* 键入 @ / : 自动补全候选条（支持 ↑/↓/Enter/Tab 快捷上屏） */}
      {inlineSuggest && inlineSuggest.items.length > 0 && (
        <div
          className="flex shrink-0 items-center gap-1.5 overflow-x-auto px-5 py-1.5 no-scrollbar"
          style={{
            borderTop: "0.5px solid var(--hairline-soft)",
            backgroundColor: "var(--subtle-surface)",
          }}
        >
          <span className="shrink-0 text-[11px]" style={{ color: "var(--ink-tertiary)" }}>
            {inlineSuggest.kind === "MENTION" ? "提及成员" : "社区表情"}
          </span>
          {inlineSuggest.items.map((item, idx) => {
            const active = idx === inlineSuggest.active;
            const applyChoice = () => {
              const replacement =
                inlineSuggest.kind === "MENTION"
                  ? `@${(item as MentionUserCandidate).username} `
                  : `${(item as { code: string }).code} `;
              const cur = rawRef.current;
              const next = `${cur.slice(0, inlineSuggest.startIdx)}${replacement}${cur.slice(inlineSuggest.endIdx)}`;
              const nextCaret = inlineSuggest.startIdx + replacement.length;
              rawRef.current = next;
              setRaw(next);
              setInlineSuggest(null);
              requestAnimationFrame(() => {
                bodyTextareaRef.current?.focus();
                bodyTextareaRef.current?.setSelectionRange(nextCaret, nextCaret);
              });
            };
            if (inlineSuggest.kind === "MENTION") {
              const u = item as MentionUserCandidate;
              return (
                <button
                  key={u.username}
                  type="button"
                  onMouseDown={(e) => {
                    e.preventDefault();
                    applyChoice();
                  }}
                  className="inline-flex shrink-0 items-center gap-1.5 rounded-full px-2.5 py-0.5 text-[11.8px] cursor-pointer"
                  style={{
                    backgroundColor: active ? "var(--active-chip-bg)" : "var(--card-surface)",
                    color: active ? "var(--active-chip-text)" : "var(--ink-primary)",
                    border: "0.5px solid var(--hairline)",
                  }}
                >
                  <AvatarWithFlair
                    username={u.username}
                    avatarTemplate={u.avatarTemplate}
                    sizePx={15}
                  />
                  <span className="font-medium">@{u.username}</span>
                </button>
              );
            }
            const sc = item as { code: string; label: string };
            return (
              <button
                key={sc.code}
                type="button"
                onMouseDown={(e) => {
                  e.preventDefault();
                  applyChoice();
                }}
                className="inline-flex shrink-0 items-center gap-1 rounded-full px-2.5 py-0.5 text-[11.8px] cursor-pointer"
                style={{
                  backgroundColor: active ? "var(--active-chip-bg)" : "var(--card-surface)",
                  color: active ? "var(--active-chip-text)" : "var(--ink-primary)",
                  border: "0.5px solid var(--hairline)",
                }}
              >
                <span>{sc.label}</span>
                <span style={{ color: "var(--ink-tertiary)" }}>{sc.code}</span>
              </button>
            );
          })}
          <span className="ml-auto shrink-0 text-[10.5px]" style={{ color: "var(--ink-tertiary)" }}>
            ↑↓ 选择 · Tab/Enter 补全
          </span>
        </div>
      )}

      {/* 编辑模式可选修改说明栏 */}
      {request.action === "EDIT" && (
        <div
          className="flex shrink-0 items-center gap-2 px-5 py-1.5"
          style={{
            borderTop: "0.5px solid var(--hairline-soft)",
            backgroundColor: "color-mix(in srgb, var(--subtle-surface) 45%, transparent)",
          }}
        >
          <span className="shrink-0 text-[11.5px]" style={{ color: "var(--ink-tertiary)" }}>
            修改说明
          </span>
          <input
            type="text"
            value={editReason}
            onChange={(e) => setEditReason(e.target.value)}
            placeholder="选填：简要记录本次修改原因（将展示在编辑历史中）"
            className="flex-1 bg-transparent text-[12px] outline-none"
            style={{ color: "var(--ink-primary)" }}
          />
        </div>
      )}

      {/* 底部极简状态栏（草稿状态 / 字数 / 快捷提交提示） */}
      <footer
        className="flex h-[32px] shrink-0 items-center justify-between px-5 text-[11.5px] select-none"
        style={{
          borderTop: "0.5px solid var(--hairline-soft)",
          color: "var(--ink-tertiary)",
        }}
      >
        <span className="truncate">
          {uploading
            ? uploadProgressLabel || "正在上传图片..."
            : cloudSyncedAt && request.action !== "EDIT"
              ? "草稿已同步至云端与本地 · 支持粘贴或拖拽图片"
              : draftSavedAt && request.action !== "EDIT"
                ? "草稿已自动保存 · 支持粘贴或拖拽图片"
                : "支持 Markdown、Callout、代码高亮、公式与粘贴/拖拽图片"}
        </span>
        <span className="shrink-0 tabular-nums">
          {Array.from(raw.trim()).length} 字 · {submitShortcutLabel} {actionLabel}
        </span>
      </footer>

      {/* 抽奖话题扣除 20 积分官方规则二次确认 */}
      {showLotteryConfirm && (
        <ConfirmDialog
          title="确认发布抽奖话题？"
          detail="根据 LINUX DO 社区规则，发布带有「抽奖」标签的话题将自动消耗 20 社区积分。"
          confirmLabel="确认并发布"
          onCancel={() => setShowLotteryConfirm(false)}
          onConfirm={() => {
            setShowLotteryConfirm(false);
            executePublish();
          }}
        />
      )}

      {/* 未发送内容退出 / 切帖防误触确认 */}
      {showExitConfirm &&
        (request.action === "EDIT" ? (
          <ConfirmDialog
            title="放弃未保存的修改？"
            detail="当前编辑的内容尚未提交保存，关闭后修改将会丢失。"
            confirmLabel="放弃修改"
            destructive
            onCancel={() => {
              pendingProceedRef.current = null;
              setShowExitConfirm(false);
            }}
            onConfirm={() => {
              const after = pendingProceedRef.current;
              pendingProceedRef.current = null;
              setShowExitConfirm(false);
              onClose();
              after?.();
            }}
          />
        ) : (
          <ConfirmDialog
            title="保存草稿？"
            detail="当前编辑内容尚未发送，是否保存至草稿以便稍后继续？"
            confirmLabel="保存"
            alternateLabel="不保存"
            onCancel={() => {
              pendingProceedRef.current = null;
              setShowExitConfirm(false);
            }}
            onAlternate={() => {
              const after = pendingProceedRef.current;
              pendingProceedRef.current = null;
              setShowExitConfirm(false);
              deleteLocalDraftInRust(draftKey).catch(() => {});
              onClose();
              after?.();
            }}
            onConfirm={() => {
              const after = pendingProceedRef.current;
              pendingProceedRef.current = null;
              setShowExitConfirm(false);
              if (title.trim() || raw.trim() || tags.length > 0) {
                saveLocalDraftInRust({
                  key: draftKey,
                  action: request.action,
                  topicId: request.topicId ?? null,
                  postId: request.postId ?? null,
                  replyToPostNumber: request.replyToPostNumber ?? null,
                  categoryId,
                  title,
                  raw,
                  tags,
                  sequence: 0,
                  updatedAt: Date.now(),
                }).catch(() => {});
              }
              onClose();
              after?.();
            }}
          />
        ))}
    </>
  );

  if (isDrawer) {
    return (
      <section
        ref={drawerRef}
        className="lindo-hairline-t relative z-30 flex shrink-0 flex-col shadow-[0_-12px_32px_rgba(0,0,0,0.34)]"
        style={{
          height: `${drawerHeight}px`,
          backgroundColor: "var(--card-surface)",
        }}
      >
        {editorCore}
      </section>
    );
  }

  return (
    <div
      className="fixed inset-0 z-40 flex items-center justify-center bg-black/58 p-5"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && !submitting && !uploading) {
          requestClose();
        }
      }}
    >
      <div
        className={`flex flex-col overflow-hidden rounded-2xl shadow-2xl transition-[width,height] duration-150 ${
          viewMode === "SPLIT"
            ? "h-[min(690px,88vh)] w-[min(1040px,94vw)]"
            : "h-[min(650px,86vh)] w-[min(840px,92vw)]"
        }`}
        style={{
          backgroundColor: "var(--card-surface)",
          border: "0.5px solid var(--hairline)",
        }}
        onMouseDown={(e) => e.stopPropagation()}
      >
        {editorCore}
      </div>
    </div>
  );
};
