package com.wangchaozhi.wechatassistant.data.repo

import android.content.Context
import android.graphics.Bitmap
import com.wangchaozhi.wechatassistant.data.db.AiAnswerDao
import com.wangchaozhi.wechatassistant.data.model.AiAnswer
import com.wangchaozhi.wechatassistant.util.scaleToMaxSide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class AiAnswerRepository(
    private val context: Context,
    private val dao: AiAnswerDao,
) {

    fun observe(): Flow<List<AiAnswer>> = dao.answersFlow()

    suspend fun save(
        bitmap: Bitmap,
        prompt: String,
        answer: String,
        scriptId: Long? = null,
    ): Long = withContext(Dispatchers.IO) {
        val path = saveThumbnail(bitmap)
        dao.insert(
            AiAnswer(
                prompt = prompt,
                answer = answer,
                thumbnailPath = path,
                scriptId = scriptId,
            )
        )
    }

    suspend fun delete(id: Long, thumbnailPath: String?) = withContext(Dispatchers.IO) {
        dao.delete(id)
        thumbnailPath?.let { runCatching { File(it).delete() } }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        val dir = thumbnailDir()
        dir.listFiles()?.forEach { it.delete() }
        dao.clear()
    }

    private fun saveThumbnail(bitmap: Bitmap): String? = try {
        val dir = thumbnailDir().apply { if (!exists()) mkdirs() }
        val file = File(dir, "thumb_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.scaleToMaxSide(480).compress(Bitmap.CompressFormat.JPEG, 70, out)
        }
        file.absolutePath
    } catch (t: Throwable) { null }

    private fun thumbnailDir(): File = File(context.filesDir, "ai_thumbs")
}
