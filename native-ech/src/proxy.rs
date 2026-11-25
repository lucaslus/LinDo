use std::net::{IpAddr, SocketAddr};
use std::pin::Pin;
use std::sync::Arc;
use std::time::Duration;

use boring2::ssl::{AlpnError, Ssl, SslAcceptor, SslMethod};
use bytes::Bytes;
use http::{Response, StatusCode};
use http_body_util::{BodyExt, Full};
use hyper::body::Incoming;
use hyper::service::service_fn;
use hyper::Request;
use hyper_util::rt::{TokioExecutor, TokioIo};
use hyper_util::server::conn::auto;
use parking_lot::RwLock;
use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::oneshot;
use tokio::time::timeout;
use tokio_boring2::SslStream;

use crate::ca::{normalize_host, CaAuthority};
use crate::doh::DohResolver;
use crate::net_bind;
use crate::tls_upstream::{BoxRespBody, UpstreamPool};

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum GatewayRouteMode {
    EchDirect,
    /// Terminates local TLS for `should_mitm_host` and connects upstream via BoringSSL TLS 1.3 + HTTP/2
    /// over the host's normal routing table / TUN (including FakeIP) or explicit upstream `http_proxy`,
    /// without DoH ECH override or physical interface `SO_BINDTODEVICE`.
    SystemTlsBridge { http_proxy: Option<SocketAddr> },
    SystemPassThrough { http_proxy: Option<SocketAddr> },
}

impl GatewayRouteMode {
    pub fn uses_local_tls_mitm(&self) -> bool {
        matches!(self, Self::EchDirect | Self::SystemTlsBridge { .. })
    }
}

/// Returns `true` ONLY for forum/CDN origins where SNI ECH bypass is required.
/// Critically, `challenges.cloudflare.com` and third-party domains are NEVER MITM'd —
/// they use transparent physical-socket TCP tunneling so WebView Turnstile remains intact.
pub fn should_mitm_host(host: &str) -> bool {
    let h = normalize_host(host);
    h == "linux.do"
        || h.ends_with(".linux.do")
        || h == "ldstatic.com"
        || h.ends_with(".ldstatic.com")
        || h == "idcflare.com"
        || h.ends_with(".idcflare.com")
}

pub struct GatewayServer {
    pub listen_port: u16,
    pub ca: Arc<CaAuthority>,
    pub doh: Arc<DohResolver>,
    route_mode: Arc<RwLock<GatewayRouteMode>>,
    shutdown_tx: Option<oneshot::Sender<()>>,
}

impl GatewayServer {
    pub fn start_on_runtime(
        rt: &tokio::runtime::Handle,
        ca: Arc<CaAuthority>,
        doh: Arc<DohResolver>,
        upstream: Arc<UpstreamPool>,
    ) -> Result<Self, String> {
        let std_listener = std::net::TcpListener::bind("127.0.0.1:0")
            .map_err(|e| format!("bind 127.0.0.1:0: {e}"))?;
        std_listener
            .set_nonblocking(true)
            .map_err(|e| format!("set_nonblocking: {e}"))?;
        let listen_port = std_listener
            .local_addr()
            .map_err(|e| format!("local_addr: {e}"))?
            .port();
        let _enter = rt.enter();
        let listener = TcpListener::from_std(std_listener)
            .map_err(|e| format!("TcpListener::from_std: {e}"))?;
        let (tx, mut rx) = oneshot::channel::<()>();
        let route_mode = Arc::new(RwLock::new(GatewayRouteMode::EchDirect));

        let ca_clone = Arc::clone(&ca);
        let doh_clone = Arc::clone(&doh);
        let upstream_clone = Arc::clone(&upstream);
        let mode_clone = Arc::clone(&route_mode);

        rt.spawn(async move {
            loop {
                tokio::select! {
                    _ = &mut rx => {
                        break;
                    }
                    accept_res = listener.accept() => {
                        let Ok((client_stream, _peer)) = accept_res else {
                            continue;
                        };
                        let _ = client_stream.set_nodelay(true);
                        let ca_req = Arc::clone(&ca_clone);
                        let doh_req = Arc::clone(&doh_clone);
                        let up_req = Arc::clone(&upstream_clone);
                        let mode_req = Arc::clone(&mode_clone);
                        tokio::spawn(async move {
                            let _ = handle_proxy_client(
                                client_stream,
                                ca_req,
                                doh_req,
                                up_req,
                                mode_req,
                                listen_port,
                            )
                            .await;
                        });
                    }
                }
            }
        });

        Ok(Self {
            listen_port,
            ca,
            doh,
            route_mode,
            shutdown_tx: Some(tx),
        })
    }

