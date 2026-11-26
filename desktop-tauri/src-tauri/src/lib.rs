pub mod bridge;
pub mod crypto_rsa;
pub mod ech_desktop;
pub mod legacy;
#[cfg(target_os = "linux")]
pub mod linux_webview_bridge;
pub mod net_policy;
pub mod platform_bridge;
#[cfg(target_os = "windows")]
pub mod win_webview_bridge;

use base64::{engine::general_purpose::STANDARD as BASE64_STD, Engine as _};
use bridge::{
    BridgeEngine, ClearLocalCachesResult, ClipboardMediaItem, ClipboardMediaResult,
    DiagRuntimeSnapshot, ForumMediaPayload, QrLoginGrantDto, SiteHttpResponse,
};
use futures_util::StreamExt;
use legacy::{
    load_or_migrate_state, save_state, LocalDraftRecord, PersistedState, UserPreferences,
    UserProfile,
};
use serde::Serialize;
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};
use tauri::menu::{Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, DragDropEvent, Emitter, Manager, RunEvent, State, Theme, WindowEvent};
use tauri_plugin_opener::OpenerExt;

pub(crate) static EXPLICIT_QUIT_REQUESTED: AtomicBool = AtomicBool::new(false);
static DROPPED_FILE_ALLOWLIST: OnceLock<Mutex<HashMap<PathBuf, Instant>>> = OnceLock::new();

fn dropped_allowlist() -> &'static Mutex<HashMap<PathBuf, Instant>> {
    DROPPED_FILE_ALLOWLIST.get_or_init(|| Mutex::new(HashMap::new()))
}

fn record_os_dropped_paths(paths: &[PathBuf]) {
    let Ok(mut map) = dropped_allowlist().lock() else {
        return;
    };
    let now = Instant::now();
    map.retain(|_, exp| *exp > now);
    let exp = now + Duration::from_secs(30);
    for p in paths.iter().take(20) {
        map.insert(p.clone(), exp);
        if let Ok(canon) = std::fs::canonicalize(p) {
            map.insert(canon, exp);
        }
    }
    while map.len() > 64 {
        if let Some(oldest) = map
            .iter()
            .min_by_key(|(_, v)| **v)
            .map(|(k, _)| k.clone())
        {
            map.remove(&oldest);
        } else {
            break;
        }
    }
}

fn is_path_os_dropped(path: &Path) -> bool {
    let Ok(mut map) = dropped_allowlist().lock() else {
        return false;
    };
    let now = Instant::now();
    map.retain(|_, exp| *exp > now);
    if map.contains_key(path) {
        return true;
    }
    if let Ok(canon) = std::fs::canonicalize(path) {
        if map.contains_key(&canon) {
            return true;
        }
    }
    false
}

