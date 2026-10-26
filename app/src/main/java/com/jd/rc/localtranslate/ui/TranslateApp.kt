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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.core.net.toUri
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import com.jd.rc.localtranslate.AppViewModel
import com.jd.rc.localtranslate.R
import com.jd.rc.localtranslate.LiveState
import com.jd.rc.localtranslate.audio.*
import com.jd.rc.localtranslate.core.*
import com.jd.rc.localtranslate.data.*
import com.jd.rc.localtranslate.model.*
import com.jd.rc.localtranslate.inference.ConversationState
import com.jd.rc.localtranslate.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class Page(val title: String, val icon: ImageVector) {
    LIVE("即时字幕", Icons.Outlined.GraphicEq),
    MODELS("离线模型", Icons.Outlined.Memory),
    SESSIONS("对话记录", Icons.Outlined.History),
    SETTINGS("设置", Icons.Outlined.Tune),
}

@Composable
fun TranslateApp(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val cityState by vm.cityState.collectAsStateWithLifecycle()
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
                            LiveScreen(live, recording, settings, vm::startDemo, vm::pauseDemo, vm::stopDemo, vm::record, processing, changeMode = { vm.setSettings(SettingsChange(translationMode = it)) }, modelBusy = models.busy)
                        }
                        composable(Page.SESSIONS.name) {
                            val sessions by vm.sessions.collectAsStateWithLifecycle()
                            val count by vm.sessionCount.collectAsStateWithLifecycle()
                            val page by vm.sessionPage.collectAsStateWithLifecycle()
                            SessionsScreen(sessions, settings, vm::renameSession, vm::deleteSession, vm::export,
                                page, count, vm::changeSessionPage, vm::loadSessionPage)
                        }
                        composable(Page.SETTINGS.name) { SettingsScreen(settings, recording, vm::setSettings, processing, performance,
                            processSample, diagnostics != null, vm::prepareDiagnosticExport, vm::exportDiagnostics, live.demo, vm::setDemo, live.running || live.paused,
                            modelState = models, modelContent = { back -> ModelsScreen(models, vm::importModel, vm::deleteModel,
                                vm::downloadModels, vm::cancelModelDownload, onBack = back) },
                            cityBusy = cityState.busy, cityStatus = cityState.message, fetchCity = vm::fetchCity) }
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
        val active = selected == page || (selected == Page.MODELS && page == Page.SETTINGS)
        Column(itemModifier.testTag("dock-${page.name.lowercase(Locale.ROOT)}").widthIn(min = 48.dp).clip(MaterialTheme.shapes.small)
            .background(if (active) colors.primaryContainer.copy(alpha = .85f) else Color.Transparent)
            .selectable(active, role = Role.Tab, onClick = { onSelect(page) }).heightIn(min = UiSpacing.TouchTarget)
            .padding(horizontal = UiSpacing.Inline, vertical = UiSpacing.Compact),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(page.icon, page.title, tint = if (active) colors.primary else colors.onSurfaceVariant, modifier = Modifier.size(24.dp))
            Text(if (LocalDensity.current.fontScale > 1.5f) when (page) { Page.LIVE -> "字幕"; Page.MODELS -> "模型"; Page.SESSIONS -> "记录"; Page.SETTINGS -> "设置" } else page.title,
                maxLines = 1,
                fontSize = 12.sp, color = if (active) colors.primary else colors.onSurfaceVariant,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(top = 2.dp))
        }
    }
    if (rail) GlassSurface(Modifier.padding(UiSpacing.Panel).width(80.dp).fillMaxHeight().testTag("app-dock")) {
        Column(Modifier.fillMaxSize().padding(UiSpacing.PanelInset), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Page.LIVE, Page.SESSIONS).forEach { content(it, Modifier.fillMaxWidth()) }
            }
            Spacer(Modifier.height(8.dp))
            content(Page.SETTINGS, Modifier.fillMaxWidth())
        }
    } else GlassSurface(Modifier.padding(start = UiSpacing.Panel, end = UiSpacing.Panel, bottom = UiSpacing.Panel).fillMaxWidth().testTag("app-dock")) {
        Row(Modifier.fillMaxWidth().padding(UiSpacing.PanelInset), horizontalArrangement = Arrangement.SpaceEvenly) {
            Page.entries.filter { it != Page.MODELS }.forEach { content(it, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun PageHeader(page: Page, onBack: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = UiSpacing.Page, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        onBack?.let { ToolButton(Icons.AutoMirrored.Outlined.ArrowBack, "返回设置", onClick = it) }
        Column(Modifier.weight(1f)) {
            Text(page.title, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        }
        trailing()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolButton(icon: ImageVector, label: String, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    val plan = LocalPanePlan.current
    val baseProvider = TooltipDefaults.rememberPlainTooltipPositionProvider()
    val provider = remember(baseProvider, plan) { PanePopupPositionProvider(baseProvider, plan) }
    val width = with(LocalDensity.current) { (plan?.let { minOf(it.primary.width, it.controls.width) } ?: Int.MAX_VALUE).toDp() }
    TooltipBox(positionProvider = provider,
        tooltip = { PlainTooltip(modifier = Modifier.widthIn(max = minOf(240.dp, (width - 16.dp).coerceAtLeast(1.dp)))) { Text(label) } }, state = rememberTooltipState()) {
        FilledTonalIconButton(onClick, enabled = enabled, shape = MaterialTheme.shapes.small,
            modifier = Modifier.size(UiSpacing.TouchTarget),
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                contentColor = if (destructive) MaterialTheme.colorScheme.error else LocalContentColor.current)) {
            Icon(icon, label, Modifier.size(UiSpacing.Icon))
        }
    }
}

@Composable
internal fun LiveScreen(live: LiveState, recording: RecordingState, settings: UserSettings,
    startDemo: () -> Unit, pauseDemo: () -> Unit, stopDemo: () -> Unit, record: (String) -> Unit,
    processing: ConversationState = ConversationState(), changeMode: (TranslationMode) -> Unit = {}, modelBusy: Boolean = false) {
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
        Row(Modifier.fillMaxWidth().padding(horizontal = UiSpacing.Panel).testTag("live-toolbar"), verticalAlignment = Alignment.CenterVertically) {
            val mode = settings.translationMode
            val canChange = !active && !paused && !starting
            LanguageSelector(mode.first, listOf(null) + SourceLanguage.supported.filter { it != mode.second }, "第一语言", canChange,
                Modifier.weight(1f).testTag("first-language")) { changeMode(mode.withFirst(it)) }
            Icon(if (mode.first == null) Icons.AutoMirrored.Outlined.ArrowForward else Icons.Outlined.SwapHoriz,
                if (mode.first == null) "自动识别后翻译" else "双向互译", modifier = Modifier.size(20.dp))
            LanguageSelector(mode.second, SourceLanguage.supported.filter { it != mode.first }, "第二语言", canChange,
                Modifier.weight(1f).testTag("second-language")) { changeMode(mode.withSecond(requireNotNull(it))) }
            if (live.demo && LocalDensity.current.fontScale <= 1.3f) Text("演示数据", color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp))
        }
        if (live.demo && LocalDensity.current.fontScale > 1.3f) Text("演示数据", color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = UiSpacing.Page))
        if (!live.demo && processing.liveWindowTrimmed) Text(
            if (settings.keepSessions) "显示最近 500 条，保留的内容可在对话记录查看" else "显示最近 500 条，记录保留已关闭",
            Modifier.padding(horizontal = UiSpacing.Page), style = MaterialTheme.typography.labelSmall)
        val listState = rememberLazyListState()
        var followBottom by rememberSaveable(live.sessionId) { mutableStateOf(true) }
        var userScrolling by remember(live.sessionId) { mutableStateOf(false) }
        val userScroll = remember(listState, live.sessionId) { object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    userScrolling = true; followBottom = false
                }
                return Offset.Zero
            }
        } }
        LaunchedEffect(listState, live.sessionId) {
            snapshotFlow { listState.isScrollInProgress }.distinctUntilChanged().filter { !it }.collect {
                if (userScrolling) { followBottom = !listState.canScrollForward; userScrolling = false }
            }
        }
        LaunchedEffect(live.captions, followBottom) {
            if (live.captions.isNotEmpty() && followBottom && !listState.isScrollInProgress) {
                listState.scrollToItem(live.captions.lastIndex)
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull { it.index == live.captions.lastIndex }
                if (last != null) listState.scrollBy(last.size.toFloat())
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().testTag("live-content")) {
            if (live.captions.isEmpty()) Box(Modifier.align(Alignment.Center).padding(16.dp)) {
                Text(if (active && !live.demo) "正在拾音" else "暂无字幕", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("live-captions").nestedScroll(userScroll), contentPadding = PaddingValues(horizontal = UiSpacing.Page, vertical = 4.dp)) {
                items(live.captions, key = { it.segmentId }) { CaptionRow(it, settings) }
            }
            if (live.captions.isNotEmpty() && !followBottom) FilledTonalIconButton(
                onClick = { followBottom = true }, shape = MaterialTheme.shapes.small,
                modifier = Modifier.align(Alignment.BottomEnd).padding(UiSpacing.Page)) {
                Icon(Icons.Outlined.South, "回到最新字幕")
            }
        }
    } }, controls = { companion -> Column(if (companion) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
        if (companion) TranslationCompanion(live.captions.lastOrNull(), settings, Modifier.weight(1f).fillMaxWidth())
        if (!live.demo) processing.performanceStatus?.let {
            Text(it, Modifier.testTag("performance-status").padding(horizontal = UiSpacing.Page, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        if (!live.demo) processing.warning?.let {
            Text(it, Modifier.padding(horizontal = UiSpacing.Page, vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!live.demo && (permissionError || recording.error != null)) Row(Modifier.padding(horizontal = UiSpacing.Page), verticalAlignment = Alignment.CenterVertically) {
            Text(recording.error ?: "麦克风权限未开启", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (permissionError) TextButton(shape = MaterialTheme.shapes.small, onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
            }) { Text("应用权限") }
        }
        if (!live.demo && active && Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            TextButton(shape = MaterialTheme.shapes.small, onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.padding(horizontal = UiSpacing.Page)) {
                Text(if (notificationDenied) "通知未开启 · 可在应用中停止" else "开启录音状态通知", fontSize = 12.sp)
            }
        }
        GlassSurface(Modifier.fillMaxWidth().padding(start = UiSpacing.Panel, end = UiSpacing.Panel,
            top = UiSpacing.Compact, bottom = UiSpacing.Panel).testTag("live-controls")) {
            Row(Modifier.fillMaxWidth().padding(UiSpacing.PanelInset).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(UiSpacing.Compact),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(horizontal = UiSpacing.Compact)) {
                    Text(when { modelBusy -> "正在安装模型"; stopping -> "正在结束推理"; starting -> "正在加载模型"; paused -> "已暂停"; active -> if (live.demo) "演示播放中" else "拾音中"; else -> "准备就绪" },
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatTime(duration), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (active && !live.demo && LocalDensity.current.fontScale <= 1.3f)
                    WaveMeter(recording.level, Modifier.width(48.dp).height(28.dp).padding(end = 8.dp))
                if (active || paused) ToolButton(Icons.Outlined.Stop, "停止", onClick = { if (live.demo) stopDemo() else record(RecordingService.STOP) })
                Button(onClick = {
                    if (live.demo) { if (active) pauseDemo() else startDemo() }
                    else if (active) record(RecordingService.PAUSE) else startMicrophone()
                }, enabled = !starting && !modelBusy, shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.fillMaxHeight().heightIn(min = UiSpacing.TouchTarget).testTag("live-primary-action")) {
                    Icon(if (active) Icons.Outlined.Pause else if (live.demo || paused) Icons.Outlined.PlayArrow else Icons.Outlined.Mic, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(UiSpacing.Inline))
                    Text(when { active -> "暂停"; paused -> "继续"; live.demo -> "播放演示"; else -> "开始拾音" })
                }
            }
        }
    } })
}

@Composable
private fun LanguageSelector(value: SourceLanguage?, options: List<SourceLanguage?>, label: String,
    enabled: Boolean, modifier: Modifier = Modifier, onSelect: (SourceLanguage?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) open = false }
    Box(modifier) {
        TextButton(shape = MaterialTheme.shapes.small, onClick = { open = true }, enabled = enabled,
            contentPadding = PaddingValues(horizontal = UiSpacing.Panel),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = label }) {
            Text(value?.label ?: "自动识别", fontSize = 14.sp, modifier = Modifier.padding(end = 4.dp))
            Icon(Icons.Outlined.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }, shape = MaterialTheme.shapes.large) {
            options.forEach { language ->
                DropdownMenuItem(text = { Text(language?.label ?: "自动识别") },
                    leadingIcon = { if (value == language) Icon(Icons.Outlined.Check, "已选择") },
                    modifier = Modifier.testTag("language-option-${language?.code ?: "auto"}").semantics { selected = value == language },
                    onClick = { open = false; onSelect(language) })
            }
        }
    }
}

@Composable
private fun TranslationCompanion(item: SubtitleItem?, settings: UserSettings, modifier: Modifier) {
    val scroll = rememberScrollState()
    LaunchedEffect(item?.segmentId) { scroll.scrollTo(0) }
    Column(modifier.verticalScroll(scroll).padding(UiSpacing.Page).testTag("live-companion")) {
        Text("实时译文", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        if (item == null) {
            Text("等待语音", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp))
        } else {
            Text("${item.speakerId?.let { "Speaker $it" } ?: "未知说话人"} · ${item.sourceLanguage.label}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp))
            val text = when {
                item.translationStatus == TranslationStatus.NOT_REQUIRED -> item.sourceText
                item.targetText.isNotBlank() -> item.targetText
                item.translationStatus == TranslationStatus.UNAVAILABLE -> "译文不可用"
                item.stage == CaptionStage.PARTIAL -> "识别中"
                else -> "翻译中"
            }
            Text(text, color = readableSpeakerColor(MaterialTheme.colorScheme.onSurface, item.speakerId, MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.surface), fontSize = settings.fontSize.sp, lineHeight = (settings.fontSize * 1.35f).sp,
                modifier = Modifier.padding(top = 16.dp))
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
    val badgeBackground = speakerAccent(item.speakerId)?.copy(alpha = .12f)?.compositeOver(colors.background) ?: colors.surface
    val speakerColor = readableSpeakerColor(colors.onSurface, item.speakerId, colors.background, colors.surface,
        extraBackground = badgeBackground, tint = 1f)
    val avatarSize = with(LocalDensity.current) { (12.sp.toDp() + 10.dp).coerceAtLeast(22.dp) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) {}) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(avatarSize).background(badgeBackground, MaterialTheme.shapes.extraSmall), contentAlignment = Alignment.Center) {
                Text(item.speakerId ?: "?", fontSize = 12.sp, color = speakerColor, fontWeight = FontWeight.Bold)
            }
            Text(item.speakerId?.let { "Speaker $it" } ?: "未知说话人", color = speakerColor, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = UiSpacing.Inline))
            }
            Row(Modifier.heightIn(min = avatarSize), verticalAlignment = Alignment.CenterVertically) {
            Text(item.sourceLanguage.label, fontSize = 12.sp, color = colors.onSurfaceVariant)
            Text(formatTime(item.elapsedSeconds), fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = UiSpacing.Inline))
            }
        }
        val translated = item.targetText.isNotBlank()
        if (settings.bilingual || !translated) Text(item.sourceText, fontSize = if (translated) (settings.fontSize - 2).coerceAtLeast(8f).sp else settings.fontSize.sp,
            color = readableSpeakerColor(if (translated) colors.onSurfaceVariant else colors.onSurface, item.speakerId, colors.background, colors.surface),
            lineHeight = (settings.fontSize * 1.3f).sp, modifier = Modifier.padding(top = 4.dp))
        if (translated) Text(item.targetText, color = readableSpeakerColor(colors.onSurface, item.speakerId, colors.background, colors.surface), fontSize = settings.fontSize.sp, lineHeight = (settings.fontSize * 1.35f).sp,
            fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 3.dp))
        if (item.stage != CaptionStage.FINAL || item.translationStatus in setOf(TranslationStatus.WAITING, TranslationStatus.UNAVAILABLE)) Text(when {
            item.stage == CaptionStage.FINAL && item.translationStatus == TranslationStatus.WAITING -> "翻译中"
            item.stage != CaptionStage.PARTIAL && item.translationStatus == TranslationStatus.UNAVAILABLE && item.sourceLanguage != SourceLanguage.UNKNOWN -> "译文不可用"
            else -> item.stage.label
        },
            fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
        HorizontalDivider(Modifier.padding(top = 6.dp), color = colors.outlineVariant.copy(alpha = .55f))
    }
}

