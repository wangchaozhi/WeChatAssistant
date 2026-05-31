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

class Converters {
    @TypeConverter fun typeToInt(t: ActionType): Int = t.ordinal
    @TypeConverter fun intToType(i: Int): ActionType = ActionType.entries[i]
}

@Database(
    entities = [Script::class, Action::class, AiAnswer::class, Edge::class],
    version = 8,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scriptDao(): ScriptDao
    abstract fun aiAnswerDao(): AiAnswerDao

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
    }
}
