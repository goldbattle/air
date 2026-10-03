package com.aria.air.ui.components

// Shared building blocks of every screen, in the style of iOS settings lists.

import com.aria.air.data.*
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.delay

/** Shows Android's date picker at [initial]; [onPicked] gets the day chosen. */
fun pickDate(ctx: Context, initial: LocalDate, onPicked: (LocalDate) -> Unit) =
    DatePickerDialog(ctx, { _, y, m, d -> onPicked(LocalDate.of(y, m + 1, d)) }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()

/** Shows Android's 12-hour time picker at [initial]; [onPicked] gets the time chosen. */
fun pickTime(ctx: Context, initial: LocalTime, onPicked: (LocalTime) -> Unit) =
    TimePickerDialog(ctx, { _, h, m -> onPicked(LocalTime.of(h, m)) }, initial.hour, initial.minute, false).show()

// ---------- Navigation ----------

/** iOS-style push/pop. [depth] decides the slide direction. */
@Composable
fun <T> Stack(state: T, depth: (T) -> Int, content: @Composable (T) -> Unit) = AnimatedContent(
    state,
    transitionSpec = {
        if (depth(targetState) >= depth(initialState)) {
            (slideInHorizontally(tween(320)) { it } + fadeIn(tween(200))) togetherWith
                (slideOutHorizontally(tween(320)) { -it / 4 } + fadeOut(tween(250)))
        } else {
            (slideInHorizontally(tween(320)) { -it / 4 } + fadeIn(tween(200))) togetherWith
                (slideOutHorizontally(tween(320)) { it } + fadeOut(tween(250)))
        }
    },
    label = "stack",
) { content(it) }

// ---------- Building blocks ----------

/**
 * A screen in the iOS style: a top bar with Back and actions, a big title that scrolls away with the content, and
 * pull-to-refresh when [onRefresh] is set. [content] fills a LazyColumn.
 */
@Composable
fun Page(
    title: String,
    onBack: (() -> Unit)? = null,
    backLabel: String = "Back",
    titleSize: TextUnit = 34.sp,
    actions: @Composable RowScope.() -> Unit = {},
    state: androidx.compose.foundation.lazy.LazyListState? = null,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) = Column(Modifier.fillMaxSize().background(C.bg).statusBarsPadding().imePadding()) {
    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) BackButton(backLabel, onBack)
        Spacer(Modifier.weight(1f))
        actions()
    }
    val list: @Composable () -> Unit = { LazyColumn(state = state ?: androidx.compose.foundation.lazy.rememberLazyListState(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
        item("title") {
            Text(
                title, fontSize = titleSize, fontWeight = FontWeight.Bold, color = C.label, lineHeight = titleSize * 1.15f,
                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
            )
        }
        content()
    } }
    if (onRefresh == null) list() else {
        // Pull down to refresh, like iOS lists.
        @OptIn(ExperimentalMaterial3Api::class)
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(refreshing, onRefresh, Modifier.fillMaxSize()) { list() }
    }
}

/** "‹ Back" in the top bar. */
@Composable
fun BackButton(label: String, onBack: () -> Unit, tint: Color = C.blue) = Row(
    Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onBack).padding(end = 10.dp, top = 8.dp, bottom = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Icon(Icons.Filled.ChevronLeft, null, tint = tint, modifier = Modifier.size(30.dp))
    Text(label, color = tint, fontSize = 17.sp, maxLines = 1)
}

/** Round tinted icon button for the top bar (the iOS "+" in a circle). */
@Composable
fun CircleButton(icon: ImageVector, description: String, onClick: () -> Unit) = Box(
    Modifier.padding(horizontal = 4.dp).size(36.dp).clip(CircleShape).background(C.fill).clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
) { Icon(icon, description, tint = C.blue, modifier = Modifier.size(22.dp)) }

/** A rounded card holding rows, like a section of an iOS grouped list. */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.card), content = content)

/** Small gray capitals above a [Group]. */
@Composable
fun SectionHeader(text: String) = Text(
    text.uppercase(), fontSize = 13.sp, color = C.secondary, letterSpacing = 0.3.sp,
    modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 7.dp),
)

/** Small gray text under a [Group], for explanations. */
@Composable
fun Footnote(text: String, color: Color = C.secondary) =
    Text(text, fontSize = 13.sp, color = color, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp))

/** The thin line between rows, inset from the left like iOS. */
@Composable
fun Hairline(inset: Dp = 16.dp) = Box(Modifier.padding(start = inset).fillMaxWidth().height(0.5.dp).background(C.separator))

/**
 * A row: optional icon, title and subtitle, a value on the right, and a chevron when it opens something. Tappable when
 * [onClick] is set.
 */
@Composable
fun Cell(
    title: String,
    value: String? = null,
    color: Color = C.label,
    icon: ImageVector? = null,
    iconTint: Color = C.blue,
    subtitle: String? = null,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
) = Row(
    Modifier.fillMaxWidth().heightIn(min = 48.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 16.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    if (icon != null) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
    }
    // Label and value share the row; a long value wraps within its half instead of crushing the label.
    Column(Modifier.weight(1f)) {
        Text(title, fontSize = 17.sp, color = color)
        if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = C.secondary)
    }
    if (value != null) Text(
        value, fontSize = 17.sp, color = C.secondary, textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 12.dp).weight(0.8f),
    )
    if (chevron) Icon(Icons.Filled.ChevronRight, null, tint = C.tertiary)
}

