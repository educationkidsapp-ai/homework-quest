package quest

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Enforces the feature layering by scanning sources:
 *  - presentation never touches the database or another feature's data layer
 *  - domain is pure Kotlin: no Compose, no data, no presentation imports
 */
class ArchitectureTest {
    private val root = File("src/commonMain/kotlin/quest/feature")

    private fun violations(layer: String, forbidden: List<Regex>): List<String> =
        root.walkTopDown().filter { it.isFile && it.extension == "kt" && it.path.contains("/$layer/") }.flatMap { file ->
            file.readLines().filter { line -> line.startsWith("import ") && forbidden.any { it.containsMatchIn(line) } }.map { "${file.path}: $it" }
        }.toList()

    @Test fun presentationDoesNotUseDbOrForeignData() {
        val v = violations("presentation", listOf(Regex("quest\\.core\\.db\\."), Regex("quest\\.feature\\.\\w+\\.data\\.(?!wire)")))
        assertTrue(v.isEmpty(), v.joinToString("\n"))
    }

    @Test fun domainIsPure() {
        val v = violations("domain", listOf(Regex("androidx\\.compose"), Regex("quest\\.feature\\.\\w+\\.(data|presentation)\\."), Regex("quest\\.core\\.db\\.")))
        assertTrue(v.isEmpty(), v.joinToString("\n"))
    }

    @Test fun dataDoesNotDependOnPresentation() {
        val v = violations("data", listOf(Regex("androidx\\.compose"), Regex("quest\\.feature\\.\\w+\\.presentation\\.")))
        assertTrue(v.isEmpty(), v.joinToString("\n"))
    }
}
