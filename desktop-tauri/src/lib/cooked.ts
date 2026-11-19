import {
  getCachedForumMediaBlobUrl,
  needsSameOriginMediaBridge,
  TRANSPARENT_1PX_GIF,
} from "./forumMedia";
import { resolveForumAssetUrl } from "./palette";

/**
 * Sanitizes Discourse `cooked` HTML and normalizes relative `linux.do` asset/link URLs
 * so native WebKit DOM renders emojis, uploads, tables, quotes, spoilers, and code blocks cleanly.
 */
export function prepareCookedHtml(rawHtml: string, opts?: { stripPolls?: boolean }): string {
  if (!rawHtml) return "";
  const parser = new DOMParser();
  const doc = parser.parseFromString(rawHtml, "text/html");

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
      } else if ((name === "href" || name === "src") && /^\s*javascript:/i.test(attr.value)) {
        el.removeAttribute(attr.name);
      }
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
      // Unwrap Discourse lightbox anchor wrapper around images so click triggers our native lightbox
      const parentAnchor = el.parentElement;
      if (parentAnchor && parentAnchor.tagName === "A" && parentAnchor.classList.contains("lightbox")) {
        const fullHref = parentAnchor.getAttribute("href");
        if (fullHref) {
          el.setAttribute("data-full-src", resolveForumAssetUrl(fullHref));
        }
        // Remove Discourse's `.meta` filename/resolution caption noise inside `.lightbox-wrapper`
        parentAnchor.querySelectorAll(".meta").forEach((m) => m.remove());
      }
    }

    if (el instanceof HTMLAnchorElement) {
      const href = el.getAttribute("href") || "";
      if (href.startsWith("/")) {
        el.setAttribute("href", `https://linux.do${href}`);
      }
    }
  });

  // Enhance Discourse `<aside class="quote" data-post="N">` headers with a clean floor jump badge
  doc.querySelectorAll("aside.quote").forEach((aside) => {
    const postNum = aside.getAttribute("data-post");
    const topicAttr = aside.getAttribute("data-topic") || "";
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
      if (postNum && Number(postNum) > 0 && !titleEl.querySelector("[data-quote-jump-floor]")) {
        const jumpBtn = doc.createElement("button");
        jumpBtn.setAttribute("type", "button");
        jumpBtn.setAttribute("data-quote-jump-floor", postNum);
        if (topicAttr) {
          jumpBtn.setAttribute("data-quote-jump-topic", topicAttr);
        }
        jumpBtn.textContent = `#${postNum} 楼 ›`;
        titleEl.appendChild(jumpBtn);
      }
    }
  });

  // Add one-click copy button (and language badge if present) to every <pre> code block
  doc.querySelectorAll("pre").forEach((pre) => {
    const codeEl = pre.querySelector("code");
    const langMatch = codeEl?.className.match(/lang-([a-zA-Z0-9_+-]+)/);
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

/** Parse internal linux.do topic links like https://linux.do/t/slug/12345/6 */
export function parseInternalTopicLink(href: string): { topicId: number; postNumber?: number } | null {
  try {
    const u = new URL(href, "https://linux.do");
    if (u.hostname !== "linux.do") return null;
    const m = u.pathname.match(/^\/t\/(?:[^/]+\/)?(\d+)(?:\/(\d+))?/);
    if (!m) return null;
    return {
      topicId: Number(m[1]),
      postNumber: m[2] ? Number(m[2]) : undefined,
    };
  } catch {
    return null;
  }
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
