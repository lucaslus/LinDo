import {
  getCachedForumMediaBlobUrl,
  needsSameOriginMediaBridge,
  TRANSPARENT_1PX_GIF,
} from "./forumMedia";
import { resolveForumAssetUrl } from "./palette";

const INLINE_SVG_ICON_PATHS: Record<string, { viewBox: string; body: string }> = {
  code: {
    viewBox: "0 0 24 24",
    body: '<path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" d="m8 6-6 6 6 6M16 6l6 6-6 6M14 4l-4 16"/>',
  },
  tag: {
    viewBox: "0 0 24 24",
    body: '<path fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" d="M20.59 13.41 13.42 20.58a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82Z"/><circle cx="7" cy="7" r="1.4" fill="currentColor"/>',
  },
  lock: {
    viewBox: "0 0 24 24",
    body: '<rect x="5" y="11" width="14" height="10" rx="2" fill="none" stroke="currentColor" stroke-width="2"/><path fill="none" stroke="currentColor" stroke-width="2" d="M8 11V7a4 4 0 0 1 8 0v4"/>',
  },
  question: {
    viewBox: "0 0 24 24",
    body: '<circle cx="12" cy="12" r="9.5" fill="none" stroke="currentColor" stroke-width="1.9"/><path fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" d="M9.4 9.3a2.7 2.7 0 0 1 5.2.9c0 1.8-2.6 2.2-2.6 3.7"/><circle cx="12" cy="17.2" r="1" fill="currentColor"/>',
  },
  checkSquare: {
    viewBox: "0 0 24 24",
    body: '<rect x="3.5" y="3.5" width="17" height="17" rx="3" fill="none" stroke="currentColor" stroke-width="2"/><path fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round" d="m8 12.2 2.8 2.8L16.5 9"/>',
  },
  emptySquare: {
    viewBox: "0 0 24 24",
    body: '<rect x="3.5" y="3.5" width="17" height="17" rx="3" fill="none" stroke="currentColor" stroke-width="2"/>',
  },
  link: {
    viewBox: "0 0 24 24",
    body: '<path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" d="M10 13a5 5 0 0 0 7.07 0l2.83-2.83a5 5 0 0 0-7.07-7.07L11 4.93M14 11a5 5 0 0 0-7.07 0L4.1 13.83a5 5 0 0 0 7.07 7.07L13 19.07"/>',
  },
  pin: {
    viewBox: "0 0 24 24",
    body: '<path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" d="M12 17v5M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16h14v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V6h1a2 2 0 0 0 0-4H8a2 2 0 0 0 0 4h1Z"/>',
  },
  info: {
    viewBox: "0 0 24 24",
    body: '<circle cx="12" cy="12" r="9.5" fill="none" stroke="currentColor" stroke-width="1.9"/><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" d="M12 11v5"/><circle cx="12" cy="7.8" r="1" fill="currentColor"/>',
  },
  warn: {
    viewBox: "0 0 24 24",
    body: '<path fill="none" stroke="currentColor" stroke-width="1.9" stroke-linejoin="round" d="M10.3 3.9 2.3 18a2 2 0 0 0 1.7 3h16a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z"/><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" d="M12 9.5v4.5"/><circle cx="12" cy="17.2" r="1" fill="currentColor"/>',
  },
  dot: {
    viewBox: "0 0 16 16",
    body: '<circle cx="8" cy="8" r="4.5" fill="currentColor"/>',
  },
};

function resolveInlineSvgKey(token: string, hashtagType?: string | null): string | null {
  const t = token.toLowerCase();
  if (t.includes("code")) return "code";
  if (t.includes("question") || t.includes("help")) return "question";
  if (t.includes("check-square") || t.includes("square-check") || t.includes("circle-check")) {
    return "checkSquare";
  }
  if (t.includes("far-square") || t === "square") return "emptySquare";
  if (t.includes("lock")) return "lock";
  if (t.includes("thumbtack") || t.includes("pin")) return "pin";
  if (t.includes("tag")) return "tag";
  if (t.includes("link") || t.includes("external")) return "link";
  if (t.includes("triangle") || t.includes("exclamation") || t.includes("warn")) return "warn";
  if (t.includes("info") || t.includes("lightbulb")) return "info";
  if (hashtagType === "tag") return "tag";
  if (hashtagType === "category") return "dot";
  return null;
}

function normalizeCookedSvgIcons(doc: Document): void {
  // Strip Discourse heading hover anchor `#` links whose SVG sprite is absent in Tauri
  doc.querySelectorAll("h1 a.anchor, h2 a.anchor, h3 a.anchor, h4 a.anchor, h5 a.anchor, h6 a.anchor").forEach((a) => {
    if (!(a.textContent || "").trim()) {
      a.remove();
    }
  });

  doc.querySelectorAll("svg").forEach((svg) => {
    const className = typeof svg.getAttribute("class") === "string" ? svg.getAttribute("class")! : "";
    const hashtagAnchor = svg.closest<HTMLAnchorElement>("a.hashtag-cooked");
    const isIconSvg =
      /\b(d-icon|svg-icon|fa|octicon|hashtag-icon)\b/i.test(className) ||
      Boolean(hashtagAnchor) ||
      Boolean(svg.closest("a, aside.quote .title, .badge-category__wrapper, .hashtag-icon-placeholder, .quote-title__text-content, aside.onebox header, .chckbx-box"));

    if (!isIconSvg) {
      // Non-icon inline SVG diagram: guard against unbounded overflow
      svg.setAttribute("class", `${className} lindo-cooked-svg-graphic`.trim());
      return;
    }

    const useEl = svg.querySelector("use");
    const useHref = useEl ? (useEl.getAttribute("href") || useEl.getAttribute("xlink:href") || "") : "";
    const hasInlineShapes = Boolean(svg.querySelector("path, circle, rect, polygon, polyline, line, ellipse"));

    if (useEl && !hasInlineShapes) {
      // Discourse relies on a page-level `<svg id="svg-sprites">` that does not exist inside Tauri;
      // without replacement, `<svg><use href="#..."/></svg>` renders as a huge 300x150 blank void!
      const token = `${className} ${useHref.replace(/^#/, "")}`.trim();
      const hashtagType = hashtagAnchor?.getAttribute("data-type");
      const key = resolveInlineSvgKey(token, hashtagType);
      if (key && INLINE_SVG_ICON_PATHS[key]) {
        const spec = INLINE_SVG_ICON_PATHS[key];
        const replacement = doc.createElementNS("http://www.w3.org/2000/svg", "svg");
        replacement.setAttribute("viewBox", spec.viewBox);
        replacement.setAttribute("width", "13");
        replacement.setAttribute("height", "13");
        replacement.setAttribute("aria-hidden", "true");
        replacement.setAttribute("class", "lindo-cooked-icon");
        replacement.innerHTML = spec.body;
        svg.replaceWith(replacement);
      } else {
        svg.remove();
      }
      return;
    }

    // Inline vector icon (e.g. `</>` code badge inside `<aside class="quote">`): lock strictly to 1em inline!
    svg.removeAttribute("style");
    svg.setAttribute("width", "14");
    svg.setAttribute("height", "14");
    svg.setAttribute("aria-hidden", "true");
    svg.setAttribute("class", `${className} lindo-cooked-icon`.trim());
  });
}

const CALLOUT_TONE_SPECS: Record<
  string,
  { tone: "note" | "tip" | "success" | "warning" | "danger" | "example"; defaultTitle: string; iconSvg: string }
