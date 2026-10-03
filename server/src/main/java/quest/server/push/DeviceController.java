package quest.server.push;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import quest.api.dto.RegisterDeviceRequest;
import quest.api.dto.UnregisterDeviceRequest;
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.config.Json;

/**
 * B4: the parent app registers the phone it runs on for push, and takes it back on sign-out. Parent-only (the
 * `/me/devices` matcher in `SecurityConfig`); her own rows, from her Firebase token. The permission is `parent.me.write`,
 * the one her own account already writes under: a phone is part of that account, and a key of its own would only be a
 * second name for the same "this parent, herself".
 *
 * <p>Carries no feature flag, and is listed as infrastructure in `FeatureFlagCoverageTest`: a phone is the parent's,
 * not a school's — she may have children in two — and sign-out has to be able to take the token back whatever any school
 * has switched off. What a push is <em>about</em> is gated where that feature lives: no `chat`, no message, no push.
 */
@RestController
@Tag(name = "Devices", description = "The parent's phones for push notifications (Firebase Cloud Messaging)")
public class DeviceController {
    private final DeviceService devices; private final Json json;
    public DeviceController(DeviceService devices, Json json) { this.devices = devices; this.json = json; }

    @PostMapping(value = "/me/devices", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('parent.me.write')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = RegisterDeviceRequest.class)))
    @ApiResponse(responseCode = "204", description = "Registered, or refreshed")
    public void registerDevice(@AuthenticationPrincipal Principals.Parent parent, @RequestBody String body) {
        devices.register(parent, decode(body, RegisterDeviceRequest.Companion.serializer()));
    }

    /**
     * Sign-out. The token travels in the body, never in the path: Cloud Run's request log and {@code RequestLogging}
     * record every path, and a token in one would be in the logs for as long as they are kept.
     */
    @PostMapping(value = "/me/devices/unregister", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('parent.me.write')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = UnregisterDeviceRequest.class)))
    @ApiResponse(responseCode = "204", description = "Gone, or never hers")
    public void unregisterDevice(@AuthenticationPrincipal Principals.Parent parent, @RequestBody String body) {
        devices.unregister(parent, decode(body, UnregisterDeviceRequest.Companion.serializer()).getToken());
    }

    /** The body is never echoed back or logged: it carries a token. */
    private <T> T decode(String body, kotlinx.serialization.KSerializer<T> serializer) {
        try { return json.decodeShared(body, serializer); }
        catch (RuntimeException e) { throw ApiException.badRequest("Send {\"token\": \"…\"} — and, to register, \"platform\": \"ANDROID\" | \"IOS\" and, optionally, appVersion and locale."); }
    }
}
