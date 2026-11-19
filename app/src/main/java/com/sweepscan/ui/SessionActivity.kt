package com.sweepscan.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sweepscan.R
import com.sweepscan.SweepScanApp
import com.sweepscan.hid.HidKeyboard
import com.sweepscan.hid.PauseReason
import com.sweepscan.location.BarcodeModel
import com.sweepscan.session.ScanSession
import com.sweepscan.tracking.BarcodeRules
import com.sweepscan.ui.theme.IconAction
import com.sweepscan.ui.theme.LocalStatusColors
import com.sweepscan.ui.theme.Palette
import com.sweepscan.ui.theme.SectionLabel
import com.sweepscan.ui.theme.Space
import com.sweepscan.ui.theme.SweepCard
import com.sweepscan.ui.theme.SweepTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/**
 * Review and send ("count first, then send", S6). The count is the hero; every barcode is a card
 * with its model, colour and size when the map knows them (grouped by model), its send state and a
 * − / + stepper; warnings for labels seen but not read; one button types everything into DİA after
 * a countdown, and a progress notification (a Live Update on Android 16) follows the typing.
 * Sending can be paused; an uncertain line stops it until the user checks DİA.
 */
class SessionActivity : AppCompatActivity() {

    private val app get() = application as SweepScanApp

    /** Bumped on every session or keyboard change: the screen reads everything again. */
    private val version = mutableIntStateOf(0)

    /** Size of the current send run, for the progress line and the notification. */
    private var runTotal = 0
    private var runSentAtStart = 0

    private val sessionListener: () -> Unit = { refresh() }
    private val keyboardListener = object : HidKeyboard.Listener {
        override fun onStatus(status: HidKeyboard.Status, hostName: String?) = refresh()
        override fun onSent(entryId: Long) = refresh()
        override fun onPauseChanged(reason: PauseReason?) = refresh()
        override fun onUncertain(entryId: Long, text: String, typed: Int, enterSent: Boolean) =
            UncertainDialog.show(this@SessionActivity, entryId, typed, enterSent)
        override fun onNotice(notice: HidKeyboard.Notice) = PcControls.showNotice(this@SessionActivity, notice)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Losing the foreground drops the keyboard registration mid-send.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { SweepTheme { ReviewScreen() } }
        app.logger.logEvent("review_open", mapOf("boxes" to app.session.total()))
    }

    override fun onStart() {
        super.onStart()
        app.session.addListener(sessionListener)
        app.keyboard.addListener(keyboardListener)
        app.keyboard.ensureRegistered()
        app.session.uncertainEntries().firstOrNull()?.let { UncertainDialog.show(this, it.id) }
        refresh()
    }

    override fun onStop() {
        app.session.removeListener(sessionListener)
        app.keyboard.removeListener(keyboardListener)
        // Android drops the keyboard of an app that leaves the screen: typing stops. The
        // notification stays as "paused", and a tap brings the review screen back to go on.
        val k = app.keyboard
        if (k.pending > 0) {
            val done = (app.session.sentCount() - runSentAtStart).coerceAtLeast(0)
            SendNotification.show(this, done, runTotal.coerceAtLeast(done + k.pending), k.pending * k.timing.perBarcodeMs(), paused = true)
        } else {
            SendNotification.cancel(this)
        }
        super.onStop()
    }

    private fun refresh() {
        version.intValue++
        val k = app.keyboard
        val done = (app.session.sentCount() - runSentAtStart).coerceAtLeast(0)
        if (k.pending > 0 || (k.paused != null && runTotal > done)) {
            SendNotification.show(this, done, runTotal.coerceAtLeast(done + k.pending), k.pending * k.timing.perBarcodeMs(), k.paused != null)
        } else {
            SendNotification.cancel(this)
        }
    }

    /** What the screen shows, read from the session and the keyboard. */
    private data class Snapshot(
        val total: Int,
        val variants: Int,
        val sent: Int,
        val queued: Int,
        val uncertain: Int,
        val unread: Int,
        val uncounted: Int,
        val waiting: Int,
        val lines: List<LineState>,
        val pcLine: String,
        val pcConnected: Boolean,
        val sending: Boolean,
        val paused: Boolean,
        val pauseVisible: Boolean,
    )

    private data class LineState(val line: ScanSession.Line, val queued: Int)