> = {
  note: {
    tone: "note",
    defaultTitle: "说明",
    iconSvg:
      '<svg class="lindo-callout-icon" viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" d="M12 20h9M16.5 3.5a2.12 2.12 0 1 1 3 3L7 19l-4 1 1-4Z"/></svg>',
  },
  info: {
    tone: "note",
    defaultTitle: "信息",
    iconSvg:
      '<svg class="lindo-callout-icon" viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"><circle cx="12" cy="12" r="9.5" fill="none" stroke="currentColor" stroke-width="1.9"/><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" d="M12 11v5"/><circle cx="12" cy="7.8" r="1" fill="currentColor"/></svg>',
  },
  todo: {
    tone: "note",
    defaultTitle: "待办",
    iconSvg:
      '<svg class="lindo-callout-icon" viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"><circle cx="12" cy="12" r="9.5" fill="none" stroke="currentColor" stroke-width="1.9"/><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" d="m8.5 12.3 2.4 2.4 4.8-5.1"/></svg>',
  },
  tip: {
    tone: "tip",
    defaultTitle: "提示",
    iconSvg:
      '<svg class="lindo-callout-icon" viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" d="M9 18h6M10 21h4M12 2a7 7 0 0 0-4 12.7c.7.6 1 1.4 1 2.3h6c0-.9.3-1.7 1-2.3A7 7 0 0 0 12 2Z"/></svg>',
  },
  hint: {
    tone: "tip",
    defaultTitle: "提示",
    iconSvg:
      '<svg class="lindo-callout-icon" viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" d="M9 18h6M10 21h4M12 2a7 7 0 0 0-4 12.7c.7.6 1 1.4 1 2.3h6c0-.9.3-1.7 1-2.3A7 7 0 0 0 12 2Z"/></svg>',
  },
  important: {
    tone: "tip",
    defaultTitle: "重要",
    iconSvg:
      '<svg class="lindo-callout-icon" viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" d="M12 2 14.9 8.6 22 9.3l-5.3 4.7 1.6 7L12 17.3 5.7 21l1.6-7L2 9.3l7.1-.7Z"/></svg>',
  },
  abstract: {
    tone: "tip",
    defaultTitle: "摘要",
    iconSvg:
      '<svg class="lindo-callout-icon" viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"><rect x="4" y="3" width="16" height="18" rx="2" fill="none" stroke="currentColor" stroke-width="1.9"/><path fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" d="M8 8h8M8 12h8M8 16h5"/></svg>',
  },
  summary: {
    tone: "tip",
    defaultTitle: "摘要",
    iconSvg:
      '<svg class="lindo-callout-icon" viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"><rect x="4" y="3" width="16" height="18" rx="2" fill="none" stroke="currentColor" stroke-width="1.9"/><path fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" d="M8 8h8M8 12h8M8 16h5"/></svg>',
  },
  tldr: {
    tone: "tip",
    defaultTitle: "TL;DR",
    iconSvg:
      '<svg class="lindo-callout-icon" viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"><rect x="4" y="3" width="16" height="18" rx="2" fill="none" stroke="currentColor" stroke-width="1.9"/><path fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" d="M8 8h8M8 12h8M8 16h5"/></svg>',
  },
  success: {
    tone: "success",
    defaultTitle: "成功",
    iconSvg:
      '<svg class="lindo-callout-icon" viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"><circle cx="12" cy="12" r="9.5" fill="none" stroke="currentColor" stroke-width="1.9"/><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" d="m8.2 12.2 2.6 2.6 5-5.3"/></svg>',
  },
  check: {
    tone: "success",
    defaultTitle: "通过",
    iconSvg:
      '<svg class="lindo-callout-icon" viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"><circle cx="12" cy="12" r="9.5" fill="none" stroke="currentColor" stroke-width="1.9"/><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" d="m8.2 12.2 2.6 2.6 5-5.3"/></svg>',
  },
  done: {
    tone: "success",
    defaultTitle: "完成",
    iconSvg:
      '<svg class="lindo-callout-icon" viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"><circle cx="12" cy="12" r="9.5" fill="none" stroke="currentColor" stroke-width="1.9"/><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" d="m8.2 12.2 2.6 2.6 5-5.3"/></svg>',
  },
  warning: {
    tone: "warning",
    defaultTitle: "注意",
    iconSvg:
      '<svg class="lindo-callout-icon" viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="1.9" stroke-linejoin="round" d="M10.3 3.9 2.3 18a2 2 0 0 0 1.7 3h16a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z"/><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" d="M12 9.5v4.5"/><circle cx="12" cy="17.2" r="1" fill="currentColor"/></svg>',
  },
  caution: {
    tone: "warning",
    defaultTitle: "谨慎",
    iconSvg:
      '<svg class="lindo-callout-icon" viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="1.9" stroke-linejoin="round" d="M10.3 3.9 2.3 18a2 2 0 0 0 1.7 3h16a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z"/><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" d="M12 9.5v4.5"/><circle cx="12" cy="17.2" r="1" fill="currentColor"/></svg>',
  },
  attention: {
    tone: "warning",
    defaultTitle: "注意",
    iconSvg:
      '<svg class="lindo-callout-icon" viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="1.9" stroke-linejoin="round" d="M10.3 3.9 2.3 18a2 2 0 0 0 1.7 3h16a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z"/><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" d="M12 9.5v4.5"/><circle cx="12" cy="17.2" r="1" fill="currentColor"/></svg>',
  },
  question: {
    tone: "warning",
    defaultTitle: "疑问",
    iconSvg:
      '<svg class="lindo-callout-icon" viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"><circle cx="12" cy="12" r="9.5" fill="none" stroke="currentColor" stroke-width="1.9"/><path fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" d="M9.4 9.3a2.7 2.7 0 0 1 5.2.9c0 1.8-2.6 2.2-2.6 3.7"/><circle cx="12" cy="17.2" r="1" fill="currentColor"/></svg>',
  },
  help: {
    tone: "warning",
    defaultTitle: "帮助",
    iconSvg:
      '<svg class="lindo-callout-icon" viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"><circle cx="12" cy="12" r="9.5" fill="none" stroke="currentColor" stroke-width="1.9"/><path fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" d="M9.4 9.3a2.7 2.7 0 0 1 5.2.9c0 1.8-2.6 2.2-2.6 3.7"/><circle cx="12" cy="17.2" r="1" fill="currentColor"/></svg>',
  },
  faq: {
    tone: "warning",
    defaultTitle: "FAQ",
    iconSvg:
      '<svg class="lindo-callout-icon" viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"><circle cx="12" cy="12" r="9.5" fill="none" stroke="currentColor" stroke-width="1.9"/><path fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" d="M9.4 9.3a2.7 2.7 0 0 1 5.2.9c0 1.8-2.6 2.2-2.6 3.7"/><circle cx="12" cy="17.2" r="1" fill="currentColor"/></svg>',
  },
  danger: {
    tone: "danger",
    defaultTitle: "危险",
    iconSvg:
      '<svg class="lindo-callout-icon" viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" d="M13 2 4 14h7l-1 8 9-12h-7Z"/></svg>',
  },
  error: {
    tone: "danger",
    defaultTitle: "错误",
    iconSvg:
      '<svg class="lindo-callout-icon" viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"><circle cx="12" cy="12" r="9.5" fill="none" stroke="currentColor" stroke-width="1.9"/><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" d="m9 9 6 6M15 9l-6 6"/></svg>',
  },
  failure: {
    tone: "danger",
    defaultTitle: "失败",
    iconSvg:
      '<svg class="lindo-callout-icon" viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"><circle cx="12" cy="12" r="9.5" fill="none" stroke="currentColor" stroke-width="1.9"/><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" d="m9 9 6 6M15 9l-6 6"/></svg>',
  },
  fail: {
    tone: "danger",
    defaultTitle: "失败",
    iconSvg:
      '<svg class="lindo-callout-icon" viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"><circle cx="12" cy="12" r="9.5" fill="none" stroke="currentColor" stroke-width="1.9"/><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" d="m9 9 6 6M15 9l-6 6"/></svg>',
  },
  bug: {
    tone: "danger",
    defaultTitle: "Bug",
    iconSvg:
      '<svg class="lindo-callout-icon" viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"><circle cx="12" cy="12" r="9.5" fill="none" stroke="currentColor" stroke-width="1.9"/><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" d="M12 8v5"/><circle cx="12" cy="16.4" r="1" fill="currentColor"/></svg>',
  },
  example: {
    tone: "example",
    defaultTitle: "示例",
    iconSvg:
      '<svg class="lindo-callout-icon" viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"/></svg>',
  },
  quote: {
    tone: "example",
    defaultTitle: "引用",
    iconSvg:
      '<svg class="lindo-callout-icon" viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" d="M10 11H6a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h3a1 1 0 0 1 1 1v6c0 2.5-1.5 4.2-4 5M20 11h-4a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h3a1 1 0 0 1 1 1v6c0 2.5-1.5 4.2-4 5"/></svg>',
  },
  cite: {
    tone: "example",
    defaultTitle: "引用",
    iconSvg:
      '<svg class="lindo-callout-icon" viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" d="M10 11H6a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h3a1 1 0 0 1 1 1v6c0 2.5-1.5 4.2-4 5M20 11h-4a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h3a1 1 0 0 1 1 1v6c0 2.5-1.5 4.2-4 5"/></svg>',
  },
};

