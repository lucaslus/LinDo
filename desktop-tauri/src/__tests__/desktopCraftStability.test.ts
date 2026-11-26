import fs from "node:fs";
import path from "node:path";
import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { PostItemModel, TopicDetailModel, TopicSummary } from "../lib/types";

const invokeMock = vi.fn();
const openUrlMock = vi.fn();

vi.mock("@tauri-apps/api/core", () => ({
  invoke: (...args: any[]) => invokeMock(...args),
}));

vi.mock("@tauri-apps/api/webview", () => ({
  getCurrentWebview: () => ({
    onDragDropEvent: vi.fn().mockResolvedValue(() => {}),
  }),
}));

vi.mock("@tauri-apps/api/window", () => ({
  getCurrentWindow: () => ({
    startDragging: vi.fn(),
    toggleMaximize: vi.fn(),
  }),
}));

vi.mock("@tauri-apps/plugin-opener", () => ({
  openUrl: (...args: any[]) => openUrlMock(...args),
}));

import { SideRail } from "../components/SideRail";
import { TopicReaderPane } from "../components/TopicReaderPane";

(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;

const SRC_ROOT = path.resolve(__dirname, "..");

let activeRoot: Root | null = null;
let activeContainer: HTMLDivElement | null = null;

function unmountActive(): void {
  if (activeRoot) {
    act(() => {
      activeRoot!.unmount();
    });
    activeRoot = null;
  }
  if (activeContainer) {
    activeContainer.remove();
    activeContainer = null;
  }
}

function mountElement(node: React.ReactElement): HTMLDivElement {
  unmountActive();
  activeContainer = document.createElement("div");
  document.body.appendChild(activeContainer);
  activeRoot = createRoot(activeContainer);
  act(() => {
    activeRoot!.render(node);
  });
  return activeContainer;
}

function walkComponentFiles(dir: string): string[] {
  const results: string[] = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === "__tests__" || entry.name === "node_modules") continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      results.push(...walkComponentFiles(full));
    } else if (/\.tsx$/.test(entry.name)) {
      results.push(full);
    }
  }
  return results;
}

function makeSummary(): TopicSummary {
  return {
    id: 4242,
    title: "Desktop Native Craft Stability Topic",
    slug: "desktop-native-craft",
    postsCount: 2,
    replyCount: 1,
    views: 120,
    likeCount: 0,
    highestPostNumber: 2,
    unreadPosts: 0,
    unseen: false,
    categoryId: 1,
    categoryName: "开发调优",
    categoryColorHex: "#3B82F6",
    tags: [],
    createdAt: "2026-11-26T00:00:00Z",
    lastPostedAt: "2026-11-26T00:10:00Z",
    pinned: false,
    closed: false,
    archived: false,
    bookmarked: false,
    originalPoster: {
      userId: 1,
      username: "opuser",
      name: "OP User",
      avatarTemplate: "",
    },
  };
}

function makePost(overrides: Partial<PostItemModel> = {}): PostItemModel {
  return {
    id: 2002,
    topicId: 4242,
    postNumber: 2,
    username: "alice",
    displayName: "Alice",
    avatarTemplate: "",
    trustLevel: 2,
    isModerator: false,
    isAdmin: false,
    cookedHtml: "<p>Only apple can do</p>",
    createdAt: "2026-11-26T00:05:00Z",
    likeCount: 0,
    liked: false,
    bookmarked: false,
    replyCount: 0,
    reactions: [],
    boosts: [],
    polls: [],
    canEdit: false,
    canDelete: false,
    acceptedAnswer: false,
    canAcceptAnswer: false,
    ...overrides,
  };
}

function makeDetail(replyPost: PostItemModel): TopicDetailModel {
  return {
    id: 4242,
    title: "Desktop Native Craft Stability Topic",
    categoryId: 1,
    categoryName: "开发调优",
    categoryColorHex: "#3B82F6",
    tags: [],
    postsCount: 2,
    views: 120,
    likeCount: replyPost.likeCount,
    bookmarked: false,
    closed: false,
    archived: false,
    notificationLevel: 1,
    streamIds: [2001, 2002],
    opUsername: "opuser",
    hasSummary: false,
    posts: [
      makePost({
        id: 2001,
        postNumber: 1,
        username: "opuser",
        displayName: "OP User",
        cookedHtml: "<p>Main topic body</p>",
      }),
      replyPost,
    ],
  };
}

