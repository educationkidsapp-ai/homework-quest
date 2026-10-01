package quest.feature.school.domain

import kotlinx.coroutines.flow.StateFlow
import quest.api.DEFAULT_FLAGS
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
    /** Null until the current child belongs to a themed school; every screen then reads the token values. */
    val theme: StateFlow<SchoolTheme?>
    val branding: StateFlow<SchoolBranding>
}

/**
 * What the app is called and what it shows for a logo, resolved once (§A): the school's `appName`, else the
 * platform's name from `GET /platform-settings`, else the string the app shipped with.
 */
data class SchoolBranding(val appName: String = DEFAULT_APP_NAME, val logoUrl: String? = null, val schoolName: String? = null) {
    companion object {
        const val DEFAULT_APP_NAME = "MySchool"
    }
}

/**
 * The one school the app is currently themed by. It owns the id, the theme and the flags together because they are
 * always fetched, cached and invalidated as a set: a child belongs to exactly one school.
 */
interface SchoolSession : FlagStore, SchoolThemeStore {
    /** The current child's school; null for a child of the default school, which has no theme of its own. */
    val schoolId: StateFlow<String?>

    /** Reads the last school's cached theme and flags off the device. No network: it runs before the first frame. */
    suspend fun restore()

    /**
     * The current child's school changed: load its cached theme and flags, then refresh both from the server.
     * `"default"` (a child of the default school) clears the theme, the branding and the flags instead.
     */
    suspend fun use(schoolId: String)

    /** Launch and every six hours (§4): re-reads the flags and revalidates the theme against its ETag. */
    suspend fun sync()
}
