use std::collections::HashMap;
#[cfg(not(any(target_os = "macos", target_os = "linux")))]
use std::io;
use std::net::{IpAddr, SocketAddr};
use std::pin::Pin;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

#[cfg(not(any(target_os = "macos", target_os = "linux")))]
use boring2::ssl::{CertificateCompressionAlgorithm, CertificateCompressor};
use boring2::ssl::{SslConnector, SslConnectorBuilder, SslMethod, SslVerifyMode, SslVersion};
use boring2::x509::store::X509StoreBuilder;
use boring2::x509::X509;
use bytes::Bytes;
use http_body_util::combinators::BoxBody;
use http_body_util::BodyExt;
use hyper::body::Incoming;
use hyper::client::conn::{http1, http2};
use hyper::{Request, Response};
use hyper_util::rt::{TokioExecutor, TokioIo, TokioTimer};
use parking_lot::Mutex as SyncMutex;
use tokio::sync::Mutex as AsyncMutex;
use tokio::time::timeout;
use tokio_boring2::SslStream;

use crate::doh::DohResolver;
use crate::net_bind;

pub type BoxReqBody = BoxBody<Bytes, hyper::Error>;
pub type BoxRespBody = BoxBody<Bytes, hyper::Error>;

pub const H2_POOL_PROBE_TIMEOUT: Duration = Duration::from_secs(3);
pub const UPSTREAM_TCP_CONNECT_TIMEOUT: Duration = Duration::from_millis(3500);
pub const UPSTREAM_TLS_HANDSHAKE_TIMEOUT: Duration = Duration::from_millis(4000);
pub const HEDGED_TLS_BACKUP_DELAY: Duration = Duration::from_millis(1500);
pub const UPSTREAM_GET_HEADER_TIMEOUT: Duration = Duration::from_secs(8);

/// Validates that `bytes` is a syntactically well-formed RFC 9849 `ECHConfigList` (`0xfe0d`),
/// rejecting empty slices and BoringSSL's 5-byte `[0xfe, 0x0d, 0xff, 0xff, 0xff]` syntax-error placeholder.
pub fn is_valid_ech_config_list(bytes: &[u8]) -> bool {
    if bytes.len() < 6 {
        return false;
    }
    let list_len = usize::from(u16::from_be_bytes([bytes[0], bytes[1]]));
    if list_len == 0 || list_len + 2 != bytes.len() {
        return false;
    }
    let first_ver = u16::from_be_bytes([bytes[2], bytes[3]]);
    let first_len = usize::from(u16::from_be_bytes([bytes[4], bytes[5]]));
    first_ver == 0xfe0d && first_len > 0 && 4 + first_len <= list_len
}

fn is_ech_rejected_error(err: &boring2::ssl::Error) -> bool {
    err.ssl_error().is_some_and(|stack| {
        stack
            .errors()
            .iter()
            .any(|e| e.reason() == Some("ECH_REJECTED"))
    })
}

fn empty_req_body() -> BoxReqBody {
    http_body_util::Empty::<Bytes>::new()
        .map_err(|never| match never {})
        .boxed()
}

/// Computes response-header wait timeout for upstream ECH HTTP/1.1 & HTTP/2 requests.
/// Multipart `/uploads` streams full file bytes and waits for Discourse synchronous S3 processing before
/// response headers arrive, so it MUST NOT be capped at 8s; GET/HEAD/OPTIONS are converged to 8s
/// so degraded upstream streams fail faster than the WebView JS bridge timeout (10s).
pub fn upstream_send_timeout_for_path(method: &http::Method, path: &str) -> Duration {
    let lower_path = path.to_ascii_lowercase();
    if lower_path.contains("/uploads") {
        Duration::from_secs(95)
    } else if matches!(*method, http::Method::GET | http::Method::HEAD | http::Method::OPTIONS) {
        UPSTREAM_GET_HEADER_TIMEOUT
    } else {
        Duration::from_secs(25)
    }
}

static ECH_HANDSHAKE_COUNT: AtomicU64 = AtomicU64::new(0);
static LAST_ECH_ACCEPTED: AtomicBool = AtomicBool::new(false);

pub fn ech_handshake_count() -> u64 {
    ECH_HANDSHAKE_COUNT.load(Ordering::Relaxed)
}

pub fn last_ech_accepted() -> bool {
    LAST_ECH_ACCEPTED.load(Ordering::Relaxed)
}

#[cfg(not(any(target_os = "macos", target_os = "linux")))]
struct BrotliCertDecompressor;

#[cfg(not(any(target_os = "macos", target_os = "linux")))]
impl CertificateCompressor for BrotliCertDecompressor {
    const ALGORITHM: CertificateCompressionAlgorithm = CertificateCompressionAlgorithm::BROTLI;
    const CAN_COMPRESS: bool = false;
    const CAN_DECOMPRESS: bool = true;

    fn compress<W>(&self, _input: &[u8], _output: &mut W) -> io::Result<()>
    where
        W: io::Write,
    {
        Err(io::Error::new(
            io::ErrorKind::Unsupported,
            "client does not compress server certs",
        ))
    }

    fn decompress<W>(&self, input: &[u8], output: &mut W) -> io::Result<()>
    where
        W: io::Write,
    {
        brotli_decompressor::BrotliDecompress(&mut io::Cursor::new(input), output)
    }
}

/// Optional deterministic target override used in offline unit/integration tests.
#[derive(Clone)]
pub struct TestTargetOverride {
    pub addr: SocketAddr,
    pub ech_config_list: Vec<u8>,
    pub ca_cert: X509,
}

