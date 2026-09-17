package quest.server.teacher;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import kotlinx.serialization.KSerializer;
import kotlinx.serialization.builtins.BuiltinSerializersKt;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import quest.api.dashboard.TeacherAnswerUpload;
import quest.api.dashboard.TeacherQuestionPlay;
import quest.api.dto.AttemptAck;
import quest.server.auth.Principals;
import quest.server.children.ChildService;
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.flags.FeatureFlag;
import quest.server.flags.FlagKeys;

/**
 * §6 screen 14 and its app half: the teacher writes and sends questions, the child answers them from her map.
 *
 * <p><strong>One flag over both halves.</strong> `teacherQuestions` is seeded off, so every route here is a 404 —
 * the body an unknown path gets — until a school switches it on, for the teacher and for the parent alike. The
 * parent side is resolved by the child in the path, so a parent whose children are in two schools gets each child's
 * own answer rather than one school's answer for all of them (see {@link quest.server.flags.FeatureFlagInterceptor}).
 *
 * <p>The dashboard routes are Java records so `server/openapi.json` carries a real schema for them; the two
 * `/children/**` routes are encoded with the shared kotlinx codec like every other `ContentApi` route, because a
 * question is a list of §5 `Stop`s and only that codec can write one.
 */
@RestController
@FeatureFlag(FlagKeys.TEACHER_QUESTIONS)
@Tag(name = "Teacher questions", description = "Questions a teacher sends to her students, and the answers")
public class TeacherQuestionController {
    private final TeacherQuestionService questions; private final ChildService childService; private final Json json;

    public TeacherQuestionController(TeacherQuestionService questions, ChildService childService, Json json) {
        this.questions = questions; this.childService = childService; this.json = json;
    }

    // ---------------------------------------------------------------- the teacher (§6 screen 14)

    @GetMapping(value = "/teacher/questions", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('question.read')")
    public List<TeacherDto.TeacherQuestion> teacherQuestions(@AuthenticationPrincipal Principals.User caller) {
        return questions.list(TeacherAccess.require(caller));
    }

    @PostMapping(value = "/teacher/questions", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@permit.has('question.write')")
    public TeacherDto.TeacherQuestion createTeacherQuestion(@AuthenticationPrincipal Principals.User caller,
                                                            @RequestBody @Valid TeacherDto.CreateTeacherQuestionRequest body) {
        return questions.create(TeacherAccess.require(caller), body);
    }

    /** 409 once it has been sent: children may have answered, and rewriting the stops would orphan their rows. */
    @PutMapping(value = "/teacher/questions/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('question.write')")
    public TeacherDto.TeacherQuestion updateTeacherQuestion(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                                                            @RequestBody @Valid TeacherDto.UpdateTeacherQuestionRequest body) {
        return questions.update(TeacherAccess.require(caller), id, body);
    }

    /** Validates the stops against the shared schema one last time and stamps `sent_at`; 409 if already sent. */
    @PostMapping(value = "/teacher/questions/{id}/send", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('question.send')")
    public TeacherDto.TeacherQuestion sendTeacherQuestion(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return questions.send(TeacherAccess.require(caller), id);
    }

    @GetMapping(value = "/teacher/questions/{id}/results", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('question.read')")
    public TeacherDto.TeacherQuestionResults teacherQuestionResults(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return questions.results(TeacherAccess.require(caller), id);
    }

    // ---------------------------------------------------------------- the child (app)

    /** The stops behind a `MapResponse.teacherIslands` entry, shaped like a play the existing player can run. */
    @GetMapping(value = "/children/{id}/teacher-questions/{questionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('child.teacherQuestion.read')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = TeacherQuestionPlay.class)))
    public String childTeacherQuestion(@AuthenticationPrincipal Principals.Parent parent,
                                       @PathVariable String id, @PathVariable String questionId) {
        var child = childService.owned(id, parent);
        return json.encodeShared(questions.playFor(child, questionId, LocalDate.now()), TeacherQuestionPlay.Companion.serializer());
    }

    /** A batch, like `POST /children/{id}/attempts`: idempotent on (question, child, stop). */
    @PostMapping(value = "/children/{id}/teacher-questions/{questionId}/answers",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('child.teacherQuestion.answer')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AttemptAck.class)))
    public String childTeacherAnswers(@AuthenticationPrincipal Principals.Parent parent,
                                      @PathVariable String id, @PathVariable String questionId, @RequestBody String body) {
        var child = childService.owned(id, parent);
        List<TeacherAnswerUpload> uploads = decode(body, BuiltinSerializersKt.ListSerializer(TeacherAnswerUpload.Companion.serializer()));
        if (uploads.size() > 50) throw ApiException.badRequest("at most 50 answers per upload");
        int accepted = questions.recordAnswers(child, questionId, uploads, LocalDate.now());
        return json.encodeShared(new AttemptAck(accepted), AttemptAck.Companion.serializer());
    }

    private <T> T decode(String body, KSerializer<T> serializer) {
        try { return json.decodeShared(body, serializer); }
        catch (Exception e) { throw ApiException.badRequest("Malformed request: " + e.getMessage()); }
    }
}
