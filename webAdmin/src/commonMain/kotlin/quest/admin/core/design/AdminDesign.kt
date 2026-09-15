package quest.admin.core.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import quest.ui.design.AdminTokens
import quest.ui.design.AdminType
import quest.ui.design.Palette
import quest.ui.design.ParentTheme
import quest.ui.design.parentFontFamily

/*
 * The admin panel's design system: the parent-mode visual language (Archivo, square corners, 2px ink rules, one red
 * accent, warm grey ground) laid out for wide screens. Every colour comes from [Palette], every size from
 * [AdminTokens], every text style from [AdminType] / the theme typography — nothing is hard-coded here.
 */

@Composable
fun AdminTheme(content: @Composable () -> Unit) = ParentTheme(rtl = false, content = content)

@Composable
private fun family() = parentFontFamily(rtl = false)

@Composable fun titleStyle() = AdminType.title(family())
@Composable fun descriptionStyle() = AdminType.description(family())
@Composable fun inputStyle() = AdminType.input(family())
@Composable fun labelStyle() = AdminType.label(family())
@Composable fun cardTitleStyle() = AdminType.cardTitle(family())
@Composable fun monoStyle() = AdminType.mono(family())

// ---------------------------------------------------------------- shell: fixed nav + page slot
object AdminNav { val items = listOf("lessons" to "Lessons", "calendar" to "Calendar", "new" to "New lesson", "cache" to "AI cache", "usage" to "Usage") }

@Composable
fun AdminShell(current: String, email: String?, apiBaseUrl: String, onNavigate: (String) -> Unit, onSignOut: () -> Unit, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(AdminTokens.navWidth).fillMaxHeight().background(Palette.parentSurface)) {
            Column(Modifier.padding(AdminTokens.gutter)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AdminTokens.gutter / 2)) {
                    Box(Modifier.size(AdminTokens.logoSize).background(Palette.parentAccent))
                    Text("Homework Quest", style = MaterialTheme.typography.titleMedium, color = Palette.parentInk, maxLines = 1)
                }
                Spacer(Modifier.height(AdminTokens.gutter / 3))
                Text("ADMIN", style = labelStyle(), color = Palette.parentInkSoft)
            }
            Rule()
            Spacer(Modifier.height(AdminTokens.gutter / 2))
            Text("CONTENT", style = labelStyle(), color = Palette.parentInkSoft, modifier = Modifier.padding(horizontal = AdminTokens.gutter, vertical = AdminTokens.gutter / 3))
            AdminNav.items.forEach { (route, label) ->
                val selected = current == route || (route == "lessons" && current.startsWith("lesson/"))
                NavItem(label, selected) { onNavigate(route) }
            }
            Spacer(Modifier.weight(1f))
            Rule(thin = true)
            Column(Modifier.padding(AdminTokens.gutter)) {
                Text(email ?: "", style = MaterialTheme.typography.bodySmall, color = Palette.parentInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(apiBaseUrl.removePrefix("https://"), style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(AdminTokens.gutter / 2))
                SecondaryButton("Sign out", onSignOut, Modifier.fillMaxWidth())
            }
        }
        Box(Modifier.width(AdminTokens.rule).fillMaxHeight().background(Palette.parentInk))
        Box(Modifier.weight(1f).fillMaxHeight().background(Palette.parentBg)) { content() }
    }
}

@Composable
private fun NavItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Row(Modifier.fillMaxWidth().height(AdminTokens.buttonHeight).background(if (hovered && !selected) Palette.parentAccentSoft else Color.Transparent).hoverable(src).clickable(src, null, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(AdminTokens.selectedBorder).fillMaxHeight().background(if (selected) Palette.parentAccent else Color.Transparent))
        Spacer(Modifier.width(AdminTokens.gutter - AdminTokens.selectedBorder))
        Text(label, style = AdminType.navItem(family()), color = if (selected) Palette.parentAccent else Palette.parentInk)
    }
}

// ---------------------------------------------------------------- page template: header (fixed) → scrolling content → sticky footer
/**
 * Every screen uses this. The header and the optional [footer] never move; only [content] scrolls (a Column in a
 * `verticalScroll`, so screens must not nest another vertical scroll inside it — long lists go through [LazyPage]).
 */
