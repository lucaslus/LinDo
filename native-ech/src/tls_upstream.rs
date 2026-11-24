use std::collections::HashMap;
use std::io;
use std::net::{IpAddr, SocketAddr};
use std::pin::Pin;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

use boring2::ssl::{
    CertificateCompressionAlgorithm, CertificateCompressor, SslConnector, SslConnectorBuilder,
    SslMethod, SslVerifyMode, SslVersion,
};
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

fn empty_req_body() -> BoxReqBody {
    http_body_util::Empty::<Bytes>::new()
        .map_err(|never| match never {})
        .boxed()
}

/// Computes response-header wait timeout for upstream ECH HTTP/1.1 & HTTP/2 requests.
/// Multipart `/uploads` streams full file bytes and waits for Discourse synchronous S3 processing before
/// response headers arrive, so it MUST NOT be capped at 15s (which previously aborted >15s uploads with 502).
pub fn upstream_send_timeout_for_path(method: &http::Method, path: &str) -> Duration {
    let lower_path = path.to_ascii_lowercase();
    if lower_path.contains("/uploads") {
        Duration::from_secs(95)
    } else if matches!(*method, http::Method::GET | http::Method::HEAD | http::Method::OPTIONS) {
        Duration::from_secs(15)
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

struct BrotliCertDecompressor;

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
}

type H2Sender = http2::SendRequest<BoxReqBody>;

pub struct UpstreamPool {
    connector: Arc<SslConnector>,
    doh: Arc<DohResolver>,
    h2_pool: SyncMutex<HashMap<PoolKey, H2Sender>>,
    connect_lock: AsyncMutex<()>,
    test_override: Option<TestTargetOverride>,
}

impl UpstreamPool {
    pub fn new(doh: Arc<DohResolver>) -> Result<Arc<Self>, String> {
        let builder = build_chromium_connector_builder(None)?;
        Ok(Arc::new(Self {
            connector: Arc::new(builder.build()),
            doh,
            h2_pool: SyncMutex::new(HashMap::new()),
            connect_lock: AsyncMutex::new(()),
            test_override: None,
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
            connect_lock: AsyncMutex::new(()),
            test_override: Some(override_cfg),
        }))
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
                if !sender.is_closed() && sender.ready().await.is_ok() {
                    let mut pooled_req = Request::from_parts(parts.clone(), empty_req_body());
                    normalize_for_h2(host, &mut pooled_req);
                    match timeout(Duration::from_secs(6), sender.send_request(pooled_req)).await {
                        Ok(Ok(resp)) if !is_edge_restricted_403(&resp) => {
                            return Ok(resp.map(|b| b.boxed()));
                        }
                        Ok(Ok(_)) => {
                            self.evict_h2_sender(host);
                            let _ = self.doh.rotate_current_sticky_ip().await;
                        }
                        Ok(Err(_)) | Err(_) => {
                            self.evict_h2_sender(host);
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
                let _ = self.doh.rotate_current_sticky_ip().await;
                return self
                    .connect_and_send(host, Request::from_parts(parts, empty_req_body()))
                    .await;
            }
            return Ok(first);
        }

        let mut boxed_req = req.map(|b| b.boxed());
        let send_timeout = upstream_send_timeout_for_path(boxed_req.method(), boxed_req.uri().path());
        if let Some(mut sender) = self.take_ready_h2_sender(host) {
            if !sender.is_closed() && sender.ready().await.is_ok() {
                normalize_for_h2(host, &mut boxed_req);
                return match timeout(send_timeout, sender.send_request(boxed_req)).await {
                    Ok(Ok(resp)) => Ok(resp.map(|b| b.boxed())),
                    Ok(Err(e)) => {
                        self.evict_h2_sender(host);
                        Err(format!("pooled h2 send failed: {e}"))
                    }
                    Err(_) => {
                        self.evict_h2_sender(host);
                        Err("pooled h2 send timeout".to_string())
                    }
                };
            } else {
                self.evict_h2_sender(host);
            }
        }

        self.connect_and_send(host, boxed_req).await
    }

    fn take_ready_h2_sender(&self, host: &str) -> Option<H2Sender> {
        let key = PoolKey {
            host: crate::ca::normalize_host(host),
            network_gen: net_bind::network_gen(),
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
        let key = PoolKey {
            host: crate::ca::normalize_host(host),
            network_gen: net_gen,
        };
        self.h2_pool.lock().insert(key, sender);
    }

    fn evict_h2_sender(&self, host: &str) {
        let clean = crate::ca::normalize_host(host);
        self.h2_pool.lock().retain(|k, _| k.host != clean);
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
        let conn = {
            let _guard = self.connect_lock.lock().await;
            if let Some(mut sender) = self.take_ready_h2_sender(host) {
                if !sender.is_closed() && sender.ready().await.is_ok() {
                    normalize_for_h2(host, &mut req);
                    return match timeout(send_timeout, sender.send_request(req)).await {
                        Ok(Ok(resp)) => Ok(resp.map(|b| b.boxed())),
                        Ok(Err(e)) => {
                            self.evict_h2_sender(host);
                            Err(format!("h2 coalesced send error: {e}"))
                        }
                        Err(_) => {
                            self.evict_h2_sender(host);
                            Err("h2 coalesced send timeout".to_string())
                        }
                    };
                }
                self.evict_h2_sender(host);
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
            h2_builder
                .timer(TokioTimer::new())
                .initial_stream_window_size(6_291_456)
                .initial_connection_window_size(15_728_640)
                .header_table_size(65_536)
                .max_header_list_size(262_144)
                .keep_alive_interval(Duration::from_secs(25))
                .keep_alive_timeout(Duration::from_secs(10));

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
            let resp = timeout(send_timeout, sender.send_request(req))
                .await
                .map_err(|_| "http1 send timeout".to_string())?
                .map_err(|e| format!("http1 send: {e}"))?;
            Ok(resp.map(|b| b.boxed()))
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
                    Err(_) => {
                        // Fall back to standard OS DNS + TLS 1.3 if DoH IPs are blocked on the current network.
                        let resolved: Vec<SocketAddr> = tokio::net::lookup_host((host, 443))
                            .await
                            .map_err(|e| format!("DoH unavailable and OS DNS failed for {host}: {e}"))?
                            .collect();
                        let first = resolved
                            .first()
                            .copied()
                            .ok_or_else(|| format!("OS DNS yielded no addresses for {host}"))?;
                        (resolved.into_iter().skip(1).collect(), first, Vec::new())
                    }
                }
            };

        for attempt in 0..2 {
            let net_gen = net_bind::network_gen();
            let (tcp, _bound) = match net_bind::connect_tcp(target_addr, Duration::from_millis(5000)).await {
                Ok(pair) => pair,
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
                    return Err(format!("upstream tcp connect {target_addr}: {err}"));
                }
            };

            let mut cfg = self
                .connector
                .configure()
                .map_err(|e| format!("connector configure: {e}"))?;
            let _ = cfg.add_application_settings(b"h2");
            if !ech_list.is_empty() {
                cfg.set_ech_config_list(&ech_list)
                    .map_err(|e| format!("set_ech_config_list: {e}"))?;
            }

            let ssl = cfg
                .into_ssl(host)
                .map_err(|e| format!("into_ssl({host}): {e}"))?;
            let mut tls = SslStream::new(ssl, tcp).map_err(|e| format!("ssl stream: {e}"))?;

            match timeout(Duration::from_millis(6000), Pin::new(&mut tls).connect()).await {
                Ok(Ok(())) => {
                    let accepted = tls.ssl().ech_accepted();
                    LAST_ECH_ACCEPTED.store(accepted, Ordering::Relaxed);
                    if accepted {
                        ECH_HANDSHAKE_COUNT.fetch_add(1, Ordering::Relaxed);
                    }
                    return Ok(EstablishedTls {
                        tls,
                        network_gen: net_gen,
                    });
                }
                Ok(Err(err)) => {
                    if attempt == 0 {
                        if let Some(retry) = tls.ssl().get_ech_retry_configs() {
                            let retry_vec = retry.to_vec();
                            if self.test_override.is_none() {
                                self.doh.update_ech_retry_configs(retry_vec.clone()).await;
                            }
                            ech_list = retry_vec;
                            continue;
                        }
                        if self.test_override.is_none() {
                            if let IpAddr::V4(old_v4) = target_addr.ip() {
                                if let Some(next_ip) = self.doh.rotate_sticky_ip(old_v4).await {
                                    target_addr = SocketAddr::new(IpAddr::V4(next_ip), 443);
                                    continue;
                                }
                            }
                        }
                    }
                    return Err(format!("upstream boringssl connect {host}: {err}"));
                }
                Err(_) => {
                    return Err(format!("upstream tls handshake timeout for {host}"));
                }
            }
        }

        Err(format!("upstream ECH connect exhausted retries for {host}"))
    }
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
    builder.set_permute_extensions(true);
    let _ = builder.set_min_proto_version(Some(SslVersion::TLS1_2));
    let _ = builder.set_max_proto_version(Some(SslVersion::TLS1_3));

    // Prefer PQ hybrid X25519MLKEM768 first to match Chrome 131+ ClientHello, fallback cleanly.
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
         ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305",
    );
    let _ = builder.set_sigalgs_list(
        "ecdsa_secp256r1_sha256:rsa_pss_rsae_sha256:rsa_pkcs1_sha256:\
         ecdsa_secp384r1_sha384:rsa_pss_rsae_sha384:rsa_pkcs1_sha384:\
         rsa_pss_rsae_sha512:rsa_pkcs1_sha512",
    );
    let _ = builder.add_certificate_compression_algorithm(BrotliCertDecompressor);
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
