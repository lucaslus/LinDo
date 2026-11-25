use std::io;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicU64, Ordering};
use std::time::Duration;

use tokio::net::{TcpSocket, TcpStream};
use tokio::time::timeout;

#[cfg(target_os = "android")]
use std::os::unix::io::AsRawFd;

#[cfg(target_os = "android")]
#[link(name = "android")]
extern "C" {
    fn android_setsocknetwork(
        network: u64,
        fd: std::os::raw::c_int,
    ) -> std::os::raw::c_int;
}

static PHYSICAL_NET_HANDLE: AtomicU64 = AtomicU64::new(0);
static BYPASS_VPN: AtomicBool = AtomicBool::new(false);
static NETWORK_GEN: AtomicU64 = AtomicU64::new(1);
static LAST_BOUND_PHYSICAL: AtomicBool = AtomicBool::new(false);
static LAST_BIND_ERRNO: AtomicI32 = AtomicI32::new(0);

#[cfg(test)]
static TEST_FORCE_BIND_ERRNO: AtomicI32 = AtomicI32::new(0);
#[cfg(test)]
static TEST_UNBOUND_CONNECT_COUNT: AtomicU64 = AtomicU64::new(0);

#[cfg(test)]
pub fn set_test_force_bind_errno(errno: i32) {
    TEST_FORCE_BIND_ERRNO.store(errno, Ordering::SeqCst);
}

#[cfg(test)]
pub fn reset_test_unbound_connect_count() {
    TEST_UNBOUND_CONNECT_COUNT.store(0, Ordering::SeqCst);
}

#[cfg(test)]
pub fn test_unbound_connect_count() -> u64 {
    TEST_UNBOUND_CONNECT_COUNT.load(Ordering::SeqCst)
}

pub fn physical_handle() -> u64 {
    PHYSICAL_NET_HANDLE.load(Ordering::Acquire)
}

pub fn bypass_vpn() -> bool {
    BYPASS_VPN.load(Ordering::Acquire)
}

pub fn network_gen() -> u64 {
    NETWORK_GEN.load(Ordering::Acquire)
}

pub fn last_bound_physical() -> bool {
    LAST_BOUND_PHYSICAL.load(Ordering::Relaxed)
}

pub fn last_bind_errno() -> i32 {
    LAST_BIND_ERRNO.load(Ordering::Relaxed)
}

