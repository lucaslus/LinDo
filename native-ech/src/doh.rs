use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::pin::Pin;
use std::sync::Arc;
use std::time::{Duration, Instant};

use boring2::ssl::{SslConnector, SslMethod, SslVerifyMode};
use boring2::x509::store::X509StoreBuilder;
use boring2::x509::X509;
use parking_lot::Mutex;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::task::JoinSet;
use tokio::time::timeout;
use tokio_boring2::SslStream;

use crate::net_bind;

pub const QTYPE_A: u16 = 1;
pub const QTYPE_HTTPS: u16 = 65;

const SVC_PARAM_IPV4HINT: u16 = 4;
const SVC_PARAM_ECH: u16 = 5;

/// Curated Cloudflare Anycast IPv4 addresses verified to serve `linux.do` / Cloudflare ECH
/// (excludes `104.16.132.229`/`104.16.133.229`, which return Enterprise error 1034 for `linux.do`).
pub const CURATED_CF_IPS: &[Ipv4Addr] = &[
    Ipv4Addr::new(104, 18, 10, 118),
    Ipv4Addr::new(104, 18, 11, 118),
    Ipv4Addr::new(104, 20, 16, 234),
    Ipv4Addr::new(172, 66, 166, 61),
    Ipv4Addr::new(104, 18, 32, 47),
    Ipv4Addr::new(172, 67, 150, 25),
];

#[derive(Clone, Copy)]
struct DohUpstream {
    ip: Ipv4Addr,
    sni: &'static str,
    path: &'static str,
}

const DOH_UPSTREAMS: &[DohUpstream] = &[
    DohUpstream {
        ip: Ipv4Addr::new(223, 5, 5, 5),
        sni: "dns.alidns.com",
        path: "/dns-query",
    },
    DohUpstream {
        ip: Ipv4Addr::new(223, 6, 6, 6),
        sni: "dns.alidns.com",
        path: "/dns-query",
    },
    DohUpstream {
        ip: Ipv4Addr::new(1, 12, 12, 12),
        sni: "doh.pub",
        path: "/dns-query",
    },
    DohUpstream {
        ip: Ipv4Addr::new(120, 53, 53, 53),
        sni: "doh.pub",
        path: "/dns-query",
    },
    DohUpstream {
        ip: Ipv4Addr::new(1, 1, 1, 1),
        sni: "cloudflare-dns.com",
        path: "/dns-query",
    },
    DohUpstream {
        ip: Ipv4Addr::new(1, 0, 0, 1),
        sni: "cloudflare-dns.com",
        path: "/dns-query",
    },
];

#[derive(Debug, Clone, Default)]
pub struct ParsedDnsResponse {
    pub ipv4: Vec<Ipv4Addr>,
    pub ech_configs: Vec<Vec<u8>>,
    pub ipv4_hints: Vec<Ipv4Addr>,
    pub min_ttl_secs: u32,
}

#[derive(Clone)]
pub struct EchBootstrap {
    pub ech_config_list: Vec<u8>,
    pub sticky_ip: Ipv4Addr,
    pub candidates: Vec<Ipv4Addr>,
    pub network_gen: u64,
    pub fetched_at: Instant,
    pub ttl: Duration,
}

#[derive(Clone)]
struct HostCacheEntry {
    ips: Vec<Ipv4Addr>,
    network_gen: u64,
    expires_at: Instant,
}

pub struct DohResolver {
    tls_connector: Arc<SslConnector>,
    ech_cache: Arc<tokio::sync::Mutex<Option<EchBootstrap>>>,
    host_cache: Mutex<HashMap<String, HostCacheEntry>>,
    last_sticky_ip: Mutex<Option<(u64, Ipv4Addr)>>,
    avoid_sticky_ip: Mutex<Option<Ipv4Addr>>,
}

impl DohResolver {
    pub fn new() -> Result<Arc<Self>, String> {
        let mut builder = SslConnector::builder(SslMethod::tls_client())
            .map_err(|e| format!("doh ssl builder: {e}"))?;
        builder.set_verify(SslVerifyMode::PEER);
        let mut store = X509StoreBuilder::new().map_err(|e| format!("doh store: {e}"))?;
        for der in webpki_root_certs::TLS_SERVER_ROOT_CERTS {
            if let Ok(cert) = X509::from_der(der) {
                let _ = store.add_cert(cert);
            }
        }
        builder
            .set_verify_cert_store(store.build())
            .map_err(|e| format!("doh set verify store: {e}"))?;
        let _ = builder.set_alpn_protos(b"\x08http/1.1");
        Ok(Arc::new(Self {
            tls_connector: Arc::new(builder.build()),
            ech_cache: Arc::new(tokio::sync::Mutex::new(None)),
            host_cache: Mutex::new(HashMap::new()),
            last_sticky_ip: Mutex::new(None),
            avoid_sticky_ip: Mutex::new(None),
        }))
    }

