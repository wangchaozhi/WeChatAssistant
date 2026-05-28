package com.wangchaozhi.wechatassistant.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.wangchaozhi.wechatassistant.data.model.Action
import com.wangchaozhi.wechatassistant.data.model.ActionType
import com.wangchaozhi.wechatassistant.data.model.AiAnswer
import com.wangchaozhi.wechatassistant.data.model.Script

class Converters {
    @TypeConverter fun typeToInt(t: ActionType): Int = t.ordinal
    @TypeConverter fun intToType(i: Int): ActionType = ActionType.entries[i]
}

@Database(
    entities = [Script::class, Action::class, AiAnswer::class],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scriptDao(): ScriptDao
    abstract fun aiAnswerDao(): AiAnswerDao
}
