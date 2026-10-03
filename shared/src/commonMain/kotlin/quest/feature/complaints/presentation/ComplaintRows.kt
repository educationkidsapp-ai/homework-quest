package quest.feature.complaints.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import quest.api.dto.ChatPeerRole
import quest.api.dto.ChatSender
import quest.api.dto.ChatStaffRole
import quest.api.dto.ChatThreadStatus
import quest.api.dto.Complaint
import quest.api.dto.ComplaintActor
import quest.api.dto.ComplaintEvent
import quest.core.text.isolate
import quest.feature.chat.presentation.avatarInitial
import quest.feature.chat.presentation.staffLabel
import quest.feature.chat.presentation.MessageAttachments
import quest.feature.chat.presentation.messagePreview
import quest.feature.complaints.domain.TimelineItem
import quest.feature.parent.presentation.Chip
import quest.feature.parent.presentation.ParentCard
import quest.feature.parent.presentation.Strings
import quest.ui.design.DashboardTokens
import quest.ui.design.Dimens

/** The staff role as Messages spells it, so "Subject coordinator · Math" reads the same on both pages. */
private fun staffRoleOf(role: ChatPeerRole): ChatStaffRole = when (role) {
    ChatPeerRole.COORDINATOR -> ChatStaffRole.COORDINATOR
    ChatPeerRole.MANAGERIAL, ChatPeerRole.ADMIN -> ChatStaffRole.MANAGERIAL
    else -> ChatStaffRole.TEACHER
}

/** "Subject coordinator · Math" / "Department manager" — her role, then her subjects on the child's section. */
fun recipientLabel(role: ChatPeerRole, subject: String?, strings: Strings): String =
    staffLabel(staffRoleOf(role), subject, null, strings)

/** "3 October" in the reader's language and the phone's own zone. */
fun complaintDate(epochMillis: Long, strings: Strings): String {
    val dt = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${dt.dayOfMonth} ${strings.months[dt.monthNumber - 1]}"
}

/** The system line for a status change: "Resolved by Ms. Lina · 3 October", "Reopened by you · 4 October". */
fun eventLine(event: ComplaintEvent, strings: Strings): String {
    val c = strings.complaints
    // A Latin name inside an Arabic line keeps its own direction, so "· 1 October" stays after it.
    val who = if (event.by == ComplaintActor.PARENT) c.you else isolate(event.byName)
    val template = if (event.status == ChatThreadStatus.RESOLVED) c.resolvedBy else c.reopenedBy
    return template.replace("{name}", who).replace("{date}", complaintDate(event.at, strings))
}

/** Screen-reader copy for a row — the chips say the same thing visually. */
fun complaintDescription(complaint: Complaint, strings: Strings): String = buildList {
    add(complaint.title)
    add(strings.complaints.to.replace("{name}", complaint.recipientName))
    add(recipientLabel(complaint.recipientRole, complaint.subject, strings))
    add(if (complaint.status == ChatThreadStatus.RESOLVED) strings.complaints.statusResolved else strings.complaints.statusOpen)
    if (complaint.unread > 0) add("${complaint.unread} ${strings.complaints.unread}")
}.joinToString(", ")

/** Open or Resolved: the only two states, never a red mark — resolved is the calm green, open the brand tint. */
@Composable
fun ComplaintStatusChip(status: ChatThreadStatus, strings: Strings) {
    val resolved = status == ChatThreadStatus.RESOLVED
    Chip(
        text = if (resolved) strings.complaints.statusResolved else strings.complaints.statusOpen,
        color = if (resolved) DashboardTokens.successBg else MaterialTheme.colorScheme.primaryContainer,
    )
}

