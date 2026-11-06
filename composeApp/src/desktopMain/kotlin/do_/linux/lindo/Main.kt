package do_.linux.lindo

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import do_.linux.lindo.ui.LinDoApp

fun main() = application {
    val windowState = rememberWindowState(
        size = DpSize(1240.dp, 820.dp),
        position = WindowPosition.Aligned(androidx.compose.ui.Alignment.Center)
    )
    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "Lindo — Linux.do High-Performance Client (macOS / Windows / Android)"
    ) {
        LinDoApp()
    }
}
