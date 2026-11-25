#![cfg(target_os = "linux")]

use cookie::time::Duration as CookieDuration;
use cookie::Cookie;
use serde_json::{json, Value};
use std::sync::atomic::{AtomicBool, AtomicU16, AtomicU64, Ordering};
use std::sync::OnceLock;
use std::time::{Duration, Instant};
use tauri::{AppHandle, Manager, Theme, WebviewUrl, WebviewWindow, WebviewWindowBuilder, WindowEvent};
use tokio::sync::oneshot;
use url::Url;

const BRIDGE_WINDOW_LABEL: &str = "lindo-bridge-host";
const BROWSER_WINDOW_LABEL: &str = "lindo-forum-browser";
const CONNECT_PROBE_LABEL: &str = "lindo-connect-probe";

const LINUX_ORIGIN_HTML: &str =
    "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body></body></html>";

const FORUM_ECH_PINNED_HOSTS: [&str; 6] = [
    "linux.do",
    "www.linux.do",
    "connect.linux.do",
    "ldstatic.com",
    "cdn.ldstatic.com",
    "idcflare.com",
];

static ACTIVE_PROXY_PORT: AtomicU16 = AtomicU16::new(0);
static VERIFY_CANCELLED: AtomicBool = AtomicBool::new(false);
static BRIDGE_LOADING: AtomicBool = AtomicBool::new(false);
static BRIDGE_ORIGIN_404: AtomicBool = AtomicBool::new(false);
static BRIDGE_INTERACTIVE: AtomicBool = AtomicBool::new(false);
static EVAL_SEQ: AtomicU64 = AtomicU64::new(1);
static RELOAD_EPOCH: AtomicU64 = AtomicU64::new(0);
static PROTOCOL_REGISTERED: OnceLock<()> = OnceLock::new();
static CACHED_BRIDGE_UA: OnceLock<std::sync::Mutex<String>> = OnceLock::new();
static BRIDGE_LAST_ERROR: OnceLock<std::sync::Mutex<String>> = OnceLock::new();

fn register_linux_url_protocols_once() {
    PROTOCOL_REGISTERED.get_or_init(|| {
        std::thread::spawn(|| {
            let system_installed = [
                "/usr/share/applications/Lindo.desktop",
                "/usr/share/applications/lindo.desktop",
                "/usr/share/applications/lindo-desktop.desktop",
            ]
            .iter()
            .any(|p| std::path::Path::new(p).exists());

            let Some(data_dir) = dirs::data_local_dir()
                .or_else(|| dirs::home_dir().map(|h| h.join(".local/share")))
            else {
                return;
            };
            let apps_dir = data_dir.join("applications");
            let local_desktop = apps_dir.join("lindo-url-handler.desktop");

            if system_installed {
                if local_desktop.exists() {
                    let _ = std::fs::remove_file(&local_desktop);
                    let _ = std::process::Command::new("update-desktop-database")
                        .arg("-q")
                        .arg(&apps_dir)
                        .status();
                }
                return;
            }

            let exe_path = std::env::var_os("APPIMAGE")
                .map(std::path::PathBuf::from)
                .filter(|p| p.exists())
                .or_else(|| std::env::current_exe().ok());
            let Some(exe) = exe_path else {
                return;
            };
            let Some(exe_str) = exe.to_str() else {
                return;
            };
            if std::fs::create_dir_all(&apps_dir).is_err() {
                return;
            }
            let escaped_exec = exe_str.replace('\\', "\\\\").replace('"', "\\\"");
            let content = format!(
                "[Desktop Entry]\n\
                 Type=Application\n\
                 Name=Lindo\n\
                 Exec=\"{escaped_exec}\" %u\n\
                 Terminal=false\n\
                 NoDisplay=true\n\
                 MimeType=x-scheme-handler/discourse;x-scheme-handler/lindo;x-scheme-handler/fluxdo;\n"
            );
            if std::fs::write(&local_desktop, content).is_ok() {
                let _ = std::process::Command::new("xdg-mime")
                    .args([
                        "default",
                        "lindo-url-handler.desktop",
                        "x-scheme-handler/discourse",
                        "x-scheme-handler/lindo",
                        "x-scheme-handler/fluxdo",
                    ])
                    .status();
                let _ = std::process::Command::new("update-desktop-database")
                    .arg("-q")
                    .arg(&apps_dir)
                    .status();
            }
        });
    });
}

fn bridge_last_error() -> &'static std::sync::Mutex<String> {
    BRIDGE_LAST_ERROR.get_or_init(|| std::sync::Mutex::new(String::new()))
}

fn set_bridge_last_error(msg: &str) {
    if let Ok(mut g) = bridge_last_error().lock() {
        *g = msg.to_string();
    }
}

fn get_bridge_last_error() -> String {
    bridge_last_error()
        .lock()
        .map(|g| g.clone())
        .unwrap_or_default()
}

