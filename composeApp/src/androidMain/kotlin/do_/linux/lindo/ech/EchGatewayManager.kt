package do_.linux.lindo.ech

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import do_.linux.lindo.core.diagnostics.DiagnosticCategory
import do_.linux.lindo.core.diagnostics.LinDoDiagnosticLogger
import do_.linux.lindo.core.network.EchRoutingMode
import do_.linux.lindo.core.network.EchRoutingPolicy
import do_.linux.lindo.core.network.EchRuntimeSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal object NativeEchBridge {
    val loaded: Boolean
    val loadError: String

    init {
        var ok = false
        var errMsg = ""
        try {
            runCatching { System.loadLibrary("c++_shared") }
            System.loadLibrary("lindo_ech")
            ok = true
        } catch (t: Throwable) {
            errMsg = t.message ?: t::class.java.simpleName
        }
        loaded = ok
        loadError = errMsg
    }

    external fun nativeGenerateCaJson(): String
    external fun nativeStartGateway(certPem: String, keyPem: String, netHandle: Long, bypassVpn: Boolean): Int
    external fun nativeStopGateway()
    external fun nativeUpdateNetwork(netHandle: Long, bypassVpn: Boolean)
    external fun nativeCanBindNetwork(netHandle: Long): Boolean
    external fun nativeVerifyLeafDer(leafDer: ByteArray, host: String): Boolean
    external fun nativeRotateStickyIp()
    external fun nativeGetStatusJson(): String
}

object EchGatewayManager {
    private const val PREF_NAME = "lindo_auth_prefs"
    private const val PREF_KEY_MODE = "ech_routing_mode"
    private const val CA_DIR = "ech_ca"
    private const val CA_CERT_FILE = "ca_cert.pem"
    private const val CA_KEY_FILE = "ca_key.pem"

    private data class PhysicalNetInfo(
        val handle: Long,
        val rank: Int,
        val ifaceName: String
    )

