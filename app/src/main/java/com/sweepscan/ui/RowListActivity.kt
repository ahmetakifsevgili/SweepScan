package com.sweepscan.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.sweepscan.R
import com.sweepscan.SweepScanApp
import com.sweepscan.location.ColumnScan
import com.sweepscan.location.LocationCode
import com.sweepscan.location.RowFlags
import com.sweepscan.location.SiteLayout
import com.sweepscan.ui.theme.Palette
import com.sweepscan.ui.theme.SectionLabel
import com.sweepscan.ui.theme.Space
import com.sweepscan.ui.theme.StatusPill
import com.sweepscan.ui.theme.SweepCard
import com.sweepscan.ui.theme.SweepScreen
import com.sweepscan.ui.theme.SweepTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Raf tara": which row to sweep. Every row of every depot with how fresh its last sweep is
 * (green this week, orange older, grey never, "kontrol et" when it was found wrong), this week's
 * progress, the suggested next one on top. Tapping a row opens the camera on it (or, when the
 * camera asked, returns the row to it).
 */
class RowListActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val suggest = intent.getStringExtra(EXTRA_SUGGEST)
        val forResult = intent.getBooleanExtra(EXTRA_FOR_RESULT, false)
        setContent {
            SweepTheme {
                RowsScreen(suggest, onBack = ::finish) { layout, code ->
                    if (forResult) {
                        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_CHOSEN, layout.short(code)))
                        finish()
                    } else {
                        startActivity(MainActivity.intent(this, MainActivity.Task.MAP).putExtra(MainActivity.EXTRA_ROW, layout.short(code)))
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_FOR_RESULT = "for_result"
        const val EXTRA_SUGGEST = "suggest"
        const val EXTRA_CHOSEN = "chosen"
        private const val DAY_MS = 24L * 3600 * 1000

        fun codes(layout: SiteLayout): List<LocationCode> =
            layout.depots.flatMap { d -> d.rows.flatMap { r -> (1..r.columns).map { LocationCode(d.code, r.name.uppercase(), it) } } }

        /** The row after [code] in the layout's order (the usual next step of a walk), or null. */
        fun nextAfter(layout: SiteLayout, code: LocationCode): LocationCode? {
            val all = codes(layout)
            val i = all.indexOf(code)
            return if (i < 0) null else all.getOrNull(i + 1)
        }

        internal fun ago(c: Context, ms: Long): String {
            val days = ((System.currentTimeMillis() - ms) / DAY_MS).toInt()
            return when (days) {
                0 -> c.getString(R.string.ago_today, SimpleDateFormat("HH:mm", Locale.forLanguageTag("tr")).format(Date(ms)))
                1 -> c.getString(R.string.ago_yesterday)
                else -> c.getString(R.string.ago_days, days)
            }
        }
    }
}

private data class RowsState(
    val layout: SiteLayout,
    val last: Map<LocationCode, ColumnScan>,
    val flags: Map<LocationCode, Long>,
    val suggested: LocationCode?,
    val demo: Boolean,
)

@Composable
private fun RowsScreen(suggest: String?, onBack: () -> Unit, onChoose: (SiteLayout, LocationCode) -> Unit) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<RowsState?>(null) }
    LifecycleResumeEffect(Unit) {
        val store = (context.applicationContext as SweepScanApp).locations
        val layout = store.layout
        store.query({ it.scans() }) { scans ->
            val last = scans.associateBy { LocationCode(it.depot, it.row, it.col) }
            val all = RowListActivity.codes(layout)
            val flags = RowFlags.flagged(context, last.mapValues { it.value.scannedAt })
            val suggested = suggest?.let { LocationCode.parseText(it) }?.takeIf { layout.contains(it) }
                ?: flags.keys.firstOrNull { layout.contains(it) }
                ?: all.firstOrNull { it !in last }
                ?: all.minByOrNull { last[it]?.scannedAt ?: 0L }
            state = RowsState(layout, last, flags, suggested, store.demo && !com.sweepscan.request.Presentation.isOn(context))
        }
        onPauseOrDispose { }
    }

    SweepScreen(title = stringResource(R.string.home_rows), onBack = onBack, backDescription = stringResource(R.string.back)) {
        val s = state ?: run {
            // The rows come from the database: a short wait, shown as one, never as an empty screen.
            item {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(top = 96.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(color = Palette.Brand)
                }
            }
            return@SweepScreen
        }
        val all = RowListActivity.codes(s.layout)
        val now = System.currentTimeMillis()
        val week = all.count { c -> s.last[c]?.let { now - it.scannedAt < LocationActivity.FRESH_MS } == true }
        if (s.demo) item { Text(stringResource(R.string.demo_banner), color = Palette.Amber, style = MaterialTheme.typography.labelMedium) }
        item {
            Column(Modifier.padding(top = Space.s)) {
                Text(stringResource(R.string.rows_week, week, all.size), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(Space.s))
                LinearProgressIndicator(
                    progress = { week / all.size.coerceAtLeast(1).toFloat() },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = Palette.Green,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
        }
        s.suggested?.let { code ->
            item { SectionLabel(stringResource(R.string.rows_suggested)) }
            item { RowItem(s, code, highlight = true) { onChoose(s.layout, code) } }
        }
        for (d in s.layout.depots) {
            item(key = "depot-${d.code}") { SectionLabel(d.name) }
            items(all.filter { it.depot == d.code }, key = { it.text }) { code -> RowItem(s, code, highlight = false) { onChoose(s.layout, code) } }
        }
        item {
            Text(
                stringResource(R.string.rows_help),
                Modifier.padding(top = Space.l),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RowItem(s: RowsState, code: LocationCode, highlight: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val scan = s.last[code]
    val now = System.currentTimeMillis()
    val (label, fg, bg) = when {
        code in s.flags -> Triple(stringResource(R.string.rows_check), Color(0xFFFFB74D), Color(0xFF5A2A1F))
        scan == null -> Triple(stringResource(R.string.ov_never), Palette.TextSoft, Color(0xFF2E353D))
        now - scan.scannedAt >= LocationActivity.FRESH_MS ->
            Triple(RowListActivity.ago(context, scan.scannedAt) + " · " + stringResource(R.string.rows_old), Color(0xFFFFB74D), Color(0xFF4D3F2A))
        else -> Triple(RowListActivity.ago(context, scan.scannedAt), Color(0xFF7EE2B8), Color(0xFF24453A))
    }
    val describe = s.layout.describe(code)
    SweepCard(
        onClick = onClick,
        modifier = Modifier
            .then(if (highlight) Modifier.border(2.dp, Palette.Blue, MaterialTheme.shapes.medium) else Modifier)
            .semantics { contentDescription = "$describe, $label" },
    ) {
        Row(Modifier.padding(horizontal = Space.l, vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
            Text(
                s.layout.short(code),
                style = MaterialTheme.typography.titleMedium,
                color = if (highlight) Palette.Blue else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.width(64.dp),
            )
            Text(
                if (highlight) describe else describe.substringAfter(" · "),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            StatusPill(label, bg, onColor = fg)
        }
    }
}
