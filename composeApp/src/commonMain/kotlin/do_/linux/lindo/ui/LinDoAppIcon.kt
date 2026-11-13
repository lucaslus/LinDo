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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import do_.linux.lindo.core.copy.LinDoCommonCopy
import do_.linux.lindo.core.copy.LinDoCopyEntry
import do_.linux.lindo.core.copy.LinDoMessagesCopy
import lindo.composeapp.generated.resources.Res
import lindo.composeapp.generated.resources.lindo_brand_mark
import lindo.composeapp.generated.resources.lindo_expression_happy
import lindo.composeapp.generated.resources.lindo_expression_sleepy
import lindo.composeapp.generated.resources.lindo_expression_surprised
import lindo.composeapp.generated.resources.lindo_expression_wink
import lindo.composeapp.generated.resources.lindo_linuxdo_mark
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

internal enum class LinDoIconBackground(
    val key: String,
    val labelCopy: LinDoMessagesCopy,
    val color: Color
) {
    White("white", LinDoMessagesCopy.ICON_BG_LIGHT, Color.White),
    Black("black", LinDoMessagesCopy.ICON_BG_DARK, Color(0xFF121212)),
    Amber("amber", LinDoMessagesCopy.ICON_BG_CLASSIC, Color(0xFFF6B73C));

    val label: String get() = labelCopy.text
}

internal enum class LinDoIconArtwork(
    val key: String,
    val labelCopy: LinDoCopyEntry,
    val expression: DrawableResource? = null
) {
    Happy("happy", LinDoMessagesCopy.ICON_ART_HAPPY, Res.drawable.lindo_expression_happy),
    Classic("classic", LinDoMessagesCopy.ICON_ART_CLASSIC),
    Wink("wink", LinDoMessagesCopy.ICON_ART_WINK, Res.drawable.lindo_expression_wink),
    Surprised("surprised", LinDoMessagesCopy.ICON_ART_SURPRISED, Res.drawable.lindo_expression_surprised),
    Sleepy("sleepy", LinDoMessagesCopy.ICON_ART_SLEEPY, Res.drawable.lindo_expression_sleepy),
    Official("official", LinDoCommonCopy.LINUX_DO);

    val label: String get() = labelCopy.text

    val description: String
        get() = if (this == Official) {
            LinDoMessagesCopy.ICON_DESC_LOGO.text
        } else {
            LinDoMessagesCopy.ICON_DESC_MASCOT.format(label)
        }

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
        LinDoPageTopBar(title = LinDoMessagesCopy.ROW_APP_ICON.text, onBack = onDismiss) {
            TextButton(enabled = pending != current, onClick = { applyFailed = !onApply(pending) }) {
                Text(LinDoMessagesCopy.APPLY_ICON_AND_RESTART.text)
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(LinDoMessagesCopy.ICON_SECTION_ARTWORK.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.inkPrimary)
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
                                    .pointerHoverIcon(PointerIcon.Hand)
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
            Text(LinDoMessagesCopy.ICON_SECTION_BACKGROUND.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.inkPrimary)
            Row(
                Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                pending.artwork.backgrounds.forEach { background ->
                    val selected = pending.background == background
                    Row(
                        modifier = Modifier.weight(1f, fill = false).heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                            .pointerHoverIcon(PointerIcon.Hand)
                            .selectable(selected, role = Role.RadioButton, onClick = {
                                pending = pending.copy(background = background)
                                applyFailed = false
                            }).padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            Modifier.size(32.dp)
                                .border(2.dp, if (selected) palette.accent else Color.Transparent, CircleShape)
                                .padding(4.dp)
                                .background(background.color, CircleShape)
                                .border(1.dp, palette.inkSecondary.copy(alpha = 0.4f), CircleShape)
                        )
                        Text(
                            background.label,
                            fontSize = 13.sp,
                            color = if (selected) palette.inkPrimary else palette.inkSecondary,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
            if (applyFailed) {
                Text(LinDoMessagesCopy.SWITCH_ICON_FAILED.text, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
