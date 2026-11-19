package com.sweepscan.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.sweepscan.R
import com.sweepscan.request.RequestParser
import com.sweepscan.request.Requests
import com.sweepscan.request.ShareRequestActivity
import com.sweepscan.request.VoiceRequestActivity
import com.sweepscan.ui.theme.EmptyNote
import com.sweepscan.ui.theme.Kpi
import com.sweepscan.ui.theme.LocalStatusColors
import com.sweepscan.ui.theme.Palette
import com.sweepscan.ui.theme.SectionLabel
import com.sweepscan.ui.theme.Space
import com.sweepscan.ui.theme.StatusPill
import com.sweepscan.ui.theme.SweepCard
import com.sweepscan.ui.theme.SweepScreen
import com.sweepscan.ui.theme.SweepTheme
import com.sweepscan.ui.theme.TileShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * "Talepler": the shop floor's requests as a queue, from the WhatsApp group and from shop-floor
 * phones. Today's numbers on top, waiting requests first (coloured by how long they wait, as a
 * kitchen display does) with Gönderdim / Yok / Bakıyorum, then the ones done. A request told in
 * person can be added by hand.
 */
class RequestsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SweepTheme { RequestsScreen(onBack = ::finish) } }
    }
}

@Composable
private fun RequestsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var all by remember { mutableStateOf(Requests.recent(context)) }
    var adding by remember { mutableStateOf(false) }
    fun refresh() { all = Requests.recent(context) }

    // Shop-floor phones add requests while this screen is open; ages tick. Only while it is shown.
    val scope = rememberCoroutineScope()
    // Anything that changes the queue (a new request, a status from the notification or a shop
    // phone) shows at once.
    val version by Requests.changes.collectAsState()
    LaunchedEffect(version) { refresh() }
    val presenting = com.sweepscan.request.Presentation.isOn(context)
    LifecycleResumeEffect(Unit) {
        refresh()
        val job = scope.launch { while (true) { delay(5000); refresh() } }
        onPauseOrDispose { job.cancel() }
    }

    val today = all.filter { it.time >= startOfToday() }
    val open = all.filter { it.status.open }
    val done = all.filter { !it.status.open }

    SweepScreen(
        title = stringResource(R.string.requests_title),
        onBack = onBack,
        backDescription = stringResource(R.string.back),
        actions = {
            TextButton(onClick = { adding = true }) {
                Icon(painterResource(R.drawable.ic_add), null)
                Spacer(Modifier.width(Space.xs))
                Text(stringResource(R.string.req_add))
            }
        },
    ) {
        when {
            presenting -> item { RehearsalChat(com.sweepscan.request.Presentation.chat(context).takeLast(6)) }
            !ShareRequestActivity.watcherAvailable(context) -> item { Note(stringResource(R.string.share_how)) }
            !Requests.enabled(context) || !Requests.accessGranted(context) -> item {
                SweepCard(onClick = { context.startActivity(Intent(context, SettingsActivity::class.java)) }) {
                    Column(Modifier.padding(Space.l)) {
                        Text(stringResource(R.string.requests_off_title), style = MaterialTheme.typography.titleMedium, color = Palette.Amber)
                        Text(stringResource(R.string.requests_off_body), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        item { TodayNumbers(today) }
        if (all.isEmpty()) {
            item { EmptyNote(stringResource(R.string.requests_empty)) }
            return@SweepScreen
        }
        if (open.isNotEmpty()) item { SectionLabel(stringResource(R.string.requests_waiting, open.size)) }
        val placed = open.filter { it.code != null }
        if (placed.size > 1) item {
            Button(onClick = { collect(context, placed) }, modifier = Modifier.fillMaxWidth()) {
                Icon(painterResource(R.drawable.ic_route), null)
                Spacer(Modifier.width(Space.s))
                Text(stringResource(R.string.requests_collect))
            }
        }
        items(open, key = { it.id }) { r -> RequestItem(r, waiting = true, onChanged = ::refresh) }
        if (done.isNotEmpty()) item { SectionLabel(stringResource(R.string.requests_done)) }
        items(done.take(30), key = { it.id }) { r -> RequestItem(r, waiting = false, onChanged = ::refresh) }
    }

    if (adding) {
        TextDialog(
            title = stringResource(R.string.req_add),
            initial = "",
            keyboard = KeyboardType.Text,
            onDone = { text ->
                adding = false
                if (RequestParser.parse(text).isEmpty()) Toast.makeText(context, R.string.req_add_no_code, Toast.LENGTH_LONG).show()
                else Requests.addManual(context, text) { refresh() }
            },
            onDismiss = { adding = false },
        )
    }
}

@Composable
private fun TodayNumbers(today: List<Requests.Request>) {
    val sent = today.count { it.status == Requests.Status.SENT }
    val missing = today.count { it.status == Requests.Status.MISSING }
    val waiting = today.count { it.status.open }
    val times = today.filter { it.doneAt > 0 }.map { (it.doneAt - it.time) / 60000.0 }
    val avg = if (times.isEmpty()) "–" else Math.round(times.average()).toString()
    val colors = LocalStatusColors.current
    Column(Modifier.padding(top = Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Kpi(today.size.toString(), stringResource(R.string.kpi_today), Modifier.weight(1f))
            Kpi(sent.toString(), stringResource(R.string.kpi_sent), Modifier.weight(1f), colors.sent)
            Kpi(missing.toString(), stringResource(R.string.kpi_missing), Modifier.weight(1f), if (missing > 0) colors.missing else MaterialTheme.colorScheme.onSurface)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Kpi(waiting.toString(), stringResource(R.string.kpi_waiting), Modifier.weight(1f), if (waiting > 0) colors.new else MaterialTheme.colorScheme.onSurface)
            Kpi(avg, stringResource(R.string.kpi_avg), Modifier.weight(1f))
            Kpi(today.count { it.source == Requests.SOURCE_SHOP }.toString(), stringResource(R.string.kpi_shop), Modifier.weight(1f))
        }
    }
}

@Composable
private fun RequestItem(r: Requests.Request, waiting: Boolean, onChanged: () -> Unit) {
    val context = LocalContext.current
    // Waiting requests with a code: swipe right = sent, swipe left = not there (the buttons stay).
    if (!waiting || (r.voice && r.digits == null)) {
        RequestCard(r, waiting, onChanged)
        return
    }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val colors = LocalStatusColors.current
    val state = androidx.compose.material3.rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        when (v) {
            androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd -> {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
                mark(context, r, Requests.Status.SENT); onChanged(); true
            }
            androidx.compose.material3.SwipeToDismissBoxValue.EndToStart -> {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Reject)
                mark(context, r, Requests.Status.MISSING); onChanged(); true
            }
            else -> false
        }
    })
    androidx.compose.material3.SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val toSent = state.dismissDirection == androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd
            val toMissing = state.dismissDirection == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(TileShape)
                    .background(
                        when {
                            toSent -> colors.sent.copy(alpha = 0.35f)
                            toMissing -> colors.missing.copy(alpha = 0.35f)
                            else -> Color.Transparent
                        },
                    )
                    .padding(horizontal = Space.xl),
                contentAlignment = if (toMissing) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                if (toSent) Text(stringResource(R.string.req_sent), style = MaterialTheme.typography.titleMedium, color = colors.sent)
                if (toMissing) Text(stringResource(R.string.req_missing), style = MaterialTheme.typography.titleMedium, color = colors.missing)
            }
        },
    ) { RequestCard(r, waiting, onChanged) }
}

