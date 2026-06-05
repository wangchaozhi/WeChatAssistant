package com.wangchaozhi.wechatassistant.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * 解码文件时按 [reqMaxSide] 做降采样，避免把整张全屏截图按原分辨率读进内存。
 * 仅用 inSampleSize（2 的幂），解码出的长边 >= reqMaxSide，足够清晰且省内存。
 */
fun decodeSampledBitmap(path: String, reqMaxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) return null
    var sample = 1
    while (longest / (sample * 2) >= reqMaxSide) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(path, opts)
}

fun Bitmap.scaleToMaxSide(maxSide: Int): Bitmap {
    val w = width; val h = height
    val longest = maxOf(w, h)
    if (longest <= maxSide) return this
    val ratio = maxSide.toFloat() / longest
    val nw = (w * ratio).toInt().coerceAtLeast(1)
    val nh = (h * ratio).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, nw, nh, true)
}

fun Bitmap.toBase64Jpeg(quality: Int = 80, maxSide: Int = 1280): String {
    val scaled = scaleToMaxSide(maxSide)
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}
