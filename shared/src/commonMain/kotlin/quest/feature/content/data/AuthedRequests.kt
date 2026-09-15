package quest.feature.content.data

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import quest.api.AuthProvider

/**
 * The one place that turns an [AuthProvider] into the `Authorization: Bearer <Firebase ID token>` header every call
 * to our API carries. Signed out (`idToken() == null`) means no header — the server answers 401 and the screen shows
 * its empty state.
 */
suspend fun HttpRequestBuilder.authed(auth: AuthProvider, forceRefresh: Boolean = false) {
    auth.idToken(forceRefresh)?.let { bearer(it) }
}

fun HttpRequestBuilder.bearer(token: String) = header(HttpHeaders.Authorization, "Bearer $token")

/**
 * `GET url` with the bearer token, for the id-addressed media routes (`/media/pages/{id}`, `/media/child/{id}`) that
 * return raw bytes rather than JSON.
 *
 * Returns null without touching the network when nobody is signed in, so a signed-out app draws the placeholder
 * instead of leaking an anonymous request. A 401 is retried exactly once with a force-refreshed token: ID tokens
 * expire after an hour and a lesson can sit open for longer than that.
 */
suspend fun HttpClient.getAuthedBytes(url: String, auth: AuthProvider): HttpResponse? {
    val token = auth.idToken() ?: return null
    val first = get(url) { bearer(token) }
    if (first.status != HttpStatusCode.Unauthorized) return first
    val refreshed = auth.idToken(forceRefresh = true) ?: return first
    return get(url) { bearer(refreshed) }
}
