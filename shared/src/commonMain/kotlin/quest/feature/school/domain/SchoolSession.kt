package quest.feature.school.domain

import kotlinx.coroutines.flow.StateFlow
import quest.api.DEFAULT_FLAGS
import quest.api.dashboard.JoinSchoolInfo
import quest.api.dto.SchoolTheme

/**
 * §4 feature flags as the app reads them: a flat `{ key: boolean }` for the child's school, cached on the device and
 * refreshed on launch and every six hours. Before the first sync — and for a child in the default school — the values
 * are `DEFAULT_FLAGS`, so a feature that ships today is never hidden by a slow network.
 */
interface FlagStore {
    val flags: StateFlow<Map<String, Boolean>>

    /** An unknown key falls back to the platform default and, failing that, to off. */
    fun isEnabled(key: String): Boolean = flags.value[key] ?: DEFAULT_FLAGS[key] ?: false
}

/** §3 white label: the school's theme, and §A the name and logo shown in its place. */
interface SchoolThemeStore {
    /** Null until a themed school is joined; every screen then reads the token values. */
    val theme: StateFlow<SchoolTheme?>
    val branding: StateFlow<SchoolBranding>
}

/**
 * What the app is called and what it shows for a logo, resolved once (§A): the school's `appName`, else the
 * platform's name from `GET /platform-settings`, else the string the app shipped with.
 */
data class SchoolBranding(val appName: String = DEFAULT_APP_NAME, val logoUrl: String? = null, val schoolName: String? = null) {
    companion object {
        const val DEFAULT_APP_NAME = "Homework Quest"
    }
}

/**
 * The one school the app is currently themed by. It owns the id, the theme and the flags together because they are
 * always fetched, cached and invalidated as a set: a child belongs to exactly one school.
 */
interface SchoolSession : FlagStore, SchoolThemeStore {
    /** The current child's school; null while nobody has joined one (the child is in the default school). */
    val schoolId: StateFlow<String?>

    /** Reads the last school's cached theme and flags off the device. No network: it runs before the first frame. */
    suspend fun restore()

    /** `GET /schools/by-code/{code}`. Throws `ApiException(NOT_FOUND)` for a code no school has. */
    suspend fun lookUp(code: String): JoinSchoolInfo

    /**
     * The parent confirmed a school in Add child. The theme applies straight away — before the child exists, so the
     * rest of the form is already in the school's colours — but nothing is written to the device until [use].
     */
    suspend fun confirm(info: JoinSchoolInfo)

    /**
     * The parent backed out of the join before saving: drop the previewed school and go back to whatever the app was
     * actually wearing, so abandoning Add child never leaves the app in a school's colours.
     */
    suspend fun cancelJoin()

    /**
     * The current child's school changed: load its cached theme and flags, then refresh both from the server.
     * `"default"` (a child who joined no school) clears the theme, the branding and the flags instead.
     */
    suspend fun use(schoolId: String)

    /** Launch and every six hours (§4): re-reads the flags and revalidates the theme against its ETag. */
    suspend fun sync()
}