fn guess_mime_from_ext(ext: &str) -> &'static str {
    match ext {
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "gif" => "image/gif",
        "webp" => "image/webp",
        "svg" => "image/svg+xml",
        "avif" => "image/avif",
        "bmp" => "image/bmp",
        "tif" | "tiff" => "image/tiff",
        "heic" => "image/heic",
        "heif" => "image/heif",
        "pdf" => "application/pdf",
        "txt" => "text/plain",
        "md" => "text/markdown",
        "json" => "application/json",
        "zip" => "application/zip",
        "mp4" => "video/mp4",
        "webm" => "video/webm",
        "mov" => "video/quicktime",
        _ => "application/octet-stream",
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BootstrapPayload {
    pub is_authorized: bool,
    pub state: PersistedState,
    pub platform: String,
}

#[tauri::command]
fn set_app_cursor(pointer: bool) {
    bridge::set_app_cursor(pointer);
}

#[tauri::command]
async fn bootstrap_state(engine: State<'_, Arc<BridgeEngine>>) -> Result<BootstrapPayload, String> {
    bridge::enable_high_refresh_and_smooth_wheel(true);
    engine.spawn_warmup();
    let snapshot = { engine.state.lock().unwrap().clone() };
    let is_authorized = snapshot.current_user.is_some();
    let platform = if cfg!(target_os = "macos") {
        "macos"
    } else if cfg!(target_os = "windows") {
        "windows"
    } else {
        "linux"
    }
    .to_string();
    Ok(BootstrapPayload {
        is_authorized,
        state: snapshot,
        platform,
    })
}

#[tauri::command]
async fn site_request(
    app: AppHandle,
    engine: State<'_, Arc<BridgeEngine>>,
    url: String,
    method: String,
    form: Option<HashMap<String, String>>,
    headers: Option<HashMap<String, String>>,
) -> Result<SiteHttpResponse, String> {
    engine.request(&app, &url, &method, form, headers).await
}

#[tauri::command]
async fn site_upload(
    app: AppHandle,
    engine: State<'_, Arc<BridgeEngine>>,
    filename: String,
    mime: String,
    data_base64: String,
) -> Result<SiteHttpResponse, String> {
    engine.upload(&app, &filename, &mime, &data_base64).await
}

#[tauri::command]
async fn read_clipboard_media(
    engine: State<'_, Arc<BridgeEngine>>,
    only_file_urls: Option<bool>,
) -> Result<ClipboardMediaResult, String> {
    engine
        .read_clipboard_media(only_file_urls.unwrap_or(false))
        .await
}

#[tauri::command]
async fn read_dropped_files(
    engine: State<'_, Arc<BridgeEngine>>,
    paths: Vec<String>,
) -> Result<Vec<ClipboardMediaItem>, String> {
    const MAX_FILES: usize = 10;
    const MAX_BYTES: usize = 20 * 1024 * 1024;
    let mut out = Vec::new();
    for raw_path in paths.into_iter().take(MAX_FILES) {
        let p = PathBuf::from(&raw_path);
        if !is_path_os_dropped(&p) {
            continue;
        }
        let Ok(meta) = tokio::fs::metadata(&p).await else {
            continue;
        };
        if !meta.is_file() || meta.len() == 0 || meta.len() as usize > MAX_BYTES * 2 {
            continue;
        }
        let orig_name = p
            .file_name()
            .and_then(|s| s.to_str())
            .unwrap_or("attachment")
            .to_string();
        let ext = p
            .extension()
            .and_then(|s| s.to_str())
            .unwrap_or("")
            .to_ascii_lowercase();

        if matches!(ext.as_str(), "heic" | "heif" | "tif" | "tiff" | "bmp") {
            if let Ok(Some(png_b64)) = engine.convert_local_image_to_png(&raw_path).await {
                let stem = p
                    .file_stem()
                    .and_then(|s| s.to_str())
                    .filter(|s| !s.is_empty())
                    .unwrap_or("image");
                out.push(ClipboardMediaItem {
                    filename: format!("{stem}.png"),
                    mime: "image/png".to_string(),
                    data_base64: png_b64,
                });
                continue;
            }
        }
        if meta.len() as usize > MAX_BYTES {
            continue;
        }
        let Ok(bytes) = tokio::fs::read(&p).await else {
            continue;
        };
        if bytes.is_empty() || bytes.len() > MAX_BYTES {
            continue;
        }
        out.push(ClipboardMediaItem {
            filename: orig_name,
            mime: guess_mime_from_ext(&ext).to_string(),
            data_base64: BASE64_STD.encode(&bytes),
        });
    }
    Ok(out)
}

#[tauri::command]
async fn site_fetch_media(
    engine: State<'_, Arc<BridgeEngine>>,
    url: String,
) -> Result<ForumMediaPayload, String> {
    engine.fetch_media(&url).await
}

#[tauri::command]
async fn site_verify(
    app: AppHandle,
    engine: State<'_, Arc<BridgeEngine>>,
    login: bool,
    is_dark: bool,
    manual: Option<bool>,
    force_refresh: Option<bool>,
) -> Result<bool, String> {
    engine
        .verify_or_login_with_options(
            &app,
            login,
            is_dark,
            manual.unwrap_or(true),
            force_refresh.unwrap_or(false),
        )
        .await
}

#[tauri::command]
async fn start_browser_auth(
    app: AppHandle,
    engine: State<'_, Arc<BridgeEngine>>,
) -> Result<String, String> {
    let url = engine.start_browser_one_click_auth().await?;
    let _ = app.opener().open_url(&url, None::<&str>);
    Ok(url)
}

#[tauri::command]
async fn update_current_user(
    engine: State<'_, Arc<BridgeEngine>>,
    profile: UserProfile,
) -> Result<(), String> {
    {
        let mut st = engine.state.lock().unwrap();
        st.current_user = Some(profile.clone());
        st.expired_username = None;
        save_state(&st);
    }
    let _ = engine.sync_cookies_from_webkit(false).await;
    // CRITICAL: Do NOT emit `lindo://state-updated` here! `update_current_user` is invoked by JS `refreshCurrentUserSession()`
    // after React state is already updated; emitting `lindo://state-updated` here previously caused a recursive refresh loop!
    Ok(())
}

#[tauri::command]
async fn clear_expired_notice(engine: State<'_, Arc<BridgeEngine>>) -> Result<(), String> {
    let mut st = engine.state.lock().unwrap();
    st.expired_username = None;
    save_state(&st);
    Ok(())
}

#[tauri::command]
async fn switch_account(
    app: AppHandle,
    engine: State<'_, Arc<BridgeEngine>>,
    username: String,
) -> Result<bool, String> {
    engine.switch_saved_account(&app, &username).await
}

#[tauri::command]
async fn logout_account(
    app: AppHandle,
    engine: State<'_, Arc<BridgeEngine>>,
    remove_saved: bool,
) -> Result<(), String> {
    engine.logout(&app, remove_saved).await
}

#[tauri::command]
async fn remove_saved_account(
    app: AppHandle,
    engine: State<'_, Arc<BridgeEngine>>,
    username: String,
) -> Result<(), String> {
    engine.remove_saved_account(&app, &username).await
}

#[tauri::command]
async fn add_account_web_login(
    app: AppHandle,
    engine: State<'_, Arc<BridgeEngine>>,
    is_dark: bool,
) -> Result<bool, String> {
    engine.add_account_via_web(&app, is_dark).await
}

#[tauri::command]
async fn import_manual_credential(
    app: AppHandle,
    engine: State<'_, Arc<BridgeEngine>>,
    raw_input: String,
    is_dark: bool,
) -> Result<bool, String> {
    engine
        .import_manual_credential(&app, &raw_input, is_dark)
        .await
}

#[tauri::command]
async fn generate_qr_login(
    app: AppHandle,
    engine: State<'_, Arc<BridgeEngine>>,
) -> Result<QrLoginGrantDto, String> {
    engine.create_qr_login(&app).await
}

#[tauri::command]
async fn open_in_app_forum_panel(
    engine: State<'_, Arc<BridgeEngine>>,
    url: String,
    is_dark: bool,
) -> Result<(), String> {
    engine.open_forum_web_panel(&url, is_dark).await
}

#[tauri::command]
async fn fetch_connect_page_html(
    app: AppHandle,
    engine: State<'_, Arc<BridgeEngine>>,
    is_dark: Option<bool>,
) -> Result<String, String> {
    engine.fetch_connect_html(&app, is_dark.unwrap_or(true)).await
}

#[tauri::command]
async fn save_preferences(
    app: AppHandle,
    engine: State<'_, Arc<BridgeEngine>>,
    preferences: UserPreferences,
) -> Result<(), String> {
    let appearance = preferences.appearance.clone();
    let next_ech_mode = preferences.ech_routing_mode.clone();
    let ech_changed = {
        let mut st = engine.state.lock().unwrap();
        let changed = st.preferences.ech_routing_mode != next_ech_mode;
        st.preferences = preferences;
        save_state(&st);
        changed
    };
    if ech_changed {
        engine.on_ech_mode_switched(&app).await;
    } else {
        let snap = ech_desktop::refresh_status_snapshot(&next_ech_mode);
        ech_desktop::notify_ui_ech_updated(&app, &snap);
    }
    if let Some(w) = app.get_webview_window("main") {
        let theme_opt = match appearance.as_str() {
            "dark" => Some(Theme::Dark),
            "light" => Some(Theme::Light),
            _ => None,
        };
        let _ = w.set_theme(theme_opt);
    }
    Ok(())
}

#[tauri::command]
async fn record_read_floor(
    engine: State<'_, Arc<BridgeEngine>>,
    topic_id: i64,
    floor: i32,
) -> Result<(), String> {
    let mut st = engine.state.lock().unwrap();
    let key = topic_id.to_string();
    let prev = st.read_floors.get(&key).copied().unwrap_or(0);
    if floor > prev {
        st.read_floors.insert(key, floor);
        save_state(&st);
    }
    Ok(())
}

#[tauri::command]
async fn clear_read_history(engine: State<'_, Arc<BridgeEngine>>) -> Result<(), String> {
    let mut st = engine.state.lock().unwrap();
    st.read_floors.clear();
    save_state(&st);
    Ok(())
}

#[tauri::command]
async fn clear_local_caches(
    engine: State<'_, Arc<BridgeEngine>>,
) -> Result<ClearLocalCachesResult, String> {
    Ok(engine.clear_local_caches())
}

#[tauri::command]
async fn save_local_draft(
    engine: State<'_, Arc<BridgeEngine>>,
    draft: LocalDraftRecord,
) -> Result<(), String> {
    let mut st = engine.state.lock().unwrap();
    st.drafts.insert(draft.key.clone(), draft);
    save_state(&st);
    Ok(())
}

#[tauri::command]
async fn delete_local_draft(
    engine: State<'_, Arc<BridgeEngine>>,
    key: String,
) -> Result<(), String> {
    let mut st = engine.state.lock().unwrap();
    if st.drafts.remove(&key).is_some() {
        save_state(&st);
    }
    Ok(())
}

#[tauri::command]
async fn set_unread_badge(
    engine: State<'_, Arc<BridgeEngine>>,
    count: i32,
) -> Result<(), String> {
    {
        let mut st = engine.state.lock().unwrap();
        if st.unread_notifications != count {
            st.unread_notifications = count;
            save_state(&st);
        }
    }
    engine.set_dock_badge(count).await;
    Ok(())
}

const GITHUB_LATEST_RELEASE_API: &str =
    "https://api.github.com/repos/lucaslus/LinDo/releases/latest";
const GITHUB_RELEASES_PAGE: &str = "https://github.com/lucaslus/LinDo/releases";

#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct DesktopUpdateInfo {
    pub current_version: String,
    pub latest_version: String,
    pub has_update: bool,
    pub arch: String,
    pub zip_url: String,
    pub dmg_url: String,
    pub sha256: String,
    pub updated_at: String,
    pub notes: Vec<String>,
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
struct UpdateProgressEvent {
    stage: String,
    downloaded_bytes: u64,
    total_bytes: u64,
    percent: u8,
    message: String,
}

fn parse_semver_tuple(v: &str) -> (u32, u32, u32) {
    let clean = v.trim().trim_start_matches(['v', 'V']);
    let mut parts = clean.split('.').map(|s| {
        s.chars()
            .take_while(|c| c.is_ascii_digit())
            .collect::<String>()
            .parse::<u32>()
            .unwrap_or(0)
    });
    (
        parts.next().unwrap_or(0),
        parts.next().unwrap_or(0),
        parts.next().unwrap_or(0),
    )
}

fn strip_markdown_links_and_urls(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    let chars: Vec<char> = input.chars().collect();
    let mut i = 0;
    while i < chars.len() {
        if chars[i] == '[' {
            if let Some(close_bracket) = chars[i + 1..].iter().position(|&c| c == ']') {
                let cb = i + 1 + close_bracket;
                if cb + 1 < chars.len() && chars[cb + 1] == '(' {
                    if let Some(close_paren) = chars[cb + 2..].iter().position(|&c| c == ')') {
                        let cp = cb + 2 + close_paren;
                        let url_part: String = chars[cb + 2..cp].iter().collect();
                        if url_part.starts_with("http://") || url_part.starts_with("https://") {
                            for &ch in &chars[i + 1..cb] {
                                out.push(ch);
                            }
                            i = cp + 1;
                            continue;
                        }
                    }
                }
            }
        }
        out.push(chars[i]);
        i += 1;
    }
    out.split_whitespace()
        .filter(|tok| !tok.starts_with("http://") && !tok.starts_with("https://"))
        .collect::<Vec<_>>()
        .join(" ")
}

fn extract_release_notes_from_markdown(body: &str) -> Vec<String> {
    let mut notes: Vec<String> = Vec::new();
    for raw_line in body.lines() {
        let trimmed = raw_line.trim();
        let lower = trimmed.to_ascii_lowercase();
        if lower.contains("download based on your os") || trimmed.contains("按系统选择下载") {
            break;
        }
        if trimmed.is_empty()
            || trimmed.starts_with('#')
            || trimmed.starts_with('|')
            || trimmed.starts_with('<')
            || trimmed.starts_with("![")
            || trimmed.starts_with("[![")
            || trimmed.starts_with("```")
            || trimmed.starts_with("---")
            || trimmed.starts_with("***")
            || lower.contains("full changelog")
            || trimmed.contains("完整变更记录")
            || lower.contains("what's changed")
            || lower.contains("new contributors")
            || lower.contains("made their first contribution")
        {
            continue;
        }
        let mut cleaned = trimmed
            .trim_start_matches("- ")
            .trim_start_matches("* ")
            .trim_start_matches("• ")
            .trim()
            .to_string();
        if let Some(dot_idx) = cleaned.find(". ") {
            if cleaned[..dot_idx].chars().all(|c| c.is_ascii_digit()) {
                cleaned = cleaned[dot_idx + 2..].trim().to_string();
            }
        }
        if let Some(by_idx) = cleaned.to_ascii_lowercase().find(" by @") {
            if cleaned[by_idx..].contains("http://") || cleaned[by_idx..].contains("https://") {
                cleaned.truncate(by_idx);
            }
        }
        cleaned = strip_markdown_links_and_urls(&cleaned)
            .replace("**", "")
            .replace("__", "")
            .replace('`', "");
        let final_line = cleaned
            .trim()
            .trim_end_matches([':', '：', '-', '·'])
            .trim()
            .to_string();
        if final_line.is_empty()
            || final_line
                .to_ascii_lowercase()
                .starts_with("chore(release)")
        {
            continue;
        }
        if !notes.contains(&final_line) {
            notes.push(final_line);
            if notes.len() >= 6 {
                break;
            }
        }
    }
    notes
}

fn extract_sha256_from_sums(sums_raw: &str, asset_url_or_name: &str) -> Option<String> {
    let target_raw = asset_url_or_name
        .trim()
        .split('?')
        .next()
        .unwrap_or("")
        .rsplit('/')
        .next()
        .unwrap_or("")
        .trim();
    if target_raw.is_empty() {
        return None;
    }
    for raw_line in sums_raw.lines() {
        let line = raw_line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let Some(ws_idx) = line.find(char::is_whitespace) else {
            continue;
        };
        if ws_idx != 64 {
            continue;
        }
        let hash = &line[..64];
        if !hash.chars().all(|c| c.is_ascii_hexdigit()) {
            continue;
        }
        let file_token = line[ws_idx..]
            .trim()
            .trim_start_matches('*')
            .trim_start_matches("./")
            .rsplit('/')
            .next()
            .unwrap_or("");
        if file_token.eq_ignore_ascii_case(target_raw) {
            return Some(hash.to_ascii_lowercase());
        }
    }
    None
}

fn parse_github_release_for_desktop(
    release: &serde_json::Value,
    manifest_override: Option<&serde_json::Value>,
    current_version: &str,
    target_os: &str,
    arch: &str,
) -> DesktopUpdateInfo {
    parse_github_release_for_desktop_with_sums(
        release,
        manifest_override,
        None,
        current_version,
        target_os,
        arch,
    )
}

fn parse_github_release_for_desktop_with_sums(
    release: &serde_json::Value,
    manifest_override: Option<&serde_json::Value>,
    sha256_sums_raw: Option<&str>,
    current_version: &str,
    target_os: &str,
    arch: &str,
) -> DesktopUpdateInfo {
    let tag_name = release
        .get("tag_name")
        .and_then(|v| v.as_str())
        .unwrap_or(current_version)
        .trim();
    let published_at = release
        .get("published_at")
        .and_then(|v| v.as_str())
        .unwrap_or("");
    let default_date = published_at.get(0..10).unwrap_or(published_at).to_string();
    let body_md = release.get("body").and_then(|v| v.as_str()).unwrap_or("");

    let mut latest_version = tag_name.trim_start_matches(['v', 'V']).to_string();
    let mut zip_url = String::new();
    let mut dmg_url = String::new();
    let mut sha256 = String::new();
    let mut fallback_sha256 = String::new();
    let mut updated_at = default_date;
    let mut notes = extract_release_notes_from_markdown(body_md);

    let is_windows = target_os.eq_ignore_ascii_case("windows");
    let is_linux = target_os.eq_ignore_ascii_case("linux");

    if let Some(assets) = release.get("assets").and_then(|v| v.as_array()) {
        for asset in assets {
            let name = asset
                .get("name")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_ascii_lowercase();
            let dl_url = asset
                .get("browser_download_url")
                .and_then(|v| v.as_str())
                .unwrap_or("");
            if dl_url.is_empty() {
                continue;
            }
            let digest = asset
                .get("digest")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .strip_prefix("sha256:")
                .unwrap_or("")
                .to_string();

            if is_windows {
                let matches_win_arch = if arch == "arm64" {
                    name.contains("arm64") || name.contains("aarch64")
                } else {
                    !name.contains("arm64") && !name.contains("aarch64")
                };
                if !matches_win_arch {
                    continue;
                }
                if name.ends_with(".exe") && zip_url.is_empty() {
                    zip_url = dl_url.to_string();
                    if !digest.is_empty() {
                        sha256 = digest;
                    }
                } else if name.ends_with(".msi") && dmg_url.is_empty() {
                    dmg_url = dl_url.to_string();
                    if !digest.is_empty() {
                        fallback_sha256 = digest;
                    }
                }
                continue;
            }

            if is_linux {
                let matches_linux_arch = if arch == "arm64" {
                    name.contains("arm64") || name.contains("aarch64")
                } else {
                    name.contains("x86_64") || name.contains("x64") || name.contains("amd64")
                };
                if !matches_linux_arch {
                    continue;
                }
                if (name.ends_with(".pkg.tar.zst") || name.ends_with(".appimage"))
                    && zip_url.is_empty()
                {
                    zip_url = dl_url.to_string();
                    if !digest.is_empty() {
                        sha256 = digest;
                    }
                } else if (name.ends_with(".deb")
                    || name.ends_with(".rpm")
                    || (name.contains("linux") && name.ends_with(".tar.gz")))
                    && dmg_url.is_empty()
                {
                    dmg_url = dl_url.to_string();
                    if !digest.is_empty() {
                        fallback_sha256 = digest;
                    }
                }
                continue;
            }

            // macOS asset matching (exclude Windows/Linux/Android assets)
            if name.contains("windows")
                || name.contains("win-")
                || name.contains("linux")
                || name.ends_with(".exe")
                || name.ends_with(".msi")
                || name.ends_with(".apk")
                || name.ends_with(".deb")
                || name.ends_with(".rpm")
                || name.ends_with(".appimage")
                || name.ends_with(".pkg.tar.zst")
            {
                continue;
            }
            let matches_arch = if arch == "x64" {
                name.contains("x64") || name.contains("x86_64") || name.contains("intel")
            } else {
                name.contains("arm64") || name.contains("aarch64")
            };
            if !matches_arch {
                continue;
            }
            if name.ends_with(".zip") && zip_url.is_empty() {
                zip_url = dl_url.to_string();
                if !digest.is_empty() {
                    sha256 = digest;
                }
            } else if name.ends_with(".dmg") && dmg_url.is_empty() {
                dmg_url = dl_url.to_string();
                if fallback_sha256.is_empty() && !digest.is_empty() {
                    fallback_sha256 = digest;
                }
            }
        }
    }

    if (is_windows || is_linux) && zip_url.is_empty() && !dmg_url.is_empty() {
        zip_url = dmg_url.clone();
        if sha256.is_empty() && !fallback_sha256.is_empty() {
            sha256 = fallback_sha256;
        }
    } else if !(is_windows || is_linux) && zip_url.is_empty() && !fallback_sha256.is_empty() {
        sha256 = fallback_sha256;
    }

    // Optional attached `latest.json` inside GitHub Release assets can refine desktop version, notes, or SHA-256
    if let Some(desk) = manifest_override.and_then(|m| m.get("desktop")) {
        if let Some(v) = desk.get("version").and_then(|x| x.as_str()) {
            let trimmed = v.trim().trim_start_matches(['v', 'V']);
            if !trimmed.is_empty() {
                latest_version = trimmed.to_string();
            }
        }
        let (zip_key, dmg_key, sha_key) = if is_windows {
            ("winSetupUrl", "winMsiUrl", "winSetupSha256")
        } else if is_linux {
            ("linuxPkgUrl", "linuxDebUrl", "linuxPkgSha256")
        } else if arch == "x64" {
            ("x64ZipUrl", "x64DmgUrl", "x64ZipSha256")
        } else {
            ("arm64ZipUrl", "arm64DmgUrl", "arm64ZipSha256")
        };
        if zip_url.is_empty() {
            if let Some(u) = desk.get(zip_key).and_then(|x| x.as_str()) {
                if u.starts_with("https://github.com/") {
                    zip_url = u.to_string();
                }
            }
        }
        if dmg_url.is_empty() {
            if let Some(u) = desk.get(dmg_key).and_then(|x| x.as_str()) {
                if u.starts_with("https://github.com/") {
                    dmg_url = u.to_string();
                }
            }
        }
        if let Some(s) = desk.get(sha_key).and_then(|x| x.as_str()) {
            if !s.trim().is_empty() {
                sha256 = s.trim().to_string();
            }
        }
        if let Some(arr) = desk.get("notes").and_then(|x| x.as_array()) {
            let parsed: Vec<String> = arr
                .iter()
                .filter_map(|x| x.as_str().map(|s| s.trim().to_string()))
                .filter(|s| !s.is_empty())
                .collect();
            if !parsed.is_empty() {
                notes = parsed;
            }
        }
        if let Some(d) = manifest_override
            .and_then(|m| m.get("updatedAt"))
            .and_then(|x| x.as_str())
        {
            if !d.trim().is_empty() {
                updated_at = d.trim().to_string();
            }
        }
    }

    if sha256.is_empty() {
        if let Some(sums) = sha256_sums_raw {
            let primary_target = if !zip_url.is_empty() {
                zip_url.as_str()
            } else {
                dmg_url.as_str()
            };
            if let Some(found_sha) = extract_sha256_from_sums(sums, primary_target) {
                sha256 = found_sha;
            }
        }
    }

    let has_pkg = !zip_url.is_empty() || !dmg_url.is_empty();
    let has_update =
        has_pkg && parse_semver_tuple(&latest_version) > parse_semver_tuple(current_version);

    if dmg_url.is_empty() {
        dmg_url = if !zip_url.is_empty() {
            zip_url.clone()
        } else {
            GITHUB_RELEASES_PAGE.to_string()
        };
    }

    let arch_tag = if is_windows {
        format!("windows-{arch}")
    } else if is_linux {
        format!("linux-{arch}")
    } else {
        arch.to_string()
    };

    DesktopUpdateInfo {
        current_version: current_version.to_string(),
        latest_version,
        has_update,
        arch: arch_tag,
        zip_url,
        dmg_url,
        sha256,
        updated_at,
        notes,
    }
}

fn build_update_http_client(timeout_secs: u64) -> Result<reqwest::Client, String> {
    reqwest::Client::builder()
        .user_agent(concat!("Lindo-Desktop/", env!("CARGO_PKG_VERSION")))
        .connect_timeout(std::time::Duration::from_secs(10))
        .timeout(std::time::Duration::from_secs(timeout_secs))
        .build()
        .map_err(|e| format!("初始化网络客户端失败: {e}"))
}

async fn stream_download_and_verify_sha256(
    url: &str,
    dest_path: &std::path::Path,
    expected_sha256: &str,
    mut on_progress: impl FnMut(u64, u64),
) -> Result<(u64, String), String> {
    let client = build_update_http_client(600)?;
    let resp = client
        .get(url)
        .header("Cache-Control", "no-cache")
        .send()
        .await
        .map_err(|e| format!("无法连接安装包下载源: {e}"))?;

    let status = resp.status();
    if !status.is_success() {
        return Err(format!("安装包下载失败 (HTTP {})", status.as_u16()));
    }

    let total_bytes = resp.content_length().unwrap_or(0);
    on_progress(0, total_bytes);

    let mut file = std::fs::File::create(dest_path)
        .map_err(|e| format!("无法创建本地安装包文件: {e}"))?;
    let mut hasher = Sha256::new();
    let mut downloaded: u64 = 0;
    let mut last_emit = std::time::Instant::now();
    let mut stream = resp.bytes_stream();

    while let Some(chunk_res) = stream.next().await {
        let chunk = chunk_res.map_err(|e| format!("下载数据包中断: {e}"))?;
        std::io::Write::write_all(&mut file, &chunk)
            .map_err(|e| format!("写入本地安装包失败: {e}"))?;
        hasher.update(&chunk);
        downloaded += chunk.len() as u64;

        if last_emit.elapsed() >= std::time::Duration::from_millis(80) {
            on_progress(downloaded, total_bytes);
            last_emit = std::time::Instant::now();
        }
    }
    std::io::Write::flush(&mut file).map_err(|e| format!("刷新安装包缓存失败: {e}"))?;
    drop(file);
    on_progress(downloaded, total_bytes.max(downloaded));

    let digest_bytes = hasher.finalize();
    let actual_hex: String = digest_bytes.iter().map(|b| format!("{b:02x}")).collect();

    let expected_clean = expected_sha256.trim().to_ascii_lowercase();
    if !expected_clean.is_empty() && actual_hex != expected_clean {
        let _ = std::fs::remove_file(dest_path);
        return Err("安装包 SHA-256 完整性校验不一致，已安全拦截安装".to_string());
    }

    Ok((downloaded, actual_hex))
}

#[tauri::command]
async fn check_app_update() -> Result<DesktopUpdateInfo, String> {
    let current_version = env!("CARGO_PKG_VERSION").to_string();
    let target_os = if cfg!(target_os = "windows") {
        "windows"
    } else if cfg!(target_os = "linux") {
        "linux"
    } else {
        "macos"
    };
    let arch = if cfg!(target_arch = "x86_64") {
        "x64"
    } else {
        "arm64"
    };

    let ts = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or(0);
    let req_url = format!("{GITHUB_LATEST_RELEASE_API}?_t={ts}");

    let client = build_update_http_client(12)?;
    let resp = client
        .get(&req_url)
        .header("Accept", "application/vnd.github+json")
        .header("Cache-Control", "no-cache")
        .send()
        .await
        .map_err(|e| format!("连接 GitHub Release 失败，请检查网络后重试: {e}"))?;

    let http_code = resp.status().as_u16();

    // HTTP 404 on `/releases/latest` means the repository has not published any Release yet -> cleanly report up-to-date!
    if http_code == 404 {
        let arch_tag = if target_os == "windows" {
            format!("windows-{arch}")
        } else if target_os == "linux" {
            format!("linux-{arch}")
        } else {
            arch.to_string()
        };
        return Ok(DesktopUpdateInfo {
            current_version: current_version.clone(),
            latest_version: current_version,
            has_update: false,
            arch: arch_tag,
            zip_url: String::new(),
            dmg_url: GITHUB_RELEASES_PAGE.to_string(),
            sha256: String::new(),
            updated_at: String::new(),
            notes: Vec::new(),
        });
    }

    if !(200..300).contains(&http_code) {
        return Err(format!(
            "GitHub Release 接口响应异常 (HTTP {http_code})，请稍后再试"
        ));
    }

    let release: serde_json::Value = resp
        .json()
        .await
        .map_err(|_| "GitHub Release 数据解析失败".to_string())?;

    // Check if an optional `latest.json` asset is attached to the GitHub Release
    let mut manifest_json: Option<serde_json::Value> = None;
    if let Some(assets) = release.get("assets").and_then(|v| v.as_array()) {
        if let Some(manifest_asset) = assets.iter().find(|a| {
            a.get("name")
                .and_then(|n| n.as_str())
                .map(|n| n.eq_ignore_ascii_case("latest.json"))
                .unwrap_or(false)
        }) {
            if let Some(dl_url) = manifest_asset
                .get("browser_download_url")
                .and_then(|u| u.as_str())
            {
                if let Ok(m_resp) = client
                    .get(dl_url)
                    .header("Cache-Control", "no-cache")
                    .send()
                    .await
                {
                    if m_resp.status().is_success() {
                        manifest_json = m_resp.json::<serde_json::Value>().await.ok();
                    }
                }
            }
        }
    }

    let mut parsed = parse_github_release_for_desktop(
        &release,
        manifest_json.as_ref(),
        &current_version,
        target_os,
        arch,
    );

    if parsed.sha256.is_empty() && (!parsed.zip_url.is_empty() || !parsed.dmg_url.is_empty()) {
        if let Some(assets) = release.get("assets").and_then(|v| v.as_array()) {
            if let Some(sums_asset) = assets.iter().find(|a| {
                a.get("name")
                    .and_then(|n| n.as_str())
                    .map(|n| n.eq_ignore_ascii_case("SHA256SUMS.txt"))
                    .unwrap_or(false)
            }) {
                if let Some(dl_url) = sums_asset
                    .get("browser_download_url")
                    .and_then(|u| u.as_str())
                {
                    if let Ok(s_resp) = client
                        .get(dl_url)
                        .header("Cache-Control", "no-cache")
                        .send()
                        .await
                    {
                        if s_resp.status().is_success() {
                            if let Ok(sums_raw) = s_resp.text().await {
                                parsed = parse_github_release_for_desktop_with_sums(
                                    &release,
                                    manifest_json.as_ref(),
                                    Some(&sums_raw),
                                    &current_version,
                                    target_os,
                                    arch,
                                );
                            }
                        }
                    }
                }
            }
        }
    }

    if !parsed.has_update {
        if let Some(base_dir) = dirs::home_dir()
            .or_else(dirs::cache_dir)
            .map(|d| d.join(".lindo").join("updates"))
        {
            let _ = std::fs::remove_dir_all(base_dir);
        }
    }

    Ok(parsed)
}

#[tauri::command]
async fn get_diag_runtime_snapshot(
    engine: State<'_, Arc<BridgeEngine>>,
) -> Result<DiagRuntimeSnapshot, String> {
    Ok(engine.diag_runtime_snapshot())
}

#[tauri::command]
async fn get_ech_status(
    engine: State<'_, Arc<BridgeEngine>>,
) -> Result<ech_desktop::EchDesktopSnapshot, String> {
    let mode_pref = {
        let st = engine.state.lock().unwrap();
        st.preferences.ech_routing_mode.clone()
    };
    Ok(ech_desktop::refresh_status_snapshot(&mode_pref))
}

#[tauri::command]
async fn export_diagnostic_log(file_name: String, content: String) -> Result<String, String> {
    let safe_name: String = file_name
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | '.') {
                c
            } else {
                '_'
            }
        })
        .collect();
    let final_name = if safe_name.ends_with(".log") || safe_name.ends_with(".txt") {
        safe_name
    } else {
        format!("{safe_name}.log")
    };
    let downloads_dir = dirs::download_dir()
        .or_else(|| dirs::home_dir().map(|h| h.join("Downloads")))
        .ok_or_else(|| "无法定位下载目录".to_string())?;
    std::fs::create_dir_all(&downloads_dir).map_err(|e| format!("无法创建导出目录: {e}"))?;
    let target_path = downloads_dir.join(&final_name);
    std::fs::write(&target_path, content.as_bytes())
        .map_err(|e| format!("写入诊断日志失败: {e}"))?;
    #[cfg(target_os = "macos")]
    {
        let _ = tokio::process::Command::new("/usr/bin/open")
            .args(["-R", target_path.to_str().unwrap_or("")])
            .status()
            .await;
    }
    #[cfg(target_os = "windows")]
    {
        if let Some(p) = target_path.to_str() {
            let _ = std::process::Command::new("explorer.exe")
                .arg(format!("/select,{p}"))
                .spawn();
        }
    }
    Ok(target_path.to_string_lossy().to_string())
}

