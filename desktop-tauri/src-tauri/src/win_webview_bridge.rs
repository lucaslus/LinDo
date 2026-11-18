#![cfg(not(target_os = "macos"))]

use cookie::time::Duration as CookieDuration;
use cookie::Cookie;
use serde_json::{json, Value};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::OnceLock;
use std::time::{Duration, Instant};
use tauri::{AppHandle, Manager, Theme, WebviewUrl, WebviewWindow, WebviewWindowBuilder, WindowEvent};
use tokio::sync::oneshot;
use url::Url;

const BRIDGE_WINDOW_LABEL: &str = "lindo-bridge-host";
const BROWSER_WINDOW_LABEL: &str = "lindo-forum-browser";
const CONNECT_PROBE_LABEL: &str = "lindo-connect-probe";

static VERIFY_CANCELLED: AtomicBool = AtomicBool::new(false);
static EVAL_SEQ: AtomicU64 = AtomicU64::new(1);
static PROTOCOL_REGISTERED: OnceLock<()> = OnceLock::new();

fn is_forum_host(host: Option<&str>) -> bool {
    let Some(h) = host else { return false };
    let lower = h.to_ascii_lowercase();
    lower == "linux.do" || lower.ends_with(".linux.do")
}

#[cfg(target_os = "windows")]
fn register_windows_url_protocols_once() {
    PROTOCOL_REGISTERED.get_or_init(|| {
        let Ok(exe_path) = std::env::current_exe() else {
            return;
        };
        let Some(exe_str) = exe_path.to_str() else {
            return;
        };
        let cmd_val = format!("\"{}\" \"%1\"", exe_str);
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x08000000;

        for scheme in ["discourse", "lindo"] {
            let base_key = format!(r"HKCU\Software\Classes\{scheme}");
            let cmd_key = format!(r"{base_key}\shell\open\command");
            let _ = std::process::Command::new("reg")
                .args(["add", &base_key, "/ve", "/d", &format!("URL:{scheme} Protocol"), "/f"])
                .creation_flags(CREATE_NO_WINDOW)
                .status();
            let _ = std::process::Command::new("reg")
                .args(["add", &base_key, "/v", "URL Protocol", "/d", "", "/f"])
                .creation_flags(CREATE_NO_WINDOW)
                .status();
            let _ = std::process::Command::new("reg")
                .args(["add", &cmd_key, "/ve", "/d", &cmd_val, "/f"])
                .creation_flags(CREATE_NO_WINDOW)
                .status();
        }
    });
}

#[cfg(not(target_os = "windows"))]
fn register_windows_url_protocols_once() {
    let _ = PROTOCOL_REGISTERED.set(());
}

fn ensure_bridge_window(app: &AppHandle) -> Result<WebviewWindow, String> {
    if let Some(existing) = app.get_webview_window(BRIDGE_WINDOW_LABEL) {
        return Ok(existing);
    }
    let init_url: Url = "https://linux.do/"
        .parse()
        .map_err(|e| format!("Invalid forum URL: {e}"))?;

    // Preserve genuine Edge WebView2 User-Agent and untouched document DOM (zero initialization scripts).
    let wv = WebviewWindowBuilder::new(app, BRIDGE_WINDOW_LABEL, WebviewUrl::External(init_url))
        .title("linux.do · 安全验证与登录")
        .inner_size(680.0, 560.0)
        .min_inner_size(380.0, 400.0)
        .resizable(true)
        .visible(false)
        .center()
        .build()
        .map_err(|e| format!("无法初始化 WebView2 桥接实例: {e}"))?;

    wv.on_window_event({
        let wv_clone = wv.clone();
        move |event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                VERIFY_CANCELLED.store(true, Ordering::SeqCst);
                let _ = wv_clone.hide();
            }
        }
    });

    Ok(wv)
}

