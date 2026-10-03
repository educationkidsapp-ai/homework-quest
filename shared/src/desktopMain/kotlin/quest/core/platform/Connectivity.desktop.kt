package quest.core.platform

import org.koin.core.module.Module
import org.koin.dsl.module

/** The desktop build has no network monitor: it is treated as online, and a failed request still says otherwise. */
actual fun connectivityModule(): Module = module {
    single<Connectivity> { ManualConnectivity(initial = true) }
}
