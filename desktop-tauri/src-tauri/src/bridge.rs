use crate::legacy::{
    clear_pending_rsa, load_pending_rsa, save_pending_rsa, save_state, PendingRsaDiskRecord,
    PersistedState, SavedAccountEntry, UserProfile,
};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::{HashMap, VecDeque};
#[cfg(target_os = "macos")]
use std::ffi::{CStr, CString};
#[cfg(target_os = "macos")]
use std::os::raw::c_char;
#[cfg(target_os = "macos")]
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex as StdMutex, OnceLock};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tauri::{AppHandle, Emitter, Manager};
#[cfg(target_os = "macos")]
use tokio::sync::oneshot;
use tokio::sync::{Mutex, Notify, RwLock, Semaphore};

const CF_CHALLENGE_PREFIX: &str = "<!--lindo-cf-challenge-->";
const CF_COLD_SEED_MAX_AGE_MS: u64 = 12 * 3600 * 1000;
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

/// Authoritative state machine for `cf_clearance` (aligned with FluxDO `CfClearanceAuthority` insights):
/// - Protects a healthy unchallenged `incumbent` against transient WebKit empty cookie reads or stale host-only variant overwrites.
/// - Immediately marks a clearance ticket dead (`challenged`) upon `cf-mitigated: challenge`, opening clean rotation.
/// - Only cold-seeds `cf_clearance` into WebKit/WebView2 when the browser store has none and the persisted ticket is unchallenged & fresh (< 12h).
#[derive(Debug, Clone, Default)]
pub struct CfClearanceAuthority {
    pub incumbent: Option<String>,
    pub challenged: Option<String>,
    pub updated_at_ms: u64,
}

impl CfClearanceAuthority {
    pub fn from_persisted(raw_cookies: &str, saved_updated_at_ms: u64, now_ms: u64) -> Self {
        let mut auth = Self::default();
        if let Some(cf) = BridgeEngine::parse_cookie_map(raw_cookies).remove("cf_clearance") {
            if !cf.is_empty() && saved_updated_at_ms > 0 && now_ms.saturating_sub(saved_updated_at_ms) <= CF_COLD_SEED_MAX_AGE_MS {
                auth.incumbent = Some(cf);
                auth.updated_at_ms = saved_updated_at_ms;
            }
        }
        auth
    }

    pub fn confirm_healthy(&mut self, value: &str, now_ms: u64) {
        let trimmed = value.trim();
        if trimmed.is_empty() {
            return;
        }
        if self.challenged.as_deref() == Some(trimmed) {
            return;
        }
        self.incumbent = Some(trimmed.to_string());
        self.updated_at_ms = now_ms;
    }

    pub fn note_challenged(&mut self, carried_value: Option<&str>) {
        let target = carried_value
            .map(|s| s.trim())
            .filter(|s| !s.is_empty())
            .map(|s| s.to_string())
            .or_else(|| self.incumbent.clone());
        if let Some(dead) = target {
            if self.incumbent.as_deref() == Some(dead.as_str()) {
                self.incumbent = None;
            }
            self.challenged = Some(dead);
        }
    }

    /// `candidates` is `Vec<(value, host_only)>`. Canonical Cloudflare `cf_clearance` has `host_only == false` (`Domain=.linux.do`).
    pub fn select_winner(
        &mut self,
        candidates: &[(String, bool)],
        verified: bool,
        now_ms: u64,
    ) -> Option<String> {
        let valid: Vec<&(String, bool)> = candidates
            .iter()
            .filter(|(v, _)| {
                let t = v.trim();
                !t.is_empty() && self.challenged.as_deref() != Some(t)
            })
            .collect();

        if verified {
            // Right after user passes site verification / login, prefer a newly rotated `.linux.do` ticket over the old incumbent
            let rotated_domain = valid.iter().rev().find(|(v, host_only)| {
                !*host_only && self.incumbent.as_deref() != Some(v.as_str())
            });
            let any_domain = valid.iter().rev().find(|(_, host_only)| !*host_only);
            let chosen = rotated_domain
                .or(any_domain)
                .or_else(|| valid.last())
                .map(|(v, _)| v.clone());
            if let Some(ref win) = chosen {
                self.incumbent = Some(win.clone());
                self.challenged = None;
                self.updated_at_ms = now_ms;
                return chosen;
            }
        }

        // Sticky incumbent rule: if incumbent is healthy and still present in browser cookies, keep it regardless of enumeration order
        if let Some(ref inc) = self.incumbent {
            if self.challenged.as_deref() != Some(inc.as_str()) {
                if valid.iter().any(|(v, _)| v == inc) {
                    if self.updated_at_ms == 0 {
                        self.updated_at_ms = now_ms;
                    }
                    return Some(inc.clone());
                }
            }
        }

        // Natural Cloudflare background rotation: adopt canonical `.linux.do` (`host_only == false`) candidate first
        let natural = valid
            .iter()
            .rev()
            .find(|(_, host_only)| !*host_only)
            .or_else(|| valid.last())
            .map(|(v, _)| v.clone());
        if let Some(ref win) = natural {
            self.incumbent = Some(win.clone());
            if self.updated_at_ms == 0 || !verified {
                self.updated_at_ms = now_ms;
            }
            return natural;
        }

        // Transient empty read protection: if browser returned 0 valid cf_clearance cookies transiently while incumbent is fresh & unchallenged
        if candidates.is_empty() {
            if let Some(ref inc) = self.incumbent {
                if self.challenged.as_deref() != Some(inc.as_str())
                    && self.updated_at_ms > 0
                    && now_ms.saturating_sub(self.updated_at_ms) <= CF_COLD_SEED_MAX_AGE_MS
                {
                    return Some(inc.clone());
                }
            }
        }

        None
    }

