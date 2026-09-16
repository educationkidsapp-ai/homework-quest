package quest.server.schools;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import quest.server.platform.ThemeDto;

/** The Java mirror of the school half of `quest.api.dashboard` (§2, §6 screens 4–5). */
public final class SchoolDto {
    private SchoolDto() {}

    /** A school code is six characters of A–Z and 0–9; parents type it to join (§2). */
    public static final String CODE_PATTERN = "^[A-Z0-9]{6}$";

    public record School(String id, String name, String code, List<String> curriculumOptions, List<Integer> gradeOptions,
                         String theme, String featureFlags, String status, long createdAt) {}

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
}