    /// Returns cached `(ech_config_list, sticky_ip)` or resolves `cloudflare-ech.com`
    /// over direct-IP DoH (`223.5.5.5` / `1.12.12.12`) bound to the physical network and pins a
    /// fast reachable Cloudflare Anycast IP for the current `network_gen`.
    pub async fn get_ech_and_sticky_ip(&self) -> Result<(Vec<u8>, Ipv4Addr), String> {
        let cur_gen = net_bind::network_gen();
        {
            let guard = self.ech_cache.lock().await;
            if let Some(ref cached) = *guard {
                if cached.network_gen == cur_gen
                    && cached.fetched_at.elapsed() < cached.ttl
                    && !cached.ech_config_list.is_empty()
                {
                    return Ok((cached.ech_config_list.clone(), cached.sticky_ip));
                }
            }
        }

        let mut guard = self.ech_cache.lock().await;
        let cur_gen = net_bind::network_gen();
        if let Some(ref cached) = *guard {
            if cached.network_gen == cur_gen
                && cached.fetched_at.elapsed() < cached.ttl
                && !cached.ech_config_list.is_empty()
            {
                return Ok((cached.ech_config_list.clone(), cached.sticky_ip));
            }
        }

        let (https_res, a_res) = tokio::join!(
            self.query_first("cloudflare-ech.com", QTYPE_HTTPS),
            self.query_first("linux.do", QTYPE_A)
        );

        let mut ech_list = https_res
            .as_ref()
            .ok()
            .and_then(|r| r.ech_configs.first().cloned())
            .unwrap_or_default();
        if ech_list.is_empty() {
            if let Ok(r2) = self.query_first("linux.do", QTYPE_HTTPS).await {
                if let Some(first) = r2.ech_configs.into_iter().next() {
                    ech_list = first;
                }
            }
        }
        if ech_list.is_empty() {
            return Err("no valid ECHConfigList returned by DoH".to_string());
        }

        let mut candidates: Vec<Ipv4Addr> = Vec::new();
        if let Ok(ref h) = https_res {
            for &ip in &h.ipv4_hints {
                if is_cloudflare_for_linux_do(ip) && !candidates.contains(&ip) {
                    candidates.push(ip);
                }
            }
        }
        if let Ok(ref a) = a_res {
            for &ip in &a.ipv4 {
                if is_cloudflare_for_linux_do(ip) && !candidates.contains(&ip) {
                    candidates.push(ip);
                }
            }
        }
        for &ip in CURATED_CF_IPS {
            if !candidates.contains(&ip) {
                candidates.push(ip);
            }
        }
        let avoided = *self.avoid_sticky_ip.lock();
        if let Some(bad_ip) = avoided {
            if candidates.len() > 1 {
                if let Some(pos) = candidates.iter().position(|&ip| ip == bad_ip) {
                    let removed = candidates.remove(pos);
                    candidates.push(removed);
                }
            }
        }

        let sticky_ip = race_fastest_cf_ip(&candidates).await.unwrap_or(candidates[0]);
        *self.last_sticky_ip.lock() = Some((cur_gen, sticky_ip));
        let ttl_secs = https_res
            .as_ref()
            .ok()
            .map(|r| r.min_ttl_secs)
            .unwrap_or(180)
            .clamp(60, 600);

        *guard = Some(EchBootstrap {
            ech_config_list: ech_list.clone(),
            sticky_ip,
            candidates,
            network_gen: cur_gen,
            fetched_at: Instant::now(),
            ttl: Duration::from_secs(ttl_secs as u64),
        });

        Ok((ech_list, sticky_ip))
    }

    pub fn cached_sticky_ip_sync(&self) -> Option<Ipv4Addr> {
        let cur_gen = net_bind::network_gen();
        self.last_sticky_ip
            .lock()
            .filter(|(g, _)| *g == cur_gen)
            .map(|(_, ip)| ip)
    }

    pub async fn cached_sticky_ip(&self) -> Option<Ipv4Addr> {
        self.cached_sticky_ip_sync()
    }

    /// Marks the currently active sticky IP (if any) as degraded so subsequent rotations or DoH
    /// races deprioritize it behind fresh candidates.
    pub fn note_degraded_current_sticky_ip(&self) -> Option<Ipv4Addr> {
        let prev = self.last_sticky_ip.lock().map(|(_, ip)| ip);
        if let Some(ip) = prev {
            *self.avoid_sticky_ip.lock() = Some(ip);
        }
        prev
    }

