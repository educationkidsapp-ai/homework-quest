package quest.ui.stops

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import quest.api.dto.Stop

/**
 * Renders any stop (dev prompt §5). The same composables draw the app's player and the admin panel's
 * phone preview. Each stop owns its interaction state and reports [StopEvent]s; the player owns the
 * hint sheet, the correct overlay, the pot and the stars.
 */
@Composable
fun StopContent(stop: Stop, onEvent: (StopEvent) -> Unit, modifier: Modifier = Modifier, childName: String = "") {
    when (stop) {
        is Stop.ReadPage -> ReadPageStop(stop, onEvent, modifier)
        is Stop.StoryPieces -> StoryPiecesStop(stop, onEvent, modifier)
        is Stop.WordCards -> WordCardsStop(stop, onEvent, modifier)
        is Stop.Move -> MoveStop(stop, onEvent, modifier)
        is Stop.Explain -> ExplainStop(stop, onEvent, modifier)
        is Stop.Choice -> ChoiceStop(stop, onEvent, modifier)
        is Stop.TrueFalse -> TrueFalseStop(stop, onEvent, modifier)
        is Stop.Sequence -> SequenceStop(stop, onEvent, modifier)
        is Stop.Count -> CountStop(stop, onEvent, modifier)
        is Stop.Compare -> CompareStop(stop, onEvent, modifier)
        is Stop.Sound -> SoundStop(stop, onEvent, modifier)
        is Stop.Word -> WordStop(stop, onEvent, modifier)
        is Stop.ReadTap -> ReadTapStop(stop, onEvent, modifier)
        is Stop.MultiSelect -> MultiSelectStop(stop, onEvent, modifier)
        is Stop.SelectAll -> SelectAllStop(stop, onEvent, modifier)
        is Stop.Match -> MatchStop(stop, onEvent, modifier)
        is Stop.Order -> OrderStop(stop, onEvent, modifier)
        is Stop.Trace -> TraceStop(stop, onEvent, modifier)
        is Stop.Retell -> RetellStop(stop, onEvent, modifier)
        is Stop.OpenAnswer -> OpenAnswerStop(stop, onEvent, modifier)
        is Stop.WriteSentence -> WriteSentenceStop(stop, onEvent, modifier)
        is Stop.ExitTicket -> ExitTicketStop(stop, onEvent, modifier)
    }
}
