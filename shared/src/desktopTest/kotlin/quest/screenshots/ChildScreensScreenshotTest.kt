package quest.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.datetime.LocalDate
import quest.api.dto.Child
import quest.api.dto.Curriculum
import quest.api.dto.Island
import quest.api.dto.IslandKind
import quest.api.dto.IslandState
import quest.api.dto.Stop
import quest.api.dto.Subject
import quest.api.samples.HotSoupSeed
import quest.api.samples.MathSeed
import quest.api.samples.PhonicsSeed
import quest.feature.journey.presentation.CompleteContract
import quest.feature.journey.presentation.JourneyContract
import quest.feature.journey.presentation.JourneyScreen
import quest.feature.journey.presentation.LessonCompleteScreen
import quest.feature.journey.presentation.PlayerContract
import quest.feature.journey.presentation.StopPlayerScreen
import quest.feature.map.presentation.MapContract
import quest.feature.map.presentation.WorldMapScreen
import quest.feature.rewards.domain.Sticker
import quest.feature.rewards.presentation.RewardsContract
import quest.feature.rewards.presentation.StickerBookScreen
import quest.feature.rewards.presentation.TreasureChestScreen
import quest.ui.design.ChildTheme
import quest.ui.design.Dimens
import quest.ui.design.Palette
import quest.ui.stops.StopContent
import kotlin.test.Test
import kotlin.test.assertTrue

/** One screenshot per child screen and per stop type (dev prompt §10). Files land in shared/build/screenshots. */
class ChildScreensScreenshotTest {
    private val hot = HotSoupSeed.lesson
    private val child = Child("c", "Maya", "sun", Curriculum.BRITISH, 1)

    private fun shot(name: String, content: @Composable () -> Unit) {
        val f = Screenshots.render(name) { ChildTheme { content() } }
        assertTrue(f.length() > 1000, "screenshot $name is empty")
    }

    private fun stopShot(name: String, stop: Stop) = shot(name) {
        Column(Modifier.fillMaxSize().background(Palette.sky).verticalScroll(rememberScrollState()).padding(top = Dimens.s24)) {
            quest.ui.design.SpeechBubble(stop.speak, Modifier.padding(horizontal = Dimens.s16))
            Box(Modifier.padding(top = Dimens.s16)) { StopContent(stop, onEvent = {}) }
        }
    }

    @Test fun worldMap() = shot("02-world-map") {
        WorldMapScreen(MapContract.State(loading = false, child = child, streakDays = 2, islands = listOf(
            Island("a", IslandKind.LESSON, LocalDate(2026, 9, 11), IslandState.DONE, "The sh sound", Subject.ENGLISH, "l1", 1, listOf(1, 2), listOf(1), 18, 21),
            Island("r", IslandKind.REVIEW, LocalDate(2026, 9, 14), IslandState.TODAY, "The sh sound", skillId = "sh", playId = "p"),
            Island("b", IslandKind.LESSON, LocalDate(2026, 9, 14), IslandState.TODAY, "Counting by 2s", Subject.MATH, "l2", 1, listOf(1)),
            Island("c", IslandKind.LESSON, LocalDate(2026, 9, 14), IslandState.WAITING, "Hot Soup for Mummy · Part 1", Subject.ENGLISH, "l3", 1, listOf(1)),
            Island("locked", IslandKind.LOCKED, LocalDate(2026, 9, 15), IslandState.LOCKED, "Still asleep"),
        )), {}, {}, {}, {})
    }
    @Test fun worldMapEmpty() = shot("02b-world-map-empty") { WorldMapScreen(MapContract.State(loading = false, child = child, islands = listOf(Island("locked", IslandKind.LOCKED, LocalDate(2026, 9, 15), IslandState.LOCKED, "Still asleep"))), {}, {}, {}, {}) }

    @Test fun journey() = shot("03-journey") {
        JourneyScreen(JourneyContract.State(loading = false, lesson = hot, play = hot.plays[0], levelsUnlocked = listOf(1), stopStars = mapOf("hs1-move" to 3, "hs1-pieces" to 3), childName = "Maya"), {}, {}, {})
    }
    @Test fun journeyComplete() = shot("03b-journey-full") {
        JourneyScreen(JourneyContract.State(loading = false, lesson = hot, play = hot.plays[0], levelsUnlocked = listOf(1, 2), completedLevels = listOf(1), stopStars = hot.plays[0].stops.associate { it.id to 3 }, childName = "Maya"), {}, {}, {})
    }

