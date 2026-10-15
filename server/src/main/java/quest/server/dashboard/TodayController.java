package quest.server.dashboard;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import quest.server.auth.Principals;
import quest.server.flags.FeatureFlag;
import quest.server.flags.FlagKeys;

/**
 * Feature 21: the one call behind the Admin's and the department manager's "what needs me today?" Home — see
 * {@link TodayService} for what each card reads and who narrows it.
 *
 * <p>`home.today` is ADMIN and MANAGERIAL: the two roles that decide things for a whole school or a whole academy.
 * A teacher or a coordinator has her own Home and decides her own reports' requests on the attendance pages, and a
 * parent or a child never reaches a staff route at all. Behind `todayHome`, so a school still on the counts-only
 * Home (`GET /me/home`, which is core and unflagged) answers 404 here and the dashboard keeps the old screen.
 */
@RestController
@FeatureFlag(FlagKeys.TODAY_HOME)
@Tag(name = "Today", description = "What needs the Admin or the department manager today (feature 21)")
public class TodayController {
    private final TodayService today;

    public TodayController(TodayService today) { this.today = today; }

    @GetMapping(value = "/me/today", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('home.today')")
    public TodayDto.TodayHome todayHome(@AuthenticationPrincipal Principals.User caller) { return today.today(caller); }
}