describe("Desktop Native Craft & Zero-Layout-Jitter Stability", () => {
  beforeEach(() => {
    invokeMock.mockReset();
    openUrlMock.mockReset();
  });

  afterEach(() => {
    unmountActive();
    vi.restoreAllMocks();
  });

  it("eliminates press-wobble scale() and transition-all across desktop TSX chrome", () => {
    const scalePat =
      /\b(?:group-)?(?:hover|active):scale(?:-|\[\d)|\bscale-(?:90|95|105|110|115|125)\b/;
    const transitionAllPat = /\btransition-all\b/;
    const scaleOffenders: string[] = [];
    const transitionAllOffenders: string[] = [];

    for (const file of walkComponentFiles(SRC_ROOT)) {
      const lines = fs.readFileSync(file, "utf8").split("\n");
      lines.forEach((line, idx) => {
        const trimmed = line.trim();
        if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) {
          return;
        }
        if (scalePat.test(line)) {
          scaleOffenders.push(`${path.relative(SRC_ROOT, file)}:${idx + 1}: ${trimmed}`);
        }
        if (transitionAllPat.test(line)) {
          transitionAllOffenders.push(`${path.relative(SRC_ROOT, file)}:${idx + 1}: ${trimmed}`);
        }
      });
    }

    expect(scaleOffenders).toEqual([]);
    expect(transitionAllOffenders).toEqual([]);
  });

  it("eliminates conditional border-none <-> border-solid and mismatched border-width toggles", () => {
    const borderNoneTogglePat =
      /border(?:Top|Bottom|Left|Right)?:\s*[^,\n]*\?[^,\n]*solid[^,\n]*:\s*["']none["']|border(?:Top|Bottom|Left|Right)?:\s*[^,\n]*\?[^,\n]*["']none["'][^,\n]*solid/i;
    const offenders: string[] = [];

    for (const file of walkComponentFiles(SRC_ROOT)) {
      const text = fs.readFileSync(file, "utf8");
      if (borderNoneTogglePat.test(text)) {
        offenders.push(path.relative(SRC_ROOT, file));
      }
    }

    const readerSrc = fs.readFileSync(
      path.join(SRC_ROOT, "components/TopicReaderPane.tsx"),
      "utf8"
    );
    expect(offenders).toEqual([]);
    expect(readerSrc).not.toContain('"0.6px solid #EF4444"');
    expect(readerSrc).not.toContain('"4px solid var(--brand-gold)"');
  });

  it("locks TopicReader floor action row margin & height identically when toggling Like 0 -> 1", () => {
    const summary = makeSummary();
    const commonProps = {
      summary,
      isLoading: false,
      errorMessage: null,
      targetFloor: null,
      targetJumpNonce: 0,
      onlyOpFilter: false,
      isAuthorized: true,
      isDark: true,
      currentUser: {
        userId: 99,
        username: "viewer",
        displayName: "Viewer",
        avatarTemplate: "",
        trustLevel: 2,
      },
      onRefresh: vi.fn().mockResolvedValue(undefined),
      onToggleOnlyOp: vi.fn(),
      onAppendPosts: vi.fn(),
      onUpdateDetail: vi.fn(),
      onRemovePost: vi.fn(),
      onOpenComposer: vi.fn(),
      onInspectUser: vi.fn(),
      onOpenImageLightbox: vi.fn(),
      onNavigateTopicLink: vi.fn(),
      onRequestGuestLogin: vi.fn(),
      onTriggerVerify: vi.fn(),
      showBackButton: false,
      onBack: vi.fn(),
      onFloorSeen: vi.fn(),
    };

    // State A: 0 reactions, 0 likes
    const container0 = mountElement(
      React.createElement(TopicReaderPane, {
        ...commonProps,
        detail: makeDetail(makePost({ liked: false, likeCount: 0, reactions: [] })),
      })
    );
    const floor2Before = container0.querySelector<HTMLElement>('[data-floor="2"]');
    const actionsBefore = floor2Before?.querySelector<HTMLElement>('[data-floor-actions="1"]');
    expect(actionsBefore).not.toBeNull();
    expect(actionsBefore?.className).toContain("min-h-[28px]");
    expect(actionsBefore?.className).toContain("mt-1.5");
    expect(actionsBefore?.className).not.toContain("mt-0.5");
    expect(actionsBefore?.className).toContain("select-none");

    const heartBefore = floor2Before?.querySelector<HTMLButtonElement>('button[title="点赞"]');
    expect(heartBefore).not.toBeNull();
    expect(heartBefore?.className).toContain("h-[26px]");
    expect(heartBefore?.className).toContain("w-[26px]");

    // State B: 1 heart reaction after clicking Like
    const container1 = mountElement(
      React.createElement(TopicReaderPane, {
        ...commonProps,
        detail: makeDetail(
          makePost({
            liked: true,
            likeCount: 1,
            currentUserReaction: "heart",
            reactions: [{ id: "heart", count: 1 }],
          })
        ),
      })
    );
    const floor2After = container1.querySelector<HTMLElement>('[data-floor="2"]');
    const actionsAfter = floor2After?.querySelector<HTMLElement>('[data-floor-actions="1"]');
    expect(actionsAfter).not.toBeNull();
    // Exact same container margin & min-height classes -> 0px vertical jump
    expect(actionsAfter?.className).toBe(actionsBefore?.className);

    const heartPill = floor2After?.querySelector<HTMLButtonElement>('button[title=":heart:"]');
    expect(heartPill).not.toBeNull();
    expect(heartPill?.className).toContain("h-[24px]");
    expect(heartPill?.className).toContain("leading-none");
    expect(heartPill?.className).toContain("box-border");
    expect(heartPill?.className).toContain("tabular-nums");
    expect(heartPill?.style.border).toContain("0.5px solid");

    const heartAfter = floor2After?.querySelector<HTMLButtonElement>('button[title="取消赞"]');
    expect(heartAfter).not.toBeNull();
    expect(heartAfter?.className).toContain("h-[26px]");
    expect(heartAfter?.className).toContain("w-[26px]");
  });

  it("renders SideRail with quiet macOS hover pills, zero scale wobble, uniform label weight, and tabular-nums", () => {
    const container = mountElement(
      React.createElement(SideRail, {
        currentTab: "HOME",
        isDark: true,
        unreadCount: 12,
        isMacos: true,
        cfAttentionNeeded: true,
        onSelectTab: vi.fn(),
        onPublishClick: vi.fn(),
        onToggleTheme: vi.fn(),
        onTriggerVerify: vi.fn(),
      })
    );

    const rail = container.querySelector("aside");
    expect(rail?.className).toContain("select-none");

    const html = container.innerHTML;
    expect(html).not.toContain("scale-[");
    expect(html).not.toContain("scale-95");
    expect(html).not.toContain("transition-all");

    const interactivePills = container.querySelectorAll(".lindo-interactive");
    expect(interactivePills.length).toBeGreaterThanOrEqual(6);

    const badge = Array.from(container.querySelectorAll("span")).find(
      (el) => el.textContent === "12"
    );
    expect(badge?.className).toContain("tabular-nums");
  });

  it("enforces app.css native craft rules (.lindo-interactive, .lindo-tab-label, selectable content)", () => {
    const css = fs.readFileSync(path.join(SRC_ROOT, "styles/app.css"), "utf8");
    const homeFeed = fs.readFileSync(
      path.join(SRC_ROOT, "components/HomeFeedPane.tsx"),
      "utf8"
    );

    expect(css).toContain(
      "transition: background-color 120ms ease, color 120ms ease, border-color 120ms ease;"
    );
    expect(css).toContain(".lindo-tab-label::after");
    expect(css).toContain("content: attr(data-label);");
    expect(css).toContain(".lindo-feed-card[data-selected=\"false\"]:hover");
    expect(css).toContain(".lindo-cooked");
    expect(css).toContain("user-select: text;");

    expect(homeFeed).toContain("lindo-tab-label");
    expect(homeFeed).toContain("lindo-feed-card");
  });
});