fn cached_bridge_ua() -> &'static std::sync::Mutex<String> {
    CACHED_BRIDGE_UA.get_or_init(|| std::sync::Mutex::new(String::new()))
}

fn is_forum_host(host: Option<&str>) -> bool {
    let Some(h) = host else { return false };
    let lower = h.to_ascii_lowercase();
    lower == "linux.do" || lower.ends_with(".linux.do")
}

fn is_auth_redirect_scheme(url: &Url) -> bool {
    matches!(url.scheme(), "discourse" | "lindo" | "fluxdo")
}

fn apply_proxy_and_certs_on_inner(inner: &webkit2gtk::WebView, port: u16) {
    use webkit2gtk::{
        NetworkProxyMode, NetworkProxySettings, WebContextExt, WebViewExt, WebsiteDataManagerExt,
    };

    if port == 0 {
        if let Some(dm) = inner.website_data_manager() {
            dm.set_network_proxy_settings(NetworkProxyMode::Default, None);
        }
        if let Some(ctx_dm) = inner.context().and_then(|c| c.website_data_manager()) {
            ctx_dm.set_network_proxy_settings(NetworkProxyMode::Default, None);
        }
    } else {
        let proxy_uri = format!("http://127.0.0.1:{port}");
        let ignore = ["127.0.0.1", "localhost"];
        if let Some(dm) = inner.website_data_manager() {
            let mut settings = NetworkProxySettings::new(Some(&proxy_uri), &ignore);
            dm.set_network_proxy_settings(NetworkProxyMode::Custom, Some(&mut settings));
        }
        if let Some(ctx) = inner.context() {
            if let Some(ctx_dm) = ctx.website_data_manager() {
                let mut settings = NetworkProxySettings::new(Some(&proxy_uri), &ignore);
                ctx_dm.set_network_proxy_settings(NetworkProxyMode::Custom, Some(&mut settings));
            }
            for host in FORUM_ECH_PINNED_HOSTS {
                if let Some(pem) = lindo_ech::mint_active_gateway_leaf_pem(host) {
                    if let Ok(tls_cert) = gio::TlsCertificate::from_pem(&pem) {
                        ctx.allow_tls_certificate_for_host(&tls_cert, host);
                    }
                }
            }
        }
    }
}

/// Live-switches WebKitGTK's proxy configuration across all forum WebViews (1:1 with macOS `WKWebsiteDataStore`).
/// `port == 0` routes WebKitGTK directly through OS / TUN (`NetworkProxyMode::Default`);
/// `port > 0` routes WebKitGTK through the local ECH gateway (`http://127.0.0.1:{port}`).
pub fn set_webkit_proxy_port(port: u16) {
    ACTIVE_PROXY_PORT.store(port, Ordering::SeqCst);
    let Some(app) = crate::bridge::global_app_handle() else {
        return;
    };
    for label in [BRIDGE_WINDOW_LABEL, BROWSER_WINDOW_LABEL, CONNECT_PROBE_LABEL] {
        if let Some(wv) = app.get_webview_window(label) {
            let _ = wv.with_webview(move |platform_wv| {
                let inner = platform_wv.inner();
                apply_proxy_and_certs_on_inner(&inner, port);
            });
        }
    }
}

