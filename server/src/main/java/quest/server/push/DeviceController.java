package quest.server.push;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import quest.api.dto.RegisterDeviceRequest;
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
        devices.register(parent, decode(body));
    }

    @DeleteMapping(value = "/me/devices/{token}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('parent.me.write')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "204", description = "Gone, or never hers")
    public void unregisterDevice(@AuthenticationPrincipal Principals.Parent parent, @PathVariable String token) {
        devices.unregister(parent, token);
    }

    private RegisterDeviceRequest decode(String body) {
        try { return json.decodeShared(body, RegisterDeviceRequest.Companion.serializer()); }
        catch (RuntimeException e) { throw ApiException.badRequest("Send {\"token\": \"…\", \"platform\": \"ANDROID\" | \"IOS\"} and, optionally, appVersion and locale."); }
    }
}