fn open_or_reuse_browser_window(app: &AppHandle, target_url: Url, dark: bool) -> Result<(), String> {
    let theme = if dark { Theme::Dark } else { Theme::Light };
    if let Some(existing) = app.get_webview_window(BROWSER_WINDOW_LABEL) {
        let _ = existing.set_theme(Some(theme));
        let _ = existing.navigate(target_url);
        let _ = existing.unminimize();
        let _ = existing.show();
        let _ = existing.set_focus();
        return Ok(());
    }

    let browser = WebviewWindowBuilder::new(
        app,
        BROWSER_WINDOW_LABEL,
        WebviewUrl::External(target_url),
    )
    .title("linux.do")
    .inner_size(1120.0, 780.0)
    .min_inner_size(720.0, 520.0)
    .resizable(true)
    .theme(Some(theme))
    .center()
    .visible(true)
    .focused(true)
    .build()
    .map_err(|e| format!("无法打开内置浏览器窗口: {e}"))?;

    browser.on_window_event({
        let b_clone = browser.clone();
        move |event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                let _ = b_clone.hide();
            }
        }
    });

    Ok(())
}

async fn eval_single_sync(wv: &WebviewWindow, js: String) -> Result<String, String> {
    let (tx, rx) = oneshot::channel::<String>();
    let cell = std::sync::Mutex::new(Some(tx));
    wv.eval_with_callback(js, move |raw_json| {
        if let Ok(mut guard) = cell.lock() {
            if let Some(sender) = guard.take() {
                let _ = sender.send(raw_json);
            }
        }
    })
    .map_err(|e| format!("WebView2 脚本调用失败: {e}"))?;

    tokio::time::timeout(Duration::from_secs(6), rx)
        .await
        .map_err(|_| "WebView2 同步脚本读取超时".to_string())?
        .map_err(|_| "WebView2 回调通道已关闭".to_string())
}

async fn eval_async_promise(
    wv: &WebviewWindow,
    script: &str,
    timeout_secs: u64,
) -> Result<Value, String> {
    let slot = format!("__lindo_r_{}", EVAL_SEQ.fetch_add(1, Ordering::Relaxed));
    let launch_js = format!(
        r#"(() => {{
  window.__lindoAsyncStore = window.__lindoAsyncStore || Object.create(null);
  (async () => {{
    try {{
      const val = await ({script});
      window.__lindoAsyncStore["{slot}"] = JSON.stringify({{ done: true, ok: true, value: val === undefined ? null : val }});
    }} catch (err) {{
      window.__lindoAsyncStore["{slot}"] = JSON.stringify({{ done: true, ok: false, error: String((err && err.message) || err || "Eval error") }});
    }}
  }})();
  return true;
}})()"#
    );

    wv.eval(launch_js)
        .map_err(|e| format!("WebView2 启动异步脚本失败: {e}"))?;

    let poll_js = format!(
        r#"(() => {{
  const st = window.__lindoAsyncStore;
  if (!st) return "";
  const hit = st["{slot}"];
  if (hit !== undefined) {{
    delete st["{slot}"];
    return hit;
  }}
  return "";
}})()"#
    );

    let deadline = Instant::now() + Duration::from_secs(timeout_secs.max(2));
    let mut delay_ms = 10u64;

    while Instant::now() < deadline {
        tokio::time::sleep(Duration::from_millis(delay_ms)).await;
        delay_ms = (delay_ms + 12).min(55);

        let raw = eval_single_sync(wv, poll_js.clone()).await?;
        let trimmed = raw.trim();
        if trimmed.is_empty() || trimmed == "null" || trimmed == "\"\"" {
            continue;
        }
        // `eval_with_callback` returns a JSON-encoded string of our `JSON.stringify(...)` payload
        let inner_json_str: String = match serde_json::from_str::<String>(trimmed) {
            Ok(s) => s,
            Err(_) => trimmed.to_string(),
        };
        if inner_json_str.is_empty() {
            continue;
        }
        let envelope: Value = serde_json::from_str(&inner_json_str)
            .map_err(|e| format!("解析 WebView2 返回结果失败: {e}"))?;
        if envelope.get("ok").and_then(|b| b.as_bool()) == Some(true) {
            return Ok(envelope.get("value").cloned().unwrap_or(Value::Null));
        } else {
            let err_msg = envelope
                .get("error")
                .and_then(|e| e.as_str())
                .unwrap_or("WebView2 script execution failed");
            return Err(err_msg.to_string());
        }
    }

    let cleanup_js = format!(
        r#"if (window.__lindoAsyncStore) {{ delete window.__lindoAsyncStore["{slot}"]; }}"#
    );
    let _ = wv.eval(cleanup_js);
    Err("WebView2 异步操作超时".to_string())
}

