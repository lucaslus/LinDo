use crate::legacy;
use lindo_ech::proxy::GatewayRouteMode;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::ffi::{c_char, CStr};
use std::fs;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, UdpSocket};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Mutex, OnceLock};
use tauri::{AppHandle, Emitter};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EchRoutingMode {
    SmartAuto,
    EchPriority,
    SystemDefault,
}

impl EchRoutingMode {
    pub fn as_str(&self) -> &'static str {
        match self {
            EchRoutingMode::SmartAuto => "smart_auto",
            EchRoutingMode::EchPriority => "ech_priority",
            EchRoutingMode::SystemDefault => "system_default",
        }
    }

    pub fn from_pref(raw: &str) -> Self {
        match raw.trim().to_ascii_lowercase().as_str() {
            "ech_priority" | "direct_only" => EchRoutingMode::EchPriority,
            "system_default" => EchRoutingMode::SystemDefault,
            "smart_auto" | "smart_bypass" | "" => EchRoutingMode::SmartAuto,
            _ => EchRoutingMode::SmartAuto,
        }
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EchDesktopSnapshot {
    pub supported: bool,
    pub active: bool,
    pub mode: String,
    pub listen_port: u16,
    pub sticky_ip: String,
    pub proxy_or_vpn_active: bool,
    pub tun_active: bool,
    pub http_proxy: String,
    pub route_iface: String,
    pub vpn_yielded: bool,
    pub ech_handshake_count: u64,
    pub last_ech_accepted: bool,
    pub status_subtitle: String,
    pub diag_line: String,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct NetEnv {
    pub local_ip: String,
    pub sys_proxy: bool,
    pub http_proxy: Option<SocketAddr>,
    pub tun_active: bool,
    pub route_iface: String,
}

impl NetEnv {
    pub fn signature(&self) -> String {
        let proxy_str = self
            .http_proxy
            .map(|a| a.to_string())
            .unwrap_or_default();
        format!(
            "{}|{}|{}|{}|{}",
            self.sys_proxy, proxy_str, self.tun_active, self.local_ip, self.route_iface
        )
    }

    pub fn has_proxy_or_vpn(&self) -> bool {
        self.sys_proxy || self.tun_active
    }
}

#[no_mangle]
pub extern "C" fn lindo_ech_verify_leaf_der(
    der_ptr: *const u8,
    der_len: usize,
    host_utf8: *const c_char,
) -> i32 {
    if der_ptr.is_null() || der_len == 0 || der_len > 65_536 || host_utf8.is_null() {
        return 0;
    }
    let der = unsafe { std::slice::from_raw_parts(der_ptr, der_len) };
    let Ok(host_str) = (unsafe { CStr::from_ptr(host_utf8) }).to_str() else {
        return 0;
    };
    if lindo_ech::verify_leaf_der(der, host_str) {
        1
    } else {
        0
    }
}

#[cfg(target_os = "macos")]
extern "C" {
    fn lindo_macos_supports_wk_proxy() -> i32;
    fn lindo_macos_set_webkit_proxy(port: u16);
    fn lindo_macos_detect_proxy_json(local_ip_utf8: *const c_char, buf: *mut u8, buf_len: usize);
}

static STOP_SEQ: AtomicU64 = AtomicU64::new(0);
static DESIRED_ECH: AtomicBool = AtomicBool::new(false);
static PROXY_TUNNEL_UNREACHABLE: AtomicBool = AtomicBool::new(false);
static LAST_ENV_SIGNATURE: OnceLock<Mutex<String>> = OnceLock::new();
static LAST_SNAPSHOT: OnceLock<Mutex<EchDesktopSnapshot>> = OnceLock::new();
static CA_MATERIAL_CACHE: OnceLock<Mutex<Option<(String, String)>>> = OnceLock::new();
static RECONCILE_LOCK: OnceLock<Mutex<()>> = OnceLock::new();

fn last_env_sig_lock() -> &'static Mutex<String> {
    LAST_ENV_SIGNATURE.get_or_init(|| Mutex::new(String::new()))
}

fn last_snapshot_lock() -> &'static Mutex<EchDesktopSnapshot> {
    LAST_SNAPSHOT.get_or_init(|| Mutex::new(EchDesktopSnapshot::default()))
}

