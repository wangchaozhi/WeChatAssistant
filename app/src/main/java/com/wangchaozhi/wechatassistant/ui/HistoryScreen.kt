package com.wangchaozhi.wechatassistant.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
                    TextButton(onClick = viewModel::clearHistory) { Text("清空") }
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
                    onCopy = { ctx.copyToClipboard(a.answer) },
                    onDelete = { viewModel.deleteAnswer(a) },
                )
            }
        }
    }
}

@Composable
private fun AnswerRow(answer: AiAnswer, onCopy: () -> Unit, onDelete: () -> Unit) {
    val bitmap = remember(answer.thumbnailPath) {
        answer.thumbnailPath?.let { p ->
            runCatching {
                val f = File(p)
                if (f.exists()) BitmapFactory.decodeFile(p) else null
            }.getOrNull()
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
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
                    TextButton(onClick = onCopy) { Text("复制") }
                    Spacer(Modifier.size(8.dp))
                    TextButton(onClick = onDelete) { Text("删除") }
                }
            }
        }
    }
}

private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
private fun formatTime(millis: Long): String = timeFmt.format(Date(millis))
