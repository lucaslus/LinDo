package do_.linux.lindo.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lindo.composeapp.generated.resources.Res
import lindo.composeapp.generated.resources.lindo_brand_mark
import lindo.composeapp.generated.resources.lindo_mascot_welcome
import org.jetbrains.compose.resources.painterResource

private val BrandGold = Color(0xFFF6B73C)
private val BrandInk = Color(0xFF342820)
private fun brandCanvas(dark: Boolean) = if (dark) Color(0xFF191815) else Color(0xFFFFFCF7)
private fun brandText(dark: Boolean) = if (dark) Color(0xFFF5F0E8) else BrandInk
private fun brandSecondary(dark: Boolean) = if (dark) Color(0xFFB8AFA2) else Color(0xFF746C61)

/** The exact same vector paths are used by SVG, Compose and Android adaptive icons. */
@Composable
internal fun LinDoGlassAppIcon(tileSize: Dp = 84.dp, isCircle: Boolean = false, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(Res.drawable.lindo_brand_mark),
        contentDescription = "LinDo 零度娘",
        modifier = modifier.size(tileSize).clip(if (isCircle) CircleShape else RoundedCornerShape(tileSize * 0.25f))
            .background(BrandGold).padding(tileSize * 0.07f),
        contentScale = ContentScale.Fit
    )
}

@Composable
fun LinDoBrandWordmark(isDark: Boolean = false, fontSizeSp: Float = 24f, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Image(painterResource(Res.drawable.lindo_brand_mark), contentDescription = null,
            modifier = Modifier.size((fontSizeSp * 1.5f).dp), contentScale = ContentScale.Fit)
        Text("LinDo", fontSize = fontSizeSp.sp, fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.8).sp, color = brandText(isDark), maxLines = 1)
    }
}

@Composable
internal fun LinDoWelcomeScreen(
    isDark: Boolean,
    onPrimaryWebLogin: () -> Unit,
    onBrowserOneClickAuth: () -> Unit,
    onToggleDark: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(brandCanvas(isDark))
        .windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars)) {
        val heroSize = if (maxHeight < 660.dp) 212.dp else 264.dp
        Column(Modifier.align(Alignment.Center).widthIn(max = 440.dp).fillMaxSize()
            .padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                LinDoBrandWordmark(isDark = isDark)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onToggleDark, modifier = Modifier.size(48.dp)) {
                    Icon(if (isDark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                        contentDescription = if (isDark) "切换至浅色模式" else "切换至深色模式",
                        tint = brandSecondary(isDark), modifier = Modifier.size(21.dp))
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(painterResource(Res.drawable.lindo_mascot_welcome), contentDescription = "零度娘向你挥手",
                        modifier = Modifier.size(heroSize), contentScale = ContentScale.Fit)
                    Spacer(Modifier.height(20.dp))
                    Text("你好，佬友。", fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp,
                        color = brandText(isDark), textAlign = TextAlign.Center, lineHeight = 36.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("连接 LINUX DO，发现有趣的讨论。", fontSize = 14.sp, lineHeight = 23.sp,
                        color = brandSecondary(isDark), textAlign = TextAlign.Center)
                }
            }
            Button(onClick = onPrimaryWebLogin, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandInk),
                shape = RoundedCornerShape(18.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)) {
                Text("使用 LINUX DO 登录", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onBrowserOneClickAuth, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = brandSecondary(isDark))) {
                Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(7.dp))
                Text("使用系统浏览器授权", fontSize = 13.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text("LINUX DO 第三方客户端", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                fontSize = 10.sp, letterSpacing = 0.7.sp, color = brandSecondary(isDark))
        }
    }
}

@Composable
fun LinDoMascotLoginTransitionView(
    isDark: Boolean,
    progress: Float,
    statusTitle: String = "正在连接 LINUX DO",
    statusSubtitle: String = "零度娘正在为你准备登录页面",
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null
) {
    CompositionLocalProvider(LocalLinDoDarkTheme provides isDark) {
        Column(modifier.fillMaxSize().background(brandCanvas(isDark))
            .windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 20.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                LinDoBrandWordmark(isDark = isDark)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Close, "取消登录", tint = brandSecondary(isDark), modifier = Modifier.size(21.dp))
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    LinDoMascot(state = if (onRetry != null) LinDoMascotState.NetworkError else LinDoMascotState.Loading, size = 188.dp)
                    Spacer(Modifier.height(24.dp))
                    Text(statusTitle.removeSuffix("..."), color = brandText(isDark), fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, lineHeight = 29.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(statusSubtitle, color = brandSecondary(isDark), fontSize = 13.sp,
                        textAlign = TextAlign.Center, lineHeight = 21.sp)
                    if (onRetry != null) {
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandInk),
                            shape = RoundedCornerShape(15.dp)) { Text("重新连接") }
                    } else if (progress.isFinite()) {
                        Spacer(Modifier.height(22.dp))
                        // Actual WebView progress only. Session verification has no measured percentage.
                        LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) },
                            color = BrandGold, trackColor = if (isDark) Color(0xFF39332A) else Color(0xFFEDE6DC),
                            modifier = Modifier.width(144.dp).height(3.dp))
                    }
                }
            }
            Text("连接过程中可随时返回", color = brandSecondary(isDark), modifier = Modifier.fillMaxWidth(),
                fontSize = 11.sp, textAlign = TextAlign.Center)
        }
    }
}
