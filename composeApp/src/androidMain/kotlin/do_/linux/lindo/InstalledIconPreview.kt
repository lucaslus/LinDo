package do_.linux.lindo

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private data class IconProbe(val bitmap: Bitmap?, val message: String)

/** Exercises Android's real resource inflater, not Compose's vector parser. Debug entry only. */
@Composable
fun InstalledIconPreview(component: ComponentName) {
    val context = LocalContext.current
    val result = remember(component) {
        try {
            val drawable = context.packageManager.getActivityIcon(component)
            val bitmap = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, 432, 432)
            drawable.draw(Canvas(bitmap))
            IconProbe(bitmap, "系统图标加载成功：${drawable.javaClass.simpleName}")
        } catch (error: Exception) {
            val reason = generateSequence(error as Throwable) { it.cause }
                .joinToString(" / ") { "${it.javaClass.simpleName}: ${it.message}" }
            IconProbe(null, "系统图标错误：$reason")
        }
    }
    Column(Modifier.fillMaxSize().padding(32.dp)) {
        Text(result.message)
        result.bitmap?.let { Image(it.asImageBitmap(), "已安装的应用图标", Modifier.size(108.dp)) }
    }
}