#[derive(Clone, Hash, PartialEq, Eq)]
struct PoolKey {
    host: String,
    network_gen: u64,
    system_bridge: bool,
    http_proxy: Option<SocketAddr>,
}

type H2Sender = http2::SendRequest<BoxReqBody>;

pub struct UpstreamPool {
    connector: Arc<SslConnector>,
    doh: Arc<DohResolver>,
    h2_pool: SyncMutex<HashMap<PoolKey, H2Sender>>,
    connect_locks: SyncMutex<HashMap<String, Arc<AsyncMutex<()>>>>,
    test_override: Option<TestTargetOverride>,
    #[cfg(test)]
    test_ip_port_map: Option<HashMap<std::net::Ipv4Addr, SocketAddr>>,
    #[cfg(test)]
    test_tls_timeout: Option<Duration>,
}

impl UpstreamPool {
    pub fn new(doh: Arc<DohResolver>) -> Result<Arc<Self>, String> {
        let builder = build_chromium_connector_builder(None)?;
        Ok(Arc::new(Self {
            connector: Arc::new(builder.build()),
            doh,
            h2_pool: SyncMutex::new(HashMap::new()),
            connect_locks: SyncMutex::new(HashMap::new()),
            test_override: None,
            #[cfg(test)]
            test_ip_port_map: None,
            #[cfg(test)]
            test_tls_timeout: None,
        }))
    }

    pub fn new_with_test_override(
        doh: Arc<DohResolver>,
        override_cfg: TestTargetOverride,
    ) -> Result<Arc<Self>, String> {
        let builder = build_chromium_connector_builder(Some(&override_cfg.ca_cert))?;
        Ok(Arc::new(Self {
            connector: Arc::new(builder.build()),
            doh,
            h2_pool: SyncMutex::new(HashMap::new()),
            connect_locks: SyncMutex::new(HashMap::new()),
            test_override: Some(override_cfg),
            #[cfg(test)]
            test_ip_port_map: None,
            #[cfg(test)]
            test_tls_timeout: None,
        }))
    }

    #[cfg(test)]
    pub fn new_for_doh_rotation_test(
        doh: Arc<DohResolver>,
        ca_cert: &X509,
        ip_port_map: HashMap<std::net::Ipv4Addr, SocketAddr>,
        tls_timeout: Duration,
    ) -> Result<Arc<Self>, String> {
        let builder = build_chromium_connector_builder(Some(ca_cert))?;
        Ok(Arc::new(Self {
            connector: Arc::new(builder.build()),
            doh,
            h2_pool: SyncMutex::new(HashMap::new()),
            connect_locks: SyncMutex::new(HashMap::new()),
            test_override: None,
            test_ip_port_map: Some(ip_port_map),
            test_tls_timeout: Some(tls_timeout),
        }))
    }

    fn host_connect_lock(&self, host: &str) -> Arc<AsyncMutex<()>> {
        let key = crate::ca::normalize_host(host);
        let mut guard = self.connect_locks.lock();
        if guard.len() > 64 {
            guard.retain(|_, lock| Arc::strong_count(lock) > 1);
        }
        Arc::clone(
            guard
                .entry(key)
                .or_insert_with(|| Arc::new(AsyncMutex::new(()))),
        )
    }

    async fn rotate_sticky_on_failure(&self) {
        if self.test_override.is_none() {
            let _ = self.doh.rotate_current_sticky_ip().await;
        }
    }

    /// Sends an HTTP request to `host` over BoringSSL TLS 1.3 + ECH + physical socket,
    /// reusing pooled HTTP/2 sessions per `(host, network_gen)` or falling back to HTTP/1.1.
    pub async fn send_request(
        &self,
        host: &str,
        req: Request<Incoming>,
    ) -> Result<Response<BoxRespBody>, String> {
        let is_bodyless = matches!(
            *req.method(),
            http::Method::GET | http::Method::HEAD | http::Method::OPTIONS
        );

        if is_bodyless {
            let (parts, _incoming) = req.into_parts();
            if let Some(mut sender) = self.take_ready_h2_sender(host) {
                if !sender.is_closed() {
                    let mut pooled_req = Request::from_parts(parts.clone(), empty_req_body());
                    normalize_for_h2(host, &mut pooled_req);
                    let probe_res = timeout(H2_POOL_PROBE_TIMEOUT, async {
                        sender.ready().await?;
                        sender.send_request(pooled_req).await
                    })
                    .await;
                    match probe_res {
                        Ok(Ok(resp)) if !is_edge_restricted_403(&resp) => {
                            return Ok(resp.map(|b| b.boxed()));
                        }
                        Ok(Ok(_)) => {
                            self.evict_h2_sender(host);
                            self.rotate_sticky_on_failure().await;
                        }
                        Ok(Err(_)) => {
                            self.evict_h2_sender(host);
                        }
                        Err(_) => {
                            self.evict_h2_sender(host);
                            self.rotate_sticky_on_failure().await;
                        }
                    }
                } else {
                    self.evict_h2_sender(host);
                }
            }
            let first = self
                .connect_and_send(host, Request::from_parts(parts.clone(), empty_req_body()))
                .await?;
            if is_edge_restricted_403(&first) {
                self.evict_h2_sender(host);
                self.rotate_sticky_on_failure().await;
                return self
                    .connect_and_send(host, Request::from_parts(parts, empty_req_body()))
                    .await;
            }
            return Ok(first);
        }

        let mut boxed_req = req.map(|b| b.boxed());
        let send_timeout = upstream_send_timeout_for_path(boxed_req.method(), boxed_req.uri().path());
        if let Some(mut sender) = self.take_ready_h2_sender(host) {
            if !sender.is_closed() {
                match timeout(H2_POOL_PROBE_TIMEOUT, sender.ready()).await {
                    Ok(Ok(())) => {
                        normalize_for_h2(host, &mut boxed_req);
                        return match timeout(send_timeout, sender.send_request(boxed_req)).await {
                            Ok(Ok(resp)) => Ok(resp.map(|b| b.boxed())),
                            Ok(Err(e)) => {
                                self.evict_h2_sender(host);
                                Err(format!("pooled h2 send failed: {e}"))
                            }
                            Err(_) => {
                                self.evict_h2_sender(host);
                                self.rotate_sticky_on_failure().await;
                                Err("pooled h2 send timeout".to_string())
                            }
                        };
                    }
                    Ok(Err(_)) => {
                        self.evict_h2_sender(host);
                    }
                    Err(_) => {
                        self.evict_h2_sender(host);
                        self.rotate_sticky_on_failure().await;
                    }
                }
            } else {
                self.evict_h2_sender(host);
            }
        }

        self.connect_and_send(host, boxed_req).await
    }

