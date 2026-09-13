package quest.screenshots

import quest.api.dto.NumberLine
import quest.api.dto.Question
import quest.api.dto.QuestionSet
import quest.api.dto.Subject
import quest.api.samples.Samples
import quest.api.validation.SchemaValidator
import quest.core.design.ChildTheme
import quest.feature.map.domain.Island
import quest.feature.map.domain.IslandStatus
import quest.feature.map.presentation.IntroContract
import quest.feature.map.presentation.LessonIntroScreen
import quest.feature.map.presentation.MapContract
import quest.feature.map.presentation.WelcomeScreen
import quest.feature.map.presentation.WorldMapScreen
import quest.feature.practice.presentation.PracticeContract
import quest.feature.practice.presentation.PracticeScreen
import quest.feature.rewards.presentation.RewardsContract
import quest.feature.rewards.presentation.StickerBookScreen
import quest.feature.rewards.presentation.TreasureChestScreen
import quest.feature.rewards.domain.Sticker
import kotlin.test.Test
import kotlin.test.assertTrue

/** One screenshot per child screen (dev prompt §10). Files land in shared/build/screenshots. */
class ChildScreensScreenshotTest {
    private val set = SchemaValidator.json.decodeFromString(QuestionSet.serializer(), Samples.questionSetCountingBy2s)
    private val english = SchemaValidator.json.decodeFromString(QuestionSet.serializer(), Samples.questionSetShSound)

    private fun practiceState(q: Question, phase: PracticeContract.Phase = PracticeContract.Phase.QUESTION, dimmed: Set<String> = emptySet()) =
        PracticeContract.State(
            phase = phase, setId = "s", skillId = set.skillId, skillName = "Counting by 2s", index = 2, total = 7, stars = 2,
            question = q, dimmed = dimmed, hint = q.hint, numberLine = (q as? Question.Sequence)?.numberLine ?: (q as? Question.Count)?.numberLine ?: (q as? Question.Compare)?.numberLine,
            praise = "Great!", prompt = "What number is missing?", stickerKey = "rocket", firstTryCorrect = 6,
        )

    private fun shot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        val f = Screenshots.render(name) { ChildTheme { content() } }
        assertTrue(f.length() > 1000, "screenshot $name is empty")
    }

    @Test fun welcome() = shot("01-welcome") { WelcomeScreen("Maya", {}, {}, {}) }

    @Test fun worldMap() = shot("02-world-map") {
        WorldMapScreen(
            MapContract.State(
                loading = false, childName = "Maya", streakDays = 3, stickerCount = 4,
                islands = listOf(
                    Island("a", "Counting by 2s", Subject.MATH, IslandStatus.TODAY, 0, 7, "s1"),
                    Island("b", "The sh sound", Subject.ENGLISH, IslandStatus.DONE, 7, 7, "s2"),
                    Island("c", "Number bonds to 10", Subject.MATH, IslandStatus.REPLAY, 5, 7, "s3"),
                    Island("asleep-0", "Still asleep", null, IslandStatus.ASLEEP),
                ),
            ), {}, {}, {}, {},
        )
    }

    @Test fun worldMapEmpty() = shot("02b-world-map-empty") {
        WorldMapScreen(MapContract.State(loading = false, childName = "Maya", isEmpty = true, islands = emptyList()), {}, {}, {}, {})
    }

    @Test fun lessonIntro() = shot("03-lesson-intro") {
        LessonIntroScreen(IntroContract.State(loading = false, skillName = "Counting by 2s", explanation = set.explanation, examples = set.workedExamples, setId = "s"), {}, {})
    }

    @Test fun practiceSequence() = shot("04-practice-sequence") { PracticeScreen(practiceState(set.questions[0]), {}, {}, {}) }
    @Test fun practiceCount() = shot("05-practice-count") { PracticeScreen(practiceState(set.questions[1]), {}, {}, {}) }
    @Test fun practiceCompare() = shot("06-practice-compare") { PracticeScreen(practiceState(set.questions[3]), {}, {}, {}) }
    @Test fun practiceSound() = shot("07-practice-sound") { PracticeScreen(practiceState(english.questions[0]), {}, {}, {}) }
    @Test fun practiceWord() = shot("08-practice-word") { PracticeScreen(practiceState(english.questions[1]), {}, {}, {}) }
    @Test fun practiceTrace() = shot("09-practice-trace") { PracticeScreen(practiceState(english.questions[4]), {}, {}, {}) }
    @Test fun practiceReadTap() = shot("10-practice-readtap") { PracticeScreen(practiceState(english.questions[2]), {}, {}, {}) }
    @Test fun hintSheet() = shot("11-hint-sheet") { PracticeScreen(practiceState(set.questions[0], PracticeContract.Phase.HINT, dimmed = setOf("a")), {}, {}, {}) }
    @Test fun correctOverlay() = shot("12-correct-overlay") { PracticeScreen(practiceState(set.questions[0], PracticeContract.Phase.CORRECT), {}, {}, {}) }
    @Test fun setComplete() = shot("13-set-complete") { PracticeScreen(practiceState(set.questions[0], PracticeContract.Phase.COMPLETE).copy(stars = 7), {}, {}, {}) }

    @Test fun stickerBook() = shot("14-sticker-book") {
        StickerBookScreen(RewardsContract.State(stickers = listOf(Sticker("1", "star-badge", 0), Sticker("2", "rocket", 0), Sticker("3", "rainbow", 0)), streakDays = 3, loading = false), {}, {})
    }

    @Test fun treasureChest() = shot("15-treasure-chest") { TreasureChestScreen(RewardsContract.State(streakDays = 4, loading = false), {}, {}) }
}
