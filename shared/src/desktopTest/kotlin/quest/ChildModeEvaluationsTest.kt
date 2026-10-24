package quest

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Feature 33's hardest rule: **a child never sees an evaluation.** The screen exists only in the parent area, behind
 * the parent gate, and this test is what keeps it there as the app grows — the spec's own acceptance criterion
 * ("Children never see evaluations") written as something that fails rather than something a reviewer must notice.
 *
 * It reads sources rather than classes, exactly as [ArchitectureTest] does: `childSide` is file-private in `App.kt`
 * and a child screen's wrong import is a compile-time fact, so the text is the thing to assert on.
 */
class ChildModeEvaluationsTest {
    private val common = File("src/commonMain/kotlin/quest")

    private fun text(path: String) = File(common, path).let {
        assertTrue(it.isFile, "${it.path} is gone — this test points at the wrong file")
        it.readText()
    }

    /** The route set the gate closes on, as `App.kt` declares it. */
    private val childSideLine: String
        get() = text("App.kt").lines().first { it.contains("val childSide") }

    @Test fun theChildSideRouteSetNeverContainsTheEvaluationsPage() {
        val line = childSideLine
        assertTrue(line.contains("Routes.WorldMap::class"), "found the wrong line: $line")
        assertTrue(
            !line.contains("ChildEvaluations"),
            "Routes.ChildEvaluations is on the child's side of the parent gate:\n$line",
        )
    }

    /** The route is wired in one graph, and it is the parent's. */
    @Test fun theEvaluationsRouteIsComposedInTheParentGraphOnly() {
        val wired = common.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.readText().contains("composable<Routes.ChildEvaluations>") }
            .map { it.name }.toList()
        assertTrue(wired == listOf("ParentGraph.kt"), "Routes.ChildEvaluations is composed in $wired, not in the parent graph alone")
    }

    /**
     * Nothing on the child's side knows the feature exists. The three packages below are the child's screens — the
     * world map, a lesson's journey and her rewards — and the only places `quest.feature.evaluations` may be named at
     * all are the feature itself, the parent graph that routes to it and the container that builds it.
     */
    @Test fun noChildModeScreenDependsOnTheEvaluationsFeature() {
        val childPackages = listOf("feature/map", "feature/journey", "feature/rewards", "feature/today")
        val offenders = childPackages.flatMap { pkg ->
            File(common, pkg).walkTopDown().filter { it.isFile && it.extension == "kt" }
                .filter { it.readText().contains("evaluations", ignoreCase = true) }
                .map { it.path }.toList()
        }
        assertTrue(offenders.isEmpty(), "a child-mode package names the evaluations feature:\n${offenders.joinToString("\n")}")
    }

    @Test fun onlyTheFeatureItsRouteAndTheContainerImportTheEvaluationsPackage() {
        val allowed = setOf("ParentGraph.kt", "AppModule.kt")
        val importers = common.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.path.contains("feature/evaluations/") }
            .filter { f -> f.readLines().any { it.startsWith("import quest.feature.evaluations.") } }
            .map { it.name }.toList()
        assertTrue(importers.all { it in allowed }, "unexpected importers of quest.feature.evaluations: $importers")
    }
}
