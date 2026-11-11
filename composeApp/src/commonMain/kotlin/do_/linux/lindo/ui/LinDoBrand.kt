package do_.linux.lindo.ui

import androidx.compose.foundation.BorderStroke
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
private fun brandCanvas(dark: Boolean) = if (dark) Color(0xFF0A0B0E) else Color(0xFFFFFFFF)
private fun brandText(dark: Boolean) = if (dark) Color(0xFFF3F4F6) else Color(0xFF1A1B1E)
private fun brandSecondary(dark: Boolean) = if (dark) Color(0xFF9CA3AF) else Color(0xFF5C626E)
private fun brandBorder(dark: Boolean) = if (dark) Color(0xFF262A33) else Color(0xFFE3E6ED)

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
 * Lindo 经典 Linux.do 双色品牌字标 (`Lin` + `do`)，字距舒展、`do` 随全局主题色实时联动
 */
@Composable
fun LindoColorWordmarkText(
    isDark: Boolean = LocalPalette.current.isDark,
    fontSizeSp: Float = 22f,
    linColor: Color? = null,
    doColor: Color? = null,
    modifier: Modifier = Modifier
) {
    val palette = LocalPalette.current
    val resolvedLinColor = linColor ?: if (palette.isDark == isDark) {
        palette.inkPrimary
    } else if (isDark) {
        Color(0xFFF3F4F6)
    } else {
        Color(0xFF1A1B1E)
    }
    val resolvedDoColor = doColor ?: if (palette.isDark == isDark) {
        palette.logoDoColor
    } else if (isDark) {
        Color(0xFFF6B73C)
    } else {
        Color(0xFFF0A020)
    }
    val styled = buildAnnotatedString {
        withStyle(SpanStyle(color = resolvedLinColor)) {
            append("Lin")
        }
        withStyle(SpanStyle(color = resolvedDoColor)) {
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
                    softWrap = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
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
    onOpenAppIcon: (() -> Unit)? = null,
    onGuestBrowse: (() -> Unit)? = null
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(brandCanvas(isDark))
        .windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars)) {
        val artwork = LocalLinDoAppIcon.current?.artwork
        val heroSize = if (artwork == null) {
            if (maxHeight < 680.dp) 172.dp else 220.dp
        } else {
            if (maxHeight < 680.dp) 148.dp else 188.dp
        }
        val actionShape = RoundedCornerShape(16.dp)
        Column(Modifier.align(Alignment.Center).widthIn(max = 440.dp).fillMaxSize()
            .padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                LinDoBrandWordmark(isDark = isDark, modifier = Modifier.weight(1f))
                if (onOpenAppIcon != null) {
                    IconButton(onClick = onOpenAppIcon, modifier = Modifier.size(48.dp)) {
                        Icon(LinDoIcons.AppsGrid, contentDescription = "应用图标",
                            tint = brandSecondary(isDark), modifier = Modifier.size(20.dp))
                    }
                }
                IconButton(onClick = onToggleDark, modifier = Modifier.size(48.dp)) {
                    Icon(if (isDark) LinDoIcons.Sun else LinDoIcons.Moon,
                        contentDescription = if (isDark) "切换至浅色模式" else "切换至深色模式",
                        tint = brandSecondary(isDark), modifier = Modifier.size(21.dp))
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    if (artwork != null) {
                        LinDoIconArtworkImage(artwork, Modifier.size(heroSize))
                    } else {
                        Image(painterResource(Res.drawable.lindo_mascot_welcome), "零度娘向你挥手",
                            Modifier.size(heroSize), contentScale = ContentScale.Fit)
                    }
                    Spacer(Modifier.height(18.dp))
                    Text("你好，佬友。", fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp,
                        color = brandText(isDark), textAlign = TextAlign.Center, lineHeight = 36.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("欢迎来到 Lindo · 连接 LINUX DO，发现有趣的讨论。", fontSize = 14.sp, lineHeight = 22.sp,
                        color = brandSecondary(isDark), textAlign = TextAlign.Center)
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onPrimaryWebLogin,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandInk),
                    shape = actionShape,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    Text("使用 LINUX DO 登录", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(10.dp))
                    Icon(LinDoIcons.ArrowRight, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                OutlinedButton(
                    onClick = onBrowserOneClickAuth,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                    shape = actionShape,
                    border = BorderStroke(1.dp, brandBorder(isDark)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = brandText(isDark)
                    ),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Icon(
                        LinDoIcons.Globe,
                        contentDescription = null,
                        tint = brandSecondary(isDark),
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("系统浏览器授权", fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
                }
                if (onGuestBrowse != null) {
                    OutlinedButton(
                        onClick = onGuestBrowse,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                        shape = actionShape,
                        border = BorderStroke(1.dp, brandBorder(isDark)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Transparent,
                            contentColor = brandText(isDark)
                        ),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        Icon(
                            LinDoIcons.Eye,
                            contentDescription = null,
                            tint = brandSecondary(isDark),
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("以游客模式浏览", fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
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
                    if (onRetry != null) {
                        LinDoStatePanel(
                            LinDoMascotState.NetworkError,
                            detail = statusSubtitle.ifBlank { statusTitle },
                            onRetry = onRetry
                        )
                    } else {
                        LinDoMascot(state = LinDoMascotState.Loading, size = 64.dp)
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = statusTitle,
                                color = brandSecondary(isDark),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                                lineHeight = 19.sp
                            )
                            Spacer(Modifier.width(5.dp))
                            LinDoWaveDots(color = brandSecondary(isDark), dotSize = 4.dp)
                        }
                        if (statusSubtitle.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(statusSubtitle, color = brandSecondary(isDark), fontSize = 13.sp,
                                lineHeight = 20.sp, textAlign = TextAlign.Center)
                        }
                        if (progress.isFinite()) {
                            Spacer(Modifier.height(22.dp))
                            // Actual WebView progress only. Session verification has no measured percentage.
                            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) },
                                color = BrandGold, trackColor = if (isDark) Color(0xFF39332A) else Color(0xFFEDE6DC),
                                modifier = Modifier.width(144.dp).height(3.dp))
                        }
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
