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
import com.wangchaozhi.wechatassistant.data.repo.TriggerRepository
import com.wangchaozhi.wechatassistant.feature.ai.AiTapUseCase
import com.wangchaozhi.wechatassistant.feature.ai.AiReasoningEffort
import com.wangchaozhi.wechatassistant.feature.ai.ModelScopeRepository
import com.wangchaozhi.wechatassistant.feature.ai.ScreenshotAiUseCase
import com.wangchaozhi.wechatassistant.feature.ai.VisionAiRepository
import com.wangchaozhi.wechatassistant.feature.match.TemplateMatchUseCase
import com.wangchaozhi.wechatassistant.util.WifiAdbManager
import com.wangchaozhi.wechatassistant.feature.qwen.QwenRepository
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class App : Application() {

    val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "wca.db")
            .addMigrations(
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_10_11,
                AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13,
            )
            .fallbackToDestructiveMigration()
            .build()
    }

    val scriptRepo: ScriptRepository by lazy { ScriptRepository(database.scriptDao()) }

    val triggerRepo: TriggerRepository by lazy { TriggerRepository(database.triggerDao()) }

    val aiAnswerRepo: AiAnswerRepository by lazy {
        AiAnswerRepository(this, database.aiAnswerDao())
    }

    val settingsRepo: SettingsRepository by lazy { SettingsRepository(this) }

    private val logFile: File by lazy {
        File(filesDir, "qwen_debug.log")
    }

    fun appendLog(line: String) {
        runCatching {
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            FileWriter(logFile, true).use { it.append("[$ts] $line\n") }
        }
    }

    fun readLog(maxChars: Int = 60_000): String =
        runCatching {
            if (!logFile.exists()) return@runCatching ""
            val text = logFile.readText()
            if (text.length <= maxChars) text else text.takeLast(maxChars)
        }.getOrDefault("")

    fun clearLog() {
        runCatching { logFile.writeText("") }
    }

    fun logFileSizeBytes(): Long =
        runCatching { if (logFile.exists()) logFile.length() else 0L }.getOrDefault(0L)

    fun debugBitmapFiles(): List<File> =
        if (!BuildConfig.DEBUG) {
            emptyList()
        } else {
            runCatching {
                filesDir.listFiles { file ->
                    file.isFile && file.name.startsWith("dbg_") && file.name.endsWith(".png")
                }
                    ?.sortedWith(compareBy<File> { it.name.removePrefix("dbg_") }.thenBy { it.lastModified() })
                    .orEmpty()
            }.getOrDefault(emptyList())
        }

    /** 删除所有调试图片(dbg_*.png)，返回删掉的张数。 */
    fun clearDebugBitmaps(): Int =
        if (!BuildConfig.DEBUG) {
            0
        } else {
            runCatching { debugBitmapFiles().count { it.delete() } }.getOrDefault(0)
        }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor { msg -> appendLog(msg) }.apply {
                level = HttpLoggingInterceptor.Level.BODY
                redactHeader("Authorization")
            })
            .build()
    }

    val qwenRepo: QwenRepository by lazy { QwenRepository(httpClient) { settingsRepo.qwenApiKey } }

    private val modelScopeRepo: ModelScopeRepository by lazy {
        ModelScopeRepository(httpClient) { settingsRepo.modelScopeApiKey }
    }

    val visionAi: VisionAiRepository by lazy {
        VisionAiRepository(
            qwen = qwenRepo,
            modelScope = modelScopeRepo,
            defaultProvider = {
                com.wangchaozhi.wechatassistant.feature.ai.AiProvider
                    .parse(settingsRepo.defaultAiProvider)
                    ?: com.wangchaozhi.wechatassistant.feature.ai.AiProvider.DASHSCOPE
            },
            defaultDashScopeModel = { settingsRepo.qwenModel },
            defaultModelScopeModel = { settingsRepo.modelScopeModel },
            defaultReasoningEffort = {
                AiReasoningEffort.parse(settingsRepo.aiReasoningEffort)
            },
        )
    }

    val screenshotAi: ScreenshotAiUseCase by lazy {
        ScreenshotAiUseCase(this, visionAi, aiAnswerRepo)
    }

    val aiTap: AiTapUseCase by lazy { AiTapUseCase(this, visionAi, aiAnswerRepo) }

    val templateMatch: TemplateMatchUseCase by lazy { TemplateMatchUseCase(this) }

    override fun onCreate() {
        super.onCreate()
        registerNotificationChannels()
        // settingsRepo 首次访问会创建 EncryptedSharedPreferences（Keystore + 磁盘 I/O，开销较大），
        // WifiAdbManager.install 还会顺带做一次 ADB 自动重连，放到后台线程避免阻塞应用启动。
        Thread {
            runCatching { WifiAdbManager.install(this, settingsRepo) }
            // 进程启动后，把已启用的「定时触发」重新登记到 AlarmManager（重启/被杀后闹钟会丢失）。
            runCatching {
                kotlinx.coroutines.runBlocking {
                    com.wangchaozhi.wechatassistant.trigger.ScheduleTriggers.rescheduleAll(this@App)
                }
            }
        }.start()
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
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ADB,
                getString(R.string.channel_adb_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TRIGGER,
                getString(R.string.channel_trigger_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
    }

    companion object {
        const val CHANNEL_CAPTURE = "ch_capture"
        const val CHANNEL_OVERLAY = "ch_overlay"
        const val CHANNEL_ADB = "ch_adb"
        const val CHANNEL_TRIGGER = "ch_trigger"

        fun from(ctx: Context): App = ctx.applicationContext as App
    }
}
