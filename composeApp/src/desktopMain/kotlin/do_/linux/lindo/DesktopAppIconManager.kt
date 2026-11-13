package do_.linux.lindo

import androidx.compose.runtime.*
import do_.linux.lindo.ui.*
import org.jetbrains.skia.*
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.awt.Taskbar
import java.awt.image.BufferedImage
import java.util.Properties
import javax.imageio.ImageIO

/** Renders the same vector artwork and background choices used by Android. */
internal object DesktopAppIconManager {
    var current by mutableStateOf(LinDoAppIcon.Default)
        private set
    private var persist: ((LinDoAppIcon) -> Unit)? = null
    private var renderedKey: String? = null

    fun install(props: Properties, save: (LinDoAppIcon) -> Unit) {
        persist = save
        current = LinDoAppIcon.Choices.firstOrNull { it.key == props.getProperty("ui_app_icon") }
            ?: LinDoAppIcon.Default.copy(background = LinDoIconBackground.fromAppearance(AppAppearance.iconAppearance))
        AppAppearance.onApplyIconAppearance = { select(current.copy(background = LinDoIconBackground.fromAppearance(it))) }
        AppAppearance.onSystemAppearanceChanged = { refresh() }
        javax.swing.SwingUtilities.invokeLater { refresh() }
    }

    fun select(icon: LinDoAppIcon): Boolean = runCatching {
        check(Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE))
        Taskbar.getTaskbar().iconImage = render(icon)
        current = icon
        AppAppearance.iconAppearance = icon.background.appearanceMode
        renderedKey = null
        persist?.invoke(icon)
        true
    }.getOrDefault(false)

    fun refresh() {
        val key = "${current.key}:${AppAppearance.systemDark}"
        if (renderedKey == key) return
        runCatching {
            if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
                Taskbar.getTaskbar().iconImage = render(current)
                renderedKey = key
            }
        }
    }

    internal fun render(icon: LinDoAppIcon): BufferedImage = Surface.makeRasterN32Premul(512, 512).use { surface ->
        val canvas = surface.canvas
        val background = when (icon.background) {
            LinDoIconBackground.System -> if (AppAppearance.systemDark) 0xff121212.toInt() else -1
            LinDoIconBackground.White -> -1
            LinDoIconBackground.Black -> 0xff121212.toInt()
            LinDoIconBackground.Amber -> 0xfff6b73c.toInt()
        }
        Paint().use { paint ->
            paint.color = background
            canvas.drawRRect(RRect.makeXYWH(32f, 32f, 448f, 448f, 100f), paint)
        }
        val layers = buildList {
            add(if (icon.artwork == LinDoIconArtwork.Official) "lindo_linuxdo_mark" else "lindo_brand_mark")
            if (icon.artwork.expression != null) add("lindo_expression_${icon.artwork.key}")
        }
        layers.forEach { name ->
            val xml = checkNotNull(javaClass.getResourceAsStream("/composeResources/lindo.composeapp.generated.resources/drawable/$name.xml"))
                .bufferedReader().use { it.readText() }
            val vector = checkNotNull(Jsoup.parse(xml, "", Parser.xmlParser()).selectFirst("vector"))
            canvas.save()
            canvas.translate(52f, 48f)
            canvas.scale(408f / vector.attr("android:viewportWidth").toFloat(), 408f / vector.attr("android:viewportHeight").toFloat())
            vector.select("path").forEach { node ->
                Path.makeFromSVGString(node.attr("android:pathData"))?.use { path ->
                    if (node.attr("android:fillType") == "evenOdd") path.fillMode = PathFillMode.EVEN_ODD
                    Paint().use { paint ->
                        val hex = node.attr("android:fillColor").removePrefix("#")
                        paint.color = (hex.toLong(16) or if (hex.length == 6) 0xff000000L else 0L).toInt()
                        canvas.drawPath(path, paint)
                    }
                }
            }
            canvas.restore()
        }
        surface.makeImageSnapshot().use { image ->
            checkNotNull(image.encodeToData()).use { data -> ImageIO.read(data.bytes.inputStream()) }
        }
    }
}
