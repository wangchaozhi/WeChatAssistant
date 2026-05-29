package com.wangchaozhi.wechatassistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import com.wangchaozhi.wechatassistant.data.db.AppDatabase
import com.wangchaozhi.wechatassistant.data.repo.AiAnswerRepository
import com.wangchaozhi.wechatassistant.data.repo.ScriptRepository
import com.wangchaozhi.wechatassistant.data.repo.SettingsRepository
import com.wangchaozhi.wechatassistant.feature.ai.AiTapUseCase
import com.wangchaozhi.wechatassistant.feature.ai.ScreenshotAiUseCase
import com.wangchaozhi.wechatassistant.util.ShizukuManager
import com.wangchaozhi.wechatassistant.feature.qwen.QwenRepository
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

class App : Application() {

    val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "wca.db")
            .fallbackToDestructiveMigration()
            .build()
    }

    val scriptRepo: ScriptRepository by lazy { ScriptRepository(database.scriptDao()) }

    val aiAnswerRepo: AiAnswerRepository by lazy {
        AiAnswerRepository(this, database.aiAnswerDao())
    }

    val settingsRepo: SettingsRepository by lazy { SettingsRepository(this) }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.HEADERS
                else HttpLoggingInterceptor.Level.NONE
            })
            .build()
    }

    val qwenRepo: QwenRepository by lazy { QwenRepository(httpClient) { settingsRepo.qwenApiKey } }

    val screenshotAi: ScreenshotAiUseCase by lazy {
        ScreenshotAiUseCase(this, qwenRepo, aiAnswerRepo)
    }

    val aiTap: AiTapUseCase by lazy { AiTapUseCase(this, qwenRepo, aiAnswerRepo) }

    override fun onCreate() {
        super.onCreate()
        registerNotificationChannels()
        runCatching { ShizukuManager.install() }
    }

    private fun registerNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CAPTURE,
                getString(R.string.channel_capture_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_OVERLAY,
                getString(R.string.channel_overlay_name),
                NotificationManager.IMPORTANCE_MIN,
            )
        )
    }

    companion object {
        const val CHANNEL_CAPTURE = "ch_capture"
        const val CHANNEL_OVERLAY = "ch_overlay"

        fun from(ctx: Context): App = ctx.applicationContext as App
    }
}
