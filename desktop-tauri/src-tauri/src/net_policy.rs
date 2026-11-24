use chrono::DateTime;
use serde_json::Value;
use std::sync::atomic::{AtomicU64, AtomicU8, Ordering};
use std::sync::{Arc, Mutex as StdMutex};
use std::time::{Duration, Instant};
use tokio::sync::{oneshot, watch, Notify};

pub const CF_COLD_SEED_MAX_AGE_MS: u64 = 12 * 3600 * 1000;
/// Natural seamless rotation threshold: once incumbent clearance is >= 11h old, allow fresh `.linux.do` value to take over.
pub const CF_NATURAL_ROTATION_AGE_MS: u64 = 11 * 3600 * 1000;

pub const VERIFY_MAX_FAILURES_BEFORE_COOLDOWN: u32 = 3;
pub const VERIFY_FAILURE_COOLDOWN_SECS: u64 = 30;
pub const VERIFY_INEFFECTIVE_COOLDOWN_SECS: u64 = 60;
pub const VERIFY_SILENT_BACKOFF_SECS: u64 = 45;
pub const VERIFY_RECENT_PASS_COALESCE_SECS: u64 = 5;
pub const VERIFY_INEFFECTIVE_WINDOW_SECS: u64 = 15;

// ============================================================================
// 1. CfClearanceAuthority v2 — Pure Incumbent Stickiness (No False Blacklist)
// ============================================================================

/// Authoritative session-level state machine for `cf_clearance`.
///
/// Key lesson distilled from FluxDO v4 (`CfClearanceAuthority`):
/// - NEVER maintain a permanent blacklist of previously challenged values — a valid clearance can
///   momentarily hit a rate-limit burst; permanently blacklisting it causes false rejections on
///   restore/startup.
/// - Track ONLY the single live `incumbent` and the single `last_challenged` incumbent value:
///   1. `verified == true` (right after `/challenge` passes) -> adopt fresh `.linux.do` winner immediately.
///   2. Healthy sticky `incumbent` (not matching `last_challenged`, within rotation age, still in store) -> keep it.
///   3. Rotation open (`incumbent` empty, challenged, or approaching expiry) -> adopt canonical `.linux.do` value.
///   4. Transient empty browser cookie read -> preserve healthy fresh incumbent.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VerifyTicketOutcome {
    /// A brand-new unchallenged `cf_clearance` was minted during `/challenge`.
    Rotated,
    /// Existing `cf_clearance` was NOT previously challenged and origin reached cleanly.
    ExistingValid,
    /// Previous `cf_clearance` WAS challenged, `/challenge` hit unchallenged origin 404/200,
    /// and NO fresh `cf_clearance` was minted -> stale ticket MUST NOT be washed clean!
    StaleUnchallenged404,
    /// No `cf_clearance` exists in browser cookie store.
    Absent,
}

#[derive(Debug, Clone, Default)]
pub struct CfClearanceAuthority {
    pub incumbent: Option<String>,
    pub last_challenged: Option<String>,
    pub updated_at_ms: u64,
    pub total_rotations: u32,
}

impl CfClearanceAuthority {
    pub fn from_persisted(raw_cookies: &str, saved_updated_at_ms: u64, now_ms: u64) -> Self {
        let mut auth = Self::default();
        for seg in raw_cookies.split(';') {
            let trimmed = seg.trim();
            if let Some((k, v)) = trimmed.split_once('=') {
                if k.trim() == "cf_clearance" {
                    let cf = v.trim();
                    if !cf.is_empty()
                        && saved_updated_at_ms > 0
                        && now_ms.saturating_sub(saved_updated_at_ms) <= CF_COLD_SEED_MAX_AGE_MS
                    {
                        auth.incumbent = Some(cf.to_string());
                        auth.updated_at_ms = saved_updated_at_ms;
                    }
                    break;
                }
            }
        }
        auth
    }

    pub fn is_challenged(&self, value: &str) -> bool {
        let trimmed = value.trim();
        !trimmed.is_empty() && self.last_challenged.as_deref() == Some(trimmed)
    }

    pub fn has_healthy_incumbent(&self) -> bool {
        self.incumbent
            .as_deref()
            .map(|v| !v.is_empty() && self.last_challenged.as_deref() != Some(v))
            .unwrap_or(false)
    }

    pub fn age_secs(&self, now_ms: u64) -> Option<u64> {
        if self.incumbent.is_some() && self.updated_at_ms > 0 {
            Some(now_ms.saturating_sub(self.updated_at_ms) / 1000)
        } else {
            None
        }
    }

    pub fn confirm_healthy(&mut self, value: &str, now_ms: u64) {
        let trimmed = value.trim();
        if trimmed.is_empty() || self.is_challenged(trimmed) {
            return;
        }
        if self.incumbent.as_deref() != Some(trimmed) {
            self.incumbent = Some(trimmed.to_string());
            self.updated_at_ms = now_ms;
        } else if self.updated_at_ms == 0 {
            self.updated_at_ms = now_ms;
        }
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
            self.last_challenged = Some(dead);
        }
    }

    pub fn reset(&mut self) {
        self.incumbent = None;
        self.last_challenged = None;
        self.updated_at_ms = 0;
    }

    pub fn mark_rotation_open(&mut self) {
        self.updated_at_ms = 0;
        self.last_challenged = None;
    }

    /// Evaluates whether `/challenge` actually produced a valid clearance or merely hit an unchallenged
    /// Discourse 404 while still carrying a falsified `cf_clearance` (preventing false clearance whitewashing).
    pub fn evaluate_verify_outcome(
        &self,
        baseline_cfs: &std::collections::HashSet<String>,
        current_cfs: &[String],
        previous_was_challenged: bool,
        saw_origin_pass: bool,
    ) -> VerifyTicketOutcome {
        let clean_current: Vec<&str> = current_cfs
            .iter()
            .map(|s| s.trim())
            .filter(|s| !s.is_empty())
            .collect();
        if clean_current.is_empty() {
            return VerifyTicketOutcome::Absent;
        }
        let has_fresh = clean_current
            .iter()
            .any(|c| !baseline_cfs.contains(*c) && !self.is_challenged(c));
        if has_fresh {
            return VerifyTicketOutcome::Rotated;
        }
        let any_unchallenged = clean_current.iter().any(|c| !self.is_challenged(c));
        if previous_was_challenged || !any_unchallenged {
            VerifyTicketOutcome::StaleUnchallenged404
        } else if saw_origin_pass {
            VerifyTicketOutcome::ExistingValid
        } else {
            VerifyTicketOutcome::Absent
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
                !t.is_empty() && !self.is_challenged(t)
            })
            .collect();

        if verified {
            // Right after user passes `/challenge` / login, prefer a newly rotated `.linux.do` ticket over the old incumbent.
            let rotated_domain = valid.iter().rev().find(|(v, host_only)| {
                !*host_only && self.incumbent.as_deref() != Some(v.as_str())
            });
            let any_domain = valid.iter().rev().find(|(_, host_only)| !*host_only);
            let chosen = rotated_domain
                .or(any_domain)
                .or_else(|| valid.last())
                .map(|(v, _)| v.clone());
            if let Some(ref win) = chosen {
                if self.incumbent.as_deref() != Some(win.as_str()) {
                    self.total_rotations = self.total_rotations.saturating_add(1);
                }
                self.incumbent = Some(win.clone());
                self.last_challenged = None;
                self.updated_at_ms = now_ms;
                return chosen;
            }
        }

        // Sticky incumbent rule: if incumbent is healthy, not approaching natural rotation age,
        // and still present in browser cookies, keep it regardless of WebKit/WebView2 enumeration order.
        if let Some(ref inc) = self.incumbent {
            let within_sticky_ttl = self.updated_at_ms == 0
                || now_ms.saturating_sub(self.updated_at_ms) < CF_NATURAL_ROTATION_AGE_MS;
            if within_sticky_ttl && !self.is_challenged(inc) {
                if valid.iter().any(|(v, _)| v == inc) {
                    if self.updated_at_ms == 0 {
                        self.updated_at_ms = now_ms;
                    }
                    return Some(inc.clone());
                }
            }
        }

        // Rotation window open: adopt canonical `.linux.do` (`host_only == false`) candidate first.
        let natural = valid
            .iter()
            .rev()
            .find(|(_, host_only)| !*host_only)
            .or_else(|| valid.last())
            .map(|(v, _)| v.clone());
        if let Some(ref win) = natural {
            if self.incumbent.as_deref() != Some(win.as_str()) {
                self.total_rotations = self.total_rotations.saturating_add(1);
            }
            self.incumbent = Some(win.clone());
            self.updated_at_ms = now_ms;
            return natural;
        }

        // Transient empty read protection: if browser returned 0 cf_clearance cookies transiently while incumbent is fresh & healthy.
        if candidates.is_empty() {
            if let Some(ref inc) = self.incumbent {
                if !self.is_challenged(inc)
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
        if clean.is_empty() || self.is_challenged(clean) {
            return false;
        }
        saved_updated_at_ms > 0
            && now_ms.saturating_sub(saved_updated_at_ms) <= CF_COLD_SEED_MAX_AGE_MS
    }
}

// ============================================================================
// 2. Single-Flight VerifyCoordinator with Monotonic Escalation & Circuit Breaker
// ============================================================================