    /// Synchronously advances cached sticky IP onto `new_gen` if a valid bootstrap cache is unlocked,
    /// allowing immediate status inspection right after `nativeRotateStickyIp` / mode switches.
    pub fn try_advance_sticky_ip_for_gen_sync(&self, new_gen: u64) -> Option<Ipv4Addr> {
        let mut guard = self.ech_cache.try_lock().ok()?;
        let cached = guard.as_mut()?;
        if cached.ech_config_list.is_empty() || cached.fetched_at.elapsed() >= cached.ttl {
            return None;
        }
        let prev = cached.sticky_ip;
        *self.avoid_sticky_ip.lock() = Some(prev);
        let next = rotate_cached_sticky_ip(cached)?;
        cached.network_gen = new_gen;
        *self.last_sticky_ip.lock() = Some((new_gen, next));
        Some(next)
    }

    /// Re-races candidate Cloudflare Anycast IPs (deprioritizing the degraded IP) or re-bootstraps
    /// DoH ECH config for the current `network_gen`.
    pub async fn refresh_sticky_ip_after_rotation(&self) {
        let target_gen = net_bind::network_gen();
        let avoided = *self.avoid_sticky_ip.lock();
        let race_candidates = {
            let mut guard = self.ech_cache.lock().await;
            if let Some(ref mut cached) = *guard {
                if !cached.ech_config_list.is_empty() && cached.fetched_at.elapsed() < cached.ttl {
                    if cached.network_gen != target_gen {
                        let _ = rotate_cached_sticky_ip(cached);
                        cached.network_gen = target_gen;
                        *self.last_sticky_ip.lock() = Some((target_gen, cached.sticky_ip));
                    }
                    let mut ordered: Vec<Ipv4Addr> = cached
                        .candidates
                        .iter()
                        .copied()
                        .filter(|ip| Some(*ip) != avoided)
                        .collect();
                    if let Some(bad) = avoided {
                        if cached.candidates.contains(&bad) {
                            ordered.push(bad);
                        }
                    }
                    Some(ordered)
                } else {
                    None
                }
            } else {
                None
            }
        };

        if let Some(ordered) = race_candidates {
            if let Some(fastest) = race_fastest_cf_ip(&ordered).await {
                let mut guard = self.ech_cache.lock().await;
                if let Some(ref mut cached) = *guard {
                    if cached.network_gen == target_gen && net_bind::network_gen() == target_gen {
                        cached.sticky_ip = fastest;
                        *self.last_sticky_ip.lock() = Some((target_gen, fastest));
                    }
                }
            }
        } else {
            let _ = self.get_ech_and_sticky_ip().await;
        }
    }

    /// Updates cached `ech_config_list` after an ECH retry-config response or rotates sticky IP
    /// if the current sticky Cloudflare IP failed TCP/TLS handshake or returned Edge 1034.
    pub async fn update_ech_retry_configs(&self, retry_configs: Vec<u8>) {
        if retry_configs.is_empty() {
            return;
        }
        let mut guard = self.ech_cache.lock().await;
        if let Some(ref mut cached) = *guard {
            cached.ech_config_list = retry_configs;
            cached.fetched_at = Instant::now();
        }
    }

    pub async fn rotate_sticky_ip(&self, failed_ip: Ipv4Addr) -> Option<Ipv4Addr> {
        *self.avoid_sticky_ip.lock() = Some(failed_ip);
        let mut guard = self.ech_cache.lock().await;
        let cached = guard.as_mut()?;
        if cached.sticky_ip != failed_ip {
            return Some(cached.sticky_ip);
        }
        let next = rotate_cached_sticky_ip(cached)?;
        *self.last_sticky_ip.lock() = Some((cached.network_gen, next));
        Some(next)
    }

    pub async fn rotate_current_sticky_ip(&self) -> Option<Ipv4Addr> {
        let mut guard = self.ech_cache.lock().await;
        let cached = guard.as_mut()?;
        *self.avoid_sticky_ip.lock() = Some(cached.sticky_ip);
        let next = rotate_cached_sticky_ip(cached)?;
        *self.last_sticky_ip.lock() = Some((cached.network_gen, next));
        Some(next)
    }

    pub fn try_rotate_current_sticky_ip_sync(&self) -> Option<Ipv4Addr> {
        let mut guard = self.ech_cache.try_lock().ok()?;
        let cached = guard.as_mut()?;
        *self.avoid_sticky_ip.lock() = Some(cached.sticky_ip);
        let next = rotate_cached_sticky_ip(cached)?;
        *self.last_sticky_ip.lock() = Some((cached.network_gen, next));
        Some(next)
    }

    /// Rotates a failed IPv4 address to the back of `host_cache` so subsequent transparent tunnel
    /// connections (e.g., `challenges.cloudflare.com`) immediately try a healthy candidate first.
    pub fn rotate_host_cache_ip(&self, host: &str, failed_ip: Ipv4Addr) {
        let clean = crate::ca::normalize_host(host);
        let mut guard = self.host_cache.lock();
        if let Some(entry) = guard.get_mut(&clean) {
            if entry.ips.len() > 1 && entry.ips.first() == Some(&failed_ip) {
                let first = entry.ips.remove(0);
                entry.ips.push(first);
            }
        }
    }

