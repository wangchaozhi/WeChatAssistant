package com.wangchaozhi.wechatassistant.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "scripts")
data class Script(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val loopCount: Int = 1,
    val speed: Float = 1.0f,
)

// 注意：Room 用 ordinal 存储 ActionType，新增类型只能追加到末尾，不能插入中间。
enum class ActionType {
    TAP, SWIPE, LONG_PRESS, WAIT, SCREENSHOT_AI, AI_TAP, PASTE, ENTER, IMAGE_MATCH, WAIT_PAGE_CHANGE,
    START,            // 图入口节点，执行时 no-op
    SNAPSHOT,         // 抓当前页面截图存入基准寄存器
    IF_PAGE_CHANGED,  // 比较当前截图与基准，瞬时返回；出口 port 0=变了 / 1=没变
    IF_IMAGE_EXISTS,  // 模板图在当前屏幕是否存在；出口 port 0=找到 / 1=没找到。只判断不点击。
    IF_TEXT_EXISTS,   // aiPrompt 文字是否出现在当前页面控件树；出口 port 0=找到 / 1=没找到。
    LOOP,             // 计数循环：retryCount 为次数。出口 port 0=继续(回循环体) / 1=到次数(往下)。
    STOP,             // 终止整张图的执行（从任意分支提前结束）。
}

@Entity(
    tableName = "actions",
    foreignKeys = [
        ForeignKey(
            entity = Script::class,
            parentColumns = ["id"],
            childColumns = ["scriptId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("scriptId")],
)
data class Action(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scriptId: Long,
    val index: Int,
    val type: ActionType,
    val startX: Float,
    val startY: Float,
    val endX: Float = startX,
    val endY: Float = startY,
    val durationMs: Long = 80L,
    val delayBeforeMs: Long = 0L,
    // WAIT 专用：在 durationMs 之上额外随机等待 0~randomExtraMs 毫秒，模拟真人、降低被识别风险。0=不抖动。
    val randomExtraMs: Long = 0L,
    val aiPrompt: String? = null,
    // IMAGE_MATCH 专用：模板图片在内部存储的绝对路径，以及匹配置信度阈值 (0~1)。
    val templatePath: String? = null,
    val matchThreshold: Float = 0.85f,
    // WAIT_PAGE_CHANGE 专用：页面未变化时，最多重试的次数；用尽仍未变化则继续往下。
    // 该节点用 durationMs 作为每次重试之间的轮询间隔(ms)。
    val retryCount: Int = 10,
    // WAIT_PAGE_CHANGE 专用：每次重试时回头重复执行「前几步」(默认 1=仅上一步)。
    // 例如「点出去 + 点进来」两步循环，设为 2。
    val repeatPrevSteps: Int = 1,
    // 节点图编辑器：节点在画布上的坐标（图空间）。线性时代未用，默认 0。
    val posX: Float = 0f,
    val posY: Float = 0f,
    // 节点别名：用户给节点起的可读名字，仅用于编辑器显示，不影响执行。
    val alias: String? = null,
    // AI 节点专用：模型供应商（AiProvider.name，如 "DASHSCOPE"/"MODELSCOPE"）与具体模型名。
    // 为 null 表示跟随全局设置。
    val aiProvider: String? = null,
    val aiModel: String? = null,
)

data class ScriptWithActions(
    val script: Script,
    val actions: List<Action>,
)

data class ScriptWithGraph(
    val script: Script,
    val actions: List<Action>,
    val edges: List<Edge>,
)
