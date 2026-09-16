package quest.feature.school

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import quest.api.DEFAULT_FLAGS
import quest.api.samples.HotSoupSeed
import quest.feature.journey.presentation.CompleteContract
import quest.feature.journey.presentation.LessonCompleteScreen
import quest.feature.school.domain.FlagStore
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.LevelGate
import quest.feature.school.presentation.LocalFlags
import quest.ui.design.ChildTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §4 `levels.three`, at every door into the Challenge path.
 *
 * The first version of this package gated only the journey's level selector, so a child at a school without the flag
 * could finish level 2, tap "Next level" on the finish screen and land in level 3 — on a journey whose selector did
 * not even list the level they were standing in. Each door gets a test.
 */
class LevelThreeGateTest {

    private fun flags(levelThree: Boolean) = object : FlagStore {
        override val flags: StateFlow<Map<String, Boolean>> =
            MutableStateFlow(DEFAULT_FLAGS + (Flags.LEVEL_THREE to levelThree)).asStateFlow()
    }

    // ---- the rule itself ------------------------------------------------------------------------------------------

    @Test fun aSchoolWithoutTheFlagTopsOutAtLevelTwo() {
        assertEquals(2, Flags.topLevel(false))
        assertEquals(3, Flags.topLevel(true))
        assertEquals(listOf(1, 2), Flags.levels(false))
        assertEquals(listOf(1, 2, 3), Flags.levels(true))
        assertTrue(Flags.levelAllowed(2, false))
        assertFalse(Flags.levelAllowed(3, false))
        assertTrue(Flags.levelAllowed(3, true))
    }

    // ---- door 1: the finish screen's next-level button ------------------------------------------------------------

    private fun finishedLevel(level: Int) = CompleteContract.State(
        loading = false, lesson = HotSoupSeed.lesson, level = level, stars = 24, starsTotal = 27,
        stickerKey = "rocket", childName = "Maya", served = true, nextLevelUnlocked = true,
    )

    @OptIn(ExperimentalTestApi::class)
    private fun finishScreen(level: Int, levelThree: Boolean, assertions: androidx.compose.ui.test.ComposeUiTest.() -> Unit) =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalFlags provides flags(levelThree)) {
                    ChildTheme { LessonCompleteScreen(finishedLevel(level), {}, {}, {}, {}, {}) }
                }
            }
            assertions()
        }

    @OptIn(ExperimentalTestApi::class)
    @Test fun finishingLevelTwoWithoutTheFlagOffersStickersRatherThanTheChallengePath() =
        finishScreen(level = 2, levelThree = false) {
            onNodeWithText("Next level").assertDoesNotExist()
            // The next-level slot becomes a second "Stickers" — the same ending level 3 gets at a school that has it.
            onAllNodesWithText("Stickers").assertCountEquals(2)
        }

    @OptIn(ExperimentalTestApi::class)
    @Test fun finishingLevelTwoWithTheFlagStillOffersTheNextLevel() =
        finishScreen(level = 2, levelThree = true) {
            onNodeWithText("Next level").assertExists()
            onAllNodesWithText("Stickers").assertCountEquals(1)
        }

    @OptIn(ExperimentalTestApi::class)
    @Test fun finishingLevelOneAlwaysOffersTheNextLevel() =
        finishScreen(level = 1, levelThree = false) {
            onNodeWithText("Next level").assertExists()
        }

    // ---- door 2: the route ----------------------------------------------------------------------------------------

    @Test fun theRouteIntoLevelThreeIsRefusedAndSendsTheChildBack() {
        var composed = 0
        var refused = 0
        composeOnce(flags(false)) { LevelGate(level = 3, onRefused = { refused++ }) { composed++ } }
        assertEquals(0, composed, "the journey is never composed for a level this school does not sell")
        assertEquals(1, refused, "and the child is sent somewhere that exists")
    }

    @Test fun theRouteIntoLevelTwoIsAlwaysOpen() {
        var composed = 0
        var refused = 0
        composeOnce(flags(false)) { LevelGate(level = 2, onRefused = { refused++ }) { composed++ } }
        assertEquals(1, composed)
        assertEquals(0, refused)
    }

    @Test fun theRouteIntoLevelThreeIsOpenWhenTheSchoolHasIt() {
        var composed = 0
        var refused = 0
        composeOnce(flags(true)) { LevelGate(level = 3, onRefused = { refused++ }) { composed++ } }
        assertEquals(1, composed)
        assertEquals(0, refused)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun composeOnce(store: FlagStore, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(64, 64, Density(1f))
        try {
            scene.setContent { CompositionLocalProvider(LocalFlags provides store) { content() } }
            scene.render(0L)
            scene.render(16_000_000L)   // LaunchedEffect in the refused branch runs on the next frame
        } finally {
            scene.close()
        }
    }
}
