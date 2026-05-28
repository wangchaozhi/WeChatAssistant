# 手机连点器（录制 + 截图 + 千问 AI）技术文档

## 1. 项目目标

实现一个 Android 端的自动连点器，核心能力：

| 能力 | 说明 |
| --- | --- |
| 点位录制 | 记录用户在屏幕上的点击/滑动/长按序列 |
| 自动回放 | 按录制顺序循环或单次执行，可配置速度、循环次数、间隔 |
| 屏幕截图 | 任意时刻截取整屏，可手动触发或在回放过程中触发 |
| 接入千问 | 截图发送给阿里云千问 (Qwen-VL) 多模态模型，附自定义 prompt |
| 写入粘贴板 | 模型返回的文本自动写入系统粘贴板，便于在其它应用粘贴 |

目标 SDK：Android 14 (API 34) 及以上（项目当前 minSdk = 36，按现状保持）。

## 2. 关键技术选型

| 模块 | 方案 | 原因 |
| --- | --- | --- |
| 模拟点击 | `AccessibilityService.dispatchGesture()` | 免 root 唯一可行方案，Android 7.0+ 支持坐标级手势注入 |
| 录制点位 | 悬浮窗覆盖层 + `MotionEvent` 捕获 | 不依赖系统签名，能拿到原始坐标和时间戳 |
| 屏幕截图 | `MediaProjection` + `ImageReader` | 系统级 API，免 root，支持后台连续截图 |
| 悬浮控制面板 | `WindowManager` + `TYPE_APPLICATION_OVERLAY` | 跨应用悬浮按钮，控制开始/停止/截图 |
| 持久化 | Room + DataStore | 录制脚本结构化存储，配置项轻量保存 |
| 网络请求 | OkHttp + Kotlin Coroutines | 配合千问 REST 接口，流式或一次性返回 |
| 千问模型 | `qwen-vl-max-latest`（多模态） | 支持图片 + 文本输入，识别 UI/文字效果好 |
| 粘贴板 | `ClipboardManager.setPrimaryClip()` | 系统 API；前台服务上下文写入避免 Android 10+ 限制 |

## 3. 整体架构

```
┌─────────────────────────────────────────────────────────────┐
│                       UI 层 (Activity)                       │
│   主界面：脚本列表 / 录制 / 回放 / 设置 / AI 历史              │
└──────────────────────┬──────────────────────────────────────┘
                       │ ViewModel + StateFlow
┌──────────────────────▼──────────────────────────────────────┐
│                       领域层 (UseCase)                       │
│  RecordScriptUseCase  PlayScriptUseCase  CaptureScreenUseCase│
│  AskQwenUseCase       CopyToClipboardUseCase                 │
└──────────────────────┬──────────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────────┐
│                       服务层 (后台 Service)                   │
│  ┌─────────────────────┐  ┌─────────────────────┐           │
│  │ ClickerAccessibility│  │ CaptureForegroundSvc│           │
│  │  Service            │  │  (MediaProjection)  │           │
│  │  - dispatchGesture  │  │  - ImageReader      │           │
│  │  - 接收回放指令      │  │  - 截图回调          │           │
│  └─────────────────────┘  └─────────────────────┘           │
│  ┌─────────────────────────────────────────────┐            │
│  │ OverlayService (悬浮窗 + 录制覆盖层)         │            │
│  └─────────────────────────────────────────────┘            │
└──────────────────────┬──────────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────────┐
│                       数据层 (Repository)                    │
│   ScriptRepository (Room)  SettingsRepository (DataStore)    │
│   QwenRepository (Retrofit/OkHttp)                           │
└─────────────────────────────────────────────────────────────┘
```

## 4. 数据模型

```kotlin
// 一条录制脚本
@Entity(tableName = "scripts")
data class Script(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val loopCount: Int = 1,       // -1 表示无限循环
    val speed: Float = 1.0f,      // 回放速度倍率
)

// 录制中的单步动作
@Entity(
    tableName = "actions",
    foreignKeys = [ForeignKey(
        entity = Script::class,
        parentColumns = ["id"],
        childColumns = ["scriptId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class Action(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scriptId: Long,
    val index: Int,                       // 在脚本中的顺序
    val type: ActionType,                 // TAP / SWIPE / LONG_PRESS / WAIT / SCREENSHOT_AI
    val startX: Float,
    val startY: Float,
    val endX: Float = startX,
    val endY: Float = startY,
    val durationMs: Long,                 // 手势持续时间
    val delayBeforeMs: Long,              // 与上一个动作的间隔
    val aiPrompt: String? = null,         // SCREENSHOT_AI 类型携带的 prompt
)

enum class ActionType { TAP, SWIPE, LONG_PRESS, WAIT, SCREENSHOT_AI }
```