@Composable
internal fun ModelsScreen(state: ModelState, import: (Uri) -> Unit, delete: (String) -> Unit,
    download: () -> Unit = {}, cancelDownload: () -> Unit = {}, onBack: (() -> Unit)? = null) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(import) }
    var deleting by remember { mutableStateOf<ModelSlot?>(null) }
    val speechScroll = rememberScrollState()
    val translationScroll = rememberScrollState()
    FoldContentLayout(primary = { dual -> Column(Modifier.fillMaxWidth().then(if (dual) Modifier.fillMaxHeight().verticalScroll(speechScroll) else Modifier)) {
        PageHeader(Page.MODELS, onBack) { ToolButton(Icons.Outlined.FileUpload, "导入模型包", !state.busy && !state.inUse) { picker.launch(arrayOf("application/zip", "application/octet-stream")) } }
        Column(Modifier.padding(horizontal = UiSpacing.Page, vertical = 8.dp)) {
                Text("${state.installed.size} / ${modelSlots.size} 已安装", fontSize = 24.sp, fontWeight = FontWeight.Medium)
                if (state.inUse) Text("会话正在使用模型", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = UiSpacing.Inline))
                if (state.downloading) {
                    LinearProgressIndicator(progress = { (state.downloadedBytes.toFloat() / state.downloadTotalBytes.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("${formatDownloadBytes(state.downloadedBytes)} / ${formatDownloadBytes(state.downloadTotalBytes)}", style = MaterialTheme.typography.bodySmall)
                    TextButton(shape = MaterialTheme.shapes.small, onClick = cancelDownload) { Text("取消下载") }
                } else if (state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在导入与校验", Modifier.padding(vertical = 12.dp)) }
                if (state.installed.size < modelSlots.size && !state.downloading) {
                    Button(onClick = download, enabled = !state.busy && !state.inUse, shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("download-models")) {
                        Icon(Icons.Outlined.DownloadForOffline, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp)); Text("一键下载缺失模型")
                    }
                    Text("仅下载模型；全部约 697 MiB，预留 2 GiB 空间。也可离线导入。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                }
                state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 16.dp)) }
            SectionLabel("语音模型")
            modelSlots.filter { it.id !in setOf("hy-mt2") }.forEach { slot -> ModelRow(slot, state) { deleting = slot } }
        }
    } }, secondary = { dual -> Column(Modifier.fillMaxWidth().then(if (dual) Modifier.fillMaxHeight().verticalScroll(translationScroll) else Modifier)
        .padding(horizontal = UiSpacing.Page, vertical = 8.dp)) {
            SectionLabel("翻译模型")
            modelSlots.filter { it.id in setOf("hy-mt2") }.forEach { slot -> ModelRow(slot, state) { deleting = slot } }
            OutlinedButton(onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !state.busy && !state.inUse,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.FolderOpen, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("从本地导入")
            }
            Spacer(Modifier.height(24.dp))
    } })
    deleting?.let { slot -> FoldAlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除模型？") },
        text = { Text("${slot.title}\n删除后需重新下载或导入，才能继续使用此模型。") }, confirmButton = { TextButton(shape = MaterialTheme.shapes.small,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            enabled = !state.busy && !state.inUse, onClick = { delete(slot.id); deleting = null }) { Text("删除") } },
        dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { deleting = null }) { Text("取消") } }) }
}

