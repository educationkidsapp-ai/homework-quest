package quest.feature.content.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import quest.core.platform.Connectivity
import quest.core.runCancellable
import quest.feature.children.domain.ChildrenRepository

/**
 * M4 (D3): answers kept on the device go up **by themselves** when the network is back — the student no longer has to
 * find "Try again". A pass is started when [Connectivity] turns online and whenever [nudge] is called (the app came
 * to the front, or the exam screen has just failed to hand in); it sends every lesson that still has answers on the
 * device, one request per lesson ([JourneyRepository.submit]), and while some are still not reached and the device
 * still claims a network, it tries again after [backoffMillis] — 2 s, 5 s, 15 s, 30 s, then every minute. A new
 * trigger during a wait starts the next try at once rather than waiting the wait out.
 *
 * Nothing here decides that an exam is handed in: [settled] only says what the server answered, and the exam screen
 * still asks the server itself before it shows "Submitted" (M1d's rule). A closed exam's answers stay on the device by
 * design (see [SubmitOutcome.CLOSED]); they are reported as settled and are not retried until the next trigger.
 */
class PendingAnswersSync(
    private val connectivity: Connectivity,
    private val journey: JourneyRepository,
    private val children: ChildrenRepository,
    private val backoffMillis: List<Long> = listOf(2_000, 5_000, 15_000, 30_000, 60_000),
) {
    private val triggers = Channel<Unit>(Channel.CONFLATED)
    private val _settled = MutableSharedFlow<Map<String, SubmitOutcome>>(extraBufferCapacity = 16)

    /** After each pass: the lessons the server gave an answer for (never [SubmitOutcome.QUEUED]), and what it said. */
    val settled: SharedFlow<Map<String, SubmitOutcome>> = _settled.asSharedFlow()

    /** Try now: the app came back to the front, or a screen just failed to deliver. */
    fun nudge() { triggers.trySend(Unit) }

    /** Runs until [scope] is cancelled; called once, by the app's initialiser. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        launch { connectivity.online.collect { online -> if (online) nudge() } }
        for (trigger in triggers) deliverUntilSettled()
    }

    private suspend fun deliverUntilSettled() {
        var attempt = 0
        while (true) {
            val outcomes = pass() ?: return
            val settled = outcomes.filterValues { it != SubmitOutcome.QUEUED }
            if (settled.isNotEmpty()) _settled.emit(settled)
            if (SubmitOutcome.QUEUED !in outcomes.values || !connectivity.online.value) return
            val wait = backoffMillis[attempt.coerceAtMost(backoffMillis.lastIndex)]
            attempt++
            // Either the wait runs out, or another trigger arrives first — both mean "try again now".
            withTimeoutOrNull(wait) { triggers.receive() }
            // Gone offline while waiting: the network's return is the next trigger, not the clock.
            if (!connectivity.online.value) return
        }
    }

    /** One request per lesson with answers still on the device; null when there is nothing to send. */
    private suspend fun pass(): Map<String, SubmitOutcome>? {
        val child = children.currentChild.value ?: return null
        val pending = runCancellable { journey.pending(child.id) }.getOrNull().orEmpty()
        if (pending.isEmpty()) return null
        return pending.keys.associateWith { lessonId -> runCancellable { journey.submit(child.id, lessonId) }.getOrDefault(SubmitOutcome.QUEUED) }
    }
}
