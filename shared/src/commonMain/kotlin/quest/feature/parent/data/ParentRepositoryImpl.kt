package quest.feature.parent.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import quest.feature.parent.domain.Appearance
import quest.core.db.SettingsStore
import quest.feature.parent.domain.ParentRepository
import quest.feature.parent.domain.ParentSettings
import quest.feature.parent.domain.PinHasher

class ParentRepositoryImpl(private val settings: SettingsStore) : ParentRepository {
    override val language get() = settings.language
    private val _appearance = MutableStateFlow(Appearance.SYSTEM)
    // Re-read from the store on every access: this object may be built before `SettingsStore.load()` has run.
    override val appearance: StateFlow<Appearance> get() = _appearance.also { it.value = Appearance.of(settings.appearance.value) }
    override suspend fun setAppearance(appearance: Appearance) {
        settings.set(SettingsStore.KEY_APPEARANCE, appearance.key)
        _appearance.value = appearance
    }
    override suspend fun hasPin(): Boolean = settings.get(SettingsStore.KEY_PIN_HASH) != null
    override suspend fun setPin(pin: String) = settings.set(SettingsStore.KEY_PIN_HASH, PinHasher.hash(pin))
    override suspend fun verifyPin(pin: String): Boolean = settings.get(SettingsStore.KEY_PIN_HASH)?.let { PinHasher.verify(pin, it) } ?: false
    override suspend fun settings() = ParentSettings(settings.language())
    override suspend fun setLanguage(code: String) = settings.setLanguage(code)
}
