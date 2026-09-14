package quest.api.dto

import kotlinx.serialization.Serializable

/** Output of Prompt A (`SourceAnalysis.schema.json`). */
@Serializable
data class SourceAnalysis(
    val kind: SourceKind,
    val title: String,
    val pages: List<AnalysedPage>,
    val skills: List<ExtractedSkill>,
    val vocabulary: List<VocabularyItem> = emptyList(),
    val storyPieces: StoryPiecesInfo? = null,
    val events: List<AnalysedEvent> = emptyList(),
    val facts: List<Fact> = emptyList(),
    val objectives: BilingualList,
)

@Serializable data class BilingualList(val en: List<String>, val ar: List<String>)
@Serializable data class AnalysedPage(val number: Int, val childText: List<String>, val pictureDescription: String, val illustrationKeys: List<String> = emptyList(), val keepImage: Boolean = false)
@Serializable data class VocabularyItem(val word: String, val meaning: String, val sentence: String, val illustrationKey: String)
@Serializable data class StoryPiecesInfo(val title: String, val genre: String, val characters: List<String>, val setting: String, val problem: String, val resolution: String)
@Serializable data class AnalysedEvent(val id: String, val text: String, val pageNumber: Int? = null)
@Serializable data class Fact(val id: String, val trueStatement: String, val falseTwin: String, val pageNumber: Int? = null)

@Serializable
data class ExtractedSkill(
    val id: String, val name: String, val subject: Subject, val method: String, val examples: List<String>,
    val slideNumbers: List<Int>, val confidence: Double, val unsure: Unsure? = null,
) { val isUnsure: Boolean get() = unsure != null }

@Serializable data class Unsure(val candidates: List<String>, val question: String)

/** Output of Prompt C (`ParentPanel.schema.json`). */
@Serializable
data class ParentPanel(
    val objectives: BilingualList,
    val supported: List<Bilingual>,
    val challenge: List<Bilingual>,
    val stopTips: List<StopTip> = emptyList(),
    val modelAnswers: List<ModelAnswer> = emptyList(),
)
@Serializable data class StopTip(val stopId: String, val en: String, val ar: String)
@Serializable data class ModelAnswer(val stopId: String, val en: String)
