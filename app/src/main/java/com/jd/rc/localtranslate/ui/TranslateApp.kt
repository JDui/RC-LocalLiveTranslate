package com.jd.rc.localtranslate.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.core.net.toUri
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import com.jd.rc.localtranslate.AppViewModel
import com.jd.rc.localtranslate.LiveState
import com.jd.rc.localtranslate.audio.*
import com.jd.rc.localtranslate.core.*
import com.jd.rc.localtranslate.data.*
import com.jd.rc.localtranslate.model.*
import com.jd.rc.localtranslate.inference.ConversationState
import com.jd.rc.localtranslate.runtime.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class Page(val title: String, val subtitle: String, val icon: ImageVector) {
    LIVE("即时字幕", "RC LOCAL LIVE TRANSLATE", Icons.Outlined.GraphicEq),
    MODELS("离线模型", "ON-DEVICE INTELLIGENCE", Icons.Outlined.Memory),
    SESSIONS("对话记录", "YOUR CONVERSATIONS", Icons.Outlined.History),
    SETTINGS("设置", "MAKE IT YOURS", Icons.Outlined.Tune),
}

@Composable
fun TranslateApp(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val processing by vm.conversation.collectAsStateWithLifecycle()
    val performance by vm.performance.collectAsStateWithLifecycle()
    val diagnostics by vm.diagnostics.collectAsStateWithLifecycle()
    val processSample by vm.processSample.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it); vm.acknowledgeNotice() } }
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val selected = Page.entries.find { it.name == entry?.destination?.route } ?: Page.LIVE
    val folds = rememberWindowFolds()
    GlassTheme(settings) {
        Box(Modifier.fillMaxSize()) {
            GlassBackdrop(Modifier.matchParentSize())
            FoldAppShell(folds, Modifier.fillMaxSize().safeDrawingPadding(), navigation = { rail ->
                GlassNavigation(selected, rail) { page ->
                    nav.navigate(page.name) { popUpTo(nav.graph.startDestinationId) { saveState = true }; launchSingleTop = true; restoreState = true }
                }
            }) {
                    NavHost(nav, Page.LIVE.name, Modifier.fillMaxSize()) {
                        composable(Page.LIVE.name) {
                            LiveScreen(live, recording, settings, models.installed.size, vm::setDemo,
                                vm::startDemo, vm::pauseDemo, vm::stopDemo, vm::record,
                                { vm.setSettings(SettingsChange(bilingual = !settings.bilingual)) },
                                { nav.navigate(Page.MODELS.name) { launchSingleTop = true } }, processing)
                        }
                        composable(Page.MODELS.name) { ModelsScreen(models, vm::importModel, vm::deleteModel) }
                        composable(Page.SESSIONS.name) { SessionsScreen(sessions, settings, vm::renameSession, vm::deleteSession, vm::export) }
                        composable(Page.SETTINGS.name) { SettingsScreen(settings, recording, vm::setSettings, processing, performance,
                            processSample, diagnostics != null, vm::prepareDiagnosticExport, vm::exportDiagnostics) }
                    }
                    FoldSafePage {
                        Box(Modifier.fillMaxSize()) { SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp)) }
                    }
            }
        }
    }
}

