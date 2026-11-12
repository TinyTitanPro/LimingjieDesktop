package com.landosol.toolbox.desktop.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.landosol.toolbox.desktop.app.control.ControlOps
import com.landosol.toolbox.desktop.app.control.ControlSessionUi
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put
import java.text.DateFormat
import java.util.Date

/** Desktop host for the upstream configuration share engine; plan validation stays worker-side. */
@Composable internal fun ConfigurationShareDialog(graph: ControlSessionUi, onDismiss: () -> Unit) {
    val actions = rememberActions(graph)
    val busy = LocalTaskBusy.current
    val accounts by graph.accounts.uiState.collectAsState()
    var blocked by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var exportModules by remember { mutableStateOf(setOf("STRATEGY", "PERSONAL_SCORES")) }
    var output by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var decodedModules by remember { mutableStateOf<List<String>>(emptyList()) }
    var decodedInfo by remember { mutableStateOf<String?>(null) }
    var roleWarning by remember { mutableStateOf<String?>(null) }
    var importModules by remember { mutableStateOf<List<String>>(emptyList()) }
    var targetAccountId by remember { mutableStateOf<Long?>(null) }
    var plan by remember { mutableStateOf<Triple<String, List<String>, Boolean>?>(null) }
    var confirmEmptyScores by remember { mutableStateOf(false) }
    var undo by remember { mutableStateOf<Pair<List<String>, String>?>(null) }
    var undoClearsScores by remember { mutableStateOf(false) }
    var undoConfirmed by remember { mutableStateOf(false) }

    val available = blocked == null && !actions.working && !busy
    val selectedAccount = accounts.accounts.firstOrNull { it.isSelected }

    suspend fun refreshGate() {
        val reply = graph.request(ControlOps.CONFIG_BLOCKED)
        blocked = if (reply["blocked"]?.jsonPrimitive?.booleanOrNull == true)
            "请先停止所有任务，再导入或导出配置；暂停待续不属于空闲状态" else null
    }
    suspend fun refreshUndo() {
        val reply = graph.request(ControlOps.CONFIG_UNDO_CHECK)
        undo = if (reply["available"]?.jsonPrimitive?.booleanOrNull == true)
            reply["changes"]!!.jsonArray.map { it.jsonPrimitive.content } to
                (reply["targetLabel"]?.jsonPrimitive?.contentOrNull ?: "")
        else null
        undoClearsScores = reply["clearsPersonalScores"]?.jsonPrimitive?.booleanOrNull == true
        undoConfirmed = false
    }
    fun resetImport() {
        decodedModules = emptyList(); decodedInfo = null; roleWarning = null
        importModules = emptyList(); plan = null; confirmEmptyScores = false
    }
    LaunchedEffect(graph) {
        try { refreshGate(); refreshUndo() } catch (cancelled: CancellationException) { throw cancelled }
    }

    Dialog(onDismissRequest = { if (!actions.working) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.width(760.dp).heightIn(max = 640.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("配置导入与导出", style = MaterialTheme.typography.titleLarge)
                Text("通过分享码分享全局策略、个人评分和刷开局设置", color = Muted)
                blocked?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                message?.let { Text(it, color = Accent) }
                if (actions.working) LinearProgressIndicator(Modifier.fillMaxWidth())
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    undo?.let { summary ->
                        Panel("撤销最近一次导入") {
                            summary.first.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                            if (summary.second.isNotEmpty()) Text("目标账号：${summary.second}", style = MaterialTheme.typography.bodySmall)
                            if (undoClearsScores) Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(undoConfirmed, { undoConfirmed = it }); Text("我确认恢复自定义评分", style = MaterialTheme.typography.bodySmall)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(shape = MaterialTheme.shapes.small, enabled = available && (!undoClearsScores || undoConfirmed), onClick = {
                                    actions.run {
                                        graph.request(ControlOps.CONFIG_UNDO, buildJsonObject { put("confirmEmptyScores", undoConfirmed) })
                                        message = "已恢复导入前的配置"
                                        refreshUndo(); resetImport(); output = ""
                                    }
                                }) { Text("确认恢复") }
                            }
                        }
                    }
                    Panel("导出分享码") {
                        listOf("STRATEGY" to "全局策略", "PERSONAL_SCORES" to "个人评分", "REROLL" to "刷开局设置").forEach { (name, label) ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = name in exportModules, enabled = available && (name != "REROLL" || selectedAccount != null),
                                    onCheckedChange = { checked ->
                                        exportModules = if (checked) exportModules + name else exportModules - name; output = ""
                                    })
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        if (selectedAccount != null && "REROLL" in exportModules)
                            Text("刷开局来源：${selectedAccount.alias} · ${selectedAccount.serverName}", style = MaterialTheme.typography.bodySmall)
                        if (selectedAccount == null) Text("选择账号后可导出刷开局设置", color = Muted, style = MaterialTheme.typography.bodySmall)
                        Text("仅导出已保存配置", color = Muted, style = MaterialTheme.typography.bodySmall)
                        Button(shape = MaterialTheme.shapes.small, enabled = available && exportModules.isNotEmpty() &&
                            ("REROLL" !in exportModules || selectedAccount != null), onClick = {
                            actions.run {
                                output = graph.request(ControlOps.CONFIG_EXPORT, buildJsonObject {
                                    put("modules", kotlinx.serialization.json.JsonArray(exportModules.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                                    selectedAccount?.let { put("accountId", it.id) }
                                })["code"]!!.jsonPrimitive.content
                                message = null
                            }
                        }) { Text("生成分享码") }
                        if (output.isNotEmpty()) {
                            OutlinedTextField(output, {}, readOnly = true, enabled = available,
                                label = { Text("分享码") }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
                            OutlinedButton(shape = MaterialTheme.shapes.small, enabled = available, onClick = {
                                actions.run { copyText(output); message = "已复制" }
                            }) { Text("复制分享码") }
                        }
                    }
                    Panel("导入分享码") {
                        OutlinedTextField(input, { value ->
                            input = value; if (value.length <= 200_000) resetImport() else message = "分享码过长"
                        }, enabled = available, label = { Text("输入或粘贴分享码") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(shape = MaterialTheme.shapes.small, enabled = available && input.isNotBlank(), onClick = {
                                actions.run {
                                    val reply = graph.request(ControlOps.CONFIG_DECODE, buildJsonObject { put("input", input) })
                                    decodedModules = reply["modules"]!!.jsonArray.map { it.jsonPrimitive.content }
                                    decodedInfo = "来源版本：${reply["appVersion"]!!.jsonPrimitive.content} · 导出时间：" +
                                        DateFormat.getDateTimeInstance().format(Date(reply["exportedAtMillis"]!!.jsonPrimitive.longOrNull ?: 0L))
                                    reply["personalScores"]?.jsonPrimitive?.let { decodedInfo += " · ${it.content} 项评分" }
                                    roleWarning = reply["roleWarning"]?.jsonPrimitive?.contentOrNull
                                    importModules = decodedModules
                                    targetAccountId = selectedAccount?.id
                                    message = null
                                }
                            }) { Text("解析分享码") }
                        }
                        if (decodedModules.isNotEmpty()) {
                            Text(decodedInfo ?: "", style = MaterialTheme.typography.bodySmall)
                            roleWarning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            listOf("STRATEGY" to "全局策略", "PERSONAL_SCORES" to "个人评分", "REROLL" to "刷开局设置").forEach { (name, label) ->
                                if (name !in decodedModules) return@forEach
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = name in importModules, enabled = available, onCheckedChange = { checked ->
                                        importModules = if (checked) importModules + name else importModules - name
                                        plan = null; confirmEmptyScores = false
                                    })
                                    Text(label, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            if ("REROLL" in importModules) {
                                Text("刷开局目标账号：", style = MaterialTheme.typography.bodySmall)
                                accounts.accounts.forEach { account ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected = account.id == targetAccountId, enabled = available,
                                            onClick = { targetAccountId = account.id; plan = null })
                                        Text("${account.alias} · ${account.serverName}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            if ((("PERSONAL_SCORES" in decodedModules) && decodedModules.isNotEmpty() && decodedInfo?.contains("· 0 项评分") == true && "PERSONAL_SCORES" in importModules))
                                Text("导入将清除现有自定义评分，恢复原评分", color = Muted, style = MaterialTheme.typography.bodySmall)
                            Button(shape = MaterialTheme.shapes.small, enabled = available && importModules.isNotEmpty() &&
                                ("REROLL" !in importModules || accounts.accounts.any { it.id == targetAccountId }), onClick = {
                                actions.run {
                                    val reply = graph.request(ControlOps.CONFIG_PREVIEW, buildJsonObject {
                                        put("input", input)
                                        put("modules", kotlinx.serialization.json.JsonArray(importModules.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                                        targetAccountId?.let { put("targetAccountId", it) }
                                    })
                                    plan = Triple(reply["planId"]!!.jsonPrimitive.content,
                                        reply["changes"]!!.jsonArray.map { it.jsonPrimitive.content },
                                        reply["clearsPersonalScores"]?.jsonPrimitive?.booleanOrNull == true)
                                    confirmEmptyScores = false
                                }
                            }) { Text("预览变更") }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { if (!actions.working) onDismiss() }) { Text("关闭") }
                }
            }
        }
    }
    plan?.let { preview ->
        AlertDialog(onDismissRequest = { if (!actions.working) plan = null }, title = { Text("确认替换配置？") }, text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                preview.second.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                roleWarning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (preview.third) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(confirmEmptyScores, { confirmEmptyScores = it })
                    Text("我确认清除上述自定义评分并恢复原评分", style = MaterialTheme.typography.bodySmall)
                }
                blocked?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                message?.let { Text(it) }
            }
        }, confirmButton = {
            TextButton(enabled = available && (!preview.third || confirmEmptyScores), onClick = {
                actions.run {
                    graph.request(ControlOps.CONFIG_COMMIT, buildJsonObject {
                        put("planId", preview.first); put("confirmEmptyScores", confirmEmptyScores)
                    })
                    plan = null; input = ""; output = ""; resetImport()
                    message = "导入成功，下次新任务生效"
                    refreshUndo()
                }
            }) { Text(if (actions.working) "正在导入…" else "确认导入") }
        }, dismissButton = { TextButton(enabled = !actions.working, onClick = { plan = null }) { Text("取消") } })
    }
}