    /// Sends an HTTP request to `host` over BoringSSL TLS 1.3 + HTTP/2 using the host's normal
    /// OS/TUN routing table (including FakeIP) or explicit upstream `http_proxy`, without DoH ECH override.
    pub async fn send_request_via_system(
        &self,
        host: &str,
        req: Request<Incoming>,
        http_proxy: Option<SocketAddr>,
        listen_port: u16,
    ) -> Result<Response<BoxRespBody>, String> {
        let is_bodyless = matches!(
            *req.method(),
            http::Method::GET | http::Method::HEAD | http::Method::OPTIONS
        );

        if is_bodyless {
            let (parts, _incoming) = req.into_parts();
            if let Some(mut sender) = self.take_ready_h2_sender_mode(host, true, http_proxy) {
                if !sender.is_closed() {
                    let mut pooled_req = Request::from_parts(parts.clone(), empty_req_body());
                    normalize_for_h2(host, &mut pooled_req);
                    let probe_res = timeout(H2_POOL_PROBE_TIMEOUT, async {
                        sender.ready().await?;
                        sender.send_request(pooled_req).await
                    })
                    .await;
                    match probe_res {
                        Ok(Ok(resp)) => return Ok(resp.map(|b| b.boxed())),
                        _ => self.evict_h2_sender_mode(host, true, http_proxy),
                    }
                } else {
                    self.evict_h2_sender_mode(host, true, http_proxy);
                }
            }
            return self
                .connect_and_send_system(
                    host,
                    Request::from_parts(parts, empty_req_body()),
                    http_proxy,
                    listen_port,
                )
                .await;
        }

        let mut boxed_req = req.map(|b| b.boxed());
        let send_timeout =
            upstream_send_timeout_for_path(boxed_req.method(), boxed_req.uri().path());
        if let Some(mut sender) = self.take_ready_h2_sender_mode(host, true, http_proxy) {
            if !sender.is_closed() {
                match timeout(H2_POOL_PROBE_TIMEOUT, sender.ready()).await {
                    Ok(Ok(())) => {
                        normalize_for_h2(host, &mut boxed_req);
                        return match timeout(send_timeout, sender.send_request(boxed_req)).await {
                            Ok(Ok(resp)) => Ok(resp.map(|b| b.boxed())),
                            Ok(Err(e)) => {
                                self.evict_h2_sender_mode(host, true, http_proxy);
                                Err(format!("pooled system h2 send failed: {e}"))
                            }
                            Err(_) => {
                                self.evict_h2_sender_mode(host, true, http_proxy);
                                Err("pooled system h2 send timeout".to_string())
                            }
                        };
                    }
                    _ => self.evict_h2_sender_mode(host, true, http_proxy),
                }
            } else {
                self.evict_h2_sender_mode(host, true, http_proxy);
            }
        }

        self.connect_and_send_system(host, boxed_req, http_proxy, listen_port)
            .await
    }

    fn take_ready_h2_sender(&self, host: &str) -> Option<H2Sender> {
        self.take_ready_h2_sender_mode(host, false, None)
    }

    fn take_ready_h2_sender_mode(
        &self,
        host: &str,
        system_bridge: bool,
        http_proxy: Option<SocketAddr>,
    ) -> Option<H2Sender> {
        let key = PoolKey {
            host: crate::ca::normalize_host(host),
            network_gen: net_bind::network_gen(),
            system_bridge,
            http_proxy,
        };
        let mut guard = self.h2_pool.lock();
        if let Some(sender) = guard.get(&key) {
            if !sender.is_closed() {
                return Some(sender.clone());
            }
            guard.remove(&key);
        }
        None
    }

    fn store_h2_sender(&self, host: &str, net_gen: u64, sender: H2Sender) {
        self.store_h2_sender_mode(host, net_gen, false, None, sender);
    }

    fn store_h2_sender_mode(
        &self,
        host: &str,
        net_gen: u64,
        system_bridge: bool,
        http_proxy: Option<SocketAddr>,
        sender: H2Sender,
    ) {
        let key = PoolKey {
            host: crate::ca::normalize_host(host),
            network_gen: net_gen,
            system_bridge,
            http_proxy,
        };
        self.h2_pool.lock().insert(key, sender);
    }

    fn evict_h2_sender(&self, host: &str) {
        self.evict_h2_sender_mode(host, false, None);
    }