fn ca_cache_lock() -> &'static Mutex<Option<(String, String)>> {
    CA_MATERIAL_CACHE.get_or_init(|| Mutex::new(None))
}

fn reconcile_mutex() -> &'static Mutex<()> {
    RECONCILE_LOCK.get_or_init(|| Mutex::new(()))
}

fn ensure_ca_material() -> Result<(String, String), String> {
    if let Some(pair) = ca_cache_lock().lock().unwrap().clone() {
        return Ok(pair);
    }
    let dir = legacy::lindo_home_dir();
    let cert_path = dir.join("ech_ca_cert.pem");
    let key_path = dir.join("ech_ca_key.pem");
    if cert_path.is_file() && key_path.is_file() {
        if let (Ok(cert), Ok(key)) = (fs::read_to_string(&cert_path), fs::read_to_string(&key_path))
        {
            if cert.contains("BEGIN CERTIFICATE") && key.contains("PRIVATE KEY") {
                let pair = (cert, key);
                *ca_cache_lock().lock().unwrap() = Some(pair.clone());
                return Ok(pair);
            }
        }
    }
    let raw = lindo_ech::generate_ca_json()?;
    let parsed: Value =
        serde_json::from_str(&raw).map_err(|e| format!("parse generated CA json: {e}"))?;
    let cert = parsed
        .get("certPem")
        .and_then(|v| v.as_str())
        .ok_or_else(|| "missing certPem".to_string())?
        .to_string();
    let key = parsed
        .get("keyPem")
        .and_then(|v| v.as_str())
        .ok_or_else(|| "missing keyPem".to_string())?
        .to_string();
    let _ = fs::write(&cert_path, &cert);
    let _ = fs::write(&key_path, &key);
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = fs::set_permissions(&key_path, fs::Permissions::from_mode(0o600));
    }
    let pair = (cert, key);
    *ca_cache_lock().lock().unwrap() = Some(pair.clone());
    Ok(pair)
}

fn probe_default_route_ipv4() -> String {
    let Ok(sock) = UdpSocket::bind("0.0.0.0:0") else {
        return String::new();
    };
    if sock.connect("104.18.10.118:443").is_err() {
        return String::new();
    }
    match sock.local_addr() {
        Ok(SocketAddr::V4(addr)) => {
            let ip = *addr.ip();
            if ip.is_unspecified() {
                String::new()
            } else {
                ip.to_string()
            }
        }
        _ => String::new(),
    }
}

pub fn parse_http_proxy_addr(raw: &str) -> Option<SocketAddr> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return None;
    }
    let mut selected = trimmed;
    if trimmed.contains('=') {
        let mut https_val = None;
        let mut http_val = None;
        for part in trimmed.split(';') {
            let p = part.trim();
            if let Some(rest) = p.strip_prefix("https=").or_else(|| p.strip_prefix("HTTPS=")) {
                https_val = Some(rest.trim());
            } else if let Some(rest) = p.strip_prefix("http=").or_else(|| p.strip_prefix("HTTP=")) {
                http_val = Some(rest.trim());
            }
        }
        selected = https_val.or(http_val).unwrap_or("");
    }
    let clean = selected
        .trim()
        .trim_start_matches("http://")
        .trim_start_matches("https://")
        .split('/')
        .next()
        .unwrap_or("")
        .trim();
    if clean.is_empty() {
        return None;
    }
    if let Ok(addr) = clean.parse::<SocketAddr>() {
        return Some(addr);
    }
    if let Some((host, port_str)) = clean.rsplit_once(':') {
        if let Ok(port) = port_str.parse::<u16>() {
            let h = host.trim().trim_start_matches('[').trim_end_matches(']');
            if h.eq_ignore_ascii_case("localhost") {
                return Some(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), port));
            }
            if let Ok(ip) = h.parse::<IpAddr>() {
                return Some(SocketAddr::new(ip, port));
            }
        }
    }
    None
}

fn is_fake_or_tun_ip(ip_str: &str) -> bool {
    let Ok(ip) = ip_str.parse::<Ipv4Addr>() else {
        return false;
    };
    let oct = ip.octets();
    // 198.18.0.0/15 FakeIP/benchmark TUN, 172.19.0.0/16 Clash TUN
    (oct[0] == 198 && (oct[1] == 18 || oct[1] == 19)) || (oct[0] == 172 && oct[1] == 19)
}

