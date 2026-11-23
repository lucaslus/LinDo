pub mod ca;
pub mod doh;
pub mod net_bind;
pub mod proxy;
pub mod tls_upstream;

use std::sync::{Arc, OnceLock};

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jint, jlong, jstring, JNI_TRUE};
use jni::JNIEnv;
use parking_lot::Mutex;
use serde::Serialize;
use tokio::runtime::Runtime;

use crate::ca::CaAuthority;
use crate::doh::DohResolver;
use crate::proxy::GatewayServer;
use crate::tls_upstream::UpstreamPool;

struct GatewayState {
    server: GatewayServer,
    ca: Arc<CaAuthority>,
    doh: Arc<DohResolver>,
    upstream: Arc<UpstreamPool>,
    bypass_vpn: bool,
    net_handle: u64,
}

static RUNTIME: OnceLock<Runtime> = OnceLock::new();
static GATEWAY: OnceLock<Mutex<Option<GatewayState>>> = OnceLock::new();
static LAST_ERROR: OnceLock<Mutex<String>> = OnceLock::new();

fn runtime() -> Result<&'static Runtime, String> {
    if let Some(rt) = RUNTIME.get() {
        return Ok(rt);
    }
    let rt = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .thread_name("lindo-ech")
        .enable_all()
        .build()
        .map_err(|e| format!("tokio runtime init: {e}"))?;
    let _ = RUNTIME.set(rt);
    RUNTIME
        .get()
        .ok_or_else(|| "tokio runtime unavailable".to_string())
}

fn gateway_lock() -> &'static Mutex<Option<GatewayState>> {
    GATEWAY.get_or_init(|| Mutex::new(None))
}

fn set_last_error(msg: impl Into<String>) {
    let lock = LAST_ERROR.get_or_init(|| Mutex::new(String::new()));
    *lock.lock() = msg.into();
}

fn last_error_string() -> String {
    LAST_ERROR
        .get_or_init(|| Mutex::new(String::new()))
        .lock()
        .clone()
}

#[derive(Serialize)]
struct GeneratedCaPayload {
    #[serde(rename = "certPem")]
    cert_pem: String,
    #[serde(rename = "keyPem")]
    key_pem: String,
    #[serde(rename = "spkiSha256")]
    spki_sha256: String,
}

#[derive(Serialize)]
struct GatewayStatusPayload {
    active: bool,
    #[serde(rename = "listenPort")]
    listen_port: u16,
    #[serde(rename = "stickyIp")]
    sticky_ip: String,
    #[serde(rename = "bypassVpnRequested")]
    bypass_vpn_requested: bool,
    #[serde(rename = "bypassVpnBound")]
    bypass_vpn_bound: bool,
    #[serde(rename = "netHandle")]
    net_handle: u64,
    #[serde(rename = "networkGen")]
    network_gen: u64,
    #[serde(rename = "echHandshakeCount")]
    ech_handshake_count: u64,
    #[serde(rename = "lastEchAccepted")]
    last_ech_accepted: bool,
    #[serde(rename = "lastBindErrno")]
    last_bind_errno: i32,
    #[serde(rename = "lastError")]
    last_error: String,
}

pub fn generate_ca_json() -> Result<String, String> {
    let (cert_pem, key_pem) = ca::generate_root_ca_pem()?;
    let ca = CaAuthority::from_pem(&cert_pem, &key_pem)?;
    let spki_sha256 = ca.spki_sha256_base64()?;
    serde_json::to_string(&GeneratedCaPayload {
        cert_pem,
        key_pem,
        spki_sha256,
    })
    .map_err(|e| format!("serialize ca json: {e}"))
}

pub fn start_gateway(
    cert_pem: &str,
    key_pem: &str,
    net_handle: u64,
    bypass_vpn: bool,
) -> Result<u16, String> {
    let rt = runtime()?;
    let ca = CaAuthority::from_pem(cert_pem, key_pem)?;
    let doh = DohResolver::new()?;
    let upstream = UpstreamPool::new(Arc::clone(&doh))?;

    net_bind::update_network(net_handle, bypass_vpn);

    let mut guard = gateway_lock().lock();
    if let Some(mut old) = guard.take() {
        old.server.stop();
    }

    let server = rt.block_on(GatewayServer::start(
        Arc::clone(&ca),
        Arc::clone(&doh),
        Arc::clone(&upstream),
    ))?;
    let port = server.listen_port;

    // Warm up DoH ECH + sticky Cloudflare IP asynchronously right after binding.
    let doh_warm = Arc::clone(&doh);
    rt.spawn(async move {
        let _ = doh_warm.get_ech_and_sticky_ip().await;
    });

    *guard = Some(GatewayState {
        server,
        ca,
        doh,
        upstream,
        bypass_vpn,
        net_handle,
    });
    set_last_error("");
    Ok(port)
}

pub fn stop_gateway() {
    if let Some(mut old) = gateway_lock().lock().take() {
        old.server.stop();
    }
}

pub fn update_network(net_handle: u64, bypass_vpn: bool) {
    let changed = net_bind::update_network(net_handle, bypass_vpn);
    let doh_to_warm = {
        let mut guard = gateway_lock().lock();
        if let Some(ref mut state) = *guard {
            state.net_handle = net_handle;
            state.bypass_vpn = bypass_vpn;
            if changed {
                Some(Arc::clone(&state.doh))
            } else {
                None
            }
        } else {
            None
        }
    };
    if let Some(doh) = doh_to_warm {
        if let Ok(rt) = runtime() {
            rt.spawn(async move {
                let _ = doh.get_ech_and_sticky_ip().await;
            });
        }
    }
}

pub fn verify_leaf_der(leaf_der: &[u8], expected_host: &str) -> bool {
    let ca_opt = gateway_lock().lock().as_ref().and_then(|g| {
        if g.server.route_mode() == proxy::GatewayRouteMode::EchDirect {
            Some(Arc::clone(&g.ca))
        } else {
            None
        }
    });
    let Some(ca) = ca_opt else {
        return false;
    };
    ca.verify_leaf_der(leaf_der, expected_host)
}

