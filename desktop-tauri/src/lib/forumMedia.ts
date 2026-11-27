import { useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { resolveForumAssetUrl } from "./palette";
import { diagLog } from "./diagnostics";

export const TRANSPARENT_1PX_GIF =
  "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7";

interface ForumMediaPayload {
  status: number;
  contentType: string;
  dataBase64: string;
}

const MAX_BLOB_CACHE_ENTRIES = 240;
const blobUrlCache = new Map<string, string>();
const inFlightFetches = new Map<string, Promise<string | null>>();
const loggedMediaFailures = new Set<string>();

/**
 * Returns true if the resolved URL points to `linux.do` or `*.linux.do`
 * (meaning cross-site `<img>` from `tauri://localhost` / `http://tauri.localhost`
 * would lack `SameSite=Lax` `_t` / ITP-blocked `cf_clearance` and must use same-origin bridge).
 */
export function needsSameOriginMediaBridge(url?: string | null): boolean {
  if (!url) return false;
  if (
    url.startsWith("/") ||
    url.startsWith("data:") ||
    url.startsWith("blob:") ||
    url.startsWith("https://cdn.ldstatic.com/") ||
    url.startsWith("https://avatars.discourse-cdn.com/")
  ) {
    return false;
  }
  try {
    const parsed = new URL(url);
    const host = parsed.hostname.toLowerCase();
    return host === "linux.do" || host.endsWith(".linux.do");
  } catch {
    return false;
  }
}

export function clearForumMediaBlobCache(): number {
  const count = blobUrlCache.size;
  for (const blobUrl of blobUrlCache.values()) {
    try {
      URL.revokeObjectURL(blobUrl);
    } catch {
      // ignore
    }
  }
  blobUrlCache.clear();
  inFlightFetches.clear();
  loggedMediaFailures.clear();
  return count;
}

export function getCachedForumMediaBlobUrl(url: string): string | null {
  const hit = blobUrlCache.get(url);
  if (!hit) return null;
  // Refresh LRU order
  blobUrlCache.delete(url);
  blobUrlCache.set(url, hit);
  return hit;
}

function putCachedForumMediaBlobUrl(url: string, blobUrl: string): void {
  const prev = blobUrlCache.get(url);
  if (prev && prev !== blobUrl) {
    try {
      URL.revokeObjectURL(prev);
    } catch {
      // ignore
    }
  }
  blobUrlCache.delete(url);
  blobUrlCache.set(url, blobUrl);
  while (blobUrlCache.size > MAX_BLOB_CACHE_ENTRIES) {
    const oldestKey = blobUrlCache.keys().next().value;
    if (!oldestKey) break;
    const oldestBlob = blobUrlCache.get(oldestKey);
    blobUrlCache.delete(oldestKey);
    if (oldestBlob) {
      try {
        URL.revokeObjectURL(oldestBlob);
      } catch {
        // ignore
      }
    }
  }
}

export function fetchForumMediaBlobUrl(rawUrl: string): Promise<string | null> {
  const cleanUrl = resolveForumAssetUrl(rawUrl);
  if (!cleanUrl) return Promise.resolve(null);
  if (!needsSameOriginMediaBridge(cleanUrl)) {
    return Promise.resolve(cleanUrl);
  }
  const cached = getCachedForumMediaBlobUrl(cleanUrl);
  if (cached) return Promise.resolve(cached);

  const existing = inFlightFetches.get(cleanUrl);
  if (existing) return existing;

  const task = (async () => {
    const t0 = performance.now();
    try {
      const payload = await invoke<ForumMediaPayload>("site_fetch_media", { url: cleanUrl });
      const ms = Math.round(performance.now() - t0);
      if (
        !payload ||
        payload.status < 200 ||
        payload.status >= 300 ||
        !payload.dataBase64
      ) {
        const status = payload?.status ?? 0;
        if (loggedMediaFailures.size < 120 && !loggedMediaFailures.has(cleanUrl)) {
          loggedMediaFailures.add(cleanUrl);
          const pathOnly = cleanUrl.replace(/^https?:\/\/[^/]+/i, "");
          diagLog(
            "WARN",
            "NETWORK",
            "MediaBridge",
            `GET ${cleanUrl} -> HTTP ${status} (媒体加载失败, ${ms}ms)`,
            `url: ${cleanUrl}\nstatus: ${status}\ncontentType: ${payload?.contentType || "unknown"}\nduration: ${ms}ms`,
            { method: "GET", path: pathOnly, status, durationMs: ms, note: "媒体桥接加载失败" }
          );
        }
        return null;
      }
      const mime = payload.contentType || "image/png";
      // Off-main-thread native C++ base64 decode via `fetch(data:...)` instead of synchronous JS `atob` + multi-million charCodeAt loop
      const dataResp = await fetch(`data:${mime};base64,${payload.dataBase64}`);
      const blob = await dataResp.blob();
      const blobUrl = URL.createObjectURL(blob);
      putCachedForumMediaBlobUrl(cleanUrl, blobUrl);
      return blobUrl;
    } catch (err: any) {
      const ms = Math.round(performance.now() - t0);
      const reason = String(err?.message || err || "媒体桥接异常");
      if (
        !reason.includes("verification in progress") &&
        loggedMediaFailures.size < 120 &&
        !loggedMediaFailures.has(cleanUrl)
      ) {
        loggedMediaFailures.add(cleanUrl);
        const pathOnly = cleanUrl.replace(/^https?:\/\/[^/]+/i, "");
        diagLog(
          "WARN",
          "NETWORK",
          "MediaBridge",
          `GET ${cleanUrl} 调用异常 (${ms}ms)`,
          `url: ${cleanUrl}\nerror: ${reason}\nduration: ${ms}ms`,
          { method: "GET", path: pathOnly, durationMs: ms, note: reason }
        );
      }
      return null;
    } finally {
      inFlightFetches.delete(cleanUrl);
    }
  })();

  inFlightFetches.set(cleanUrl, task);
  return task;
}

/**
 * React hook that resolves static CDN URLs synchronously on the first frame,
 * and seamlessly bridges same-origin `https://linux.do/...` assets (`/uploads/...`, GIF avatars)
 * into cached `blob:` URLs without EVER emitting a cross-site `<img>` request to `linux.do`.
 */
export function useForumImageSrc(
  rawUrl?: string | null,
  fallbackUrl: string = TRANSPARENT_1PX_GIF
): string {
  const resolved = rawUrl ? resolveForumAssetUrl(rawUrl) : "";
  const requiresBridge = needsSameOriginMediaBridge(resolved);

  const [bridgedSrc, setBridgedSrc] = useState<string>(() => {
    if (!resolved) return fallbackUrl;
    if (!requiresBridge) return resolved;
    return getCachedForumMediaBlobUrl(resolved) ?? fallbackUrl;
  });

  useEffect(() => {
    if (!resolved) {
      setBridgedSrc(fallbackUrl);
      return;
    }
    if (!requiresBridge) {
      setBridgedSrc(resolved);
      return;
    }
    const syncHit = getCachedForumMediaBlobUrl(resolved);
    if (syncHit) {
      setBridgedSrc(syncHit);
      return;
    }
    setBridgedSrc(fallbackUrl);
    let cancelled = false;
    fetchForumMediaBlobUrl(resolved).then((blobUrl) => {
      if (!cancelled && blobUrl) {
        setBridgedSrc(blobUrl);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [resolved, requiresBridge, fallbackUrl]);

  if (!resolved) return fallbackUrl;
  if (!requiresBridge) return resolved;
  return getCachedForumMediaBlobUrl(resolved) ?? bridgedSrc;
}

let sharedCookedObserver: IntersectionObserver | null = null;

const MAX_IMAGE_DIM_CACHE = 500;
const imageNaturalDimCache = new Map<string, { width: number; height: number }>();

export function rememberImageNaturalDimensions(
  url: string | null | undefined,
  width: number,
  height: number
): void {
  const key = (url || "").trim();
  if (!key || !Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) {
    return;
  }
  const w = Math.round(width);
  const h = Math.round(height);
  if (w <= 0 || h <= 0 || w > 20000 || h > 20000) return;
  if (imageNaturalDimCache.has(key)) {
    imageNaturalDimCache.delete(key);
  } else if (imageNaturalDimCache.size >= MAX_IMAGE_DIM_CACHE) {
    const oldest = imageNaturalDimCache.keys().next().value;
    if (oldest) imageNaturalDimCache.delete(oldest);
  }
  imageNaturalDimCache.set(key, { width: w, height: h });
}

export function getCachedImageNaturalDimensions(
  url: string | null | undefined
): { width: number; height: number } | null {
  const key = (url || "").trim();
  if (!key) return null;
  return imageNaturalDimCache.get(key) ?? null;
}

export function markCookedImageLoaded(img: HTMLImageElement): void {
  img.classList.remove("lindo-img-loading", "lindo-img-loading-fallback", "lindo-img-error");
  img.removeAttribute("data-lindo-loading");
  img.style.removeProperty("aspect-ratio");

  const nw = img.naturalWidth;
  const nh = img.naturalHeight;
  if (nw > 0 && nh > 0) {
    const canonical = img.getAttribute("data-canonical-src") || img.getAttribute("src");
    if (canonical && !canonical.startsWith("data:") && !canonical.startsWith("blob:")) {
      rememberImageNaturalDimensions(canonical, nw, nh);
    }
    if (img.classList.contains("lindo-img-sized")) {
      const declaredAr = Number(img.style.getPropertyValue("--lindo-img-ar"));
      const actualAr = nw / nh;
      if (
        Number.isFinite(declaredAr) &&
        declaredAr > 0 &&
        Math.abs(actualAr - declaredAr) / declaredAr > 0.05
      ) {
        // Declared HTML dimensions disagreed with real bitmap aspect ratio: release lock to prevent distortion
        img.classList.remove("lindo-img-sized");
        img.style.removeProperty("--lindo-img-w");
        img.style.removeProperty("--lindo-img-ar");
      }
    }
  }
  img.classList.add("lindo-img-loaded");
}

export function markCookedImageError(img: HTMLImageElement): void {
  img.classList.remove(
    "lindo-img-loading",
    "lindo-img-loading-fallback",
    "lindo-img-loaded",
    "lindo-img-sized"
  );
  img.removeAttribute("data-lindo-loading");
  img.style.removeProperty("aspect-ratio");
  img.style.removeProperty("--lindo-img-w");
  img.style.removeProperty("--lindo-img-ar");
  img.classList.add("lindo-img-error");
}

function attachDecodeCleanupOnce(img: HTMLImageElement): () => void {
  const onLoad = () => {
    cleanup();
    markCookedImageLoaded(img);
  };
  const onError = () => {
    cleanup();
    markCookedImageError(img);
  };
  const cleanup = () => {
    img.removeEventListener("load", onLoad);
    img.removeEventListener("error", onError);
  };
  img.addEventListener("load", onLoad, { once: true });
  img.addEventListener("error", onError, { once: true });
  return cleanup;
}

function applyResolvedBlobToImage(img: HTMLImageElement, blobUrl: string): void {
  attachDecodeCleanupOnce(img);
  img.src = blobUrl;
  img.removeAttribute("data-lindo-src");
  if (img.complete && img.naturalWidth > 0) {
    markCookedImageLoaded(img);
  }
}

function hydrateImageElement(img: HTMLImageElement): void {
  const targetUrl = img.getAttribute("data-lindo-src");
  if (!targetUrl || img.getAttribute("data-lindo-hydrating") === "1") return;
  const cached = getCachedForumMediaBlobUrl(targetUrl);
  if (cached) {
    applyResolvedBlobToImage(img, cached);
    return;
  }
  img.setAttribute("data-lindo-hydrating", "1");
  fetchForumMediaBlobUrl(targetUrl).then((blobUrl) => {
    img.removeAttribute("data-lindo-hydrating");
    if (blobUrl) {
      applyResolvedBlobToImage(img, blobUrl);
    } else {
      img.removeAttribute("data-lindo-src");
      markCookedImageError(img);
    }
  });
}

function getSharedCookedObserver(): IntersectionObserver | null {
  if (typeof IntersectionObserver === "undefined") return null;
  if (!sharedCookedObserver) {
    sharedCookedObserver = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            const img = entry.target as HTMLImageElement;
            sharedCookedObserver?.unobserve(img);
            hydrateImageElement(img);
          }
        }
      },
      { rootMargin: "220px 0px" }
    );
  }
  return sharedCookedObserver;
}

export function observeCookedForumImages(container: HTMLElement | null): () => void {
  if (!container) return () => {};
  const cleanups: Array<() => void> = [];

  // 1. Track direct/external images marked with skeleton loading state (cached or still fetching)
  const externalLoading = Array.from(
    container.querySelectorAll<HTMLImageElement>('img[data-lindo-loading="1"]:not([data-lindo-src])')
  );
  for (const img of externalLoading) {
    if (img.complete) {
      if (img.naturalWidth > 0) {
        markCookedImageLoaded(img);
      } else if (img.getAttribute("src") && !img.getAttribute("src")?.startsWith("data:image/gif")) {
        markCookedImageError(img);
      } else {
        cleanups.push(attachDecodeCleanupOnce(img));
      }
    } else {
      cleanups.push(attachDecodeCleanupOnce(img));
    }
  }

  // 2. Hydrate same-origin bridged images via IntersectionObserver + cache
  const pending = Array.from(
    container.querySelectorAll<HTMLImageElement>("img[data-lindo-src]")
  );
  if (pending.length === 0) {
    return () => {
      cleanups.forEach((fn) => fn());
    };
  }

  const observer = getSharedCookedObserver();
  if (!observer) {
    pending.forEach(hydrateImageElement);
    return () => {
      cleanups.forEach((fn) => fn());
    };
  }

  for (const img of pending) {
    const targetUrl = img.getAttribute("data-lindo-src");
    if (!targetUrl) continue;
    const syncHit = getCachedForumMediaBlobUrl(targetUrl);
    if (syncHit) {
      applyResolvedBlobToImage(img, syncHit);
    } else {
      observer.observe(img);
    }
  }

  return () => {
    cleanups.forEach((fn) => fn());
    for (const img of pending) {
      observer.unobserve(img);
    }
  };
}
