use crate::legacy::{
    clear_pending_rsa, load_pending_rsa, save_pending_rsa, save_state, PendingRsaDiskRecord,
    PersistedState, SavedAccountEntry, UserProfile,
};
use crate::net_policy::{
    is_transient_retryable_status, parse_retry_after_secs, transient_backoff_ms,
    AdaptiveTokenBucket, AttemptBudget, CfClearanceAuthority, PriorityGate, RequestPriority,
    VerifyCoordinator, VerifyFlightGuard, VerifyJoin, VerifyOutcome, VerifyPresentation,
};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::{HashMap, VecDeque};
#[cfg(target_os = "macos")]
use std::ffi::{CStr, CString};
#[cfg(target_os = "macos")]
use std::os::raw::c_char;
#[cfg(target_os = "macos")]
use std::sync::atomic::AtomicI64;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicU8, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex as StdMutex, OnceLock};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tauri::{AppHandle, Emitter, Manager};
#[cfg(target_os = "macos")]
use tokio::sync::oneshot;
use tokio::sync::{Mutex, Notify, RwLock, Semaphore};

const CF_CHALLENGE_PREFIX: &str = "<!--lindo-cf-challenge-->";
const MAX_MEDIA_CACHE_ITEMS: usize = 192;
const MAX_MEDIA_CACHE_SINGLE_BYTES: usize = 4 * 1024 * 1024;
const MAX_MEDIA_CACHE_TOTAL_BYTES: usize = 36 * 1024 * 1024;

pub fn now_epoch_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ForumMediaPayload {
    pub status: u16,
    pub content_type: String,
    pub data_base64: String,
}

struct MediaCacheState {
    entries: HashMap<String, (ForumMediaPayload, usize)>,
    order: VecDeque<String>,
    total_bytes: usize,
}

impl Default for MediaCacheState {
    fn default() -> Self {
        Self {
            entries: HashMap::new(),
            order: VecDeque::new(),
            total_bytes: 0,
        }
    }
}

impl MediaCacheState {
    fn get(&mut self, url: &str) -> Option<ForumMediaPayload> {
        let payload = self.entries.get(url)?.0.clone();
        if let Some(pos) = self.order.iter().position(|k| k == url) {
            self.order.remove(pos);
            self.order.push_back(url.to_string());
        }
        Some(payload)
    }

    fn insert(&mut self, url: String, payload: ForumMediaPayload) {
        let approx_bytes = payload.data_base64.len();
        if approx_bytes == 0 || approx_bytes > MAX_MEDIA_CACHE_SINGLE_BYTES {
            return;
        }
        if let Some((_, old_len)) = self.entries.remove(&url) {
            self.total_bytes = self.total_bytes.saturating_sub(old_len);
            if let Some(pos) = self.order.iter().position(|k| k == &url) {
                self.order.remove(pos);
            }
        }
        self.total_bytes += approx_bytes;
        self.order.push_back(url.clone());
        self.entries.insert(url, (payload, approx_bytes));

        while self.entries.len() > MAX_MEDIA_CACHE_ITEMS
            || self.total_bytes > MAX_MEDIA_CACHE_TOTAL_BYTES
        {
            let Some(oldest) = self.order.pop_front() else {
                break;
            };
            if let Some((_, len)) = self.entries.remove(&oldest) {
                self.total_bytes = self.total_bytes.saturating_sub(len);
            }
        }
    }
}

#[cfg(target_os = "macos")]
extern "C" {
    fn lindo_bridge_command(
        request: i64,
        raw: *const c_char,
        callback: extern "C" fn(i64, *const c_char),
    );
    fn lindo_bridge_set_event_callback(callback: extern "C" fn(i64, *const c_char));
    fn lindo_bridge_enable_high_refresh(js_ready: i32);
}

pub fn enable_high_refresh_and_smooth_wheel(js_ready: bool) {
    #[cfg(target_os = "macos")]
    unsafe {
        lindo_bridge_enable_high_refresh(if js_ready { 1 } else { 0 });
    }
    #[cfg(not(target_os = "macos"))]
    {
        let _ = js_ready;
    }
}

#[cfg(target_os = "macos")]
type PendingMap = StdMutex<HashMap<i64, oneshot::Sender<Result<Value, String>>>>;
#[cfg(target_os = "macos")]
static NEXT_REQ_ID: AtomicI64 = AtomicI64::new(1);
#[cfg(target_os = "macos")]
static PENDING_CALLS: OnceLock<PendingMap> = OnceLock::new();
static GLOBAL_APP_HANDLE: OnceLock<AppHandle> = OnceLock::new();

#[cfg(target_os = "macos")]
fn pending_map() -> &'static PendingMap {
    PENDING_CALLS.get_or_init(|| StdMutex::new(HashMap::new()))
}

#[cfg(target_os = "macos")]
extern "C" fn on_native_bridge_reply(request: i64, raw: *const c_char) {
    let text = if raw.is_null() {
        "{}".to_string()
    } else {
        unsafe { CStr::from_ptr(raw).to_string_lossy().into_owned() }
    };
    let parsed: Value = serde_json::from_str(&text).unwrap_or_else(|_| json!({"error": "Invalid JSON"}));
    if request == 0 {
        if let Some(redirect) = parsed
            .get("value")
            .and_then(|v| v.get("redirect"))
            .and_then(|v| v.as_str())
            .map(|s| s.to_string())
        {
            if let Some(app) = GLOBAL_APP_HANDLE.get().cloned() {
                tauri::async_runtime::spawn(async move {
                    if let Some(engine) = app.try_state::<Arc<BridgeEngine>>() {
                        let _ = engine.handle_auth_redirect(&app, &redirect).await;
                    }
                });
            }
        } else if parsed
            .get("value")
            .and_then(|v| v.get("browserAuth"))
            .and_then(|v| v.as_bool())
            == Some(true)
        {
            if let Some(app) = GLOBAL_APP_HANDLE.get().cloned() {
                tauri::async_runtime::spawn(async move {
                    if let Some(engine) = app.try_state::<Arc<BridgeEngine>>() {
                        if let Ok(url) = engine.start_browser_one_click_auth().await {
                            use tauri_plugin_opener::OpenerExt;
                            let _ = app.opener().open_url(&url, None::<&str>);
                            let _ = app.emit(
                                "lindo://auth-notice",
                                "已在默认浏览器打开授权页，点击「授权」并在浏览器弹窗确认「打开 Lindo」即可完成登录",
                            );
                            let _ = app.emit(
                                "lindo://diag-event",
                                json!({
                                    "level": "INFO",
                                    "category": "AUTH",
                                    "tag": "AuthPending",
                                    "summary": "已切换至默认浏览器授权页，等待点击「授权」返回"
                                }),
                            );
                        }
                    }
                });
            }
        }
        return;
    }
    let sender = pending_map().lock().ok().and_then(|mut m| m.remove(&request));
    if let Some(tx) = sender {
        if let Some(err) = parsed.get("error").and_then(|e| e.as_str()) {
            let _ = tx.send(Err(err.to_string()));
        } else {
            let _ = tx.send(Ok(parsed.get("value").cloned().unwrap_or(Value::Null)));
        }
    }
}

#[cfg(target_os = "macos")]
async fn native_call(method: &str, params: Value, timeout_secs: u64) -> Result<Value, String> {
    let id = NEXT_REQ_ID.fetch_add(1, Ordering::Relaxed);
    let (tx, rx) = oneshot::channel();
    pending_map()
        .lock()
        .map_err(|_| "Bridge mutex poisoned".to_string())?
        .insert(id, tx);
    let payload = json!({ "method": method, "params": params }).to_string();
    let c_payload = CString::new(payload).map_err(|_| "NUL byte in command".to_string())?;
    unsafe {
        lindo_bridge_command(id, c_payload.as_ptr(), on_native_bridge_reply);
    }
    match tokio::time::timeout(Duration::from_secs(timeout_secs), rx).await {
        Ok(Ok(res)) => res,
        Ok(Err(_)) => Err("Bridge channel closed".to_string()),
        Err(_) => {
            if let Ok(mut m) = pending_map().lock() {
                m.remove(&id);
            }
            Err(format!("Native WebKit command timed out: {method}"))
        }
    }
}

#[cfg(not(target_os = "macos"))]
async fn native_call(method: &str, params: Value, timeout_secs: u64) -> Result<Value, String> {
    crate::win_webview_bridge::native_call(GLOBAL_APP_HANDLE.get(), method, params, timeout_secs)
        .await
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ClipboardMediaItem {
    pub filename: String,
    pub mime: String,
    pub data_base64: String,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ClipboardMediaResult {
    pub source: String,
    pub items: Vec<ClipboardMediaItem>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SiteHttpDiag {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub cf_ray: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub cf_mitigated: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub retry_after: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub content_type: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub request_id: Option<String>,
    pub local_cooldown: bool,
    pub is_challenge_429: bool,
    pub pace_wait_ms: u64,
    pub cooldown_wait_ms: u64,
    pub retried: bool,
    pub silent_cf_revalidated: bool,
    pub verify_attempted: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub verify_round: Option<u64>,
    pub verify_cooldown_ms: u64,
    pub ineffective_clearance: bool,
    pub csrf_refreshed: bool,
    pub guest_fallback: bool,
    pub body_bytes: usize,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DiagRuntimeSnapshot {
    pub os: String,
    pub arch: String,
    pub bridge_ua: String,
    pub origin_ready: bool,
    pub verifying: bool,
    pub bootstrap_challenged: bool,
    pub cf_attention_notified: bool,
    pub has_cookie_t: bool,
    pub has_forum_session: bool,
    pub has_cf_clearance: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub cf_clearance_age_secs: Option<u64>,
    pub saved_accounts_count: usize,
    pub rate_limit_remain_ms: u64,
    pub fg_challenge_remain_ms: u64,
    pub bg_challenge_remain_ms: u64,
    pub media_cooldown_remain_ms: u64,
    pub challenge_429_streak: u32,
    pub verify_round: u64,
    pub verify_consecutive_failures: u32,
    pub verify_cooldown_remain_ms: u64,
    pub verify_ineffective_breaker: bool,
    pub pacer_rate_per_sec: f64,
    pub session_generation: u64,
    pub media_cache_entries: usize,
    pub media_cache_bytes: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SiteHttpResponse {
    pub status: u16,
    pub body: String,
    pub is_challenge: bool,
    pub is_unauthenticated: bool,
    pub rate_limited_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub diag: Option<SiteHttpDiag>,
}

#[derive(Clone, Default)]
struct PendingRsaGrant {
    private_base64: String,
    nonce: String,
    created_at_ms: u64,
}

pub struct BridgeEngine {
    pub state: StdMutex<PersistedState>,
    bootstrap_lock: Mutex<()>,
    verify_lock: Mutex<()>,
    account_lock: RwLock<()>,
    nav_lock: RwLock<()>,
    priority_gate: PriorityGate,
    avatar_sem: Semaphore,
    emoji_sem: Semaphore,
    media_sem: Semaphore,
    api_pacer: StdMutex<AdaptiveTokenBucket>,
    media_pacer: StdMutex<AdaptiveTokenBucket>,
    active_fg_requests: AtomicUsize,
    session_generation: AtomicU64,
    warmup_spawned: AtomicBool,
    inner: StdMutex<BridgeRuntime>,
    verify_coord: Arc<StdMutex<VerifyCoordinator>>,
    cf_authority: StdMutex<CfClearanceAuthority>,
    media_cache: StdMutex<MediaCacheState>,
    media_inflight: StdMutex<HashMap<String, Arc<Notify>>>,
}

struct FgRequestGuard<'a>(&'a AtomicUsize);
impl<'a> FgRequestGuard<'a> {
    fn enter(counter: &'a AtomicUsize) -> Self {
        counter.fetch_add(1, Ordering::Relaxed);
        Self(counter)
    }
}
impl Drop for FgRequestGuard<'_> {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::Relaxed);
    }
}

struct VerifyingFlagGuard<'a>(&'a StdMutex<BridgeRuntime>);
impl<'a> VerifyingFlagGuard<'a> {
    fn enter(inner: &'a StdMutex<BridgeRuntime>) -> Self {
        {
            let mut rt = inner.lock().unwrap();
            rt.verifying = true;
            rt.origin_ready = false;
            rt.bootstrap_challenged = false;
            rt.rate_limit_until = None;
            rt.fg_challenge_until = None;
            rt.bg_challenge_until = None;
        }
        Self(inner)
    }
}
impl Drop for VerifyingFlagGuard<'_> {
    fn drop(&mut self) {
        if let Ok(mut rt) = self.0.lock() {
            rt.verifying = false;
        }
    }
}

fn emit_diag(
    app: &AppHandle,
    level: &str,
    category: &str,
    tag: &str,
    summary: &str,
    detail: Option<&str>,
) {
    let mut payload = json!({
        "level": level,
        "category": category,
        "tag": tag,
        "summary": summary
    });
    if let Some(d) = detail.filter(|s| !s.trim().is_empty()) {
        payload["detail"] = json!(d);
    }
    let _ = app.emit("lindo://diag-event", payload);
}

struct BridgeRuntime {
    initialized: bool,
    origin_ready: bool,
    bootstrap_challenged: bool,
    bridge_ua: String,
    last_seeded_token: String,
    last_challenge_url: Option<String>,
    last_cookie_sync: Option<Instant>,
    last_login_at: Option<Instant>,
    last_expiry_check: Option<Instant>,
    rate_limit_until: Option<Instant>,
    last_429_probe_at: Option<Instant>,
    fg_challenge_until: Option<Instant>,
    bg_challenge_until: Option<Instant>,
    media_cooldown_until: Option<Instant>,
    challenge_429_streak: u32,
    cf_attention_notified: bool,
    verifying: bool,
    pending_rsa: Option<PendingRsaGrant>,
    consumed_nonces: Vec<String>,
}

impl BridgeEngine {
    pub fn new(mut initial: PersistedState) -> Arc<Self> {
        // Decouple `_t` (durable session) from `cf_clearance` (ephemeral network/TLS-bound token):
        // never re-import disk `cf_clearance` across cold restarts or IP/proxy changes!
        let cleaned_cookies: Vec<String> = initial
            .raw_cookies
            .split(';')
            .map(|s| s.trim())
            .filter(|s| {
                !s.is_empty()
                    && !s.starts_with("cf_clearance=")
                    && !s.starts_with("__cf")
                    && !s.starts_with("_cf")
            })
            .map(|s| s.to_string())
            .collect();
        initial.raw_cookies = cleaned_cookies.join("; ");
        initial.cf_clearance_updated_at_ms = 0;

        let saved_ua = initial.last_bridge_ua.clone();
        Arc::new(Self {
            state: StdMutex::new(initial),
            bootstrap_lock: Mutex::new(()),
            verify_lock: Mutex::new(()),
            account_lock: RwLock::new(()),
            nav_lock: RwLock::new(()),
            priority_gate: PriorityGate::new(3, 1),
            // Conservative 3-lane media concurrency + AIMD media pacer so same-origin `/uploads/` never starve or 429 JSON APIs
            avatar_sem: Semaphore::new(2),
            emoji_sem: Semaphore::new(4),
            media_sem: Semaphore::new(2),
            api_pacer: StdMutex::new(AdaptiveTokenBucket::new(5.0, 3.4, 1.1, 3.8)),
            media_pacer: StdMutex::new(AdaptiveTokenBucket::new(3.0, 5.2, 2.0, 5.6)),
            active_fg_requests: AtomicUsize::new(0),
            session_generation: AtomicU64::new(1),
            warmup_spawned: AtomicBool::new(false),
            inner: StdMutex::new(BridgeRuntime {
                initialized: false,
                origin_ready: false,
                bootstrap_challenged: false,
                bridge_ua: saved_ua,
                last_seeded_token: "__unseeded__".to_string(),
                last_challenge_url: None,
                last_cookie_sync: None,
                last_login_at: None,
                last_expiry_check: None,
                rate_limit_until: None,
                last_429_probe_at: None,
                fg_challenge_until: None,
                bg_challenge_until: None,
                media_cooldown_until: None,
                challenge_429_streak: 0,
                cf_attention_notified: false,
                verifying: false,
                pending_rsa: None,
                consumed_nonces: Vec::new(),
            }),
            verify_coord: Arc::new(StdMutex::new(VerifyCoordinator::default())),
            cf_authority: StdMutex::new(CfClearanceAuthority::default()),
            media_cache: StdMutex::new(MediaCacheState::default()),
            media_inflight: StdMutex::new(HashMap::new()),
        })
    }

    pub fn spawn_warmup(self: &Arc<Self>) {
        if self.warmup_spawned.swap(true, Ordering::SeqCst) {
            return;
        }
        let eng = Arc::clone(self);
        tauri::async_runtime::spawn(async move {
            let _ = eng.ensure_ready(false).await;
            // After initial foreground boot requests settle, silently prewarm `cf_clearance` via `/challenge`
            // if the browser cookie store doesn't hold one yet — eliminating first-POST (Boost/Like) popups!
            tokio::time::sleep(Duration::from_millis(1600)).await;
            let _ = eng.prewarm_cf_clearance_if_needed().await;
        });
    }

    async fn sweep_auth_cookies(&self, clear_all: bool, preferred_t: Option<&str>) {
        let _ = native_call(
            "sweepAuthCookies",
            json!({
                "mode": if clear_all { "clear" } else { "dedupe" },
                "preferredT": preferred_t.unwrap_or("")
            }),
            8,
        )
        .await;
    }

    pub fn bind_app_handle(app: &AppHandle) {
        let _ = GLOBAL_APP_HANDLE.set(app.clone());
        #[cfg(target_os = "macos")]
        unsafe {
            lindo_bridge_set_event_callback(on_native_bridge_reply);
        }
    }

    pub async fn set_dock_badge(&self, count: i32) {
        let label = if count <= 0 {
            "".to_string()
        } else if count > 99 {
            "99+".to_string()
        } else {
            count.to_string()
        };
        let _ = native_call("setDockBadge", json!({ "label": label }), 5).await;
    }

    fn is_allowed_forum_url(url: &str) -> bool {
        url == "https://linux.do" || url.starts_with("https://linux.do/")
    }

    pub fn looks_like_challenge(body: &str) -> bool {
        if body.starts_with(CF_CHALLENGE_PREFIX) {
            return true;
        }
        let lower = body.to_lowercase();
        (lower.contains("<html") || lower.contains("<!doctype html"))
            && (lower.contains("just a moment")
                || lower.contains("请稍候")
                || lower.contains("cf-turnstile")
                || lower.contains("challenges.cloudflare.com")
                || lower.contains("challenge-platform")
                || lower.contains("_cf_chl_opt"))
    }

    /// Authoritative Cloudflare challenge classifier (aligned with FluxDO `isCfChallengeResponse`):
    /// - `cf-mitigated: challenge` is an authoritative CF challenge signal on BOTH 403 AND 429/503 (never treat CF challenge 429 as a blind retryable rate limit!).
    /// - Explicit non-HTML responses (e.g. Discourse JSON 429/403) are never CF challenges.
    pub fn is_cf_challenge_response(
        status: u16,
        cf_header: &str,
        content_type: &str,
        raw_body: &str,
    ) -> bool {
        if raw_body.starts_with(CF_CHALLENGE_PREFIX) {
            return true;
        }
        if cf_header.to_ascii_lowercase().contains("challenge") {
            return true;
        }
        if status != 403 && status != 429 && status != 503 {
            return false;
        }
        let ct = content_type.to_ascii_lowercase();
        if !ct.is_empty() && !ct.contains("text/html") {
            return false;
        }
        Self::looks_like_challenge(raw_body)
    }

    async fn purge_cf_clearance_for_reverify(&self) -> Vec<String> {
        let deleted: Vec<String> = native_call("deleteCfClearance", json!({}), 6)
            .await
            .ok()
            .and_then(|v| {
                v.get("deleted")?.as_array().map(|arr| {
                    arr.iter()
                        .filter_map(|x| x.as_str().map(|s| s.trim().to_string()))
                        .filter(|s| !s.is_empty())
                        .collect()
                })
            })
            .unwrap_or_default();
        if !deleted.is_empty() {
            let mut auth = self.cf_authority.lock().unwrap();
            for val in &deleted {
                auth.note_challenged(Some(val));
            }
        }
        deleted
    }

    async fn restore_cf_clearance_backup(&self, backup: &[String]) {
        let Some(first) = backup.first().filter(|s| !s.is_empty()) else {
            return;
        };
        if !self.browser_cf_clearances().await.is_empty() {
            return;
        }
        let _ = native_call("restoreCfClearance", json!({ "value": first }), 6).await;
    }

    fn is_unauthenticated_response(status: u16, body: &str) -> bool {
        if status == 401 {
            return true;
        }
        if status == 403 && !Self::looks_like_challenge(body) {
            return body.contains("\"not_logged_in\"")
                || body.contains("You need to be logged in");
        }
        false
    }

    fn parse_cookie_map(raw: &str) -> HashMap<String, String> {
        let mut out = HashMap::new();
        for seg in raw.split(';') {
            let trimmed = seg.trim();
            if let Some((k, v)) = trimmed.split_once('=') {
                let name = k.trim();
                let val = v.trim();
                if !name.is_empty() && !val.is_empty() {
                    out.insert(name.to_string(), val.to_string());
                }
            }
        }
        out
    }

    async fn browser_cf_clearances(&self) -> Vec<String> {
        let Ok(current) = native_call("cookies", json!({}), 6).await else {
            return Vec::new();
        };
        current
            .get("cookies")
            .and_then(|c| c.as_array())
            .map(|arr| {
                arr.iter()
                    .filter_map(|item| {
                        if item.get("name")?.as_str()? == "cf_clearance" {
                            let val = item.get("value")?.as_str()?.trim().to_string();
                            if !val.is_empty() {
                                return Some(val);
                            }
                        }
                        None
                    })
                    .collect()
            })
            .unwrap_or_default()
    }

    fn current_is_dark(&self) -> bool {
        self.state
            .lock()
            .map(|st| st.preferences.appearance != "light")
            .unwrap_or(true)
    }

    fn emit_net_health_diag(&self, app: &AppHandle, level: &str, trigger: &str, extra: Option<&str>) {
        let snap = self.diag_runtime_snapshot();
        let (inflight, waiters) = self.priority_gate.stats();
        let mut detail = format!(
            "trigger={trigger}\nua={}\nverify: round={} active={} fails={} cooldownMs={} ineffective={} | pacer={:.2}/s gate={inflight}/{waiters} gen={}\ncookies: _t={} _forum_session={} cf_clearance={} cfAgeSec={:?} | rlRemainMs={} fgChalMs={} bgChalMs={}",
            if snap.bridge_ua.is_empty() { "unknown" } else { snap.bridge_ua.as_str() },
            snap.verify_round,
            snap.verifying,
            snap.verify_consecutive_failures,
            snap.verify_cooldown_remain_ms,
            snap.verify_ineffective_breaker,
            snap.pacer_rate_per_sec,
            snap.session_generation,
            snap.has_cookie_t,
            snap.has_forum_session,
            snap.has_cf_clearance,
            snap.cf_clearance_age_secs,
            snap.rate_limit_remain_ms,
            snap.fg_challenge_remain_ms,
            snap.bg_challenge_remain_ms,
        );
        if let Some(ex) = extra.filter(|s| !s.trim().is_empty()) {
            detail.push('\n');
            detail.push_str(ex);
        }
        let summary = match trigger {
            "cf_challenge_detected" => "命中站点安全验证，已记录当前网络健康快照",
            "ineffective_clearance_breaker" => "安全确认已通过但重放仍被拦截，已启动 60 秒熔断冷却",
            "verify_cooldown_active" => "安全确认处于自动冷却期，已阻止重复弹窗",
            _ => "网络与验证健康快照",
        };
        emit_diag(app, level, "NETWORK", "NetHealth", summary, Some(&detail));
    }

