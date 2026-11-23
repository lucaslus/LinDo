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
static BYPASS_VPN: AtomicBool = AtomicBool::new(true);
static NETWORK_GEN: AtomicU64 = AtomicU64::new(1);
static LAST_BOUND_PHYSICAL: AtomicBool = AtomicBool::new(false);
static LAST_BIND_ERRNO: AtomicI32 = AtomicI32::new(0);

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

/// Opens a TCP connection to `addr`. When Android VPN bypass is enabled and a
/// physical non-VPN `net_handle_t` is known, binds the non-loopback socket to
/// the physical interface via `android_setsocknetwork`, falling back cleanly once
/// to the system default route if the bound path fails.
pub async fn connect_tcp(addr: SocketAddr, timeout_dur: Duration) -> io::Result<(TcpStream, bool)> {
    let handle = physical_handle();
    let should_bind = !addr.ip().is_loopback() && bypass_vpn() && handle != 0;

    if should_bind {
        match connect_with_handle(addr, timeout_dur, Some(handle)).await {
            Ok((stream, bound)) => {
                LAST_BOUND_PHYSICAL.store(bound, Ordering::Relaxed);
                return Ok((stream, bound));
            }
            Err(_bound_err) => {
                // Fall back once to system routing (e.g., lockdown VPN or interface transition).
                let (stream, _) = connect_with_handle(addr, timeout_dur, None).await?;
                LAST_BOUND_PHYSICAL.store(false, Ordering::Relaxed);
                return Ok((stream, false));
            }
        }
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
    Ok((stream, bound))
}
