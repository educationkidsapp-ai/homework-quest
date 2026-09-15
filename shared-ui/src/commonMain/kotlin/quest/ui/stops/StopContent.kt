package quest.ui.stops

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import quest.ui.design.Dimens
import quest.api.dto.Stop

/**
 * Renders any stop (dev prompt §5). The same composables draw the app's player and the admin panel's
 * phone preview. Each stop owns its interaction state and reports [StopEvent]s; the player owns the
 * hint sheet, the correct overlay, the pot and the stars.
 */
@Composable
fun StopContent(stop: Stop, onEvent: (StopEvent) -> Unit, modifier: Modifier = Modifier, childName: String = "") {
    // an attached picture sits above any stop except readPage, which shows it in its own picture area
    val image = stop.imageId
    if (image != null && stop !is Stop.ReadPage) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            StopPicture(image)
            Spacer(Modifier.height(Dimens.s12))
            StopBody(stop, onEvent, Modifier, childName)
        }
    } else StopBody(stop, onEvent, modifier, childName)
}

@Composable
private fun StopBody(stop: Stop, onEvent: (StopEvent) -> Unit, modifier: Modifier, childName: String) {
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