@Composable
private fun ModelRow(slot: ModelSlot, state: ModelState, delete: () -> Unit) {
    var details by remember { mutableStateOf(false) }
    val model = state.installed.find { it.id == slot.id }
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.Top) {
        Icon(if (model == null) Icons.Outlined.DownloadForOffline else Icons.Outlined.Verified,
            null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp).size(UiSpacing.Icon))
        Column(Modifier.weight(1f).padding(horizontal = UiSpacing.Page)) {
            Text(slot.title, style = MaterialTheme.typography.titleMedium)
            Text(slot.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))
            Text(if (model == null) "未安装" else "文件已校验", color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = UiSpacing.Inline))
            model?.let {
                val size = if (it.sizeBytes < 1024L * 1024) "${maxOf(1, it.sizeBytes / 1024)} KiB"
                    else "${it.sizeBytes / 1024 / 1024} MiB"
                Text(size, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (model != null) {
            ToolButton(Icons.Outlined.Info, "${slot.title}详情", onClick = { details = true })
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
    if (details && model != null) FoldAlertDialog(onDismissRequest = { details = false },
        title = { Text(slot.title) }, text = {
            Column {
                Text("版本：${model.version}")
                Text("许可：${model.license}", Modifier.padding(top = 12.dp))
                SelectionContainer { Text(model.source, Modifier.padding(top = 12.dp)) }
            }
        }, confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { details = false }) { Text("关闭") } },
        dismissButton = { TextButton(shape = MaterialTheme.shapes.small, enabled = !state.busy && !state.inUse,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            onClick = { details = false; delete() }) { Text("删除模型") } })
}

