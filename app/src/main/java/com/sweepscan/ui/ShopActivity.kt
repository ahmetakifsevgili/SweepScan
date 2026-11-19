package com.sweepscan.ui

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sweepscan.R
import com.sweepscan.request.RequestParser
import com.sweepscan.request.Requests
import com.sweepscan.server.AppUpdate
import com.sweepscan.server.ShopClient
import com.sweepscan.ui.shop.Hint
import com.sweepscan.ui.shop.Update
import com.sweepscan.ui.shop.Link
import com.sweepscan.ui.shop.ShopEvent
import com.sweepscan.ui.shop.ShopRequest
import com.sweepscan.ui.shop.ShopState
import com.sweepscan.ui.shop.ShopViewModel
import com.sweepscan.ui.theme.EmptyNote
import com.sweepscan.ui.theme.IconAction
import com.sweepscan.ui.theme.LocalStatusColors
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
 * The shop floor's screen: ask the depot for a shoe and see it come. Scan the shoe's label, say or
 * type its code and size; the depot phone gets a notification with where it is, and this screen
 * shows the request going from "received" to "looking" to "sent" (or "not in stock").
 */
class ShopActivity : AppCompatActivity() {

    private val model: ShopViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SweepTheme {
                ShopScreen(model, onDepotMode = {
                    ShopClient.setShopPhone(this, false)
                    startActivity(Intent(this, HomeActivity::class.java))
                    finish()
                })
            }
        }
    }
}

@Composable
private fun ShopScreen(model: ShopViewModel, onDepotMode: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    var code by rememberSaveable { mutableStateOf("") }

    LifecycleResumeEffect(Unit) {
        model.start()
        onPauseOrDispose { model.stop() }
    }
    LaunchedEffect(Unit) {
        model.events.collect { e ->
            when (e) {
                ShopEvent.Sent -> {
                    code = ""
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    Toast.makeText(context, R.string.shop_sent, Toast.LENGTH_SHORT).show()
                }
                is ShopEvent.Failed -> Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
                ShopEvent.AllowInstall -> {
                    Toast.makeText(context, R.string.update_allow, Toast.LENGTH_LONG).show()
                    runCatching { context.startActivity(AppUpdate.allowIntent(context)) }
                }
                is ShopEvent.Changed -> {
                    val msg = when (e.status) {
                        Requests.Status.SENT -> R.string.shop_now_sent
                        Requests.Status.MISSING -> R.string.shop_now_missing
                        Requests.Status.LOOKING -> R.string.shop_now_looking
                        else -> null
                    }
                    if (msg != null) {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        Toast.makeText(context, context.getString(msg, e.what), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    val scan = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringExtra(MainActivity.EXTRA_BARCODE)?.let { model.send(null, it) }
    }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty()
        val text = heard.firstNotNullOfOrNull { h -> RequestParser.parseSpoken(h)?.let { w -> listOfNotNull(w.model, w.size).joinToString(" ") } }
            ?: heard.firstOrNull() ?: return@rememberLauncherForActivityResult
        code = text
        model.typed(text)
        if (RequestParser.parse(text).isNotEmpty()) model.send(text, null)
    }

    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Edit?>(null) }

    SweepScreen(
        title = stringResource(R.string.shop_title),
        actions = {
            IconAction(R.drawable.ic_settings, stringResource(R.string.settings)) { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.shop_set_name, state.name)) }, onClick = { menu = false; editing = Edit.NAME })
                DropdownMenuItem(text = { Text(stringResource(R.string.shop_set_address, state.address ?: "—")) }, onClick = { menu = false; editing = Edit.ADDRESS })
                DropdownMenuItem(text = { Text(stringResource(R.string.shop_set_pin)) }, onClick = { menu = false; model.reenterPin() })
                DropdownMenuItem(text = { Text(stringResource(R.string.shop_set_depot_mode)) }, onClick = { menu = false; onDepotMode() })
            }
        },
    ) {
        item { LinkPill(state) { model.connect(force = true) } }
        state.update?.let { u -> item { UpdateCard(u, model::startUpdate) } }
        item {
            AskCard(
                code = code,
                state = state,
                onCode = { code = it; model.typed(it) },
                onScan = { scan.launch(MainActivity.intent(context, MainActivity.Task.LOOKUP).putExtra(MainActivity.EXTRA_RETURN, true)) },
                onSay = {
                    val ask = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.shop_say_prompt))
                    runCatching { speech.launch(ask) }.onFailure { Toast.makeText(context, R.string.voice_unavailable, Toast.LENGTH_LONG).show() }
                },
                onSend = { model.send(code.trim(), null) },
            )
        }
        item { SectionLabel(stringResource(R.string.shop_mine)) }
        if (state.requests.isEmpty()) item { EmptyNote(stringResource(R.string.shop_empty)) }
        items(state.requests, key = { it.id }) { RequestCard(it) }
    }

    state.askPin?.let { address -> PinDialog(address, model::submitPin, model::dismissPin) }
    editing?.let { e ->
        TextDialog(
            title = stringResource(if (e == Edit.NAME) R.string.shop_name_title else R.string.shop_address_title),
            initial = if (e == Edit.NAME) state.name else state.address.orEmpty(),
            keyboard = if (e == Edit.NAME) KeyboardType.Text else KeyboardType.Uri,
            onDone = { if (e == Edit.NAME) model.setName(it) else model.setAddress(it); editing = null },
            onDismiss = { editing = null },
        )
    }
}