    /// Clean DoH `A` + `HTTPS ipv4hint` resolution with concurrent fast-TCP candidate ordering
    /// for transparent pass-through hosts (such as `challenges.cloudflare.com`, never MITM'd).
    pub async fn resolve_clean_a(&self, host: &str) -> Result<Vec<Ipv4Addr>, String> {
        let clean = crate::ca::normalize_host(host);
        if clean.is_empty() {
            return Err("empty host".to_string());
        }
        if let Ok(ip) = clean.parse::<Ipv4Addr>() {
            if net_bind::bypass_vpn() && !ip.is_loopback() && !is_valid_public_ipv4(ip) {
                return Err(format!(
                    "refusing non-public IPv4 literal {ip} under bypass_vpn"
                ));
            }
            return Ok(vec![ip]);
        }
        let cur_gen = net_bind::network_gen();
        {
            let guard = self.host_cache.lock();
            if let Some(entry) = guard.get(&clean) {
                if entry.network_gen == cur_gen
                    && Instant::now() < entry.expires_at
                    && !entry.ips.is_empty()
                {
                    return Ok(entry.ips.clone());
                }
            }
        }

        let cf_zone = is_cloudflare_zone_host(&clean);
        let (a_res, https_res) = tokio::join!(
            self.query_first(&clean, QTYPE_A),
            self.query_first(&clean, QTYPE_HTTPS)
        );
        if a_res.is_err() && https_res.is_err() {
            return Err(a_res.err().unwrap_or_else(|| format!("DoH failed for {clean}")));
        }

        let accept_ip = |ip: Ipv4Addr| -> bool {
            if cf_zone {
                is_cloudflare_anycast_ipv4(ip)
            } else {
                is_valid_public_ipv4(ip)
            }
        };

        let mut candidates: Vec<Ipv4Addr> = Vec::new();
        let mut min_ttl_secs: u32 = 180;
        if let Ok(ref parsed_a) = a_res {
            min_ttl_secs = min_ttl_secs.min(parsed_a.min_ttl_secs);
            for &ip in &parsed_a.ipv4 {
                if accept_ip(ip) && !candidates.contains(&ip) {
                    candidates.push(ip);
                }
            }
        }
        if let Ok(ref parsed_h) = https_res {
            min_ttl_secs = min_ttl_secs.min(parsed_h.min_ttl_secs);
            for &ip in &parsed_h.ipv4_hints {
                if accept_ip(ip) && !candidates.contains(&ip) {
                    candidates.push(ip);
                }
            }
            for &ip in &parsed_h.ipv4 {
                if accept_ip(ip) && !candidates.contains(&ip) {
                    candidates.push(ip);
                }
            }
        }

        // Supplement from an alternate DoH provider if a Cloudflare zone yielded fewer than 2 IPs.
        if cf_zone && candidates.len() < 2 {
            let query = build_dns_query(&clean, QTYPE_A);
            for upstream in DOH_UPSTREAMS.iter().skip(2).take(3) {
                if let Ok(extra) = self
                    .query_single_upstream(*upstream, &query, &clean, QTYPE_A)
                    .await
                {
                    for ip in extra.ipv4 {
                        if accept_ip(ip) && !candidates.contains(&ip) {
                            candidates.push(ip);
                        }
                    }
                    if candidates.len() >= 3 {
                        break;
                    }
                }
            }
        }

        if candidates.is_empty() {
            return Err(format!("DoH A/HTTPS for {clean} yielded no clean public IPv4"));
        }

        let ordered = order_candidates_by_fast_tcp(&candidates, 443).await;
        let ttl = Duration::from_secs(min_ttl_secs.clamp(60, 600) as u64);
        self.host_cache.lock().insert(
            clean,
            HostCacheEntry {
                ips: ordered.clone(),
                network_gen: cur_gen,
                expires_at: Instant::now() + ttl,
            },
        );
        Ok(ordered)
    }

    #[cfg(test)]
    pub async fn seed_ech_cache_for_test(
        &self,
        ech_config_list: Vec<u8>,
        candidates: Vec<Ipv4Addr>,
    ) {
        let cur_gen = net_bind::network_gen();
        let sticky_ip = candidates[0];
        *self.avoid_sticky_ip.lock() = None;
        *self.last_sticky_ip.lock() = Some((cur_gen, sticky_ip));
        *self.ech_cache.lock().await = Some(EchBootstrap {
            ech_config_list,
            sticky_ip,
            candidates,
            network_gen: cur_gen,
            fetched_at: Instant::now(),
            ttl: Duration::from_secs(300),
        });
    }

