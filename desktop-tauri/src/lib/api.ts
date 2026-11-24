import { invoke } from "@tauri-apps/api/core";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { useEffect, useRef, useState } from "react";
import type React from "react";
import type {
  BootstrapPayload,
  CategoryMeta,
  CloudDraftItem,
  CommunitySearchFilters,
  CommunitySearchTagHit,
  CommunitySearchUserHit,
  ConnectMetricItem,
  ConnectTrustLevelReport,
  DesktopUpdateInfo,
  EchDesktopSnapshot,
  FlairInfo,
  FollowUserEntry,
  GrantedBadge,
  LocalDraftRecord,
  LocalHistoryItem,
  MentionUserCandidate,
  NotificationItem,
  PollVoterItem,
  PostBoostItem,
  PostItemModel,
  PostLinkCountItem,
  PostPollModel,
  PostReactionItem,
  PostRevisionDetail,
  ReactionUserGroup,
  SiteHttpResponse,
  TagChoiceOption,
  TagChoiceResult,
  TopicDetailModel,
  TopicPoster,
  TopicSummary,
  UserAccountPreferencesSnapshot,
  UserPreferences,
  UserProfile,
} from "./types";
import { parseInternalTopicLink } from "./cooked";
import { extractUsernameFromAvatarUrl } from "./palette";
import { diagLog } from "./diagnostics";

export class SiteError extends Error {
  readonly isVerification: boolean;
  readonly isAuthRequired: boolean;
  readonly status: number;
  readonly retryAfterMs: number;

  constructor(
    message: string,
    opts: {
      isVerification?: boolean;
      isAuthRequired?: boolean;
      status?: number;
      retryAfterMs?: number;
    } = {}
  ) {
    super(message);
    this.name = "SiteError";
    this.isVerification = Boolean(opts.isVerification);
    this.isAuthRequired = Boolean(opts.isAuthRequired);
    this.status = opts.status ?? 0;
    this.retryAfterMs = opts.retryAfterMs ?? 0;
  }
}

let globalRateLimitUntilMs = 0;
let forceNextRetryUntilMs = 0;
const inflightGetRequests = new Map<string, Promise<SiteHttpResponse>>();

/**
 * Arms a 2s window where manual user retry buttons bypass local short-circuiting
 * and invalidate short SWR caches so an explicit click on 「重试 / 刷新」 performs a live probe.
 */
export function markManualRetryIntent(): void {
  forceNextRetryUntilMs = Date.now() + 2000;
  shortFeedCache.clear();
  shortNotifCache = null;
}

/**
 * React hook for automatic rate-limit countdown & zero-touch self-recovery:
 * Whenever a view displays a 429 rate-limit message, counts down remaining cooldown seconds
 * and automatically triggers `onRecover()` as soon as the cooldown window completes.
 */
export function useRateLimitAutoRecovery(
  errorMessage: string | null | undefined,
  onRecover: () => void
): number {
  const isRateLimited = Boolean(errorMessage && errorMessage.includes("频繁"));
  const onRecoverRef = useRef(onRecover);
  onRecoverRef.current = onRecover;
  const [remainSecs, setRemainSecs] = useState(0);

  useEffect(() => {
    if (!isRateLimited) {
      setRemainSecs(0);
      return;
    }
    const deadline =
      globalRateLimitUntilMs > Date.now() + 800
        ? globalRateLimitUntilMs
        : Date.now() + 4000;
    const updateTick = () => {
      const leftMs = deadline - Date.now();
      if (leftMs <= 0) {
        setRemainSecs(0);
        markManualRetryIntent();
        onRecoverRef.current();
        return true;
      }
      setRemainSecs(Math.max(1, Math.ceil(leftMs / 1000)));
      return false;
    };
    if (updateTick()) return;
    const timer = setInterval(() => {
      if (updateTick()) clearInterval(timer);
    }, 500);
    return () => clearInterval(timer);
  }, [isRateLimited, errorMessage]);

  return isRateLimited ? remainSecs : 0;
}

export async function bootstrapApp(): Promise<BootstrapPayload> {
  return invoke<BootstrapPayload>("bootstrap_state");
}

function extractDiagPath(rawUrl: string): string {
  try {
    const u = new URL(rawUrl);
    return `${u.pathname}${u.search}` || "/";
  } catch {
    return rawUrl.replace(/^https?:\/\/[^/]+/i, "") || rawUrl;
  }
}

function summarizeDiagBody(rawBody: string, maxLen = 680): string {
  const clean = (rawBody || "")
    .replace(/^<!--lindo-cf-challenge-->/i, "")
    .replace(/\r?\n+/g, " ")
    .replace(/\s+/g, " ")
    .trim();
  if (!clean) return "";
  if (clean.startsWith("<")) {
    const titleMatch = clean.match(/<title[^>]*>([^<]{1,120})<\/title>/i);
    const title = titleMatch?.[1]?.trim();
    return title ? `<HTML title="${title}"> (${clean.length}B)` : `<HTML> ${clean.slice(0, 220)}`;
  }
  return clean.length > maxLen ? `${clean.slice(0, maxLen)}…` : clean;
}

function formatHttpDiagDetail(
  url: string,
  method: string,
  res: SiteHttpResponse,
  ms: number,
  bgKey: string,
  includeBody = true
): string {
  const d = res.diag;
  const lines: string[] = [
    `url: ${url}`,
    `meta: method=${method} status=${res.status} duration=${ms}ms lane=${bgKey} bodyBytes=${d?.bodyBytes ?? (res.body ? res.body.length : 0)}`,
  ];
  const hdrParts: string[] = [];
  if (d?.cfMitigated) hdrParts.push(`cf-mitigated=${d.cfMitigated}`);
  if (d?.cfRay) hdrParts.push(`cf-ray=${d.cfRay}`);
  if (d?.retryAfter) hdrParts.push(`retry-after=${d.retryAfter}`);
  if (d?.contentType) hdrParts.push(`content-type=${d.contentType}`);
  if (d?.requestId) hdrParts.push(`x-request-id=${d.requestId}`);
  if (hdrParts.length > 0) {
    lines.push(`headers: ${hdrParts.join(" | ")}`);
  }
  const bridgeParts: string[] = [];
  if (d?.localCooldown) bridgeParts.push("localCooldown=true");
  if (d?.isChallenge429) bridgeParts.push("challenge429=true");
  if (res.rateLimitedMs) bridgeParts.push(`cooldownSet=${res.rateLimitedMs}ms`);
  if (d?.cooldownWaitMs) bridgeParts.push(`cooldownWait=${d.cooldownWaitMs}ms`);
  if (d?.paceWaitMs) bridgeParts.push(`paceWait=${d.paceWaitMs}ms`);
  if (d?.retried) bridgeParts.push("retried=true");
  if (d?.silentCfRevalidated) bridgeParts.push("cfRevalidated=true");
  if (d?.verifyAttempted) bridgeParts.push("verifyAttempted=true");
  if (d?.verifyRound) bridgeParts.push(`verifyRound=${d.verifyRound}`);
  if (d?.verifyCooldownMs) bridgeParts.push(`verifyCooldown=${d.verifyCooldownMs}ms`);
  if (d?.ineffectiveClearance) bridgeParts.push("ineffectiveClearance=true");
  if (d?.csrfRefreshed) bridgeParts.push("csrfRefreshed=true");
  if (d?.guestFallback) bridgeParts.push("guestFallback=true");
  if (bridgeParts.length > 0) {
    lines.push(`bridge: ${bridgeParts.join(" | ")}`);
  }
  if (includeBody && res.body) {
    const snippet = summarizeDiagBody(res.body);
    if (snippet) {
      lines.push(`body: ${snippet}`);
    }
  }
  return lines.join("\n");
}

export async function siteRequest(
  url: string,
  method: "GET" | "POST" | "PUT" | "DELETE" = "GET",
  form?: Record<string, string>,
  headers?: Record<string, string>,
  opts?: { silentDiag?: boolean }
): Promise<SiteHttpResponse> {
  const isGet = method === "GET";
  const isWrite = method === "POST" || method === "PUT" || method === "DELETE";
  const isForceRetry = headers?.["X-LinDo-Force-Retry"] === "1";
  const bgKey = headers?.["Discourse-Background"] === "true" ? "bg" : "fg";
  const dedupeKey = isGet && !isForceRetry ? `${bgKey}:${url}` : null;
  const diagPath = extractDiagPath(url);

  if (dedupeKey) {
    const existing = inflightGetRequests.get(dedupeKey);
    if (existing) return existing;
  }

  const run = (async (): Promise<SiteHttpResponse> => {
    const t0 = performance.now();
    const silent = Boolean(opts?.silentDiag);
    try {
      const res = await invoke<SiteHttpResponse>("site_request", { url, method, form, headers });
      const ms = Math.round(performance.now() - t0);
      if (res.status >= 200 && res.status < 300 && !res.isChallenge) {
        globalRateLimitUntilMs = 0;
      } else if (res.status === 429 && !res.isChallenge) {
        const isActionOnly429 =
          isWrite &&
          !res.diag?.isChallenge429 &&
          !res.diag?.localCooldown &&
          !(res.body || "").trimStart().startsWith("<");
        if (!isActionOnly429) {
          const waitMs = Math.max(1500, Number(res.rateLimitedMs || 3500));
          globalRateLimitUntilMs = Math.max(globalRateLimitUntilMs, Date.now() + waitMs);
        }
      }

      if (res.isChallenge) {
        const isLocalGuardShortCircuit =
          Boolean(res.diag?.localCooldown) ||
          (typeof res.body === "string" &&
            (res.body.startsWith("<!--lindo-cf-challenge-->verifying") ||
              res.body.startsWith("<!--lindo-cf-challenge-->cooldown")));
        if (!silent && !isLocalGuardShortCircuit) {
          diagLog(
            "WARN",
            "NETWORK",
            "Cloudflare",
            `${method} ${url} -> HTTP ${res.status} (站点验证挑战, ${ms}ms)`,
            formatHttpDiagDetail(url, method, res, ms, bgKey, true),
            { method, path: diagPath, status: res.status, durationMs: ms, note: "需站点验证挑战" }
          );
        }
      } else if (res.isUnauthenticated) {
        if (!silent) {
          diagLog(
            "WARN",
            "AUTH",
            "Session",
            `${method} ${url} -> HTTP ${res.status} (未授权或登录态失效, ${ms}ms)`,
            formatHttpDiagDetail(url, method, res, ms, bgKey, true),
            { method, path: diagPath, status: res.status, durationMs: ms, note: "未授权或登录态失效" }
          );
        }
      } else if (res.status === 429) {
        const isLocalCooldown =
          Boolean(res.diag?.localCooldown) ||
          (typeof res.body === "string" && res.body.includes('"localCooldown":true'));
        const isActionOnly429 =
          isWrite &&
          !res.diag?.isChallenge429 &&
          !isLocalCooldown &&
          !(res.body || "").trimStart().startsWith("<");
        const sec = Math.ceil(Number(res.rateLimitedMs || 3000) / 1000);
        if (!silent && (!isLocalCooldown || (isWrite && bgKey === "fg"))) {
          const sourceNote = isLocalCooldown
            ? `本地冷却拦截 · 剩余 ${sec}s`
            : res.diag?.isChallenge429
              ? `WAF 限流冷却 ${sec}s`
              : isActionOnly429
                ? `服务端操作频率限制 · 建议等待 ${sec}s`
                : `限流冷却 ${sec}s${res.diag?.retryAfter ? ` · Retry-After ${res.diag.retryAfter}s` : ""}`;
          diagLog(
            "WARN",
            "NETWORK",
            isLocalCooldown ? "LocalCooldown" : isActionOnly429 ? "ActionRateLimit" : "RateLimit",
            `${method} ${url} -> HTTP 429 (${sourceNote}, ${ms}ms)`,
            formatHttpDiagDetail(url, method, res, ms, bgKey, true),
            { method, path: diagPath, status: 429, durationMs: ms, note: sourceNote }
          );
        }
      } else if ((res.status < 200 || res.status >= 300) && !silent) {
        const errHint = extractDiscourseError(safeJson(res.body));
        diagLog(
          res.status >= 500 ? "ERROR" : "WARN",
          "NETWORK",
          "HttpStatus",
          `${method} ${url} -> HTTP ${res.status} (${ms}ms)`,
          formatHttpDiagDetail(url, method, res, ms, bgKey, true),
          {
            method,
            path: diagPath,
            status: res.status,
            durationMs: ms,
            note: errHint || undefined,
          }
        );
      } else if (!silent) {
        const isTimings = url.includes("/topics/timings");
        if (isWrite && !isTimings) {
          const flags: string[] = [];
          if (res.diag?.csrfRefreshed) flags.push("已刷新 CSRF");
          if (res.diag?.silentCfRevalidated) flags.push("静默验证恢复");
          if (res.diag?.cooldownWaitMs) flags.push(`等待冷却 ${res.diag.cooldownWaitMs}ms`);
          diagLog(
            "INFO",
            "NETWORK",
            "Mutation",
            `${method} ${url} -> HTTP ${res.status} (${ms}ms)`,
            formatHttpDiagDetail(url, method, res, ms, bgKey, false),
            {
              method,
              path: diagPath,
              status: res.status,
              durationMs: ms,
              note: flags.length > 0 ? flags.join(" · ") : undefined,
            }
          );
        } else if (ms >= 3000 && !isTimings) {
          const waitNotes: string[] = [];
          if (res.diag?.cooldownWaitMs) waitNotes.push(`冷却等待 ${res.diag.cooldownWaitMs}ms`);
          if (res.diag?.paceWaitMs) waitNotes.push(`节流排队 ${res.diag.paceWaitMs}ms`);
          if (res.diag?.silentCfRevalidated) waitNotes.push("静默验证恢复");
          if (res.diag?.retried) waitNotes.push("已自动重试");
          diagLog(
            "WARN",
            "NETWORK",
            "SlowRequest",
            `${method} ${url} -> HTTP ${res.status} (慢请求 ${ms}ms)`,
            formatHttpDiagDetail(url, method, res, ms, bgKey, false),
            {
              method,
              path: diagPath,
              status: res.status,
              durationMs: ms,
              note: waitNotes.length > 0 ? `慢响应 · ${waitNotes.join(" · ")}` : "响应较慢",
            }
          );
        }
      }
      return res;
    } catch (err: any) {
      const ms = Math.round(performance.now() - t0);
      if (!silent) {
        const errMsg = String(err?.message || err || "未知异常");
        diagLog(
          "ERROR",
          "NETWORK",
          "BridgeIPC",
          `${method} ${url} 调用异常 (${ms}ms)`,
          `url: ${url}\nmeta: method=${method} lane=${bgKey} duration=${ms}ms\nerror: ${errMsg}`,
          { method, path: diagPath, durationMs: ms, note: errMsg }
        );
      }
      throw err;
    }
  })();

  if (dedupeKey) {
    inflightGetRequests.set(dedupeKey, run);
    run.finally(() => {
      if (inflightGetRequests.get(dedupeKey) === run) {
        inflightGetRequests.delete(dedupeKey);
      }
    });
  }
  return run;
}

let currentApiDarkTheme = true;
let inflightVerifyReplayPromise: Promise<boolean> | null = null;

export function setApiDarkTheme(isDark: boolean): void {
  currentApiDarkTheme = isDark;
}

export function triggerInteractiveVerifyOnce(manual = true): Promise<boolean> {
  if (inflightVerifyReplayPromise) return inflightVerifyReplayPromise;
  inflightVerifyReplayPromise = siteVerify(false, currentApiDarkTheme, manual).finally(() => {
    inflightVerifyReplayPromise = null;
  });
  return inflightVerifyReplayPromise;
}

export async function siteVerify(
  login: boolean,
  isDark: boolean,
  manual = true
): Promise<boolean> {
  diagLog(
    "INFO",
    "AUTH",
    "AuthPending",
    login ? "已打开内置登录窗口，等待完成登录" : "已打开站点安全验证窗口，等待完成验证"
  );
  const ok = await invoke<boolean>("site_verify", { login, isDark, manual });
  if (ok) {
    globalRateLimitUntilMs = 0;
    shortFeedCache.clear();
    shortNotifCache = null;
  }
  diagLog(
    ok ? "INFO" : "WARN",
    "AUTH",
    ok ? "AuthSuccess" : "WebVerify",
    ok ? (login ? "内置窗口登录已完成" : "站点安全验证已通过") : "登录/验证窗口已关闭（未完成）"
  );
  return ok;
}

function buildChallengeSiteError(res: SiteHttpResponse, defaultMsg: string): SiteError {
  if (res.diag?.ineffectiveClearance) {
    const sec = Math.max(1, Math.ceil(Number(res.diag.verifyCooldownMs || 60000) / 1000));
    return new SiteError(
      `安全确认已通过但请求仍被站点拦截（冷却 ${sec} 秒），请检查网络代理后点击「安全验证」手动重试`,
      { isVerification: true, status: res.status }
    );
  }
  if ((res.diag?.verifyCooldownMs ?? 0) > 0) {
    const sec = Math.max(1, Math.ceil(Number(res.diag?.verifyCooldownMs || 30000) / 1000));
    return new SiteError(
      `安全确认自动重试冷却中（剩余 ${sec} 秒），可点击「安全验证」手动完成`,
      { isVerification: true, status: res.status }
    );
  }
  return new SiteError(defaultMsg, { isVerification: true, status: res.status });
}

export async function startBrowserOneClickAuth(): Promise<string> {
  diagLog(
    "INFO",
    "AUTH",
    "AuthPending",
    "已打开默认浏览器授权页，等待点击「授权」返回应用"
  );
  return invoke<string>("start_browser_auth");
}

export async function updateCurrentUserInRust(profile: UserProfile): Promise<void> {
  return invoke("update_current_user", { profile });
}

export async function clearExpiredNoticeInRust(): Promise<void> {
  return invoke("clear_expired_notice");
}

export async function switchSavedAccount(username: string): Promise<boolean> {
  clearTopicDetailCache();
  resetNotificationReadState();
  diagLog("INFO", "AUTH", "AccountSwitch", `切换至已保存账号 @${username}`);
  const ok = await invoke<boolean>("switch_account", { username });
  if (!ok) {
    diagLog("WARN", "AUTH", "AccountSwitch", `切换账号 @${username} 未成功`);
  }
  return ok;
}

export async function logoutAccount(removeSaved = true): Promise<void> {
  clearTopicDetailCache();
  resetNotificationReadState();
  diagLog("INFO", "AUTH", "Logout", removeSaved ? "退出登录并移除本地凭据" : "退出当前会话（保留已保存账号）");
  return invoke("logout_account", { removeSaved });
}

export async function removeSavedAccountInRust(username: string): Promise<void> {
  return invoke("remove_saved_account", { username });
}

export async function addAccountWebLoginInRust(isDark: boolean): Promise<boolean> {
  clearTopicDetailCache();
  resetNotificationReadState();
  return invoke<boolean>("add_account_web_login", { isDark });
}

export async function importManualCredentialInRust(rawInput: string, isDark: boolean): Promise<boolean> {
  clearTopicDetailCache();
  resetNotificationReadState();
  return invoke<boolean>("import_manual_credential", { rawInput, isDark });
}

export async function openInAppForumPanel(url: string, isDark: boolean): Promise<void> {
  return invoke("open_in_app_forum_panel", { url, isDark });
}

export async function savePreferencesToRust(preferences: UserPreferences): Promise<void> {
  return invoke("save_preferences", { preferences });
}

export async function getEchStatus(): Promise<EchDesktopSnapshot> {
  return invoke<EchDesktopSnapshot>("get_ech_status");
}

export async function recordReadFloorInRust(topicId: number, floor: number): Promise<void> {
  return invoke("record_read_floor", { topicId, floor });
}

