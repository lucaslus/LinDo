package do_.linux.lindo.core.network

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import do_.linux.lindo.core.runUiSafeStateWrite

enum class EchRoutingMode(
    val prefValue: String,
    val labelZh: String,
    val descZh: String
) {
    SMART_AUTO(
        prefValue = "smart_auto",
        labelZh = "智能推荐",
        descZh = "无代理时自动启用 ECH 免代理直连，开启代理或 VPN 时自动让行；切换实时生效，无需重启"
    ),
    ECH_PRIORITY(
        prefValue = "ech_priority",
        labelZh = "直连优先",
        descZh = "优先通过内置 ECH + 国内 DoH 物理直连规避劣质代理弹盾，VPN 禁止绕过时自动回退；切换实时生效，无需重启"
    ),
    SYSTEM_DEFAULT(
        prefValue = "system_default",
        labelZh = "系统默认",
        descZh = "关闭内置 ECH 直连，完全沿用系统默认 DNS 与网络栈；切换实时生效，无需重启"
    );

    companion object {
        fun fromPref(raw: String?): EchRoutingMode =
            when (raw?.trim()?.lowercase()) {
                "smart_auto", "smart_bypass", "", null -> SMART_AUTO
                "ech_priority", "direct_only" -> ECH_PRIORITY
                "system_default" -> SYSTEM_DEFAULT
                else -> SMART_AUTO
            }
    }
}

data class EchRuntimeSnapshot(
    val supported: Boolean = false,
    val active: Boolean = false,
    val mode: EchRoutingMode = EchRoutingMode.SMART_AUTO,
    val listenPort: Int = 0,
    val stickyIp: String = "",
    val bypassVpnRequested: Boolean = false,
    val bypassVpnBound: Boolean = false,
    val physicalIface: String = "",
    val vpnActive: Boolean = false,
    val vpnLockdownYielded: Boolean = false,
    val lastBindErrno: Int = 0,
    val echHandshakeCount: Long = 0L,
    val lastEchAccepted: Boolean = false,
    val lastError: String = ""
)

object EchRoutingPolicy {
    var mode by mutableStateOf(EchRoutingMode.SMART_AUTO)
        private set

    var snapshot by mutableStateOf(EchRuntimeSnapshot())
        private set

    @Volatile
    var onSelectMode: ((EchRoutingMode) -> Unit)? = null

    @Volatile
    var leafVerifier: ((derBytes: ByteArray, expectedHost: String) -> Boolean)? = null

    fun selectMode(next: EchRoutingMode) {
        runUiSafeStateWrite {
            mode = next
        }
        onSelectMode?.invoke(next)
    }

    fun publishSnapshot(next: EchRuntimeSnapshot) {
        runUiSafeStateWrite {
            mode = next.mode
            snapshot = next
        }
    }

    fun resetForTest() {
        onSelectMode = null
        leafVerifier = null
        runUiSafeStateWrite {
            mode = EchRoutingMode.SMART_AUTO
            snapshot = EchRuntimeSnapshot()
        }
    }

    /**
     * Matches exact hosts routed through the local BoringSSL ECH terminator.
     * `challenges.cloudflare.com` and third-party domains intentionally return `false`
     * so Turnstile always runs end-to-end without TLS termination.
     */
    fun shouldMitmHost(host: String?): Boolean {
        val clean = host?.trim()?.trimEnd('.')?.lowercase().orEmpty()
        if (clean.isEmpty()) return false
        return clean == "linux.do" ||
            clean.endsWith(".linux.do") ||
            clean == "ldstatic.com" ||
            clean.endsWith(".ldstatic.com") ||
            clean == "idcflare.com" ||
            clean.endsWith(".idcflare.com")
    }

    fun verifyGatewayLeaf(derBytes: ByteArray, expectedHost: String?): Boolean {
        val clean = expectedHost?.trim()?.trimEnd('.')?.lowercase().orEmpty()
        if (!snapshot.active || !shouldMitmHost(clean) || derBytes.isEmpty()) {
            return false
        }
        return leafVerifier?.invoke(derBytes, clean) == true
    }

    fun statusSubtitle(): String {
        val snap = snapshot
        if (!snap.supported) return "当前平台不适用"
        if (snap.lastError.isNotBlank() && !snap.active) {
            return "网关异常：${snap.lastError.take(48)}"
        }
        if (snap.mode == EchRoutingMode.SYSTEM_DEFAULT) {
            return "已关闭 · 使用系统默认网络"
        }
        if (snap.mode == EchRoutingMode.SMART_AUTO && (snap.vpnActive || snap.vpnLockdownYielded) && !snap.active) {
            return "检测到代理/VPN · 已自动使用代理通道（关闭代理后自动启用直连）"
        }
        if (snap.mode == EchRoutingMode.ECH_PRIORITY && snap.vpnActive && snap.vpnLockdownYielded && !snap.active) {
            return "当前 VPN 禁止绕过 · 已自动回退代理通道（在 VPN 分应用绕过 Lindo 可固定直连）"
        }
        if (!snap.active) {
            return "正在初始化 ECH 通道…"
        }
        val netPart = when {
            snap.bypassVpnRequested && snap.vpnActive && snap.physicalIface.isNotBlank() ->
                "穿透 ${snap.physicalIface}"
            snap.physicalIface.isNotBlank() -> snap.physicalIface
            else -> "直连"
        }
        val ipPart = if (snap.stickyIp.isNotBlank()) " · 固定边缘 ${snap.stickyIp}" else ""
        return "已开启直连 ($netPart$ipPart)"
    }

    fun toDiagnosticLine(): String {
        val s = snapshot
        if (!s.supported) return "unsupported"
        return "mode=${s.mode.prefValue} active=${s.active} port=${s.listenPort} " +
            "stickyIp=${s.stickyIp.ifBlank { "pending" }} vpn=${s.vpnActive} " +
            "bypassReq=${s.bypassVpnRequested} bypassBound=${s.bypassVpnBound} " +
            "vpnYield=${s.vpnLockdownYielded} bindErrno=${s.lastBindErrno} " +
            "iface=${s.physicalIface.ifBlank { "none" }} echCount=${s.echHandshakeCount} " +
            "lastEch=${s.lastEchAccepted}" +
            if (s.lastError.isNotBlank()) " err=${s.lastError}" else ""
    }
}