private fun formatDownloadBytes(bytes: Long): String = if (bytes < 1024L * 1024) "${bytes / 1024} KiB" else "${bytes / 1024 / 1024} MiB"

@Composable
internal fun SessionsScreen(sessions: List<SavedSession>, settings: UserSettings, rename: (String, String) -> Unit,
    delete: (String) -> Unit, export: (Uri, String, String) -> Unit,
    listPage: Int = 0, totalCount: Int = sessions.size, changePage: (Int) -> Unit = {},
    loadPage: (SavedSession, Int) -> kotlinx.coroutines.flow.Flow<SessionCaptionPage>) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedTitle by rememberSaveable { mutableStateOf("") }
    var selectedCreatedAt by rememberSaveable { mutableLongStateOf(0) }
    var selectedDuration by rememberSaveable { mutableLongStateOf(0) }
    var selectedDemo by rememberSaveable { mutableStateOf(false) }
    val chosen = sessions.find { it.id == selectedId } ?: selectedId?.let {
        SavedSession(it, selectedTitle, selectedCreatedAt, selectedDuration, selectedDemo, "[]")
    }
    LaunchedEffect(chosen?.id, chosen?.title, chosen?.durationSeconds) {
        chosen?.let { selectedTitle = it.title; selectedCreatedAt = it.createdAt; selectedDuration = it.durationSeconds; selectedDemo = it.isDemo }
    }
    val lastListPage = (totalCount - 1).coerceAtLeast(0) / SESSION_PAGE_SIZE
    LaunchedEffect(listPage, lastListPage) { if (listPage > lastListPage) changePage(lastListPage) }
    val listState = rememberSaveable(listPage, saver = LazyListState.Saver) { LazyListState() }
    var deleting by remember { mutableStateOf<SavedSession?>(null) }
    var renaming by remember { mutableStateOf<SavedSession?>(null) }
    var title by remember { mutableStateOf("") }
    var exportSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var exportFormat by rememberSaveable { mutableStateOf("txt") }
    var exportMenu by remember { mutableStateOf(false) }
    val txt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { target -> exportSessionId?.let { export(target, it, exportFormat) } }; exportSessionId = null
    }
    val json = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { target -> exportSessionId?.let { export(target, it, exportFormat) } }; exportSessionId = null
    }
    val srt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        uri?.let { target -> exportSessionId?.let { export(target, it, exportFormat) } }; exportSessionId = null
    }
    FoldContentLayout(compact = if (chosen == null) CompactContent.PRIMARY else CompactContent.SECONDARY, primary = { dual ->
        Column(Modifier.fillMaxSize()) {
            PageHeader(Page.SESSIONS)
            if (totalCount > SESSION_PAGE_SIZE || listPage > 0) RecordPager(listPage, listPage < lastListPage, "记录", changePage)
            if (sessions.isEmpty()) Column(Modifier.fillMaxSize().padding(UiSpacing.Page), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Icon(Icons.Outlined.History, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
                Text("还没有对话记录", fontSize = 22.sp, modifier = Modifier.padding(top = UiSpacing.Section))
                Text(if (settings.keepSessions) "开始拾音后，保留的对话会显示在这里。" else "本地记录已关闭，可在设置中开启保留。", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
            } else LazyColumn(state = listState, modifier = Modifier.weight(1f).testTag("sessions-list"),
                contentPadding = PaddingValues(horizontal = UiSpacing.Page, vertical = 8.dp)) {
                items(sessions, key = { it.id }) { session ->
                    val active = session.id == (chosen?.id ?: if (dual) sessions.firstOrNull()?.id else null)
                    SwipeDeleteSession(session, onDelete = {
                        delete(session.id); if (selectedId == session.id) selectedId = null
                    }) { Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                        .background(if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f) else Color.Transparent)
                        .clickable { selectedId = session.id }.semantics { contentDescription = "打开会话 ${SessionTitle.display(session)}"; selected = active }
                        .padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Outlined.Chat, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(horizontal = UiSpacing.Page)) {
                            Text(SessionTitle.display(session), style = MaterialTheme.typography.titleMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(Date(session.createdAt)) + " · " + formatTime(session.durationSeconds),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = UiSpacing.Inline))
                            if (session.isDemo) Text("演示数据", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                        ToolButton(Icons.Outlined.DeleteOutline, "删除会话 ${SessionTitle.display(session)}", destructive = true) { deleting = session }
                    } }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                }
            }
        }
    }, secondary = { dual ->
        val selected = chosen ?: if (dual) sessions.firstOrNull() else null
        BackHandler(enabled = !dual && chosen != null) { selectedId = null }
        // A restored selection must keep its scroll key while Room is still loading.
        var captionPage by rememberSaveable(selected?.id ?: selectedId) { mutableIntStateOf(0) }
        val detailState = rememberSaveable(selected?.id ?: selectedId, captionPage, saver = LazyListState.Saver) { LazyListState() }
        Column(Modifier.fillMaxSize()) {
        if (selected == null) {
            Text("对话详情", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(UiSpacing.Page))
            Text("暂无打开的对话", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(UiSpacing.Page))
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = UiSpacing.Page, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!dual) ToolButton(Icons.AutoMirrored.Outlined.ArrowBack, "返回记录列表") { selectedId = null }
                Text(SessionTitle.display(selected), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                ToolButton(Icons.Outlined.Edit, "重命名") { renaming = selected; title = SessionTitle.display(selected) }
                Box {
                    ToolButton(Icons.Outlined.FileDownload, "导出") { exportMenu = true }
                    if (exportMenu) FoldAlertDialog(onDismissRequest = { exportMenu = false }, title = { Text("导出记录") },
                        confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { exportMenu = false }) { Text("取消") } }, text = { Column {
                        listOf("txt" to "纯文本", "srt" to "SRT 字幕", "json" to "JSON").forEach { (format, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                exportMenu = false; exportSessionId = selected.id; exportFormat = format
                                val filename = "${SessionTitle.exportStem(selected)}.$format"
                                when (format) { "srt" -> srt.launch(filename); "json" -> json.launch(filename); else -> txt.launch(filename) }
                            })
                        }
                    } })
                }
            }
            var retry by remember { mutableIntStateOf(0) }
            val detail by produceState<SessionDetailLoad>(SessionDetailLoad(), selected.id, selected.captionsJson, captionPage, retry) {
                value = SessionDetailLoad()
                try { loadPage(selected, captionPage).collect { value = SessionDetailLoad(it, loading = false) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { value = SessionDetailLoad(loading = false, failed = true) }
            }
            if (captionPage > 0 || detail.page?.hasMore == true)
                RecordPager(captionPage, detail.page?.hasMore == true, "字幕") { captionPage = it }
            if (detail.loading) Text("正在读取记录", Modifier.padding(horizontal = UiSpacing.Page))
            if (detail.failed) Row(Modifier.fillMaxWidth().padding(horizontal = UiSpacing.Page), verticalAlignment = Alignment.CenterVertically) {
                Text("记录读取失败", Modifier.weight(1f))
                TextButton(shape = MaterialTheme.shapes.small, onClick = { retry++ }) { Text("重试") }
            }
            val captions = detail.page?.items.orEmpty()
            if (selected.isDemo) Text("演示数据", Modifier.padding(horizontal = UiSpacing.Page), color = MaterialTheme.colorScheme.secondary)
            if (captions.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth().padding(UiSpacing.Page), contentAlignment = Alignment.Center) {
                if (!detail.loading && !detail.failed) Text("这段记录暂无字幕", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else LazyColumn(state = detailState, modifier = Modifier.weight(1f).testTag("session-captions"), contentPadding = PaddingValues(UiSpacing.Page)) {
                items(captions, key = { it.segmentId }) { CaptionRow(it, settings) }
            }
        }
        }
    })
    renaming?.let { session -> FoldAlertDialog(onDismissRequest = { renaming = null }, title = { Text("重命名会话") },
        text = { OutlinedTextField(title, { title = it.take(80) }, label = { Text("名称") }, singleLine = true,
            isError = title.isBlank(), supportingText = { Text(if (title.isBlank()) "请输入会话名称" else "${title.length} / 80") },
            shape = MaterialTheme.shapes.small) },
        confirmButton = { TextButton(shape = MaterialTheme.shapes.small, enabled = title.isNotBlank(), onClick = { rename(session.id, title.trim()); renaming = null }) { Text("保存") } },
        dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { renaming = null }) { Text("取消") } }) }
    deleting?.let { session -> FoldAlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这段记录？") },
        text = { Text("${SessionTitle.display(session)}\n删除后无法恢复。") },
        confirmButton = { TextButton(shape = MaterialTheme.shapes.small,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            onClick = { delete(session.id); if (selectedId == session.id) selectedId = null; deleting = null }) { Text("删除") } },
        dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { deleting = null }) { Text("取消") } }) }
}