fn install_linux_webview_hooks(wv: &WebviewWindow, app: &AppHandle, is_bridge_host: bool) {
    let main_wv_opt = if is_bridge_host {
        app.get_webview_window("main")
    } else {
        None
    };
    let app_for_events = app.clone();
    let initial_port = ACTIVE_PROXY_PORT.load(Ordering::SeqCst);

    let _ = wv.with_webview(move |platform_wv| {
        use gio::prelude::TlsCertificateExt;
        use glib::Cast;
        use gtk::prelude::{GtkWindowExt, WidgetExt};
        use webkit2gtk::{
            CookieAcceptPolicy, CookieManagerExt, LoadEvent, SettingsExt, WebContextExt,
            WebViewExt, WebsiteDataManagerExt,
        };

        let inner = platform_wv.inner();

        if let Some(settings) = WebViewExt::settings(&inner) {
            if let Some(ua_gstr) = settings.user_agent() {
                let ua_str = ua_gstr.as_str().trim();
                if !ua_str.is_empty() {
                    if let Ok(mut g) = cached_bridge_ua().lock() {
                        if g.is_empty() {
                            *g = ua_str.to_string();
                        }
                    }
                }
            }
        }

        if let Some(cm) = inner
            .website_data_manager()
            .and_then(|dm| dm.cookie_manager())
        {
            // Allow Cloudflare Turnstile cross-origin iframe (`challenges.cloudflare.com`) cookies
            cm.set_accept_policy(CookieAcceptPolicy::Always);
        }

        apply_proxy_and_certs_on_inner(&inner, initial_port);

        if is_bridge_host {
            if let Some(bridge_top) = inner
                .toplevel()
                .and_then(|w| w.downcast::<gtk::Window>().ok())
            {
                bridge_top.set_type_hint(gtk::gdk::WindowTypeHint::Dialog);
                if let Some(main_wv) = main_wv_opt {
                    if let Ok(main_top) = main_wv.gtk_window() {
                        bridge_top.set_transient_for(Some(&main_top));
                    }
                }
            }
        }

        inner.connect_load_failed_with_tls_errors(|web_view, failing_uri, cert, _errors| {
            let Ok(parsed) = Url::parse(failing_uri) else {
                return false;
            };
            let Some(host) = parsed.host_str() else {
                return false;
            };
            let Some(der_bytes) = cert.certificate() else {
                return false;
            };
            if !lindo_ech::verify_leaf_der(&der_bytes, host) {
                return false;
            }
            if let Some(ctx) = web_view.context() {
                ctx.allow_tls_certificate_for_host(cert, host);
                web_view.load_uri(failing_uri);
                return true;
            }
            false
        });

        if is_bridge_host {
            let app_load = app_for_events.clone();
            inner.connect_load_changed(move |_web_view, event| match event {
                LoadEvent::Started => {
                    BRIDGE_LOADING.store(true, Ordering::Relaxed);
                    set_bridge_last_error("");
                }
                LoadEvent::Finished => {
                    BRIDGE_LOADING.store(false, Ordering::Relaxed);
                    if let Some(eng) =
                        app_load.try_state::<std::sync::Arc<crate::bridge::BridgeEngine>>()
                    {
                        eng.notify_verify_event();
                    }
                }
                _ => {}
            });

            let app_fail = app_for_events.clone();
            inner.connect_load_failed(move |_web_view, _event, failing_uri, err| {
                if err.matches(webkit2gtk::NetworkError::Cancelled) {
                    return false;
                }
                BRIDGE_LOADING.store(false, Ordering::Relaxed);
                set_bridge_last_error(&format!("{err} ({failing_uri})"));
                if let Some(eng) =
                    app_fail.try_state::<std::sync::Arc<crate::bridge::BridgeEngine>>()
                {
                    eng.notify_verify_event();
                }
                false
            });

            // Establish clean zero-network same-origin `https://linux.do/about` context immediately (<2ms)
            inner.load_html(LINUX_ORIGIN_HTML, Some("https://linux.do/about"));
        }
    });
}

fn configure_forum_webview<'a, R: tauri::Runtime, M: Manager<R>>(
    mut builder: WebviewWindowBuilder<'a, R, M>,
    app: &AppHandle,
    hide_on_redirect: bool,
) -> WebviewWindowBuilder<'a, R, M> {
    let mode_pref = app
        .try_state::<std::sync::Arc<crate::bridge::BridgeEngine>>()
        .map(|e| e.state.lock().unwrap().preferences.ech_routing_mode.clone())
        .unwrap_or_else(|| "smart_auto".to_string());
    crate::ech_desktop::ensure_linux_ech_reconciled(&mode_pref);

    let data_dir = crate::legacy::lindo_home_dir().join("webkitgtk-forum");
    let _ = std::fs::create_dir_all(&data_dir);
    builder = builder.data_directory(data_dir);

    let app_for_nav = app.clone();
    builder = builder.on_navigation(move |nav_url| {
        if is_auth_redirect_scheme(nav_url) {
            let raw = nav_url.as_str().to_string();
            let app_clone = app_for_nav.clone();
            if hide_on_redirect {
                if let Some(bw) = app_for_nav.get_webview_window(BROWSER_WINDOW_LABEL) {
                    let _ = bw.hide();
                }
                if let Some(hw) = app_for_nav.get_webview_window(BRIDGE_WINDOW_LABEL) {
                    let _ = hw.hide();
                }
            }
            tauri::async_runtime::spawn(async move {
                if let Some(eng) = app_clone.try_state::<std::sync::Arc<crate::bridge::BridgeEngine>>() {
                    let _ = eng.handle_auth_redirect(&app_clone, &raw).await;
                }
            });
            return false;
        }
        true
    });
    builder
}

