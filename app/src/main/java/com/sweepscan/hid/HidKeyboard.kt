package com.sweepscan.hid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * Makes the phone a Bluetooth keyboard (HID Device profile, Android 9+), so the PC sees it exactly
 * like the wired barcode gun: digits followed by Enter, typed into whatever field has focus.
 *
 * The Android side (registration, connection, callbacks) lives here; queueing and typing rules are
 * in the platform-free [HidSender] and [HidTypist] so they can be unit-tested.
 *
 * Android unregisters a HID app that leaves the foreground, so screens call [ensureRegistered] in
 * onStart. Callers must hold BLUETOOTH_CONNECT (Android 12+) before calling [start].
 */
@SuppressLint("MissingPermission")
class HidKeyboard(private val context: Context, private val prefs: SharedPreferences) {

    enum class Status { OFF, UNSUPPORTED, REGISTERING, READY, CONNECTED }

    interface Listener {
        fun onStatus(status: Status, hostName: String?) {}
        fun onSent(entryId: Long) {}
        fun onUncertain(entryId: Long, text: String, typed: Int, enterSent: Boolean) {}
        fun onPauseChanged(reason: PauseReason?) {}
        fun onNotice(notice: Notice) {}
    }

    /** Host-side events worth telling the user about. */
    enum class Notice { BOOT_PROTOCOL, UNPLUGGED }

    @Volatile var status = Status.OFF
        private set
    @Volatile var unsupportedReason: String? = null
        private set
    @Volatile var timing = HidTiming()
    /** Receives every HID event for the field/event log. */
    @Volatile var eventSink: ((HidEvent) -> Unit)? = null
    /**
     * Write-ahead hooks, called synchronously on the sender thread: [onJobStarted] right before a
     * barcode's first key, [onJobNotStarted] when nothing was typed after all. They let the session
     * persist "being typed", so a crash mid-barcode can't lead to a silent second send.
     */
    @Volatile var onJobStarted: ((Long) -> Unit)? = null
    @Volatile var onJobNotStarted: ((Long) -> Unit)? = null

    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    @Volatile private var hid: BluetoothHidDevice? = null
    @Volatile private var host: BluetoothDevice? = null
    @Volatile private var registered = false

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val main = Handler(Looper.getMainLooper())
    private val callbackExecutor = Executors.newSingleThreadExecutor()
    private var senderThread: Thread? = null

    private val link = object : HidLink {
        override fun isConnected() = status == Status.CONNECTED && host != null && hid != null
        override fun send(report: ByteArray): Boolean {
            val device = hid ?: return false
            val target = host ?: return false
            return device.sendReport(target, KeyboardReport.REPORT_ID, report)
        }
    }

    private val sender = HidSender(
        typist = HidTypist(link) { Thread.sleep(it) },
        timing = { timing },
        listener = object : HidSender.Listener {
            override fun onStarted(entryId: Long) { onJobStarted?.invoke(entryId) }
            override fun onNotStarted(entryId: Long) { onJobNotStarted?.invoke(entryId) }
            override fun onSent(entryId: Long) = post { it.onSent(entryId) }
            override fun onUncertain(entryId: Long, text: String, result: HidTypist.Result) =
                post { it.onUncertain(entryId, text, result.typed, result.enterSent) }
            override fun onPauseChanged(reason: PauseReason?) = post { it.onPauseChanged(reason) }
        },
        events = { event -> eventSink?.invoke(event) },
        sleep = { Thread.sleep(it) },
        clock = { SystemClock.elapsedRealtime() },
    )

    val hostName: String? get() = host?.let { it.name ?: it.address }
    val pending: Int get() = sender.pending
    val paused: PauseReason? get() = sender.paused

    fun addListener(listener: Listener) = listeners.add(listener)
    fun removeListener(listener: Listener) = listeners.remove(listener)