@Composable
internal fun SwipeDeleteSession(session: SavedSession, onDelete: () -> Unit, content: @Composable () -> Unit) {
    var revealed by rememberSaveable(session.id) { mutableStateOf(false) }
    var drag by remember(session.id) { mutableFloatStateOf(0f) }
    val width = with(LocalDensity.current) { 104.dp.toPx() }
    val threshold = with(LocalDensity.current) { 56.dp.toPx() }
    val latestDelete by rememberUpdatedState(onDelete)
    Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).testTag("swipe-session-${session.id}")
        .semantics {
            stateDescription = if (revealed) "删除已展开，再向左滑动删除" else "向左滑动显示删除"
            customActions = listOf(CustomAccessibilityAction(if (revealed) "删除记录" else "显示删除") {
                if (revealed) latestDelete() else revealed = true
                true
            })
        }
        .pointerInput(session.id, revealed, width, threshold) {
            detectHorizontalDragGestures(onDragStart = { drag = 0f }, onDragCancel = { drag = 0f },
                onHorizontalDrag = { change, amount -> change.consume(); drag += amount },
                onDragEnd = {
                    val amount = drag; drag = 0f
                    if (amount <= -threshold) {
                        if (revealed) latestDelete() else revealed = true
                    } else if (amount >= threshold) revealed = false
                })
        }) {
        if (revealed || drag < 0) Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.errorContainer), contentAlignment = Alignment.CenterEnd) {
            TextButton(onClick = { latestDelete() }, shape = MaterialTheme.shapes.small, modifier = Modifier.width(104.dp).fillMaxHeight(),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.DeleteOutline, null)
                    Text("删除")
                    Text("再滑一次", fontSize = 10.sp)
                }
            }
        }
        Box(Modifier.fillMaxWidth().offset { IntOffset(((if (revealed) -width else 0f) + drag).coerceIn(-2 * width, 0f).roundToInt(), 0) }
            .background(MaterialTheme.colorScheme.background)) { content() }
    }
}

