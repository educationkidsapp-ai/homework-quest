package quest.server.schools;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import quest.server.platform.ThemeDto;

/** The Java mirror of the school half of `quest.api.dashboard` (§2, §6 screens 4–5). */
public final class SchoolDto {
    private SchoolDto() {}

    /** A school code is six characters of A–Z and 0–9; parents type it to join (§2). */
    public static final String CODE_PATTERN = "^[A-Z0-9]{6}$";

    /** The Overview tab of the School page (§6 screen 5); the four counts are what it puts above the tabs. */
    public record School(String id, String name, String code, List<String> curriculumOptions, List<Integer> gradeOptions,
                         String theme, String featureFlags, String status, long createdAt,
                         int children, int teachers, int lessons, int classes) {}

    /** A card on the Schools screen: the counts are what the Admin scans for. */
    public record SchoolSummary(String id, String name, String code, String status, int teachers, int children, int lessons) {}

    public record CreateSchoolRequest(@NotBlank @Size(max = 120) String name,
                                      @Pattern(regexp = CODE_PATTERN, message = "must be 6 characters of A-Z and 0-9") String code,
                                      List<String> curriculumOptions, List<Integer> gradeOptions) {}

    /** Only the fields that are present are written. */
    public record UpdateSchoolRequest(@Size(max = 120) String name, List<String> curriculumOptions, List<Integer> gradeOptions, String status) {}

    /**
     * What a parent sees after typing a school code, before confirming (§2, app "Join school"). `theme` is the
     * school's §3 theme, so the app can run the colour transition on the confirm step without a second request.
     */
    public record JoinSchoolInfo(String name, String logoUrl, List<String> curriculumOptions, List<Integer> gradeOptions,
                                 ThemeDto.SchoolTheme theme) {}

    // ---- §6 screen 4: the New school wizard, and screen 1: the logo the sign-in page fades in.

    /**
     * The first Managerial user of a brand-new school. With a `password` the account exists and can sign in at once
     * (and must replace it); without one an invitation is emailed and the account stays `invited`.
     */
    public record WizardManagerInput(@NotBlank @Email String email, @Size(max = 120) String displayName, String password) {}

    /** `POST /admin/schools/wizard`: what the four wizard steps collected, applied in one transaction. */
    public record SchoolWizardRequest(@NotNull @Valid CreateSchoolRequest school, ThemeDto.SchoolTheme theme,
                                      Map<String, Boolean> flags, @NotNull @Valid WizardManagerInput manager) {}

    /** What the wizard made; `invited` says whether the Managerial account is waiting on its email link. */
    public record SchoolWizardResponse(School school, quest.server.auth.DashboardDto.DashboardUser user, boolean invited,
                                       ThemeDto.SchoolTheme theme, Map<String, Boolean> flags) {}

    /**
     * `GET /schools/logo?email=` (§6 screen 1). Deliberately two fields: the sign-in page needs a name and a picture,
     * and a caller who guesses an address learns nothing it did not already know.
     */
    public record SchoolLogo(String name, String logoUrl) {}
}
