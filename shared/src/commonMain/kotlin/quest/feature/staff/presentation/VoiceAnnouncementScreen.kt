package quest.feature.staff.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import quest.api.dto.Curriculum
import quest.core.platform.SpeechInput
import quest.core.platform.rememberSpeechInput
import quest.feature.parent.presentation.ParentButton
import quest.feature.parent.presentation.ParentCard
import quest.feature.parent.presentation.SectionTitle
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.FeatureGate
import quest.feature.school.presentation.GateFallback
import quest.ui.design.AnimatedDotsLoader
import quest.ui.design.DashboardFilterChip
import quest.ui.design.DashboardTokens
import quest.ui.design.Dimens

/**
 * Feature 22: "سجّل رسالة صوتية" on the phone, for the Admin and a department manager.
 *
 * The screen owns the recogniser and the view model owns the flow: it starts and stops `SpeechInput` from
 * [VoiceAnnouncementContract.State.recording] and hands what it hears straight back, so the two-minute cap is
 * enforced in one place and the device's speech API never reaches the view model.
 *
 * **Send is the only thing that sends.** Everything before it — the recording, the transcript, the prepared draft and
 * every edit to it — stays on this screen and in an unsent draft row.
 */
@Composable
fun VoiceAnnouncementRoute(onBack: () -> Unit) {
    val vm: VoiceAnnouncementViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val speech: SpeechInput = rememberSpeechInput()

    LaunchedEffect(vm) { vm.dispatch(VoiceAnnouncementContract.Intent.Open) }

    // The one place the microphone is opened and closed: the view model's own state says which it should be.
    LaunchedEffect(state.recording) {
        if (state.recording) {
            val started = speech.start(
                onText = { text, final -> vm.dispatch(VoiceAnnouncementContract.Intent.Transcribed(text, final)) },
                onEnd = { vm.dispatch(VoiceAnnouncementContract.Intent.RecordingEnded) },
            )
            if (!started) vm.dispatch(VoiceAnnouncementContract.Intent.RecordingEnded)
        } else {
            speech.stop()
        }
    }

    LaunchedEffect(vm) {
        vm.effects.collect { effect ->
            when (effect) {
                VoiceAnnouncementContract.Effect.Sent -> onBack()
            }
        }
    }

    StaffShell(title = { it.voiceAnnouncement }, currentTab = null, onTabSelected = {}, onBack = onBack) { s, _ ->
        GateFallback(Flags.VOICE_ANNOUNCEMENT, onClosed = onBack)
        VoiceAnnouncementScreen(
            state = state,
            strings = s,
            canRecord = speech.available,
            dispatch = vm::dispatch,
        )
    }
}

@Composable
fun VoiceAnnouncementScreen(
    state: VoiceAnnouncementContract.State,
    strings: StaffStrings,
    /** False where the phone has no Arabic recogniser: there is then nothing to offer, and the screen says so. */
    canRecord: Boolean = true,
    dispatch: (VoiceAnnouncementContract.Intent) -> Unit = {},
) {
    FeatureGate(Flags.VOICE_ANNOUNCEMENT) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.s16, vertical = Dimens.s8),
        ) {
            if (!state.allowed) {
                Note(strings.voiceNotAllowed)
                return@FeatureGate
            }
            when (state.step) {
                VoiceAnnouncementContract.Step.RECORD -> Recorder(state, strings, canRecord, dispatch)
                VoiceAnnouncementContract.Step.PREPARING -> Preparing(strings)
                VoiceAnnouncementContract.Step.PREVIEW -> Preview(state, strings, dispatch)
                VoiceAnnouncementContract.Step.SENT -> Note(strings.voiceSent)
            }
            Spacer(Modifier.height(Dimens.s16))
        }
    }
}

@Composable
private fun Recorder(
    state: VoiceAnnouncementContract.State,
    strings: StaffStrings,
    canRecord: Boolean,
    dispatch: (VoiceAnnouncementContract.Intent) -> Unit,
) {
    Spacer(Modifier.height(Dimens.s8))
    ParentCard(Modifier.fillMaxWidth()) {
        Text(strings.voiceRecordHint, style = MaterialTheme.typography.bodyMedium, color = DashboardTokens.inkSoft)
        Spacer(Modifier.height(Dimens.s8))
        if (!canRecord) {
            Text(strings.voiceMessage(VoiceMessage.NoRecogniser), style = MaterialTheme.typography.bodyMedium, color = DashboardTokens.ink)
            return@ParentCard
        }
        if (state.recording) {
            Text(
                strings.voiceTimeLeft.replace("{time}", countdown(state.remainingSeconds)),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = DashboardTokens.inkStrong,
            )
            Spacer(Modifier.height(Dimens.s8))
            Text(
                state.transcript.ifBlank { strings.voiceListening },
                style = MaterialTheme.typography.bodyMedium,
                color = DashboardTokens.ink,
            )
            Spacer(Modifier.height(Dimens.s8))
            ParentButton(strings.voiceStop, { dispatch(VoiceAnnouncementContract.Intent.StopRecording) }, icon = "⏹")
        } else {
            ParentButton(strings.voiceRecord, { dispatch(VoiceAnnouncementContract.Intent.StartRecording) }, icon = "🎙")
        }
        state.message?.let { message ->
            Spacer(Modifier.height(Dimens.s8))
            Text(strings.voiceMessage(message), style = MaterialTheme.typography.bodyMedium, color = DashboardTokens.ink)
        }
    }
}

