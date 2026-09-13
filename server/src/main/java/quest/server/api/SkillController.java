package quest.server.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import quest.server.api.dto.ApiError;
import quest.server.api.dto.Questions;
import quest.server.api.dto.Requests;
import quest.server.domain.LessonEntity;
import quest.server.domain.SkillEntity;
import quest.server.repository.*;
import quest.server.service.GenerationService;

@RestController
@RequestMapping("/skills")
public class SkillController {
    private final SkillRepository skills;
    private final LessonRepository lessons;
    private final GenerationService generation;

    public SkillController(SkillRepository skills, LessonRepository lessons, GenerationService generation) {
        this.skills = skills; this.lessons = lessons; this.generation = generation;
    }

    /** POST /skills/{id}/generate — again / harder / easier; never repeats excluded ids. */
    @PostMapping("/{id}/generate")
    public Questions.QuestionSet generate(@PathVariable String id, @RequestBody @Valid Requests.GenerateRequest request) {
        SkillEntity skill = skills.findById(id).orElseThrow(() -> ApiException.notFound("skill"));
        LessonEntity lesson = lessons.findById(skill.getLessonId()).orElseThrow(() -> ApiException.notFound("lesson"));
        try {
            return generation.generate(skill, lesson.getGrade(), request.mode(), request.excludeQuestionIds(), request.length());
        } catch (GenerationService.GenerationException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, ApiError.MODEL_FAILED, "New questions could not be made right now. Please try again.");
        }
    }
}
