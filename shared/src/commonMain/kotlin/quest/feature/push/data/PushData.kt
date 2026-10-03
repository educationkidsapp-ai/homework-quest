package quest.feature.push.data

import quest.api.ContentApi
import quest.api.dto.RegisterDeviceRequest
import quest.core.db.SettingsStore
import quest.feature.push.domain.PushPreferences
import quest.feature.push.domain.PushRegistrar

/** B4's two routes through whichever [ContentApi] is bound — the server's, or the in-app fake's, which remembers. */
class ApiPushRegistrar(private val api: ContentApi) : PushRegistrar {
    override suspend fun register(device: RegisterDeviceRequest) = api.registerDevice(device)
    override suspend fun unregister(token: String) = api.unregisterDevice(token)
}

class PushPreferencesImpl(private val settings: SettingsStore) : PushPreferences {
    override suspend fun registeredToken(): String? = settings.get(KEY_TOKEN)
    override suspend fun setRegisteredToken(token: String?) = settings.set(KEY_TOKEN, token)
    override suspend fun promptAnswered(): Boolean = settings.get(KEY_PROMPT) == "1"
    override suspend fun setPromptAnswered() = settings.set(KEY_PROMPT, "1")

    private companion object {
        const val KEY_TOKEN = "pushToken"
        const val KEY_PROMPT = "pushPromptAnswered"
    }
}