    async fn query_first(&self, domain: &str, qtype: u16) -> Result<ParsedDnsResponse, String> {
        let query = build_dns_query(domain, qtype);
        let mut last_err = String::from("no DoH upstream succeeded");
        for upstream in DOH_UPSTREAMS {
            match self
                .query_single_upstream(*upstream, &query, domain, qtype)
                .await
            {
                Ok(parsed) => {
                    if (qtype == QTYPE_A && !parsed.ipv4.is_empty())
                        || (qtype == QTYPE_HTTPS
                            && (!parsed.ech_configs.is_empty() || !parsed.ipv4_hints.is_empty()))
                    {
                        return Ok(parsed);
                    }
                    last_err = format!("{} returned empty answers for {domain}", upstream.ip);
                }
                Err(err) => {
                    last_err = format!("{}: {err}", upstream.ip);
                }
            }
        }
        Err(last_err)
    }

    async fn query_single_upstream(
        &self,
        upstream: DohUpstream,
        dns_wire: &[u8],
        _domain: &str,
        _qtype: u16,
    ) -> Result<ParsedDnsResponse, String> {
        let addr = SocketAddr::new(IpAddr::V4(upstream.ip), 443);
        let (tcp, _) = net_bind::connect_tcp(addr, Duration::from_millis(2500))
            .await
            .map_err(|e| format!("tcp connect: {e}"))?;

        let ssl = self
            .tls_connector
            .configure()
            .map_err(|e| format!("ssl configure: {e}"))?
            .into_ssl(upstream.sni)
            .map_err(|e| format!("ssl sni: {e}"))?;
        let mut tls = SslStream::new(ssl, tcp).map_err(|e| format!("ssl wrap: {e}"))?;
        timeout(Duration::from_millis(3000), Pin::new(&mut tls).connect())
            .await
            .map_err(|_| "doh tls timeout".to_string())?
            .map_err(|e| format!("doh tls handshake: {e}"))?;

        let req = format!(
            "POST {} HTTP/1.1\r\nHost: {}\r\nAccept: application/dns-message\r\nContent-Type: application/dns-message\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
            upstream.path,
            upstream.sni,
            dns_wire.len()
        );
        tls.write_all(req.as_bytes())
            .await
            .map_err(|e| format!("doh write hdr: {e}"))?;
        tls.write_all(dns_wire)
            .await
            .map_err(|e| format!("doh write body: {e}"))?;
        tls.flush()
            .await
            .map_err(|e| format!("doh flush: {e}"))?;

        let mut buf = Vec::with_capacity(2048);
        let read_res = timeout(Duration::from_millis(3000), async {
            let mut tmp = [0u8; 2048];
            loop {
                match tls.read(&mut tmp).await {
                    Ok(0) => break Ok(()),
                    Ok(n) => {
                        buf.extend_from_slice(&tmp[..n]);
                        if buf.len() > 65_536 {
                            break Ok(());
                        }
                    }
                    Err(e) => break Err(e),
                }
            }
        })
        .await
        .map_err(|_| "doh read timeout".to_string())?;
        if let Err(e) = read_res {
            if buf.is_empty() {
                return Err(format!("doh read error: {e}"));
            }
        }

        let decoded = extract_http1_body(&buf)?;
        parse_dns_response(&decoded)
    }
}

async fn race_fastest_cf_ip(candidates: &[Ipv4Addr]) -> Option<Ipv4Addr> {
    if candidates.is_empty() {
        return None;
    }
    let mut set = JoinSet::new();
    for &ip in candidates.iter().take(4) {
        set.spawn(async move {
            let addr = SocketAddr::new(IpAddr::V4(ip), 443);
            match net_bind::connect_tcp(addr, Duration::from_millis(2000)).await {
                Ok(_) => Some(ip),
                Err(_) => None,
            }
        });
    }
    while let Some(res) = set.join_next().await {
        if let Ok(Some(ip)) = res {
            set.abort_all();
            return Some(ip);
        }
    }
    None
}