fn ensure_bridge_window(app: &AppHandle) -> Result<WebviewWindow, String> {
    if let Some(existing) = app.get_webview_window(BRIDGE_WINDOW_LABEL) {
        return Ok(existing);
    }
    let init_url: Url = "about:blank"
        .parse()
        .map_err(|e| format!("Invalid blank URL: {e}"))?;

    let wv = configure_forum_webview(
        WebviewWindowBuilder::new(app, BRIDGE_WINDOW_LABEL, WebviewUrl::External(init_url)),
        app,
        true,
    )
    .title("安全确认")
    .inner_size(480.0, 380.0)
    .min_inner_size(380.0, 340.0)
    .resizable(false)
    .visible(false)
    .center()
    .build()
    .map_err(|e| format!("无法初始化 WebKitGTK 桥接实例: {e}"))?;
    install_linux_webview_hooks(&wv, app, true);

    wv.on_window_event({
        let wv_clone = wv.clone();
        let app_clone = app.clone();
        move |event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                if !crate::EXPLICIT_QUIT_REQUESTED.load(Ordering::SeqCst) {
                    api.prevent_close();
                    VERIFY_CANCELLED.store(true, Ordering::SeqCst);
                    let _ = wv_clone.hide();
                    if let Some(eng) =
                        app_clone.try_state::<std::sync::Arc<crate::bridge::BridgeEngine>>()
                    {
                        eng.notify_verify_event();
                    }
                }
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

    let blank_url: Url = "about:blank"
        .parse()
        .map_err(|e| format!("Invalid blank URL: {e}"))?;
    let browser = configure_forum_webview(
        WebviewWindowBuilder::new(app, BROWSER_WINDOW_LABEL, WebviewUrl::External(blank_url)),
        app,
        true,
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
    install_linux_webview_hooks(&browser, app, false);
    let _ = browser.navigate(target_url);

    browser.on_window_event({
        let b_clone = browser.clone();
        move |event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                if !crate::EXPLICIT_QUIT_REQUESTED.load(Ordering::SeqCst) {
                    api.prevent_close();
                    let _ = b_clone.hide();
                }
            }
        }
    });

    Ok(())
}

fn soup_to_rust_cookie(mut sc: soup::Cookie) -> Cookie<'static> {
    let name = sc.name().map(|n| n.to_string()).unwrap_or_default();
    let value = sc.value().map(|v| v.to_string()).unwrap_or_default();
    let mut b = cookie::CookieBuilder::new(name, value);
    if let Some(dom) = sc.domain().map(|d| d.to_string()) {
        b = b.domain(dom);
    }
    if let Some(path) = sc.path().map(|p| p.to_string()) {
        b = b.path(path);
    }
    b = b.http_only(sc.is_http_only());
    b = b.secure(sc.is_secure());
    b = b.same_site(match sc.same_site_policy() {
        soup::SameSitePolicy::Lax => cookie::SameSite::Lax,
        soup::SameSitePolicy::Strict => cookie::SameSite::Strict,
        _ => cookie::SameSite::None,
    });
    if let Some(dt) = sc.expires() {
        if let Ok(odt) = cookie::time::OffsetDateTime::from_unix_timestamp(dt.to_unix()) {
            b = b.expires(cookie::Expiration::DateTime(odt));
        }
    } else {
        b = b.expires(cookie::Expiration::Session);
    }
    b.build()
}

fn rust_to_soup_cookie(ck: &Cookie<'_>) -> soup::Cookie {
    let mut sc = soup::Cookie::new(
        ck.name(),
        ck.value(),
        ck.domain().unwrap_or("linux.do"),
        ck.path().unwrap_or("/"),
        ck.max_age()
            .map(|d| d.whole_seconds() as i32)
            .unwrap_or(-1),
    );
    if let Some(dt) = ck.expires_datetime() {
        if let Ok(gdt) = glib::DateTime::from_unix_utc(dt.unix_timestamp()) {
            sc.set_expires(&gdt);
        }
    }
    if let Some(ho) = ck.http_only() {
        sc.set_http_only(ho);
    }
    if let Some(ss) = ck.same_site() {
        sc.set_same_site_policy(match ss {
            cookie::SameSite::Lax => soup::SameSitePolicy::Lax,
            cookie::SameSite::Strict => soup::SameSitePolicy::Strict,
            cookie::SameSite::None => soup::SameSitePolicy::None,
        });
    }
    if let Some(sec) = ck.secure() {
        sc.set_secure(sec);
    }
    sc
}

async fn read_forum_cookies_async(wv: &WebviewWindow) -> Result<Vec<Cookie<'static>>, String> {
    let (tx, rx) = oneshot::channel::<Result<Vec<Cookie<'static>>, String>>();
    wv.with_webview(move |platform_wv| {
        use webkit2gtk::{CookieManagerExt, WebViewExt, WebsiteDataManagerExt};
        let inner = platform_wv.inner();
        let Some(cm) = inner
            .website_data_manager()
            .and_then(|dm| dm.cookie_manager())
        else {
            let _ = tx.send(Ok(Vec::new()));
            return;
        };
        cm.cookies("https://linux.do/", None::<&gio::Cancellable>, move |res| {
            let mapped = res
                .map(|list| {
                    list.into_iter()
                        .map(soup_to_rust_cookie)
                        .filter(|c| {
                            let dom = c.domain().unwrap_or("");
                            let clean = dom.strip_prefix('.').unwrap_or(dom);
                            is_forum_host(Some(clean))
                        })
                        .collect::<Vec<_>>()
                })
                .map_err(|e| format!("读取 WebKitGTK Cookie 失败: {e}"));
            let _ = tx.send(mapped);
        });
    })
    .map_err(|e| format!("调度 WebKitGTK Cookie 读取失败: {e}"))?;

    tokio::time::timeout(Duration::from_secs(5), rx)
        .await
        .map_err(|_| "WebKitGTK Cookie 读取超时".to_string())?
        .map_err(|_| "WebKitGTK Cookie 通道已关闭".to_string())?
}

