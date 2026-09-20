package quest.feature.content.domain

import quest.api.dashboard.ClassLookup
import quest.api.dashboard.JoinSchoolInfo
import quest.api.dto.PlatformSettings
import quest.api.dto.SchoolTheme

/**
 * The public, token-less routes the app needs for §2 join-school and §3 white label. They are not on
 * `ContentApi` because `shared-api` describes what a *signed-in* parent asks for; these are answered before anyone
 * has signed in, and `GET /schools/by-code/{code}` is defined on the dashboard contract.
 *
 * Both `RemoteContentApi` and `FakeContentApi` implement it, so Koin binds the one `ContentApi` singleton to this
 * interface as well and no screen can tell which is behind it.
 */
interface SchoolApi {
    /** `GET /schools/by-code/{code}` — the name, logo, curriculum/grade options and theme behind a 6-character code. */
    suspend fun schoolByCode(code: String): JoinSchoolInfo

    /**
     * `POST /classes/lookup` — the section behind the join code printed on a class's card (`docs/teacher-flow.md` §2):
     * its name, course and school, and deliberately nothing else. The code travels in a **body**, not a query string,
     * because it is a credential; an unknown, disabled or inactive code is the same uniform 404.
     */
    suspend fun classByJoinCode(code: String): ClassLookup

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