    fn evict_h2_sender_mode(
        &self,
        host: &str,
        system_bridge: bool,
        http_proxy: Option<SocketAddr>,
    ) {
        let clean = crate::ca::normalize_host(host);
        self.h2_pool.lock().retain(|k, _| {
            !(k.host == clean && k.system_bridge == system_bridge && k.http_proxy == http_proxy)
        });
    }

    pub fn clear_h2_pool(&self) {
        self.h2_pool.lock().clear();
    }

    async fn connect_and_send(
        &self,
        host: &str,
        mut req: Request<BoxReqBody>,
    ) -> Result<Response<BoxRespBody>, String> {
        let send_timeout = upstream_send_timeout_for_path(req.method(), req.uri().path());
        let host_lock = self.host_connect_lock(host);
        let conn = {
            let _guard = host_lock.lock().await;
            if let Some(mut sender) = self.take_ready_h2_sender(host) {
                if !sender.is_closed() {
                    match timeout(H2_POOL_PROBE_TIMEOUT, sender.ready()).await {
                        Ok(Ok(())) => {
                            normalize_for_h2(host, &mut req);
                            return match timeout(send_timeout, sender.send_request(req)).await {
                                Ok(Ok(resp)) => Ok(resp.map(|b| b.boxed())),
                                Ok(Err(e)) => {
                                    self.evict_h2_sender(host);
                                    Err(format!("h2 coalesced send error: {e}"))
                                }
                                Err(_) => {
                                    self.evict_h2_sender(host);
                                    self.rotate_sticky_on_failure().await;
                                    Err("h2 coalesced send timeout".to_string())
                                }
                            };
                        }
                        Ok(Err(_)) => {
                            self.evict_h2_sender(host);
                        }
                        Err(_) => {
                            self.evict_h2_sender(host);
                            self.rotate_sticky_on_failure().await;
                        }
                    }
                } else {
                    self.evict_h2_sender(host);
                }
            }
            self.establish_tls_ech(host).await?
        };

        let net_gen = conn.network_gen;
        let alpn = conn
            .tls
            .ssl()
            .selected_alpn_protocol()
            .unwrap_or(b"http/1.1")
            .to_vec();

        if alpn.as_slice() == b"h2" {
            let io = TokioIo::new(conn.tls);
            let mut h2_builder = http2::Builder::new(TokioExecutor::new());
            #[cfg(any(target_os = "macos", target_os = "linux"))]
            {
                h2_builder
                    .timer(TokioTimer::new())
                    .max_frame_size(None)
                    .initial_stream_window_size(2_097_152)
                    .initial_connection_window_size(10_551_296)
                    .max_concurrent_streams(100)
                    .keep_alive_interval(Duration::from_secs(25))
                    .keep_alive_timeout(Duration::from_secs(10))
                    .keep_alive_while_idle(true);
            }
            #[cfg(not(any(target_os = "macos", target_os = "linux")))]
            {
                h2_builder
                    .timer(TokioTimer::new())
                    .max_frame_size(None)
                    .initial_stream_window_size(6_291_456)
                    .initial_connection_window_size(15_728_640)
                    .header_table_size(65_536)
                    .max_header_list_size(262_144)
                    .keep_alive_interval(Duration::from_secs(25))
                    .keep_alive_timeout(Duration::from_secs(10))
                    .keep_alive_while_idle(true);
            }

            let (mut sender, driver) = h2_builder
                .handshake::<_, BoxReqBody>(io)
                .await
                .map_err(|e| format!("h2 handshake: {e}"))?;

            let clean_host = crate::ca::normalize_host(host);
            tokio::spawn(async move {
                let _ = driver.await;
            });

            sender
                .ready()
                .await
                .map_err(|e| format!("h2 sender ready: {e}"))?;
            self.store_h2_sender(&clean_host, net_gen, sender.clone());

            normalize_for_h2(host, &mut req);
            match timeout(send_timeout, sender.send_request(req)).await {
                Ok(Ok(resp)) => Ok(resp.map(|b| b.boxed())),
                Ok(Err(e)) => {
                    self.evict_h2_sender(&clean_host);
                    Err(format!("h2 send: {e}"))
                }
                Err(_) => {
                    self.evict_h2_sender(&clean_host);
                    self.rotate_sticky_on_failure().await;
                    Err("h2 send timeout".to_string())
                }
            }
        } else {
            let io = TokioIo::new(conn.tls);
            let (mut sender, driver) = http1::Builder::new()
                .handshake::<_, BoxReqBody>(io)
                .await
                .map_err(|e| format!("http1 handshake: {e}"))?;
            tokio::spawn(async move {
                let _ = driver.await;
            });
            sender
                .ready()
                .await
                .map_err(|e| format!("http1 ready: {e}"))?;
            normalize_for_h1(host, &mut req);
            match timeout(send_timeout, sender.send_request(req)).await {
                Ok(Ok(resp)) => Ok(resp.map(|b| b.boxed())),
                Ok(Err(e)) => Err(format!("http1 send: {e}")),
                Err(_) => {
                    self.rotate_sticky_on_failure().await;
                    Err("http1 send timeout".to_string())
                }
            }
        }
    }