@Composable
fun Page(title: String, description: String? = null, breadcrumb: String? = null, actions: @Composable RowScope.() -> Unit = {}, footer: (@Composable RowScope.() -> Unit)? = null, scroll: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PageHeader(title, description, breadcrumb, actions)
        if (scroll) Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = AdminTokens.contentMaxWidth).fillMaxWidth().padding(horizontal = AdminTokens.pagePadding, vertical = AdminTokens.gutter), content = content)
        }
        // scroll = false: the content fills the space between header and footer and owns its scroll regions (e.g. list + pinned phone)
        else Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = AdminTokens.pagePadding).padding(top = AdminTokens.gutter), content = content)
        if (footer != null) StickyFooter(footer)
    }
}

/** Small solid triangle (reorder arrows) — drawn, so it never depends on a font glyph. */
@Composable
fun Arrow(up: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Box(Modifier.size(AdminTokens.gutter).hoverable(src, enabled).clickable(src, null, enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        val color = if (!enabled) Palette.parentRule else if (hovered) Palette.parentAccent else Palette.parentInk
        Canvas(Modifier.size(AdminTokens.gutter / 2)) {
            val path = androidx.compose.ui.graphics.Path()
            if (up) { path.moveTo(size.width / 2, 0f); path.lineTo(size.width, size.height); path.lineTo(0f, size.height) }
            else { path.moveTo(0f, 0f); path.lineTo(size.width, 0f); path.lineTo(size.width / 2, size.height) }
            path.close(); drawPath(path, color)
        }
    }
}

/** Same template with a lazy list as the scrolling part (lessons, cache entries…). */
@Composable
fun LazyPage(title: String, description: String? = null, breadcrumb: String? = null, actions: @Composable RowScope.() -> Unit = {}, footer: (@Composable RowScope.() -> Unit)? = null, list: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PageHeader(title, description, breadcrumb, actions)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.widthIn(max = AdminTokens.contentMaxWidth).fillMaxWidth(), contentPadding = PaddingValues(horizontal = AdminTokens.pagePadding, vertical = AdminTokens.gutter), content = list)
        }
        if (footer != null) StickyFooter(footer)
    }
}

@Composable
private fun PageHeader(title: String, description: String?, breadcrumb: String?, actions: @Composable RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Palette.parentBg)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = AdminTokens.contentMaxWidth).fillMaxWidth().padding(horizontal = AdminTokens.pagePadding).padding(top = AdminTokens.pagePadding, bottom = AdminTokens.gutter / 2)) {
                if (breadcrumb != null) { Text(breadcrumb.uppercase(), style = labelStyle(), color = Palette.parentAccent); Spacer(Modifier.height(AdminTokens.gutter / 3)) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AdminTokens.gutter / 2)) {
                    Text(title, style = titleStyle(), color = Palette.parentInk, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    actions()
                }
                if (description != null) { Spacer(Modifier.height(AdminTokens.gutter / 6)); Text(description, style = descriptionStyle(), color = Palette.parentInkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
        Rule()
    }
}

@Composable
private fun StickyFooter(content: @Composable RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Palette.parentSurface)) {
        Rule()
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Row(Modifier.widthIn(max = AdminTokens.contentMaxWidth).fillMaxWidth().padding(horizontal = AdminTokens.pagePadding, vertical = AdminTokens.gutter / 2), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AdminTokens.gutter / 2), content = content)
        }
    }
}

@Composable
fun Rule(thin: Boolean = false) { Box(Modifier.fillMaxWidth().height(if (thin) AdminTokens.ruleThin else AdminTokens.rule).background(if (thin) Palette.parentRule else Palette.parentInk)) }

@Composable
fun SectionLabel(text: String) { Text(text.uppercase(), style = labelStyle(), color = Palette.parentInkSoft, modifier = Modifier.padding(bottom = AdminTokens.gutter / 3)) }

@Composable
fun Card(modifier: Modifier = Modifier, padding: Dp = AdminTokens.gutter, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().background(Palette.parentSurface).border(AdminTokens.rule, Palette.parentInk).padding(padding), content = content)
}

// ---------------------------------------------------------------- buttons: primary (ink), secondary (outline), destructive (red text); 44px; label flush left when wide
enum class ButtonKind { PRIMARY, SECONDARY, DESTRUCTIVE, ACCENT }

