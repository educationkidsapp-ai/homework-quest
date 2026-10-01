package quest.feature.school.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import quest.api.ContentApi
import quest.api.DEFAULT_FLAGS
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

    private val _theme = MutableStateFlow<SchoolTheme?>(null)
    override val theme: StateFlow<SchoolTheme?> = _theme.asStateFlow()

    private val _flags = MutableStateFlow(DEFAULT_FLAGS)
    override val flags: StateFlow<Map<String, Boolean>> = _flags.asStateFlow()

    private val _branding = MutableStateFlow(SchoolBranding())
    override val branding: StateFlow<SchoolBranding> = _branding.asStateFlow()

    /**
     * The school whose **flags** apply — which is not always the school we theme as.
     *
     * [_schoolId] is the *themed* school and stays null for `default`, because the default school has no colours of
     * its own (F6). Flags are a different question: `default` is a real school an Admin turns features on for, and on
     * QA it is the only one there is. Keeping the two apart is what lets a child of the default school see the
     * features her school bought while the app still paints itself in the design's own colours.
     */
    private var flagsSchoolId: String? = null

    override suspend fun restore() {
        _branding.value = SchoolBranding(appName = settings.get(KEY_PLATFORM_NAME) ?: SchoolBranding.DEFAULT_APP_NAME)
        // Flags first, and on their own key: a child of the default school has flags to restore and no theme, so an
        // offline launch keeps her features instead of falling back to the platform defaults.
        settings.get(KEY_CURRENT_FLAGS)?.takeIf { it.isNotBlank() }?.let {
            flagsSchoolId = it
            readFlagsCache(it)
        }
        val id = settings.get(KEY_CURRENT)?.takeIf { it.isNotBlank() } ?: return
        _schoolId.value = id
        readCache(id)
    }

    override suspend fun use(schoolId: String) {
        // Her flags come from the school she is actually in, `default` included — see [flagsSchoolId].
        useFlagsOf(schoolId.takeIf { it.isNotBlank() })

        val id = schoolId.takeIf { it.isNotBlank() && it != DEFAULT_SCHOOL }
        if (id == null) {
            forgetTheme()
        } else {
            if (_schoolId.value != id) {
                _schoolId.value = id
                settings.set(KEY_CURRENT, id)
                readCache(id)
            }
        }
        sync()
    }

    /** Point the flag store at [id], answering from the device first so an offline launch is never feature-less. */
    private suspend fun useFlagsOf(id: String?) {
        if (flagsSchoolId == id) return
        flagsSchoolId = id
        settings.set(KEY_CURRENT_FLAGS, id)
        // The reset is unconditional: a school this device has never cached must start from the platform defaults,
        // not from whatever the previous school answered. Offline, nothing else would ever clear them.
        _flags.value = DEFAULT_FLAGS
        if (id != null) readFlagsCache(id)
    }

    override suspend fun sync() {
        // The platform's own name is fetched even before a child is known: it is what the sign-in screen calls the
        // app (§A).
        refreshPlatformName()
        // The flags of her own school — the default one has them too — and the theme only of a school that has one.
        flagsSchoolId?.let { refreshFlags(it) }
        _schoolId.value?.let { refreshTheme(it) }
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

    /** Back to the design's own colours and the platform name — a child of the default school — flags untouched. */
    private suspend fun forgetTheme() {
        _schoolId.value = null
        _theme.value = null
        _branding.value = SchoolBranding(appName = settings.get(KEY_PLATFORM_NAME)?.takeIf { it.isNotBlank() } ?: SchoolBranding.DEFAULT_APP_NAME)
        settings.set(KEY_CURRENT, null)
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
    }

    private suspend fun readFlagsCache(schoolId: String) {
        val json = settings.get(flagsKey(schoolId)) ?: return
        runCatching { QuestJson.decodeFromString(FLAGS, json) }.getOrNull()?.let { _flags.value = DEFAULT_FLAGS + it }
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

        /** `Child.schoolId` for a child of the default school; the app is then unthemed. */
        const val DEFAULT_SCHOOL = "default"

        const val KEY_CURRENT = "school.current"

        /** The school whose flags apply; written even for `default`, which [KEY_CURRENT] never holds. */
        const val KEY_CURRENT_FLAGS = "school.current.flags"

        const val KEY_PLATFORM_NAME = "platform.name"
        fun nameKey(schoolId: String) = "school.name.$schoolId"
        fun themeKey(schoolId: String) = "school.theme.$schoolId"
        fun etagKey(schoolId: String) = "school.theme.etag.$schoolId"
        fun flagsKey(schoolId: String) = "school.flags.$schoolId"
    }
}