#[cfg(target_os = "macos")]
pub fn detect_net_env() -> NetEnv {
    let local_ip = probe_default_route_ipv4();
    let mut env = NetEnv {
        local_ip: local_ip.clone(),
        ..Default::default()
    };
    let Ok(ip_c) = std::ffi::CString::new(local_ip.as_str()) else {
        return env;
    };
    let mut buf = [0u8; 1024];
    unsafe {
        lindo_macos_detect_proxy_json(ip_c.as_ptr(), buf.as_mut_ptr(), buf.len());
    }
    let end = buf.iter().position(|&b| b == 0).unwrap_or(buf.len());
    if let Ok(text) = std::str::from_utf8(&buf[..end]) {
        if let Ok(v) = serde_json::from_str::<Value>(text) {
            env.sys_proxy = v.get("sysProxy").and_then(|x| x.as_bool()).unwrap_or(false);
            let proxy_raw = v.get("httpProxy").and_then(|x| x.as_str()).unwrap_or("");
            env.http_proxy = parse_http_proxy_addr(proxy_raw);
            env.route_iface = v
                .get("routeIface")
                .and_then(|x| x.as_str())
                .unwrap_or("")
                .to_string();
            env.tun_active = v
                .get("hasTunIface")
                .and_then(|x| x.as_bool())
                .unwrap_or(false)
                || is_fake_or_tun_ip(&env.local_ip);
        }
    }
    env
}

#[cfg(target_os = "windows")]
pub fn detect_net_env() -> NetEnv {
    use winreg::enums::HKEY_CURRENT_USER;
    use winreg::RegKey;

    let local_ip = probe_default_route_ipv4();
    let mut sys_proxy = false;
    let mut http_proxy = None;
    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    if let Ok(key) = hkcu.open_subkey("Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings")
    {
        let enable: u32 = key.get_value("ProxyEnable").unwrap_or(0);
        let pac: String = key.get_value("AutoConfigURL").unwrap_or_default();
        let server: String = key.get_value("ProxyServer").unwrap_or_default();
        sys_proxy = enable != 0 || !pac.trim().is_empty();
        if enable != 0 {
            http_proxy = parse_http_proxy_addr(&server);
        }
    }
    let tun_active = is_fake_or_tun_ip(&local_ip);
    NetEnv {
        local_ip,
        sys_proxy,
        http_proxy,
        tun_active,
        route_iface: if tun_active {
            "tun".to_string()
        } else {
            String::new()
        },
    }
}

#[cfg(target_os = "linux")]
fn probe_linux_route_iface() -> String {
    let Ok(out) = std::process::Command::new("ip")
        .args(["-o", "route", "get", "104.18.10.118"])
        .output()
    else {
        return String::new();
    };
    if !out.status.success() {
        return String::new();
    }
    let text = String::from_utf8_lossy(&out.stdout);
    let tokens: Vec<&str> = text.split_whitespace().collect();
    for win in tokens.windows(2) {
        if win[0] == "dev" {
            return win[1].to_string();
        }
    }
    String::new()
}

#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
fn is_linux_tun_iface_name(iface: &str) -> bool {
    let lower = iface.trim().to_ascii_lowercase();
    if lower.is_empty() {
        return false;
    }
    lower.starts_with("tun")
        || lower.starts_with("utun")
        || lower.starts_with("flclash")
        || lower.starts_with("mihomo")
        || lower.starts_with("meta")
        || lower.starts_with("clash")
        || lower.starts_with("singtun")
        || lower.starts_with("nekoray")
}