@Composable
fun AdminButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, kind: ButtonKind = ButtonKind.PRIMARY, wide: Boolean = false) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState(); val pressed by src.collectIsPressedAsState()
    val fill = when {
        !enabled -> if (kind == ButtonKind.PRIMARY || kind == ButtonKind.ACCENT) Palette.parentRule else Palette.parentSurface
        kind == ButtonKind.PRIMARY -> if (pressed) Palette.parentAccent else if (hovered) Palette.parentInkSoft else Palette.parentInk
        kind == ButtonKind.ACCENT -> if (pressed || hovered) Palette.parentInk else Palette.parentAccent
        else -> if (pressed) Palette.parentAccentSoft else if (hovered) Palette.parentBg else Palette.parentSurface
    }
    val ink = when {
        !enabled -> if (kind == ButtonKind.PRIMARY || kind == ButtonKind.ACCENT) Palette.parentSurface else Palette.parentDisabled
        kind == ButtonKind.PRIMARY || kind == ButtonKind.ACCENT -> Palette.parentSurface
        kind == ButtonKind.DESTRUCTIVE -> Palette.parentAccent
        else -> Palette.parentInk
    }
    val borderColor = if (kind == ButtonKind.PRIMARY || kind == ButtonKind.ACCENT) fill else if (!enabled) Palette.parentRule else Palette.parentInk
    Box(modifier.height(AdminTokens.buttonHeight).then(if (wide) Modifier.fillMaxWidth() else Modifier).background(fill).border(AdminTokens.rule, borderColor)
        .hoverable(src, enabled).clickable(src, null, enabled = enabled, onClick = onClick).padding(horizontal = AdminTokens.gutter * 2 / 3), contentAlignment = if (wide) Alignment.CenterStart else Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) = AdminButton(text, onClick, modifier, enabled, ButtonKind.SECONDARY)
@Composable fun DestructiveButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) = AdminButton(text, onClick, modifier, enabled, ButtonKind.DESTRUCTIVE)

/** Small inline text action (table rows, "+ Add"). */
@Composable
fun LinkButton(text: String, onClick: () -> Unit, enabled: Boolean = true, color: Color = Palette.parentAccent) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Text(text, Modifier.hoverable(src, enabled).clickable(src, null, enabled = enabled, onClick = onClick).padding(vertical = AdminTokens.gutter / 6),
        style = MaterialTheme.typography.labelLarge, color = if (!enabled) Palette.parentDisabled else if (hovered) Palette.parentInk else color)
}

// ---------------------------------------------------------------- fields: label above, 17px, 2px border, accent on focus, red band errors
@Composable
fun Field(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, singleLine: Boolean = true, minLines: Int = 1, error: String? = null, placeholder: String? = null, password: Boolean = false, mono: Boolean = false, enabled: Boolean = true, onSubmit: (() -> Unit)? = null) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Palette.parentInk, modifier = Modifier.padding(bottom = AdminTokens.gutter / 4))
        val border = when { error != null -> Palette.parentAccent; focused -> Palette.parentAccent; !enabled -> Palette.parentRule; else -> Palette.parentInk }
        Box(Modifier.fillMaxWidth().heightIn(min = if (singleLine) AdminTokens.inputHeight else AdminTokens.inputHeight * minLines / 2 + AdminTokens.gutter).background(if (enabled) Palette.parentSurface else Palette.parentBg).border(AdminTokens.rule, border).padding(horizontal = AdminTokens.gutter / 2, vertical = if (singleLine) 0.dp else AdminTokens.gutter / 2), contentAlignment = Alignment.CenterStart) {
            BasicTextField(value, onChange, Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }, enabled = enabled, singleLine = singleLine, minLines = minLines,
                textStyle = (if (mono) monoStyle() else inputStyle()).copy(color = Palette.parentInk), cursorBrush = SolidColor(Palette.parentAccent),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = if (onSubmit != null) KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done) else KeyboardOptions.Default,
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onSubmit?.invoke() }),
                decorationBox = { inner -> Box { if (value.isEmpty() && placeholder != null) Text(placeholder, style = inputStyle(), color = Palette.parentDisabled); inner() } })
        }
        if (error != null) { Spacer(Modifier.height(AdminTokens.gutter / 4)); ErrorBand(error) }
    }
}

@Composable
fun ErrorBand(message: String, onDismiss: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().background(Palette.parentAccentSoft).border(AdminTokens.rule, Palette.parentAccent).padding(horizontal = AdminTokens.gutter / 2, vertical = AdminTokens.gutter / 3), verticalAlignment = Alignment.CenterVertically) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Palette.parentInk, modifier = Modifier.weight(1f))
        if (onDismiss != null) LinkButton("Dismiss", onDismiss)
    }
}

