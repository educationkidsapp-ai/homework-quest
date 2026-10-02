package quest.core.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.module.Module

/**
 * M4 (D3): whether the device has a usable network right now — Android's `ConnectivityManager.NetworkCallback`, iOS's
 * `NWPathMonitor`. It is a hint, not a promise: "online" only means it is worth trying the server again, and every
 * request still decides for itself whether it got through.
 */
interface Connectivity {
    val online: StateFlow<Boolean>
}

/** A source the caller moves by hand: the desktop build (always online) and the tests. */
class ManualConnectivity(initial: Boolean = true) : Connectivity {
    private val state = MutableStateFlow(initial)
    override val online: StateFlow<Boolean> = state.asStateFlow()
    fun set(online: Boolean) { state.value = online }
}

/** Binds the platform's [Connectivity]; a module of its own so `platformModule()` stays as it was. */
expect fun connectivityModule(): Module
