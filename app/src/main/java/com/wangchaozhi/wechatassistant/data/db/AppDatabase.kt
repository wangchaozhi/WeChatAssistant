package com.wangchaozhi.wechatassistant.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.wangchaozhi.wechatassistant.data.model.Action
import com.wangchaozhi.wechatassistant.data.model.ActionType
import com.wangchaozhi.wechatassistant.data.model.AiAnswer
import com.wangchaozhi.wechatassistant.data.model.Edge
import com.wangchaozhi.wechatassistant.data.model.Script
import com.wangchaozhi.wechatassistant.data.model.ScriptTrigger
import com.wangchaozhi.wechatassistant.data.model.TriggerType

class Converters {
    @TypeConverter fun typeToInt(t: ActionType): Int = t.ordinal
    @TypeConverter fun intToType(i: Int): ActionType = ActionType.entries[i]
    @TypeConverter fun triggerTypeToInt(t: TriggerType): Int = t.ordinal
    @TypeConverter fun intToTriggerType(i: Int): TriggerType = TriggerType.entries[i]
}

@Database(
    entities = [Script::class, Action::class, AiAnswer::class, Edge::class, ScriptTrigger::class],
    // v10：移除 AI_TAP / SNAPSHOT / IF_PAGE_CHANGED / IF_TEXT_EXISTS 四种节点，ActionType 序号重排。
    // 不提供 9→10 迁移，靠 fallbackToDestructiveMigration 销毁重建（旧脚本数据按需求一并清空）。
    // v12：CALL_SCRIPT 节点新增 callScriptId 列。v13：新增 triggers 触发器表。
    // v14：找图节点新增 upFallbackPx（上方容错）列。v15：AI 节点新增快速区域截图开关。
    // v16：AI 节点新增流式输出开关。
    version = 16,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scriptDao(): ScriptDao
    abstract fun aiAnswerDao(): AiAnswerDao
    abstract fun triggerDao(): TriggerDao

    companion object {
        // 给 actions 加节点别名列，保留已有脚本数据（不走销毁重建）。
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE actions ADD COLUMN alias TEXT")
            }
        }

        // AI 节点新增「供应商 / 模型」两列，保留已有脚本数据。
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE actions ADD COLUMN aiProvider TEXT")
                db.execSQL("ALTER TABLE actions ADD COLUMN aiModel TEXT")
            }
        }

        // AI 历史记录新增「供应商 / 模型」两列，保留已有历史。
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ai_answers ADD COLUMN aiProvider TEXT")
                db.execSQL("ALTER TABLE ai_answers ADD COLUMN aiModel TEXT")
            }
        }

        // WAIT 节点新增随机抖动列，保留已有脚本数据。
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE actions ADD COLUMN randomExtraMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        // PASTE 节点新增录制时捕获的剪贴板文本，保留已有脚本数据。
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE actions ADD COLUMN pasteText TEXT")
            }
        }

        // CALL_SCRIPT 节点新增目标脚本 id 列，保留已有脚本数据。
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE actions ADD COLUMN callScriptId INTEGER")
            }
        }

        // 新增触发器表（通知监听 / 定时启动脚本），保留已有脚本数据。
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS triggers (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "scriptId INTEGER NOT NULL, " +
                        "type INTEGER NOT NULL, " +
                        "enabled INTEGER NOT NULL, " +
                        "hour INTEGER NOT NULL, " +
                        "minute INTEGER NOT NULL, " +
                        "daysMask INTEGER NOT NULL, " +
                        "packageName TEXT NOT NULL, " +
                        "keyword TEXT, " +
                        "FOREIGN KEY(scriptId) REFERENCES scripts(id) ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_triggers_scriptId ON triggers(scriptId)")
            }
        }

        // 找图节点新增「上方容错」列，保留已有脚本数据（默认 0=关闭，行为不变）。
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE actions ADD COLUMN upFallbackPx INTEGER NOT NULL DEFAULT 0")
            }
        }

        // AI 节点新增「快速区域截图」开关，默认开启，保留当前高速行为。
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE actions ADD COLUMN aiFastRegionCapture INTEGER NOT NULL DEFAULT 1")
            }
        }

        // AI 节点新增「流式输出」开关，默认关闭，保持原来的完整回答方式。
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE actions ADD COLUMN aiStreamOutput INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
