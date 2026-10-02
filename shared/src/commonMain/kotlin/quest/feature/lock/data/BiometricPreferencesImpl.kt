package quest.feature.lock.data

import quest.core.db.SettingsStore
import quest.feature.lock.domain.BiometricChoice
import quest.feature.lock.domain.BiometricPreferences

/**
 * One setting, `<uid>|<choice>`: a device holds one signed-in account at a time, and a value written by another
 * account reads as "not asked" for this one. Nothing secret is stored — it is a yes or a no.
 */
class BiometricPreferencesImpl(private val settings: SettingsStore) : BiometricPreferences {
    private suspend fun stored(): Pair<String, BiometricChoice>? {
        val raw = settings.get(KEY) ?: return null
        val choice = BiometricChoice.entries.firstOrNull { it.name == raw.substringAfterLast('|') } ?: return null
        return raw.substringBeforeLast('|') to choice
    }

    override suspend fun choice(uid: String): BiometricChoice = stored()?.takeIf { it.first == uid }?.second ?: BiometricChoice.NOT_ASKED

    override suspend fun set(uid: String, choice: BiometricChoice) = settings.set(KEY, "$uid|${choice.name}")

    override suspend fun signedOut() {
        val (uid, choice) = stored() ?: return
        if (choice == BiometricChoice.ENABLED) set(uid, BiometricChoice.DECLINED)
    }

    private companion object { const val KEY = "biometricUnlock" }
}
