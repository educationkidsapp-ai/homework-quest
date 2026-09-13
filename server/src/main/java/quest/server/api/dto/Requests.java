package quest.server.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

public final class Requests {
    private Requests() {}

    public record CreateLessonRequest(
            @NotNull Enums.Subject subject,
            @Min(1) @Max(6) int grade,
            @NotBlank String curriculum,
            @NotNull LocalDate date,
            @Min(5) @Max(10) int practiceLength,
            String typedTask,
            List<String> fileNames) {}

    public record ConfirmSkillsRequest(@NotEmpty List<ConfirmedSkill> skills, @Min(5) @Max(10) int practiceLength) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConfirmedSkill(String id, @NotBlank String name, @NotNull Enums.Subject subject, String method) {}

    public record GenerateRequest(@NotNull Enums.Mode mode, List<String> excludeQuestionIds, @Min(5) @Max(10) int length) {
        public GenerateRequest {
            if (excludeQuestionIds == null) excludeQuestionIds = List.of();
            if (length == 0) length = 7;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LessonJob(
            String id, Enums.Status status, Enums.Subject subject, LocalDate date,
            List<Skills.ExtractedSkill> skills, List<Questions.QuestionSet> questionSets, ApiError error, List<String> sourceFileNames) {}

    public record Health(String status, String version) {}
}
