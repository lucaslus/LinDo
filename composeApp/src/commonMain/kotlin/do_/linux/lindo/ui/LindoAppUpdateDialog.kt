package do_.linux.lindo.ui

import androidx.compose.foundation.Image
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import do_.linux.lindo.core.update.AppUpdateManager
import do_.linux.lindo.core.update.MobileUpdateInfo
import do_.linux.lindo.ui.components.LinDoIcons
import kotlinx.coroutines.launch
import lindo.composeapp.generated.resources.Res
import lindo.composeapp.generated.resources.lindo_brand_mark
import org.jetbrains.compose.resources.painterResource

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
                .padding(horizontal = 20.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .border(0.5.dp, palette.hairline, RoundedCornerShape(22.dp)),
            shape = RoundedCornerShape(22.dp),
            color = palette.cardSurface,
            tonalElevation = 0.dp,
            shadowElevation = 16.dp
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(Res.drawable.lindo_brand_mark),
                        contentDescription = "Lindo",
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "发现新版本 v${info.latestVersionName}",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.inkPrimary
                            )
                            Spacer(Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(palette.activeChipBg)
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "build ${info.latestVersionCode}",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.activeChipText
                                )
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = buildString {
                                append("当前 build ${info.currentVersionCode}")
                                if (info.sizeBytes > 0L) {
                                    append(" · 包体 ${formatMb(info.sizeBytes)}")
                                }
                                if (info.updatedAt.isNotBlank()) {
                                    append(" · ${info.updatedAt}")
                                }
                            },
                            fontSize = 11.5.sp,
                            color = palette.inkTertiary
                        )
                    }
                    if (!downloading) {
                        IconButton(
                            onClick = { AppUpdateManager.dismissDialog() },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = LinDoIcons.Close,
                                contentDescription = "关闭",
                                modifier = Modifier.size(16.dp),
                                tint = palette.inkSecondary
                            )
                        }
                    }
                }

                if (info.notes.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 190.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(palette.subtleSurface)
                            .border(0.5.dp, palette.hairline, RoundedCornerShape(14.dp))
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        info.notes.forEach { line ->
                            Row(verticalAlignment = Alignment.Top) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 7.dp)
                                        .size(5.dp)
                                        .clip(CircleShape)
                                        .background(palette.brandGold)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = line,
                                    fontSize = 12.5.sp,
                                    lineHeight = 18.sp,
                                    color = palette.inkSecondary
                                )
                            }
                        }
                    }
                }

                if (downloading || progress != null) {
                    val curProg = progress
                    if (curProg != null && downloading) {
                        Spacer(Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = curProg.message,
                                fontSize = 12.sp,
                                color = palette.inkPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "${curProg.percent}%",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.brandGold
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { (curProg.percent.coerceIn(2, 100)) / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape),
                            color = palette.brandGold,
                            trackColor = palette.subtleSurface
                        )
                        if (curProg.totalBytes > 0L) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "${formatMb(curProg.downloadedBytes)} / ${formatMb(curProg.totalBytes)}",
                                fontSize = 11.sp,
                                color = palette.inkTertiary,
                                modifier = Modifier.align(Alignment.End)
                            )
                        }
                    }
                }

                if (!error.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x1FEF4444))
                            .border(0.5.dp, Color(0x4DEF4444), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 9.dp)
                    ) {
                        Text(
                            text = error,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = Color(0xFFEF4444)
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "前往 GitHub 下载",
                        fontSize = 12.sp,
                        color = palette.inkTertiary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                onOpenExternalUrl(info.apkUrl.ifBlank { AppUpdateManager.DOWNLOAD_PAGE_URL })
                            }
                            .padding(horizontal = 6.dp, vertical = 6.dp)
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!downloading) {
                            TextButton(onClick = { AppUpdateManager.dismissDialog() }) {
                                Text("稍后", fontSize = 13.sp, color = palette.inkSecondary)
                            }
                        }
                        Button(
                            enabled = !downloading,
                            onClick = {
                                scope.launch {
                                    AppUpdateManager.downloadAndInstall(
                                        info = info,
                                        onFallbackOpenBrowser = onOpenExternalUrl
                                    )
                                }
                            },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = palette.publishBtnBg,
                                contentColor = palette.publishBtnContent
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp)
                        ) {
                            Icon(
                                imageVector = LinDoIcons.Download,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (downloading) "正在升级..." else "立即下载并安装",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
