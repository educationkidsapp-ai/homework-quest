package quest.feature.content.domain

import quest.api.dto.PlatformSettings
import quest.api.dto.SchoolTheme

/**
 * The public, token-less routes the app needs for §3 white label. They are not on `ContentApi` because `shared-api`
 * describes what a *signed-in* parent asks for; these are answered before anyone has signed in.
 *
 * Both `RemoteContentApi` and `FakeContentApi` implement it, so Koin binds the one `ContentApi` singleton to this
 * interface as well and no screen can tell which is behind it.
 */
interface SchoolApi {
    /**
     * `GET /schools/{id}/theme` with `If-None-Match`. The route is `max-age=300` and ETagged, so the launch refresh
     * costs one 304 on most days. [ifNoneMatch] null always fetches.
     */
    suspend fun schoolTheme(schoolId: String, ifNoneMatch: String?): ThemeFetch

    /** `GET /platform-settings` — the platform's own name, used when a school has no `appName` of its own (§A). */
    suspend fun platformSettings(): PlatformSettings
}

/**
 * The answer to a conditional theme fetch. [notModified] means the server answered 304 and the caller should keep the
 * theme it cached; [theme] is then null.
 */
data class ThemeFetch(val theme: SchoolTheme?, val etag: String?, val notModified: Boolean = false)
