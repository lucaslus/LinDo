package do_.linux.lindo.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.QrLoginManager
import do_.linux.lindo.core.network.QrLoginPayload
import do_.linux.lindo.core.network.QrParseResult
import do_.linux.lindo.ui.components.LinDoIcons
import do_.linux.lindo.ui.components.SmartAvatar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 跨平台扫码能力桥接（Android 提供 Camera2 实时取景 + 相册图片解码；桌面端回退粘贴口令）。
 */
object PlatformQrScanner {
    @Volatile
    var scannerContent: (@Composable (
        onDecodedText: (String) -> Unit,
        onError: (String) -> Unit
    ) -> Unit)? = null

    @Volatile
    var pickImageAndDecodeQr: ((onResult: (Result<String>) -> Unit) -> Unit)? = null
}

internal enum class QrLoginMode {
    GENERATE,
    REDEEM
}

internal enum class QrRedeemTab(val label: String) {
    TOKEN("口令登录"),
    ALBUM("从相册识图"),
    SCAN("扫码登录")
}

/**
 * 统一跨设备登录二维码页（对齐桌面端 `QrLoginModal`）：
 * - 已登录时默认进入「生成登录码」模式，同时支持顶部切换「扫码 / 口令」模式以添加或切换其他账号；
 * - Redeem 模式采用 3-Tab 共用内容区（默认选中「口令登录」，绝不自动拉起相机；用户点选「扫码登录」时才请求权限并启动取景器，切走立即释放）。
 */