    async fn connect_and_send_system(
        &self,
        host: &str,
        mut req: Request<BoxReqBody>,
        http_proxy: Option<SocketAddr>,
        listen_port: u16,
    ) -> Result<Response<BoxRespBody>, String> {
        let send_timeout = upstream_send_timeout_for_path(req.method(), req.uri().path());
        let host_lock = self.host_connect_lock(host);
        let conn = {
            let _guard = host_lock.lock().await;
            if let Some(mut sender) = self.take_ready_h2_sender_mode(host, true, http_proxy) {
                if !sender.is_closed() {
                    match timeout(H2_POOL_PROBE_TIMEOUT, sender.ready()).await {
                        Ok(Ok(())) => {
                            normalize_for_h2(host, &mut req);
                            return match timeout(send_timeout, sender.send_request(req)).await {
                                Ok(Ok(resp)) => Ok(resp.map(|b| b.boxed())),
                                Ok(Err(e)) => {
                                    self.evict_h2_sender_mode(host, true, http_proxy);
                                    Err(format!("system h2 coalesced send error: {e}"))
                                }
                                Err(_) => {
                                    self.evict_h2_sender_mode(host, true, http_proxy);
                                    Err("system h2 coalesced send timeout".to_string())
                                }
                            };
                        }
                        _ => self.evict_h2_sender_mode(host, true, http_proxy),
                    }
                } else {
                    self.evict_h2_sender_mode(host, true, http_proxy);
                }
            }
            self.establish_tls_system(host, http_proxy, listen_port)
                .await?
        };

        let net_gen = conn.network_gen;
        let alpn = conn
            .tls
            .ssl()
            .selected_alpn_protocol()
            .unwrap_or(b"http/1.1")
            .to_vec();

        if alpn.as_slice() == b"h2" {
            let io = TokioIo::new(conn.tls);
            let mut h2_builder = http2::Builder::new(TokioExecutor::new());
            #[cfg(any(target_os = "macos", target_os = "linux"))]
            {
                h2_builder
                    .timer(TokioTimer::new())
                    .max_frame_size(None)
                    .initial_stream_window_size(2_097_152)
                    .initial_connection_window_size(10_551_296)
                    .max_concurrent_streams(100)
                    .keep_alive_interval(Duration::from_secs(25))
                    .keep_alive_timeout(Duration::from_secs(10))
                    .keep_alive_while_idle(true);
            }
            #[cfg(not(any(target_os = "macos", target_os = "linux")))]
            {
                h2_builder
                    .timer(TokioTimer::new())
                    .max_frame_size(None)
                    .initial_stream_window_size(6_291_456)
                    .initial_connection_window_size(15_728_640)
                    .header_table_size(65_536)
                    .max_header_list_size(262_144)
                    .keep_alive_interval(Duration::from_secs(25))
                    .keep_alive_timeout(Duration::from_secs(10))
                    .keep_alive_while_idle(true);
            }

            let (mut sender, driver) = h2_builder
                .handshake::<_, BoxReqBody>(io)
                .await
                .map_err(|e| format!("system h2 handshake: {e}"))?;

            let clean_host = crate::ca::normalize_host(host);
            tokio::spawn(async move {
                let _ = driver.await;
            });

            sender
                .ready()
                .await
                .map_err(|e| format!("system h2 sender ready: {e}"))?;
            self.store_h2_sender_mode(&clean_host, net_gen, true, http_proxy, sender.clone());

            normalize_for_h2(host, &mut req);
            match timeout(send_timeout, sender.send_request(req)).await {
                Ok(Ok(resp)) => Ok(resp.map(|b| b.boxed())),
                Ok(Err(e)) => {
                    self.evict_h2_sender_mode(&clean_host, true, http_proxy);
                    Err(format!("system h2 send: {e}"))
                }
                Err(_) => {
                    self.evict_h2_sender_mode(&clean_host, true, http_proxy);
                    Err("system h2 send timeout".to_string())
                }
            }
        } else {
            let io = TokioIo::new(conn.tls);
            let (mut sender, driver) = http1::Builder::new()
                .handshake::<_, BoxReqBody>(io)
                .await
                .map_err(|e| format!("system http1 handshake: {e}"))?;
            tokio::spawn(async move {
                let _ = driver.await;
            });
            sender
                .ready()
                .await
                .map_err(|e| format!("system http1 ready: {e}"))?;
            normalize_for_h1(host, &mut req);
            match timeout(send_timeout, sender.send_request(req)).await {
                Ok(Ok(resp)) => Ok(resp.map(|b| b.boxed())),
                Ok(Err(e)) => Err(format!("system http1 send: {e}")),
                Err(_) => Err("system http1 send timeout".to_string()),
            }
        }
    }

    async fn establish_tls_system(
        &self,
        host: &str,
        http_proxy: Option<SocketAddr>,
        listen_port: u16,
    ) -> Result<EstablishedTls, String> {
        let net_gen = net_bind::network_gen();
        let sni_host = strip_default_https_port(host);
        let tcp = crate::proxy::connect_passthrough_target(sni_host, 443, http_proxy, listen_port)
            .await?;

        let mut cfg = self
            .connector
            .configure()
            .map_err(|e| format!("system bridge connector configure: {e}"))?;
        #[cfg(not(any(target_os = "macos", target_os = "linux")))]
        {
            let _ = cfg.add_application_settings(b"h2");
            cfg.set_alps_use_new_codepoint(true);
        }
        cfg.set_enable_ech_grease(true);

        let ssl = cfg
            .into_ssl(sni_host)
            .map_err(|e| format!("system bridge into_ssl({sni_host}): {e}"))?;
        let mut tls =
            SslStream::new(ssl, tcp).map_err(|e| format!("system bridge ssl stream: {e}"))?;

        let tls_handshake_timeout = {
            #[cfg(test)]
            {
                self.test_tls_timeout.unwrap_or(UPSTREAM_TLS_HANDSHAKE_TIMEOUT)
            }
            #[cfg(not(test))]
            {
                UPSTREAM_TLS_HANDSHAKE_TIMEOUT
            }
        };

        timeout(tls_handshake_timeout, Pin::new(&mut tls).connect())
            .await
            .map_err(|_| format!("system bridge tls handshake timeout for {sni_host}"))?
            .map_err(|e| format!("system bridge tls connect ({sni_host}): {e}"))?;

        Ok(EstablishedTls {
            tls,
            network_gen: net_gen,
        })
    }

