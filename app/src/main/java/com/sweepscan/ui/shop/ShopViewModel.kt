package com.sweepscan.ui.shop

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sweepscan.R
import com.sweepscan.request.RequestParser
import com.sweepscan.request.Requests
import com.sweepscan.server.AppUpdate
import com.sweepscan.server.DepotServer
import com.sweepscan.server.ShopClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

/** One request this phone sent, as the depot sees it. */
data class ShopRequest(
    val id: Long,
    val time: Long,
    val what: String,
    val where: String,
    val status: Requests.Status,
)

enum class Link { SEARCHING, NOT_FOUND, CONNECTED, LOST }

data class ShopState(
    val link: Link = Link.SEARCHING,
    val address: String? = null,
    val name: String = "",
    val requests: List<ShopRequest> = emptyList(),
    /** While typing: where the depot has it ("Büyük depo · 3. sıra"), or null. */
    val hint: Hint? = null,
    /** The depot found needs the PIN: its address. */
    val askPin: String? = null,
    val sending: Boolean = false,
    /** The depot phone runs a newer SweepScan than this phone. */
    val update: Update? = null,
)

/** [progress] 0–100 while downloading, null before. */
data class Update(val version: String, val progress: Int? = null)

sealed interface Hint {
    data class InDepot(val where: String) : Hint
    data object NotOnMap : Hint
}

sealed interface ShopEvent {
    data object Sent : ShopEvent
    data class Failed(val message: Int) : ShopEvent
    data class Changed(val what: String, val status: Requests.Status) : ShopEvent
    /** SweepScan may not install apps yet: open its "install unknown apps" switch. */
    data object AllowInstall : ShopEvent
}

/**
 * The shop screen's state: the link to the depot phone (found on the Wi-Fi, PIN once), the
 * requests this phone sent (polled every few seconds while the screen is shown) and asking.
 */
class ShopViewModel(app: Application) : AndroidViewModel(app) {

    private val client = ShopClient(app)
    private val _state = MutableStateFlow(ShopState(address = client.address, name = client.name))
    val state: StateFlow<ShopState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<ShopEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ShopEvent> = _events

    private var polling: Job? = null
    private var lookAhead: Job? = null
    private val lastStatus = HashMap<Long, Requests.Status>()

    // ---- Link ----------------------------------------------------------------------------------

    fun start() {
        connect(force = false)
        polling?.cancel()
        polling = viewModelScope.launch {
            while (true) {
                delay(POLL_MS)
                if (_state.value.link == Link.CONNECTED) refresh()
            }
        }
    }

    fun stop() {
        polling?.cancel()
        client.stopDiscovery()
    }

    fun connect(force: Boolean) {
        viewModelScope.launch {
            val known = client.address
            if (known != null && !force && call<JSONObject> { client.ping(known, it) }.isSuccess) onDepot(known) else search()
        }
    }

    private fun search() {
        _state.update { it.copy(link = Link.SEARCHING) }
        client.discover { depot ->
            client.stopDiscovery()
            client.address = depot.address
            _state.update { it.copy(address = depot.address) }
            viewModelScope.launch { onDepot(depot.address) }
        }
        viewModelScope.launch {
            delay(6000)
            if (_state.value.link == Link.SEARCHING) _state.update { it.copy(link = Link.NOT_FOUND) }
        }
    }

    private suspend fun onDepot(address: String) {
        if (client.pin == null) {
            _state.update { it.copy(askPin = address) }
            return
        }
        val r = call<JSONObject> { client.requests(it) }
        val failure = r.exceptionOrNull() as? ShopClient.Failure
        when {
            failure?.code == 401 -> { client.pin = null; _state.update { it.copy(askPin = address) } }
            r.isSuccess -> {
                _state.update { it.copy(link = Link.CONNECTED, address = address) }
                show(r.getOrThrow())
                checkVersion(address)
            }
            else -> _state.update { it.copy(link = Link.NOT_FOUND) }
        }
    }

    fun submitPin(pin: String) {
        val address = _state.value.askPin ?: return
        client.pin = pin.trim()
        _state.update { it.copy(askPin = null) }
        viewModelScope.launch { onDepot(address) }
    }

    fun dismissPin() = _state.update { it.copy(askPin = null, link = Link.NOT_FOUND) }

    fun reenterPin() {
        client.pin = null
        val a = client.address
        if (a != null) _state.update { it.copy(askPin = a) } else connect(force = true)
    }

    fun setName(name: String) {
        client.name = name
        _state.update { it.copy(name = client.name) }
    }

    fun setAddress(text: String) {
        val a = text.trim().let { if (it.contains(':')) it else "$it:${DepotServer.PORT}" }
        client.address = a
        _state.update { it.copy(address = a) }
        connect(force = false)
    }

    // ---- Update from the depot phone ----------------------------------------------------------

