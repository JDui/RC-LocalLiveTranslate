package com.jd.rc.localtranslate.ui

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupPositionProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

internal enum class FoldAxis { VERTICAL, HORIZONTAL }
internal data class WindowFold(val bounds: IntRect, val axis: FoldAxis, val separating: Boolean, val occluding: Boolean)
internal data class PanePlan(val primary: IntRect, val controls: IntRect, val split: Boolean, val rail: Boolean = false,
    val windowOrigin: IntOffset = IntOffset.Zero, val avoidsFold: Boolean = false)
internal val LocalPanePlan = compositionLocalOf<PanePlan?> { null }

internal fun contentPanes(plan: PanePlan?, width: Int, height: Int, minimumWidth: Int, minimumHeight: Int, gap: Int): PanePlan {
    val current = plan ?: PanePlan(IntRect(0, 0, width, height), IntRect(0, 0, width, height), false)
    if (current.split) return current
    val region = current.primary
    if (region.width < minimumWidth * 2 + gap || region.height < minimumHeight) return current
    val middle = region.left + (region.width - gap) / 2
    return current.copy(primary = region.copy(right = middle), controls = region.copy(left = middle + gap), split = true)
}

internal enum class CompactContent { STACK, PRIMARY, SECONDARY }

/** Stable pane slots keep scroll/selection state while their measurements change. */
@Composable
internal fun FoldContentLayout(compact: CompactContent = CompactContent.STACK,
    primary: @Composable (Boolean) -> Unit, secondary: @Composable (Boolean) -> Unit) {
    val plan = LocalPanePlan.current
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panes = contentPanes(plan, constraints.maxWidth, constraints.maxHeight,
            with(density) { (if (fontScale > 1.3f) 400 else 360).dp.roundToPx() },
            with(density) { (if (fontScale > 1.3f) 360 else 240).dp.roundToPx() }, with(density) { 24.dp.roundToPx() })
        val dual = panes.split
        val viewport = if (dual) Modifier.fillMaxSize() else with(density) {
            Modifier.offset { IntOffset(panes.primary.left, panes.primary.top) }
                .requiredSize(panes.primary.width.toDp(), panes.primary.height.toDp())
                .then(if (compact == CompactContent.STACK) Modifier.verticalScroll(scroll) else Modifier)
        }
        Layout(modifier = viewport, content = {
            Box(Modifier.clipToBounds().then(if (!dual && compact == CompactContent.SECONDARY) Modifier.clearAndSetSemantics {} else Modifier)) { primary(dual) }
            Box(Modifier.clipToBounds().then(if (!dual && compact == CompactContent.PRIMARY) Modifier.clearAndSetSemantics {} else Modifier)) { secondary(dual) }
        }) { children, constraints ->
            if (dual) {
                val first = children[0].measure(Constraints.fixed(panes.primary.width, panes.primary.height))
                val second = children[1].measure(Constraints.fixed(panes.controls.width, panes.controls.height))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    first.place(panes.primary.left, panes.primary.top); second.place(panes.controls.left, panes.controls.top)
                }
            } else if (compact == CompactContent.STACK) {
                val childConstraints = Constraints(minWidth = constraints.maxWidth, maxWidth = constraints.maxWidth)
                val first = children[0].measure(childConstraints); val second = children[1].measure(childConstraints)
                layout(constraints.maxWidth, first.height + second.height) { first.place(0, 0); second.place(0, first.height) }
            } else {
                val first = children[0].measure(Constraints.fixed(constraints.maxWidth, constraints.maxHeight))
                val second = children[1].measure(Constraints.fixed(constraints.maxWidth, constraints.maxHeight))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    if (compact == CompactContent.PRIMARY) first.place(0, 0) else second.place(0, 0)
                }
            }
        }
    }
}

internal class PanePopupPositionProvider(private val delegate: PopupPositionProvider, private val plan: PanePlan?) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val preferred = delegate.calculatePosition(anchorBounds, windowSize, layoutDirection, popupContentSize)
        if (plan?.avoidsFold != true) return preferred
        val panes = listOf(plan.primary, plan.controls).map { it.translate(plan.windowOrigin) }
        val center = anchorBounds.center
        val pane = panes.firstOrNull { it.contains(center) } ?: panes.first()
        return IntOffset(preferred.x.coerceIn(pane.left, maxOf(pane.left, pane.right - popupContentSize.width)),
            preferred.y.coerceIn(pane.top, maxOf(pane.top, pane.bottom - popupContentSize.height)))
    }
}