    fn map_dial_addr(&self, addr: SocketAddr) -> SocketAddr {
        #[cfg(test)]
        {
            if let (Some(ref map), IpAddr::V4(v4)) = (&self.test_ip_port_map, addr.ip()) {
                return map.get(&v4).copied().unwrap_or(addr);
            }
        }
        addr
    }

    async fn connect_single_tls_ech(
        &self,
        host: &str,
        dial_addr: SocketAddr,
        ech_list: &[u8],
        tls_handshake_timeout: Duration,
    ) -> Result<EstablishedTls, SingleHandshakeError> {
        let net_gen = net_bind::network_gen();
        let (tcp, _bound) = net_bind::connect_tcp(dial_addr, UPSTREAM_TCP_CONNECT_TIMEOUT)
            .await
            .map_err(|e| SingleHandshakeError::Tcp(e.to_string()))?;

        let mut cfg = self
            .connector
            .configure()
            .map_err(|e| SingleHandshakeError::Tls(format!("connector configure: {e}")))?;
        #[cfg(not(any(target_os = "macos", target_os = "linux")))]
        {
            let _ = cfg.add_application_settings(b"h2");
            cfg.set_alps_use_new_codepoint(true);
        }
        if !ech_list.is_empty() {
            cfg.set_ech_config_list(ech_list)
                .map_err(|e| SingleHandshakeError::Tls(format!("set_ech_config_list: {e}")))?;
        } else {
            cfg.set_enable_ech_grease(true);
        }

        let ssl = cfg
            .into_ssl(host)
            .map_err(|e| SingleHandshakeError::Tls(format!("into_ssl({host}): {e}")))?;
        let mut tls = SslStream::new(ssl, tcp)
            .map_err(|e| SingleHandshakeError::Tls(format!("ssl stream: {e}")))?;

        match timeout(tls_handshake_timeout, Pin::new(&mut tls).connect()).await {
            Ok(Ok(())) => {
                let accepted = tls.ssl().ech_accepted();
                LAST_ECH_ACCEPTED.store(accepted, Ordering::Relaxed);
                if accepted {
                    ECH_HANDSHAKE_COUNT.fetch_add(1, Ordering::Relaxed);
                }
                Ok(EstablishedTls {
                    tls,
                    network_gen: net_gen,
                })
            }
            Ok(Err(err)) => {
                // CRITICAL: BoringSSL asserts / returns a 5-byte syntax-error placeholder if
                // `SSL_get0_ech_retry_configs` is called for anything other than `SSL_R_ECH_REJECTED`.
                if is_ech_rejected_error(&err) && tls.ssl().get_ech_name_override().is_some() {
                    if let Some(retry) = tls.ssl().get_ech_retry_configs() {
                        if is_valid_ech_config_list(retry) {
                            return Err(SingleHandshakeError::EchRejected {
                                retry_configs: retry.to_vec(),
                            });
                        }
                    }
                }
                Err(SingleHandshakeError::Tls(err.to_string()))
            }
            Err(_) => Err(SingleHandshakeError::TlsTimeout),
        }
    }