export async function clearReadHistoryInRust(): Promise<void> {
  return invoke("clear_read_history");
}

export async function saveLocalDraftInRust(draft: LocalDraftRecord): Promise<void> {
  return invoke("save_local_draft", { draft });
}

export async function deleteLocalDraftInRust(key: string): Promise<void> {
  return invoke("delete_local_draft", { key });
}

export async function setUnreadBadgeInRust(count: number): Promise<void> {
  return invoke("set_unread_badge", { count });
}

export interface ClipboardMediaItem {
  filename: string;
  mime: string;
  dataBase64: string;
}

export interface ClipboardMediaResult {
  source: "none" | "file-url" | "image-data" | string;
  items: ClipboardMediaItem[];
}

export async function readClipboardMediaInRust(
  onlyFileUrls = false
): Promise<ClipboardMediaResult> {
  try {
    return await invoke<ClipboardMediaResult>("read_clipboard_media", { onlyFileUrls });
  } catch {
    return { source: "none", items: [] };
  }
}

export async function readDroppedFilesInRust(paths: string[]): Promise<ClipboardMediaItem[]> {
  if (!paths.length) return [];
  try {
    return await invoke<ClipboardMediaItem[]>("read_dropped_files", { paths });
  } catch {
    return [];
  }
}

export async function uploadMediaToSite(
  filename: string,
  mime: string,
  dataBase64: string
): Promise<{ shortUrl: string; url: string; width: number; height: number; originalFilename: string }> {
  const t0 = performance.now();
  const approxBytes = Math.floor((dataBase64.length * 3) / 4);
  let res = await invoke<SiteHttpResponse>("site_upload", { filename, mime, dataBase64 });
  if (res.isChallenge && !res.diag?.verifyAttempted && !(res.diag?.verifyCooldownMs ?? 0)) {
    const passed = await triggerInteractiveVerifyOnce(false);
    if (passed) {
      res = await invoke<SiteHttpResponse>("site_upload", { filename, mime, dataBase64 });
    }
  }
  const durationMs = Math.round(performance.now() - t0);
  if (res.isChallenge) {
    diagLog(
      "WARN",
      "NETWORK",
      "Upload",
      `POST /uploads.json 需要安全确认 (${filename}, ${durationMs}ms)`,
      `filename: ${filename}\nmime: ${mime}\nsizeBytes: ${approxBytes}\nstatus: ${res.status}`,
      { method: "POST", path: "/uploads.json", status: res.status, durationMs, note: "需要安全确认" }
    );
    throw buildChallengeSiteError(res, "需要完成安全确认");
  }
  if (res.isUnauthenticated) {
    diagLog(
      "WARN",
      "AUTH",
      "Upload",
      `POST /uploads.json 未登录 (${filename}, ${durationMs}ms)`,
      undefined,
      { method: "POST", path: "/uploads.json", status: res.status, durationMs }
    );
    throw new SiteError("请先登录账号", { isAuthRequired: true, status: res.status });
  }
  const parsed = safeJson(res.body);
  if (res.status < 200 || res.status >= 300 || !parsed) {
    const errMsg = extractDiscourseError(parsed) || `文件上传失败 (${res.status})`;
    diagLog(
      "WARN",
      "NETWORK",
      "Upload",
      `POST /uploads.json -> HTTP ${res.status} (${filename}, ${durationMs}ms)`,
      `filename: ${filename}\nmime: ${mime}\nsizeBytes: ${approxBytes}\nstatus: ${res.status}\nerror: ${errMsg}`,
      { method: "POST", path: "/uploads.json", status: res.status, durationMs, note: errMsg }
    );
    throw new SiteError(errMsg, { status: res.status });
  }
  diagLog(
    "INFO",
    "NETWORK",
    "Upload",
    `POST /uploads.json -> HTTP ${res.status} (${filename}, ${Math.max(1, Math.round(approxBytes / 1024))}KB, ${durationMs}ms)`,
    undefined,
    { method: "POST", path: "/uploads.json", status: res.status, durationMs, note: `${Math.max(1, Math.round(approxBytes / 1024))}KB` }
  );
  return {
    shortUrl: String(parsed.short_url || parsed.url || ""),
    url: String(parsed.url || parsed.short_url || ""),
    width: Number(parsed.width || 0),
    height: Number(parsed.height || 0),
    originalFilename: String(parsed.original_filename || filename),
  };
}

