package quest.server.brief;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Feature 25: the Java mirror of `quest.api.dashboard.MorningBrief.kt`, as {@link quest.server.dashboard.TodayDto}
 * mirrors feature 21's.
 *
 * <p>Nothing here is assembled prose except the lines themselves, which <em>are</em> the feature: they are written in
 * both languages on the server, because the model writes them and a client cannot translate a sentence it did not
 * compose. Everything else is a number, so the dashboard's own catalogue still owns every label around them.
 * {@code day} and {@code yesterday} are `yyyy-MM-dd` in the school's zone.
 */
public final class MorningBriefDto {
    private MorningBriefDto() {}

    /** The model phrased it; the deterministic template did. */
    public static final String SOURCE_AI = "ai", SOURCE_TEMPLATE = "template";

    /**
     * What the brief is about, in numbers only, already narrowed to the caller: {@code scope} is `SCHOOL` for the
     * Admin and `ACADEMY` for a manager. {@code childAttendanceRate} is null — not zero — when no register was taken,
     * and {@code remoteStudyDayReason} is null unless {@code remoteStudyDayToday} is true.
     */
    @Schema(name = "MorningBriefFacts")
    public record Facts(String scope, String yesterday, int lateYesterday, int absentYesterday,
                        int missingCheckoutYesterday, int requestsWaiting, int overdueComplaints, int eventsToday,
                        Integer childAttendanceRate, boolean remoteStudyDayToday, String remoteStudyDayReason) {}

    /** `GET /me/morning-brief`; `204 No Content` on a day the school does not teach. */
    @Schema(name = "MorningBrief")
    public record View(String day, List<String> linesAr, List<String> linesEn, String source, Facts facts) {}
}