fn purge_storage_auth_js(wv: &WebviewWindow) {
    let _ = wv.eval(
        r#"try {
  if (location.hostname === 'linux.do') {
    for (const k of ['authentication_data']) {
      try { localStorage.removeItem(k); } catch (_) {}
      try { sessionStorage.removeItem(k); } catch (_) {}
    }
  }
} catch (_) {}"#,
    );
}

fn is_cloudflare_domain_cookie_name(name: &str) -> bool {
    matches!(
        name,
        "cf_clearance" | "__cf_bm" | "_cfuvid" | "cf_chl_rc_i" | "cf_chl_rc_ni"
    )
}

fn build_host_only_forum_cookie(name: String, value: String, persistent: bool) -> Cookie<'static> {
    let mut builder = Cookie::build((name, value))
        .domain("linux.do")
        .path("/")
        .secure(true)
        .http_only(true);
    if persistent {
        builder = builder.max_age(CookieDuration::days(60));
    }
    let mut c = builder.build();
    // Clearing explicit domain makes cookie::Cookie host-only when associated with `https://linux.do/` where supported;
    // WebView2 requires a target domain on AddOrUpdateCookie, so we normalize and deduplicate strictly to `linux.do` (no leading dot!).
    if c.domain().is_none() {
        c.set_domain("linux.do");
    }
    c
}

