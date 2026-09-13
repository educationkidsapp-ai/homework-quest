package quest.server.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

public final class Skills {
    private Skills() {}

    /** Prompt A output. */
    public record SkillExtraction(Enums.Subject subject, List<ExtractedSkill> skills) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExtractedSkill(
            String id, String name, Enums.Subject subject, String method, List<String> examples,
            List<Integer> slideNumbers, double confidence, Unsure unsure) {}

    public record Unsure(List<String> candidates, String question) {}
}
