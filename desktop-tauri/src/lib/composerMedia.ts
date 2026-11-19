import type React from "react";
import {
  type ClipboardMediaItem,
  readClipboardMediaInRust,
  readDroppedFilesInRust,
} from "./api";

export interface PreparedUploadFile {
  filename: string;
  mime: string;
  dataBase64: string;
  isImage: boolean;
  localPreviewUrl?: string;
}

const IMAGE_EXT_RE = /\.(png|jpe?g|gif|webp|svg|avif|bmp|tiff?|heic|heif)$/i;
const SINGLE_IMAGE_FILENAME_RE =
  /^[^\r\n/\\:*?"<>|]+\.(png|jpe?g|gif|webp|svg|avif|bmp|tiff?|heic|heif)$/i;
const STANDALONE_HTTP_URL_RE = /^https?:\/\/[^\s<>"{}|\\^`[\]]+$/i;

export function isImageMimeOrName(mime: string, filename: string): boolean {
  return mime.toLowerCase().startsWith("image/") || IMAGE_EXT_RE.test(filename);
}

export function isStandaloneHttpUrl(text: string): boolean {
  const trimmed = text.trim();
  if (!trimmed || /[\r\n]/.test(trimmed)) return false;
  return STANDALONE_HTTP_URL_RE.test(trimmed);
}

function isSingleImageFilename(text: string): boolean {
  const trimmed = text.trim();
  if (!trimmed || trimmed.length > 260 || /[\r\n]/.test(trimmed)) return false;
  if (/^https?:\/\//i.test(trimmed)) return false;
  return SINGLE_IMAGE_FILENAME_RE.test(trimmed);
}

function formatPasteTimestamp(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`;
}

function extensionForMime(mime: string): string {
  const lower = mime.toLowerCase();
  if (lower === "image/png") return "png";
  if (lower === "image/jpeg" || lower === "image/jpg") return "jpg";
  if (lower === "image/gif") return "gif";
  if (lower === "image/webp") return "webp";
  if (lower === "image/svg+xml") return "svg";
  if (lower === "image/avif") return "avif";
  if (lower === "application/pdf") return "pdf";
  return "bin";
}

function normalizeUploadFilename(rawName: string, mime: string, index: number): string {
  const trimmed = (rawName || "").trim();
  const generic =
    !trimmed ||
    trimmed === "image.png" ||
    trimmed === "image.jpg" ||
    trimmed === "image.jpeg" ||
    trimmed === "blob" ||
    trimmed === "clipboard.png";
  if (!generic) return trimmed;
  const ext = extensionForMime(mime);
  const suffix = index > 0 ? `-${index + 1}` : "";
  return `paste-${formatPasteTimestamp()}${suffix}.${ext}`;
}

function bytesToBase64(bytes: Uint8Array): string {
  let bin = "";
  for (let i = 0; i < bytes.length; i += 8192) {
    bin += String.fromCharCode(...bytes.subarray(i, i + 8192));
  }
  return btoa(bin);
}

async function convertCanvasCompatibleImageToPng(
  blob: Blob
): Promise<{ blob: Blob; mime: string } | null> {
  if (typeof document === "undefined" || typeof createImageBitmap !== "function") {
    return null;
  }
  try {
    const bmp = await createImageBitmap(blob);
    const canvas = document.createElement("canvas");
    canvas.width = bmp.width;
    canvas.height = bmp.height;
    const ctx = canvas.getContext("2d");
    if (!ctx) {
      bmp.close();
      return null;
    }
    ctx.drawImage(bmp, 0, 0);
    bmp.close();
    const pngBlob = await new Promise<Blob | null>((resolve) =>
      canvas.toBlob((b) => resolve(b), "image/png")
    );
    if (!pngBlob) return null;
    return { blob: pngBlob, mime: "image/png" };
  } catch {
    return null;
  }
}

export async function fileToPreparedUpload(
  file: File,
  index = 0
): Promise<PreparedUploadFile> {
  let blob: Blob = file;
  let mime = file.type || "application/octet-stream";
  let filename = normalizeUploadFilename(file.name, mime, index);

  if (
    mime === "image/tiff" ||
    mime === "image/bmp" ||
    /\.(tiff?|bmp)$/i.test(filename)
  ) {
    const converted = await convertCanvasCompatibleImageToPng(file);
    if (converted) {
      blob = converted.blob;
      mime = converted.mime;
      filename = filename.replace(/\.(tiff?|bmp)$/i, ".png");
      if (!filename.toLowerCase().endsWith(".png")) {
        filename = `${filename}.png`;
      }
    }
  }

  const buf = await blob.arrayBuffer();
  const dataBase64 = bytesToBase64(new Uint8Array(buf));
  const isImage = isImageMimeOrName(mime, filename);
  const localPreviewUrl = isImage ? URL.createObjectURL(blob) : undefined;
  return { filename, mime, dataBase64, isImage, localPreviewUrl };
}

export async function nativeItemToPreparedUpload(
  item: ClipboardMediaItem
): Promise<PreparedUploadFile> {
  const isImage = isImageMimeOrName(item.mime, item.filename);
  let localPreviewUrl: string | undefined;
  if (isImage && item.dataBase64) {
    try {
      const resp = await fetch(`data:${item.mime};base64,${item.dataBase64}`);
      const blob = await resp.blob();
      localPreviewUrl = URL.createObjectURL(blob);
    } catch {
      localPreviewUrl = undefined;
    }
  }
  return {
    filename: item.filename,
    mime: item.mime || "application/octet-stream",
    dataBase64: item.dataBase64,
    isImage,
    localPreviewUrl,
  };
}

export async function prepareDroppedPaths(paths: string[]): Promise<PreparedUploadFile[]> {
  const rawItems = await readDroppedFilesInRust(paths);
  const prepared: PreparedUploadFile[] = [];
  for (const item of rawItems) {
    prepared.push(await nativeItemToPreparedUpload(item));
  }
  return prepared;
}

export function extractSyncClipboardFiles(dt: DataTransfer | null): File[] {
  if (!dt) return [];
  const out: File[] = [];
  const seen = new Set<string>();
  const push = (f: File | null) => {
    if (!f || f.size === 0) return;
    const key = `${f.name}|${f.size}|${f.type}`;
    if (seen.has(key)) return;
    seen.add(key);
    out.push(f);
  };
  if (dt.items && dt.items.length > 0) {
    for (const item of Array.from(dt.items)) {
      if (item.kind === "file") {
        push(item.getAsFile());
      }
    }
  }
  if (dt.files && dt.files.length > 0) {
    for (const file of Array.from(dt.files)) {
      push(file);
    }
  }
  return out;
}

function isRichTextWithSnapshotImage(dt: DataTransfer, plainText: string): boolean {
  if (!plainText.trim()) return false;
  const types = Array.from(dt.types || []);
  if (types.includes("Files")) return false;
  if (isSingleImageFilename(plainText) || isStandaloneHttpUrl(plainText)) return false;
  const html = dt.getData("text/html") || "";
  if (!html) return false;
  const isOfficeOrSheet =
    html.includes("<table") ||
    html.includes("office:excel") ||
    html.includes("office:word") ||
    html.includes("docs-internal-guid");
  if (isOfficeOrSheet) return true;
  if (html.includes("Apple-interchange-newline") && !html.includes("<img")) return true;
  return false;
}

export function formatUploadedMarkdown(
  uploaded: {
    shortUrl: string;
    width: number;
    height: number;
    originalFilename: string;
  },
  isImage: boolean
): string {
  const safeName = uploaded.originalFilename.replace(/[[\]|]/g, "_");
  if (isImage && uploaded.width > 0 && uploaded.height > 0) {
    return `![${safeName}|${uploaded.width}x${uploaded.height}](${uploaded.shortUrl})`;
  }
  if (isImage) {
    return `![${safeName}](${uploaded.shortUrl})`;
  }
  return `[${safeName}|attachment](${uploaded.shortUrl})`;
}

export function buildUploadPlaceholder(
  filename: string,
  isImage: boolean,
  uniqueTag: string
): string {
  const safe = filename.replace(/[[\]()]/g, "_");
  const prefix = isImage ? "!" : "";
  return `${prefix}[正在上传：${safe}…](${uniqueTag})`;
}

export interface HandlePasteOptions {
  textarea: HTMLTextAreaElement | null;
  value: string;
  onUploadFiles: (files: PreparedUploadFile[]) => void;
  onInsertText: (text: string) => void;
  onWrapSelectionWithUrl?: (url: string) => boolean;
}

/**
 * Handles image/file paste (browser clipboard + macOS native NSPasteboard fallback for Finder files / TIFF / HEIC)
 * and official Discourse smart-URL wrapping onto selected text, while never interfering with normal text paste.
 */
export function handleComposerTextareaPaste(
  e: React.ClipboardEvent<HTMLTextAreaElement>,
  opts: HandlePasteOptions
): void {
  const dt = e.clipboardData;
  if (!dt) return;

  const plainText = dt.getData("text/plain") || "";
  const types = Array.from(dt.types || []);
  const syncFiles = extractSyncClipboardFiles(dt);

  // 1. Direct WebKit/Chromium clipboard files (screenshots, copied browser images, dragged/copied files)
  if (syncFiles.length > 0 && !isRichTextWithSnapshotImage(dt, plainText)) {
    const hasImage = syncFiles.some((f) => isImageMimeOrName(f.type, f.name));
    if (hasImage || !plainText.trim() || types.includes("Files")) {
      e.preventDefault();
      void Promise.all(syncFiles.map((f, idx) => fileToPreparedUpload(f, idx))).then(
        (prepared) => {
          if (prepared.length > 0) {
            opts.onUploadFiles(prepared);
          } else if (plainText) {
            opts.onInsertText(plainText);
          }
        }
      );
      return;
    }
  }

  // 2. Official Discourse smart URL paste: pasting a URL over selected text wraps it as `[selection](url)`
  if (
    syncFiles.length === 0 &&
    opts.onWrapSelectionWithUrl &&
    isStandaloneHttpUrl(plainText)
  ) {
    if (opts.onWrapSelectionWithUrl(plainText.trim())) {
      e.preventDefault();
      return;
    }
  }

  // 3. macOS Native NSPasteboard fallback:
  //    - Empty text/plain: screenshots (TIFF/PNG), Preview/Photos/WeChat/CleanShot copies not exposed by WKWebView
  //    - Finder Cmd+C on image files: WKWebView often exposes only the bare filename string or `Files` type
  if (syncFiles.length === 0) {
    const noPlainText = plainText.length === 0;
    const maybeFinderFileCopy = types.includes("Files") || isSingleImageFilename(plainText);
    if (noPlainText || maybeFinderFileCopy) {
      e.preventDefault();
      void readClipboardMediaInRust(!noPlainText).then(async (res) => {
        if (res.items && res.items.length > 0) {
          const prepared: PreparedUploadFile[] = [];
          for (const item of res.items) {
            prepared.push(await nativeItemToPreparedUpload(item));
          }
          if (prepared.length > 0) {
            opts.onUploadFiles(prepared);
            return;
          }
        }
        if (plainText) {
          opts.onInsertText(plainText);
        }
      });
    }
  }
}