    async fn establish_tls_ech(
        &self,
        host: &str,
    ) -> Result<EstablishedTls, String> {
        let (mut os_fallback_addrs, mut target_addr, mut ech_list) =
            if let Some(ref ov) = self.test_override {
                (Vec::new(), ov.addr, ov.ech_config_list.clone())
            } else {
                match self.doh.get_ech_and_sticky_ip().await {
                    Ok((list, sticky)) => (
                        Vec::new(),
                        SocketAddr::new(IpAddr::V4(sticky), 443),
                        list,
                    ),
                    Err(doh_err) => {
                        if net_bind::bypass_vpn() {
                            return Err(format!(
                                "DoH unavailable under bypass_vpn for {host} (OS DNS fallback disabled): {doh_err}"
                            ));
                        }
                        // Fall back to standard OS DNS + TLS 1.3 only when bypass_vpn is off,
                        // filtering out Clash/Sing-box 198.18.0.0/15 FakeIPs.
                        let resolved: Vec<SocketAddr> = tokio::net::lookup_host((host, 443))
                            .await
                            .map_err(|e| format!("DoH unavailable and OS DNS failed for {host}: {e}"))?
                            .filter(|addr| match addr.ip() {
                                IpAddr::V4(v4) => {
                                    v4.is_loopback() || crate::doh::is_valid_public_ipv4(v4)
                                }
                                IpAddr::V6(v6) => !v6.is_unspecified(),
                            })
                            .collect();
                        let first = resolved
                            .first()
                            .copied()
                            .ok_or_else(|| format!("OS DNS yielded no clean public addresses for {host}"))?;
                        (resolved.into_iter().skip(1).collect(), first, Vec::new())
                    }
                }
            };

        let tls_handshake_timeout = {
            #[cfg(test)]
            {
                self.test_tls_timeout.unwrap_or(UPSTREAM_TLS_HANDSHAKE_TIMEOUT)
            }
            #[cfg(not(test))]
            {
                UPSTREAM_TLS_HANDSHAKE_TIMEOUT
            }
        };

        for attempt in 0..2 {
            let dial_addr = self.map_dial_addr(target_addr);
            let hedged_backup_ip = if attempt == 0
                && self.test_override.is_none()
                && tls_handshake_timeout >= Duration::from_millis(2000)
            {
                if let IpAddr::V4(primary_v4) = target_addr.ip() {
                    self.doh.peek_alternate_sticky_ip(primary_v4).await
                } else {
                    None
                }
            } else {
                None
            };

            let outcome = if let Some(backup_v4) = hedged_backup_ip {
                let backup_dial = self.map_dial_addr(SocketAddr::new(IpAddr::V4(backup_v4), 443));
                let ech_ref = ech_list.as_slice();
                let mut primary_fut = Box::pin(self.connect_single_tls_ech(
                    host,
                    dial_addr,
                    ech_ref,
                    tls_handshake_timeout,
                ));
                tokio::select! {
                    primary_res = &mut primary_fut => primary_res,
                    _ = tokio::time::sleep(HEDGED_TLS_BACKUP_DELAY) => {
                        // Primary stalled >= 1.5s: race ongoing primary against backup Anycast IP
                        let mut backup_fut = Box::pin(self.connect_single_tls_ech(
                            host,
                            backup_dial,
                            ech_ref,
                            tls_handshake_timeout,
                        ));
                        tokio::select! {
                            primary_res = &mut primary_fut => match primary_res {
                                Ok(conn) => Ok(conn),
                                Err(primary_err) => match backup_fut.await {
                                    Ok(conn) => {
                                        self.doh.promote_sticky_ip(backup_v4).await;
                                        Ok(conn)
                                    }
                                    Err(_) => Err(primary_err),
                                },
                            },
                            backup_res = &mut backup_fut => match backup_res {
                                Ok(conn) => {
                                    self.doh.promote_sticky_ip(backup_v4).await;
                                    Ok(conn)
                                }
                                Err(_) => primary_fut.await,
                            },
                        }
                    }
                }
            } else {
                self.connect_single_tls_ech(host, dial_addr, &ech_list, tls_handshake_timeout)
                    .await
            };

            match outcome {
                Ok(conn) => return Ok(conn),
                Err(SingleHandshakeError::EchRejected { retry_configs }) if attempt == 0 => {
                    if self.test_override.is_none() {
                        self.doh
                            .update_ech_retry_configs(retry_configs.clone())
                            .await;
                    }
                    ech_list = retry_configs;
                    continue;
                }
                Err(err) => {
                    if attempt == 0 && self.test_override.is_none() {
                        if !os_fallback_addrs.is_empty() {
                            target_addr = os_fallback_addrs.remove(0);
                            continue;
                        }
                        if let IpAddr::V4(old_v4) = target_addr.ip() {
                            if let Some(next_ip) = self.doh.rotate_sticky_ip(old_v4).await {
                                target_addr = SocketAddr::new(IpAddr::V4(next_ip), 443);
                                continue;
                            }
                        }
                    }
                    return Err(match err {
                        SingleHandshakeError::Tcp(e) => {
                            format!("upstream tcp connect {target_addr}: {e}")
                        }
                        SingleHandshakeError::EchRejected { .. } => {
                            format!("upstream boringssl ECH rejected for {host}")
                        }
                        SingleHandshakeError::Tls(e) => {
                            format!("upstream boringssl connect {host}: {e}")
                        }
                        SingleHandshakeError::TlsTimeout => {
                            format!("upstream tls handshake timeout for {host}")
                        }
                    });
                }
            }
        }

        Err(format!("upstream ECH connect exhausted retries for {host}"))
    }
}

enum SingleHandshakeError {
    Tcp(String),
    EchRejected { retry_configs: Vec<u8> },
    Tls(String),
    TlsTimeout,
}

struct EstablishedTls {
    tls: SslStream<tokio::net::TcpStream>,
    network_gen: u64,
}