private enum class Edit { NAME, ADDRESS }

@Composable
private fun LinkPill(state: ShopState, onRetry: () -> Unit) {
    val (text, color) = when (state.link) {
        Link.CONNECTED -> stringResource(R.string.shop_connected, state.address?.substringBefore(':').orEmpty()) to Palette.Green
        Link.SEARCHING -> stringResource(R.string.shop_searching) to Palette.TextSoft
        Link.NOT_FOUND -> stringResource(R.string.shop_not_found) to Palette.TextSoft
        Link.LOST -> stringResource(R.string.shop_lost) to Palette.Red
    }
    Row(
        Modifier
            .clip(CircleShape)
            .then(if (state.link == Link.CONNECTED) Modifier else Modifier.clickable(onClick = onRetry))
            .padding(vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(Space.s))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun UpdateCard(u: Update, onUpdate: () -> Unit) {
    SweepCard {
        Column(Modifier.padding(Space.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.update_title, u.version), style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (u.progress != null) stringResource(R.string.update_progress, u.progress) else stringResource(R.string.update_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.TextSoft,
                    )
                }
                Spacer(Modifier.width(Space.m))
                Button(onClick = onUpdate, enabled = u.progress == null) { Text(stringResource(R.string.update_button)) }
            }
            if (u.progress != null) {
                Spacer(Modifier.height(Space.m))
                LinearProgressIndicator(progress = { u.progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun AskCard(
    code: String, state: ShopState, onCode: (String) -> Unit, onScan: () -> Unit, onSay: () -> Unit, onSend: () -> Unit,
) {
    SweepCard {
        Column(Modifier.padding(Space.l)) {
            Text(stringResource(R.string.shop_ask), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(Space.m))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                Button(onClick = onScan, modifier = Modifier.weight(1f).height(64.dp)) {
                    Icon(painterResource(R.drawable.ic_camera), null)
                    Spacer(Modifier.width(Space.s))
                    Text(stringResource(R.string.shop_scan), style = MaterialTheme.typography.titleMedium)
                }
                FilledTonalButton(onClick = onSay, modifier = Modifier.weight(1f).height(64.dp)) {
                    Icon(painterResource(R.drawable.ic_mic), null)
                    Spacer(Modifier.width(Space.s))
                    Text(stringResource(R.string.shop_say), style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(Modifier.height(Space.m))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = code,
                    onValueChange = onCode,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text(stringResource(R.string.shop_code_label)) },
                    placeholder = { Text("0209 13243 46") },
                    textStyle = MaterialTheme.typography.titleMedium,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                )
                Spacer(Modifier.width(Space.s))
                Button(onClick = onSend, enabled = !state.sending, modifier = Modifier.height(56.dp)) {
                    Text(stringResource(R.string.shop_send))
                }
            }
            when (val h = state.hint) {
                is Hint.InDepot -> HintText(stringResource(R.string.shop_in_depot, h.where), Palette.Green)
                Hint.NotOnMap -> HintText(stringResource(R.string.shop_not_on_map), Palette.TextSoft)
                null -> Unit
            }
        }
    }
}

@Composable
private fun HintText(text: String, color: Color) {
    Text(text, Modifier.padding(top = Space.s), style = MaterialTheme.typography.bodyMedium, color = color)
}

@Composable
private fun RequestCard(r: ShopRequest) {
    val colors = LocalStatusColors.current
    val (label, color) = when (r.status) {
        Requests.Status.LOOKING -> R.string.shop_status_looking to colors.looking
        Requests.Status.SENT -> R.string.shop_status_sent to colors.sent
        Requests.Status.MISSING -> R.string.shop_status_missing to colors.missing
        Requests.Status.NEW -> R.string.shop_status_new to colors.new
    }
    val status = stringResource(label)
    SweepCard(accent = color, modifier = Modifier.semantics { contentDescription = "${r.what}, $status" }) {
        Row(Modifier.padding(Space.l), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.what, style = MaterialTheme.typography.titleMedium)
                val time = SimpleDateFormat("HH:mm", Locale.forLanguageTag("tr")).format(Date(r.time))
                Text(
                    "$time · " + r.where.ifEmpty { stringResource(R.string.shop_where_unknown) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusPill(status, color)
        }
    }
}

@Composable
private fun PinDialog(address: String, onPin: (String) -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shop_pin_title)) },
        text = {
            Column {
                Text(stringResource(R.string.shop_pin_body, address.substringBefore(':')))
                Spacer(Modifier.height(Space.m))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(6) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.headlineSmall,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (pin.length >= 4) onPin(pin) }),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onPin(pin) }, enabled = pin.length >= 4) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
fun TextDialog(title: String, initial: String, keyboard: KeyboardType, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = keyboard, capitalization = KeyboardCapitalization.Words),
            )
        },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onDone(text) }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