/// Monotonic 4-tier UI intrusiveness level for a Cloudflare verification round.
/// `Silent(0) < Pill(1) < Blocking(2) < Manual(3)` — concurrent callers joining the same round
/// atomically take `max(current, requested)`, never downgrading, and waking the leader immediately.
pub const VERIFY_PILL_DELAY_MS: u64 = 2_000;

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
#[repr(u8)]
pub enum VerifyPresentation {
    /// Background prewarm / silent near-expiry refresh: never displays any UI; aborting never counts as failure.
    Silent = 0,
    /// Non-intrusive delayed corner progress pill (shown after 2s if hidden check still running; click opens panel).
    Pill = 1,
    /// Foreground user-waiting request: starts hidden on `/challenge`; if invisible JS passes -> 0 UI;
    /// >2s -> gentle corner Pill; if Turnstile triggers `interactiveBegin` -> promotes straight to panel on the SAME page!
    Blocking = 2,
    /// User explicitly clicked 「安全验证」: bypasses all cooldowns and displays the native sheet immediately.
    Manual = 3,
}

impl VerifyPresentation {
    pub fn from_u8(v: u8) -> Self {
        match v {
            0 => Self::Silent,
            1 => Self::Pill,
            2 => Self::Blocking,
            _ => Self::Manual,
        }
    }

    pub fn allows_pill_ui(self) -> bool {
        self >= Self::Pill
    }

    pub fn allows_panel_ui(self) -> bool {
        self >= Self::Blocking
    }

    pub fn allows_ui(self) -> bool {
        self.allows_panel_ui()
    }
}

// ============================================================================
// 1b. Adaptive Two-Stage CF 429 Challenge Classifier (Surpasses FluxDO)
// ============================================================================

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Cf429Decision {
    ShortCooldown { cooldown_ms: u64 },
    EscalateVerify,
}

/// Two-stage adaptive classifier for `429 + cf-mitigated: challenge`:
/// - When we hold a healthy incumbent clearance and hit a first transient 429 challenge burst,
///   apply a short backoff (`1800ms`) so a brief rate spike never kills a healthy ticket.
/// - If a 2nd consecutive 429 challenge hits within 12s (or no healthy incumbent exists),
///   immediately escalate to single-flight `VerifyCoordinator` rotation.
#[derive(Debug, Clone)]
pub struct Cf429AdaptiveClassifier {
    burst_window: Duration,
    first_hit_cooldown_ms: u64,
    consecutive_hits: u32,
    last_hit_at: Option<Instant>,
}

impl Default for Cf429AdaptiveClassifier {
    fn default() -> Self {
        Self {
            burst_window: Duration::from_secs(12),
            first_hit_cooldown_ms: 1_800,
            consecutive_hits: 0,
            last_hit_at: None,
        }
    }
}

impl Cf429AdaptiveClassifier {
    pub fn classify(&mut self, has_healthy_incumbent: bool, now: Instant) -> Cf429Decision {
        if let Some(prev) = self.last_hit_at {
            if now.saturating_duration_since(prev) > self.burst_window {
                self.consecutive_hits = 0;
            }
        }
        self.last_hit_at = Some(now);
        self.consecutive_hits = self.consecutive_hits.saturating_add(1);
        if has_healthy_incumbent && self.consecutive_hits == 1 {
            Cf429Decision::ShortCooldown {
                cooldown_ms: self.first_hit_cooldown_ms,
            }
        } else {
            Cf429Decision::EscalateVerify
        }
    }

    pub fn note_healthy_2xx(&mut self) {
        self.consecutive_hits = 0;
        self.last_hit_at = None;
    }

    pub fn reset(&mut self) {
        self.consecutive_hits = 0;
        self.last_hit_at = None;
    }
}

// ============================================================================
// 1c. P1 Primitives: CallDeadline (CF-Pauseable), CsrfFailureCooldown,
//     Logout Exemption & Idle Near-Expiry Silent Refresh Policy
// ============================================================================

pub const CF_NEAR_EXPIRY_SILENT_REFRESH_SECS: u64 = 10 * 3600 + 1800; // 10.5 hours
pub const CSRF_FAILURE_COOLDOWN_SECS: u64 = 30;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct BridgeTimeoutSpec {
    pub fetch_timeout_ms: u64,
    pub csrf_timeout_ms: u64,
    pub retry_fetch_timeout_ms: u64,
    pub js_hard_cap_ms: u64,
    pub rust_ipc_timeout_secs: u64,
}

/// Authoritative timeout budget matrix across JS `fetch()` / `AbortSignal`, JS outer hard-cap,
/// and Rust `native_call("evaluate", ..., timeout_secs)`.
/// Invariant: `rust_ipc_timeout_secs * 1000 >= js_hard_cap_ms + 5_000` across ALL lanes!
pub fn compute_bridge_timeouts(
    is_read: bool,
    is_background: bool,
    is_timings: bool,
) -> BridgeTimeoutSpec {
    if is_timings {
        return BridgeTimeoutSpec {
            fetch_timeout_ms: 6_000,
            csrf_timeout_ms: 5_000,
            retry_fetch_timeout_ms: 5_000,
            js_hard_cap_ms: 12_000,
            rust_ipc_timeout_secs: 18,
        };
    }
    match (is_read, is_background) {
        (true, true) => BridgeTimeoutSpec {
            fetch_timeout_ms: 9_000,
            csrf_timeout_ms: 0,
            retry_fetch_timeout_ms: 7_000,
            js_hard_cap_ms: 11_000,
            rust_ipc_timeout_secs: 17,
        },
        (false, true) => BridgeTimeoutSpec {
            fetch_timeout_ms: 10_000,
            csrf_timeout_ms: 6_000,
            retry_fetch_timeout_ms: 8_000,
            js_hard_cap_ms: 17_000,
            rust_ipc_timeout_secs: 23,
        },
        (true, false) => BridgeTimeoutSpec {
            fetch_timeout_ms: 12_000,
            csrf_timeout_ms: 0,
            retry_fetch_timeout_ms: 10_000,
            js_hard_cap_ms: 22_000,
            rust_ipc_timeout_secs: 28,
        },
        (false, false) => BridgeTimeoutSpec {
            fetch_timeout_ms: 15_000,
            csrf_timeout_ms: 8_000,
            retry_fetch_timeout_ms: 12_000,
            js_hard_cap_ms: 30_000,
            rust_ipc_timeout_secs: 36,
        },
    }
}

pub const BG_READ_WAVE_DEDUP_WINDOW: Duration = Duration::from_secs(5);
pub const BG_WRITE_ESCALATE_THRESHOLD: u32 = 2;
pub const BG_READ_WAVE_ESCALATE_THRESHOLD: u32 = 3;

/// Tracks background Cloudflare challenge hits separately for reads vs writes (aligned with Android `BackgroundChallengeTracker`):
/// - Concurrent background GETs within a 5s window count as a single `read_wave` (`>= 3` waves escalate).
/// - Background mutations (`POST /topics/timings`, `PUT /notifications/mark-read.json`, `POST /drafts.json`)
///   increment `consecutive_write_hits` (`>= 2` hits escalate).
/// - Critically, a foreground `GET 2xx` ONLY resets `read_waves`; it MUST NOT reset `consecutive_write_hits`,
///   preventing an infinite oscillation loop when Cloudflare allows GETs while rejecting stale IP-bound `cf_clearance` on writes!
#[derive(Debug, Clone, Default)]
pub struct BackgroundChallengeTracker {
    read_waves: u32,
    last_read_wave_at: Option<Instant>,
    consecutive_write_hits: u32,
}

impl BackgroundChallengeTracker {
    /// Records a background CF challenge and returns `true` when silent verification / attention should trigger.
    pub fn note_background_challenge(&mut self, is_write: bool, now: Instant) -> bool {
        if is_write {
            self.consecutive_write_hits = self.consecutive_write_hits.saturating_add(1);
        } else {
            let is_new_wave = self
                .last_read_wave_at
                .map(|prev| now.saturating_duration_since(prev) >= BG_READ_WAVE_DEDUP_WINDOW)
                .unwrap_or(true);
            if is_new_wave {
                self.last_read_wave_at = Some(now);
                self.read_waves = self.read_waves.saturating_add(1);
            }
        }
        self.consecutive_write_hits >= BG_WRITE_ESCALATE_THRESHOLD
            || self.read_waves >= BG_READ_WAVE_ESCALATE_THRESHOLD
    }

    pub fn note_healthy_response(&mut self, is_write: bool) {
        if is_write {
            self.consecutive_write_hits = 0;
        } else {
            self.read_waves = 0;
            self.last_read_wave_at = None;
        }
    }

    pub fn has_unresolved_write_challenges(&self) -> bool {
        self.consecutive_write_hits > 0
    }

    pub fn consecutive_write_hits(&self) -> u32 {
        self.consecutive_write_hits
    }

    pub fn read_waves(&self) -> u32 {
        self.read_waves
    }

    pub fn reset(&mut self) {
        self.read_waves = 0;
        self.last_read_wave_at = None;
        self.consecutive_write_hits = 0;
    }
}

/// End-to-end logical call deadline across queue wait, retries, and body read.
/// Supports `pause()` / `resume()` while user interacts with Cloudflare Turnstile
/// so manual verification never causes the waiting request to expire prematurely.
#[derive(Debug, Clone)]
pub struct CallDeadline {
    budget: Duration,
    started_at: Instant,
    paused_at: Option<Instant>,
    paused_total: Duration,
}