    private val initialized = AtomicBoolean(false)
    private val reconcileExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "lindo-ech-ctrl").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateLock = Any()
    private val reconcileLock = Any()

    private lateinit var appContext: Context
    private var connectivityManager: ConnectivityManager? = null
    private var physicalCallback: ConnectivityManager.NetworkCallback? = null
    private var defaultCallback: ConnectivityManager.NetworkCallback? = null
    private var vpnCallback: ConnectivityManager.NetworkCallback? = null
    private var routeChangedListener: (() -> Unit)? = null

    private val physicalNetworks = LinkedHashMap<Long, PhysicalNetInfo>()
    private var currentMode: EchRoutingMode = EchRoutingMode.SMART_AUTO
    private var lastAppliedMode: EchRoutingMode? = null
    private var vpnActive: Boolean = false
    private var vpnLockdownYielded: Boolean = false
    private var activeGatewayPort: Int = 0
    private var lastAppliedPort: Int = 0
    private var lastAppliedHandle: Long = -1L
    private var lastAppliedBypass: Boolean = false
    private var lastAppliedVpn: Boolean = false
    private var proxyOverrideActive: Boolean = false
    private val initialReconcileDone = AtomicBoolean(false)
    private val routeStackFlipped = AtomicBoolean(false)
    private val notifyRouteOnProxyComplete = AtomicBoolean(false)
    @Volatile
    private var pendingProxyDeferred: CompletableDeferred<Unit>? = null
    private var cachedCertPem: String = ""
    private var cachedKeyPem: String = ""

    fun initialize(context: Context, onRouteChanged: (() -> Unit)? = null) {
        if (!initialized.compareAndSet(false, true)) {
            routeChangedListener = onRouteChanged
            refreshStatusAsync()
            return
        }
        appContext = context.applicationContext
        routeChangedListener = onRouteChanged

        if (!NativeEchBridge.loaded) {
            LinDoDiagnosticLogger.error(
                DiagnosticCategory.SYSTEM,
                "EchGateway",
                "Failed to load liblindo_ech.so: ${NativeEchBridge.loadError}"
            )
            EchRoutingPolicy.publishSnapshot(
                EchRuntimeSnapshot(
                    supported = true,
                    active = false,
                    mode = EchRoutingMode.SYSTEM_DEFAULT,
                    lastError = NativeEchBridge.loadError.ifBlank { "native library load failed" }
                )
            )
            return
        }

        val prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        currentMode = EchRoutingMode.fromPref(prefs.getString(PREF_KEY_MODE, null))

        EchRoutingPolicy.leafVerifier = { der, host ->
            NativeEchBridge.loaded && NativeEchBridge.nativeVerifyLeafDer(der, host)
        }
        EchRoutingPolicy.onSelectMode = { nextMode ->
            onModeSelected(nextMode)
        }

        connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        seedInitialNetworkSnapshot()

        // Ensure native gateway & WebView ProxyController are reconciled synchronously before callbacks or WebView init.
        reconcileSync()
        initialReconcileDone.set(true)
        registerNetworkCallbacks()
        scheduleDelayedStatusRefresh()
    }

    fun refreshStatusAsync() {
        if (!initialized.get() || !NativeEchBridge.loaded) return
        reconcileExecutor.execute {
            runCatching {
                seedInitialNetworkSnapshot()
                reconcileInternal()
            }
        }
    }

    fun rotateStickyIpAsync() {
        if (!initialized.get() || !NativeEchBridge.loaded) return
        reconcileExecutor.execute {
            runCatching {
                NativeEchBridge.nativeRotateStickyIp()
                publishStatusFromNative()
            }
        }
    }

    fun consumeRouteStackFlip(): Boolean = routeStackFlipped.getAndSet(false)

    fun hasPendingProxyOverride(): Boolean = pendingProxyDeferred?.isCompleted == false

    suspend fun awaitPendingProxyOverride(timeoutMs: Long = 1_500L) {
        if (pendingProxyDeferred == null) return
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                val pending = pendingProxyDeferred ?: break
                if (pending.isCompleted) break
                pending.await()
            }
        }
    }

    suspend fun awaitReconcileAndProxyReady(timeoutMs: Long = 3_500L) {
        if (!initialized.get() || !NativeEchBridge.loaded) return
        withTimeoutOrNull(timeoutMs) {
            val reconcileDone = CompletableDeferred<Unit>()
            reconcileExecutor.execute {
                try {
                    seedInitialNetworkSnapshot()
                    reconcileInternal()
                } catch (_: Throwable) {
                } finally {
                    reconcileDone.complete(Unit)
                }
            }
            reconcileDone.await()
            while (true) {
                val pending = pendingProxyDeferred ?: break
                if (pending.isCompleted) break
                pending.await()
            }
        }
    }

    fun verifyWebViewSslError(error: SslError?, urlHost: String?): Boolean {
        if (error == null || !NativeEchBridge.loaded) {
            return false
        }
        val activeNow = EchRoutingPolicy.snapshot.active
        val candidateHost = urlHost?.trim()?.takeIf { it.isNotEmpty() }
            ?: error.url?.let { runCatching { Uri.parse(it).host }.getOrNull() }.orEmpty()
        val mitmMatch = EchRoutingPolicy.shouldMitmHost(candidateHost)
        val derBytes = extractDerBytes(error.certificate)
        val verified = activeNow && mitmMatch && derBytes != null &&
            EchRoutingPolicy.verifyGatewayLeaf(derBytes, candidateHost)
        if (!verified) {
            LinDoDiagnosticLogger.warn(
                DiagnosticCategory.NETWORK,
                "EchSslVerify",
                "Rejected WebView SslError host=$candidateHost active=$activeNow mitm=$mitmMatch derLen=${derBytes?.size ?: -1} primaryErr=${error.primaryError} url=${LinDoDiagnosticLogger.sanitizeUrl(error.url.orEmpty())}"
            )
        }
        return verified
    }

    private fun extractDerBytes(cert: SslCertificate?): ByteArray? {
        if (cert == null) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cert.x509Certificate?.let { x509 ->
                runCatching { x509.encoded }.getOrNull()?.let { return it }
            }
        }
        return runCatching {
            val saved = SslCertificate.saveState(cert)
            saved?.getByteArray("x509-certificate")
        }.getOrNull()
    }

    private fun onModeSelected(nextMode: EchRoutingMode) {
        if (!initialized.get()) return
        appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_KEY_MODE, nextMode.prefValue)
            .apply()
        val changed = synchronized(stateLock) {
            if (currentMode == nextMode) {
                false
            } else {
                currentMode = nextMode
                true
            }
        }
        if (changed) {
            LinDoDiagnosticLogger.info(
                DiagnosticCategory.NETWORK,
                "EchGateway",
                "User switched ECH routing mode -> ${nextMode.prefValue}"
            )
            reconcileExecutor.execute {
                runCatching {
                    seedInitialNetworkSnapshot()
                    reconcileInternal(forceModeRefresh = true)
                }
            }
            scheduleDelayedStatusRefresh()
        }
    }

    private fun reconcileSync() {
        runCatching {
            reconcileInternal()
        }.onFailure { err ->
            LinDoDiagnosticLogger.error(
                DiagnosticCategory.SYSTEM,
                "EchGateway",
                "Initial ECH gateway setup failed: ${err.message ?: err::class.java.simpleName}"
            )
        }
    }

    private fun scheduleDelayedStatusRefresh() {
        for (delayMs in longArrayOf(650L, 2200L, 5500L)) {
            mainHandler.postDelayed({
                if (initialized.get() && NativeEchBridge.loaded) {
                    reconcileExecutor.execute { runCatching { publishStatusFromNative() } }
                }
            }, delayMs)
        }
    }

    private fun registerNetworkCallbacks() {
        val cm = connectivityManager ?: return
        val physReq = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        val pCb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val iface = runCatching { cm.getLinkProperties(network)?.interfaceName.orEmpty() }.getOrDefault("")
                recordPhysicalNetwork(network, caps, iface)
                reconcileAsyncIfChanged()
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull() ?: return
                recordPhysicalNetwork(network, caps, lp.interfaceName.orEmpty())
                reconcileAsyncIfChanged()
            }

            override fun onLost(network: Network) {
                synchronized(stateLock) {
                    physicalNetworks.remove(network.networkHandle)
                }
                reconcileAsyncIfChanged()
            }
        }
        physicalCallback = pCb
        runCatching { cm.registerNetworkCallback(physReq, pCb) }

        val dCb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || detectVpnForApp(cm)
                synchronized(stateLock) {
                    vpnActive = isVpn
                }
                reconcileAsyncIfChanged()
            }

            override fun onLost(network: Network) {
                seedInitialNetworkSnapshot()
                reconcileAsyncIfChanged()
            }
        }
        defaultCallback = dCb
        runCatching { cm.registerDefaultNetworkCallback(dCb) }

        val vpnReq = NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .build()
        val vCb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                seedInitialNetworkSnapshot()
                reconcileAsyncIfChanged()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                seedInitialNetworkSnapshot()
                reconcileAsyncIfChanged()
            }

            override fun onLost(network: Network) {
                seedInitialNetworkSnapshot()
                reconcileAsyncIfChanged()
            }
        }
        vpnCallback = vCb
        runCatching { cm.registerNetworkCallback(vpnReq, vCb) }
    }

    @Suppress("DEPRECATION")
    private fun detectVpnForApp(cm: ConnectivityManager): Boolean {
        val active = runCatching { cm.activeNetwork }.getOrNull()
        if (active != null) {
            return runCatching {
                cm.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }.getOrDefault(false)
        }
        return runCatching {
            cm.allNetworks.any { net ->
                val caps = cm.getNetworkCapabilities(net)
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private fun seedInitialNetworkSnapshot() {
        val cm = connectivityManager ?: return
        val isVpn = detectVpnForApp(cm)
        val nets = runCatching { cm.allNetworks }.getOrNull().orEmpty()
        synchronized(stateLock) {
            vpnActive = isVpn
            physicalNetworks.clear()
        }
        for (net in nets) {
            val caps = runCatching { cm.getNetworkCapabilities(net) }.getOrNull() ?: continue
            val iface = runCatching { cm.getLinkProperties(net)?.interfaceName.orEmpty() }.getOrDefault("")
            recordPhysicalNetwork(net, caps, iface)
        }
    }

    private fun recordPhysicalNetwork(network: Network, caps: NetworkCapabilities, iface: String) {
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        ) {
            synchronized(stateLock) {
                physicalNetworks.remove(network.networkHandle)
            }
            return
        }
        val rank = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 3
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 2
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
            else -> 0
        }
        if (rank <= 0) return
        val handle = network.networkHandle
        synchronized(stateLock) {
            physicalNetworks[handle] = PhysicalNetInfo(handle = handle, rank = rank, ifaceName = iface)
        }
    }

    private fun bestPhysicalLocked(): PhysicalNetInfo? =
        physicalNetworks.values.maxWithOrNull(compareBy<PhysicalNetInfo> { it.rank }.thenBy { it.ifaceName.isNotBlank() })

    private fun reconcileAsyncIfChanged() {
        reconcileExecutor.execute {
            runCatching { reconcileInternal() }
        }
    }

    private fun isSystemHttpProxySet(): Boolean =
        !System.getProperty("http.proxyHost").isNullOrBlank() ||
            !System.getProperty("https.proxyHost").isNullOrBlank()

    private fun reconcileInternal(forceModeRefresh: Boolean = false) = synchronized(reconcileLock) {
        val (mode, vpnNow, bestPhys) = synchronized(stateLock) {
            Triple(currentMode, vpnActive, bestPhysicalLocked())
        }
        val hasSysProxy = isSystemHttpProxySet()
        val hasProxyOrVpn = vpnNow || hasSysProxy
        val physHandle = bestPhys?.handle ?: 0L
        val canBindPhys = mode == EchRoutingMode.ECH_PRIORITY &&
            physHandle != 0L &&
            NativeEchBridge.nativeCanBindNetwork(physHandle)
        val (shouldRun, bypassVpn, yielded) = when (mode) {
            EchRoutingMode.SYSTEM_DEFAULT -> Triple(false, false, false)
            EchRoutingMode.SMART_AUTO ->
                if (hasProxyOrVpn) Triple(false, false, false)
                else Triple(true, false, false)
            EchRoutingMode.ECH_PRIORITY -> when {
                !vpnNow -> Triple(true, false, false)
                canBindPhys -> Triple(true, true, false)
                else -> Triple(false, false, true)
            }
        }
        val (prevYielded, prevMode) = synchronized(stateLock) {
            val pYield = vpnLockdownYielded
            val pMode = lastAppliedMode
            vpnLockdownYielded = yielded
            lastAppliedMode = mode
            pYield to pMode
        }
        val initDone = initialReconcileDone.get()
        val modeChanged = forceModeRefresh || (initDone && prevMode != null && prevMode != mode)

        if (shouldRun) {
            val (certPem, keyPem) = ensureCaMaterial()
            val needStart = synchronized(stateLock) { activeGatewayPort <= 0 }
            if (needStart) {
                val port = NativeEchBridge.nativeStartGateway(certPem, keyPem, physHandle, bypassVpn)
                if (port > 0) {
                    synchronized(stateLock) {
                        activeGatewayPort = port
                        lastAppliedHandle = physHandle
                        lastAppliedBypass = bypassVpn
                        lastAppliedVpn = vpnNow
                    }
                    publishStatusFromNative()
                    applyWebViewProxyOverride(port, notifyRouteChanged = initDone)
                    LinDoDiagnosticLogger.info(
                        DiagnosticCategory.NETWORK,
                        "EchGateway",
                        "Started BoringSSL ECH gateway on 127.0.0.1:$port (mode=${mode.prefValue}, vpn=$vpnNow, bypass=$bypassVpn, iface=${bestPhys?.ifaceName.orEmpty().ifBlank { "default" }})"
                    )
                    scheduleDelayedStatusRefresh()
                } else {
                    publishStatusFromNative()
                    if (initDone && modeChanged) {
                        dispatchRouteChangedWhenProxySettled()
                    }
                }
            } else {
                val (needNetUpdate, bypassChanged) = synchronized(stateLock) {
                    val bChanged = lastAppliedBypass != bypassVpn
                    if (lastAppliedHandle != physHandle ||
                        bChanged ||
                        lastAppliedVpn != vpnNow
                    ) {
                        lastAppliedHandle = physHandle
                        lastAppliedBypass = bypassVpn
                        lastAppliedVpn = vpnNow
                        true to bChanged
                    } else {
                        false to false
                    }
                }
                if (bypassChanged) {
                    routeStackFlipped.set(true)
                }
                if (needNetUpdate) {
                    NativeEchBridge.nativeUpdateNetwork(physHandle, bypassVpn)
                }
                if (modeChanged) {
                    NativeEchBridge.nativeRotateStickyIp()
                }
                publishStatusFromNative()
                val portNow = synchronized(stateLock) { activeGatewayPort }
                if (portNow > 0 && !proxyOverrideActive) {
                    applyWebViewProxyOverride(portNow, notifyRouteChanged = initDone)
                } else if (initDone && (needNetUpdate || modeChanged)) {
                    dispatchRouteChangedWhenProxySettled()
                }
                if (needNetUpdate || modeChanged) {
                    if (modeChanged) {
                        LinDoDiagnosticLogger.info(
                            DiagnosticCategory.NETWORK,
                            "EchGateway",
                            "Refreshed BoringSSL ECH gateway on 127.0.0.1:$portNow (mode=${mode.prefValue}, vpn=$vpnNow, bypass=$bypassVpn, iface=${bestPhys?.ifaceName.orEmpty().ifBlank { "default" }})"
                        )
                    }
                    scheduleDelayedStatusRefresh()
                }
            }
        } else {
            val wasRunning = synchronized(stateLock) {
                val r = activeGatewayPort > 0
                activeGatewayPort = 0
                lastAppliedHandle = -1L
                lastAppliedBypass = false
                lastAppliedVpn = vpnNow
                r
            }
            if (wasRunning || modeChanged) {
                NativeEchBridge.nativeRotateStickyIp()
            }
            if (yielded && (wasRunning || !prevYielded)) {
                LinDoDiagnosticLogger.warn(
                    DiagnosticCategory.NETWORK,
                    "EchGateway",
                    "Yielded ECH gateway to locked VPN tunnel (mode=${mode.prefValue}, iface=${bestPhys?.ifaceName.orEmpty().ifBlank { "none" }})"
                )
            } else if (wasRunning) {
                LinDoDiagnosticLogger.info(
                    DiagnosticCategory.NETWORK,
                    "EchGateway",
                    "Stopped BoringSSL ECH gateway (mode=${mode.prefValue}, vpn=$vpnNow)"
                )
            }
            if (proxyOverrideActive) {
                publishStatusFromNative()
                clearWebViewProxyOverride(
                    stopGatewayAfterClear = wasRunning,
                    notifyRouteChanged = initDone
                )
            } else {
                if (wasRunning) {
                    NativeEchBridge.nativeStopGateway()
                }
                publishStatusFromNative()
                if (initDone && (wasRunning || modeChanged)) {
                    dispatchRouteChangedWhenProxySettled()
                }
            }
        }
    }

    private fun ensureCaMaterial(): Pair<String, String> {
        synchronized(stateLock) {
            if (cachedCertPem.isNotBlank() && cachedKeyPem.isNotBlank()) {
                return cachedCertPem to cachedKeyPem
            }
        }
        val dir = File(appContext.filesDir, CA_DIR).apply { mkdirs() }
        val certFile = File(dir, CA_CERT_FILE)
        val keyFile = File(dir, CA_KEY_FILE)
        if (certFile.isFile && keyFile.isFile) {
            val existingCert = runCatching { certFile.readText(Charsets.UTF_8) }.getOrDefault("")
            val existingKey = runCatching { keyFile.readText(Charsets.UTF_8) }.getOrDefault("")
            if (existingCert.contains("BEGIN CERTIFICATE") && existingKey.contains("PRIVATE KEY")) {
                synchronized(stateLock) {
                    cachedCertPem = existingCert
                    cachedKeyPem = existingKey
                }
                return existingCert to existingKey
            }
        }
        val rawJson = NativeEchBridge.nativeGenerateCaJson()
        val parsed = JSONObject(rawJson)
        val certPem = parsed.getString("certPem")
        val keyPem = parsed.getString("keyPem")
        certFile.writeText(certPem, Charsets.UTF_8)
        keyFile.writeText(keyPem, Charsets.UTF_8)
        runCatching {
            keyFile.setReadable(false, false)
            keyFile.setReadable(true, true)
            keyFile.setWritable(false, false)
            keyFile.setWritable(true, true)
        }
        synchronized(stateLock) {
            cachedCertPem = certPem
            cachedKeyPem = keyPem
        }
        return certPem to keyPem
    }

    private fun runOnMainSyncOrPost(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun dispatchRouteChangedWhenProxySettled() {
        val pending = pendingProxyDeferred
        if (pending != null && !pending.isCompleted) {
            notifyRouteOnProxyComplete.set(true)
            if (pending.isCompleted && notifyRouteOnProxyComplete.getAndSet(false)) {
                routeChangedListener?.invoke()
            }
        } else {
            routeChangedListener?.invoke()
        }
    }

    private fun applyWebViewProxyOverride(port: Int, notifyRouteChanged: Boolean = false) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            if (notifyRouteChanged) {
                dispatchRouteChangedWhenProxySettled()
            }
            return
        }
        if (lastAppliedPort == port && proxyOverrideActive) {
            if (notifyRouteChanged) {
                dispatchRouteChangedWhenProxySettled()
            }
            return
        }
        if (!proxyOverrideActive) {
            routeStackFlipped.set(true)
        }
        lastAppliedPort = port
        proxyOverrideActive = true
        if (notifyRouteChanged) {
            notifyRouteOnProxyComplete.set(true)
        }
        val prevDeferred = pendingProxyDeferred
        val deferred = CompletableDeferred<Unit>()
        pendingProxyDeferred = deferred
        val executor = ContextCompat.getMainExecutor(appContext)
        runOnMainSyncOrPost {
            runCatching {
                val cfg = ProxyConfig.Builder()
                    .addProxyRule("http://127.0.0.1:$port")
                    .addDirect()
                    .build()
                ProxyController.getInstance().setProxyOverride(cfg, executor) {
                    deferred.complete(Unit)
                    prevDeferred?.complete(Unit)
                    if (pendingProxyDeferred === deferred && notifyRouteOnProxyComplete.getAndSet(false)) {
                        routeChangedListener?.invoke()
                    }
                }
            }.onFailure { err ->
                proxyOverrideActive = false
                deferred.complete(Unit)
                prevDeferred?.complete(Unit)
                LinDoDiagnosticLogger.warn(
                    DiagnosticCategory.NETWORK,
                    "EchGateway",
                    "WebView ProxyController.setProxyOverride failed: ${err.message ?: err::class.java.simpleName}"
                )
                if (pendingProxyDeferred === deferred && notifyRouteOnProxyComplete.getAndSet(false)) {
                    routeChangedListener?.invoke()
                }
            }
        }
    }

    private fun clearWebViewProxyOverride(
        stopGatewayAfterClear: Boolean = false,
        notifyRouteChanged: Boolean = false
    ) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            if (stopGatewayAfterClear) {
                NativeEchBridge.nativeStopGateway()
                publishStatusFromNative()
            }
            if (notifyRouteChanged) {
                dispatchRouteChangedWhenProxySettled()
            }
            return
        }
        if (proxyOverrideActive) {
            routeStackFlipped.set(true)
        }
        proxyOverrideActive = false
        lastAppliedPort = 0
        if (notifyRouteChanged) {
            notifyRouteOnProxyComplete.set(true)
        }
        val prevDeferred = pendingProxyDeferred
        val deferred = CompletableDeferred<Unit>()
        pendingProxyDeferred = deferred
        val executor = ContextCompat.getMainExecutor(appContext)
        runOnMainSyncOrPost {
            runCatching {
                ProxyController.getInstance().clearProxyOverride(executor) {
                    deferred.complete(Unit)
                    prevDeferred?.complete(Unit)
                    if (pendingProxyDeferred === deferred && notifyRouteOnProxyComplete.getAndSet(false)) {
                        routeChangedListener?.invoke()
                    }
                    if (stopGatewayAfterClear) {
                        mainHandler.postDelayed({
                            reconcileExecutor.execute {
                                if (synchronized(stateLock) { activeGatewayPort == 0 }) {
                                    NativeEchBridge.nativeStopGateway()
                                    publishStatusFromNative()
                                }
                            }
                        }, 350L)
                    }
                }
            }.onFailure {
                deferred.complete(Unit)
                prevDeferred?.complete(Unit)
                if (pendingProxyDeferred === deferred && notifyRouteOnProxyComplete.getAndSet(false)) {
                    routeChangedListener?.invoke()
                }
                if (stopGatewayAfterClear) {
                    reconcileExecutor.execute {
                        if (synchronized(stateLock) { activeGatewayPort == 0 }) {
                            NativeEchBridge.nativeStopGateway()
                            publishStatusFromNative()
                        }
                    }
                }
            }
        }
    }

    private fun publishStatusFromNative() {
        val raw = if (NativeEchBridge.loaded) NativeEchBridge.nativeGetStatusJson() else "{}"
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: JSONObject()
        val (mode, vpnNow, phys) = synchronized(stateLock) {
            Triple(currentMode, vpnActive, bestPhysicalLocked())
        }
        val (vpnYieldNow, desiredActive) = synchronized(stateLock) {
            vpnLockdownYielded to (activeGatewayPort > 0)
        }
        val nativeActive = desiredActive && json.optBoolean("active", false)
        val snap = EchRuntimeSnapshot(
            supported = true,
            active = nativeActive,
            mode = mode,
            listenPort = if (nativeActive) json.optInt("listenPort", 0) else 0,
            stickyIp = if (nativeActive) json.optString("stickyIp", "") else "",
            bypassVpnRequested = nativeActive && json.optBoolean("bypassVpnRequested", false),
            bypassVpnBound = nativeActive && json.optBoolean("bypassVpnBound", false),
            physicalIface = phys?.ifaceName.orEmpty(),
            vpnActive = vpnNow || isSystemHttpProxySet(),
            vpnLockdownYielded = vpnYieldNow,
            lastBindErrno = json.optInt("lastBindErrno", 0),
            echHandshakeCount = json.optLong("echHandshakeCount", 0L),
            lastEchAccepted = json.optBoolean("lastEchAccepted", false),
            lastError = json.optString("lastError", NativeEchBridge.loadError)
        )
        val prevActiveCount = if (EchRoutingPolicy.snapshot.active) EchRoutingPolicy.snapshot.echHandshakeCount else 0L
        EchRoutingPolicy.publishSnapshot(snap)
        if (snap.active && prevActiveCount == 0L && snap.echHandshakeCount > 0L) {
            LinDoDiagnosticLogger.info(
                DiagnosticCategory.NETWORK,
                "EchGateway",
                "Live ECH active: ${EchRoutingPolicy.toDiagnosticLine()}"
            )
        }
    }
}
