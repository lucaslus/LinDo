package do_.linux.lindo.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import do_.linux.lindo.core.diagnostics.LinDoDiagnosticLogger
import do_.linux.lindo.ui.components.LinDoIcons
import lindo.composeapp.generated.resources.Res
import lindo.composeapp.generated.resources.lindo_brand_mark
import org.jetbrains.compose.resources.painterResource

private const val LINDO_PROJECT_URL = "https://github.com/lucaslus/LinDo"

/** Local app information: opening About never requires a forum session or network request. */
@Composable
internal fun LindoAboutPage(
    onDismiss: () -> Unit,
    onOpenLink: (String) -> Unit,
    onOpenDebugDiagnostics: () -> Unit = {}
) {
    val palette = LocalPalette.current
    var showLicenses by remember { mutableStateOf(false) }
    val (debugHint, onVersionTap) = rememberDebugUnlockTapState(onUnlocked = onOpenDebugDiagnostics)
    LinDoFullScreenPage(onBack = onDismiss) {
        LinDoPageTopBar(title = "关于 Lindo", onBack = onDismiss)
        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 600.dp).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(painterResource(Res.drawable.lindo_brand_mark), "Lindo 零度娘 LOGO", Modifier.size(80.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("Lindo", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = palette.inkPrimary)
                        Spacer(Modifier.height(6.dp))
                        Text("Linux DO 社区客户端", fontSize = 14.sp, color = palette.inkSecondary)
                        Spacer(Modifier.height(18.dp))
                        Text(LinDoDiagnosticLogger.APP_VERSION_DISPLAY, fontSize = 12.sp, color = palette.inkTertiary,
                            modifier = Modifier.testTag("about_version_debug_trigger")
                                .clickable(onClick = onVersionTap).padding(horizontal = 12.dp, vertical = 12.dp))
                        if (debugHint != null) Text(debugHint, fontSize = 12.sp, color = palette.activeChipText)
                    }
                }
                item { LinDoSettingRow(LinDoIcons.Globe, "项目主页", onClick = { onOpenLink(LINDO_PROJECT_URL) }) }
                item { LinDoSettingRow(LinDoIcons.MessageCircle, "问题反馈", onClick = { onOpenLink("$LINDO_PROJECT_URL/issues") }) }
                item { LinDoSettingRow(LinDoIcons.Code, "开源许可", showDivider = false, onClick = { showLicenses = true }) }
            }
        }
    }
    if (showLicenses) {
        LindoLicensesPage(onBack = { showLicenses = false }, onOpenLink = onOpenLink)
    }
}

private data class ComponentLicense(val name: String, val license: String, val url: String)
private val componentLicenses = listOf(
    ComponentLicense("Kotlin", "Apache 2.0", "https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt"),
    ComponentLicense("Compose Multiplatform", "Apache 2.0", "https://github.com/JetBrains/compose-multiplatform/blob/master/LICENSE.txt"),
    ComponentLicense("AndroidX", "Apache 2.0", "https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt"),
    ComponentLicense("Kotlin Coroutines", "Apache 2.0", "https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt"),
    ComponentLicense("Kotlin Serialization", "Apache 2.0", "https://github.com/Kotlin/kotlinx.serialization/blob/master/LICENSE.txt"),
    ComponentLicense("Ktor", "Apache 2.0", "https://github.com/ktorio/ktor/blob/main/LICENSE"),
    ComponentLicense("Coil", "Apache 2.0", "https://github.com/coil-kt/coil/blob/main/LICENSE.txt"),
    ComponentLicense("jsoup", "MIT", "https://github.com/jhy/jsoup/blob/master/LICENSE"),
    ComponentLicense("commonmark-java", "BSD 2-Clause", "https://github.com/commonmark/commonmark-java/blob/main/LICENSE.txt"),
    ComponentLicense("Font Awesome Free 图标", "CC BY 4.0", "https://fontawesome.com/license/free")
)

@Composable
private fun LindoLicensesPage(onBack: () -> Unit, onOpenLink: (String) -> Unit) {
    val palette = LocalPalette.current
    LinDoFullScreenPage(onBack = onBack) {
        LinDoPageTopBar(title = "开源许可", onBack = onBack)
        HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 600.dp).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Text("感谢这些开源项目。以下列出主要组件及其许可原文。", fontSize = 13.sp,
                        lineHeight = 20.sp, color = palette.inkSecondary, modifier = Modifier.padding(16.dp))
                }
                items(componentLicenses, key = { it.name }) { entry ->
                    LinDoSettingRow(LinDoIcons.Code, entry.name, value = entry.license,
                        onClick = { onOpenLink(entry.url) })
                }
                item {
                    Text("Font Awesome 图标 © Fonticons, Inc.；社区徽章矢量由原始图标转换。", fontSize = 12.sp,
                        lineHeight = 18.sp, color = palette.inkTertiary, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}
