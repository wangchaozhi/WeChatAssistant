package com.wangchaozhi.wechatassistant.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import com.wangchaozhi.wechatassistant.ui.theme.GlassTextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import com.wangchaozhi.wechatassistant.data.model.AiAnswer
import com.wangchaozhi.wechatassistant.util.copyToClipboard
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val answers by viewModel.answers.collectAsState()
    var detail by remember { mutableStateOf<AiAnswer?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 历史 (${answers.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    GlassTextButton(onClick = viewModel::clearHistory) { Text("清空") }
                },
            )
        },
    ) { inner ->
        if (answers.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(inner),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("还没有 AI 回答。", style = MaterialTheme.typography.bodyMedium)
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(answers, key = { it.id }) { a ->
                AnswerRow(
                    answer = a,
                    onClick = { detail = a },
                    onCopy = { ctx.copyToClipboard(a.answer) },
                    onDelete = { viewModel.deleteAnswer(a) },
                )
            }
        }
    }

    detail?.let { a ->
        AnswerDetailDialog(
            answer = a,
            onCopy = { ctx.copyToClipboard(a.answer) },
            onDismiss = { detail = null },
        )
    }
}

@Composable
private fun AnswerDetailDialog(
    answer: AiAnswer,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    val bitmap = remember(answer.thumbnailPath) {
        answer.thumbnailPath?.let { p ->
            runCatching {
                val f = File(p)
                if (f.exists()) BitmapFactory.decodeFile(p) else null
            }.getOrNull()
        }
    }
    var fullscreen by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { GlassTextButton(onClick = onCopy) { Text("复制") } },
        dismissButton = { GlassTextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text(formatTime(answer.createdAt)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { fullscreen = true },
                        contentScale = ContentScale.Fit,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Text("Prompt", style = MaterialTheme.typography.labelMedium)
                Text(answer.prompt, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                Text("回答", style = MaterialTheme.typography.labelMedium)
                Text(answer.answer, style = MaterialTheme.typography.bodyMedium)
            }
        },
    )

    if (fullscreen && bitmap != null) {
        FullscreenImagePreview(bitmap = bitmap, onDismiss = { fullscreen = false })
    }
}

@Composable
private fun FullscreenImagePreview(bitmap: Bitmap, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true,
            dismissOnBackPress = true,
        ),
    ) {
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onDismiss() },
                        onDoubleTap = {
                            scale = 1f
                            offset = Offset.Zero
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (scale <= 1f) Offset.Zero else offset + pan
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
private fun AnswerRow(
    answer: AiAnswer,
    onClick: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    val bitmap = remember(answer.thumbnailPath) {
        answer.thumbnailPath?.let { p ->
            runCatching {
                val f = File(p)
                if (f.exists()) BitmapFactory.decodeFile(p) else null
            }.getOrNull()
        }
    }
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(12.dp)) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.size(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(formatTime(answer.createdAt), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    answer.prompt.take(40),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    answer.answer,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                )
                Spacer(Modifier.height(6.dp))
                Row {
                    GlassTextButton(onClick = onCopy) { Text("复制") }
                    Spacer(Modifier.size(8.dp))
                    GlassTextButton(onClick = onDelete) { Text("删除") }
                }
            }
        }
    }
}

private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
private fun formatTime(millis: Long): String = timeFmt.format(Date(millis))
