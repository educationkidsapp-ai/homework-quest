package quest.server.coordinator;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import quest.server.auth.Principals;
import quest.server.flags.FeatureFlag;
import quest.server.flags.FlagKeys;
import quest.server.teacher.TeacherDto;
import quest.server.tenancy.CoordinatorScope;

/**
 * R4 (DR4): a coordinator's announcement to the parents of the classes she coordinates. Behind the `announcements`
 * flag like {@code AnnouncementController} is, and on the same rows — a school that has the feature off gets 404 on
 * both halves, hers and the teacher's, and the parent's read with it.
 *
 * <p>The manager's broadcasts (weekly plan, event, a department-wide audience) are DR6 and RM2, not this: one note to
 * the parents of a class is what already exists, and RM2 generalises it rather than this package pre-empting it.
 */
@RestController
@FeatureFlag(FlagKeys.ANNOUNCEMENTS)
@Tag(name = "Coordinator announcements", description = "Notes a coordinator posts to the parents of the classes in her scope")
public class CoordinatorAnnouncementController {
    private final CoordinatorAnnouncementService announcements; private final quest.server.broadcasts.BroadcastService broadcasts;

    public CoordinatorAnnouncementController(CoordinatorAnnouncementService announcements,
                                             quest.server.broadcasts.BroadcastService broadcasts) {
        this.announcements = announcements; this.broadcasts = broadcasts;
    }

    @GetMapping(value = "/coordinator/announcements", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('coordinator.announce')")
    public List<TeacherDto.Announcement> coordinatorAnnouncements(@AuthenticationPrincipal Principals.User caller) {
        return announcements.mine(CoordinatorScope.require(caller));
    }

    /**
     * One row per class, so the answer names exactly who was told; 201 with them all. RM2 (DR6): the write goes through
     * {@link quest.server.broadcasts.BroadcastService} and also writes the broadcast row, so a note posted here appears
     * in `GET /coordinator/broadcasts` and in the parent's feed beside the announcements screen it always reached.
     */
    @PostMapping(value = "/coordinator/announcements", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@permit.has('coordinator.announce')")
    public List<TeacherDto.Announcement> createCoordinatorAnnouncement(@AuthenticationPrincipal Principals.User caller,
                                                                      @RequestBody @Valid CoordinatorDto.CreateAnnouncementRequest body) {
        var request = new quest.server.broadcasts.BroadcastDto.CreateRequest(quest.server.broadcasts.BroadcastService.ANNOUNCEMENT,
                body.bodyEn(), null, body.bodyAr(), null, java.util.List.of(quest.server.broadcasts.BroadcastService.PARENTS),
                body.classIds(), null, body.expiresAt());
        return broadcasts.coordinatorPost(CoordinatorScope.require(caller), request).announcements();
    }
}
