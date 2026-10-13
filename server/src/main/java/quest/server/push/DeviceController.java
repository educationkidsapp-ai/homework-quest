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
 * B4: the app registers the phone it runs on for push, and takes it back on sign-out. Her own rows, resolved from her
 * token and never from the request.
 *
 * <p>Feature 13 opened both routes to staff as well, so the permission is now `device.register` — PARENT and the five
 * staff roles — rather than `parent.me.write`, which is the parent's own account and nothing a teacher holds. Which
 * table a registration lands in is the principal's: a Firebase parent writes `parent_devices`, a dashboard or worker
 * token writes `staff_devices` ({@link DeviceService}).
 *
 * <p>Carries no feature flag, and is listed as infrastructure in `FeatureFlagCoverageTest`: a phone belongs to a
 * person rather than to a school — a parent may have children in two — and sign-out has to be able to take the token
 * back whatever any school has switched off. What a push is <em>about</em> is gated where that feature lives: no
 * `chat`, no message, no push; no `staffMobileApp`, no staff push.
 */
@RestController
@Tag(name = "Devices", description = "The phones a parent or a member of staff receives push notifications on")
public class DeviceController {
    private final DeviceService devices; private final Json json;
    public DeviceController(DeviceService devices, Json json) { this.devices = devices; this.json = json; }

    @PostMapping(value = "/me/devices", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('device.register')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = RegisterDeviceRequest.class)))
    @ApiResponse(responseCode = "204", description = "Registered, or refreshed")
    public void registerDevice(@AuthenticationPrincipal Object principal, @RequestBody String body) {
        var request = decode(body, RegisterDeviceRequest.Companion.serializer());
        if (principal instanceof Principals.User caller) devices.register(caller, request);
        else devices.register(parent(principal), request);
    }

    /**
     * Sign-out. The token travels in the body, never in the path: Cloud Run's request log and {@code RequestLogging}
     * record every path, and a token in one would be in the logs for as long as they are kept.
     */
    @PostMapping(value = "/me/devices/unregister", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('device.register')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = UnregisterDeviceRequest.class)))
    @ApiResponse(responseCode = "204", description = "Gone, or never hers")
    public void unregisterDevice(@AuthenticationPrincipal Object principal, @RequestBody String body) {
        String token = decode(body, UnregisterDeviceRequest.Companion.serializer()).getToken();
        if (principal instanceof Principals.User caller) devices.unregister(caller, token);
        else devices.unregister(parent(principal), token);
    }

    /** The two filters put exactly one of the two principals on the request; anything else never signed in. */
    private static Principals.Parent parent(Object principal) {
        if (principal instanceof Principals.Parent parent) return parent;
        throw ApiException.unauthorized("Sign in first.");
    }

    /** The body is never echoed back or logged: it carries a token. */
    private <T> T decode(String body, kotlinx.serialization.KSerializer<T> serializer) {
        try { return json.decodeShared(body, serializer); }
        catch (RuntimeException e) { throw ApiException.badRequest("Send {\"token\": \"…\"} — and, to register, \"platform\": \"ANDROID\" | \"IOS\" and, optionally, appVersion and locale."); }
    }
}
