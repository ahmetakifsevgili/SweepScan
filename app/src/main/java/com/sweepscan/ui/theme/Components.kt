package com.sweepscan.ui.theme

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A screen: a title bar (back, title, actions) over a scrolling list, inside the system bars.
 * The list's items are added in [content].
 */
@Composable
fun SweepScreen(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    backDescription: String = "",
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = if (onBack == null) Space.l else Space.xs, end = Space.xs, top = Space.s, bottom = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(com.sweepscan.R.drawable.ic_back), backDescription, tint = MaterialTheme.colorScheme.onSurface)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            actions()
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Space.l, end = Space.l, bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.s),
            content = content,
        )
    } }
}

/** "TALEPLER", "VERİ": a small caps label above a group. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(java.util.Locale.forLanguageTag("tr")),
        modifier = modifier.padding(top = Space.l, bottom = Space.xs),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A rounded surface; clickable when [onClick] is given. [accent] draws a coloured bar on the left. */
@Composable
fun SweepCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    accent: Color? = null,
    container: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .then(
                if (onClick != null || onLongClick != null) {
                    Modifier.combinedClickableCompat(onClick ?: {}, onLongClick)
                } else Modifier,
            ),
        color = container,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            if (accent != null) Box(Modifier.width(6.dp).fillMaxHeight().background(accent))
            Box(Modifier.weight(1f)) { content() }
        }
    }
}

fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: (() -> Unit)?): Modifier =
    this.combinedClickable(role = Role.Button, onLongClick = onLongClick, onClick = onClick)

/** A small coloured pill with a word: request status, "kontrol et". */
@Composable
fun StatusPill(text: String, color: Color, onColor: Color = Palette.OnBrand) {
    Text(
        text,
        modifier = Modifier
            .clip(CircleShape)
            .background(color)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        color = onColor,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
    )
}

/** A settings-style row: title, a line under it, the whole row tappable. */
@Composable
fun SettingRow(title: String, subtitle: String?, @DrawableRes icon: Int? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = Space.m, horizontal = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(Space.l))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A number with its label: today's requests, the average time. */
@Composable
fun Kpi(value: String, label: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = Space.m, vertical = Space.s),
    ) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = color, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
    }
}

/** Muted text for an empty list. */
@Composable
fun EmptyNote(text: String) {
    Text(text, Modifier.padding(vertical = Space.m), style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
}

/** An icon button from the app's drawables. */
@Composable
fun IconAction(@DrawableRes icon: Int, description: String, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(painterResource(icon), description, tint = tint) }
}

/** The shape used for big task tiles. */
val TileShape = RoundedCornerShape(20.dp)