/** One row of the Complaints list: the title, to whom, the last word, and where it stands. */
@Composable
fun ComplaintRow(complaint: Complaint, strings: Strings, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ParentCard(
        modifier = modifier.fillMaxWidth().padding(bottom = Dimens.s8)
            .clearAndSetSemantics { contentDescription = complaintDescription(complaint, strings) },
        onClick = onClick,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(DashboardTokens.bgSubtle),
                contentAlignment = Alignment.Center,
            ) {
                Text(avatarInitial(complaint.recipientName), style = MaterialTheme.typography.titleMedium, color = DashboardTokens.ink, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(Dimens.s12))
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text(
                        isolate(complaint.title),
                        style = MaterialTheme.typography.titleMedium,
                        color = DashboardTokens.ink,
                        fontWeight = if (complaint.unread > 0) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(Dimens.s8))
                    Text(
                        complaintDate(complaint.lastMessage?.createdAt ?: complaint.createdAt, strings),
                        style = MaterialTheme.typography.bodySmall,
                        color = DashboardTokens.inkSoft,
                    )
                }
                Text(
                    "${strings.complaints.to.replace("{name}", isolate(complaint.recipientName))} · ${recipientLabel(complaint.recipientRole, complaint.subject, strings)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = DashboardTokens.inkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                complaint.lastMessage?.let { last ->
                    Spacer(Modifier.height(Dimens.s4))
                    val who = if (last.sender == ChatSender.PARENT) strings.complaints.youSaid else ""
                    Text(
                        who + isolate(messagePreview(last, strings)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = DashboardTokens.inkSoft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(Dimens.s8))
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s8), verticalAlignment = Alignment.CenterVertically) {
                    ComplaintStatusChip(complaint.status, strings)
                    if (complaint.unread > 0) Chip("${complaint.unread}", DashboardTokens.secondarySoft)
                }
            }
        }
    }
}

/** A status change, drawn between the messages as a quiet centred line with rules either side. */
@Composable
fun ComplaintEventLine(event: ComplaintEvent, strings: Strings) {
    Row(Modifier.fillMaxWidth().padding(vertical = Dimens.s4), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = DashboardTokens.rule)
        Text(
            eventLine(event, strings),
            style = MaterialTheme.typography.labelMedium,
            color = DashboardTokens.inkSoft,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = Dimens.s8).widthIn(max = 260.dp),
        )
        HorizontalDivider(Modifier.weight(1f), color = DashboardTokens.rule)
    }
}

/** One message in a complaint: hers on the end side in the brand colour, the staff member's on the start side. */
@Composable
fun ComplaintBubble(item: TimelineItem.Message, strings: Strings, onRetry: (String) -> Unit) {
    val mine = item.message.sender == ChatSender.PARENT
    val shape = if (mine) RoundedCornerShape(12.dp, 12.dp, 2.dp, 12.dp) else RoundedCornerShape(12.dp, 12.dp, 12.dp, 2.dp)
    val textColor = if (mine) MaterialTheme.colorScheme.onPrimary else DashboardTokens.ink
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 300.dp)
                .background(if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface, shape)
                .then(if (mine) Modifier else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape))
                .padding(horizontal = Dimens.s12, vertical = Dimens.s8),
        ) {
            // M7's bubbles: a photo as the server's thumbnail (opened full screen), a PDF as the plan's card.
            item.message.attachments?.takeIf { it.isNotEmpty() }?.let {
                MessageAttachments(it, strings, Modifier.padding(bottom = if (item.message.body.isBlank()) 0.dp else Dimens.s8))
            }
            if (item.message.body.isNotBlank()) Text(isolate(item.message.body), style = MaterialTheme.typography.bodyLarge, color = textColor)
            Spacer(Modifier.height(Dimens.s4))
            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                when {
                    // A full 48 dp target with its own label: the word alone says nothing about which message it resends.
                    item.failed -> TextButton(
                        onClick = { item.clientId?.let(onRetry) },
                        enabled = item.clientId != null,
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics { contentDescription = strings.complaints.retryReply },
                        colors = ButtonDefaults.textButtonColors(contentColor = textColor),
                    ) {
                        Text(strings.retry, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                    else -> Text(
                        complaintClock(item.message.createdAt) + if (item.pending) " …" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (mine) textColor.copy(alpha = 0.8f) else DashboardTokens.inkSoft,
                    )
                }
            }
        }
    }
}

private fun complaintClock(epochMillis: Long): String {
    val dt = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${dt.hour.toString().padStart(2, '0')}:${dt.minute.toString().padStart(2, '0')}"
}
