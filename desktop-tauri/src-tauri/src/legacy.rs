use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};

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
    #[serde(default)]
    pub admin: bool,
    #[serde(default)]
    pub moderator: bool,
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
    #[serde(default)]
    pub drafts: HashMap<String, LocalDraftRecord>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UserPreferences {
    /// "system" | "light" | "dark"
    #[serde(default = "default_appearance")]
    pub appearance: String,
    /// "WARM_ORANGE" | "OFFICIAL_GOLD" | "GEEK_BLUE" | "BAMBOO_GREEN" | "IDEAL_RED" | "SOFT_PURPLE" | "CLEAR_TEAL"
    #[serde(default = "default_theme_option")]
    pub theme_option: String,
    #[serde(default = "default_font_scale")]
    pub font_scale: f32,
    #[serde(default = "default_split_fraction")]
    pub split_fraction: f32,
    #[serde(default = "default_true")]
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
    "WARM_ORANGE".to_string()
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
            compact_feed: true,
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
    pub qr_client_id: String,
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
            qr_client_id: format!("lindo-qr-{}", uuid::Uuid::new_v4()),
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
    if existing.qr_client_id.trim().is_empty() {
        existing.qr_client_id = format!("lindo-qr-{}", uuid::Uuid::new_v4());
    }
    // Retire stale legacy session.properties once tauri_state.json already exists so expired sessions are NEVER resurrected on restart!
    if legacy_file.exists() {
        let _ = fs::rename(&legacy_file, legacy_file.with_extension("properties.migrated"));
    }
    enforce_private_file_permissions(&state_file);
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
            admin: false,
            moderator: false,
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
                    drafts: HashMap::new(),
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

pub fn snapshot_active_account_state(st: &mut PersistedState) {
    let Some(ref active) = st.current_user else {
        return;
    };
    let active_username = active.username.trim();
    if active_username.is_empty() || active_username == "linuxdo_user" {
        return;
    }
    if let Some(entry) = st
        .saved_accounts
        .iter_mut()
        .find(|a| a.profile.username.eq_ignore_ascii_case(active_username))
    {
        entry.profile = active.clone();
        entry.read_floors = st.read_floors.clone();
        entry.drafts = st.drafts.clone();
    } else {
        st.saved_accounts.push(SavedAccountEntry {
            profile: active.clone(),
            cookies: st.raw_cookies.clone(),
            needs_login: false,
            read_floors: st.read_floors.clone(),
            drafts: st.drafts.clone(),
        });
    }
}

pub fn apply_switch_saved_account_state(
    st: &mut PersistedState,
    target_username: &str,
) -> Option<SavedAccountEntry> {
    snapshot_active_account_state(st);
    let target = st
        .saved_accounts
        .iter()
        .find(|a| a.profile.username.eq_ignore_ascii_case(target_username))
        .cloned()?;
    if target.needs_login || target.cookies.is_empty() {
        return None;
    }
    st.current_user = Some(target.profile.clone());
    st.raw_cookies = target.cookies.clone();
    st.expired_username = None;
    st.read_floors = target.read_floors.clone();
    st.drafts = target.drafts.clone();
    Some(target)
}

pub fn apply_logout_state(st: &mut PersistedState, remove_saved: bool) {
    if remove_saved {
        if let Some(ref u) = st.current_user {
            let uname = u.username.clone();
            st.saved_accounts
                .retain(|a| !a.profile.username.eq_ignore_ascii_case(&uname));
        }
    } else {
        snapshot_active_account_state(st);
    }
    st.current_user = None;
    st.expired_username = None;
    st.unread_notifications = 0;
    st.read_floors.clear();
    st.drafts.clear();
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
}

fn enforce_private_file_permissions(path: &Path) {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = fs::set_permissions(path, fs::Permissions::from_mode(0o600));
    }
    #[cfg(not(unix))]
    {
        let _ = path;
    }
}

pub fn save_state_to_path(state: &PersistedState, path: &Path) {
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

    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    let tmp = path.with_extension("json.tmp");
    if let Ok(serialized) = serde_json::to_string_pretty(&sanitized) {
        if fs::write(&tmp, serialized).is_ok() {
            enforce_private_file_permissions(&tmp);
            if fs::rename(&tmp, path).is_ok() {
                enforce_private_file_permissions(path);
            }
        }
    }
}