private data class SessionDetailLoad(val page: SessionCaptionPage? = null, val loading: Boolean = true, val failed: Boolean = false)

@Composable
private fun RecordPager(page: Int, hasMore: Boolean, label: String, change: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ToolButton(Icons.AutoMirrored.Outlined.ArrowBack, "上一页$label", page > 0) { change(page - 1) }
        Text("第 ${page + 1} 页", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        ToolButton(Icons.AutoMirrored.Outlined.ArrowForward, "下一页$label", hasMore) { change(page + 1) }
    }
}

@Composable
internal fun SettingsScreen(settings: UserSettings, recording: RecordingState, update: (SettingsChange) -> Unit,
    processing: ConversationState = ConversationState(), runtime: RuntimePerformance = RuntimePerformance(),
    processSample: ProcessSample? = null, hasDiagnostics: Boolean = false, prepareDiagnosticExport: () -> Boolean = { false },
    exportDiagnostics: (Uri, String) -> Unit = { _, _ -> }, demo: Boolean = false, setDemo: (Boolean) -> Unit = {}, inputLocked: Boolean = false,
    modelState: ModelState = ModelState(),
    modelContent: @Composable (() -> Unit) -> Unit = { back -> ModelsScreen(ModelState(), {}, {}, onBack = back) },
    cityBusy: Boolean = false, cityStatus: String? = null, fetchCity: () -> Unit = {}) {
    val context = LocalContext.current
    val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    var licenses by remember { mutableStateOf(false) }
    var section by rememberSaveable { mutableStateOf("home") }
    val homeState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    BackHandler(enabled = section != "home") { section = "home" }
    if (section == "models") { modelContent { section = "home" }; return }
    if (section == "runtime") {
        RuntimeSettingsScreen(settings, recording, processing, runtime, processSample, hasDiagnostics,
            prepareDiagnosticExport, exportDiagnostics) { section = "home" }
        return
    }
    homeState.SaveableStateProvider("settings-home") {
        val displayScroll = rememberScrollState()
        val runtimeScroll = rememberScrollState()
        FoldContentLayout(primary = { dual -> Column(Modifier.fillMaxWidth().then(if (dual) Modifier.fillMaxHeight().verticalScroll(displayScroll) else Modifier)) {
            PageHeader(Page.SETTINGS)
            SettingsEntry("离线模型", if (modelState.busy) "正在安装模型" else "${modelState.installed.size}/${modelSlots.size} 已安装",
                Icons.Outlined.Memory) { section = "models" }
            Column(Modifier.padding(horizontal = UiSpacing.Page, vertical = 8.dp)) {
                SectionLabel("显示")
                SettingSwitch("双语字幕", settings.bilingual) { update(SettingsChange(bilingual = it)) }
                SettingSwitch("衬线字体", settings.serifFont) { update(SettingsChange(serifFont = it)) }
                Text("字幕字号", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = UiSpacing.Section))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(settings.fontSize, { update(SettingsChange(fontSize = it)) }, valueRange = 8f..24f, steps = 15,
                        modifier = Modifier.weight(1f).semantics { contentDescription = "字幕字号"; stateDescription = "${settings.fontSize.toInt()} sp" })
                    Text("${settings.fontSize.toInt()}", modifier = Modifier.padding(start = 16.dp))
                }
                Text("字幕预览", fontSize = settings.fontSize.sp, modifier = Modifier.padding(vertical = UiSpacing.Page))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                Text("外观", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp, bottom = 12.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    ThemeMode.entries.forEachIndexed { index, theme ->
                        SegmentedButton(settings.theme == theme, { update(SettingsChange(theme = theme)) },
                            modifier = Modifier.fillMaxHeight(),
                            shape = SegmentedButtonDefaults.itemShape(index, 3, MaterialTheme.shapes.small), icon = {}) { Text(theme.label, fontSize = 12.sp) }
                    }
                }
                SettingSwitch("降低透明度", settings.reduceTransparency) { update(SettingsChange(reduceTransparency = it)) }
                var cityDraft by rememberSaveable(settings.sessionCity) { mutableStateOf(settings.sessionCity) }
                OutlinedTextField(cityDraft, { cityDraft = it.take(32) }, label = { Text("记录城市") }, singleLine = true, enabled = !cityBusy,
                    shape = MaterialTheme.shapes.small,
                    placeholder = { Text("未设置") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    supportingText = { Text("用于新记录名称；系统城市查询可能联网") })
                var locationDenied by rememberSaveable { mutableStateOf(false) }
                val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    locationDenied = !granted
                    if (granted) fetchCity()
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(UiSpacing.Inline)) {
                    TextButton(shape = MaterialTheme.shapes.small, enabled = !cityBusy && SessionTitle.normalizeCity(cityDraft) != settings.sessionCity,
                        modifier = Modifier.heightIn(min = UiSpacing.TouchTarget),
                        onClick = { update(SettingsChange(sessionCity = cityDraft)) }) { Text("保存城市") }
                    TextButton(shape = MaterialTheme.shapes.small, enabled = !cityBusy, onClick = {
                        locationDenied = false
                        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) fetchCity()
                        else locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    }, modifier = Modifier.heightIn(min = UiSpacing.TouchTarget)) { Text(if (cityBusy) "正在获取城市" else "获取当地城市") }
                }
                if (settings.sessionCity.isNotBlank() && SessionTitle.normalizeCity(cityDraft) == settings.sessionCity)
                    Text("城市已保存：${settings.sessionCity}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                cityStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (locationDenied) Text("定位权限未开启，可手动填写城市。", style = MaterialTheme.typography.bodySmall)
                SettingSwitch("演示模式", demo, enabled = !inputLocked && recording.phase !in listOf(RecordingPhase.RECORDING,
                    RecordingPhase.PAUSED, RecordingPhase.STARTING, RecordingPhase.STOPPING), modifier = Modifier.testTag("demo-mode"), onChecked = setDemo)
                if (inputLocked || recording.phase in listOf(RecordingPhase.RECORDING, RecordingPhase.PAUSED, RecordingPhase.STARTING, RecordingPhase.STOPPING))
                    Text("停止当前会话后可切换演示模式", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SectionLabel("隐私")
                SettingSwitch("保留对话记录", settings.keepSessions) { update(SettingsChange(keepSessions = it)) }
                Text("不保存原始音频", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 16.dp))
            }
        } }, secondary = { dual -> Column(Modifier.fillMaxWidth().then(if (dual) Modifier.fillMaxHeight().verticalScroll(runtimeScroll) else Modifier)
            .padding(horizontal = UiSpacing.Page, vertical = 8.dp)) {
                SectionLabel("性能模式")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    PerformanceMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(settings.performanceMode == mode, { update(SettingsChange(performanceMode = mode)) },
                            modifier = Modifier.fillMaxHeight(),
                            shape = SegmentedButtonDefaults.itemShape(index, PerformanceMode.entries.size, MaterialTheme.shapes.small), icon = {}) { Text(mode.label) }
                    }
                }
                Text("自动模式允许翻译使用更多 CPU 资源；省电或温度较高时降低占用。", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp))
                SettingsEntry("运行与诊断", "当前运行状态与诊断报告", Icons.Outlined.QueryStats, contentPadding = PaddingValues(vertical = 12.dp)) { section = "runtime" }
                SectionLabel(stringResource(R.string.app_name))
                TextButton(shape = MaterialTheme.shapes.small, onClick = { licenses = true }) { Text("开源许可") }
                Text(version, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 24.dp))
        } })
        if (licenses) LicenseDialog { licenses = false }
    }
}