impl CallDeadline {
    pub fn new(budget: Duration, now: Instant) -> Self {
        Self {
            budget,
            started_at: now,
            paused_at: None,
            paused_total: Duration::ZERO,
        }
    }

    pub fn for_request(idempotent: bool, is_background: bool, now: Instant) -> Self {
        let secs = match (idempotent, is_background) {
            (true, true) => 20,
            (false, true) => 26,
            (true, false) => 34,
            (false, false) => 42,
        };
        Self::new(Duration::from_secs(secs), now)
    }

    pub fn pause(&mut self, now: Instant) {
        if self.paused_at.is_none() {
            self.paused_at = Some(now);
        }
    }

    pub fn resume(&mut self, now: Instant) {
        if let Some(p) = self.paused_at.take() {
            self.paused_total = self.paused_total.saturating_add(now.saturating_duration_since(p));
        }
    }

    pub fn elapsed_active(&self, now: Instant) -> Duration {
        let wall = now.saturating_duration_since(self.started_at);
        let current_pause = self
            .paused_at
            .map(|p| now.saturating_duration_since(p))
            .unwrap_or(Duration::ZERO);
        wall.saturating_sub(self.paused_total.saturating_add(current_pause))
    }

    pub fn remaining_ms(&self, now: Instant) -> u64 {
        self.budget
            .saturating_sub(self.elapsed_active(now))
            .as_millis() as u64
    }

    pub fn is_expired(&self, now: Instant) -> bool {
        self.elapsed_active(now) >= self.budget
    }
}

/// 30s cooldown on `/session/csrf.json` failures to prevent CSRF storming during 403/429 windows.
#[derive(Debug, Clone, Default)]
pub struct CsrfFailureCooldown {
    until: Option<Instant>,
}

impl CsrfFailureCooldown {
    pub fn record_failure(&mut self, now: Instant) {
        self.until = Some(now + Duration::from_secs(CSRF_FAILURE_COOLDOWN_SECS));
    }

    pub fn is_in_cooldown(&self, now: Instant) -> bool {
        self.until.map(|u| now < u).unwrap_or(false)
    }

    pub fn clear(&mut self) {
        self.until = None;
    }
}

/// Logout (`DELETE /session/...`) and ephemeral key self-revocation (`POST /user-api-key/revoke`)
/// must NEVER trigger 401/403 session self-heal loops on the current user session.
pub fn is_logout_exempt_from_session_recovery(method: &str, url: &str) -> bool {
    let lower_url = url.to_ascii_lowercase();
    (method.eq_ignore_ascii_case("DELETE") && lower_url.contains("/session/"))
        || (method.eq_ignore_ascii_case("POST") && lower_url.contains("/user-api-key/revoke"))
}

/// Zero-resident idle near-expiry silent refresh:
/// Triggers only when incumbent clearance age >= 10.5h, no foreground requests are active, and not verifying.
pub fn should_trigger_near_expiry_silent_refresh(
    incumbent_age_secs: Option<u64>,
    active_fg_requests: usize,
    is_verifying: bool,
) -> bool {
    if active_fg_requests > 0 || is_verifying {
        return false;
    }
    incumbent_age_secs
        .map(|age| age >= CF_NEAR_EXPIRY_SILENT_REFRESH_SECS)
        .unwrap_or(false)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct VerifyOutcome {
    pub passed: bool,
    pub round: u64,
    pub ineffective_blocked: bool,
    pub cooldown_remain_ms: u64,
}

#[derive(Debug, Clone, Default)]
pub struct VerifyHealthSnapshot {
    pub active: bool,
    pub round: u64,
    pub consecutive_failures: u32,
    pub cooldown_remain_ms: u64,
    pub cooldown_is_ineffective: bool,
    pub silent_backoff_remain_ms: u64,
    pub last_passed_age_secs: Option<u64>,
}

struct ActiveVerifyFlight {
    round: u64,
    presentation: Arc<AtomicU8>,
    escalate_notify: Arc<Notify>,
    tx: watch::Sender<Option<VerifyOutcome>>,
}

pub enum VerifyJoin {
    Leader {
        round: u64,
        presentation: Arc<AtomicU8>,
        escalate_notify: Arc<Notify>,
    },
    Follower {
        round: u64,
        rx: watch::Receiver<Option<VerifyOutcome>>,
    },
    RecentPass {
        round: u64,
    },
    BlockedByCooldown {
        remain_ms: u64,
        ineffective: bool,
    },
}

#[derive(Default)]
pub struct VerifyCoordinator {
    round: u64,
    active: Option<ActiveVerifyFlight>,
    consecutive_failures: u32,
    failure_counted_round: Option<u64>,
    cooldown_until: Option<Instant>,
    cooldown_is_ineffective: bool,
    silent_backoff_until: Option<Instant>,
    last_passed_round: u64,
    last_passed_at: Option<Instant>,
}

impl VerifyCoordinator {
    pub fn is_active(&self) -> bool {
        self.active.is_some()
    }

    pub fn current_round(&self) -> u64 {
        self.round
    }

    pub fn last_passed_round(&self) -> u64 {
        self.last_passed_round
    }

    pub fn join_or_start(
        &mut self,
        requested: VerifyPresentation,
        now: Instant,
        is_post_pass_replay: bool,
    ) -> VerifyJoin {
        // 1. If a verification round is already running, coalesce into it & escalate presentation monotonically!
        if let Some(ref flight) = self.active {
            let prev = flight.presentation.fetch_max(requested as u8, Ordering::SeqCst);
            if (requested as u8) > prev {
                flight.escalate_notify.notify_waiters();
            }
            return VerifyJoin::Follower {
                round: flight.round,
                rx: flight.tx.subscribe(),
            };
        }

        // Expire stale cooldowns cleanly.
        if let Some(until) = self.cooldown_until {
            if now >= until {
                self.cooldown_until = None;
                self.cooldown_is_ineffective = false;
                self.consecutive_failures = 0;
                self.failure_counted_round = None;
            }
        }
        if let Some(until) = self.silent_backoff_until {
            if now >= until {
                self.silent_backoff_until = None;
            }
        }

        // 2. Manual user click ALWAYS bypasses auto-cooldowns and recent-pass coalescing.
        if requested != VerifyPresentation::Manual {
            if let Some(until) = self.cooldown_until {
                let remain_ms = until.saturating_duration_since(now).as_millis() as u64;
                if remain_ms > 0 {
                    return VerifyJoin::BlockedByCooldown {
                        remain_ms,
                        ineffective: self.cooldown_is_ineffective,
                    };
                }
            }
            if requested == VerifyPresentation::Silent {
                if let Some(until) = self.silent_backoff_until {
                    let remain_ms = until.saturating_duration_since(now).as_millis() as u64;
                    if remain_ms > 0 {
                        return VerifyJoin::BlockedByCooldown {
                            remain_ms,
                            ineffective: false,
                        };
                    }
                }
            }
            if !is_post_pass_replay {
                if let Some(passed_at) = self.last_passed_at {
                    if now.saturating_duration_since(passed_at)
                        <= Duration::from_secs(VERIFY_RECENT_PASS_COALESCE_SECS)
                    {
                        return VerifyJoin::RecentPass {
                            round: self.last_passed_round,
                        };
                    }
                }
            }
        }

        // 3. Start a new authoritative round as the single leader.
        self.round = self.round.saturating_add(1);
        let round = self.round;
        let presentation = Arc::new(AtomicU8::new(requested as u8));
        let escalate_notify = Arc::new(Notify::new());
        let (tx, _rx) = watch::channel(None);

        self.active = Some(ActiveVerifyFlight {
            round,
            presentation: Arc::clone(&presentation),
            escalate_notify: Arc::clone(&escalate_notify),
            tx,
        });

        VerifyJoin::Leader {
            round,
            presentation,
            escalate_notify,
        }
    }

    pub fn finish_flight(
        &mut self,
        round: u64,
        passed: bool,
        abandoned: bool,
        final_presentation: VerifyPresentation,
        now: Instant,
    ) -> VerifyOutcome {
        let flight = match self.active.as_ref() {
            Some(f) if f.round == round => self.active.take(),
            _ => None,
        };

        if passed {
            self.consecutive_failures = 0;
            self.failure_counted_round = None;
            self.cooldown_until = None;
            self.cooldown_is_ineffective = false;
            self.silent_backoff_until = None;
            self.last_passed_round = round;
            self.last_passed_at = Some(now);
        } else if !abandoned {
            if final_presentation == VerifyPresentation::Silent {
                // Silent background probe hit interactive challenge: do NOT count as a user failure,
                // only apply a gentle silent backoff so background polls never spin `/challenge`.
                self.silent_backoff_until = Some(now + Duration::from_secs(VERIFY_SILENT_BACKOFF_SECS));
            } else {
                self.record_round_failure_locked(Some(round), now);
            }
        }

        let cooldown_remain_ms = self
            .cooldown_until
            .and_then(|u| u.checked_duration_since(now))
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0);

        let outcome = VerifyOutcome {
            passed,
            round,
            ineffective_blocked: self.cooldown_is_ineffective && cooldown_remain_ms > 0,
            cooldown_remain_ms,
        };

        if let Some(f) = flight {
            let _ = f.tx.send(Some(outcome));
        }
        outcome
    }

    /// Round-deduplicated failure accounting: N concurrent callers failing round K only count once.
    pub fn record_round_failure(&mut self, round: Option<u64>, now: Instant) -> u64 {
        self.record_round_failure_locked(round, now);
        self.cooldown_until
            .and_then(|u| u.checked_duration_since(now))
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0)
    }

    fn record_round_failure_locked(&mut self, round: Option<u64>, now: Instant) {
        if let Some(r) = round {
            if self.failure_counted_round == Some(r) {
                return;
            }
            self.failure_counted_round = Some(r);
        }
        self.consecutive_failures = self.consecutive_failures.saturating_add(1);
        if self.consecutive_failures >= VERIFY_MAX_FAILURES_BEFORE_COOLDOWN {
            self.cooldown_until = Some(now + Duration::from_secs(VERIFY_FAILURE_COOLDOWN_SECS));
            self.cooldown_is_ineffective = false;
        }
    }

    /// Circuit breaker: verification round `round` succeeded, yet the immediate replay STILL got CF challenged!
    /// Enter 60s ineffective-clearance cooldown immediately so the app NEVER enters an infinite popup loop.
    pub fn record_ineffective_clearance(&mut self, round: u64, now: Instant) -> u64 {
        let within_window = self.last_passed_round == round
            && self
                .last_passed_at
                .map(|t| now.saturating_duration_since(t) <= Duration::from_secs(VERIFY_INEFFECTIVE_WINDOW_SECS))
                .unwrap_or(true);
        if within_window || self.failure_counted_round != Some(round) {
            self.failure_counted_round = Some(round);
            self.consecutive_failures = VERIFY_MAX_FAILURES_BEFORE_COOLDOWN;
            self.cooldown_until = Some(now + Duration::from_secs(VERIFY_INEFFECTIVE_COOLDOWN_SECS));
            self.cooldown_is_ineffective = true;
            // Invalidate recent-pass coalescing so subsequent requests immediately observe the breaker.
            self.last_passed_at = None;
        }
        self.cooldown_until
            .and_then(|u| u.checked_duration_since(now))
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0)
    }

    pub fn reset_on_healthy_response(&mut self) {
        self.consecutive_failures = 0;
        self.failure_counted_round = None;
        self.cooldown_until = None;
        self.cooldown_is_ineffective = false;
        self.silent_backoff_until = None;
    }

    pub fn reset_on_session_change(&mut self) {
        self.reset_on_healthy_response();
        self.last_passed_at = None;
    }

    pub fn snapshot(&self, now: Instant) -> VerifyHealthSnapshot {
        VerifyHealthSnapshot {
            active: self.active.is_some(),
            round: self.round,
            consecutive_failures: self.consecutive_failures,
            cooldown_remain_ms: self
                .cooldown_until
                .and_then(|u| u.checked_duration_since(now))
                .map(|d| d.as_millis() as u64)
                .unwrap_or(0),
            cooldown_is_ineffective: self.cooldown_is_ineffective,
            silent_backoff_remain_ms: self
                .silent_backoff_until
                .and_then(|u| u.checked_duration_since(now))
                .map(|d| d.as_millis() as u64)
                .unwrap_or(0),
            last_passed_age_secs: self
                .last_passed_at
                .map(|t| now.saturating_duration_since(t).as_secs()),
        }
    }
}