const CALLOUT_CHEVRON_SVG =
  '<svg class="lindo-callout-chevron" viewBox="0 0 24 24" width="14" height="14" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" d="m6 9 6 6 6-6"/></svg>';

const QUOTE_EXPAND_CHEVRON_SVG =
  '<svg class="lindo-quote-expand-icon" viewBox="0 0 24 24" width="14" height="14" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" d="m6 9 6 6 6-6"/></svg>';

function isSafeCssColor(raw: string): boolean {
  const v = raw.trim();
  if (!v || v.length > 48) return false;
  if (/(url|expression|var\(|calc\(|;|\\|\/\*)/i.test(v)) return false;
  return /^(#[0-9a-f]{3,8}|[a-z]{3,24}|rgba?\([\d\s.,/%]+\)|hsla?\([\d\s.,/%deg]+\))$/i.test(v);
}

/**
 * Transforms Obsidian / Discourse Callout blockquotes (`> [!Note]+ title`, `> [!Warning]- title`, `> [!Tip]`)
 * into semantic, collapsible native callout cards matching Linux.do Web's `discourse-callouts` plugin.
 */
function transformObsidianCallouts(doc: Document): void {
  // Process inner-most blockquotes first so nested callouts transform cleanly
  const blockquotes = Array.from(doc.querySelectorAll("blockquote")).reverse();
  for (const bq of blockquotes) {
    if (bq.parentElement?.tagName === "ASIDE" && bq.parentElement.classList.contains("quote")) {
      continue;
    }
    const firstEl = bq.firstElementChild;
    if (!firstEl || firstEl.tagName !== "P") continue;
    const firstNode = firstEl.firstChild;
    if (!firstNode || firstNode.nodeType !== Node.TEXT_NODE) continue;

    const leadingText = firstNode.textContent || "";
    const match = leadingText.match(/^\s*\[!([a-zA-Z0-9_-]+)\]([+-])?(?:[ \t]+)?/);
    if (!match) continue;

    const rawType = match[1].toLowerCase();
    const fold = match[2] || "";
    const spec = CALLOUT_TONE_SPECS[rawType] || {
      tone: "note" as const,
      defaultTitle: match[1].charAt(0).toUpperCase() + match[1].slice(1).toLowerCase(),
      iconSvg: CALLOUT_TONE_SPECS.note.iconSvg,
    };

    // Strip `[!Type][+-]?` prefix from the leading text node
    firstNode.textContent = leadingText.slice(match[0].length);

    // Split `firstEl` (<p>) into title nodes (before first `<br>` or `\n`) and remainder body nodes
    const titleFrag = doc.createDocumentFragment();
    const restP = doc.createElement("p");
    let inBody = false;

    for (const child of Array.from(firstEl.childNodes)) {
      if (inBody) {
        restP.appendChild(child);
        continue;
      }
      if (child.nodeType === Node.ELEMENT_NODE && (child as Element).tagName === "BR") {
        inBody = true;
        child.remove();
        continue;
      }
      if (child.nodeType === Node.TEXT_NODE) {
        const txt = child.textContent || "";
        const nlIdx = txt.indexOf("\n");
        if (nlIdx >= 0) {
          const beforeNl = txt.slice(0, nlIdx);
          const afterNl = txt.slice(nlIdx + 1);
          if (beforeNl) titleFrag.appendChild(doc.createTextNode(beforeNl));
          if (afterNl) restP.appendChild(doc.createTextNode(afterNl));
          child.remove();
          inBody = true;
          continue;
        }
      }
      titleFrag.appendChild(child);
    }

    const isCollapsible = fold === "+" || fold === "-";
    const container = doc.createElement(isCollapsible ? "details" : "div");
    container.className = `lindo-callout lindo-callout-${spec.tone}`;
    container.setAttribute("data-callout", rawType);
    if (fold === "+") {
      container.setAttribute("open", "");
    }

    const header = doc.createElement(isCollapsible ? "summary" : "div");
    header.className = "lindo-callout-title";
    header.innerHTML = spec.iconSvg;

    const titleTextSpan = doc.createElement("span");
    titleTextSpan.className = "lindo-callout-title-text";
    titleTextSpan.appendChild(titleFrag);
    if (!(titleTextSpan.textContent || "").trim() && !titleTextSpan.querySelector("img, svg, code")) {
      titleTextSpan.textContent = spec.defaultTitle;
    }
    header.appendChild(titleTextSpan);

    if (isCollapsible) {
      header.insertAdjacentHTML("beforeend", CALLOUT_CHEVRON_SVG);
    }
    container.appendChild(header);

    const bodyDiv = doc.createElement("div");
    bodyDiv.className = "lindo-callout-body";
    if ((restP.textContent || "").trim() || restP.querySelector("img, svg, code, a")) {
      bodyDiv.appendChild(restP);
    }
    let nextSibling = firstEl.nextSibling;
    while (nextSibling) {
      const following = nextSibling.nextSibling;
      bodyDiv.appendChild(nextSibling);
      nextSibling = following;
    }
    if (bodyDiv.childNodes.length > 0) {
      container.appendChild(bodyDiv);
    }

    bq.replaceWith(container);
  }
}

function describeIframeHost(rawUrl: string): { host: string; label: string } {
  try {
    const normalized = rawUrl.startsWith("//") ? `https:${rawUrl}` : rawUrl;
    const u = new URL(normalized, "https://linux.do");
    const host = u.hostname.toLowerCase().replace(/^www\./, "");
    if (host.includes("bilibili.com") || host.includes("b23.tv")) {
      return { host, label: "Bilibili 嵌入视频" };
    }
    if (host.includes("youtube.com") || host.includes("youtube-nocookie.com") || host === "youtu.be") {
      return { host, label: "YouTube 嵌入视频" };
    }
    if (host.includes("codepen.io")) return { host, label: "CodePen 交互示例" };
    if (host.includes("codesandbox.io")) return { host, label: "CodeSandbox 示例" };
    if (host.includes("stackblitz.com")) return { host, label: "StackBlitz 示例" };
    if (host.includes("music.163.com")) return { host, label: "网易云音乐外链" };
    if (host.includes("spotify.com")) return { host, label: "Spotify 音频嵌入" };
    if (host.includes("vimeo.com")) return { host, label: "Vimeo 嵌入视频" };
    return { host, label: `外部嵌入内容 (${host})` };
  } catch {
    return { host: "", label: "外部嵌入内容" };
  }
}

function isDirectImageAssetHref(href: string): boolean {
  const clean = href.trim();
  if (!clean) return false;
  return (
    /\.(?:png|jpe?g|webp|gif|avif|bmp|svg)(?:[?#]|$)/i.test(clean) ||
    /\/uploads\/(?:default|short-url)\//i.test(clean) ||
    /(?:^|\/\/)(?:[^/]+\.)?ldstatic\.com\/(?:original|optimized)\//i.test(clean)
  );
}

function hasVisibleAnchorText(anchor: HTMLAnchorElement): boolean {
  if (!anchor.querySelector(".meta")) {
    return Boolean((anchor.textContent || "").trim());
  }
  const clone = anchor.cloneNode(true) as HTMLAnchorElement;
  clone.querySelectorAll(".meta").forEach((m) => m.remove());
  return Boolean((clone.textContent || "").trim());
}

/**
 * Unwraps image-only anchors so inline `<a>` continuation boxes beside block images
 * never create full-width invisible hitboxes, while tagging genuine webpage-link images.
 */
function normalizeImageAnchors(doc: Document): void {
  doc.querySelectorAll<HTMLAnchorElement>("a.lightbox, .lightbox-wrapper > a").forEach((anchor) => {
    const img =
      anchor.querySelector<HTMLImageElement>("img:not(.emoji):not(.avatar):not(.site-icon)") ||
      anchor.querySelector<HTMLImageElement>("img");
    if (img) {
      const fullHref = anchor.getAttribute("href");
      if (fullHref && !img.getAttribute("data-full-src")) {
        img.setAttribute("data-full-src", resolveForumAssetUrl(fullHref));
      }
      anchor.querySelectorAll(".meta").forEach((m) => m.remove());
      anchor.replaceWith(img);
    } else if (!hasVisibleAnchorText(anchor)) {
      anchor.remove();
    }
  });

  doc.querySelectorAll<HTMLAnchorElement>("a[href]").forEach((anchor) => {
    const contentImgs = anchor.querySelectorAll<HTMLImageElement>(
      "img:not(.emoji):not(.avatar):not(.site-icon)"
    );
    if (contentImgs.length !== 1 || hasVisibleAnchorText(anchor)) {
      return;
    }
    const img = contentImgs[0];
    const href = (anchor.getAttribute("href") || "").trim();
    const imgCanonical = img.getAttribute("data-canonical-src") || img.getAttribute("src") || "";
    if (isDirectImageAssetHref(href) || (href && resolveForumAssetUrl(href) === imgCanonical)) {
      if (href && !img.getAttribute("data-full-src")) {
        img.setAttribute("data-full-src", resolveForumAssetUrl(href));
      }
      anchor.querySelectorAll(".meta").forEach((m) => m.remove());
      anchor.replaceWith(img);
    } else {
      anchor.setAttribute("data-image-link", "1");
    }
  });
}

/**
 * Sanitizes Discourse `cooked` HTML and normalizes relative `linux.do` asset/link URLs
 * so native WebKit DOM renders emojis, uploads, tables, quotes, callouts, videos, embeds,
 * hashtags, spoilers, and code blocks cleanly.
 */
export function prepareCookedHtml(
  rawHtml: string,
  opts?: { stripPolls?: boolean; currentTopicId?: number }
): string {
  if (!rawHtml) return "";
  const parser = new DOMParser();
  const doc = parser.parseFromString(rawHtml, "text/html");

  // Convert valid HTTP(S) `<iframe>` players (Bilibili/YouTube/CodePen/etc.) into native clickable cards
  // BEFORE removing executable/embedded tags so users never see an unexplained blank void.
  doc.querySelectorAll<HTMLIFrameElement>("iframe").forEach((iframe) => {
    const rawSrc = (iframe.getAttribute("src") || "").trim();
    const normalized = rawSrc.startsWith("//") ? `https:${rawSrc}` : rawSrc;
    if (/^https?:\/\//i.test(normalized)) {
      const { label, host } = describeIframeHost(normalized);
      const card = doc.createElement("div");
      card.className = "lindo-embed-card";
      const link = doc.createElement("a");
      link.setAttribute("href", normalized);
      link.className = "lindo-embed-link";
      const title = doc.createElement("span");
      title.className = "lindo-embed-title";
      title.textContent = label;
      const meta = doc.createElement("span");
      meta.className = "lindo-embed-url";
      meta.textContent = host || normalized;
      link.appendChild(title);
      link.appendChild(meta);
      card.appendChild(link);
      iframe.replaceWith(card);
    } else {
      iframe.remove();
    }
  });

  // Remove unsafe executable tags
  doc.querySelectorAll("script, iframe, object, embed, form, meta, base, link").forEach((el) => el.remove());

  // Strip raw static .poll blocks when interactive PollCard is rendered below cooked body
  if (opts?.stripPolls) {
    doc.querySelectorAll("div.poll").forEach((el) => el.remove());
  }

  doc.querySelectorAll("*").forEach((el) => {
    // Strip inline event handlers & javascript: URIs
    for (const attr of Array.from(el.attributes)) {
      const name = attr.name.toLowerCase();
      if (name.startsWith("on")) {
        el.removeAttribute(attr.name);
      } else if ((name === "href" || name === "src" || name === "poster") && /^\s*javascript:/i.test(attr.value)) {
        el.removeAttribute(attr.name);
      }
    }

    // Preserve explicit BBCode / inline colors so nested `<strong>` inherits the author's highlight color
    const bbColor = el.getAttribute("data-bbcode-color") || (el.tagName === "FONT" ? el.getAttribute("color") : null);
    const bbBg = el.getAttribute("data-bbcode-bgcolor");
    const existingStyle = el.getAttribute("style") || "";
    if (bbColor && isSafeCssColor(bbColor)) {
      el.setAttribute("style", `${existingStyle}${existingStyle && !existingStyle.trim().endsWith(";") ? ";" : ""}color:${bbColor.trim()};`);
      el.setAttribute("data-has-custom-color", "1");
    } else if (/(?:^|;)\s*color\s*:/i.test(existingStyle)) {
      el.setAttribute("data-has-custom-color", "1");
    }
    if (bbBg && isSafeCssColor(bbBg)) {
      const curStyle = el.getAttribute("style") || "";
      el.setAttribute(
        "style",
        `${curStyle}${curStyle && !curStyle.trim().endsWith(";") ? ";" : ""}background-color:${bbBg.trim()};padding:0 3px;border-radius:3px;`
      );
    }

    if (el instanceof HTMLImageElement) {
      const rawSrc =
        el.getAttribute("src") ||
        el.getAttribute("data-orig-src") ||
        el.getAttribute("data-small-upload") ||
        "";
      if (rawSrc) {
        const resolved = resolveForumAssetUrl(rawSrc);
        el.setAttribute("data-canonical-src", resolved);
        if (needsSameOriginMediaBridge(resolved)) {
          const cachedBlob = getCachedForumMediaBlobUrl(resolved);
          if (cachedBlob) {
            el.setAttribute("src", cachedBlob);
          } else {
            el.setAttribute("src", TRANSPARENT_1PX_GIF);
            el.setAttribute("data-lindo-src", resolved);
          }
        } else {
          el.setAttribute("src", resolved);
        }
      }
      el.setAttribute("loading", "lazy");
      el.setAttribute("decoding", "async");
    }

    // Normalize uploaded `<video>`, `<audio>`, and `<source>` elements so native playback works with auth cookies
    if (el.tagName === "VIDEO" || el.tagName === "AUDIO") {
      el.setAttribute("controls", "");
      el.setAttribute("preload", "metadata");
      if (el.tagName === "VIDEO") {
        el.setAttribute("playsinline", "");
        const rawPoster = (el.getAttribute("poster") || "").trim();
        if (rawPoster) {
          const resolvedPoster = resolveForumAssetUrl(rawPoster);
          const cachedPoster = needsSameOriginMediaBridge(resolvedPoster)
            ? getCachedForumMediaBlobUrl(resolvedPoster)
            : resolvedPoster;
          if (cachedPoster) el.setAttribute("poster", cachedPoster);
          else el.removeAttribute("poster");
        }
      }
      const rawSrc = (el.getAttribute("src") || "").trim();
      if (rawSrc) {
        const resolved = resolveForumAssetUrl(rawSrc);
        if (needsSameOriginMediaBridge(resolved)) {
          const cachedBlob = getCachedForumMediaBlobUrl(resolved);
          if (cachedBlob) {
            el.setAttribute("src", cachedBlob);
          } else {
            el.removeAttribute("src");
            el.setAttribute("data-lindo-media-src", resolved);
          }
        } else {
          el.setAttribute("src", resolved);
        }
      }
    }
    if (el.tagName === "SOURCE") {
      const rawSrc = (el.getAttribute("src") || "").trim();
      if (rawSrc) {
        const resolved = resolveForumAssetUrl(rawSrc);
        if (needsSameOriginMediaBridge(resolved)) {
          const cachedBlob = getCachedForumMediaBlobUrl(resolved);
          if (cachedBlob) {
            el.setAttribute("src", cachedBlob);
          } else {
            el.removeAttribute("src");
            el.setAttribute("data-lindo-media-src", resolved);
          }
        } else {
          el.setAttribute("src", resolved);
        }
      }
    }

    if (el instanceof HTMLAnchorElement) {
      const href = (el.getAttribute("href") || "").trim();
      if (href.startsWith("#")) {
        // Keep in-page footnote/heading anchors strictly relative so click handler scrolls/popovers in-app
        el.setAttribute("href", href);
      } else if (href.startsWith("//")) {
        // CRITICAL: Discourse cross-topic quote links use protocol-relative `//linux.do/t/...`!
        // Must prepend `https:` BEFORE checking single leading `/` so we never mangle into `https://linux.do//linux.do/...`!
        el.setAttribute("href", `https:${href}`);
      } else if (href.startsWith("/")) {
        el.setAttribute("href", `https://linux.do${href}`);
      }
    }
  });

  // Transform Obsidian / Discourse Callouts (`> [!Note]+ title`, `> [!Warning]- title`, etc.)
  transformObsidianCallouts(doc);

  // Normalize inline Discourse SVG icons & missing `<use href="#...">` sprites so icons never explode to 250px or blank gaps
  normalizeCookedSvgIcons(doc);

  // Wrap bare `<table>` elements in a horizontal-scroll wrapper so wide multi-column tables never clip or crush
  doc.querySelectorAll("table").forEach((table) => {
    const parent = table.parentElement;
    if (parent && (parent.classList.contains("md-table") || parent.classList.contains("lindo-table-wrap"))) {
      return;
    }
    const wrap = doc.createElement("div");
    wrap.className = "lindo-table-wrap";
    table.replaceWith(wrap);
    wrap.appendChild(table);
  });

  // Unwrap Discourse lightbox & image-only anchors and tag webpage-linked images
  normalizeImageAnchors(doc);

  // Mark internal `linux.do` topic/post oneboxes so clicking anywhere on the card navigates natively inside Lindo PC
  doc.querySelectorAll<HTMLElement>("aside.onebox").forEach((onebox) => {
    const rawSrc =
      onebox.getAttribute("data-onebox-src") ||
      onebox.querySelector<HTMLAnchorElement>("header a[href], h3 a[href], h4 a[href], a[href]")?.getAttribute("href") ||
      "";
    if (!rawSrc) return;
    const parsed = parseInternalForumLink(rawSrc);
    if (parsed && (parsed.kind === "topic" || parsed.kind === "post")) {
      onebox.setAttribute("data-internal-onebox-href", rawSrc);
    }
  });

  // Enhance Discourse `<aside class="quote" data-post="N" data-topic="T">` headers:
  // - Cross-topic quotes: clicking the title link already jumps natively; omit redundant `查看原帖 ›` and show clean inline expand `v`!
  // - Same-topic floor quotes: keep compact `#N 楼 ›` jump + inline expand `v` when truncated!
  doc.querySelectorAll("aside.quote").forEach((aside) => {
    const postNum = aside.getAttribute("data-post");
    const topicAttr = aside.getAttribute("data-topic") || "";
    const quoteTopicId = Number(topicAttr || 0);
    const isFullQuote = aside.getAttribute("data-full") === "true";
    const isCrossTopic =
      quoteTopicId > 0 &&
      Boolean(opts?.currentTopicId) &&
      quoteTopicId !== opts?.currentTopicId;
    if (isCrossTopic) {
      aside.setAttribute("data-cross-topic", "1");
    }

    const titleEl = aside.querySelector(".title");
    if (titleEl) {
      titleEl.querySelectorAll(".quote-controls").forEach((c) => c.remove());
      for (const child of Array.from(titleEl.childNodes)) {
        if (child.nodeType === Node.TEXT_NODE) {
          const cleaned = (child.textContent || "").trim().replace(/[:：]\s*$/, "").trim();
          if (cleaned) {
            child.textContent = cleaned;
          } else {
            child.remove();
          }
        }
      }
      const primaryTitleLink = titleEl.querySelector<HTMLAnchorElement>(
        "a:not(.hashtag-cooked):not(.badge-category__wrapper)"
      );
      const quoteTitleText = (primaryTitleLink?.textContent || "").trim();
      if (isCrossTopic && primaryTitleLink) {
        const curHref = primaryTitleLink.getAttribute("href") || "";
        if (!parseInternalTopicLink(curHref)) {
          primaryTitleLink.setAttribute(
            "href",
            `https://linux.do/t/topic/${quoteTopicId}${postNum && Number(postNum) > 1 ? `/${postNum}` : ""}`
          );
        }
      }

      const actionsWrap = doc.createElement("span");
      actionsWrap.className = "lindo-quote-actions";

      // Show compact `#N 楼 ›` ONLY for same-topic quotes (or cross-topic quotes lacking a clickable title link)
      if (
        postNum &&
        Number(postNum) > 0 &&
        (!isCrossTopic || !primaryTitleLink) &&
        !titleEl.querySelector("[data-quote-jump-floor]")
      ) {
        const jumpBtn = doc.createElement("button");
        jumpBtn.setAttribute("type", "button");
        jumpBtn.setAttribute("data-quote-jump-floor", postNum);
        if (topicAttr) {
          jumpBtn.setAttribute("data-quote-jump-topic", topicAttr);
        }
        if (quoteTitleText) {
          jumpBtn.setAttribute("data-quote-jump-title", quoteTitleText);
        }
        jumpBtn.textContent = `#${postNum} 楼 ›`;
        actionsWrap.appendChild(jumpBtn);
      }

      // Add Web-style quiet `v` expand/collapse chevron to fetch & expand the full quoted post inline!
      const effectiveTopicId = quoteTopicId > 0 ? quoteTopicId : opts?.currentTopicId || 0;
      if (!isFullQuote && postNum && Number(postNum) > 0 && effectiveTopicId > 0) {
        const expandBtn = doc.createElement("button");
        expandBtn.setAttribute("type", "button");
        expandBtn.setAttribute("data-quote-expand", "1");
        expandBtn.setAttribute("data-quote-topic", String(effectiveTopicId));
        expandBtn.setAttribute("data-quote-post", postNum);
        expandBtn.setAttribute("aria-expanded", "false");
        expandBtn.setAttribute("title", "展开完整引用");
        expandBtn.className = "lindo-quote-expand-btn";
        expandBtn.innerHTML = QUOTE_EXPAND_CHEVRON_SVG;
        actionsWrap.appendChild(expandBtn);
      }

      if (actionsWrap.childNodes.length > 0) {
        titleEl.appendChild(actionsWrap);
      }
    }
  });

  // Add one-click copy button (and language badge if present) to every non-mermaid <pre> code block
  doc.querySelectorAll("pre").forEach((pre) => {
    const codeEl = pre.querySelector("code");
    const isMermaid =
      pre.classList.contains("mermaid") ||
      Boolean(codeEl?.className.match(/(?:^|\s)(?:lang|language)-mermaid\b/i));
    if (isMermaid) return;

    const langMatch = codeEl?.className.match(/(?:lang|language)-([a-zA-Z0-9_+#-]+)/);
    const lang = langMatch?.[1] && langMatch[1] !== "auto" ? langMatch[1] : "";

    const btn = doc.createElement("button");
    btn.setAttribute("type", "button");
    btn.setAttribute("data-copy-code", "1");
    btn.textContent = lang ? `${lang} · 复制` : "复制";
    btn.setAttribute(
      "style",
      "position:absolute;top:8px;right:8px;padding:2px 8px;font-size:11px;border-radius:6px;background:var(--tonal-pill);color:var(--ink-secondary);border:0.5px solid var(--hairline);cursor:pointer;opacity:0.88;"
    );
    pre.appendChild(btn);
  });

  return doc.body.innerHTML;
}

const shortCookedCache = new Map<string, string>();
const MAX_SHORT_COOKED_CACHE = 400;

/**
 * Memoized `prepareCookedHtml` for high-frequency short snippets (Boosts, Poll options, small actions)
 * so re-renders on Boost-heavy topics (e.g. 40+ Boosts) incur ZERO `DOMParser` work!
 */
export function prepareCookedHtmlCached(
  rawHtml: string,
  opts?: { stripPolls?: boolean; currentTopicId?: number }
): string {
  if (!rawHtml) return "";
  const key = `${opts?.stripPolls ? 1 : 0}:${opts?.currentTopicId || 0}:${rawHtml}`;
  const hit = shortCookedCache.get(key);
  if (hit !== undefined) return hit;
  const out = prepareCookedHtml(rawHtml, opts);
  if (shortCookedCache.size >= MAX_SHORT_COOKED_CACHE) {
    const oldest = shortCookedCache.keys().next().value;
    if (oldest !== undefined) shortCookedCache.delete(oldest);
  }
  shortCookedCache.set(key, out);
  return out;
}

export interface PostReplyPreviewMeta {
  snippet: string;
  hasCode: boolean;
  hasPoll: boolean;
  hasTable: boolean;
  imageCount: number;
  firstImageUrl: string | null;
  isLongOrRich: boolean;
  leadingQuotedFloor: number | null;
  leadingQuotedTopicId: number | null;
}

const replyPreviewCache = new Map<string, PostReplyPreviewMeta>();

/**
 * Extracts a clean, human-readable core content preview from a Discourse `cooked` HTML post
 * (aligned with Mobile `CommunityPostContent` + enriched for Desktop wide-screen reading).
 * Cached in-memory by `postId + html.length` for O(1) zero-reflow 144Hz scrolling!
 */
export function extractPostReplyPreview(postId: number, rawHtml: string): PostReplyPreviewMeta {
  if (!rawHtml) {
    return {
      snippet: "",
      hasCode: false,
      hasPoll: false,
      hasTable: false,
      imageCount: 0,
      firstImageUrl: null,
      isLongOrRich: false,
      leadingQuotedFloor: null,
      leadingQuotedTopicId: null,
    };
  }
  const cacheKey = `${postId}:${rawHtml.length}`;
  const hit = replyPreviewCache.get(cacheKey);
  if (hit) return hit;

  const parser = new DOMParser();
  const doc = parser.parseFromString(rawHtml, "text/html");

  // Check if the post's very first visible block is a Discourse `<aside class="quote" data-post="N">` with a title header
  let leadingQuotedFloor: number | null = null;
  let leadingQuotedTopicId: number | null = null;
  let firstEl: Element | null = doc.body.firstElementChild;
  while (
    firstEl &&
    firstEl.tagName === "P" &&
    !(firstEl.textContent || "").trim() &&
    !firstEl.querySelector("img")
  ) {
    firstEl = firstEl.nextElementSibling;
  }
  if (
    firstEl &&
    firstEl.tagName === "ASIDE" &&
    firstEl.classList.contains("quote") &&
    firstEl.querySelector(".title")
  ) {
    const dp = Number(firstEl.getAttribute("data-post") || 0);
    const dt = Number(firstEl.getAttribute("data-topic") || 0);
    if (dp > 0) leadingQuotedFloor = dp;
    if (dt > 0) leadingQuotedTopicId = dt;
  }

  // Work on body without nested quotes first so we preview what the target floor ITSELF wrote
  const clone = doc.body.cloneNode(true) as HTMLElement;
  clone.querySelectorAll("script, style, .meta, .quote-controls").forEach((el) => el.remove());

  const cloneWithoutQuotes = clone.cloneNode(true) as HTMLElement;
  cloneWithoutQuotes.querySelectorAll("aside.quote").forEach((q) => q.remove());

  const targetRoot =
    (cloneWithoutQuotes.textContent || "").trim().length > 0 ||
    cloneWithoutQuotes.querySelectorAll("img:not(.emoji):not(.avatar), pre, .poll, table").length > 0
      ? cloneWithoutQuotes
      : clone;

  let imageCount = 0;
  let firstImageUrl: string | null = null;
  targetRoot.querySelectorAll("img").forEach((img) => {
    if (img.classList.contains("avatar")) {
      img.remove();
      return;
    }
    if (img.classList.contains("emoji")) {
      const alt = img.getAttribute("alt") || img.getAttribute("title") || "";
      img.replaceWith(doc.createTextNode(alt));
      return;
    }
    const rawSrc =
      img.getAttribute("src") ||
      img.getAttribute("data-orig-src") ||
      img.getAttribute("data-small-upload") ||
      "";
    if (rawSrc) {
      imageCount += 1;
      if (!firstImageUrl) {
        firstImageUrl = resolveForumAssetUrl(rawSrc);
      }
    }
    img.remove();
  });

  const hasPoll = Boolean(targetRoot.querySelector("div.poll"));
  targetRoot.querySelectorAll("div.poll").forEach((p) => p.remove());

  const hasTable = Boolean(targetRoot.querySelector("table"));
  const hasCode = Boolean(targetRoot.querySelector("pre"));
  targetRoot.querySelectorAll("pre").forEach((pre) => {
    const firstLine = (pre.textContent || "")
      .trim()
      .split(/\r?\n/)
      .map((s) => s.trim())
      .find(Boolean);
    pre.replaceWith(doc.createTextNode(firstLine ? ` \`${firstLine.slice(0, 48)}\` ` : " "));
  });

  // Insert spaces around block boundaries so adjacent paragraphs don't glue together
  targetRoot.querySelectorAll("p, br, li, div, h1, h2, h3, h4, blockquote, tr").forEach((b) => {
    b.appendChild(doc.createTextNode(" "));
  });

  const cleanText = (targetRoot.textContent || "").replace(/\s+/g, " ").trim();
  const tags: string[] = [];
  if (hasPoll) tags.push("[投票]");
  if (hasCode) tags.push("[代码块]");
  if (hasTable) tags.push("[表格]");
  if (imageCount > 1) tags.push(`[${imageCount}张图片]`);
  else if (imageCount === 1 && !cleanText) tags.push("[图片]");

  const prefix = tags.length > 0 ? `${tags.join(" ")} ` : "";
  const combined = `${prefix}${cleanText}`.trim();
  const snippet = combined.slice(0, 240);
  const isLongOrRich =
    cleanText.length > 108 || imageCount > 0 || hasCode || hasPoll || hasTable;

  const meta: PostReplyPreviewMeta = {
    snippet,
    hasCode,
    hasPoll,
    hasTable,
    imageCount,
    firstImageUrl,
    isLongOrRich,
    leadingQuotedFloor,
    leadingQuotedTopicId,
  };

  if (replyPreviewCache.size >= 600) {
    const oldest = replyPreviewCache.keys().next().value;
    if (oldest) replyPreviewCache.delete(oldest);
  }
  replyPreviewCache.set(cacheKey, meta);
  return meta;
}

/** Normalize protocol-relative or accidentally double-prefixed linux.do hrefs */
function normalizeInternalForumUrl(rawHref: string): URL | null {
  try {
    let clean = (rawHref || "").trim();
    if (!clean) return null;
    if (clean.startsWith("//")) {
      clean = `https:${clean}`;
    } else if (/^https?:\/\/linux\.do\/\/linux\.do\//i.test(clean)) {
      clean = clean.replace(/^https?:\/\/linux\.do\/\/linux\.do\//i, "https://linux.do/");
    }
    const u = new URL(clean, "https://linux.do");
    const host = u.hostname.toLowerCase();
    if (host !== "linux.do" && host !== "www.linux.do") return null;
    return u;
  } catch {
    return null;
  }
}

/** Parse internal linux.do topic links like https://linux.do/t/slug/12345/6, //linux.do/t/topic/12345, /t/12345/6 */
export function parseInternalTopicLink(href: string): { topicId: number; postNumber?: number } | null {
  const u = normalizeInternalForumUrl(href);
  if (!u) return null;
  const pathname = u.pathname.replace(/\/{2,}/g, "/");
  // Match bare numeric `/t/{topicId}` or `/t/{topicId}/{postNumber}` FIRST so greedy slug never swallows `topicId`!
  const m =
    pathname.match(/^\/t\/(\d+)(?:\/(\d+))?\/?$/) ||
    pathname.match(/^\/t\/[^/]+\/(\d+)(?:\/(\d+))?\/?$/);
  if (!m) return null;
  const topicId = Number(m[1]);
  if (!topicId || topicId <= 0) return null;
  let postNumber = m[2] ? Number(m[2]) : undefined;
  if (!postNumber && u.hash) {
    const hm = u.hash.match(/^#post_(\d+)$/i);
    if (hm) postNumber = Number(hm[1]);
  }
  return { topicId, postNumber };
}

export type InternalForumLinkTarget =
  | { kind: "topic"; topicId: number; postNumber?: number }
  | { kind: "post"; postId: number }
  | { kind: "user"; username: string }
  | { kind: "category"; categoryId?: number; slug?: string }
  | { kind: "tag"; tag: string };

/** Parse all natively supported internal linux.do links so topics, posts, profiles, categories, and tags stay inside Lindo PC */
export function parseInternalForumLink(
  href: string,
  anchorEl?: HTMLAnchorElement | null
): InternalForumLinkTarget | null {
  const topicHit = parseInternalTopicLink(href);
  if (topicHit) {
    return { kind: "topic", topicId: topicHit.topicId, postNumber: topicHit.postNumber };
  }

  // Discourse `<a class="hashtag-cooked" data-type="category|tag" data-id="..." data-slug="...">`
  if (anchorEl?.classList.contains("hashtag-cooked")) {
    const hType = (anchorEl.getAttribute("data-type") || "").toLowerCase();
    const hSlug = (anchorEl.getAttribute("data-slug") || "").trim();
    const hId = Number(anchorEl.getAttribute("data-id") || 0);
    if (hType === "category" && (hId > 0 || hSlug)) {
      return { kind: "category", categoryId: hId > 0 ? hId : undefined, slug: hSlug || undefined };
    }
    if (hType === "tag" && hSlug) {
      return { kind: "tag", tag: hSlug };
    }
  }

  const u = normalizeInternalForumUrl(href);
  if (!u) return null;
  const pathname = u.pathname.replace(/\/{2,}/g, "/");

  // Discourse post permalink `/p/{postId}`
  const pm = pathname.match(/^\/p\/(\d+)(?:\/)?$/i);
  if (pm && Number(pm[1]) > 0) {
    return { kind: "post", postId: Number(pm[1]) };
  }

  // User profile `/u/{username}` or `/users/{username}`
  const um = pathname.match(/^\/(?:u|users)\/([^/?#]+)(?:\/summary|\/activity)?\/?$/i);
  if (um && um[1] && um[1].toLowerCase() !== "account-created") {
    return { kind: "user", username: decodeURIComponent(um[1]) };
  }

  // Category `/c/{slug}/{id}` or `/c/{parent}/{sub}/{id}` or `/c/{slug}`
  const cm = pathname.match(/^\/c\/(.+?)\/?$/i);
  if (cm && cm[1]) {
    const segs = cm[1].split("/").filter(Boolean);
    const lastSeg = segs[segs.length - 1] || "";
    if (/^\d+$/.test(lastSeg)) {
      const prevSlug = segs.length >= 2 ? decodeURIComponent(segs[segs.length - 2]) : undefined;
      return { kind: "category", categoryId: Number(lastSeg), slug: prevSlug };
    }
    if (segs.length >= 1) {
      return { kind: "category", slug: decodeURIComponent(lastSeg) };
    }
  }

  // Tag `/tag/{slug}` or `/tags/{slug}` or `/tags/c/.../{slug}`
  const tm = pathname.match(/^\/tags?(?:\/c\/[^/]+(?:\/[^/]+)*)?\/([^/?#]+)\/?$/i);
  if (tm && tm[1] && !/^(intersection|latest|top|new)$/i.test(tm[1])) {
    return { kind: "tag", tag: decodeURIComponent(tm[1]) };
  }

  return null;
}

/* ==================== 外链安全风险分级与白名单记忆（对齐官方 Web Theme #128） ==================== */

export type ExternalUrlSafetyTier =
  | "INTERNAL"
  | "TRUSTED"
  | "STANDARD"
  | "RISKY"
  | "BLOCKED";

export interface ExternalUrlAssessment {
  tier: ExternalUrlSafetyTier;
  normalizedUrl: string;
  hostname: string;
  canRememberHost: boolean;
  riskReason?: string;
}

const TRUSTED_EXT_HOSTS_STORAGE_KEY = "lindo.trusted_external_hosts.v1";

const SHORT_LINK_DOMAINS = new Set([
  "bit.ly",
  "t.cn",
  "tinyurl.com",
  "dwz.cn",
  "url.cn",
  "is.gd",
  "cutt.ly",
  "rb.gy",
  "reurl.cc",
  "suo.im",
  "shorturl.at",
  "goo.gl",
  "ow.ly",
  "v.gd",
  "tiny.cc",
  "t.co",
  "b23.tv",
]);

const TYPOSQUAT_BLOCKED_DOMAINS = new Set([
  "linuxdo.com",
  "linux-do.com",
  "linuxdo.org",
  "linux-do.org",
  "linuxdo.net",
  "linux-do.net",
  "1inux.do",
  "llnux.do",
  "Iinux.do".toLowerCase(),
  "linux.d0",
]);

export function getTrustedExternalHosts(): Set<string> {
  try {
    const raw = localStorage.getItem(TRUSTED_EXT_HOSTS_STORAGE_KEY);
    if (!raw) return new Set();
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return new Set();
    return new Set(
      parsed
        .map((s) => String(s || "").trim().toLowerCase())
        .filter(Boolean)
    );
  } catch {
    return new Set();
  }
}

export function isTrustedExternalHost(hostname: string): boolean {
  const clean = hostname.trim().toLowerCase();
  if (!clean) return false;
  return getTrustedExternalHosts().has(clean);
}

export function rememberTrustedExternalHost(hostname: string): void {
  const clean = hostname.trim().toLowerCase();
  if (!clean || SHORT_LINK_DOMAINS.has(clean)) return;
  try {
    const set = getTrustedExternalHosts();
    set.add(clean);
    localStorage.setItem(
      TRUSTED_EXT_HOSTS_STORAGE_KEY,
      JSON.stringify(Array.from(set).slice(-200))
    );
  } catch {
    // ignore storage quota errors
  }
}

export function classifyExternalUrl(
  rawUrl: string,
  anchorDisplayText?: string | null
): ExternalUrlAssessment {
  const trimmed = (rawUrl || "").trim();
  if (!trimmed) {
    return {
      tier: "BLOCKED",
      normalizedUrl: "",
      hostname: "",
      canRememberHost: false,
      riskReason: "无效的空链接",
    };
  }

  if (/^(javascript|vbscript|data|file|blob):/i.test(trimmed)) {
    return {
      tier: "BLOCKED",
      normalizedUrl: trimmed,
      hostname: "",
      canRememberHost: false,
      riskReason: "已拦截不安全的脚本或本地协议链接",
    };
  }

  if (/^mailto:/i.test(trimmed)) {
    return {
      tier: "INTERNAL",
      normalizedUrl: trimmed,
      hostname: "mailto",
      canRememberHost: false,
    };
  }

  let parsed: URL;
  try {
    parsed = new URL(trimmed, "https://linux.do");
  } catch {
    return {
      tier: "BLOCKED",
      normalizedUrl: trimmed,
      hostname: "",
      canRememberHost: false,
      riskReason: "链接地址格式异常，无法安全解析",
    };
  }

  const protocol = parsed.protocol.toLowerCase();
  if (protocol !== "https:" && protocol !== "http:") {
    return {
      tier: "BLOCKED",
      normalizedUrl: parsed.href,
      hostname: parsed.hostname.toLowerCase(),
      canRememberHost: false,
      riskReason: `不支持打开非网页协议 (${protocol.replace(":", "")})`,
    };
  }

  const host = parsed.hostname.toLowerCase();
  const isOfficialInternal =
    host === "linux.do" ||
    host.endsWith(".linux.do") ||
    host === "ldstatic.com" ||
    host.endsWith(".ldstatic.com");

  if (isOfficialInternal) {
    return {
      tier: "INTERNAL",
      normalizedUrl: parsed.href,
      hostname: host,
      canRememberHost: false,
    };
  }

  const bareHost = host.replace(/^www\./, "");
  if (TYPOSQUAT_BLOCKED_DOMAINS.has(host) || TYPOSQUAT_BLOCKED_DOMAINS.has(bareHost)) {
    return {
      tier: "BLOCKED",
      normalizedUrl: parsed.href,
      hostname: host,
      canRememberHost: false,
      riskReason: "检测到疑似仿冒 LINUX DO 社区的混淆域名，已为您拦截访问",
    };
  }

  // Disguised anchor text check: e.g. `[https://github.com/foo](https://phishing.xyz)`
  const cleanAnchor = (anchorDisplayText || "").trim();
  const anchorUrlMatch = cleanAnchor.match(/^https?:\/\/([a-z0-9.-]+)(?:[/:?#]|$)/i);
  if (anchorUrlMatch) {
    const shownHost = anchorUrlMatch[1].toLowerCase().replace(/^www\./, "");
    if (shownHost && shownHost !== bareHost && !bareHost.endsWith(`.${shownHost}`)) {
      return {
        tier: "RISKY",
        normalizedUrl: parsed.href,
        hostname: host,
        canRememberHost: false,
        riskReason: `链接文字显示为 ${shownHost}，但实际指向 ${host}，请警惕伪装钓鱼链接`,
      };
    }
  }

  if (SHORT_LINK_DOMAINS.has(host) || SHORT_LINK_DOMAINS.has(bareHost)) {
    return {
      tier: "RISKY",
      normalizedUrl: parsed.href,
      hostname: host,
      canRememberHost: false,
      riskReason: "短链接跳转服务隐藏了最终目标站点，打开前请确认发帖人可信",
    };
  }

  if (/^\d{1,3}(?:\.\d{1,3}){3}$/.test(host) || host.startsWith("[")) {
    return {
      tier: "RISKY",
      normalizedUrl: parsed.href,
      hostname: host,
      canRememberHost: false,
      riskReason: "该链接直接指向裸 IP 地址而非经过认证的域名，请注意甄别",
    };
  }

  if (host.includes("xn--")) {
    return {
      tier: "RISKY",
      normalizedUrl: parsed.href,
      hostname: host,
      canRememberHost: false,
      riskReason: "检测到国际化编码域名 (Punycode)，请核对域名拼写以防同形异义字符仿冒",
    };
  }

  if (protocol === "http:") {
    return {
      tier: "RISKY",
      normalizedUrl: parsed.href,
      hostname: host,
      canRememberHost: false,
      riskReason: "该链接使用未加密的 HTTP 协议，请勿在目标页面输入任何账号或密码",
    };
  }

  if (isTrustedExternalHost(host)) {
    return {
      tier: "TRUSTED",
      normalizedUrl: parsed.href,
      hostname: host,
      canRememberHost: true,
    };
  }

  return {
    tier: "STANDARD",
    normalizedUrl: parsed.href,
    hostname: host,
    canRememberHost: true,
  };
}

/** Pangu spacing optimizer for CJK + ASCII/numbers (preserving fenced code, inline code, links & @mentions) */
export function optimizePanguSpacing(input: string): string {
  if (!input.trim()) return input;
  const placeholders: string[] = [];
  const mask = (s: string) =>
    s.replace(/```[\s\S]*?```|`[^`\n]+`|!\[[^\]]*\]\([^)]*\)|\[[^\]]*\]\([^)]*\)|https?:\/\/\S+|@[\w-]+/g, (m) => {
      const idx = placeholders.push(m) - 1;
      return `\uE000${idx}\uE001`;
    });

  let masked = mask(input);
  masked = masked
    .replace(/([\u4e00-\u9fff\u3040-\u30ff])([A-Za-z0-9])/g, "$1 $2")
    .replace(/([A-Za-z0-9])([\u4e00-\u9fff\u3040-\u30ff])/g, "$1 $2");

  return masked.replace(/\uE000(\d+)\uE001/g, (_, idx) => placeholders[Number(idx)] ?? "");
}
