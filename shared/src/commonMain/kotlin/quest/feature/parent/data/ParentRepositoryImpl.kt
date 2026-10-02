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
    // Read from the store synchronously, once, when this object is built — which is before the first frame (the root
    // composable asks for it), so the app never starts in the device's palette and then switches to the chosen one.
    private val _appearance = MutableStateFlow(Appearance.of(settings.appearanceNow()))
    override val appearance: StateFlow<Appearance> = _appearance
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