    /// Background silent prewarm of `cf_clearance` via `https://linux.do/challenge` when missing.
    /// Runs through the unified single-flight `VerifyCoordinator`:
    /// - Never opens a panel on its own (`VerifyPresentation::Silent`) and never counts abort as a user failure.
    /// - If a foreground request arrives mid-prewarm, it seamlessly escalates the live `/challenge` page
    ///   without double-loading `/challenge`!
    pub async fn prewarm_cf_clearance_if_needed(&self) -> bool {
        let Some(app) = GLOBAL_APP_HANDLE.get().cloned() else {
            return false;
        };
        {
            let rt = self.inner.lock().unwrap();
            if rt.verifying || !rt.initialized {
                return false;
            }
            if let Some(until) = rt.rate_limit_until {
                if Instant::now() < until {
                    return false;
                }
            }
        }
        if self.active_fg_requests.load(Ordering::Relaxed) > 0 {
            return false;
        }
        let existing = self.browser_cf_clearances().await;
        let has_healthy = {
            let auth = self.cf_authority.lock().unwrap();
            existing.iter().any(|c| !auth.is_challenged(c))
        };
        if has_healthy {
            return true;
        }
        let is_dark = self.current_is_dark();
        self.coordinate_cf_verification(&app, VerifyPresentation::Silent, is_dark, false)
            .await
            .passed
    }

    /// Navigates the hidden shared bridge WebView back to lightweight `https://linux.do/about`
    /// and waits for `/about` to actually commit before returning so immediate POST/GET replays never abort with `Load failed`.
    async fn restore_about_origin_context(&self) {
        let _ = native_call("navigate", json!({ "url": "https://linux.do/about" }), 6).await;
        let deadline = Instant::now() + Duration::from_secs(4);
        while Instant::now() < deadline {
            let ready = native_call(
                "evaluate",
                json!({
                    "isolated": true,
                    "script": "(() => { if (location.origin !== 'https://linux.do') return false; if (location.pathname.indexOf('/about') !== 0) return false; return document.readyState !== 'loading'; })()"
                }),
                4,
            )
            .await
            .ok()
            .and_then(|v| v.as_bool())
            .unwrap_or(false);
            if ready {
                let _ = native_call(
                    "evaluate",
                    json!({
                        "script": "(() => { const tok = document.querySelector('meta[name=\"csrf-token\"]')?.content || null; window.__lindoCsrf = tok; window.__lindoIgnoreDomCsrf = !tok; try { if (window.MessageBus && typeof window.MessageBus.stop === 'function') window.MessageBus.stop(); } catch (_) {} return true; })()"
                    }),
                    4,
                )
                .await;
                self.inner.lock().unwrap().origin_ready = true;
                return;
            }
            tokio::time::sleep(Duration::from_millis(60)).await;
        }
        let _ = native_call(
            "evaluate",
            json!({ "script": "(() => { window.__lindoCsrf = null; window.__lindoIgnoreDomCsrf = true; return true; })()" }),
            4,
        )
        .await;
        self.inner.lock().unwrap().origin_ready = true;
    }

    /// Unified single-flight Cloudflare verification coordinator:
    /// - Coalesces all concurrent callers into ONE active `/challenge` round via `tokio::sync::watch`.
    /// - Monotonically escalates presentation (`Silent -> Blocking -> Manual`) mid-flight without reloading `/challenge`.
    /// - Enforces round-deduplicated failure cooldown (3 failed rounds -> 30s) and 60s ineffective-clearance circuit breaker.
    /// - Explicit `Manual` clicks always bypass auto-cooldowns.
    pub async fn coordinate_cf_verification(
        &self,
        app: &AppHandle,
        requested: VerifyPresentation,
        is_dark: bool,
        is_post_pass_replay: bool,
    ) -> VerifyOutcome {
        let join = {
            let mut coord = self.verify_coord.lock().unwrap();
            coord.join_or_start(requested, Instant::now(), is_post_pass_replay)
        };

        match join {
            VerifyJoin::RecentPass { round } => VerifyOutcome {
                passed: true,
                round,
                ineffective_blocked: false,
                cooldown_remain_ms: 0,
            },
            VerifyJoin::BlockedByCooldown {
                remain_ms,
                ineffective,
            } => {
                if requested == VerifyPresentation::Blocking {
                    self.emit_net_health_diag(
                        app,
                        "WARN",
                        "verify_cooldown_active",
                        Some(&format!("remainMs={remain_ms} ineffective={ineffective}")),
                    );
                }
                VerifyOutcome {
                    passed: false,
                    round: 0,
                    ineffective_blocked: ineffective,
                    cooldown_remain_ms: remain_ms,
                }
            }
            VerifyJoin::Follower { round, mut rx } => {
                loop {
                    if let Some(outcome) = *rx.borrow_and_update() {
                        return outcome;
                    }
                    if rx.changed().await.is_err() {
                        return VerifyOutcome {
                            passed: false,
                            round,
                            ineffective_blocked: false,
                            cooldown_remain_ms: 0,
                        };
                    }
                }
            }
            VerifyJoin::Leader {
                round,
                presentation,
                escalate_notify,
            } => {
                let flight_guard = VerifyFlightGuard::new(
                    Arc::clone(&self.verify_coord),
                    round,
                    Arc::clone(&presentation),
                );
                let _verify_guard = self.verify_lock.lock().await;
                let _verifying_flag = VerifyingFlagGuard::enter(&self.inner);

                let passed = self
                    .run_unified_cf_verify(app, is_dark, round, &presentation, &escalate_notify)
                    .await
                    .unwrap_or(false);

                let _ = native_call("hide", json!({}), 5).await;
                {
                    let _nav = self.nav_lock.write().await;
                    self.restore_about_origin_context().await;
                }
                if passed {
                    let mut rt = self.inner.lock().unwrap();
                    rt.origin_ready = true;
                    rt.bootstrap_challenged = false;
                    rt.last_challenge_url = None;
                    rt.fg_challenge_until = None;
                    rt.bg_challenge_until = None;
                    rt.rate_limit_until = None;
                    rt.challenge_429_streak = 0;
                    rt.cf_attention_notified = false;
                }
                let outcome = flight_guard.complete(passed, Instant::now());
                if passed {
                    let _ = app.emit("lindo://cf-attention-needed", false);
                    let _ = app.emit("lindo://verified", ());
                }
                outcome
            }
        }
    }

    async fn run_unified_cf_verify(
        &self,
        app: &AppHandle,
        is_dark: bool,
        round: u64,
        presentation: &Arc<AtomicU8>,
        escalate_notify: &Arc<Notify>,
    ) -> Result<bool, String> {
        native_call("init", json!({}), 10).await?;
        self.inner.lock().unwrap().initialized = true;
        let _ = self.seed_session_verified(true).await;

        // Drain any in-flight `evaluate()` fetches cleanly BEFORE navigating away from `/about` to `/challenge`!
        let backup_cfs = {
            let _nav = self.nav_lock.write().await;
            let backups = self.purge_cf_clearance_for_reverify().await;
            let cur_pres = VerifyPresentation::from_u8(presentation.load(Ordering::SeqCst));
            if cur_pres.allows_ui() {
                let _ = native_call(
                    "setVerifyMask",
                    json!({ "visible": true, "dark": is_dark, "message": "校验连接中…" }),
                    4,
                )
                .await;
            }
            if let Err(e) = native_call("navigate", json!({ "url": "https://linux.do/challenge" }), 10).await {
                self.restore_cf_clearance_backup(&backups).await;
                return Err(e);
            }
            backups
        };

        let stale_cf: std::collections::HashSet<String> = backup_cfs.iter().cloned().collect();
        let mut mask_visible = VerifyPresentation::from_u8(presentation.load(Ordering::SeqCst)).allows_ui();
        let mut panel_shown = false;
        if VerifyPresentation::from_u8(presentation.load(Ordering::SeqCst)) == VerifyPresentation::Manual {
            panel_shown = true;
            let _ = native_call("show", json!({ "dark": is_dark, "login": false }), 8).await;
        }

        let mut oops_reloaded = false;
        let mut challenge_ticks = 0u32;
        let mut quiet_rounds = 0u32;
        let mut last_reload_epoch: Option<u64> = None;
        let mut pass_mask_stuck_ticks = 0u32;
        let mut stuck_reloaded = false;
        let mut terminated_reloaded = false;
        let loop_started = Instant::now();
        let mut cleared = false;

        loop {
            let cur_pres = VerifyPresentation::from_u8(presentation.load(Ordering::SeqCst));
            let max_dur = if cur_pres == VerifyPresentation::Silent {
                Duration::from_millis(3800)
            } else {
                Duration::from_secs(180)
            };
            if loop_started.elapsed() >= max_dur {
                break;
            }

            // Sleep 220ms OR wake up instantaneously if a foreground caller escalates `Silent -> Blocking/Manual`!
            tokio::select! {
                _ = tokio::time::sleep(Duration::from_millis(220)) => {}
                _ = escalate_notify.notified() => {}
            }

            let cur_pres = VerifyPresentation::from_u8(presentation.load(Ordering::SeqCst));
            if cur_pres == VerifyPresentation::Manual && !panel_shown {
                if !mask_visible {
                    mask_visible = true;
                    let _ = native_call(
                        "setVerifyMask",
                        json!({ "visible": true, "dark": is_dark, "message": "校验连接中…" }),
                        4,
                    )
                    .await;
                }
                panel_shown = true;
                let _ = native_call("show", json!({ "dark": is_dark, "login": false }), 8).await;
            }

            let Ok(status_obj) = native_call("status", json!({}), 6).await else {
                continue;
            };
            let user_cancelled = status_obj.get("cancelled").and_then(|v| v.as_bool()) == Some(true);
            let native_origin_404 = status_obj.get("origin404").and_then(|v| v.as_bool()) == Some(true);
            let native_interactive = status_obj
                .get("interactiveRequired")
                .and_then(|v| v.as_bool())
                .unwrap_or(false);
            let native_user_reloading = status_obj
                .get("userReloading")
                .and_then(|v| v.as_bool())
                .unwrap_or(false);
            let native_reload_epoch = status_obj
                .get("reloadEpoch")
                .and_then(|v| v.as_u64())
                .unwrap_or(0);
            let native_mask_visible = status_obj.get("maskVisible").and_then(|v| v.as_bool());
            let native_mask_message = status_obj
                .get("maskMessage")
                .and_then(|v| v.as_str())
                .unwrap_or("");
            let native_nav_error = status_obj
                .get("error")
                .and_then(|v| v.as_str())
                .unwrap_or("");
            let native_terminated = status_obj
                .get("processTerminated")
                .and_then(|v| v.as_bool())
                .unwrap_or(false);

            if let Some(vis) = native_mask_visible {
                mask_visible = vis;
            }
            match last_reload_epoch {
                None => last_reload_epoch = Some(native_reload_epoch),
                Some(prev) if prev != native_reload_epoch => {
                    last_reload_epoch = Some(native_reload_epoch);
                    challenge_ticks = 0;
                    quiet_rounds = 0;
                    pass_mask_stuck_ticks = 0;
                    stuck_reloaded = false;
                    emit_diag(
                        app,
                        "INFO",
                        "AUTH",
                        "VerifyReload",
                        "安全确认弹窗已手动刷新，重新加载验证",
                        Some(&format!("round={round}")),
                    );
                }
                _ => {}
            }

            if cur_pres.allows_ui() && user_cancelled && (native_user_reloading || native_terminated) {
                break;
            }

            if native_terminated && !terminated_reloaded {
                terminated_reloaded = true;
                challenge_ticks = 0;
                quiet_rounds = 0;
                pass_mask_stuck_ticks = 0;
                emit_diag(
                    app,
                    "WARN",
                    "AUTH",
                    "VerifyRecover",
                    "安全确认 WebView 进程异常中断，自动重新加载",
                    Some(&format!("round={round}")),
                );
                if cur_pres.allows_ui() {
                    mask_visible = true;
                    let _ = native_call(
                        "setVerifyMask",
                        json!({ "visible": true, "dark": is_dark, "message": "正在重新加载验证…" }),
                        4,
                    )
                    .await;
                }
                let _ = native_call("navigate", json!({ "url": "https://linux.do/challenge" }), 8).await;
                continue;
            }

            if native_user_reloading {
                challenge_ticks = 0;
                quiet_rounds = 0;
                pass_mask_stuck_ticks = 0;
                if cur_pres.allows_ui() && !panel_shown && loop_started.elapsed() >= Duration::from_millis(1200) {
                    panel_shown = true;
                    let _ = native_call("show", json!({ "dark": is_dark, "login": false }), 8).await;
                }
                continue;
            }

            // Silent probe bails immediately on `interactiveBegin` unless escalated to Blocking/Manual!
            if cur_pres == VerifyPresentation::Silent && native_interactive {
                break;
            }

            let probe = native_call(
                "evaluate",
                json!({
                    "isolated": true,
                    "script": r#"(() => {
                        const site = location.origin === 'https://linux.do';
                        const complete = document.readyState === 'complete';
                        const domReady = document.readyState === 'interactive' || complete;
                        const csrfVal = document.querySelector('meta[name="csrf-token"]')?.content || null;
                        const hasCsrf = !!csrfVal;
                        const preloadedEl = document.getElementById('data-preloaded');
                        const hasPreloaded = !!preloadedEl;
                        const hasDiscourseDom = hasPreloaded || !!document.querySelector('header.d-header, #main-outlet .topic-list, #main-outlet #topic-title, #main-outlet .container, #d-splash');
                        const bodyText = (document.body?.innerText || '').trim();
                        const lowerHtml = ((document.documentElement?.innerHTML || '') + ' ' + (document.title || '')).toLowerCase();
                        const isOrigin404 = site && (
                            !!document.querySelector('.page-not-found, .discourse-no-results, #main-outlet .not-found-container')
                            || lowerHtml.includes('page-not-found')
                            || lowerHtml.includes('discourse-no-results')
                            || lowerHtml.includes('"errortype":"notfound"')
                            || lowerHtml.includes('404-body')
                            || lowerHtml.includes('exist or is private')
                            || lowerHtml.includes('page you requested')
                            || lowerHtml.includes('该页面不存在')
                        );
                        const explicitChallenge = !isOrigin404 && (
                            !!document.querySelector('#challenge-running, #challenge-stage, #challenge-form, .cf-turnstile, #turnstile-wrapper, iframe[src*="challenges.cloudflare.com"]')
                            || /^(Just a moment|请稍候|Attention Required|Security Check)/i.test(document.title || '')
                        );
                        const widgetIframe = document.querySelector('iframe[src*="challenges.cloudflare.com"], #turnstile-wrapper iframe, .cf-turnstile iframe');
                        const widgetIframeRect = widgetIframe ? widgetIframe.getBoundingClientRect() : null;
                        const widgetHost = document.querySelector('#turnstile-wrapper, .cf-turnstile, #challenge-stage > div');
                        const widgetHostRect = widgetHost ? widgetHost.getBoundingClientRect() : null;
                        const hasRenderedWidget =
                            !!(widgetIframeRect && widgetIframeRect.width >= 80 && widgetIframeRect.height >= 30)
                            || !!(widgetHostRect && widgetHostRect.width >= 220 && widgetHostRect.height >= 55);
                        const isDiscourseOops = site && complete && !explicitChallenge && /discussion forum encountered an unexpected problem/i.test(bodyText);
                        const missingDiscourseBoot = site && complete && !hasCsrf && !isOrigin404 && !isDiscourseOops && !bodyText.startsWith('{');
                        let preloadedUserJson = null;
                        if (site && domReady && hasCsrf && hasPreloaded && !explicitChallenge) {
                            try {
                                const raw = preloadedEl.getAttribute('data-preloaded') || preloadedEl.textContent || '';
                                if (raw) {
                                    const outer = JSON.parse(raw);
                                    if (outer && typeof outer.currentUser === 'string' && outer.currentUser.length > 2) {
                                        preloadedUserJson = JSON.stringify({ current_user: JSON.parse(outer.currentUser) });
                                    } else if (outer && outer.currentUser && typeof outer.currentUser === 'object') {
                                        preloadedUserJson = JSON.stringify({ current_user: outer.currentUser });
                                    }
                                }
                            } catch (_) {}
                        }
                        return {
                            site, domReady, complete, hasCsrf, hasPreloaded, hasDiscourseDom,
                            isOrigin404, isDiscourseOops, preloadedUserJson,
                            explicitChallenge, hasRenderedWidget,
                            challenge: !isOrigin404 && (explicitChallenge || missingDiscourseBoot)
                        };
                    })()"#
                }),
                8,
            )
            .await
            .ok();

            let is_discourse_oops = probe
                .as_ref()
                .and_then(|p| p.get("isDiscourseOops")?.as_bool())
                .unwrap_or(false);
            let dom_origin_404 = probe
                .as_ref()
                .and_then(|p| p.get("isOrigin404")?.as_bool())
                .unwrap_or(false);
            let is_origin_404 = native_origin_404 || dom_origin_404;
            let explicit_challenge = !is_origin_404
                && probe
                    .as_ref()
                    .and_then(|p| p.get("explicitChallenge")?.as_bool())
                    .unwrap_or(false);
            let has_rendered_widget = !is_origin_404
                && probe
                    .as_ref()
                    .and_then(|p| p.get("hasRenderedWidget")?.as_bool())
                    .unwrap_or(false);
            let in_challenge = !is_origin_404
                && probe
                    .as_ref()
                    .and_then(|p| p.get("challenge")?.as_bool())
                    .unwrap_or(false);
            let ready = probe
                .as_ref()
                .map(|p| {
                    p.get("site").and_then(|v| v.as_bool()) == Some(true)
                        && p.get("domReady").and_then(|v| v.as_bool()) == Some(true)
                        && p.get("hasCsrf").and_then(|v| v.as_bool()) == Some(true)
                        && p.get("hasDiscourseDom").and_then(|v| v.as_bool()) == Some(true)
                })
                .unwrap_or(false);

            if is_discourse_oops {
                let _ = self.sync_cookies_from_webkit_verified(false, true).await;
                if !oops_reloaded {
                    oops_reloaded = true;
                    let _ = native_call("navigate", json!({ "url": "https://linux.do/challenge" }), 8).await;
                    continue;
                }
                cleared = true;
                break;
            }

            if cur_pres.allows_ui() && user_cancelled {
                if (ready || is_origin_404) && !in_challenge {
                    // User closed right as verification finished
                } else {
                    break;
                }
            }

            if in_challenge {
                quiet_rounds = 0;
                pass_mask_stuck_ticks = 0;
                challenge_ticks += 1;

                if cur_pres == VerifyPresentation::Silent {
                    // Pure silent check: if Turnstile widget requires human click past ~1.1s, bail cleanly without UI
                    if has_rendered_widget && challenge_ticks >= 5 {
                        break;
                    }
                    continue;
                }

                // Interactive/Blocking presentation: promote seamlessly on the ALREADY-LOADED `/challenge` page!
                let ready_to_reveal = native_interactive
                    || has_rendered_widget
                    || (explicit_challenge && challenge_ticks >= 6)
                    || challenge_ticks >= 10;
                if (native_interactive || has_rendered_widget || challenge_ticks >= 2) && !panel_shown {
                    if !ready_to_reveal && !mask_visible {
                        mask_visible = true;
                        let _ = native_call(
                            "setVerifyMask",
                            json!({ "visible": true, "dark": is_dark, "message": "校验连接中…" }),
                            4,
                        )
                        .await;
                    }
                    panel_shown = true;
                    let _ = native_call("show", json!({ "dark": is_dark, "login": false }), 8).await;
                }
                if ready_to_reveal && mask_visible {
                    mask_visible = false;
                    let _ = native_call("setVerifyMask", json!({ "visible": false, "dark": is_dark }), 4).await;
                } else if mask_visible && native_mask_message == "已通过，正在继续…" {
                    let _ = native_call(
                        "setVerifyMask",
                        json!({ "visible": true, "dark": is_dark, "message": "校验连接中…" }),
                        4,
                    )
                    .await;
                }
                continue;
            }
            challenge_ticks = 0;

            if cur_pres.allows_ui() && !mask_visible && (is_origin_404 || ready) {
                mask_visible = true;
                let _ = native_call(
                    "setVerifyMask",
                    json!({ "visible": true, "dark": is_dark, "message": "已通过，正在继续…" }),
                    4,
                )
                .await;
            }

            let current_cfs = self.browser_cf_clearances().await;
            let has_fresh_cf = {
                let auth = self.cf_authority.lock().unwrap();
                current_cfs
                    .iter()
                    .any(|c| !stale_cf.contains(c) && !auth.is_challenged(c))
            };

            if has_fresh_cf || is_origin_404 || ready {
                pass_mask_stuck_ticks = 0;
                quiet_rounds += 1;
            } else {
                quiet_rounds = 0;
                if cur_pres.allows_ui() && !panel_shown && loop_started.elapsed() >= Duration::from_millis(1200) {
                    panel_shown = true;
                    let _ = native_call("show", json!({ "dark": is_dark, "login": false }), 8).await;
                }
                if cur_pres.allows_ui() && mask_visible && native_mask_message == "已通过，正在继续…" {
                    pass_mask_stuck_ticks += 1;
                    if pass_mask_stuck_ticks >= 18 {
                        let detail = format!(
                            "round={round}, elapsedMs={}, hasFreshCf={}, origin404={}, ready={}, navError={}",
                            loop_started.elapsed().as_millis(),
                            has_fresh_cf,
                            is_origin_404,
                            ready,
                            if native_nav_error.is_empty() { "none" } else { native_nav_error }
                        );
                        emit_diag(
                            app,
                            "WARN",
                            "AUTH",
                            "VerifyWatchdog",
                            "安全确认停留在过渡状态超过 4 秒，触发自动恢复",
                            Some(&detail),
                        );
                        pass_mask_stuck_ticks = 0;
                        if !stuck_reloaded {
                            stuck_reloaded = true;
                            let _ = native_call(
                                "setVerifyMask",
                                json!({ "visible": true, "dark": is_dark, "message": "正在重新加载验证…" }),
                                4,
                            )
                            .await;
                            let _ = native_call("navigate", json!({ "url": "https://linux.do/challenge" }), 8).await;
                        } else {
                            mask_visible = false;
                            let _ = native_call("setVerifyMask", json!({ "visible": false, "dark": is_dark }), 4).await;
                        }
                    }
                } else {
                    pass_mask_stuck_ticks = 0;
                }
                continue;
            }

            let needed_rounds = if has_fresh_cf && (is_origin_404 || ready) { 1 } else { 2 };
            if quiet_rounds < needed_rounds {
                continue;
            }

            // Self-heal zombie local session if Discourse `data-preloaded` proves `_t` expired
            let has_preloaded = probe
                .as_ref()
                .and_then(|p| p.get("hasPreloaded")?.as_bool())
                .unwrap_or(false);
            let preloaded_user = probe
                .as_ref()
                .and_then(|p| p.get("preloadedUserJson")?.as_str())
                .and_then(Self::extract_current_user);

            if has_preloaded && preloaded_user.is_none() {
                let mut expired_changed = false;
                {
                    let mut st = self.state.lock().unwrap();
                    if let Some(prev) = st.current_user.take() {
                        st.expired_username = Some(prev.username.clone());
                        Self::upsert_saved_account(&mut st, prev, String::new(), true);
                        let cf_only: Vec<String> = st
                            .raw_cookies
                            .split(';')
                            .map(|s| s.trim())
                            .filter(|s| !s.is_empty() && !s.starts_with("_t=") && !s.starts_with("_forum_session="))
                            .map(|s| s.to_string())
                            .collect();
                        st.raw_cookies = cf_only.join("; ");
                        st.unread_notifications = 0;
                        save_state(&st);
                        expired_changed = true;
                    }
                }
                if expired_changed {
                    self.sweep_auth_cookies(true, None).await;
                    self.inner.lock().unwrap().last_seeded_token = String::new();
                    let _ = app.emit("lindo://state-updated", ());
                }
            } else if let Some(profile) = preloaded_user {
                let _ = self.sync_cookies_from_webkit_verified(true, true).await;
                self.commit_authenticated_user(app, profile);
            }