@Composable
fun ErrorBanner(message: String?, onDismiss: (() -> Unit)? = null) {
    if (message == null) return
    ErrorBand(message, onDismiss)
    Spacer(Modifier.height(AdminTokens.gutter / 2))
}

/** A quiet confirmation (never a toast): ink band that the user dismisses. */
@Composable
fun NoticeBand(message: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Palette.parentSurface).border(AdminTokens.rule, Palette.parentInk).padding(horizontal = AdminTokens.gutter / 2, vertical = AdminTokens.gutter / 3), verticalAlignment = Alignment.CenterVertically) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Palette.parentInk, modifier = Modifier.weight(1f))
        LinkButton("OK", onDismiss)
    }
    Spacer(Modifier.height(AdminTokens.gutter / 2))
}

// ---------------------------------------------------------------- chips / choices
@Composable
fun Choice(options: List<Pair<String, String>>, selected: String?, enabled: Boolean = true, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(AdminTokens.gutter / 3)) {
        options.forEach { (value, label) ->
            val on = value == selected
            val src = remember { MutableInteractionSource() }
            val hovered by src.collectIsHoveredAsState()
            Box(Modifier.height(AdminTokens.buttonHeight - AdminTokens.gutter / 3).border(AdminTokens.rule, if (on) Palette.parentAccent else if (!enabled) Palette.parentRule else Palette.parentInk)
                .background(if (on) Palette.parentAccentSoft else if (hovered) Palette.parentBg else Palette.parentSurface).hoverable(src, enabled).clickable(src, null, enabled = enabled) { onSelect(value) }.padding(horizontal = AdminTokens.gutter / 2), contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) Palette.parentAccent else if (!enabled) Palette.parentDisabled else Palette.parentInk)
            }
        }
    }
}

@Composable
fun Tag(text: String, color: Color = Palette.parentAccentSoft, textColor: Color = Palette.parentInk) {
    Text(text, Modifier.background(color).padding(horizontal = AdminTokens.gutter / 3, vertical = AdminTokens.gutter / 8), style = MaterialTheme.typography.bodySmall, color = textColor, fontWeight = FontWeight.SemiBold)
}

/** Status as a plain word, right-aligned in rows: Draft / Published / Reading… — never a coloured chip. */
fun statusWord(status: String): String = when (status) {
    "published" -> "Published"; "review" -> "Draft"; "needs_review" -> "Draft"; "draft" -> "Draft"
    "uploading" -> "Uploading…"; "analyzing" -> "Reading…"; "generating" -> "Writing…"; "error" -> "Error"; else -> status
}

// ---------------------------------------------------------------- selection cards (curriculum, grade, source)
@Composable
fun SelectCard(title: String, subtitle: String? = null, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = AdminTokens.courseCard, glyph: String? = null, enabled: Boolean = true) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Column(modifier.size(size).background(if (selected) Palette.parentAccentSoft else if (hovered) Palette.parentBg else Palette.parentSurface)
        .border(if (selected) AdminTokens.selectedBorder else AdminTokens.rule, if (selected) Palette.parentAccent else if (!enabled) Palette.parentRule else Palette.parentInk)
        .hoverable(src, enabled).clickable(src, null, enabled = enabled, onClick = onClick).padding(AdminTokens.gutter / 2), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        if (glyph != null) { Text(glyph, style = titleStyle(), color = Palette.parentInk); Spacer(Modifier.height(AdminTokens.gutter / 4)) }
        Text(title, style = if (size < AdminTokens.courseCard) MaterialTheme.typography.labelLarge else cardTitleStyle(), color = if (!enabled) Palette.parentDisabled else Palette.parentInk, textAlign = TextAlign.Center, maxLines = 2)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft, textAlign = TextAlign.Center)
    }
}

// ---------------------------------------------------------------- tables: 56px rows, hover tint, right-aligned numbers
data class Col(val title: String, val width: Dp? = null, val numeric: Boolean = false)

@Composable
fun TableHeader(cols: List<Col>) {
    Row(Modifier.fillMaxWidth().height(AdminTokens.buttonHeight).padding(horizontal = AdminTokens.gutter / 2), verticalAlignment = Alignment.CenterVertically) {
        cols.forEach { c -> Text(c.title.uppercase(), if (c.width == null) Modifier.weight(1f) else Modifier.width(c.width), style = labelStyle(), color = Palette.parentInkSoft, textAlign = if (c.numeric) TextAlign.End else TextAlign.Start) }
    }
    Rule()
}