    pub fn should_seed_cold_start(
        &self,
        saved_cf: &str,
        saved_updated_at_ms: u64,
        browser_has_cf: bool,
        now_ms: u64,
    ) -> bool {
        if browser_has_cf {
            return false;
        }
        let clean = saved_cf.trim();
        if clean.is_empty() {
            return false;
        }
        if self.challenged.as_deref() == Some(clean) {
            return false;
        }
        saved_updated_at_ms > 0 && now_ms.saturating_sub(saved_updated_at_ms) <= CF_COLD_SEED_MAX_AGE_MS
    }
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
                                "已在系统浏览器打开授权页（支持通行密钥 Passkey），点击「授权」后自动返回",
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
pub struct SiteHttpResponse {
    pub status: u16,
    pub body: String,
    pub is_challenge: bool,
    pub is_unauthenticated: bool,
    pub rate_limited_ms: Option<u64>,
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
    fg_sem: Semaphore,
    bg_sem: Semaphore,
    media_sem: Semaphore,
    inner: StdMutex<BridgeRuntime>,
    cf_authority: StdMutex<CfClearanceAuthority>,
    media_cache: StdMutex<MediaCacheState>,
    media_inflight: StdMutex<HashMap<String, Arc<Notify>>>,
}

struct BridgeRuntime {
    initialized: bool,
    origin_ready: bool,
    bootstrap_challenged: bool,
    last_seeded_token: String,
    last_challenge_url: Option<String>,
    last_cookie_sync: Option<Instant>,
    last_login_at: Option<Instant>,
    rate_limit_until: Option<Instant>,
    fg_challenge_until: Option<Instant>,
    bg_challenge_until: Option<Instant>,
    verifying: bool,
    pending_rsa: Option<PendingRsaGrant>,
    consumed_nonces: Vec<String>,
}

impl BridgeEngine {
    pub fn new(initial: PersistedState) -> Arc<Self> {
        let initial_cf = CfClearanceAuthority::from_persisted(
            &initial.raw_cookies,
            initial.cf_clearance_updated_at_ms,
            now_epoch_ms(),
        );
        Arc::new(Self {
            state: StdMutex::new(initial),
            bootstrap_lock: Mutex::new(()),
            verify_lock: Mutex::new(()),
            account_lock: RwLock::new(()),
            fg_sem: Semaphore::new(6),
            bg_sem: Semaphore::new(2),
            media_sem: Semaphore::new(6),
            inner: StdMutex::new(BridgeRuntime {
                initialized: false,
                origin_ready: false,
                bootstrap_challenged: false,
                last_seeded_token: "__unseeded__".to_string(),
                last_challenge_url: None,
                last_cookie_sync: None,
                last_login_at: None,
                rate_limit_until: None,
                fg_challenge_until: None,
                bg_challenge_until: None,
                verifying: false,
                pending_rsa: None,
                consumed_nonces: Vec::new(),
            }),
            cf_authority: StdMutex::new(initial_cf),
            media_cache: StdMutex::new(MediaCacheState::default()),
            media_inflight: StdMutex::new(HashMap::new()),
        })
    }