pub fn set_gateway_route_mode(mode: proxy::GatewayRouteMode) {
    let updated = {
        let guard = gateway_lock().lock();
        if let Some(ref state) = *guard {
            if state.server.route_mode() != mode {
                state.server.set_route_mode(mode);
                Some((state.net_handle, state.bypass_vpn))
            } else {
                None
            }
        } else {
            None
        }
    };
    if let Some((handle, bypass)) = updated {
        net_bind::update_network(handle, bypass);
    }
}

pub fn current_ca_spki_sha256() -> String {
    let ca_opt = gateway_lock().lock().as_ref().map(|g| Arc::clone(&g.ca));
    ca_opt
        .and_then(|ca| ca.spki_sha256_base64().ok())
        .unwrap_or_default()
}

pub fn current_gateway_port() -> u16 {
    gateway_lock()
        .lock()
        .as_ref()
        .map(|g| g.server.listen_port)
        .unwrap_or(0)
}

pub fn current_gateway_route_mode() -> Option<proxy::GatewayRouteMode> {
    gateway_lock()
        .lock()
        .as_ref()
        .map(|g| g.server.route_mode())
}

pub fn rotate_sticky_ip() {
    let pair = gateway_lock()
        .lock()
        .as_ref()
        .map(|g| (Arc::clone(&g.doh), Arc::clone(&g.upstream)));
    if let Some((doh, upstream)) = pair {
        upstream.clear_h2_pool();
        if doh.try_rotate_current_sticky_ip_sync().is_none() {
            if let Ok(rt) = runtime() {
                rt.spawn(async move {
                    let _ = doh.rotate_current_sticky_ip().await;
                });
            }
        }
    }
}

pub fn get_status_json() -> String {
    let snap_meta = gateway_lock().lock().as_ref().map(|g| {
        (
            g.server.listen_port,
            Arc::clone(&g.doh),
            g.bypass_vpn,
            g.net_handle,
        )
    });

    let payload = if let Some((listen_port, doh, bypass_req, net_handle)) = snap_meta {
        let sticky_ip = doh
            .cached_sticky_ip_sync()
            .map(|ip| ip.to_string())
            .unwrap_or_default();
        GatewayStatusPayload {
            active: true,
            listen_port,
            sticky_ip,
            bypass_vpn_requested: bypass_req,
            bypass_vpn_bound: bypass_req && net_handle != 0 && net_bind::last_bound_physical(),
            net_handle,
            network_gen: net_bind::network_gen(),
            ech_handshake_count: tls_upstream::ech_handshake_count(),
            last_ech_accepted: tls_upstream::last_ech_accepted(),
            last_bind_errno: net_bind::last_bind_errno(),
            last_error: last_error_string(),
        }
    } else {
        GatewayStatusPayload {
            active: false,
            listen_port: 0,
            sticky_ip: String::new(),
            bypass_vpn_requested: false,
            bypass_vpn_bound: false,
            net_handle: net_bind::physical_handle(),
            network_gen: net_bind::network_gen(),
            ech_handshake_count: tls_upstream::ech_handshake_count(),
            last_ech_accepted: tls_upstream::last_ech_accepted(),
            last_bind_errno: net_bind::last_bind_errno(),
            last_error: last_error_string(),
        }
    };

    serde_json::to_string(&payload).unwrap_or_else(|_| "{\"active\":false}".to_string())
}

fn new_jstring(env: &JNIEnv<'_>, text: &str) -> jstring {
    match env.new_string(text) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_do_1_linux_lindo_ech_NativeEchBridge_nativeGenerateCaJson<'local>(
    env: JNIEnv<'local>,
    _cls: JClass<'local>,
) -> jstring {
    match generate_ca_json() {
        Ok(json) => new_jstring(&env, &json),
        Err(err) => {
            set_last_error(err);
            new_jstring(&env, "")
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_do_1_linux_lindo_ech_NativeEchBridge_nativeStartGateway<'local>(
    mut env: JNIEnv<'local>,
    _cls: JClass<'local>,
    cert_pem: JString<'local>,
    key_pem: JString<'local>,
    net_handle: jlong,
    bypass_vpn: jboolean,
) -> jint {
    let Ok(cert) = env.get_string(&cert_pem).map(|s| s.to_string_lossy().into_owned()) else {
        set_last_error("invalid certPem JNI string");
        return -1;
    };
    let Ok(key) = env.get_string(&key_pem).map(|s| s.to_string_lossy().into_owned()) else {
        set_last_error("invalid keyPem JNI string");
        return -1;
    };
    let handle_u64 = net_handle as u64;
    let bypass = bypass_vpn == JNI_TRUE;
    match start_gateway(&cert, &key, handle_u64, bypass) {
        Ok(port) => port as jint,
        Err(err) => {
            set_last_error(err);
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_do_1_linux_lindo_ech_NativeEchBridge_nativeStopGateway<'local>(
    _env: JNIEnv<'local>,
    _cls: JClass<'local>,
) {
    stop_gateway();
}

#[no_mangle]
pub extern "system" fn Java_do_1_linux_lindo_ech_NativeEchBridge_nativeUpdateNetwork<'local>(
    _env: JNIEnv<'local>,
    _cls: JClass<'local>,
    net_handle: jlong,
    bypass_vpn: jboolean,
) {
    update_network(net_handle as u64, bypass_vpn == JNI_TRUE);
}

#[no_mangle]
pub extern "system" fn Java_do_1_linux_lindo_ech_NativeEchBridge_nativeCanBindNetwork<'local>(
    _env: JNIEnv<'local>,
    _cls: JClass<'local>,
    net_handle: jlong,
) -> jboolean {
    if net_bind::can_bind_network(net_handle as u64) {
        JNI_TRUE
    } else {
        0
    }
}

#[no_mangle]
pub extern "system" fn Java_do_1_linux_lindo_ech_NativeEchBridge_nativeVerifyLeafDer<'local>(
    mut env: JNIEnv<'local>,
    _cls: JClass<'local>,
    leaf_der: JByteArray<'local>,
    host: JString<'local>,
) -> jboolean {
    let Ok(der_bytes) = env.convert_byte_array(&leaf_der) else {
        return 0;
    };
    let Ok(host_str) = env.get_string(&host).map(|s| s.to_string_lossy().into_owned()) else {
        return 0;
    };
    if verify_leaf_der(&der_bytes, &host_str) {
        JNI_TRUE
    } else {
        0
    }
}

#[no_mangle]
pub extern "system" fn Java_do_1_linux_lindo_ech_NativeEchBridge_nativeRotateStickyIp<'local>(
    _env: JNIEnv<'local>,
    _cls: JClass<'local>,
) {
    rotate_sticky_ip();
}

