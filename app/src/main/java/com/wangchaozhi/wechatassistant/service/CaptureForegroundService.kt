package com.wangchaozhi.wechatassistant.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.wangchaozhi.wechatassistant.App
import com.wangchaozhi.wechatassistant.R
import com.wangchaozhi.wechatassistant.ui.MainActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import android.media.Image
import java.nio.ByteBuffer

class CaptureForegroundService : LifecycleService() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    private var widthPx: Int = 0
    private var heightPx: Int = 0
    private var densityDpi: Int = 0
    private var streaming = false
    private var streamHidOverlay = false
    private var streamFrameId = 0L
    private var lastStreamEmitMs = 0L

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        lifecycleScope.launch {
            ServiceBus.captureCmd.collectLatest { cmd ->
                when (cmd) {
                    is ServiceBus.CaptureCmd.JustCapture -> {
                        val bmp = captureExcludingOverlay(cmd.region)
                        ServiceBus.lastBitmap.value = bmp
                    }
                    ServiceBus.CaptureCmd.StartStream -> startFrameStream()
                    ServiceBus.CaptureCmd.StopStream -> stopFrameStream()
                    is ServiceBus.CaptureCmd.TakeAndAsk -> {
                        val app = App.from(this@CaptureForegroundService)
                        app.appendLog("TakeAndAsk start, prompt='${cmd.prompt}'")
                        val bmp = captureExcludingOverlay(cmd.region)
                        if (bmp == null) {
                            app.appendLog("capture() returned null")
                            ServiceBus.lastAiResult.value =
                                ServiceBus.AiResult.Failure("截图失败，请确认截图服务已启动")
                            return@collectLatest
                        }
                        app.appendLog("capture() ok ${bmp.width}x${bmp.height}, calling Qwen…")
                        ServiceBus.lastBitmap.value = bmp
                        val result = try {
                            app.screenshotAi.runWithBitmap(bmp, cmd.prompt)
                        } catch (t: Throwable) {
                            app.appendLog("runWithBitmap threw: ${t.javaClass.simpleName}: ${t.message}")
                            Result.failure(t)
                        }
                        ServiceBus.lastAiResult.value = result.fold(
                            onSuccess = {
                                app.appendLog("Qwen success, answer length=${it.length}")
                                ServiceBus.AiResult.Success(it)
                            },
                            onFailure = {
                                app.appendLog("Qwen failure: ${it.javaClass.simpleName}: ${it.message}")
                                ServiceBus.AiResult.Failure(
                                    it.message ?: it::class.java.simpleName,
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        if (data != null && resultCode != 0 && projection == null) {
            initProjection(resultCode, data)
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = NotificationCompat.Builder(this, App.CHANNEL_CAPTURE)
            .setContentTitle(getString(R.string.notif_capture_title))
            .setContentText(getString(R.string.notif_capture_text))
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun initProjection(resultCode: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java) ?: return
        val proj = mpm.getMediaProjection(resultCode, data) ?: return
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { releaseProjection() }
        }, Handler(mainLooper))
        projection = proj
        handlerThread = HandlerThread("capture-thread").apply { start() }
        bgHandler = Handler(handlerThread!!.looper)
        measureDisplay()
        imageReader = ImageReader.newInstance(widthPx, heightPx, PixelFormat.RGBA_8888, 2)
        virtualDisplay = proj.createVirtualDisplay(
            "wca-capture",
            widthPx, heightPx, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, bgHandler
        )
        ServiceBus.captureReady.value = true
    }

    private fun measureDisplay() {
        val wm = getSystemService(WindowManager::class.java)
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            widthPx = bounds.width()
            heightPx = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            widthPx = metrics.widthPixels
            heightPx = metrics.heightPixels
        }
        densityDpi = resources.displayMetrics.densityDpi
    }

    private fun imageToBitmap(img: Image, region: Rect? = null): Bitmap {
        val crop = region?.let { clampRegion(it) }
        if (crop != null) return imageRegionToBitmap(img, crop)

        val plane = img.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * widthPx
        val bmp = Bitmap.createBitmap(
            widthPx + rowPadding / pixelStride,
            heightPx,
            Bitmap.Config.ARGB_8888
        )
        bmp.copyPixelsFromBuffer(plane.buffer)
        return if (rowPadding == 0) bmp
        else Bitmap.createBitmap(bmp, 0, 0, widthPx, heightPx)
    }

    private fun imageRegionToBitmap(img: Image, crop: Rect): Bitmap {
        val plane = img.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val buffer = plane.buffer
        val w = crop.width()
        val h = crop.height()
        val bytesPerPixel = 4
        val pixels = ByteArray(w * h * bytesPerPixel)

        if (pixelStride == bytesPerPixel) {
            repeat(h) { y ->
                buffer.position((crop.top + y) * rowStride + crop.left * pixelStride)
                buffer.get(pixels, y * w * bytesPerPixel, w * bytesPerPixel)
            }
        } else {
            val px = ByteArray(pixelStride)
            repeat(h) { y ->
                repeat(w) { x ->
                    buffer.position((crop.top + y) * rowStride + (crop.left + x) * pixelStride)
                    buffer.get(px, 0, pixelStride)
                    val dst = (y * w + x) * bytesPerPixel
                    pixels[dst] = px[0]
                    pixels[dst + 1] = px.getOrElse(1) { 0 }
                    pixels[dst + 2] = px.getOrElse(2) { 0 }
                    pixels[dst + 3] = px.getOrElse(3) { -1 }
                }
            }
        }

        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
        }
    }

    private fun clampRegion(region: Rect): Rect? {
        val l = region.left.coerceIn(0, widthPx - 1)
        val t = region.top.coerceIn(0, heightPx - 1)
        val r = region.right.coerceIn(l + 1, widthPx)
        val b = region.bottom.coerceIn(t + 1, heightPx)
        if (r - l < 8 || b - t < 8) return null
        return Rect(l, t, r, b)
    }

    private suspend fun captureExcludingOverlay(region: Rect? = null): Bitmap? {
        val wasOverlayActive = ServiceBus.overlayReady.value
        if (!wasOverlayActive) return capture(region)
        ServiceBus.overlayHidden.value = true
        return try {
            delay(180)
            drainBuffer()
            capture(region)
        } finally {
            ServiceBus.overlayHidden.value = false
        }
    }

    private fun drainBuffer() {
        val reader = imageReader ?: return
        repeat(3) {
            runCatching { reader.acquireLatestImage()?.close() }
        }
    }

    private fun startFrameStream() {
        if (streaming) return
        val reader = imageReader ?: return
        streaming = true
        lastStreamEmitMs = 0L
        ServiceBus.streamFrame.value = null
        streamHidOverlay = ServiceBus.overlayReady.value
        if (streamHidOverlay) ServiceBus.overlayHidden.value = true
        bgHandler?.postDelayed({
            if (!streaming) return@postDelayed
            drainBuffer()
            reader.setOnImageAvailableListener({ r ->
                val img = runCatching { r.acquireLatestImage() }.getOrNull()
                    ?: return@setOnImageAvailableListener
                // 限流：始终取走并释放帧，避免 ImageReader 缓冲占满后停止产帧；
                // 但仅按最小间隔构建整屏位图，省掉 60fps 全屏 Bitmap 分配带来的 GC 压力。
                // 页面变化是持续态而非瞬时闪烁，间隔内丢帧不影响检出。
                val nowMs = android.os.SystemClock.uptimeMillis()
                if (nowMs - lastStreamEmitMs < STREAM_MIN_INTERVAL_MS) {
                    img.close()
                    return@setOnImageAvailableListener
                }
                lastStreamEmitMs = nowMs
                val bmp = runCatching { img.use { imageToBitmap(it) } }.getOrNull()
                if (bmp != null) {
                    ServiceBus.streamFrame.value =
                        ServiceBus.CaptureFrame(++streamFrameId, bmp)
                }
            }, bgHandler)
        }, if (streamHidOverlay) 180L else 0L)
    }

    private fun stopFrameStream() {
        if (!streaming) return
        streaming = false
        imageReader?.setOnImageAvailableListener(null, null)
        if (streamHidOverlay) ServiceBus.overlayHidden.value = false
        streamHidOverlay = false
    }

    private suspend fun capture(region: Rect? = null): Bitmap? = withContext(Dispatchers.Default) {
        stopFrameStream()
        val reader = imageReader ?: return@withContext null
        runCatching {
            reader.acquireLatestImage()?.use { img ->
                return@withContext imageToBitmap(img, region)
            }
        }
        val deferred = CompletableDeferred<Bitmap?>()
        reader.setOnImageAvailableListener({ r ->
            val bmp = runCatching {
                r.acquireLatestImage()?.use { imageToBitmap(it, region) }
            }.getOrNull()
            r.setOnImageAvailableListener(null, null)
            deferred.complete(bmp)
        }, bgHandler)
        withTimeoutOrNull(5_000) { deferred.await() }.also {
            reader.setOnImageAvailableListener(null, null)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val vd = virtualDisplay ?: return
        val oldW = widthPx
        val oldH = heightPx
        measureDisplay()
        if (oldW == widthPx && oldH == heightPx) return
        runCatching {
            imageReader?.close()
            imageReader = ImageReader.newInstance(widthPx, heightPx, PixelFormat.RGBA_8888, 2)
            vd.resize(widthPx, heightPx, densityDpi)
            vd.surface = imageReader?.surface
        }
    }

    private fun releaseProjection() {
        stopFrameStream()
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        projection?.stop(); projection = null
        handlerThread?.quitSafely(); handlerThread = null
        bgHandler = null
        ServiceBus.captureReady.value = false
    }

    override fun onDestroy() {
        releaseProjection()
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 0x10A1
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        // 流式帧的最小产帧间隔（毫秒）。约 40fps：足够低的检出延迟，又避免每个刷新帧
        // 都构建整屏位图。变化检测无需 60fps，调大可进一步省电、调小可降延迟。
        private const val STREAM_MIN_INTERVAL_MS = 25L

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, CaptureForegroundService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CaptureForegroundService::class.java))
        }
    }
}