@Composable
internal fun rememberWindowFolds(): List<WindowFold> {
    val context = LocalContext.current
    val activity = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
    }
    val flow = remember(activity) {
        if (activity == null) flowOf(emptyList()) else WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).map { info ->
            info.displayFeatures.filterIsInstance<FoldingFeature>().map { fold ->
                WindowFold(IntRect(fold.bounds.left, fold.bounds.top, fold.bounds.right, fold.bounds.bottom),
                    if (fold.orientation == FoldingFeature.Orientation.VERTICAL) FoldAxis.VERTICAL else FoldAxis.HORIZONTAL,
                    fold.isSeparating, fold.occlusionType == FoldingFeature.OcclusionType.FULL)
            }
        }
    }
    return flow.collectAsStateWithLifecycle(emptyList()).value
}

/** WindowManager bounds are in window pixels, including the area consumed by system insets. */
internal fun calculatePanes(width: Int, height: Int, origin: IntOffset, folds: List<WindowFold>,
    clearance: Int, minimumWidth: Int, minimumHeight: Int): PanePlan {
    val full = IntRect(0, 0, width.coerceAtLeast(0), height.coerceAtLeast(0))
    var regions = listOf(full)
    folds.filter { it.separating || it.occluding }.forEach { feature ->
        val b = feature.bounds
        val bounds = IntRect(b.left - origin.x, b.top - origin.y, b.right - origin.x, b.bottom - origin.y)
        regions = regions.flatMap { region ->
            if (feature.axis == FoldAxis.VERTICAL && bounds.left < region.right && bounds.right > region.left &&
                bounds.bottom > region.top && bounds.top < region.bottom) {
                val before = (bounds.left - clearance).coerceIn(region.left, region.right)
                val after = (bounds.right + clearance).coerceIn(region.left, region.right)
                listOf(IntRect(region.left, region.top, before, region.bottom), IntRect(after, region.top, region.right, region.bottom))
            } else if (feature.axis == FoldAxis.HORIZONTAL && bounds.top < region.bottom && bounds.bottom > region.top &&
                bounds.right > region.left && bounds.left < region.right) {
                val before = (bounds.top - clearance).coerceIn(region.top, region.bottom)
                val after = (bounds.bottom + clearance).coerceIn(region.top, region.bottom)
                listOf(IntRect(region.left, region.top, region.right, before), IntRect(region.left, after, region.right, region.bottom))
            } else listOf(region)
        }.filter { it.width > 0 && it.height > 0 }
    }
    val usable = regions.filter { it.width >= minimumWidth && it.height >= minimumHeight }
    if (usable.size >= 2) {
        val selected = usable.sortedByDescending { it.width.toLong() * it.height }.take(2).sortedWith(compareBy({ it.top }, { it.left }))
        return PanePlan(selected[0], selected[1], true)
    }
    val largest = regions.maxByOrNull { it.width.toLong() * it.height } ?: IntRect(0, 0, 0, 0)
    return PanePlan(largest, largest, false)
}

/** The content composition stays at the same slot while only its measured pane bounds change. */
@Composable
internal fun FoldAppShell(folds: List<WindowFold>, modifier: Modifier = Modifier,
    navigation: @Composable (Boolean) -> Unit, content: @Composable () -> Unit) {
    var origin by remember { mutableStateOf(IntOffset.Zero) }
    var panePlan by remember { mutableStateOf<PanePlan?>(null) }
    val density = LocalDensity.current
    val clearance = with(density) { 8.dp.roundToPx() }
    val minWidth = with(density) { (if (fontScale > 1.3f) 320 else 280).dp.roundToPx() }
    val minHeight = with(density) { (if (fontScale > 1.3f) 360 else 240).dp.roundToPx() }
    val railWidth = with(density) { 96.dp.roundToPx() }
    val railThreshold = with(density) { 600.dp.roundToPx() }
    Layout(modifier = modifier.onGloballyPositioned {
        val position = it.positionInWindow()
        origin = IntOffset(position.x.roundToInt(), position.y.roundToInt())
    }, content = {
        Box {
            CompositionLocalProvider(LocalPanePlan provides panePlan, content = content)
        }
        Box { navigation(panePlan?.rail == true) }
    }) { children, constraints ->
        val width = constraints.maxWidth; val height = constraints.maxHeight
        val raw = calculatePanes(width, height, origin, folds, clearance, minWidth, minHeight)
        val rail = !raw.split && raw.primary.width >= railThreshold
        val navPane = if (raw.split) raw.controls else raw.primary
        val nav = children[1].measure(if (rail) Constraints.fixed(railWidth.coerceAtMost(navPane.width), navPane.height)
            else Constraints(minWidth = navPane.width, maxWidth = navPane.width, maxHeight = navPane.height))
        val primary = if (rail) raw.primary.copy(left = (raw.primary.left + nav.width).coerceAtMost(raw.primary.right))
            else if (!raw.split) raw.primary.copy(bottom = (raw.primary.bottom - nav.height).coerceAtLeast(raw.primary.top)) else raw.primary
        val controls = if (raw.split) raw.controls.copy(bottom = (raw.controls.bottom - nav.height).coerceAtLeast(raw.controls.top)) else primary
        val next = PanePlan(primary, controls, raw.split, rail, origin, folds.any { it.separating || it.occluding })
        if (panePlan != next) panePlan = next
        val body = children[0].measure(Constraints.fixed(width, height))
        layout(width, height) {
            body.place(0, 0)
            nav.place(navPane.left, if (rail) navPane.top else navPane.bottom - nav.height)
        }
    }
}