#[cfg(not(any(target_os = "macos", target_os = "windows")))]
pub fn detect_net_env() -> NetEnv {
    let local_ip = probe_default_route_ipv4();
    let env_proxy_raw = std::env::var("HTTPS_PROXY")
        .ok()
        .filter(|s| !s.trim().is_empty())
        .or_else(|| std::env::var("https_proxy").ok().filter(|s| !s.trim().is_empty()))
        .or_else(|| std::env::var("HTTP_PROXY").ok().filter(|s| !s.trim().is_empty()))
        .or_else(|| std::env::var("http_proxy").ok().filter(|s| !s.trim().is_empty()))
        .unwrap_or_default();
    let http_proxy = parse_http_proxy_addr(&env_proxy_raw);
    let sys_proxy = http_proxy.is_some();

    #[cfg(target_os = "linux")]
    let route_iface = probe_linux_route_iface();
    #[cfg(not(target_os = "linux"))]
    let route_iface = String::new();

    #[cfg(target_os = "linux")]
    let iface_is_tun = is_linux_tun_iface_name(&route_iface);
    #[cfg(not(target_os = "linux"))]
    let iface_is_tun = false;

    let tun_active = is_fake_or_tun_ip(&local_ip) || iface_is_tun;
    NetEnv {
        local_ip,
        sys_proxy,
        http_proxy,
        tun_active,
        route_iface,
    }
}

pub fn decide_should_use_ech(mode: EchRoutingMode, env: &NetEnv) -> (bool, bool) {
    decide_should_use_ech_with_fallback(mode, env, false)
}

pub fn decide_should_use_ech_with_fallback(
    mode: EchRoutingMode,
    env: &NetEnv,
    proxy_tunnel_unreachable: bool,
) -> (bool, bool) {
    match mode {
        EchRoutingMode::SystemDefault => (false, false),
        EchRoutingMode::SmartAuto => {
            if !env.has_proxy_or_vpn() {
                (true, false)
            } else if proxy_tunnel_unreachable && !env.tun_active {
                // Dead local HTTP proxy or fake proxy rule under SmartAuto: automatically fall back to local ECH
                (true, false)
            } else {
                (false, false)
            }
        }
        EchRoutingMode::EchPriority => {
            if env.tun_active {
                (false, true)
            } else {
                (true, false)
            }
        }
    }
}

/// Marks the detected system proxy as unreachable under `SmartAuto` so the next reconcile
/// automatically falls back to direct ECH (reset automatically on mode or network signature change).
pub fn note_proxy_tunnel_unreachable() -> bool {
    let env = detect_net_env();
    if env.sys_proxy && !env.tun_active {
        !PROXY_TUNNEL_UNREACHABLE.swap(true, Ordering::AcqRel)
    } else {
        false
    }
}

pub fn format_status_subtitle(
    supported: bool,
    mode: EchRoutingMode,
    active: bool,
    env: &NetEnv,
    vpn_yielded: bool,
    sticky_ip: &str,
) -> String {
    if !supported {
        return if cfg!(target_os = "macos") {
            "当前系统低于 macOS 14 · 使用系统默认网络".to_string()
        } else {
            "当前平台不支持 · 使用系统默认网络".to_string()
        };
    }
    if mode == EchRoutingMode::SystemDefault {
        return "已关闭 · 使用系统默认网络".to_string();
    }
    if mode == EchRoutingMode::SmartAuto && env.has_proxy_or_vpn() && !active {
        return "检测到代理/VPN · 已自动使用代理通道（关闭代理后自动启用直连）".to_string();
    }
    if mode == EchRoutingMode::EchPriority && vpn_yielded && !active {
        return "当前 TUN/VPN 全局接管 · 已自动回退至 VPN 通道".to_string();
    }
    if !active {
        return "正在初始化 ECH 通道…".to_string();
    }
    let iface = if env.route_iface.is_empty() {
        "直连"
    } else {
        env.route_iface.as_str()
    };
    if sticky_ip.is_empty() {
        format!("已开启直连 ({iface})")
    } else {
        format!("已开启直连 ({iface} · 固定边缘 {sticky_ip})")
    }
}

fn is_platform_supported() -> bool {
    #[cfg(target_os = "macos")]
    {
        unsafe { lindo_macos_supports_wk_proxy() == 1 }
    }
    #[cfg(any(target_os = "windows", target_os = "linux"))]
    {
        true
    }
    #[cfg(not(any(target_os = "macos", target_os = "windows", target_os = "linux")))]
    {
        false
    }
}

#[cfg(target_os = "linux")]
pub fn ensure_linux_ech_reconciled(initial_mode: &str) {
    let mode = EchRoutingMode::from_pref(initial_mode);
    let _snap = reconcile_with_mode(mode);
}