    private fun snapshot(): Snapshot {
        val session = app.session
        val k = app.keyboard
        val unsent = session.unsent()
        val queued = unsent.count { k.isQueued(it.id) }
        val pc = when {
            k.paused == PauseReason.UNCERTAIN -> getString(R.string.sending_stopped_uncertain, session.uncertainCount())
            k.paused == PauseReason.LINK -> getString(R.string.sending_stopped_link, k.pending)
            k.pending > 0 -> {
                val done = (session.sentCount() - runSentAtStart).coerceAtLeast(0)
                val minutes = ceil(k.pending * k.timing.perBarcodeMs() / 60_000.0).toInt()
                getString(R.string.sending_progress, done, runTotal.coerceAtLeast(done), minutes)
            }
            else -> PcControls.statusLine(this)
        }
        return Snapshot(
            total = session.total(),
            variants = session.variants(),
            sent = session.sentCount(),
            queued = queued,
            uncertain = session.uncertainCount(),
            unread = session.unreadable(),
            uncounted = session.uncounted(),
            waiting = unsent.size - queued,
            lines = session.lines().map { l -> LineState(l, session.idsOf(l.value).count { k.isQueued(it) }) },
            pcLine = pc,
            pcConnected = k.status == HidKeyboard.Status.CONNECTED,
            sending = k.pending > 0,
            paused = k.paused != null,
            pauseVisible = k.pending > 0 || k.paused != null,
        )
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun ReviewScreen() {
        val tick by version
        val s = remember(tick) { snapshot() }
        // Model, colour and size of every barcode the map knows (list import or label text).
        var models by remember { mutableStateOf<Map<String, BarcodeModel>>(emptyMap()) }
        val values = s.lines.map { it.line.value }
        LaunchedEffect(values.toSet()) {
            app.locations.query({ dao -> values.mapNotNull { v -> dao.model(v)?.let { v to it } }.toMap() }) { models = it }
        }
        var confirm by remember { mutableStateOf(false) }
        var countdown by remember { mutableStateOf<List<ScanSession.Entry>?>(null) }
        var adding by remember { mutableStateOf(false) }
        var clearing by remember { mutableStateOf(false) }

        // A Surface, not a bare background: it sets the content colour (light text) for the screen.
        Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }, color = MaterialTheme.colorScheme.background) { Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                TopBar(onMenu = { id ->
                    when (id) {
                        0 -> adding = true
                        1 -> shareCsv()
                        2 -> showPcMenu()
                        3 -> clearing = true
                    }
                })
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(start = Space.l, end = Space.l, bottom = Space.l),
                    verticalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    item { Hero(s) }
                    if (s.unread + s.uncounted > 0) item { Warning(stringResource(R.string.review_suspects, s.unread, s.uncounted)) }
                    item { PcCapsule(s) }
                    if (s.lines.isEmpty()) item {
                        Text(
                            stringResource(R.string.review_empty),
                            Modifier.fillMaxWidth().padding(vertical = Space.xl),
                            style = MaterialTheme.typography.bodyLarge,
                            color = Palette.TextMuted,
                            textAlign = TextAlign.Center,
                        )
                    }
                    for (group in group(s.lines, models)) {
                        item(key = "h:" + group.key) {
                            val title = group.model?.let { m -> listOfNotNull(m.model, m.color).joinToString(" · ") }
                                ?: stringResource(R.string.review_unknown_model)
                            val boxes = group.lines.sumOf { it.line.count }
                            SectionLabel(androidx.compose.ui.res.pluralStringResource(R.plurals.review_group, boxes, title, boxes))
                        }
                        items(group.lines, key = { "l:" + it.line.value }) { l ->
                            val index = s.lines.indexOf(l)
                            LineCard(l, models[l.line.value], index)
                        }
                    }
                }
                BottomBar(s, onSend = { if (canSend()) confirm = true }, onPause = { togglePause() })
            }

            if (confirm) {
                val n = s.waiting
                val minutes = ceil(n * app.keyboard.timing.perBarcodeMs() / 60_000.0).toInt()
                AlertDialog(
                    onDismissRequest = { confirm = false },
                    icon = { Icon(painterResource(R.drawable.ic_pc), null) },
                    title = { Text(stringResource(R.string.send_confirm_title)) },
                    text = { Text(stringResource(R.string.send_confirm_body, n, minutes)) },
                    confirmButton = {
                        Button(onClick = {
                            confirm = false
                            countdown = app.session.unsent().filter { !app.keyboard.isQueued(it.id) }.takeIf { it.isNotEmpty() }
                        }, Modifier.testTag("confirm_send")) { Text(stringResource(R.string.send_confirm_title)) }
                    },
                    dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(android.R.string.cancel)) } },
                )
            }
            if (adding) AddDialog(onDone = { value -> adding = false; value?.let { addOne(it) } })
            if (clearing) {
                AlertDialog(
                    onDismissRequest = { clearing = false },
                    title = { Text(stringResource(R.string.new_session)) },
                    text = { Text(stringResource(R.string.new_session_confirm)) },
                    confirmButton = {
                        Button(
                            onClick = {
                                clearing = false
                                app.logger.logEvent("new_session", mapOf("boxes" to app.session.total()))
                                app.session.clear()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Palette.Red, contentColor = Palette.OnBrand),
                        ) { Text(stringResource(R.string.new_session)) }
                    },
                    dismissButton = { TextButton(onClick = { clearing = false }) { Text(stringResource(android.R.string.cancel)) } },
                )
            }
            countdown?.let { entries ->
                Countdown(onCancel = { countdown = null }, onDone = { countdown = null; send(entries) })
            }
        } }
    }

    @Composable
    private fun TopBar(onMenu: (Int) -> Unit) {
        var open by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = Space.xs, end = Space.xs, top = Space.s, bottom = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { finish() }, Modifier.testTag("back")) {
                Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back_to_scan), tint = MaterialTheme.colorScheme.onSurface)
            }
            Text(stringResource(R.string.review_title), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            Box {
                Box(Modifier.testTag("menu")) { IconAction(R.drawable.ic_more, stringResource(R.string.more)) { open = true } }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    listOf(R.string.add_manual, R.string.share_csv, R.string.pc_settings, R.string.new_session).forEachIndexed { i, label ->
                        DropdownMenuItem(
                            text = { Text(stringResource(label)) },
                            onClick = { open = false; onMenu(i) },
                            modifier = Modifier.testTag("menu_$i"),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun Hero(s: Snapshot) {
        val progress by animateFloatAsState(if (s.total == 0) 0f else s.sent.toFloat() / s.total, label = "sent")
        Surface(
            Modifier.fillMaxWidth().testTag("summary"),
            color = Palette.BrandContainer,
            shape = MaterialTheme.shapes.large,
        ) {
            Column(Modifier.padding(horizontal = Space.xl, vertical = Space.l)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(s.total.toString(), style = MaterialTheme.typography.headlineMedium.copy(fontSize = 56.sp, lineHeight = 60.sp), color = Palette.Text)
                    Spacer(Modifier.width(Space.s))
                    Text(
                        stringResource(R.string.review_hero_boxes, s.variants),
                        Modifier.padding(bottom = 10.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = Palette.TextSoft,
                    )
                }
                if (s.sent > 0 || s.sending) {
                    Spacer(Modifier.height(Space.s))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                        color = Palette.Brand,
                        trackColor = Color(0x33FFFFFF),
                        drawStopIndicator = {},
                    )
                }
                Spacer(Modifier.height(Space.s))
                Text(
                    stringResource(R.string.review_status, s.sent, s.queued, s.uncertain),
                    Modifier.testTag("summary_status"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextSoft,
                )
            }
        }
    }

    @Composable
    private fun Warning(text: String) {
        Surface(Modifier.fillMaxWidth(), color = Color(0x33FFB300), shape = MaterialTheme.shapes.medium) {
            Text(text, Modifier.padding(horizontal = Space.l, vertical = Space.m), style = MaterialTheme.typography.bodyMedium, color = Palette.Amber)
        }
    }

    @Composable
    private fun PcCapsule(s: Snapshot) {
        val dot = when {
            s.paused -> Palette.Amber
            s.pcConnected -> Palette.Green
            else -> Palette.TextMuted
        }
        Row(
            Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { showPcMenu() }
                .testTag("progress")
                .padding(horizontal = Space.l, vertical = Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(Space.s))
            Icon(painterResource(R.drawable.ic_pc), null, Modifier.size(18.dp), tint = Palette.TextSoft)
            Spacer(Modifier.width(Space.s))
            Text(s.pcLine, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
        }
    }

    @Composable
    private fun LineCard(l: LineState, model: BarcodeModel?, index: Int) {
        val colors = LocalStatusColors.current
        val haptic = LocalHapticFeedback.current
        val line = l.line
        val (status, color) = when {
            line.uncertain > 0 -> stringResource(R.string.line_uncertain, line.uncertain) to colors.missing
            line.sent >= line.count -> stringResource(R.string.line_sent, line.sent, line.count) to colors.sent
            l.queued > 0 -> stringResource(R.string.line_queued, line.sent, line.count) to colors.new
            line.sent > 0 -> stringResource(R.string.line_sent, line.sent, line.count) to colors.new
            else -> stringResource(R.string.line_not_sent) to Palette.TextMuted
        }
        val accent = when {
            line.uncertain > 0 -> colors.missing
            line.sent >= line.count -> colors.sent
            l.queued > 0 || line.sent > 0 -> colors.new
            else -> null
        }
        SweepCard(
            onClick = if (line.uncertain > 0) ({ resolveUncertain(line.value) }) else null,
            accent = accent,
        ) {
            Row(Modifier.padding(start = Space.l, end = Space.s, top = Space.m, bottom = Space.m), verticalAlignment = Alignment.CenterVertically) {
                if (model?.size != null) {
                    Box(
                        Modifier.size(48.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) { Text(model.size, style = MaterialTheme.typography.titleLarge) }
                    Spacer(Modifier.width(Space.m))
                }
                Column(Modifier.weight(1f)) {
                    Text(emphasised(line.value), style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace), maxLines = 1)
                    Text(status, style = MaterialTheme.typography.bodySmall, color = color)
                }
                FilledTonalIconButton(
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); removeOne(line.value) },
                    Modifier.size(48.dp).testTag("minus_$index"),
                ) { Text("−", style = MaterialTheme.typography.titleLarge) }
                Text(
                    line.count.toString(),
                    Modifier.widthIn(min = 44.dp),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                FilledTonalIconButton(
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); addOne(line.value) },
                    Modifier.size(48.dp).testTag("plus_$index"),
                ) { Icon(painterResource(R.drawable.ic_add), stringResource(R.string.add_manual)) }
            }
        }
    }

    @Composable
    private fun BottomBar(s: Snapshot, onSend: () -> Unit, onPause: () -> Unit) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = androidx.compose.foundation.shape.CornerSize(0.dp), bottomEnd = androidx.compose.foundation.shape.CornerSize(0.dp))) {
            Row(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = Space.l, vertical = Space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedVisibility(s.pauseVisible, enter = fadeIn(), exit = fadeOut()) {
                    FilledTonalButton(onClick = onPause, Modifier.height(60.dp).padding(end = Space.s).testTag("btn_pause")) {
                        Text(stringResource(if (s.paused) R.string.pc_resume else R.string.pc_pause))
                    }
                }
                Button(
                    onClick = onSend,
                    enabled = s.waiting > 0,
                    modifier = Modifier.weight(1f).height(60.dp).testTag("btn_send"),
                ) {
                    Icon(painterResource(R.drawable.ic_pc), null, Modifier.size(20.dp))
                    Spacer(Modifier.width(Space.s))
                    Text(stringResource(R.string.send_to_dia, s.waiting), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    /**
     * Typing starts after a few seconds, so there is time to click into DİA's barcode field: the
     * phone types into whatever window has focus (16 Nov: five barcodes went into a chat window).
     */
    @Composable
    private fun Countdown(onCancel: () -> Unit, onDone: () -> Unit) {
        var left by remember { mutableIntStateOf(COUNTDOWN_S) }
        val haptic = LocalHapticFeedback.current
        LaunchedEffect(Unit) {
            while (left > 0) {
                delay(1000)
                left--
                haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            }
            onDone()
        }
        val ring by animateFloatAsState(left / COUNTDOWN_S.toFloat(), label = "countdown")
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xF00E1013))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .testTag("countdown"),
            contentAlignment = Alignment.Center,
        ) {
            Column(Modifier.padding(Space.xl), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.send_countdown_title), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(Space.xl))
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { ring },
                        modifier = Modifier.size(180.dp),
                        color = Palette.Brand,
                        strokeWidth = 10.dp,
                        trackColor = Color(0x22FFFFFF),
                    )
                    Text(left.toString(), style = MaterialTheme.typography.headlineMedium.copy(fontSize = 72.sp, lineHeight = 76.sp))
                }
                Spacer(Modifier.height(Space.xl))
                Text(
                    stringResource(R.string.send_countdown_hint),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Palette.TextSoft,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(Space.xl))
                OutlinedButton(onClick = onCancel, Modifier.height(52.dp).testTag("countdown_cancel")) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        }
    }

    @Composable
    private fun AddDialog(onDone: (String?) -> Unit) {
        var text by remember { mutableStateOf("") }
        val valid = BarcodeRules.isAcceptable(text.trim())
        AlertDialog(
            onDismissRequest = { onDone(null) },
            title = { Text(stringResource(R.string.add_manual)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { v -> text = v.filter { it.isDigit() }.take(14) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text(stringResource(R.string.add_manual_hint, BarcodeRules.describe())) },
                    isError = text.isNotEmpty() && !valid,
                    modifier = Modifier.testTag("add_field"),
                )
            },
            confirmButton = { Button(onClick = { onDone(text.trim()) }, enabled = valid) { Text(stringResource(android.R.string.ok)) } },
            dismissButton = { TextButton(onClick = { onDone(null) }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    private class Group(val key: String, val model: BarcodeModel?, val lines: List<LineState>)

    /** Lines grouped by model and colour (sorted by size); barcodes the map does not know come last. */
    private fun group(lines: List<LineState>, models: Map<String, BarcodeModel>): List<Group> {
        if (models.isEmpty()) return if (lines.isEmpty()) emptyList() else listOf(Group("?", null, lines))
        val known = lines.filter { models[it.line.value] != null }
            .groupBy { models.getValue(it.line.value).let { m -> m.modelDigits + "|" + (m.color ?: "") } }
            .map { (key, ls) ->
                Group(key, models.getValue(ls.first().line.value), ls.sortedBy { models[it.line.value]?.size?.toDoubleOrNull() ?: 99.0 })
            }
            .sortedBy { it.model?.modelDigits }
        val unknown = lines.filter { models[it.line.value] == null }
        return if (unknown.isEmpty()) known else known + Group("?", null, unknown)
    }

    private fun emphasised(value: String): AnnotatedString = buildAnnotatedString {
        val cut = (value.length - BarcodeText.TAIL).coerceAtLeast(0)
        withStyle(SpanStyle(color = Palette.TextMuted)) { append(value.substring(0, cut)) }
        withStyle(SpanStyle(color = Palette.Text, fontWeight = FontWeight.Bold, fontSize = 20.sp)) { append(value.substring(cut)) }
    }

    private fun canSend(): Boolean {
        if (app.keyboard.status != HidKeyboard.Status.CONNECTED) {
            // An unconnected queue could fire later into whatever window has focus.
            Toast.makeText(this, R.string.send_needs_pc, Toast.LENGTH_LONG).show()
            showPcMenu()
            return false
        }
        return app.session.unsent().any { !app.keyboard.isQueued(it.id) }
    }

    private fun send(entries: List<ScanSession.Entry>) {
        // A copy of what was sent, to compare against DİA's line count afterwards.
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        app.logger.writeFile("session-$stamp.csv", app.session.toCsv())
        runSentAtStart = app.session.sentCount()
        runTotal = entries.size
        app.logger.logEvent("send_all", mapOf("count" to entries.size))
        entries.forEach { app.keyboard.enqueue(it.id, it.value) }
        refresh()
    }

    private fun togglePause() {
        if (app.keyboard.paused == null) app.keyboard.pause() else PcControls.resumeOrResolve(this)
        refresh()
    }

    private fun showPcMenu() {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (granted) PcControls.showMenu(this) { refresh() }
    }

    private fun addOne(value: String) {
        val entry = app.session.add(value, manual = true)
        app.logger.logEvent("manual", mapOf("id" to entry.id, "value" to value))
        if (app.liveSend) app.keyboard.enqueue(entry.id, entry.value)
    }

    private fun removeOne(value: String) {
        val entry = app.session.removeOne(value) ?: return
        app.logger.logEvent("remove", mapOf("id" to entry.id, "value" to value, "sent" to entry.sent))
        val message = when {
            entry.uncertain -> getString(R.string.undo_uncertain, value)
            app.keyboard.cancel(entry.id) -> null
            entry.sent || app.keyboard.isQueued(entry.id) -> getString(R.string.undo_already_sent, value)
            else -> null
        }
        message?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
    }

    private fun resolveUncertain(value: String) {
        app.session.uncertainEntries().firstOrNull { it.value == value }?.let { UncertainDialog.show(this, it.id) }
    }

    private fun shareCsv() {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "SweepScan")
            .putExtra(Intent.EXTRA_TEXT, app.session.toCsv())
        startActivity(Intent.createChooser(intent, getString(R.string.share_csv)))
    }

    private companion object {
        const val COUNTDOWN_S = 5
    }
}
