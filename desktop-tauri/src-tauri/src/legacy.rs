use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs;
use std::path::PathBuf;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UserProfile {
    pub user_id: i64,
    pub username: String,
    pub display_name: String,
    pub avatar_template: String,
    #[serde(default)]
    pub animated_avatar: Option<String>,
    #[serde(default = "default_trust_level")]
    pub trust_level: i32,
    #[serde(default)]
    pub user_title: Option<String>,
}

fn default_trust_level() -> i32 {
    1
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SavedAccountEntry {
    pub profile: UserProfile,
    #[serde(default)]
    pub cookies: String,
    #[serde(default)]
    pub needs_login: bool,
    #[serde(default)]
    pub read_floors: HashMap<String, i32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UserPreferences {
    /// "system" | "light" | "dark"
    #[serde(default = "default_appearance")]
    pub appearance: String,
    /// "OFFICIAL_GOLD" | "GEEK_BLUE" | "BAMBOO_GREEN" | "IDEAL_RED" | "SOFT_PURPLE" | "CLEAR_TEAL" | "WARM_ORANGE"
    #[serde(default = "default_theme_option")]
    pub theme_option: String,
    #[serde(default = "default_font_scale")]
    pub font_scale: f32,
    #[serde(default = "default_split_fraction")]
    pub split_fraction: f32,
    #[serde(default)]
    pub compact_feed: bool,
    #[serde(default = "default_true")]
    pub default_search_expanded: bool,
    #[serde(default = "default_true")]
    pub enable_animations: bool,
    /// "smart_auto" | "ech_priority" | "system_default"
    #[serde(default = "default_ech_routing_mode")]
    pub ech_routing_mode: String,
}

fn default_appearance() -> String {
    "dark".to_string()
}
fn default_theme_option() -> String {
    "OFFICIAL_GOLD".to_string()
}
fn default_font_scale() -> f32 {
    1.0
}
fn default_split_fraction() -> f32 {
    0.39
}
fn default_true() -> bool {
    true
}
fn default_ech_routing_mode() -> String {
    "smart_auto".to_string()
}

impl Default for UserPreferences {
    fn default() -> Self {
        Self {
            appearance: default_appearance(),
            theme_option: default_theme_option(),
            font_scale: default_font_scale(),
            split_fraction: default_split_fraction(),
            compact_feed: false,
            default_search_expanded: true,
            enable_animations: true,
            ech_routing_mode: default_ech_routing_mode(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LocalDraftRecord {
    pub key: String,
    pub action: String,
    #[serde(default)]
    pub topic_id: Option<i64>,
    #[serde(default)]
    pub post_id: Option<i64>,
    #[serde(default)]
    pub reply_to_post_number: Option<i32>,
    #[serde(default)]
    pub category_id: Option<i32>,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub raw: String,
    #[serde(default)]
    pub tags: Vec<String>,
    #[serde(default)]
    pub sequence: i64,
    #[serde(default)]
    pub updated_at: i64,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PersistedState {
    pub current_user: Option<UserProfile>,
    #[serde(default)]
    pub raw_cookies: String,
    #[serde(default)]
    pub cf_clearance_updated_at_ms: u64,
    #[serde(default)]
    pub last_bridge_ua: String,
    #[serde(default)]
    pub client_id: String,
    #[serde(default)]
    pub expired_username: Option<String>,
    #[serde(default)]
    pub unread_notifications: i32,
    #[serde(default)]
    pub saved_accounts: Vec<SavedAccountEntry>,
    #[serde(default)]
    pub preferences: UserPreferences,
    #[serde(default)]
    pub read_floors: HashMap<String, i32>,
    #[serde(default)]
    pub drafts: HashMap<String, LocalDraftRecord>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PendingRsaDiskRecord {
    pub private_base64: String,
    pub nonce: String,
    pub created_at_ms: u64,
}

pub fn pending_rsa_path() -> PathBuf {
    lindo_home_dir().join("pending_rsa.json")
}

pub fn save_pending_rsa(record: &PendingRsaDiskRecord) {
    let path = pending_rsa_path();
    if let Ok(raw) = serde_json::to_string(record) {
        let _ = fs::write(&path, raw);
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let _ = fs::set_permissions(&path, fs::Permissions::from_mode(0o600));
        }
    }
}

pub fn load_pending_rsa() -> Option<PendingRsaDiskRecord> {
    let raw = fs::read_to_string(pending_rsa_path()).ok()?;
    serde_json::from_str::<PendingRsaDiskRecord>(&raw).ok()
}

pub fn clear_pending_rsa() {
    let _ = fs::remove_file(pending_rsa_path());
}

pub fn lindo_home_dir() -> PathBuf {
    let base = dirs::home_dir().unwrap_or_else(|| PathBuf::from("."));
    let dir = base.join(".lindo");
    let _ = fs::create_dir_all(&dir);
    dir
}

pub fn tauri_state_path() -> PathBuf {
    lindo_home_dir().join("tauri_state.json")
}

pub fn legacy_properties_path() -> PathBuf {
    lindo_home_dir().join("session.properties")
}

fn unescape_java_property(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len());
    let chars: Vec<char> = raw.chars().collect();
    let mut i = 0;
    while i < chars.len() {
        if chars[i] == '\\' && i + 1 < chars.len() {
            match chars[i + 1] {
                'n' => {
                    out.push('\n');
                    i += 2;
                }
                'r' => {
                    out.push('\r');
                    i += 2;
                }
                't' => {
                    out.push('\t');
                    i += 2;
                }
                'u' if i + 5 < chars.len() => {
                    let hex: String = chars[i + 2..i + 6].iter().collect();
                    if let Ok(code) = u32::from_str_radix(&hex, 16) {
                        if let Some(ch) = char::from_u32(code) {
                            out.push(ch);
                            i += 6;
                            continue;
                        }
                    }
                    out.push(chars[i + 1]);
                    i += 2;
                }
                other => {
                    out.push(other);
                    i += 2;
                }
            }
        } else {
            out.push(chars[i]);
            i += 1;
        }
    }
    out
}

fn parse_java_properties(content: &str) -> HashMap<String, String> {
    let mut map = HashMap::new();
    for line in content.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() || trimmed.starts_with('#') || trimmed.starts_with('!') {
            continue;
        }
        // Find first unescaped '=' or ':'
        let bytes = trimmed.as_bytes();
        let mut sep = None;
        let mut idx = 0;
        while idx < bytes.len() {
            if bytes[idx] == b'\\' {
                idx += 2;
                continue;
            }
            if bytes[idx] == b'=' || bytes[idx] == b':' {
                sep = Some(idx);
                break;
            }
            idx += 1;
        }
        if let Some(pos) = sep {
            let key = unescape_java_property(trimmed[..pos].trim());
            let val = unescape_java_property(trimmed[pos + 1..].trim());
            map.insert(key, val);
        }
    }
    map
}

#[derive(Deserialize)]
struct LegacySavedAccountsStore {
    #[serde(default)]
    sessions: Vec<LegacySavedSession>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct LegacySavedSession {
    profile: UserProfile,
    #[serde(default)]
    cookies: String,
    #[serde(default)]
    needs_login: bool,
}

pub fn load_or_migrate_state() -> PersistedState {
    let state_file = tauri_state_path();
    let mut state: Option<PersistedState> = fs::read_to_string(&state_file)
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok());

    let legacy_file = legacy_properties_path();
    let legacy_props = fs::read_to_string(&legacy_file)
        .ok()
        .map(|s| parse_java_properties(&s));

    if state.is_none() {
        let mut fresh = PersistedState {
            client_id: format!("lindo-desktop-{}", uuid::Uuid::new_v4().simple()),
            ..Default::default()
        };
        if let Some(ref props) = legacy_props {
            apply_legacy_props(&mut fresh, props);
            let _ = fs::rename(&legacy_file, legacy_file.with_extension("properties.migrated"));
        }
        save_state(&fresh);
        return fresh;
    }

    let mut existing = state.take().unwrap();
    if existing.client_id.is_empty() {
        existing.client_id = legacy_props
            .as_ref()
            .and_then(|p| p.get("client_id").cloned())
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| format!("lindo-desktop-{}", uuid::Uuid::new_v4().simple()));
    }
    // Retire stale legacy session.properties once tauri_state.json already exists so expired sessions are NEVER resurrected on restart!
    if legacy_file.exists() {
        let _ = fs::rename(&legacy_file, legacy_file.with_extension("properties.migrated"));
    }
    existing
}

fn apply_legacy_props(target: &mut PersistedState, props: &HashMap<String, String>) {
    if let Some(cid) = props.get("client_id").filter(|s| !s.is_empty()) {
        target.client_id = cid.clone();
    }
    if let Some(raw_cookies) = props.get("raw_cookies").filter(|s| !s.is_empty()) {
        target.raw_cookies = raw_cookies.clone();
    }
    if let Some(uname) = props.get("username").filter(|s| !s.is_empty() && *s != "linuxdo_user") {
        let user_id = props.get("user_id").and_then(|v| v.parse::<i64>().ok()).unwrap_or(0);
        let display_name = props
            .get("display_name")
            .filter(|s| !s.is_empty())
            .cloned()
            .unwrap_or_else(|| uname.clone());
        let avatar_template = props.get("avatar_template").cloned().unwrap_or_default();
        let animated_avatar = props.get("animated_avatar").filter(|s| !s.is_empty()).cloned();
        let trust_level = props.get("trust_level").and_then(|v| v.parse::<i32>().ok()).unwrap_or(1);
        let user_title = props.get("user_title").filter(|s| !s.is_empty()).cloned();
        target.current_user = Some(UserProfile {
            user_id,
            username: uname.clone(),
            display_name,
            avatar_template,
            animated_avatar,
            trust_level,
            user_title,
        });
    }
    if let Some(unread) = props.get("unread_notifications").and_then(|v| v.parse::<i32>().ok()) {
        target.unread_notifications = unread;
    }
    if let Some(saved_json) = props.get("saved_accounts").filter(|s| !s.is_empty()) {
        if let Ok(parsed) = serde_json::from_str::<LegacySavedAccountsStore>(saved_json) {
            target.saved_accounts = parsed
                .sessions
                .into_iter()
                .filter(|s| !s.profile.username.is_empty() && s.profile.username != "linuxdo_user")
                .map(|s| SavedAccountEntry {
                    profile: s.profile,
                    cookies: s.cookies,
                    needs_login: s.needs_login,
                    read_floors: HashMap::new(),
                })
                .collect();
        }
    }
    // Preferences
    if let Some(app_mode) = props.get("ui_appearance").or_else(|| props.get("mac_appearance")) {
        let lower = app_mode.to_lowercase();
        if lower == "light" || lower == "dark" || lower == "system" {
            target.preferences.appearance = lower;
        }
    } else if let Some(dark) = props.get("ui_dark_theme") {
        target.preferences.appearance = if dark == "false" { "light".into() } else { "dark".into() };
    }
    if let Some(theme) = props.get("theme_option").filter(|s| !s.is_empty()) {
        target.preferences.theme_option = theme.clone();
    }
    if let Some(scale) = props
        .get("ui_font_scale_mac_v3")
        .or_else(|| props.get("ui_font_scale_v2"))
        .and_then(|v| v.parse::<f32>().ok())
    {
        target.preferences.font_scale = scale.clamp(0.85, 1.35);
    }
    if let Some(split) = props.get("ui_split_fraction").and_then(|v| v.parse::<f32>().ok()) {
        target.preferences.split_fraction = split.clamp(0.28, 0.62);
    }
    if let Some(compact) = props.get("is_compact_feed") {
        target.preferences.compact_feed = compact == "true";
    }
    if let Some(reads) = props.get("read_topic_posts").filter(|s| !s.is_empty()) {
        for pair in reads.split(',') {
            if let Some((tid, floor)) = pair.split_once(':') {
                if let Ok(f) = floor.trim().parse::<i32>() {
                    target.read_floors.insert(tid.trim().to_string(), f);
                }
            }
        }
    }
}

pub fn save_state(state: &PersistedState) {
    let mut sanitized = state.clone();
    sanitized.raw_cookies = sanitized
        .raw_cookies
        .split(';')
        .map(|s| s.trim())
        .filter(|s| {
            !s.is_empty()
                && !s.starts_with("cf_clearance=")
                && !s.starts_with("__cf")
                && !s.starts_with("_cf")
        })
        .collect::<Vec<_>>()
        .join("; ");
    sanitized.cf_clearance_updated_at_ms = 0;

    let path = tauri_state_path();
    let tmp = path.with_extension("json.tmp");
    if let Ok(serialized) = serde_json::to_string_pretty(&sanitized) {
        if fs::write(&tmp, serialized).is_ok() {
            let _ = fs::rename(&tmp, &path);
        }
    }
}
