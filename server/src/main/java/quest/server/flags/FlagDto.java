package quest.server.flags;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The Java mirror of the flag half of `quest.api.dashboard` (§4), as records so springdoc can describe them. */
public final class FlagDto {
    private FlagDto() {}

    /** How far along a flag is; the `rollout_stage` column's three values, serialised lowercase as they are stored. */
    public enum RolloutStage {
        INTERNAL, BETA, GA;

        @JsonValue public String json() { return name().toLowerCase(Locale.ROOT); }

        @JsonCreator public static RolloutStage from(String value) {
            if (value == null) return INTERNAL;
            for (RolloutStage stage : values()) if (stage.json().equalsIgnoreCase(value.trim())) return stage;
            throw new IllegalArgumentException("rolloutStage is internal, beta or ga, not " + value);
        }
    }

    /** A flag as the platform defines it: the left-hand column of the Admin matrix. */
    public record FeatureFlagDefinition(String key, String description, boolean defaultOn, RolloutStage rolloutStage, long createdAt) {}

    /** One row of the matrix: every key with the value this school sees (its override, or the default). */
    public record SchoolFlags(String schoolId, String schoolName, Map<String, Boolean> flags) {}

    /** `GET /admin/flags`: the definitions once, then a row per school the caller may see. */
    public record FlagMatrix(List<FeatureFlagDefinition> definitions, List<SchoolFlags> schools) {}

    /** The body of both `PUT /admin/schools/{id}/flags/{key}` and `PUT /admin/flags/{key}/all`. */
    public record UpdateFlagRequest(@NotNull Boolean enabled) {}

    /** A row of `flag_audit`. `schoolId` null is the "for all schools" action (§4). */
    public record FlagAuditEntry(String id, String flagKey, String schoolId, String schoolName, boolean enabled,
                                 String actorUserId, String actorEmail, long createdAt) {}
}