@Composable
internal fun GlassNavigation(selected: Page, rail: Boolean, onSelect: (Page) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val content: @Composable (Page, Modifier) -> Unit = { page, itemModifier ->
        val active = selected == page
        Column(itemModifier.widthIn(min = 48.dp).clip(RoundedCornerShape(22.dp))
            .background(if (active) colors.primaryContainer.copy(alpha = .85f) else Color.Transparent)
            .selectable(active, role = Role.Tab, onClick = { onSelect(page) }).padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(page.icon, page.title, tint = if (active) colors.primary else colors.onSurfaceVariant, modifier = Modifier.size(24.dp))
            Text(if (LocalDensity.current.fontScale > 1.5f) page.title.chunked(2).joinToString("\n") else page.title,
                fontSize = 11.sp, color = if (active) colors.primary else colors.onSurfaceVariant,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(top = 4.dp))
        }
    }
    if (rail) GlassSurface(Modifier.padding(12.dp).width(92.dp).fillMaxHeight(), radius = 32) {
        Column(Modifier.fillMaxSize().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("RC", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = colors.primary)
            Spacer(Modifier.height(12.dp)); Page.entries.forEach { content(it, Modifier.fillMaxWidth()) }
        }
    } else GlassSurface(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp).fillMaxWidth(), radius = 32) {
        Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Page.entries.forEach { content(it, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun PageHeader(page: Page, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(page.subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(page.title, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        }
        trailing()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val plan = LocalPanePlan.current
    val baseProvider = TooltipDefaults.rememberPlainTooltipPositionProvider()
    val provider = remember(baseProvider, plan) { PanePopupPositionProvider(baseProvider, plan) }
    val width = with(LocalDensity.current) { (plan?.let { minOf(it.primary.width, it.controls.width) } ?: Int.MAX_VALUE).toDp() }
    TooltipBox(positionProvider = provider,
        tooltip = { PlainTooltip(modifier = Modifier.widthIn(max = minOf(240.dp, (width - 16.dp).coerceAtLeast(1.dp)))) { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick, enabled = enabled, modifier = Modifier.size(48.dp)) { Icon(icon, label) }
    }
}

@Composable
internal fun LiveScreen(live: LiveState, recording: RecordingState, settings: UserSettings, modelCount: Int,
    setDemo: (Boolean) -> Unit, startDemo: () -> Unit, pauseDemo: () -> Unit, stopDemo: () -> Unit,
    record: (String) -> Unit, toggleBilingual: () -> Unit, openModels: () -> Unit,
    processing: ConversationState = ConversationState()) {
    val context = LocalContext.current
    var permissionError by remember { mutableStateOf(false) }
    var notificationDenied by remember { mutableStateOf(false) }
    val recordingPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionError = !granted
        if (granted) record(RecordingService.START)
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> notificationDenied = !granted }
    fun startMicrophone() {
        permissionError = false
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) record(RecordingService.START)
        else recordingPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    val active = if (live.demo) live.running else recording.phase == RecordingPhase.RECORDING
    val paused = if (live.demo) live.paused else recording.phase == RecordingPhase.PAUSED
    val stopping = recording.phase == RecordingPhase.STOPPING
    val starting = recording.phase == RecordingPhase.STARTING || stopping
    val duration = live.elapsedSeconds
    FoldLiveLayout(body = { Column(Modifier.fillMaxSize()) {
        PageHeader(Page.LIVE) {
            ToolButton(if (settings.bilingual) Icons.Outlined.Translate else Icons.Outlined.Subtitles,
                if (settings.bilingual) "切换为仅中文" else "切换为双语", onClick = toggleBilingual)
        }
        if (LocalDensity.current.fontScale > 1.3f) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                Text(if (live.demo) "演示数据" else "仅在本机", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                InputModeSwitch(live.demo, !active && !paused && !starting, Modifier.fillMaxWidth().padding(top = 10.dp), setDemo)
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (live.demo) "演示数据" else "仅在本机", color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                InputModeSwitch(live.demo, !active && !paused && !starting, onChange = setDemo)
            }
        }
        if (!live.demo) Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)
            .clickable(onClick = openModels), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (modelCount == 0) "请安装离线模型" else "$modelCount 个模型已安装 · 完全本地推理",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(start = 6.dp))
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, "打开模型", Modifier.size(16.dp))
        }
        val listState = rememberLazyListState()
        var followBottom by rememberSaveable { mutableStateOf(true) }
        LaunchedEffect(listState) {
            snapshotFlow { listState.isScrollInProgress }.distinctUntilChanged().filter { !it }.collect {
                followBottom = !listState.canScrollForward
            }
        }
        LaunchedEffect(live.captions, followBottom) {
            if (live.captions.isNotEmpty() && followBottom && !listState.isScrollInProgress) listState.animateScrollToItem(live.captions.lastIndex)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (live.captions.isEmpty()) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.GraphicEq, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = .65f))
                Text(if (active && !live.demo) "正在拾音" else "每一种语言，都听得见", fontSize = 23.sp,
                    fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 20.dp))
                Text(if (live.demo) "中 · 英 · 日 / 对话演示" else "拾音测试", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            } else LazyColumn(state = listState, modifier = Modifier.testTag("live-captions"), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 20.dp)) {
                items(live.captions, key = { it.segmentId }) { CaptionRow(it, settings) }
            }
            if (live.captions.isNotEmpty() && !followBottom) FilledTonalIconButton(
                onClick = { followBottom = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                Icon(Icons.Outlined.South, "回到最新字幕")
            }
        }
    } }, controls = { companion -> Column(if (companion) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
        if (companion) TranslationCompanion(live.captions.lastOrNull(), settings, Modifier.weight(1f).fillMaxWidth())
        if (!live.demo) processing.performanceStatus?.let {
            Text(it, Modifier.testTag("performance-status").padding(horizontal = 24.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        if (!live.demo) processing.warning?.let {
            Text(it, Modifier.padding(horizontal = 24.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!live.demo && (permissionError || recording.error != null)) Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(recording.error ?: "麦克风权限未开启", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (permissionError) TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
            }) { Text("应用权限") }
        }
        if (!live.demo && active && Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            TextButton(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(if (notificationDenied) "通知未开启 · 可在应用中停止" else "开启录音状态通知", fontSize = 12.sp)
            }
        }
        GlassSurface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape))
                    Text(when { stopping -> "正在结束推理"; starting -> "正在加载模型"; paused -> "已暂停"; active -> if (live.demo) "演示播放中" else "麦克风采集中"; else -> "准备就绪" },
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 7.dp).weight(1f))
                    Text(formatTime(duration), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    WaveMeter(if (active && !live.demo) recording.level else 0f, Modifier.weight(1f).height(38.dp))
                    Spacer(Modifier.width(16.dp))
                    if (active || paused) ToolButton(Icons.Outlined.Stop, "停止", onClick = { if (live.demo) stopDemo() else record(RecordingService.STOP) })
                    Button(onClick = {
                        if (live.demo) { if (active) pauseDemo() else startDemo() }
                        else if (active) record(RecordingService.PAUSE) else startMicrophone()
                    }, enabled = !starting, contentPadding = PaddingValues(horizontal = 18.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(if (active) Icons.Outlined.Pause else if (live.demo || paused) Icons.Outlined.PlayArrow else Icons.Outlined.Mic, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(when { active -> "暂停"; paused -> "继续"; live.demo -> "播放演示"; else -> "开始拾音" })
                    }
                }
            }
        }
    } })
}

