package quest.admin.di

import quest.admin.BuildConfig
import quest.admin.feature.auth.data.RemoteAdminApi
import quest.admin.feature.auth.domain.SessionStore
import quest.api.AdminApi

/** Hand-wired singletons (the admin panel is small enough not to need a container). */
object Graph {
    val session: SessionStore by lazy { SessionStore() }
    val remote: RemoteAdminApi by lazy { RemoteAdminApi(BuildConfig.API_BASE_URL, { session.token() }) }
    val api: AdminApi get() = remote
    /** Empty at build time (the production bundle) means "same origin": the server serves the panel under /panel/. */
    val apiBaseUrl: String get() = BuildConfig.API_BASE_URL.ifBlank { quest.admin.core.platform.Browser.origin() }
}
