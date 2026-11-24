package do_.linux.lindo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import do_.linux.lindo.core.update.AppUpdateManager
import do_.linux.lindo.core.update.MobileUpdateInfo
import kotlinx.coroutines.launch

@Composable
internal fun LindoAppUpdateDialog(
    info: MobileUpdateInfo,
    onOpenExternalUrl: (String) -> Unit
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val downloading = AppUpdateManager.downloading
    val progress = AppUpdateManager.progress
    val error = AppUpdateManager.errorMessage
    val readyToInstall = AppUpdateManager.cachedApkReady

    fun formatMb(bytes: Long): String {
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        val tenths = kotlin.math.round(mb * 10.0).toLong()
        return "${tenths / 10}.${tenths % 10} MB"
    }

    Dialog(
        onDismissRequest = { AppUpdateManager.dismissDialog() },
        properties = DialogProperties(
            dismissOnBackPress = !downloading,
            dismissOnClickOutside = !downloading,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 340.dp)
                .fillMaxWidth()
                .border(0.5.dp, palette.hairline, RoundedCornerShape(18.dp)),
            shape = RoundedCornerShape(18.dp),
            color = palette.cardSurface,
            tonalElevation = 0.dp,
            shadowElevation = 14.dp
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "发现新版本 v${info.latestVersionName}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.inkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append("当前 v${info.currentVersionName}")
                        if (readyToInstall) {
                            append(" · 安装包已就绪")
                        } else if (info.sizeBytes > 0L) {
                            append(" · ${formatMb(info.sizeBytes)}")
                        }
                    },
                    fontSize = 12.5.sp,
                    color = palette.inkTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (info.notes.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(palette.hairline)
                    )
                    Spacer(Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 156.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        info.notes.forEach { line ->
                            Row(verticalAlignment = Alignment.Top) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 7.dp)
                                        .size(4.dp)
                                        .clip(CircleShape)
                                        .background(palette.inkTertiary)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = line,
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp,
                                    color = palette.inkSecondary
                                )
                            }
                        }
                    }
                }

                if (downloading) {
                    val pct = (progress?.percent ?: 2).coerceIn(2, 100)
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (pct >= 97) "正在校验安装包..." else "正在下载更新...",
                            fontSize = 12.sp,
                            color = palette.inkSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "$pct%",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.inkPrimary
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { pct / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape),
                        color = palette.brandGold,
                        trackColor = palette.subtleSurface
                    )
                }

                if (!error.isNullOrBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0x14EF4444))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = error,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = Color(0xFFEF4444)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "使用浏览器下载安装包",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.brandGold,
                            modifier = Modifier.clickable {
                                onOpenExternalUrl(info.apkUrl.ifBlank { AppUpdateManager.DOWNLOAD_PAGE_URL })
                            }
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))
                if (downloading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(palette.subtleSurface),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "更新中...",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.inkTertiary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(palette.subtleSurface)
                                .clickable { AppUpdateManager.dismissDialog() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "稍后",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = palette.inkSecondary,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(palette.publishBtnBg)
                                .clickable {
                                    scope.launch {
                                        AppUpdateManager.downloadAndInstall(
                                            info = info,
                                            onFallbackOpenBrowser = onOpenExternalUrl
                                        )
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (readyToInstall) "立即安装" else "立即更新",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.publishBtnContent,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }
    }
}