pub async fn native_call(
    app_opt: Option<&AppHandle>,
    method: &str,
    params: Value,
    timeout_secs: u64,
) -> Result<Value, String> {
    let app = app_opt
        .cloned()
        .ok_or_else(|| "Tauri AppHandle 尚未就绪".to_string())?;

    match method {
        "init" => {
            VERIFY_CANCELLED.store(false, Ordering::SeqCst);
            register_windows_url_protocols_once();
            let wv = ensure_bridge_window(&app)?;
            purge_storage_auth_js(&wv);
            Ok(json!({}))
        }
        "openBrowser" => {
            let raw_url = params.get("url").and_then(|v| v.as_str()).unwrap_or("");
            let parsed: Url = raw_url
                .parse()
                .map_err(|_| "无效的社区链接".to_string())?;
            if parsed.scheme() != "https" || !is_forum_host(parsed.host_str()) {
                return Err("仅允许在内置浏览器打开 linux.do 域名".to_string());
            }
            let dark = params.get("dark").and_then(|v| v.as_bool()).unwrap_or(true);
            open_or_reuse_browser_window(&app, parsed, dark)?;
            Ok(json!({}))
        }
        "show" => {
            let wv = ensure_bridge_window(&app)?;
            VERIFY_CANCELLED.store(false, Ordering::SeqCst);
            let dark = params.get("dark").and_then(|v| v.as_bool()).unwrap_or(true);
            let _ = wv.set_theme(Some(if dark { Theme::Dark } else { Theme::Light }));
            let _ = wv.unminimize();
            let _ = wv.center();
            let _ = wv.show();
            let _ = wv.set_focus();
            Ok(json!({}))
        }
        "hide" => {
            if let Some(wv) = app.get_webview_window(BRIDGE_WINDOW_LABEL) {
                let _ = wv.hide();
            }
            Ok(json!({}))
        }
        "status" => {
            let cancelled = VERIFY_CANCELLED.load(Ordering::SeqCst);
            Ok(json!({
                "cancelled": cancelled,
                "loading": false,
                "error": ""
            }))
        }
        "setDockBadge" => {
            let label = params.get("label").and_then(|v| v.as_str()).unwrap_or("");
            if let Some(main_win) = app.get_webview_window("main") {
                let title = if label.is_empty() {
                    "Lindo".to_string()
                } else {
                    format!("Lindo ({label})")
                };
                let _ = main_win.set_title(&title);
            }
            Ok(json!({}))
        }
        "navigate" => {
            let wv = ensure_bridge_window(&app)?;
            VERIFY_CANCELLED.store(false, Ordering::SeqCst);
            let raw_url = params.get("url").and_then(|v| v.as_str()).unwrap_or("");
            let parsed: Url = raw_url
                .parse()
                .map_err(|_| "无效的跳转目标".to_string())?;
            let valid = (parsed.scheme() == "https" && is_forum_host(parsed.host_str()))
                || parsed.as_str() == "about:blank";
            if !valid {
                return Err("非法跳转域名".to_string());
            }
            wv.navigate(parsed)
                .map_err(|e| format!("WebView2 导航失败: {e}"))?;
            Ok(json!({}))
        }
        "evaluate" => {
            let wv = ensure_bridge_window(&app)?;
            let script = params.get("script").and_then(|v| v.as_str()).unwrap_or("null");
            let isolated = params
                .get("isolated")
                .and_then(|v| v.as_bool())
                .unwrap_or(false);
            if isolated {
                // Zero-footprint synchronous read without mutating `window.__lindoAsyncStore` on verification pages
                let sync_js = format!(
                    r#"(() => {{
  try {{
    const val = ({script});
    return JSON.stringify({{ ok: true, value: val === undefined ? null : val }});
  }} catch (err) {{
    return JSON.stringify({{ ok: false, error: String((err && err.message) || err || "Eval error") }});
  }}
}})()"#
                );
                let raw = eval_single_sync(&wv, sync_js).await?;
                let trimmed = raw.trim();
                let inner_json_str: String = match serde_json::from_str::<String>(trimmed) {
                    Ok(s) => s,
                    Err(_) => trimmed.to_string(),
                };
                let envelope: Value = serde_json::from_str(&inner_json_str)
                    .map_err(|e| format!("解析 WebView2 返回结果失败: {e}"))?;
                if envelope.get("ok").and_then(|b| b.as_bool()) == Some(true) {
                    Ok(envelope.get("value").cloned().unwrap_or(Value::Null))
                } else {
                    Err(envelope
                        .get("error")
                        .and_then(|e| e.as_str())
                        .unwrap_or("WebView2 script execution failed")
                        .to_string())
                }
            } else {
                eval_async_promise(&wv, script, timeout_secs).await
            }
        }
        "cookies" => {
            let wv = ensure_bridge_window(&app)?;
            tokio::task::spawn_blocking(move || {
                let forum_url: Url = "https://linux.do/".parse().unwrap();
                let list = wv
                    .cookies_for_url(forum_url)
                    .map_err(|e| format!("读取 WebView2 Cookie 失败: {e}"))?;
                let mut out = Vec::new();
                for c in list {
                    let dom = c.domain().unwrap_or("linux.do");
                    let clean_dom = dom.strip_prefix('.').unwrap_or(dom);
                    if is_forum_host(Some(clean_dom)) {
                        let host_only = !dom.starts_with('.');
                        out.push(json!({
                            "name": c.name(),
                            "value": c.value(),
                            "domain": dom,
                            "path": c.path().unwrap_or("/"),
                            "hostOnly": host_only
                        }));
                    }
                }
                Ok(json!({ "cookies": out }))
            })
            .await
            .map_err(|e| format!("Cookie 线程异常: {e}"))?
        }
        "setCookies" => {
            let wv = ensure_bridge_window(&app)?;
            let incoming = params
                .get("cookies")
                .and_then(|v| v.as_array())
                .cloned()
                .unwrap_or_default();

            tokio::task::spawn_blocking(move || {
                let forum_url: Url = "https://linux.do/".parse().unwrap();
                // Site-owned Cloudflare cookies remain 100% managed by WebView2's native store; never delete or synthesize them.
                let target_names: std::collections::HashSet<String> = incoming
                    .iter()
                    .filter_map(|i| {
                        let name = i.get("name")?.as_str()?;
                        if name.is_empty() || is_cloudflare_domain_cookie_name(name) {
                            None
                        } else {
                            Some(name.to_string())
                        }
                    })
                    .collect();

                if target_names.is_empty() {
                    return Ok(json!({}));
                }

                if let Ok(existing) = wv.cookies_for_url(forum_url) {
                    for c in existing {
                        if target_names.contains(c.name()) {
                            let _ = wv.delete_cookie(c);
                        }
                    }
                }

                for item in incoming {
                    let Some(name) = item.get("name").and_then(|v| v.as_str()) else {
                        continue;
                    };
                    let Some(val) = item.get("value").and_then(|v| v.as_str()) else {
                        continue;
                    };
                    if name.is_empty() || val.is_empty() || is_cloudflare_domain_cookie_name(name) {
                        continue;
                    }
                    let persistent = name == "_t";
                    let ck = build_host_only_forum_cookie(name.to_string(), val.to_string(), persistent);
                    let _ = wv.set_cookie(ck);
                }
                Ok(json!({}))
            })
            .await
            .map_err(|e| format!("写入 Cookie 线程异常: {e}"))?
        }
        "sweepAuthCookies" => {
            let wv = ensure_bridge_window(&app)?;
            let mode = params
                .get("mode")
                .and_then(|v| v.as_str())
                .unwrap_or("dedupe")
                .to_string();
            if mode == "clear" {
                purge_storage_auth_js(&wv);
            }
            let preferred_t = params
                .get("preferredT")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string();

            tokio::task::spawn_blocking(move || {
                let forum_url: Url = "https://linux.do/".parse().unwrap();
                let existing = wv.cookies_for_url(forum_url).unwrap_or_default();

                if mode == "clear" {
                    for c in existing {
                        if matches!(c.name(), "_t" | "_forum_session" | "authentication_data") {
                            let _ = wv.delete_cookie(c);
                        }
                    }
                    return Ok(json!({ "cleared": true }));
                }

                // Strictly deduplicate Discourse `_t` / `_forum_session` when needed; never touch `cf_clearance`!
                let mut t_variants = Vec::new();
                let mut fs_variants = Vec::new();
                for c in existing {
                    if c.name() == "_t" && !c.value().is_empty() {
                        t_variants.push(c);
                    } else if c.name() == "_forum_session" && !c.value().is_empty() {
                        fs_variants.push(c);
                    }
                }

                let t_needs = t_variants.len() > 1
                    || t_variants
                        .first()
                        .and_then(|c| c.domain())
                        .map(|d| d.starts_with('.'))
                        .unwrap_or(false);
                let fs_needs = fs_variants.len() > 1
                    || fs_variants
                        .first()
                        .and_then(|c| c.domain())
                        .map(|d| d.starts_with('.'))
                        .unwrap_or(false);

                if !t_needs && !fs_needs {
                    return Ok(json!({ "swept": false }));
                }

                let winner_t = if t_needs {
                    t_variants
                        .iter()
                        .find(|c| !preferred_t.is_empty() && c.value() == preferred_t)
                        .or_else(|| {
                            t_variants
                                .iter()
                                .rev()
                                .find(|c| !c.domain().unwrap_or("").starts_with('.'))
                        })
                        .or_else(|| t_variants.last())
                        .map(|c| c.value().to_string())
                } else {
                    None
                };

                let winner_fs = if fs_needs {
                    fs_variants
                        .iter()
                        .rev()
                        .find(|c| !c.domain().unwrap_or("").starts_with('.'))
                        .or_else(|| fs_variants.last())
                        .map(|c| c.value().to_string())
                } else {
                    None
                };

                if t_needs {
                    for c in t_variants {
                        let _ = wv.delete_cookie(c);
                    }
                }
                if fs_needs {
                    for c in fs_variants {
                        let _ = wv.delete_cookie(c);
                    }
                }
                if let Some(wt) = winner_t {
                    let _ = wv.set_cookie(build_host_only_forum_cookie("_t".to_string(), wt, true));
                }
                if let Some(wfs) = winner_fs {
                    let _ = wv.set_cookie(build_host_only_forum_cookie(
                        "_forum_session".to_string(),
                        wfs,
                        false,
                    ));
                }
                Ok(json!({ "swept": true }))
            })
            .await
            .map_err(|e| format!("清理 Cookie 线程异常: {e}"))?
        }
        "clearCookies" | "deleteCookie" => {
            let wv = ensure_bridge_window(&app)?;
            let is_clear_all = method == "clearCookies";
            let target_name = params
                .get("name")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string();

            tokio::task::spawn_blocking(move || {
                let forum_url: Url = "https://linux.do/".parse().unwrap();
                if let Ok(existing) = wv.cookies_for_url(forum_url) {
                    for c in existing {
                        if is_clear_all || c.name() == target_name {
                            let _ = wv.delete_cookie(c);
                        }
                    }
                }
                Ok(json!({}))
            })
            .await
            .map_err(|e| format!("删除 Cookie 线程异常: {e}"))?
        }
        "fetchConnectHtml" => {
            let connect_url: Url = "https://connect.linux.do/"
                .parse()
                .map_err(|e| format!("Invalid Connect URL: {e}"))?;
            let probe = if let Some(existing) = app.get_webview_window(CONNECT_PROBE_LABEL) {
                let _ = existing.navigate(connect_url);
                existing
            } else {
                WebviewWindowBuilder::new(&app, CONNECT_PROBE_LABEL, WebviewUrl::External(connect_url))
                    .title("Lindo Connect Probe")
                    .inner_size(960.0, 720.0)
                    .visible(false)
                    .build()
                    .map_err(|e| format!("初始化 Connect 探测窗口失败: {e}"))?
            };

            let check_js = r#"(() => {
  const h = (location && location.hostname) || '';
  const isChallenge = Boolean(document.querySelector('#challenge-running, #challenge-form, .cf-turnstile, iframe[src*="challenges.cloudflare.com"]'));
  const hasMarkers = h === 'connect.linux.do' && Boolean(document.querySelector('.tl3-ring, .tl3-bar-item, .tl3-quota-card, .card-title, table'));
  return JSON.stringify({ host: h, isChallenge, hasMarkers, html: hasMarkers && document.documentElement ? document.documentElement.outerHTML : '' });
})()"#
            .to_string();

            let deadline = Instant::now() + Duration::from_secs(8);
            let mut extracted = String::new();
            while Instant::now() < deadline {
                tokio::time::sleep(Duration::from_millis(280)).await;
                if let Ok(raw) = eval_single_sync(&probe, check_js.clone()).await {
                    let inner_str = serde_json::from_str::<String>(raw.trim())
                        .unwrap_or_else(|_| raw.trim().to_string());
                    if let Ok(v) = serde_json::from_str::<Value>(&inner_str) {
                        if v.get("isChallenge").and_then(|b| b.as_bool()) == Some(true) {
                            break;
                        }
                        if v.get("hasMarkers").and_then(|b| b.as_bool()) == Some(true) {
                            let h = v.get("html").and_then(|s| s.as_str()).unwrap_or("");
                            if !h.is_empty() {
                                extracted = h.to_string();
                                break;
                            }
                        }
                    }
                }
            }
            if let Ok(blank) = "about:blank".parse::<Url>() {
                let _ = probe.navigate(blank);
            }
            Ok(json!({ "html": extracted }))
        }
        "setVerifyMask" => {
            let visible = params.get("visible").and_then(|v| v.as_bool()).unwrap_or(false);
            let dark = params.get("dark").and_then(|v| v.as_bool()).unwrap_or(true);
            let msg = params
                .get("message")
                .and_then(|v| v.as_str())
                .unwrap_or("正在连接安全验证服务…");
            if let Ok(wv) = ensure_bridge_window(&app) {
                let bg = if dark { "#101218" } else { "#F7F8FA" };
                let fg = if dark { "#A1A8B5" } else { "#525866" };
                let msg_json = json!(msg).to_string();
                let js = if visible {
                    format!(
                        r#"(() => {{
  const root = document.body || document.documentElement;
  if (!root) return true;
  let m = document.getElementById('__lindoVerifyMask');
  if (!m) {{
    m = document.createElement('div');
    m.id = '__lindoVerifyMask';
    root.appendChild(m);
  }}
  m.style.cssText = 'position:fixed!important;inset:0!important;z-index:2147483647!important;display:flex!important;align-items:center!important;justify-content:center!important;background:{bg}!important;color:{fg}!important;font:500 13.5px -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif!important;pointer-events:auto!important;';
  m.textContent = {msg_json};
  return true;
}})()"#
                    )
                } else {
                    r#"(() => {
  const m = document.getElementById('__lindoVerifyMask');
  if (m) m.style.display = 'none';
  return true;
})()"#
                        .to_string()
                };
                let _ = wv.eval(js);
            }
            Ok(json!({}))
        }
        _ => Err(format!("Unsupported bridge command: {method}")),
    }
}
