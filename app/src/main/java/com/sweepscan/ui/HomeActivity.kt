package com.sweepscan.ui

import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.sweepscan.R
import com.sweepscan.SweepScanApp
import com.sweepscan.hid.HidKeyboard
import com.sweepscan.location.LocationCode
import com.sweepscan.request.Requests
import com.sweepscan.request.ShareRequestActivity
import com.sweepscan.request.VoiceRequestActivity
import com.sweepscan.server.DepotServer
import com.sweepscan.server.DepotService
import com.sweepscan.server.ShopClient
import com.sweepscan.ui.theme.IconAction
import com.sweepscan.ui.theme.Palette
import com.sweepscan.ui.theme.SectionLabel
import com.sweepscan.ui.theme.Space
import com.sweepscan.ui.theme.SweepCard
import com.sweepscan.ui.theme.SweepScreen
import com.sweepscan.ui.theme.SweepTheme
import com.sweepscan.ui.theme.TileShape

/**
 * Where the app starts. The question asked most ("where is this model?") is the search bar on
 * top, with the microphone and a camera button to search by reading a box; below it the jobs as
 * big tiles, each with a live status line (requests move to the top while some wait); then the
 * waiting requests and recent searches. The camera is not opened until a job needs it.
 */
class HomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A shop-floor phone opens on the shop screen.
        if (ShopClient.isShopPhone(this)) {
            startActivity(Intent(this, ShopActivity::class.java))
            finish()
            return
        }
        // The depot server was on: bring it back (after an update or a restart of the app).
        if (DepotService.isOn(this) && !DepotService.running) runCatching { DepotService.start(this) }
        if (!prefs(this).getBoolean(KEY_ONBOARDED, false)) startActivity(Intent(this, OnboardingActivity::class.java))
        setContent { SweepTheme { HomeScreen() } }
        handleShortcut(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShortcut(intent)
    }

    /** Launcher shortcuts (long press on the icon) and the quick settings tile land here. */
    private fun handleShortcut(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_SHORTCUT)) {
            "find" -> openSearch(this, null)
            "say" -> startActivity(Intent(this, VoiceRequestActivity::class.java))
            "rows" -> startActivity(Intent(this, RowListActivity::class.java))
            "count" -> startActivity(MainActivity.intent(this, MainActivity.Task.COUNT))
            "requests" -> startActivity(Intent(this, RequestsActivity::class.java))
            else -> return
        }
        intent.removeExtra(EXTRA_SHORTCUT)
    }

    companion object {
        private const val KEY_ONBOARDED = "onboarded"
        const val EXTRA_SHORTCUT = "shortcut"
        private const val KEY_RECENT = "recent_searches"
        private const val RECENT_MAX = 6

        fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

        fun setOnboarded(context: Context) =
            prefs(context).edit().putBoolean(KEY_ONBOARDED, true).putBoolean("guide_shown", true).apply()

        fun recentSearches(context: Context): List<String> =
            prefs(context).getString(KEY_RECENT, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

        /** Remembers a search that found something, with where (newest first, no duplicates). */
        fun addRecent(context: Context, query: String, place: String? = null) {
            val q = query.trim()
            if (q.isEmpty()) return
            val entry = if (place == null) q else q + "\t" + place
            val list = (listOf(entry) + recentSearches(context).filter { !it.substringBefore('\t').equals(q, ignoreCase = true) }).take(RECENT_MAX)
            prefs(context).edit().putString(KEY_RECENT, list.joinToString("\n")).apply()
        }

        fun openSearch(c: Context, query: String?) {
            c.startActivity(
                Intent(c, LocationActivity::class.java)
                    .putExtra(LocationActivity.EXTRA_QUERY, query)
                    .putExtra(LocationActivity.EXTRA_FOCUS, query == null),
            )
        }
    }
}

/** What the home screen shows, read again every time it comes back. */
private data class HomeState(
    val demo: Boolean = false,
    val findSub: String = "",
    val rowsSub: String = "",
    val countSub: String = "",
    val requestsTitle: String = "",
    val requestsSub: String = "",
    val waiting: List<Requests.Request> = emptyList(),
    val recent: List<String> = emptyList(),
    val server: String? = null,
)