    private suspend fun checkVersion(address: String) {
        val p = call<JSONObject> { client.ping(address, it) }.getOrNull() ?: return
        val newer = p.optBoolean("apk") && AppUpdate.isNewer(AppUpdate.versionCode(getApplication()), p.optLong("code"))
        _state.update { s ->
            s.copy(update = if (newer) s.update?.takeIf { it.version == p.optString("version") } ?: Update(p.optString("version")) else null)
        }
    }

    fun startUpdate() {
        val u = _state.value.update ?: return
        if (u.progress != null) return
        val app = getApplication<Application>()
        if (!AppUpdate.canInstall(app)) {
            _events.tryEmit(ShopEvent.AllowInstall)
            return
        }
        _state.update { it.copy(update = u.copy(progress = 0)) }
        client.download(AppUpdate.file(app), { p -> _state.update { s -> s.copy(update = s.update?.copy(progress = p)) } }) { r ->
            _state.update { s -> s.copy(update = s.update?.copy(progress = null)) }
            r.onSuccess { f ->
                runCatching { AppUpdate.install(app, f) }.onFailure { _events.tryEmit(ShopEvent.Failed(R.string.update_download_failed)) }
            }.onFailure { _events.tryEmit(ShopEvent.Failed(R.string.update_download_failed)) }
        }
    }

    // ---- Asking --------------------------------------------------------------------------------

    fun send(text: String?, barcode: String?) {
        if (_state.value.link != Link.CONNECTED) {
            _events.tryEmit(ShopEvent.Failed(R.string.shop_no_depot))
            connect(force = client.address == null)
            return
        }
        if (barcode == null && RequestParser.parse(text.orEmpty()).isEmpty()) {
            _events.tryEmit(ShopEvent.Failed(R.string.req_add_no_code))
            return
        }
        _state.update { it.copy(sending = true) }
        viewModelScope.launch {
            val r = call<JSONObject> { client.send(text, barcode, null, it) }
            _state.update { it.copy(sending = false) }
            r.onSuccess { o ->
                val made = o.optJSONArray("requests")
                client.mine = client.mine + (0 until (made?.length() ?: 0)).map { made!!.getJSONObject(it).getLong("id") }
                _state.update { it.copy(hint = null) }
                _events.tryEmit(ShopEvent.Sent)
                refresh()
            }.onFailure { e ->
                _events.tryEmit(
                    ShopEvent.Failed(
                        when ((e as? ShopClient.Failure)?.error) {
                            "unknown_barcode" -> R.string.shop_unknown_barcode
                            "no_code" -> R.string.req_add_no_code
                            "busy" -> R.string.shop_busy
                            else -> R.string.shop_no_depot
                        },
                    ),
                )
            }
        }
    }

    /** While typing: does the depot have it, and where? (Waits for a pause in typing.) */
    fun typed(text: String) {
        lookAhead?.cancel()
        if (_state.value.link != Link.CONNECTED || RequestParser.parse(text).isEmpty()) {
            _state.update { it.copy(hint = null) }
            return
        }
        lookAhead = viewModelScope.launch {
            delay(350)
            val o = call<JSONObject> { client.find(text, it) }.getOrNull() ?: return@launch
            _state.update {
                it.copy(hint = if (o.optBoolean("found")) Hint.InDepot(o.optString("where")) else Hint.NotOnMap)
            }
        }
    }

    // ---- My requests ---------------------------------------------------------------------------

    private suspend fun refresh() {
        val r = call<JSONObject> { client.requests(it) }
        r.onSuccess { show(it) }.onFailure { _state.update { it.copy(link = Link.LOST) } }
    }

    private fun show(o: JSONObject) {
        val arr = o.optJSONArray("requests") ?: return
        val mine = client.mine
        val list = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.getLong("id") in mine }.map { r ->
            ShopRequest(
                id = r.getLong("id"),
                time = r.getLong("time"),
                what = listOf(r.optString("model").ifEmpty { r.optString("text") }, r.optString("size")).filter { it.isNotEmpty() }.joinToString(" · "),
                where = r.optString("where"),
                status = runCatching { Requests.Status.valueOf(r.optString("status")) }.getOrDefault(Requests.Status.NEW),
            )
        }
        for (r in list) {
            val before = lastStatus[r.id]
            if (before != null && before != r.status) _events.tryEmit(ShopEvent.Changed(r.what, r.status))
            lastStatus[r.id] = r.status
        }
        _state.update { it.copy(requests = list) }
    }

    override fun onCleared() {
        client.close()
    }

    private suspend fun <T> call(block: ((Result<T>) -> Unit) -> Unit): Result<T> =
        suspendCancellableCoroutine { cont -> block { if (cont.isActive) cont.resume(it) } }

    companion object {
        private const val POLL_MS = 2500L
    }
}
