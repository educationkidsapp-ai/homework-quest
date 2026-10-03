package quest.feature.content.data

import quest.api.dto.Bilingual
import quest.api.dto.MatchPair
import quest.api.dto.Play
import quest.api.dto.PublishedLesson
import quest.api.dto.Stop

/**
 * B3 parity for [FakeContentApi]: an exam paper as the server sends it before release — [Play.sealed] `true`, every
 * option, tile, item, pair and hotspot id opaque, every key field a placeholder (the first option, `false`, `[]`, the
 * items in the order sent, the first option(s) sent), hints, model answers and parent tips "…", number-line
 * highlights empty, and a match stop's
 * right-hand tiles moved off their pairs. The app must sit it without ever reading the key.
 */
fun PublishedLesson.sealedForChild(): PublishedLesson =
    copy(plays = plays.map { it.sealed() }, variant = variant.sealed(), examPlay = examPlay?.sealed(), parentPanel = parentPanel.copy(stopTips = parentPanel.stopTips.map { it.copy(en = PLACEHOLDER, ar = PLACEHOLDER) }, modelAnswers = parentPanel.modelAnswers.map { it.copy(en = PLACEHOLDER) }))

fun Play.sealed(): Play = copy(stops = stops.map { it.sealed() }, sealed = true)

/** What the server writes where the schema requires text it must not give away. */
const val PLACEHOLDER = "…"
private val noTip = Bilingual(PLACEHOLDER, PLACEHOLDER)
private fun opaque(stopId: String, id: String) = "s" + (stopId + "/" + id).hashCode().toUInt().toString(36)

private fun Stop.sealed(): Stop {
    return when (this) {
        is Stop.Choice -> options.map { it.copy(id = opaque(id, it.id)) }.reversed().let { o -> copy(options = o, correctOptionId = o.first().id, hint = PLACEHOLDER, parentTip = noTip) }
        is Stop.TrueFalse -> copy(answer = false, hint = PLACEHOLDER, parentTip = noTip)
        is Stop.Sequence -> options.map { it.copy(id = opaque(id, it.id)) }.reversed().let { o -> copy(options = o, correctOptionId = o.first().id, hint = PLACEHOLDER, numberLine = numberLine.copy(highlight = emptyList()), parentTip = noTip) }
        is Stop.Count -> options.map { it.copy(id = opaque(id, it.id)) }.reversed().let { o -> copy(options = o, correctOptionId = o.first().id, hint = PLACEHOLDER, numberLine = numberLine.copy(highlight = emptyList()), parentTip = noTip) }
        is Stop.Compare -> options.map { it.copy(id = opaque(id, it.id)) }.reversed().let { o -> copy(options = o, correctOptionId = o.first().id, hint = PLACEHOLDER, numberLine = numberLine.copy(highlight = emptyList()), parentTip = noTip) }
        is Stop.Sound -> options.map { it.copy(id = opaque(id, it.id)) }.reversed().let { o -> copy(options = o, correctOptionId = o.first().id, hint = PLACEHOLDER, parentTip = noTip) }
        is Stop.Word -> options.map { it.copy(id = opaque(id, it.id)) }.reversed().let { o -> copy(options = o, correctOptionId = o.first().id, hint = PLACEHOLDER, parentTip = noTip) }
        is Stop.ReadTap -> options.map { it.copy(id = opaque(id, it.id)) }.reversed().let { o -> copy(options = o, correctOptionId = o.first().id, hint = PLACEHOLDER, parentTip = noTip) }
        is Stop.MultiSelect -> options.map { it.copy(id = opaque(id, it.id)) }.reversed().let { o -> copy(options = o, correctIds = o.take(pick).map { it.id }, parentTip = noTip) }
        is Stop.SelectAll -> options.map { it.copy(id = opaque(id, it.id)) }.reversed().let { o -> copy(options = o, correctIds = o.take(1).map { it.id }, parentTip = noTip) }
        is Stop.Order -> items.map { it.copy(id = opaque(id, it.id)) }.reversed().let { i -> copy(items = i, correctOrder = i.map { it.id }, parentTip = noTip) }
        // The right-hand tiles move one pair along: the pairing on the paper is not the answer.
        is Stop.Match -> copy(pairs = pairs.mapIndexed { i, p -> MatchPair(opaque(id, p.id), p.left, pairs[(i + 1) % pairs.size].right) }, parentTip = noTip)
        is Stop.Trace -> copy(hint = PLACEHOLDER, parentTip = noTip)
        is Stop.Retell -> copy(modelAnswer = PLACEHOLDER, parentTip = noTip)
        is Stop.OpenAnswer -> copy(modelAnswer = PLACEHOLDER, parentTip = noTip)
        is Stop.WriteSentence -> if (options != null && !free) options!!.reversed().let { o -> copy(options = o, answer = o.first(), parentTip = noTip) } else copy(parentTip = noTip)
        is Stop.ReadPage -> copy(tapTask = tapTask?.let { t -> t.hotspots.map { it.copy(id = opaque(id, it.id)) }.let { h -> t.copy(hotspots = h, correctIds = h.take(1).map { it.id }) } }, parentTip = noTip)
        is Stop.ExitTicket -> copy(questions = questions.map { it.sealed() }, parentTip = noTip)
        is Stop.StoryPieces -> copy(parentTip = noTip)
        is Stop.WordCards -> copy(parentTip = noTip)
        is Stop.Move -> copy(parentTip = noTip)
        is Stop.Explain -> copy(parentTip = noTip)
    }
}
