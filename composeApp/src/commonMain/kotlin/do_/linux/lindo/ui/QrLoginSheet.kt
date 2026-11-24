package do_.linux.lindo.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.QrLoginManager
import do_.linux.lindo.core.network.QrLoginPayload
import do_.linux.lindo.ui.components.LinDoIcons
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

@Suppress("DEPRECATION")
@Composable
internal fun QrCodeScanSheet(
    onDismiss: () -> Unit,
    onAccepted: () -> Unit
) {
    val palette = LocalPalette.current
    val clipboard = LocalClipboardManager.current
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var showManualInput by remember { mutableStateOf(PlatformQrScanner.scannerContent == null) }
    var manualText by remember { mutableStateOf("") }
    var handled by remember { mutableStateOf(false) }

    fun submitRawPayload(raw: String) {
        if (handled) return
        val res = QrLoginManager.acceptScannedQrPayload(raw)
        if (res.isSuccess) {
            handled = true
            errorMsg = null
            onAccepted()
        } else {
            errorMsg = res.exceptionOrNull()?.message ?: "未能识别有效登录二维码"
        }
    }

    LinDoFullScreenPage(onBack = onDismiss, containerColor = palette.cardSurface) {
        LinDoPageTopBar(title = "扫码 / 口令登录", onBack = onDismiss)
        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val nativeScanner = PlatformQrScanner.scannerContent
            if (nativeScanner != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 360.dp)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(palette.subtleSurface)
                        .border(1.dp, palette.hairline, RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    nativeScanner(
                        { text -> submitRawPayload(text) },
                        { msg -> errorMsg = msg }
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "扫描已登录 Lindo / FluxDO 设备的二维码，或在下方粘贴登录口令",
                    color = palette.inkSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            } else {
                Icon(
                    imageVector = LinDoIcons.QrCode,
                    contentDescription = null,
                    tint = palette.brandGold,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "粘贴跨设备登录口令完成登录",
                    color = palette.inkPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "可在已登录设备的「我的 → 扫码 / 口令登录」生成并复制口令",
                    color = palette.inkSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }

            if (!errorMsg.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = errorMsg!!,
                    color = Color(0xFFEF4444),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(18.dp))

            val picker = PlatformQrScanner.pickImageAndDecodeQr
            if (picker != null) {
                OutlinedButton(
                    onClick = {
                        errorMsg = null
                        picker { res ->
                            if (res.isSuccess) {
                                submitRawPayload(res.getOrThrow())
                            } else {
                                errorMsg = res.exceptionOrNull()?.message ?: "未能从图片中识别到二维码"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, palette.hairline),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.inkPrimary)
                ) {
                    Icon(LinDoIcons.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("从相册识别二维码", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(10.dp))
            }

            OutlinedButton(
                onClick = {
                    val clip = clipboard.getText()?.text?.trim().orEmpty()
                    if (clip.isNotEmpty() && QrLoginManager.isQrLoginUri(clip)) {
                        submitRawPayload(clip)
                    } else {
                        if (clip.isNotEmpty()) manualText = clip
                        showManualInput = true
                        if (clip.isEmpty()) {
                            errorMsg = "剪贴板暂无登录口令，请手动粘贴到下方输入框"
                        } else {
                            errorMsg = "剪贴板内容不是有效登录口令，可手动编辑后确认"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp).heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, palette.hairline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.inkPrimary)
            ) {
                Text("粘贴登录口令", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }

            if (showManualInput) {
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = manualText,
                    onValueChange = {
                        manualText = it
                        errorMsg = null
                    },
                    placeholder = { Text("lindo://qr-login?...", fontSize = 13.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = palette.inkPrimary,
                        unfocusedTextColor = palette.inkPrimary,
                        focusedBorderColor = palette.brandGold,
                        unfocusedBorderColor = palette.hairline
                    ),
                    modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp)
                )
                Spacer(Modifier.height(10.dp))
                LinDoPrimaryButton(
                    label = "确认登录",
                    onClick = { submitRawPayload(manualText) },
                    enabled = manualText.isNotBlank(),
                    modifier = Modifier.widthIn(max = 360.dp)
                )
            }
        }
    }
}

@Suppress("DEPRECATION")
@Composable
internal fun QrCodeGenerateSheet(
    username: String,
    onDismiss: () -> Unit
) {
    val palette = LocalPalette.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var generating by remember { mutableStateOf(false) }
    var generatedUri by remember { mutableStateOf<String?>(null) }
    var generatedPayload by remember { mutableStateOf<QrLoginPayload?>(null) }
    var matrix by remember { mutableStateOf<Array<BooleanArray>?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var copiedHint by remember { mutableStateOf(false) }

    fun generate() {
        if (generating) return
        generating = true
        errorMsg = null
        copiedHint = false
        scope.launch {
            try {
                val res = LinuxDoRepository.createCrossDeviceQrLoginUri()
                if (res.isSuccess) {
                    val (uri, payload) = res.getOrThrow()
                    matrix = QrLoginManager.encodeQrBooleanMatrix(uri)
                    generatedUri = uri
                    generatedPayload = payload
                } else {
                    errorMsg = res.exceptionOrNull()?.message ?: "生成二维码失败，请重试"
                }
            } finally {
                generating = false
            }
        }
    }

    LaunchedEffect(Unit) {
        generate()
    }

    LinDoFullScreenPage(onBack = onDismiss, containerColor = palette.cardSurface) {
        LinDoPageTopBar(title = "生成登录二维码与口令", onBack = onDismiss)
        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (username.isNotBlank()) {
                Text(
                    text = "当前账号：@$username",
                    color = palette.inkPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
            }
            Text(
                text = "同时生成一次性登录二维码与跨设备登录口令：另一台手机可直接扫码，电脑端可复制下方口令或识别二维码截图登录。使用后凭据自动销毁，请勿发给他人。",
                color = palette.inkSecondary,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 360.dp)
            )

            Spacer(Modifier.height(22.dp))

            val qrMatrix = matrix
            if (qrMatrix != null) {
                Box(
                    modifier = Modifier
                        .size(236.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.White)
                        .border(1.dp, palette.hairline, RoundedCornerShape(18.dp))
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
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
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "二维码与登录口令已就绪（约 10 分钟内有效）",
                    color = palette.inkSecondary,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(18.dp))
                OutlinedButton(
                    onClick = {
                        generatedUri?.let {
                            clipboard.setText(AnnotatedString(it))
                            copiedHint = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, palette.hairline),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.inkPrimary)
                ) {
                    Text(if (copiedHint) "已复制登录口令" else "复制登录口令（供电脑或其他设备粘贴）", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(10.dp))
            }

            if (!errorMsg.isNullOrBlank()) {
                Text(
                    text = errorMsg!!,
                    color = Color(0xFFEF4444),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 14.dp)
                )
            }

            LinDoPrimaryButton(
                label = if (matrix == null) "生成登录二维码与口令" else "重新生成二维码与口令",
                onClick = ::generate,
                busy = generating,
                busyLabel = "正在生成二维码与口令...",
                leadingIcon = LinDoIcons.QrCode,
                modifier = Modifier.widthIn(max = 360.dp)
            )
        }
    }
}