#[no_mangle]
pub extern "system" fn Java_do_1_linux_lindo_ech_NativeEchBridge_nativeGetStatusJson<'local>(
    env: JNIEnv<'local>,
    _cls: JClass<'local>,
) -> jstring {
    let json = get_status_json();
    new_jstring(&env, &json)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{IpAddr, Ipv4Addr, SocketAddr};
    use std::pin::Pin;
    use std::sync::Arc;

    use base64::Engine as _;
    use boring2::ssl::{Ssl, SslAcceptor, SslConnector, SslMethod, SslVerifyMode};
    use boring2::x509::store::X509StoreBuilder;
    use foreign_types::ForeignTypeRef;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;
    use tokio_boring2::SslStream;

    #[test]
    fn test_ca_leaf_minting_and_strict_verification() {
        let (ca1_cert, ca1_key) = ca::generate_root_ca_pem().unwrap();
        let (ca2_cert, ca2_key) = ca::generate_root_ca_pem().unwrap();
        let auth1 = CaAuthority::from_pem(&ca1_cert, &ca1_key).unwrap();
        let auth2 = CaAuthority::from_pem(&ca2_cert, &ca2_key).unwrap();

        let leaf_linuxdo = auth1.leaf_for_host("linux.do").unwrap();
        let der = leaf_linuxdo.cert.to_der().unwrap();
        assert!(auth1.verify_leaf_der(&der, "linux.do"));
        assert!(auth1.verify_leaf_der(&der, "LINUX.DO."));
        assert!(!auth1.verify_leaf_der(&der, "evil.example.com"));
        assert!(!auth1.verify_leaf_der(&der, "notlinux.do"));
        assert!(!auth1.verify_leaf_der(&der, "cdn.ldstatic.com"));
        // Reject certificate signed by another local CA
        assert!(!auth2.verify_leaf_der(&der, "linux.do"));
        // Reject corrupted DER bytes
        assert!(!auth1.verify_leaf_der(b"not-a-der-cert", "linux.do"));
    }

    #[test]
    fn test_dns_wire_encode_decode_https_ech_and_fakeip_filter() {
        let query = doh::build_dns_query("cloudflare-ech.com", doh::QTYPE_HTTPS);
        assert!(query.len() > 18);
        assert_eq!(&query[query.len() - 4..], &[0x00, 65, 0x00, 1]);

        // Synthetic DNS response with 2 answers:
        // 1) A record for linux.do -> 104.16.132.229 (TTL 120)
        // 2) HTTPS (Type 65) record with SvcPriority=1, Target='.', SvcParam key=4 (ipv4hint 172.67.150.25), key=5 (ECH=[0xAA, 0xBB, 0xCC, 0xDD])
        let mut pkt = vec![
            0x00, 0x00, 0x81, 0x80, // Header
            0x00, 0x01, // QDCOUNT=1
            0x00, 0x02, // ANCOUNT=2
            0x00, 0x00, 0x00, 0x00,
        ];
        // Question: cloudflare-ech.com HTTPS IN
        pkt.extend_from_slice(b"\x0ecloudflare-ech\x03com\x00");
        pkt.extend_from_slice(&[0x00, 65, 0x00, 0x01]);
        // Answer 1 (compressed pointer 0xC00C): A 104.16.132.229 TTL=120
        pkt.extend_from_slice(&[0xC0, 0x0C, 0x00, 1, 0x00, 1, 0x00, 0x00, 0x00, 120, 0x00, 4]);
        pkt.extend_from_slice(&[104, 16, 132, 229]);
        // Answer 2 (compressed pointer 0xC00C): HTTPS Type 65 TTL=90
        let https_rdata: Vec<u8> = vec![
            0x00, 0x01, // Priority=1
            0x00,       // TargetName='.'
            0x00, 0x04, 0x00, 0x04, 172, 67, 150, 25, // SvcParamKey=4 ipv4hint
            0x00, 0x05, 0x00, 0x04, 0xAA, 0xBB, 0xCC, 0xDD, // SvcParamKey=5 ECHConfigList
        ];
        pkt.extend_from_slice(&[0xC0, 0x0C, 0x00, 65, 0x00, 1, 0x00, 0x00, 0x00, 90]);
        pkt.extend_from_slice(&(https_rdata.len() as u16).to_be_bytes());
        pkt.extend_from_slice(&https_rdata);

        let parsed = doh::parse_dns_response(&pkt).unwrap();
        assert_eq!(parsed.ipv4, vec![Ipv4Addr::new(104, 16, 132, 229)]);
        assert_eq!(parsed.ipv4_hints, vec![Ipv4Addr::new(172, 67, 150, 25)]);
        assert_eq!(parsed.ech_configs, vec![vec![0xAA, 0xBB, 0xCC, 0xDD]]);
        assert_eq!(parsed.min_ttl_secs, 90);

        // FakeIP / bogon / public IP checks
        assert!(!doh::is_valid_public_ipv4(Ipv4Addr::new(198, 18, 0, 2)));
        assert!(!doh::is_valid_public_ipv4(Ipv4Addr::new(198, 19, 255, 1)));
        assert!(!doh::is_valid_public_ipv4(Ipv4Addr::new(127, 0, 0, 1)));
        assert!(!doh::is_valid_public_ipv4(Ipv4Addr::new(10, 0, 0, 1)));
        assert!(!doh::is_valid_public_ipv4(Ipv4Addr::new(100, 64, 0, 1)));
        assert!(doh::is_valid_public_ipv4(Ipv4Addr::new(104, 16, 132, 229)));
    }

    #[test]
    fn test_doh_cf_ip_filter_rejects_gfw_poison_and_enterprise_1034_ips() {
        assert!(!doh::is_cloudflare_for_linux_do(Ipv4Addr::new(104, 16, 132, 229)));
        assert!(!doh::is_cloudflare_for_linux_do(Ipv4Addr::new(104, 16, 133, 229)));
        assert!(!doh::is_cloudflare_for_linux_do(Ipv4Addr::new(199, 96, 59, 95)));
        assert!(!doh::is_cloudflare_for_linux_do(Ipv4Addr::new(104, 244, 46, 21)));
        assert!(!doh::is_cloudflare_for_linux_do(Ipv4Addr::new(108, 160, 165, 139)));
        assert!(!doh::is_cloudflare_for_linux_do(Ipv4Addr::new(173, 252, 248, 244)));
        assert!(!doh::is_cloudflare_for_linux_do(Ipv4Addr::new(198, 18, 0, 2)));

        assert!(doh::is_cloudflare_for_linux_do(Ipv4Addr::new(104, 18, 10, 118)));
        assert!(doh::is_cloudflare_for_linux_do(Ipv4Addr::new(104, 18, 11, 118)));
        assert!(doh::is_cloudflare_for_linux_do(Ipv4Addr::new(104, 20, 16, 234)));
        assert!(doh::is_cloudflare_for_linux_do(Ipv4Addr::new(172, 66, 166, 61)));
    }

    #[test]
    fn test_mitm_policy_excludes_turnstile_challenges() {
        assert!(proxy::should_mitm_host("linux.do"));
        assert!(proxy::should_mitm_host("LINUX.DO."));
        assert!(proxy::should_mitm_host("cdn.ldstatic.com"));
        assert!(proxy::should_mitm_host("idcflare.com"));
        assert!(!proxy::should_mitm_host("challenges.cloudflare.com"));
        assert!(!proxy::should_mitm_host("static.cloudflareinsights.com"));
        assert!(!proxy::should_mitm_host("github.com"));
    }

    #[tokio::test]
    async fn test_loopback_ech_gateway_and_tunnel_end_to_end() {
        let ech_cfg = base64::engine::general_purpose::STANDARD
            .decode("/g0AOgAAIAAguy8p4+MFfgQZ1S/F9EEYd2+NthzqT992B5uTYGxaYkgACAABAAEAAQADAAdlY2guY29tAAA=")
            .unwrap();
        let ech_key = base64::engine::general_purpose::STANDARD
            .decode("nx3rjPVWNbMfaatuM+8AnorXKBktqxI3lEvHfD+pH4I=")
            .unwrap();
        let ech_list = base64::engine::general_purpose::STANDARD
            .decode("AD7+DQA6AAAgACC7Lynj4wV+BBnVL8X0QRh3b422HOpP33YHm5NgbFpiSAAIAAEAAQABAAMAB2VjaC5jb20AAA==")
            .unwrap();

        // Mint an upstream server CA + leaf for "linux.do" + an ECH acceptor.
        let (up_ca_pem, up_ca_key) = ca::generate_root_ca_pem().unwrap();
        let up_ca = CaAuthority::from_pem(&up_ca_pem, &up_ca_key).unwrap();
        let up_leaf = up_ca.leaf_for_host("linux.do").unwrap();

        let mut up_acc_builder = SslAcceptor::mozilla_intermediate_v5(SslMethod::tls_server()).unwrap();
        up_acc_builder.set_certificate(&up_leaf.cert).unwrap();
        up_acc_builder.set_private_key(&up_leaf.key).unwrap();
        up_acc_builder.set_alpn_select_callback(|_, protos| {
            let mut p = 0usize;
            while p < protos.len() {
                let l = protos[p] as usize;
                p += 1;
                if p + l <= protos.len() && &protos[p..p + l] == b"http/1.1" {
                    return Ok(&protos[p..p + l]);
                }
                p += l;
            }
            Err(boring2::ssl::AlpnError::NOACK)
        });
        let up_acceptor = Arc::new(up_acc_builder.build());
        unsafe {
            boring_sys2::init();
            let hpke_key = boring_sys2::EVP_HPKE_KEY_new();
            assert!(!hpke_key.is_null());
            assert_eq!(
                boring_sys2::EVP_HPKE_KEY_init(
                    hpke_key,
                    boring_sys2::EVP_hpke_x25519_hkdf_sha256(),
                    ech_key.as_ptr(),
                    ech_key.len(),
                ),
                1
            );
            let ech_keys = boring_sys2::SSL_ECH_KEYS_new();
            assert!(!ech_keys.is_null());
            assert_eq!(
                boring_sys2::SSL_ECH_KEYS_add(
                    ech_keys,
                    1,
                    ech_cfg.as_ptr(),
                    ech_cfg.len(),
                    hpke_key,
                ),
                1
            );
            assert_eq!(
                boring_sys2::SSL_CTX_set1_ech_keys(up_acceptor.context().as_ptr() as *mut _, ech_keys),
                1
            );
            boring_sys2::SSL_ECH_KEYS_free(ech_keys);
            boring_sys2::EVP_HPKE_KEY_free(hpke_key);
        }

        let upstream_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let upstream_addr = upstream_listener.local_addr().unwrap();
        tokio::spawn(async move {
            let (tcp, _) = upstream_listener.accept().await.unwrap();
            let ssl = Ssl::new(up_acceptor.context()).unwrap();
            let mut tls = SslStream::new(ssl, tcp).unwrap();
            Pin::new(&mut tls).accept().await.unwrap();
            assert!(tls.ssl().ech_accepted());
            let mut buf = [0u8; 1024];
            let _ = tls.read(&mut buf).await.unwrap();
            tls.write_all(
                b"HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 9\r\nConnection: close\r\n\r\nech-works",
            )
            .await
            .unwrap();
            tls.flush().await.unwrap();
        });

        // Start local ECH proxy gateway targeting our loopback ECH server.
        let (gw_ca_pem, gw_ca_key) = ca::generate_root_ca_pem().unwrap();
        let gw_ca = CaAuthority::from_pem(&gw_ca_pem, &gw_ca_key).unwrap();
        let gw_ca_cert = boring2::x509::X509::from_pem(gw_ca_pem.as_bytes()).unwrap();
        let doh = DohResolver::new().unwrap();
        let up_ca_x509 = boring2::x509::X509::from_pem(up_ca_pem.as_bytes()).unwrap();
        let upstream = UpstreamPool::new_with_test_override(
            Arc::clone(&doh),
            tls_upstream::TestTargetOverride {
                addr: upstream_addr,
                ech_config_list: ech_list,
                ca_cert: up_ca_x509,
            },
        )
        .unwrap();

        let mut server = GatewayServer::start(Arc::clone(&gw_ca), doh, upstream)
            .await
            .unwrap();
        let proxy_addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), server.listen_port);

        // Client simulates WebView connecting through `HTTP CONNECT linux.do:443`.
        let mut client_tcp = tokio::net::TcpStream::connect(proxy_addr).await.unwrap();
        client_tcp
            .write_all(b"CONNECT linux.do:443 HTTP/1.1\r\nHost: linux.do:443\r\n\r\n")
            .await
            .unwrap();
        let mut conn_resp = vec![0u8; 64];
        let n = client_tcp.read(&mut conn_resp).await.unwrap();
        assert!(std::str::from_utf8(&conn_resp[..n]).unwrap().contains("200 Connection Established"));

        let mut cli_builder = SslConnector::builder(SslMethod::tls_client()).unwrap();
        cli_builder.set_verify(SslVerifyMode::PEER);
        let mut cli_store = X509StoreBuilder::new().unwrap();
        cli_store.add_cert(gw_ca_cert).unwrap();
        cli_builder.set_verify_cert_store(cli_store.build()).unwrap();
        let _ = cli_builder.set_alpn_protos(b"\x08http/1.1");
        let ssl = cli_builder
            .build()
            .configure()
            .unwrap()
            .into_ssl("linux.do")
            .unwrap();
        let mut cli_tls = SslStream::new(ssl, client_tcp).unwrap();
        Pin::new(&mut cli_tls).connect().await.unwrap();
        cli_tls
            .write_all(b"GET /about.json HTTP/1.1\r\nHost: linux.do\r\nConnection: close\r\n\r\n")
            .await
            .unwrap();
        let mut http_buf = Vec::new();
        cli_tls.read_to_end(&mut http_buf).await.unwrap();
        let text = String::from_utf8_lossy(&http_buf);
        assert!(text.contains("200 OK") && text.contains("ech-works"), "unexpected response: {text}");
        assert!(tls_upstream::ech_handshake_count() >= 1);
        assert!(tls_upstream::last_ech_accepted());

        // Also test transparent non-MITM CONNECT tunnel passthrough.
        let echo_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let echo_addr = echo_listener.local_addr().unwrap();
        tokio::spawn(async move {
            let (mut s, _) = echo_listener.accept().await.unwrap();
            let mut buf = [0u8; 32];
            let n = s.read(&mut buf).await.unwrap();
            s.write_all(&buf[..n]).await.unwrap();
        });
        let mut tun = tokio::net::TcpStream::connect(proxy_addr).await.unwrap();
        tun.write_all(format!("CONNECT 127.0.0.1:{} HTTP/1.1\r\n\r\n", echo_addr.port()).as_bytes())
            .await
            .unwrap();
        let mut hdr = [0u8; 64];
        let hn = tun.read(&mut hdr).await.unwrap();
        assert!(std::str::from_utf8(&hdr[..hn]).unwrap().contains("200 Connection Established"));
        tun.write_all(b"PING_TURNSTILE").await.unwrap();
        let mut echo = [0u8; 32];
        let en = tun.read(&mut echo).await.unwrap();
        assert_eq!(&echo[..en], b"PING_TURNSTILE");

        server.stop();
    }

    #[tokio::test]
    async fn test_loopback_h2_over_ech_gateway_and_pooled_reuse() {
        let ech_cfg = base64::engine::general_purpose::STANDARD
            .decode("/g0AOgAAIAAguy8p4+MFfgQZ1S/F9EEYd2+NthzqT992B5uTYGxaYkgACAABAAEAAQADAAdlY2guY29tAAA=")
            .unwrap();
        let ech_key = base64::engine::general_purpose::STANDARD
            .decode("nx3rjPVWNbMfaatuM+8AnorXKBktqxI3lEvHfD+pH4I=")
            .unwrap();
        let ech_list = base64::engine::general_purpose::STANDARD
            .decode("AD7+DQA6AAAgACC7Lynj4wV+BBnVL8X0QRh3b422HOpP33YHm5NgbFpiSAAIAAEAAQABAAMAB2VjaC5jb20AAA==")
            .unwrap();

        let (up_ca_pem, up_ca_key) = ca::generate_root_ca_pem().unwrap();
        let up_ca = CaAuthority::from_pem(&up_ca_pem, &up_ca_key).unwrap();
        let up_leaf = up_ca.leaf_for_host("linux.do").unwrap();

        let mut up_acc_builder = SslAcceptor::mozilla_intermediate_v5(SslMethod::tls_server()).unwrap();
        up_acc_builder.set_certificate(&up_leaf.cert).unwrap();
        up_acc_builder.set_private_key(&up_leaf.key).unwrap();
        up_acc_builder.set_alpn_select_callback(|_, protos| {
            let mut p = 0usize;
            while p < protos.len() {
                let l = protos[p] as usize;
                p += 1;
                if p + l <= protos.len() && &protos[p..p + l] == b"h2" {
                    return Ok(&protos[p..p + l]);
                }
                p += l;
            }
            Err(boring2::ssl::AlpnError::NOACK)
        });
        let up_acceptor = Arc::new(up_acc_builder.build());
        unsafe {
            boring_sys2::init();
            let hpke_key = boring_sys2::EVP_HPKE_KEY_new();
            assert!(!hpke_key.is_null());
            assert_eq!(
                boring_sys2::EVP_HPKE_KEY_init(
                    hpke_key,
                    boring_sys2::EVP_hpke_x25519_hkdf_sha256(),
                    ech_key.as_ptr(),
                    ech_key.len(),
                ),
                1
            );
            let ech_keys = boring_sys2::SSL_ECH_KEYS_new();
            assert!(!ech_keys.is_null());
            assert_eq!(
                boring_sys2::SSL_ECH_KEYS_add(
                    ech_keys,
                    1,
                    ech_cfg.as_ptr(),
                    ech_cfg.len(),
                    hpke_key,
                ),
                1
            );
            assert_eq!(
                boring_sys2::SSL_CTX_set1_ech_keys(up_acceptor.context().as_ptr() as *mut _, ech_keys),
                1
            );
            boring_sys2::SSL_ECH_KEYS_free(ech_keys);
            boring_sys2::EVP_HPKE_KEY_free(hpke_key);
        }

        let upstream_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let upstream_addr = upstream_listener.local_addr().unwrap();
        let up_accept_count = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let up_accept_count_srv = Arc::clone(&up_accept_count);
        tokio::spawn(async move {
            while let Ok((tcp, _)) = upstream_listener.accept().await {
                up_accept_count_srv.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                let ssl = Ssl::new(up_acceptor.context()).unwrap();
                let mut tls = SslStream::new(ssl, tcp).unwrap();
                Pin::new(&mut tls).accept().await.unwrap();
                assert!(tls.ssl().ech_accepted());
                assert_eq!(tls.ssl().selected_alpn_protocol(), Some(b"h2".as_slice()));
                let svc = hyper::service::service_fn(|req: hyper::Request<hyper::body::Incoming>| async move {
                    assert_eq!(req.uri().scheme_str(), Some("https"));
                    assert_eq!(req.uri().authority().map(|a| a.as_str()), Some("linux.do"));
                    assert!(!req.headers().contains_key(http::header::HOST));
                    let pq = req.uri().path_and_query().map(|v| v.as_str()).unwrap_or("/");
                    let body = format!("h2-ech:{pq}");
                    Ok::<_, std::convert::Infallible>(hyper::Response::new(
                        http_body_util::Full::new(bytes::Bytes::from(body)),
                    ))
                });
                tokio::spawn(async move {
                    let _ = hyper::server::conn::http2::Builder::new(hyper_util::rt::TokioExecutor::new())
                        .serve_connection(hyper_util::rt::TokioIo::new(tls), svc)
                        .await;
                });
            }
        });

        let (gw_ca_pem, gw_ca_key) = ca::generate_root_ca_pem().unwrap();
        let gw_ca = CaAuthority::from_pem(&gw_ca_pem, &gw_ca_key).unwrap();
        let gw_ca_cert = boring2::x509::X509::from_pem(gw_ca_pem.as_bytes()).unwrap();
        let doh = DohResolver::new().unwrap();
        let up_ca_x509 = boring2::x509::X509::from_pem(up_ca_pem.as_bytes()).unwrap();
        let upstream = UpstreamPool::new_with_test_override(
            Arc::clone(&doh),
            tls_upstream::TestTargetOverride {
                addr: upstream_addr,
                ech_config_list: ech_list,
                ca_cert: up_ca_x509,
            },
        )
        .unwrap();

        let mut server = GatewayServer::start(Arc::clone(&gw_ca), doh, upstream)
            .await
            .unwrap();
        let proxy_addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), server.listen_port);

        let mut client_tcp = tokio::net::TcpStream::connect(proxy_addr).await.unwrap();
        client_tcp
            .write_all(b"CONNECT linux.do:443 HTTP/1.1\r\nHost: linux.do:443\r\n\r\n")
            .await
            .unwrap();
        let mut conn_resp = vec![0u8; 64];
        let n = client_tcp.read(&mut conn_resp).await.unwrap();
        assert!(std::str::from_utf8(&conn_resp[..n]).unwrap().contains("200 Connection Established"));

        let mut cli_builder = SslConnector::builder(SslMethod::tls_client()).unwrap();
        cli_builder.set_verify(SslVerifyMode::PEER);
        let mut cli_store = X509StoreBuilder::new().unwrap();
        cli_store.add_cert(gw_ca_cert).unwrap();
        cli_builder.set_verify_cert_store(cli_store.build()).unwrap();
        let _ = cli_builder.set_alpn_protos(b"\x08http/1.1");
        let ssl = cli_builder
            .build()
            .configure()
            .unwrap()
            .into_ssl("linux.do")
            .unwrap();
        let mut cli_tls = SslStream::new(ssl, client_tcp).unwrap();
        Pin::new(&mut cli_tls).connect().await.unwrap();

        // Request 1 (keep-alive) establishes pooled H2 over ECH.
        cli_tls
            .write_all(
                b"GET /latest.json?ascending=false HTTP/1.1\r\nHost: linux.do\r\nProxy-Connection: keep-alive\r\n\r\n",
            )
            .await
            .unwrap();
        let mut first_buf = Vec::new();
        let mut tmp = [0u8; 1024];
        loop {
            let r = cli_tls.read(&mut tmp).await.unwrap();
            assert!(r > 0, "unexpected EOF waiting for H2 response 1");
            first_buf.extend_from_slice(&tmp[..r]);
            if String::from_utf8_lossy(&first_buf).contains("h2-ech:/latest.json?ascending=false") {
                break;
            }
        }

        // Request 2 reuses the pooled H2 sender on the same upstream connection.
        cli_tls
            .write_all(b"GET /t/topic/123.json HTTP/1.1\r\nHost: linux.do\r\nConnection: close\r\n\r\n")
            .await
            .unwrap();
        let mut second_buf = Vec::new();
        cli_tls.read_to_end(&mut second_buf).await.unwrap();
        let second_text = String::from_utf8_lossy(&second_buf);
        assert!(
            second_text.contains("200 OK") && second_text.contains("h2-ech:/t/topic/123.json"),
            "unexpected pooled H2 response: {second_text}"
        );
        assert_eq!(
            up_accept_count.load(std::sync::atomic::Ordering::SeqCst),
            1,
            "expected second request to reuse the pooled H2 connection"
        );
        assert!(tls_upstream::ech_handshake_count() >= 1);
        assert!(tls_upstream::last_ech_accepted());

        server.stop();
    }

    #[tokio::test]
    async fn test_dead_pooled_h2_evicts_and_transparently_reconnects() {
        let ech_cfg = base64::engine::general_purpose::STANDARD
            .decode("/g0AOgAAIAAguy8p4+MFfgQZ1S/F9EEYd2+NthzqT992B5uTYGxaYkgACAABAAEAAQADAAdlY2guY29tAAA=")
            .unwrap();
        let ech_key = base64::engine::general_purpose::STANDARD
            .decode("nx3rjPVWNbMfaatuM+8AnorXKBktqxI3lEvHfD+pH4I=")
            .unwrap();
        let ech_list = base64::engine::general_purpose::STANDARD
            .decode("AD7+DQA6AAAgACC7Lynj4wV+BBnVL8X0QRh3b422HOpP33YHm5NgbFpiSAAIAAEAAQABAAMAB2VjaC5jb20AAA==")
            .unwrap();

        let (up_ca_pem, up_ca_key) = ca::generate_root_ca_pem().unwrap();
        let up_ca = CaAuthority::from_pem(&up_ca_pem, &up_ca_key).unwrap();
        let up_leaf = up_ca.leaf_for_host("linux.do").unwrap();

        let mut up_acc_builder = SslAcceptor::mozilla_intermediate_v5(SslMethod::tls_server()).unwrap();
        up_acc_builder.set_certificate(&up_leaf.cert).unwrap();
        up_acc_builder.set_private_key(&up_leaf.key).unwrap();
        up_acc_builder.set_alpn_select_callback(|_, protos| {
            let mut p = 0usize;
            while p < protos.len() {
                let l = protos[p] as usize;
                p += 1;
                if p + l <= protos.len() && &protos[p..p + l] == b"h2" {
                    return Ok(&protos[p..p + l]);
                }
                p += l;
            }
            Err(boring2::ssl::AlpnError::NOACK)
        });
        let up_acceptor = Arc::new(up_acc_builder.build());
        unsafe {
            boring_sys2::init();
            let hpke_key = boring_sys2::EVP_HPKE_KEY_new();
            assert_eq!(
                boring_sys2::EVP_HPKE_KEY_init(
                    hpke_key,
                    boring_sys2::EVP_hpke_x25519_hkdf_sha256(),
                    ech_key.as_ptr(),
                    ech_key.len(),
                ),
                1
            );
            let ech_keys = boring_sys2::SSL_ECH_KEYS_new();
            assert_eq!(
                boring_sys2::SSL_ECH_KEYS_add(
                    ech_keys,
                    1,
                    ech_cfg.as_ptr(),
                    ech_cfg.len(),
                    hpke_key,
                ),
                1
            );
            assert_eq!(
                boring_sys2::SSL_CTX_set1_ech_keys(up_acceptor.context().as_ptr() as *mut _, ech_keys),
                1
            );
            boring_sys2::SSL_ECH_KEYS_free(ech_keys);
            boring_sys2::EVP_HPKE_KEY_free(hpke_key);
        }

        let upstream_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let upstream_addr = upstream_listener.local_addr().unwrap();
        let up_accept_count = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let up_accept_count_srv = Arc::clone(&up_accept_count);
        let kill_conn1 = Arc::new(tokio::sync::Notify::new());
        let kill_conn1_srv = Arc::clone(&kill_conn1);

        tokio::spawn(async move {
            while let Ok((tcp, _)) = upstream_listener.accept().await {
                let conn_idx = up_accept_count_srv.fetch_add(1, std::sync::atomic::Ordering::SeqCst) + 1;
                let ssl = Ssl::new(up_acceptor.context()).unwrap();
                let mut tls = SslStream::new(ssl, tcp).unwrap();
                Pin::new(&mut tls).accept().await.unwrap();
                let svc = hyper::service::service_fn(move |req: hyper::Request<hyper::body::Incoming>| async move {
                    let pq = req.uri().path_and_query().map(|v| v.as_str()).unwrap_or("/");
                    let body = format!("conn{conn_idx}:{pq}");
                    Ok::<_, std::convert::Infallible>(hyper::Response::new(
                        http_body_util::Full::new(bytes::Bytes::from(body)),
                    ))
                });
                let conn_kill = Arc::clone(&kill_conn1_srv);
                tokio::spawn(async move {
                    let h2_fut = hyper::server::conn::http2::Builder::new(hyper_util::rt::TokioExecutor::new())
                        .serve_connection(hyper_util::rt::TokioIo::new(tls), svc);
                    if conn_idx == 1 {
                        tokio::select! {
                            _ = h2_fut => {}
                            _ = conn_kill.notified() => {}
                        }
                    } else {
                        let _ = h2_fut.await;
                    }
                });
            }
        });

        let (gw_ca_pem, gw_ca_key) = ca::generate_root_ca_pem().unwrap();
        let gw_ca = CaAuthority::from_pem(&gw_ca_pem, &gw_ca_key).unwrap();
        let gw_ca_cert = boring2::x509::X509::from_pem(gw_ca_pem.as_bytes()).unwrap();
        let doh = DohResolver::new().unwrap();
        let up_ca_x509 = boring2::x509::X509::from_pem(up_ca_pem.as_bytes()).unwrap();
        let upstream = UpstreamPool::new_with_test_override(
            Arc::clone(&doh),
            tls_upstream::TestTargetOverride {
                addr: upstream_addr,
                ech_config_list: ech_list,
                ca_cert: up_ca_x509,
            },
        )
        .unwrap();

        let mut server = GatewayServer::start(Arc::clone(&gw_ca), doh, upstream)
            .await
            .unwrap();
        let proxy_addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), server.listen_port);

        let mut client_tcp = tokio::net::TcpStream::connect(proxy_addr).await.unwrap();
        client_tcp
            .write_all(b"CONNECT linux.do:443 HTTP/1.1\r\nHost: linux.do:443\r\n\r\n")
            .await
            .unwrap();
        let mut conn_resp = vec![0u8; 64];
        let n = client_tcp.read(&mut conn_resp).await.unwrap();
        assert!(std::str::from_utf8(&conn_resp[..n]).unwrap().contains("200 Connection Established"));

        let mut cli_builder = SslConnector::builder(SslMethod::tls_client()).unwrap();
        cli_builder.set_verify(SslVerifyMode::PEER);
        let mut cli_store = X509StoreBuilder::new().unwrap();
        cli_store.add_cert(gw_ca_cert).unwrap();
        cli_builder.set_verify_cert_store(cli_store.build()).unwrap();
        let _ = cli_builder.set_alpn_protos(b"\x08http/1.1");
        let ssl = cli_builder.build().configure().unwrap().into_ssl("linux.do").unwrap();
        let mut cli_tls = SslStream::new(ssl, client_tcp).unwrap();
        Pin::new(&mut cli_tls).connect().await.unwrap();

        // Request 1 succeeds on conn 1.
        cli_tls
            .write_all(b"GET /step1 HTTP/1.1\r\nHost: linux.do\r\nProxy-Connection: keep-alive\r\n\r\n")
            .await
            .unwrap();
        let mut first_buf = Vec::new();
        let mut tmp = [0u8; 1024];
        loop {
            let r = cli_tls.read(&mut tmp).await.unwrap();
            assert!(r > 0);
            first_buf.extend_from_slice(&tmp[..r]);
            if String::from_utf8_lossy(&first_buf).contains("conn1:/step1") {
                break;
            }
        }

        // Drop upstream connection #1 completely before Request 2.
        kill_conn1.notify_waiters();
        tokio::time::sleep(std::time::Duration::from_millis(40)).await;

        // Request 2 evicts dead conn 1 and reconnects transparently on conn 2.
        cli_tls
            .write_all(b"GET /challenge HTTP/1.1\r\nHost: linux.do\r\nConnection: close\r\n\r\n")
            .await
            .unwrap();
        let mut second_buf = Vec::new();
        cli_tls.read_to_end(&mut second_buf).await.unwrap();
        let second_text = String::from_utf8_lossy(&second_buf);
        assert!(
            second_text.contains("200 OK") && second_text.contains("conn2:/challenge"),
            "expected transparent reconnect onto conn 2, got: {second_text}"
        );
        assert_eq!(
            up_accept_count.load(std::sync::atomic::Ordering::SeqCst),
            2,
            "expected dead pooled connection eviction and reconnect to conn 2"
        );

        server.stop();
    }

    #[tokio::test]
    async fn test_system_passthrough_mode_bypasses_mitm_and_chains_http_proxy() {
        let (gw_ca_pem, gw_ca_key) = ca::generate_root_ca_pem().unwrap();
        let gw_ca = CaAuthority::from_pem(&gw_ca_pem, &gw_ca_key).unwrap();
        assert!(!gw_ca.spki_sha256_base64().unwrap().is_empty());

        let doh = DohResolver::new().unwrap();
        let upstream = UpstreamPool::new(Arc::clone(&doh)).unwrap();
        let mut server = GatewayServer::start(Arc::clone(&gw_ca), doh, upstream)
            .await
            .unwrap();

        // Spawn a mock upstream HTTP proxy (simulating Clash / V2RayN) and echo target.
        let upstream_proxy_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let upstream_proxy_addr = upstream_proxy_listener.local_addr().unwrap();
        let captured_connect = Arc::new(Mutex::new(String::new()));
        let captured_clone = Arc::clone(&captured_connect);

        tokio::spawn(async move {
            let (mut sock, _) = upstream_proxy_listener.accept().await.unwrap();
            let mut buf = [0u8; 512];
            let n = sock.read(&mut buf).await.unwrap();
            *captured_clone.lock() = String::from_utf8_lossy(&buf[..n]).to_string();
            sock.write_all(b"HTTP/1.1 200 Connection Established\r\n\r\n")
                .await
                .unwrap();
            let mut payload = [0u8; 64];
            let pn = sock.read(&mut payload).await.unwrap();
            let mut reply = b"PROXY_ECHO:".to_vec();
            reply.extend_from_slice(&payload[..pn]);
            sock.write_all(&reply).await.unwrap();
        });

        server.set_route_mode(proxy::GatewayRouteMode::SystemPassThrough {
            http_proxy: Some(upstream_proxy_addr),
        });
        assert!(matches!(
            server.route_mode(),
            proxy::GatewayRouteMode::SystemPassThrough { .. }
        ));

        let proxy_addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), server.listen_port);
        let mut cli = tokio::net::TcpStream::connect(proxy_addr).await.unwrap();
        // Notice: even for MITM domain `linux.do:443`, SystemPassThrough MUST tunnel transparently without TLS MITM!
        cli.write_all(b"CONNECT linux.do:443 HTTP/1.1\r\nHost: linux.do:443\r\n\r\n")
            .await
            .unwrap();
        let mut hdr = [0u8; 64];
        let hn = cli.read(&mut hdr).await.unwrap();
        assert!(std::str::from_utf8(&hdr[..hn]).unwrap().contains("200 Connection Established"));

        cli.write_all(b"HELLO_ECH_BYPASS").await.unwrap();
        let mut echoed = [0u8; 64];
        let en = cli.read(&mut echoed).await.unwrap();
        assert_eq!(&echoed[..en], b"PROXY_ECHO:HELLO_ECH_BYPASS");
        assert!(captured_connect.lock().starts_with("CONNECT linux.do:443 HTTP/1.1"));

        server.stop();
    }
}
