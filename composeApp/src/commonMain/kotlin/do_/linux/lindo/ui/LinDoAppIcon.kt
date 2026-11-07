package do_.linux.lindo.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import do_.linux.lindo.ui.components.LinDoIcons
import lindo.composeapp.generated.resources.Res
import lindo.composeapp.generated.resources.lindo_brand_mark
import lindo.composeapp.generated.resources.lindo_expression_happy
import lindo.composeapp.generated.resources.lindo_expression_sleepy
import lindo.composeapp.generated.resources.lindo_expression_surprised
import lindo.composeapp.generated.resources.lindo_expression_wink
import lindo.composeapp.generated.resources.lindo_linuxdo_mark
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

internal enum class LinDoIconBackground(val key: String, val label: String, val color: Color) {
    White("white", "白底", Color.White),
    Black("black", "黑底", Color(0xFF121212)),
    Amber("amber", "经典金底", Color(0xFFF6B73C))
}

internal enum class LinDoIconArtwork(
    val key: String,
    val label: String,
    val expression: DrawableResource? = null
) {
    Happy("happy", "开心", Res.drawable.lindo_expression_happy),
    Classic("classic", "经典"),
    Wink("wink", "眨眼", Res.drawable.lindo_expression_wink),
    Surprised("surprised", "惊讶", Res.drawable.lindo_expression_surprised),
    Sleepy("sleepy", "困困", Res.drawable.lindo_expression_sleepy),
    Official("official", "LINUX DO");

    val description: String
        get() = if (this == Official) "LINUX DO 标志" else "零度娘（$label）"

    val backgrounds: List<LinDoIconBackground>
        get() = if (this == Classic) LinDoIconBackground.entries
        else listOf(LinDoIconBackground.White, LinDoIconBackground.Black)
}

internal data class LinDoAppIcon(val artwork: LinDoIconArtwork, val background: LinDoIconBackground) {
    init {
        require(background in artwork.backgrounds)
    }

    val key: String get() = "${artwork.key}_${background.key}"

    companion object {
        val Default = LinDoAppIcon(LinDoIconArtwork.Happy, LinDoIconBackground.White)
        val Classic = LinDoAppIcon(LinDoIconArtwork.Classic, LinDoIconBackground.Amber)
        val Choices = LinDoIconArtwork.entries.flatMap { art -> art.backgrounds.map { LinDoAppIcon(art, it) } }
    }
}

internal val LocalLinDoAppIcon = staticCompositionLocalOf<LinDoAppIcon?> { null }

@Composable
internal fun LinDoIconArtworkImage(
    artwork: LinDoIconArtwork,
    modifier: Modifier,
    contentDescription: String? = artwork.description
) {
    Box(modifier) {
        val base = if (artwork == LinDoIconArtwork.Official) Res.drawable.lindo_linuxdo_mark else Res.drawable.lindo_brand_mark
        Image(painterResource(base), contentDescription, Modifier.matchParentSize(), contentScale = ContentScale.Fit)
        artwork.expression?.let { overlay ->
            Image(painterResource(overlay), null, Modifier.matchParentSize(), contentScale = ContentScale.Fit)
        }
    }
}

@Composable
internal fun LinDoLauncherIconTile(icon: LinDoAppIcon, modifier: Modifier) {
    val shape = RoundedCornerShape(25)
    LinDoIconArtworkImage(
        artwork = icon.artwork,
        modifier = modifier.clip(shape)
            .background(icon.background.color)
            .border(0.5.dp, Color.Black.copy(alpha = 0.12f), shape)
            .padding(4.dp),
        contentDescription = null
    )
}

@Composable
internal fun LinDoAppIconPage(
    current: LinDoAppIcon,
    onDismiss: () -> Unit,
    onApply: (LinDoAppIcon) -> Boolean
) {
    var pending by remember(current) { mutableStateOf(current) }
    var applyFailed by remember { mutableStateOf(false) }
    val palette = LocalPalette.current
    LinDoFullScreenPage(onBack = onDismiss) {
        LinDoPageTopBar(title = "应用图标", onBack = onDismiss) {
            TextButton(enabled = pending != current, onClick = { applyFailed = !onApply(pending) }) {
                Text("应用并重启")
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("图案", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.inkPrimary)
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinDoIconArtwork.entries.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { artwork ->
                            val selected = pending.artwork == artwork
                            val background = pending.background.takeIf { it in artwork.backgrounds }
                                ?: LinDoIconBackground.White
                            val choice = LinDoAppIcon(artwork, background)
                            Column(
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                                    .selectable(selected, role = Role.RadioButton, onClick = {
                                        pending = choice
                                        applyFailed = false
                                    }).padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                val ring = RoundedCornerShape(16.dp)
                                LinDoLauncherIconTile(
                                    choice,
                                    Modifier.size(66.dp)
                                        .border(1.5.dp, if (selected) palette.accent else Color.Transparent, ring)
                                        .padding(4.dp)
                                )
                                Text(
                                    artwork.label, fontSize = 13.sp, textAlign = TextAlign.Center,
                                    color = if (selected) palette.inkPrimary else palette.inkSecondary,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
            Text("底色", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.inkPrimary)
            Row(
                Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                pending.artwork.backgrounds.forEach { background ->
                    val selected = pending.background == background
                    Row(
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                            .selectable(selected, role = Role.RadioButton, onClick = {
                                pending = pending.copy(background = background)
                                applyFailed = false
                            }).padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
                    ) {
                        Box(Modifier.size(16.dp).clip(CircleShape).background(background.color)
                            .border(0.8.dp, palette.hairline, CircleShape))
                        Text(background.label, fontSize = 13.sp, color = palette.inkPrimary)
                        if (selected) Icon(LinDoIcons.Check, null, Modifier.size(13.dp), tint = palette.accent)
                    }
                }
            }
            Text(
                if (applyFailed) "切换图标失败，请重试" else "应用后会自动重启，首页图案也会一起更新。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = if (applyFailed) MaterialTheme.colorScheme.error else palette.inkSecondary
            )
        }
    }
}