@Composable
fun TableRow(onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Column {
        Row(Modifier.fillMaxWidth().height(AdminTokens.rowHeight).background(if (hovered && onClick != null) Palette.parentAccentSoft else Palette.parentSurface).hoverable(src, onClick != null)
            .then(if (onClick != null) Modifier.clickable(src, null, onClick = onClick) else Modifier).padding(horizontal = AdminTokens.gutter / 2), verticalAlignment = Alignment.CenterVertically, content = content)
        Rule(thin = true)
    }
}

@Composable
fun RowScope.Cell(text: String, col: Col, color: Color = Palette.parentInk, weight: FontWeight = FontWeight.Normal) {
    Text(text, if (col.width == null) Modifier.weight(1f) else Modifier.width(col.width), style = MaterialTheme.typography.bodyMedium, color = color, fontWeight = weight, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = if (col.numeric) TextAlign.End else TextAlign.Start)
}

// ---------------------------------------------------------------- empty state with the primary action inside
@Composable
fun EmptyState(title: String, description: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().background(Palette.parentSurface).border(AdminTokens.rule, Palette.parentRule).padding(AdminTokens.pagePadding), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = cardTitleStyle(), color = Palette.parentInk, textAlign = TextAlign.Center)
        Spacer(Modifier.height(AdminTokens.gutter / 3))
        Text(description, style = descriptionStyle(), color = Palette.parentInkSoft, textAlign = TextAlign.Center)
        if (action != null && onAction != null) { Spacer(Modifier.height(AdminTokens.gutter)); AdminButton(action, onAction) }
    }
}

// ---------------------------------------------------------------- loading: square progress bar + spinning accent arc (design doc), never a browser spinner
@Composable
fun Loading(label: String = "Loading…") {
    Column(Modifier.fillMaxWidth().padding(vertical = AdminTokens.pagePadding), horizontalAlignment = Alignment.CenterHorizontally) {
        Spinner()
        Spacer(Modifier.height(AdminTokens.gutter / 2))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Palette.parentInkSoft)
    }
}

@Composable
fun Spinner(size: Dp = AdminTokens.spinner) {
    val t = rememberInfiniteTransition()
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart))
    Canvas(Modifier.size(size).rotate(angle)) {
        val stroke = AdminTokens.rule.toPx() * 1.5f
        drawArc(Palette.parentRule, 0f, 360f, false, style = Stroke(stroke))
        drawArc(Palette.parentAccent, 0f, 100f, false, style = Stroke(stroke))
    }
}

/** Indeterminate square bar: an accent block sweeping a light rule. */
@Composable
fun ProgressBar(label: String, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition()
    val x by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart))
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AdminTokens.gutter / 2)) {
            Spinner(AdminTokens.spinner * 2 / 3)
            Text(label, style = MaterialTheme.typography.labelLarge, color = Palette.parentInk)
        }
        Spacer(Modifier.height(AdminTokens.gutter / 3))
        Canvas(Modifier.fillMaxWidth().height(AdminTokens.progressBar)) {
            drawRect(Palette.parentRule)
            val w = size.width * 0.25f
            drawRect(Palette.parentAccent, Offset((size.width + w) * x - w, 0f), Size(w, size.height))
        }
    }
}

/** Non-blocking "busy" bar used at the top of a page while a job runs. */
@Composable
fun BusyBar(label: String?) { if (label != null) { ProgressBar(label); Spacer(Modifier.height(AdminTokens.gutter / 2)) } }

// ---------------------------------------------------------------- small helpers
@Composable fun Gap(n: Int = 1) = Spacer(Modifier.height(AdminTokens.gutter / 3 * n))
@Composable fun RowScope.GapW(n: Int = 1) = Spacer(Modifier.width(AdminTokens.gutter / 3 * n))

fun Long.tokens(): String = when { this >= 1_000_000 -> "${this / 100_000 / 10.0}M"; this >= 1_000 -> "${this / 100 / 10.0}k"; else -> toString() }

@Composable
fun BoxScope.Overlay(visible: Boolean, content: @Composable () -> Unit) { if (visible) Box(Modifier.matchParentSize().background(Palette.parentInk.copy(alpha = 0.35f)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) { content() } }

@Composable
fun Dimmed(dim: Boolean, content: @Composable () -> Unit) { Box(Modifier.alpha(if (dim) 0.5f else 1f)) { content() } }

/** Thin outline used around pickers/drop zones. */
fun Modifier.dashedOutline(color: Color = Palette.parentInk): Modifier = this.border(BorderStroke(AdminTokens.rule, color))