pub fn save_state(state: &PersistedState) {
    save_state_to_path(state, &tauri_state_path());
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_user(id: i64, username: &str) -> UserProfile {
        UserProfile {
            user_id: id,
            username: username.to_string(),
            display_name: username.to_string(),
            avatar_template: String::new(),
            animated_avatar: None,
            trust_level: 2,
            user_title: None,
            admin: false,
            moderator: false,
        }
    }

    fn sample_draft(key: &str, raw: &str) -> LocalDraftRecord {
        LocalDraftRecord {
            key: key.to_string(),
            action: "REPLY".to_string(),
            topic_id: Some(42),
            post_id: None,
            reply_to_post_number: None,
            category_id: None,
            title: "Draft Title".to_string(),
            raw: raw.to_string(),
            tags: vec![],
            sequence: 1,
            updated_at: 1_700_000_000,
        }
    }

    #[test]
    fn saved_account_entry_deserializes_legacy_json_without_drafts_field() {
        let legacy_json = r#"{
            "profile": { "userId": 1, "username": "alice", "displayName": "Alice", "avatarTemplate": "" },
            "cookies": "_t=tok_a",
            "needsLogin": false,
            "readFloors": { "100": 5 }
        }"#;
        let parsed: SavedAccountEntry = serde_json::from_str(legacy_json).expect("legacy parse");
        assert_eq!(parsed.profile.username, "alice");
        assert!(!parsed.profile.admin);
        assert!(!parsed.profile.moderator);
        assert_eq!(parsed.read_floors.get("100"), Some(&5));
        assert!(parsed.drafts.is_empty());
    }

    #[test]
    fn switch_and_logout_isolate_read_floors_and_drafts_per_account() {
        let mut st = PersistedState::default();
        st.current_user = Some(sample_user(1, "alice"));
        st.raw_cookies = "_t=tok_alice".to_string();
        st.read_floors.insert("101".to_string(), 12);
        st.drafts
            .insert("topic_101".to_string(), sample_draft("topic_101", "Alice secret draft"));

        let mut bob_floors = HashMap::new();
        bob_floors.insert("202".to_string(), 7);
        let mut bob_drafts = HashMap::new();
        bob_drafts.insert("new_topic".to_string(), sample_draft("new_topic", "Bob draft"));
        st.saved_accounts.push(SavedAccountEntry {
            profile: sample_user(1, "alice"),
            cookies: "_t=tok_alice".to_string(),
            needs_login: false,
            read_floors: HashMap::new(),
            drafts: HashMap::new(),
        });
        st.saved_accounts.push(SavedAccountEntry {
            profile: sample_user(2, "bob"),
            cookies: "_t=tok_bob".to_string(),
            needs_login: false,
            read_floors: bob_floors,
            drafts: bob_drafts,
        });

        // Switch Alice -> Bob: Alice's floors & drafts snapshotted, Bob's restored
        assert!(apply_switch_saved_account_state(&mut st, "bob").is_some());
        assert_eq!(st.current_user.as_ref().unwrap().username, "bob");
        assert_eq!(st.read_floors.get("202"), Some(&7));
        assert!(!st.read_floors.contains_key("101"));
        assert!(st.drafts.contains_key("new_topic"));
        assert!(!st.drafts.contains_key("topic_101"));

        // Logout Bob (keep saved): active floors/drafts cleared, Bob's saved snapshot intact
        st.read_floors.insert("303".to_string(), 9);
        apply_logout_state(&mut st, false);
        assert!(st.current_user.is_none());
        assert!(st.read_floors.is_empty());
        assert!(st.drafts.is_empty());

        // Switch back to Alice: Alice's original floors & drafts restored with zero Bob leakage
        assert!(apply_switch_saved_account_state(&mut st, "alice").is_some());
        assert_eq!(st.read_floors.get("101"), Some(&12));
        assert_eq!(st.drafts.get("topic_101").unwrap().raw, "Alice secret draft");
        assert!(!st.drafts.contains_key("new_topic"));

        // Switch to Bob: Bob's updated floor 303 preserved
        assert!(apply_switch_saved_account_state(&mut st, "bob").is_some());
        assert_eq!(st.read_floors.get("303"), Some(&9));
    }

    #[cfg(unix)]
    #[test]
    fn save_state_to_path_enforces_0600_permissions_even_on_existing_loose_file() {
        use std::os::unix::fs::PermissionsExt;

        let dir = std::env::temp_dir().join(format!(
            "lindo_state_perm_test_{}",
            uuid::Uuid::new_v4().simple()
        ));
        let path = dir.join("tauri_state.json");
        fs::create_dir_all(&dir).unwrap();
        fs::write(&path, "{}").unwrap();
        fs::set_permissions(&path, fs::Permissions::from_mode(0o644)).unwrap();

        let mut st = PersistedState::default();
        st.raw_cookies = "_t=secret_session_cookie; cf_clearance=drop_me".to_string();
        save_state_to_path(&st, &path);

        let mode = fs::metadata(&path).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode, 0o600, "tauri_state.json must have 0o600 permissions");
        assert!(
            !path.with_extension("json.tmp").exists(),
            "temporary file must be renamed atomically"
        );
        let saved_text = fs::read_to_string(&path).unwrap();
        assert!(saved_text.contains("_t=secret_session_cookie"));
        assert!(!saved_text.contains("cf_clearance="));

        let _ = fs::remove_dir_all(&dir);
    }
}
