import { invoke } from "@tauri-apps/api/core";
import type { DiagRuntimeSnapshot } from "./types";

export const DESKTOP_APP_VERSION = "1.1.9";

export type DiagCategory = "NETWORK" | "AUTH" | "ERROR" | "SYSTEM";
export type DiagLevel = "INFO" | "WARN" | "ERROR";

export interface DiagEntryMeta {
  method?: string;
  path?: string;
  status?: number;
  durationMs?: number;
  note?: string;
}

export interface DiagEntry extends DiagEntryMeta {
  id: number;
  timestampIso: string;
  level: DiagLevel;
  category: DiagCategory;
  tag: string;
  summary: string;
  detail?: string;
}

const STORAGE_KEY = "lindo_desktop_diag_v1";
const MAX_ENTRIES = 600;

const SENSITIVE_PATTERNS: Array<[RegExp, string]> = [
  [/\b(_t|_forum_session|cf_clearance|__cf_bm|_cfuvid|cf_chl_rc_i|cf_chl_rc_ni)\s*=\s*([^;\r\n"'\s&]+)/gi, "$1=<REDACTED>"],
  [/\b(User-Api-Key|X-CSRF-Token|Authorization|Set-Cookie|Cookie)\s*:\s*([^\r\n;]+)/gi, "$1: <REDACTED>"],
  [/\b(Bearer)\s+([A-Za-z0-9._\-+/=]+)/gi, "$1 <REDACTED>"],
  [/(\/session\/otp\/)([^/?#\s"']+)/gi, "$1<REDACTED_OTP>"],
  [
    /([?&](?:payload|oneTimePassword|one_time_password|public_key|nonce|api_key|user_api_key|token|csrf|password|secret)=)([^&#\s"']+)/gi,
    "$1<REDACTED>",
  ],
];

export function redactDiagText(raw: string): string {
  if (!raw) return "";
  let out = raw;
  out = out.replace(/(?:lindo|fluxdo):\/\/\/?qr-login\?[^\s"']+/gi, (match) =>
    match.replace(/([?&](?:k|o)=)([^&#\s"']+)/gi, "$1<REDACTED>")
  );
  for (const [re, rep] of SENSITIVE_PATTERNS) {
    out = out.replace(re, rep);
  }
  return out;
}

let nextId = Date.now();
let memoryEntries: DiagEntry[] | null = null;
const listeners = new Set<() => void>();
let persistTimer: ReturnType<typeof setTimeout> | null = null;

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

function flushPersistNow(): void {
  if (persistTimer !== null) {
    clearTimeout(persistTimer);
    persistTimer = null;
  }
  try {
    if (memoryEntries) {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(memoryEntries.slice(0, MAX_ENTRIES)));
    }
  } catch {
    // Trim oldest half if storage quota is tight
    try {
      if (memoryEntries && memoryEntries.length > 100) {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(memoryEntries.slice(0, 200)));
      }
    } catch {
      // ignore
    }
  }
}

function persistAndNotify(immediate = false): void {
  if (immediate) {
    flushPersistNow();
  } else if (persistTimer === null) {
    persistTimer = setTimeout(flushPersistNow, 150);
  }
  listeners.forEach((fn) => fn());
}

export function diagLog(
  level: DiagLevel,
  category: DiagCategory,
  tag: string,
  summary: string,
  detail?: string,
  meta?: DiagEntryMeta
): void {
  const list = ensureLoaded();
  const entry: DiagEntry = {
    id: ++nextId,
    timestampIso: new Date().toISOString(),
    level,
    category,
    tag,
    summary: redactDiagText(summary).slice(0, 360),
    detail: detail ? redactDiagText(detail).slice(0, 4000) : undefined,
    ...(meta?.method ? { method: meta.method.toUpperCase() } : {}),
    ...(meta?.path ? { path: redactDiagText(meta.path).slice(0, 260) } : {}),
    ...(typeof meta?.status === "number" ? { status: meta.status } : {}),
    ...(typeof meta?.durationMs === "number" ? { durationMs: Math.max(0, Math.round(meta.durationMs)) } : {}),
    ...(meta?.note ? { note: redactDiagText(meta.note).slice(0, 180) } : {}),
  };
  list.unshift(entry);
  if (list.length > MAX_ENTRIES) {
    list.length = MAX_ENTRIES;
  }
  persistAndNotify(level === "ERROR");
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
  if (persistTimer !== null) {
    clearTimeout(persistTimer);
    persistTimer = null;
  }
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

export async function fetchDiagRuntimeSnapshot(): Promise<DiagRuntimeSnapshot | null> {
  try {
    return await invoke<DiagRuntimeSnapshot>("get_diag_runtime_snapshot");
  } catch {
    return null;
  }
}

export function buildDiagReport(
  appVersion: string,
  isAuthorized: boolean,
  category?: DiagCategory | "ALL",
  snapshot?: DiagRuntimeSnapshot | null,
  username?: string | null
): string {
  const entries = getDiagEntries(category);
  const warnCount = entries.filter((e) => e.level === "WARN").length;
  const errCount = entries.filter((e) => e.level === "ERROR").length;
  const headerLines = [
    `=== Lindo Desktop Diagnostics Report ===`,
    `Version      : v${appVersion}`,
    `ExportedAt   : ${new Date().toISOString()}`,
    `Platform     : ${snapshot ? `${snapshot.os} (${snapshot.arch})` : navigator.platform || "unknown"} · DPR ${window.devicePixelRatio || 1} · ${window.innerWidth}x${window.innerHeight} · ${navigator.onLine ? "Online" : "Offline"}`,
    `UserAgent    : ${redactDiagText(navigator.userAgent)}`,
    `Session      : ${isAuthorized ? (username ? `Authorized (@${username})` : "Authorized") : "Guest"}${snapshot ? ` · SavedAccounts=${snapshot.savedAccountsCount}` : ""}`,
  ];
  if (snapshot) {
    headerLines.push(
      `BridgeUA     : ${snapshot.bridgeUa ? redactDiagText(snapshot.bridgeUa) : "unknown"}`,
      `BridgeState  : originReady=${snapshot.originReady}, verifying=${snapshot.verifying}, bootChallenged=${snapshot.bootstrapChallenged}, cfAttention=${snapshot.cfAttentionNotified}`,
      ...(snapshot.echState?.supported ? [`ECH State    : ${snapshot.echState.diagLine}`] : []),
      `CookieFlags  : _t=${snapshot.hasCookieT}, _forum_session=${snapshot.hasForumSession}, cf_clearance=${snapshot.hasCfClearance}${snapshot.cfClearanceAgeSecs != null ? ` (age=${snapshot.cfClearanceAgeSecs}s)` : ""}`,
      `VerifyPolicy : round=${snapshot.verifyRound ?? 0}, pendingConnectionRound=${snapshot.pendingConnectionRound ?? "-"}, fails=${snapshot.verifyConsecutiveFailures ?? 0}, cooldownRemainMs=${snapshot.verifyCooldownRemainMs ?? 0}, ineffectiveBreaker=${Boolean(snapshot.verifyIneffectiveBreaker)}`,
      `GuardState   : rateLimitRemainMs=${snapshot.rateLimitRemainMs}, fgChallengeRemainMs=${snapshot.fgChallengeRemainMs}, bgChallengeRemainMs=${snapshot.bgChallengeRemainMs}, mediaCooldownRemainMs=${snapshot.mediaCooldownRemainMs}, pacer=${snapshot.pacerRatePerSec ?? 0}/s, gen=${snapshot.sessionGeneration ?? 1}`,
      `MediaCache   : entries=${snapshot.mediaCacheEntries}, bytes=${snapshot.mediaCacheBytes}`
    );
  }
  headerLines.push(
    `Entries      : ${entries.length} (filter=${category || "ALL"}, errors=${errCount}, warns=${warnCount})`,
    `Privacy      : All tokens, cookies, CSRF headers and OTP payloads are automatically redacted.`,
    `========================================`,
    ""
  );

  const body = entries
    .map((e) => {
      const metaParts: string[] = [];
      if (e.method) metaParts.push(e.method);
      if (typeof e.status === "number") metaParts.push(`HTTP ${e.status}`);
      if (typeof e.durationMs === "number") metaParts.push(`${e.durationMs}ms`);
      const metaSuffix = metaParts.length > 0 ? ` (${metaParts.join(", ")})` : "";
      const head = `[${e.timestampIso}] [${e.level}] [${e.category}/${e.tag}] ${e.summary}${metaSuffix}`;
      return e.detail ? `${head}\n  ${e.detail.replace(/\n/g, "\n  ")}` : head;
    })
    .join("\n\n");

  return headerLines.join("\n") + (body || "(暂无运行日志记录)\n");
}

export async function exportDiagLogToFile(
  appVersion: string,
  isAuthorized: boolean,
  category?: DiagCategory | "ALL",
  username?: string | null
): Promise<string> {
  const snapshot = await fetchDiagRuntimeSnapshot();
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, "0");
  const stamp = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(
    now.getHours()
  )}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
  const fileName = `lindo-desktop-diagnostics-${stamp}.log`;
  const content = buildDiagReport(appVersion, isAuthorized, category, snapshot, username);
  return invoke<string>("export_diagnostic_log", { fileName, content });
}

let globalHooksInstalled = false;
export function installGlobalDiagHooks(appVersion: string): void {
  if (globalHooksInstalled || typeof window === "undefined") return;
  globalHooksInstalled = true;
  diagLog(
    "INFO",
    "SYSTEM",
    "Bootstrap",
    `Lindo Desktop v${appVersion} 启动就绪`,
    `viewport=${window.innerWidth}x${window.innerHeight} dpr=${window.devicePixelRatio || 1} online=${navigator.onLine} platform=${navigator.platform || "unknown"}`
  );
  window.addEventListener("online", () => {
    diagLog("INFO", "NETWORK", "Connectivity", "系统网络已恢复连接");
  });
  window.addEventListener("offline", () => {
    diagLog("WARN", "NETWORK", "Connectivity", "系统网络已断开");
  });
  window.addEventListener("beforeunload", flushPersistNow);
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
