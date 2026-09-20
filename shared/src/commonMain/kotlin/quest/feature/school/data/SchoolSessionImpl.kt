package quest.feature.school.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import quest.api.ContentApi
import quest.api.DEFAULT_FLAGS
import quest.api.dashboard.ClassLookup
import quest.api.dashboard.JoinSchoolInfo
import quest.api.dto.SchoolTheme
import quest.core.db.QuestJson
import quest.core.db.SettingsStore
import quest.core.runCancellable
import quest.feature.content.domain.SchoolApi
import quest.feature.school.domain.SchoolBranding
import quest.feature.school.domain.SchoolSession

/**
 * The school theme + flag store (§3, §4), cached in [SettingsStore] rather than a table of its own: it is one small
 * JSON blob and one flat map per school, read once per launch and written only when the server sends something new.
 *
 * Every read is cache-first: [restore] paints the last school's colours before the first frame, [use] answers from the
 * device and only then goes to the network, and a failed refresh leaves the cached values exactly where they were —
 * an offline launch is themed and fully featured, never a blank app.
 */
class SchoolSessionImpl(
    private val api: ContentApi,
    private val schools: SchoolApi,
    private val settings: SettingsStore,
) : SchoolSession {

    private val _schoolId = MutableStateFlow<String?>(null)
    override val schoolId: StateFlow<String?> = _schoolId.asStateFlow()

    private val _joinedCode = MutableStateFlow<String?>(null)
    override val joinedCode: StateFlow<String?> = _joinedCode.asStateFlow()

    private val _theme = MutableStateFlow<SchoolTheme?>(null)
    override val theme: StateFlow<SchoolTheme?> = _theme.asStateFlow()

    private val _flags = MutableStateFlow(DEFAULT_FLAGS)
    override val flags: StateFlow<Map<String, Boolean>> = _flags.asStateFlow()

    private val _branding = MutableStateFlow(SchoolBranding())
    override val branding: StateFlow<SchoolBranding> = _branding.asStateFlow()

    /** The school the parent confirmed in Add child, kept until the created child tells us its id. */
    private var confirmed: JoinSchoolInfo? = null

    /** The code that found [confirmed]; stored beside the school id so Add child never asks for it twice. */
    private var confirmedCode: String? = null

    override suspend fun restore() {
        _branding.value = SchoolBranding(appName = settings.get(KEY_PLATFORM_NAME) ?: SchoolBranding.DEFAULT_APP_NAME)
        val id = settings.get(KEY_CURRENT)?.takeIf { it.isNotBlank() } ?: return
        _schoolId.value = id
        _joinedCode.value = settings.get(KEY_CURRENT_CODE)?.takeIf { it.isNotBlank() }
        readCache(id)
    }

    override suspend fun lookUp(code: String): JoinSchoolInfo = schools.schoolByCode(code.trim().uppercase())

    override suspend fun lookUpClass(code: String): ClassLookup = schools.classByJoinCode(code.trim().uppercase())

    override suspend fun confirm(code: String, info: JoinSchoolInfo) {
        info.theme?.let { _theme.value = it }
        // The id is not known until the child is created, so the name and the code are held here and written in [use].
        confirmed = info
        confirmedCode = code
        _branding.value = brandingFor(info.theme, info.logoUrl, info.name)
    }

    override suspend fun cancelJoin() {
        confirmed = null
        confirmedCode = null
        val id = _schoolId.value
        if (id == null) forget() else readCache(id)
    }

    override suspend fun use(schoolId: String) {
        val id = schoolId.takeIf { it.isNotBlank() && it != DEFAULT_SCHOOL } ?: return forget()
        if (_schoolId.value != id) {
            _schoolId.value = id
            settings.set(KEY_CURRENT, id)
            readCache(id)
        }
        // The name only ever arrives with the join lookup; without it a relaunch would lose the header logo.
        confirmed?.let { info ->
            settings.set(nameKey(id), info.name)
            _branding.value = brandingFor(info.theme ?: _theme.value, info.logoUrl, info.name)
            confirmed = null
        }
        // The code is the parent's, not the child's: it is written once and then reused for every later child.
        confirmedCode?.let { code ->
            settings.set(KEY_CURRENT_CODE, code)
            _joinedCode.value = code
            confirmedCode = null
        }
        sync()
    }

    override suspend fun sync() {
        // The platform's own name is fetched even with no school joined: it is what the sign-in screen and parent mode
        // call the app before anyone has typed a code (§A).
        refreshPlatformName()
        val id = _schoolId.value ?: return
        refreshFlags(id)
        refreshTheme(id)
    }

    // ---- network refresh; every failure keeps what the device already has ------------------------------------------

    private suspend fun refreshFlags(schoolId: String) {
        val fetched = runCancellable { api.schoolFlags(schoolId) }.getOrNull() ?: return
        // The server is authoritative for the keys it sends; a key it has never heard of keeps its platform default,
        // so an app that ships a flag before the server seeds it still runs.
        val merged = DEFAULT_FLAGS + fetched
        _flags.value = merged
        settings.set(flagsKey(schoolId), QuestJson.encodeToString(FLAGS, merged))
    }

    private suspend fun refreshTheme(schoolId: String) {
        val cachedEtag = settings.get(etagKey(schoolId))
        val fetch = runCancellable { schools.schoolTheme(schoolId, cachedEtag) }.getOrNull() ?: return
        if (fetch.notModified) return
        val theme = fetch.theme ?: return
        _theme.value = theme
        _branding.value = brandingFor(theme, theme.logoUrl, _branding.value.schoolName)
        settings.set(themeKey(schoolId), QuestJson.encodeToString(SchoolTheme.serializer(), theme))
        settings.set(etagKey(schoolId), fetch.etag)
    }

    /** §A: the platform's own name, so a school without an `appName` still shows the product's current name. */
    private suspend fun refreshPlatformName() {
        val name = runCancellable { schools.platformSettings() }.getOrNull()?.name?.takeIf { it.isNotBlank() } ?: return
        settings.set(KEY_PLATFORM_NAME, name)
        if (_theme.value?.appName.isNullOrBlank()) _branding.value = _branding.value.copy(appName = name)
    }

    /** Back to the unthemed app: a child in the default school gets the design's own colours and the platform name. */
    private suspend fun forget() {
        confirmed = null
        confirmedCode = null
        _schoolId.value = null
        _joinedCode.value = null
        _theme.value = null
        _flags.value = DEFAULT_FLAGS
        _branding.value = SchoolBranding(appName = settings.get(KEY_PLATFORM_NAME)?.takeIf { it.isNotBlank() } ?: SchoolBranding.DEFAULT_APP_NAME)
        settings.set(KEY_CURRENT, null)
        settings.set(KEY_CURRENT_CODE, null)
    }

    // ---- device cache ---------------------------------------------------------------------------------------------

    private suspend fun readCache(schoolId: String) {
        val name = settings.get(nameKey(schoolId))?.takeIf { it.isNotBlank() }
        _branding.value = _branding.value.copy(schoolName = name)
        settings.get(themeKey(schoolId))?.let { json ->
            runCatching { QuestJson.decodeFromString(SchoolTheme.serializer(), json) }.getOrNull()?.let { theme ->
                _theme.value = theme
                _branding.value = brandingFor(theme, theme.logoUrl, _branding.value.schoolName)
            }
        }
        settings.get(flagsKey(schoolId))?.let { json ->
            runCatching { QuestJson.decodeFromString(FLAGS, json) }.getOrNull()?.let { _flags.value = DEFAULT_FLAGS + it }
        }
    }

    /** §A's resolution order: the school's `appName`, else the platform name we last saw, else the shipped string. */
    private suspend fun brandingFor(theme: SchoolTheme?, logoUrl: String?, schoolName: String?): SchoolBranding {
        val platform = settings.get(KEY_PLATFORM_NAME)?.takeIf { it.isNotBlank() } ?: SchoolBranding.DEFAULT_APP_NAME
        return SchoolBranding(
            appName = theme?.appName?.takeIf { it.isNotBlank() } ?: platform,
            logoUrl = logoUrl?.takeIf { it.isNotBlank() } ?: theme?.logoUrl?.takeIf { it.isNotBlank() },
            schoolName = schoolName,
        )
    }

    companion object {
        private val FLAGS = MapSerializer(String.serializer(), Boolean.serializer())

        /** `Child.schoolId` for a child who joined no school; the app is then unthemed. */
        const val DEFAULT_SCHOOL = "default"

        const val KEY_CURRENT = "school.current"

        /** The code the parent joined with (D16 slice 2); one per device, because one parent signs in on it. */
        const val KEY_CURRENT_CODE = "school.current.code"
        const val KEY_PLATFORM_NAME = "platform.name"
        fun nameKey(schoolId: String) = "school.name.$schoolId"
        fun themeKey(schoolId: String) = "school.theme.$schoolId"
        fun etagKey(schoolId: String) = "school.theme.etag.$schoolId"
        fun flagsKey(schoolId: String) = "school.flags.$schoolId"
    }
}
