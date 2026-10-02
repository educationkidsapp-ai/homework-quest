package quest.core.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_monitor_t
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_get_main_queue

/** `NWPathMonitor` for the life of the process: a satisfied path is online. */
class IosConnectivity : Connectivity {
    private val state = MutableStateFlow(true)
    override val online: StateFlow<Boolean> = state.asStateFlow()
    private val monitor: nw_path_monitor_t = nw_path_monitor_create()

    init {
        nw_path_monitor_set_update_handler(monitor) { path -> state.value = nw_path_get_status(path) == nw_path_status_satisfied }
        nw_path_monitor_set_queue(monitor, dispatch_get_main_queue())
        nw_path_monitor_start(monitor)
    }
}

actual fun connectivityModule(): Module = module {
    single<Connectivity> { IosConnectivity() }
}