async fn set_forum_cookie_async(wv: &WebviewWindow, ck: Cookie<'static>) -> Result<(), String> {
    let (tx, rx) = oneshot::channel::<Result<(), String>>();
    wv.with_webview(move |platform_wv| {
        use webkit2gtk::{CookieManagerExt, WebViewExt, WebsiteDataManagerExt};
        let inner = platform_wv.inner();
        let Some(cm) = inner
            .website_data_manager()
            .and_then(|dm| dm.cookie_manager())
        else {
            let _ = tx.send(Ok(()));
            return;
        };
        let mut sc = rust_to_soup_cookie(&ck);
        cm.add_cookie(&mut sc, None::<&gio::Cancellable>, move |res| {
            let _ = tx.send(res.map_err(|e| format!("写入 Cookie 失败: {e}")));
        });
    })
    .map_err(|e| format!("调度写入 Cookie 失败: {e}"))?;
    let _ = tokio::time::timeout(Duration::from_secs(4), rx).await;
    Ok(())
}

async fn delete_forum_cookie_async(wv: &WebviewWindow, ck: Cookie<'static>) -> Result<(), String> {
    let (tx, rx) = oneshot::channel::<Result<(), String>>();
    wv.with_webview(move |platform_wv| {
        use webkit2gtk::{CookieManagerExt, WebViewExt, WebsiteDataManagerExt};
        let inner = platform_wv.inner();
        let Some(cm) = inner
            .website_data_manager()
            .and_then(|dm| dm.cookie_manager())
        else {
            let _ = tx.send(Ok(()));
            return;
        };
        let mut sc = rust_to_soup_cookie(&ck);
        cm.delete_cookie(&mut sc, None::<&gio::Cancellable>, move |res| {
            let _ = tx.send(res.map_err(|e| format!("删除 Cookie 失败: {e}")));
        });
    })
    .map_err(|e| format!("调度删除 Cookie 失败: {e}"))?;
    let _ = tokio::time::timeout(Duration::from_secs(4), rx).await;
    Ok(())
}

fn clear_challenge_retry_counters_fire_and_forget(wv: &WebviewWindow) {
    let _ = wv.with_webview(|platform_wv| {
        use webkit2gtk::{CookieManagerExt, WebViewExt, WebsiteDataManagerExt};
        let inner = platform_wv.inner();
        let Some(cm) = inner
            .website_data_manager()
            .and_then(|dm| dm.cookie_manager())
        else {
            return;
        };
        let cm_clone = cm.clone();
        cm.cookies("https://linux.do/", None::<&gio::Cancellable>, move |res| {
            if let Ok(list) = res {
                for mut sc in list {
                    let is_counter = sc
                        .name()
                        .map(|n| matches!(n.as_str(), "cf_chl_rc_ni" | "cf_chl_rc_i" | "cf_chl_rc_m"))
                        .unwrap_or(false);
                    if is_counter {
                        cm_clone.delete_cookie(&mut sc, None::<&gio::Cancellable>, |_| {});
                    }
                }
            }
        });
    });
}

#[allow(deprecated)]
fn eval_fire_and_forget(wv: &WebviewWindow, js: String) -> Result<(), String> {
    wv.with_webview(move |platform_wv| {
        use webkit2gtk::WebViewExt;
        platform_wv
            .inner()
            .run_javascript(&js, None::<&gio::Cancellable>, |_res| {});
    })
    .map_err(|e| format!("WebKitGTK 启动脚本失败: {e}"))
}