    private val l1 = hot.plays[0].stops
    @Test fun stopMove() = stopShot("04-stop-move", l1[0])
    @Test fun stopStoryPieces() = stopShot("05-stop-story-pieces", l1[1])
    @Test fun stopReadPage() = stopShot("06-stop-read-page", l1[2])
    @Test fun stopReadPageTap() = stopShot("06b-stop-read-page-tap", l1[3])
    @Test fun stopWordCards() = stopShot("07-stop-word-cards", l1[5])
    @Test fun stopMatch() = stopShot("08-stop-match", l1[6])
    @Test fun stopOrder() = stopShot("09-stop-order", l1[7])
    @Test fun stopExitTicket() = stopShot("10-stop-exit-ticket", l1[8])
    @Test fun stopChoice() = stopShot("11-stop-choice", hot.plays[1].stops[1])
    @Test fun stopMultiSelect() = stopShot("12-stop-multi-select", hot.plays[1].stops[2])
    @Test fun stopTrueFalse() = stopShot("13-stop-true-false", hot.plays[1].stops[3])
    @Test fun stopWriteSentence() = stopShot("14-stop-write-sentence", hot.plays[1].stops[6])
    @Test fun stopRetell() = stopShot("15-stop-retell", hot.plays[2].stops[1])
    @Test fun stopOpenAnswer() = stopShot("16-stop-open-answer", hot.plays[2].stops[2])
    @Test fun stopWriteFree() = stopShot("16b-stop-write-free", hot.plays[2].stops[3])
    @Test fun stopExplain() = stopShot("17-stop-explain", MathSeed.level1.stops[0])
    @Test fun stopSequence() = stopShot("18-stop-sequence", MathSeed.level1.stops[1])
    @Test fun stopCount() = stopShot("19-stop-count", MathSeed.level1.stops[2])
    @Test fun stopCompare() = stopShot("20-stop-compare", MathSeed.level1.stops[4])
    @Test fun stopSound() = stopShot("21-stop-sound", PhonicsSeed.level1.stops[1])
    @Test fun stopWord() = stopShot("22-stop-word", PhonicsSeed.level1.stops[2])
    @Test fun stopReadTap() = stopShot("23-stop-read-tap", PhonicsSeed.level1.stops[3])
    @Test fun stopTrace() = stopShot("24-stop-trace", PhonicsSeed.level1.stops[5])
    @Test fun stopSelectAll() = stopShot("25-stop-select-all", PhonicsSeed.level2.stops[5])

    private fun playerState(phase: PlayerContract.Phase) = PlayerContract.State(phase = phase, lesson = hot, play = hot.plays[0], index = 3, stopStars = mapOf("hs1-move" to 3, "hs1-pieces" to 3, "hs1-page1" to 3),
        hint = "Who is in bed on page 1?", praise = "Great!", lastIngredient = l1[3].ingredient, childName = "Maya")
    @Test fun player() = shot("26-player") { StopPlayerScreen(playerState(PlayerContract.Phase.STOP), {}, {}) }
    @Test fun hintSheet() = shot("27-hint-sheet") { StopPlayerScreen(playerState(PlayerContract.Phase.HINT).copy(index = 8, numberLine = MathSeed.level1.stops.filterIsInstance<Stop.Sequence>().first().numberLine), {}, {}) }
    @Test fun correctOverlay() = shot("28-correct-overlay") { StopPlayerScreen(playerState(PlayerContract.Phase.CORRECT), {}, {}) }
    @Test fun ingredientDrop() = shot("29-ingredient-drop") { StopPlayerScreen(playerState(PlayerContract.Phase.INGREDIENT), {}, {}) }

    @Test fun potFull() = shot("30-pot-full") { LessonCompleteScreen(CompleteContract.State(loading = false, lesson = hot, stars = 24, starsTotal = 27, stickerKey = "rocket", childName = "Maya"), {}, {}, {}, {}, {}) }
    @Test fun certificate() = shot("31-certificate") { LessonCompleteScreen(CompleteContract.State(loading = false, lesson = hot, stars = 24, starsTotal = 27, stickerKey = "rocket", childName = "Maya", served = true, nextLevelUnlocked = true), {}, {}, {}, {}, {}) }

    @Test fun stickerBook() = shot("32-sticker-book") { StickerBookScreen(RewardsContract.State(stickers = listOf(Sticker("1", "star-badge", 0), Sticker("2", "rocket", 0)), streakDays = 3, loading = false), {}, {}) }
    @Test fun treasureChest() = shot("33-treasure-chest") { TreasureChestScreen(RewardsContract.State(streakDays = 4, loading = false), {}, {}) }
}