    fun start() {
        if (senderThread == null) {
            senderThread = Thread({ while (true) sender.step(link.isConnected()) }, "hid-sender").apply {
                isDaemon = true
                start()
            }
        }
        watchBluetoothState()
        if (hid != null) {
            ensureRegistered()
            return
        }
        if (status == Status.REGISTERING) return
        val bt = adapter
        if (bt == null) {
            setUnsupported("no_adapter")
            return
        }
        // With Bluetooth off the profile proxy never connects; asking anyway used to end in a
        // misleading "not available (timeout)". Wait for the STATE_ON broadcast instead.
        if (!bt.isEnabled) {
            unsupportedReason = null
            setStatus(Status.OFF)
            return
        }
        setStatus(Status.REGISTERING)
        if (!bt.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)) {
            setUnsupported("no_proxy")
            return
        }
        armRegistrationTimeout()
    }

    /** Re-registers after Android dropped the app's registration (e.g. it went to the background). */
    fun ensureRegistered() {
        val device = hid ?: return
        if (registered || status == Status.REGISTERING) return
        setStatus(Status.REGISTERING)
        register(device)
    }

    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true

    private var watchingState = false

    /** Starts the keyboard when Bluetooth is switched on while the app runs (e.g. from quick settings). */
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> {
                    event("bt_on")
                    if (status == Status.OFF || status == Status.UNSUPPORTED) start()
                }
                BluetoothAdapter.STATE_OFF -> {
                    event("bt_off")
                    if (hid == null && status != Status.OFF) setStatus(Status.OFF)
                }
            }
        }
    }

    private fun watchBluetoothState() {
        if (watchingState) return
        watchingState = true
        // A protected system broadcast: delivered to a non-exported receiver.
        androidx.core.content.ContextCompat.registerReceiver(
            context.applicationContext, stateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun bondedDevices(): List<BluetoothDevice> = adapter?.bondedDevices?.toList().orEmpty()

    fun connect(device: BluetoothDevice) {
        hid?.connect(device)
    }

    fun enqueue(entryId: Long, text: String) = sender.enqueue(entryId, text)
    fun cancel(entryId: Long): Boolean = sender.cancel(entryId)
    fun isQueued(entryId: Long) = sender.isQueued(entryId)
    fun pause() = sender.pause()
    fun resume() = sender.resume()

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            val device = proxy as BluetoothHidDevice
            hid = device
            register(device)
        }

        override fun onServiceDisconnected(profile: Int) {
            hid = null
            host = null
            registered = false
            event("service_disconnected")
            setStatus(Status.OFF)
        }
    }

    private fun register(device: BluetoothHidDevice) {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "SweepScan", "Barcode keyboard", "SweepScan",
            BluetoothHidDevice.SUBCLASS1_KEYBOARD, KeyboardReport.DESCRIPTOR,
        )
        // Explicit L2CAP QoS (values from hid-barcode-scanner) avoids stuck/lost keys on some hosts.
        val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, 800, 9, 0, 11250, 11250,
        )
        if (!device.registerApp(sdp, null, qos, callbackExecutor, hidCallback)) {
            Log.w(TAG, "registerApp returned false")
            setUnsupported("register_false")
            return
        }
        armRegistrationTimeout()
    }

    private fun armRegistrationTimeout() {
        main.postDelayed({
            if (status == Status.REGISTERING) setUnsupported("timeout")
        }, REGISTER_TIMEOUT_MS)
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@HidKeyboard.registered = registered
            event("register", detail = registered.toString())
            if (!registered) {
                host = null
                setStatus(Status.OFF)
                return
            }
            unsupportedReason = null
            setStatus(Status.READY)
            // Reconnect to the last PC so the user does not have to pick it every time.
            val target = pluggedDevice ?: prefs.getString(KEY_LAST_HOST, null)
                ?.let { address -> bondedDevices().firstOrNull { it.address == address } }
            target?.let { hid?.connect(it) }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            event("status", detail = "conn=$state")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    prefs.edit().putString(KEY_LAST_HOST, device.address).apply()
                    setStatus(Status.CONNECTED)
                }
                BluetoothProfile.STATE_DISCONNECTED -> if (host == null || host?.address == device.address) {
                    host = null
                    if (hid != null && registered) setStatus(Status.READY)
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            hid?.replyReport(device, type, id, ByteArray(KeyboardReport.SIZE))
        }

        override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) {
            // Boot protocol reports carry no report ID; our report-ID-1 reports could be misread.
            event("protocol", detail = protocol.toString())
            if (protocol.toInt() == BluetoothHidDevice.PROTOCOL_BOOT_MODE.toInt()) post { it.onNotice(Notice.BOOT_PROTOCOL) }
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            event("unplug")
            host = null
            if (hid != null && registered) setStatus(Status.READY)
            post { it.onNotice(Notice.UNPLUGGED) }
        }
    }

    private fun setUnsupported(reason: String) {
        unsupportedReason = reason
        event("unsupported", reason = reason)
        setStatus(Status.UNSUPPORTED)
    }

    private fun setStatus(newStatus: Status) {
        status = newStatus
        val name = hostName
        post { it.onStatus(newStatus, name) }
    }

    private fun event(ev: String, reason: String? = null, detail: String? = null) {
        eventSink?.invoke(HidEvent(ev, reason = reason, detail = detail))
    }

    private fun post(block: (Listener) -> Unit) {
        main.post { listeners.forEach(block) }
    }

    private companion object {
        const val TAG = "HidKeyboard"
        const val KEY_LAST_HOST = "last_host"
        const val REGISTER_TIMEOUT_MS = 5000L
    }
}