    pub fn spawn_warmup(self: &Arc<Self>) {
        let eng = Arc::clone(self);
        tauri::async_runtime::spawn(async move {
            let _ = eng.ensure_ready(false).await;
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

        let status_terminated = native_call("status", json!({}), 5)
            .await
            .ok()
            .and_then(|s| s.get("processTerminated")?.as_bool())
            .unwrap_or(false);

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
            while Instant::now() < deadline {
                if self.inner.lock().unwrap().verifying {
                    break;
                }
                let st_obj = native_call("status", json!({}), 5).await.ok();
                let is_loading = st_obj
                    .as_ref()
                    .and_then(|s| s.get("loading")?.as_bool())
                    .unwrap_or(false);

                let boot_state = native_call(
                    "evaluate",
                    json!({
                        "isolated": true,
                        "script": r#"(() => {
                            if (location.origin !== 'https://linux.do') return { state: 'waiting' };
                            const explicitChallenge = !!document.querySelector('#challenge-running, #challenge-stage, #challenge-form, .cf-turnstile, #turnstile-wrapper, iframe[src*="challenges.cloudflare.com"], script[src*="challenge-platform"]')
                                || /^(Just a moment|请稍候|Attention Required|Security Check)/i.test(document.title || '');
                            if (explicitChallenge) return { state: 'challenge' };
                            const tok = document.querySelector('meta[name="csrf-token"]')?.content || '';
                            const hasPreloaded = !!document.getElementById('data-preloaded');
                            if (tok || hasPreloaded) {
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
                            if let Some(tok) = obj.get("csrf").and_then(|v| v.as_str()).filter(|s| !s.is_empty()) {
                                let csrf_js = format!("window.__lindoCsrf = {};", json!(tok));
                                let _ = native_call("evaluate", json!({ "script": csrf_js }), 4).await;
                            }
                            let mut rt = self.inner.lock().unwrap();
                            rt.origin_ready = true;
                            rt.bootstrap_challenged = false;
                            break;
                        }
                        Some("challenge") if !is_loading => {
                            let mut rt = self.inner.lock().unwrap();
                            rt.origin_ready = false;
                            rt.bootstrap_challenged = true;
                            break;
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
        let saved_raw = { self.state.lock().unwrap().raw_cookies.clone() };
        let saved_map = Self::parse_cookie_map(&saved_raw);
        let app_token = saved_map
            .get("_t")
            .cloned()
            .filter(|v| !v.is_empty() && v != "demo_session_token");

        {
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

        let (t_count, has_dot_domain_t) = cookies_arr
            .map(|arr| {
                let mut cnt = 0usize;
                let mut dot = false;
                for item in arr {
                    if item.get("name").and_then(|n| n.as_str()) == Some("_t")
                        && !item.get("value").and_then(|v| v.as_str()).unwrap_or("").is_empty()
                    {
                        cnt += 1;
                        if item.get("hostOnly").and_then(|h| h.as_bool()) == Some(false) {
                            dot = true;
                        }
                    }
                }
                (cnt, dot)
            })
            .unwrap_or((0, false));

        if t_count > 1 || has_dot_domain_t {
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

        // Never overwrite WebKit/WebView2's live rotated `_t` or synthesize `cf_clearance` on startup.
        if let Some(bt) = browser_token {
            self.inner.lock().unwrap().last_seeded_token = bt.clone();
            if app_token.as_deref() != Some(bt.as_str()) || browser_has_cf {
                let _ = self.sync_cookies_from_webkit(false).await;
            }
            return Ok(());
        }

        let mut to_set = Vec::new();
        for name in ["_t", "_forum_session"] {
            if let Some(val) = saved_map.get(name) {
                if !val.is_empty() && val != "demo_session_token" {
                    to_set.push(json!({ "name": name, "value": val }));
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
        let _acct = self.account_lock.read().await;
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
        let method_upper = method.to_uppercase();
        let is_logged_in = { self.state.lock().unwrap().current_user.is_some() };
        let mut safe_headers = HashMap::new();
        safe_headers.insert(
            "Accept".to_string(),
            "application/json, text/javascript, */*; q=0.01".to_string(),
        );
        let mut is_background = false;
        let mut expects_html = false;
        if let Some(h) = headers {
            for (k, v) in h {
                let lower = k.to_lowercase();
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

        {
            let rt = self.inner.lock().unwrap();
            if !in_verification {
                if rt.verifying {
                    return Ok(SiteHttpResponse {
                        status: 403,
                        body: format!("{CF_CHALLENGE_PREFIX}verifying"),
                        is_challenge: true,
                        is_unauthenticated: false,
                        rate_limited_ms: None,
                    });
                }
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
                        });
                    }
                }
                if let Some(until) = rt.rate_limit_until {
                    if Instant::now() < until {
                        let remain = until.saturating_duration_since(Instant::now()).as_millis() as u64;
                        return Ok(SiteHttpResponse {
                            status: 429,
                            body: r#"{"errors":["请求较频繁，请稍后再试"]}"#.to_string(),
                            is_challenge: false,
                            is_unauthenticated: false,
                            rate_limited_ms: Some(remain),
                        });
                    }
                }
            }
        }

        let _permit = if in_verification {
            None
        } else if is_background {
            self.bg_sem.acquire().await.ok()
        } else {
            self.fg_sem.acquire().await.ok()
        };

        if !in_verification {
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
                return Ok(SiteHttpResponse {
                    status: 403,
                    body: format!("{CF_CHALLENGE_PREFIX}verifying"),
                    is_challenge: true,
                    is_unauthenticated: false,
                    rate_limited_ms: None,
                });
            }
        }
        if !expects_html {
            safe_headers
                .entry("X-Requested-With".to_string())
                .or_insert_with(|| "XMLHttpRequest".to_string());
            if !is_background && !url.contains("/topics/timings") {
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
            "form": form
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
                    async function fetchFreshCsrf() {{
                        if (window.__lindoCsrfPromise) {{
                            return await window.__lindoCsrfPromise;
                        }}
                        window.__lindoCsrfPromise = (async () => {{
                            try {{
                                const cr = await fetch('/session/csrf.json', {{
                                    credentials: 'include',
                                    cache: 'no-store',
                                    referrer: 'https://linux.do/',
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
                                            challenge: cr.headers.get('cf-mitigated')
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
                        referrer: 'https://linux.do/',
                        referrerPolicy: 'strict-origin-when-cross-origin',
                        cache: noStore ? 'no-store' : 'default',
                        body: c.form ? new URLSearchParams(c.form) : undefined,
                        signal: AbortSignal.timeout(reqTimeoutMs)
                    }});
                    let bodyText = await r.text();
                    let cfHeader = r.headers.get('cf-mitigated');
                    if (!write && !cfHeader && (r.status === 403 || r.status === 401) && c.headers['Discourse-Logged-In'] && !c.url.includes('/session/current.json') && /not_logged_in|need to be logged in/i.test(bodyText)) {{
                        const guestHdrs = Object.assign({{}}, c.headers);
                        delete guestHdrs['Discourse-Logged-In'];
                        const gr = await fetch(c.url, {{
                            method: c.method,
                            headers: guestHdrs,
                            credentials: 'include',
                            referrer: 'https://linux.do/',
                            referrerPolicy: 'strict-origin-when-cross-origin',
                            cache: 'no-store',
                            signal: AbortSignal.timeout(15000)
                        }});
                        if (gr.ok) {{
                            return {{
                                status: gr.status,
                                body: await gr.text(),
                                retryAfter: gr.headers.get('Retry-After'),
                                challenge: gr.headers.get('cf-mitigated'),
                                hadUnauthLoggedHeader: true
                            }};
                        }}
                    }}
                    if (write && !isOtp && !cfHeader && (r.status === 403 || r.status === 422) && /csrf|authenticity token/i.test(bodyText)) {{
                        window.__lindoCsrf = null;
                        window.__lindoIgnoreDomCsrf = true;
                        const fc2 = await fetchFreshCsrf();
                        if (fc2.token) {{
                            c.headers['X-CSRF-Token'] = fc2.token;
                            r = await fetch(c.url, {{
                                method: c.method,
                                headers: c.headers,
                                credentials: 'include',
                                referrer: 'https://linux.do/',
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
                        challenge: cfHeader
                    }};
                }} catch (err) {{
                    return {{
                        netErr: String((err && err.message) || err || 'Network error'),
                        originLost: location.origin !== 'https://linux.do'
                    }};
                }}
            }})()"#
        );

        let idempotent = !in_verification
            && !self.inner.lock().unwrap().verifying
            && (method_upper == "GET" || method_upper == "HEAD");

        let mut eval_res = native_call("evaluate", json!({ "script": script }), 16).await;
        let needs_retry = match &eval_res {
            Ok(v) if v.is_object() => {
                v.get("originLost").and_then(|b| b.as_bool()) == Some(true)
                    || v.get("netErr").is_some()
            }
            _ => true,
        };
        if needs_retry && idempotent && !self.inner.lock().unwrap().verifying {
            let force_reinit = eval_res
                .as_ref()
                .ok()
                .and_then(|v| v.get("originLost"))
                .and_then(|b| b.as_bool())
                .unwrap_or(false)
                || eval_res.is_err();
            if force_reinit {
                self.inner.lock().unwrap().origin_ready = false;
                self.ensure_ready(true).await?;
                if self.inner.lock().unwrap().bootstrap_challenged {
                    if !is_background && method_upper == "GET" && !url.contains("/session/") && !url.contains("/user-api-key/") {
                        self.inner.lock().unwrap().last_challenge_url = Some(url.to_string());
                    }
                    return Ok(SiteHttpResponse {
                        status: 403,
                        body: format!("{CF_CHALLENGE_PREFIX}bootstrap"),
                        is_challenge: true,
                        is_unauthenticated: false,
                        rate_limited_ms: None,
                    });
                }
            } else {
                tokio::time::sleep(Duration::from_millis(180)).await;
            }
            eval_res = native_call("evaluate", json!({ "script": script }), 16).await;
        }
        let eval_res = eval_res?;
        if eval_res.get("originLost").and_then(|v| v.as_bool()) == Some(true) {
            return Err("内置网页上下文正在唤醒，请重试".to_string());
        }
        if let Some(net_err) = eval_res.get("netErr").and_then(|v| v.as_str()) {
            return Err(format!("网络请求超时或中断: {net_err}"));
        }

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
        let body = if cf_header == "challenge" {
            format!("{CF_CHALLENGE_PREFIX}{raw_body}")
        } else {
            raw_body
        };

        let is_challenge = Self::looks_like_challenge(&body);
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
            // Background polls back off politely for 30s; foreground requests only dedupe simultaneous bursts (1.5s)
            // and never touch `cf_clearance` in WebKit/WebView2!
            let now = Instant::now();
            let mut rt = self.inner.lock().unwrap();
            rt.bg_challenge_until = Some(now + Duration::from_secs(30));
            if !is_background {
                rt.fg_challenge_until = Some(now + Duration::from_millis(1500));
            }
        } else if (200..300).contains(&status) && !is_background {
            {
                let mut rt = self.inner.lock().unwrap();
                rt.fg_challenge_until = None;
                rt.bg_challenge_until = None;
                rt.bootstrap_challenged = false;
            }
            let live_cf = {
                let raw = self.state.lock().unwrap().raw_cookies.clone();
                Self::parse_cookie_map(&raw).remove("cf_clearance")
            };
            if let Some(cf_val) = live_cf {
                let ts = now_epoch_ms();
                self.cf_authority.lock().unwrap().confirm_healthy(&cf_val, ts);
            }
        }
        if status == 429 && !is_challenge {
            let secs = eval_res
                .get("retryAfter")
                .and_then(|v| v.as_str())
                .and_then(|s| s.trim().parse::<u64>().ok())
                .unwrap_or(8)
                .clamp(3, 120);
            let dur = Duration::from_secs(secs);
            self.inner.lock().unwrap().rate_limit_until = Some(Instant::now() + dur);
            rate_limited_ms = Some(dur.as_millis() as u64);
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

        // Passive session expiry check if authorized request received explicit not_logged_in
        if !in_verification && (is_unauth || had_unauth_logged_hdr) && !url.contains("/session/current.json") {
            let had_user = { self.state.lock().unwrap().current_user.is_some() };
            if had_user {
                self.confirm_passive_expiry(app).await;
            }
        }

        Ok(SiteHttpResponse {
            status,
            body,
            is_challenge,
            is_unauthenticated: is_unauth,
            rate_limited_ms,
        })
    }

    async fn confirm_passive_expiry(&self, app: &AppHandle) {
        // 新登录完成后的 20s 保护期内忽略飞行中旧请求返回的未登录信号，防止刚登录即误判过期
        if self
            .inner
            .lock()
            .unwrap()
            .last_login_at
            .map(|t| t.elapsed() < Duration::from_secs(20))
            .unwrap_or(false)
        {
            return;
        }

        // 先对 WebKit 执行 _t 去重（剔除历史遗留的 .linux.do 死 Cookie 污染）并确保磁盘中的 _t 已同步回注 WebKit
        self.sweep_auth_cookies(false, None).await;
        {
            self.inner.lock().unwrap().last_seeded_token = "__recheck__".to_string();
        }
        let _ = self.seed_session().await;

        // 1. 复核 /session/current.json（遇到挑战/429/5xx 绝不踢出登录）
        let Ok(probe) = Box::pin(self.request_unlocked(
            app,
            "https://linux.do/session/current.json",
            "GET",
            None,
            None,
            true,
        ))
        .await
        else {
            return;
        };
        if probe.is_challenge || probe.status >= 500 || probe.status == 429 {
            return;
        }
        let still_logged_in = (200..300).contains(&probe.status)
            && serde_json::from_str::<Value>(&probe.body)
                .ok()
                .and_then(|v| v.get("current_user")?.get("username")?.as_str().map(|s| !s.is_empty()))
                .unwrap_or(false);
        if still_logged_in {
            return;
        }

        // 2. 独立主站 HTML 双重确认：仅在真实主站页面明确渲染为游客 class="... anon" 且无 currentUser 时才判定服务端会话真正失效
        let mut html_hdrs = HashMap::new();
        html_hdrs.insert("Accept".to_string(), "text/html".to_string());
        html_hdrs.insert("Cache-Control".to_string(), "no-cache".to_string());
        let Ok(home_page) = Box::pin(self.request_unlocked(
            app,
            "https://linux.do/",
            "GET",
            None,
            Some(html_hdrs),
            true,
        ))
        .await
        else {
            return;
        };
        if home_page.is_challenge || !(200..300).contains(&home_page.status) {
            return;
        }
        let lower_html = home_page.body.to_lowercase();
        let confirmed_anon = lower_html.contains("data-preloaded")
            && (lower_html.contains(" anon\"") || lower_html.contains(" anon ") || !home_page.body.contains("currentUser"));
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
                json!({ "script": "window.__lindoCsrf = null; window.__lindoIgnoreDomCsrf = true;" }),
                4,
            )
            .await;
            self.set_dock_badge(0).await;
            let _ = app.emit("lindo://state-updated", ());
        }
    }

    pub async fn upload(
        &self,
        filename: &str,
        mime: &str,
        data_base64: &str,
    ) -> Result<SiteHttpResponse, String> {
        let _acct = self.account_lock.read().await;
        self.ensure_ready(false).await?;
        let cfg = json!({
            "name": filename,
            "mime": mime,
            "data": data_base64
        });
        let script = format!(
            r#"(async () => {{
                if (location.origin !== 'https://linux.do') return null;
                const c = {cfg};
                const csrfRes = await fetch('/session/csrf.json', {{ credentials: 'include', signal: AbortSignal.timeout(12000) }});
                if (!csrfRes.ok) return {{ status: csrfRes.status, body: await csrfRes.text(), challenge: csrfRes.headers.get('cf-mitigated') }};
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
                    headers: {{ 'X-CSRF-Token': token, 'X-Requested-With': 'XMLHttpRequest' }},
                    body: form,
                    signal: AbortSignal.timeout(90000)
                }});
                return {{ status: r.status, body: await r.text(), challenge: r.headers.get('cf-mitigated') }};
            }})()"#
        );
        let res = native_call("evaluate", json!({ "script": script }), 105).await?;
        let status = res.get("status").and_then(|v| v.as_u64()).unwrap_or(500) as u16;
        let body = res.get("body").and_then(|v| v.as_str()).unwrap_or("").to_string();
        let is_challenge = res.get("challenge").and_then(|v| v.as_str()) == Some("challenge")
            || Self::looks_like_challenge(&body);
        let is_unauthenticated = Self::is_unauthenticated_response(status, &body);
        if is_challenge {
            let carried_cf = {
                let raw = self.state.lock().unwrap().raw_cookies.clone();
                Self::parse_cookie_map(&raw).remove("cf_clearance")
            };
            self.cf_authority
                .lock()
                .unwrap()
                .note_challenged(carried_cf.as_deref());
        }
        Ok(SiteHttpResponse {
            status,
            body,
            is_challenge,
            is_unauthenticated,
            rate_limited_ms: None,
        })
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
            if let Some(until) = rt.bg_challenge_until {
                if Instant::now() < until {
                    return Err("Challenge cooldown active".to_string());
                }
            }
        }
        let _permit = self.media_sem.acquire().await.ok();
        self.ensure_ready(false).await?;
        if self.inner.lock().unwrap().bootstrap_challenged {
            return Err("Site verification required".to_string());
        }
        let cfg = json!({ "url": clean_url });
        let script = format!(
            r#"(async () => {{
                const c = {cfg};
                if (location.origin !== 'https://linux.do') return null;
                const r = await fetch(c.url, {{
                    method: 'GET',
                    credentials: 'include',
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

        let res = native_call("evaluate", json!({ "script": script }), 25).await?;
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
        if cf_hdr == "challenge" {
            // Subresource image fetch must never lock out foreground user navigation!
            self.inner.lock().unwrap().bg_challenge_until =
                Some(Instant::now() + Duration::from_secs(20));
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
    ) -> Result<bool, String> {
        self.verify_internal(app, login, is_dark, None).await
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

        {
            let mut rt = self.inner.lock().unwrap();
            rt.verifying = false;
            if matches!(result, Ok(true)) {
                rt.origin_ready = true;
                rt.bootstrap_challenged = false;
            }
        }
        let _ = native_call("hide", json!({}), 5).await;
        result
    }

    async fn run_verify_loop(
        &self,
        app: &AppHandle,
        login: bool,
        is_dark: bool,
        otp_token: Option<&str>,
    ) -> Result<bool, String> {
        native_call("init", json!({}), 10).await?;
        self.inner.lock().unwrap().initialized = true;

        let is_otp_flow = otp_token.is_some();
        if login || is_otp_flow {
            // Clear stale Discourse `_t` / `_forum_session` before fresh login/OTP while keeping Cloudflare cookies untouched.
            self.sweep_auth_cookies(true, None).await;
            self.inner.lock().unwrap().last_seeded_token = String::new();
        } else {
            self.seed_session().await?;
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
            "https://linux.do/".to_string()
        };

        native_call("navigate", json!({ "url": initial_url }), 10).await?;
        let mut panel_shown = false;
        if !is_otp_flow {
            panel_shown = true;
            native_call("show", json!({ "dark": is_dark, "login": login }), 8).await?;
        }

        let mut requested_login = login || is_otp_flow;
        let mut otp_submitted = false;
        let mut challenge_ticks = 0u32;
        let mut last_current_session_poll: Option<Instant> = None;
        let deadline = Instant::now() + Duration::from_secs(180);

        while Instant::now() < deadline {
            tokio::time::sleep(Duration::from_millis(750)).await;

            let status_obj = native_call("status", json!({}), 6).await?;
            if status_obj.get("cancelled").and_then(|v| v.as_bool()) == Some(true) {
                return Ok(false);
            }

            // Passive read-only DOM observation in isolated `defaultClientWorld` (macOS) / zero-global sync eval (Windows):
            // never touches `pageWorld` or mutates `window` while site verification is on screen!
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
                        const isOtpPage = location.pathname.indexOf('/session/otp/') === 0;
                        const isLoginPage = location.pathname === '/login'
                            || location.pathname.indexOf('/login/') === 0
                            || location.pathname.indexOf('/signup') === 0
                            || location.pathname.indexOf('/u/email-login') === 0
                            || location.pathname.indexOf('/session/email-login') === 0
                            || location.pathname.indexOf('/auth/') === 0;
                        const explicitChallenge = !!document.querySelector('#challenge-running, #challenge-stage, #challenge-form, .cf-turnstile, #turnstile-wrapper, iframe[src*="challenges.cloudflare.com"], script[src*="challenge-platform"]')
                            || /^(Just a moment|请稍候|Attention Required|Security Check)/i.test(document.title || '');
                        const missingDiscourseBoot = site && complete && !hasCsrf && !(document.body?.innerText || '').trim().startsWith('{');
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
                            isOtpPage,
                            isLoginPage,
                            preloadedUserJson,
                            challenge: explicitChallenge || missingDiscourseBoot
                        };
                    })()"#
                }),
                8,
            )
            .await
            .ok();

            let in_challenge = probe
                .as_ref()
                .and_then(|p| p.get("challenge")?.as_bool())
                .unwrap_or(false);
            if in_challenge {
                challenge_ticks += 1;
                if challenge_ticks >= 1 && !panel_shown {
                    panel_shown = true;
                    let _ = native_call("show", json!({ "dark": is_dark }), 8).await;
                }
                continue;
            }
            challenge_ticks = 0;

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

            let ready = probe
                .as_ref()
                .map(|p| {
                    p.get("site").and_then(|v| v.as_bool()) == Some(true)
                        && p.get("domReady").and_then(|v| v.as_bool()) == Some(true)
                        && p.get("hasCsrf").and_then(|v| v.as_bool()) == Some(true)
                        && p.get("hasPreloaded").and_then(|v| v.as_bool()) == Some(true)
                        && p.get("isOtpPage").and_then(|v| v.as_bool()) != Some(true)
                })
                .unwrap_or(false);
            if !ready {
                continue;
            }

            // 1. 纯站点验证流：一旦真实 Discourse 页面（含 meta csrf-token + data-preloaded 且无挑战节点）加载完毕，同步 CSRF 并直接放行
            let has_preloaded = probe
                .as_ref()
                .and_then(|p| p.get("hasPreloaded")?.as_bool())
                .unwrap_or(false);
            if !login && !requested_login && has_preloaded {
                let _ = self.sync_cookies_from_webkit_verified(false, true).await;
                let _ = native_call(
                    "evaluate",
                    json!({ "script": "window.__lindoCsrf = document.querySelector('meta[name=\"csrf-token\"]')?.content || null; window.__lindoIgnoreDomCsrf = false;" }),
                    4,
                )
                .await;
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
                            json!({ "script": "window.__lindoCsrf = document.querySelector('meta[name=\"csrf-token\"]')?.content || null; window.__lindoIgnoreDomCsrf = false;" }),
                            4,
                        )
                        .await;
                        self.commit_authenticated_user(app, profile).await;
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
                recovery_url.as_str()
            };
            let Ok(resp) = self
                .request_unlocked(app, target, "GET", None, None, true)
                .await
            else {
                continue;
            };

            if resp.is_challenge {
                if !panel_shown {
                    panel_shown = true;
                    let _ = native_call("show", json!({ "dark": is_dark }), 8).await;
                }
                tokio::time::sleep(Duration::from_millis(1800)).await;
                continue;
            }

            if !login && !requested_login && (200..300).contains(&resp.status) && !resp.body.trim().starts_with('<') {
                let _ = self.sync_cookies_from_webkit_verified(false, true).await;
                self.inner.lock().unwrap().last_challenge_url = None;
                let _ = native_call("hide", json!({}), 5).await;
                let _ = app.emit("lindo://verified", ());
                return Ok(true);
            }

            if requested_login && (200..300).contains(&resp.status) {
                if let Some(profile) = Self::extract_current_user(&resp.body) {
                    let _ = self.sync_cookies_from_webkit_verified(true, true).await;
                    let _ = native_call(
                        "evaluate",
                        json!({ "script": "window.__lindoCsrf = null; window.__lindoIgnoreDomCsrf = true;" }),
                        4,
                    )
                    .await;
                    self.commit_authenticated_user(app, profile).await;
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

    async fn commit_authenticated_user(&self, app: &AppHandle, profile: UserProfile) {
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
        let _ = app.emit("lindo://state-updated", ());
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
        let _ = app.emit(
            "lindo://diag-event",
            json!({
                "level": "INFO",
                "category": "AUTH",
                "tag": "BrowserAuth",
                "summary": "收到系统浏览器授权回调，正在安全兑换登录会话"
            }),
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
            let _ = app.emit(
                "lindo://auth-notice",
                "未找到待确认的授权请求，请重新点击「浏览器授权」",
            );
            return Ok(false);
        };

        let now_ms = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as u64;
        if now_ms.saturating_sub(grant.created_at_ms) > 900_000 {
            clear_pending_rsa();
            let _ = app.emit(
                "lindo://auth-notice",
                "授权链接已超过 15 分钟有效期，请重新点击「浏览器授权」",
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
            let _ = app.emit("lindo://auth-notice", "授权回调缺少 payload 参数，请重试");
            return Ok(false);
        };
        let plain_payload = match crate::crypto_rsa::decrypt_rsa_pkcs1v15(
            &grant.private_base64,
            &normalize_b64(payload_cipher),
        ) {
            Ok(plain) => plain,
            Err(_) => {
                let _ = app.emit(
                    "lindo://auth-notice",
                    "收到的是旧授权页回调，请在最新打开的浏览器授权页点击「授权」",
                );
                return Ok(false);
            }
        };
        let Ok(payload_json) = serde_json::from_str::<Value>(&plain_payload) else {
            let _ = app.emit("lindo://auth-notice", "授权载荷解析失败，请重试");
            return Ok(false);
        };
        let returned_nonce = payload_json
            .get("nonce")
            .and_then(|v| v.as_str())
            .unwrap_or("");
        if returned_nonce != grant.nonce {
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

        let _ = app.emit("lindo://auth-completing", true);

        // 1. 确保同源 WebKit 就绪并清理任何历史残留的死 _t / _forum_session（100% 保留 cf_clearance）
        if let Err(e) = self.ensure_ready(false).await {
            let _ = app.emit("lindo://auth-completing", false);
            let _ = app.emit("lindo://auth-notice", format!("初始化安全会话失败：{e}"));
            return Ok(false);
        }
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

        // 2. 单次 OTP 极其珍贵：兑换前先探测 /session/csrf.json 确认 WebKit 未被 Cloudflare 拦截；
        //    若新装首次无 cf_clearance 遇到挑战，先在主站完成验证放行（绝不提前消耗 OTP！），再执行兑换。
        let is_dark = { self.state.lock().unwrap().preferences.appearance != "light" };
        let csrf_probe = self
            .request(app, "https://linux.do/session/csrf.json", "GET", None, None)
            .await;
        let pre_challenged = match &csrf_probe {
            Ok(r) => r.is_challenge || !(200..300).contains(&r.status),
            Err(_) => true,
        };
        if pre_challenged {
            let _ = app.emit("lindo://auth-completing", false);
            let verified = self.verify_or_login(app, false, is_dark).await.unwrap_or(false);
            if !verified {
                let _ = app.emit("lindo://auth-notice", "请先完成站点验证后再完成授权登录");
                return Ok(false);
            }
            let _ = app.emit("lindo://auth-completing", true);
        }

        // 3. 在同源 WebKit 内原子获取最新 CSRF 并以 redirect:'manual' 提交 POST /session/otp/{otp}（直接落盘 Set-Cookie: _t=...）
        let otp_cfg = json!({ "otp": otp_token });
        let otp_script = format!(
            r#"(async () => {{
                if (location.origin !== 'https://linux.do') return {{ status: 0 }};
                const cfg = {otp_cfg};
                const cr = await fetch('/session/csrf.json', {{
                    credentials: 'include',
                    cache: 'no-store',
                    headers: {{ 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' }},
                    signal: AbortSignal.timeout(12000)
                }});
                if (!cr.ok) {{
                    return {{ status: cr.status, challenge: cr.headers.get('cf-mitigated') || '' }};
                }}
                const csrf = (await cr.json()).csrf;
                if (!csrf) return {{ status: 422 }};
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
                    challenge: (r.headers && r.headers.get('cf-mitigated')) || ''
                }};
            }})()"#
        );

        let otp_eval = native_call("evaluate", json!({ "script": otp_script }), 22)
            .await
            .unwrap_or_else(|_| json!({ "status": 0 }));
        let otp_status = otp_eval.get("status").and_then(|v| v.as_u64()).unwrap_or(0) as u16;
        let otp_challenged = otp_eval.get("challenge").and_then(|v| v.as_str()) == Some("challenge");

        if otp_challenged {
            let _ = app.emit("lindo://auth-completing", false);
            return self.verify_with_otp(app, &otp_token, is_dark).await;
        }

        if (200..400).contains(&otp_status) || otp_status == 0 {
            // 4. 重载隐藏主站 DOM，使 meta csrf-token 与 data-preloaded 同步至新登录态
            let _ = native_call("navigate", json!({ "url": "https://linux.do/" }), 10).await;
            let nav_deadline = Instant::now() + Duration::from_secs(8);
            let mut preloaded_profile: Option<UserProfile> = None;
            while Instant::now() < nav_deadline {
                tokio::time::sleep(Duration::from_millis(200)).await;
                let dom_check = native_call(
                    "evaluate",
                    json!({
                        "script": r#"(() => {
                            const domReady = document.readyState === 'interactive' || document.readyState === 'complete';
                            if (location.origin !== 'https://linux.do' || !domReady) return null;
                            const el = document.getElementById('data-preloaded');
                            if (!el) return document.readyState === 'complete' ? { ready: true, userJson: null } : null;
                            const csrf = document.querySelector('meta[name="csrf-token"]')?.content || null;
                            if (csrf) { window.__lindoCsrf = csrf; window.__lindoIgnoreDomCsrf = false; }
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

            let _ = self.sync_cookies_from_webkit(true).await;
            if let Some(profile) = preloaded_profile {
                self.commit_authenticated_user(app, profile).await;
                let _ = app.emit("lindo://auth-completing", false);
                let _ = app.emit("lindo://auth-notice", "");
                let app2 = app.clone();
                tauri::async_runtime::spawn(async move {
                    tokio::time::sleep(Duration::from_millis(500)).await;
                    let _ = app2.emit("lindo://state-updated", ());
                });
                return Ok(true);
            }

            for attempt in 0..4 {
                if attempt > 0 {
                    tokio::time::sleep(Duration::from_millis(550)).await;
                }
                if let Ok(me) = self
                    .request(
                        app,
                        "https://linux.do/session/current.json",
                        "GET",
                        None,
                        None,
                    )
                    .await
                {
                    if let Some(profile) = Self::extract_current_user(&me.body) {
                        let _ = self.sync_cookies_from_webkit(true).await;
                        self.commit_authenticated_user(app, profile).await;
                        let _ = app.emit("lindo://auth-completing", false);
                        let _ = app.emit("lindo://auth-notice", "");
                        let app2 = app.clone();
                        tauri::async_runtime::spawn(async move {
                            tokio::time::sleep(Duration::from_millis(500)).await;
                            let _ = app2.emit("lindo://state-updated", ());
                        });
                        return Ok(true);
                    }
                }
            }
        }

        // 兜底：若 fetch POST 未能直接种下会话（且 OTP 尚未被消费），通过可视化表单确认完成兑换
        let _ = app.emit("lindo://auth-completing", false);
        let fallback_ok = self.verify_with_otp(app, &otp_token, is_dark).await.unwrap_or(false);
        if !fallback_ok {
            let _ = app.emit(
                "lindo://auth-notice",
                "浏览器授权回调未能完成会话兑换，请再试一次或使用内置登录",
            );
        }
        Ok(fallback_ok)
    }

    pub async fn replace_account_cookies(&self, raw_cookies: &str) -> Result<(), String> {
        let _acct = self.account_lock.write().await;
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
            json!({ "script": "window.__lindoCsrf = null; window.__lindoIgnoreDomCsrf = true;" }),
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
        let ok = self.verify_or_login(app, true, is_dark).await?;
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
            if self.verify_or_login(app, false, is_dark).await.unwrap_or(false) {
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
            self.commit_authenticated_user(app, profile).await;
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
        let _ = self.seed_session().await;
        native_call("openBrowser", json!({ "url": url, "dark": is_dark }), 10).await?;
        Ok(())
    }

    pub async fn fetch_connect_html(&self) -> Result<String, String> {
        let initialized = self.inner.lock().unwrap().initialized;
        if !initialized {
            native_call("init", json!({}), 10).await?;
            self.inner.lock().unwrap().initialized = true;
        }
        let _ = self.seed_session().await;
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
    fn cf_authority_protects_healthy_incumbent_from_stale_or_transient_reads() {
        let now = 1_700_000_000_000u64;
        let mut auth = CfClearanceAuthority::default();
        auth.confirm_healthy("healthy_cf_1", now);

        // Even if a stray host-only cookie appears after `.linux.do` in enumeration, sticky incumbent wins!
        let candidates = vec![
            ("healthy_cf_1".to_string(), false),
            ("stray_host_only_old".to_string(), true),
        ];
        assert_eq!(
            auth.select_winner(&candidates, false, now + 1_000).as_deref(),
            Some("healthy_cf_1")
        );

        // Transient empty read from WKHTTPCookieStore preserves healthy fresh incumbent
        assert_eq!(
            auth.select_winner(&[], false, now + 5_000).as_deref(),
            Some("healthy_cf_1")
        );
    }

    #[test]
    fn cf_authority_evicts_challenged_ticket_and_adopts_fresh_domain_clearance() {
        let now = 1_700_000_000_000u64;
        let mut auth = CfClearanceAuthority::default();
        auth.confirm_healthy("dead_cf", now);

        // Challenge hit invalidates dead_cf immediately
        auth.note_challenged(Some("dead_cf"));
        assert_eq!(auth.incumbent, None);
        assert_eq!(auth.challenged.as_deref(), Some("dead_cf"));
        assert!(!auth.should_seed_cold_start("dead_cf", now, false, now + 1_000));

        // After Turnstile verification, even if dead_cf is still lingering alongside fresh_cf, fresh_cf wins!
        let candidates = vec![
            ("dead_cf".to_string(), true),
            ("fresh_cf".to_string(), false),
        ];
        assert_eq!(
            auth.select_winner(&candidates, true, now + 2_000).as_deref(),
            Some("fresh_cf")
        );
        assert_eq!(auth.challenged, None);
        assert_eq!(auth.incumbent.as_deref(), Some("fresh_cf"));
    }

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
}