    pub async fn start(
        ca: Arc<CaAuthority>,
        doh: Arc<DohResolver>,
        upstream: Arc<UpstreamPool>,
    ) -> Result<Self, String> {
        Self::start_on_runtime(&tokio::runtime::Handle::current(), ca, doh, upstream)
    }

    pub fn set_route_mode(&self, mode: GatewayRouteMode) {
        *self.route_mode.write() = mode;
    }

    pub fn route_mode(&self) -> GatewayRouteMode {
        self.route_mode.read().clone()
    }

    pub fn stop(&mut self) {
        if let Some(tx) = self.shutdown_tx.take() {
            let _ = tx.send(());
        }
    }
}

async fn handle_proxy_client(
    stream: TcpStream,
    ca: Arc<CaAuthority>,
    doh: Arc<DohResolver>,
    upstream: Arc<UpstreamPool>,
    route_mode: Arc<RwLock<GatewayRouteMode>>,
    listen_port: u16,
) -> Result<(), String> {
    let mut reader = BufReader::new(stream);
    let mut request_line = String::new();
    let n = reader
        .read_line(&mut request_line)
        .await
        .map_err(|e| format!("read connect line: {e}"))?;
    if n == 0 {
        return Ok(());
    }

    // Drain remaining proxy request headers until empty CRLF line.
    loop {
        let mut hdr_line = String::new();
        let read_n = reader
            .read_line(&mut hdr_line)
            .await
            .map_err(|e| format!("read header line: {e}"))?;
        if read_n == 0 || hdr_line == "\r\n" || hdr_line == "\n" {
            break;
        }
    }

    let mut parts = request_line.split_whitespace();
    let method = parts.next().unwrap_or("");
    let authority = parts.next().unwrap_or("");

    let mut client_stream = reader.into_inner();
    if !method.eq_ignore_ascii_case("CONNECT") {
        let _ = client_stream
            .write_all(b"HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\n\r\n")
            .await;
        return Ok(());
    }

    let (host, port) = split_authority(authority);
    if host.is_empty() {
        let _ = client_stream
            .write_all(b"HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n")
            .await;
        return Ok(());
    }

    let mode_now = route_mode.read().clone();
    if mode_now.uses_local_tls_mitm() && port == 443 && should_mitm_host(&host) {
        let leaf = ca.leaf_for_host(&host)?;
        client_stream
            .write_all(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            .await
            .map_err(|e| format!("write connect ok: {e}"))?;
        let _ = client_stream.flush().await;

        let mut acceptor_builder = SslAcceptor::mozilla_intermediate_v5(SslMethod::tls_server())
            .map_err(|e| format!("acceptor builder: {e}"))?;
        acceptor_builder
            .set_certificate(&leaf.cert)
            .map_err(|e| format!("set leaf cert: {e}"))?;
        let _ = acceptor_builder.add_extra_chain_cert(ca.root_cert().clone());
        acceptor_builder
            .set_private_key(&leaf.key)
            .map_err(|e| format!("set leaf key: {e}"))?;
        acceptor_builder
            .check_private_key()
            .map_err(|e| format!("check key: {e}"))?;
        acceptor_builder.set_alpn_select_callback(|_ssl, client_protos| {
            // Prefer h2 when offered, fall back cleanly to http/1.1.
            let mut idx = 0usize;
            let mut offers_h2 = false;
            let mut offers_h1 = false;
            while idx < client_protos.len() {
                let len = client_protos[idx] as usize;
                idx += 1;
                if idx + len > client_protos.len() {
                    break;
                }
                let proto = &client_protos[idx..idx + len];
                if proto == b"h2" {
                    offers_h2 = true;
                } else if proto == b"http/1.1" {
                    offers_h1 = true;
                }
                idx += len;
            }
            if offers_h2 {
                // Find exact slice inside `client_protos` for "h2".
                let mut p = 0usize;
                while p < client_protos.len() {
                    let len = client_protos[p] as usize;
                    p += 1;
                    if p + len <= client_protos.len() && &client_protos[p..p + len] == b"h2" {
                        return Ok(&client_protos[p..p + len]);
                    }
                    p += len;
                }
            }
            if offers_h1 {
                let mut p = 0usize;
                while p < client_protos.len() {
                    let len = client_protos[p] as usize;
                    p += 1;
                    if p + len <= client_protos.len() && &client_protos[p..p + len] == b"http/1.1" {
                        return Ok(&client_protos[p..p + len]);
                    }
                    p += len;
                }
            }
            Err(AlpnError::NOACK)
        });
        let acceptor = acceptor_builder.build();
        let ssl = Ssl::new(acceptor.context()).map_err(|e| format!("server ssl new: {e}"))?;
        let mut tls = SslStream::new(ssl, client_stream).map_err(|e| format!("server wrap: {e}"))?;
        Pin::new(&mut tls)
            .accept()
            .await
            .map_err(|e| format!("local tls accept ({host}): {e}"))?;

        let host_arc: Arc<str> = Arc::from(host.as_str());
        let route_mode_for_service = Arc::clone(&route_mode);
        let service = service_fn(move |req: Request<Incoming>| {
            let host_inner = Arc::clone(&host_arc);
            let up_inner = Arc::clone(&upstream);
            let req_mode = route_mode_for_service.read().clone();
            async move {
                let send_res = match req_mode {
                    GatewayRouteMode::SystemTlsBridge { http_proxy }
                    | GatewayRouteMode::SystemPassThrough { http_proxy } => {
                        up_inner
                            .send_request_via_system(&host_inner, req, http_proxy, listen_port)
                            .await
                    }
                    GatewayRouteMode::EchDirect => up_inner.send_request(&host_inner, req).await,
                };
                let res = match send_res {
                    Ok(resp) => resp,
                    Err(err_msg) => {
                        let body: BoxRespBody = Full::new(Bytes::from(err_msg))
                            .map_err(|never| match never {})
                            .boxed();
                        Response::builder()
                            .status(StatusCode::BAD_GATEWAY)
                            .header("content-type", "text/plain; charset=utf-8")
                            .body(body)
                            .unwrap_or_else(|_| Response::new(Full::new(Bytes::new()).map_err(|n| match n {}).boxed()))
                    }
                };
                Ok::<_, std::convert::Infallible>(res)
            }
        });

        let io = TokioIo::new(tls);
        let _ = auto::Builder::new(TokioExecutor::new())
            .serve_connection(io, service)
            .await;
        Ok(())
    } else {
        // Transparent tunnel pass-through: either Turnstile/third-party host or SystemPassThrough mode.
        let remote = match mode_now {
            GatewayRouteMode::SystemPassThrough { http_proxy }
            | GatewayRouteMode::SystemTlsBridge { http_proxy } => {
                connect_passthrough_target(&host, port, http_proxy, listen_port).await
            }
            GatewayRouteMode::EchDirect => connect_tunnel_target(&doh, &host, port).await?,
        };
        let mut remote_stream = match remote {
            Ok(s) => s,
            Err(err_msg) => {
                let _ = client_stream
                    .write_all(b"HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n")
                    .await;
                return Err(err_msg);
            }
        };
        client_stream
            .write_all(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            .await
            .map_err(|e| format!("write tunnel connect ok: {e}"))?;
        let _ = client_stream.flush().await;
        let _ = tokio::io::copy_bidirectional(&mut client_stream, &mut remote_stream).await;
        Ok(())
    }
}

pub(crate) async fn connect_passthrough_target(
    host: &str,
    port: u16,
    http_proxy: Option<SocketAddr>,
    listen_port: u16,
) -> Result<TcpStream, String> {
    if let Some(proxy_addr) = http_proxy {
        if !(proxy_addr.ip().is_loopback() && proxy_addr.port() == listen_port) {
            if let Ok(stream) = tunnel_via_upstream_http_proxy(proxy_addr, host, port).await {
                return Ok(stream);
            }
        }
    }
    let resolved = tokio::net::lookup_host((host, port))
        .await
        .map_err(|e| format!("passthrough dns failed for {host}:{port}: {e}"))?;
    let mut last_err = format!("passthrough connect failed for {host}:{port}");
    for addr in resolved.take(4) {
        match timeout(Duration::from_millis(5000), TcpStream::connect(addr)).await {
            Ok(Ok(stream)) => {
                let _ = stream.set_nodelay(true);
                return Ok(stream);
            }
            Ok(Err(e)) => last_err = format!("passthrough connect {addr}: {e}"),
            Err(_) => last_err = format!("passthrough connect timeout {addr}"),
        }
    }
    Err(last_err)
}

async fn tunnel_via_upstream_http_proxy(
    proxy_addr: SocketAddr,
    host: &str,
    port: u16,
) -> Result<TcpStream, String> {
    let mut stream = timeout(Duration::from_millis(2500), TcpStream::connect(proxy_addr))
        .await
        .map_err(|_| format!("upstream proxy {proxy_addr} timeout"))?
        .map_err(|e| format!("upstream proxy {proxy_addr} connect: {e}"))?;
    let _ = stream.set_nodelay(true);
    let authority = if host.contains(':') && !host.starts_with('[') {
        format!("[{host}]:{port}")
    } else {
        format!("{host}:{port}")
    };
    let req = format!("CONNECT {authority} HTTP/1.1\r\nHost: {authority}\r\nProxy-Connection: keep-alive\r\n\r\n");
    timeout(Duration::from_millis(2500), stream.write_all(req.as_bytes()))
        .await
        .map_err(|_| "upstream proxy write timeout".to_string())?
        .map_err(|e| format!("upstream proxy write: {e}"))?;
    let _ = stream.flush().await;

    let mut hdr_buf = Vec::with_capacity(256);
    let mut one = [0u8; 1];
    while hdr_buf.len() < 4096 {
        let n = timeout(Duration::from_millis(2500), stream.read(&mut one))
            .await
            .map_err(|_| "upstream proxy header timeout".to_string())?
            .map_err(|e| format!("upstream proxy read: {e}"))?;
        if n == 0 {
            return Err("upstream proxy EOF before CONNECT response".to_string());
        }
        hdr_buf.push(one[0]);
        if hdr_buf.ends_with(b"\r\n\r\n") || hdr_buf.ends_with(b"\n\n") {
            break;
        }
    }
    let head = String::from_utf8_lossy(&hdr_buf);
    let first_line = head.lines().next().unwrap_or("");
    let status_ok = first_line
        .split_whitespace()
        .nth(1)
        .map(|code| code == "200")
        .unwrap_or(false);
    if status_ok {
        Ok(stream)
    } else {
        Err(format!("upstream proxy rejected CONNECT: {first_line}"))
    }
}

async fn connect_tunnel_target(
    doh: &DohResolver,
    host: &str,
    port: u16,
) -> Result<Result<TcpStream, String>, String> {
    let bypass = net_bind::bypass_vpn();
    if let Ok(ip) = host.parse::<IpAddr>() {
        if bypass && !ip.is_loopback() {
            if let IpAddr::V4(v4) = ip {
                if !crate::doh::is_valid_public_ipv4(v4) {
                    return Ok(Err(format!(
                        "refusing non-public tunnel target {ip}:{port} under bypass_vpn"
                    )));
                }
            }
        }
        let addr = SocketAddr::new(ip, port);
        return Ok(net_bind::connect_tcp(addr, Duration::from_millis(3500))
            .await
            .map(|(s, _)| s)
            .map_err(|e| format!("tunnel connect {addr}: {e}")));
    }

    let mut addrs: Vec<SocketAddr> = Vec::new();
    if let Ok(clean_ips) = doh.resolve_clean_a(host).await {
        for ip in clean_ips {
            addrs.push(SocketAddr::new(IpAddr::V4(ip), port));
        }
    }
    // When bypass_vpn is enabled, NEVER fall back to OS DNS (which yields Clash 198.18.x.x FakeIPs
    // or VPN-polluted routes, causing physical-interface blackholes and cross-NIC IP split).
    if addrs.is_empty() && !bypass {
        if let Ok(resolved) = tokio::net::lookup_host((host, port)).await {
            for a in resolved {
                let usable = match a.ip() {
                    IpAddr::V4(v4) => v4.is_loopback() || crate::doh::is_valid_public_ipv4(v4),
                    IpAddr::V6(v6) => !v6.is_unspecified(),
                };
                if usable && !addrs.contains(&a) {
                    addrs.push(a);
                }
            }
        }
    }
    if addrs.is_empty() {
        return Ok(Err(if bypass {
            format!(
                "DoH resolution failed for tunnel {host}:{port} (OS DNS fallback disabled under bypass_vpn)"
            )
        } else {
            format!("dns resolution failed for tunnel {host}:{port}")
        }));
    }

    let mut last_err = String::from("tunnel connect failed");
    for addr in addrs.into_iter().take(4) {
        match net_bind::connect_tcp(addr, Duration::from_millis(3000)).await {
            Ok((stream, _)) => return Ok(Ok(stream)),
            Err(e) => {
                if let IpAddr::V4(failed_v4) = addr.ip() {
                    doh.rotate_host_cache_ip(host, failed_v4);
                }
                last_err = format!("tunnel connect {addr}: {e}");
            }
        }
    }
    Ok(Err(last_err))
}

fn split_authority(authority: &str) -> (String, u16) {
    let trimmed = authority.trim();
    if trimmed.starts_with('[') {
        if let Some((host_part, port_part)) = trimmed.rsplit_once("]:") {
            let h = host_part.trim_start_matches('[').to_string();
            let p = port_part.parse::<u16>().unwrap_or(443);
            return (h, p);
        }
        return (trimmed.trim_matches(|c| c == '[' || c == ']').to_string(), 443);
    }
    if let Some((h, p)) = trimmed.rsplit_once(':') {
        if let Ok(port) = p.parse::<u16>() {
            return (h.to_string(), port);
        }
    }
    (trimmed.to_string(), 443)
}
