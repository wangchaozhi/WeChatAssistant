package com.wangchaozhi.wechatassistant.data.repo

import android.content.Context
import android.graphics.Bitmap
import com.wangchaozhi.wechatassistant.App
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
        val maxSide = App.from(context).settingsRepo.thumbnailMaxSide
        val quality = qualityFor(maxSide)
        FileOutputStream(file).use { out ->
            bitmap.scaleToMaxSide(maxSide).compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        file.absolutePath
    } catch (t: Throwable) { null }

    private fun qualityFor(maxSide: Int): Int = when {
        maxSide <= 480 -> 70
        maxSide <= 720 -> 75
        maxSide <= 1440 -> 85
        else -> 90
    }

    private fun thumbnailDir(): File = File(context.filesDir, "ai_thumbs")
}