/// RAII safety guard guaranteeing that even if a verification leader task is cancelled/dropped mid-flight,
/// `active` is cleaned up and all waiting followers are unblocked immediately.
pub struct VerifyFlightGuard {
    coord: Arc<StdMutex<VerifyCoordinator>>,
    round: u64,
    presentation: Arc<AtomicU8>,
    finished: bool,
}

impl VerifyFlightGuard {
    pub fn new(
        coord: Arc<StdMutex<VerifyCoordinator>>,
        round: u64,
        presentation: Arc<AtomicU8>,
    ) -> Self {
        Self {
            coord,
            round,
            presentation,
            finished: false,
        }
    }

    pub fn complete(mut self, passed: bool, now: Instant) -> VerifyOutcome {
        self.finished = true;
        let final_pres = VerifyPresentation::from_u8(self.presentation.load(Ordering::SeqCst));
        match self.coord.lock() {
            Ok(mut c) => c.finish_flight(self.round, passed, false, final_pres, now),
            Err(_) => VerifyOutcome {
                passed,
                round: self.round,
                ineffective_blocked: false,
                cooldown_remain_ms: 0,
            },
        }
    }

    pub fn abandon(mut self, now: Instant) -> VerifyOutcome {
        self.finished = true;
        let final_pres = VerifyPresentation::from_u8(self.presentation.load(Ordering::SeqCst));
        match self.coord.lock() {
            Ok(mut c) => c.finish_flight(self.round, false, true, final_pres, now),
            Err(_) => VerifyOutcome {
                passed: false,
                round: self.round,
                ineffective_blocked: false,
                cooldown_remain_ms: 0,
            },
        }
    }
}

impl Drop for VerifyFlightGuard {
    fn drop(&mut self) {
        if !self.finished {
            let final_pres = VerifyPresentation::from_u8(self.presentation.load(Ordering::SeqCst));
            if let Ok(mut c) = self.coord.lock() {
                let _ = c.finish_flight(self.round, false, true, final_pres, Instant::now());
            }
        }
    }
}

// ============================================================================
// 3. PriorityGate — 3-Tier Fair Scheduler with Anti-Starvation & Drop Cancel
// ============================================================================

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
#[repr(u8)]
pub enum RequestPriority {
    Low = 0,    // Background polls, timings telemetry
    Normal = 1, // Foreground GET reads
    High = 2,   // Interactive user writes (POST / PUT / DELETE / Upload)
}

const AGING_BOOST_AFTER: Duration = Duration::from_secs(4);

struct PriorityWaiter {
    seq: u64,
    priority: RequestPriority,
    enqueued_at: Instant,
    tx: oneshot::Sender<PriorityPermit>,
}

struct PriorityGateState {
    inflight: usize,
    low_inflight: usize,
    max_inflight: usize,
    max_low_inflight: usize,
    waiters: Vec<PriorityWaiter>,
}

/// Strict 3-tier priority concurrency gate:
/// - `High` (interactive writes) > `Normal` (foreground GET) > `Low` (background polls), FIFO within tier.
/// - Anti-starvation aging: any task waiting >= 4s gains +1 effective priority tier.
/// - Background cap (`max_low_inflight`): background polls can never occupy all concurrency slots.
/// - O(1) async cancellation: dropped futures automatically drop their `oneshot::Receiver` and are pruned
///   without leaking permits.
pub struct PriorityGate {
    inner: Arc<StdMutex<PriorityGateState>>,
    next_seq: AtomicU64,
}

pub struct PriorityPermit {
    gate: Arc<StdMutex<PriorityGateState>>,
    is_low: bool,
}

impl PriorityGate {
    pub fn new(max_inflight: usize, max_low_inflight: usize) -> Self {
        Self {
            inner: Arc::new(StdMutex::new(PriorityGateState {
                inflight: 0,
                low_inflight: 0,
                max_inflight: max_inflight.max(1),
                max_low_inflight: max_low_inflight.max(1).min(max_inflight.max(1)),
                waiters: Vec::new(),
            })),
            next_seq: AtomicU64::new(1),
        }
    }

    pub async fn acquire(&self, priority: RequestPriority) -> PriorityPermit {
        let rx = {
            let mut st = self.inner.lock().unwrap();
            let is_low = priority == RequestPriority::Low;
            let has_slot = st.inflight < st.max_inflight
                && (!is_low || st.low_inflight < st.max_low_inflight);
            // Fast path: grant immediately if slot available and no strictly higher-priority waiter exists.
            let higher_waiting = st.waiters.iter().any(|w| !w.tx.is_closed() && w.priority > priority);
            if has_slot && !higher_waiting {
                st.inflight += 1;
                if is_low {
                    st.low_inflight += 1;
                }
                return PriorityPermit {
                    gate: Arc::clone(&self.inner),
                    is_low,
                };
            }
            let (tx, rx) = oneshot::channel();
            let seq = self.next_seq.fetch_add(1, Ordering::Relaxed);
            st.waiters.push(PriorityWaiter {
                seq,
                priority,
                enqueued_at: Instant::now(),
                tx,
            });
            rx
        };

        match rx.await {
            Ok(permit) => permit,
            // Should never happen while gate lives, fallback safely.
            Err(_) => PriorityPermit {
                gate: Arc::clone(&self.inner),
                is_low: priority == RequestPriority::Low,
            },
        }
    }

    pub fn stats(&self) -> (usize, usize) {
        let st = self.inner.lock().unwrap();
        let live_waiters = st.waiters.iter().filter(|w| !w.tx.is_closed()).count();
        (st.inflight, live_waiters)
    }
}