/** Dialog windows have their own insets; translate the selected activity pane into that window. */
@Composable
internal fun FoldAlertDialog(onDismissRequest: () -> Unit, title: @Composable () -> Unit,
    text: @Composable () -> Unit, confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit = {}) {
    val plan = LocalPanePlan.current
    if (plan?.avoidsFold != true) {
        AlertDialog(onDismissRequest, confirmButton, dismissButton = dismissButton, title = title, text = text,
            shape = MaterialTheme.shapes.extraLarge)
        return
    }
    var origin by remember { mutableStateOf(IntOffset.Zero) }
    val density = LocalDensity.current
    val inset = with(density) { 16.dp.roundToPx() }
    val maxWidth = with(density) { 560.dp.roundToPx() }
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Layout(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding().onGloballyPositioned {
            val p = it.positionInWindow(); origin = IntOffset(p.x.roundToInt(), p.y.roundToInt())
        }.pointerInput(onDismissRequest) { detectTapGestures { onDismissRequest() } }, content = {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = AlertDialogDefaults.containerColor,
                tonalElevation = AlertDialogDefaults.TonalElevation,
                modifier = Modifier.pointerInput(Unit) { detectTapGestures { } }.semantics { paneTitle = "对话框" }) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
                    ProvideTextStyle(MaterialTheme.typography.headlineSmall) { title() }
                    Spacer(Modifier.height(16.dp))
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
                    Spacer(Modifier.height(24.dp))
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        dismissButton(); confirmButton()
                    }
                }
            }
        }) { children, constraints ->
            val p = plan.primary
            val dx = plan.windowOrigin.x - origin.x; val dy = plan.windowOrigin.y - origin.y
            val left = (p.left + dx).coerceIn(0, constraints.maxWidth)
            val right = (p.right + dx).coerceIn(left, constraints.maxWidth)
            val top = (p.top + dy).coerceIn(0, constraints.maxHeight)
            val bottom = (p.bottom + dy).coerceIn(top, constraints.maxHeight)
            val availableWidth = (right - left - inset * 2).coerceAtLeast(0)
            val availableHeight = (bottom - top - inset * 2).coerceAtLeast(0)
            val dialog = children.single().measure(Constraints(maxWidth = minOf(maxWidth, availableWidth), maxHeight = availableHeight))
            layout(constraints.maxWidth, constraints.maxHeight) {
                dialog.place(left + (right - left - dialog.width) / 2, top + (bottom - top - dialog.height) / 2)
            }
        }
    }
}

@Composable
internal fun FoldSafePage(content: @Composable () -> Unit) {
    val plan = LocalPanePlan.current
    Layout(content = { Box(Modifier.clipToBounds()) { content() } }, modifier = Modifier.fillMaxSize()) { children, constraints ->
        val pane = plan?.primary ?: IntRect(0, 0, constraints.maxWidth, constraints.maxHeight)
        val body = children.single().measure(Constraints.fixed(pane.width, pane.height))
        layout(constraints.maxWidth, constraints.maxHeight) { body.place(pane.left, pane.top) }
    }
}

@Composable
internal fun FoldLiveLayout(body: @Composable () -> Unit, controls: @Composable (Boolean) -> Unit) {
    val plan = LocalPanePlan.current
    val density = LocalDensity.current
    var companion by remember { mutableStateOf(false) }
    Layout(content = {
        Box(Modifier.clipToBounds()) { body() }
        Box(Modifier.clipToBounds()) { controls(companion) }
    }, modifier = Modifier.fillMaxSize()) { children, constraints ->
        // An unfolded screen gives all remaining width to the subtitle list.
        // Only physical fold separation creates a translation companion pane.
        val panes = plan ?: PanePlan(IntRect(0, 0, constraints.maxWidth, constraints.maxHeight),
            IntRect(0, 0, constraints.maxWidth, constraints.maxHeight), false)
        val primary = panes.primary
        val secondary = panes.controls
        val next = panes.split && primary.top == secondary.top && secondary.height >= with(density) { 400.dp.roundToPx() }
        if (companion != next) companion = next
        val dock = children[1].measure(Constraints(minWidth = secondary.width, maxWidth = secondary.width, maxHeight = secondary.height))
        val bodyHeight = if (panes.split) primary.height else (primary.height - dock.height).coerceAtLeast(0)
        val captions = children[0].measure(Constraints.fixed(primary.width, bodyHeight))
        layout(constraints.maxWidth, constraints.maxHeight) {
            captions.place(primary.left, primary.top)
            dock.place(secondary.left, secondary.bottom - dock.height)
        }
    }
}
