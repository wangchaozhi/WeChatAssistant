package com.wangchaozhi.wechatassistant.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 可手填 + 可拉取官方列表的模型选择器。
 * 点「选择模型」按钮时调用 [fetch] 实时拉取供应商可用模型；拉取失败时退回 [fallback]。
 */
@Composable
fun RefreshableModelField(
    model: String,
    onModel: (String) -> Unit,
    fetch: suspend () -> Result<List<String>>,
    fallback: List<String>,
    label: String = "模型名（可手填）",
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var fetched by remember { mutableStateOf<List<String>?>(null) }

    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = model,
            onValueChange = onModel,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Box {
            OutlinedButton(
                onClick = {
                    if (fetched != null) {
                        menu = true
                    } else {
                        loading = true
                        error = null
                        scope.launch {
                            val r = fetch()
                            loading = false
                            r.onSuccess { fetched = it; menu = true }
                                .onFailure { error = it.message ?: "拉取失败" }
                        }
                    }
                },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (loading) "拉取中…" else "选择模型（拉取官方列表）")
            }
            DropdownMenu(
                expanded = menu,
                onDismissRequest = { menu = false },
                modifier = Modifier.heightIn(max = 360.dp),
            ) {
                val list = fetched ?: fallback
                if (list.isEmpty()) {
                    DropdownMenuItem(text = { Text("（无可用模型）") }, onClick = { menu = false })
                } else {
                    list.forEach { m ->
                        DropdownMenuItem(text = { Text(m) }, onClick = { onModel(m); menu = false })
                    }
                }
            }
        }
        val err = error
        if (err != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                "拉取失败：$err（可手填模型名）",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