#[allow(deprecated)]
async fn eval_single_sync(wv: &WebviewWindow, js: String) -> Result<String, String> {
    let (tx, rx) = oneshot::channel::<Result<String, String>>();
    wv.with_webview(move |platform_wv| {
        use javascriptcore::ValueExt;
        use webkit2gtk::WebViewExt;
        platform_wv
            .inner()
            .run_javascript(&js, None::<&gio::Cancellable>, move |res| {
                let mapped = match res {
                    Ok(js_res) => Ok(js_res
                        .js_value()
                        .and_then(|v| v.to_json(0))
                        .map(|g| g.to_string())
                        .unwrap_or_default()),
                    Err(err) => Err(format!("WebKitGTK 脚本执行错误: {err}")),
                };
                let _ = tx.send(mapped);
            });
    })
    .map_err(|e| format!("WebKitGTK 脚本调用失败: {e}"))?;

    tokio::time::timeout(Duration::from_secs(5), rx)
        .await
        .map_err(|_| "WebKitGTK 同步脚本读取超时".to_string())?
        .map_err(|_| "WebKitGTK 回调通道已关闭".to_string())?
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

    eval_fire_and_forget(wv, launch_js)?;

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
    let mut last_poll_err: Option<String> = None;

    while Instant::now() < deadline {
        tokio::time::sleep(Duration::from_millis(delay_ms)).await;
        delay_ms = (delay_ms + 12).min(55);

        let raw = match eval_single_sync(wv, poll_js.clone()).await {
            Ok(r) => {
                last_poll_err = None;
                r
            }
            Err(e) => {
                last_poll_err = Some(e);
                continue;
            }
        };
        let trimmed = raw.trim();
        if trimmed.is_empty() || trimmed == "null" || trimmed == "\"\"" {
            continue;
        }
        let inner_json_str: String = match serde_json::from_str::<String>(trimmed) {
            Ok(s) => s,
            Err(_) => trimmed.to_string(),
        };
        if inner_json_str.is_empty() {
            continue;
        }
        let envelope: Value = match serde_json::from_str(&inner_json_str) {
            Ok(v) => v,
            Err(e) => {
                let cleanup_js = format!(
                    r#"if (window.__lindoAsyncStore) {{ delete window.__lindoAsyncStore["{slot}"]; }}"#
                );
                let _ = eval_fire_and_forget(wv, cleanup_js);
                return Err(format!("解析 WebKitGTK 返回结果失败: {e}"));
            }
        };
        if envelope.get("ok").and_then(|b| b.as_bool()) == Some(true) {
            return Ok(envelope.get("value").cloned().unwrap_or(Value::Null));
        } else {
            let err_msg = envelope
                .get("error")
                .and_then(|e| e.as_str())
                .unwrap_or("WebKitGTK script execution failed");
            return Err(err_msg.to_string());
        }
    }

    let cleanup_js = format!(
        r#"if (window.__lindoAsyncStore) {{ delete window.__lindoAsyncStore["{slot}"]; }}"#
    );
    let _ = eval_fire_and_forget(wv, cleanup_js);
    if let Some(err) = last_poll_err {
        Err(format!("WebKitGTK 异步操作超时 ({err})"))
    } else {
        Err("WebKitGTK 异步操作超时".to_string())
    }
}

fn purge_storage_auth_js(wv: &WebviewWindow) {
    let _ = eval_fire_and_forget(
        wv,
        r#"try {
  if (location.hostname === 'linux.do') {
    for (const k of ['authentication_data']) {
      try { localStorage.removeItem(k); } catch (_) {}
      try { sessionStorage.removeItem(k); } catch (_) {}
    }
  }
} catch (_) {}"#
            .to_string(),
    );
}

