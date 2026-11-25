use serde_json::Value;
use tauri::AppHandle;

/// Explicit platform capability matrix so shared logic (`bridge.rs`, `lib.rs`) branches on
/// documented engine capabilities rather than ad-hoc OS checks, keeping macOS, Windows, and Linux
/// strictly isolated where engines differ while sharing 100% of common logic.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PlatformCaps {
    /// Wayland/GTK unmapped windows suspend WebKitGTK frame clocks, pausing Turnstile challenges.
    pub hidden_webview_pauses_turnstile: bool,
    /// WebKitGTK + GnuTLS TLS stack triggers Cloudflare HTML document re-challenge on `/challenge`
    /// even after Turnstile mints a fresh `cf_clearance` that passes XHR API requests (`200 OK`).
    pub verify_confirm_fresh_clearance_via_xhr: bool,
    /// WebKitGTK + GnuTLS TLS stack triggers Cloudflare JA4/H2 429 challenge even with valid `cf_clearance`;
    /// always route Linux forum WebViews through the local BoringSSL WebKit TLS/H2 bridge (`EchDirect` or `SystemTlsBridge`).
    pub always_route_webview_via_tls_bridge: bool,
    /// WebKitGTK navigation error recovery, first-boot UA recording, and XHR OTP headers on Linux.
    pub linux_webkit_quirks: bool,
    /// Closing the main window exits the application cleanly instead of hiding to background tray/dock.
    pub close_main_exits_app: bool,
}

#[cfg(target_os = "linux")]
pub const CAPS: PlatformCaps = PlatformCaps {
    hidden_webview_pauses_turnstile: true,
    verify_confirm_fresh_clearance_via_xhr: true,
    always_route_webview_via_tls_bridge: true,
    linux_webkit_quirks: true,
    close_main_exits_app: true,
};

#[cfg(not(target_os = "linux"))]
pub const CAPS: PlatformCaps = PlatformCaps {
    hidden_webview_pauses_turnstile: false,
    verify_confirm_fresh_clearance_via_xhr: false,
    always_route_webview_via_tls_bridge: false,
    linux_webkit_quirks: false,
    close_main_exits_app: false,
};

#[cfg(target_os = "windows")]
pub async fn native_webview_call(
    app_opt: Option<&AppHandle>,
    method: &str,
    params: Value,
    timeout_secs: u64,
) -> Result<Value, String> {
    crate::win_webview_bridge::native_call(app_opt, method, params, timeout_secs).await
}

#[cfg(target_os = "linux")]
pub async fn native_webview_call(
    app_opt: Option<&AppHandle>,
    method: &str,
    params: Value,
    timeout_secs: u64,
) -> Result<Value, String> {
    crate::linux_webview_bridge::native_call(app_opt, method, params, timeout_secs).await
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_platform_caps_match_target_os() {
        if cfg!(target_os = "linux") {
            assert!(CAPS.hidden_webview_pauses_turnstile);
            assert!(CAPS.verify_confirm_fresh_clearance_via_xhr);
            assert!(CAPS.always_route_webview_via_tls_bridge);
            assert!(CAPS.linux_webkit_quirks);
            assert!(CAPS.close_main_exits_app);
        } else {
            assert!(!CAPS.hidden_webview_pauses_turnstile);
            assert!(!CAPS.verify_confirm_fresh_clearance_via_xhr);
            assert!(!CAPS.always_route_webview_via_tls_bridge);
            assert!(!CAPS.linux_webkit_quirks);
            assert!(!CAPS.close_main_exits_app);
        }
    }
}
