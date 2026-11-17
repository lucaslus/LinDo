import { invoke } from "@tauri-apps/api/core";

export type DiagCategory = "NETWORK" | "AUTH" | "ERROR" | "SYSTEM";
export type DiagLevel = "INFO" | "WARN" | "ERROR";

export interface DiagEntry {
  id: number;
  timestampIso: string;
  level: DiagLevel;
  category: DiagCategory;
  tag: string;
  summary: string;
  detail?: string;
}

const STORAGE_KEY = "lindo_desktop_diag_v1";
const MAX_ENTRIES = 260;

const SENSITIVE_PATTERNS: Array<[RegExp, string]> = [
  [/(_t|_forum_session|cf_clearance)=([^;\s&"]+)/gi, "$1=<REDACTED>"],
  [/(User-Api-Key|X-CSRF-Token|Authorization)\s*[:=]\s*([^\s,"']+)/gi, "$1: <REDACTED>"],
  [/(\/session\/otp\/)([A-Za-z0-9_-]+)/gi, "$1<REDACTED_OTP>"],
  [/([?&](?:payload|otp|api_key|token)=)([^&\s]+)/gi, "$1<REDACTED>"],
];

export function redactDiagText(raw: string): string {
  if (!raw) return "";
  let out = raw;
  for (const [re, rep] of SENSITIVE_PATTERNS) {
    out = out.replace(re, rep);
  }
  return out;
}

let nextId = Date.now();
let memoryEntries: DiagEntry[] | null = null;
const listeners = new Set<() => void>();

function ensureLoaded(): DiagEntry[] {
  if (memoryEntries !== null) return memoryEntries;
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed)) {
        memoryEntries = parsed.slice(0, MAX_ENTRIES);
        return memoryEntries;
      }
    }
  } catch {
    // ignore corrupted cache
  }
  memoryEntries = [];
  return memoryEntries;
}

function persistAndNotify() {
  try {
    if (memoryEntries) {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(memoryEntries.slice(0, MAX_ENTRIES)));
    }
  } catch {
    // ignore storage quota
  }
  listeners.forEach((fn) => fn());
}

export function diagLog(
  level: DiagLevel,
  category: DiagCategory,
  tag: string,
  summary: string,
  detail?: string
): void {
  const list = ensureLoaded();
  const entry: DiagEntry = {
    id: ++nextId,
    timestampIso: new Date().toISOString(),
    level,
    category,
    tag,
    summary: redactDiagText(summary).slice(0, 320),
    detail: detail ? redactDiagText(detail).slice(0, 2400) : undefined,
  };
  list.unshift(entry);
  if (list.length > MAX_ENTRIES) {
    list.length = MAX_ENTRIES;
  }
  persistAndNotify();
}

export function getDiagEntries(category?: DiagCategory | "ALL"): DiagEntry[] {
  const list = ensureLoaded();
  if (!category || category === "ALL") return [...list];
  if (category === "ERROR") {
    return list.filter(
      (e) => e.category === "ERROR" || e.level === "ERROR" || e.level === "WARN"
    );
  }
  return list.filter((e) => e.category === category);
}

export function clearDiagEntries(): void {
  memoryEntries = [];
  try {
    localStorage.removeItem(STORAGE_KEY);
  } catch {
    // ignore
  }
  listeners.forEach((fn) => fn());
}

export function subscribeDiagEntries(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function buildDiagReport(
  appVersion: string,
  isAuthorized: boolean,
  category?: DiagCategory | "ALL"
): string {
  const entries = getDiagEntries(category);
  const header = [
    `=== Lindo Desktop Diagnostics Report ===`,
    `Version: v${appVersion}`,
    `ExportedAt: ${new Date().toISOString()}`,
    `UserAgent: ${redactDiagText(navigator.userAgent)}`,
    `SessionState: ${isAuthorized ? "Authorized" : "Guest"}`,
    `TotalEntries: ${entries.length}`,
    `========================================`,
    "",
  ].join("\n");

  const body = entries
    .map((e) => {
      const head = `[${e.timestampIso}] [${e.level}] [${e.category}/${e.tag}] ${e.summary}`;
      return e.detail ? `${head}\n  ${e.detail.replace(/\n/g, "\n  ")}` : head;
    })
    .join("\n\n");

  return header + (body || "(暂无异常或诊断记录)\n");
}

export async function exportDiagLogToFile(
  appVersion: string,
  isAuthorized: boolean,
  category?: DiagCategory | "ALL"
): Promise<string> {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, "0");
  const stamp = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(
    now.getHours()
  )}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
  const fileName = `lindo-desktop-diagnostics-${stamp}.log`;
  const content = buildDiagReport(appVersion, isAuthorized, category);
  return invoke<string>("export_diagnostic_log", { fileName, content });
}

let globalHooksInstalled = false;
export function installGlobalDiagHooks(appVersion: string): void {
  if (globalHooksInstalled || typeof window === "undefined") return;
  globalHooksInstalled = true;
  diagLog("INFO", "SYSTEM", "Bootstrap", `Lindo Desktop v${appVersion} 启动就绪`);
  window.addEventListener("error", (ev) => {
    diagLog(
      "ERROR",
      "ERROR",
      "WindowError",
      ev.message || "未捕获运行时异常",
      ev.error?.stack ? String(ev.error.stack) : `${ev.filename || ""}:${ev.lineno || 0}`
    );
  });
  window.addEventListener("unhandledrejection", (ev) => {
    const reason = ev.reason;
    const msg =
      reason instanceof Error ? reason.message : typeof reason === "string" ? reason : "Promise 异常";
    diagLog(
      "WARN",
      "ERROR",
      "UnhandledRejection",
      msg,
      reason instanceof Error ? reason.stack : undefined
    );
  });
}
