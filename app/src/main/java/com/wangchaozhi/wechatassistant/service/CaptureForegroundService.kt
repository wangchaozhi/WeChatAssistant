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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                        val bmp = capture()
                        ServiceBus.lastBitmap.value = bmp
                    }
                    is ServiceBus.CaptureCmd.TakeAndAsk -> {
                        val bmp = capture() ?: return@collectLatest
                        ServiceBus.lastBitmap.value = bmp
                        App.from(this@CaptureForegroundService)
                            .screenshotAi
                            .runWithBitmap(bmp, cmd.prompt)
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

    private suspend fun capture(): Bitmap? = withContext(Dispatchers.Default) {
        val reader = imageReader ?: return@withContext null
        val deferred = CompletableDeferred<Bitmap?>()
        reader.setOnImageAvailableListener({ r ->
            try {
                val img = r.acquireLatestImage()
                if (img != null) {
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
                    val cropped = if (rowPadding == 0) bmp
                    else Bitmap.createBitmap(bmp, 0, 0, widthPx, heightPx)
                    img.close()
                    deferred.complete(cropped)
                }
            } catch (t: Throwable) {
                deferred.complete(null)
            }
            r.setOnImageAvailableListener(null, null)
        }, bgHandler)
        deferred.await()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (projection != null) {
            virtualDisplay?.release()
            imageReader?.close()
            measureDisplay()
            imageReader = ImageReader.newInstance(widthPx, heightPx, PixelFormat.RGBA_8888, 2)
            virtualDisplay = projection?.createVirtualDisplay(
                "wca-capture",
                widthPx, heightPx, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null, bgHandler
            )
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
