package quest.server.content;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import quest.server.auth.Principals;
import quest.server.children.ChildRepository;
import quest.server.config.ApiException;

/** `GET /lessons/{id}` — a published lesson, immutable per version, long cache headers. */
@RestController
@Tag(name = "Lessons", description = "Published lesson content for the app")
public class LessonController {
    private final LessonRepository lessons; private final LessonStore store; private final ChildRepository children;
    private final quest.server.exams.ExamPlays exams;
    public LessonController(LessonRepository lessons, LessonStore store, ChildRepository children, quest.server.exams.ExamPlays exams) { this.lessons = lessons; this.store = store; this.children = children; this.exams = exams; }

    @PreAuthorize("@permit.has('lesson.play')")
    @GetMapping(value = "/lessons/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = quest.api.dto.PublishedLesson.class)))
    public ResponseEntity<String> lesson(@PathVariable String id, @RequestParam(required = false) Integer version, @RequestParam(required = false) String childId, @AuthenticationPrincipal Principals.Parent parent) {
        var lesson = lessons.findById(id).filter(l -> "published".equals(l.getStatus())).orElseThrow(() -> ApiException.notFound("lesson"));
        if (childId != null) {
            var child = children.findOneById(childId).filter(c -> c.getParentId().equals(parent.parentId()) && c.getDeletedAt() == null).orElseThrow(() -> ApiException.notFound("child"));
            if (!child.courseId().equals(lesson.getCourseId())) throw ApiException.forbidden("This lesson is for a different course.");
            if (!child.getSchoolId().equals(lesson.getSchoolId())) throw ApiException.notFound("lesson");   // §2: never across schools
        }
        // N4.3 (§8): an exam carries its type, the two "off" switches the player must honour and the one paper it
        // is sat over. A homework is encoded exactly as it always was — `decorate` returns it untouched.
        var assembled = store.assemble(lesson);
        if (assembled == null) throw ApiException.notFound("lesson content");
        String body = store.encode(exams.decorate(assembled, lesson));
        return ResponseEntity.ok().cacheControl(cacheFor(lesson)).eTag("\"" + lesson.getId() + "-v" + lesson.getVersion() + "\"").body(body);
    }

    /**
     * A homework is immutable per version and may sit in any cache for a year. <strong>An exam may not.</strong>
     *
     * <p>N4.3 gave the exam body the same `public, max-age=31536000` a homework has, and a public year-long cache is
     * the one thing §8's window cannot survive: the paper would be served from a proxy, or from the tablet's own
     * disk, to a child who asks for it before it opens, after it closes, or a second time after she handed it in —
     * and none of those requests would reach the server that refuses them. `private, no-store` puts every read of
     * an exam paper back in front of `@PreAuthorize` and the window check, which is where it has to be.
     */
    private static CacheControl cacheFor(Entities.LessonEntity lesson) {
        return quest.server.exams.ExamPlays.isExam(lesson)
                ? CacheControl.noStore().cachePrivate()
                : CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic();
    }
}