@Composable
private fun TranslationCompanion(item: SubtitleItem?, settings: UserSettings, modifier: Modifier) {
    val scroll = rememberScrollState()
    LaunchedEffect(item?.segmentId) { scroll.scrollTo(0) }
    Column(modifier.verticalScroll(scroll).padding(24.dp).testTag("live-companion")) {
        Text("实时译文", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        if (item == null) {
            Text("等待语音", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp))
        } else {
            Text("${item.speakerId?.let { "Speaker $it" } ?: "未知说话人"} · ${item.sourceLanguage.label}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp))
            val text = when {
                item.sourceLanguage == SourceLanguage.ZH -> item.sourceText
                item.targetText.isNotBlank() -> item.targetText
                item.translationStatus == TranslationStatus.UNAVAILABLE -> "译文不可用"
                item.stage == CaptionStage.PARTIAL -> "识别中"
                else -> "翻译中"
            }
            Text(text, fontSize = settings.fontSize.sp, lineHeight = (settings.fontSize + 10).sp,
                modifier = Modifier.padding(top = 16.dp))
        }
    }
}

@Composable
private fun InputModeSwitch(demo: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier) {
        listOf("麦克风", "演示").forEachIndexed { index, label ->
            SegmentedButton(selected = demo == (index == 1), onClick = { onChange(index == 1) }, enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, 2), icon = {}) { Text(label, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun WaveMeter(level: Float, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.semantics { contentDescription = "音量 ${(level * 100).toInt()}%" }) {
        val count = 23
        val step = size.width / count
        for (i in 0 until count) {
            val factor = .25f + ((i * 7) % 11) / 14f
            val height = 3.dp.toPx() + (size.height - 3.dp.toPx()) * level * factor
            drawLine(color.copy(alpha = .4f + level * .5f), androidx.compose.ui.geometry.Offset(step * (i + .5f), (size.height - height) / 2),
                androidx.compose.ui.geometry.Offset(step * (i + .5f), (size.height + height) / 2), strokeWidth = minOf(3.dp.toPx(), step * .55f), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

@Composable
internal fun CaptionRow(item: SubtitleItem, settings: UserSettings) {
    val colors = MaterialTheme.colorScheme
    val speakerColor = if (item.speakerId == "B") colors.secondary else colors.primary
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp).semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).background(speakerColor.copy(alpha = .12f), CircleShape), contentAlignment = Alignment.Center) {
                Text(item.speakerId ?: "?", fontSize = 12.sp, color = speakerColor, fontWeight = FontWeight.Bold)
            }
            Text(item.speakerId?.let { "Speaker $it" } ?: "未知说话人", color = speakerColor, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp).weight(1f))
            Text(item.sourceLanguage.label, fontSize = 11.sp, color = colors.onSurfaceVariant)
            Text(formatTime(item.elapsedSeconds), fontSize = 11.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp))
        }
        val translated = item.targetText.isNotBlank()
        if (settings.bilingual || !translated) Text(item.sourceText, fontSize = if (translated) (settings.fontSize - 3).sp else settings.fontSize.sp,
            color = if (translated) colors.onSurfaceVariant else colors.onSurface,
            lineHeight = (settings.fontSize + 8).sp, modifier = Modifier.padding(top = 10.dp))
        if (translated) Text(item.targetText, fontSize = settings.fontSize.sp, lineHeight = (settings.fontSize + 10).sp,
            fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
        Text(when {
            item.stage == CaptionStage.FINAL && item.translationStatus == TranslationStatus.WAITING -> "翻译中"
            item.stage != CaptionStage.PARTIAL && item.translationStatus == TranslationStatus.UNAVAILABLE && item.sourceLanguage != SourceLanguage.UNKNOWN -> "译文不可用"
            else -> item.stage.label
        },
            fontSize = 10.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        HorizontalDivider(Modifier.padding(top = 16.dp), color = colors.outlineVariant.copy(alpha = .55f))
    }
}

@Composable
internal fun ModelsScreen(state: ModelState, import: (Uri) -> Unit, delete: (String) -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(import) }
    var deleting by remember { mutableStateOf<ModelSlot?>(null) }
    val speechScroll = rememberScrollState()
    val translationScroll = rememberScrollState()
    FoldContentLayout(primary = { dual -> Column(Modifier.fillMaxWidth().then(if (dual) Modifier.fillMaxHeight().verticalScroll(speechScroll) else Modifier)) {
        PageHeader(Page.MODELS) { ToolButton(Icons.Outlined.FileUpload, "导入模型包", !state.busy && !state.inUse) { picker.launch(arrayOf("application/zip", "application/octet-stream")) } }
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text("${state.installed.size} / ${modelSlots.size} 已安装", fontSize = 24.sp, fontWeight = FontWeight.Medium)
                Text(if (state.inUse) "会话正在使用模型" else "sherpa-onnx · Foxlet", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 20.dp))
                if (state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在导入与校验", Modifier.padding(vertical = 12.dp)) }
                state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 16.dp)) }
            SectionLabel("语音模型")
            modelSlots.filter { it.id !in setOf("en-zh", "ja-en") }.forEach { slot -> ModelRow(slot, state) { deleting = slot } }
        }
    } }, secondary = { dual -> Column(Modifier.fillMaxWidth().then(if (dual) Modifier.fillMaxHeight().verticalScroll(translationScroll) else Modifier)
        .padding(horizontal = 24.dp, vertical = 8.dp)) {
            SectionLabel("翻译模型")
            modelSlots.filter { it.id in setOf("en-zh", "ja-en") }.forEach { slot -> ModelRow(slot, state) { deleting = slot } }
            Text("离线部署", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 24.dp))
            Text("模型包 ZIP · manifest.json · ARM64", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
            OutlinedButton(onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !state.busy && !state.inUse,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.FolderOpen, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("从本地导入")
            }
            Spacer(Modifier.height(24.dp))
    } })
    deleting?.let { slot -> FoldAlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除模型？") },
        text = { Text(slot.title) }, confirmButton = { TextButton(onClick = { delete(slot.id); deleting = null }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

@Composable
private fun ModelRow(slot: ModelSlot, state: ModelState, delete: () -> Unit) {
    val model = state.installed.find { it.id == slot.id }
    Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.Top) {
        Icon(if (model == null) Icons.Outlined.DownloadForOffline else Icons.Outlined.Verified,
            null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp).size(26.dp))
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(slot.title, style = MaterialTheme.typography.titleMedium)
            Text(slot.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))
            Text(if (model == null) "未安装" else "文件已校验 · ${model.version}", color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 10.dp))
            model?.let {
                Text("${it.sizeBytes / 1024 / 1024} MB · ${it.license}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                Text(it.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (model != null) ToolButton(Icons.Outlined.DeleteOutline, "删除 ${slot.title}", !state.busy && !state.inUse, delete)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
}

@Composable
internal fun SessionsScreen(sessions: List<SavedSession>, settings: UserSettings, rename: (String, String) -> Unit,
    delete: (String) -> Unit, export: (Uri, SavedSession, String) -> Unit) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val chosen = sessions.find { it.id == selectedId }
    val listState = rememberLazyListState()
    var deleting by remember { mutableStateOf<SavedSession?>(null) }
    var renaming by remember { mutableStateOf<SavedSession?>(null) }
    var title by remember { mutableStateOf("") }
    var exportSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var exportFormat by rememberSaveable { mutableStateOf("txt") }
    var exportMenu by remember { mutableStateOf(false) }
    val txt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { target -> sessions.find { it.id == exportSessionId }?.let { export(target, it, exportFormat) } }; exportSessionId = null
    }
    val json = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { target -> sessions.find { it.id == exportSessionId }?.let { export(target, it, exportFormat) } }; exportSessionId = null
    }
    val srt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        uri?.let { target -> sessions.find { it.id == exportSessionId }?.let { export(target, it, exportFormat) } }; exportSessionId = null
    }
    FoldContentLayout(compact = if (chosen == null) CompactContent.PRIMARY else CompactContent.SECONDARY, primary = { dual ->
        Column(Modifier.fillMaxSize()) {
            PageHeader(Page.SESSIONS)
            if (sessions.isEmpty()) Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Icon(Icons.Outlined.History, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
                Text("还没有对话记录", fontSize = 22.sp, modifier = Modifier.padding(top = 18.dp))
                Text(if (settings.keepSessions) "仅保存在此设备" else "本地记录已关闭", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
            } else LazyColumn(state = listState, modifier = Modifier.weight(1f).testTag("sessions-list"),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)) {
                items(sessions, key = { it.id }) { session ->
                    val active = session.id == (chosen?.id ?: if (dual) sessions.firstOrNull()?.id else null)
                    Row(Modifier.fillMaxWidth().background(if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f) else Color.Transparent)
                        .clickable { selectedId = session.id }.semantics { contentDescription = "打开会话 ${session.title}"; selected = active }
                        .padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Outlined.Chat, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                            Text(session.title, style = MaterialTheme.typography.titleMedium)
                            Text(SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(Date(session.createdAt)) + " · " + formatTime(session.durationSeconds),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                            if (session.isDemo) Text("演示数据", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                        ToolButton(Icons.Outlined.DeleteOutline, "删除会话 ${session.title}") { deleting = session }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                }
            }
        }
    }, secondary = { dual ->
        val selected = chosen ?: if (dual) sessions.firstOrNull() else null
        BackHandler(enabled = !dual && chosen != null) { selectedId = null }
        // A restored selection must keep its scroll key while Room is still loading.
        val detailState = rememberSaveable(selected?.id ?: selectedId, saver = LazyListState.Saver) { LazyListState() }
        Column(Modifier.fillMaxSize()) {
        if (selected == null) {
            Text("对话详情", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(24.dp))
            Text("暂无打开的对话", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!dual) ToolButton(Icons.AutoMirrored.Outlined.ArrowBack, "返回记录列表") { selectedId = null }
                Text(selected.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                ToolButton(Icons.Outlined.Edit, "重命名") { renaming = selected; title = selected.title }
                Box {
                    ToolButton(Icons.Outlined.FileDownload, "导出") { exportMenu = true }
                    if (exportMenu) FoldAlertDialog(onDismissRequest = { exportMenu = false }, title = { Text("导出记录") },
                        confirmButton = { TextButton(onClick = { exportMenu = false }) { Text("取消") } }, text = { Column {
                        listOf("txt" to "纯文本", "srt" to "SRT 字幕", "json" to "JSON").forEach { (format, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                exportMenu = false; exportSessionId = selected.id; exportFormat = format
                                val filename = "RC-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(selected.createdAt))}.$format"
                                when (format) { "srt" -> srt.launch(filename); "json" -> json.launch(filename); else -> txt.launch(filename) }
                            })
                        }
                    } })
                }
            }
            val captions = remember(selected.captionsJson) { runCatching { CaptionJson.decode(selected.captionsJson) }.getOrDefault(emptyList()) }
            if (selected.isDemo) Text("演示数据", Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.secondary)
            LazyColumn(state = detailState, modifier = Modifier.weight(1f).testTag("session-captions"), contentPadding = PaddingValues(24.dp)) {
                items(captions, key = { it.segmentId }) { CaptionRow(it, settings) }
            }
        }
        }
    })
    renaming?.let { session -> FoldAlertDialog(onDismissRequest = { renaming = null }, title = { Text("重命名会话") },
        text = { OutlinedTextField(title, { title = it.take(80) }, label = { Text("名称") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { rename(session.id, title); renaming = null }) { Text("保存") } },
        dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } }) }
    deleting?.let { session -> FoldAlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这段记录？") },
        text = { Text("${session.title}\n删除后无法恢复。") },
        confirmButton = { TextButton(onClick = { delete(session.id); deleting = null }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

@Composable
internal fun SettingsScreen(settings: UserSettings, recording: RecordingState, update: (SettingsChange) -> Unit,
    processing: ConversationState = ConversationState(), runtime: RuntimePerformance = RuntimePerformance(),
    processSample: ProcessSample? = null, hasDiagnostics: Boolean = false, prepareDiagnosticExport: () -> Boolean = { false },
    exportDiagnostics: (Uri, String) -> Unit = { _, _ -> }) {
    val context = LocalContext.current
    val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    var licenses by remember { mutableStateOf(false) }
    var diagnosticMenu by remember { mutableStateOf(false) }
    val json = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { exportDiagnostics(it, "json") } }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let { exportDiagnostics(it, "csv") } }
    val displayScroll = rememberScrollState()
    val runtimeScroll = rememberScrollState()
    FoldContentLayout(primary = { dual -> Column(Modifier.fillMaxWidth().then(if (dual) Modifier.fillMaxHeight().verticalScroll(displayScroll) else Modifier)) {
        PageHeader(Page.SETTINGS)
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            SectionLabel("显示")
            SettingSwitch("双语字幕", "原文与简体中文", settings.bilingual) { update(SettingsChange(bilingual = it)) }
            Text("字幕字号", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(settings.fontSize, { update(SettingsChange(fontSize = it)) }, valueRange = 16f..28f, steps = 5, modifier = Modifier.weight(1f))
                Text("${settings.fontSize.toInt()}", modifier = Modifier.padding(start = 16.dp))
            }
            Text("每一种语言，都听得见", fontSize = settings.fontSize.sp, modifier = Modifier.padding(vertical = 14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            Text("外观", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp, bottom = 12.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, theme ->
                    SegmentedButton(settings.theme == theme, { update(SettingsChange(theme = theme)) },
                        shape = SegmentedButtonDefaults.itemShape(index, 3), icon = {}) { Text(theme.label, fontSize = 12.sp) }
                }
            }
            SettingSwitch("降低透明度", "增强背景与字幕对比", settings.reduceTransparency) { update(SettingsChange(reduceTransparency = it)) }
            SectionLabel("隐私")
            SettingSwitch("保留对话记录", "文本仅保存在本机", settings.keepSessions) { update(SettingsChange(keepSessions = it)) }
            Text("不保存原始音频", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 16.dp))
        }
    } }, secondary = { dual -> Column(Modifier.fillMaxWidth().then(if (dual) Modifier.fillMaxHeight().verticalScroll(runtimeScroll) else Modifier)
        .padding(horizontal = 24.dp, vertical = 8.dp)) {
            SectionLabel("性能模式")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                PerformanceMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(settings.performanceMode == mode, { update(SettingsChange(performanceMode = mode)) },
                        shape = SegmentedButtonDefaults.itemShape(index, PerformanceMode.entries.size), icon = {}) { Text(mode.label) }
                }
            }
            SectionLabel("当前运行")
            DiagnosticRow("压力监测", if (runtime.active) "运行中" else "未监测")
            DiagnosticRow("热状态", when (runtime.snapshot.thermalStatus) { 0 -> "正常"; 1 -> "轻度"; 2 -> "中度"; 3 -> "严重"; 4 -> "临界"; 5 -> "紧急"; 6 -> "需关机"; else -> "不可用" })
            DiagnosticRow("系统可用内存", runtime.snapshot.availableBytes?.let { "${it / 1024 / 1024} MiB" } ?: "不可用")
            DiagnosticRow("Java 堆余量", runtime.snapshot.heapHeadroomBytes?.let { "${it / 1024 / 1024} MiB" } ?: "不可用")
            DiagnosticRow("最近进程 PSS", processSample?.pssBytes?.let { "${it / 1024 / 1024} MiB" } ?: "不可用")
            DiagnosticRow("最近进程 RSS", processSample?.rssBytes?.let { "${it / 1024 / 1024} MiB" } ?: "不可用")
            DiagnosticRow("暂停声纹语段", processing.skippedSpeakerSegments.toString())
            DiagnosticRow("未翻译语段", processing.deferredTranslations.toString())
            DiagnosticRow("推理引擎", "sherpa 1.13.8 / Foxlet 0.5.0")
            DiagnosticRow("采样格式", "16 kHz · 单声道 PCM16")
            DiagnosticRow("输入格式", if (recording.inputSampleRate > 0) "${recording.inputSampleRate} Hz · PCM16" else "未采集")
            DiagnosticRow("音频输入", recording.inputDevice ?: "未连接")
            DiagnosticRow("输入恢复", recording.recoveries.toString())
            DiagnosticRow("已采集", "${recording.sampleOffset} samples")
            DiagnosticRow("队列丢帧", recording.droppedFrames.toString())
            DiagnosticRow("未处理语段", processing.droppedSegments.toString())
            DiagnosticRow("最近识别耗时", "${processing.asrMillis} ms")
            DiagnosticRow("翻译路径", "EN → 中文 / JA → EN → 中文")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("运行诊断", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                ToolButton(Icons.Outlined.FileDownload, "导出运行诊断", hasDiagnostics) { diagnosticMenu = true }
            }
            SectionLabel("RC 本地同传")
            TextButton(onClick = { licenses = true }) { Text("开源许可") }
            Text("$version · Android Native", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 24.dp))
    } })
    if (licenses) LicenseDialog { licenses = false }
    if (diagnosticMenu) FoldAlertDialog(onDismissRequest = { diagnosticMenu = false }, title = { Text("导出运行诊断") },
        confirmButton = { TextButton(onClick = { diagnosticMenu = false }) { Text("取消") } }, text = { Column {
            listOf("json" to "JSON", "csv" to "CSV").forEach { (format, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    diagnosticMenu = false
                    if (prepareDiagnosticExport()) {
                        val filename = "RC-diagnostics-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.$format"
                        if (format == "csv") csv.launch(filename) else json.launch(filename)
                    }
                })
            }
        } })
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 18.dp, bottom = 14.dp))
}
@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChecked).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
        }
        Switch(checked, onCheckedChange = null)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
}
@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.6f))
    }
}
