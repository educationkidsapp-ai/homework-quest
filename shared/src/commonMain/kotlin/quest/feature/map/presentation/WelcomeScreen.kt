package quest.feature.map.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import quest.feature.parent.domain.ParentRepository
import quest.core.design.BigButton
import quest.core.design.Dimens
import quest.core.design.Palette
import quest.core.design.Pip
import quest.core.design.PipPose
import quest.core.design.ReadAloudButton
import quest.core.platform.Speaker

@Composable
fun WelcomeRoute(onPlay: () -> Unit, onGrownUps: () -> Unit) {
    val parent: ParentRepository = koinInject()
    val speaker: Speaker = koinInject()
    var name by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        name = parent.profile().name
        speaker.speak(greeting(name))
    }
    WelcomeScreen(name, onPlay, onGrownUps, onReadAloud = { speaker.speak(greeting(name)) })
}

private fun greeting(name: String) = if (name.isBlank()) "Hi! Ready for a quest?" else "Hi $name! Ready for a quest?"

@Composable
fun WelcomeScreen(name: String, onPlay: () -> Unit, onGrownUps: () -> Unit, onReadAloud: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Palette.sky).safeDrawingPadding()) {
        ReadAloudButton(onReadAloud, Modifier.align(Alignment.TopEnd).padding(Dimens.s16))
        Column(Modifier.fillMaxSize().padding(Dimens.s24), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Pip(PipPose.WAVING, Dimens.pipLarge)
            Spacer(Modifier.height(Dimens.s24))
            Text(greeting(name), style = MaterialTheme.typography.displayLarge, color = Palette.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(Dimens.s32))
            BigButton("Play", onClick = onPlay, emoji = "▶️")
        }
        Text(
            "Grown-ups", color = Palette.inkSoft, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.BottomStart).padding(Dimens.s16).size(width = 140.dp, height = Dimens.minTarget)
                .clickable(role = Role.Button, onClick = onGrownUps).semantics { contentDescription = "Grown-ups" },
        )
    }
}
