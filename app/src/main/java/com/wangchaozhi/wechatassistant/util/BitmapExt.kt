package com.wangchaozhi.wechatassistant.util

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

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
