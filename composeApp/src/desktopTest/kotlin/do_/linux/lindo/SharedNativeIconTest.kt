package do_.linux.lindo

import do_.linux.lindo.ui.*
import kotlin.test.*

class SharedNativeIconTest {
    @Test fun sharedArtworkRendersWithBothSystemBackgrounds() {
        for (dark in listOf(false, true)) {
            AppAppearance.systemDark = dark
            LinDoIconArtwork.entries.forEach { art ->
                val image = DesktopAppIconManager.render(LinDoAppIcon(art, LinDoIconBackground.System))
                assertEquals(512, image.width)
                assertEquals(if (dark) 0xff121212.toInt() else -1, image.getRGB(256, 40))
                assertEquals(0, image.getRGB(0, 0))
            }
        }
    }
}
