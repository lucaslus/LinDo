package do_.linux.lindo.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import do_.linux.lindo.ui.components.LinDoIcons
import lindo.composeapp.generated.resources.Res
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
    val icon = LocalLinDoAppIcon.current ?: LinDoAppIcon.Classic
    LinDoIconArtworkImage(
        artwork = icon.artwork,
        modifier = modifier.size(tileSize).clip(if (isCircle) CircleShape else RoundedCornerShape(tileSize * 0.25f))
            .background(icon.background.color).padding(tileSize * 0.07f)
    )
}

/**
 * Lindo 经典 Linux.do 双色品牌字标 (`Lin` + `do`)，字距舒展、克制耐看
 */
@Composable
fun LindoColorWordmarkText(
    isDark: Boolean = false,
    fontSizeSp: Float = 22f,
    modifier: Modifier = Modifier
) {
    val styled = buildAnnotatedString {
        withStyle(SpanStyle(color = if (isDark) Color(0xFFF8FAFC) else Color(0xFF201D19))) {
            append("Lin")
        }
        withStyle(SpanStyle(color = if (isDark) Color(0xFFF6B73C) else Color(0xFFF0A020))) {
            append("do")
        }
    }
    Text(
        text = styled,
        fontSize = fontSizeSp.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.4.sp,
        maxLines = 1,
        modifier = modifier
    )
}

@Composable
fun LinDoBrandWordmark(
    isDark: Boolean = false,
    fontSizeSp: Float = 24.5f,
    iconSize: Dp = 36.dp,
    showMotto: Boolean = true,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Box(
            modifier = Modifier.size(iconSize),
            contentAlignment = Alignment.Center
        ) {
            LinDoIconArtworkImage(
                artwork = LocalLinDoAppIcon.current?.artwork ?: LinDoIconArtwork.Classic,
                modifier = Modifier.size(iconSize)
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            LindoColorWordmarkText(
                isDark = isDark,
                fontSizeSp = fontSizeSp,
                modifier = Modifier.alignByBaseline()
            )
            if (showMotto) {
                Text(
                    text = "真诚 · 友善 · 团结 · 专业",
                    fontSize = 10.8.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.2.sp,
                    color = brandSecondary(isDark),
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline()
                )
            }
        }
    }
}

@Composable
internal fun LinDoWelcomeScreen(
    isDark: Boolean,
    onPrimaryWebLogin: () -> Unit,
    onBrowserOneClickAuth: () -> Unit,
    onToggleDark: () -> Unit,
    onOpenAppIcon: (() -> Unit)? = null
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(brandCanvas(isDark))
        .windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars)) {
        val artwork = LocalLinDoAppIcon.current?.artwork
        val heroSize = if (artwork == null) {
            if (maxHeight < 660.dp) 212.dp else 264.dp
        } else {
            if (maxHeight < 660.dp) 176.dp else 212.dp
        }
        Column(Modifier.align(Alignment.Center).widthIn(max = 440.dp).fillMaxSize()
            .padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                LinDoBrandWordmark(isDark = isDark, modifier = Modifier.weight(1f))
                if (onOpenAppIcon != null) {
                    IconButton(onClick = onOpenAppIcon, modifier = Modifier.size(48.dp)) {
                        Icon(LinDoIcons.Image, contentDescription = "应用图标",
                            tint = brandSecondary(isDark), modifier = Modifier.size(21.dp))
                    }
                }
                IconButton(onClick = onToggleDark, modifier = Modifier.size(48.dp)) {
                    Icon(if (isDark) LinDoIcons.Sun else LinDoIcons.Moon,
                        contentDescription = if (isDark) "切换至浅色模式" else "切换至深色模式",
                        tint = brandSecondary(isDark), modifier = Modifier.size(21.dp))
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    if (artwork != null) {
                        LinDoIconArtworkImage(artwork, Modifier.size(heroSize))
                    } else {
                        Image(painterResource(Res.drawable.lindo_mascot_welcome), "零度娘向你挥手",
                            Modifier.size(heroSize), contentScale = ContentScale.Fit)
                    }
                    Spacer(Modifier.height(20.dp))
                    Text("你好，佬友。", fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp,
                        color = brandText(isDark), textAlign = TextAlign.Center, lineHeight = 36.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("欢迎来到 Lindo · 连接 LINUX DO，发现有趣的讨论。", fontSize = 14.sp, lineHeight = 23.sp,
                        color = brandSecondary(isDark), textAlign = TextAlign.Center)
                }
            }
            Button(onClick = onPrimaryWebLogin, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandInk),
                shape = RoundedCornerShape(18.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)) {
                Text("使用 LINUX DO 登录", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                Icon(LinDoIcons.ArrowRight, contentDescription = null, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onBrowserOneClickAuth, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = brandSecondary(isDark))) {
                Icon(LinDoIcons.ExternalLink, contentDescription = null, modifier = Modifier.size(15.dp))
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
    statusTitle: String = "加载中",
    statusSubtitle: String = "",
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
                    Icon(LinDoIcons.Close, "关闭网页", tint = brandSecondary(isDark), modifier = Modifier.size(21.dp))
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    LinDoMascot(state = if (onRetry != null) LinDoMascotState.NetworkError else LinDoMascotState.Loading, size = 64.dp)
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = statusTitle,
                            color = brandSecondary(isDark),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            lineHeight = 19.sp
                        )
                        if (onRetry == null) {
                            Spacer(Modifier.width(5.dp))
                            LinDoWaveDots(color = brandSecondary(isDark), dotSize = 4.dp)
                        }
                    }
                    if (statusSubtitle.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(statusSubtitle, color = brandSecondary(isDark), fontSize = 13.sp,
                            lineHeight = 20.sp, textAlign = TextAlign.Center)
                    }
                    if (onRetry != null) {
                        Spacer(Modifier.height(14.dp))
                        TextButton(
                            onClick = onRetry,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = LinDoIcons.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = brandSecondary(isDark)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text("重新连接", fontSize = 13.sp, color = brandSecondary(isDark))
                        }
                    } else if (progress.isFinite()) {
                        Spacer(Modifier.height(22.dp))
                        // Actual WebView progress only. Session verification has no measured percentage.
                        LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) },
                            color = BrandGold, trackColor = if (isDark) Color(0xFF39332A) else Color(0xFFEDE6DC),
                            modifier = Modifier.width(144.dp).height(3.dp))
                    }
                }
            }
            if (onRetry == null) {
                Text("连接过程中可随时返回", color = brandSecondary(isDark), modifier = Modifier.fillMaxWidth(),
                    fontSize = 11.sp, textAlign = TextAlign.Center)
            }
        }
    }
}
