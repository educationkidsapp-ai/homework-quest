package quest.core.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Marker interfaces so every feature contract reads the same way. */
interface MviState
interface MviIntent
interface MviEffect

/**
 * MVI base: a single [state] stream, an [effects] stream for one-shot UI events (navigation, speech,
 * haptics), and a sequential intent queue so reducers never race.
 *
 * Long-running work inside [handle] should call [launch] so it does not block later intents.
 */
abstract class MviViewModel<S : MviState, I : MviIntent, E : MviEffect>(initial: S) : ViewModel() {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<S> = _state.asStateFlow()

    private val _effects = Channel<E>(Channel.BUFFERED)
    val effects: Flow<E> = _effects.receiveAsFlow()

    private val intents = Channel<I>(Channel.UNLIMITED)

    protected val current: S get() = _state.value

    init {
        viewModelScope.launch { for (intent in intents) handle(intent) }
    }

    fun dispatch(intent: I) { intents.trySend(intent) }

    protected abstract suspend fun handle(intent: I)

    protected fun reduce(reducer: S.() -> S) = _state.update(reducer)

    protected suspend fun effect(effect: E) = _effects.send(effect)

    protected fun launch(block: suspend CoroutineScope.() -> Unit): Job = viewModelScope.launch(block = block)
}
