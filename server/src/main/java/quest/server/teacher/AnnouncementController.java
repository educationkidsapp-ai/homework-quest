package quest.server.teacher;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import kotlinx.serialization.builtins.BuiltinSerializersKt;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import quest.api.dashboard.ParentAnnouncement;
import quest.server.auth.Principals;
import quest.server.children.ChildService;
import quest.server.config.Json;
import quest.server.flags.FeatureFlag;
import quest.server.flags.FlagKeys;

/**
 * §6 screen 16 and its app half: a short note to all parents of a class, shown in the app's parent mode.
 *
 * <p><strong>One flag over both halves.</strong> `announcements` is seeded off, so every route here is a 404 until a
 * school switches it on — for the teacher who would post and for the parent who would read. The parent side is
 * resolved by the child in the path, so a parent with children in two schools gets each child's own answer.
 */
@RestController
@FeatureFlag(FlagKeys.ANNOUNCEMENTS)
@Tag(name = "Announcements", description = "Notes a teacher posts to the parents of a class")
public class AnnouncementController {
    private final AnnouncementService announcements; private final ChildService childService; private final Json json;

    public AnnouncementController(AnnouncementService announcements, ChildService childService, Json json) {
        this.announcements = announcements; this.childService = childService; this.json = json;
    }

    // ---------------------------------------------------------------- the teacher (§6 screen 16)

    @GetMapping(value = "/teacher/announcements", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('announcement.read')")
    public List<TeacherDto.Announcement> teacherAnnouncements(@AuthenticationPrincipal Principals.User caller) {
        return announcements.list(TeacherAccess.require(caller));
    }

    @PostMapping(value = "/teacher/announcements", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@permit.has('announcement.write')")
    public TeacherDto.Announcement createAnnouncement(@AuthenticationPrincipal Principals.User caller,
                                                      @RequestBody @Valid TeacherDto.CreateAnnouncementRequest body) {
        return announcements.create(TeacherAccess.require(caller), body);
    }

    @DeleteMapping("/teacher/announcements/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@permit.has('announcement.write')")
    public void deleteAnnouncement(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        announcements.delete(TeacherAccess.require(caller), id);
    }

    // ---------------------------------------------------------------- the parent (app)

    /** The live notes of the classes this child sits in, newest first; encoded with the shared codec like `ContentApi`. */
    @GetMapping(value = "/children/{id}/announcements", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('child.announcement.read')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = ParentAnnouncement.class))))
    public String childAnnouncements(@AuthenticationPrincipal Principals.Parent parent, @PathVariable String id) {
        var child = childService.owned(id, parent);
        return json.encodeShared(announcements.forChild(child),
                BuiltinSerializersKt.ListSerializer(ParentAnnouncement.Companion.serializer()));
    }
}