@Composable
private fun HomeScreen() {
    val context = LocalContext.current
    var state by remember { mutableStateOf(load(context, query = false) {}) }
    LifecycleResumeEffect(Unit) {
        state = load(context) { state = it }
        onPauseOrDispose { }
    }

    Box(Modifier.fillMaxSize()) {
    SweepScreen(
        title = stringResource(R.string.app_name),
        actions = {
            IconAction(R.drawable.ic_settings, stringResource(R.string.settings)) {
                context.startActivity(Intent(context, SettingsActivity::class.java))
            }
        },
    ) {
        if (state.demo) item {
            Text(
                stringResource(R.string.demo_banner),
                Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Color(0x4D3F2A00)).padding(vertical = Space.xs),
                color = Palette.Amber, style = MaterialTheme.typography.labelMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        item { SearchBar(context) }
        state.server?.let { s -> item { Text(s, style = MaterialTheme.typography.bodySmall, color = Palette.Green, modifier = Modifier.padding(start = Space.xs)) } }

        val requests: @Composable () -> Unit = {
            Tile(R.drawable.ic_list_white, state.requestsTitle, state.requestsSub, highlight = state.waiting.isNotEmpty()) {
                context.startActivity(Intent(context, RequestsActivity::class.java))
            }
        }
        if (state.waiting.isNotEmpty()) item { requests() }
        item { Tile(R.drawable.ic_pin, stringResource(R.string.home_find), state.findSub) { HomeActivity.openSearch(context, null) } }
        item { Tile(R.drawable.ic_rows, stringResource(R.string.home_rows), state.rowsSub) { context.startActivity(Intent(context, RowListActivity::class.java)) } }
        if (state.waiting.isEmpty()) item { requests() }
        item {
            Tile(R.drawable.ic_truck, stringResource(R.string.home_count), state.countSub) {
                context.startActivity(MainActivity.intent(context, MainActivity.Task.COUNT))
            }
        }

        if (state.waiting.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.home_requests)) }
            items(state.waiting, key = { it.id }) { r -> WaitingRow(r) { context.startActivity(Intent(context, RequestsActivity::class.java)) } }
        }
        if (state.recent.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.home_recent)) }
            item { RecentChips(state.recent) { HomeActivity.openSearch(context, it) } }
        }
        // Room under the scan button.
        item { Spacer(Modifier.height(88.dp)) }
    }
    // The most common start: read a box. One big button in thumb reach, as in scanner apps.
    ExtendedFloatingActionButton(
        onClick = { context.startActivity(MainActivity.intent(context, MainActivity.Task.LOOKUP)) },
        icon = { Icon(painterResource(R.drawable.ic_camera), null, modifier = Modifier.size(24.dp)) },
        text = { Text(stringResource(R.string.home_scan), style = MaterialTheme.typography.titleMedium) },
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = Space.l)
            .height(64.dp),
    )
    }
}

@Composable
private fun SearchBar(context: Context) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = Space.s),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .clickable(role = Role.Button) { HomeActivity.openSearch(context, null) }
                    .padding(start = Space.l, top = Space.l, bottom = Space.l),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_search), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(Space.m))
                Text(stringResource(R.string.home_search_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconAction(R.drawable.ic_mic, stringResource(R.string.req_say)) {
                context.startActivity(Intent(context, VoiceRequestActivity::class.java))
            }
            IconAction(R.drawable.ic_camera, stringResource(R.string.task_lookup), tint = MaterialTheme.colorScheme.primary) {
                context.startActivity(MainActivity.intent(context, MainActivity.Task.LOOKUP))
            }
            Spacer(Modifier.width(Space.xs))
        }
    }
}