#[cfg(target_os = "macos")]
fn find_running_macos_app_bundle() -> Option<std::path::PathBuf> {
    let exe = std::env::current_exe().ok()?;
    for anc in exe.ancestors() {
        if anc.extension().and_then(|e| e.to_str()) == Some("app") {
            return Some(anc.to_path_buf());
        }
    }
    None
}

#[tauri::command]
async fn download_and_install_update(
    app: AppHandle,
    zip_url: String,
    dmg_url: String,
    expected_sha256: String,
) -> Result<String, String> {
    let target_url = if !zip_url.trim().is_empty() {
        zip_url.trim().to_string()
    } else if !dmg_url.trim().is_empty() {
        dmg_url.trim().to_string()
    } else {
        return Err("缺少有效更新包下载地址".to_string());
    };

    let lower_url = target_url
        .split('?')
        .next()
        .unwrap_or(&target_url)
        .to_ascii_lowercase();
    let pkg_filename = if lower_url.ends_with(".exe") {
        "Lindo-update-setup.exe"
    } else if lower_url.ends_with(".msi") {
        "Lindo-update.msi"
    } else if lower_url.ends_with(".pkg.tar.zst") {
        "Lindo-update.pkg.tar.zst"
    } else if lower_url.ends_with(".appimage") {
        "Lindo-update.AppImage"
    } else if lower_url.ends_with(".deb") {
        "Lindo-update.deb"
    } else if lower_url.ends_with(".rpm") {
        "Lindo-update.rpm"
    } else if lower_url.ends_with(".tar.gz") {
        "Lindo-update.tar.gz"
    } else if lower_url.ends_with(".zip") {
        "Lindo-update.zip"
    } else {
        "Lindo-update.dmg"
    };

    let base_dir = dirs::home_dir()
        .or_else(dirs::cache_dir)
        .ok_or_else(|| "无法定位本地更新缓存目录".to_string())?
        .join(".lindo")
        .join("updates");
    let _ = std::fs::remove_dir_all(&base_dir);
    std::fs::create_dir_all(&base_dir).map_err(|e| format!("无法创建更新缓存目录: {e}"))?;

    let pkg_path = base_dir.join(pkg_filename);

    let app_progress = app.clone();
    #[cfg_attr(not(target_os = "macos"), allow(unused_variables))]
    let (downloaded_bytes, _) = stream_download_and_verify_sha256(
        &target_url,
        &pkg_path,
        &expected_sha256,
        move |downloaded, total| {
            let pct = if total > 0 {
                ((downloaded.saturating_mul(94)) / total).clamp(1, 94) as u8
            } else if downloaded > 0 {
                55
            } else {
                2
            };
            let _ = app_progress.emit(
                "lindo://update-progress",
                UpdateProgressEvent {
                    stage: "downloading".to_string(),
                    downloaded_bytes: downloaded,
                    total_bytes: total,
                    percent: pct,
                    message: "正在下载并校验新版本安装包...".to_string(),
                },
            );
        },
    )
    .await?;

    #[cfg(target_os = "windows")]
    {
        let _ = app.emit(
            "lindo://update-progress",
            UpdateProgressEvent {
                stage: "installing".to_string(),
                downloaded_bytes,
                total_bytes: downloaded_bytes,
                percent: 100,
                message: "完整性校验通过，正在启动安装向导...".to_string(),
            },
        );
        if lower_url.ends_with(".msi") {
            std::process::Command::new("msiexec")
                .args(["/i", pkg_path.to_str().unwrap_or("")])
                .spawn()
                .map_err(|e| format!("启动 MSI 安装程序失败: {e}"))?;
        } else {
            std::process::Command::new(&pkg_path)
                .spawn()
                .map_err(|e| format!("启动 Windows 安装程序失败: {e}"))?;
        }
        tokio::time::sleep(std::time::Duration::from_millis(320)).await;
        EXPLICIT_QUIT_REQUESTED.store(true, Ordering::SeqCst);
        app.exit(0);
        return Ok("launched_windows_installer".to_string());
    }

    #[cfg(target_os = "macos")]
    {
        if !lower_url.ends_with(".zip") {
            let _ = tokio::process::Command::new("/usr/bin/open")
                .arg(&pkg_path)
                .status()
                .await;
            return Ok("opened_dmg".to_string());
        }

        let _ = app.emit(
            "lindo://update-progress",
            UpdateProgressEvent {
                stage: "installing".to_string(),
                downloaded_bytes,
                total_bytes: downloaded_bytes,
                percent: 98,
                message: "正在解压并安装新版本...".to_string(),
            },
        );

        let extract_dir = base_dir.join("extracted");
        std::fs::create_dir_all(&extract_dir).map_err(|e| format!("无法创建解压目录: {e}"))?;
        let ditto_status = tokio::process::Command::new("/usr/bin/ditto")
            .args([
                "-x",
                "-k",
                pkg_path.to_str().unwrap_or(""),
                extract_dir.to_str().unwrap_or(""),
            ])
            .status()
            .await
            .map_err(|e| format!("解压安装包失败: {e}"))?;
        if !ditto_status.success() {
            return Err("解压 Lindo.app 失败".to_string());
        }

        let extracted_app = extract_dir.join("Lindo.app");
        if !extracted_app.exists() {
            return Err("更新包内未找到 Lindo.app".to_string());
        }
        let _ = tokio::process::Command::new("/usr/bin/xattr")
            .args(["-cr", extracted_app.to_str().unwrap_or("")])
            .status()
            .await;

        if let Some(target_app) = find_running_macos_app_bundle() {
            let target_str = target_app.to_string_lossy().to_string();
            let parent_writable = target_app
                .parent()
                .and_then(|p| std::fs::metadata(p).ok())
                .map(|m| !m.permissions().readonly())
                .unwrap_or(false);

            if !target_str.starts_with("/Volumes/") && parent_writable {
                let _ = app.emit(
                    "lindo://update-progress",
                    UpdateProgressEvent {
                        stage: "restarting".to_string(),
                        downloaded_bytes,
                        total_bytes: downloaded_bytes,
                        percent: 100,
                        message: "更新完成，正在重启 Lindo...".to_string(),
                    },
                );
                let script = r#"sleep 0.45; rm -rf "$1" && mv "$2" "$1" && /usr/bin/xattr -cr "$1" >/dev/null 2>&1 || true; /usr/bin/open -n "$1""#;
                let _ = std::process::Command::new("/bin/sh")
                    .args([
                        "-c",
                        script,
                        "lindo-updater",
                        &target_str,
                        extracted_app.to_str().unwrap_or(""),
                    ])
                    .spawn();
                tokio::time::sleep(std::time::Duration::from_millis(220)).await;
                std::process::exit(0);
            }
        }

        let apps_target = std::path::PathBuf::from("/Applications/Lindo.app");
        let dest_app = if std::fs::metadata("/Applications")
            .map(|m| !m.permissions().readonly())
            .unwrap_or(false)
        {
            let _ = std::fs::remove_dir_all(&apps_target);
            if std::fs::rename(&extracted_app, &apps_target).is_ok() {
                apps_target
            } else {
                extracted_app
            }
        } else {
            extracted_app
        };
        let _ = tokio::process::Command::new("/usr/bin/open")
            .args(["-R", dest_app.to_str().unwrap_or("")])
            .status()
            .await;
        let _ = app.emit(
            "lindo://update-progress",
            UpdateProgressEvent {
                stage: "done".to_string(),
                downloaded_bytes,
                total_bytes: downloaded_bytes,
                percent: 100,
                message: "已下载并准备就绪".to_string(),
            },
        );
        return Ok("revealed_in_finder".to_string());
    }

    #[cfg(not(any(target_os = "macos", target_os = "windows")))]
    {
        let dest_path = if let Some(dl_dir) = dirs::download_dir()
            .or_else(|| dirs::home_dir().map(|h| h.join("Downloads")))
        {
            let _ = std::fs::create_dir_all(&dl_dir);
            let out_name = target_url
                .split('?')
                .next()
                .unwrap_or(&target_url)
                .rsplit('/')
                .next()
                .filter(|s| !s.is_empty())
                .unwrap_or(pkg_filename);
            let out_path = dl_dir.join(out_name);
            if std::fs::copy(&pkg_path, &out_path).is_ok() {
                out_path
            } else {
                pkg_path.clone()
            }
        } else {
            pkg_path.clone()
        };
        #[cfg(unix)]
        if lower_url.ends_with(".appimage") {
            use std::os::unix::fs::PermissionsExt;
            if let Ok(meta) = std::fs::metadata(&dest_path) {
                let mut perms = meta.permissions();
                perms.set_mode(0o755);
                let _ = std::fs::set_permissions(&dest_path, perms);
            }
        }
        let _ = app.emit(
            "lindo://update-progress",
            UpdateProgressEvent {
                stage: "done".to_string(),
                downloaded_bytes,
                total_bytes: downloaded_bytes,
                percent: 100,
                message: format!("已下载并校验完成：{}", dest_path.display()),
            },
        );
        let _ = app.opener().reveal_item_in_dir(&dest_path);
        Ok("downloaded_linux_package".to_string())
    }
}

