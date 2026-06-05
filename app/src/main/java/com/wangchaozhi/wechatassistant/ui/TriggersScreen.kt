package com.wangchaozhi.wechatassistant.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.wangchaozhi.wechatassistant.data.model.ScriptTrigger
import com.wangchaozhi.wechatassistant.data.model.TriggerType
import com.wangchaozhi.wechatassistant.trigger.ScheduleTriggers
import kotlinx.coroutines.launch

private val WEEKDAY_LABELS = listOf("日", "一", "二", "三", "四", "五", "六")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TriggersScreen(
    scriptId: Long,
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val triggers by viewModel.observeTriggers(scriptId).collectAsState(initial = emptyList())
    val scriptName = viewModel.scriptName(scriptId) ?: "脚本 #$scriptId"
    var editing by remember { mutableStateOf<ScriptTrigger?>(null) }

    // 通知使用权状态：去系统设置授权后返回本界面，Compose 不会自动重读，
    // 监听生命周期 ON_RESUME 主动刷新，避免「明明授权了仍显示未授权、要重进才更新」。
    val lifecycleOwner = LocalLifecycleOwner.current
    var notifGranted by remember { mutableStateOf(isNotifListenerEnabled(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notifGranted = isNotifListenerEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun save(trigger: ScriptTrigger) {
        scope.launch {
            val id = viewModel.upsertTrigger(trigger)
            ScheduleTriggers.scheduleNext(context, trigger.copy(id = id))
        }
    }

    fun remove(trigger: ScriptTrigger) {
        scope.launch {
            viewModel.deleteTrigger(trigger)
            ScheduleTriggers.cancel(context, trigger.id)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Column {
                        Text("触发器", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        Text(
                            scriptName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { PermissionHints(context, notifGranted) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            editing = ScriptTrigger(scriptId = scriptId, type = TriggerType.NOTIFICATION)
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("+ 通知触发") }
                    OutlinedButton(
                        onClick = {
                            editing = ScriptTrigger(scriptId = scriptId, type = TriggerType.SCHEDULE)
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("+ 定时触发") }
                }
            }
            if (triggers.isEmpty()) {
                item {
                    Text(
                        "还没有触发器。可让本脚本在「收到指定 App 通知」或「到点」时自动运行。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(triggers, key = { it.id }) { t ->
                TriggerRow(
                    trigger = t,
                    onToggle = { save(t.copy(enabled = it)) },
                    onEdit = { editing = t },
                    onDelete = { remove(t) },
                )
            }
        }
    }

    val ed = editing
    if (ed != null) {
        TriggerEditDialog(
            trigger = ed,
            onDismiss = { editing = null },
            onConfirm = { save(it); editing = null },
        )
    }
}

private fun isNotifListenerEnabled(context: android.content.Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

@Composable
private fun PermissionHints(context: android.content.Context, notifGranted: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("权限", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "通知使用权：" + if (notifGranted) "已授权" else "未授权（通知触发需要）",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                if (!notifGranted) {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }) { Text("去授权") }
                }
            }
            Text(
                "定时触发使用系统闹钟，无需额外权限；但都需「无障碍」服务开启后才能真正回放。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TriggerRow(
    trigger: ScriptTrigger,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (trigger.type == TriggerType.NOTIFICATION) "通知触发" else "定时触发",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
                Text(
                    triggerSummary(trigger),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = trigger.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun triggerSummary(t: ScriptTrigger): String = when (t.type) {
    TriggerType.NOTIFICATION -> {
        val kw = t.keyword?.trim().orEmpty()
        "${t.packageName}" + if (kw.isEmpty()) " · 任意通知" else " · 含「$kw」"
    }
    TriggerType.SCHEDULE -> {
        val time = "%02d:%02d".format(t.hour, t.minute)
        val days = if (t.daysMask == 0) "每天" else
            (0..6).filter { t.daysMask and (1 shl it) != 0 }.joinToString("") { WEEKDAY_LABELS[it] }
        "$time · $days"
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TriggerEditDialog(
    trigger: ScriptTrigger,
    onDismiss: () -> Unit,
    onConfirm: (ScriptTrigger) -> Unit,
) {
    var packageName by remember { mutableStateOf(trigger.packageName) }
    var keyword by remember { mutableStateOf(trigger.keyword.orEmpty()) }
    var hour by remember { mutableStateOf(trigger.hour.toString()) }
    var minute by remember { mutableStateOf("%02d".format(trigger.minute)) }
    var daysMask by remember { mutableStateOf(trigger.daysMask) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (trigger.type == TriggerType.NOTIFICATION) "通知触发" else "定时触发")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (trigger.type == TriggerType.NOTIFICATION) {
                    OutlinedTextField(
                        value = packageName,
                        onValueChange = { packageName = it },
                        label = { Text("App 包名（默认微信）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip(onClick = { packageName = "com.tencent.mm" }, label = { Text("微信") })
                        AssistChip(onClick = { packageName = "com.tencent.mobileqq" }, label = { Text("QQ") })
                    }
                    OutlinedTextField(
                        value = keyword,
                        onValueChange = { keyword = it },
                        label = { Text("关键词（可空 = 任意通知）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "通知标题或正文包含关键词即触发。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = hour,
                            onValueChange = { v -> hour = v.filter { it.isDigit() }.take(2) },
                            label = { Text("时 (0-23)") },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = KeyboardType.Number
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = minute,
                            onValueChange = { v -> minute = v.filter { it.isDigit() }.take(2) },
                            label = { Text("分 (0-59)") },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = KeyboardType.Number
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text("重复（不选 = 每天）", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        WEEKDAY_LABELS.forEachIndexed { i, label ->
                            val on = daysMask and (1 shl i) != 0
                            FilterChip(
                                selected = on,
                                onClick = {
                                    daysMask = if (on) daysMask and (1 shl i).inv()
                                    else daysMask or (1 shl i)
                                },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val result = if (trigger.type == TriggerType.NOTIFICATION) {
                    trigger.copy(
                        packageName = packageName.trim().ifBlank { "com.tencent.mm" },
                        keyword = keyword.trim().ifBlank { null },
                    )
                } else {
                    trigger.copy(
                        hour = (hour.toIntOrNull() ?: 0).coerceIn(0, 23),
                        minute = (minute.toIntOrNull() ?: 0).coerceIn(0, 59),
                        daysMask = daysMask,
                    )
                }
                onConfirm(result)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