fn is_cloudflare_domain_cookie_name(name: &str) -> bool {
    matches!(
        name,
        "cf_clearance" | "__cf_bm" | "_cfuvid" | "cf_chl_rc_i" | "cf_chl_rc_ni" | "cf_chl_rc_m"
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
            let login = params.get("login").and_then(|v| v.as_bool()).unwrap_or(false);
            let _ = wv.set_theme(Some(if dark { Theme::Dark } else { Theme::Light }));
            let _ = wv.set_title(if login { "登录 LINUX DO" } else { "安全确认" });
            if login {
                let _ = wv.set_resizable(true);
                let _ = wv.set_min_size(Some(tauri::LogicalSize::new(420.0, 460.0)));
                let _ = wv.set_size(tauri::LogicalSize::new(540.0, 620.0));
            } else {
                // Fixed dialog size floats cleanly on tiling Wayland WMs (Hyprland / Sway).
                let _ = wv.set_resizable(false);
                let _ = wv.set_min_size(Some(tauri::LogicalSize::new(420.0, 340.0)));
                let _ = wv.set_size(tauri::LogicalSize::new(480.0, 380.0));
            }
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
            let mut ua = cached_bridge_ua()
                .lock()
                .map(|g| g.clone())
                .unwrap_or_default();
            let mut loading = BRIDGE_LOADING.load(Ordering::Relaxed);
            let mut origin_404 = BRIDGE_ORIGIN_404.load(Ordering::Relaxed);
            let mut interactive_required = BRIDGE_INTERACTIVE.load(Ordering::Relaxed);

            if let Some(wv) = app.get_webview_window(BRIDGE_WINDOW_LABEL) {
                let probe_js = r#"(() => {
  try {
    if (!window.__lindoLinuxInteractiveHook) {
      window.__lindoLinuxInteractiveHook = true;
      window.__lindoLinuxInteractiveBegin = false;
      window.addEventListener('message', function(ev) {
        try {
          var d = ev && ev.data;
          var s = typeof d === 'string' ? d : (d && typeof d === 'object' ? JSON.stringify(d) : '');
          if (s && (s.indexOf('interactiveBegin') !== -1 || s.indexOf('interactive-begin') !== -1)) {
            window.__lindoLinuxInteractiveBegin = true;
          }
        } catch (_) {}
      }, true);
    }
    var rs = document.readyState || '';
    var loading = rs === 'loading';
    var lower = ((document.title || '') + ' ' + (document.body ? (document.body.innerText || '').slice(0, 400) : '')).toLowerCase();
    var origin404 = location.origin === 'https://linux.do' && (
      !!document.querySelector('.page-not-found, .discourse-no-results, #main-outlet .not-found-container')
      || lower.indexOf('该页面不存在') !== -1
      || lower.indexOf('exist or is private') !== -1
      || lower.indexOf('page you requested') !== -1
    );
    var iframe = document.querySelector('iframe[src*="challenges.cloudflare.com"], #turnstile-wrapper iframe, .cf-turnstile iframe');
    var rect = iframe ? iframe.getBoundingClientRect() : null;
    var host = document.querySelector('#turnstile-wrapper, .cf-turnstile, #challenge-stage > div');
    var hostRect = host ? host.getBoundingClientRect() : null;
    var hasInteractiveWidget = !!(rect && rect.width >= 80 && rect.height >= 30)
      || !!(hostRect && hostRect.width >= 220 && hostRect.height >= 55);
    var interactive = !!window.__lindoLinuxInteractiveBegin || hasInteractiveWidget;
    return JSON.stringify({ ua: navigator.userAgent || '', loading: loading, origin404: origin404, interactive: interactive });
  } catch (_) {
    return JSON.stringify({ ua: '', loading: false, origin404: false, interactive: false });
  }
})()"#
                    .to_string();
                if let Ok(raw) = eval_single_sync(&wv, probe_js).await {
                    let trimmed = raw.trim();
                    let inner: String = serde_json::from_str::<String>(trimmed)
                        .unwrap_or_else(|_| trimmed.to_string());
                    if let Ok(parsed) = serde_json::from_str::<Value>(&inner) {
                        if let Some(u) = parsed
                            .get("ua")
                            .and_then(|v| v.as_str())
                            .filter(|s| !s.is_empty())
                        {
                            if let Ok(mut g) = cached_bridge_ua().lock() {
                                *g = u.to_string();
                            }
                            ua = u.to_string();
                        }
                        if let Some(l) = parsed.get("loading").and_then(|v| v.as_bool()) {
                            loading = l;
                            BRIDGE_LOADING.store(l, Ordering::Relaxed);
                        }
                        if let Some(o) = parsed.get("origin404").and_then(|v| v.as_bool()) {
                            origin_404 = o;
                            BRIDGE_ORIGIN_404.store(o, Ordering::Relaxed);
                        }
                        if let Some(i) = parsed.get("interactive").and_then(|v| v.as_bool()) {
                            interactive_required = i;
                            if i {
                                BRIDGE_INTERACTIVE.store(true, Ordering::Relaxed);
                            }
                        }
                    }
                }
            }
            let nav_err = get_bridge_last_error();
            Ok(json!({
                "cancelled": cancelled,
                "loading": loading,
                "error": nav_err,
                "origin404": origin_404,
                "interactiveRequired": interactive_required,
                "userReloading": false,
                "reloadEpoch": RELOAD_EPOCH.load(Ordering::Relaxed),
                "maskVisible": false,
                "maskMessage": "",
                "processTerminated": false,
                "bridgeUa": ua
            }))
        }
        "setDockBadge" => {
            register_linux_url_protocols_once();
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
            set_bridge_last_error("");
            BRIDGE_ORIGIN_404.store(false, Ordering::Relaxed);
            BRIDGE_INTERACTIVE.store(false, Ordering::Relaxed);
            let raw_url = params.get("url").and_then(|v| v.as_str()).unwrap_or("");
            let parsed: Url = raw_url
                .parse()
                .map_err(|_| "无效的跳转目标".to_string())?;
            let valid = (parsed.scheme() == "https" && is_forum_host(parsed.host_str()))
                || parsed.as_str() == "about:blank";
            if !valid {
                return Err("非法跳转域名".to_string());
            }
            let target_str = parsed.as_str().to_string();
            if target_str == "https://linux.do/about" {
                clear_challenge_retry_counters_fire_and_forget(&wv);
            }
            let cur_port = ACTIVE_PROXY_PORT.load(Ordering::SeqCst);
            BRIDGE_LOADING.store(true, Ordering::Relaxed);
            wv.with_webview(move |platform_wv| {
                use webkit2gtk::WebViewExt;
                let inner = platform_wv.inner();
                apply_proxy_and_certs_on_inner(&inner, cur_port);
                if target_str == "https://linux.do/about" {
                    inner.load_html(LINUX_ORIGIN_HTML, Some("https://linux.do/about"));
                } else {
                    inner.load_uri(&target_str);
                }
            })
            .map_err(|e| format!("WebKitGTK 导航失败: {e}"))?;
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
                    .map_err(|e| format!("解析 WebKitGTK 返回结果失败: {e}"))?;
                if envelope.get("ok").and_then(|b| b.as_bool()) == Some(true) {
                    Ok(envelope.get("value").cloned().unwrap_or(Value::Null))
                } else {
                    Err(envelope
                        .get("error")
                        .and_then(|e| e.as_str())
                        .unwrap_or("WebKitGTK script execution failed")
                        .to_string())
                }
            } else {
                eval_async_promise(&wv, script, timeout_secs).await
            }
        }
        "cookies" => {
            let wv = ensure_bridge_window(&app)?;
            let list = read_forum_cookies_async(&wv).await?;
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
        }
        "setCookies" => {
            let wv = ensure_bridge_window(&app)?;
            let incoming = params
                .get("cookies")
                .and_then(|v| v.as_array())
                .cloned()
                .unwrap_or_default();

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

            if let Ok(existing) = read_forum_cookies_async(&wv).await {
                for c in existing {
                    if target_names.contains(c.name()) {
                        let _ = delete_forum_cookie_async(&wv, c).await;
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
                let _ = set_forum_cookie_async(&wv, ck).await;
            }
            Ok(json!({}))
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

            let existing = read_forum_cookies_async(&wv).await.unwrap_or_default();

            if mode == "clear" {
                for c in existing {
                    if matches!(c.name(), "_t" | "_forum_session" | "authentication_data") {
                        let _ = delete_forum_cookie_async(&wv, c).await;
                    }
                }
                return Ok(json!({ "cleared": true }));
            }

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
                    let _ = delete_forum_cookie_async(&wv, c).await;
                }
            }
            if fs_needs {
                for c in fs_variants {
                    let _ = delete_forum_cookie_async(&wv, c).await;
                }
            }
            if let Some(wt) = winner_t {
                let _ =
                    set_forum_cookie_async(&wv, build_host_only_forum_cookie("_t".to_string(), wt, true))
                        .await;
            }
            if let Some(wfs) = winner_fs {
                let _ = set_forum_cookie_async(
                    &wv,
                    build_host_only_forum_cookie("_forum_session".to_string(), wfs, false),
                )
                .await;
            }
            Ok(json!({ "swept": true }))
        }
        "deleteCfClearance" => {
            let wv = ensure_bridge_window(&app)?;
            let mut deleted: Vec<String> = Vec::new();
            if let Ok(existing) = read_forum_cookies_async(&wv).await {
                for c in existing {
                    if matches!(
                        c.name(),
                        "cf_clearance" | "__cf_bm" | "cf_chl_rc_ni" | "cf_chl_rc_i" | "cf_chl_rc_m"
                    ) {
                        if c.name() == "cf_clearance" {
                            let val = c.value().to_string();
                            if !val.is_empty() && !deleted.contains(&val) {
                                deleted.push(val);
                            }
                        }
                        let _ = delete_forum_cookie_async(&wv, c).await;
                    }
                }
            }
            Ok(json!({ "deleted": deleted }))
        }
        "clearCookies" | "deleteCookie" => {
            let wv = ensure_bridge_window(&app)?;
            let is_clear_all = method == "clearCookies";
            let target_name = params
                .get("name")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string();

            if let Ok(existing) = read_forum_cookies_async(&wv).await {
                for c in existing {
                    if is_clear_all || c.name() == target_name {
                        let _ = delete_forum_cookie_async(&wv, c).await;
                    }
                }
            }
            Ok(json!({}))
        }
        "fetchConnectHtml" => {
            let connect_url: Url = "https://connect.linux.do/"
                .parse()
                .map_err(|e| format!("Invalid Connect URL: {e}"))?;
            let probe = if let Some(existing) = app.get_webview_window(CONNECT_PROBE_LABEL) {
                let _ = existing.navigate(connect_url);
                existing
            } else {
                let blank_url: Url = "about:blank"
                    .parse()
                    .map_err(|e| format!("Invalid blank URL: {e}"))?;
                let built = configure_forum_webview(
                    WebviewWindowBuilder::new(&app, CONNECT_PROBE_LABEL, WebviewUrl::External(blank_url)),
                    &app,
                    false,
                )
                .title("Lindo Connect Probe")
                .inner_size(960.0, 720.0)
                .visible(false)
                .build()
                .map_err(|e| format!("初始化 Connect 探测窗口失败: {e}"))?;
                install_linux_webview_hooks(&built, &app, false);
                let _ = built.navigate(connect_url);
                built
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
        "setVerifyMask" => Ok(json!({})),
        _ => Err(format!("Unsupported bridge command: {method}")),
    }
}
