package quest.server.broadcasts;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import quest.server.auth.Principals;
import quest.server.flags.FeatureFlag;
import quest.server.flags.FlagKeys;
import quest.server.tenancy.ManagerScope;

/**
 * Feature 22's one door: a leader hands over what her own device transcribed and gets an announcement back to read.
 *
 * <p>Its own controller rather than a fifth method on {@link BroadcastController}, because it is its own feature and
 * its own switch: `voiceAnnouncement` must be able to be off at a school that posts announcements every day, and a
 * class-level flag on the broadcast controller would take the whole feed with it.
 *
 * <p><strong>There is no audio route here and there never will be.</strong> Speech to text runs on the device, so the
 * server's whole surface for this feature is one POST carrying text. <strong>And it sends nothing:</strong> Send is
 * `POST /management/broadcasts` with `voiceDraftId`, which is the route, the permission and the audience rules the
 * school already has.
 */
@RestController
@FeatureFlag(FlagKeys.VOICE_ANNOUNCEMENT)
@Tag(name = "Voice announcements", description = "Feature 22: a leader's transcribed voice note prepared as a bilingual announcement she reviews and sends")
public class VoiceDraftController {
    private final VoiceDraftService drafts;
    public VoiceDraftController(VoiceDraftService drafts) { this.drafts = drafts; }

    /** The transcript in, one unsent draft out. 400 when it is blank or longer than 4,000 characters. */
    @PostMapping(value = "/management/broadcasts/voice-drafts", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@permit.has('broadcast.voice')")
    public VoiceDraftDto.View createVoiceDraft(@AuthenticationPrincipal Principals.User caller,
                                               @RequestBody @Valid VoiceDraftDto.CreateRequest body) {
        return drafts.create(ManagerScope.require(caller), body);
    }
}