/// Concurrent TCP-connect preference ordering for transparent tunnel candidate addresses.
/// Probes up to 6 candidates in parallel over the active `net_bind` interface and moves the
/// fastest reachable candidate to position `0`, pushing early-failed candidates to the back.
pub async fn order_socket_addrs_by_fast_tcp(
    addrs: &[SocketAddr],
    probe_timeout: Duration,
) -> Vec<SocketAddr> {
    if addrs.len() <= 1 {
        return addrs.to_vec();
    }
    let probe_limit = addrs.len().min(6);
    let mut failed_early = vec![false; addrs.len()];
    let mut set = JoinSet::new();
    for (idx, &addr) in addrs.iter().enumerate().take(probe_limit) {
        set.spawn(async move {
            let ok = net_bind::connect_tcp(addr, probe_timeout).await.is_ok();
            (idx, addr, ok)
        });
    }

    let mut winner: Option<(usize, SocketAddr)> = None;
    while let Some(res) = set.join_next().await {
        if let Ok((idx, addr, ok)) = res {
            if ok {
                winner = Some((idx, addr));
                set.abort_all();
                break;
            } else {
                failed_early[idx] = true;
            }
        }
    }

    let Some((win_idx, win_addr)) = winner else {
        return addrs.to_vec();
    };

    let mut ordered = Vec::with_capacity(addrs.len());
    ordered.push(win_addr);
    for (idx, &addr) in addrs.iter().enumerate() {
        if idx != win_idx && !failed_early[idx] {
            ordered.push(addr);
        }
    }
    for (idx, &addr) in addrs.iter().enumerate() {
        if idx != win_idx && failed_early[idx] {
            ordered.push(addr);
        }
    }
    ordered
}

pub async fn order_candidates_by_fast_tcp(candidates: &[Ipv4Addr], port: u16) -> Vec<Ipv4Addr> {
    if candidates.len() <= 1 {
        return candidates.to_vec();
    }
    let sock_addrs: Vec<SocketAddr> = candidates
        .iter()
        .map(|&ip| SocketAddr::new(IpAddr::V4(ip), port))
        .collect();
    order_socket_addrs_by_fast_tcp(&sock_addrs, Duration::from_millis(1800))
        .await
        .into_iter()
        .filter_map(|sa| match sa.ip() {
            IpAddr::V4(v4) => Some(v4),
            IpAddr::V6(_) => None,
        })
        .collect()
}

fn is_cloudflare_zone_host(clean_host: &str) -> bool {
    clean_host == "challenges.cloudflare.com"
        || clean_host == "cloudflare.com"
        || clean_host.ends_with(".cloudflare.com")
        || clean_host == "cloudflareinsights.com"
        || clean_host.ends_with(".cloudflareinsights.com")
}

/// Validates that an IPv4 address belongs to any official Cloudflare public Anycast range
/// (including `104.16.0.0/15`, which legitimately serves `challenges.cloudflare.com`).
pub fn is_cloudflare_anycast_ipv4(ip: Ipv4Addr) -> bool {
    if !is_valid_public_ipv4(ip) {
        return false;
    }
    let v = u32::from(ip);
    in_cidr(v, 104, 16, 0, 0, 15)
        || in_cidr(v, 173, 245, 48, 0, 20)
        || in_cidr(v, 103, 21, 244, 0, 22)
        || in_cidr(v, 103, 22, 200, 0, 22)
        || in_cidr(v, 103, 31, 4, 0, 22)
        || in_cidr(v, 141, 101, 64, 0, 18)
        || in_cidr(v, 108, 162, 192, 0, 18)
        || in_cidr(v, 190, 93, 240, 0, 20)
        || in_cidr(v, 188, 114, 96, 0, 20)
        || in_cidr(v, 197, 234, 240, 0, 22)
        || in_cidr(v, 198, 41, 128, 0, 17)
        || in_cidr(v, 162, 158, 0, 0, 15)
        || in_cidr(v, 104, 18, 0, 0, 15)
        || in_cidr(v, 104, 20, 0, 0, 14)
        || in_cidr(v, 104, 24, 0, 0, 14)
        || in_cidr(v, 172, 64, 0, 0, 13)
        || in_cidr(v, 131, 0, 72, 0, 22)
}

pub fn is_valid_public_ipv4(ip: Ipv4Addr) -> bool {
    let o = ip.octets();
    if ip.is_unspecified()
        || ip.is_loopback()
        || ip.is_private()
        || ip.is_link_local()
        || ip.is_multicast()
        || ip.is_broadcast()
    {
        return false;
    }
    // 100.64.0.0/10 CGNAT
    if o[0] == 100 && (o[1] & 0xC0) == 64 {
        return false;
    }
    // 198.18.0.0/15 Clash / Sing-box FakeIP + benchmarking range
    if o[0] == 198 && (o[1] == 18 || o[1] == 19) {
        return false;
    }
    // Reserved 240.0.0.0/4
    if o[0] >= 240 {
        return false;
    }
    true
}

fn in_cidr(val: u32, a: u8, b: u8, c: u8, d: u8, prefix: u32) -> bool {
    let base = u32::from(Ipv4Addr::new(a, b, c, d));
    let mask = if prefix == 0 { 0 } else { !0u32 << (32 - prefix) };
    (val & mask) == (base & mask)
}

