package quest.server.dashboard;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import quest.server.auth.Principals;
import quest.server.flags.FeatureFlag;

/**
 * §6 screen 2: the role Home. One route, three shapes — see {@link HomeService}.
 *
 * <p>Carries no {@link FeatureFlag} and is listed as core in `FeatureFlagCoverageTest`: `/` redirects every signed-in
 * user to their Home, so a flag that could switch it off would leave that user with nowhere to land. Whatever a Home
 * reports about a flagged feature is gated by that feature's own flag where the feature lives, not here.
 */
@RestController
@Tag(name = "Home", description = "The role-specific dashboard home (§6 screen 2)")
public class HomeController {
    private final HomeService home;
    public HomeController(HomeService home) { this.home = home; }

    @GetMapping(value = "/me/home", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('me.home')")
    public HomeDto.HomeResponse home(@AuthenticationPrincipal Principals.User caller) { return home.home(caller); }
}