@Composable
private fun Preparing(strings: StaffStrings) {
    Spacer(Modifier.height(Dimens.s8))
    ParentCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedDotsLoader()
            Spacer(Modifier.width(Dimens.s8))
            Text(strings.voicePreparing, style = MaterialTheme.typography.bodyMedium, color = DashboardTokens.ink)
        }
    }
}

@Composable
private fun Preview(
    state: VoiceAnnouncementContract.State,
    strings: StaffStrings,
    dispatch: (VoiceAnnouncementContract.Intent) -> Unit,
) {
    val form = state.form
    SectionTitle(strings.voicePreview)
    OutlinedTextField(
        value = form.title,
        onValueChange = { dispatch(VoiceAnnouncementContract.Intent.SetTitle(it)) },
        label = { Text(strings.voiceTitle, color = DashboardTokens.inkSoft) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(Dimens.s8))
    OutlinedTextField(
        value = form.bodyAr,
        onValueChange = { dispatch(VoiceAnnouncementContract.Intent.SetBodyAr(it)) },
        label = { Text(strings.voiceBodyAr, color = DashboardTokens.inkSoft) },
        minLines = 3,
        modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
    )
    Spacer(Modifier.height(Dimens.s8))
    // The English half of a bilingual announcement is written left to right even while she is reading right to
    // left, or its full stop lands at the start of the line.
    Ltr {
        OutlinedTextField(
            value = form.bodyEn,
            onValueChange = { dispatch(VoiceAnnouncementContract.Intent.SetBodyEn(it)) },
            label = { Text(strings.voiceBodyEn, color = DashboardTokens.inkSoft) },
            minLines = 3,
            modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
        )
    }

    SectionTitle(strings.voiceAudience)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), Arrangement.spacedBy(Dimens.s8)) {
        DashboardFilterChip(
            strings.voiceEveryAcademy,
            selected = form.curriculum == null,
            onClick = { dispatch(VoiceAnnouncementContract.Intent.SetCurriculum(null)) },
        )
        state.curricula.forEach { track ->
            DashboardFilterChip(
                strings.academyName(track),
                selected = form.curriculum == track,
                onClick = { dispatch(VoiceAnnouncementContract.Intent.SetCurriculum(track)) },
            )
        }
    }
    Spacer(Modifier.height(Dimens.s8))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), Arrangement.spacedBy(Dimens.s8)) {
        DashboardFilterChip(
            strings.voiceEveryGrade,
            selected = form.grade == null,
            onClick = { dispatch(VoiceAnnouncementContract.Intent.SetGrade(null)) },
        )
        state.grades(form.curriculum).forEach { grade ->
            DashboardFilterChip(
                strings.gradeName.replace("{n}", grade.toString()),
                selected = form.grade == grade,
                onClick = { dispatch(VoiceAnnouncementContract.Intent.SetGrade(grade)) },
            )
        }
    }

    Spacer(Modifier.height(Dimens.s8))
    Ltr {
        OutlinedTextField(
            value = form.expiresOn,
            onValueChange = { dispatch(VoiceAnnouncementContract.Intent.SetExpiry(it)) },
            label = { Text(strings.voiceExpiry, color = DashboardTokens.inkSoft) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    SectionTitle(strings.voiceTranscript)
    ParentCard(Modifier.fillMaxWidth()) {
        Text(state.transcript, style = MaterialTheme.typography.bodySmall, color = DashboardTokens.inkSoft)
    }

    state.message?.let { message ->
        Spacer(Modifier.height(Dimens.s8))
        Text(strings.voiceMessage(message), style = MaterialTheme.typography.bodyMedium, color = DashboardTokens.ink)
    }

    Spacer(Modifier.height(Dimens.s16))
    ParentButton(
        strings.voiceSend,
        { dispatch(VoiceAnnouncementContract.Intent.Send) },
        enabled = state.canSend,
        icon = "📣",
    )
    Spacer(Modifier.height(Dimens.s8))
    ParentButton(
        strings.voiceAgain,
        { dispatch(VoiceAnnouncementContract.Intent.Again) },
        primary = false,
        enabled = !state.sending,
        icon = "🎙",
    )
}

/** One field written left to right inside a right-to-left screen: the English body and an ISO date. */
@Composable
private fun Ltr(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)

@Composable
private fun Note(text: String) {
    ParentCard(Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = DashboardTokens.ink)
    }
}

/** `m:ss`, the only shape a two-minute countdown needs. */
internal fun countdown(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val rest = safe % 60
    return "${safe / 60}:${if (rest < 10) "0$rest" else "$rest"}"
}

/** The academy's name in her language; the wire word is the enum's. */
internal fun StaffStrings.academyName(curriculum: Curriculum): String =
    academyNames[curriculum.name.lowercase()] ?: curriculum.name