`SCREENSHOT_AI` 是把"截图 + 调千问 + 写粘贴板"这一组动作作为脚本里的一步插入，回放时执行到该步会触发完整链路。

## 5. 权限与清单

`AndroidManifest.xml` 关键声明：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<application ...>
    <!-- 无障碍服务，执行手势 -->
    <service
        android:name=".service.ClickerAccessibilityService"
        android:label="@string/accessibility_label"
        android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
        android:exported="true">
        <intent-filter>
            <action android:name="android.accessibilityservice.AccessibilityService" />
        </intent-filter>
        <meta-data
            android:name="android.accessibilityservice"
            android:resource="@xml/accessibility_config" />
    </service>

    <!-- 截图前台服务 -->
    <service
        android:name=".service.CaptureForegroundService"
        android:foregroundServiceType="mediaProjection"
        android:exported="false" />

    <!-- 悬浮窗服务（控制面板 + 录制覆盖层） -->
    <service
        android:name=".service.OverlayService"
        android:foregroundServiceType="specialUse"
        android:exported="false">
        <property
            android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
            android:value="floating_control_panel" />
    </service>
</application>
```

`res/xml/accessibility_config.xml`：

```xml
<accessibility-service
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeAllMask"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:canPerformGestures="true"
    android:canRetrieveWindowContent="false"
    android:notificationTimeout="100" />
```

需要用户手动开启的权限（首启引导）：
1. 悬浮窗（`Settings.canDrawOverlays`）
2. 无障碍（跳转 `Settings.ACTION_ACCESSIBILITY_SETTINGS`）
3. 通知权限（Android 13+，前台服务通知需要）
4. MediaProjection 授权（每次启动截图首次弹窗）

## 6. 核心流程

### 6.1 录制流程

```
用户点[录制] → 启动 OverlayService
            → 覆盖一层透明全屏 View（FLAG_NOT_FOCUSABLE，但可触摸）
            → 该层通过 onTouchEvent 拿到 MotionEvent，记录 (x,y,t)
            → ⚠️ 此时下层 App 收不到触摸，所以采用"录制模式"
              用户每点一次屏幕，只产生一个动作记录，由悬浮按钮"完成"提交
            → 或：采用 ACTION_OUTSIDE 模式，悬浮按钮缩小占角，
              使用 dispatchGesture 回放检查（牺牲精度换录制真实性）
```

实现要点：

```kotlin
class RecordingOverlayView(context: Context) : View(context) {
    private val events = mutableListOf<RawTouch>()
    private var downAt: Long = 0
    private var downX = 0f; private var downY = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downAt = SystemClock.uptimeMillis()
                downX = e.rawX; downY = e.rawY
            }
            MotionEvent.ACTION_UP -> {
                val dur = SystemClock.uptimeMillis() - downAt
                val dx = e.rawX - downX; val dy = e.rawY - downY
                val type = when {
                    dur > 500 && hypot(dx, dy) < 20 -> ActionType.LONG_PRESS
                    hypot(dx, dy) > 20 -> ActionType.SWIPE
                    else -> ActionType.TAP
                }
                events += RawTouch(type, downX, downY, e.rawX, e.rawY, dur, downAt)
            }
        }
        return true
    }
}
```

WindowManager 参数：

```kotlin
WindowManager.LayoutParams(
    MATCH_PARENT, MATCH_PARENT,
    TYPE_APPLICATION_OVERLAY,
    FLAG_NOT_FOCUSABLE,            // 不抢输入法焦点
    PixelFormat.TRANSLUCENT
)
```

### 6.2 回放流程

```
PlayScriptUseCase
  → 通过 LocalBroadcast / Binder 发指令到 ClickerAccessibilityService
  → 服务里循环 actions：
       delay(action.delayBeforeMs / speed)
       when (action.type) {
         TAP / LONG_PRESS / SWIPE -> dispatchGesture(buildGesture(action))
         WAIT                     -> delay(action.durationMs)
         SCREENSHOT_AI            -> CaptureBus.requestCapture(action.aiPrompt)
       }