/** A row with an on/off switch, green when on. */
@Composable
fun SwitchCell(title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) = Row(
    Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Column(Modifier.weight(1f)) {
        Text(title, fontSize = 17.sp)
        if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = C.secondary)
    }
    Switch(
        checked, onChange,
        colors = SwitchDefaults.colors(
            checkedTrackColor = C.green, checkedThumbColor = Color.White, checkedBorderColor = Color.Transparent,
            uncheckedTrackColor = C.fill, uncheckedThumbColor = Color.White, uncheckedBorderColor = Color.Transparent,
        ),
    )
}

/** Label on the left, borderless input on the right, like an iOS settings row. */
@Composable
fun FieldCell(
    label: String,
    value: String,
    placeholder: String = "",
    secret: Boolean = false,
    caps: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    val focus = remember { FocusRequester() }
    val bring = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    // Re-run as the keyboard slides in (its height changes each frame), so the row ends up just above it.
    val ime = WindowInsets.ime.getBottom(LocalDensity.current)
    var size by remember { mutableStateOf(IntSize.Zero) }
    val below = with(LocalDensity.current) { 160.dp.toPx() } // room for the next field and the button
    LaunchedEffect(focused, ime) {
        if (focused) { delay(80); bring.bringIntoView(Rect(0f, 0f, size.width.toFloat(), size.height + below)) }
    }
    Row(
        // Tapping anywhere on the row types into it; once focused, the row scrolls clear of the keyboard.
        Modifier.fillMaxWidth().onSizeChanged { size = it }.bringIntoViewRequester(bring).clickable { focus.requestFocus() }
            .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 17.sp, color = C.label)
        Spacer(Modifier.width(16.dp))
        BasicTextField(
            value, onChange, singleLine = true,
            modifier = Modifier.weight(1f).focusRequester(focus)
                .onFocusEvent { focused = it.isFocused },
            textStyle = TextStyle(fontSize = 17.sp, color = C.secondary, textAlign = TextAlign.End), cursorBrush = SolidColor(C.blue),
            keyboardOptions = KeyboardOptions(
                capitalization = when {
                    caps -> KeyboardCapitalization.Characters
                    secret || keyboard != KeyboardType.Text -> KeyboardCapitalization.None
                    else -> KeyboardCapitalization.Words
                },
                autoCorrectEnabled = !secret && keyboard == KeyboardType.Text,
                keyboardType = if (secret) KeyboardType.Password else keyboard,
            ),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterEnd) {
                    if (value.isEmpty()) Text(placeholder, fontSize = 17.sp, color = C.tertiary, maxLines = 1)
                    inner()
                }
            },
        )
    }
}

/** A borderless multi-line text field with a placeholder. */
@Composable
fun TextArea(value: String, placeholder: String, minHeight: Dp = 88.dp, onChange: (String) -> Unit) = BasicTextField(
    value, onChange,
    modifier = Modifier.fillMaxWidth().heightIn(min = minHeight).padding(16.dp),
    textStyle = TextStyle(fontSize = 17.sp, color = C.label, lineHeight = 22.sp), cursorBrush = SolidColor(C.blue),
    decorationBox = { inner ->
        Box {
            if (value.isEmpty()) Text(placeholder, fontSize = 17.sp, color = C.tertiary)
            inner()
        }
    },
)

/** A rounded button: filled with [color], or [color] text on a card when not [filled]; dimmed when disabled. */
@Composable
fun Button(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = C.blue,
    filled: Boolean = true,
    enabled: Boolean = true,
    onClick: () -> Unit,
) = Row(
    modifier.heightIn(min = 50.dp).clip(RoundedCornerShape(14.dp))
        .background(if (filled) color.copy(alpha = if (enabled) 1f else 0.35f) else C.card)
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
) {
    val fg = if (filled) Color.White else color.copy(alpha = if (enabled) 1f else 0.35f)
    if (icon != null) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
    }
    Text(text, color = fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
}

/** A small tinted pill, for statuses and labels. */
@Composable
fun Badge(text: String, color: Color) = Text(
    text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
    modifier = Modifier.clip(CircleShape).background(color.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp),
)

/** An iOS segmented control: equal-width options, the selected one raised. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) = Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(C.fill).padding(2.dp),
) {
    options.forEachIndexed { i, o ->
        Box(
            Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(7.dp))
                .background(if (i == selected) C.raised else Color.Transparent).clickable { onSelect(i) },
            contentAlignment = Alignment.Center,
        ) {
            Text(o, fontSize = 13.sp, fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Medium, color = C.label)
        }
    }
}

/** Sideways-scrolling pills, for lists that don't fit a segmented control (years, friends, settings tabs). */
@Composable
fun Pills(options: List<String>, selected: Int, onSelect: (Int) -> Unit) = Row(
    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
) {
    options.forEachIndexed { i, name ->
        val on = i == selected
        Text(
            name, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
            color = if (on) Color.White else C.label,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) C.blue else C.fill)
                .clickable { onSelect(i) }.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** One-field edit dialog. Show it by setting the state to non-null. */
class EditRequest(val title: String, val value: String, val caps: Boolean = false, val onSave: (String) -> Unit)

/** The dialog for an [EditRequest]; nothing when [req] is null. */
@Composable
fun EditDialog(req: EditRequest?, onDismiss: () -> Unit) {
    if (req == null) return
    var text by remember(req) { mutableStateOf(req.value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(req.title) },
        text = {
            OutlinedTextField(
                text, { text = it }, singleLine = req.title != "Notes",
                keyboardOptions = KeyboardOptions(capitalization = if (req.caps) KeyboardCapitalization.Characters else KeyboardCapitalization.Sentences),
            )
        },
        confirmButton = { TextButton({ req.onSave(text.trim()); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