            let _ = self.sync_cookies_from_webkit_verified(false, true).await;
            let pass_detail = format!(
                "round={round}, panelShown={panel_shown}, elapsedMs={}, hasFreshCf={has_fresh_cf}, origin404={is_origin_404}, ready={ready}",
                loop_started.elapsed().as_millis()
            );
            emit_diag(
                app,
                "INFO",
                "AUTH",
                "VerifyPassed",
                if panel_shown {
                    "安全确认已完成并恢复请求通道"
                } else {
                    "静默安全确认已通过，自动恢复请求通道"
                },
                Some(&pass_detail),
            );
            cleared = true;
            break;
        }

        if !cleared && !backup_cfs.is_empty() {
            self.restore_cf_clearance_backup(&backup_cfs).await;
        }
        Ok(cleared)
    }

    async fn ensure_ready(&self, force_reload: bool) -> Result<(), String> {
        {
            let rt = self.inner.lock().unwrap();
            if rt.verifying {
                return Ok(());
            }
            if !force_reload && rt.initialized && rt.origin_ready && rt.last_seeded_token != "__unseeded__" {
                return Ok(());
            }
        }
        let _guard = self.bootstrap_lock.lock().await;
        {
            let rt = self.inner.lock().unwrap();
            if rt.verifying {
                return Ok(());
            }
            if !force_reload && rt.initialized && rt.origin_ready && rt.last_seeded_token != "__unseeded__" {
                return Ok(());
            }
        }
        let need_init = {
            let rt = self.inner.lock().unwrap();
            !rt.initialized
        };
        if need_init {
            native_call("init", json!({}), 10).await?;
            self.inner.lock().unwrap().initialized = true;
        }
        self.seed_session().await?;

        let status_obj = native_call("status", json!({}), 5).await.ok();
        let status_terminated = status_obj
            .as_ref()
            .and_then(|s| s.get("processTerminated")?.as_bool())
            .unwrap_or(false);
        let bridge_ua = status_obj
            .as_ref()
            .and_then(|s| s.get("bridgeUa")?.as_str())
            .unwrap_or("")
            .trim()
            .to_string();

        // Cloudflare binds `cf_clearance` to the exact User-Agent string.
        // Whenever the bridge UA changes (e.g. upgrading to authentic Safari UA or after a macOS Safari update),
        // purge any UA-mismatched `cf_clearance` in WKHTTPCookieStore once so startup never loops on 429/403 challenge!
        if !bridge_ua.is_empty() {
            self.inner.lock().unwrap().bridge_ua = bridge_ua.clone();
            let ua_changed = {
                let st = self.state.lock().unwrap();
                st.last_bridge_ua != bridge_ua
            };
            if ua_changed {
                let _ = self.purge_cf_clearance_for_reverify().await;
                self.cf_authority.lock().unwrap().reset();
                self.verify_coord.lock().unwrap().reset_on_session_change();
                {
                    let mut st = self.state.lock().unwrap();
                    let cleaned: Vec<String> = st
                        .raw_cookies
                        .split(';')
                        .map(|s| s.trim())
                        .filter(|s| !s.is_empty() && !s.starts_with("cf_clearance="))
                        .map(|s| s.to_string())
                        .collect();
                    st.raw_cookies = cleaned.join("; ");
                    st.cf_clearance_updated_at_ms = 0;
                    st.last_bridge_ua = bridge_ua.clone();
                    save_state(&st);
                }
                if let Some(app) = GLOBAL_APP_HANDLE.get() {
                    emit_diag(
                        app,
                        "INFO",
                        "AUTH",
                        "UaMigration",
                        "检测到浏览器指纹版本更新，已清理旧 cf_clearance 以重新建立可信会话",
                        Some(&format!("bridgeUa={bridge_ua}")),
                    );
                }
            }
        }

        let current_href = if force_reload || status_terminated {
            None
        } else {
            native_call("evaluate", json!({ "script": "location.href", "isolated": true }), 6)
                .await
                .ok()
                .and_then(|v| v.as_str().map(|s| s.to_string()))
        };
        let needs_nav = match current_href.as_deref() {
            None | Some("") | Some("about:blank") => true,
            Some(u) => !u.starts_with("https://linux.do"),
        };
        if (force_reload || status_terminated || needs_nav) && !self.inner.lock().unwrap().verifying {
            let _nav_guard = self.nav_lock.write().await;
            {
                let mut rt = self.inner.lock().unwrap();
                rt.origin_ready = false;
                rt.bootstrap_challenged = false;
            }
            // Bootstrap same-origin `https://linux.do` via lightweight `/about` HTML document:
            // establishes genuine `Sec-Fetch-Mode: navigate` HTML context (`__cf_bm` + `_forum_session` + `<meta name="csrf-token">`)
            // without loading heavy 30-topic image streams!
            native_call("navigate", json!({ "url": "https://linux.do/about" }), 10).await?;
            tokio::time::sleep(Duration::from_millis(80)).await;
            let deadline = Instant::now() + Duration::from_secs(12);
            let mut challenge_seen_since: Option<Instant> = None;
            while Instant::now() < deadline {
                if self.inner.lock().unwrap().verifying {
                    break;
                }
                let st_obj = native_call("status", json!({}), 5).await.ok();
                let is_loading = st_obj
                    .as_ref()
                    .and_then(|s| s.get("loading")?.as_bool())
                    .unwrap_or(false);
                let is_interactive = st_obj
                    .as_ref()
                    .and_then(|s| s.get("interactiveRequired")?.as_bool())
                    .unwrap_or(false);

                let boot_state = native_call(
                    "evaluate",
                    json!({
                        "isolated": true,
                        "script": r#"(() => {
                            if (location.origin !== 'https://linux.do') return { state: 'waiting' };
                            const explicitChallenge = !!document.querySelector('#challenge-running, #challenge-stage, #challenge-form, .cf-turnstile, #turnstile-wrapper, iframe[src*="challenges.cloudflare.com"]')
                                || /^(Just a moment|请稍候|Attention Required|Security Check)/i.test(document.title || '');
                            if (explicitChallenge) return { state: 'challenge' };
                            const tok = document.querySelector('meta[name="csrf-token"]')?.content || '';
                            const hasPreloaded = !!document.getElementById('data-preloaded');
                            const hasDiscourseDom = hasPreloaded || !!document.querySelector('header.d-header, #main-outlet, #d-splash');
                            if (tok || hasDiscourseDom) {
                                return { state: 'ready', csrf: tok };
                            }
                            if (document.readyState === 'complete' && location.pathname.indexOf('/about') === 0) {
                                return { state: 'ready', csrf: '' };
                            }
                            return { state: 'waiting' };
                        })()"#
                    }),
                    5,
                )
                .await
                .ok();

                if let Some(ref obj) = boot_state {
                    match obj.get("state").and_then(|v| v.as_str()) {
                        Some("ready") => {
                            let tok_val = obj.get("csrf").and_then(|v| v.as_str()).unwrap_or("");
                            let quiet_js = format!(
                                "(() => {{ if ({tok}.length > 0) {{ window.__lindoCsrf = {tok}; window.__lindoIgnoreDomCsrf = false; }} try {{ const sp = document.getElementById('d-splash'); if (sp) sp.remove(); if (window.MessageBus && typeof window.MessageBus.stop === 'function') window.MessageBus.stop(); }} catch (_) {{}} return true; }})()",
                                tok = json!(tok_val)
                            );
                            let _ = native_call("evaluate", json!({ "script": quiet_js }), 4).await;
                            let mut rt = self.inner.lock().unwrap();
                            rt.origin_ready = true;
                            rt.bootstrap_challenged = false;
                            break;
                        }
                        Some("challenge") => {
                            let first_at = *challenge_seen_since.get_or_insert_with(Instant::now);
                            // Give Cloudflare's invisible JS challenge up to ~3.2s to auto-pass before flagging `bootstrap_challenged`
                            if is_interactive || (!is_loading && first_at.elapsed() >= Duration::from_millis(3200)) {
                                let mut rt = self.inner.lock().unwrap();
                                rt.origin_ready = false;
                                rt.bootstrap_challenged = true;
                                break;
                            }
                        }
                        _ => {}
                    }
                }
                tokio::time::sleep(Duration::from_millis(90)).await;
            }
        } else if !needs_nav {
            let mut rt = self.inner.lock().unwrap();
            rt.origin_ready = true;
            rt.bootstrap_challenged = false;
        }
        Ok(())
    }

    async fn seed_session(&self) -> Result<(), String> {
        self.seed_session_verified(false).await
    }

    async fn seed_session_verified(&self, verify_browser_store: bool) -> Result<(), String> {
        let (saved_raw, fallback_acct_cookies, has_current_user) = {
            let st = self.state.lock().unwrap();
            let fb = st.current_user.as_ref().and_then(|u| {
                st.saved_accounts
                    .iter()
                    .find(|a| !a.needs_login && a.profile.username.eq_ignore_ascii_case(&u.username))
                    .map(|a| a.cookies.clone())
            });
            (st.raw_cookies.clone(), fb, st.current_user.is_some())
        };
        let mut saved_map = Self::parse_cookie_map(&saved_raw);
        if !saved_map.contains_key("_t") {
            if let Some(ref fb_raw) = fallback_acct_cookies {
                for (k, v) in Self::parse_cookie_map(fb_raw) {
                    saved_map.entry(k).or_insert(v);
                }
            }
        }
        let app_token = if has_current_user {
            saved_map
                .get("_t")
                .cloned()
                .filter(|v| !v.is_empty() && v != "demo_session_token")
        } else {
            None
        };

        if !verify_browser_store {
            let rt = self.inner.lock().unwrap();
            if rt.last_seeded_token == app_token.as_deref().unwrap_or("") {
                return Ok(());
            }
        }

        let current = native_call("cookies", json!({}), 10).await?;
        let cookies_arr = current.get("cookies").and_then(|c| c.as_array());
        let browser_has_cf = cookies_arr
            .map(|arr| {
                arr.iter().any(|item| {
                    item.get("name").and_then(|n| n.as_str()) == Some("cf_clearance")
                        && !item
                            .get("value")
                            .and_then(|v| v.as_str())
                            .unwrap_or("")
                            .is_empty()
                })
            })
            .unwrap_or(false);

        let (t_count, has_dot_domain_t, fs_count, has_dot_domain_fs) = cookies_arr
            .map(|arr| {
                let mut t_cnt = 0usize;
                let mut t_dot = false;
                let mut f_cnt = 0usize;
                let mut f_dot = false;
                for item in arr {
                    let val_non_empty = !item.get("value").and_then(|v| v.as_str()).unwrap_or("").is_empty();
                    if !val_non_empty {
                        continue;
                    }
                    let host_only = item.get("hostOnly").and_then(|h| h.as_bool()).unwrap_or(true);
                    match item.get("name").and_then(|n| n.as_str()) {
                        Some("_t") => {
                            t_cnt += 1;
                            if !host_only {
                                t_dot = true;
                            }
                        }
                        Some("_forum_session") => {
                            f_cnt += 1;
                            if !host_only {
                                f_dot = true;
                            }
                        }
                        _ => {}
                    }
                }
                (t_cnt, t_dot, f_cnt, f_dot)
            })
            .unwrap_or((0, false, 0, false));

        if !has_current_user {
            if t_count > 0 {
                self.sweep_auth_cookies(true, None).await;
            } else if fs_count > 1 || has_dot_domain_fs {
                self.sweep_auth_cookies(false, None).await;
            }
            self.inner.lock().unwrap().last_seeded_token = String::new();
            return Ok(());
        }

        if t_count > 1 || has_dot_domain_t || fs_count > 1 || has_dot_domain_fs {
            self.sweep_auth_cookies(false, app_token.as_deref()).await;
        }

        let browser_token = cookies_arr.and_then(|arr| {
            arr.iter()
                .rev()
                .find_map(|item| {
                    if item.get("name")?.as_str()? == "_t"
                        && item.get("hostOnly").and_then(|h| h.as_bool()) == Some(true)
                    {
                        let val = item.get("value")?.as_str()?.to_string();
                        if !val.is_empty() {
                            return Some(val);
                        }
                    }
                    None
                })
                .or_else(|| {
                    arr.iter().rev().find_map(|item| {
                        if item.get("name")?.as_str()? == "_t" {
                            let val = item.get("value")?.as_str()?.to_string();
                            if !val.is_empty() {
                                return Some(val);
                            }
                        }
                        None
                    })
                })
        });

        // Preserve WebKit/WebView2's live rotated `_t` when present; otherwise reliably restore `_t` from persisted session!
        if let Some(bt) = browser_token {
            self.inner.lock().unwrap().last_seeded_token = bt.clone();
            if app_token.as_deref() != Some(bt.as_str()) || browser_has_cf {
                let _ = self.sync_cookies_from_webkit(false).await;
            }
            return Ok(());
        }

        let mut to_set = Vec::new();
        if let Some(val) = saved_map.get("_t") {
            if !val.is_empty() && val != "demo_session_token" {
                to_set.push(json!({ "name": "_t", "value": val }));
            }
        }
        // Only seed persisted `_forum_session` when WKHTTPCookieStore has no live `_forum_session` yet,
        // avoiding overwriting a fresh Turnstile/CSRF session cookie with an older disk copy!
        if fs_count == 0 {
            if let Some(val) = saved_map.get("_forum_session") {
                if !val.is_empty() && val != "demo_session_token" {
                    to_set.push(json!({ "name": "_forum_session", "value": val }));
                }
            }
        }
        if !to_set.is_empty() {
            native_call("setCookies", json!({ "cookies": to_set }), 10).await?;
        }
        self.inner.lock().unwrap().last_seeded_token = app_token.unwrap_or_default();
        Ok(())
    }

    pub async fn sync_cookies_from_webkit(&self, expect_auth: bool) -> Result<bool, String> {
        self.sync_cookies_from_webkit_verified(expect_auth, false)
            .await
    }

    pub async fn sync_cookies_from_webkit_verified(
        &self,
        expect_auth: bool,
        verified: bool,
    ) -> Result<bool, String> {
        let mut cookies_val = native_call("cookies", json!({}), 10).await?;
        let inspect_t = |val: &Value| -> (usize, bool) {
            val.get("cookies")
                .and_then(|c| c.as_array())
                .map(|arr| {
                    let mut cnt = 0usize;
                    let mut dot = false;
                    for i in arr {
                        if i.get("name").and_then(|n| n.as_str()) == Some("_t")
                            && !i.get("value").and_then(|v| v.as_str()).unwrap_or("").is_empty()
                        {
                            cnt += 1;
                            if i.get("hostOnly").and_then(|h| h.as_bool()) == Some(false) {
                                dot = true;
                            }
                        }
                    }
                    (cnt, dot)
                })
                .unwrap_or((0, false))
        };
        let (mut t_cnt, mut t_dot) = inspect_t(&cookies_val);
        if expect_auth && t_cnt == 0 {
            tokio::time::sleep(Duration::from_millis(160)).await;
            cookies_val = native_call("cookies", json!({}), 10).await?;
            let pair = inspect_t(&cookies_val);
            t_cnt = pair.0;
            t_dot = pair.1;
        }
        // Only sweep Discourse `_t` if duplicates or legacy `.linux.do` domain variants actually exist
        if t_cnt > 1 || t_dot {
            self.sweep_auth_cookies(false, None).await;
            cookies_val = native_call("cookies", json!({}), 10).await?;
        }

        let allowed = ["_t", "_forum_session", "cf_clearance", "__cf_bm", "_cfuvid"];
        let mut deduped: HashMap<String, (String, bool)> = HashMap::new();
        let mut cf_candidates: Vec<(String, bool)> = Vec::new();

        if let Some(arr) = cookies_val.get("cookies").and_then(|c| c.as_array()) {
            for item in arr {
                let Some(name) = item.get("name").and_then(|n| n.as_str()) else { continue };
                let Some(value) = item.get("value").and_then(|v| v.as_str()) else { continue };
                if value.is_empty() || !allowed.contains(&name) {
                    continue;
                }
                let host_only = item.get("hostOnly").and_then(|h| h.as_bool()).unwrap_or(true);
                if name == "cf_clearance" {
                    cf_candidates.push((value.to_string(), host_only));
                    continue;
                }
                let is_cf_domain_cookie = matches!(name, "__cf_bm" | "_cfuvid");
                let replace = match deduped.get(name) {
                    None => true,
                    // Discourse _t / _forum_session prefer host_only=true; Cloudflare __cf_bm / _cfuvid prefer host_only=false (.linux.do)!
                    Some((_, prev_host_only)) => {
                        if is_cf_domain_cookie {
                            !host_only || *prev_host_only
                        } else {
                            host_only || !*prev_host_only
                        }
                    }
                };
                if replace {
                    deduped.insert(name.to_string(), (value.to_string(), host_only));
                }
            }
        }

        let now_ms = now_epoch_ms();
        let (winning_cf, cf_ts_ms) = {
            let mut cf_auth = self.cf_authority.lock().unwrap();
            let win = cf_auth.select_winner(&cf_candidates, verified, now_ms);
            (win, cf_auth.updated_at_ms)
        };
        if let Some(cf_val) = winning_cf {
            deduped.insert("cf_clearance".to_string(), (cf_val, false));
        }

        let mut pairs = Vec::new();
        let mut auth_pairs = Vec::new();
        let found_t = deduped.get("_t").map(|(v, _)| v.clone());
        for name in allowed {
            if let Some((value, _)) = deduped.get(name) {
                pairs.push(format!("{name}={value}"));
                if name == "_t" || name == "_forum_session" {
                    auth_pairs.push(format!("{name}={value}"));
                }
            }
        }

        if let Some(ref t) = found_t {
            self.inner.lock().unwrap().last_seeded_token = t.clone();
        }

        if !pairs.is_empty() {
            let mut missing_t_to_reseed: Vec<Value> = Vec::new();
            {
                let mut st = self.state.lock().unwrap();
                // 严禁在 WKWebView 瞬态仅返回 cf_clearance / __cf_bm 而漏读 _t 时覆盖抹除已登录用户的持久化 _t！
                if found_t.is_none() && st.current_user.is_some() {
                    let prev_map = Self::parse_cookie_map(&st.raw_cookies);
                    for auth_name in ["_t", "_forum_session"] {
                        if deduped.contains_key(auth_name) {
                            continue;
                        }
                        if let Some(saved_val) = prev_map.get(auth_name) {
                            if !saved_val.is_empty() && saved_val != "demo_session_token" {
                                pairs.push(format!("{auth_name}={saved_val}"));
                                missing_t_to_reseed.push(json!({ "name": auth_name, "value": saved_val }));
                            }
                        }
                    }
                }
                st.raw_cookies = pairs.join("; ");
                if cf_ts_ms > 0 && deduped.contains_key("cf_clearance") {
                    st.cf_clearance_updated_at_ms = cf_ts_ms;
                }
                if found_t.is_some() {
                    if let Some(user) = st.current_user.clone() {
                        let auth_cookie_str = auth_pairs.join("; ");
                        Self::upsert_saved_account(&mut st, user, auth_cookie_str, false);
                    }
                }
                save_state(&st);
            }
            if !missing_t_to_reseed.is_empty() {
                let _ = native_call("setCookies", json!({ "cookies": missing_t_to_reseed }), 8).await;
            }
        }
        Ok(found_t.is_some())
    }

    fn upsert_saved_account(
        st: &mut PersistedState,
        profile: UserProfile,
        cookies: String,
        needs_login: bool,
    ) {
        if profile.username.is_empty() || profile.username == "linuxdo_user" {
            return;
        }
        if let Some(existing) = st
            .saved_accounts
            .iter_mut()
            .find(|a| a.profile.username.eq_ignore_ascii_case(&profile.username))
        {
            existing.profile = profile;
            if !cookies.is_empty() || needs_login {
                existing.cookies = cookies;
            }
            existing.needs_login = needs_login;
        } else {
            st.saved_accounts.push(SavedAccountEntry {
                profile,
                cookies,
                needs_login,
                read_floors: HashMap::new(),
            });
        }
    }

    pub async fn request(
        &self,
        app: &AppHandle,
        url: &str,
        method: &str,
        form: Option<HashMap<String, String>>,
        headers: Option<HashMap<String, String>>,
    ) -> Result<SiteHttpResponse, String> {
        self.request_unlocked(app, url, method, form, headers, false).await
    }

    async fn request_unlocked(
        &self,
        app: &AppHandle,
        url: &str,
        method: &str,
        form: Option<HashMap<String, String>>,
        headers: Option<HashMap<String, String>>,
        in_verification: bool,
    ) -> Result<SiteHttpResponse, String> {
        if !Self::is_allowed_forum_url(url) {
            return Err("Blocked non-forum URL".to_string());
        }
        let session_gen = self.session_generation.load(Ordering::Acquire);
        let method_upper = method.to_uppercase();
        let is_logged_in = { self.state.lock().unwrap().current_user.is_some() };
        let mut safe_headers = HashMap::new();
        safe_headers.insert(
            "Accept".to_string(),
            "application/json, text/javascript, */*; q=0.01".to_string(),
        );
        let mut is_background = false;
        let mut expects_html = false;
        let mut force_retry = false;
        let mut custom_referrer = "https://linux.do/".to_string();
        if let Some(h) = headers {
            for (k, v) in h {
                let lower = k.to_lowercase();
                if lower == "x-lindo-force-retry" {
                    force_retry = v == "1" || v.eq_ignore_ascii_case("true");
                    continue;
                }
                if lower == "x-lindo-referer" {
                    if v.starts_with("https://linux.do/") {
                        custom_referrer = v;
                    }
                    continue;
                }
                if lower == "discourse-background" && v == "true" {
                    is_background = true;
                }
                if lower == "accept" && v.to_lowercase().starts_with("text/html") {
                    expects_html = true;
                }
                if !["cookie", "host", "origin", "referer", "user-agent", "content-length"]
                    .contains(&lower.as_str())
                {
                    safe_headers.insert(k, v);
                }
            }
        }

        let is_timings_req = url.contains("/topics/timings");
        let is_write = method_upper != "GET" && method_upper != "HEAD";
        let req_priority = if is_background || is_timings_req {
            RequestPriority::Low
        } else if is_write {
            RequestPriority::High
        } else {
            RequestPriority::Normal
        };

        let mut pending_cooldown_wait: Option<Duration> = None;
        let mut cooldown_wait_ms: u64 = 0;
        let mut pace_wait_ms: u64 = 0;
        let mut retried = false;
        let mut silent_cf_revalidated = false;
        let mut verify_attempted = false;
        let mut verify_round: Option<u64> = None;
        let mut verify_cooldown_ms: u64 = 0;
        let mut ineffective_clearance = false;
        let mut joined_active_verify = false;

        {
            let mut rt = self.inner.lock().unwrap();
            if !in_verification {
                if rt.verifying {
                    if is_background || is_timings_req {
                        return Ok(SiteHttpResponse {
                            status: 403,
                            body: format!("{CF_CHALLENGE_PREFIX}verifying"),
                            is_challenge: true,
                            is_unauthenticated: false,
                            rate_limited_ms: None,
                            diag: Some(SiteHttpDiag {
                                local_cooldown: true,
                                ..Default::default()
                            }),
                        });
                    }
                    joined_active_verify = true;
                }
                if !joined_active_verify {
                    let active_until = if is_background {
                        rt.bg_challenge_until
                    } else {
                        rt.fg_challenge_until
                    };
                    if let Some(until) = active_until {
                        if Instant::now() < until {
                            return Ok(SiteHttpResponse {
                                status: 403,
                                body: format!("{CF_CHALLENGE_PREFIX}cooldown"),
                                is_challenge: true,
                                is_unauthenticated: false,
                                rate_limited_ms: None,
                                diag: Some(SiteHttpDiag {
                                    local_cooldown: true,
                                    ..Default::default()
                                }),
                            });
                        }
                    }
                }
                if let Some(until) = rt.rate_limit_until {
                    let now = Instant::now();
                    if now < until {
                        let remain_dur = until.saturating_duration_since(now);
                        let remain_ms = remain_dur.as_millis() as u64;
                        let can_force_probe = !is_background
                            && force_retry
                            && rt
                                .last_429_probe_at
                                .map(|t| now.saturating_duration_since(t) >= Duration::from_millis(1800))
                                .unwrap_or(true);
                        if can_force_probe {
                            rt.last_429_probe_at = Some(now);
                        } else if !is_background && remain_ms <= 6500 {
                            // Short rate-limit window: hold quietly in normal Loading state & auto-dispatch once cooldown ends!
                            pending_cooldown_wait = Some(remain_dur);
                            cooldown_wait_ms = remain_ms;
                        } else {
                            return Ok(SiteHttpResponse {
                                status: 429,
                                body: r#"{"errors":["请求较频繁，请稍后再试"],"localCooldown":true}"#
                                    .to_string(),
                                is_challenge: false,
                                is_unauthenticated: false,
                                rate_limited_ms: Some(remain_ms),
                                diag: Some(SiteHttpDiag {
                                    local_cooldown: true,
                                    ..Default::default()
                                }),
                            });
                        }
                    }
                }
            }
        }

        // Foreground request arrived while another round is verifying: join the single-flight round
        // (monotonically escalating Silent -> Blocking if needed) instead of failing immediately!
        if joined_active_verify {
            let is_dark = self.current_is_dark();
            let outcome = self
                .coordinate_cf_verification(app, VerifyPresentation::Blocking, is_dark, false)
                .await;
            verify_attempted = true;
            if outcome.round > 0 {
                verify_round = Some(outcome.round);
            }
            if !outcome.passed {
                return Ok(SiteHttpResponse {
                    status: 403,
                    body: format!("{CF_CHALLENGE_PREFIX}verifying"),
                    is_challenge: true,
                    is_unauthenticated: false,
                    rate_limited_ms: None,
                    diag: Some(SiteHttpDiag {
                        local_cooldown: true,
                        verify_attempted: true,
                        verify_round,
                        verify_cooldown_ms: outcome.cooldown_remain_ms,
                        ineffective_clearance: outcome.ineffective_blocked,
                        ..Default::default()
                    }),
                });
            }
            if self.session_generation.load(Ordering::Acquire) != session_gen {
                return Err("账号会话已切换，请求已取消".to_string());
            }
        }

        if let Some(wait_dur) = pending_cooldown_wait {
            tokio::time::sleep(wait_dur).await;
            if !in_verification && self.session_generation.load(Ordering::Acquire) != session_gen {
                return Err("账号会话已切换，请求已取消".to_string());
            }
        }

        if !expects_html {
            safe_headers
                .entry("X-Requested-With".to_string())
                .or_insert_with(|| "XMLHttpRequest".to_string());
            if is_timings_req {
                safe_headers
                    .entry("Discourse-Background".to_string())
                    .or_insert_with(|| "true".to_string());
                safe_headers
                    .entry("X-SILENCE-LOGGER".to_string())
                    .or_insert_with(|| "true".to_string());
            } else if !is_background {
                safe_headers
                    .entry("Discourse-Present".to_string())
                    .or_insert_with(|| "true".to_string());
            }
            if is_logged_in {
                safe_headers
                    .entry("Discourse-Logged-In".to_string())
                    .or_insert_with(|| "true".to_string());
            }
        }

        let cfg = json!({
            "url": url,
            "method": method_upper,
            "headers": safe_headers,
            "form": form,
            "referrer": custom_referrer
        });

        let script = format!(
            r#"(async () => {{
                try {{
                    const c = {cfg};
                    if (location.origin !== 'https://linux.do') return {{ originLost: true }};
                    const write = !['GET', 'HEAD'].includes(c.method);
                    const isOtp = c.url.indexOf('/session/otp/') !== -1;
                    const isTimings = c.url.indexOf('/topics/timings') !== -1;
                    const reqTimeoutMs = isTimings ? 6000 : (write ? 15000 : 10000);
                    const reqReferrer = c.referrer || 'https://linux.do/';
                    const readResetHdr = (hdrs) =>
                        hdrs.get('x-ratelimit-reset') || hdrs.get('ratelimit-reset') || hdrs.get('x-rate-limit-reset');
                    async function fetchFreshCsrf() {{
                        if (window.__lindoCsrfPromise) {{
                            return await window.__lindoCsrfPromise;
                        }}
                        window.__lindoCsrfPromise = (async () => {{
                            try {{
                                const cr = await fetch('/session/csrf.json', {{
                                    credentials: 'include',
                                    cache: 'no-store',
                                    referrer: reqReferrer,
                                    referrerPolicy: 'strict-origin-when-cross-origin',
                                    headers: {{ 'Accept': 'application/json, text/javascript, */*; q=0.01', 'X-Requested-With': 'XMLHttpRequest', 'Discourse-Present': 'true' }},
                                    signal: AbortSignal.timeout(10000)
                                }});
                                if (!cr.ok) {{
                                    return {{
                                        err: {{
                                            status: cr.status,
                                            body: await cr.text(),
                                            retryAfter: cr.headers.get('Retry-After'),
                                            rateLimitReset: readResetHdr(cr.headers),
                                            challenge: cr.headers.get('cf-mitigated'),
                                            cfRay: cr.headers.get('cf-ray'),
                                            contentType: cr.headers.get('content-type')
                                        }}
                                    }};
                                }}
                                const tok = (await cr.json()).csrf;
                                if (tok) window.__lindoCsrf = tok;
                                return {{ token: tok }};
                            }} finally {{
                                window.__lindoCsrfPromise = null;
                            }}
                        }})();
                        return await window.__lindoCsrfPromise;
                    }}
                    if (write) {{
                        let token = isOtp
                            ? null
                            : (window.__lindoCsrf || (!window.__lindoIgnoreDomCsrf && document.querySelector('meta[name="csrf-token"]')?.content));
                        if (!token) {{
                            const fc = await fetchFreshCsrf();
                            if (fc.err) return fc.err;
                            token = fc.token;
                        }}
                        if (!token) return {{ status: 422, body: '{{"errors":["会话尚未就绪，请稍后再试"]}}' }};
                        c.headers['X-CSRF-Token'] = token;
                    }}
                    const noStore = /\/session\/(csrf|current)\.json|\/notifications|\/drafts/i.test(c.url);
                    let r = await fetch(c.url, {{
                        method: c.method,
                        headers: c.headers,
                        credentials: 'include',
                        referrer: reqReferrer,
                        referrerPolicy: 'strict-origin-when-cross-origin',
                        cache: noStore ? 'no-store' : 'default',
                        body: c.form ? new URLSearchParams(c.form) : undefined,
                        signal: AbortSignal.timeout(reqTimeoutMs)
                    }});
                    let bodyText = await r.text();
                    let cfHeader = r.headers.get('cf-mitigated');
                    let csrfRefreshed = false;
                    if (!write && !cfHeader && (r.status === 403 || r.status === 401) && c.headers['Discourse-Logged-In'] && !c.url.includes('/session/current.json') && /not_logged_in|need to be logged in/i.test(bodyText)) {{
                        const guestHdrs = Object.assign({{}}, c.headers);
                        delete guestHdrs['Discourse-Logged-In'];
                        const gr = await fetch(c.url, {{
                            method: c.method,
                            headers: guestHdrs,
                            credentials: 'include',
                            referrer: reqReferrer,
                            referrerPolicy: 'strict-origin-when-cross-origin',
                            cache: 'no-store',
                            signal: AbortSignal.timeout(15000)
                        }});
                        if (gr.ok) {{
                            return {{
                                status: gr.status,
                                body: await gr.text(),
                                retryAfter: gr.headers.get('Retry-After'),
                                rateLimitReset: readResetHdr(gr.headers),
                                challenge: gr.headers.get('cf-mitigated'),
                                cfRay: gr.headers.get('cf-ray'),
                                contentType: gr.headers.get('content-type'),
                                requestId: gr.headers.get('x-request-id'),
                                hadUnauthLoggedHeader: true,
                                guestFallback: true
                            }};
                        }}
                    }}
                    if (write && !isOtp && !cfHeader && (r.status === 403 || r.status === 422) && /csrf|authenticity token/i.test(bodyText)) {{
                        window.__lindoCsrf = null;
                        window.__lindoIgnoreDomCsrf = true;
                        const fc2 = await fetchFreshCsrf();
                        if (fc2.token) {{
                            csrfRefreshed = true;
                            c.headers['X-CSRF-Token'] = fc2.token;
                            r = await fetch(c.url, {{
                                method: c.method,
                                headers: c.headers,
                                credentials: 'include',
                                referrer: reqReferrer,
                                referrerPolicy: 'strict-origin-when-cross-origin',
                                cache: 'no-store',
                                body: c.form ? new URLSearchParams(c.form) : undefined,
                                signal: AbortSignal.timeout(15000)
                            }});
                            bodyText = await r.text();
                            cfHeader = r.headers.get('cf-mitigated');
                        }}
                    }}
                    return {{
                        status: r.status,
                        body: bodyText,
                        retryAfter: r.headers.get('Retry-After'),
                        rateLimitReset: readResetHdr(r.headers),
                        challenge: cfHeader,
                        cfRay: r.headers.get('cf-ray'),
                        contentType: r.headers.get('content-type'),
                        requestId: r.headers.get('x-request-id'),
                        csrfRefreshed
                    }};
                }} catch (err) {{
                    return {{
                        netErr: String((err && err.message) || err || 'Network error'),
                        originLost: location.origin !== 'https://linux.do'
                    }};
                }}
            }})()"#
        );

        let idempotent = !in_verification && (method_upper == "GET" || method_upper == "HEAD");
        let mut budget = AttemptBudget::for_request(idempotent);

        // Execute fetch inside scoped `account_lock.read()` + `PriorityGate` (always released before verification!)
        let eval_res: Value = loop {
            let _acct = if in_verification {
                None
            } else {
                Some(self.account_lock.read().await)
            };
            if !in_verification && self.session_generation.load(Ordering::Acquire) != session_gen {
                return Err("账号会话已切换，请求已取消".to_string());
            }

            let _permit = if in_verification {
                None
            } else {
                Some(self.priority_gate.acquire(req_priority).await)
            };
            let _fg_guard = if !in_verification && !is_background {
                Some(FgRequestGuard::enter(&self.active_fg_requests))
            } else {
                None
            };

            if !in_verification {
                let pace_wait = {
                    let min_floor = if is_background || is_timings_req {
                        1.6
                    } else {
                        0.0
                    };
                    self.api_pacer
                        .lock()
                        .unwrap()
                        .reserve(Instant::now(), min_floor)
                };
                if !pace_wait.is_zero() {
                    pace_wait_ms += pace_wait.as_millis() as u64;
                    tokio::time::sleep(pace_wait).await;
                }
                self.ensure_ready(false).await?;
                let (verifying_now, boot_challenged) = {
                    let rt = self.inner.lock().unwrap();
                    (rt.verifying, rt.bootstrap_challenged)
                };
                if verifying_now || boot_challenged {
                    if boot_challenged && !is_background && method_upper == "GET" {
                        if !url.contains("/session/") && !url.contains("/user-api-key/") {
                            self.inner.lock().unwrap().last_challenge_url = Some(url.to_string());
                        }
                    }
                    if !is_background && !is_timings_req && !url.contains("/session/otp/") && budget.try_consume_cf() {
                        // Drop concurrency permit & account lock before coordinating verification!
                        drop(_fg_guard);
                        drop(_permit);
                        drop(_acct);
                        let is_dark = self.current_is_dark();
                        self.emit_net_health_diag(
                            app,
                            "WARN",
                            "cf_challenge_detected",
                            Some(&format!("phase=bootstrap method={method_upper} url={url}")),
                        );
                        let outcome = self
                            .coordinate_cf_verification(app, VerifyPresentation::Blocking, is_dark, false)
                            .await;
                        verify_attempted = true;
                        if outcome.round > 0 {
                            verify_round = Some(outcome.round);
                        }
                        if outcome.passed {
                            silent_cf_revalidated = true;
                            continue;
                        }
                        return Ok(SiteHttpResponse {
                            status: 403,
                            body: format!("{CF_CHALLENGE_PREFIX}bootstrap"),
                            is_challenge: true,
                            is_unauthenticated: false,
                            rate_limited_ms: None,
                            diag: Some(SiteHttpDiag {
                                local_cooldown: true,
                                pace_wait_ms,
                                cooldown_wait_ms,
                                verify_attempted: true,
                                verify_round,
                                verify_cooldown_ms: outcome.cooldown_remain_ms,
                                ineffective_clearance: outcome.ineffective_blocked,
                                ..Default::default()
                            }),
                        });
                    }
                    return Ok(SiteHttpResponse {
                        status: 403,
                        body: format!("{CF_CHALLENGE_PREFIX}verifying"),
                        is_challenge: true,
                        is_unauthenticated: false,
                        rate_limited_ms: None,
                        diag: Some(SiteHttpDiag {
                            local_cooldown: true,
                            pace_wait_ms,
                            cooldown_wait_ms,
                            ..Default::default()
                        }),
                    });
                }
            }

            let raw_eval = if in_verification {
                native_call("evaluate", json!({ "script": script }), 16).await
            } else {
                let _nav = self.nav_lock.read().await;
                native_call("evaluate", json!({ "script": script }), 16).await
            };

            // Check transient network/origin failure or retryable 502/503/504 (STRICTLY idempotent GET/HEAD only!)
            let (is_net_or_origin_err, is_origin_lost, is_transient_5xx) = match &raw_eval {
                Ok(v) if v.is_object() => {
                    let origin_lost = v.get("originLost").and_then(|b| b.as_bool()) == Some(true);
                    let net_err = v.get("netErr").is_some();
                    let st = v.get("status").and_then(|s| s.as_u64()).unwrap_or(0) as u16;
                    let cf = v.get("challenge").and_then(|s| s.as_str()).unwrap_or("");
                    let ct = v.get("contentType").and_then(|s| s.as_str()).unwrap_or("");
                    let bd = v.get("body").and_then(|s| s.as_str()).unwrap_or("");
                    let is_cf = Self::is_cf_challenge_response(st, cf, ct, bd);
                    (
                        origin_lost || net_err,
                        origin_lost,
                        !origin_lost && !net_err && is_transient_retryable_status(st, is_cf),
                    )
                }
                _ => (true, true, false),
            };

            if (is_net_or_origin_err || is_transient_5xx)
                && idempotent
                && !self.inner.lock().unwrap().verifying
            {
                if let Some(attempt_idx) = budget.try_consume_transient() {
                    retried = true;
                    if is_origin_lost {
                        self.inner.lock().unwrap().origin_ready = false;
                        let _ = self.ensure_ready(true).await;
                    } else {
                        let wait_ms = transient_backoff_ms(attempt_idx, rand::random::<f64>());
                        // Release permit during backoff sleep so other lanes keep moving!
                        drop(_fg_guard);
                        drop(_permit);
                        drop(_acct);
                        tokio::time::sleep(Duration::from_millis(wait_ms)).await;
                    }
                    continue;
                }
            }

            let val = raw_eval?;
            if val.get("originLost").and_then(|v| v.as_bool()) == Some(true) {
                return Err("内置网页上下文正在唤醒，请重试".to_string());
            }
            if let Some(net_err) = val.get("netErr").and_then(|v| v.as_str()) {
                return Err(format!("网络请求超时或中断: {net_err}"));
            }

            // Unified single-flight foreground CF challenge recovery (Silent -> seamless Blocking escalation on the SAME `/challenge` page!)
            if !in_verification && !is_background && !is_timings_req && !url.contains("/session/otp/") {
                let st = val.get("status").and_then(|v| v.as_u64()).unwrap_or(0) as u16;
                let cf = val.get("challenge").and_then(|v| v.as_str()).unwrap_or("");
                let ct = val.get("contentType").and_then(|v| v.as_str()).unwrap_or("");
                let bd = val.get("body").and_then(|v| v.as_str()).unwrap_or("");
                if Self::is_cf_challenge_response(st, cf, ct, bd) {
                    let carried_cf = {
                        let raw = self.state.lock().unwrap().raw_cookies.clone();
                        Self::parse_cookie_map(&raw).remove("cf_clearance")
                    };
                    self.cf_authority
                        .lock()
                        .unwrap()
                        .note_challenged(carried_cf.as_deref());

                    if budget.try_consume_cf() {
                        // Drop concurrency permit & account lock BEFORE waiting on verification!
                        drop(_fg_guard);
                        drop(_permit);
                        drop(_acct);

                        self.emit_net_health_diag(
                            app,
                            "WARN",
                            "cf_challenge_detected",
                            Some(&format!("method={method_upper} url={url} status={st} cf={cf}")),
                        );
                        let is_dark = self.current_is_dark();
                        let outcome = self
                            .coordinate_cf_verification(app, VerifyPresentation::Blocking, is_dark, false)
                            .await;
                        verify_attempted = true;
                        if outcome.round > 0 {
                            verify_round = Some(outcome.round);
                        }
                        if outcome.passed {
                            silent_cf_revalidated = true;
                            if self.session_generation.load(Ordering::Acquire) != session_gen {
                                return Err("账号会话已切换，请求已取消".to_string());
                            }
                            let _ = native_call(
                                "evaluate",
                                json!({ "script": "(() => { window.__lindoCsrf = null; window.__lindoIgnoreDomCsrf = true; return true; })()" }),
                                4,
                            )
                            .await;
                            continue;
                        } else {
                            verify_cooldown_ms = outcome.cooldown_remain_ms;
                            ineffective_clearance = outcome.ineffective_blocked;
                            break val;
                        }
                    } else if let Some(passed_round) = verify_round {
                        // Verification just passed for `passed_round`, yet the immediate replay STILL hit CF challenge!
                        // Trip 60s ineffective-clearance circuit breaker to eliminate infinite popup loops!
                        let breaker_ms = self
                            .verify_coord
                            .lock()
                            .unwrap()
                            .record_ineffective_clearance(passed_round, Instant::now());
                        verify_cooldown_ms = breaker_ms;
                        ineffective_clearance = true;
                        self.emit_net_health_diag(
                            app,
                            "WARN",
                            "ineffective_clearance_breaker",
                            Some(&format!(
                                "round={passed_round} method={method_upper} url={url} status={st} breakerMs={breaker_ms}"
                            )),
                        );
                    }
                }
            }

            break val;
        };

        let status = eval_res
            .get("status")
            .and_then(|v| v.as_u64())
            .ok_or_else(|| "Empty WebKit fetch status".to_string())? as u16;
        let raw_body = eval_res
            .get("body")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        let cf_header = eval_res
            .get("challenge")
            .and_then(|v| v.as_str())
            .unwrap_or("");
        let raw_content_type = eval_res
            .get("contentType")
            .and_then(|v| v.as_str())
            .unwrap_or("");

        // Authoritative Cloudflare challenge detection (aligned with FluxDO `isCfChallengeResponse`):
        // - `403` / `429` / `503` with `cf-mitigated: challenge` (or HTML challenge page) is a REAL Cloudflare challenge:
        //   route into CF verification so dead/UA-mismatched `cf_clearance` gets rotated instead of looping on fake 429 countdowns!
        // - Genuine Discourse JSON 429 (heart/reply rate limit, `application/json`, no `cf-mitigated`) stays strictly in rate-limit handling.
        let raw_body_is_html = raw_body.trim_start().starts_with('<');
        let is_challenge =
            Self::is_cf_challenge_response(status, cf_header, raw_content_type, &raw_body);
        let is_challenge_429 = status == 429 && is_challenge;
        let body = if is_challenge {
            if raw_body.starts_with(CF_CHALLENGE_PREFIX) {
                raw_body
            } else {
                format!("{CF_CHALLENGE_PREFIX}{raw_body}")
            }
        } else if status == 429 && raw_body_is_html {
            "{\"errors\":[\"操作较为频繁，请稍候片刻再试\"]}".to_string()
        } else {
            raw_body
        };
        let had_unauth_logged_hdr = eval_res
            .get("hadUnauthLoggedHeader")
            .and_then(|v| v.as_bool())
            .unwrap_or(false);
        let is_unauth = Self::is_unauthenticated_response(status, &body);

        let mut rate_limited_ms = None;
        if is_challenge {
            let carried_cf = {
                let raw = self.state.lock().unwrap().raw_cookies.clone();
                Self::parse_cookie_map(&raw).remove("cf_clearance")
            };
            self.cf_authority
                .lock()
                .unwrap()
                .note_challenged(carried_cf.as_deref());
            let should_probe_bg = {
                let now = Instant::now();
                let mut rt = self.inner.lock().unwrap();
                rt.bg_challenge_until = Some(now + Duration::from_secs(30));
                if !is_background {
                    rt.fg_challenge_until = Some(now + Duration::from_millis(1200));
                    false
                } else {
                    !rt.verifying && !rt.cf_attention_notified
                }
            };
            // Background challenge: run silent probe through unified `VerifyCoordinator`; ONLY if human click is needed,
            // surface the non-intrusive 32px hairline bar (`lindo://cf-attention-needed`) without interrupting reading!
            if should_probe_bg {
                if let Some(eng) = app.try_state::<Arc<BridgeEngine>>() {
                    let eng_clone = Arc::clone(&eng);
                    let app_clone = app.clone();
                    tauri::async_runtime::spawn(async move {
                        let is_dark = eng_clone.current_is_dark();
                        let outcome = eng_clone
                            .coordinate_cf_verification(&app_clone, VerifyPresentation::Silent, is_dark, false)
                            .await;
                        let mut emit_attention = None;
                        {
                            let mut rt = eng_clone.inner.lock().unwrap();
                            if outcome.passed {
                                if rt.cf_attention_notified {
                                    rt.cf_attention_notified = false;
                                    emit_attention = Some(false);
                                }
                            } else if !rt.verifying && !rt.cf_attention_notified {
                                rt.cf_attention_notified = true;
                                emit_attention = Some(true);
                            }
                        }
                        if let Some(flag) = emit_attention {
                            let _ = app_clone.emit("lindo://cf-attention-needed", flag);
                        }
                    });
                }
            }
        } else if (200..300).contains(&status) {
            let now = Instant::now();
            self.api_pacer.lock().unwrap().on_success(now);
            if !is_background {
                self.verify_coord.lock().unwrap().reset_on_healthy_response();
            }
            let clear_banner = {
                let mut rt = self.inner.lock().unwrap();
                rt.rate_limit_until = None;
                rt.challenge_429_streak = 0;
                if !is_background {
                    rt.fg_challenge_until = None;
                    rt.bg_challenge_until = None;
                    rt.bootstrap_challenged = false;
                    if rt.cf_attention_notified {
                        rt.cf_attention_notified = false;
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            };
            if clear_banner {
                let _ = app.emit("lindo://cf-attention-needed", false);
            }
            if !is_background {
                let live_cf = {
                    let raw = self.state.lock().unwrap().raw_cookies.clone();
                    Self::parse_cookie_map(&raw).remove("cf_clearance")
                };
                if let Some(cf_val) = live_cf {
                    let ts = now_epoch_ms();
                    self.cf_authority.lock().unwrap().confirm_healthy(&cf_val, ts);
                }
            }
        }
        if status == 429 && !is_challenge {
            let retry_after_hdr = eval_res.get("retryAfter").and_then(|v| v.as_str());
            let reset_hdr = eval_res.get("rateLimitReset").and_then(|v| v.as_str());
            let now_epoch_secs = (now_epoch_ms() / 1000) as i64;
            let retry_after_secs =
                parse_retry_after_secs(retry_after_hdr, reset_hdr, &body, now_epoch_secs);
            let is_discourse_action_429 = method_upper != "GET" && !raw_body_is_html;
            // `/topics/timings` is a discardable background stat: never lock out main app browsing on timings 429!
            // Discourse JSON write-action 429s (e.g. reaction toggle / reply cooldown) are action-scoped:
            // surface exact wait seconds to the caller without triggering a global app-wide lockout!
            if is_discourse_action_429 {
                let now = Instant::now();
                self.api_pacer.lock().unwrap().on_rate_limit(now);
                let secs = retry_after_secs.unwrap_or(5).clamp(2, 120);
                rate_limited_ms = Some(secs * 1000);
            } else if !is_timings_req {
                let now = Instant::now();
                self.api_pacer.lock().unwrap().on_rate_limit(now);
                let secs = retry_after_secs.unwrap_or(3).clamp(2, 45);
                let dur = Duration::from_secs(secs);
                {
                    let mut rt = self.inner.lock().unwrap();
                    rt.rate_limit_until = Some(now + dur);
                    rt.last_429_probe_at = Some(now);
                }
                rate_limited_ms = Some(dur.as_millis() as u64);
            }
        }

        if !in_verification && !is_background && is_challenge && method_upper == "GET" {
            if !url.contains("/session/") && !url.contains("/user-api-key/") {
                self.inner.lock().unwrap().last_challenge_url = Some(url.to_string());
            }
        }

        // Sync rotated _t cookies synchronously on writes/auth, and asynchronously in background on periodic reads so UI never waits!
        if !in_verification && (200..400).contains(&status) && !is_challenge {
            let should_sync = {
                let mut rt = self.inner.lock().unwrap();
                let elapsed_ok = rt
                    .last_cookie_sync
                    .map(|t| t.elapsed() >= Duration::from_secs(20))
                    .unwrap_or(true);
                if !idempotent || url.contains("/session/") || elapsed_ok {
                    rt.last_cookie_sync = Some(Instant::now());
                    true
                } else {
                    false
                }
            };
            if should_sync {
                let expect_auth = url.contains("/session/otp/");
                if !idempotent || url.contains("/session/") {
                    let _ = self.sync_cookies_from_webkit(expect_auth).await;
                } else if let Some(eng) = app.try_state::<Arc<BridgeEngine>>() {
                    let eng_clone = Arc::clone(&eng);
                    tauri::async_runtime::spawn(async move {
                        let _ = eng_clone.sync_cookies_from_webkit(false).await;
                    });
                }
            }
        }

        // Throttled non-blocking passive session expiry check (eliminates 4x synchronous request storm per click when `_t` expires!)
        let session_404 = url.contains("/session/current.json") && status == 404 && !is_challenge;
        if !in_verification && (is_unauth || had_unauth_logged_hdr || session_404) {
            let should_check = {
                let has_user = self.state.lock().unwrap().current_user.is_some();
                if !has_user {
                    false
                } else {
                    let mut rt = self.inner.lock().unwrap();
                    let due = rt
                        .last_expiry_check
                        .map(|t| t.elapsed() >= Duration::from_secs(25))
                        .unwrap_or(true);
                    if due {
                        rt.last_expiry_check = Some(Instant::now());
                        true
                    } else {
                        false
                    }
                }
            };
            if should_check {
                if let Some(eng) = app.try_state::<Arc<BridgeEngine>>() {
                    let eng_clone = Arc::clone(&eng);
                    let app_clone = app.clone();
                    tauri::async_runtime::spawn(async move {
                        eng_clone.confirm_passive_expiry(&app_clone).await;
                    });
                }
            }
        }

        let cf_ray = eval_res
            .get("cfRay")
            .and_then(|v| v.as_str())
            .filter(|s| !s.trim().is_empty())
            .map(|s| s.trim().to_string());
        let retry_after = eval_res
            .get("retryAfter")
            .and_then(|v| v.as_str())
            .filter(|s| !s.trim().is_empty())
            .map(|s| s.trim().to_string());
        let content_type = eval_res
            .get("contentType")
            .and_then(|v| v.as_str())
            .filter(|s| !s.trim().is_empty())
            .map(|s| s.trim().to_string());
        let request_id = eval_res
            .get("requestId")
            .and_then(|v| v.as_str())
            .filter(|s| !s.trim().is_empty())
            .map(|s| s.trim().to_string());
        let csrf_refreshed = eval_res
            .get("csrfRefreshed")
            .and_then(|v| v.as_bool())
            .unwrap_or(false);
        let guest_fallback = eval_res
            .get("guestFallback")
            .and_then(|v| v.as_bool())
            .unwrap_or(false);
        let body_bytes = body.len();

        Ok(SiteHttpResponse {
            status,
            body,
            is_challenge,
            is_unauthenticated: is_unauth,
            rate_limited_ms,
            diag: Some(SiteHttpDiag {
                cf_ray,
                cf_mitigated: if cf_header.is_empty() {
                    None
                } else {
                    Some(cf_header.to_string())
                },
                retry_after,
                content_type,
                request_id,
                local_cooldown: false,
                is_challenge_429,
                pace_wait_ms,
                cooldown_wait_ms,
                retried,
                silent_cf_revalidated,
                verify_attempted,
                verify_round,
                verify_cooldown_ms,
                ineffective_clearance,
                csrf_refreshed,
                guest_fallback,
                body_bytes,
            }),
        })
    }

    async fn confirm_passive_expiry(&self, app: &AppHandle) {
        // 新登录完成后的 20s 保护期内，或站点验证/登录面板正在操作期间，绝不触碰共享 WKWebView 的 Cookie 与会话！
        {
            let rt = self.inner.lock().unwrap();
            if rt.verifying
                || rt
                    .last_login_at
                    .map(|t| t.elapsed() < Duration::from_secs(20))
                    .unwrap_or(false)
            {
                return;
            }
        }

        // 先对 WebKit 执行 _t 去重并强制校验磁盘中的 _t 已真实注入 WebKit
        self.sweep_auth_cookies(false, None).await;
        let _ = self.seed_session_verified(true).await;

        let check_current_json = || async {
            if self.inner.lock().unwrap().verifying {
                return None;
            }
            let script = r#"(async () => {
                if (location.origin !== 'https://linux.do') return { originLost: true };
                try {
                    const r = await fetch('/session/current.json', {
                        method: 'GET',
                        credentials: 'include',
                        cache: 'no-store',
                        referrer: 'https://linux.do/',
                        headers: { 'Accept': 'application/json, text/javascript, */*; q=0.01', 'X-Requested-With': 'XMLHttpRequest' },
                        signal: AbortSignal.timeout(8000)
                    });
                    return {
                        status: r.status,
                        body: await r.text(),
                        challenge: r.headers.get('cf-mitigated') || ''
                    };
                } catch (_) {
                    return { netErr: true };
                }
            })()"#;
            let val = native_call("evaluate", json!({ "script": script }), 12).await.ok()?;
            if val.get("originLost").and_then(|b| b.as_bool()) == Some(true)
                || val.get("netErr").and_then(|b| b.as_bool()) == Some(true)
            {
                return None;
            }
            let st = val.get("status").and_then(|v| v.as_u64())? as u16;
            let body = val.get("body").and_then(|v| v.as_str()).unwrap_or("").to_string();
            let cf = val.get("challenge").and_then(|v| v.as_str()).unwrap_or("");
            let is_chal = cf == "challenge" || Self::looks_like_challenge(&body);
            Some((st, body, is_chal))
        };

        // 1. 第一次复核 /session/current.json（遇到挑战/429/5xx 绝不踢出登录）
        let Some((st1, body1, chal1)) = check_current_json().await else {
            return;
        };
        if chal1 || st1 >= 500 || st1 == 429 {
            return;
        }
        let logged_in_1 = (200..300).contains(&st1)
            && serde_json::from_str::<Value>(&body1)
                .ok()
                .and_then(|v| v.get("current_user")?.get("username")?.as_str().map(|s| !s.is_empty()))
                .unwrap_or(false);
        if logged_in_1 {
            return;
        }

        // 2. 短暂退避后二次复核 /session/current.json（零 HTML 导航开销，避免触发 Cloudflare 整页挑战并准确识别失效 _t）
        tokio::time::sleep(Duration::from_millis(350)).await;
        let Some((st2, body2, chal2)) = check_current_json().await else {
            return;
        };
        if chal2 || st2 >= 500 || st2 == 429 || self.inner.lock().unwrap().verifying {
            return;
        };
        let logged_in_2 = (200..300).contains(&st2)
            && serde_json::from_str::<Value>(&body2)
                .ok()
                .and_then(|v| v.get("current_user")?.get("username")?.as_str().map(|s| !s.is_empty()))
                .unwrap_or(false);
        if logged_in_2 {
            return;
        }
        let confirmed_anon = st2 == 404
            || st2 == 401
            || ((200..300).contains(&st2) && !logged_in_2);
        if !confirmed_anon {
            return;
        }

        let mut changed = false;
        {
            let mut st = self.state.lock().unwrap();
            if let Some(prev) = st.current_user.take() {
                st.expired_username = Some(prev.username.clone());
                Self::upsert_saved_account(&mut st, prev, String::new(), true);
                // Preserve cf_clearance while removing dead _t
                let filtered: Vec<String> = st
                    .raw_cookies
                    .split(';')
                    .map(|s| s.trim())
                    .filter(|s| {
                        !s.is_empty()
                            && !s.starts_with("_t=")
                            && !s.starts_with("_forum_session=")
                    })
                    .map(|s| s.to_string())
                    .collect();
                st.raw_cookies = filtered.join("; ");
                st.unread_notifications = 0;
                save_state(&st);
                changed = true;
            }
        }
        if changed {
            // 同步彻底清除 WKHTTPCookieStore 与 NSHTTPCookieStorage 中的失效 _t / _forum_session（100% 保留 cf_clearance），
            // 彻底根除死 Cookie 残留在浏览器中污染后续重新登录导致的无限「登录已过期」！
            self.sweep_auth_cookies(true, None).await;
            self.inner.lock().unwrap().last_seeded_token = String::new();
            let _ = native_call(
                "evaluate",
                json!({ "script": "(() => { window.__lindoCsrf = null; window.__lindoIgnoreDomCsrf = true; return true; })()" }),
                4,
            )
            .await;
            self.set_dock_badge(0).await;
            let _ = app.emit("lindo://state-updated", ());
        }
    }

    pub async fn upload(
        &self,
        app: &AppHandle,
        filename: &str,
        mime: &str,
        data_base64: &str,
    ) -> Result<SiteHttpResponse, String> {
        let session_gen = self.session_generation.load(Ordering::Acquire);
        let cfg = json!({
            "name": filename,
            "mime": mime,
            "data": data_base64
        });
        let script = format!(
            r#"(async () => {{
                if (location.origin !== 'https://linux.do') return {{ originLost: true }};
                const c = {cfg};
                const csrfRes = await fetch('/session/csrf.json', {{ credentials: 'include', cache: 'no-store', signal: AbortSignal.timeout(12000) }});
                if (!csrfRes.ok) return {{ status: csrfRes.status, body: await csrfRes.text(), challenge: csrfRes.headers.get('cf-mitigated'), cfRay: csrfRes.headers.get('cf-ray'), contentType: csrfRes.headers.get('content-type') }};
                const token = (await csrfRes.json()).csrf;
                if (!token) return {{ status: 422, body: '{{"errors":["请先完成登录"]}}' }};
                const bin = atob(c.data);
                const bytes = new Uint8Array(bin.length);
                for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
                const form = new FormData();
                form.append('type', 'composer');
                form.append('synchronous', 'true');
                form.append('files[]', new Blob([bytes], {{ type: c.mime }}), c.name);
                const r = await fetch('/uploads.json', {{
                    method: 'POST',
                    credentials: 'include',
                    headers: {{ 'X-CSRF-Token': token, 'X-Requested-With': 'XMLHttpRequest', 'Discourse-Present': 'true' }},
                    body: form,
                    signal: AbortSignal.timeout(90000)
                }});
                return {{ status: r.status, body: await r.text(), challenge: r.headers.get('cf-mitigated'), cfRay: r.headers.get('cf-ray'), contentType: r.headers.get('content-type') }};
            }})()"#
        );

        let mut budget = AttemptBudget::for_request(false);
        let mut verify_attempted = false;
        let mut verify_round: Option<u64> = None;
        let mut verify_cooldown_ms: u64 = 0;
        let mut ineffective_clearance = false;
        let mut silent_cf_revalidated = false;

        let res: Value = loop {
            let _acct = self.account_lock.read().await;
            if self.session_generation.load(Ordering::Acquire) != session_gen {
                return Err("账号会话已切换，上传已取消".to_string());
            }
            let _permit = self.priority_gate.acquire(RequestPriority::High).await;
            let _fg = FgRequestGuard::enter(&self.active_fg_requests);
            self.ensure_ready(false).await?;

            let eval_val = {
                let _nav = self.nav_lock.read().await;
                native_call("evaluate", json!({ "script": script }), 105).await?
            };
            let st = eval_val.get("status").and_then(|v| v.as_u64()).unwrap_or(500) as u16;
            let bd = eval_val.get("body").and_then(|v| v.as_str()).unwrap_or("");
            let cf = eval_val.get("challenge").and_then(|v| v.as_str()).unwrap_or("");
            let ct = eval_val.get("contentType").and_then(|v| v.as_str()).unwrap_or("");
            let is_cf = Self::is_cf_challenge_response(st, cf, ct, bd);

            if is_cf {
                let carried_cf = {
                    let raw = self.state.lock().unwrap().raw_cookies.clone();
                    Self::parse_cookie_map(&raw).remove("cf_clearance")
                };
                self.cf_authority
                    .lock()
                    .unwrap()
                    .note_challenged(carried_cf.as_deref());
                if budget.try_consume_cf() {
                    drop(_fg);
                    drop(_permit);
                    drop(_acct);
                    self.emit_net_health_diag(
                        app,
                        "WARN",
                        "cf_challenge_detected",
                        Some(&format!("method=POST url=https://linux.do/uploads.json status={st}")),
                    );
                    let is_dark = self.current_is_dark();
                    let outcome = self
                        .coordinate_cf_verification(app, VerifyPresentation::Blocking, is_dark, false)
                        .await;
                    verify_attempted = true;
                    if outcome.round > 0 {
                        verify_round = Some(outcome.round);
                    }
                    if outcome.passed {
                        silent_cf_revalidated = true;
                        continue;
                    }
                    verify_cooldown_ms = outcome.cooldown_remain_ms;
                    ineffective_clearance = outcome.ineffective_blocked;
                    break eval_val;
                } else if let Some(passed_round) = verify_round {
                    let breaker_ms = self
                        .verify_coord
                        .lock()
                        .unwrap()
                        .record_ineffective_clearance(passed_round, Instant::now());
                    verify_cooldown_ms = breaker_ms;
                    ineffective_clearance = true;
                    self.emit_net_health_diag(
                        app,
                        "WARN",
                        "ineffective_clearance_breaker",
                        Some(&format!("round={passed_round} method=POST url=/uploads.json breakerMs={breaker_ms}")),
                    );
                }
            }
            break eval_val;
        };

        let status = res.get("status").and_then(|v| v.as_u64()).unwrap_or(500) as u16;
        let body = res.get("body").and_then(|v| v.as_str()).unwrap_or("").to_string();
        let cf_hdr = res.get("challenge").and_then(|v| v.as_str()).unwrap_or("");
        let ct_hdr = res.get("contentType").and_then(|v| v.as_str()).unwrap_or("");
        let is_challenge = Self::is_cf_challenge_response(status, cf_hdr, ct_hdr, &body);
        let is_unauthenticated = Self::is_unauthenticated_response(status, &body);
        if (200..300).contains(&status) && !is_challenge {
            self.api_pacer.lock().unwrap().on_success(Instant::now());
            self.verify_coord.lock().unwrap().reset_on_healthy_response();
        }
        let body_bytes = body.len();
        Ok(SiteHttpResponse {
            status,
            body,
            is_challenge,
            is_unauthenticated,
            rate_limited_ms: None,
            diag: Some(SiteHttpDiag {
                cf_ray: res.get("cfRay").and_then(|v| v.as_str()).map(|s| s.to_string()),
                cf_mitigated: if cf_hdr.is_empty() {
                    None
                } else {
                    Some(cf_hdr.to_string())
                },
                content_type: if ct_hdr.is_empty() {
                    None
                } else {
                    Some(ct_hdr.to_string())
                },
                silent_cf_revalidated,
                verify_attempted,
                verify_round,
                verify_cooldown_ms,
                ineffective_clearance,
                body_bytes,
                ..Default::default()
            }),
        })
    }

    pub async fn read_clipboard_media(
        &self,
        only_file_urls: bool,
    ) -> Result<ClipboardMediaResult, String> {
        #[cfg(target_os = "macos")]
        {
            let val = native_call(
                "readClipboardMedia",
                json!({ "onlyFileUrls": only_file_urls }),
                6,
            )
            .await?;
            let parsed: ClipboardMediaResult = serde_json::from_value(val).unwrap_or_default();
            Ok(parsed)
        }
        #[cfg(not(target_os = "macos"))]
        {
            let _ = only_file_urls;
            Ok(ClipboardMediaResult {
                source: "none".to_string(),
                items: Vec::new(),
            })
        }
    }

    pub async fn convert_local_image_to_png(&self, path: &str) -> Result<Option<String>, String> {
        #[cfg(target_os = "macos")]
        {
            let val = native_call("convertLocalImageToPng", json!({ "path": path }), 12).await?;
            Ok(val
                .get("dataBase64")
                .and_then(|v| v.as_str())
                .filter(|s| !s.is_empty())
                .map(|s| s.to_string()))
        }
        #[cfg(not(target_os = "macos"))]
        {
            let _ = path;
            Ok(None)
        }
    }

    pub fn diag_runtime_snapshot(&self) -> DiagRuntimeSnapshot {
        let now = Instant::now();
        let now_ms = now_epoch_ms();
        let rem = |opt: Option<Instant>| -> u64 {
            opt.and_then(|t| t.checked_duration_since(now))
                .map(|d| d.as_millis() as u64)
                .unwrap_or(0)
        };
        let (
            bridge_ua,
            origin_ready,
            verifying,
            bootstrap_challenged,
            cf_attention_notified,
            rate_limit_remain_ms,
            fg_challenge_remain_ms,
            bg_challenge_remain_ms,
            media_cooldown_remain_ms,
            challenge_429_streak,
        ) = {
            let rt = self.inner.lock().unwrap();
            (
                rt.bridge_ua.clone(),
                rt.origin_ready,
                rt.verifying,
                rt.bootstrap_challenged,
                rt.cf_attention_notified,
                rem(rt.rate_limit_until),
                rem(rt.fg_challenge_until),
                rem(rt.bg_challenge_until),
                rem(rt.media_cooldown_until),
                rt.challenge_429_streak,
            )
        };
        let (has_cookie_t, has_forum_session, has_cf_clearance, saved_accounts_count) = {
            let st = self.state.lock().unwrap();
            let cmap = Self::parse_cookie_map(&st.raw_cookies);
            (
                cmap.get("_t").map(|v| !v.trim().is_empty()).unwrap_or(false),
                cmap.get("_forum_session")
                    .map(|v| !v.trim().is_empty())
                    .unwrap_or(false),
                cmap.get("cf_clearance")
                    .map(|v| !v.trim().is_empty())
                    .unwrap_or(false),
                st.saved_accounts.len(),
            )
        };
        let cf_clearance_age_secs = self.cf_authority.lock().unwrap().age_secs(now_ms);
        let v_snap = self.verify_coord.lock().unwrap().snapshot(now);
        let pacer_rate_per_sec =
            (self.api_pacer.lock().unwrap().current_rate() * 100.0).round() / 100.0;
        let session_generation = self.session_generation.load(Ordering::Acquire);
        let (media_cache_entries, media_cache_bytes) = {
            let mc = self.media_cache.lock().unwrap();
            (mc.entries.len(), mc.total_bytes)
        };
        DiagRuntimeSnapshot {
            os: std::env::consts::OS.to_string(),
            arch: std::env::consts::ARCH.to_string(),
            bridge_ua,
            origin_ready,
            verifying: verifying || v_snap.active,
            bootstrap_challenged,
            cf_attention_notified,
            has_cookie_t,
            has_forum_session,
            has_cf_clearance,
            cf_clearance_age_secs,
            saved_accounts_count,
            rate_limit_remain_ms,
            fg_challenge_remain_ms,
            bg_challenge_remain_ms,
            media_cooldown_remain_ms,
            challenge_429_streak,
            verify_round: v_snap.round,
            verify_consecutive_failures: v_snap.consecutive_failures,
            verify_cooldown_remain_ms: v_snap.cooldown_remain_ms,
            verify_ineffective_breaker: v_snap.cooldown_is_ineffective
                && v_snap.cooldown_remain_ms > 0,
            pacer_rate_per_sec,
            session_generation,
            media_cache_entries,
            media_cache_bytes,
        }
    }

    /// Fetches `https://linux.do/...` binary assets (`/uploads/...`, animated `.gif` avatars, custom badges)
    /// exclusively inside the same-origin `gHost.web` / `lindo-bridge-host` WebView so the main UI window
    /// (`tauri://localhost` / `http://tauri.localhost`) NEVER sends cross-site requests without `cf_clearance` / `_t`!
    pub async fn fetch_media(&self, url: &str) -> Result<ForumMediaPayload, String> {
        let clean_url = url.trim();
        if !Self::is_allowed_forum_url(clean_url) {
            return Err("Blocked non-forum media URL".to_string());
        }

        if let Some(hit) = self.media_cache.lock().unwrap().get(clean_url) {
            return Ok(hit);
        }

        // Single-flight deduplication across concurrent post / reaction / avatar renders
        loop {
            let existing_notify = {
                let mut inflight = self.media_inflight.lock().unwrap();
                if let Some(waiter) = inflight.get(clean_url) {
                    Some(waiter.clone())
                } else {
                    inflight.insert(clean_url.to_string(), Arc::new(Notify::new()));
                    None
                }
            };
            if let Some(waiter) = existing_notify {
                waiter.notified().await;
                if let Some(hit) = self.media_cache.lock().unwrap().get(clean_url) {
                    return Ok(hit);
                }
                continue;
            }
            break;
        }

        let result = self.fetch_media_uncached(clean_url).await;
        if let Ok(ref payload) = result {
            if (200..300).contains(&payload.status) && !payload.data_base64.is_empty() {
                self.media_cache
                    .lock()
                    .unwrap()
                    .insert(clean_url.to_string(), payload.clone());
            }
        }

        let notify = self.media_inflight.lock().unwrap().remove(clean_url);
        if let Some(n) = notify {
            n.notify_waiters();
        }
        result
    }

    async fn fetch_media_uncached(&self, clean_url: &str) -> Result<ForumMediaPayload, String> {
        {
            let rt = self.inner.lock().unwrap();
            if rt.verifying || rt.bootstrap_challenged {
                return Err("Site verification in progress".to_string());
            }
            let now = Instant::now();
            if let Some(until) = rt.rate_limit_until {
                if now < until {
                    return Err("Rate limit cooldown active".to_string());
                }
            }
            if let Some(until) = rt.media_cooldown_until {
                if now < until {
                    return Err("Media cooldown active".to_string());
                }
            }
            if let Some(until) = rt.bg_challenge_until {
                if now < until {
                    return Err("Challenge cooldown active".to_string());
                }
            }
        }
        // Yield politely while foreground JSON API calls are active so subresource image `fetch()` never spikes Cloudflare burst counters
        let mut waited_ms = 0u64;
        while waited_ms < 560 && self.active_fg_requests.load(Ordering::Relaxed) > 0 {
            tokio::time::sleep(Duration::from_millis(80)).await;
            waited_ms += 80;
        }
        // 3-lane image concurrency gate + smooth media pacer
        let lower_url = clean_url.to_ascii_lowercase();
        let is_emoji = lower_url.contains("/images/emoji/") || lower_url.contains("/svg-sprite/");
        let is_avatar = lower_url.contains("/user_avatar/") || lower_url.contains("/letter_avatar");
        let sem = if is_emoji {
            &self.emoji_sem
        } else if is_avatar {
            &self.avatar_sem
        } else {
            &self.media_sem
        };
        let _permit = sem.acquire().await.ok();
        if !is_emoji {
            let pace_wait = {
                let floor = if self.active_fg_requests.load(Ordering::Relaxed) > 0 {
                    1.0
                } else {
                    0.0
                };
                self.media_pacer.lock().unwrap().reserve(Instant::now(), floor)
            };
            if !pace_wait.is_zero() {
                tokio::time::sleep(pace_wait).await;
            }
        }
        self.ensure_ready(false).await?;
        if self.inner.lock().unwrap().bootstrap_challenged {
            return Err("Site verification required".to_string());
        }
        let cache_mode = if is_emoji || is_avatar { "force-cache" } else { "default" };
        let cfg = json!({ "url": clean_url, "cacheMode": cache_mode });
        let script = format!(
            r#"(async () => {{
                const c = {cfg};
                if (location.origin !== 'https://linux.do') return null;
                const r = await fetch(c.url, {{
                    method: 'GET',
                    credentials: 'include',
                    cache: c.cacheMode || 'default',
                    referrer: 'https://linux.do/',
                    referrerPolicy: 'strict-origin-when-cross-origin',
                    headers: {{ 'Accept': 'image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8' }},
                    signal: AbortSignal.timeout(20000)
                }});
                const cf = r.headers.get('cf-mitigated') || '';
                const ct = r.headers.get('content-type') || 'image/png';
                if (!r.ok || cf === 'challenge' || ct.toLowerCase().includes('text/html')) {{
                    return {{ status: r.status || 502, contentType: ct, dataBase64: '', challenge: cf }};
                }}
                const buf = await r.arrayBuffer();
                const bytes = new Uint8Array(buf);
                if (!bytes.length) return {{ status: 204, contentType: ct, dataBase64: '', challenge: '' }};
                const chunk = 0x8000;
                const parts = [];
                for (let i = 0; i < bytes.length; i += chunk) {{
                    parts.push(String.fromCharCode.apply(null, bytes.subarray(i, i + chunk)));
                }}
                return {{ status: r.status, contentType: ct, dataBase64: btoa(parts.join('')), challenge: '' }};
            }})()"#
        );

        let res = {
            let _nav = self.nav_lock.read().await;
            native_call("evaluate", json!({ "script": script }), 25).await?
        };
        let status = res.get("status").and_then(|v| v.as_u64()).unwrap_or(502) as u16;
        let content_type = res
            .get("contentType")
            .and_then(|v| v.as_str())
            .unwrap_or("image/png")
            .to_string();
        let data_base64 = res
            .get("dataBase64")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        let cf_hdr = res.get("challenge").and_then(|v| v.as_str()).unwrap_or("");
        if status == 429 || cf_hdr == "challenge" {
            // Isolate image throttling to `media_cooldown_until` so subresource fetches never lock out foreground API calls!
            let now = Instant::now();
            self.media_pacer.lock().unwrap().on_rate_limit(now);
            let cooldown = if cf_hdr == "challenge" { 8 } else { 4 };
            self.inner.lock().unwrap().media_cooldown_until =
                Some(now + Duration::from_secs(cooldown));
        } else if (200..300).contains(&status) {
            self.media_pacer.lock().unwrap().on_success(Instant::now());
        }
        Ok(ForumMediaPayload {
            status,
            content_type,
            data_base64,
        })
    }

    pub async fn verify_or_login(
        &self,
        app: &AppHandle,
        login: bool,
        is_dark: bool,
        manual: bool,
    ) -> Result<bool, String> {
        if !login {
            let pres = if manual {
                VerifyPresentation::Manual
            } else {
                VerifyPresentation::Blocking
            };
            let outcome = self.coordinate_cf_verification(app, pres, is_dark, false).await;
            return Ok(outcome.passed);
        }
        self.verify_internal(app, true, is_dark, None).await
    }

    pub async fn verify_with_otp(
        &self,
        app: &AppHandle,
        otp_token: &str,
        is_dark: bool,
    ) -> Result<bool, String> {
        self.verify_internal(app, false, is_dark, Some(otp_token.to_string()))
            .await
    }

    async fn verify_internal(
        &self,
        app: &AppHandle,
        login: bool,
        is_dark: bool,
        otp_token: Option<String>,
    ) -> Result<bool, String> {
        let Ok(_guard) = self.verify_lock.try_lock() else {
            return Ok(false);
        };
        {
            let mut rt = self.inner.lock().unwrap();
            rt.verifying = true;
            rt.origin_ready = false;
            rt.bootstrap_challenged = false;
            rt.rate_limit_until = None;
            rt.fg_challenge_until = None;
            rt.bg_challenge_until = None;
        }

        let result = self
            .run_verify_loop(app, login, is_dark, otp_token.as_deref())
            .await;

        let _ = native_call("hide", json!({}), 5).await;
        if !matches!(result, Ok(true)) {
            let _nav = self.nav_lock.write().await;
            self.restore_about_origin_context().await;
        }
        {
            let mut rt = self.inner.lock().unwrap();
            rt.verifying = false;
            if matches!(result, Ok(true)) {
                rt.origin_ready = true;
                rt.bootstrap_challenged = false;
            }
        }
        result
    }

    pub(crate) fn extract_topic_id_from_url(url: &str) -> Option<u64> {
        let path = url
            .strip_prefix("https://linux.do")
            .or_else(|| url.strip_prefix("https://www.linux.do"))
            .unwrap_or(url)
            .split(['?', '#'])
            .next()
            .unwrap_or("");
        let rest = path.strip_prefix("/t/")?;
        for seg in rest.split('/') {
            let clean = seg.strip_suffix(".json").unwrap_or(seg);
            if !clean.is_empty() && clean.chars().all(|c| c.is_ascii_digit()) {
                if let Ok(id) = clean.parse::<u64>() {
                    if id > 0 {
                        return Some(id);
                    }
                }
            }
        }
        None
    }

    pub(crate) fn challenge_url_to_html_url(_challenge_url: &str) -> String {
        // Dedicated Cloudflare verification endpoint (aligned with xuemk/Fluxdo):
        // - Always intercepted by Cloudflare to issue/refresh `cf_clearance` (unlike `/` which often skips GET challenges);
        // - Returns lightweight Discourse origin 404 once cleared (never loading heavy feeds, `Log In` guest chrome, or Rails 500 `Oops`!).
        "https://linux.do/challenge".to_string()
    }

    pub(crate) fn challenge_url_to_probe_get_url(challenge_url: &str) -> String {
        if let Some(tid) = Self::extract_topic_id_from_url(challenge_url) {
            return format!("https://linux.do/t/{tid}.json?track_visit=false");
        }
        let clean_path = challenge_url
            .split(['?', '#'])
            .next()
            .unwrap_or("");
        if clean_path.starts_with("https://linux.do/")
            && clean_path.ends_with(".json")
            && !clean_path.ends_with("/posts.json")
            && !clean_path.contains("/post_actions")
            && !clean_path.contains("/timings")
        {
            return challenge_url.to_string();
        }
        "https://linux.do/latest.json?no_definitions=true".to_string()
    }

    async fn run_verify_loop(
        &self,
        app: &AppHandle,
        login: bool,
        is_dark: bool,
        otp_token: Option<&str>,
    ) -> Result<bool, String> {
        let is_otp_flow = otp_token.is_some();
        let backup_cfs = if !login && !is_otp_flow {
            native_call("init", json!({}), 10).await?;
            self.inner.lock().unwrap().initialized = true;
            // Backup & delete any challenged/stale `cf_clearance` BEFORE loading `/challenge`
            // (FluxDO proven fix: otherwise Cloudflare may pass the dead cookie straight to 404 without minting a fresh one!)
            self.purge_cf_clearance_for_reverify().await
        } else {
            Vec::new()
        };

        let res = self
            .run_verify_loop_inner(app, login, is_dark, otp_token, &backup_cfs)
            .await;

        if !matches!(res, Ok(true)) && !backup_cfs.is_empty() {
            self.restore_cf_clearance_backup(&backup_cfs).await;
        }
        res
    }

    async fn run_verify_loop_inner(
        &self,
        app: &AppHandle,
        login: bool,
        is_dark: bool,
        otp_token: Option<&str>,
        purged_backup_cfs: &[String],
    ) -> Result<bool, String> {
        native_call("init", json!({}), 10).await?;
        self.inner.lock().unwrap().initialized = true;

        let is_otp_flow = otp_token.is_some();
        if login {
            // Clear stale Discourse `_t` / `_forum_session` before fresh interactive login while keeping Cloudflare cookies untouched.
            self.sweep_auth_cookies(true, None).await;
            self.inner.lock().unwrap().last_seeded_token = String::new();
        } else if !is_otp_flow {
            self.seed_session_verified(true).await?;
        }
        let recovery_url = {
            let rt = self.inner.lock().unwrap();
            if login || is_otp_flow {
                "https://linux.do/session/current.json".to_string()
            } else {
                rt.last_challenge_url
                    .clone()
                    .unwrap_or_else(|| "https://linux.do/latest.json".to_string())
            }
        };
        let initial_url = if let Some(otp) = otp_token {
            format!("https://linux.do/session/otp/{otp}")
        } else if login {
            "https://linux.do/login".to_string()
        } else {
            Self::challenge_url_to_html_url(&recovery_url)
        };
        let probe_get_url = Self::challenge_url_to_probe_get_url(&recovery_url);

        let mut stale_cf: std::collections::HashSet<String> = if !login && !is_otp_flow {
            self.browser_cf_clearances().await.into_iter().collect()
        } else {
            std::collections::HashSet::new()
        };
        for old in purged_backup_cfs {
            stale_cf.insert(old.clone());
        }

        let mut mask_visible = false;
        if !login && !is_otp_flow {
            mask_visible = true;
            let _ = native_call(
                "setVerifyMask",
                json!({
                    "visible": true,
                    "dark": is_dark,
                    "message": "校验连接中…"
                }),
                4,
            )
            .await;
        } else {
            let _ = native_call("setVerifyMask", json!({ "visible": false, "dark": is_dark }), 4).await;
        }

        native_call("navigate", json!({ "url": initial_url }), 10).await?;
        let mut panel_shown = false;
        if login {
            panel_shown = true;
            native_call("show", json!({ "dark": is_dark, "login": true }), 8).await?;
        }

        let mut requested_login = login || is_otp_flow;
        let mut otp_submitted = false;
        let mut oops_reloaded = false;
        let mut challenge_ticks = 0u32;
        let mut quiet_rounds = 0u32;
        let mut last_reload_epoch: Option<u64> = None;
        let mut pass_mask_stuck_ticks = 0u32;
        let mut stuck_reloaded = false;
        let mut terminated_reloaded = false;
        let mut last_current_session_poll: Option<Instant> = None;
        let loop_started = Instant::now();
        let deadline = loop_started + Duration::from_secs(180);

        while Instant::now() < deadline {
            let poll_sleep = if requested_login { 500 } else { 220 };
            tokio::time::sleep(Duration::from_millis(poll_sleep)).await;

            let status_obj = native_call("status", json!({}), 6).await?;
            let user_cancelled = status_obj.get("cancelled").and_then(|v| v.as_bool()) == Some(true);
            let native_origin_404 = status_obj.get("origin404").and_then(|v| v.as_bool()) == Some(true);
            let native_interactive = status_obj
                .get("interactiveRequired")
                .and_then(|v| v.as_bool())
                .unwrap_or(false);
            let native_user_reloading = status_obj
                .get("userReloading")
                .and_then(|v| v.as_bool())
                .unwrap_or(false);
            let native_reload_epoch = status_obj
                .get("reloadEpoch")
                .and_then(|v| v.as_u64())
                .unwrap_or(0);
            let native_mask_visible = status_obj.get("maskVisible").and_then(|v| v.as_bool());
            let native_mask_message = status_obj
                .get("maskMessage")
                .and_then(|v| v.as_str())
                .unwrap_or("");
            let native_nav_error = status_obj
                .get("error")
                .and_then(|v| v.as_str())
                .unwrap_or("");
            let native_terminated = status_obj
                .get("processTerminated")
                .and_then(|v| v.as_bool())
                .unwrap_or(false);

            // Always sync Rust's mask flag with native WKWebView truth so manual reload or native callbacks never desync!
            if let Some(vis) = native_mask_visible {
                mask_visible = vis;
            }
            match last_reload_epoch {
                None => {
                    last_reload_epoch = Some(native_reload_epoch);
                }
                Some(prev) if prev != native_reload_epoch => {
                    last_reload_epoch = Some(native_reload_epoch);
                    challenge_ticks = 0;
                    quiet_rounds = 0;
                    pass_mask_stuck_ticks = 0;
                    stuck_reloaded = false;
                    emit_diag(
                        app,
                        "INFO",
                        "AUTH",
                        "VerifyReload",
                        "安全确认弹窗已手动刷新，重新加载验证",
                        None,
                    );
                }
                _ => {}
            }
            if user_cancelled && (native_user_reloading || native_terminated) {
                return Ok(false);
            }
            if !login && !requested_login && native_terminated && !terminated_reloaded {
                terminated_reloaded = true;
                challenge_ticks = 0;
                quiet_rounds = 0;
                pass_mask_stuck_ticks = 0;
                emit_diag(
                    app,
                    "WARN",
                    "AUTH",
                    "VerifyRecover",
                    "安全确认 WebView 进程异常中断，自动重新加载",
                    None,
                );
                mask_visible = true;
                let _ = native_call(
                    "setVerifyMask",
                    json!({ "visible": true, "dark": is_dark, "message": "正在重新加载验证…" }),
                    4,
                )
                .await;
                let _ = native_call("navigate", json!({ "url": "https://linux.do/challenge" }), 8).await;
                continue;
            }
            if !login && !requested_login && native_user_reloading {
                challenge_ticks = 0;
                quiet_rounds = 0;
                pass_mask_stuck_ticks = 0;
                if !panel_shown && loop_started.elapsed() >= Duration::from_millis(1200) {
                    panel_shown = true;
                    let _ = native_call("show", json!({ "dark": is_dark, "login": false }), 8).await;
                }
                continue;
            }

            // Passive read-only DOM observation in isolated `defaultClientWorld` (macOS) / zero-global sync eval (Windows):
            // NEVER include `script[src*="challenge-platform"]` (present passively on normal & 404 Discourse pages!),
            // and ALWAYS evaluate `isOrigin404` BEFORE `explicitChallenge` so `/challenge` -> 404 closes reliably!
            let probe = native_call(
                "evaluate",
                json!({
                    "isolated": true,
                    "script": r#"(() => {
                        const site = location.origin === 'https://linux.do';
                        const complete = document.readyState === 'complete';
                        const domReady = document.readyState === 'interactive' || complete;
                        const csrfVal = document.querySelector('meta[name="csrf-token"]')?.content || null;
                        const hasCsrf = !!csrfVal;
                        const preloadedEl = document.getElementById('data-preloaded');
                        const hasPreloaded = !!preloadedEl;
                        const hasDiscourseDom = hasPreloaded || !!document.querySelector('header.d-header, #main-outlet .topic-list, #main-outlet #topic-title, #main-outlet .container, #d-splash');
                        const isOtpPage = location.pathname.indexOf('/session/otp/') === 0;
                        const isChallengePath = location.pathname === '/challenge';
                        const isLoginPage = location.pathname === '/login'
                            || location.pathname.indexOf('/login/') === 0
                            || location.pathname.indexOf('/signup') === 0
                            || location.pathname.indexOf('/u/email-login') === 0
                            || location.pathname.indexOf('/session/email-login') === 0
                            || location.pathname.indexOf('/auth/') === 0;
                        const bodyText = (document.body?.innerText || '').trim();
                        const lowerHtml = ((document.documentElement?.innerHTML || '') + ' ' + (document.title || '')).toLowerCase();
                        const isOrigin404 = site && (
                            !!document.querySelector('.page-not-found, .discourse-no-results, #main-outlet .not-found-container')
                            || lowerHtml.includes('page-not-found')
                            || lowerHtml.includes('discourse-no-results')
                            || lowerHtml.includes('"errortype":"notfound"')
                            || lowerHtml.includes('404-body')
                            || lowerHtml.includes('exist or is private')
                            || lowerHtml.includes('page you requested')
                            || lowerHtml.includes('该页面不存在')
                        );
                        const explicitChallenge = !isOrigin404 && (
                            !!document.querySelector('#challenge-running, #challenge-stage, #challenge-form, .cf-turnstile, #turnstile-wrapper, iframe[src*="challenges.cloudflare.com"]')
                            || /^(Just a moment|请稍候|Attention Required|Security Check)/i.test(document.title || '')
                        );
                        const widgetIframe = document.querySelector('iframe[src*="challenges.cloudflare.com"], #turnstile-wrapper iframe, .cf-turnstile iframe');
                        const widgetIframeRect = widgetIframe ? widgetIframe.getBoundingClientRect() : null;
                        const widgetHost = document.querySelector('#turnstile-wrapper, .cf-turnstile, #challenge-stage > div');
                        const widgetHostRect = widgetHost ? widgetHost.getBoundingClientRect() : null;
                        const hasRenderedWidget =
                            !!(widgetIframeRect && widgetIframeRect.width >= 80 && widgetIframeRect.height >= 30)
                            || !!(widgetHostRect && widgetHostRect.width >= 220 && widgetHostRect.height >= 55);
                        const isDiscourseOops = site && complete && !explicitChallenge && /discussion forum encountered an unexpected problem/i.test(bodyText);
                        const missingDiscourseBoot = site && complete && !hasCsrf && !isOrigin404 && !isDiscourseOops && !bodyText.startsWith('{');
                        let preloadedUserJson = null;
                        if (site && domReady && hasCsrf && hasPreloaded && !explicitChallenge) {
                            try {
                                const raw = preloadedEl.getAttribute('data-preloaded') || preloadedEl.textContent || '';
                                if (raw) {
                                    const outer = JSON.parse(raw);
                                    if (outer && typeof outer.currentUser === 'string' && outer.currentUser.length > 2) {
                                        preloadedUserJson = JSON.stringify({ current_user: JSON.parse(outer.currentUser) });
                                    } else if (outer && outer.currentUser && typeof outer.currentUser === 'object') {
                                        preloadedUserJson = JSON.stringify({ current_user: outer.currentUser });
                                    }
                                }
                            } catch (_) {}
                        }
                        return {
                            site,
                            domReady,
                            complete,
                            hasCsrf,
                            csrfVal,
                            hasPreloaded,
                            hasDiscourseDom,
                            isOtpPage,
                            isChallengePath,
                            isOrigin404,
                            isLoginPage,
                            isDiscourseOops,
                            preloadedUserJson,
                            explicitChallenge,
                            hasRenderedWidget,
                            challenge: !isOrigin404 && (explicitChallenge || missingDiscourseBoot)
                        };
                    })()"#
                }),
                8,
            )
            .await
            .ok();

            let is_discourse_oops = probe
                .as_ref()
                .and_then(|p| p.get("isDiscourseOops")?.as_bool())
                .unwrap_or(false);
            let dom_origin_404 = probe
                .as_ref()
                .and_then(|p| p.get("isOrigin404")?.as_bool())
                .unwrap_or(false);
            let is_origin_404 = native_origin_404 || dom_origin_404;
            let explicit_challenge = !is_origin_404
                && probe
                    .as_ref()
                    .and_then(|p| p.get("explicitChallenge")?.as_bool())
                    .unwrap_or(false);
            let has_rendered_widget = !is_origin_404
                && probe
                    .as_ref()
                    .and_then(|p| p.get("hasRenderedWidget")?.as_bool())
                    .unwrap_or(false);
            let in_challenge = !is_origin_404
                && probe
                    .as_ref()
                    .and_then(|p| p.get("challenge")?.as_bool())
                    .unwrap_or(false);
            let ready = probe
                .as_ref()
                .map(|p| {
                    p.get("site").and_then(|v| v.as_bool()) == Some(true)
                        && p.get("domReady").and_then(|v| v.as_bool()) == Some(true)
                        && p.get("hasCsrf").and_then(|v| v.as_bool()) == Some(true)
                        && p.get("hasDiscourseDom").and_then(|v| v.as_bool()) == Some(true)
                        && p.get("isOtpPage").and_then(|v| v.as_bool()) != Some(true)
                })
                .unwrap_or(false);

            if is_discourse_oops {
                let _ = self.sync_cookies_from_webkit_verified(false, true).await;
                if !oops_reloaded {
                    oops_reloaded = true;
                    let clean_url = if requested_login {
                        "https://linux.do/login"
                    } else {
                        "https://linux.do/challenge"
                    };
                    let _ = native_call("navigate", json!({ "url": clean_url }), 8).await;
                    continue;
                }
                if !login && !requested_login {
                    {
                        let mut rt = self.inner.lock().unwrap();
                        rt.last_challenge_url = None;
                        rt.fg_challenge_until = None;
                        rt.bg_challenge_until = None;
                        rt.bootstrap_challenged = false;
                        rt.cf_attention_notified = false;
                    }
                    let _ = native_call("hide", json!({}), 5).await;
                    {
                        let _nav = self.nav_lock.write().await;
                        self.restore_about_origin_context().await;
                    }
                    let _ = app.emit("lindo://cf-attention-needed", false);
                    let _ = app.emit("lindo://verified", ());
                    return Ok(true);
                }
                if user_cancelled {
                    return Ok(false);
                }
                continue;
            }

            if user_cancelled {
                if !login && !requested_login && (ready || is_origin_404) && !in_challenge {
                    // User closed the panel right as verification completed: treat as verified!
                } else {
                    return Ok(false);
                }
            }

            if in_challenge {
                quiet_rounds = 0;
                pass_mask_stuck_ticks = 0;
                challenge_ticks += 1;
                // Show compact card immediately when `interactiveBegin` or widget rendered, or after 2 ticks (~440ms)
                if (native_interactive || has_rendered_widget || challenge_ticks >= 2) && !panel_shown {
                    panel_shown = true;
                    let _ = native_call("show", json!({ "dark": is_dark, "login": login }), 8).await;
                }
                // Reveal the unclipped 480x380 verification sheet once Turnstile is interactive/rendered,
                // or promptly after ~1.3s so Cloudflare's challenge page is always fully visible & interactive.
                let ready_to_reveal = native_interactive
                    || has_rendered_widget
                    || (explicit_challenge && challenge_ticks >= 6)
                    || challenge_ticks >= 10;
                if ready_to_reveal && mask_visible {
                    mask_visible = false;
                    let _ = native_call("setVerifyMask", json!({ "visible": false, "dark": is_dark }), 4).await;
                } else if mask_visible && native_mask_message == "已通过，正在继续…" {
                    let _ = native_call(
                        "setVerifyMask",
                        json!({
                            "visible": true,
                            "dark": is_dark,
                            "message": "校验连接中…"
                        }),
                        4,
                    )
                    .await;
                }
                continue;
            }
            challenge_ticks = 0;

            // Pure verification flow (`/challenge`):
            // Immediately cover any non-challenge page with 0-frame mask, confirm fresh `cf_clearance` / origin reach, and close!
            if !login && !requested_login {
                if !mask_visible && (is_origin_404 || ready) {
                    mask_visible = true;
                    let _ = native_call(
                        "setVerifyMask",
                        json!({
                            "visible": true,
                            "dark": is_dark,
                            "message": "已通过，正在继续…"
                        }),
                        4,
                    )
                    .await;
                }

                let current_cfs = self.browser_cf_clearances().await;
                let has_fresh_cf = {
                    let auth = self.cf_authority.lock().unwrap();
                    current_cfs
                        .iter()
                        .any(|c| !stale_cf.contains(c) && !auth.is_challenged(c))
                };

                if has_fresh_cf || is_origin_404 || ready {
                    pass_mask_stuck_ticks = 0;
                    quiet_rounds += 1;
                } else {
                    quiet_rounds = 0;
                    if !panel_shown && loop_started.elapsed() >= Duration::from_millis(1200) {
                        panel_shown = true;
                        let _ = native_call("show", json!({ "dark": is_dark, "login": false }), 8).await;
                    }
                    if mask_visible && native_mask_message == "已通过，正在继续…" {
                        pass_mask_stuck_ticks += 1;
                        if pass_mask_stuck_ticks >= 18 {
                            let detail = format!(
                                "elapsedMs={}, hasFreshCf={}, origin404={}, ready={}, navError={}",
                                loop_started.elapsed().as_millis(),
                                has_fresh_cf,
                                is_origin_404,
                                ready,
                                if native_nav_error.is_empty() {
                                    "none"
                                } else {
                                    native_nav_error
                                }
                            );
                            emit_diag(
                                app,
                                "WARN",
                                "AUTH",
                                "VerifyWatchdog",
                                "安全确认停留在过渡状态超过 4 秒，触发自动恢复",
                                Some(&detail),
                            );
                            pass_mask_stuck_ticks = 0;
                            if !stuck_reloaded {
                                stuck_reloaded = true;
                                let _ = native_call(
                                    "setVerifyMask",
                                    json!({
                                        "visible": true,
                                        "dark": is_dark,
                                        "message": "正在重新加载验证…"
                                    }),
                                    4,
                                )
                                .await;
                                let _ = native_call(
                                    "navigate",
                                    json!({ "url": "https://linux.do/challenge" }),
                                    8,
                                )
                                .await;
                            } else {
                                mask_visible = false;
                                let _ = native_call(
                                    "setVerifyMask",
                                    json!({ "visible": false, "dark": is_dark }),
                                    4,
                                )
                                .await;
                            }
                        }
                    } else {
                        pass_mask_stuck_ticks = 0;
                    }
                    continue;
                }

                let needed_rounds = if has_fresh_cf && (is_origin_404 || ready) { 1 } else { 2 };
                if quiet_rounds < needed_rounds {
                    continue;
                }

                let has_preloaded = probe
                    .as_ref()
                    .and_then(|p| p.get("hasPreloaded")?.as_bool())
                    .unwrap_or(false);
                let preloaded_user = probe
                    .as_ref()
                    .and_then(|p| p.get("preloadedUserJson")?.as_str())
                    .and_then(Self::extract_current_user);

                // Self-heal zombie local session if Discourse's own `data-preloaded` proves `_t` is unauthenticated (`currentUser == null`)
                if has_preloaded && preloaded_user.is_none() {
                    let mut expired_changed = false;
                    {
                        let mut st = self.state.lock().unwrap();
                        if let Some(prev) = st.current_user.take() {
                            st.expired_username = Some(prev.username.clone());
                            Self::upsert_saved_account(&mut st, prev, String::new(), true);
                            let cf_only: Vec<String> = st
                                .raw_cookies
                                .split(';')
                                .map(|s| s.trim())
                                .filter(|s| {
                                    !s.is_empty()
                                        && !s.starts_with("_t=")
                                        && !s.starts_with("_forum_session=")
                                })
                                .map(|s| s.to_string())
                                .collect();
                            st.raw_cookies = cf_only.join("; ");
                            st.unread_notifications = 0;
                            save_state(&st);
                            expired_changed = true;
                        }
                    }
                    if expired_changed {
                        self.sweep_auth_cookies(true, None).await;
                        self.inner.lock().unwrap().last_seeded_token = String::new();
                        let _ = app.emit("lindo://state-updated", ());
                    }
                } else if let Some(profile) = preloaded_user {
                    let _ = self.sync_cookies_from_webkit_verified(true, true).await;
                    self.commit_authenticated_user(app, profile);
                }

                let _ = self.sync_cookies_from_webkit_verified(false, true).await;
                {
                    let mut rt = self.inner.lock().unwrap();
                    rt.last_challenge_url = None;
                    rt.fg_challenge_until = None;
                    rt.bg_challenge_until = None;
                    rt.bootstrap_challenged = false;
                    rt.rate_limit_until = None;
                    rt.challenge_429_streak = 0;
                    rt.cf_attention_notified = false;
                }
                let _ = native_call("hide", json!({}), 5).await;
                // Restore clean `/about` origin document and WAIT for commit before returning `Ok(true)`
                // so an immediate POST/GET replay never collides with the `/about` navigation and aborts with `Load failed`!
                {
                    let _nav = self.nav_lock.write().await;
                    self.restore_about_origin_context().await;
                }
                let pass_detail = format!(
                    "elapsedMs={}, hasFreshCf={}, origin404={}, ready={}",
                    loop_started.elapsed().as_millis(),
                    has_fresh_cf,
                    is_origin_404,
                    ready
                );
                emit_diag(
                    app,
                    "INFO",
                    "AUTH",
                    "VerifyPassed",
                    "安全确认已完成并恢复请求通道",
                    Some(&pass_detail),
                );
                let _ = app.emit("lindo://cf-attention-needed", false);
                let _ = app.emit("lindo://verified", ());
                return Ok(true);
            }

            let is_otp_page_ready = probe
                .as_ref()
                .map(|p| {
                    p.get("site").and_then(|v| v.as_bool()) == Some(true)
                        && p.get("domReady").and_then(|v| v.as_bool()) == Some(true)
                        && p.get("hasCsrf").and_then(|v| v.as_bool()) == Some(true)
                        && p.get("isOtpPage").and_then(|v| v.as_bool()) == Some(true)
                })
                .unwrap_or(false);
            if is_otp_page_ready && !otp_submitted {
                otp_submitted = true;
                let _ = native_call(
                    "evaluate",
                    json!({
                        "isolated": true,
                        "script": r#"(() => {
                            const form = document.querySelector('form[action*="/session/otp/"]') || document.querySelector('form[method="post"]') || document.querySelector('form');
                            if (form) {
                                const btn = form.querySelector('input[type="submit"], button[type="submit"], .btn-primary, button');
                                if (btn) btn.click(); else form.submit();
                            }
                            return true;
                        })()"#
                    }),
                    5,
                )
                .await;
                continue;
            }

            if !ready {
                if !panel_shown && !is_otp_flow && loop_started.elapsed() >= Duration::from_millis(1050) {
                    panel_shown = true;
                    let _ = native_call("show", json!({ "dark": is_dark, "login": login }), 8).await;
                }
                continue;
            }

            // 2. 登录 / OTP 流：优先直接从页面 data-preloaded 提取已登录用户（零额外请求），若缺失再兜底请求 /session/current.json
            if requested_login {
                if let Some(preloaded_body) = probe
                    .as_ref()
                    .and_then(|p| p.get("preloadedUserJson")?.as_str())
                {
                    if let Some(profile) = Self::extract_current_user(preloaded_body) {
                        let _ = self.sync_cookies_from_webkit_verified(true, true).await;
                        let _ = native_call(
                            "evaluate",
                            json!({ "script": "(() => { window.__lindoCsrf = document.querySelector('meta[name=\"csrf-token\"]')?.content || null; window.__lindoIgnoreDomCsrf = false; return true; })()" }),
                            4,
                        )
                        .await;
                        self.commit_authenticated_user(app, profile);
                        let _ = native_call("hide", json!({}), 5).await;
                        let _ = app.emit("lindo://verified", ());
                        return Ok(true);
                    }
                }
                // While user is still on `/login`, `/u/email-login`, or `/auth/`, never spam `/session/current.json` (404) in the background!
                let still_on_login_form = probe
                    .as_ref()
                    .and_then(|p| p.get("isLoginPage")?.as_bool())
                    .unwrap_or(false);
                if still_on_login_form {
                    continue;
                }
                if last_current_session_poll
                    .map(|t| t.elapsed() < Duration::from_millis(2200))
                    .unwrap_or(false)
                {
                    continue;
                }
                last_current_session_poll = Some(Instant::now());
            }

            let target = if requested_login {
                "https://linux.do/session/current.json"
            } else {
                probe_get_url.as_str()
            };
            let Ok(resp) = self
                .request_unlocked(app, target, "GET", None, None, true)
                .await
            else {
                if user_cancelled {
                    return Ok(false);
                }
                continue;
            };

            if resp.is_challenge {
                if user_cancelled {
                    return Ok(false);
                }
                if !panel_shown {
                    panel_shown = true;
                    let _ = native_call("show", json!({ "dark": is_dark }), 8).await;
                }
                tokio::time::sleep(Duration::from_millis(1800)).await;
                continue;
            }

            if !login && !requested_login && resp.status != 429 && !resp.body.trim().starts_with('<') {
                let _ = self.sync_cookies_from_webkit_verified(false, true).await;
                {
                    let mut rt = self.inner.lock().unwrap();
                    rt.last_challenge_url = None;
                    rt.fg_challenge_until = None;
                    rt.bg_challenge_until = None;
                    rt.bootstrap_challenged = false;
                }
                let _ = native_call("hide", json!({}), 5).await;
                let _ = app.emit("lindo://verified", ());
                return Ok(true);
            }

            if user_cancelled {
                return Ok(false);
            }

            if requested_login && (200..300).contains(&resp.status) {
                if let Some(profile) = Self::extract_current_user(&resp.body) {
                    let _ = self.sync_cookies_from_webkit_verified(true, true).await;
                    let _ = native_call(
                        "evaluate",
                        json!({ "script": "(() => { window.__lindoCsrf = null; window.__lindoIgnoreDomCsrf = true; return true; })()" }),
                        4,
                    )
                    .await;
                    self.commit_authenticated_user(app, profile);
                    let _ = native_call("hide", json!({}), 5).await;
                    let _ = native_call("navigate", json!({ "url": "https://linux.do/" }), 8).await;
                    let _ = app.emit("lindo://verified", ());
                    return Ok(true);
                }
            }

            if !requested_login && resp.is_unauthenticated {
                let is_auth = { self.state.lock().unwrap().current_user.is_some() };
                if is_auth {
                    requested_login = true;
                    let _ = native_call("navigate", json!({ "url": "https://linux.do/login" }), 8).await;
                    if !panel_shown {
                        panel_shown = true;
                        let _ = native_call("show", json!({ "dark": is_dark }), 8).await;
                    }
                    continue;
                }
            }

            if requested_login && !panel_shown && !is_otp_flow {
                panel_shown = true;
                let _ = native_call("show", json!({ "dark": is_dark }), 8).await;
            }
            tokio::time::sleep(Duration::from_millis(1400)).await;
        }
        Ok(false)
    }

    fn extract_current_user(body: &str) -> Option<UserProfile> {
        let root: Value = serde_json::from_str(body).ok()?;
        let u = root.get("current_user")?;
        let username = u.get("username")?.as_str()?.trim().to_string();
        if username.is_empty() {
            return None;
        }
        Some(UserProfile {
            user_id: u.get("id").and_then(|v| v.as_i64()).unwrap_or(0),
            display_name: u
                .get("name")
                .and_then(|v| v.as_str())
                .filter(|s| !s.trim().is_empty())
                .unwrap_or(&username)
                .to_string(),
            username,
            avatar_template: u
                .get("avatar_template")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string(),
            animated_avatar: u
                .get("animated_avatar")
                .and_then(|v| v.as_str())
                .filter(|s| !s.is_empty())
                .map(|s| s.to_string()),
            trust_level: u.get("trust_level").and_then(|v| v.as_i64()).unwrap_or(1) as i32,
            user_title: u
                .get("title")
                .and_then(|v| v.as_str())
                .filter(|s| !s.is_empty())
                .map(|s| s.to_string()),
        })
    }

    fn commit_authenticated_user(&self, app: &AppHandle, profile: UserProfile) {
        let logged_username = profile.username.clone();
        self.session_generation.fetch_add(1, Ordering::SeqCst);
        self.verify_coord.lock().unwrap().reset_on_healthy_response();
        {
            let mut rt = self.inner.lock().unwrap();
            rt.last_login_at = Some(Instant::now());
            rt.fg_challenge_until = None;
            rt.bg_challenge_until = None;
            rt.bootstrap_challenged = false;
            rt.rate_limit_until = None;
        }
        {
            let mut st = self.state.lock().unwrap();
            let auth_cookies: String = st
                .raw_cookies
                .split(';')
                .map(|s| s.trim())
                .filter(|s| s.starts_with("_t=") || s.starts_with("_forum_session="))
                .collect::<Vec<_>>()
                .join("; ");
            st.current_user = Some(profile.clone());
            st.expired_username = None;
            Self::upsert_saved_account(&mut st, profile, auth_cookies, false);
            save_state(&st);
        }
        emit_diag(
            app,
            "INFO",
            "AUTH",
            "AuthSuccess",
            &format!("登录会话已就绪 · @{}", logged_username),
            None,
        );
        let _ = app.emit("lindo://auth-completing", false);
        let _ = app.emit("lindo://state-updated", ());
        // After external browser OTP/OAuth login, WebKit may hold `_t` without `cf_clearance`:
        // silently prewarm `/challenge` in the background once initial post-login GETs settle!
        if let Some(eng) = app.try_state::<Arc<BridgeEngine>>() {
            let eng_clone = Arc::clone(&eng);
            tauri::async_runtime::spawn(async move {
                tokio::time::sleep(Duration::from_millis(1800)).await;
                let _ = eng_clone.prewarm_cf_clearance_if_needed().await;
            });
        }
    }

    pub async fn start_browser_one_click_auth(&self) -> Result<String, String> {
        let (public_pem, private_b64) = crate::crypto_rsa::generate_rsa_keypair()?;

        let nonce = uuid::Uuid::new_v4().simple().to_string();
        let client_id = { self.state.lock().unwrap().client_id.clone() };
        let now_ms = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as u64;

        save_pending_rsa(&PendingRsaDiskRecord {
            private_base64: private_b64.clone(),
            nonce: nonce.clone(),
            created_at_ms: now_ms,
        });
        self.inner.lock().unwrap().pending_rsa = Some(PendingRsaGrant {
            private_base64: private_b64,
            nonce: nonce.clone(),
            created_at_ms: now_ms,
        });

        let encode = |s: &str| -> String {
            let mut out = String::new();
            for b in s.bytes() {
                match b {
                    b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                        out.push(b as char)
                    }
                    b' ' => out.push('+'),
                    _ => out.push_str(&format!("%{:02X}", b)),
                }
            }
            out
        };

        let url = format!(
            "https://linux.do/user-api-key/new?application_name={}&client_id={}&scopes=one_time_password&public_key={}&nonce={}&auth_redirect={}&otp=true",
            encode("Lindo"),
            encode(&client_id),
            encode(&public_pem),
            encode(&nonce),
            encode("discourse://auth_redirect")
        );
        Ok(url)
    }

    fn url_decode(raw: &str) -> String {
        let bytes = raw.as_bytes();
        let mut out = Vec::with_capacity(bytes.len());
        let mut i = 0;
        while i < bytes.len() {
            if bytes[i] == b'+' {
                out.push(b' ');
                i += 1;
            } else if bytes[i] == b'%' && i + 2 < bytes.len() {
                if let Ok(hex_str) = std::str::from_utf8(&bytes[i + 1..i + 3]) {
                    if let Ok(val) = u8::from_str_radix(hex_str, 16) {
                        out.push(val);
                        i += 3;
                        continue;
                    }
                }
                out.push(bytes[i]);
                i += 1;
            } else {
                out.push(bytes[i]);
                i += 1;
            }
        }
        String::from_utf8_lossy(&out).into_owned()
    }

    pub async fn handle_auth_redirect(&self, app: &AppHandle, uri_str: &str) -> Result<bool, String> {
        let trimmed_uri = uri_str.trim();
        let Some((prefix, query)) = trimmed_uri.split_once('?') else {
            return Ok(false);
        };
        let prefix_lower = prefix.to_ascii_lowercase();
        if !prefix_lower.starts_with("discourse://auth_redirect")
            && !prefix_lower.starts_with("lindo://auth_redirect")
        {
            return Ok(false);
        }

        if let Some(w) = app.get_webview_window("main") {
            let _ = w.unminimize();
            let _ = w.show();
            let _ = w.set_focus();
        }
        emit_diag(
            app,
            "INFO",
            "AUTH",
            "AuthPending",
            "收到默认浏览器授权回调，正在校验并兑换登录会话",
            None,
        );

        // Peek pending RSA grant without destroying it until payload & nonce pass verification
        let grant = { self.inner.lock().unwrap().pending_rsa.clone() }.or_else(|| {
            load_pending_rsa().map(|d| PendingRsaGrant {
                private_base64: d.private_base64,
                nonce: d.nonce,
                created_at_ms: d.created_at_ms,
            })
        });
        let Some(grant) = grant else {
            emit_diag(
                app,
                "WARN",
                "AUTH",
                "BrowserAuth",
                "未找到待确认的浏览器授权会话（可能已完成或已重置）",
                None,
            );
            let _ = app.emit(
                "lindo://auth-notice",
                "未找到待确认的授权请求，请重新点击「使用默认浏览器一键授权」",
            );
            return Ok(false);
        };

        let now_ms = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as u64;
        if now_ms.saturating_sub(grant.created_at_ms) > 900_000 {
            clear_pending_rsa();
            emit_diag(
                app,
                "WARN",
                "AUTH",
                "BrowserAuth",
                "浏览器授权链接已超过 15 分钟有效期",
                None,
            );
            let _ = app.emit(
                "lindo://auth-notice",
                "授权链接已超过 15 分钟有效期，请重新发起浏览器授权",
            );
            return Ok(false);
        }

        let mut params = HashMap::new();
        for pair in query.split('&') {
            if let Some((k, v)) = pair.split_once('=') {
                params.insert(k.to_string(), Self::url_decode(v));
            }
        }

        let normalize_b64 = |s: &str| -> String {
            s.trim()
                .replace(' ', "+")
                .replace('-', "+")
                .replace('_', "/")
        };

        let Some(payload_cipher) = params.get("payload").filter(|s| !s.trim().is_empty()) else {
            emit_diag(
                app,
                "ERROR",
                "AUTH",
                "BrowserAuth",
                "浏览器授权回调缺少 payload 参数",
                None,
            );
            let _ = app.emit("lindo://auth-notice", "授权回调缺少 payload 参数，请重试");
            return Ok(false);
        };
        let plain_payload = match crate::crypto_rsa::decrypt_rsa_pkcs1v15(
            &grant.private_base64,
            &normalize_b64(payload_cipher),
        ) {
            Ok(plain) => plain,
            Err(dec_err) => {
                emit_diag(
                    app,
                    "WARN",
                    "AUTH",
                    "BrowserAuth",
                    "回调密钥不匹配（可能点击了旧的浏览器授权标签页）",
                    Some(&dec_err),
                );
                let _ = app.emit(
                    "lindo://auth-notice",
                    "收到的是旧授权页回调，请在最新打开的浏览器授权页点击「授权」",
                );
                return Ok(false);
            }
        };
        let Ok(payload_json) = serde_json::from_str::<Value>(&plain_payload) else {
            emit_diag(
                app,
                "ERROR",
                "AUTH",
                "BrowserAuth",
                "浏览器授权回调载荷 JSON 解析失败",
                None,
            );
            let _ = app.emit("lindo://auth-notice", "授权载荷解析失败，请重试");
            return Ok(false);
        };
        let returned_nonce = payload_json
            .get("nonce")
            .and_then(|v| v.as_str())
            .unwrap_or("");
        if returned_nonce != grant.nonce {
            emit_diag(
                app,
                "WARN",
                "AUTH",
                "BrowserAuth",
                "浏览器授权回调 nonce 不匹配，请使用最新打开的授权页",
                None,
            );
            let _ = app.emit(
                "lindo://auth-notice",
                "收到的是较早一次授权页的回调，请使用最新打开的授权页",
            );
            return Ok(false);
        }

        let otp = if let Some(enc_otp) = params
            .get("oneTimePassword")
            .or_else(|| params.get("one_time_password"))
            .filter(|s| !s.trim().is_empty())
        {
            crate::crypto_rsa::decrypt_rsa_pkcs1v15(&grant.private_base64, &normalize_b64(enc_otp))
                .ok()
                .map(|s| s.trim().to_string())
        } else {
            payload_json
                .get("one_time_password")
                .and_then(|v| v.as_str())
                .map(|s| s.trim().to_string())
        };

        let Some(otp_token) = otp.filter(|s| !s.is_empty()) else {
            emit_diag(
                app,
                "ERROR",
                "AUTH",
                "BrowserAuth",
                "授权回调未包含有效的一次性登录令牌 (OTP)",
                None,
            );
            let _ = app.emit("lindo://auth-notice", "未获取到一次性登录令牌，请重试");
            return Ok(false);
        };

        // Guard against duplicate delivery of the exact same callback URI
        {
            let mut rt = self.inner.lock().unwrap();
            if rt.consumed_nonces.contains(&grant.nonce) {
                return Ok(true);
            }
            rt.consumed_nonces.push(grant.nonce.clone());
            if rt.consumed_nonces.len() > 16 {
                rt.consumed_nonces.remove(0);
            }
            rt.pending_rsa = None;
        }
        clear_pending_rsa();

        // Hold `verify_lock` + `rt.verifying = true` across the ENTIRE OTP redemption & user commit sequence:
        // guarantees background `silent_revalidate_challenge` or `seed_session` NEVER navigates `gHost.web`
        // or calls `sweep_auth_cookies` on the newly redeemed `_t` before `commit_authenticated_user` finishes!
        let _verify_guard = self.verify_lock.lock().await;
        let _verifying_flag = VerifyingFlagGuard::enter(&self.inner);

        let _ = app.emit("lindo://auth-completing", true);

        // 1. 初始化桥接并清理任何历史残留的死 _t / _forum_session（100% 保留 cf_clearance），然后等待同源 `/about` 稳定就绪
        if let Err(e) = native_call("init", json!({}), 10).await {
            let _ = app.emit("lindo://auth-completing", false);
            emit_diag(
                app,
                "ERROR",
                "AUTH",
                "BrowserAuth",
                "初始化本地安全桥接失败",
                Some(&e),
            );
            let _ = app.emit("lindo://auth-notice", format!("初始化安全会话失败：{e}"));
            return Ok(false);
        }
        self.inner.lock().unwrap().initialized = true;
        self.sweep_auth_cookies(true, None).await;
        {
            let mut st = self.state.lock().unwrap();
            let cf_only: Vec<String> = st
                .raw_cookies
                .split(';')
                .map(|s| s.trim())
                .filter(|s| !s.is_empty() && !s.starts_with("_t=") && !s.starts_with("_forum_session="))
                .map(|s| s.to_string())
                .collect();
            st.raw_cookies = cf_only.join("; ");
        }
        self.inner.lock().unwrap().last_seeded_token = String::new();

        // Helper closure: ensure `gHost.web` is settled on `https://linux.do` and NOT mid-navigation before running JS fetch!
        let settle_origin = async {
            let cur = native_call(
                "evaluate",
                json!({
                    "isolated": true,
                    "script": "({ origin: location.origin, ready: document.readyState === 'complete' || document.readyState === 'interactive' })"
                }),
                5,
            )
            .await
            .ok();
            let st_loading = native_call("status", json!({}), 4)
                .await
                .ok()
                .and_then(|s| s.get("loading")?.as_bool())
                .unwrap_or(false);
            let already_settled = !st_loading
                && cur
                    .as_ref()
                    .map(|c| {
                        c.get("origin").and_then(|v| v.as_str()) == Some("https://linux.do")
                            && c.get("ready").and_then(|v| v.as_bool()) == Some(true)
                    })
                    .unwrap_or(false);
            if !already_settled {
                let _ = native_call("navigate", json!({ "url": "https://linux.do/about" }), 8).await;
                let wait_until = Instant::now() + Duration::from_secs(8);
                while Instant::now() < wait_until {
                    tokio::time::sleep(Duration::from_millis(85)).await;
                    let loading_now = native_call("status", json!({}), 4)
                        .await
                        .ok()
                        .and_then(|s| s.get("loading")?.as_bool())
                        .unwrap_or(false);
                    let ok = native_call(
                        "evaluate",
                        json!({
                            "isolated": true,
                            "script": "location.origin === 'https://linux.do' && document.readyState === 'complete'"
                        }),
                        4,
                    )
                    .await
                    .ok()
                    .and_then(|v| v.as_bool())
                    .unwrap_or(false);
                    if ok && !loading_now {
                        break;
                    }
                }
            }
        };
        settle_origin.await;

        // 2. 单次 OTP 极其珍贵：兑换前用 `in_verification = true` 探测 `/session/csrf.json` 确认未被 Cloudflare 拦截；
        //    若首次无 `cf_clearance` 遇到挑战，先完成站点验证放行并等待 `/about` 彻底落稳（绝不提前消耗或中途打断 OTP！）。
        let is_dark = { self.state.lock().unwrap().preferences.appearance != "light" };
        let csrf_probe = self
            .request_unlocked(app, "https://linux.do/session/csrf.json", "GET", None, None, true)
            .await;
        let pre_challenged = match &csrf_probe {
            Ok(r) => r.is_challenge || !(200..300).contains(&r.status),
            Err(_) => true,
        };
        if pre_challenged {
            emit_diag(
                app,
                "WARN",
                "AUTH",
                "AuthPending",
                "兑换登录令牌前需完成站点安全验证，正在打开安全验证面板",
                None,
            );
            let _ = app.emit("lindo://auth-completing", false);
            let verified = self
                .run_verify_loop(app, false, is_dark, None)
                .await
                .unwrap_or(false);
            let _ = native_call("hide", json!({}), 5).await;
            if !verified {
                emit_diag(
                    app,
                    "WARN",
                    "AUTH",
                    "BrowserAuth",
                    "站点安全验证未完成，已中止本次授权令牌兑换",
                    None,
                );
                let _ = app.emit("lindo://auth-notice", "请先完成站点验证后再完成授权登录");
                return Ok(false);
            }
            let _ = app.emit("lindo://auth-completing", true);
            // Wait for `run_verify_loop`'s trailing `/about` navigation to finish completely before `otp_eval`!
            let settle_deadline = Instant::now() + Duration::from_secs(6);
            while Instant::now() < settle_deadline {
                let loading_now = native_call("status", json!({}), 4)
                    .await
                    .ok()
                    .and_then(|s| s.get("loading")?.as_bool())
                    .unwrap_or(false);
                let ok = native_call(
                    "evaluate",
                    json!({
                        "isolated": true,
                        "script": "location.origin === 'https://linux.do' && document.readyState === 'complete'"
                    }),
                    4,
                )
                .await
                .ok()
                .and_then(|v| v.as_bool())
                .unwrap_or(false);
                if ok && !loading_now {
                    break;
                }
                tokio::time::sleep(Duration::from_millis(80)).await;
            }
        }

        // 3. 在同源 WebKit 内原子获取最新 CSRF 并以 redirect:'manual' 提交 POST /session/otp/{otp}（直接落盘 Set-Cookie: _t=...）
        let otp_cfg = json!({ "otp": otp_token });
        let otp_script = format!(
            r#"(async () => {{
                try {{
                    if (location.origin !== 'https://linux.do') return {{ status: 0, otpSent: false, err: 'origin_mismatch' }};
                    const cfg = {otp_cfg};
                    const cr = await fetch('/session/csrf.json', {{
                        credentials: 'include',
                        cache: 'no-store',
                        headers: {{ 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' }},
                        signal: AbortSignal.timeout(12000)
                    }});
                    if (!cr.ok) {{
                        return {{ status: cr.status, otpSent: false, challenge: cr.headers.get('cf-mitigated') || '' }};
                    }}
                    const csrf = (await cr.json()).csrf;
                    if (!csrf) return {{ status: 422, otpSent: false, err: 'empty_csrf' }};
                    window.__lindoCsrf = csrf;
                    const r = await fetch('/session/otp/' + encodeURIComponent(cfg.otp), {{
                        method: 'POST',
                        credentials: 'include',
                        redirect: 'manual',
                        headers: {{
                            'X-CSRF-Token': csrf,
                            'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8'
                        }},
                        body: '',
                        signal: AbortSignal.timeout(15000)
                    }});
                    return {{
                        status: r.type === 'opaqueredirect' ? 302 : r.status,
                        otpSent: true,
                        challenge: (r.headers && r.headers.get('cf-mitigated')) || ''
                    }};
                }} catch (e) {{
                    return {{ status: 0, otpSent: false, err: String((e && e.message) || e) }};
                }}
            }})()"#
        );

        let otp_eval = native_call("evaluate", json!({ "script": otp_script }), 22)
            .await
            .unwrap_or_else(|e| json!({ "status": 0, "otpSent": false, "err": e }));
        let otp_status = otp_eval.get("status").and_then(|v| v.as_u64()).unwrap_or(0) as u16;
        let otp_sent = otp_eval.get("otpSent").and_then(|v| v.as_bool()).unwrap_or(false);
        let otp_challenged = otp_eval.get("challenge").and_then(|v| v.as_str()) == Some("challenge");

        if !otp_sent && otp_challenged {
            let _ = app.emit("lindo://auth-completing", false);
            let ok = self
                .run_verify_loop(app, false, is_dark, Some(&otp_token))
                .await
                .unwrap_or(false);
            let _ = native_call("hide", json!({}), 5).await;
            return Ok(ok);
        }

        if otp_sent && ((200..400).contains(&otp_status) || otp_status == 0) {
            // Allow WKHTTPCookieStore 150ms to flush Set-Cookie: _t=... before reading `/session/current.json`
            tokio::time::sleep(Duration::from_millis(150)).await;

            // 4A. 快速通道：直接通过 `request_unlocked(..., true)` 读取 `/session/current.json`（不触发 `seed_session` 删 Cookie，也不加载重型首页）
            for attempt in 0..3 {
                if attempt > 0 {
                    tokio::time::sleep(Duration::from_millis(450)).await;
                }
                if let Ok(me) = self
                    .request_unlocked(
                        app,
                        "https://linux.do/session/current.json",
                        "GET",
                        None,
                        None,
                        true,
                    )
                    .await
                {
                    if let Some(profile) = Self::extract_current_user(&me.body) {
                        let _ = self.sync_cookies_from_webkit_verified(true, true).await;
                        let _ = native_call(
                            "evaluate",
                            json!({ "script": "(() => { window.__lindoCsrf = null; window.__lindoIgnoreDomCsrf = true; return true; })()" }),
                            4,
                        )
                        .await;
                        self.inner.lock().unwrap().origin_ready = true;
                        self.commit_authenticated_user(app, profile);
                        let _ = app.emit("lindo://auth-notice", "");
                        return Ok(true);
                    }
                }
            }

            // 4B. 兜底通道：若 `/session/current.json` 偶发限流，从主站 HTML `data-preloaded` 提取当前用户并恢复 `/about`
            let _ = native_call("navigate", json!({ "url": "https://linux.do/" }), 10).await;
            let nav_deadline = Instant::now() + Duration::from_secs(8);
            let mut preloaded_profile: Option<UserProfile> = None;
            while Instant::now() < nav_deadline {
                tokio::time::sleep(Duration::from_millis(200)).await;
                let dom_check = native_call(
                    "evaluate",
                    json!({
                        "isolated": true,
                        "script": r#"(() => {
                            const domReady = document.readyState === 'interactive' || document.readyState === 'complete';
                            if (location.origin !== 'https://linux.do' || !domReady) return null;
                            const el = document.getElementById('data-preloaded');
                            if (!el) return document.readyState === 'complete' ? { ready: true, userJson: null } : null;
                            try {
                                const raw = el.getAttribute('data-preloaded') || el.textContent || '';
                                const outer = raw ? JSON.parse(raw) : null;
                                if (outer && typeof outer.currentUser === 'string' && outer.currentUser.length > 2) {
                                    return { ready: true, userJson: JSON.stringify({ current_user: JSON.parse(outer.currentUser) }) };
                                } else if (outer && outer.currentUser && typeof outer.currentUser === 'object') {
                                    return { ready: true, userJson: JSON.stringify({ current_user: outer.currentUser }) };
                                }
                            } catch (_) {}
                            return { ready: true, userJson: null };
                        })()"#
                    }),
                    6,
                )
                .await
                .ok();
                if let Some(obj) = dom_check {
                    if obj.get("ready").and_then(|v| v.as_bool()) == Some(true) {
                        if let Some(ujson) = obj.get("userJson").and_then(|v| v.as_str()) {
                            preloaded_profile = Self::extract_current_user(ujson);
                        }
                        break;
                    }
                }
            }

            let _ = self.sync_cookies_from_webkit_verified(true, true).await;
            let _ = native_call("navigate", json!({ "url": "https://linux.do/about" }), 6).await;
            let _ = native_call(
                "evaluate",
                json!({ "script": "(() => { window.__lindoCsrf = null; window.__lindoIgnoreDomCsrf = true; return true; })()" }),
                4,
            )
            .await;
            self.inner.lock().unwrap().origin_ready = true;

            if let Some(profile) = preloaded_profile {
                self.commit_authenticated_user(app, profile);
                let _ = app.emit("lindo://auth-notice", "");
                return Ok(true);
            }
        }

        // 兜底：仅当 fetch POST 尚未消耗单次 OTP 时，通过可视化表单确认完成兑换
        let _ = app.emit("lindo://auth-completing", false);
        let fallback_ok = if !otp_sent {
            let ok = self
                .run_verify_loop(app, false, is_dark, Some(&otp_token))
                .await
                .unwrap_or(false);
            let _ = native_call("hide", json!({}), 5).await;
            ok
        } else {
            false
        };

        if !fallback_ok {
            let err_detail = otp_eval
                .get("err")
                .and_then(|v| v.as_str())
                .unwrap_or("");
            emit_diag(
                app,
                "ERROR",
                "AUTH",
                "BrowserAuth",
                &format!("浏览器授权回调兑换会话未成功 (HTTP {otp_status})"),
                if err_detail.is_empty() { None } else { Some(err_detail) },
            );
            let _ = app.emit(
                "lindo://auth-notice",
                "浏览器授权回调未能完成会话兑换，请再试一次或使用内置登录",
            );
        }
        Ok(fallback_ok)
    }

    pub async fn replace_account_cookies(&self, raw_cookies: &str) -> Result<(), String> {
        let _acct = self.account_lock.write().await;
        self.session_generation.fetch_add(1, Ordering::SeqCst);
        self.verify_coord.lock().unwrap().reset_on_session_change();
        native_call("init", json!({}), 10).await?;
        {
            let mut rt = self.inner.lock().unwrap();
            rt.initialized = true;
            rt.origin_ready = false;
            rt.bootstrap_challenged = false;
            rt.last_seeded_token = "__unseeded__".to_string();
        }

        native_call("navigate", json!({ "url": "about:blank" }), 10).await?;
        tokio::time::sleep(Duration::from_millis(120)).await;

        self.sweep_auth_cookies(true, None).await;

        let stored = native_call("cookies", json!({}), 10).await?;
        let cf_names = ["cf_clearance", "__cf_bm", "_cfuvid", "cf_chl_rc_i", "cf_chl_rc_ni"];
        if let Some(arr) = stored.get("cookies").and_then(|c| c.as_array()) {
            for item in arr {
                let name = item.get("name").and_then(|v| v.as_str()).unwrap_or("");
                let domain = item.get("domain").and_then(|v| v.as_str()).unwrap_or("linux.do");
                let path = item.get("path").and_then(|v| v.as_str()).unwrap_or("/");
                if !cf_names.contains(&name) {
                    let _ = native_call(
                        "deleteCookie",
                        json!({ "name": name, "domain": domain, "path": path }),
                        6,
                    )
                    .await;
                }
            }
        }

        let map = Self::parse_cookie_map(raw_cookies);
        let mut to_set = Vec::new();
        let mut installed_t = String::new();
        for name in ["_t", "_forum_session"] {
            if let Some(val) = map.get(name) {
                if !val.is_empty() {
                    if name == "_t" {
                        installed_t = val.clone();
                    }
                    to_set.push(json!({ "name": name, "value": val }));
                }
            }
        }
        if !to_set.is_empty() {
            native_call("setCookies", json!({ "cookies": to_set }), 10).await?;
        }
        self.inner.lock().unwrap().last_seeded_token = installed_t;
        native_call("navigate", json!({ "url": "https://linux.do/about" }), 10).await?;
        let deadline = Instant::now() + Duration::from_secs(12);
        while Instant::now() < deadline {
            let ok = native_call(
                "evaluate",
                json!({ "script": "location.origin === 'https://linux.do' && document.readyState !== 'loading'" }),
                6,
            )
            .await
            .ok()
            .and_then(|v| v.as_bool())
            .unwrap_or(false);
            if ok {
                self.inner.lock().unwrap().origin_ready = true;
                break;
            }
            tokio::time::sleep(Duration::from_millis(60)).await;
        }
        let _ = native_call(
            "evaluate",
            json!({ "script": "(() => { window.__lindoCsrf = null; window.__lindoIgnoreDomCsrf = true; return true; })()" }),
            4,
        )
        .await;
        Ok(())
    }

    pub async fn switch_saved_account(&self, app: &AppHandle, username: &str) -> Result<bool, String> {
        let target = {
            let st = self.state.lock().unwrap();
            st.saved_accounts
                .iter()
                .find(|a| a.profile.username.eq_ignore_ascii_case(username))
                .cloned()
        };
        let Some(entry) = target else {
            return Ok(false);
        };
        if entry.needs_login || entry.cookies.is_empty() {
            return Ok(false);
        }
        self.replace_account_cookies(&entry.cookies).await?;
        {
            let mut rt = self.inner.lock().unwrap();
            rt.last_login_at = Some(Instant::now());
            rt.fg_challenge_until = None;
            rt.bg_challenge_until = None;
            rt.bootstrap_challenged = false;
        }
        {
            let mut st = self.state.lock().unwrap();
            st.current_user = Some(entry.profile);
            st.raw_cookies = entry.cookies;
            st.expired_username = None;
            save_state(&st);
        }
        let _ = self.sync_cookies_from_webkit(false).await;
        let _ = app.emit("lindo://state-updated", ());
        Ok(true)
    }

    pub async fn logout(&self, app: &AppHandle, remove_saved: bool) -> Result<(), String> {
        {
            let mut st = self.state.lock().unwrap();
            if remove_saved {
                if let Some(ref u) = st.current_user {
                    let uname = u.username.clone();
                    st.saved_accounts
                        .retain(|a| !a.profile.username.eq_ignore_ascii_case(&uname));
                }
            }
            st.current_user = None;
            st.expired_username = None;
            st.unread_notifications = 0;
            let cf_only: Vec<String> = st
                .raw_cookies
                .split(';')
                .map(|s| s.trim())
                .filter(|s| {
                    !s.is_empty() && !s.starts_with("_t=") && !s.starts_with("_forum_session=")
                })
                .map(|s| s.to_string())
                .collect();
            st.raw_cookies = cf_only.join("; ");
            save_state(&st);
        }
        self.set_dock_badge(0).await;
        let _ = self.replace_account_cookies("").await;
        let _ = app.emit("lindo://state-updated", ());
        Ok(())
    }

    pub async fn remove_saved_account(&self, app: &AppHandle, username: &str) -> Result<(), String> {
        {
            let mut st = self.state.lock().unwrap();
            st.saved_accounts
                .retain(|a| !a.profile.username.eq_ignore_ascii_case(username));
            save_state(&st);
        }
        let _ = app.emit("lindo://state-updated", ());
        Ok(())
    }

    pub async fn add_account_via_web(&self, app: &AppHandle, is_dark: bool) -> Result<bool, String> {
        // Snapshot current account before clearing auth cookies for new login
        let _ = self.sync_cookies_from_webkit(false).await;
        let backup = {
            let st = self.state.lock().unwrap();
            (st.current_user.clone(), st.raw_cookies.clone())
        };
        let _ = self.replace_account_cookies("").await;
        let ok = self.verify_or_login(app, true, is_dark, true).await?;
        if !ok {
            if let (Some(prev_user), prev_cookies) = backup {
                let _ = self.replace_account_cookies(&prev_cookies).await;
                let mut st = self.state.lock().unwrap();
                st.current_user = Some(prev_user);
                st.raw_cookies = prev_cookies;
                save_state(&st);
                let _ = app.emit("lindo://state-updated", ());
            }
        }
        Ok(ok)
    }

    pub async fn import_manual_credential(
        &self,
        app: &AppHandle,
        raw_input: &str,
        is_dark: bool,
    ) -> Result<bool, String> {
        let trimmed = raw_input.trim();
        if trimmed.is_empty() {
            return Err("请输入授权回调链接、_t Cookie 或 cURL 命令".to_string());
        }
        if trimmed.starts_with("discourse://auth_redirect")
            || trimmed.starts_with("lindo://auth_redirect")
        {
            return self.handle_auth_redirect(app, trimmed).await;
        }

        // Extract cookie header from cURL (`-b '...'` / `-H 'cookie: ...'`), `Cookie: ...`, `_t=...`, or bare token
        let mut extracted_t = String::new();
        let mut extracted_fs = String::new();

        for seg in trimmed
            .replace('\n', " ")
            .replace('\r', " ")
            .split(&[';', '\'', '"'][..])
        {
            let s = seg.trim().trim_start_matches("Cookie:").trim_start_matches("cookie:").trim();
            if let Some(rest) = s.strip_prefix("_t=") {
                let clean = rest.split_whitespace().next().unwrap_or("").trim();
                if !clean.is_empty() {
                    extracted_t = clean.to_string();
                }
            } else if let Some(rest) = s.strip_prefix("_forum_session=") {
                let clean = rest.split_whitespace().next().unwrap_or("").trim();
                if !clean.is_empty() {
                    extracted_fs = clean.to_string();
                }
            }
        }

        if extracted_t.is_empty()
            && !trimmed.contains(' ')
            && !trimmed.contains('=')
            && trimmed.len() >= 16
        {
            extracted_t = trimmed.to_string();
        }

        if extracted_t.is_empty() {
            return Err("未检测到有效的 _t 登录会话 Cookie 或回调链接".to_string());
        }

        let mut cookie_str = format!("_t={extracted_t}");
        if !extracted_fs.is_empty() {
            cookie_str.push_str(&format!("; _forum_session={extracted_fs}"));
        }

        self.replace_account_cookies(&cookie_str).await?;
        let mut me = self
            .request(app, "https://linux.do/session/current.json", "GET", None, None)
            .await?;
        if me.is_challenge {
            if self.verify_or_login(app, false, is_dark, true).await.unwrap_or(false) {
                me = self
                    .request(app, "https://linux.do/session/current.json", "GET", None, None)
                    .await?;
            }
        }
        if let Some(profile) = Self::extract_current_user(&me.body) {
            {
                let mut st = self.state.lock().unwrap();
                st.raw_cookies = cookie_str;
            }
            let _ = self.sync_cookies_from_webkit(true).await;
            self.commit_authenticated_user(app, profile);
            return Ok(true);
        }
        Err("凭证校验未通过，请确认复制了最新的 _t Cookie 或回调链接".to_string())
    }

    pub async fn open_forum_web_panel(&self, url: &str, is_dark: bool) -> Result<(), String> {
        let initialized = self.inner.lock().unwrap().initialized;
        if !initialized {
            native_call("init", json!({}), 10).await?;
            self.inner.lock().unwrap().initialized = true;
        }
        let _ = self.seed_session_verified(true).await;
        native_call("openBrowser", json!({ "url": url, "dark": is_dark }), 10).await?;
        Ok(())
    }

    pub async fn fetch_connect_html(&self) -> Result<String, String> {
        let initialized = self.inner.lock().unwrap().initialized;
        if !initialized {
            native_call("init", json!({}), 10).await?;
            self.inner.lock().unwrap().initialized = true;
        }
        let _ = self.seed_session_verified(true).await;
        let res = native_call("fetchConnectHtml", json!({}), 12).await?;
        Ok(res
            .get("html")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cf_authority_cold_seed_respects_browser_presence_and_ttl() {
        let now = 1_700_000_000_000u64;
        let auth = CfClearanceAuthority::from_persisted(
            "_t=tok; cf_clearance=recent_cf",
            now - 3600_000,
            now,
        );
        // Never overwrite live browser cf_clearance
        assert!(!auth.should_seed_cold_start("recent_cf", now - 3600_000, true, now));
        // Seed recent unchallenged cf_clearance when browser has none
        assert!(auth.should_seed_cold_start("recent_cf", now - 3600_000, false, now));
        // Never seed ancient (> 12h) cf_clearance
        assert!(!auth.should_seed_cold_start(
            "recent_cf",
            now - (13 * 3600 * 1000),
            false,
            now
        ));
    }

    #[test]
    fn challenge_url_routes_to_dedicated_challenge_endpoint_and_probe_path() {
        assert_eq!(
            BridgeEngine::challenge_url_to_html_url(
                "https://linux.do/t/1895432.json?track_visit=true&forceLoad=true"
            ),
            "https://linux.do/challenge"
        );
        assert_eq!(
            BridgeEngine::challenge_url_to_probe_get_url(
                "https://linux.do/t/1895432/posts.json?post_ids%5B%5D=99123"
            ),
            "https://linux.do/t/1895432.json?track_visit=false"
        );
        assert_eq!(
            BridgeEngine::challenge_url_to_html_url("https://linux.do/latest.json?page=1"),
            "https://linux.do/challenge"
        );
        assert_eq!(
            BridgeEngine::challenge_url_to_probe_get_url("https://linux.do/posts.json"),
            "https://linux.do/latest.json?no_definitions=true"
        );
    }

    #[test]
    fn verifying_flag_guard_and_discourse_url_encoded_rsa_callback_roundtrip() {
        let eng = BridgeEngine::new(PersistedState::default());
        assert!(!eng.inner.lock().unwrap().verifying);
        {
            let _g = VerifyingFlagGuard::enter(&eng.inner);
            assert!(eng.inner.lock().unwrap().verifying);
        }
        assert!(!eng.inner.lock().unwrap().verifying);

        // Verify diagnostic runtime snapshot reports healthy initial policy state
        let snap = eng.diag_runtime_snapshot();
        assert_eq!(snap.session_generation, 1);
        assert_eq!(snap.verify_round, 0);
        assert_eq!(snap.verify_consecutive_failures, 0);
        assert!(!snap.verify_ineffective_breaker);
        assert!(snap.pacer_rate_per_sec >= 3.0);

        // Simulate Ruby Discourse CGI.escape(Base64.encode64(...)) with 60-col `%0A` line wraps and `+`/`/`/`=`
        use base64::engine::general_purpose::STANDARD as B64;
        use base64::Engine;
        use rand::rngs::OsRng;
        use rsa::pkcs8::DecodePublicKey;
        use rsa::{Pkcs1v15Encrypt, RsaPublicKey};

        let (pub_pem, priv_b64) = crate::crypto_rsa::generate_rsa_keypair().unwrap();
        let pub_key = RsaPublicKey::from_public_key_pem(&pub_pem).unwrap();
        let raw_json = r#"{"nonce":"nonce_7788","one_time_password":"otp_token_991122"}"#;
        let cipher = pub_key
            .encrypt(&mut OsRng, Pkcs1v15Encrypt, raw_json.as_bytes())
            .unwrap();
        let wrapped_b64 = B64
            .encode(&cipher)
            .as_bytes()
            .chunks(60)
            .map(|c| std::str::from_utf8(c).unwrap())
            .collect::<Vec<_>>()
            .join("\n")
            + "\n";
        let cgi_escaped = wrapped_b64
            .replace('+', "%2B")
            .replace('/', "%2F")
            .replace('=', "%3D")
            .replace('\n', "%0A");
        let decoded = BridgeEngine::url_decode(&cgi_escaped);
        let plain = crate::crypto_rsa::decrypt_rsa_pkcs1v15(&priv_b64, &decoded).unwrap();
        assert_eq!(plain, raw_json);
    }

    #[test]
    fn cf_challenge_classifier_separates_cf_429_from_discourse_json_429() {
        // 1. CF managed challenge on 429 MUST be classified as CF challenge (never blind rate-limit loop)
        assert!(BridgeEngine::is_cf_challenge_response(
            429,
            "challenge",
            "text/html; charset=UTF-8",
            "<html><title>Just a moment...</title></html>"
        ));
        assert!(BridgeEngine::is_cf_challenge_response(
            429,
            "",
            "text/html; charset=UTF-8",
            "<!DOCTYPE html><html><head><title>Just a moment...</title></head><script>window._cf_chl_opt={};</script></html>"
        ));
        // 2. Genuine Discourse JSON 429 (e.g., reaction toggle / reply cooldown) MUST NOT be classified as CF challenge
        assert!(!BridgeEngine::is_cf_challenge_response(
            429,
            "",
            "application/json; charset=utf-8",
            r#"{"errors":["您执行此操作的次数过多。请等待 29 秒后再试。"],"error_type":"rate_limit","extras":{"wait_seconds":29}}"#
        ));
        // 3. Genuine Discourse JSON 403 MUST NOT be classified as CF challenge, while CF 403 IS
        assert!(!BridgeEngine::is_cf_challenge_response(
            403,
            "",
            "application/json; charset=utf-8",
            r#"{"errors":["You need to be logged in"]}"#
        ));
        assert!(BridgeEngine::is_cf_challenge_response(
            403,
            "challenge",
            "text/html; charset=UTF-8",
            "<html><title>Just a moment...</title></html>"
        ));
    }
}
