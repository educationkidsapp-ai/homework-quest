package quest.admin.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import quest.ui.design.Palette
import quest.ui.design.ParentTheme

/** Admin = parent visual language (Archivo, square corners, red accent) on wide screens. */
@Composable
fun AdminTheme(content: @Composable () -> Unit) = ParentTheme(rtl = false, content = content)

object AdminNav { val items = listOf("lessons" to "Lessons", "calendar" to "Calendar", "cache" to "AI cache", "usage" to "Usage") }

@Composable
fun AdminShell(current: String, email: String?, apiBaseUrl: String, onNavigate: (String) -> Unit, onSignOut: () -> Unit, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(220.dp).fillMaxHeight().background(Palette.parentSurface).border(1.dp, Palette.parentRule).padding(20.dp)) {
            Text("Homework Quest", style = MaterialTheme.typography.titleLarge, color = Palette.parentInk)
            Text("Admin", style = MaterialTheme.typography.bodySmall, color = Palette.parentAccent, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(28.dp))
            AdminNav.items.forEach { (route, label) ->
                val selected = current.startsWith(route)
                Row(Modifier.fillMaxWidth().background(if (selected) Palette.parentAccentSoft else Color.Transparent).clickable { onNavigate(route) }.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(label, color = if (selected) Palette.parentAccent else Palette.parentInk, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(email ?: "", style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft)
            Text(apiBaseUrl, style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft)
            Spacer(Modifier.height(8.dp))
            AdminOutlinedButton("Sign out", onSignOut)
        }
        Box(Modifier.weight(1f).fillMaxHeight()) { content() }
    }
}

@Composable
fun Page(title: String, actions: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = Palette.parentInk, modifier = Modifier.weight(1f))
            actions()
        }
        Spacer(Modifier.height(20.dp))
        content()
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().background(Palette.parentSurface).border(1.dp, Palette.parentLine).padding(20.dp), content = content)
}

@Composable
fun SectionTitle(text: String) { Text(text, style = MaterialTheme.typography.titleMedium, color = Palette.parentInk, modifier = Modifier.padding(bottom = 10.dp)) }

@Composable
fun AdminButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick, modifier, enabled = enabled, colors = ButtonDefaults.buttonColors(containerColor = Palette.parentAccent, contentColor = Color.White)) { Text(text) }
}

@Composable
fun AdminOutlinedButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(onClick, modifier, enabled = enabled, colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.parentInk)) { Text(text) }
}

@Composable
fun Field(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, singleLine: Boolean = true, minLines: Int = 1, error: String? = null) {
    OutlinedTextField(value, onChange, modifier.fillMaxWidth(), label = { Text(label) }, singleLine = singleLine, minLines = minLines, isError = error != null, supportingText = error?.let { { Text(it, color = Palette.parentAccent) } })
}

@Composable
fun Tag(text: String, color: Color = Palette.parentAccentSoft, textColor: Color = Palette.parentInk) {
    Text(text, Modifier.background(color).padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.bodySmall, color = textColor, fontWeight = FontWeight.SemiBold)
}

@Composable
fun StatusTag(status: String) {
    val (label, color) = when (status) {
        "draft" -> "Draft" to Palette.parentRule
        "uploading", "analyzing", "generating" -> status.replaceFirstChar { it.uppercase() } + "…" to Palette.sand
        "needs_review" -> "Confirm skills" to Palette.sun
        "review" -> "Review" to Palette.sea
        "published" -> "Published" to Palette.mint
        "error" -> "Error" to Palette.coral
        else -> status to Palette.parentRule
    }
    Tag(label, color)
}

@Composable
fun Loading() { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.parentAccent) } }

@Composable
fun ErrorBanner(message: String?, onDismiss: (() -> Unit)? = null) {
    if (message == null) return
    Row(Modifier.fillMaxWidth().background(Palette.parentAccentSoft).border(1.dp, Palette.parentAccent).padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(message, color = Palette.parentInk, modifier = Modifier.weight(1f))
        if (onDismiss != null) Text("×", Modifier.clickable(onClick = onDismiss).padding(horizontal = 8.dp), color = Palette.parentAccent)
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
fun Choice(options: List<Pair<String, String>>, selected: String?, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            Text(label, Modifier.border(1.dp, if (on) Palette.parentAccent else Palette.parentRule).background(if (on) Palette.parentAccentSoft else Palette.parentSurface).clickable { onSelect(value) }.padding(horizontal = 14.dp, vertical = 8.dp),
                color = if (on) Palette.parentAccent else Palette.parentInk, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

fun Long.tokens(): String = when { this >= 1_000_000 -> "${this / 100_000 / 10.0}M"; this >= 1_000 -> "${this / 100 / 10.0}k"; else -> toString() }
