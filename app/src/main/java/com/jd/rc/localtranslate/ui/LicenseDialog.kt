package com.jd.rc.localtranslate.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun licenseTextBlocks(text: String): List<String> {
    val blocks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + 2048, text.length)
        if (end < text.length) {
            val newline = text.lastIndexOf('\n', end - 1)
            if (newline >= start + 512) end = newline + 1
            else if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        }
        blocks += text.substring(start, end)
        start = end
    }
    return blocks
}

@Composable
internal fun LicenseDialog(dismiss: () -> Unit) {
    val assets = LocalContext.current.assets
    var selected by remember { mutableStateOf<String?>(null) }
    val paths by produceState<List<String>>(emptyList()) {
        value = withContext(Dispatchers.IO) {
            fun files(directory: String): List<String> = assets.list(directory).orEmpty().flatMap { name ->
                val path = "$directory/$name"
                if (assets.list(path).orEmpty().isNotEmpty()) files(path) else listOf(path)
            }
            files("licenses")
        }
    }
    val text by produceState("", selected) {
        value = ""
        value = selected?.let { withContext(Dispatchers.IO) { runCatching { assets.open(it).bufferedReader().use { reader -> reader.readText() } }
            .getOrDefault("无法读取许可文件。") } }.orEmpty()
    }
    val blocks = remember(text) { licenseTextBlocks(text) }
    FoldAlertDialog(onDismissRequest = dismiss, title = { Text("开源许可") }, confirmButton = {
        TextButton(onClick = { if (selected != null) selected = null else dismiss() }) { Text(if (selected != null) "返回" else "关闭") }
    }, text = {
        key(selected) {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                if (selected != null) items(blocks) { Text(it, style = MaterialTheme.typography.bodySmall) }
                else items(paths, key = { it }) { path -> Text(path.removePrefix("licenses/"),
                    Modifier.fillMaxWidth().clickable { selected = path }.padding(vertical = 16.dp), style = MaterialTheme.typography.bodyMedium) }
            }
        }
    })
}