#[cfg(target_os = "windows")]
pub fn windows_webview_proxy_args(initial_mode: &str) -> Option<(String, std::path::PathBuf)> {
    let mode = EchRoutingMode::from_pref(initial_mode);
    let _snap = reconcile_with_mode(mode);
    let port = lindo_ech::current_gateway_port();
    let spki = lindo_ech::current_ca_spki_sha256();
    if port == 0 || spki.is_empty() {
        return None;
    }
    let data_dir = legacy::lindo_home_dir().join("webview2-forum");
    let _ = fs::create_dir_all(&data_dir);
    let args = format!(
        "--disable-features=msWebOOUI,msPdfOOUI,msSmartScreenProtection \
         --proxy-server=http://127.0.0.1:{port} \
         --proxy-bypass-list=<-loopback> \
         --ignore-certificate-errors-spki-list={spki}"
    );
    Some((args, data_dir))
}

#[cfg(not(target_os = "windows"))]
pub fn windows_webview_proxy_args(_initial_mode: &str) -> Option<(String, std::path::PathBuf)> {
    None
}

pub fn reconcile_with_mode(mode: EchRoutingMode) -> EchDesktopSnapshot {
    let _guard = reconcile_mutex().lock().unwrap_or_else(|e| e.into_inner());
    let prev_mode = last_snapshot_lock()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .mode
        .clone();
    let mode_changed = !prev_mode.is_empty() && prev_mode != mode.as_str();
    let prev_gw_route = lindo_ech::current_gateway_route_mode();
    let had_running_gateway = lindo_ech::current_gateway_port() > 0;

    let env = detect_net_env();
    let cur_sig = env.signature();
    {
        let mut sig_guard = last_env_sig_lock().lock().unwrap();
        if *sig_guard != cur_sig || mode_changed {
            *sig_guard = cur_sig;
            PROXY_TUNNEL_UNREACHABLE.store(false, Ordering::Release);
        }
    }
    let supported = is_platform_supported();
    let (mut should_ech, vpn_yielded) = if supported {
        decide_should_use_ech_with_fallback(
            mode,
            &env,
            PROXY_TUNNEL_UNREACHABLE.load(Ordering::Acquire),
        )
    } else {
        (false, false)
    };

    DESIRED_ECH.store(should_ech, Ordering::SeqCst);
    if should_ech {
        // Cancel any pending delayed gateway shutdown from a rapid `ech -> system_default -> ech` toggle.
        STOP_SEQ.fetch_add(1, Ordering::AcqRel);
    }
    let mut last_err = String::new();

    if cfg!(target_os = "macos") || cfg!(target_os = "linux") {
        if supported && should_ech {
            if lindo_ech::current_gateway_port() == 0 {
                match ensure_ca_material()
                    .and_then(|(cert, key)| lindo_ech::start_gateway(&cert, &key, 0, false))
                {
                    Ok(_) => {}
                    Err(e) => {
                        last_err = e;
                        should_ech = false;
                    }
                }
            }
            if should_ech {
                lindo_ech::set_gateway_route_mode(GatewayRouteMode::EchDirect);
                if had_running_gateway
                    && (mode_changed || prev_gw_route != Some(GatewayRouteMode::EchDirect))
                {
                    lindo_ech::rotate_sticky_ip();
                    lindo_ech::notify_network_changed();
                }
                let port = lindo_ech::current_gateway_port();
                #[cfg(target_os = "macos")]
                unsafe {
                    lindo_macos_set_webkit_proxy(port);
                }
                #[cfg(target_os = "linux")]
                crate::linux_webview_bridge::set_webkit_proxy_port(port);
            } else {
                #[cfg(target_os = "linux")]
                crate::linux_webview_bridge::set_webkit_proxy_port(0);
            }
        } else if supported {
            if lindo_ech::current_gateway_port() > 0 {
                lindo_ech::set_gateway_route_mode(GatewayRouteMode::SystemPassThrough {
                    http_proxy: env.http_proxy,
                });
                lindo_ech::rotate_sticky_ip();
                lindo_ech::notify_network_changed();
            }
            #[cfg(target_os = "macos")]
            unsafe {
                lindo_macos_set_webkit_proxy(0);
            }
            #[cfg(target_os = "linux")]
            crate::linux_webview_bridge::set_webkit_proxy_port(0);
            if lindo_ech::current_gateway_port() > 0 {
                let seq = STOP_SEQ.fetch_add(1, Ordering::AcqRel) + 1;
                if let Ok(handle) = tokio::runtime::Handle::try_current() {
                    handle.spawn(async move {
                        tokio::time::sleep(std::time::Duration::from_millis(350)).await;
                        if STOP_SEQ.load(Ordering::Acquire) == seq
                            && !DESIRED_ECH.load(Ordering::Acquire)
                        {
                            lindo_ech::stop_gateway();
                        }
                    });
                } else {
                    lindo_ech::stop_gateway();
                }
            }
        }
    } else if cfg!(target_os = "windows") && supported {
        if lindo_ech::current_gateway_port() == 0 {
            if let Err(e) = ensure_ca_material()
                .and_then(|(cert, key)| lindo_ech::start_gateway(&cert, &key, 0, false))
            {
                last_err = e;
                should_ech = false;
            }
        }
        if lindo_ech::current_gateway_port() > 0 {
            let target_route = if should_ech {
                GatewayRouteMode::EchDirect
            } else {
                GatewayRouteMode::SystemPassThrough {
                    http_proxy: env.http_proxy,
                }
            };
            lindo_ech::set_gateway_route_mode(target_route.clone());
            if had_running_gateway && (mode_changed || prev_gw_route.as_ref() != Some(&target_route)) {
                lindo_ech::rotate_sticky_ip();
                lindo_ech::notify_network_changed();
            }
        }
    }

    let raw_status = lindo_ech::get_status_json();
    let v: Value = serde_json::from_str(&raw_status).unwrap_or(Value::Null);
    let listen_port = v.get("listenPort").and_then(|x| x.as_u64()).unwrap_or(0) as u16;
    let sticky_ip = v
        .get("stickyIp")
        .and_then(|x| x.as_str())
        .unwrap_or("")
        .to_string();
    let ech_handshake_count = v
        .get("echHandshakeCount")
        .and_then(|x| x.as_u64())
        .unwrap_or(0);
    let last_ech_accepted = v
        .get("lastEchAccepted")
        .and_then(|x| x.as_bool())
        .unwrap_or(false);
    let gw_err = v
        .get("lastError")
        .and_then(|x| x.as_str())
        .unwrap_or("");
    if last_err.is_empty() && !gw_err.is_empty() {
        last_err = gw_err.to_string();
    }

    let is_ech_active = supported
        && should_ech
        && listen_port > 0
        && matches!(
            lindo_ech::current_gateway_route_mode(),
            Some(GatewayRouteMode::EchDirect)
        );
    let subtitle = if !last_err.is_empty() && !is_ech_active && should_ech {
        format!("网关异常：{last_err}")
    } else {
        format_status_subtitle(supported, mode, is_ech_active, &env, vpn_yielded, &sticky_ip)
    };
    let http_proxy_str = env
        .http_proxy
        .map(|a| a.to_string())
        .unwrap_or_default();
    let diag_line = format_diag_line(
        mode,
        is_ech_active,
        listen_port,
        &sticky_ip,
        &env,
        &http_proxy_str,
        vpn_yielded,
        ech_handshake_count,
        last_ech_accepted,
        &last_err,
    );

    let snap = EchDesktopSnapshot {
        supported,
        active: is_ech_active,
        mode: mode.as_str().to_string(),
        listen_port,
        sticky_ip,
        proxy_or_vpn_active: env.has_proxy_or_vpn(),
        tun_active: env.tun_active,
        http_proxy: http_proxy_str,
        route_iface: env.route_iface,
        vpn_yielded,
        ech_handshake_count,
        last_ech_accepted,
        status_subtitle: subtitle,
        diag_line,
    };
    *last_snapshot_lock().lock().unwrap() = snap.clone();
    snap
}