fn build_chromium_connector_builder(
    extra_ca: Option<&X509>,
) -> Result<SslConnectorBuilder, String> {
    let mut builder = SslConnector::builder(SslMethod::tls_client())
        .map_err(|e| format!("ssl connector builder: {e}"))?;
    builder.set_verify(SslVerifyMode::PEER);

    let mut store = X509StoreBuilder::new().map_err(|e| format!("x509 store: {e}"))?;
    if let Some(ca) = extra_ca {
        let _ = store.add_cert(ca.clone());
    } else {
        for der in webpki_root_certs::TLS_SERVER_ROOT_CERTS {
            if let Ok(cert) = X509::from_der(der) {
                let _ = store.add_cert(cert);
            }
        }
    }
    builder
        .set_verify_cert_store(store.build())
        .map_err(|e| format!("set_verify_cert_store: {e}"))?;

    builder.set_grease_enabled(true);
    builder.enable_ocsp_stapling();
    builder.enable_signed_cert_timestamps();
    let _ = builder.set_min_proto_version(Some(SslVersion::TLS1_2));
    let _ = builder.set_max_proto_version(Some(SslVersion::TLS1_3));

    #[cfg(any(target_os = "macos", target_os = "linux"))]
    {
        // WebKit (macOS WKWebView / Linux WebKit2GTK) TLS profile: fixed extension order (no permutation), P-521 included, 17+ Safari/WebKit ciphers.
        builder.set_permute_extensions(false);
        if builder
            .set_curves_list("X25519MLKEM768:X25519:P-256:P-384:P-521")
            .is_err()
        {
            let _ = builder.set_curves_list("X25519:P-256:P-384:P-521");
        }
        let _ = builder.set_cipher_list(
            "TLS_AES_128_GCM_SHA256:TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256:\
             ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-ECDSA-CHACHA20-POLY1305:\
             ECDHE-RSA-AES256-GCM-SHA384:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-RSA-CHACHA20-POLY1305:\
             ECDHE-ECDSA-AES256-SHA:ECDHE-ECDSA-AES128-SHA:ECDHE-RSA-AES256-SHA:ECDHE-RSA-AES128-SHA:\
             AES256-GCM-SHA384:AES128-GCM-SHA256:AES256-SHA:AES128-SHA",
        );
        let _ = builder.set_sigalgs_list(
            "ecdsa_secp256r1_sha256:rsa_pss_rsae_sha256:rsa_pkcs1_sha256:\
             ecdsa_secp384r1_sha384:ecdsa_secp521r1_sha512:\
             rsa_pss_rsae_sha384:rsa_pss_rsae_sha512:rsa_pkcs1_sha384:rsa_pkcs1_sha512",
        );
    }

    #[cfg(not(any(target_os = "macos", target_os = "linux")))]
    {
        // Chromium (Android System WebView / Windows WebView2) TLS profile:
        // extension permutation + PQ X25519MLKEM768 + full 15-cipher suite (`t13d1516h2`).
        builder.set_permute_extensions(true);
        if builder
            .set_curves_list("X25519MLKEM768:X25519:P-256:P-384")
            .is_err()
        {
            let _ = builder.set_curves_list("X25519:P-256:P-384");
        }
        let _ = builder.set_cipher_list(
            "TLS_AES_128_GCM_SHA256:TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256:\
             ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:\
             ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:\
             ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305:\
             ECDHE-RSA-AES128-SHA:ECDHE-RSA-AES256-SHA:\
             AES128-GCM-SHA256:AES256-GCM-SHA384:AES128-SHA:AES256-SHA",
        );
        let _ = builder.set_sigalgs_list(
            "ecdsa_secp256r1_sha256:rsa_pss_rsae_sha256:rsa_pkcs1_sha256:\
             ecdsa_secp384r1_sha384:rsa_pss_rsae_sha384:rsa_pkcs1_sha384:\
             rsa_pss_rsae_sha512:rsa_pkcs1_sha512",
        );
        let _ = builder.add_certificate_compression_algorithm(BrotliCertDecompressor);
    }

    let _ = builder.set_alpn_protos(b"\x02h2\x08http/1.1");

    Ok(builder)
}

fn is_edge_restricted_403<B>(resp: &Response<B>) -> bool {
    if resp.status() != http::StatusCode::FORBIDDEN {
        return false;
    }
    resp.headers()
        .get(http::header::CONTENT_LENGTH)
        .and_then(|v| v.to_str().ok())
        .and_then(|s| s.trim().parse::<usize>().ok())
        .map(|len| (1..=64).contains(&len))
        .unwrap_or(false)
}

fn strip_default_https_port(host: &str) -> &str {
    host.strip_suffix(":443").unwrap_or(host)
}

fn strip_hop_by_hop_headers(headers: &mut http::HeaderMap, preserve_te_trailers: bool) {
    headers.remove("proxy-connection");
    headers.remove("proxy-authenticate");
    headers.remove("proxy-authorization");
    headers.remove(http::header::CONNECTION);
    headers.remove("keep-alive");
    headers.remove(http::header::UPGRADE);
    headers.remove(http::header::TRANSFER_ENCODING);
    if preserve_te_trailers {
        let keep_trailers = headers
            .get(http::header::TE)
            .and_then(|v| v.to_str().ok())
            .map(|v| {
                v.split(',')
                    .any(|token| token.trim().eq_ignore_ascii_case("trailers"))
            })
            .unwrap_or(false);
        headers.remove(http::header::TE);
        if keep_trailers {
            headers.insert(
                http::header::TE,
                http::HeaderValue::from_static("trailers"),
            );
        }
    } else {
        headers.remove(http::header::TE);
    }
}

fn normalize_for_h2(host: &str, req: &mut Request<BoxReqBody>) {
    let pq = req
        .uri()
        .path_and_query()
        .map(|v| v.as_str().to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "/".to_string());
    let pq = if pq.starts_with('/') { pq } else { format!("/{pq}") };
    let authority = strip_default_https_port(host);
    let full = format!("https://{authority}{pq}");
    if let Ok(full_uri) = full.parse::<hyper::Uri>() {
        *req.uri_mut() = full_uri;
    }
    *req.version_mut() = http::Version::HTTP_2;
    let headers = req.headers_mut();
    strip_hop_by_hop_headers(headers, true);
    // HTTP/2 carries authority in :authority; keeping Host with differing or duplicate authority triggers PROTOCOL_ERROR.
    headers.remove(http::header::HOST);
}

fn normalize_for_h1(host: &str, req: &mut Request<BoxReqBody>) {
    let pq = req
        .uri()
        .path_and_query()
        .map(|v| v.as_str().to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "/".to_string());
    if let Ok(origin_uri) = pq.parse::<hyper::Uri>() {
        *req.uri_mut() = origin_uri;
    }
    *req.version_mut() = http::Version::HTTP_11;
    let headers = req.headers_mut();
    strip_hop_by_hop_headers(headers, false);
    if let Ok(host_val) = http::HeaderValue::from_str(strip_default_https_port(host)) {
        headers.insert(http::header::HOST, host_val);
    }
}