@Suppress("DEPRECATION")
@Composable
internal fun QrLoginSheet(
    isAuthorized: Boolean,
    username: String,
    avatarTemplate: String? = null,
    animatedAvatar: String? = null,
    initialMode: QrLoginMode? = null,
    onBeforeRedeem: (suspend () -> Unit)? = null,
    onDismiss: () -> Unit,
    onAccepted: () -> Unit
) {
    val palette = LocalPalette.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val effectiveInitialMode = when {
        !isAuthorized -> QrLoginMode.REDEEM
        initialMode != null -> initialMode
        else -> QrLoginMode.GENERATE
    }
    var mode by remember(isAuthorized, effectiveInitialMode) {
        mutableStateOf(effectiveInitialMode)
    }

    // Generate state (当前已登录设备 -> 生成一次性登录二维码与口令供电脑或其他手机登录)
    var generating by remember { mutableStateOf(false) }
    var generatedUri by remember { mutableStateOf<String?>(null) }
    var generatedPayload by remember { mutableStateOf<QrLoginPayload?>(null) }
    var matrix by remember { mutableStateOf<Array<BooleanArray>?>(null) }
    var genError by remember { mutableStateOf<String?>(null) }
    var copiedHint by remember { mutableStateOf(false) }
    var genSeq by remember { mutableIntStateOf(0) }

    // Redeem state (口令登录 [默认] / 从相册识图 / 扫码登录 — 3 Tab 共用一块内容区)
    var redeemTab by remember { mutableStateOf(QrRedeemTab.TOKEN) }
    var redeemError by remember { mutableStateOf<String?>(null) }
    var manualText by remember { mutableStateOf("") }
    var redeeming by remember { mutableStateOf(false) }
    var handled by remember { mutableStateOf(false) }

    LaunchedEffect(copiedHint) {
        if (copiedHint) {
            delay(2200L)
            copiedHint = false
        }
    }

    fun generateQr() {
        if (!isAuthorized || generating) return
        val seq = ++genSeq
        generating = true
        genError = null
        copiedHint = false
        scope.launch {
            try {
                val res = LinuxDoRepository.createCrossDeviceQrLoginUri()
                if (seq != genSeq) return@launch
                if (res.isSuccess) {
                    val (uri, payload) = res.getOrThrow()
                    matrix = QrLoginManager.encodeQrBooleanMatrix(uri)
                    generatedUri = uri
                    generatedPayload = payload
                } else {
                    genError = res.exceptionOrNull()?.message ?: "生成二维码失败，请重试"
                }
            } finally {
                if (seq == genSeq) {
                    generating = false
                }
            }
        }
    }

    LaunchedEffect(mode, isAuthorized) {
        if (mode == QrLoginMode.GENERATE && isAuthorized && matrix == null && !generating) {
            generateQr()
        }
    }

    fun submitRawPayload(raw: String) {
        if (handled || redeeming) return
        val trimmed = raw.trim()
        when (val parsed = QrLoginManager.parsePayload(trimmed)) {
            is QrParseResult.Invalid -> {
                redeemError = parsed.message
                return
            }
            is QrParseResult.Valid -> Unit
        }
        redeeming = true
        redeemError = null
        scope.launch {
            try {
                onBeforeRedeem?.invoke()
                val res = QrLoginManager.acceptScannedQrPayload(trimmed)
                if (res.isSuccess) {
                    handled = true
                    redeemError = null
                    onAccepted()
                } else {
                    redeemError = res.exceptionOrNull()?.message ?: "未能识别有效登录二维码"
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (err: Throwable) {
                redeemError = err.message ?: "登录口令处理失败，请重试"
            } finally {
                redeeming = false
            }
        }
    }

    LinDoFullScreenPage(onBack = onDismiss, containerColor = palette.cardSurface) {
        LinDoPageTopBar(
            title = if (isAuthorized) "登录二维码" else "扫码 / 口令登录",
            onBack = onDismiss
        )
        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isAuthorized) {
                Row(
                    modifier = Modifier
                        .widthIn(max = 360.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.subtleSurface)
                        .border(0.5.dp, palette.hairline, RoundedCornerShape(12.dp))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    val tabs = listOf(
                        QrLoginMode.GENERATE to "生成登录码",
                        QrLoginMode.REDEEM to "扫码 / 口令"
                    )
                    tabs.forEach { (tabMode, label) ->
                        val selected = mode == tabMode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (selected) palette.cardSurface else Color.Transparent)
                                .semantics { role = Role.Tab }
                                .clickable {
                                    redeemError = null
                                    genError = null
                                    if (tabMode == QrLoginMode.REDEEM) {
                                        redeemTab = QrRedeemTab.TOKEN
                                    }
                                    mode = tabMode
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                fontSize = 13.5.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (selected) palette.inkPrimary else palette.inkTertiary,
                                maxLines = 1
                            )
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
            }

            if (mode == QrLoginMode.GENERATE && isAuthorized) {
                val resolvedUsername = generatedPayload?.username?.takeIf { it.isNotBlank() } ?: username
                if (resolvedUsername.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SmartAvatar(
                            username = resolvedUsername,
                            avatarTemplate = avatarTemplate,
                            animatedAvatar = animatedAvatar,
                            size = 26.dp,
                            enableAnimations = false
                        )
                        Text(
                            text = "@$resolvedUsername",
                            color = palette.inkPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    text = "用另一台设备扫码，或复制口令登录。使用后自动失效，请勿分享给他人。",
                    color = palette.inkSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 360.dp)
                )

                Spacer(Modifier.height(20.dp))

                Box(
                    modifier = Modifier
                        .size(236.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White)
                        .border(1.dp, palette.hairline, RoundedCornerShape(20.dp))
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val qrMatrix = matrix
                    when {
                        qrMatrix != null && !generating -> {
                            Canvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .semantics { contentDescription = "Lindo 跨设备登录二维码" }
                            ) {
                                val rows = qrMatrix.size
                                val cols = if (rows > 0) qrMatrix[0].size else 0
                                if (rows > 0 && cols > 0) {
                                    val cellW = size.width / cols.toFloat()
                                    val cellH = size.height / rows.toFloat()
                                    for (y in 0 until rows) {
                                        val row = qrMatrix[y]
                                        for (x in 0 until cols) {
                                            if (row[x]) {
                                                drawRect(
                                                    color = Color(0xFF111827),
                                                    topLeft = Offset(x * cellW, y * cellH),
                                                    size = Size(cellW + 0.35f, cellH + 0.35f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        generating -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(26.dp),
                                    strokeWidth = 2.4.dp,
                                    color = palette.brandGold
                                )
                                Text(
                                    text = "生成中…",
                                    color = Color(0xFF4B5563),
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        else -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = LinDoIcons.QrCode,
                                    contentDescription = null,
                                    tint = Color(0xFF6B7280),
                                    modifier = Modifier.size(42.dp)
                                )
                                Text(
                                    text = "点击下方按钮生成登录码",
                                    color = Color(0xFF6B7280),
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }

                if (matrix != null && genError.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "约 10 分钟内有效",
                        color = palette.inkTertiary,
                        fontSize = 12.sp
                    )
                }

                if (!genError.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = genError!!,
                        color = Color(0xFFEF4444),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 360.dp)
                    )
                }

                Spacer(Modifier.height(18.dp))

                if (generatedUri != null) {
                    OutlinedButton(
                        onClick = {
                            generatedUri?.let {
                                clipboard.setText(AnnotatedString(it))
                                copiedHint = true
                            }
                        },
                        modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, palette.hairline),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (copiedHint) palette.brandGold else palette.inkPrimary
                        )
                    ) {
                        Icon(
                            imageVector = if (copiedHint) LinDoIcons.Check else LinDoIcons.Link,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = if (copiedHint) "已复制口令" else "复制登录口令",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }

                LinDoPrimaryButton(
                    label = if (matrix == null) "生成登录码" else "重新生成",
                    onClick = ::generateQr,
                    busy = generating,
                    busyLabel = "生成中…",
                    leadingIcon = LinDoIcons.QrCode,
                    modifier = Modifier.widthIn(max = 360.dp)
                )
            } else {
                val nativeScanner = PlatformQrScanner.scannerContent
                val picker = PlatformQrScanner.pickImageAndDecodeQr
                val availableTabs = buildList {
                    add(QrRedeemTab.TOKEN)
                    if (picker != null) add(QrRedeemTab.ALBUM)
                    if (nativeScanner != null) add(QrRedeemTab.SCAN)
                }
                val activeTab = if (redeemTab in availableTabs) redeemTab else QrRedeemTab.TOKEN

                if (availableTabs.size > 1) {
                    Row(
                        modifier = Modifier
                            .widthIn(max = 380.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.subtleSurface)
                            .border(0.5.dp, palette.hairline, RoundedCornerShape(12.dp))
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        availableTabs.forEach { tab ->
                            val isSelected = activeTab == tab
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(if (isSelected) palette.cardSurface else Color.Transparent)
                                    .then(
                                        if (isSelected) {
                                            Modifier.border(0.5.dp, palette.hairline, RoundedCornerShape(9.dp))
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .semantics {
                                        role = Role.Tab
                                        selected = isSelected
                                    }
                                    .clickable {
                                        if (redeemTab != tab) {
                                            redeemTab = tab
                                            redeemError = null
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = tab.label,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (isSelected) palette.inkPrimary else palette.inkTertiary,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                // Shared fixed-height content area for all Redeem tabs (0dp height jump across 口令登录 / 从相册识图 / 扫码登录)
                Box(
                    modifier = Modifier
                        .widthIn(max = 380.dp)
                        .fillMaxWidth()
                        .height(348.dp)
                        .testTag("qr_redeem_content_card")
                        .clip(RoundedCornerShape(18.dp))
                        .background(palette.subtleSurface.copy(alpha = if (palette.isDark) 0.52f else 0.72f))
                        .border(0.5.dp, palette.hairline, RoundedCornerShape(18.dp))
                        .padding(horizontal = 18.dp, vertical = 18.dp)
                ) {
                    when (activeTab) {
                        QrRedeemTab.TOKEN -> {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.SpaceBetween,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "在已登录设备的「我的 → 登录二维码」复制口令，粘贴后即可登录。",
                                        color = palette.inkSecondary,
                                        fontSize = 13.sp,
                                        lineHeight = 19.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Spacer(Modifier.height(12.dp))

                                    OutlinedTextField(
                                        value = manualText,
                                        onValueChange = {
                                            manualText = it
                                            redeemError = null
                                        },
                                        placeholder = { Text("粘贴 lindo://qr-login?... 登录口令", fontSize = 13.sp) },
                                        singleLine = false,
                                        minLines = 4,
                                        maxLines = 5,
                                        shape = RoundedCornerShape(12.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedContainerColor = palette.cardSurface,
                                            unfocusedContainerColor = palette.cardSurface,
                                            focusedTextColor = palette.inkPrimary,
                                            unfocusedTextColor = palette.inkPrimary,
                                            focusedBorderColor = palette.brandGold,
                                            unfocusedBorderColor = palette.hairline
                                        ),
                                        modifier = Modifier.fillMaxWidth().weight(1f)
                                    )
                                }

                                Spacer(Modifier.height(12.dp))

                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            val clip = clipboard.getText()?.text?.trim().orEmpty()
                                            if (clip.isNotEmpty() && QrLoginManager.isQrLoginUri(clip)) {
                                                manualText = clip
                                                submitRawPayload(clip)
                                            } else {
                                                if (clip.isNotEmpty()) manualText = clip
                                                redeemError = if (clip.isEmpty()) {
                                                    "剪贴板暂无登录口令，请手动粘贴"
                                                } else {
                                                    "剪贴板内容不是有效登录口令"
                                                }
                                            }
                                        },
                                        enabled = !redeeming,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, palette.hairline),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            containerColor = palette.cardSurface,
                                            contentColor = palette.inkPrimary
                                        )
                                    ) {
                                        Icon(LinDoIcons.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(7.dp))
                                        Text("粘贴口令", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    }

                                    LinDoPrimaryButton(
                                        label = "确认登录",
                                        onClick = { submitRawPayload(manualText) },
                                        enabled = manualText.isNotBlank() && !redeeming,
                                        busy = redeeming,
                                        busyLabel = "正在登录...",
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }

                        QrRedeemTab.ALBUM -> {
                            val launchPicker = {
                                if (!redeeming && picker != null) {
                                    redeemError = null
                                    picker { res ->
                                        if (res.isSuccess) {
                                            submitRawPayload(res.getOrThrow())
                                        } else {
                                            redeemError = res.exceptionOrNull()?.message ?: "未能从图片中识别到二维码"
                                        }
                                    }
                                }
                            }
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.SpaceBetween,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(palette.cardSurface)
                                        .border(1.dp, palette.hairline, RoundedCornerShape(14.dp))
                                        .clickable(enabled = !redeeming && picker != null) { launchPicker() }
                                        .padding(horizontal = 20.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (redeeming) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(28.dp),
                                                strokeWidth = 2.5.dp,
                                                color = palette.brandGold
                                            )
                                            Text(
                                                text = "正在验证登录…",
                                                color = palette.inkSecondary,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    } else {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = LinDoIcons.Image,
                                                contentDescription = null,
                                                tint = palette.brandGold,
                                                modifier = Modifier.size(40.dp)
                                            )
                                            Text(
                                                text = "从相册选择二维码截图",
                                                color = palette.inkPrimary,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                textAlign = TextAlign.Center
                                            )
                                            Text(
                                                text = "自动识别图片中的登录二维码并完成登录",
                                                color = palette.inkSecondary,
                                                fontSize = 12.5.sp,
                                                lineHeight = 18.sp,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }

                                Spacer(Modifier.height(14.dp))

                                LinDoPrimaryButton(
                                    label = "选择二维码图片",
                                    onClick = launchPicker,
                                    enabled = !redeeming && picker != null,
                                    busy = redeeming,
                                    busyLabel = "正在登录...",
                                    leadingIcon = LinDoIcons.Image,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        QrRedeemTab.SCAN -> {
                            // Camera2 preview & permission prompt are ONLY composed while user is on 「扫码登录」
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                if (nativeScanner != null) {
                                    Box(
                                        modifier = Modifier
                                            .widthIn(max = 240.dp)
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(palette.cardSurface)
                                            .border(1.dp, palette.hairline, RoundedCornerShape(16.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        nativeScanner(
                                            { text -> submitRawPayload(text) },
                                            { msg -> redeemError = msg }
                                        )
                                    }
                                    Spacer(Modifier.height(14.dp))
                                    Text(
                                        text = if (redeeming) "已识别，正在登录…" else "将镜头对准已登录设备的登录二维码",
                                        color = palette.inkSecondary,
                                        fontSize = 13.sp,
                                        lineHeight = 19.sp,
                                        textAlign = TextAlign.Center,
                                        maxLines = 2
                                    )
                                }
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .widthIn(max = 380.dp)
                        .fillMaxWidth()
                        .heightIn(min = 24.dp)
                        .padding(top = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (!redeemError.isNullOrBlank()) {
                        Text(
                            text = redeemError!!,
                            color = Color(0xFFEF4444),
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun QrCodeScanSheet(
    onDismiss: () -> Unit,
    onAccepted: () -> Unit
) {
    QrLoginSheet(
        isAuthorized = false,
        username = "",
        initialMode = QrLoginMode.REDEEM,
        onDismiss = onDismiss,
        onAccepted = onAccepted
    )
}

@Composable
internal fun QrCodeGenerateSheet(
    username: String,
    onDismiss: () -> Unit
) {
    QrLoginSheet(
        isAuthorized = true,
        username = username,
        initialMode = QrLoginMode.GENERATE,
        onDismiss = onDismiss,
        onAccepted = onDismiss
    )
}