#[allow(clippy::too_many_arguments)]
fn format_diag_line(
    mode: EchRoutingMode,
    is_ech_active: bool,
    listen_port: u16,
    sticky_ip: &str,
    env: &NetEnv,
    http_proxy_str: &str,
    vpn_yielded: bool,
    ech_handshake_count: u64,
    last_ech_accepted: bool,
    last_err: &str,
) -> String {
    format!(
        "mode={} active={} port={} stickyIp={} proxyOrVpn={} tun={} sysProxy={} httpProxy={} iface={} yield={} echCount={} lastEch={}{}",
        mode.as_str(),
        is_ech_active,
        listen_port,
        if sticky_ip.is_empty() { "pending" } else { sticky_ip },
        env.has_proxy_or_vpn(),
        env.tun_active,
        env.sys_proxy,
        if http_proxy_str.is_empty() { "none" } else { http_proxy_str },
        if env.route_iface.is_empty() { "none" } else { env.route_iface.as_str() },
        vpn_yielded,
        ech_handshake_count,
        last_ech_accepted,
        if last_err.is_empty() {
            String::new()
        } else {
            format!(" err={last_err}")
        }
    )
}

pub fn refresh_status_snapshot(mode_pref: &str) -> EchDesktopSnapshot {
    let mode = EchRoutingMode::from_pref(mode_pref);
    let prev = last_snapshot_lock().lock().unwrap().clone();
    if prev.mode == mode.as_str() {
        // Pull fresh live counters/stickyIp/network flags from native gateway if active
        let env = detect_net_env();
        let raw_status = lindo_ech::get_status_json();
        let v: Value = serde_json::from_str(&raw_status).unwrap_or(Value::Null);
        let listen_port = v
            .get("listenPort")
            .and_then(|x| x.as_u64())
            .map(|p| p as u16)
            .unwrap_or(prev.listen_port);
        let sticky_ip = v
            .get("stickyIp")
            .and_then(|x| x.as_str())
            .unwrap_or(&prev.sticky_ip)
            .to_string();
        let ech_handshake_count = v
            .get("echHandshakeCount")
            .and_then(|x| x.as_u64())
            .unwrap_or(prev.ech_handshake_count);
        let last_ech_accepted = v
            .get("lastEchAccepted")
            .and_then(|x| x.as_bool())
            .unwrap_or(prev.last_ech_accepted);
        let gw_err = v
            .get("lastError")
            .and_then(|x| x.as_str())
            .unwrap_or("");
        let is_ech_active = prev.supported
            && listen_port > 0
            && matches!(
                lindo_ech::current_gateway_route_mode(),
                Some(GatewayRouteMode::EchDirect)
            );
        let subtitle =
            format_status_subtitle(prev.supported, mode, is_ech_active, &env, prev.vpn_yielded, &sticky_ip);
        let http_proxy_str = env
            .http_proxy
            .map(|a| a.to_string())
            .unwrap_or_default();
        let diag_line = format_diag_line(
            mode,
            is_ech_active,
            listen_port,
            &sticky_ip,
            &env,
            &http_proxy_str,
            prev.vpn_yielded,
            ech_handshake_count,
            last_ech_accepted,
            gw_err,
        );
        let updated = EchDesktopSnapshot {
            supported: prev.supported,
            active: is_ech_active,
            mode: prev.mode,
            listen_port,
            sticky_ip,
            proxy_or_vpn_active: env.has_proxy_or_vpn(),
            tun_active: env.tun_active,
            http_proxy: http_proxy_str,
            route_iface: env.route_iface,
            vpn_yielded: prev.vpn_yielded,
            ech_handshake_count,
            last_ech_accepted,
            status_subtitle: subtitle,
            diag_line,
        };
        *last_snapshot_lock().lock().unwrap() = updated.clone();
        updated
    } else {
        reconcile_with_mode(mode)
    }
}

