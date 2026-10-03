package quest.feature.push.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import quest.core.platform.rememberNotificationPermission
import quest.feature.parent.presentation.ParentButton
import quest.feature.parent.presentation.ParentCard
import quest.feature.push.domain.PushPrompts
import quest.ui.design.DashboardTokens

/** M5: the words of push — the system channels and the parent home's card — in the app's two languages. */
data class PushStrings(
    val channelMessages: String = "Messages",
    val channelExams: String = "Exams",
    val channelHomework: String = "Homework",
    val channelComplaints: String = "Complaints",
    val channelSchoolNews: String = "School news",
    /** Shown only when a push arrives with no title at all. */
    val genericTitle: String = "News from the school",
    val cardTitle: String = "Turn on notifications",
    val cardBody: String = "Hear from the school when a teacher writes to you, a result is released or homework is set — even when the app is closed.",
    val turnOn: String = "Turn on",
    val notNow: String = "Not now",
) {
    companion object {
        val en = PushStrings()
        val ar = PushStrings(
            channelMessages = "الرسائل",
            channelExams = "الاختبارات",
            channelHomework = "الواجبات",
            channelComplaints = "الشكاوى",
            channelSchoolNews = "أخبار المدرسة",
            genericTitle = "جديد من المدرسة",
            cardTitle = "تفعيل الإشعارات",
            cardBody = "تصلك أخبار المدرسة عندما يراسلك المعلّم أو تُعلن نتيجة أو يُنشر واجب — حتى والتطبيق مغلق.",
            turnOn = "تفعيل",
            notNow = "ليس الآن",
        )

        fun forLanguage(code: String) = if (code == "ar") ar else en
    }
}

/**
 * M5: asked in context — on the parent home, after sign-in, with a line on what it is for — never at app start. The
 * card shows only where the platform asks before posting (Android 13+), only while not granted, and only until the
 * parent has answered once: "Not now" and a "no" in the system dialog are both final as far as the app is concerned.
 */
@Composable
fun PushPermissionCard(arabic: Boolean, modifier: Modifier = Modifier) {
    val prompts: PushPrompts = koinInject()
    val permission = rememberNotificationPermission()
    var ask by remember { mutableStateOf(false) }
    LaunchedEffect(permission) { ask = prompts.shouldAsk(permission.needed, permission.granted()) }
    if (!ask) return
    val scope = rememberCoroutineScope()
    PushPermissionCardContent(
        PushStrings.forLanguage(if (arabic) "ar" else "en"), modifier,
        onTurnOn = { scope.launch { permission.request(); prompts.answered(); ask = false } },
        onNotNow = { scope.launch { prompts.answered(); ask = false } },
    )
}

@Composable
fun PushPermissionCardContent(s: PushStrings, modifier: Modifier = Modifier, onTurnOn: () -> Unit, onNotNow: () -> Unit) {
    ParentCard(modifier.testTag("push-permission-card")) {
        Text(s.cardTitle, style = MaterialTheme.typography.titleMedium, color = DashboardTokens.inkStrong)
        Spacer(Modifier.height(4.dp))
        Text(s.cardBody, style = MaterialTheme.typography.bodyMedium, color = DashboardTokens.ink)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ParentButton(s.notNow, onNotNow, Modifier.weight(1f), primary = false)
            ParentButton(s.turnOn, onTurnOn, Modifier.weight(1f))
        }
    }
}
