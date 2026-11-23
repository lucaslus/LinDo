/**
 * Viewport-aware, zero-jank rich-content decorator for `.lindo-cooked` containers:
 * - Lazy syntax highlighting (`highlight.js/lib/core` subset) only when `<pre><code>` enters viewport
 * - Lazy KaTeX math rendering only when `.math` / `.discourse-math` exists
 * - Lazy Mermaid diagram rendering only when `code.lang-mermaid` / `pre.mermaid` exists
 * - Same-origin `<video>` / `<audio>` media bridging for authenticated `linux.do` uploads
 */
import { fetchForumMediaBlobUrl, needsSameOriginMediaBridge } from "./forumMedia";

let codeModPromise: Promise<typeof import("./richCodeHighlight")> | null = null;
let mathMermaidModPromise: Promise<typeof import("./richMathMermaid")> | null = null;

function getCodeHighlightModule() {
  if (!codeModPromise) {
    codeModPromise = import("./richCodeHighlight");
  }
  return codeModPromise;
}

function getMathMermaidModule() {
  if (!mathMermaidModPromise) {
    mathMermaidModPromise = import("./richMathMermaid");
  }
  return mathMermaidModPromise;
}

function scheduleIdle(fn: () => void): () => void {
  if (typeof window !== "undefined" && "requestIdleCallback" in window) {
    const id = (window as any).requestIdleCallback(fn, { timeout: 220 });
    return () => (window as any).cancelIdleCallback?.(id);
  }
  const id = setTimeout(fn, 16);
  return () => clearTimeout(id);
}

function hydrateMediaElement(mediaEl: HTMLMediaElement): void {
  if (mediaEl.dataset.lindoMediaHydrated === "1") return;
  mediaEl.dataset.lindoMediaHydrated = "1";

  const srcAttr = mediaEl.getAttribute("data-lindo-media-src");
  if (srcAttr && needsSameOriginMediaBridge(srcAttr)) {
    void fetchForumMediaBlobUrl(srcAttr).then((blobUrl) => {
      if (blobUrl && mediaEl.isConnected) {
        mediaEl.src = blobUrl;
      }
    });
  }

  const sources = Array.from(mediaEl.querySelectorAll<HTMLSourceElement>("source[data-lindo-media-src]"));
  if (sources.length > 0) {
    void Promise.all(
      sources.map(async (sourceEl) => {
        const raw = sourceEl.getAttribute("data-lindo-media-src");
        if (!raw || !needsSameOriginMediaBridge(raw)) return false;
        const blobUrl = await fetchForumMediaBlobUrl(raw);
        if (blobUrl && sourceEl.isConnected) {
          sourceEl.src = blobUrl;
          return true;
        }
        return false;
      })
    ).then((results) => {
      if (results.some(Boolean) && mediaEl.isConnected) {
        try {
          mediaEl.load();
        } catch {
          // ignore media load errors in headless environments
        }
      }
    });
  }
}

function decorateElement(target: HTMLElement): void {
  if (!target.isConnected) return;
  const role = target.dataset.lindoRichRole;
  if (role === "code") {
    void getCodeHighlightModule().then(({ highlightCodeElement }) => {
      if (target.isConnected) highlightCodeElement(target);
    });
  } else if (role === "math") {
    void getMathMermaidModule().then(({ renderMathElement }) => {
      if (target.isConnected) void renderMathElement(target);
    });
  } else if (role === "mermaid") {
    void getMathMermaidModule().then(({ renderMermaidBlock }) => {
      if (target.isConnected) void renderMermaidBlock(target);
    });
  } else if (role === "media") {
    hydrateMediaElement(target as HTMLMediaElement);
  }
}

/**
 * Attaches lazy rich-content decoration (syntax highlighting, KaTeX, Mermaid, video/audio bridge)
 * to a `.lindo-cooked` DOM container. Uses IntersectionObserver + idle scheduling for zero 144Hz jank.
 */
export function observeCookedRichContent(container: HTMLElement | null): () => void {
  if (!container) return () => {};

  const targets: HTMLElement[] = [];

  // 1. Mermaid blocks (must be identified BEFORE syntax highlighter touches `pre > code`)
  container.querySelectorAll<HTMLElement>("pre.mermaid, pre > code.lang-mermaid, pre > code.language-mermaid").forEach((el) => {
    const pre = el.tagName === "PRE" ? el : (el.parentElement as HTMLElement | null);
    if (!pre || pre.dataset.lindoMermaid === "1") return;
    pre.dataset.lindoRichRole = "mermaid";
    const childCode = pre.querySelector<HTMLElement>("code");
    if (childCode) childCode.dataset.lindoHljs = "1";
    targets.push(pre);
  });

  // 2. Code blocks (`pre > code`)
  container.querySelectorAll<HTMLElement>("pre > code").forEach((codeEl) => {
    if (codeEl.dataset.lindoHljs === "1") return;
    if (codeEl.parentElement?.dataset.lindoRichRole === "mermaid") return;
    codeEl.dataset.lindoRichRole = "code";
    targets.push(codeEl);
  });

  // 3. Math blocks & inline math (`.math`, `.discourse-math`)
  container.querySelectorAll<HTMLElement>(".math, .discourse-math").forEach((mathEl) => {
    if (mathEl.dataset.lindoMath === "1") return;
    mathEl.dataset.lindoRichRole = "math";
    targets.push(mathEl);
  });

  // 4. Same-origin authenticated `<video>` / `<audio>`
  container.querySelectorAll<HTMLMediaElement>("video, audio").forEach((mediaEl) => {
    if (mediaEl.dataset.lindoMediaHydrated === "1") return;
    if (
      mediaEl.hasAttribute("data-lindo-media-src") ||
      Boolean(mediaEl.querySelector("source[data-lindo-media-src]"))
    ) {
      mediaEl.dataset.lindoRichRole = "media";
      targets.push(mediaEl);
    }
  });

  if (targets.length === 0) return () => {};

  const cancelIdleFns: Array<() => void> = [];
  let observer: IntersectionObserver | null = null;

  if (typeof IntersectionObserver !== "undefined") {
    observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (!entry.isIntersecting) continue;
          const el = entry.target as HTMLElement;
          observer?.unobserve(el);
          cancelIdleFns.push(scheduleIdle(() => decorateElement(el)));
        }
      },
      { rootMargin: "320px 0px" }
    );
    for (const t of targets) {
      observer.observe(t);
    }
  } else {
    for (const t of targets) {
      cancelIdleFns.push(scheduleIdle(() => decorateElement(t)));
    }
  }

  return () => {
    observer?.disconnect();
    for (const cancel of cancelIdleFns) cancel();
  };
}
