package do_.linux.lindo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import do_.linux.lindo.core.network.QrLoginManager
import do_.linux.lindo.ui.LocalPalette
import do_.linux.lindo.ui.components.LinDoIcons
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

internal object AndroidQrDecoder {
    fun decodeFromImageUri(context: Context, uri: Uri): String? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        if (maxDim > 1280) {
            while (maxDim / sample > 1280) sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null
        val bitmap = if (decoded.config != Bitmap.Config.ARGB_8888) {
            decoded.copy(Bitmap.Config.ARGB_8888, false).also { decoded.recycle() }
        } else {
            decoded
        }
        return try {
            val w = bitmap.width
            val h = bitmap.height
            if (w <= 0 || h <= 0) return null
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            QrLoginManager.decodeQrFromArgbPixels(w, h, pixels)
        } finally {
            bitmap.recycle()
        }
    }

    internal fun extractTightYPlane(image: Image): ByteArray? {
        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val out = ByteArray(width * height)
        if (pixelStride == 1 && rowStride == width && buf.remaining() >= out.size) {
            buf.get(out)
            return out
        }
        val row = ByteArray(rowStride)
        for (y in 0 until height) {
            val remaining = buf.remaining()
            if (remaining <= 0) break
            val toRead = if (y == height - 1) minOf(remaining, rowStride) else rowStride
            if (toRead < (width - 1) * pixelStride + 1) break
            buf.get(row, 0, toRead)
            val dstOffset = y * width
            if (pixelStride == 1) {
                System.arraycopy(row, 0, out, dstOffset, width)
            } else {
                for (x in 0 until width) {
                    out[dstOffset + x] = row[x * pixelStride]
                }
            }
        }
        return out
    }

    internal fun decodeQrFromYuv(yBytes: ByteArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || yBytes.size < width * height) return null
        val source = PlanarYUVLuminanceSource(yBytes, width, height, 0, 0, width, height, false)
        val reader = QRCodeReader()
        val hints = mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.CHARACTER_SET to "UTF-8"
        )
        return runCatching {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull() ?: run {
            reader.reset()
            runCatching {
                reader.decode(BinaryBitmap(HybridBinarizer(source.invert())), hints).text?.trim()?.takeIf { it.isNotEmpty() }
            }.getOrNull()
        }
    }
}

@Composable
internal fun AndroidQrScannerPreview(
    onDecodedText: (String) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val palette = LocalPalette.current
    val latestOnDecoded by rememberUpdatedState(onDecodedText)
    val latestOnError by rememberUpdatedState(onError)

    var hasCameraPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var cameraUnavailable by remember { mutableStateOf<String?>(null) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPerm = granted
        if (!granted) {
            latestOnError("未授予相机权限，可切换「从相册识图」或「口令登录」")
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPerm) {
            permLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (!hasCameraPerm) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(LinDoIcons.QrCode, contentDescription = null, tint = palette.inkSecondary, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(10.dp))
            Text(
                text = "需要相机权限以实时扫描二维码",
                color = palette.inkSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("授予相机权限", fontSize = 13.sp)
            }
        }
        return
    }

    if (cameraUnavailable != null) {
        Box(modifier = Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
            Text(
                text = cameraUnavailable!!,
                color = palette.inkSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    val controller = remember { Camera2QrController(context.applicationContext) }

    DisposableEffect(Unit) {
        controller.onDecoded = { text ->
            ContextCompat.getMainExecutor(context).execute {
                latestOnDecoded(text)
            }
        }
        controller.onFailure = { reason ->
            ContextCompat.getMainExecutor(context).execute {
                cameraUnavailable = reason
                latestOnError(reason)
            }
        }
        onDispose {
            controller.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = controller.surfaceListener
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

private class Camera2QrController(
    private val appContext: Context
) {
    var onDecoded: ((String) -> Unit)? = null
    var onFailure: ((String) -> Unit)? = null

    private var textureView: TextureView? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSize: Size = Size(640, 480)
    private val lastAnalyzeMs = AtomicLong(0L)
    private val released = AtomicBoolean(false)
    private val matched = AtomicBoolean(false)

    val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCamera(surface, width, height)
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            release()
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun openCamera(surface: SurfaceTexture, viewWidth: Int, viewHeight: Int) {
        if (released.get()) return
        val thread = HandlerThread("LindoQrCam").apply { start() }
        val handler = Handler(thread.looper)
        bgThread = thread
        bgHandler = handler

        runCatching {
            val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull()
            if (cameraId == null) {
                onFailure?.invoke("未检测到可用摄像头，可通过相册或口令登录")
                return
            }

            val chars = manager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val previewChoices = map?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
            previewSize = chooseBestSize(previewChoices, 720, 720)
            val yuvChoices = map?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
            val yuvSize = chooseBestSize(yuvChoices, 640, 480)

            val reader = ImageReader.newInstance(yuvSize.width, yuvSize.height, ImageFormat.YUV_420_888, 2)
            reader.setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val now = System.currentTimeMillis()
                    if (released.get() || matched.get() || now - lastAnalyzeMs.get() < 180L) return@setOnImageAvailableListener
                    lastAnalyzeMs.set(now)
                    val yBytes = AndroidQrDecoder.extractTightYPlane(img) ?: return@setOnImageAvailableListener
                    val decoded = AndroidQrDecoder.decodeQrFromYuv(yBytes, img.width, img.height)
                    if (decoded != null && matched.compareAndSet(false, true)) {
                        onDecoded?.invoke(decoded)
                    }
                } finally {
                    img.close()
                }
            }, handler)
            imageReader = reader

            surface.setDefaultBufferSize(previewSize.width, previewSize.height)
            val previewSurface = Surface(surface)
            val readerSurface = reader.surface

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (released.get()) {
                        camera.close()
                        return
                    }
                    cameraDevice = camera
                    runCatching {
                        val reqBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(previewSurface)
                            addTarget(readerSurface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }
                        @Suppress("DEPRECATION")
                        camera.createCaptureSession(
                            listOf(previewSurface, readerSurface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    if (released.get()) {
                                        session.close()
                                        return
                                    }
                                    captureSession = session
                                    runCatching {
                                        session.setRepeatingRequest(reqBuilder.build(), null, handler)
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    onFailure?.invoke("摄像头预览初始化失败，请使用相册或口令登录")
                                }
                            },
                            handler
                        )
                    }.onFailure {
                        onFailure?.invoke("无法开启摄像头预览")
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (cameraDevice === camera) cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cameraDevice === camera) cameraDevice = null
                    onFailure?.invoke("摄像头打开失败 ($error)")
                }
            }, handler)
        }.onFailure {
            onFailure?.invoke("未找到可用摄像头，请通过相册或口令登录")
        }
    }

    private fun chooseBestSize(choices: Array<out Size>, targetW: Int, targetH: Int): Size {
        if (choices.isEmpty()) return Size(targetW, targetH)
        return choices.minByOrNull { s ->
            val areaPenalty = abs(s.width * s.height - targetW * targetH)
            areaPenalty + if (s.width > 1280 || s.height > 1280) 1_000_000 else 0
        } ?: Size(targetW, targetH)
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { bgThread?.quitSafely() }
        bgThread = null
        bgHandler = null
        textureView = null
    }
}