@Composable
private fun SettingsEntry(title: String, summary: String, icon: ImageVector,
    contentPadding: PaddingValues = PaddingValues(horizontal = UiSpacing.Page, vertical = 12.dp), onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 64.dp)
        .padding(contentPadding).semantics(mergeDescendants = true) { contentDescription = title },
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null)
    }
}

@Composable
internal fun RuntimeSettingsScreen(settings: UserSettings, recording: RecordingState, processing: ConversationState,
    runtime: RuntimePerformance, processSample: ProcessSample?, hasDiagnostics: Boolean,
    prepareDiagnosticExport: () -> Boolean, exportDiagnostics: (Uri, String) -> Unit, onBack: () -> Unit) {
    var diagnosticMenu by remember { mutableStateOf(false) }
    val json = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { exportDiagnostics(it, "json") } }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let { exportDiagnostics(it, "csv") } }
    val firstScroll = rememberScrollState()
    val secondScroll = rememberScrollState()
    FoldContentLayout(primary = { dual -> Column(Modifier.fillMaxWidth().then(if (dual) Modifier.fillMaxHeight().verticalScroll(firstScroll) else Modifier)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = UiSpacing.Panel, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolButton(Icons.AutoMirrored.Outlined.ArrowBack, "返回设置", onClick = onBack)
            Text("运行与诊断", style = MaterialTheme.typography.headlineSmall)
        }
        Column(Modifier.padding(horizontal = UiSpacing.Page)) {
            DiagnosticRow("压力监测", if (runtime.active) "运行中" else "未监测")
            DiagnosticRow("热状态", when (runtime.snapshot.thermalStatus) { 0 -> "正常"; 1 -> "轻度"; 2 -> "中度"; 3 -> "严重"; 4 -> "临界"; 5 -> "紧急"; 6 -> "需关机"; else -> "不可用" })
            DiagnosticRow("系统可用内存", runtime.snapshot.availableBytes?.let { "${it / 1024 / 1024} MiB" } ?: "不可用")
            DiagnosticRow("Java 堆余量", runtime.snapshot.heapHeadroomBytes?.let { "${it / 1024 / 1024} MiB" } ?: "不可用")
            DiagnosticRow("最近进程 PSS", processSample?.pssBytes?.let { "${it / 1024 / 1024} MiB" } ?: "不可用")
            DiagnosticRow("最近进程 RSS", processSample?.rssBytes?.let { "${it / 1024 / 1024} MiB" } ?: "不可用")
            DiagnosticRow("暂停声纹语段", processing.skippedSpeakerSegments.toString())
            DiagnosticRow("未翻译语段", processing.deferredTranslations.toString())
        }
    } }, secondary = { dual -> Column(Modifier.fillMaxWidth().then(if (dual) Modifier.fillMaxHeight().verticalScroll(secondScroll) else Modifier)
        .padding(horizontal = UiSpacing.Page, vertical = 8.dp)) {
            DiagnosticRow("推理引擎", "sherpa 1.13.8 / Hy-MT2 STQ")
            DiagnosticRow("采样格式", "16 kHz · 单声道 PCM16")
            DiagnosticRow("输入格式", if (recording.inputSampleRate > 0) "${recording.inputSampleRate} Hz · PCM16" else "未采集")
            DiagnosticRow("音频输入", recording.inputDevice ?: "未连接")
            DiagnosticRow("输入恢复", recording.recoveries.toString())
            DiagnosticRow("已采集", "${recording.sampleOffset} samples")
            DiagnosticRow("队列丢帧", recording.droppedFrames.toString())
            DiagnosticRow("未处理语段", processing.droppedSegments.toString())
            DiagnosticRow("最近识别耗时", "${processing.asrMillis} ms")
            DiagnosticRow("翻译语言", settings.translationMode.label)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("诊断报告", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                ToolButton(Icons.Outlined.FileDownload, "导出运行诊断", hasDiagnostics) { diagnosticMenu = true }
            }
            if (!hasDiagnostics) Text("暂无诊断报告；完成一次实际拾音后可导出。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = UiSpacing.Panel))
            Text("未采集或设备不提供的指标显示为不可用。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
    } })
    if (diagnosticMenu) FoldAlertDialog(onDismissRequest = { diagnosticMenu = false }, title = { Text("导出运行诊断") },
        confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { diagnosticMenu = false }) { Text("取消") } }, text = { Column {
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
        modifier = Modifier.padding(top = UiSpacing.Section, bottom = 12.dp))
}
@Composable
private fun SettingSwitch(title: String, checked: Boolean, modifier: Modifier = Modifier,
    enabled: Boolean = true, onChecked: (Boolean) -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = UiSpacing.TouchTarget)
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChecked)
        .semantics { contentDescription = title }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                color = if (enabled) LocalContentColor.current else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
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
