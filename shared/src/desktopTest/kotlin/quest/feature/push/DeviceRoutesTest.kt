package quest.feature.push

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import quest.api.AuthProvider
import quest.api.AuthState
import quest.api.dto.DevicePlatform
import quest.api.dto.RegisterDeviceRequest
import quest.feature.content.data.RemoteContentApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** M5 on B4's fc67b9d: both device routes carry the push token in a JSON body — never in a URL. */
class DeviceRoutesTest {
    private val token = "fcm:secret-token-value"
    private val auth = object : AuthProvider {
        override val state = MutableStateFlow<AuthState>(AuthState.SignedIn("u", "p@school.test"))
        override suspend fun signIn(email: String, password: String) = Unit
        override suspend fun signOut() = Unit
        override suspend fun idToken(forceRefresh: Boolean) = "bearer"
    }

    private fun api(seen: MutableList<HttpRequestData>) =
        RemoteContentApi("http://server.test", auth, HttpClient(MockEngine { request -> seen += request; respond("", HttpStatusCode.NoContent) }))

    private fun body(r: HttpRequestData) = (r.body as TextContent).text

    @Test fun registerAndUnregisterPostTheTokenInTheBody() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val api = api(seen)
        api.registerDevice(RegisterDeviceRequest(token, DevicePlatform.ANDROID, "0.1.0", "ar"))
        api.unregisterDevice(token)
        assertEquals(listOf(HttpMethod.Post, HttpMethod.Post), seen.map { it.method })
        assertEquals(listOf("/me/devices", "/me/devices/unregister"), seen.map { it.url.encodedPath })
        seen.forEach { assertFalse(token in it.url.toString() || "secret-token-value" in it.url.toString(), "no token in ${it.url}") }
        assertTrue("\"token\":\"$token\"" in body(seen[1]), body(seen[1]))
        assertTrue("\"platform\":\"ANDROID\"" in body(seen[0]) && "\"locale\":\"ar\"" in body(seen[0]), body(seen[0]))
    }
}