fn terminate_app_cleanly(app: &AppHandle) {
    EXPLICIT_QUIT_REQUESTED.store(true, Ordering::SeqCst);
    lindo_ech::stop_gateway();
    app.exit(0);
    #[cfg(target_os = "linux")]
    std::process::exit(0);
}

#[tauri::command]
fn quit_app(app: AppHandle) {
    terminate_app_cleanly(&app);
}

fn focus_main_window(app: &AppHandle) {
    #[cfg(target_os = "macos")]
    {
        let _ = app.show();
    }
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.unminimize();
        let _ = w.show();
        let _ = w.set_focus();
    }
}

pub fn run() {
    let initial_state = load_or_migrate_state();
    let engine = BridgeEngine::new(initial_state);

    let builder = tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_single_instance::init(|app, argv, _cwd| {
            focus_main_window(app);
            for arg in argv {
                let lower = arg.to_ascii_lowercase();
                if lower.starts_with("discourse://auth_redirect")
                    || lower.starts_with("lindo://auth_redirect")
                    || lower.starts_with("lindo://qr-login")
                    || lower.starts_with("fluxdo://qr-login")
                {
                    let app_clone = app.clone();
                    tauri::async_runtime::spawn(async move {
                        if let Some(eng) = app_clone.try_state::<Arc<BridgeEngine>>() {
                            #[cfg(target_os = "linux")]
                            if let Err(err) = eng.handle_auth_redirect(&app_clone, &arg).await {
                                let _ = app_clone.emit("lindo://auth-completing", false);
                                let _ = app_clone.emit("lindo://auth-notice", err);
                            }
                            #[cfg(not(target_os = "linux"))]
                            {
                                let _ = eng.handle_auth_redirect(&app_clone, &arg).await;
                            }
                        }
                    });
                }
            }
        }))
        .manage(engine)
        .setup(|app| {
            BridgeEngine::bind_app_handle(app.handle());
            bridge::enable_high_refresh_and_smooth_wheel(false);
            if let Some(main_win) = app.get_webview_window("main") {
                let init_appearance = {
                    app.try_state::<Arc<BridgeEngine>>()
                        .map(|e| e.state.lock().unwrap().preferences.appearance.clone())
                        .unwrap_or_else(|| "dark".to_string())
                };
                let theme_opt = match init_appearance.as_str() {
                    "dark" => Some(Theme::Dark),
                    "light" => Some(Theme::Light),
                    _ => None,
                };
                let _ = main_win.set_theme(theme_opt);
            }

            // Tray menu setup
            let open_item = MenuItem::with_id(app, "open_main", "打开 Lindo", true, None::<&str>)?;
            let new_topic_item =
                MenuItem::with_id(app, "new_topic", "发布新话题", true, None::<&str>)?;
            let refresh_item =
                MenuItem::with_id(app, "refresh_feed", "刷新动态", true, None::<&str>)?;
            let sep = PredefinedMenuItem::separator(app)?;
            let quit_item = MenuItem::with_id(app, "quit_app", "退出 Lindo", true, None::<&str>)?;
            let tray_menu =
                Menu::with_items(app, &[&open_item, &new_topic_item, &refresh_item, &sep, &quit_item])?;

            #[cfg(target_os = "macos")]
            let tray_icon_img = tauri::image::Image::from_bytes(include_bytes!("../icons/tray.png"))
                .ok()
                .or_else(|| app.default_window_icon().cloned());
            #[cfg(not(target_os = "macos"))]
            let tray_icon_img = app.default_window_icon().cloned();

            if let Some(icon) = tray_icon_img {
                let _ = TrayIconBuilder::with_id("main_tray")
                    .icon(icon)
                    .icon_as_template(cfg!(target_os = "macos"))
                    .tooltip("Lindo — Linux.do 社区客户端")
                    .menu(&tray_menu)
                    .show_menu_on_left_click(false)
                    .on_menu_event(|app, event| match event.id.as_ref() {
                        "open_main" => focus_main_window(app),
                        "new_topic" => {
                            focus_main_window(app);
                            let _ = app.emit("lindo://tray-command", "new_topic");
                        }
                        "refresh_feed" => {
                            focus_main_window(app);
                            let _ = app.emit("lindo://tray-command", "refresh");
                        }
                        "quit_app" => {
                            #[cfg(target_os = "linux")]
                            terminate_app_cleanly(app);
                            #[cfg(not(target_os = "linux"))]
                            {
                                EXPLICIT_QUIT_REQUESTED.store(true, Ordering::SeqCst);
                                app.exit(0);
                            }
                        }
                        _ => {}
                    })
                    .on_tray_icon_event(|tray, event| match event {
                        TrayIconEvent::Click {
                            button: MouseButton::Left,
                            button_state: MouseButtonState::Up,
                            ..
                        }
                        | TrayIconEvent::DoubleClick {
                            button: MouseButton::Left,
                            ..
                        } => {
                            focus_main_window(tray.app_handle());
                        }
                        _ => {}
                    })
                    .build(app);
            }

            // Check CLI args on cold start + initialize native bundle URL registration early
            let startup_args: Vec<String> = std::env::args().collect();
            let handle = app.handle().clone();
            tauri::async_runtime::spawn(async move {
                if let Some(eng) = handle.try_state::<Arc<BridgeEngine>>() {
                    let unread = { eng.state.lock().unwrap().unread_notifications };
                    eng.set_dock_badge(unread).await;
                    for arg in startup_args {
                        let lower = arg.to_ascii_lowercase();
                        if lower.starts_with("discourse://")
                            || lower.starts_with("lindo://")
                            || lower.starts_with("fluxdo://")
                        {
                            let _ = eng.handle_auth_redirect(&handle, &arg).await;
                        }
                    }
                }
            });
            Ok(())
        })
        .on_window_event(|window, event| {
            if window.label() == "main" {
                match event {
                    WindowEvent::DragDrop(DragDropEvent::Drop { paths, .. }) => {
                        record_os_dropped_paths(paths);
                    }
                    WindowEvent::CloseRequested { api, .. } => {
                        if platform_bridge::CAPS.close_main_exits_app {
                            let _ = api;
                            terminate_app_cleanly(window.app_handle());
                        } else if !EXPLICIT_QUIT_REQUESTED.load(Ordering::SeqCst) {
                            // Keep app running in background (Tray / Dock) on macOS & Windows
                            api.prevent_close();
                            #[cfg(target_os = "macos")]
                            if window.is_fullscreen().unwrap_or(false) {
                                let _ = window.set_fullscreen(false);
                                let win_clone = window.clone();
                                tauri::async_runtime::spawn(async move {
                                    tokio::time::sleep(std::time::Duration::from_millis(620)).await;
                                    let _ = win_clone.hide();
                                });
                                return;
                            }
                            let _ = window.hide();
                        }
                    }
                    _ => {}
                }
            }
        })
        .invoke_handler(tauri::generate_handler![
            set_app_cursor,
            bootstrap_state,
            site_request,
            site_upload,
            read_clipboard_media,
            read_dropped_files,
            site_fetch_media,
            site_verify,
            start_browser_auth,
            update_current_user,
            clear_expired_notice,
            switch_account,
            logout_account,
            remove_saved_account,
            add_account_web_login,
            import_manual_credential,
            generate_qr_login,
            open_in_app_forum_panel,
            fetch_connect_page_html,
            save_preferences,
            record_read_floor,
            clear_read_history,
            clear_local_caches,
            save_local_draft,
            delete_local_draft,
            set_unread_badge,
            check_app_update,
            download_and_install_update,
            get_diag_runtime_snapshot,
            get_ech_status,
            export_diagnostic_log,
            quit_app
        ]);

    let app = builder
        .build(tauri::generate_context!())
        .expect("error while building Lindo Tauri application");

    app.run(|app_handle, event| match event {
        RunEvent::ExitRequested { api, code, .. } => {
            if code.is_none() && !EXPLICIT_QUIT_REQUESTED.load(Ordering::SeqCst) {
                api.prevent_exit();
            }
        }
        #[cfg(target_os = "macos")]
        RunEvent::Reopen { .. } => {
            focus_main_window(app_handle);
        }
        #[cfg(target_os = "macos")]
        RunEvent::Opened { urls } => {
            focus_main_window(app_handle);
            for u in urls {
                let raw = u.to_string();
                let lower = raw.to_ascii_lowercase();
                if lower.starts_with("discourse://")
                    || lower.starts_with("lindo://")
                    || lower.starts_with("fluxdo://")
                {
                    let app_clone = app_handle.clone();
                    tauri::async_runtime::spawn(async move {
                        if let Some(eng) = app_clone.try_state::<Arc<BridgeEngine>>() {
                            if let Err(err) = eng.handle_auth_redirect(&app_clone, &raw).await {
                                let _ = app_clone.emit("lindo://auth-completing", false);
                                let _ = app_clone.emit("lindo://auth-notice", err);
                            }
                        }
                    });
                }
            }
        }
        _ => {
            let _ = app_handle;
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_standard_github_release_assets_and_digest_across_macos_and_windows() {
        let rel = serde_json::json!({
            "tag_name": "v1.0.39",
            "published_at": "2026-11-17T14:35:00Z",
            "body": "## 更新说明\n- 楼层未读黄点平滑淡出\n- 纯 GitHub Release 三端自动升级",
            "assets": [
                {
                    "name": "Lindo-1.0.39-macos-arm64.zip",
                    "digest": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "browser_download_url": "https://github.com/lucaslus/LinDo/releases/download/v1.0.39/Lindo-1.0.39-macos-arm64.zip"
                },
                {
                    "name": "Lindo-1.0.39-macos-x64.zip",
                    "digest": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                    "browser_download_url": "https://github.com/lucaslus/LinDo/releases/download/v1.0.39/Lindo-1.0.39-macos-x64.zip"
                },
                {
                    "name": "Lindo-1.0.39-windows-x64-setup.exe",
                    "digest": "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                    "browser_download_url": "https://github.com/lucaslus/LinDo/releases/download/v1.0.39/Lindo-1.0.39-windows-x64-setup.exe"
                },
                {
                    "name": "Lindo-1.0.39-windows-x64.msi",
                    "digest": "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                    "browser_download_url": "https://github.com/lucaslus/LinDo/releases/download/v1.0.39/Lindo-1.0.39-windows-x64.msi"
                }
            ]
        });

        let arm = parse_github_release_for_desktop(&rel, None, "1.0.38", "macos", "arm64");
        assert!(arm.has_update);
        assert_eq!(arm.latest_version, "1.0.39");
        assert_eq!(arm.arch, "arm64");
        assert_eq!(
            arm.sha256,
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        );
        assert_eq!(arm.notes.len(), 2);

        let x64 = parse_github_release_for_desktop(&rel, None, "1.0.38", "macos", "x64");
        assert!(x64.has_update);
        assert_eq!(x64.arch, "x64");
        assert!(x64.zip_url.ends_with("Lindo-1.0.39-macos-x64.zip"));
        assert_eq!(
            x64.sha256,
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        );

        let win = parse_github_release_for_desktop(&rel, None, "1.0.38", "windows", "x64");
        assert!(win.has_update);
        assert_eq!(win.arch, "windows-x64");
        assert!(win.zip_url.ends_with("Lindo-1.0.39-windows-x64-setup.exe"));
        assert!(win.dmg_url.ends_with("Lindo-1.0.39-windows-x64.msi"));
        assert_eq!(
            win.sha256,
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        );

        // Linux must never falsely match macOS/Windows assets when a release has no Linux package
        let linux_none = parse_github_release_for_desktop(&rel, None, "1.0.38", "linux", "x64");
        assert!(!linux_none.has_update);
        assert_eq!(linux_none.arch, "linux-x64");
        assert!(linux_none.zip_url.is_empty());

        let rel_with_linux = serde_json::json!({
            "tag_name": "v1.1.5",
            "published_at": "2026-11-25T12:00:00Z",
            "body": "- Linux Arch & AppImage 原生支持",
            "assets": [
                {
                    "name": "Lindo-1.1.5-macos-arm64.zip",
                    "browser_download_url": "https://github.com/lucaslus/LinDo/releases/download/v1.1.5/Lindo-1.1.5-macos-arm64.zip"
                },
                {
                    "name": "lindo-1.1.5-1-x86_64.pkg.tar.zst",
                    "digest": "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                    "browser_download_url": "https://github.com/lucaslus/LinDo/releases/download/v1.1.5/lindo-1.1.5-1-x86_64.pkg.tar.zst"
                },
                {
                    "name": "Lindo-1.1.5-linux-amd64.deb",
                    "browser_download_url": "https://github.com/lucaslus/LinDo/releases/download/v1.1.5/Lindo-1.1.5-linux-amd64.deb"
                }
            ]
        });
        let linux_pkg = parse_github_release_for_desktop(&rel_with_linux, None, "1.1.4", "linux", "x64");
        assert!(linux_pkg.has_update);
        assert_eq!(linux_pkg.arch, "linux-x64");
        assert!(linux_pkg.zip_url.ends_with("lindo-1.1.5-1-x86_64.pkg.tar.zst"));
        assert!(linux_pkg.dmg_url.ends_with("Lindo-1.1.5-linux-amd64.deb"));
        assert_eq!(
            linux_pkg.sha256,
            "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        );

        let rel_ci_linux = serde_json::json!({
            "tag_name": "v1.1.5",
            "published_at": "2026-11-25T12:00:00Z",
            "body": "- Linux Ubuntu/Debian/Fedora/AppImage 多发行版打包",
            "assets": [
                {
                    "name": "Lindo-1.1.5-linux-x86_64.AppImage",
                    "digest": "sha256:1111111111111111111111111111111111111111111111111111111111111111",
                    "browser_download_url": "https://github.com/lucaslus/LinDo/releases/download/v1.1.5/Lindo-1.1.5-linux-x86_64.AppImage"
                },
                {
                    "name": "Lindo-1.1.5-linux-amd64.deb",
                    "digest": "sha256:2222222222222222222222222222222222222222222222222222222222222222",
                    "browser_download_url": "https://github.com/lucaslus/LinDo/releases/download/v1.1.5/Lindo-1.1.5-linux-amd64.deb"
                },
                {
                    "name": "Lindo-1.1.5-linux-x86_64.rpm",
                    "digest": "sha256:3333333333333333333333333333333333333333333333333333333333333333",
                    "browser_download_url": "https://github.com/lucaslus/LinDo/releases/download/v1.1.5/Lindo-1.1.5-linux-x86_64.rpm"
                }
            ]
        });
        let linux_ci = parse_github_release_for_desktop(&rel_ci_linux, None, "1.1.4", "linux", "x64");
        assert!(linux_ci.has_update);
        assert!(linux_ci.zip_url.ends_with("Lindo-1.1.5-linux-x86_64.AppImage"));
        assert!(linux_ci.dmg_url.ends_with("Lindo-1.1.5-linux-amd64.deb"));
        assert_eq!(
            linux_ci.sha256,
            "1111111111111111111111111111111111111111111111111111111111111111"
        );
        let mac_ignores_linux = parse_github_release_for_desktop(&rel_ci_linux, None, "1.1.4", "macos", "x64");
        assert!(!mac_ignores_linux.has_update);
    }

    #[test]
    fn falls_back_to_sha256sums_txt_without_mismatched_dmg_digest() {
        let rel = serde_json::json!({
            "tag_name": "v1.1.1",
            "published_at": "2026-11-24T12:00:00Z",
            "body": "- SHA256SUMS fallback verification",
            "assets": [
                {
                    "name": "Lindo-1.1.1-macos-arm64.zip",
                    "browser_download_url": "https://github.com/lucaslus/LinDo/releases/download/v1.1.1/Lindo-1.1.1-macos-arm64.zip"
                },
                {
                    "name": "Lindo-1.1.1-macos-arm64.dmg",
                    "digest": "sha256:9999999999999999999999999999999999999999999999999999999999999999",
                    "browser_download_url": "https://github.com/lucaslus/LinDo/releases/download/v1.1.1/Lindo-1.1.1-macos-arm64.dmg"
                },
                {
                    "name": "Lindo-1.1.1-windows-x64-setup.exe",
                    "browser_download_url": "https://github.com/lucaslus/LinDo/releases/download/v1.1.1/Lindo-1.1.1-windows-x64-setup.exe"
                }
            ]
        });
        let sums = "\
            ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789  Lindo-1.1.1-macos-arm64.zip\n\
            1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef  *Lindo-1.1.1-windows-x64-setup.exe\n\
        ";

        // Without SHA256SUMS, zip_url must NOT inherit dmg's unrelated digest
        let arm_no_sums = parse_github_release_for_desktop(&rel, None, "1.1.0", "macos", "arm64");
        assert!(arm_no_sums.has_update);
        assert_eq!(arm_no_sums.sha256, "");

        let arm_with_sums = parse_github_release_for_desktop_with_sums(
            &rel,
            None,
            Some(sums),
            "1.1.0",
            "macos",
            "arm64",
        );
        assert_eq!(
            arm_with_sums.sha256,
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
        );

        let win_with_sums = parse_github_release_for_desktop_with_sums(
            &rel,
            None,
            Some(sums),
            "1.1.0",
            "windows",
            "x64",
        );
        assert_eq!(
            win_with_sums.sha256,
            "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
        );
    }

    #[test]
    fn sanitizes_github_full_changelog_and_markdown_syntax_from_release_notes() {
        let raw = "\
            ## What's Changed\n\
            * **极简更新弹窗**：移除多余装饰与链接 by @lucaslus in https://github.com/lucaslus/LinDo/pull/12\n\
            - chore(release): bump v1.1.1\n\
            **Full Changelog**: https://github.com/lucaslus/LinDo/commits/v1.1.1\n\
        ";
        let notes = extract_release_notes_from_markdown(raw);
        assert_eq!(notes, vec!["极简更新弹窗：移除多余装饰与链接".to_string()]);
        assert!(extract_release_notes_from_markdown(
            "**Full Changelog**: https://github.com/lucaslus/LinDo/commits/v1.1.0"
        )
        .is_empty());

        let with_os_matrix = "\
            ### 更新内容\n\n\
            - 新增 Linux 原生客户端与多发行版打包\n\
            - 补齐全端相关话题作者头像展示\n\n\
            ![Downloads](https://img.shields.io/github/downloads/lucaslus/LinDo/v1.1.5/total?logo=github)\n\n\
            **按系统选择下载（Download based on your OS）：**\n\n\
            | OS | Download |\n\
            | :--- | :--- |\n\
            | **Android** | [![APK-ARMv8](https://img.shields.io/badge/APK-ARMv8-2ea043)](https://github.com/lucaslus/LinDo/releases/download/v1.1.5/lindo-1.1.5-build105.apk) |\n\n\
            **完整变更记录**: [v1.1.4...v1.1.5](https://github.com/lucaslus/LinDo/compare/v1.1.4...v1.1.5)\n\
        ";
        assert_eq!(
            extract_release_notes_from_markdown(with_os_matrix),
            vec![
                "新增 Linux 原生客户端与多发行版打包".to_string(),
                "补齐全端相关话题作者头像展示".to_string(),
            ]
        );
    }

    #[tokio::test]
    async fn live_github_release_e2e_when_enabled() {
        let mode = std::env::var("LINDO_LIVE_GH_RELEASE_MODE").unwrap_or_default();
        if mode.is_empty() {
            return;
        }
        let info = check_app_update()
            .await
            .expect("check_app_update should succeed against GitHub API");
        println!("LIVE GH DESKTOP UPDATE INFO ({mode}): {info:?}");
        if mode == "expect_empty" {
            assert!(
                !info.has_update,
                "Expected no update when GitHub has 0 releases"
            );
            return;
        }
        if mode == "expect_update" {
            assert!(info.has_update, "Expected has_update=true from live GitHub Release");
            assert!(
                info.zip_url.starts_with("https://github.com/lucaslus/LinDo/releases/download/"),
                "Unexpected zip_url: {}",
                info.zip_url
            );
            assert_eq!(info.sha256.len(), 64, "Expected 64-hex SHA-256 from GitHub Release");

            let tmp = std::env::temp_dir().join("lindo_gh_live_e2e_test");
            let _ = std::fs::remove_dir_all(&tmp);
            std::fs::create_dir_all(&tmp).unwrap();
            let zip_path = tmp.join("pkg.zip");

            let (bytes, actual_sha) = stream_download_and_verify_sha256(
                &info.zip_url,
                &zip_path,
                &info.sha256,
                |_, _| {},
            )
            .await
            .expect("Pure-Rust streaming download + SHA-256 verification failed");
            assert!(bytes > 1024, "Downloaded zip seems unexpectedly small");
            assert_eq!(actual_sha, info.sha256.to_ascii_lowercase());

            #[cfg(target_os = "macos")]
            {
                let ext = tmp.join("extracted");
                std::fs::create_dir_all(&ext).unwrap();
                let unz = tokio::process::Command::new("/usr/bin/ditto")
                    .args(["-x", "-k", zip_path.to_str().unwrap(), ext.to_str().unwrap()])
                    .status()
                    .await
                    .unwrap();
                assert!(unz.success(), "ditto extraction of downloaded zip failed");
                assert!(
                    ext.join("Lindo.app").exists(),
                    "Extracted Lindo.app bundle must exist"
                );
            }
            let _ = std::fs::remove_dir_all(&tmp);
        }
    }
}
