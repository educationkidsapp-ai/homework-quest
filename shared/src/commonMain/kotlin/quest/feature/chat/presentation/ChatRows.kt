package quest.feature.chat.presentation

import quest.core.text.isolate
import quest.ui.design.DashboardTokens
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import quest.api.dto.ChatStaffRole
import quest.api.dto.ChatThread
import quest.api.dto.ChatThreadStatus
import quest.api.dto.ChatTopic
import quest.feature.parent.presentation.Chip
import quest.feature.parent.presentation.ParentCard
import quest.feature.parent.presentation.Strings
import quest.ui.design.Dimens

/**
 * R8: how a parent's thread row reads once the dashboard side can be a coordinator as well as a teacher (DR3).
 *
 * The three R4 fields are all a row needs: [ChatThread.staffRole] picks the word under the name, [ChatThread.topic]
 * adds the Complaint badge, and [ChatThread.status] the Open/Resolved chip. Every one of them has a contract default,
 * so a row from a server that predates R4 reads exactly as it did in C3 — teacher, question, open, no chips.
 *
 * This lives outside a `*Screen.kt` on purpose: the thread list and the coordinator picker draw the same row, and
 * one spelling of "Subject coordinator · Math" is what keeps them from drifting apart.
 */

/**
 * The server's own `subject` word — or several, comma-joined, when a coordinator holds more than one — in the
 * parent's language. An unknown key passes through as written rather than being hidden.
 */
fun subjectLabel(subject: String, strings: Strings): String =
    subject.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        .joinToString(", ") { strings.subjectNames[it.lowercase()] ?: it }

/**
 * The word under the name: her role, then what she holds and where, when they are known.
 *
 * RM4 adds the third role. A manager speaks for a **department**, not a class — `GET /children/{id}/managers` fills
 * `className` with the child's section because that is the shape a thread row has, so [department] (the child's own
 * curriculum, which the caller knows) is what the row says instead: "Department manager · British". Without it the
 * section name is still better than nothing.
 */
fun staffLabel(role: ChatStaffRole, subject: String?, className: String?, strings: Strings, department: String? = null): String {
    val word = when (role) {
        ChatStaffRole.COORDINATOR -> strings.coordinatorRole
        ChatStaffRole.MANAGERIAL -> strings.managerRole
        ChatStaffRole.TEACHER -> strings.teacherRole
    }
    val detail = if (role == ChatStaffRole.MANAGERIAL) {
        listOfNotNull(department?.takeIf { it.isNotBlank() } ?: className?.takeIf { it.isNotBlank() })
    } else {
        listOfNotNull(
            subject?.takeIf { it.isNotBlank() }?.let { subjectLabel(it, strings) },
            className?.takeIf { it.isNotBlank() },
        )
    }
    return (listOf(word) + detail).joinToString(" · ")
}


/**
 * S1: the name a row shows. A thread the school administration holds carries a fixed English `teacherName` meant to be
 * localised, so the app writes its own words for it.
 */
fun staffName(thread: ChatThread, strings: Strings): String =
    if (thread.withAdmin == true) strings.schoolAdministration else thread.teacherName

fun staffLabel(thread: ChatThread, strings: Strings, department: String? = null): String =
    if (thread.withAdmin == true) thread.childName else
    staffLabel(thread.staffRole, thread.subject, thread.className, strings, department)

/**
 * MH4: the one-line preview under a thread. An attachment travels in the body as `[attachment:id:type:name:size]`
 * (`parseMessageBody`), which the conversation's bubble turns into a card — but the thread list printed the tag
 * verbatim. The same decoder is used here so the row reads `📎 report.png`, with the parent's own words after it
 * when the message carried both.
 */
fun threadPreview(body: String): String {
    val parsed = parseMessageBody(body)
    val attachment = parsed.attachment ?: return body
    return if (parsed.text.isBlank()) "📎 ${attachment.name}" else "📎 ${attachment.name} · ${parsed.text}"
}

