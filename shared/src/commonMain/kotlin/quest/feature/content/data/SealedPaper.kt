package quest.feature.content.data

import quest.api.dto.Bilingual
import quest.api.dto.MatchPair
import quest.api.dto.Play
import quest.api.dto.PublishedLesson
import quest.api.dto.Stop

/**
 * B3 parity for [FakeContentApi]: an exam paper as the server sends it before release — [Play.sealed] `true`, every
 * option, tile, item, pair and hotspot id opaque, every key field a placeholder (the first option, `false`, `[]`, the
 * items in the order sent), hints, model answers, parent tips and number-line highlights empty, and a match stop's
 * right-hand tiles moved off their pairs. The app must sit it without ever reading the key.
 */
fun PublishedLesson.sealedForChild(): PublishedLesson =
    copy(plays = plays.map { it.sealed() }, variant = variant.sealed(), examPlay = examPlay?.sealed(), parentPanel = parentPanel.copy(stopTips = emptyList(), modelAnswers = emptyList()))

fun Play.sealed(): Play = copy(stops = stops.map { it.sealed() }, sealed = true)

private val noTip = Bilingual("", "")
private fun opaque(stopId: String, id: String) = "s" + (stopId + "/" + id).hashCode().toUInt().toString(36)

private fun Stop.sealed(): Stop {
    return when (this) {
        is Stop.Choice -> options.map { it.copy(id = opaque(id, it.id)) }.reversed().let { o -> copy(options = o, correctOptionId = o.first().id, hint = "", parentTip = noTip) }
        is Stop.TrueFalse -> copy(answer = false, hint = "", parentTip = noTip)
        is Stop.Sequence -> options.map { it.copy(id = opaque(id, it.id)) }.reversed().let { o -> copy(options = o, correctOptionId = o.first().id, hint = "", numberLine = numberLine.copy(highlight = emptyList()), parentTip = noTip) }
        is Stop.Count -> options.map { it.copy(id = opaque(id, it.id)) }.reversed().let { o -> copy(options = o, correctOptionId = o.first().id, hint = "", numberLine = numberLine.copy(highlight = emptyList()), parentTip = noTip) }
        is Stop.Compare -> options.map { it.copy(id = opaque(id, it.id)) }.reversed().let { o -> copy(options = o, correctOptionId = o.first().id, hint = "", numberLine = numberLine.copy(highlight = emptyList()), parentTip = noTip) }
        is Stop.Sound -> options.map { it.copy(id = opaque(id, it.id)) }.reversed().let { o -> copy(options = o, correctOptionId = o.first().id, hint = "", parentTip = noTip) }
        is Stop.Word -> options.map { it.copy(id = opaque(id, it.id)) }.reversed().let { o -> copy(options = o, correctOptionId = o.first().id, hint = "", parentTip = noTip) }
        is Stop.ReadTap -> options.map { it.copy(id = opaque(id, it.id)) }.reversed().let { o -> copy(options = o, correctOptionId = o.first().id, hint = "", parentTip = noTip) }
        is Stop.MultiSelect -> copy(options = options.map { it.copy(id = opaque(id, it.id)) }.reversed(), correctIds = emptyList(), parentTip = noTip)
        is Stop.SelectAll -> copy(options = options.map { it.copy(id = opaque(id, it.id)) }.reversed(), correctIds = emptyList(), parentTip = noTip)
        is Stop.Order -> items.map { it.copy(id = opaque(id, it.id)) }.reversed().let { i -> copy(items = i, correctOrder = i.map { it.id }, parentTip = noTip) }
        // The right-hand tiles move one pair along: the pairing on the paper is not the answer.
        is Stop.Match -> copy(pairs = pairs.mapIndexed { i, p -> MatchPair(opaque(id, p.id), p.left, pairs[(i + 1) % pairs.size].right) }, parentTip = noTip)
        is Stop.Trace -> copy(hint = "", parentTip = noTip)
        is Stop.Retell -> copy(modelAnswer = "", parentTip = noTip)
        is Stop.OpenAnswer -> copy(modelAnswer = "", parentTip = noTip)
        is Stop.WriteSentence -> if (options != null && !free) options!!.reversed().let { o -> copy(options = o, answer = o.first(), parentTip = noTip) } else copy(parentTip = noTip)
        is Stop.ReadPage -> copy(tapTask = tapTask?.let { t -> t.copy(hotspots = t.hotspots.map { it.copy(id = opaque(id, it.id)) }, correctIds = emptyList()) }, parentTip = noTip)
        is Stop.ExitTicket -> copy(questions = questions.map { it.sealed() }, parentTip = noTip)
        is Stop.StoryPieces -> copy(parentTip = noTip)
        is Stop.WordCards -> copy(parentTip = noTip)
        is Stop.Move -> copy(parentTip = noTip)
        is Stop.Explain -> copy(parentTip = noTip)
    }
}