/// Validates that an IPv4 address belongs to Cloudflare's public Anycast ranges that serve
/// `linux.do` (filtering both GFW-poisoned Twitter/Facebook IPs returned by domestic DoH and
/// `104.16.0.0/15` Enterprise-only nodes that return HTTP 403 error code 1034).
pub fn is_cloudflare_for_linux_do(ip: Ipv4Addr) -> bool {
    if !is_valid_public_ipv4(ip) {
        return false;
    }
    let o = ip.octets();
    if o[0] == 104 && (o[1] == 16 || o[1] == 17) {
        return false;
    }
    let v = u32::from(ip);
    in_cidr(v, 173, 245, 48, 0, 20)
        || in_cidr(v, 103, 21, 244, 0, 22)
        || in_cidr(v, 103, 22, 200, 0, 22)
        || in_cidr(v, 103, 31, 4, 0, 22)
        || in_cidr(v, 141, 101, 64, 0, 18)
        || in_cidr(v, 108, 162, 192, 0, 18)
        || in_cidr(v, 190, 93, 240, 0, 20)
        || in_cidr(v, 188, 114, 96, 0, 20)
        || in_cidr(v, 197, 234, 240, 0, 22)
        || in_cidr(v, 198, 41, 128, 0, 17)
        || in_cidr(v, 162, 158, 0, 0, 15)
        || in_cidr(v, 104, 18, 0, 0, 15)
        || in_cidr(v, 104, 20, 0, 0, 14)
        || in_cidr(v, 104, 24, 0, 0, 14)
        || in_cidr(v, 172, 64, 0, 0, 13)
        || in_cidr(v, 131, 0, 72, 0, 22)
}

fn rotate_cached_sticky_ip(cached: &mut EchBootstrap) -> Option<Ipv4Addr> {
    if cached.candidates.len() <= 1 {
        return None;
    }
    let pos = cached
        .candidates
        .iter()
        .position(|&ip| ip == cached.sticky_ip)
        .unwrap_or(0);
    let next = cached.candidates[(pos + 1) % cached.candidates.len()];
    cached.sticky_ip = next;
    Some(next)
}

fn extract_http1_body(raw: &[u8]) -> Result<Vec<u8>, String> {
    let Some(hdr_end) = raw.windows(4).position(|w| w == b"\r\n\r\n") else {
        return Err("incomplete HTTP/1.1 DoH response".to_string());
    };
    let head = std::str::from_utf8(&raw[..hdr_end]).unwrap_or("");
    let status_line = head.lines().next().unwrap_or("");
    if !status_line.contains(" 200 ") {
        return Err(format!("non-200 DoH status: {status_line}"));
    }
    let body = &raw[hdr_end + 4..];
    if head
        .to_ascii_lowercase()
        .contains("transfer-encoding: chunked")
    {
        return decode_chunked_body(body);
    }
    Ok(body.to_vec())
}

fn decode_chunked_body(raw: &[u8]) -> Result<Vec<u8>, String> {
    let mut out = Vec::new();
    let mut pos = 0usize;
    while pos < raw.len() {
        let Some(rel_crlf) = raw[pos..].windows(2).position(|w| w == b"\r\n") else {
            return Err("malformed chunked header".to_string());
        };
        let line = std::str::from_utf8(&raw[pos..pos + rel_crlf])
            .map_err(|_| "invalid chunked size line".to_string())?;
        let hex_len = line.split(';').next().unwrap_or("").trim();
        let chunk_len = usize::from_str_radix(hex_len, 16)
            .map_err(|_| format!("invalid chunk len '{hex_len}'"))?;
        pos += rel_crlf + 2;
        if chunk_len == 0 {
            return Ok(out);
        }
        let end = pos.checked_add(chunk_len).ok_or("chunk overflow")?;
        if end + 2 > raw.len() {
            return Err("truncated chunked body".to_string());
        }
        out.extend_from_slice(&raw[pos..end]);
        pos = end + 2;
    }
    Ok(out)
}

/// Encodes a standard RFC 1035 / RFC 8484 DNS query wire packet.
pub fn build_dns_query(domain: &str, qtype: u16) -> Vec<u8> {
    let mut buf = Vec::with_capacity(64);
    // Header: ID = 0 (recommended by RFC 8484), RD = 1, QDCOUNT = 1
    buf.extend_from_slice(&[
        0x00, 0x00, // ID
        0x01, 0x00, // Flags: standard query with RD
        0x00, 0x01, // QDCOUNT
        0x00, 0x00, // ANCOUNT
        0x00, 0x00, // NSCOUNT
        0x00, 0x00, // ARCOUNT
    ]);
    for label in domain.trim_end_matches('.').split('.') {
        let bytes = label.as_bytes();
        let len = bytes.len().min(63);
        buf.push(len as u8);
        buf.extend_from_slice(&bytes[..len]);
    }
    buf.push(0x00);
    buf.extend_from_slice(&qtype.to_be_bytes());
    buf.extend_from_slice(&1u16.to_be_bytes()); // QCLASS = IN
    buf
}