fn dispatch_next_waiters(gate: &Arc<StdMutex<PriorityGateState>>) {
    let mut to_send: Vec<(oneshot::Sender<PriorityPermit>, PriorityPermit)> = Vec::new();
    {
        let mut st = gate.lock().unwrap();
        let now = Instant::now();
        // Prune cancelled futures first.
        st.waiters.retain(|w| !w.tx.is_closed());

        while st.inflight < st.max_inflight && !st.waiters.is_empty() {
            let best_idx = st
                .waiters
                .iter()
                .enumerate()
                .filter(|(_, w)| {
                    w.priority != RequestPriority::Low || st.low_inflight < st.max_low_inflight
                })
                .max_by(|(_, a), (_, b)| {
                    let eff_a = (a.priority as u8)
                        .saturating_add(u8::from(now.saturating_duration_since(a.enqueued_at) >= AGING_BOOST_AFTER))
                        .min(RequestPriority::High as u8);
                    let eff_b = (b.priority as u8)
                        .saturating_add(u8::from(now.saturating_duration_since(b.enqueued_at) >= AGING_BOOST_AFTER))
                        .min(RequestPriority::High as u8);
                    eff_a.cmp(&eff_b).then_with(|| b.seq.cmp(&a.seq))
                })
                .map(|(idx, _)| idx);

            let Some(idx) = best_idx else {
                break;
            };
            let waiter = st.waiters.swap_remove(idx);
            let is_low = waiter.priority == RequestPriority::Low;
            st.inflight += 1;
            if is_low {
                st.low_inflight += 1;
            }
            to_send.push((
                waiter.tx,
                PriorityPermit {
                    gate: Arc::clone(gate),
                    is_low,
                },
            ));
        }
    }

    // Send outside mutex! If receiver dropped in the tiny race window, `Err(permit)` drops `permit`,
    // which automatically releases the slot and re-dispatches to the next waiter.
    for (tx, permit) in to_send {
        let _ = tx.send(permit);
    }
}

impl Drop for PriorityPermit {
    fn drop(&mut self) {
        {
            let mut st = self.gate.lock().unwrap();
            st.inflight = st.inflight.saturating_sub(1);
            if self.is_low {
                st.low_inflight = st.low_inflight.saturating_sub(1);
            }
        }
        dispatch_next_waiters(&self.gate);
    }
}

// ============================================================================
// 4. AdaptiveTokenBucket — AIMD Congestion-Controlled Smooth Pacer
// ============================================================================

pub struct AdaptiveTokenBucket {
    tokens: f64,
    capacity: f64,
    refill_per_sec: f64,
    min_refill_per_sec: f64,
    max_refill_per_sec: f64,
    last_refill: Instant,
}

impl AdaptiveTokenBucket {
    pub fn new(capacity: f64, nominal_rate: f64, min_rate: f64, max_rate: f64) -> Self {
        Self {
            tokens: capacity,
            capacity,
            refill_per_sec: nominal_rate.clamp(min_rate, max_rate),
            min_refill_per_sec: min_rate,
            max_refill_per_sec: max_rate,
            last_refill: Instant::now(),
        }
    }

    fn refill_to(&mut self, now: Instant) {
        let elapsed = now.saturating_duration_since(self.last_refill).as_secs_f64();
        if elapsed > 0.0 {
            self.tokens = (self.tokens + elapsed * self.refill_per_sec).min(self.capacity);
            self.last_refill = now;
        }
    }

    /// Reserves 1 request slot using virtual-time debt so concurrent bursts glide smoothly
    /// without thundering herds or holding a mutex across `.await`.
    pub fn reserve(&mut self, now: Instant, min_floor: f64) -> Duration {
        self.refill_to(now);
        let needed_level = 1.0 + min_floor;
        let wait_secs = if self.tokens >= needed_level {
            0.0
        } else {
            ((needed_level - self.tokens) / self.refill_per_sec).min(6.5)
        };
        self.tokens -= 1.0;
        Duration::from_secs_f64(wait_secs)
    }

    /// AIMD multiplicative decrease on genuine 429 rate-limit: cuts send rate cleanly and drains burst debt.
    pub fn on_rate_limit(&mut self, now: Instant) {
        self.refill_to(now);
        self.refill_per_sec = (self.refill_per_sec * 0.55).max(self.min_refill_per_sec);
        if self.tokens > 0.0 {
            self.tokens = 0.0;
        }
    }

    /// AIMD additive increase on healthy 2xx response: gently restores send rate back to nominal ceiling.
    pub fn on_success(&mut self, now: Instant) {
        self.refill_to(now);
        if self.refill_per_sec < self.max_refill_per_sec {
            self.refill_per_sec = (self.refill_per_sec + 0.12).min(self.max_refill_per_sec);
        }
    }

    pub fn current_rate(&self) -> f64 {
        self.refill_per_sec
    }
}

// ============================================================================
// 5. Complete RFC 7231 + X-RateLimit-Reset + Discourse Retry-After Parser
// ============================================================================

/// Extracts exact wait seconds from a 429/503 response using all Linux.do / Discourse conventions:
/// 1. Standard `Retry-After` (delta seconds OR RFC 7231 HTTP-Date e.g. `Wed, 21 Oct 2026 07:28:00 GMT`)
/// 2. `X-RateLimit-Reset` / `RateLimit-Reset` (delta seconds OR Unix epoch timestamp)
/// 3. JSON body `extras.wait_seconds` or `extras.time_left`
/// 4. Discourse Chinese (`请等待 N 天/小时/分钟/秒`) & English (`Please wait N second(s)/minute(s)/hour(s)/day(s)`) text
pub fn parse_retry_after_secs(
    retry_after_header: Option<&str>,
    ratelimit_reset_header: Option<&str>,
    body: &str,
    now_epoch_secs: i64,
) -> Option<u64> {
    if let Some(raw) = retry_after_header.map(str::trim).filter(|s| !s.is_empty()) {
        if let Ok(secs) = raw.parse::<u64>() {
            if secs > 0 {
                return Some(secs);
            }
        }
        // RFC 7231 IMF-fixdate (identical to RFC 2822 with GMT/UTC)
        if let Ok(dt) = DateTime::parse_from_rfc2822(raw) {
            let delta = dt.timestamp() - now_epoch_secs;
            if delta > 0 {
                return Some(delta as u64);
            }
        }
        // Fallback: some servers emit `+0000` or RFC 3339 timestamps
        if let Ok(dt) = DateTime::parse_from_rfc3339(raw) {
            let delta = dt.timestamp() - now_epoch_secs;
            if delta > 0 {
                return Some(delta as u64);
            }
        }
    }

    if let Some(raw) = ratelimit_reset_header.map(str::trim).filter(|s| !s.is_empty()) {
        if let Ok(val) = raw.parse::<i64>() {
            if val > 0 {
                let delta = if val > 1_000_000_000 {
                    val - now_epoch_secs
                } else {
                    val
                };
                if delta > 0 {
                    return Some(delta as u64);
                }
            }
        }
    }

    if let Ok(json_val) = serde_json::from_str::<Value>(body) {
        if let Some(extras) = json_val.get("extras").and_then(|v| v.as_object()) {
            for key in ["wait_seconds", "time_left"] {
                if let Some(raw_v) = extras.get(key) {
                    let parsed = raw_v
                        .as_u64()
                        .or_else(|| raw_v.as_str().and_then(|s| s.trim().parse::<u64>().ok()));
                    if let Some(secs) = parsed.filter(|&s| s > 0) {
                        return Some(secs);
                    }
                }
            }
        }
        if let Some(err_str) = json_val.get("error").and_then(|v| v.as_str()) {
            if let Some(secs) = parse_wait_secs_from_text(err_str) {
                return Some(secs);
            }
        }
        if let Some(arr) = json_val.get("errors").and_then(|v| v.as_array()) {
            for item in arr {
                if let Some(s) = item.as_str() {
                    if let Some(secs) = parse_wait_secs_from_text(s) {
                        return Some(secs);
                    }
                }
            }
        } else if let Some(s) = json_val.get("errors").and_then(|v| v.as_str()) {
            if let Some(secs) = parse_wait_secs_from_text(s) {
                return Some(secs);
            }
        }
    }

    parse_wait_secs_from_text(body)
}

fn parse_wait_secs_from_text(text: &str) -> Option<u64> {
    // Chinese pattern: "请等待 29 秒" / "请等待 2 分钟" / "请等待 1 小时" / "请等待 1 天"
    if let Some(idx) = text.find("请等待") {
        let rest = text[idx + "请等待".len()..].trim_start();
        let digits_len = rest.bytes().take_while(|b| b.is_ascii_digit()).count();
        if digits_len > 0 {
            if let Ok(num) = rest[..digits_len].parse::<u64>() {
                let unit_rest = rest[digits_len..].trim_start();
                let mult = if unit_rest.starts_with("天") {
                    Some(86_400u64)
                } else if unit_rest.starts_with("小时") {
                    Some(3_600)
                } else if unit_rest.starts_with("分钟") {
                    Some(60)
                } else if unit_rest.starts_with("秒") {
                    Some(1)
                } else {
                    None
                };
                if let Some(m) = mult {
                    return num.checked_mul(m).filter(|&s| s > 0);
                }
            }
        }
    }

    // English pattern (case-insensitive): "Please wait 30 seconds" / "Please wait 2 minutes"
    let lower = text.to_ascii_lowercase();
    if let Some(idx) = lower.find("please wait") {
        let rest = lower[idx + "please wait".len()..].trim_start();
        let digits_len = rest.bytes().take_while(|b| b.is_ascii_digit()).count();
        if digits_len > 0 {
            if let Ok(num) = rest[..digits_len].parse::<u64>() {
                let unit_rest = rest[digits_len..].trim_start();
                let mult = if unit_rest.starts_with("day") {
                    Some(86_400u64)
                } else if unit_rest.starts_with("hour") {
                    Some(3_600)
                } else if unit_rest.starts_with("minute") {
                    Some(60)
                } else if unit_rest.starts_with("second") {
                    Some(1)
                } else {
                    None
                };
                if let Some(m) = mult {
                    return num.checked_mul(m).filter(|&s| s > 0);
                }
            }
        }
    }

    None
}

