package do_.linux.lindo

import android.content.ContentValues
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import do_.linux.lindo.core.network.CookieSentinel
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import do_.linux.lindo.ui.AudioPlaybackState
import do_.linux.lindo.ui.PlatformAudioController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

internal suspend fun saveImageToAndroidGallery(
    context: Context,
    imageUrl: String,
    suggestedFileName: String
): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
        val bytes = fetchImageBytes(imageUrl)
            ?: error("下载图片失败，请稍后重试")
        val cleanName = suggestedFileName
            .replace(Regex("""[^a-zA-Z0-9._-]"""), "_")
            .ifBlank { "lindo_${System.currentTimeMillis()}.jpg" }
        val mime = when (cleanName.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }
        val finalName = if (cleanName.contains('.')) cleanName else "$cleanName.jpg"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, finalName)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/LinDo")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("无法写入系统相册")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(bytes)
                    out.flush()
                } ?: error("无法打开相册输出流")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "LinDo"
            ).apply { mkdirs() }
            val outFile = File(dir, finalName)
            outFile.writeBytes(bytes)
        }
        "已保存到相册 Pictures/LinDo"
    }
}

private suspend fun fetchImageBytes(rawUrl: String): ByteArray? {
    val webViewBin = runCatching {
        UserApiKeyAuthManager.webViewBinaryFetcher?.invoke(rawUrl)
    }.getOrNull()
    if (webViewBin != null && webViewBin.status in 200..299 && webViewBin.bytes.isNotEmpty()) {
        return webViewBin.bytes
    }

    return runCatching {
        val conn = URL(rawUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 25_000
        conn.instanceFollowRedirects = true
        val host = runCatching { URI(rawUrl).host?.lowercase().orEmpty() }.getOrDefault("")
        val cookies = CookieSentinel.buildCookieHeader(targetHost = host.ifBlank { "linux.do" })
        if (cookies.isNotBlank()) conn.setRequestProperty("Cookie", cookies)
        UserApiKeyAuthManager.customUserAgent?.takeIf { it.isNotBlank() }?.let {
            conn.setRequestProperty("User-Agent", it)
        }
        try {
            if (conn.responseCode in 200..299) conn.inputStream.use { it.readBytes() } else null
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}

internal class AndroidMediaPlayerAudioController(
    private val context: Context
) : PlatformAudioController {
    private val _state = mutableStateOf(AudioPlaybackState())
    override val state: State<AudioPlaybackState> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: MediaPlayer? = null
    private var preparedUrl: String? = null
    private var progressJob: Job? = null

    override fun togglePlayPause(url: String) {
        val current = player
        if (current != null && preparedUrl == url && !_state.value.isPreparing) {
            runCatching {
                if (current.isPlaying) {
                    current.pause()
                    progressJob?.cancel()
                    _state.value = _state.value.copy(
                        isPlaying = false,
                        positionMs = current.currentPosition.toLong()
                    )
                } else {
                    current.start()
                    _state.value = _state.value.copy(isPlaying = true, error = null)
                    startProgressTicker()
                }
            }.onFailure { err ->
                _state.value = _state.value.copy(isPlaying = false, error = err.message ?: "音频播放失败")
            }
            return
        }

        releasePlayerOnly()
        preparedUrl = url
        _state.value = AudioPlaybackState(isPreparing = true)
        runCatching {
            val headers = buildMediaHeaders(url)
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(context, Uri.parse(url), headers)
                setOnPreparedListener { ready ->
                    _state.value = AudioPlaybackState(
                        isPlaying = true,
                        isPreparing = false,
                        positionMs = 0L,
                        durationMs = ready.duration.coerceAtLeast(0).toLong()
                    )
                    ready.start()
                    startProgressTicker()
                }
                setOnCompletionListener { finished ->
                    progressJob?.cancel()
                    _state.value = _state.value.copy(
                        isPlaying = false,
                        positionMs = finished.duration.coerceAtLeast(0).toLong()
                    )
                }
                setOnErrorListener { _, _, _ ->
                    progressJob?.cancel()
                    _state.value = AudioPlaybackState(error = "无法播放此音频，请点击右侧外部打开")
                    true
                }
                prepareAsync()
            }
            player = mp
        }.onFailure {
            _state.value = AudioPlaybackState(error = "无法初始化音频播放器")
        }
    }

    override fun seekTo(positionMs: Long) {
        val mp = player ?: return
        runCatching {
            mp.seekTo(positionMs.coerceAtLeast(0L).toInt())
            _state.value = _state.value.copy(positionMs = positionMs.coerceAtLeast(0L))
        }
    }

    private fun startProgressTicker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                val mp = player ?: break
                if (!runCatching { mp.isPlaying }.getOrDefault(false)) break
                val pos = runCatching { mp.currentPosition.toLong() }.getOrDefault(_state.value.positionMs)
                val dur = runCatching { mp.duration.toLong() }.getOrDefault(_state.value.durationMs)
                _state.value = _state.value.copy(
                    isPlaying = true,
                    positionMs = pos.coerceAtLeast(0L),
                    durationMs = dur.coerceAtLeast(0L)
                )
                delay(400L)
            }
        }
    }

    private fun releasePlayerOnly() {
        progressJob?.cancel()
        progressJob = null
        player?.let { mp ->
            runCatching { if (mp.isPlaying) mp.stop() }
            runCatching { mp.release() }
        }
        player = null
        preparedUrl = null
    }

    override fun release() {
        releasePlayerOnly()
        scope.cancel()
    }
}

@Composable
internal fun AndroidInlineVideoPlayer(
    url: String,
    onOpenExternal: () -> Unit
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val headers = buildMediaHeaders(url)
            VideoView(ctx).apply {
                val controller = MediaController(ctx)
                controller.setAnchorView(this)
                setMediaController(controller)
                setVideoURI(Uri.parse(url), headers)
                setOnPreparedListener { mp ->
                    mp.isLooping = false
                    start()
                }
                setOnErrorListener { _, _, _ ->
                    onOpenExternal()
                    true
                }
            }
        }
    )
}

private fun buildMediaHeaders(rawUrl: String): Map<String, String> = buildMap {
    val host = runCatching { URI(rawUrl).host?.lowercase().orEmpty() }.getOrDefault("")
    val cookies = CookieSentinel.buildCookieHeader(targetHost = host.ifBlank { "linux.do" })
    if (cookies.isNotBlank()) put("Cookie", cookies)
    UserApiKeyAuthManager.customUserAgent?.takeIf { it.isNotBlank() }?.let {
        put("User-Agent", it)
    }
}
