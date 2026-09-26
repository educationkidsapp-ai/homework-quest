package quest.server.broadcasts;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import quest.server.auth.Principals;
import quest.server.flags.FeatureFlag;
import quest.server.flags.FlagKeys;
import quest.server.tenancy.CoordinatorScope;
import quest.server.tenancy.ManagerScope;

/**
 * RM2 (DR6): the broadcast feature's four doors — the manager's composer, the coordinator's, the dashboard feed and
 * the app feed. One controller because it is one feature: the audience is a property of the row, so the rules live in
 * {@link BroadcastService} and each door only says who is knocking.
 *
 * <p><strong>Behind `announcements`</strong>, the flag the feature it supersedes already carries: a school that has it
 * off answers 404 to the composer, the feed and the parent's app alike, exactly as it does for
 * `/coordinator/announcements` and `/teacher/announcements` today. There is no `broadcasts` flag of its own — DR6 calls
 * this one feature, and a second key would let a school collect broadcasts it has no screen for.
 *
 * <p>Records rather than the kotlinx codec: there is no `Stop` in a broadcast and springdoc needs a real schema for the
 * generated Angular client ({@link BroadcastDto}).
 */
@RestController
@FeatureFlag(FlagKeys.ANNOUNCEMENTS)
@Tag(name = "Broadcasts", description = "The weekly plan, announcements and events: composing them and the feeds that receive them")
public class BroadcastController {
    private final BroadcastService broadcasts;
    public BroadcastController(BroadcastService broadcasts) { this.broadcasts = broadcasts; }

    // ---------------------------------------------------------------- the manager composes

    /** Her department's broadcast; `sectionIds` empty is the whole department and a section outside it is 403. */
    @PostMapping(value = "/management/broadcasts", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@permit.has('management.broadcast')")
    public BroadcastDto.View createManagementBroadcast(@AuthenticationPrincipal Principals.User caller,
                                                       @RequestBody @Valid BroadcastDto.CreateRequest body) {
        return broadcasts.managerPost(ManagerScope.require(caller), body);
    }

    /** What she posted, newest first — her composer's own list, expired rows included. */
    @GetMapping(value = "/management/broadcasts", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.broadcast')")
    public List<BroadcastDto.View> managementBroadcasts(@AuthenticationPrincipal Principals.User caller) {
        return broadcasts.managerPosts(ManagerScope.require(caller));
    }

    // ---------------------------------------------------------------- the coordinator composes

    /** The parents of the classes she coordinates; `weekly_plan` is the manager's kind and is refused here. */
    @PostMapping(value = "/coordinator/broadcasts", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@permit.has('coordinator.broadcast')")
    public BroadcastDto.View createCoordinatorBroadcast(@AuthenticationPrincipal Principals.User caller,
                                                        @RequestBody @Valid BroadcastDto.CreateRequest body) {
        return broadcasts.coordinatorPost(CoordinatorScope.require(caller), body).broadcast();
    }

    @GetMapping(value = "/coordinator/broadcasts", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('coordinator.broadcast')")
    public List<BroadcastDto.View> coordinatorBroadcasts(@AuthenticationPrincipal Principals.User caller) {
        return broadcasts.coordinatorPosts(CoordinatorScope.require(caller));
    }

    // ---------------------------------------------------------------- the dashboard reads

    /** Every broadcast this teacher, coordinator or manager is an audience of, newest first, with her unread count. */
    @GetMapping(value = "/me/broadcasts", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('broadcast.read')")
    public BroadcastDto.Feed myBroadcasts(@AuthenticationPrincipal Principals.User caller) {
        return broadcasts.forStaff(quest.server.tenancy.TeacherScope.require(caller));
    }

    /**
     * The read mark is its own key, for `NotificationController`'s reason: the dashboard derives "what a read-only
     * View-as session must hide" from the methods behind a key, so one key over the feed and its mark would take the
     * whole feed off the screen during View-as.
     */
    @PostMapping(value = "/me/broadcasts/{id}/read", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('broadcast.write')")
    public BroadcastDto.View markMyBroadcastRead(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return broadcasts.staffRead(quest.server.tenancy.TeacherScope.require(caller), id);
    }

    // ---------------------------------------------------------------- the app reads

    /**
     * The app's feed (RM4): what was sent to the parents of the section this child sits in. Named by child because a
     * flagged parent route must say which child it is about — {@code FeatureFlagInterceptor} refuses one that cannot,
     * so that one school's flags never decide what a parent sees about her child in another.
     */
    @GetMapping(value = "/children/{id}/broadcasts", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('child.broadcast.read')")
    public BroadcastDto.Feed childBroadcasts(@AuthenticationPrincipal Principals.Parent parent, @PathVariable String id) {
        return broadcasts.forChild(parent, id);
    }

    @PostMapping(value = "/children/{id}/broadcasts/{broadcastId}/read", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('child.broadcast.write')")
    public BroadcastDto.View markChildBroadcastRead(@AuthenticationPrincipal Principals.Parent parent,
                                                    @PathVariable String id, @PathVariable String broadcastId) {
        return broadcasts.parentRead(parent, id, broadcastId);
    }
}