// ============================================================================
// 6. Structural AttemptBudget & Jittered Idempotent 5xx Backoff
// ============================================================================

/// Structural anti-loop budget for a single request lifecycle (inspired by FluxDO `AttemptBudget`).
#[derive(Debug, Clone, Copy)]
pub struct AttemptBudget {
    pub transient_used: u8,
    pub max_transient: u8,
    pub cf_used: u8,
    pub max_cf: u8,
}

impl AttemptBudget {
    pub fn for_request(idempotent: bool) -> Self {
        Self {
            transient_used: 0,
            max_transient: if idempotent { 2 } else { 0 },
            cf_used: 0,
            max_cf: 1,
        }
    }

    pub fn try_consume_transient(&mut self) -> Option<u8> {
        if self.transient_used < self.max_transient {
            let attempt_idx = self.transient_used;
            self.transient_used += 1;
            Some(attempt_idx)
        } else {
            None
        }
    }

    pub fn try_consume_cf(&mut self) -> bool {
        if self.cf_used < self.max_cf {
            self.cf_used += 1;
            true
        } else {
            false
        }
    }
}

pub fn is_transient_retryable_status(status: u16, is_challenge: bool) -> bool {
    !is_challenge && matches!(status, 502 | 503 | 504)
}

/// Computes decorrelated jittered backoff (ms) for transient 502/503/504 / network errors.
/// `jitter_unit` is in `[0.0, 1.0)`.
pub fn transient_backoff_ms(attempt_idx: u8, jitter_unit: f64) -> u64 {
    let base_ms: f64 = match attempt_idx {
        0 => 550.0,
        1 => 1350.0,
        _ => 2400.0,
    };
    let clamped = jitter_unit.clamp(0.0, 0.999);
    // ±30% jitter prevents synchronized retry storms across concurrent requests
    let factor = 0.70 + clamped * 0.60;
    (base_ms * factor).round() as u64
}

