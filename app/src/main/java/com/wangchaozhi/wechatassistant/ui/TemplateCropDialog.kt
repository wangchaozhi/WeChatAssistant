package com.wangchaozhi.wechatassistant.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wangchaozhi.wechatassistant.util.CropRect
import com.wangchaozhi.wechatassistant.util.DragMode
import kotlin.math.min

/**
 * 对一张给定位图做「可调裁剪框」式裁剪：默认给一个居中框，四角可拖拽改大小、框内可整体拖动，框外变暗。
 * 裁剪框 [crop] 以「原图像素坐标」存储，绘制时乘以显示缩放比，裁剪时直接用。
 */
@Composable
fun TemplateCropDialog(
    source: Bitmap,
    onDismiss: () -> Unit,
    onConfirm: (Bitmap) -> Unit,
) {
    val bw = source.width.toFloat()
    val bh = source.height.toFloat()
    // 默认居中、占图一半的裁剪框（原图像素坐标）。
    var crop by remember { mutableStateOf(CropRect.centered(bw, bh)) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = Color(0xF2000000)) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Text("拖动四角改大小、框内拖动可移动", color = Color.White)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val density = LocalDensity.current
                        val cw = with(density) { maxWidth.toPx() }
                        val ch = with(density) { maxHeight.toPx() }
                        val scale = min(cw / source.width, ch / source.height)
                        val dispW = with(density) { (source.width * scale).toDp() }
                        val dispH = with(density) { (source.height * scale).toDp() }
                        val handlePx = with(density) { 22.dp.toPx() }

                        Box(
                            Modifier
                                .size(dispW, dispH)
                                .pointerInput(source) {
                                    var mode = DragMode.NONE
                                    detectDragGestures(
                                        onDragStart = { off ->
                                            // 触点换算到原图坐标，判断抓住了哪个角 / 框内 / 外部
                                            val tol = handlePx / scale
                                            mode = crop.hitTest(off.x / scale, off.y / scale, tol)
                                        },
                                        onDrag = { change, delta ->
                                            change.consume()
                                            crop = crop.apply(mode, delta.x / scale, delta.y / scale, bw, bh)
                                        },
                                    )
                                },
                        ) {
                            Image(
                                bitmap = source.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                            )
                            Canvas(Modifier.fillMaxSize()) {
                                val l = crop.left * scale
                                val t = crop.top * scale
                                val r = crop.right * scale
                                val b = crop.bottom * scale
                                val dim = Color(0x99000000)
                                // 框外四块变暗
                                drawRect(dim, Offset(0f, 0f), Size(size.width, t))
                                drawRect(dim, Offset(0f, b), Size(size.width, size.height - b))
                                drawRect(dim, Offset(0f, t), Size(l, b - t))
                                drawRect(dim, Offset(r, t), Size(size.width - r, b - t))
                                // 边框
                                drawRect(
                                    color = Color(0xFF00E5FF),
                                    topLeft = Offset(l, t),
                                    size = Size(r - l, b - t),
                                    style = Stroke(width = 3f),
                                )
                                // 四角把手
                                val hr = handlePx * 0.5f
                                listOf(Offset(l, t), Offset(r, t), Offset(l, b), Offset(r, b)).forEach {
                                    drawCircle(Color(0xFF00E5FF), hr, it)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                    Button(
                        onClick = { crop.crop(source)?.let(onConfirm) },
                        modifier = Modifier.weight(1f),
                    ) { Text("确定") }
                }
            }
        }
    }
}