pub fn current_snapshot() -> EchDesktopSnapshot {
    last_snapshot_lock().lock().unwrap().clone()
}

pub fn rotate_sticky_ip() {
    lindo_ech::rotate_sticky_ip();
}

pub fn notify_network_route_changed() {
    lindo_ech::notify_network_changed();
}

pub fn notify_ui_ech_updated(app: &AppHandle, snap: &EchDesktopSnapshot) {
    let _ = app.emit("lindo://ech-updated", snap);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_proxy_server_variants() {
        assert_eq!(
            parse_http_proxy_addr("127.0.0.1:7890"),
            Some(SocketAddr::new(IpAddr::V4(Ipv4Addr::new(127, 0, 0, 1)), 7890))
        );
        assert_eq!(
            parse_http_proxy_addr("http://localhost:8888/"),
            Some(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 8888))
        );
        assert_eq!(
            parse_http_proxy_addr("http=127.0.0.1:7890;https=127.0.0.1:7891"),
            Some(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 7891))
        );
        assert_eq!(parse_http_proxy_addr(""), None);
    }

    #[test]
    fn test_mode_decision_matrix_matches_android() {
        let direct_env = NetEnv {
            local_ip: "192.168.1.50".to_string(),
            sys_proxy: false,
            http_proxy: None,
            tun_active: false,
            route_iface: "en0".to_string(),
        };
        let sys_proxy_env = NetEnv {
            sys_proxy: true,
            http_proxy: parse_http_proxy_addr("127.0.0.1:7890"),
            ..direct_env.clone()
        };
        let tun_env = NetEnv {
            local_ip: "198.18.0.1".to_string(),
            tun_active: true,
            route_iface: "utun4".to_string(),
            ..direct_env.clone()
        };

        assert_eq!(
            decide_should_use_ech(EchRoutingMode::SmartAuto, &direct_env),
            (true, false)
        );
        assert_eq!(
            decide_should_use_ech(EchRoutingMode::SmartAuto, &sys_proxy_env),
            (false, false)
        );
        assert_eq!(
            decide_should_use_ech(EchRoutingMode::SmartAuto, &tun_env),
            (false, false)
        );

        assert_eq!(
            decide_should_use_ech(EchRoutingMode::EchPriority, &direct_env),
            (true, false)
        );
        assert_eq!(
            decide_should_use_ech(EchRoutingMode::EchPriority, &sys_proxy_env),
            (true, false)
        );
        assert_eq!(
            decide_should_use_ech(EchRoutingMode::EchPriority, &tun_env),
            (false, true)
        );

        assert_eq!(
            decide_should_use_ech(EchRoutingMode::SystemDefault, &direct_env),
            (false, false)
        );
        assert_eq!(
            EchRoutingMode::from_pref("smart_bypass"),
            EchRoutingMode::SmartAuto
        );
        assert_eq!(
            EchRoutingMode::from_pref("direct_only"),
            EchRoutingMode::EchPriority
        );

        // Unreachable local HTTP proxy under SmartAuto promotes to direct ECH, whereas global TUN still yields
        assert_eq!(
            decide_should_use_ech_with_fallback(EchRoutingMode::SmartAuto, &sys_proxy_env, true),
            (true, false)
        );
        assert_eq!(
            decide_should_use_ech_with_fallback(EchRoutingMode::SmartAuto, &tun_env, true),
            (false, false)
        );
    }

    #[test]
    fn test_status_subtitle_consistency() {
        let env = NetEnv {
            local_ip: "192.168.1.2".to_string(),
            sys_proxy: true,
            http_proxy: None,
            tun_active: false,
            route_iface: "en0".to_string(),
        };
        assert_eq!(
            format_status_subtitle(true, EchRoutingMode::SmartAuto, false, &env, false, ""),
            "检测到代理/VPN · 已自动使用代理通道（关闭代理后自动启用直连）"
        );
        assert_eq!(
            format_status_subtitle(true, EchRoutingMode::EchPriority, true, &env, false, "104.16.132.229"),
            "已开启直连 (en0 · 固定边缘 104.16.132.229)"
        );
    }

    #[test]
    fn test_linux_tun_iface_detection_ignores_split_tunnel_wireguard() {
        assert!(is_linux_tun_iface_name("FlClash"));
        assert!(is_linux_tun_iface_name("mihomo0"));
        assert!(is_linux_tun_iface_name("tun0"));
        assert!(is_linux_tun_iface_name("singtun0"));
        assert!(!is_linux_tun_iface_name("wt0"));
        assert!(!is_linux_tun_iface_name("wlan0"));
        assert!(!is_linux_tun_iface_name("enp8s0"));
        assert!(!is_linux_tun_iface_name(""));
    }
}
