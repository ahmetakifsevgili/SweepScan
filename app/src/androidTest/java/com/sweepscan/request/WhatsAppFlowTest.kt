package com.sweepscan.request

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.sweepscan.SweepScanApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The WhatsApp automation rehearsed end to end with **fake** WhatsApp notifications (debug build,
 * sample map). Their "Yanıtla" actions lead to this test's own receiver, which records what the
 * app would have written: nothing ever reaches WhatsApp, the real group or anyone. (The debug app
 * has no notification access either, so real WhatsApp notifications never reach it.)
 *
 * Covers the bugs found in review: a one-to-one chat whose name contains the group's name must not
 * be taken for the group nor receive answers (H1/H4), late and same-millisecond messages (H2),
 * and our own "✅ Gönderdim" replies shown back by WhatsApp (H7).
 */
@RunWith(AndroidJUnit4::class)
class WhatsAppFlowTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<SweepScanApp>()

    /** Texts the app "wrote", per fake chat. */
    private val written = Collections.synchronizedList(ArrayList<Pair<String, String>>())
    private lateinit var receiver: BroadcastReceiver
    private var replyCode = 0

    @Before
    fun setUp() {
        val ready = CountDownLatch(1)
        instrumentation.runOnMainSync { app.setDemoMode(true) { ready.countDown() } }
        assertTrue(ready.await(30, TimeUnit.SECONDS))
        clean()
        Requests.setGroup(app, "Ürün isteme")
        Requests.setReplies(app, true)
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY)?.toString() ?: return
                written += (intent.getStringExtra("chat") ?: "?") to text
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    @After
    fun tearDown() {
        app.unregisterReceiver(receiver)
        clean()
        app.getSharedPreferences("presentation", Context.MODE_PRIVATE).edit().clear().commit()
        val off = CountDownLatch(1)
        instrumentation.runOnMainSync { app.setDemoMode(false) { off.countDown() } }
        off.await(10, TimeUnit.SECONDS)
    }

    private fun clean() {
        app.getSharedPreferences("requests", Context.MODE_PRIVATE).edit().remove("list").remove("last_time").commit()
        app.getSharedPreferences(Presentation.REQUEST_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("row_flags", Context.MODE_PRIVATE).edit().clear().commit()
        Intake.forget(app)
        Requests.forgetReply()
    }

    // ---- Fake WhatsApp ---------------------------------------------------------------------

    private class Msg(val sender: String?, val text: String, val time: Long = System.currentTimeMillis())

    /** A WhatsApp-like chat notification; its reply action is recorded under [chat]. */
    private fun whatsapp(title: String, group: Boolean, vararg messages: Msg, chat: String = title, summary: Boolean = false): Notification {
        val reply = PendingIntent.getBroadcast(
            app, ++replyCode, Intent(ACTION).setPackage(app.packageName).putExtra("chat", chat),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val action = NotificationCompat.Action.Builder(0, "Yanıtla", reply).addRemoteInput(RemoteInput.Builder(KEY).build()).build()
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("Sen").build())
            .setConversationTitle(if (group) title else null)
            .setGroupConversation(group)
        for (m in messages) style.addMessage(m.text, m.time, m.sender?.let { Person.Builder().setName(it).build() })
        return NotificationCompat.Builder(app, "test")
            .setSmallIcon(com.sweepscan.R.drawable.ic_pin)
            .setContentTitle(title)
            .setStyle(style)
            .addAction(action)
            .setGroupSummary(summary)
            .setGroup("g")
            .build()
    }

    /** Posts [n] as WhatsApp would to the watcher's logic; returns the requests made (waits for [expect] messages to be answered). */
    private fun deliver(n: Notification, pkg: String = "com.whatsapp", expect: Int = 1, rehearsal: Boolean = false): List<Requests.Request> {
        val made = Collections.synchronizedList(ArrayList<Requests.Request>())
        val latch = CountDownLatch(expect)
        var taken = 0
        instrumentation.runOnMainSync {
            val incoming = WhatsAppParse.read(pkg, n)
            taken = if (incoming == null) 0 else Intake.whatsApp(app, incoming, rehearsal) { r -> made += r; latch.countDown() }
        }
        if (expect > 0) assertTrue("requests not made in time", latch.await(15, TimeUnit.SECONDS))
        else Thread.sleep(800)
        if (expect == 0) assertEquals(0, taken)
        return made.distinctBy { it.id }
    }

    private fun status(r: Requests.Request, s: Requests.Status): Boolean {
        var ok = false
        instrumentation.runOnMainSync { ok = Requests.setStatus(app, r.id, s) }
        Thread.sleep(600)
        return ok
    }

    private fun group(vararg m: Msg) = whatsapp("Ürün isteme telsiz", true, *m)

    // ---- Scenarios -------------------------------------------------------------------------

    @Test
    fun s01_requestIsAnsweredAndSentRepliesInTheGroup() {
        val r = deliver(group(Msg("Kasa 1", "0159 2000 39"))).single()
        assertEquals("0159 2000", r.model)
        assertNotNull("the sample map knows where it is", r.answer)
        assertTrue(status(r, Requests.Status.SENT))
        assertEquals(listOf("Ürün isteme telsiz" to Requests.replyText(app, r, Requests.Status.SENT)), written.toList())
        assertTrue(written.single().second.contains("0159 2000"))
    }

    @Test
    fun s02_missingRepliesAndFlagsTheRow() {
        val r = deliver(group(Msg("Reyon", "0159 2010 41"))).single()
        assertTrue(status(r, Requests.Status.MISSING))
        assertEquals(Requests.replyText(app, r, Requests.Status.MISSING), written.single().second)
        val code = com.sweepscan.location.LocationCode.parseText(r.code!!)!!
        assertTrue(com.sweepscan.location.RowFlags.flagged(app, emptyMap()).containsKey(code))
    }

    @Test
    fun s03_privateChatNamedLikeTheGroupIsNeverAnswered() {
        val r = deliver(group(Msg("Kasa 1", "0159 2000 39"))).single()
        // A one-to-one chat with "Ürün isteme Ali": not the group, its reply action is not kept.
        deliver(whatsapp("Ürün isteme Ali", false, Msg("Ürün isteme Ali", "0159 2041 40"), chat = "private"), expect = 0)
        assertTrue(status(r, Requests.Status.SENT))
        assertEquals(listOf("Ürün isteme telsiz"), written.map { it.first })
        assertTrue(written.none { it.first == "private" })
    }

    @Test
    fun s04_summariesAndOtherAppsAreIgnored() {
        assertNull(WhatsAppParse.read("com.whatsapp", whatsapp("Ürün isteme", true, Msg("Kasa 1", "0159 2000 39"), summary = true)))
        assertNull(WhatsAppParse.read("org.telegram.messenger", group(Msg("Kasa 1", "0159 2000 39"))))
        // WhatsApp Business is WhatsApp too.
        assertNotNull(WhatsAppParse.read("com.whatsapp.w4b", group(Msg("Kasa 2", "0171 3310 38"))))
    }

    @Test
    fun s05_sameNotificationAgainAddsNothing() {
        val m = Msg("Kasa 1", "0159 2000 39")
        deliver(group(m))
        deliver(group(m), expect = 0)
        assertEquals(1, Requests.recent(app).size)
    }

    @Test
    fun s06_twoCodesInOneMessageAreTwoRequests() {
        // One message: one answer callback carrying both requests.
        val made = deliver(group(Msg("Kasa 1", "0159 2000 39 ve 0171 3300 38")), expect = 1)
        assertEquals(2, made.size)
        assertEquals(setOf("0159 2000", "0171 3300"), made.mapNotNull { it.model }.toSet())
    }

    @Test
    fun s07_askedAgainWhileWaitingIsTheSameRequest() {
        val first = deliver(group(Msg("Kasa 1", "0159 2000 39", System.currentTimeMillis() - 60_000))).single()
        val again = deliver(group(Msg("Kasa 1", "0159 2000 39 acil"))).single()
        assertEquals(first.id, again.id)
        assertEquals(1, Requests.recent(app).size)
    }

    @Test
    fun s08_voiceMessageBecomesAVoiceRequest() {
        val r = deliver(group(Msg("Vitrin", "🎤 Sesli mesaj (0:04)"))).single()
        assertTrue(r.voice)
    }

    @Test
    fun s09_lateAndSameMillisecondMessagesAreAllTaken() {
        val t = System.currentTimeMillis()
        deliver(group(Msg("Kasa 1", "0159 2000 39", t), Msg("Kasa 2", "0171 3300 38", t)), expect = 2)
        // A message from earlier, delivered late: the old "last time" rule dropped it.
        deliver(group(Msg("Reyon", "0188 120 37", t - 5 * 60_000)))
        assertEquals(3, Requests.recent(app).size)
        assertEquals(3, Requests.recent(app).map { it.id }.toSet().size)
    }

    @Test
    fun s10_oldMessagesAreHistory() {
        deliver(group(Msg("Kasa 1", "0159 2000 39", System.currentTimeMillis() - 31 * 60_000)), expect = 0)
        assertTrue(Requests.recent(app).isEmpty())
    }

    @Test
    fun s11_goneReplyActionFallsBackToTheClipboard() {
        val r = deliver(group(Msg("Kasa 1", "0159 2000 39"))).single()
        PendingIntent.getBroadcast(
            app, replyCode, Intent(ACTION).setPackage(app.packageName), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_MUTABLE,
        )?.cancel()
        assertFalse("a cancelled action cannot write", status(r, Requests.Status.SENT))
        assertTrue(written.isEmpty())
    }

    @Test
    fun s12_repliesOffWritesNothing() {
        Requests.setReplies(app, false)
        val r = deliver(group(Msg("Kasa 1", "0159 2000 39"))).single()
        assertTrue(status(r, Requests.Status.SENT))
        assertTrue(written.isEmpty())
    }

    @Test
    fun s13_shopAndTypedRequestsAreNotAnsweredInTheGroup() {
        deliver(group(Msg("Kasa 1", "0159 2000 39")))
        for ((source, text) in listOf(Intake.Source.SHOP to "0171 3310 40", Intake.Source.MANUAL to "0188 120 41")) {
            val latch = CountDownLatch(1)
            var made: List<Requests.Request> = emptyList()
            instrumentation.runOnMainSync { Intake.message(app, source, "Kasa 2", text, notify = false) { made = it; latch.countDown() } }
            assertTrue(latch.await(15, TimeUnit.SECONDS))
            assertTrue(status(made.single(), Requests.Status.SENT))
        }
        assertTrue(written.isEmpty())
    }

    @Test
    fun s14_groupNameWrittenDifferentlyStillMatches() {
        assertTrue(WhatsAppParse.isRequestGroup(WhatsAppParse.key("ÜRÜN İSTEME telsiz 📦"), true, "Ürün isteme"))
        assertTrue(WhatsAppParse.isRequestGroup(WhatsAppParse.key("ürün isteme"), true, "Ürün İsteme "))
        assertFalse(WhatsAppParse.isRequestGroup(WhatsAppParse.key("Ürün isteme"), false, "Ürün isteme"))
        assertFalse(WhatsAppParse.isRequestGroup(WhatsAppParse.key("Eski ürün isteme"), true, "Ürün isteme"))
        assertFalse(WhatsAppParse.isRequestGroup(WhatsAppParse.key("Ürün istemeler"), true, "Ürün isteme"))
        deliver(whatsapp("ÜRÜN İSTEME telsiz 📦", true, Msg("Kasa 1", "0159 2000 39")))
    }

    @Test
    fun s15_listenerReconnectDoesNotDuplicate() {
        val n = group(Msg("Kasa 1", "0159 2000 39"), Msg("Kasa 2", "0171 3300 38"))
        deliver(n, expect = 2)
        // After a restart the watcher reads every notification still shown again.
        deliver(n, expect = 0)
        assertEquals(2, Requests.recent(app).size)
    }

    @Test
    fun s16_ourOwnRepliesAreNotNewRequests() {
        val r = deliver(group(Msg("Kasa 1", "0159 2000 39"))).single()
        assertTrue(status(r, Requests.Status.SENT))
        // WhatsApp shows the chat again with our answer in it (no sender = the phone's owner).
        val shown = group(Msg("Kasa 1", "0159 2000 39", r.time), Msg(null, written.single().second))
        deliver(shown, expect = 0)
        // Even if a phone showed it with our name.
        deliver(group(Msg("Sen", "✅ Gönderdim: 0159 2000 39")), expect = 0)
        assertEquals(1, Requests.recent(app).size)
    }

    @Test
    fun s17_rehearsalAnswersStayInTheRehearsalGroup() {
        // A real-group action is kept first: presentation must never write there.
        deliver(group(Msg("Kasa 1", "0159 2000 39")))
        app.getSharedPreferences("presentation", Context.MODE_PRIVATE).edit().putBoolean("on", true).commit()
        val n = Presentation.notification(app, "Reyon", "0171 3300 38", System.currentTimeMillis())
        val r = deliver(n, rehearsal = true).single()
        assertTrue(status(r, Requests.Status.SENT))
        Thread.sleep(800)
        assertTrue("nothing to the (fake) real group", written.isEmpty())
        val chat = Presentation.chat(app)
        assertEquals(Presentation.ME, chat.last().sender)
        assertTrue(chat.last().text.contains("0171 3300"))
    }

    private companion object {
        const val ACTION = "com.sweepscan.test.FAKE_WHATSAPP_REPLY"
        const val KEY = "reply"
    }
}