// ============================================================================
// Unit Tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cf_authority_v2_uses_pure_incumbent_stickiness_without_permanent_blacklist() {
        let now = 1_700_000_000_000u64;
        let mut auth = CfClearanceAuthority::default();
        auth.confirm_healthy("cf_a", now);
        assert!(auth.has_healthy_incumbent());

        // 1. Sticky healthy incumbent survives stray host-only variants and transient empty reads
        let candidates = vec![("cf_a".to_string(), false), ("stray".to_string(), true)];
        assert_eq!(auth.select_winner(&candidates, false, now + 5_000).as_deref(), Some("cf_a"));
        assert_eq!(auth.select_winner(&[], false, now + 10_000).as_deref(), Some("cf_a"));

        // 2. Challenge marks ONLY incumbent `cf_a` as challenged -> rotation opens to `cf_b`
        auth.note_challenged(Some("cf_a"));
        assert!(!auth.has_healthy_incumbent());
        assert!(auth.is_challenged("cf_a"));

        let post_verify = vec![("cf_a".to_string(), true), ("cf_b".to_string(), false)];
        assert_eq!(auth.select_winner(&post_verify, true, now + 20_000).as_deref(), Some("cf_b"));
        assert_eq!(auth.last_challenged, None);
        assert_eq!(auth.incumbent.as_deref(), Some("cf_b"));
        assert!(!auth.is_challenged("cf_a")); // No permanent blacklist!

        // 3. Natural rotation window (> 11h) seamlessly adopts newer `.linux.do` clearance without challenge
        let later = now + CF_NATURAL_ROTATION_AGE_MS + 60_000;
        let rotated = vec![("cf_b".to_string(), false), ("cf_c".to_string(), false)];
        assert_eq!(auth.select_winner(&rotated, false, later).as_deref(), Some("cf_c"));
    }

    #[test]
    fn verify_coordinator_coalesces_escalates_and_deduplicates_round_failures() {
        let t0 = Instant::now();
        let mut coord = VerifyCoordinator::default();

        // 1. Background starts Silent round #1
        let (r1, pres1, _notify1) = match coord.join_or_start(VerifyPresentation::Silent, t0, false) {
            VerifyJoin::Leader {
                round,
                presentation,
                escalate_notify,
            } => (round, presentation, escalate_notify),
            _ => panic!("expected leader"),
        };
        assert_eq!(r1, 1);
        assert_eq!(VerifyPresentation::from_u8(pres1.load(Ordering::SeqCst)), VerifyPresentation::Silent);

        // 2. Concurrent foreground request joins round #1 and monotonically escalates Silent -> Blocking!
        let mut follower_rx = match coord.join_or_start(VerifyPresentation::Blocking, t0 + Duration::from_millis(50), false) {
            VerifyJoin::Follower { round, rx } => {
                assert_eq!(round, 1);
                rx
            }
            _ => panic!("expected follower"),
        };
        assert_eq!(VerifyPresentation::from_u8(pres1.load(Ordering::SeqCst)), VerifyPresentation::Blocking);

        // 3. Round #1 cancelled/failed: BOTH leader and follower receive identical outcome, failure counted ONCE!
        let outcome = coord.finish_flight(r1, false, false, VerifyPresentation::Blocking, t0 + Duration::from_secs(2));
        assert!(!outcome.passed);
        assert_eq!(outcome.round, 1);
        assert_eq!(follower_rx.borrow_and_update().unwrap(), outcome);
        assert_eq!(coord.snapshot(t0 + Duration::from_secs(2)).consecutive_failures, 1);

        // Extra duplicate failure reports for round #1 are ignored (round deduplication!)
        coord.record_round_failure(Some(1), t0 + Duration::from_secs(2));
        coord.record_round_failure(Some(1), t0 + Duration::from_secs(2));
        assert_eq!(coord.snapshot(t0 + Duration::from_secs(2)).consecutive_failures, 1);

        // 4. Two more distinct failed rounds (#2, #3) trigger 30s auto-verify cooldown
        for expected_round in 2..=3u64 {
            let t = t0 + Duration::from_secs(expected_round * 3);
            assert!(matches!(
                coord.join_or_start(VerifyPresentation::Blocking, t, false),
                VerifyJoin::Leader { round, .. } if round == expected_round
            ));
            coord.finish_flight(expected_round, false, false, VerifyPresentation::Blocking, t + Duration::from_secs(1));
        }

        let t_cool = t0 + Duration::from_secs(12);
        assert!(matches!(
            coord.join_or_start(VerifyPresentation::Blocking, t_cool, false),
            VerifyJoin::BlockedByCooldown { remain_ms, ineffective: false } if remain_ms > 20_000
        ));

        // 5. Explicit Manual click bypasses cooldown!
        assert!(matches!(
            coord.join_or_start(VerifyPresentation::Manual, t_cool, false),
            VerifyJoin::Leader { round: 4, .. }
        ));
        coord.finish_flight(4, true, false, VerifyPresentation::Manual, t_cool + Duration::from_secs(2));
        assert_eq!(coord.snapshot(t_cool + Duration::from_secs(2)).consecutive_failures, 0);

        // 6. Recent-pass coalescing within 5s for fresh callers
        assert!(matches!(
            coord.join_or_start(VerifyPresentation::Blocking, t_cool + Duration::from_secs(4), false),
            VerifyJoin::RecentPass { round: 4 }
        ));

        // 7. Ineffective clearance circuit breaker (60s) when replay after passed round STILL hits CF
        let breaker_ms = coord.record_ineffective_clearance(4, t_cool + Duration::from_secs(4));
        assert!(breaker_ms >= 59_000);
        assert!(matches!(
            coord.join_or_start(VerifyPresentation::Blocking, t_cool + Duration::from_secs(5), false),
            VerifyJoin::BlockedByCooldown { remain_ms, ineffective: true } if remain_ms >= 58_000
        ));
    }

    #[test]
    fn silent_abort_never_counts_as_user_failure() {
        let t0 = Instant::now();
        let mut coord = VerifyCoordinator::default();
        assert!(matches!(
            coord.join_or_start(VerifyPresentation::Silent, t0, false),
            VerifyJoin::Leader { round: 1, .. }
        ));
        coord.finish_flight(1, false, false, VerifyPresentation::Silent, t0 + Duration::from_secs(2));
        let snap = coord.snapshot(t0 + Duration::from_secs(2));
        assert_eq!(snap.consecutive_failures, 0);
        assert_eq!(snap.cooldown_remain_ms, 0);
        assert!(snap.silent_backoff_remain_ms >= 44_000);
        // Foreground Blocking request is NOT blocked by silent backoff!
        assert!(matches!(
            coord.join_or_start(VerifyPresentation::Blocking, t0 + Duration::from_secs(3), false),
            VerifyJoin::Leader { round: 2, .. }
        ));
    }

    #[test]
    fn retry_after_parser_handles_rfc7231_dates_ratelimit_reset_json_and_cn_en_text() {
        let now = 1_792_567_650i64; // 2026-10-21 07:27:30 GMT

        // 1. Numeric Retry-After
        assert_eq!(parse_retry_after_secs(Some("17"), None, "", now), Some(17));

        // 2. RFC 7231 HTTP-Date Retry-After (30s in the future)
        assert_eq!(
            parse_retry_after_secs(Some("Wed, 21 Oct 2026 07:28:00 GMT"), None, "", now),
            Some(30)
        );

        // 3. X-RateLimit-Reset (Unix epoch & relative)
        assert_eq!(
            parse_retry_after_secs(None, Some("1792567695"), "", now),
            Some(45)
        );
        assert_eq!(parse_retry_after_secs(None, Some("12"), "", now), Some(12));

        // 4. Discourse JSON extras.wait_seconds & extras.time_left
        assert_eq!(
            parse_retry_after_secs(None, None, r#"{"extras":{"wait_seconds":29}}"#, now),
            Some(29)
        );
        assert_eq!(
            parse_retry_after_secs(None, None, r#"{"extras":{"time_left":"18"}}"#, now),
            Some(18)
        );

        // 5. Discourse Chinese & English error text
        assert_eq!(
            parse_retry_after_secs(None, None, r#"{"errors":["操作过快，请等待 2 分钟后再试"]}"#, now),
            Some(120)
        );
        assert_eq!(
            parse_retry_after_secs(None, None, r#"{"errors":["Please wait 45 seconds before trying again."]}"#, now),
            Some(45)
        );
    }

    #[test]
    fn adaptive_token_bucket_aimd_decreases_on_429_and_recovers_on_200() {
        let t0 = Instant::now();
        let mut pacer = AdaptiveTokenBucket::new(5.0, 3.4, 1.1, 3.8);
        assert!((pacer.current_rate() - 3.4).abs() < 1e-6);

        pacer.on_rate_limit(t0);
        assert!((pacer.current_rate() - 1.87).abs() < 0.05);
        // Burst drained on 429
        assert!(pacer.reserve(t0, 0.0) > Duration::from_millis(450));

        // Repeated 2xx smoothly recovers up to max ceiling
        for i in 1..=25 {
            pacer.on_success(t0 + Duration::from_secs(i));
        }
        assert!((pacer.current_rate() - 3.8).abs() < 1e-6);
    }

    #[tokio::test]
    async fn priority_gate_orders_writes_ahead_of_reads_and_handles_drop_cancellation() {
        let gate = Arc::new(PriorityGate::new(1, 1));
        let hold = gate.acquire(RequestPriority::Normal).await;

        let order = Arc::new(StdMutex::new(Vec::new()));

        let g_low = Arc::clone(&gate);
        let o_low = Arc::clone(&order);
        let t_low = tokio::spawn(async move {
            let _p = g_low.acquire(RequestPriority::Low).await;
            o_low.lock().unwrap().push("low");
        });

        let g_norm = Arc::clone(&gate);
        let o_norm = Arc::clone(&order);
        let t_norm = tokio::spawn(async move {
            let _p = g_norm.acquire(RequestPriority::Normal).await;
            o_norm.lock().unwrap().push("normal");
        });

        let g_high = Arc::clone(&gate);
        let o_high = Arc::clone(&order);
        let t_high = tokio::spawn(async move {
            let _p = g_high.acquire(RequestPriority::High).await;
            o_high.lock().unwrap().push("high");
        });

        // Also spawn a waiter and cancel it immediately to verify zero permit leak
        let g_cancel = Arc::clone(&gate);
        let t_cancel = tokio::spawn(async move {
            let _p = g_cancel.acquire(RequestPriority::High).await;
        });
        tokio::time::sleep(Duration::from_millis(25)).await;
        t_cancel.abort();
        let _ = t_cancel.await;

        drop(hold);
        let _ = tokio::join!(t_high, t_norm, t_low);

        assert_eq!(*order.lock().unwrap(), vec!["high", "normal", "low"]);
        assert_eq!(gate.stats(), (0, 0));
    }

    #[test]
    fn attempt_budget_restricts_transient_retries_to_idempotent_methods() {
        let mut get_budget = AttemptBudget::for_request(true);
        assert_eq!(get_budget.try_consume_transient(), Some(0));
        assert_eq!(get_budget.try_consume_transient(), Some(1));
        assert_eq!(get_budget.try_consume_transient(), None);
        assert!(get_budget.try_consume_cf());
        assert!(!get_budget.try_consume_cf());

        let mut post_budget = AttemptBudget::for_request(false);
        assert_eq!(post_budget.try_consume_transient(), None);
        assert!(post_budget.try_consume_cf());
        assert!(!post_budget.try_consume_cf());

        assert!(is_transient_retryable_status(502, false));
        assert!(is_transient_retryable_status(503, false));
        assert!(!is_transient_retryable_status(503, true));
        assert!(!is_transient_retryable_status(500, false));
        assert!(transient_backoff_ms(0, 0.5) >= 500 && transient_backoff_ms(0, 0.5) <= 600);
    }

    #[test]
    fn verify_presentation_4_tier_monotonic_silent_pill_blocking_manual() {
        let t0 = Instant::now();
        let mut coord = VerifyCoordinator::default();
        assert!(VerifyPresentation::Silent < VerifyPresentation::Pill);
        assert!(VerifyPresentation::Pill < VerifyPresentation::Blocking);
        assert!(VerifyPresentation::Blocking < VerifyPresentation::Manual);
        assert!(!VerifyPresentation::Silent.allows_panel_ui());
        assert!(!VerifyPresentation::Pill.allows_panel_ui());
        assert!(VerifyPresentation::Pill.allows_pill_ui());
        assert!(VerifyPresentation::Blocking.allows_panel_ui());
        assert!(VerifyPresentation::Manual.allows_panel_ui());

        let (r1, pres1, _) = match coord.join_or_start(VerifyPresentation::Silent, t0, false) {
            VerifyJoin::Leader { round, presentation, escalate_notify } => (round, presentation, escalate_notify),
            _ => panic!("expected leader"),
        };
        let _ = coord.join_or_start(VerifyPresentation::Pill, t0 + Duration::from_millis(20), false);
        assert_eq!(VerifyPresentation::from_u8(pres1.load(Ordering::SeqCst)), VerifyPresentation::Pill);
        // Joining with Silent must NEVER downgrade Pill
        let _ = coord.join_or_start(VerifyPresentation::Silent, t0 + Duration::from_millis(30), false);
        assert_eq!(VerifyPresentation::from_u8(pres1.load(Ordering::SeqCst)), VerifyPresentation::Pill);
        let _ = coord.join_or_start(VerifyPresentation::Blocking, t0 + Duration::from_millis(40), false);
        assert_eq!(VerifyPresentation::from_u8(pres1.load(Ordering::SeqCst)), VerifyPresentation::Blocking);
        coord.finish_flight(r1, true, false, VerifyPresentation::Blocking, t0 + Duration::from_secs(1));
    }

    #[test]
    fn adaptive_cf_429_classifier_protects_healthy_incumbent_then_escalates() {
        let mut cls = Cf429AdaptiveClassifier::default();
        let t0 = Instant::now();
        // Healthy incumbent + first 429 challenge -> short cooldown to protect healthy ticket
        assert!(matches!(
            cls.classify(true, t0),
            Cf429Decision::ShortCooldown { cooldown_ms } if cooldown_ms >= 1200 && cooldown_ms <= 3000
        ));
        // Second consecutive 429 challenge within 12s -> escalate immediately to single-flight verify!
        assert_eq!(
            cls.classify(true, t0 + Duration::from_secs(3)),
            Cf429Decision::EscalateVerify
        );
        // Healthy 2xx resets burst state
        cls.note_healthy_2xx();
        assert!(matches!(
            cls.classify(true, t0 + Duration::from_secs(5)),
            Cf429Decision::ShortCooldown { .. }
        ));
        // Without healthy incumbent -> escalate on first hit!
        cls.note_healthy_2xx();
        assert_eq!(
            cls.classify(false, t0 + Duration::from_secs(6)),
            Cf429Decision::EscalateVerify
        );
    }

    #[test]
    fn p1_call_deadline_pauses_during_cf_interaction_and_enforces_budget() {
        let t0 = Instant::now();
        let mut dl = CallDeadline::new(Duration::from_secs(28), t0);
        assert!(!dl.is_expired(t0 + Duration::from_secs(10)));
        assert_eq!(dl.remaining_ms(t0 + Duration::from_secs(10)), 18_000);

        // User enters Cloudflare Turnstile checkbox for 25 seconds -> clock pauses!
        dl.pause(t0 + Duration::from_secs(10));
        assert!(!dl.is_expired(t0 + Duration::from_secs(35)));
        dl.resume(t0 + Duration::from_secs(35));

        // After resuming, the original 18s budget is still intact!
        assert_eq!(dl.remaining_ms(t0 + Duration::from_secs(35)), 18_000);
        assert!(!dl.is_expired(t0 + Duration::from_secs(50)));
        assert!(dl.is_expired(t0 + Duration::from_secs(54)));
    }

    #[test]
    fn p1_csrf_cooldown_logout_exemption_and_idle_near_expiry_refresh() {
        let t0 = Instant::now();
        let mut csrf = CsrfFailureCooldown::default();
        assert!(!csrf.is_in_cooldown(t0));
        csrf.record_failure(t0);
        assert!(csrf.is_in_cooldown(t0 + Duration::from_secs(10)));
        assert!(!csrf.is_in_cooldown(t0 + Duration::from_secs(31)));
        csrf.record_failure(t0 + Duration::from_secs(40));
        csrf.clear();
        assert!(!csrf.is_in_cooldown(t0 + Duration::from_secs(41)));

        // Logout DELETE /session/:u and temporary QR key self-revoke must be exempt from 401/403 session self-heal!
        assert!(is_logout_exempt_from_session_recovery("DELETE", "https://linux.do/session/jedilus"));
        assert!(is_logout_exempt_from_session_recovery("delete", "https://linux.do/session/alice.json"));
        assert!(is_logout_exempt_from_session_recovery("POST", "https://linux.do/user-api-key/revoke.json"));
        assert!(!is_logout_exempt_from_session_recovery("POST", "https://linux.do/user-api-key.json"));
        assert!(!is_logout_exempt_from_session_recovery("GET", "https://linux.do/session/current.json"));
        assert!(!is_logout_exempt_from_session_recovery("POST", "https://linux.do/posts.json"));

        // Zero-resident idle near-expiry silent refresh (>= 10.5h, idle fg == 0, not verifying)
        assert!(!should_trigger_near_expiry_silent_refresh(Some(36_000), 0, false));
        assert!(!should_trigger_near_expiry_silent_refresh(Some(38_500), 1, false));
        assert!(!should_trigger_near_expiry_silent_refresh(Some(38_500), 0, true));
        assert!(should_trigger_near_expiry_silent_refresh(Some(38_500), 0, false));
    }

    #[test]
    fn bridge_timeouts_and_call_deadlines_never_invert_across_all_lanes() {
        let now = Instant::now();
        let cases = [
            (true, true, false),   // background read
            (false, true, false),  // background write (mark-read, drafts)
            (false, true, true),   // timings telemetry
            (true, false, false),  // foreground read
            (false, false, false), // foreground write
        ];
        for (is_read, is_bg, is_timings) in cases {
            let spec = compute_bridge_timeouts(is_read, is_bg, is_timings);
            assert!(
                spec.js_hard_cap_ms >= spec.fetch_timeout_ms + spec.csrf_timeout_ms,
                "JS hard cap must cover CSRF + primary fetch for ({is_read}, {is_bg}, {is_timings}): {spec:?}"
            );
            assert!(
                spec.rust_ipc_timeout_secs * 1000 >= spec.js_hard_cap_ms + 5_000,
                "Rust IPC timeout must exceed JS hard cap by >= 5s for ({is_read}, {is_bg}, {is_timings}): {spec:?}"
            );
            let dl = CallDeadline::for_request(is_read, is_bg, now);
            assert!(
                dl.remaining_ms(now) > spec.rust_ipc_timeout_secs * 1000,
                "CallDeadline ({}) must exceed Rust IPC timeout ({}ms) for ({is_read}, {is_bg}, {is_timings})",
                dl.remaining_ms(now),
                spec.rust_ipc_timeout_secs * 1000
            );
        }
    }

    #[test]
    fn evaluate_verify_outcome_blocks_stale_unchallenged_404_whitewashing() {
        let mut auth = CfClearanceAuthority::default();
        auth.confirm_healthy("cf_stale", 1_700_000_000_000);
        auth.note_challenged(Some("cf_stale"));

        let mut baseline = std::collections::HashSet::new();
        baseline.insert("cf_stale".to_string());

        // 1. `/challenge` hits Discourse 404 without rotating `cf_stale` -> MUST be StaleUnchallenged404!
        assert_eq!(
            auth.evaluate_verify_outcome(&baseline, &["cf_stale".to_string()], true, true),
            VerifyTicketOutcome::StaleUnchallenged404
        );
        // `cf_stale` remains marked challenged (never washed clean!)
        assert!(auth.is_challenged("cf_stale"));

        // 2. `/challenge` mints fresh `cf_fresh` -> Rotated!
        assert_eq!(
            auth.evaluate_verify_outcome(
                &baseline,
                &["cf_stale".to_string(), "cf_fresh".to_string()],
                true,
                true
            ),
            VerifyTicketOutcome::Rotated
        );

        // 3. Unchallenged prewarm with healthy existing `cf_valid` -> ExistingValid!
        let mut healthy_auth = CfClearanceAuthority::default();
        healthy_auth.confirm_healthy("cf_valid", 1_700_000_000_000);
        let mut healthy_base = std::collections::HashSet::new();
        healthy_base.insert("cf_valid".to_string());
        assert_eq!(
            healthy_auth.evaluate_verify_outcome(&healthy_base, &["cf_valid".to_string()], false, true),
            VerifyTicketOutcome::ExistingValid
        );

        // 4. Empty cookie store -> Absent!
        assert_eq!(
            healthy_auth.evaluate_verify_outcome(&healthy_base, &[], false, true),
            VerifyTicketOutcome::Absent
        );
    }

    #[test]
    fn background_challenge_tracker_isolates_write_streaks_from_get_200() {
        let t0 = Instant::now();
        let mut tr = BackgroundChallengeTracker::default();

        // 1. Concurrent background GET challenges within 5s deduplicate into 1 wave
        assert!(!tr.note_background_challenge(false, t0));
        assert!(!tr.note_background_challenge(false, t0 + Duration::from_secs(2)));
        assert_eq!(tr.read_waves(), 1);
        assert!(!tr.note_background_challenge(false, t0 + Duration::from_secs(6)));
        assert_eq!(tr.read_waves(), 2);
        assert!(tr.note_background_challenge(false, t0 + Duration::from_secs(12)));
        assert_eq!(tr.read_waves(), 3);

        // Foreground GET 200 clears read waves
        tr.note_healthy_response(false);
        assert_eq!(tr.read_waves(), 0);

        // 2. Background write challenges (`POST /topics/timings`, `PUT /notifications/mark-read.json`):
        //    1st hit stays quiet; 2nd hit escalates; interleaved `GET 200` MUST NOT clear `consecutive_write_hits`!
        assert!(!tr.note_background_challenge(true, t0 + Duration::from_secs(15)));
        assert_eq!(tr.consecutive_write_hits(), 1);
        tr.note_healthy_response(false); // Foreground GET /t/123.json -> 200 OK
        assert!(tr.has_unresolved_write_challenges());
        assert_eq!(tr.consecutive_write_hits(), 1);

        // Next background write 403 immediately escalates (`consecutive_write_hits == 2`)!
        assert!(tr.note_background_challenge(true, t0 + Duration::from_secs(18)));
        assert_eq!(tr.consecutive_write_hits(), 2);

        // Only a healthy write 2xx (or explicit verification reset) clears `consecutive_write_hits`!
        tr.note_healthy_response(true);
        assert!(!tr.has_unresolved_write_challenges());
        assert_eq!(tr.consecutive_write_hits(), 0);
    }

    #[test]
    fn abandoned_flight_and_guard_drop_never_arm_backoff_or_penalize_failures() {
        let t0 = Instant::now();
        let coord = Arc::new(StdMutex::new(VerifyCoordinator::default()));

        // 1. Silent prewarm abandoned (e.g. unchallenged 404 with zero cf_clearance) -> 0 silent backoff!
        let (r1, p1) = match coord.lock().unwrap().join_or_start(VerifyPresentation::Silent, t0, false) {
            VerifyJoin::Leader { round, presentation, .. } => (round, presentation),
            _ => panic!("expected leader"),
        };
        let guard1 = VerifyFlightGuard::new(Arc::clone(&coord), r1, p1);
        let out1 = guard1.abandon(t0 + Duration::from_secs(1));
        assert!(!out1.passed);
        let snap1 = coord.lock().unwrap().snapshot(t0 + Duration::from_secs(1));
        assert!(!snap1.active);
        assert_eq!(snap1.consecutive_failures, 0);
        assert_eq!(snap1.silent_backoff_remain_ms, 0);

        // 2. Blocking leader dropped mid-flight -> followers wake up cleanly and `consecutive_failures` stays 0!
        let (r2, p2) = match coord.lock().unwrap().join_or_start(VerifyPresentation::Blocking, t0 + Duration::from_secs(2), false) {
            VerifyJoin::Leader { round, presentation, .. } => (round, presentation),
            _ => panic!("expected leader"),
        };
        let mut follower_rx = match coord.lock().unwrap().join_or_start(VerifyPresentation::Blocking, t0 + Duration::from_secs(2), false) {
            VerifyJoin::Follower { rx, .. } => rx,
            _ => panic!("expected follower"),
        };
        {
            let _guard2 = VerifyFlightGuard::new(Arc::clone(&coord), r2, p2);
            // `_guard2` dropped here without `.complete(...)`
        }
        let follower_out = follower_rx.borrow_and_update().expect("follower must receive outcome on drop");
        assert!(!follower_out.passed);
        let snap2 = coord.lock().unwrap().snapshot(t0 + Duration::from_secs(3));
        assert!(!snap2.active);
        assert_eq!(snap2.consecutive_failures, 0);
        assert_eq!(snap2.cooldown_remain_ms, 0);
    }
}