/** A job: icon, name, its live status line. [highlight] = something waits (amber). */
@Composable
private fun Tile(@DrawableRes icon: Int, title: String, sub: String, highlight: Boolean = false, onClick: () -> Unit) {
    val accent = if (highlight) Palette.Amber else MaterialTheme.colorScheme.primary
    Surface(
        modifier = Modifier.fillMaxWidth().clip(TileShape).clickable(role = Role.Button, onClick = onClick),
        shape = TileShape,
        color = if (highlight) Color(0xFF2A2312) else MaterialTheme.colorScheme.surface,
    ) {
        Row(Modifier.padding(Space.l), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(icon), null, tint = accent, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(Space.l))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = if (highlight) Palette.Amber else MaterialTheme.colorScheme.onSurface)
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun WaitingRow(r: Requests.Request, onClick: () -> Unit) {
    SweepCard(onClick = onClick, accent = Palette.Amber) {
        Row(Modifier.padding(horizontal = Space.l, vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
            Text(Requests.clock(r.time), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(52.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        r.voice -> stringResource(R.string.req_voice_short)
                        r.answer != null -> r.answer
                        else -> stringResource(R.string.req_not_found, r.digits ?: "")
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                val sub = listOf(r.sender, if (r.voice) stringResource(R.string.req_say_hint) else r.text).filter { it.isNotBlank() }.joinToString(" · ")
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecentChips(recent: List<String>, onSearch: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        for (entry in recent) {
            val (q, place) = entry.split('\t').let { it[0] to it.getOrNull(1) }
            AssistChip(onClick = { onSearch(q) }, label = { Text(if (place.isNullOrEmpty()) q else "$q · $place") })
        }
    }
}

/** Reads the status lines and returns them; the map's part follows through [done] from the database thread. */
private fun load(c: Context, query: Boolean = true, done: (HomeState) -> Unit): HomeState {
    val app = c.applicationContext as SweepScanApp
    val store = app.locations
    val layout = store.layout
    val all = Requests.recent(c)
    val waiting = all.filter { it.status.open }
    val today = all.count { it.time >= System.currentTimeMillis() - 24L * 3600 * 1000 }
    val requestsSub = when {
        waiting.isNotEmpty() -> c.getString(R.string.home_requests_waiting, today)
        !ShareRequestActivity.watcherAvailable(c) -> c.getString(R.string.home_requests_share)
        !Requests.enabled(c) && !DepotService.isOn(c) -> c.getString(R.string.home_requests_off)
        Requests.enabled(c) && !Requests.accessGranted(c) -> c.getString(R.string.home_requests_no_access)
        else -> c.getString(R.string.home_requests_none, today)
    }
    val countSub = c.getString(
        R.string.home_count_sub,
        c.getString(if (app.keyboard.status == HidKeyboard.Status.CONNECTED) R.string.home_pc_on else R.string.home_pc_off),
    )
    val server = if (DepotService.isOn(c)) c.getString(R.string.home_server_on, DepotService.address(c) ?: "—", DepotServer.pin(c)) else null
    val base = HomeState(
        demo = store.demo && !com.sweepscan.request.Presentation.isOn(c),
        findSub = c.getString(R.string.home_find_sub),
        countSub = countSub,
        requestsTitle = if (waiting.isNotEmpty()) c.getString(R.string.home_requests_title_count, waiting.size) else c.getString(R.string.requests_title),
        requestsSub = requestsSub,
        waiting = waiting.take(3),
        recent = HomeActivity.recentSearches(c),
        server = server,
    )
    if (query) store.query({ dao -> dao.scans() to dao.modelCount() }) { (scans, models) ->
        val now = System.currentTimeMillis()
        val codes = RowListActivity.codes(layout)
        val last = scans.associate { LocationCode(it.depot, it.row, it.col) to it.scannedAt }
        val fresh = codes.count { code -> last[code]?.let { now - it < LocationActivity.FRESH_MS } == true }
        val next = codes.firstOrNull { it !in last } ?: codes.minByOrNull { last[it] ?: 0L }
        done(
            base.copy(
                rowsSub = if (next == null) c.getString(R.string.home_rows_sub_none, fresh, codes.size)
                else c.getString(R.string.home_rows_sub, fresh, codes.size, layout.short(next)),
                findSub = if (scans.isEmpty()) c.getString(R.string.home_find_empty) else c.getString(R.string.home_find_sub_models, models),
            ),
        )
    }
    return base
}