/// Parses an RFC 1035 / RFC 9460 DNS response wire packet for `A` (Type 1) and
/// `HTTPS` (Type 65) `EchConfigList` (SvcParam key=5) / `ipv4hint` (key=4) records.
pub fn parse_dns_response(pkt: &[u8]) -> Result<ParsedDnsResponse, String> {
    if pkt.len() < 12 {
        return Err("dns packet shorter than header".to_string());
    }
    let qdcount = u16::from_be_bytes([pkt[4], pkt[5]]) as usize;
    let ancount = u16::from_be_bytes([pkt[6], pkt[7]]) as usize;

    let mut off = 12usize;
    for _ in 0..qdcount {
        off = skip_dns_name(pkt, off)?;
        off = off.checked_add(4).ok_or("dns question truncated")?;
        if off > pkt.len() {
            return Err("dns question past packet end".to_string());
        }
    }

    let mut out = ParsedDnsResponse::default();
    let mut min_ttl = u32::MAX;

    for _ in 0..ancount {
        if off >= pkt.len() {
            break;
        }
        off = skip_dns_name(pkt, off)?;
        if off + 10 > pkt.len() {
            return Err("dns rr header truncated".to_string());
        }
        let rtype = u16::from_be_bytes([pkt[off], pkt[off + 1]]);
        let rclass = u16::from_be_bytes([pkt[off + 2], pkt[off + 3]]);
        let ttl = u32::from_be_bytes([pkt[off + 4], pkt[off + 5], pkt[off + 6], pkt[off + 7]]);
        let rdlength = u16::from_be_bytes([pkt[off + 8], pkt[off + 9]]) as usize;
        off += 10;
        let rdend = off.checked_add(rdlength).ok_or("dns rdata overflow")?;
        if rdend > pkt.len() {
            return Err("dns rdata truncated".to_string());
        }
        let rdata = &pkt[off..rdend];
        off = rdend;

        if rclass != 1 {
            continue;
        }
        min_ttl = min_ttl.min(ttl);

        match rtype {
            QTYPE_A if rdata.len() == 4 => {
                out.ipv4
                    .push(Ipv4Addr::new(rdata[0], rdata[1], rdata[2], rdata[3]));
            }
            QTYPE_HTTPS => {
                parse_https_svcb_rdata(rdata, &mut out);
            }
            _ => {}
        }
    }

    out.min_ttl_secs = if min_ttl == u32::MAX { 180 } else { min_ttl };
    Ok(out)
}

fn parse_https_svcb_rdata(rdata: &[u8], out: &mut ParsedDnsResponse) {
    if rdata.len() < 3 {
        return;
    }
    // 2 bytes SvcPriority + TargetName (uncompressed in SVCB per RFC 9460 §2.2, allow pointer gracefully)
    let priority = u16::from_be_bytes([rdata[0], rdata[1]]);
    if priority == 0 {
        return; // AliasMode carries no SvcParams
    }
    let Ok(mut pos) = skip_dns_name(rdata, 2) else {
        return;
    };
    while pos + 4 <= rdata.len() {
        let key = u16::from_be_bytes([rdata[pos], rdata[pos + 1]]);
        let val_len = u16::from_be_bytes([rdata[pos + 2], rdata[pos + 3]]) as usize;
        pos += 4;
        if pos + val_len > rdata.len() {
            break;
        }
        let val = &rdata[pos..pos + val_len];
        pos += val_len;
        if key == SVC_PARAM_ECH && !val.is_empty() {
            out.ech_configs.push(val.to_vec());
        } else if key == SVC_PARAM_IPV4HINT {
            for chunk in val.chunks_exact(4) {
                out.ipv4_hints
                    .push(Ipv4Addr::new(chunk[0], chunk[1], chunk[2], chunk[3]));
            }
        }
    }
}

fn skip_dns_name(pkt: &[u8], mut off: usize) -> Result<usize, String> {
    let mut jumped = 0usize;
    loop {
        if off >= pkt.len() {
            return Err("dns name out of bounds".to_string());
        }
        let len = pkt[off];
        if len == 0 {
            return Ok(off + 1);
        }
        if (len & 0xC0) == 0xC0 {
            if off + 1 >= pkt.len() {
                return Err("dns compression pointer truncated".to_string());
            }
            return Ok(off + 2);
        }
        if (len & 0xC0) != 0 {
            return Err("invalid dns label bits".to_string());
        }
        let step = 1usize + len as usize;
        off = off.checked_add(step).ok_or("dns name offset overflow")?;
        jumped += 1;
        if jumped > 128 {
            return Err("dns name too deep".to_string());
        }
    }
}