/** Screen-reader copy for a row: who, what about, and where it stands — the chips say the same thing visually. */
fun threadDescription(thread: ChatThread, strings: Strings, department: String? = null): String = buildList {
    add(staffName(thread, strings))
    add(staffLabel(thread, strings, department))
    if (thread.topic == ChatTopic.COMPLAINT) add(strings.complaintBadge)
    if (thread.status == ChatThreadStatus.RESOLVED) add(strings.statusResolved)
    if (thread.unread > 0) add("${thread.unread} ${strings.messages}")
}.joinToString(", ")

/**
 * One tappable row. [showStatus] is false in the coordinator picker, where nothing has a status yet — a row with no
 * thread behind it is neither open nor resolved, and an "Open" chip on it would be a promise the server never made.
 */
@Composable
fun ChatThreadRow(
    thread: ChatThread,
    strings: Strings,
    onClick: () -> Unit,
    showStatus: Boolean = true,
    department: String? = null,
    modifier: Modifier = Modifier,
) {
    ParentCard(
        modifier = modifier.fillMaxWidth().padding(bottom = Dimens.s8)
            .clearAndSetSemantics { contentDescription = threadDescription(thread, strings, department) },
        onClick = onClick,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(CircleShape)
                    .background(
                        if (thread.staffRole != ChatStaffRole.TEACHER) DashboardTokens.bgSubtle
                        else MaterialTheme.colorScheme.primaryContainer,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = avatarInitial(staffName(thread, strings)),
                    style = MaterialTheme.typography.titleLarge,
                    color = DashboardTokens.ink,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.width(Dimens.s12))

            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text(
                        text = staffName(thread, strings),
                        style = MaterialTheme.typography.titleMedium,
                        color = DashboardTokens.ink,
                        fontWeight = FontWeight.SemiBold,
                    )
                    thread.lastMessage?.let {
                        Text(formatClock(it.createdAt), style = MaterialTheme.typography.bodySmall, color = DashboardTokens.inkSoft)
                    }
                }

                Text(
                    text = staffLabel(thread, strings, department),
                    style = MaterialTheme.typography.bodySmall,
                    color = DashboardTokens.inkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                thread.lastMessage?.body?.let { body ->
                    Spacer(Modifier.height(Dimens.s4))
                    Text(
                        text = isolate(threadPreview(body)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = DashboardTokens.inkSoft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                val badges = thread.topic == ChatTopic.COMPLAINT || (showStatus && thread.id != null) || thread.unread > 0
                if (badges) {
                    Spacer(Modifier.height(Dimens.s8))
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s8), verticalAlignment = Alignment.CenterVertically) {
                        if (thread.topic == ChatTopic.COMPLAINT) Chip(strings.complaintBadge, DashboardTokens.warningBg)
                        if (showStatus && thread.id != null) {
                            val resolved = thread.status == ChatThreadStatus.RESOLVED
                            Chip(
                                text = if (resolved) strings.statusResolved else strings.statusOpen,
                                color = if (resolved) DashboardTokens.successBg else MaterialTheme.colorScheme.primaryContainer,
                                selected = resolved,
                            )
                        }
                        if (thread.unread > 0) Chip("${thread.unread}", DashboardTokens.secondarySoft)
                    }
                }
            }
        }
    }
}

/**
 * The letter in the circle. "Ms. Lina" and "Mr. Omar" both start with M, so the honorific — anything ending in a full
 * stop — is skipped and the given name decides, which is what makes two coordinator rows tell each other apart.
 */
internal fun avatarInitial(name: String): String {
    val word = name.trim().split(' ').firstOrNull { it.isNotBlank() && !it.endsWith('.') }
    return (word ?: name.trim()).take(1).uppercase()
}

/** Local `HH:mm`, the same shape the conversation's bubbles use. */
internal fun formatClock(epochMillis: Long): String {
    val dt = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${dt.hour.toString().padStart(2, '0')}:${dt.minute.toString().padStart(2, '0')}"
}