```

构造手势：

```kotlin
fun buildGesture(a: Action): GestureDescription {
    val path = Path().apply {
        moveTo(a.startX, a.startY)
        if (a.type == ActionType.SWIPE) lineTo(a.endX, a.endY)
    }
    return GestureDescription.Builder()
        .addStroke(GestureDescription.StrokeDescription(path, 0L, a.durationMs))
        .build()
}
```

无障碍服务执行：

```kotlin
class ClickerAccessibilityService : AccessibilityService() {
    fun playGesture(g: GestureDescription, onDone: () -> Unit) {
        dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription) = onDone()
            override fun onCancelled(d: GestureDescription) = onDone()
        }, null)
    }
}
```

### 6.3 截图流程

```
用户授权 MediaProjection（Activity.startActivityForResult）
  → 把 resultCode/data 传给 CaptureForegroundService
  → 服务 startForeground(notification, FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
  → MediaProjectionManager.getMediaProjection(resultCode, data)
  → ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
  → projection.createVirtualDisplay(..., reader.surface, ...)
  → 触发截图时：
        val image = reader.acquireLatestImage()
        val bitmap = image.toBitmap()
        image.close()
        return bitmap
```

关键点：
- Android 14+ 必须 `foregroundServiceType="mediaProjection"`，否则 `getMediaProjection()` 抛 `SecurityException`。
- VirtualDisplay 在不截图时也持续输出，可放在 `MediaProjection.Callback.onStop()` 里释放，节省内存。
- 多分辨率设备需用 `DisplayMetrics` 拿真实尺寸，而非 `Resources.getSystem().displayMetrics`。

### 6.4 千问对接

千问 VL（多模态）REST 接口：

```
POST https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation
Headers:
  Authorization: Bearer <DASHSCOPE_API_KEY>
  Content-Type: application/json
Body:
{
  "model": "qwen-vl-max-latest",
  "input": {
    "messages": [
      { "role": "user",
        "content": [
          { "image": "data:image/jpeg;base64,<base64>" },
          { "text": "<用户的 prompt>" }
        ]
      }
    ]
  },
  "parameters": { "result_format": "message" }
}
```

OkHttp 实现：

```kotlin
class QwenRepository(
    private val client: OkHttpClient,
    private val apiKey: String,
) {
    suspend fun ask(image: Bitmap, prompt: String): String = withContext(Dispatchers.IO) {
        val base64 = image.toBase64Jpeg(quality = 80)
        val body = buildJson {
            "model" to "qwen-vl-max-latest"
            "input" to buildJson {
                "messages" to jsonArrayOf(buildJson {
                    "role" to "user"
                    "content" to jsonArrayOf(
                        buildJson { "image" to "data:image/jpeg;base64,$base64" },
                        buildJson { "text" to prompt }
                    )
                })
            }
            "parameters" to buildJson { "result_format" to "message" }
        }
        val req = Request.Builder()
            .url("https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "Qwen HTTP ${resp.code}: ${resp.body?.string()}" }
            parseAnswer(resp.body!!.string())
        }
    }
}
```

注意事项：
- API Key 不要硬编码进 APK，存到 `EncryptedSharedPreferences`，首次启动让用户填入。
- 图片体积控制：JPEG quality 80，长边压到 1280px 以内，单图大小一般 < 500KB，降低带宽与首字节延迟。
- 大图也可以走 OSS：先 PUT 到阿里云 OSS 再传 `"image": "<oss_url>"`，省 base64 体积。
- 超时：connect 10s / read 60s（多模态推理较慢）。

### 6.5 写入粘贴板

```kotlin
class CopyToClipboardUseCase(private val ctx: Context) {
    operator fun invoke(text: String, label: String = "QwenAnswer") {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
```

Android 10+ 限制：
- 后台应用无法读取粘贴板，但**写入**仍然允许，只是会有 Toast 提示。
- 通过前台服务（`CaptureForegroundService`）的 Context 调用即可，无需切到 Activity。
- Android 13+ 写入敏感内容可加 `ClipDescription.EXTRA_IS_SENSITIVE = true`。

### 6.6 端到端串联（"截图 → AI → 粘贴板"作为一个原子动作）

```kotlin
class ScreenshotAiUseCase(
    private val capture: CaptureRepository,
    private val qwen: QwenRepository,
    private val clipboard: CopyToClipboardUseCase,
    private val history: AnswerHistoryRepository,
) {
    suspend operator fun invoke(prompt: String): Result<String> = runCatching {
        val bitmap = capture.takeScreenshot()
        val answer = qwen.ask(bitmap, prompt)
        clipboard(answer)
        history.save(bitmap, prompt, answer)
        answer
    }
}
```

该 UseCase 既可被 UI 直接调用（悬浮按钮"AI 一下"），也可被回放器在执行 `SCREENSHOT_AI` 类型动作时调用。

## 7. UI 设计（最小可用版）

| 页面 | 内容 |
| --- | --- |
| 主页 | 脚本列表，每条卡片显示名称/动作数/上次播放；底部 FAB "新建录制" |
| 录制页 | 悬浮控制面板，按钮：开始/暂停/插入 AI 节点/完成；显示已录动作数 |
| 回放页 | 选择脚本 → 配置循环次数、速度、起始延迟 → 开始；进度条 + 当前步序号 |
| 设置页 | 千问 API Key、默认 prompt、截图压缩参数、悬浮窗位置 |
| AI 历史 | 缩略图 + prompt + 答案，可点击复制 |

悬浮控制面板：固定一个 56dp 圆形按钮，长按拖动，点击展开横向按钮组：[录制][播放][AI][停止][关闭]。

## 8. 模块划分（Gradle）

为减少耦合，按以下方式拆分：

```
app/                    -> 入口 Activity、DI 装配、AndroidManifest
core/common/            -> 通用工具（Bitmap 压缩、Base64、协程扩展）
core/database/          -> Room（Script/Action DAO）
core/datastore/         -> 设置项
feature/recorder/       -> 录制 UI + OverlayService
feature/player/         -> 回放 UI + ClickerAccessibilityService
feature/capture/        -> 截图 CaptureForegroundService
feature/qwen/           -> QwenRepository + Retrofit/OkHttp
feature/ai/             -> ScreenshotAiUseCase + 历史
```

当前项目尚未引入 Kotlin，需要先：
- 在 `app/build.gradle.kts` 加入 `kotlin("android")` 插件
- 加入 Compose / ViewBinding（按团队偏好）
- `libs.versions.toml` 增补依赖（Room、Coroutines、OkHttp、Hilt）

## 9. 关键风险与规避

| 风险 | 规避方案 |
| --- | --- |
| `dispatchGesture` 在游戏/带防作弊的 App 中失效 | 文档明确支持范围；提供"测试模式"让用户先验证 |
| 录制时覆盖层吃掉触摸，无法点到下层 | 采用"点位录制"模式：用户点哪里就记录哪里，不传递给下层 |
| MediaProjection 每次启动都弹授权框 | 把 `resultData` 缓存在前台服务生命周期内，Service 不死就不弹 |
| 千问 API Key 泄漏 | EncryptedSharedPreferences；上线版本走自家中转服务 |
| 粘贴板被竞争应用覆盖 | 同时保留到本地"AI 历史"，UI 上提供"重新复制"按钮 |
| Android 15 限制 `SYSTEM_ALERT_WINDOW` 自启 | 提示用户在前台时启动悬浮窗 |
| 高刷屏 ImageReader buffer 跟不上 | maxImages=2，并在 `OnImageAvailableListener` 内立刻 close 旧帧 |
| 录制时间精度 | 用 `SystemClock.uptimeMillis()`，不要用 `System.currentTimeMillis()` |

## 10. 实施路线

**Phase 1：骨架（1–2 天）**
- 工程切到 Kotlin + Compose（可选）
- 加 Room、OkHttp、Coroutines 依赖
- 写好 5 个 Service 的空壳与权限引导页

**Phase 2：录制 + 回放（2–3 天）**
- OverlayService 录制点位
- ClickerAccessibilityService 回放
- 脚本列表 + 持久化

**Phase 3：截图 + 千问（2 天）**
- CaptureForegroundService + MediaProjection
- QwenRepository
- 端到端 UseCase + 粘贴板

**Phase 4：体验完善（2 天）**
- 悬浮控制面板
- 设置页、历史页
- 异常路径（无网、Key 错误、授权过期）

**Phase 5：可选增强**
- 找图点击（OpenCV 模板匹配，配合截图实现"等到某 UI 出现再点击"）
- 多脚本组合 / 条件分支
- 千问回答自动 OCR 校对、流式输出

## 11. 参考接口速查

- AccessibilityService.dispatchGesture：https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
- MediaProjection：https://developer.android.com/media/grow/media-projection
- 千问 VL：https://help.aliyun.com/zh/model-studio/developer-reference/qwen-vl-api
- DashScope 鉴权：https://help.aliyun.com/zh/model-studio/developer-reference/get-api-key