function safeJson(raw: string): any {
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

function extractDiscourseError(obj: any): string | null {
  if (!obj || typeof obj !== "object") return null;
  if (Array.isArray(obj.errors) && obj.errors.length > 0) {
    return String(obj.errors[0]);
  }
  if (typeof obj.error === "string" && obj.error) {
    return obj.error;
  }
  return null;
}

async function getJsonOrThrow(
  url: string,
  background = false,
  extraHeaders?: Record<string, string>
): Promise<any> {
  const headers: Record<string, string> = { ...(extraHeaders ?? {}) };
  if (background) {
    headers["Discourse-Background"] = "true";
  } else if (Date.now() < forceNextRetryUntilMs) {
    headers["X-LinDo-Force-Retry"] = "1";
    forceNextRetryUntilMs = 0;
  }
  const res = await siteRequest(url, "GET", undefined, Object.keys(headers).length ? headers : undefined);
  if (res.isChallenge) {
    throw buildChallengeSiteError(res, "需要完成站点验证");
  }
  if (res.isUnauthenticated) {
    if (background) {
      throw new SiteError("", { isAuthRequired: true, status: res.status });
    }
    throw new SiteError("登录状态已过期，请重新登录", { isAuthRequired: true, status: res.status });
  }
  if (res.status === 429) {
    const waitMs = Math.max(1500, Number(res.rateLimitedMs || 3500));
    throw new SiteError("请求较频繁，请稍后再试", { status: 429, retryAfterMs: waitMs });
  }
  const data = safeJson(res.body);
  if (res.status < 200 || res.status >= 300 || !data) {
    const rawErr = extractDiscourseError(data);
    const localizedErr =
      res.status === 404 &&
      (!rawErr || /could not be found|not_found/i.test(rawErr) || data?.error_type === "not_found")
        ? "内容不存在，或当前账号暂未获得访问权限"
        : rawErr;
    throw new SiteError(localizedErr || `加载失败 (${res.status})`, { status: res.status });
  }
  return data;
}

async function mutateJsonOrThrow(
  url: string,
  method: "POST" | "PUT" | "DELETE",
  form?: Record<string, string>,
  extraHeaders?: Record<string, string>
): Promise<any> {
  const isBackground = extraHeaders?.["Discourse-Background"] === "true";
  let res = await siteRequest(url, method, form, extraHeaders);
  if (
    !isBackground &&
    res.isChallenge &&
    !res.diag?.verifyAttempted &&
    !(res.diag?.verifyCooldownMs ?? 0)
  ) {
    const passed = await triggerInteractiveVerifyOnce(false);
    if (passed) {
      res = await siteRequest(url, method, form, extraHeaders);
    }
  }
  if (res.isChallenge) {
    throw buildChallengeSiteError(res, "需要完成安全确认");
  }
  if (res.isUnauthenticated) {
    throw new SiteError("请先登录账号后再执行此操作", { isAuthRequired: true, status: res.status });
  }
  const data = safeJson(res.body);
  if (res.status === 429) {
    const waitMs = Math.max(1500, Number(res.rateLimitedMs || 3500));
    const waitSec = Math.ceil(waitMs / 1000);
    const serverErr = extractDiscourseError(data);
    const isGeneric =
      !serverErr ||
      serverErr === "请求较频繁，请稍后再试" ||
      serverErr === "操作较为频繁，请稍候片刻再试";
    throw new SiteError(
      isGeneric ? `请求较频繁，请 ${waitSec} 秒后再试` : serverErr,
      { status: 429, retryAfterMs: waitMs }
    );
  }
  if (res.status < 200 || res.status >= 300) {
    throw new SiteError(extractDiscourseError(data) || `操作未成功 (${res.status})`, { status: res.status });
  }
  clearTopicDetailCache();
  return data ?? {};
}

/* ==================== 1. 官方中文版块目录与权限同步引擎 (1:1 对齐 LinDoNetworkStack.kt) ==================== */

const CANONICAL_TOP_CATEGORIES: CategoryMeta[] = [
  { id: 4, name: "开发调优", displayName: "开发调优", slug: "develop", colorHex: "32C3C3", description: "软件开发与开源实践", readRestricted: false, icon: "code", canCreateTopic: true },
  { id: 14, name: "资源荟萃", displayName: "资源荟萃", slug: "resource", colorHex: "12A89D", description: "软件工具与数字资源", readRestricted: false, icon: "square-share-nodes", canCreateTopic: true },
  { id: 42, name: "文档共建", displayName: "文档共建", slug: "wiki", colorHex: "9CB6C4", description: "技术沉淀与教程指南", readRestricted: false, icon: "book", canCreateTopic: true },
  { id: 11, name: "搞七捻三", displayName: "搞七捻三", slug: "gossip", colorHex: "3AB54A", description: "日常交流与职场故事", readRestricted: false, icon: "droplet", canCreateTopic: true },
  { id: 36, name: "福利羊毛", displayName: "福利羊毛", slug: "welfare", colorHex: "E45735", description: "社区福利与公益接口", readRestricted: false, icon: "piggy-bank", canCreateTopic: true },
  { id: 34, name: "前沿快讯", displayName: "前沿快讯", slug: "news", colorHex: "BB8FCE", description: "AI 动态与前沿科技", readRestricted: false, icon: "newspaper", canCreateTopic: true },
  { id: 27, name: "非我莫属", displayName: "非我莫属", slug: "job", colorHex: "A8C6FE", description: "人才对接与远程协作", readRestricted: false, icon: "briefcase", canCreateTopic: true },
  { id: 10, name: "跳蚤市场", displayName: "跳蚤市场", slug: "market", colorHex: "ED207B", description: "闲置数码与算力流转", readRestricted: true, icon: "store", canCreateTopic: false },
  { id: 32, name: "读书成诗", displayName: "读书成诗", slug: "reading", colorHex: "E0D900", description: "书评笔记与阅读推荐", readRestricted: false, icon: "book-open-reader", canCreateTopic: true },
  { id: 46, name: "扬帆起航", displayName: "扬帆起航", slug: "startup", colorHex: "F7941D", description: "独立开发与产品出海", readRestricted: true, icon: "rocket", canCreateTopic: false },
  { id: 2, name: "运营反馈", displayName: "运营反馈", slug: "feedback", colorHex: "808281", description: "社区站务与功能建议", readRestricted: false, icon: "comments", canCreateTopic: true },
  { id: 98, name: "国产替代", displayName: "国产替代", slug: "domestic", colorHex: "D12C25", description: "软硬件国产化与信创", readRestricted: false, icon: "seedling", canCreateTopic: true },
  { id: 95, name: "积分乐园", displayName: "积分乐园", slug: "playground", colorHex: "E0D900", description: "社区活动与积分互动", readRestricted: true, icon: "coins", canCreateTopic: false },
  { id: 92, name: "网络记忆", displayName: "网络记忆", slug: "feeds", colorHex: "F7941D", description: "互联网记忆与归档", readRestricted: false, icon: "rss", canCreateTopic: true },
  { id: 102, name: "社区孵化", displayName: "社区孵化", slug: "incubation", colorHex: "F7941D", description: "社区共创与项目孵化", readRestricted: true, icon: "lightbulb", canCreateTopic: false },
  { id: 110, name: "虫洞广场", displayName: "虫洞广场", slug: "square", colorHex: "FF00F7", description: "跨界视野与奇思妙想", readRestricted: false, icon: "hurricane", canCreateTopic: true },
  { id: 108, name: "深海幽域", displayName: "深海幽域", slug: "abyss", colorHex: "12A89D", description: "深度讨论与高阶专区", readRestricted: true, icon: "water", canCreateTopic: false },
];

const CANONICAL_DEFAULT_SUBS: CategoryMeta[] = [
  {
    id: 94,
    name: "网盘资源",
    displayName: "资源荟萃 · 网盘资源",
    slug: "cloud-asset",
    colorHex: "16B176",
    description: "网盘资源分享专区",
    readRestricted: false,
    parentId: 14,
    parentSlug: "resource",
    hasServerParent: true,
    subLabel: "网盘",
    trustLevel: null,
    icon: "hard-drive",
    canCreateTopic: true,
  },
];

// Official read-only announcement/admin categories where regular topics cannot be created unless server explicitly grants permission === 1
const READ_ONLY_ADMIN_CATEGORY_IDS = new Set([49, 30, 124]);

const CANONICAL_NAME_BY_ID = new Map<number, string>([
  ...CANONICAL_TOP_CATEGORIES.map((c) => [c.id, c.name] as const),
  [94, "网盘资源"],
  [49, "公告"],
  [123, "悬赏"],
  [30, "活动"],
  [124, "模板"],
]);

let categoryCatalog: CategoryMeta[] = [...CANONICAL_TOP_CATEGORIES, ...CANONICAL_DEFAULT_SUBS];
let siteCanCreateTag = false;
const userAvatarMemoryCache = new Map<string, string>();
const followFeedCursors = new Map<string, string>();

export function getCachedCategories(): CategoryMeta[] {
  return categoryCatalog;
}

export function findCategoryMeta(id?: number | null): CategoryMeta | undefined {
  if (!id) return undefined;
  return categoryCatalog.find((c) => c.id === id);
}

function hasNonAscii(s: string): boolean {
  for (let i = 0; i < s.length; i++) {
    if (s.charCodeAt(i) > 127) return true;
  }
  return false;
}

function mergeCategoriesFromRawArray(rawCategories: any[], hasFullSitePermissions: boolean): CategoryMeta[] {
  if (!Array.isArray(rawCategories) || rawCategories.length === 0) {
    return categoryCatalog;
  }

  const topById = new Map<number, CategoryMeta>();
  for (const def of CANONICAL_TOP_CATEGORIES) {
    topById.set(def.id, { ...def });
  }

  const subById = new Map<number, CategoryMeta>();
  for (const subDef of CANONICAL_DEFAULT_SUBS) {
    subById.set(subDef.id, { ...subDef });
  }

  const rawById = new Map<number, any>();
  for (const item of rawCategories) {
    if (!item || typeof item !== "object") continue;
    const id = Number(item.id || 0);
    if (id <= 0) continue;
    rawById.set(id, item);
    if (Array.isArray(item.subcategory_list)) {
      for (const sub of item.subcategory_list) {
        const sid = Number(sub?.id || 0);
        if (sid > 0 && !rawById.has(sid)) {
          rawById.set(sid, { ...sub, parent_category_id: sub.parent_category_id ?? id });
        }
      }
    }
  }

  interface PendingSub {
    id: number;
    cObj: any;
    preferredName: string;
    slug: string;
    rawParentId: number | null;
    detectedLv: number | null;
    basePrefix: string;
  }

  const pendingSubs: PendingSub[] = [];

  // Pass 1: Top-level categories (prefer canonical Chinese name over ASCII slugs like "Develop")
  for (const [id, cObj] of rawById.entries()) {
    const rawName = String(cObj.name || "").trim();
    if (!rawName) continue;
    const slug = String(cObj.slug || `cat-${id}`).trim();
    const rawParentId = cObj.parent_category_id ? Number(cObj.parent_category_id) : null;
    const existingTop = topById.get(id);
    const canonicalName = CANONICAL_NAME_BY_ID.get(id);

    let preferredName = rawName;
    if (existingTop && hasNonAscii(existingTop.name) && !hasNonAscii(rawName)) {
      preferredName = existingTop.name;
    } else if (canonicalName && hasNonAscii(canonicalName) && !hasNonAscii(rawName)) {
      preferredName = canonicalName;
    }

    const lvMatch = preferredName.match(/Lv\s*([1-4])/i);
    const detectedLv = lvMatch ? Number(lvMatch[1]) : null;
    const basePrefix = preferredName.split(",")[0].trim();

    const isSubCandidate =
      rawParentId !== null ||
      preferredName.startsWith("网盘资源") ||
      (detectedLv !== null && basePrefix !== preferredName);

    if (isSubCandidate) {
      pendingSubs.push({
        id,
        cObj,
        preferredName,
        slug,
        rawParentId,
        detectedLv,
        basePrefix,
      });
      continue;
    }

    const colorHex = String(cObj.color || existingTop?.colorHex || "32C3C3").replace("#", "").trim();
    const readRestricted =
      cObj.read_restricted != null ? Boolean(cObj.read_restricted) : Boolean(existingTop?.readRestricted);
    const desc = stripHtmlTags(String(cObj.description_text || cObj.description_excerpt || existingTop?.description || preferredName)).slice(0, 42);
    const canCreateTopic =
      cObj.permission != null
        ? Number(cObj.permission) === 1
        : hasFullSitePermissions
          ? false
          : !READ_ONLY_ADMIN_CATEGORY_IDS.has(id);
    const topicTemplate = cObj.topic_template ? String(cObj.topic_template).trim() : existingTop?.topicTemplate;

    // Remove any canonical fallback entry that shares the same Chinese display name under an older hardcoded ID
    // (prevents duplicate pills like `积分乐园` / `深海幽域` when server category IDs differ from canonical defaults).
    for (const [existingId, existingMeta] of Array.from(topById.entries())) {
      if (existingId !== id && existingMeta.name === preferredName) {
        topById.delete(existingId);
      }
    }

    topById.set(id, {
      id,
      name: preferredName,
      displayName: preferredName,
      slug,
      colorHex,
      description: desc,
      readRestricted,
      parentId: null,
      icon: cObj.icon ? String(cObj.icon) : existingTop?.icon,
      canCreateTopic,
      topicTemplate,
      topicCount: Number(cObj.topic_count || 0),
    });
  }

  // Pass 2: Lv1–Lv3 & nested subcategories collapsed cleanly under root top-level parent
  for (const sub of pendingSubs) {
    const { id, cObj } = sub;
    let rootParentId: number | null =
      sub.rawParentId !== null
        ? sub.rawParentId
        : sub.preferredName.startsWith("网盘资源")
          ? 14
          : sub.detectedLv !== null
            ? Array.from(topById.values()).find((t) => t.name === sub.basePrefix)?.id ?? null
            : null;

    let guard = 0;
    while (rootParentId !== null && !topById.has(rootParentId) && guard++ < 5) {
      const pRaw = rawById.get(rootParentId);
      const upper = pRaw?.parent_category_id ? Number(pRaw.parent_category_id) : null;
      if (upper && upper !== rootParentId) {
        rootParentId = upper;
      } else if (sub.preferredName.startsWith("网盘资源")) {
        rootParentId = 14;
        break;
      } else {
        break;
      }
    }

    const canCreateTopic =
      cObj.permission != null
        ? Number(cObj.permission) === 1
        : !READ_ONLY_ADMIN_CATEGORY_IDS.has(id);
    const topicTemplate = cObj.topic_template ? String(cObj.topic_template).trim() : undefined;

    if (!rootParentId || rootParentId === id) {
      const existingTop = topById.get(id);
      const colorHex = String(cObj.color || existingTop?.colorHex || "32C3C3").replace("#", "").trim();
      topById.set(id, {
        id,
        name: sub.preferredName,
        displayName: sub.preferredName,
        slug: sub.slug,
        colorHex,
        description: stripHtmlTags(String(cObj.description_text || cObj.description_excerpt || sub.preferredName)).slice(0, 42),
        readRestricted: cObj.read_restricted != null ? Boolean(cObj.read_restricted) : Boolean(sub.detectedLv),
        parentId: null,
        canCreateTopic,
        topicTemplate,
      });
      continue;
    }

    const parentMeta = topById.get(rootParentId);
    const directParentObj = sub.rawParentId ? rawById.get(sub.rawParentId) : null;
    const rawColor = cObj.color ? String(cObj.color).replace("#", "").trim() : "";
    const parentColor = directParentObj?.color
      ? String(directParentObj.color).replace("#", "").trim()
      : parentMeta?.colorHex;
    const colorHex =
      rawColor && !(parentColor && rawColor.toUpperCase() === "0088CC")
        ? rawColor
        : parentColor || "32C3C3";

    const computedSubLabel =
      sub.preferredName.startsWith("网盘资源") && sub.detectedLv !== null
        ? `网盘 Lv${sub.detectedLv}`
        : sub.preferredName.startsWith("网盘资源")
          ? "网盘"
          : sub.detectedLv !== null
            ? `Lv${sub.detectedLv}`
            : sub.preferredName.includes(",")
              ? sub.preferredName.split(",").slice(1).join(",").trim()
              : sub.preferredName;

    const directParentIsTopLevel =
      sub.rawParentId !== null && !rawById.get(sub.rawParentId)?.parent_category_id;
    const serverParentSlug = directParentIsTopLevel
      ? String(directParentObj?.slug || parentMeta?.slug || "").trim() || null
      : null;

    const displayName = parentMeta
      ? sub.preferredName.startsWith(parentMeta.name)
        ? sub.preferredName.replace(",", " ·")
        : `${parentMeta.name} · ${sub.preferredName}`
      : sub.preferredName;

    subById.set(id, {
      id,
      name: sub.preferredName,
      displayName,
      slug: sub.slug,
      colorHex,
      description: stripHtmlTags(String(cObj.description_text || cObj.description_excerpt || sub.preferredName)).slice(0, 42),
      readRestricted: cObj.read_restricted != null ? Boolean(cObj.read_restricted) : Boolean(sub.detectedLv),
      parentId: rootParentId,
      parentSlug: serverParentSlug,
      hasServerParent: directParentIsTopLevel,
      subLabel: computedSubLabel,
      trustLevel: sub.detectedLv,
      icon: cObj.icon ? String(cObj.icon) : parentMeta?.icon,
      canCreateTopic,
      topicTemplate: topicTemplate || parentMeta?.topicTemplate,
      topicCount: Number(cObj.topic_count || 0),
    });
  }

  const orderedSubs = Array.from(subById.values()).sort((a, b) => {
    const lvA = a.trustLevel ?? 99;
    const lvB = b.trustLevel ?? 99;
    if (lvA !== lvB) return lvA - lvB;
    return a.name.localeCompare(b.name, "zh-CN");
  });

  return [...Array.from(topById.values()), ...orderedSubs];
}

let lastCategoriesFetchedAt = 0;
let inflightCategoriesPromise: Promise<CategoryMeta[]> | null = null;
const CATEGORIES_TTL_MS = 5 * 60 * 1000;

export async function fetchSiteCategories(force = false): Promise<CategoryMeta[]> {
  if (!force && lastCategoriesFetchedAt > 0 && Date.now() - lastCategoriesFetchedAt < CATEGORIES_TTL_MS) {
    return categoryCatalog;
  }
  if (inflightCategoriesPromise) {
    return inflightCategoriesPromise;
  }

  inflightCategoriesPromise = (async () => {
    try {
      // Primary: `/site.json` contains user-accessible Lv1–Lv3 subcategories, `permission` bitmasks, `can_create_tag`, and `topic_template`!
      const siteRoot = await getJsonOrThrow("https://linux.do/site.json", !force);
      if (typeof siteRoot?.can_create_tag === "boolean") {
        siteCanCreateTag = siteRoot.can_create_tag;
      }
      if (Array.isArray(siteRoot?.categories) && siteRoot.categories.length > 0) {
        categoryCatalog = mergeCategoriesFromRawArray(siteRoot.categories, true);
        lastCategoriesFetchedAt = Date.now();
        return categoryCatalog;
      }
    } catch {
      // Fallback to public `/categories.json?include_subcategories=true` if `/site.json` fails
    }

    try {
      const data = await getJsonOrThrow("https://linux.do/categories.json?include_subcategories=true", true);
      const rawList: any[] = data?.category_list?.categories ?? [];
      if (rawList.length > 0) {
        categoryCatalog = mergeCategoriesFromRawArray(rawList, false);
        lastCategoriesFetchedAt = Date.now();
      }
    } catch {
      // Retain canonical Chinese catalog on offline/transient failure
    }
    return categoryCatalog;
  })().finally(() => {
    inflightCategoriesPromise = null;
  });

  return inflightCategoriesPromise;
}

/* ==================== 2. 当前登录用户会话与未读角标同步 ==================== */

export async function refreshCurrentUserSession(): Promise<{ profile: UserProfile | null; unread: number }> {
  const res = await siteRequest("https://linux.do/session/current.json", "GET", undefined, {
    "Discourse-Background": "true",
  });
  if (res.isChallenge || res.status >= 500 || res.status === 429) {
    throw new SiteError("Session check deferred", { isVerification: res.isChallenge, status: res.status });
  }
  if (res.status === 404 || res.isUnauthenticated) {
    return { profile: null, unread: 0 };
  }
  const data = safeJson(res.body);
  const cu = data?.current_user;
  if (!cu?.username) {
    return { profile: null, unread: 0 };
  }
  const profile: UserProfile = {
    userId: Number(cu.id || 0),
    username: String(cu.username),
    displayName: String(cu.name || cu.username),
    avatarTemplate: String(cu.avatar_template || ""),
    animatedAvatar: cu.animated_avatar ? String(cu.animated_avatar) : null,
    trustLevel: Number(cu.trust_level ?? 1),
    userTitle: cu.title ? String(cu.title) : null,
  };
  if (profile.avatarTemplate) {
    userAvatarMemoryCache.set(profile.username.toLowerCase(), profile.avatarTemplate);
  }
  ensureNotificationOwner(profile.username);
  const rawUnread = Number(cu.all_unread_notifications_count ?? cu.unread_notifications ?? 0);
  const unread =
    shortNotifCache && Date.now() - shortNotifCache.ts <= SHORT_LIST_CACHE_TTL_MS
      ? resolveAuthoritativeUnreadCount(shortNotifCache.items, rawUnread, 60)
      : rawUnread;
  await updateCurrentUserInRust(profile);
  await setUnreadBadgeInRust(unread);
  return { profile, unread };
}

/* ==================== 3. 首页话题流与关注动态流 ==================== */

function parseFlairFromObj(obj: any): FlairInfo | null {
  if (!obj || typeof obj !== "object") return null;
  const url = obj.flair_url ? String(obj.flair_url).trim() : "";
  if (!url) return null;
  return {
    url,
    name: obj.flair_name ? String(obj.flair_name) : null,
    bgColorHex: obj.flair_bg_color ? String(obj.flair_bg_color) : null,
    fgColorHex: obj.flair_color ? String(obj.flair_color) : null,
    groupId: obj.flair_group_id != null ? Number(obj.flair_group_id) : null,
  };
}

const knownBadgesCache = new Map<number, GrantedBadge>();

export function lookupCachedBadge(badgeId: number): GrantedBadge | undefined {
  return knownBadgesCache.get(badgeId);
}

export function parseGrantedBadgeObj(
  obj: any,
  fallbackId = 0,
  isFirstGrant = false
): GrantedBadge | null {
  if (!obj || typeof obj !== "object") return null;
  const nested = obj.badge && typeof obj.badge === "object" ? obj.badge : null;
  const target = nested || obj;
  const resolvedId = Number(nested?.id ?? obj.badge_id ?? target.id ?? fallbackId ?? 0);
  const known = resolvedId ? knownBadgesCache.get(resolvedId) : undefined;
  const rawName = String(target.name || obj.badge_name || known?.name || "").trim();
  if (!resolvedId && !rawName) return null;
  const badge: GrantedBadge = {
    id: resolvedId || known?.id || 0,
    name: rawName || `徽章 #${resolvedId}`,
    description: stripHtmlTags(String(target.description ?? known?.description ?? "")),
    longDescription: stripHtmlTags(
      String(target.long_description ?? known?.longDescription ?? "")
    ),
    badgeTypeId: Number(target.badge_type_id ?? known?.badgeTypeId ?? 3),
    icon: target.icon !== undefined ? (target.icon ? String(target.icon) : null) : known?.icon ?? null,
    imageUrl:
      target.image_url !== undefined || target.image !== undefined
        ? String(target.image_url || target.image || "") || null
        : known?.imageUrl ?? null,
    slug: String(target.slug || obj.badge_slug || known?.slug || ""),
    grantCount: Number(target.grant_count ?? known?.grantCount ?? 0),
    allowTitle: Boolean(target.allow_title ?? known?.allowTitle ?? false),
    badgeGroupingId: Number(target.badge_grouping_id ?? known?.badgeGroupingId ?? 0),
    isFirstTimeGrant: isFirstGrant,
    count: Number(obj.count ?? target.count ?? 1),
    grantedAt: String(obj.granted_at || target.granted_at || "").slice(0, 10) || undefined,
    isEarned: true,
  };
  if (badge.id > 0 && (target.name || obj.badge_name) && !isFirstGrant) {
    knownBadgesCache.set(badge.id, badge);
  }
  return badge;
}

function readBadgeDefinitionsAny(elem: any): GrantedBadge[] {
  if (!elem) return [];
  if (Array.isArray(elem)) {
    return elem
      .map((x) => parseGrantedBadgeObj(x))
      .filter((x): x is GrantedBadge => Boolean(x));
  }
  if (typeof elem === "object") {
    return Object.entries(elem)
      .map(([k, v]) => parseGrantedBadgeObj(v, Number(k) || 0))
      .filter((x): x is GrantedBadge => Boolean(x));
  }
  return [];
}

function readTopicAuthorBadgesMap(root: any): Map<number, GrantedBadge[]> {
  const out = new Map<number, GrantedBadge[]>();
  if (!root || typeof root !== "object") return out;
  const stream = root.post_stream;
  readBadgeDefinitionsAny(root.badges);
  readBadgeDefinitionsAny(stream?.badges);
  const payload = root.user_badges || stream?.user_badges;
  if (!payload || typeof payload !== "object") return out;
  const defs = readBadgeDefinitionsAny(payload.badges);
  if (defs.length === 0) return out;
  const byId = new Map<number, GrantedBadge>();
  for (const d of defs) byId.set(d.id, d);
  const rawUsers = Array.isArray(payload.users)
    ? payload.users
    : payload.users && typeof payload.users === "object"
      ? Object.values(payload.users)
      : [];
  for (const rec of rawUsers as any[]) {
    const uid = Number(rec?.id || 0);
    const ids: any[] = Array.isArray(rec?.badge_ids) ? rec.badge_ids : [];
    if (!uid || ids.length === 0) continue;
    const seen = new Set<number>();
    const list: GrantedBadge[] = [];
    for (const rawId of ids) {
      const bid = Number(rawId);
      const found = byId.get(bid);
      if (found && !seen.has(found.id)) {
        seen.add(found.id);
        list.push(found);
      }
    }
    const trustCandidates = list.filter((b) => b.badgeGroupingId === 4 && b.id >= 1 && b.id <= 4);
    const highestTrust =
      trustCandidates.length > 0 ? Math.max(...trustCandidates.map((b) => b.id)) : 0;
    out.set(
      uid,
      list.filter(
        (b) => b.badgeGroupingId !== 4 || b.id < 1 || b.id > 4 || b.id === highestTrust
      )
    );
  }
  return out;
}

function parsePostGrantedBadgesList(rawArr: any): GrantedBadge[] {
  if (!Array.isArray(rawArr) || rawArr.length === 0) return [];
  const seen = new Set<number>();
  const out: GrantedBadge[] = [];
  for (const item of rawArr) {
    let badge: GrantedBadge | null = null;
    if (typeof item === "number" || typeof item === "string") {
      const cached = knownBadgesCache.get(Number(item));
      if (cached) badge = { ...cached, isFirstTimeGrant: true };
    } else if (item && typeof item === "object") {
      badge = parseGrantedBadgeObj(item, 0, true);
    }
    if (badge && badge.id > 0 && !seen.has(badge.id)) {
      seen.add(badge.id);
      out.push(badge);
    }
  }
  return out;
}

function isTodayMonthDay(rawDate?: string | null, requirePreviousYear = false): boolean {
  if (!rawDate) return false;
  const m = String(rawDate).trim().match(/^(\d{4})-(\d{2})-(\d{2})/);
  if (!m) return false;
  const now = new Date();
  const yr = Number(m[1]);
  const mo = Number(m[2]);
  const dy = Number(m[3]);
  if (requirePreviousYear && yr >= now.getFullYear()) return false;
  return mo === now.getMonth() + 1 && dy === now.getDate();
}

function localizeSmallActionCode(code: string): string {
  const c = code.toLowerCase();
  if (c === "closed.enabled") return "已关闭此话题";
  if (c === "closed.disabled") return "已重新开启此话题";
  if (c === "archived.enabled") return "已归档此话题";
  if (c === "archived.disabled") return "已取消归档此话题";
  if (c === "pinned.enabled" || c === "pinned_globally.enabled") return "已置顶此话题";
  if (c === "pinned.disabled" || c === "pinned_globally.disabled") return "已取消置顶";
  if (c === "visible.enabled" || c === "public_topic") return "已设为公开话题";
  if (c === "visible.disabled") return "已设为隐藏话题";
  if (c === "split_topic") return "已拆分部分回复至新话题";
  if (c === "invited_user" || c === "invited_group") return "邀请了成员参与讨论";
  if (c === "autobumped") return "话题已自动顶起";
  return "更新了话题状态";
}

function mapTopicListEntry(t: any, usersMap: Map<number, any>): TopicSummary {
  const opEntry =
    (t.posters ?? []).find(
      (p: any) =>
        String(p.description || "").includes("Original Poster") ||
        String(p.description || "").includes("原始发帖人")
    ) ?? t.posters?.[0];
  const opUser = (opEntry && usersMap.get(Number(opEntry.user_id))) || t.user || {};
  const catId = t.category_id ? Number(t.category_id) : undefined;
  const catMeta = findCategoryMeta(catId);
  const fallbackCatName = catId ? CANONICAL_NAME_BY_ID.get(catId) : undefined;
  const tags: string[] = Array.isArray(t.tags)
    ? t.tags.map((tg: any) => (typeof tg === "string" ? tg : String(tg?.name || ""))).filter(Boolean)
    : [];

  const opUsername = String(opUser.username || t.last_poster_username || "linuxdo");
  const opTpl = String(opUser.avatar_template || "");
  if (opUsername && opTpl) {
    userAvatarMemoryCache.set(opUsername.toLowerCase(), opTpl);
  }

  return {
    id: Number(t.id),
    title: String(t.fancy_title ? stripHtmlEntities(t.title || stripHtmlTags(t.fancy_title)) : t.title || ""),
    slug: String(t.slug || "topic"),
    excerpt: t.excerpt ? stripHtmlTags(String(t.excerpt)) : undefined,
    postsCount: Number(t.posts_count || 1),
    replyCount: Math.max(0, Number(t.posts_count || 1) - 1, Number(t.reply_count || 0)),
    views: Number(t.views || 0),
    likeCount: Number(t.like_count || 0),
    highestPostNumber: Number(t.highest_post_number || t.posts_count || 1),
    lastReadPostNumber: t.last_read_post_number != null ? Number(t.last_read_post_number) : undefined,
    unreadPosts: Number(t.unread_posts || t.unread || 0),
    unseen: Boolean(t.unseen),
    pinned: Boolean(t.pinned || t.pinned_globally),
    closed: Boolean(t.closed),
    archived: Boolean(t.archived),
    bookmarked: Boolean(t.bookmarked),
    hasAcceptedAnswer: Boolean(t.has_accepted_answer),
    categoryId: catId,
    categoryName: catMeta?.displayName || fallbackCatName,
    categoryColorHex: catMeta?.colorHex,
    tags,
    createdAt: String(t.created_at || ""),
    lastPostedAt: String(t.last_posted_at || t.bumped_at || t.created_at || ""),
    originalPoster: {
      userId: Number(opUser.id || 0),
      username: opUsername,
      name: opUser.name ? String(opUser.name) : undefined,
      avatarTemplate: opTpl,
      animatedAvatar: opUser.animated_avatar ? String(opUser.animated_avatar) : null,
      primaryGroupName: opUser.primary_group_name ? String(opUser.primary_group_name) : null,
      flair: parseFlairFromObj(opUser),
    },
  };
}

export async function fetchTopicFeed(params: {
  sort: string;
  page?: number;
  categoryId?: number | null;
  tag?: string | null;
  topPeriod?: string;
  username?: string;
  background?: boolean;
}): Promise<{ topics: TopicSummary[]; hasMore: boolean }> {
  const page = Math.max(0, params.page ?? 0);
  const sort = params.sort || "latest";

  // `follow` stream uses `discourse-follow` plugin endpoint (`/follow/posts/{username}.json?limit=30`)
  if (sort === "follow") {
    const uname = (params.username || "").trim();
    if (!uname) {
      throw new SiteError("请先登录账号查看关注动态", { isAuthRequired: true, status: 401 });
    }
    const cursorKeyPrefix = `${uname.toLowerCase()}:`;
    let cursorParam = "";
    if (page === 0) {
      for (const k of Array.from(followFeedCursors.keys())) {
        if (k.startsWith(cursorKeyPrefix)) followFeedCursors.delete(k);
      }
    } else {
      const cursor = followFeedCursors.get(`${cursorKeyPrefix}${page}`);
      if (!cursor) return { topics: [], hasMore: false };
      cursorParam = `&created_before=${encodeURIComponent(cursor)}`;
    }

    const followUrl = `https://linux.do/follow/posts/${encodeURIComponent(uname)}.json?limit=30${cursorParam}`;
    const data = await getJsonOrThrow(followUrl, Boolean(params.background));
    const postsArr: any[] | null = Array.isArray(data?.posts)
      ? data.posts
      : Array.isArray(data?.feed)
        ? data.feed
        : null;

    if (postsArr) {
      const hasMore = Boolean(data?.extras?.has_more);
      const lastIso = postsArr.length > 0 ? String(postsArr[postsArr.length - 1]?.created_at || "") : "";
      if (hasMore && lastIso) {
        followFeedCursors.set(`${cursorKeyPrefix}${page + 1}`, lastIso);
      }
      const mapped: TopicSummary[] = postsArr
        .map((pObj: any) => {
          const tObj = pObj.topic || {};
          const uObj = pObj.user || {};
          const tid = Number(tObj.id || pObj.topic_id || 0);
          if (!tid) return null;
          const catId = Number(tObj.category_id || pObj.category_id || 4);
          const catMeta = findCategoryMeta(catId);
          const postNumber = Number(pObj.post_number || 1);
          const postsCount = Math.max(Number(tObj.posts_count || postNumber), postNumber);
          const tags: string[] = Array.isArray(tObj.tags)
            ? tObj.tags.map((tg: any) => (typeof tg === "string" ? tg : String(tg?.name || ""))).filter(Boolean)
            : [];
          return {
            id: tid,
            title: String(tObj.title || pObj.title || stripHtmlTags(String(tObj.fancy_title || "")) || `话题 #${tid}`),
            slug: String(tObj.slug || pObj.slug || "topic"),
            excerpt: stripHtmlTags(String(pObj.excerpt || pObj.cooked || "")).slice(0, 180) || undefined,
            postsCount,
            replyCount: Math.max(0, Number(tObj.reply_count ?? postsCount - 1)),
            views: Number(tObj.views || 0),
            likeCount: Number(tObj.like_count || 0),
            highestPostNumber: Math.max(Number(tObj.highest_post_number || postsCount), postNumber),
            unreadPosts: 0,
            unseen: Boolean(tObj.unseen),
            pinned: false,
            closed: Boolean(tObj.closed),
            archived: Boolean(tObj.archived),
            bookmarked: Boolean(tObj.bookmarked),
            hasAcceptedAnswer: Boolean(tObj.has_accepted_answer),
            categoryId: catId,
            categoryName: catMeta?.displayName || CANONICAL_NAME_BY_ID.get(catId),
            categoryColorHex: catMeta?.colorHex,
            tags,
            createdAt: String(pObj.created_at || ""),
            lastPostedAt: String(pObj.created_at || ""),
            originalPoster: {
              userId: Number(uObj.id || 0),
              username: String(uObj.username || pObj.username || "linuxdo"),
              name: uObj.name ? String(uObj.name) : undefined,
              avatarTemplate: String(uObj.avatar_template || pObj.avatar_template || ""),
              animatedAvatar: uObj.animated_avatar ? String(uObj.animated_avatar) : null,
              primaryGroupName: uObj.primary_group_name ? String(uObj.primary_group_name) : null,
            },
          } as TopicSummary;
        })
        .filter((x): x is TopicSummary => Boolean(x))
        .filter((item) => {
          const catOk =
            !params.categoryId ||
            item.categoryId === params.categoryId ||
            findCategoryMeta(item.categoryId)?.parentId === params.categoryId;
          const tagOk =
            !params.tag ||
            item.tags.some((tg) => tg.toLowerCase() === params.tag!.trim().toLowerCase());
          return catOk && tagOk;
        });

      return { topics: mapped, hasMore };
    }
  }

  // Standard Discourse topic list routes (aligned 1:1 with LinDoNetworkStack.kt lines 3153–3202)
  const sortSuffix = ["top", "new", "unread", "hot"].includes(sort) ? `/l/${sort}.json` : ".json";
  let basePath: string;
  if (params.tag) {
    basePath = `tag/${encodeURIComponent(params.tag.trim())}${sortSuffix}`;
  } else if (params.categoryId) {
    const cat = findCategoryMeta(params.categoryId);
    const catPrefix = !cat
      ? `c/${params.categoryId}`
      : cat.hasServerParent && cat.parentSlug && !cat.slug.includes("/")
        ? `c/${cat.parentSlug}/${cat.slug}/${cat.id}`
        : `c/${cat.slug}/${cat.id}`;
    basePath = `${catPrefix}${sortSuffix}`;
  } else {
    basePath = `${sort}.json`;
  }

  const qs = ["no_definitions=true"];
  if (sort === "top") {
    qs.push(`period=${encodeURIComponent(params.topPeriod || "daily")}`);
  }
  if (page > 0) {
    qs.push(`page=${page}`);
  }
  const url = `https://linux.do/${basePath}?${qs.join("&")}`;
  const canUseShortCache = page === 0 && !params.background && Date.now() >= forceNextRetryUntilMs;
  if (canUseShortCache) {
    const hit = shortFeedCache.get(url);
    if (hit && Date.now() - hit.ts <= SHORT_LIST_CACHE_TTL_MS) {
      return hit.data;
    }
  }

  const data = await getJsonOrThrow(url, Boolean(params.background));
  const usersMap = new Map<number, any>();
  for (const u of data?.users ?? []) {
    usersMap.set(Number(u.id), u);
  }

  const rawTopics: any[] = data?.topic_list?.topics ?? data?.topics ?? [];
  const topics = rawTopics.map((t) => mapTopicListEntry(t, usersMap));
  const moreUrl = data?.topic_list?.more_topics_url;
  const result = {
    topics,
    hasMore: Boolean(moreUrl) || rawTopics.length >= 15,
  };
  if (page === 0 && topics.length > 0) {
    shortFeedCache.set(url, { ts: Date.now(), data: result });
  }
  return result;
}

/* ==================== 4. 话题阅读器、投票、嵌套回复、小火箭助力与解答采纳 ==================== */

function parsePollsOnPost(p: any): PostPollModel[] {
  if (!Array.isArray(p?.polls) || p.polls.length === 0) return [];
  const userPollsVotes = p.polls_votes && typeof p.polls_votes === "object" ? p.polls_votes : {};
  return p.polls
    .map((pl: any): PostPollModel | null => {
      const name = String(pl?.name || "poll");
      const rawOptions: any[] = Array.isArray(pl?.options) ? pl.options : [];
      if (rawOptions.length === 0) return null;
      const myVotesRaw = userPollsVotes[name];
      const myVotes = Array.isArray(myVotesRaw) ? myVotesRaw.map((v: any) => String(v)) : [];
      return {
        name,
        type: pl.type === "multiple" ? "multiple" : pl.type === "number" ? "number" : "regular",
        status: String(pl.status || "open").toLowerCase() === "closed" ? "closed" : "open",
        results: pl.results || "always",
        options: rawOptions.map((o: any) => ({
          id: String(o.id || ""),
          html: String(o.html || ""),
          votes: Number(o.votes || 0),
        })),
        voters: Number(pl.voters || 0),
        min: pl.min != null ? Number(pl.min) : undefined,
        max: pl.max != null ? Number(pl.max) : undefined,
        title: pl.title ? String(pl.title) : undefined,
        isPublic: pl.public === true || String(pl.public || "").toLowerCase() === "true",
        myVotes,
      };
    })
    .filter((x: PostPollModel | null): x is PostPollModel => Boolean(x));
}

function parsePostModel(
  p: any,
  fallbackTopicId: number,
  authorBadgesMap?: Map<number, GrantedBadge[]>,
  lastReadPostNumber?: number | null
): PostItemModel {
  const reactions: PostReactionItem[] = Array.isArray(p.reactions)
    ? p.reactions
        .map((r: any) => ({ id: String(r.id || ""), count: Number(r.count || 0) }))
        .filter((r: PostReactionItem) => r.id && r.count > 0)
    : [];

  const currentReaction = p.current_user_reaction?.id
    ? String(p.current_user_reaction.id)
    : p.current_user_used_main_reaction
      ? "heart"
      : null;

  const boosts: PostBoostItem[] = Array.isArray(p.boosts)
    ? p.boosts.map((b: any) => ({
        id: Number(b.id || 0),
        username: String(b.user?.username || b.username || ""),
        displayName: b.user?.name ? String(b.user.name) : undefined,
        avatarTemplate: String(b.user?.avatar_template || b.avatar_template || ""),
        cooked: String(b.cooked || b.raw || ""),
        canDelete: Boolean(b.can_delete),
      }))
    : [];

  const linkCounts: PostLinkCountItem[] = Array.isArray(p.link_counts)
    ? (() => {
        const seen = new Set<string>();
        const out: PostLinkCountItem[] = [];
        for (const rawLink of p.link_counts) {
          const url = String(rawLink?.url || "").trim();
          const title = stripHtmlTags(String(rawLink?.title || "")).trim();
          if (!url || !title) continue;
          const internal = Boolean(rawLink?.internal);
          const reflection = Boolean(rawLink?.reflection);
          const parsedTopic = parseInternalTopicLink(url);
          const dedupeKey = parsedTopic
            ? `t:${parsedTopic.topicId}:${reflection ? 1 : 0}`
            : `u:${url}:${reflection ? 1 : 0}`;
          if (seen.has(dedupeKey)) continue;
          seen.add(dedupeKey);
          out.push({
            url,
            title,
            clicks: Number(rawLink?.clicks || 0),
            internal: internal || Boolean(parsedTopic),
            reflection,
            topicId: parsedTopic?.topicId,
            postNumber: parsedTopic?.postNumber,
          });
        }
        return out;
      })()
    : [];

  const userId = Number(p.user_id || 0);
  const username = String(p.username || "");
  const avatarTemplate = String(p.avatar_template || "");
  if (username && avatarTemplate) {
    userAvatarMemoryCache.set(username.toLowerCase(), avatarTemplate);
  }

  const postNumber = Number(p.post_number || 1);
  const postType = Number(p.post_type || 1);
  const actionCode = p.action_code ? String(p.action_code).trim() : "";
  const isSmallAction = postType === 3 || Boolean(actionCode);
  const rawCooked = String(p.cooked || "").trim();
  const isDeletedTombstone =
    Boolean(p.user_deleted) ||
    rawCooked.includes("(帖子已被作者删除)") ||
    rawCooked.includes("（帖子已被作者删除）");

  const cookedHtml =
    !rawCooked && isSmallAction
      ? `<p><em>${actionCode ? localizeSmallActionCode(actionCode) : "话题动态更新"}</em></p>`
      : rawCooked;

  const userStatusObj = p.user_status && typeof p.user_status === "object" ? p.user_status : null;
  const userStatus =
    userStatusObj && (userStatusObj.emoji || userStatusObj.description)
      ? {
          emoji: userStatusObj.emoji ? String(userStatusObj.emoji) : null,
          description: userStatusObj.description ? String(userStatusObj.description) : null,
        }
      : null;

  const rawRead = typeof p.read === "boolean" ? p.read : undefined;
  const isRead =
    rawRead === true
      ? true
      : lastReadPostNumber != null && lastReadPostNumber > 0
        ? postNumber <= lastReadPostNumber && rawRead !== false
        : rawRead === false
          ? false
          : true;

  const replyUser = p.reply_to_user || {};
  return {
    id: Number(p.id),
    topicId: Number(p.topic_id || fallbackTopicId),
    postNumber,
    userId,
    username,
    displayName: String(p.name || p.display_username || username),
    avatarTemplate,
    animatedAvatar: p.animated_avatar ? String(p.animated_avatar) : null,
    primaryGroupName: p.primary_group_name ? String(p.primary_group_name) : null,
    userTitle: p.user_title ? String(p.user_title) : null,
    titleIsGroup: Boolean(p.title_is_group),
    trustLevel: Number(p.trust_level ?? 1),
    isModerator: Boolean(p.moderator),
    isGroupModerator: Boolean(p.group_moderator || p.category_group_moderator),
    isAdmin: Boolean(p.admin),
    flair: parseFlairFromObj(p),
    userStatus,
    iosDeviceName: p.ios_device_name ? String(p.ios_device_name).trim() || null : null,
    isTodayBirthday: isTodayMonthDay(p.user_birthdate, false),
    isTodayCakeday: isTodayMonthDay(p.user_cakedate, true),
    badgesGranted: parsePostGrantedBadgesList(p.badges_granted),
    authorBadges: authorBadgesMap?.get(userId) ?? [],
    createdAt: String(p.created_at || ""),
    cookedHtml,
    raw: p.raw ? String(p.raw) : undefined,
    replyCount: Number(p.reply_count || 0),
    replyToPostNumber: p.reply_to_post_number ? Number(p.reply_to_post_number) : null,
    replyToUsername: replyUser.username ? String(replyUser.username) : null,
    replyToDisplayName: replyUser.name
      ? String(replyUser.name)
      : replyUser.username
        ? String(replyUser.username)
        : null,
    replyToAvatarTemplate: replyUser.avatar_template
      ? String(replyUser.avatar_template)
      : replyUser.username
        ? userAvatarMemoryCache.get(String(replyUser.username).toLowerCase()) || null
        : null,
    likeCount:
      reactions.reduce((acc, r) => acc + r.count, 0) ||
      Number((p.actions_summary ?? []).find((a: any) => Number(a.id) === 2)?.count || p.like_count || 0),
    liked: Boolean(
      currentReaction || (p.actions_summary ?? []).find((a: any) => Number(a.id) === 2)?.acted
    ),
    isRead,
    currentUserReaction: currentReaction,
    reactions,
    boosts,
    linkCounts,
    canBoost: p.can_boost !== undefined ? Boolean(p.can_boost) : !Boolean(p.yours),
    polls: parsePollsOnPost(p),
    bookmarked: Boolean(p.bookmarked),
    bookmarkId: p.bookmark_id ? Number(p.bookmark_id) : null,
    bookmarkName: p.bookmark_name ? String(p.bookmark_name) : null,
    bookmarkReminderAt: p.bookmark_reminder_at ? String(p.bookmark_reminder_at) : null,
    version: Number(p.version || 1),
    updatedAt: p.updated_at ? String(p.updated_at) : null,
    canViewEditHistory:
      p.can_view_edit_history !== undefined
        ? Boolean(p.can_view_edit_history)
        : Number(p.version || 1) > 1,
    editReason: p.edit_reason ? String(p.edit_reason) : null,
    canEdit: Boolean(p.can_edit ?? p.yours),
    canDelete: Boolean(p.can_delete),
    isSmallAction,
    actionCode: actionCode || null,
    isDeletedTombstone,
    acceptedAnswer: Boolean(p.accepted_answer),
    canAcceptAnswer: Boolean(p.can_accept_answer),
  };
}

const TOPIC_DETAIL_CACHE_TTL_MS = 90_000;
const TOPIC_DETAIL_CACHE_MAX = 28;
const topicDetailMemoryCache = new Map<number, { ts: number; detail: TopicDetailModel }>();
let lastTopicTrackVisitAtMs = 0;

const SHORT_LIST_CACHE_TTL_MS = 12000;
const shortFeedCache = new Map<string, { ts: number; data: { topics: TopicSummary[]; hasMore: boolean } }>();
let shortNotifCache: { ts: number; items: NotificationItem[] } | null = null;

const MAX_TRACKED_NOTIF_IDS = 1000;
const MAX_PENDING_MARK_READ_IDS = 500;
const POST_FLOOR_READABLE_NOTIF_TYPES = new Set([1, 2, 3, 6, 9, 15, 17, 36, 37, 38, 801, 802]);
let notificationOwner = "";

function isNotifAutoReadableByTopicFloor(item: NotificationItem): boolean {
  return (
    Boolean(item.topicId && item.topicId > 0) &&
    Boolean(item.postNumber && item.postNumber > 0) &&
    !item.isConsolidatedReplies &&
    (item.count ?? 0) <= 1 &&
    POST_FLOOR_READABLE_NOTIF_TYPES.has(item.notificationType)
  );
}
const locallyReadNotifIds = new Set<number>();
const pendingMarkReadNotifIds = new Set<number>();
const confirmedTopicReadFloors = new Map<number, number>();
let inFlightMarkReadDrain: Promise<void> | null = null;

function addBoundedSetId(set: Set<number>, id: number, maxEntries: number): boolean {
  if (id <= 0) return false;
  if (set.has(id)) return false;
  set.add(id);
  while (set.size > maxEntries) {
    const oldest = set.values().next().value;
    if (oldest === undefined) break;
    set.delete(oldest);
  }
  return true;
}

function ensureNotificationOwner(username?: string | null): void {
  const clean = String(username || "").trim().toLowerCase();
  if (!clean) return;
  if (notificationOwner && notificationOwner !== clean) {
    locallyReadNotifIds.clear();
    pendingMarkReadNotifIds.clear();
    confirmedTopicReadFloors.clear();
    shortNotifCache = null;
  }
  notificationOwner = clean;
}

export function resetNotificationReadState(): void {
  notificationOwner = "";
  locallyReadNotifIds.clear();
  pendingMarkReadNotifIds.clear();
  confirmedTopicReadFloors.clear();
  shortNotifCache = null;
}

export function invalidateNotificationShortCache(): void {
  shortNotifCache = null;
}

export function resolveAuthoritativeUnreadCount(
  pageZeroItems: NotificationItem[],
  previousCount: number,
  pageSizeLimit = 60
): number {
  const pageUnread = pageZeroItems.filter((item) => !item.read).length;
  if (pageZeroItems.length < pageSizeLimit || pageUnread < pageZeroItems.length) {
    return pageUnread;
  }
  return Math.max(Math.max(0, previousCount), pageUnread);
}

function overlayNotificationsWithLocalState(items: NotificationItem[]): NotificationItem[] {
  if (items.length === 0) return items;
  return items.map((item) => {
    if (item.read) {
      pendingMarkReadNotifIds.delete(item.id);
      return item;
    }
    const readLocally = locallyReadNotifIds.has(item.id);
    const confirmedFloor = item.topicId && item.topicId > 0
      ? confirmedTopicReadFloors.get(item.topicId) ?? 0
      : 0;
    const readByTopicFloor =
      isNotifAutoReadableByTopicFloor(item) &&
      confirmedFloor >= (item.postNumber ?? 0);
    if (readLocally || readByTopicFloor) {
      addBoundedSetId(locallyReadNotifIds, item.id, MAX_TRACKED_NOTIF_IDS);
      if (readByTopicFloor) {
        addBoundedSetId(pendingMarkReadNotifIds, item.id, MAX_PENDING_MARK_READ_IDS);
      }
      return { ...item, read: true };
    }
    return item;
  });
}

function applySingleNotifReadLocally(notificationId: number): boolean {
  if (notificationId <= 0) return false;
  const addedToLedger = addBoundedSetId(locallyReadNotifIds, notificationId, MAX_TRACKED_NOTIF_IDS);
  addBoundedSetId(pendingMarkReadNotifIds, notificationId, MAX_PENDING_MARK_READ_IDS);
  let transitioned = false;
  if (shortNotifCache) {
    shortNotifCache = {
      ...shortNotifCache,
      items: shortNotifCache.items.map((item) => {
        if (item.id === notificationId && !item.read) {
          transitioned = true;
          return { ...item, read: true };
        }
        return item;
      }),
    };
  }
  return transitioned || addedToLedger;
}

export function recordConfirmedTopicFloorForNotifications(topicId: number, floor: number): number {
  if (topicId <= 0 || floor <= 0) return 0;
  const prev = confirmedTopicReadFloors.get(topicId) ?? 0;
  const updatedFloor = Math.max(prev, floor);
  if (updatedFloor > prev) {
    confirmedTopicReadFloors.delete(topicId);
    confirmedTopicReadFloors.set(topicId, updatedFloor);
    while (confirmedTopicReadFloors.size > 1500) {
      const oldest = confirmedTopicReadFloors.keys().next().value;
      if (oldest === undefined) break;
      confirmedTopicReadFloors.delete(oldest);
    }
  }
  if (!shortNotifCache || shortNotifCache.items.length === 0) {
    return 0;
  }
  let newlyMarked = 0;
  shortNotifCache = {
    ...shortNotifCache,
    items: shortNotifCache.items.map((item) => {
      if (
        !item.read &&
        isNotifAutoReadableByTopicFloor(item) &&
        item.topicId === topicId &&
        updatedFloor >= (item.postNumber ?? 0)
      ) {
        newlyMarked += 1;
        addBoundedSetId(locallyReadNotifIds, item.id, MAX_TRACKED_NOTIF_IDS);
        addBoundedSetId(pendingMarkReadNotifIds, item.id, MAX_PENDING_MARK_READ_IDS);
        return { ...item, read: true };
      }
      return item;
    }),
  };
  if (newlyMarked > 0) {
    void drainPendingMarkReads();
  }
  return newlyMarked;
}

export function getShortCachedUnreadCount(): number | null {
  if (!shortNotifCache) return null;
  return shortNotifCache.items.filter((x) => !x.read).length;
}

export function clearTopicDetailCache(): void {
  topicDetailMemoryCache.clear();
  shortFeedCache.clear();
  shortNotifCache = null;
}

export function getCachedTopicDetail(topicId: number): TopicDetailModel | null {
  const hit = topicDetailMemoryCache.get(topicId);
  if (!hit) return null;
  if (Date.now() - hit.ts > TOPIC_DETAIL_CACHE_TTL_MS) {
    topicDetailMemoryCache.delete(topicId);
    return null;
  }
  topicDetailMemoryCache.delete(topicId);
  topicDetailMemoryCache.set(topicId, hit);
  return hit.detail;
}

export function putCachedTopicDetail(detail: TopicDetailModel): void {
  if (!detail || detail.id <= 0 || detail.posts.length === 0) return;
  topicDetailMemoryCache.delete(detail.id);
  topicDetailMemoryCache.set(detail.id, { ts: Date.now(), detail });
  while (topicDetailMemoryCache.size > TOPIC_DETAIL_CACHE_MAX) {
    const oldestKey = topicDetailMemoryCache.keys().next().value;
    if (oldestKey === undefined) break;
    topicDetailMemoryCache.delete(oldestKey);
  }
}

export async function fetchTopicDetail(
  topicId: number,
  opts: {
    targetPostNumber?: number | null;
    onlyOpUsername?: string | null;
    filterSummary?: boolean;
    trackVisit?: boolean;
    forceRefresh?: boolean;
  } = {}
): Promise<TopicDetailModel> {
  const canUseCache =
    !opts.forceRefresh &&
    !opts.onlyOpUsername &&
    !opts.filterSummary &&
    (!opts.targetPostNumber || opts.targetPostNumber <= 1);
  if (canUseCache) {
    const cached = getCachedTopicDetail(topicId);
    if (cached) return cached;
  }

  const nowMs = Date.now();
  const shouldTrack =
    opts.trackVisit !== false &&
    (Boolean(opts.forceRefresh) || nowMs - lastTopicTrackVisitAtMs >= 1800);
  if (shouldTrack) {
    lastTopicTrackVisitAtMs = nowMs;
  }

  const params = new URLSearchParams();
  if (shouldTrack) params.set("track_visit", "true");
  if (opts.onlyOpUsername) params.set("username_filters", opts.onlyOpUsername);
  if (opts.filterSummary) params.set("filter", "summary");

  const qs = params.toString();
  const suffix = qs ? `?${qs}` : "";
  const path =
    opts.targetPostNumber && opts.targetPostNumber > 1 && !opts.onlyOpUsername && !opts.filterSummary
      ? `https://linux.do/t/${topicId}/${opts.targetPostNumber}.json${suffix}`
      : `https://linux.do/t/${topicId}.json${suffix}`;

  // Include Discourse-Track-View headers so reading topics counts toward Connect `topics_entered`!
  const trackHeaders = shouldTrack
    ? {
        "Discourse-Track-View": "1",
        "Discourse-Track-View-Topic-Id": String(topicId),
      }
    : undefined;

  const data = await getJsonOrThrow(path, false, trackHeaders);
  const catId = data.category_id ? Number(data.category_id) : undefined;
  const catMeta = findCategoryMeta(catId);
  const authorBadgesMap = readTopicAuthorBadgesMap(data);
  const lastReadPostNum =
    data.last_read_post_number != null ? Number(data.last_read_post_number) : null;
  const rawPosts: any[] = data?.post_stream?.posts ?? [];
  const streamIds: number[] = (data?.post_stream?.stream ?? []).map((n: any) => Number(n));
  const posts = rawPosts.map((p) =>
    parsePostModel(p, topicId, authorBadgesMap, lastReadPostNum)
  );
  const opUsername = String(data?.details?.created_by?.username || posts[0]?.username || "");
  const tags: string[] = Array.isArray(data.tags)
    ? data.tags.map((tg: any) => (typeof tg === "string" ? tg : String(tg?.name || ""))).filter(Boolean)
    : [];
  const acceptedNum = data?.accepted_answer?.post_number
    ? Number(data.accepted_answer.post_number)
    : posts.find((p) => p.acceptedAnswer)?.postNumber ?? null;

  const rawSuggested: any[] = Array.isArray(data?.suggested_topics) && data.suggested_topics.length > 0
    ? data.suggested_topics
    : Array.isArray(data?.related_topics)
      ? data.related_topics
      : [];
  const suggestedTopics: TopicSummary[] = rawSuggested
    .filter((st: any) => Number(st?.id || 0) > 0 && Number(st?.id) !== Number(data.id || topicId))
    .slice(0, 6)
    .map((st: any) => {
      const sCatId = st.category_id ? Number(st.category_id) : undefined;
      const sCatMeta = findCategoryMeta(sCatId);
      const sTags: string[] = Array.isArray(st.tags)
        ? st.tags.map((tg: any) => (typeof tg === "string" ? tg : String(tg?.name || ""))).filter(Boolean)
        : [];
      const firstPoster = Array.isArray(st.posters) ? st.posters[0] : null;
      return {
        id: Number(st.id),
        title: stripHtmlTags(String(st.title || st.fancy_title || `话题 #${st.id}`)),
        slug: String(st.slug || "topic"),
        categoryId: sCatId,
        categoryName: sCatMeta?.displayName || (sCatId ? CANONICAL_NAME_BY_ID.get(sCatId) : undefined),
        categoryColorHex: sCatMeta?.colorHex,
        tags: sTags,
        postsCount: Number(st.posts_count || 1),
        replyCount: Math.max(0, Number(st.reply_count ?? (Number(st.posts_count || 1) - 1))),
        views: Number(st.views || 0),
        likeCount: Number(st.like_count || 0),
        highestPostNumber: Number(st.highest_post_number || st.posts_count || 1),
        unreadPosts: Number(st.unread_posts || 0),
        unseen: Boolean(st.unseen),
        pinned: Boolean(st.pinned),
        closed: Boolean(st.closed),
        archived: Boolean(st.archived),
        bookmarked: Boolean(st.bookmarked),
        createdAt: String(st.created_at || ""),
        lastPostedAt: String(st.last_posted_at || st.bumped_at || st.created_at || ""),
        originalPoster: {
          userId: Number(firstPoster?.user_id || 0),
          username: String(st.last_poster_username || "linuxdo"),
          avatarTemplate: "",
        },
      };
    });

  const rawAllowedUsers: any[] = Array.isArray(data?.details?.allowed_users)
    ? data.details.allowed_users
    : [];
  const allowedUsers: TopicPoster[] = rawAllowedUsers
    .filter((u: any) => Boolean(u?.username))
    .map((u: any) => ({
      userId: Number(u.id || 0),
      username: String(u.username || ""),
      name: u.name ? String(u.name) : undefined,
      avatarTemplate: String(u.avatar_template || ""),
      animatedAvatar: u.animated_avatar ? String(u.animated_avatar) : null,
      primaryGroupName: u.primary_group_name ? String(u.primary_group_name) : null,
    }));

  const detail: TopicDetailModel = {
    id: Number(data.id || topicId),
    title: String(data.title || ""),
    archetype: String(data.archetype || "regular"),
    categoryId: catId,
    categoryName: catMeta?.displayName || (catId ? CANONICAL_NAME_BY_ID.get(catId) : undefined),
    categoryColorHex: catMeta?.colorHex,
    tags,
    views: Number(data.views || 0),
    postsCount: Number(data.posts_count || posts.length || 1),
    likeCount: Number(data.like_count || 0),
    bookmarked: Boolean(data.bookmarked),
    closed: Boolean(data.closed),
    archived: Boolean(data.archived),
    notificationLevel: Number(data?.details?.notification_level ?? 1),
    acceptedAnswerPostNumber: acceptedNum,
    lastReadPostNumber: lastReadPostNum,
    opUsername,
    streamIds,
    posts,
    suggestedTopics,
    allowedUsers,
    canInviteTo: Boolean(data?.details?.can_invite_to),
    canRemoveAllowedUsers: Boolean(data?.details?.can_remove_allowed_users),
    hasSummary: Boolean(data.has_summary),
    summarizable: Boolean(data.summarizable),
  };
  if (lastReadPostNum != null && lastReadPostNum > 0) {
    recordConfirmedTopicFloorForNotifications(detail.id, lastReadPostNum);
  }
  if (
    !opts.onlyOpUsername &&
    !opts.filterSummary &&
    (!opts.targetPostNumber || opts.targetPostNumber <= 1)
  ) {
    putCachedTopicDetail(detail);
  }
  return detail;
}

/** Resolve Discourse `/p/{postId}` short permalink to `{ topicId, postNumber }` so it opens natively in Lindo PC */
export async function fetchPostLocationById(
  postId: number
): Promise<{ topicId: number; postNumber: number; title?: string } | null> {
  if (!postId || postId <= 0) return null;
  try {
    const data = await getJsonOrThrow(`https://linux.do/posts/${postId}.json`);
    const tid = Number(data?.topic_id || 0);
    const pnum = Number(data?.post_number || 1);
    if (tid > 0) {
      return {
        topicId: tid,
        postNumber: pnum > 0 ? pnum : 1,
        title: data?.topic_slug ? undefined : undefined,
      };
    }
    return null;
  } catch {
    return null;
  }
}

export async function fetchPostsByIds(
  topicId: number,
  postIds: number[],
  fallbackAuthorBadges?: Map<number, GrantedBadge[]>,
  lastReadPostNumber?: number | null,
  background = false
): Promise<PostItemModel[]> {
  if (postIds.length === 0) return [];
  const batch = postIds.slice(0, 20);
  const qs = batch.map((id) => `post_ids[]=${encodeURIComponent(String(id))}`).join("&");
  const data = await getJsonOrThrow(`https://linux.do/t/${topicId}/posts.json?${qs}`, background);
  const parsedMap = readTopicAuthorBadgesMap(data);
  const effectiveBadges = parsedMap.size > 0 ? parsedMap : fallbackAuthorBadges;
  const rawPosts: any[] = data?.post_stream?.posts ?? [];
  return rawPosts.map((p) => parsePostModel(p, topicId, effectiveBadges, lastReadPostNumber));
}

/**
 * 轻量尾部增量刷新：仅探测当前最后楼层之后的新增 `post_ids` 并拉取未加载楼层，绝不重载整页。
 */
export async function fetchTopicTailNewPosts(
  topicId: number,
  afterPostNumber: number,
  loadedPosts: readonly PostItemModel[],
  fallbackAuthorBadges?: Map<number, GrantedBadge[]>,
  lastReadPostNumber?: number | null
): Promise<{ incomingStreamIds: number[]; fetchedPosts: PostItemModel[] }> {
  const safeFloor = Math.max(1, Math.floor(afterPostNumber));
  let candidateIds: number[] = [];
  let inlinePosts: PostItemModel[] = [];

  try {
    const idsResp = await getJsonOrThrow(
      `https://linux.do/t/${topicId}/post_ids.json?post_number=${safeFloor}&limit=100`
    );
    if (Array.isArray(idsResp?.post_ids)) {
      candidateIds = idsResp.post_ids
        .map((x: any) => Number(x))
        .filter((n: number) => Number.isFinite(n) && n > 0);
    }
  } catch (err) {
    if (err instanceof SiteError && (err.isVerification || err.status === 429)) {
      throw err;
    }
    // 部分节点或私信不支持 `/post_ids.json` 时，回退轻量锚点尾页拉取
    const tailData = await getJsonOrThrow(
      `https://linux.do/t/${topicId}/${safeFloor}.json?track_visit=false`
    );
    const parsedMap = readTopicAuthorBadgesMap(tailData);
    const effectiveBadges = parsedMap.size > 0 ? parsedMap : fallbackAuthorBadges;
    if (Array.isArray(tailData?.post_stream?.stream)) {
      candidateIds = tailData.post_stream.stream
        .map((x: any) => Number(x))
        .filter((n: number) => Number.isFinite(n) && n > 0);
    }
    if (Array.isArray(tailData?.post_stream?.posts)) {
      inlinePosts = tailData.post_stream.posts.map((p: any) =>
        parsePostModel(p, topicId, effectiveBadges, lastReadPostNumber)
      );
    }
  }

  const loadedIds = new Set(loadedPosts.map((p) => p.id));
  const inlineMap = new Map<number, PostItemModel>();
  for (const p of inlinePosts) {
    if (!loadedIds.has(p.id)) {
      inlineMap.set(p.id, p);
    }
  }

  const missingIds: number[] = [];
  const seenMissing = new Set<number>();
  for (const id of candidateIds) {
    if (loadedIds.has(id) || inlineMap.has(id) || seenMissing.has(id)) continue;
    seenMissing.add(id);
    missingIds.push(id);
    if (missingIds.length >= 20) break;
  }

  let fetchedFromApi: PostItemModel[] = [];
  if (missingIds.length > 0) {
    fetchedFromApi = await fetchPostsByIds(
      topicId,
      missingIds,
      fallbackAuthorBadges,
      lastReadPostNumber,
      false
    );
  }

  const combinedMap = new Map<number, PostItemModel>();
  for (const p of inlineMap.values()) combinedMap.set(p.id, p);
  for (const p of fetchedFromApi) combinedMap.set(p.id, p);

  return {
    incomingStreamIds: candidateIds,
    fetchedPosts: Array.from(combinedMap.values()).sort((a, b) => a.postNumber - b.postNumber),
  };
}

export async function fetchPostByNumber(
  topicId: number,
  postNumber: number,
  fallbackAuthorBadges?: Map<number, GrantedBadge[]>
): Promise<PostItemModel | null> {
  if (!topicId || postNumber <= 0) return null;
  const data = await getJsonOrThrow(
    `https://linux.do/posts/by_number/${topicId}/${postNumber}.json`,
    true
  );
  if (!data || !data.id) return null;
  return parsePostModel(data, topicId, fallbackAuthorBadges);
}

export async function fetchPostReplies(postId: number, fallbackTopicId: number): Promise<PostItemModel[]> {
  const data = await getJsonOrThrow(`https://linux.do/posts/${postId}/replies.json`);
  const arr: any[] = Array.isArray(data) ? data : data?.replies ?? [];
  return arr.map((r) => parsePostModel(r, fallbackTopicId));
}

export async function fetchTopicAiSummary(topicId: number): Promise<string> {
  const endpointJson = `https://linux.do/discourse-ai/summarization/t/${topicId}.json`;
  const endpointBare = `https://linux.do/discourse-ai/summarization/t/${topicId}`;

  const extractSummaryText = (obj: any): string | null => {
    const raw =
      obj?.ai_topic_summary?.summarized_text ??
      obj?.summarized_text ??
      (typeof obj?.summary === "string" ? obj.summary : null);
    const trimmed = typeof raw === "string" ? raw.trim() : "";
    return trimmed.length > 0 ? trimmed : null;
  };

  // 1. Check existing cached summary first (`GET .../t/{id}.json`)
  const firstRes = await siteRequest(endpointJson, "GET");
  if (firstRes.isChallenge) {
    if (!firstRes.diag?.verifyAttempted && !(firstRes.diag?.verifyCooldownMs ?? 0)) {
      const passed = await triggerInteractiveVerifyOnce(false);
      if (passed) {
        return fetchTopicAiSummary(topicId);
      }
    }
    throw buildChallengeSiteError(firstRes, "需要完成安全确认");
  }
  if (firstRes.status >= 200 && firstRes.status < 300) {
    const firstJson = safeJson(firstRes.body);
    const cached = extractSummaryText(firstJson);
    if (cached) return cached;
  } else if (firstRes.status === 403) {
    throw new SiteError("当前账号等级或该话题暂不支持 AI 总结", { status: 403 });
  }

  // 2. Modern Discourse AI triggers summary generation via `POST /discourse-ai/summarization/t/{id}` when cache misses (404/405)
  const createRes = await siteRequest(endpointBare, "POST", { stream: "false" });
  if (createRes.status >= 200 && createRes.status < 300) {
    const createJson = safeJson(createRes.body);
    const immediate = extractSummaryText(createJson);
    if (immediate) return immediate;
  } else if (createRes.status === 404 || createRes.status === 405) {
    // Legacy Discourse fallback (`GET ?stream=true`)
    const legacyRes = await siteRequest(`${endpointJson}?stream=true`, "GET");
    if (legacyRes.status >= 200 && legacyRes.status < 300) {
      const legacyText = extractSummaryText(safeJson(legacyRes.body));
      if (legacyText) return legacyText;
    }
  } else if (createRes.status === 403) {
    throw new SiteError("当前账号等级或该话题暂不支持 AI 总结", { status: 403 });
  }

  // 3. Poll briefly if server queued background generation
  for (let i = 0; i < 6; i++) {
    await new Promise((r) => setTimeout(r, 1100));
    const pollRes = await siteRequest(endpointJson, "GET", undefined, { "Discourse-Background": "true" });
    if (pollRes.status >= 200 && pollRes.status < 300) {
      const polled = extractSummaryText(safeJson(pollRes.body));
      if (polled) return polled;
    }
  }

  return "暂无可用总结，请稍后再试";
}

export async function togglePostReaction(postId: number, reactionId = "heart"): Promise<PostItemModel | null> {
  const data = await mutateJsonOrThrow(
    `https://linux.do/discourse-reactions/posts/${postId}/custom-reactions/${encodeURIComponent(reactionId)}/toggle.json`,
    "PUT"
  );
  if (data && data.id) {
    return parsePostModel(data, Number(data.topic_id || 0));
  }
  return null;
}

/**
 * Sends a Discourse Boost (`POST /discourse-boosts/posts/{id}/boosts.json` with `raw` parameter — aligned 1:1 with LinDoNetworkStack.kt).
 */
export async function sendPostBoost(postId: number, content: string): Promise<PostBoostItem | null> {
  const trimmed = content.trim();
  const data = await mutateJsonOrThrow(
    `https://linux.do/discourse-boosts/posts/${postId}/boosts.json`,
    "POST",
    { raw: trimmed }
  );
  const b = data?.boost || data;
  if (!b?.id) return null;
  return {
    id: Number(b.id),
    username: String(b.user?.username || ""),
    displayName: b.user?.name ? String(b.user.name) : undefined,
    avatarTemplate: String(b.user?.avatar_template || ""),
    cooked: String(b.cooked || trimmed),
    canDelete: true,
  };
}

export async function deletePostBoost(boostId: number): Promise<void> {
  await mutateJsonOrThrow(`https://linux.do/discourse-boosts/boosts/${boostId}.json`, "DELETE");
}

export async function deletePostFloor(postId: number): Promise<void> {
  await mutateJsonOrThrow(`https://linux.do/posts/${postId}.json`, "DELETE");
}

export async function acceptPostSolution(postId: number, accept = true): Promise<void> {
  const endpoint = accept
    ? "https://linux.do/solution/accept.json"
    : "https://linux.do/solution/unaccept.json";
  await mutateJsonOrThrow(endpoint, "POST", { id: String(postId) });
}

/**
 * 举报违规楼层 (`POST /post_actions.json`)
 * `flagTypeId`: 8=垃圾广告, 4=不当内容, 3=偏离主题, 7=其他问题（上报版主，需附带说明）
 */
export async function flagPost(
  postId: number,
  flagTypeId: 3 | 4 | 7 | 8,
  message?: string
): Promise<void> {
  const form: Record<string, string> = {
    id: String(postId),
    post_action_type_id: String(flagTypeId),
    flag_topic: "false",
  };
  if (message && message.trim()) {
    form.message = message.trim();
  }
  await mutateJsonOrThrow("https://linux.do/post_actions.json", "POST", form);
}

export async function voteInPoll(
  postId: number,
  pollName: string,
  options: string[]
): Promise<{ poll: PostPollModel | null; myVotes: string[] }> {
  const form: Record<string, string> = {
    post_id: String(postId),
    poll_name: pollName,
  };
  options.forEach((opt, idx) => {
    form[`options[${idx}]`] = opt;
  });
  const root = await mutateJsonOrThrow("https://linux.do/polls/vote.json", "PUT", form);
  const myVotes = Array.isArray(root?.vote)
    ? root.vote.map((v: any) => String(v))
    : options;
  const parsed = root?.poll
    ? parsePollsOnPost({ polls: [root.poll], polls_votes: { [pollName]: myVotes } })[0] ?? null
    : null;
  return { poll: parsed, myVotes };
}

export async function removePollVote(
  postId: number,
  pollName: string
): Promise<PostPollModel | null> {
  const root = await mutateJsonOrThrow(
    `https://linux.do/polls/vote.json?post_id=${encodeURIComponent(String(postId))}&poll_name=${encodeURIComponent(pollName)}`,
    "DELETE",
    { post_id: String(postId), poll_name: pollName }
  );
  const pollObj = root?.poll || (root?.options ? root : null);
  if (!pollObj) return null;
  return parsePollsOnPost({ polls: [pollObj], polls_votes: {} })[0] ?? null;
}

export async function setTopicNotificationLevel(topicId: number, level: number): Promise<void> {
  await mutateJsonOrThrow(`https://linux.do/t/${topicId}/notifications.json`, "POST", {
    notification_level: String(level),
  });
}

export async function togglePostBookmark(
  postId: number,
  existingBookmarkId?: number | null
): Promise<number | null> {
  if (existingBookmarkId) {
    await mutateJsonOrThrow(`https://linux.do/bookmarks/${existingBookmarkId}.json`, "DELETE");
    return null;
  }
  const data = await mutateJsonOrThrow("https://linux.do/bookmarks.json", "POST", {
    bookmarkable_id: String(postId),
    bookmarkable_type: "Post",
  });
  return Number(data?.id || 1);
}

export async function deleteBookmarkById(bookmarkId: number): Promise<void> {
  await mutateJsonOrThrow(`https://linux.do/bookmarks/${bookmarkId}.json`, "DELETE");
}

export async function saveBookmarkWithDetails(params: {
  postId: number;
  existingBookmarkId?: number | null;
  name?: string;
  reminderAt?: string | null;
  autoDeletePreference?: number;
}): Promise<{ id: number; name?: string | null; reminderAt?: string | null }> {
  const form: Record<string, string> = {
    bookmarkable_id: String(params.postId),
    bookmarkable_type: "Post",
    name: (params.name || "").trim(),
    auto_delete_preference: String(params.autoDeletePreference ?? 3),
  };
  if (params.reminderAt) {
    form.reminder_at = params.reminderAt;
  }
  if (params.existingBookmarkId) {
    const res = await mutateJsonOrThrow(
      `https://linux.do/bookmarks/${params.existingBookmarkId}.json`,
      "PUT",
      form
    );
    return {
      id: Number(res?.id || params.existingBookmarkId),
      name: (params.name || "").trim() || null,
      reminderAt: params.reminderAt || null,
    };
  }
  const data = await mutateJsonOrThrow("https://linux.do/bookmarks.json", "POST", form);
  return {
    id: Number(data?.id || 1),
    name: (params.name || "").trim() || null,
    reminderAt: params.reminderAt || null,
  };
}

export async function fetchPostRevision(
  postId: number,
  revision?: number
): Promise<PostRevisionDetail> {
  const path = revision
    ? `https://linux.do/posts/${postId}/revisions/${revision}.json`
    : `https://linux.do/posts/${postId}/revisions/latest.json`;
  const data = await getJsonOrThrow(path);
  return {
    postId,
    currentRevision: Number(data?.current_revision ?? revision ?? 2),
    previousRevision:
      data?.previous_revision != null ? Number(data.previous_revision) : null,
    nextRevision: data?.next_revision != null ? Number(data.next_revision) : null,
    versionCount: Number(data?.version_count ?? data?.current_revision ?? 2),
    username: String(data?.username || ""),
    displayName: data?.display_username ? String(data.display_username) : undefined,
    avatarTemplate: String(data?.avatar_template || ""),
    createdAt: String(data?.created_at || ""),
    editReason: data?.edit_reason ? String(data.edit_reason) : null,
    bodyChangesHtml: data?.body_changes?.inline
      ? String(data.body_changes.inline)
      : data?.body_changes?.side_by_side
        ? String(data.body_changes.side_by_side)
        : null,
    titleChangesHtml: data?.title_changes?.inline
      ? String(data.title_changes.inline)
      : null,
  };
}

export async function fetchPostReactionUsers(postId: number): Promise<ReactionUserGroup[]> {
  const data = await getJsonOrThrow(
    `https://linux.do/discourse-reactions/posts/${postId}/reactions-users.json`
  );
  const rawGroups: any[] = Array.isArray(data?.reaction_users)
    ? data.reaction_users
    : Array.isArray(data)
      ? data
      : [];
  return rawGroups
    .map((g: any) => ({
      id: String(g?.id || "heart"),
      count: Number(g?.count || g?.users?.length || 0),
      users: Array.isArray(g?.users)
        ? g.users
            .filter((u: any) => Boolean(u?.username))
            .map((u: any) => ({
              username: String(u.username),
              name: u.name ? String(u.name) : undefined,
              avatarTemplate: String(u.avatar_template || ""),
            }))
        : [],
    }))
    .filter((g) => g.users.length > 0 || g.count > 0);
}

export async function fetchPollVoters(
  postId: number,
  pollName: string,
  optionId: string,
  page = 1
): Promise<PollVoterItem[]> {
  const qs = new URLSearchParams({
    post_id: String(postId),
    poll_name: pollName,
    option_id: optionId,
    page: String(page),
  });
  const data = await getJsonOrThrow(`https://linux.do/polls/voters.json?${qs.toString()}`);
  const votersMap = data?.voters && typeof data.voters === "object" ? data.voters : {};
  const list: any[] = Array.isArray(votersMap[optionId])
    ? votersMap[optionId]
    : Array.isArray(data?.voters)
      ? data.voters
      : [];
  return list
    .filter((u: any) => Boolean(u?.username))
    .map((u: any) => ({
      username: String(u.username),
      name: u.name ? String(u.name) : undefined,
      avatarTemplate: String(u.avatar_template || ""),
    }));
}

export async function inviteUserToPm(topicId: number, username: string): Promise<void> {
  const clean = username.trim().replace(/^@/, "");
  if (!clean) throw new Error("请输入用户名");
  await mutateJsonOrThrow(`https://linux.do/t/${topicId}/invite.json`, "POST", {
    user: clean,
  });
}

export async function removeUserFromPm(topicId: number, username: string): Promise<void> {
  const clean = username.trim().replace(/^@/, "");
  if (!clean) return;
  await mutateJsonOrThrow(`https://linux.do/t/${topicId}/remove-allowed-user.json`, "PUT", {
    username: clean,
  });
}

// Single-flight coalesced `topics/timings` reporter so rapid 144Hz scroll never piles up concurrent POSTs!
const pendingTimingsByTopic = new Map<number, Set<number>>();
let timingsFlushTimer: ReturnType<typeof setTimeout> | null = null;
let timingsInFlight = false;
let timingsPausedForVerify = false;

/**
 * Cloudflare 验证期间冻结并丢弃阻塞期未发出的阅读时长批次（借鉴 FluxDO 经验），
 * 避免过盾恢复瞬间积压的 `/topics/timings` 齐射把刚恢复的通道直接打成 429。
 */
export function pauseAndDropPendingTimings(): void {
  timingsPausedForVerify = true;
  pendingTimingsByTopic.clear();
  if (timingsFlushTimer) {
    clearTimeout(timingsFlushTimer);
    timingsFlushTimer = null;
  }
}

export function resumeTimingsAfterVerify(): void {
  timingsPausedForVerify = false;
}

async function drainPendingTimings(): Promise<void> {
  if (timingsPausedForVerify || timingsInFlight || pendingTimingsByTopic.size === 0) return;
  timingsInFlight = true;
  try {
    while (pendingTimingsByTopic.size > 0) {
      const nextEntry = pendingTimingsByTopic.entries().next().value as [number, Set<number>] | undefined;
      if (!nextEntry) break;
      const [topicId, set] = nextEntry;
      pendingTimingsByTopic.delete(topicId);
      const nums = Array.from(set).slice(0, 25);
      if (nums.length === 0) continue;
      const form: Record<string, string> = {
        topic_id: String(topicId),
        topic_time: String(Math.min(12000, Math.max(2200, nums.length * 900))),
      };
      for (const num of nums) {
        form[`timings[${num}]`] = "1500";
      }
      try {
        const res = await siteRequest(
          "https://linux.do/topics/timings",
          "POST",
          form,
          { "Discourse-Background": "true" },
          { silentDiag: true }
        );
        if (res.status >= 200 && res.status < 300 && !res.isChallenge && !res.isUnauthenticated) {
          const maxNum = nums.reduce((acc, n) => (n > acc ? n : acc), 0);
          if (maxNum > 0) {
            recordConfirmedTopicFloorForNotifications(topicId, maxNum);
          }
        }
      } catch {
        // non-fatal background telemetry
        break;
      }
    }
  } finally {
    timingsInFlight = false;
  }
}

export async function flushTopicTimingsNow(): Promise<void> {
  if (timingsFlushTimer) {
    clearTimeout(timingsFlushTimer);
    timingsFlushTimer = null;
  }
  await drainPendingTimings();
}

export async function reportTopicTimings(topicId: number, postNumbers: number[]): Promise<void> {
  if (timingsPausedForVerify || postNumbers.length === 0) return;
  let bucket = pendingTimingsByTopic.get(topicId);
  if (!bucket) {
    bucket = new Set<number>();
    pendingTimingsByTopic.set(topicId, bucket);
  }
  for (const n of postNumbers) {
    if (n > 0) bucket.add(n);
  }
  if (timingsFlushTimer) clearTimeout(timingsFlushTimer);
  timingsFlushTimer = setTimeout(() => {
    timingsFlushTimer = null;
    void drainPendingTimings();
  }, 2400);
}

/* ==================== 5. 社区通知中心 (1:1 对齐 fetchUserNotifications & parseNotificationItemsFromRoot) ==================== */

const CONSOLIDATED_COUNT_RE =
  /^(\d+)\s*(?:个|条|位|次)?\s*(?:新)?(?:回复|消息|用户|人|发言|赞|replies|users|messages|notifications).*$/i;

export async function fetchNotifications(offset = 0, forceRefresh = false): Promise<NotificationItem[]> {
  const cleanOffset = Math.max(0, offset);
  if (
    cleanOffset === 0 &&
    !forceRefresh &&
    Date.now() >= forceNextRetryUntilMs &&
    shortNotifCache &&
    Date.now() - shortNotifCache.ts <= SHORT_LIST_CACHE_TTL_MS
  ) {
    return overlayNotificationsWithLocalState(shortNotifCache.items);
  }
  // CRITICAL: Never combine `recent=true` with `offset` in Discourse (returns 400 InvalidParameters!).
  // Use `offset=${cleanOffset}&limit=60&silent=true` exactly like composeApp LinDoNetworkStack.kt line 4243!
  const data = await getJsonOrThrow(
    `https://linux.do/notifications.json?offset=${cleanOffset}&limit=60&silent=true`
  );
  const list: any[] = Array.isArray(data?.notifications) ? data.notifications : [];

  // Pass 1: warm user avatar cache across consolidated items
  for (const n of list) {
    const d = n?.data || {};
    const tpl =
      String(
        n?.acting_user_avatar_template ||
          d?.acting_user_avatar_template ||
          d?.original_user_avatar_template ||
          ""
      ).trim();
    if (!tpl) continue;
    const candidates = [
      d?.original_username,
      d?.username,
      d?.display_username,
      n?.acting_user_name,
      extractUsernameFromAvatarUrl(tpl),
    ];
    for (const cand of candidates) {
      const s = String(cand || "").trim();
      if (s && s !== "系统" && !CONSOLIDATED_COUNT_RE.test(s)) {
        userAvatarMemoryCache.set(s.toLowerCase(), tpl);
        break;
      }
    }
  }

  const parsed: NotificationItem[] = list.map((n) => {
    const d = n?.data || {};
    const nType = Number(n?.notification_type || 0);
    const topicId = n?.topic_id ? Number(n.topic_id) : undefined;
    const postNumber = n?.post_number ? Number(n.post_number) : 1;
    const slug = n?.slug ? String(n.slug) : undefined;

    const rawDisplay = String(d?.display_username || "").trim();
    const consolMatch = rawDisplay ? rawDisplay.match(CONSOLIDATED_COUNT_RE) : null;
    const count = Number(
      d?.consolidated_count ??
        d?.count ??
        d?.inbox_count ??
        (consolMatch ? Number(consolMatch[1]) : 0) ??
        0
    );

    const rawActingTpl = String(
      n?.acting_user_avatar_template ||
        d?.acting_user_avatar_template ||
        d?.original_user_avatar_template ||
        ""
    ).trim();

    const pickCleanActor = (v: any) => {
      const s = String(v || "").trim();
      return s && !CONSOLIDATED_COUNT_RE.test(s) ? s : "";
    };

    const primaryActor =
      (!consolMatch ? rawDisplay : "") ||
      pickCleanActor(d?.original_username) ||
      pickCleanActor(d?.username) ||
      pickCleanActor(d?.mentioned_by_username) ||
      pickCleanActor(extractUsernameFromAvatarUrl(rawActingTpl)) ||
      pickCleanActor(n?.acting_user_name) ||
      "系统";

    const secondActor = pickCleanActor(d?.username2);
    const actorUsername =
      secondActor && secondActor !== primaryActor && primaryActor !== "系统"
        ? `${primaryActor}、${secondActor}`
        : primaryActor;

    const badgeName = String(d?.badge_name || "").trim();
    const cleanFancyTitle = n?.fancy_title ? stripHtmlTags(String(n.fancy_title)).trim() : "";
    const rawTopicTitle =
      String(d?.topic_title || "").trim() ||
      cleanFancyTitle ||
      String(d?.title || "").trim() ||
      String(d?.bookmark_name || "").trim();
    const chatChannelTitle = String(d?.chat_channel_title || "").trim();
    const groupName = String(d?.group_name || "").trim();
    const customMsg = String(d?.message || "").trim();
    const slugTitle = slug && slug !== "topic" ? slug.replace(/-/g, " ") : "";

    const title =
      rawTopicTitle ||
      badgeName ||
      chatChannelTitle ||
      (groupName ? (count > 0 ? `${groupName} (${count} 条消息)` : groupName) : "") ||
      customMsg ||
      slugTitle ||
      (topicId ? `话题 #${topicId}` : "社区通知");

    const rawExcerpt =
      d?.excerpt ||
      n?.excerpt ||
      d?.chat_message ||
      d?.original_post_excerpt ||
      (rawTopicTitle && customMsg ? customMsg : "");

    const actorAvatarTemplate =
      rawActingTpl ||
      (primaryActor !== "系统" ? userAvatarMemoryCache.get(primaryActor.toLowerCase()) : undefined);

    const reactionIcon = String(
      d?.reaction_icon || d?.reaction || d?.emoji || ""
    ).trim();
    const isConsolidatedReplies = Boolean(
      consolMatch && [2, 9, 17, 38, 802].includes(nType)
    );

    return {
      id: Number(n.id),
      notificationType: nType,
      read: Boolean(n.read),
      createdAt: String(n.created_at || ""),
      topicId,
      postNumber,
      originalPostId: d?.original_post_id ? Number(d.original_post_id) : undefined,
      slug,
      title,
      actorUsername,
      actorAvatarTemplate,
      excerpt: rawExcerpt ? stripHtmlTags(String(rawExcerpt)).trim() : undefined,
      count: count > 0 ? count : undefined,
      reactionIcon: reactionIcon || undefined,
      isConsolidatedReplies,
      badgeId: d?.badge_id ? Number(d.badge_id) : undefined,
      badgeName: badgeName || undefined,
      chatChannelId: d?.chat_channel_id ? Number(d.chat_channel_id) : undefined,
    };
  });

  const overlayed = overlayNotificationsWithLocalState(parsed);

  if (cleanOffset === 0) {
    shortNotifCache = { ts: Date.now(), items: overlayed };
    const pageUnread = overlayed.filter((x) => !x.read).length;
    if (overlayed.length < 60 || pageUnread < overlayed.length) {
      const resolved = resolveAuthoritativeUnreadCount(overlayed, pageUnread, 60);
      setUnreadBadgeInRust(resolved).catch(() => {});
    }
    if (pendingMarkReadNotifIds.size > 0) {
      void drainPendingMarkReads();
    }
  }

  return overlayed;
}

export async function drainPendingMarkReads(): Promise<void> {
  if (inFlightMarkReadDrain) {
    await inFlightMarkReadDrain;
  }
  if (inFlightMarkReadDrain || pendingMarkReadNotifIds.size === 0) return;

  const task = (async () => {
    const snapshot = Array.from(pendingMarkReadNotifIds);
    for (const id of snapshot) {
      if (!pendingMarkReadNotifIds.has(id)) continue;
      try {
        const res = await siteRequest(
          "https://linux.do/notifications/mark-read.json",
          "PUT",
          { id: String(id) },
          { "Discourse-Background": "true" },
          { silentDiag: true }
        );
        if (res.status >= 200 && res.status < 300 && !res.isChallenge && !res.isUnauthenticated) {
          pendingMarkReadNotifIds.delete(id);
        } else {
          break;
        }
      } catch {
        break;
      }
    }
  })().finally(() => {
    if (inFlightMarkReadDrain === task) {
      inFlightMarkReadDrain = null;
    }
  });
  inFlightMarkReadDrain = task;
  await task;
}

export async function markAllNotificationsRead(): Promise<void> {
  await mutateJsonOrThrow("https://linux.do/notifications/mark-read.json", "PUT");
  if (shortNotifCache) {
    for (const item of shortNotifCache.items) {
      addBoundedSetId(locallyReadNotifIds, item.id, MAX_TRACKED_NOTIF_IDS);
    }
    shortNotifCache = {
      ts: Date.now(),
      items: shortNotifCache.items.map((x) => ({ ...x, read: true })),
    };
  }
  pendingMarkReadNotifIds.clear();
  setUnreadBadgeInRust(0).catch(() => {});
}

export async function markSingleNotificationRead(id: number): Promise<void> {
  if (id <= 0) return;
  applySingleNotifReadLocally(id);
  try {
    const res = await siteRequest(
      "https://linux.do/notifications/mark-read.json",
      "PUT",
      { id: String(id) },
      { "Discourse-Background": "true" },
      { silentDiag: true }
    );
    if (res.status >= 200 && res.status < 300 && !res.isChallenge && !res.isUnauthenticated) {
      pendingMarkReadNotifIds.delete(id);
    }
  } catch {
    // keep id in pendingMarkReadNotifIds so drainPendingMarkReads retries after recovery
  }
}

/* ==================== 6. 全站搜索、快速引用搜索与 @提及联想 ==================== */

export interface CommunitySearchHit {
  id: string;
  topicId: number;
  postNumber: number;
  title: string;
  blurb: string;
  username: string;
  avatarTemplate: string;
  createdAt: string;
  categoryId?: number;
  categoryName?: string;
  categoryColorHex?: string;
  likeCount: number;
  postsCount: number;
}

function parseSearchRoot(data: any): {
  posts: CommunitySearchHit[];
  users: CommunitySearchUserHit[];
  tags: CommunitySearchTagHit[];
  hasMore: boolean;
} {
  const topicsMap = new Map<number, any>();
  for (const t of data?.topics ?? []) {
    topicsMap.set(Number(t.id), t);
  }
  const seenKeys = new Set<string>();
  const hits: CommunitySearchHit[] = [];

  for (const p of data?.posts ?? []) {
    const tid = Number(p.topic_id || 0);
    if (!tid) continue;
    const postNum = Number(p.post_number || 1);
    const t = topicsMap.get(tid) || {};
    const catId = t.category_id ? Number(t.category_id) : undefined;
    const catMeta = findCategoryMeta(catId);
    seenKeys.add(`${tid}_${postNum}`);
    seenKeys.add(`${tid}_1`);
    hits.push({
      id: `p_${p.id || `${tid}_${postNum}`}`,
      topicId: tid,
      postNumber: postNum,
      title: stripHtmlTags(String(t.title || t.fancy_title || p.topic_title_headline || `话题 #${tid}`)),
      blurb: stripHtmlTags(String(p.blurb || "")),
      username: String(p.username || "linuxdo"),
      avatarTemplate: String(p.avatar_template || ""),
      createdAt: String(p.created_at || ""),
      categoryId: catId,
      categoryName: catMeta?.displayName || (catId ? CANONICAL_NAME_BY_ID.get(catId) : undefined),
      categoryColorHex: catMeta?.colorHex,
      likeCount: Number(p.like_count || 0),
      postsCount: Number(t.posts_count || 1),
    });
  }

  // Also include title-only matches in `topics` array (from `/search/query.json` or tag/title hits)
  for (const [tid, t] of topicsMap.entries()) {
    if (seenKeys.has(`${tid}_1`)) continue;
    const catId = t.category_id ? Number(t.category_id) : undefined;
    const catMeta = findCategoryMeta(catId);
    hits.push({
      id: `t_${tid}`,
      topicId: tid,
      postNumber: 1,
      title: stripHtmlTags(String(t.title || t.fancy_title || `话题 #${tid}`)),
      blurb: stripHtmlTags(String(t.excerpt || "")),
      username: String(t.last_poster_username || "linuxdo"),
      avatarTemplate: "",
      createdAt: String(t.created_at || t.last_posted_at || ""),
      categoryId: catId,
      categoryName: catMeta?.displayName || (catId ? CANONICAL_NAME_BY_ID.get(catId) : undefined),
      categoryColorHex: catMeta?.colorHex,
      likeCount: Number(t.like_count || 0),
      postsCount: Number(t.posts_count || 1),
    });
  }

  const users: CommunitySearchUserHit[] = Array.isArray(data?.users)
    ? data.users
        .filter((u: any) => Boolean(u?.username))
        .slice(0, 6)
        .map((u: any) => ({
          username: String(u.username),
          name: u.name ? String(u.name) : undefined,
          avatarTemplate: String(u.avatar_template || ""),
          trustLevel: u.trust_level != null ? Number(u.trust_level) : undefined,
        }))
    : [];

  const tags: CommunitySearchTagHit[] = Array.isArray(data?.tags)
    ? data.tags
        .filter((tg: any) => Boolean(tg?.name || tg?.id))
        .slice(0, 8)
        .map((tg: any) => ({
          name: String(tg.name || tg.id),
          topicCount: Number(tg.topic_count || tg.count || 0),
        }))
    : [];

  const hasMore = Boolean(data?.grouped_search_result?.more_full_page_results);
  return { posts: hits, users, tags, hasMore };
}

export function buildDiscourseSearchQuery(
  rawQuery: string,
  order?: string | null,
  filters?: CommunitySearchFilters
): string {
  const parts: string[] = [];
  const cleanQ = rawQuery.trim();
  if (cleanQ) parts.push(cleanQ);
  if (filters?.categorySlug) {
    parts.push(`#${filters.categorySlug.replace(/^#/, "").trim()}`);
  }
  if (filters?.tag) {
    parts.push(`tags:${filters.tag.replace(/^#/, "").trim()}`);
  }
  if (filters?.author) {
    parts.push(`@${filters.author.replace(/^@/, "").trim()}`);
  }
  if (filters?.inTitle) {
    parts.push("in:title");
  }
  if (filters?.solved) {
    parts.push("status:solved");
  }
  if (filters?.afterDays && filters.afterDays > 0) {
    const d = new Date(Date.now() - filters.afterDays * 86_400_000);
    const iso = d.toISOString().slice(0, 10);
    parts.push(`after:${iso}`);
  }
  if (order) {
    parts.push(`order:${order}`);
  }
  return parts.join(" ").trim();
}

export async function searchCommunity(
  query: string,
  page = 1,
  order?: string | null,
  filters?: CommunitySearchFilters
): Promise<{
  posts: CommunitySearchHit[];
  users: CommunitySearchUserHit[];
  tags: CommunitySearchTagHit[];
  hasMore: boolean;
}> {
  const fullQ = buildDiscourseSearchQuery(query, order, filters);
  if (!fullQ) return { posts: [], users: [], tags: [], hasMore: false };
  const data = await getJsonOrThrow(
    `https://linux.do/search.json?q=${encodeURIComponent(fullQ)}&page=${Math.max(1, page)}`
  );
  return parseSearchRoot(data);
}

export async function searchTopicsForLinkPicker(query: string): Promise<CommunitySearchHit[]> {
  const cleanQ = query.trim();
  if (!cleanQ) return [];
  try {
    const quick = await getJsonOrThrow(
      `https://linux.do/search/query.json?term=${encodeURIComponent(cleanQ)}&include_blurbs=true`
    );
    const parsed = parseSearchRoot(quick);
    if (parsed.posts.length > 0) return parsed.posts.slice(0, 12);
  } catch (err) {
    if (err instanceof SiteError && (err.isVerification || err.status === 429)) throw err;
  }
  const full = await searchCommunity(cleanQ, 1);
  return full.posts.slice(0, 12);
}

export async function searchUsersForMention(term: string): Promise<MentionUserCandidate[]> {
  const clean = term.trim().replace(/^@/, "");
  const url = `https://linux.do/u/search/users.json?term=${encodeURIComponent(clean)}&include_groups=true&limit=8`;
  const data = await getJsonOrThrow(url);
  const users: MentionUserCandidate[] = (data?.users ?? []).map((u: any) => ({
    username: String(u.username || ""),
    name: u.name ? String(u.name) : undefined,
    avatarTemplate: String(u.avatar_template || ""),
    isGroup: false,
  }));
  const groups: MentionUserCandidate[] = (data?.groups ?? []).map((g: any) => ({
    username: String(g.name || ""),
    name: g.full_name ? String(g.full_name) : "群组",
    avatarTemplate: "",
    isGroup: true,
  }));
  return [...users, ...groups].filter((x) => Boolean(x.username));
}

/* ==================== 7. 创作台版块可选标签查询 (1:1 对齐 fetchCategoryAllowedTags) ==================== */

export async function fetchCategoryAllowedTags(
  categoryId: number,
  query: string,
  selectedTags: string[]
): Promise<TagChoiceResult> {
  const cleanQ = query.trim().replace(/^#/, "").trim();
  let url = `https://linux.do/tags/filter/search.json?q=${encodeURIComponent(cleanQ)}&filterForInput=true&categoryId=${categoryId}`;
  if (!cleanQ) {
    url += "&prioritizeRecentTags=true";
  }
  for (const t of selectedTags) {
    if (t.trim()) {
      url += `&selected_tags%5B%5D=${encodeURIComponent(t.trim())}`;
    }
  }

  // CRITICAL: Do NOT pass `limit=24`! Discourse raises 400 InvalidParameters if `limit > SiteSetting.max_tag_search_results`.
  let data: any;
  try {
    data = await getJsonOrThrow(url);
  } catch (err) {
    if (err instanceof SiteError && (err.isVerification || err.status === 429 || err.isAuthRequired)) {
      throw err;
    }
    data = await getJsonOrThrow(`${url}&limit=5`);
  }

  const seen = new Set<string>();
  const options: TagChoiceOption[] = [];
  for (const r of data?.results ?? []) {
    if (!r || typeof r !== "object") continue;
    const name = String(
      r.name || r.text || (typeof r.id === "string" ? r.id : "") || ""
    ).trim();
    if (!name || seen.has(name.toLowerCase())) continue;
    seen.add(name.toLowerCase());
    options.push({
      name,
      id: typeof r.id === "number" ? r.id : null,
      count: Number(r.count || 0),
      disabled: Boolean(r.disabled),
      reason: r.title ? String(r.title) : null,
    });
  }

  return {
    options: options.slice(0, 100),
    requiredGroup: data?.required_tag_group?.name ? String(data.required_tag_group.name) : null,
    forbiddenMessage: data?.forbidden_message
      ? String(data.forbidden_message)
      : data?.forbidden
        ? "该版块限制使用此标签"
        : null,
    canCreateTag: siteCanCreateTag,
  };
}

export async function fetchPostRawMarkdown(postId: number): Promise<string> {
  const data = await getJsonOrThrow(`https://linux.do/posts/${postId}.json`);
  return String(data?.raw || "");
}

export async function publishTopic(params: {
  title: string;
  raw: string;
  categoryId: number;
  tags: string[];
  typingDurationMs?: number;
  composerOpenDurationMs?: number;
}): Promise<{ topicId: number; pendingReview: boolean }> {
  const openMs = Math.max(1200, Math.round(params.composerOpenDurationMs ?? 3200));
  const typingMs = Math.max(800, Math.min(openMs, Math.round(params.typingDurationMs ?? 2100)));
  const form: Record<string, string> = {
    title: params.title.trim(),
    raw: params.raw,
    category: String(params.categoryId),
    archetype: "regular",
    nested_post: "true",
    is_warning: "false",
    draft_key: "new_topic",
    typing_duration_msecs: String(typingMs),
    composer_open_duration_msecs: String(openMs),
  };
  params.tags
    .map((t) => t.trim())
    .filter(Boolean)
    .forEach((t, idx) => {
      form[`tags[${idx}]`] = t;
    });
  const res = await mutateJsonOrThrow("https://linux.do/posts.json", "POST", form, {
    "X-LinDo-Referer": "https://linux.do/latest",
  });
  if (res?.action === "enqueued" || res?.pending_post) {
    return { topicId: 0, pendingReview: true };
  }
  return {
    topicId: Number(res?.topic_id || res?.post?.topic_id || 0),
    pendingReview: false,
  };
}

export async function publishReply(params: {
  topicId: number;
  raw: string;
  replyToPostNumber?: number;
  typingDurationMs?: number;
  composerOpenDurationMs?: number;
}): Promise<{ post: PostItemModel | null; pendingReview: boolean }> {
  const openMs = Math.max(1000, Math.round(params.composerOpenDurationMs ?? 2800));
  const typingMs = Math.max(600, Math.min(openMs, Math.round(params.typingDurationMs ?? 1800)));
  const form: Record<string, string> = {
    topic_id: String(params.topicId),
    raw: params.raw,
    archetype: "regular",
    nested_post: "true",
    is_warning: "false",
    draft_key: `topic_${params.topicId}`,
    typing_duration_msecs: String(typingMs),
    composer_open_duration_msecs: String(openMs),
  };
  if (params.replyToPostNumber && params.replyToPostNumber > 0) {
    form.reply_to_post_number = String(params.replyToPostNumber);
  }
  const res = await mutateJsonOrThrow("https://linux.do/posts.json", "POST", form, {
    "X-LinDo-Referer": `https://linux.do/t/topic/${params.topicId}`,
  });
  if (res?.action === "enqueued" || res?.pending_post) {
    return { post: null, pendingReview: true };
  }
  const postObj = res?.post || res;
  return {
    post: postObj?.id ? parsePostModel(postObj, params.topicId) : null,
    pendingReview: false,
  };
}

export async function updateExistingPost(params: {
  postId: number;
  topicId: number;
  raw: string;
  title?: string;
  categoryId?: number;
  tags?: string[];
  editReason?: string;
}): Promise<PostItemModel | null> {
  const topicReferer = { "X-LinDo-Referer": `https://linux.do/t/topic/${params.topicId}` };
  if (params.title && params.topicId) {
    const metaForm: Record<string, string> = {
      title: params.title.trim(),
    };
    if (params.categoryId) metaForm.category_id = String(params.categoryId);
    (params.tags ?? []).forEach((t, idx) => {
      metaForm[`tags[${idx}]`] = t;
    });
    await mutateJsonOrThrow(
      `https://linux.do/t/-/${params.topicId}.json`,
      "PUT",
      metaForm,
      topicReferer
    );
  }
  const postForm: Record<string, string> = { "post[raw]": params.raw };
  if (params.editReason && params.editReason.trim()) {
    postForm["post[edit_reason]"] = params.editReason.trim();
  }
  const res = await mutateJsonOrThrow(
    `https://linux.do/posts/${params.postId}.json`,
    "PUT",
    postForm,
    topicReferer
  );
  const postObj = res?.post || res;
  return postObj?.id ? parsePostModel(postObj, params.topicId) : null;
}

/* ==================== 8. 私信中心、云端草稿、关注与粉丝、徽章与个人资料 ==================== */

export async function fetchPrivateMessages(
  username: string,
  box: "inbox" | "sent" | "archive" = "inbox",
  page = 0
): Promise<TopicSummary[]> {
  const segment =
    box === "sent"
      ? "private-messages-sent"
      : box === "archive"
        ? "private-messages-archive"
        : "private-messages";
  const path = `https://linux.do/topics/${segment}/${encodeURIComponent(username)}.json?page=${page}`;
  const data = await getJsonOrThrow(path);
  const rawTopics: any[] = data?.topic_list?.topics ?? [];
  const usersMap = new Map<number, any>();
  for (const u of data?.users ?? []) {
    usersMap.set(Number(u.id), u);
  }
  return rawTopics.map((t) => {
    const p0 = t.posters?.[0];
    const u = (p0 && usersMap.get(Number(p0.user_id))) || {};
    return {
      id: Number(t.id),
      title: String(t.title || ""),
      slug: String(t.slug || "pm"),
      postsCount: Number(t.posts_count || 1),
      replyCount: Math.max(0, Number(t.posts_count || 1) - 1),
      views: Number(t.views || 0),
      likeCount: Number(t.like_count || 0),
      highestPostNumber: Number(t.highest_post_number || 1),
      unreadPosts: Number(t.unread_posts || 0),
      unseen: Boolean(t.unseen),
      pinned: false,
      closed: Boolean(t.closed),
      archived: Boolean(t.archived),
      bookmarked: Boolean(t.bookmarked),
      tags: [],
      createdAt: String(t.created_at || ""),
      lastPostedAt: String(t.last_posted_at || t.created_at || ""),
      originalPoster: {
        userId: Number(u.id || 0),
        username: String(u.username || username),
        name: u.name ? String(u.name) : undefined,
        avatarTemplate: String(u.avatar_template || ""),
        animatedAvatar: u.animated_avatar ? String(u.animated_avatar) : null,
      },
    };
  });
}

export async function archivePrivateMessage(topicId: number, archived = true): Promise<void> {
  const route = archived ? "archive-message" : "move-to-inbox";
  await mutateJsonOrThrow(`https://linux.do/t/${topicId}/${route}.json`, "PUT", {});
}

export async function sendPrivateMessage(params: {
  recipients: string;
  title: string;
  raw: string;
}): Promise<number> {
  const targetRecipients = params.recipients
    .split(/[,，\s]+/)
    .map((s) => s.replace(/^@/, "").trim())
    .filter(Boolean)
    .join(",");
  const res = await mutateJsonOrThrow("https://linux.do/posts.json", "POST", {
    title: params.title.trim(),
    raw: params.raw,
    target_recipients: targetRecipients,
    archetype: "private_message",
  });
  return Number(res?.topic_id || res?.post?.topic_id || 0);
}

export async function fetchCloudDrafts(): Promise<CloudDraftItem[]> {
  const data = await getJsonOrThrow("https://linux.do/drafts.json");
  const arr: any[] = Array.isArray(data?.drafts) ? data.drafts : [];
  return arr
    .map((obj): CloudDraftItem | null => {
      const draftKey = String(obj?.draft_key || "");
      if (!draftKey) return null;
      let dObj: any = {};
      if (obj.data && typeof obj.data === "object") {
        dObj = obj.data;
      } else if (typeof obj.data === "string" && obj.data.trim().startsWith("{")) {
        dObj = safeJson(obj.data) || {};
      }
      const actionStr = String(dObj.action || "");
      const action: "NEW_TOPIC" | "REPLY" | "EDIT" =
        actionStr === "edit" || draftKey.startsWith("edit")
          ? "EDIT"
          : obj.topic_id || draftKey.startsWith("topic_")
            ? "REPLY"
            : "NEW_TOPIC";
      return {
        draftKey,
        sequence: Number(obj.sequence || 0),
        action,
        topicId: obj.topic_id ? Number(obj.topic_id) : dObj.topic_id ? Number(dObj.topic_id) : null,
        postId: dObj.postId ? Number(dObj.postId) : null,
        replyToPostNumber: dObj.replyToPostId ? Number(dObj.replyToPostId) : null,
        categoryId: obj.category_id
          ? Number(obj.category_id)
          : dObj.categoryId
            ? Number(dObj.categoryId)
            : null,
        title: String(dObj.title || obj.title || ""),
        raw: String(dObj.reply || dObj.raw || ""),
        tags: Array.isArray(dObj.tags) ? dObj.tags.map((t: any) => String(t)) : [],
        createdAt: obj.created_at ? String(obj.created_at) : undefined,
      };
    })
    .filter((x): x is CloudDraftItem => Boolean(x));
}

export async function saveCloudDraft(params: {
  draftKey: string;
  sequence: number;
  data: Record<string, any>;
}): Promise<number> {
  const res = await mutateJsonOrThrow(
    "https://linux.do/drafts.json",
    "POST",
    {
      draft_key: params.draftKey,
      sequence: String(Math.max(0, params.sequence)),
      data: JSON.stringify(params.data),
    },
    { "Discourse-Background": "true" }
  );
  const nextSeq = Number(res?.draft_sequence ?? params.sequence);
  return Number.isFinite(nextSeq) ? nextSeq : params.sequence;
}

export async function deleteCloudDraft(draftKey: string, sequence = 0): Promise<void> {
  await mutateJsonOrThrow(
    `https://linux.do/drafts/${encodeURIComponent(draftKey)}.json?sequence=${sequence}`,
    "DELETE"
  );
}

export async function fetchUserCardDetail(username: string): Promise<any> {
  return getJsonOrThrow(`https://linux.do/u/${encodeURIComponent(username)}/card.json`);
}

export async function toggleFollowUser(username: string, follow: boolean): Promise<void> {
  await mutateJsonOrThrow(
    `https://linux.do/follow/${encodeURIComponent(username)}.json`,
    follow ? "PUT" : "DELETE"
  );
}

export async function fetchUserFollowList(
  username: string,
  followers = false
): Promise<FollowUserEntry[]> {
  const kind = followers ? "followers" : "following";
  const data = await getJsonOrThrow(
    `https://linux.do/u/${encodeURIComponent(username)}/follow/${kind}.json`
  );
  const arr: any[] = Array.isArray(data)
    ? data
    : data?.users ?? data?.[kind] ?? data?.follow ?? [];
  return arr
    .map((u: any): FollowUserEntry | null => {
      const uname = String(u?.username || "").trim();
      if (!uname) return null;
      return {
        username: uname,
        displayName: String(u?.name || uname),
        avatarTemplate: String(u?.avatar_template || ""),
        animatedAvatar: u?.animated_avatar ? String(u.animated_avatar) : null,
        bio: u?.bio_excerpt ? stripHtmlTags(String(u.bio_excerpt)) : undefined,
        isFollowing: followers ? Boolean(u?.is_followed ?? u?.following) : true,
      };
    })
    .filter((x): x is FollowUserEntry => Boolean(x));
}

export async function fetchUserBookmarks(username: string): Promise<any[]> {
  const data = await getJsonOrThrow(`https://linux.do/u/${encodeURIComponent(username)}/bookmarks.json`);
  return data?.user_bookmark_list?.bookmarks ?? [];
}

export async function fetchUserActions(username: string, filter = "1,4,5", offset = 0): Promise<any[]> {
  const filterQs = filter ? `&filter=${encodeURIComponent(filter)}` : "";
  const data = await getJsonOrThrow(
    `https://linux.do/user_actions.json?offset=${Math.max(0, offset)}&username=${encodeURIComponent(username)}${filterQs}`
  );
  return data?.user_actions ?? [];
}

export async function fetchUserBadges(username: string): Promise<any[]> {
  const data = await getJsonOrThrow(
    `https://linux.do/user-badges/${encodeURIComponent(username)}.json?grouped=true`
  );
  readBadgeDefinitionsAny(data?.badges);
  const badgeDefs = new Map<number, any>();
  for (const b of data?.badges ?? []) {
    badgeDefs.set(Number(b.id), b);
  }
  const userBadges: any[] = data?.user_badges ?? [];
  if (userBadges.length > 0 && badgeDefs.size > 0) {
    const seen = new Set<number>();
    const merged: any[] = [];
    for (const ub of userBadges) {
      const def = badgeDefs.get(Number(ub.badge_id));
      if (def && !seen.has(Number(def.id))) {
        seen.add(Number(def.id));
        merged.push({
          ...def,
          granted_at: ub.granted_at,
          count: ub.count || 1,
          user_badge_id: ub.id,
        });
      }
    }
    if (merged.length > 0) return merged;
  }
  return data?.badges ?? userBadges;
}

export async function fetchSiteBadges(): Promise<any[]> {
  const data = await getJsonOrThrow("https://linux.do/badges.json");
  readBadgeDefinitionsAny(data?.badges);
  return data?.badges ?? [];
}

export async function fetchUserAccountPreferences(
  username: string
): Promise<UserAccountPreferencesSnapshot> {
  const encoded = encodeURIComponent(username);
  const [userRoot, badgesList] = await Promise.all([
    getJsonOrThrow(`https://linux.do/u/${encoded}.json`),
    fetchUserBadges(username).catch(() => []),
  ]);
  const u = userRoot?.user || {};
  const uOpt = u.user_option || {};
  const badgeTitles = badgesList
    .filter((b: any) => Boolean(b?.allow_title))
    .map((b: any) => String(b.name || "").trim())
    .filter(Boolean);
  const groupTitles = (u.groups ?? [])
    .map((g: any) => String(g?.title || "").trim())
    .filter(Boolean);
  const currentTitle = String(u.title || "").trim();
  const availableTitles = Array.from(
    new Set([...badgeTitles, ...groupTitles, ...(currentTitle ? [currentTitle] : [])])
  );

  return {
    username: String(u.username || username),
    displayName: String(u.name || ""),
    email: String(u.email || ""),
    bioRaw: String(u.bio_raw || ""),
    title: currentTitle,
    location: String(u.location || ""),
    website: String(u.website || ""),
    trustLevel: Number(u.trust_level ?? 1),
    createdAtText: String(u.created_at || "").slice(0, 10),
    hideProfileAndPresence: Boolean(uOpt.hide_profile_and_presence),
    allowPrivateMessages: uOpt.allow_private_messages !== false,
    availableTitles,
  };
}

export async function updateUserProfileInfo(
  username: string,
  params: {
    displayName?: string;
    bioRaw?: string;
    title?: string;
    location?: string;
    website?: string;
    hidePresence?: boolean;
    allowPrivateMessages?: boolean;
  }
): Promise<void> {
  const form: Record<string, string> = {};
  if (params.displayName !== undefined) form.name = params.displayName.trim();
  if (params.bioRaw !== undefined) form.bio_raw = params.bioRaw;
  if (params.title !== undefined) form.title = params.title.trim();
  if (params.location !== undefined) form.location = params.location.trim();
  if (params.website !== undefined) form.website = params.website.trim();
  if (params.hidePresence !== undefined) {
    form.hide_profile_and_presence = String(params.hidePresence);
  }
  if (params.allowPrivateMessages !== undefined) {
    form.allow_private_messages = String(params.allowPrivateMessages);
  }
  await mutateJsonOrThrow(`https://linux.do/u/${encodeURIComponent(username)}.json`, "PUT", form);
}

/* ==================== 6b. Connect 保级与成长原生解析引擎 (1:1 对齐 ConnectStatsParser) ==================== */

function parseCssCustomPropInt(styleAttr: string | null | undefined, varName: string): number {
  if (!styleAttr) return 0;
  const m = styleAttr.match(new RegExp(`${varName}\\s*:\\s*([0-9.]+)`));
  return m ? Math.round(Number(m[1]) || 0) : 0;
}

function parseFirstIntInText(raw: string | null | undefined): number {
  if (!raw) return 0;
  const cleaned = raw.replace(/[,，]/g, "").trim();
  const m = cleaned.match(/-?\d+/);
  return m ? Number(m[0]) || 0 : 0;
}

export function parseConnectHtml(htmlContent: string): ConnectTrustLevelReport | null {
  if (
    !htmlContent ||
    (!htmlContent.includes("tl3-ring") &&
      !htmlContent.includes("tl3-bar-item") &&
      !htmlContent.includes("card-title") &&
      !/<table/i.test(htmlContent))
  ) {
    return null;
  }

  const doc = new DOMParser().parseFromString(htmlContent, "text/html");
  const titleEl = doc.querySelector("h2.card-title, .card-title");
  let title = (titleEl?.textContent || "").trim();
  if (!title) {
    const headingMatch = Array.from(doc.querySelectorAll("h1, h2, h3")).find((h) =>
      (h.textContent || "").includes("信任等级")
    );
    title = (headingMatch?.textContent || "").trim() || "信任等级 3 要求";
  }

  const subEl = doc.querySelector(".card-subtitle");
  const subtitle = (subEl?.textContent || "").trim() || "过去 100 天内社区活跃度指标";
  const periodMatch = subtitle.match(/(\d+)\s*(?:天|days)/i);
  const periodDays = periodMatch ? Number(periodMatch[1]) || 100 : 100;

  const badgeEl = doc.querySelector("span[class*='badge'], .badge");
  const badgeClasses = badgeEl?.getAttribute("class") || "";
  const badgeText =
    (badgeEl?.textContent || "").trim() ||
    (htmlContent.includes("符合信任等级") ? "符合信任等级 3 要求" : "Connect 实时数据");

  const metrics: ConnectMetricItem[] = [];
  const makeItem = (
    label: string,
    cur: number,
    target: number,
    dispCur: string,
    dispTarget: string,
    isMet: boolean
  ): ConnectMetricItem => ({
    label,
    currentValue: cur,
    targetValue: target,
    displayCurrent: dispCur,
    displayTarget: dispTarget,
    isMet,
    progress: target > 0 ? Math.max(0, Math.min(1, cur / target)) : isMet ? 1 : 0,
  });

  // 1. 环形指标 (.tl3-ring)
  doc.querySelectorAll(".tl3-ring").forEach((ring) => {
    const label = (ring.querySelector(".tl3-ring-label")?.textContent || "").trim();
    const circle = ring.querySelector(".tl3-ring-circle");
    const style = circle?.getAttribute("style") || "";
    const cur = parseCssCustomPropInt(style, "--val");
    const max = Math.max(1, parseCssCustomPropInt(style, "--max"));
    const isMet = Boolean(circle?.classList.contains("met")) || cur >= max;
    if (label) {
      metrics.push(makeItem(label, cur, max, String(cur), String(max), isMet));
    }
  });

  // 2. 条形指标 (.tl3-bar-item)
  doc.querySelectorAll(".tl3-bar-item").forEach((bar) => {
    const label = (bar.querySelector(".tl3-bar-label")?.textContent || "").trim();
    const nums = (bar.querySelector(".tl3-bar-nums")?.textContent || "").trim();
    const fill = bar.querySelector(".tl3-bar-fill");
    const style = fill?.getAttribute("style") || "";
    const cssVal = parseCssCustomPropInt(style, "--val");
    const cssMax = Math.max(1, parseCssCustomPropInt(style, "--max"));
    const parsedNum = parseFirstIntInText(nums);
    const cur = parsedNum > 0 ? parsedNum : cssVal;
    const isMet = Boolean(fill?.classList.contains("met")) || cur >= cssMax;
    if (label) {
      metrics.push(makeItem(label, cur, cssMax, String(cur), String(cssMax), isMet));
    }
  });

  // 3. 举报/违规上限配额 (.tl3-quota-card)
  doc.querySelectorAll(".tl3-quota-card").forEach((card) => {
    const label = (card.querySelector(".tl3-quota-label")?.textContent || "").trim();
    const nums = (card.querySelector(".tl3-quota-nums")?.textContent || "").trim();
    const m = nums.match(/(\d+)\s*\/\s*(\d+)/);
    if (label && m) {
      const cur = Number(m[1]) || 0;
      const limit = Number(m[2]) || 0;
      metrics.push({
        label,
        currentValue: cur,
        targetValue: limit,
        displayCurrent: String(cur),
        displayTarget: String(limit),
        isMet: cur <= limit,
        progress: limit > 0 ? Math.max(0, Math.min(1, cur / limit)) : 0,
      });
    }
  });

  // 4. 经典表格布局兜底 (<table><tr><td>)
  if (metrics.length === 0) {
    doc.querySelectorAll("tr").forEach((tr) => {
      const tds = Array.from(tr.querySelectorAll("td"));
      if (tds.length >= 3) {
        const rawLabel = (tds[0].textContent || "").trim();
        const rawCur = (tds[1].textContent || "").trim();
        const rawTarget = (tds[2].textContent || "").trim();
        const curVal = parseFirstIntInText(rawCur);
        const targetVal = parseFirstIntInText(rawTarget);
        if (
          rawLabel &&
          targetVal > 0 &&
          !rawLabel.includes("项目") &&
          !rawLabel.includes("禁言") &&
          !rawLabel.includes("封禁")
        ) {
          const td1Html = tds[1].outerHTML.toLowerCase();
          const trHtml = tr.outerHTML.toLowerCase();
          const isUnmet = td1Html.includes("red") || trHtml.includes("unmet");
          const isMet = td1Html.includes("green") ? true : isUnmet ? false : curVal >= targetVal;
          metrics.push(
            makeItem(
              rawLabel,
              curVal,
              targetVal,
              rawCur || String(curVal),
              rawTarget || String(targetVal),
              isMet
            )
          );
        }
      }
    });
  }

  if (metrics.length === 0) return null;

  const lowerBadge = `${badgeClasses} ${badgeText}`.toLowerCase();
  const explicitlyUnqualified =
    lowerBadge.includes("不符合") ||
    lowerBadge.includes("未达标") ||
    lowerBadge.includes("danger") ||
    lowerBadge.includes("unmet") ||
    lowerBadge.includes("fail");
  const explicitlyQualified =
    !explicitlyUnqualified &&
    (lowerBadge.includes("符合") ||
      lowerBadge.includes("已达标") ||
      lowerBadge.includes("success") ||
      lowerBadge.includes("met"));

  return {
    title,
    subtitle,
    statusBadgeText: badgeText,
    isQualified: explicitlyQualified
      ? true
      : explicitlyUnqualified
        ? false
        : metrics.every((m) => m.isMet),
    timePeriodDays: periodDays,
    metrics,
    isLiveConnectData: true,
  };
}

export async function fetchConnectTrustLevelReport(
  username: string
): Promise<ConnectTrustLevelReport> {
  // 1. 优先通过共享浏览器 Cookie 的离屏 WebView 探针读取 connect.linux.do 实时 100 天滚动周期数据（零跨 TLS 栈污染）
  try {
    const html = await invoke<string>("fetch_connect_page_html");
    if (html) {
      const live = parseConnectHtml(html);
      if (live) return live;
    }
  } catch {
    // 若用户尚未在 connect.linux.do 完成首次 OAuth 授权或为 Lv1/Lv2 无 TL3 表格，平滑降级到真实个人统计档案
  }

  // 2. 降级拉取 `/u/{username}.json` + `/u/{username}/summary.json` 真实个人活跃度数据
  const encoded = encodeURIComponent(username);
  const [uRoot, sumRoot] = await Promise.all([
    getJsonOrThrow(`https://linux.do/u/${encoded}.json`),
    getJsonOrThrow(`https://linux.do/u/${encoded}/summary.json`).catch(() => null),
  ]);
  const u = uRoot?.user || {};
  const us = sumRoot?.user_summary || {};
  const trustLevel = Number(u.trust_level ?? 1);
  const daysVisited = Number(us.days_visited ?? 0);
  const topicsEntered = Number(us.topics_entered ?? 0);
  const postsRead = Number(us.posts_read_count ?? 0);
  const topicCount = Number(us.topic_count ?? 0);
  const postCount = Number(us.post_count ?? 0);
  const likesGiven = Number(us.likes_given ?? 0);
  const likesReceived = Number(us.likes_received ?? 0);
  const readMinutes = Math.round(Number(us.time_read ?? 0) / 60);

  const mk = (label: string, val: number, suffix = ""): ConnectMetricItem => ({
    label,
    currentValue: val,
    targetValue: 0,
    displayCurrent: `${val.toLocaleString()}${suffix}`,
    displayTarget: "--",
    isMet: false,
    progress: 0,
  });

  return {
    title: `Lv.${trustLevel} · @${u.username || username}`,
    subtitle:
      "当前展示社区累计真实活跃统计（若需开启 100 天保级滚动面板，可点击右上角前往网页版完成首次 Connect 授权）",
    statusBadgeText: "累计活跃统计",
    isQualified: trustLevel >= 3,
    timePeriodDays: 0,
    isLiveConnectData: false,
    metrics: [
      mk("累计访问天数", daysVisited, " 天"),
      mk("累计浏览话题", topicsEntered),
      mk("累计已读帖子", postsRead),
      mk("累计创建话题", topicCount),
      mk("累计发表回复", postCount),
      mk("累计送出赞同", likesGiven),
      mk("累计收到赞同", likesReceived),
      mk("累计阅读时长", readMinutes, " 分钟"),
    ],
  };
}

function stripHtmlTags(s: string): string {
  return stripHtmlEntities(s.replace(/<[^>]*>/g, " ").replace(/\s+/g, " ").trim());
}

function stripHtmlEntities(s: string): string {
  return s
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&hellip;/g, "…")
    .replace(/&nbsp;/g, " ");
}

/* ==================== 7. 浏览历史（服务端 /read.json + 本地富元数据缓存）与客户端静默更新 ==================== */

const LOCAL_HISTORY_STORAGE_KEY = "lindo.reading_history.v1";

export function getLocalTopicHistory(): LocalHistoryItem[] {
  try {
    const raw = localStorage.getItem(LOCAL_HISTORY_STORAGE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter(
      (x: any) =>
        x &&
        Number(x.topicId) > 0 &&
        typeof x.title === "string" &&
        x.title.trim() &&
        !/^话题\s*#\d+$/.test(x.title.trim())
    );
  } catch {
    return [];
  }
}

export function recordLocalTopicVisit(item: {
  topicId: number;
  title: string;
  categoryId?: number;
  categoryName?: string;
  categoryColorHex?: string;
  authorUsername?: string;
  authorName?: string;
  authorAvatar?: string;
  replyCount?: number;
  views?: number;
}): void {
  const cleanTitle = (item.title || "").trim();
  if (!item.topicId || !cleanTitle || /^话题\s*#\d+$/.test(cleanTitle)) return;
  try {
    const prev = getLocalTopicHistory();
    const existing = prev.find((x) => x.topicId === item.topicId);
    const entry: LocalHistoryItem = {
      topicId: item.topicId,
      title: cleanTitle,
      categoryId: item.categoryId ?? existing?.categoryId,
      categoryName: item.categoryName || existing?.categoryName,
      categoryColorHex: item.categoryColorHex || existing?.categoryColorHex,
      authorUsername:
        item.authorUsername && item.authorUsername !== "linuxdo"
          ? item.authorUsername
          : existing?.authorUsername || "linuxdo",
      authorName: item.authorName || existing?.authorName,
      authorAvatar: item.authorAvatar || existing?.authorAvatar || "",
      replyCount: item.replyCount ?? existing?.replyCount ?? 0,
      views: item.views ?? existing?.views ?? 0,
      visitedAt: new Date().toISOString(),
    };
    const next = [entry, ...prev.filter((x) => x.topicId !== item.topicId)].slice(0, 120);
    localStorage.setItem(LOCAL_HISTORY_STORAGE_KEY, JSON.stringify(next));
  } catch {
    // ignore storage quota errors
  }
}

export function clearLocalTopicHistory(): void {
  try {
    localStorage.removeItem(LOCAL_HISTORY_STORAGE_KEY);
  } catch {
    // ignore
  }
}

export async function fetchReadHistoryTopics(
  page = 0
): Promise<{ topics: TopicSummary[]; hasMore: boolean }> {
  const url =
    page > 0 ? `https://linux.do/read.json?page=${page}` : "https://linux.do/read.json";
  const data = await getJsonOrThrow(url);
  const usersMap = new Map<number, any>();
  for (const u of data?.users ?? []) {
    usersMap.set(Number(u.id), u);
  }
  const rawTopics: any[] = data?.topic_list?.topics ?? [];
  const topics = rawTopics.map((t) => mapTopicListEntry(t, usersMap));
  const moreUrl = data?.topic_list?.more_topics_url;
  return {
    topics,
    hasMore: Boolean(moreUrl) && topics.length >= 20,
  };
}

export async function checkAppUpdateInRust(): Promise<DesktopUpdateInfo> {
  return invoke<DesktopUpdateInfo>("check_app_update");
}

export async function downloadAndInstallUpdateInRust(info: DesktopUpdateInfo): Promise<string> {
  return invoke<string>("download_and_install_update", {
    zipUrl: info.zipUrl,
    dmgUrl: info.dmgUrl,
    expectedSha256: info.sha256,
  });
}

export async function requestUserArchive(): Promise<void> {
  const res = await siteRequest("https://linux.do/export_csv/export_entity.json", "POST", {
    entity: "user_archive",
  });
  if (res.isChallenge) {
    throw new SiteError("需要完成站点安全验证", { isVerification: true, status: res.status });
  }
  if (res.isUnauthenticated) {
    throw new SiteError("登录状态已过期，请重新登录", { isAuthRequired: true, status: res.status });
  }
  let parsed: any = null;
  try {
    parsed = res.body ? JSON.parse(res.body) : null;
  } catch {}
  const errs = Array.isArray(parsed?.errors)
    ? parsed.errors.filter(Boolean).join("；")
    : typeof parsed?.errors === "string"
      ? parsed.errors
      : typeof parsed?.error === "string"
        ? parsed.error
        : "";
  if (errs) {
    throw new SiteError(errs, { status: res.status });
  }
  if (res.status < 200 || res.status >= 300) {
    throw new SiteError(`导出申请未成功 (HTTP ${res.status})`, { status: res.status });
  }
}

/**
 * Surgical window drag handler ONLY for narrow top header strips (`<=52px`).
 * Never uses `-webkit-app-region: drag` and only initiates native window drag after `>= 5px` intentional pointer movement,
 * so screenshot overlays (`⌘⇧4` / Snipaste / Shottr / WeChat / Feishu) and clicks/drags across the app are NEVER interrupted!
 */
export function handleTitlebarMouseDown(e: React.MouseEvent): void {
  if (e.button !== 0) return;
  const target = e.target as HTMLElement | null;
  if (!target) return;
  if (
    target.closest(
      "button, a, input, textarea, select, [role='button'], .lindo-no-drag, .lindo-selectable, .lindo-cooked"
    )
  ) {
    return;
  }
  if (e.detail === 2) {
    getCurrentWindow().toggleMaximize().catch(() => {});
    return;
  }
  const startX = e.screenX;
  const startY = e.screenY;
  let started = false;

  const cleanup = () => {
    window.removeEventListener("mousemove", onMove, true);
    window.removeEventListener("mouseup", cleanup, true);
    window.removeEventListener("blur", cleanup, true);
  };

  const onMove = (ev: MouseEvent) => {
    if (started) return;
    if ((ev.buttons & 1) === 0) {
      cleanup();
      return;
    }
    const dx = ev.screenX - startX;
    const dy = ev.screenY - startY;
    if (dx * dx + dy * dy >= 25) {
      started = true;
      cleanup();
      getCurrentWindow().startDragging().catch(() => {});
    }
  };

  window.addEventListener("mousemove", onMove, true);
  window.addEventListener("mouseup", cleanup, true);
  window.addEventListener("blur", cleanup, true);
}