@Composable
private fun RequestCard(r: Requests.Request, waiting: Boolean, onChanged: () -> Unit) {
    val context = LocalContext.current
    val colors = LocalStatusColors.current
    val accent = when (r.status) {
        Requests.Status.SENT -> colors.sent
        Requests.Status.MISSING -> colors.missing
        else -> ageColor(r.time)
    }
    val title = if (r.voice && r.digits == null) stringResource(R.string.req_voice_short)
    else listOfNotNull(r.model ?: r.digits, r.size?.let { stringResource(R.string.req_size, it) }).joinToString(" · ")
    val where = r.answer ?: if (r.digits != null) stringResource(R.string.req_not_found, "").trim() else null
    val from = listOfNotNull(
        r.sender.ifBlank { null },
        stringResource(
            when (com.sweepscan.request.Intake.Source.of(r.source)) {
                com.sweepscan.request.Intake.Source.SHOP -> R.string.req_source_shop
                com.sweepscan.request.Intake.Source.MANUAL -> R.string.req_source_manual
                com.sweepscan.request.Intake.Source.REHEARSAL -> R.string.req_source_rehearsal
                else -> R.string.req_source_whatsapp
            },
        ),
    ).joinToString(" · ")

    SweepCard(
        accent = accent,
        onClick = { open(context, r) },
        onLongClick = if (!r.status.open) {
            {
                // Marked by mistake: back on the waiting list.
                Requests.setStatus(context, r.id, Requests.Status.NEW)
                Toast.makeText(context, R.string.req_undone, Toast.LENGTH_SHORT).show()
                onChanged()
            }
        } else null,
    ) {
        Column(Modifier.padding(horizontal = Space.l, vertical = Space.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (where != null) Text(where, style = MaterialTheme.typography.bodyMedium, color = if (r.code != null) Palette.Amber else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${Requests.clock(r.time)} · $from", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(Space.s))
                when (r.status) {
                    Requests.Status.NEW -> StatusPill(age(context, r.time), accent)
                    Requests.Status.LOOKING -> StatusPill(stringResource(R.string.shop_status_looking), colors.looking)
                    Requests.Status.SENT -> StatusPill(stringResource(R.string.req_status_sent, Requests.clock(r.doneAt)).replace("\n", " "), colors.sent)
                    Requests.Status.MISSING -> StatusPill(stringResource(R.string.req_status_missing, Requests.clock(r.doneAt)).replace("\n", " "), colors.missing)
                }
            }
            if (waiting) {
                Spacer(Modifier.height(Space.s))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    val pad = ButtonDefaults.TextButtonContentPadding
                    if (r.voice && r.digits == null) {
                        Button(onClick = { open(context, r) }) { ButtonIcon(R.drawable.ic_mic); Text(stringResource(R.string.req_say)) }
                    } else {
                        Button(onClick = { mark(context, r, Requests.Status.SENT); onChanged() }, Modifier.weight(1.3f), contentPadding = pad) {
                            Text(stringResource(R.string.req_sent), maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(onClick = { mark(context, r, Requests.Status.MISSING); onChanged() }, Modifier.weight(0.8f), contentPadding = pad) {
                            Text(stringResource(R.string.req_missing), maxLines = 1, softWrap = false)
                        }
                        if (r.status == Requests.Status.NEW) {
                            FilledTonalButton(onClick = { mark(context, r, Requests.Status.LOOKING); onChanged() }, Modifier.weight(1.2f), contentPadding = pad) {
                                Text(stringResource(R.string.req_looking), maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ButtonIcon(icon: Int) {
    Icon(painterResource(icon), null, Modifier.padding(end = Space.xs).width(18.dp))
}

@Composable
private fun Note(text: String) {
    Text(text, Modifier.padding(vertical = Space.s), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun mark(c: Context, r: Requests.Request, status: Requests.Status) {
    val replied = Requests.setStatus(c, r.id, status)
    if (Requests.replies(c) && r.source != Requests.SOURCE_SHOP && status != Requests.Status.NEW) {
        if (replied) {
            Toast.makeText(c, R.string.reply_done, Toast.LENGTH_SHORT).show()
        } else {
            // WhatsApp's notification is gone: copy the answer and open the chat to paste it.
            Requests.copyReply(c, Requests.replyText(c, r, status))
            Toast.makeText(c, R.string.reply_copied, Toast.LENGTH_LONG).show()
            Requests.openWhatsApp(c)
        }
    }
}

private fun collect(c: Context, open: List<Requests.Request>) {
    val app = c.applicationContext as com.sweepscan.SweepScanApp
    for (r in open) {
        val code = r.code?.let { com.sweepscan.location.LocationCode.parseText(it) } ?: continue
        val label = listOfNotNull(r.model ?: r.digits, r.size).joinToString(" ")
        val old = app.pickList.firstOrNull { it.code == code }
        app.pickList.removeAll { it.code == code }
        app.pickList += com.sweepscan.location.PickItem(code, listOfNotNull(old?.label, label).distinct().joinToString(", "), old?.barcodes.orEmpty())
    }
    c.startActivity(Intent(c, LocationActivity::class.java).putExtra(LocationActivity.EXTRA_ACTION, LocationActivity.ACTION_PICK))
}

private fun open(c: Context, r: Requests.Request) {
    if (r.digits == null) {
        c.startActivity(Intent(c, VoiceRequestActivity::class.java).putExtra(VoiceRequestActivity.EXTRA_REQUEST, r.id))
    } else {
        c.startActivity(
            Intent(c, LocationActivity::class.java).putExtra(LocationActivity.EXTRA_QUERY, r.digits).putExtra(LocationActivity.EXTRA_REQUEST, r.id),
        )
    }
}

/** "4 dk" since the request. */
private fun age(c: Context, time: Long): String {
    val min = ((System.currentTimeMillis() - time) / 60000).toInt()
    return if (min < 60) c.getString(R.string.req_age_min, min) else c.getString(R.string.req_age_hour, min / 60)
}

/** Green under 5 minutes, yellow under 15, red after (as kitchen displays do). */
private fun ageColor(time: Long): Color {
    val min = (System.currentTimeMillis() - time) / 60000
    return when {
        min < 5 -> Color(0xFF7EE2B8)
        min < 15 -> Color(0xFFFFD54F)
        else -> Color(0xFFFF6B6B)
    }
}

private fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/**
 * Presentation mode: the rehearsal group, standing in for the WhatsApp group. Its messages are the
 * scripted requests; the app's answers ("✅ Gönderdim: …") arrive here through the same reply path
 * WhatsApp's notification uses.
 */
@Composable
private fun RehearsalChat(lines: List<com.sweepscan.request.Presentation.Line>) {
    SweepCard {
        Column(Modifier.padding(Space.l)) {
            Text(stringResource(R.string.rehearsal_title), style = MaterialTheme.typography.titleSmall, color = Palette.Amber)
            Text(stringResource(R.string.rehearsal_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (l in lines) {
                val mine = l.sender == com.sweepscan.request.Presentation.ME
                Row(Modifier.fillMaxWidth().padding(top = Space.s), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                    Column(
                        Modifier
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                            .background(if (mine) Palette.Green.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = Space.m, vertical = Space.s),
                    ) {
                        if (!mine) Text(l.sender, style = MaterialTheme.typography.labelMedium, color = Palette.Amber)
                        Text(l.text, style = MaterialTheme.typography.bodyMedium)
                        Text(Requests.clock(l.time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