/// Probes whether Android `netd` (`FwmarkServer`) permits this UID to bind sockets
/// directly to `net_handle`. When an active VPN enforces global lockdown (`allowBypass = false`),
/// `android_setsocknetwork` fails immediately with `EPERM` without any network I/O.
pub fn can_bind_network(net_handle: u64) -> bool {
    if net_handle == 0 {
        LAST_BIND_ERRNO.store(0, Ordering::Relaxed);
        return false;
    }
    #[cfg(target_os = "android")]
    {
        let Ok(sock) = socket2::Socket::new(
            socket2::Domain::IPV4,
            socket2::Type::STREAM,
            Some(socket2::Protocol::TCP),
        ) else {
            return false;
        };
        let rc = unsafe { android_setsocknetwork(net_handle, sock.as_raw_fd()) };
        if rc == 0 {
            LAST_BIND_ERRNO.store(0, Ordering::Relaxed);
            true
        } else {
            let err = io::Error::last_os_error().raw_os_error().unwrap_or(-1);
            LAST_BIND_ERRNO.store(err, Ordering::Relaxed);
            false
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = net_handle;
        LAST_BIND_ERRNO.store(0, Ordering::Relaxed);
        true
    }
}

/// Updates the physical Android network handle and VPN bypass flag, unconditionally bumping
/// `NETWORK_GEN` so topology switches (including VPN on/off) flush pooled H2 connections and DNS caches.
pub fn update_network(net_handle: u64, bypass: bool) -> bool {
    let prev_handle = PHYSICAL_NET_HANDLE.swap(net_handle, Ordering::AcqRel);
    let prev_bypass = BYPASS_VPN.swap(bypass, Ordering::AcqRel);
    NETWORK_GEN.fetch_add(1, Ordering::AcqRel);
    if !bypass || net_handle == 0 {
        LAST_BOUND_PHYSICAL.store(false, Ordering::Relaxed);
    }
    let _ = (prev_handle, prev_bypass);
    true
}

/// Opens a TCP connection to `addr`. When Android VPN bypass is enabled,
/// non-loopback sockets MUST bind to the physical non-VPN `net_handle_t` via
/// `android_setsocknetwork` and NEVER silently fall back to the default `tun0` VPN
/// route on failure (preventing cross-NIC exit IP split between `linux.do` and Turnstile).
pub async fn connect_tcp(addr: SocketAddr, timeout_dur: Duration) -> io::Result<(TcpStream, bool)> {
    if !addr.ip().is_loopback() && bypass_vpn() {
        let handle = physical_handle();
        if handle == 0 {
            LAST_BOUND_PHYSICAL.store(false, Ordering::Relaxed);
            return Err(io::Error::new(
                io::ErrorKind::AddrNotAvailable,
                "physical network handle unavailable while bypass_vpn is enabled",
            ));
        }
        return match connect_with_handle(addr, timeout_dur, Some(handle)).await {
            Ok((stream, bound)) => {
                LAST_BOUND_PHYSICAL.store(bound, Ordering::Relaxed);
                Ok((stream, bound))
            }
            Err(bound_err) => {
                LAST_BOUND_PHYSICAL.store(false, Ordering::Relaxed);
                Err(bound_err)
            }
        };
    }

    let (stream, _) = connect_with_handle(addr, timeout_dur, None).await?;
    LAST_BOUND_PHYSICAL.store(false, Ordering::Relaxed);
    Ok((stream, false))
}

async fn connect_with_handle(
    addr: SocketAddr,
    timeout_dur: Duration,
    #[allow(unused_variables)] net_handle: Option<u64>,
) -> io::Result<(TcpStream, bool)> {
    #[cfg(test)]
    if !addr.ip().is_loopback() {
        if net_handle.is_some() {
            let forced_errno = TEST_FORCE_BIND_ERRNO.load(Ordering::SeqCst);
            if forced_errno != 0 {
                LAST_BIND_ERRNO.store(forced_errno, Ordering::Relaxed);
                return Err(io::Error::from_raw_os_error(forced_errno));
            }
        } else {
            TEST_UNBOUND_CONNECT_COUNT.fetch_add(1, Ordering::SeqCst);
        }
    }

    let sock = if addr.is_ipv4() {
        TcpSocket::new_v4()?
    } else {
        TcpSocket::new_v6()?
    };
    let _ = sock.set_nodelay(true);

    #[cfg_attr(not(target_os = "android"), allow(unused_mut))]
    let mut bound = false;
    #[cfg(target_os = "android")]
    if let Some(handle) = net_handle {
        let rc = unsafe { android_setsocknetwork(handle, sock.as_raw_fd()) };
        if rc == 0 {
            LAST_BIND_ERRNO.store(0, Ordering::Relaxed);
            bound = true;
        } else {
            let os_err = io::Error::last_os_error();
            LAST_BIND_ERRNO.store(os_err.raw_os_error().unwrap_or(-1), Ordering::Relaxed);
            return Err(os_err);
        }
    }

    let stream = timeout(timeout_dur, sock.connect(addr))
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "tcp connect timeout"))??;
    let _ = stream.set_nodelay(true);
    let keepalive = socket2::TcpKeepalive::new()
        .with_time(Duration::from_secs(30))
        .with_interval(Duration::from_secs(10));
    let _ = socket2::SockRef::from(&stream).set_tcp_keepalive(&keepalive);
    Ok((stream, bound))
}
