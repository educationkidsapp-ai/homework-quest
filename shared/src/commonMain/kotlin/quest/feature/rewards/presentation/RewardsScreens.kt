package quest.feature.rewards.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import quest.ui.design.BackButton
import quest.ui.design.Dimens
import quest.ui.design.Palette
import quest.ui.design.Pip
import quest.ui.design.PipPose
import quest.ui.design.ReadAloudButton
import quest.ui.design.SpeechBubble
import quest.ui.design.StickerKeys
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.core.platform.Speaker
import quest.feature.rewards.domain.RewardsRepository
import quest.feature.rewards.domain.Sticker
import quest.feature.rewards.domain.UpdateStreakUseCase

object RewardsContract {
    data class State(val stickers: List<Sticker> = emptyList(), val streakDays: Int = 0, val loading: Boolean = true) : MviState
    sealed interface Intent : MviIntent { data object Load : Intent; data object ReadStickers : Intent; data object ReadChest : Intent }
    sealed interface Effect : MviEffect { data class Speak(val text: String) : Effect }
}

class RewardsViewModel(private val repo: RewardsRepository) : MviViewModel<RewardsContract.State, RewardsContract.Intent, RewardsContract.Effect>(RewardsContract.State()) {
    init { dispatch(RewardsContract.Intent.Load) }
    override suspend fun handle(intent: RewardsContract.Intent) {
        when (intent) {
            RewardsContract.Intent.Load -> {
                val stickers = repo.stickers()
                val streak = repo.streak()
                reduce { copy(stickers = stickers, streakDays = streak.currentDays, loading = false) }
            }
            RewardsContract.Intent.ReadStickers -> effect(RewardsContract.Effect.Speak(stickerText(current.stickers.size)))
            RewardsContract.Intent.ReadChest -> effect(RewardsContract.Effect.Speak(chestText(current.streakDays)))
        }
    }
    companion object {
        fun stickerText(n: Int) = when (n) { 0 -> "No stickers yet. Finish a quest to earn one!"; 1 -> "You have one sticker!"; else -> "You have $n stickers!" }
        fun chestText(days: Int): String {
            val next = UpdateStreakUseCase.chestDays.firstOrNull { it > days }
            return when {
                days == 0 -> "Play today to start your streak!"
                next == null -> "Wow, $days days in a row! Every chest is open!"
                else -> "$days days in a row! ${next - days} more to open the next chest."
            }
        }
    }
}

@Composable
fun StickerBookRoute(onBack: () -> Unit) {
    val vm: RewardsViewModel = koinViewModel()
    val speaker: Speaker = koinInject()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.dispatch(RewardsContract.Intent.Load); vm.effects.collect { if (it is RewardsContract.Effect.Speak) speaker.speak(it.text) } }
    StickerBookScreen(state, onBack, onReadAloud = { vm.dispatch(RewardsContract.Intent.ReadStickers) })
}

@Composable
fun StickerBookScreen(state: RewardsContract.State, onBack: () -> Unit, onReadAloud: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Palette.sky).safeDrawingPadding().padding(horizontal = Dimens.s16)) {
        Row(Modifier.fillMaxWidth().padding(top = Dimens.s8), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack); Spacer(Modifier.weight(1f))
            Text("Stickers", style = MaterialTheme.typography.headlineMedium, color = Palette.ink); Spacer(Modifier.weight(1f))
            ReadAloudButton(onReadAloud)
        }
        Spacer(Modifier.height(Dimens.s12))
        SpeechBubble(RewardsViewModel.stickerText(state.stickers.size), Modifier.fillMaxWidth())
        Spacer(Modifier.height(Dimens.s16))
        val earned = state.stickers.map { it.key }
        val slots = StickerKeys.all.mapIndexed { i, key -> key to (earned.count { it == key } > 0 || i < earned.size) }
        LazyVerticalGrid(GridCells.Fixed(3), verticalArrangement = Arrangement.spacedBy(Dimens.s12), horizontalArrangement = Arrangement.spacedBy(Dimens.s12)) {
            items(slots) { (key, on) ->
                Box(
                    Modifier.size(110.dp).shadow(if (on) 4.dp else 0.dp, RoundedCornerShape(Dimens.radiusTile))
                        .background(if (on) Palette.cream else Palette.inkSoft.copy(alpha = 0.15f), RoundedCornerShape(Dimens.radiusTile))
                        .semantics { contentDescription = if (on) "sticker $key" else "locked sticker" },
                    contentAlignment = Alignment.Center,
                ) { Text(StickerKeys.emoji(key), fontSize = 48.sp, modifier = Modifier.alpha(if (on) 1f else 0.25f)) }
            }
        }
    }
}

@Composable
fun TreasureChestRoute(onBack: () -> Unit) {
    val vm: RewardsViewModel = koinViewModel()
    val speaker: Speaker = koinInject()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.dispatch(RewardsContract.Intent.Load); vm.effects.collect { if (it is RewardsContract.Effect.Speak) speaker.speak(it.text) } }
    TreasureChestScreen(state, onBack, onReadAloud = { vm.dispatch(RewardsContract.Intent.ReadChest) })
}

@Composable
fun TreasureChestScreen(state: RewardsContract.State, onBack: () -> Unit, onReadAloud: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Palette.sky).safeDrawingPadding().padding(horizontal = Dimens.s16), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(top = Dimens.s8), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack); Spacer(Modifier.weight(1f))
            Text("Treasure", style = MaterialTheme.typography.headlineMedium, color = Palette.ink); Spacer(Modifier.weight(1f))
            ReadAloudButton(onReadAloud)
        }
        Spacer(Modifier.height(Dimens.s24))
        Pip(if (state.streakDays > 0) PipPose.CELEBRATING else PipPose.IDLE, Dimens.pipMedium)
        Text("🔥 ${state.streakDays}", style = MaterialTheme.typography.displayLarge, color = Palette.ink, modifier = Modifier.semantics { contentDescription = "${state.streakDays} day streak" })
        Text(if (state.streakDays == 1) "day in a row" else "days in a row", style = MaterialTheme.typography.bodyLarge, color = Palette.inkSoft)
        Spacer(Modifier.height(Dimens.s24))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s16)) {
            UpdateStreakUseCase.chestDays.forEach { days ->
                val open = state.streakDays >= days
                Column(
                    Modifier.size(104.dp, 120.dp).shadow(4.dp, RoundedCornerShape(Dimens.radiusTile)).background(if (open) Palette.sun else Palette.sand, RoundedCornerShape(Dimens.radiusTile)).padding(Dimens.s8)
                        .semantics { contentDescription = if (open) "$days day chest, open" else "$days day chest, closed" },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Text(if (open) "🎁" else "📦", fontSize = 44.sp)
                    Text("$days days", style = MaterialTheme.typography.labelLarge, color = Palette.ink, textAlign = TextAlign.Center)
                }
            }
        }
        Spacer(Modifier.height(Dimens.s24))
        SpeechBubble(RewardsViewModel.chestText(state.streakDays), Modifier.fillMaxWidth())
    }
}
