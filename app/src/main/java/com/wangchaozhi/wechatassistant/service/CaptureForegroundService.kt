package com.wangchaozhi.wechatassistant.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
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

class CaptureForegroundService : LifecycleService() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    private var widthPx: Int = 0
    private var heightPx: Int = 0
    private var densityDpi: Int = 0

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        lifecycleScope.launch {
            ServiceBus.captureCmd.collectLatest { cmd ->
                when (cmd) {
                    is ServiceBus.CaptureCmd.JustCapture -> {
                        val bmp = captureExcludingOverlay()
                        ServiceBus.lastBitmap.value = bmp
                    }
                    is ServiceBus.CaptureCmd.TakeAndAsk -> {
                        val app = App.from(this@CaptureForegroundService)
                        app.appendLog("TakeAndAsk start, prompt='${cmd.prompt}'")
                        val bmp = captureExcludingOverlay()
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

    private fun imageToBitmap(img: Image): Bitmap {
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

    private suspend fun captureExcludingOverlay(): Bitmap? {
        val wasOverlayActive = ServiceBus.overlayReady.value
        if (!wasOverlayActive) return capture()
        ServiceBus.overlayHidden.value = true
        return try {
            delay(180)
            drainBuffer()
            capture()
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

    private suspend fun capture(): Bitmap? = withContext(Dispatchers.Default) {
        val reader = imageReader ?: return@withContext null
        runCatching {
            reader.acquireLatestImage()?.use { img ->
                return@withContext imageToBitmap(img)
            }
        }
        val deferred = CompletableDeferred<Bitmap?>()
        reader.setOnImageAvailableListener({ r ->
            val bmp = runCatching {
                r.acquireLatestImage()?.use { imageToBitmap(it) }
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
