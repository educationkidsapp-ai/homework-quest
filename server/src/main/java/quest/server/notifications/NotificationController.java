package quest.server.notifications;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import kotlinx.serialization.builtins.BuiltinSerializersKt;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import quest.api.dto.NotificationView;
import quest.api.dto.UnreadCount;
import quest.server.auth.Principals;
import quest.server.config.Json;
import quest.server.flags.FeatureFlag;

/**
 * E2 (D26): the dashboard bell, for ADMIN, TEACHER and MANAGERIAL alike. A caller reads and writes only her own
 * rows — the service scopes every query by `userId` and answers 404, not 403, for another user's id, because the
 * row is not hers to learn the existence of. The live half is the `notification` frame on `/ws/chat`.
 *
 * <p>Two keys, not one: `notifications.read` names the two GETs and `notifications.write` the two marks. The
 * dashboard derives "which actions a read-only View-as session must hide" from the methods behind a key
 * (`gen:permissions`), so a single key covering both would make the whole bell a write and take it off the screen
 * during View-as — the very bug `permissions.generated.spec.ts` exists to prevent.
 *
 * <p>Carries no {@link FeatureFlag} and is listed as infrastructure in `FeatureFlagCoverageTest`, for
 * `HomeController`'s reason turned around: a flag over the bell would silently swallow the only thing that tells a
 * teacher her lesson is ready, on the schools most likely to have flags off. What a notification is *about* is
 * gated where that feature lives — no lesson, no `lesson.ready` row.
 */
@RestController
@Tag(name = "Notifications", description = "The dashboard bell: the caller's own notifications, unread count and read marks")
public class NotificationController {
    private final NotificationService notifications; private final Json json;
    public NotificationController(NotificationService notifications, Json json) { this.notifications = notifications; this.json = json; }

    @GetMapping(value = "/me/notifications", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('notifications.read')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = NotificationView.class))))
    public String listNotifications(@AuthenticationPrincipal Principals.User caller, @RequestParam(required = false) Boolean unread, @RequestParam(required = false) Integer limit) {
        return views(notifications.list(caller, unread, limit));
    }

    @GetMapping(value = "/me/notifications/unread-count", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('notifications.read')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = UnreadCount.class)))
    public String unreadNotificationCount(@AuthenticationPrincipal Principals.User caller) { return count(notifications.unreadCount(caller)); }

    @PostMapping(value = "/me/notifications/{id}/read", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('notifications.write')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = NotificationView.class)))
    public String markNotificationRead(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return json.encodeShared(notifications.markRead(caller, id), NotificationView.Companion.serializer());
    }

    @PostMapping(value = "/me/notifications/read-all", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('notifications.write')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = UnreadCount.class)))
    public String markAllNotificationsRead(@AuthenticationPrincipal Principals.User caller) { return count(notifications.markAllRead(caller)); }

    private String views(List<NotificationView> rows) { return json.encodeShared(rows, BuiltinSerializersKt.ListSerializer(NotificationView.Companion.serializer())); }
    private String count(UnreadCount c) { return json.encodeShared(c, UnreadCount.Companion.serializer()); }
}
