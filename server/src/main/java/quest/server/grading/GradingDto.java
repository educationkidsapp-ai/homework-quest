package quest.server.grading;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The Java mirror of `quest.api.dashboard.Grading.kt` (`docs/teacher-flow.md` step 9, teacher prompt §7).
 *
 * <p>Records rather than the Kotlin types, for the reason {@link quest.server.teacher.TeacherDto} gives: springdoc
 * derives a real schema from a record, and `server/openapi.json` is what the Angular client is generated from.
 */
public final class GradingDto {
    private GradingDto() {}

    // ---------------------------------------------------------------- marking (§7)

    /**
     * One mark. `stopId` null means the mark is about the whole lesson — that is where `score` (the override §7
     * keeps the automatic score visible beside) and the comment to the parent live. On a stop, `stars` is 1–3 and
     * `score` is ignored: §7 marks open stops in stars and the stop score follows from them.
     *
     * <p>Sending `stars`, `score` and `comment` all null <strong>deletes</strong> the mark, which is how a teacher
     * takes back a star she gave by accident without a second route.
     *
     * <p>A released lesson can still be marked; see {@link ReleaseRequest}.
     */
    public record MarkInput(@NotBlank String childId, @NotBlank String lessonId, String stopId,
                            @Min(0) @Max(3) Integer stars, @Min(0) @Max(100) Integer score,
                            @Size(max = 500) String comment) {}

    /** `PUT /teacher/marks`: a page of marking saved in one request, so one screen is one write. */
    public record SaveMarksRequest(@NotEmpty @Valid List<MarkInput> marks) {}

    public record TeacherMark(String childId, String lessonId, String stopId, Integer stars, Integer score,
                              String comment, String markedBy, long markedAt) {}

    // ---------------------------------------------------------------- release (§7)

    /**
     * `POST /teacher/lessons/{id}/release`; `released` false withdraws it.
     *
     * <p>Publishing a homework releases it (§7's "default on for homework"), so this route is for an exam, for
     * withdrawing a release, and for putting one back. <strong>Marking is never refused because a lesson is
     * released.</strong> §7 says only that a parent sees the score and the comment after release; it says nothing
     * about freezing a lesson, and since a homework is released the moment it is published, refusing marks on a
     * released lesson would make §7's own marking flow impossible. A mark on a released lesson therefore reaches
     * the parent on her next read, which is what "the teacher's comment for each lesson" asks for.
     */
    public record ReleaseRequest(Boolean released) {}

    /** What the release did: the whole section at once, which is how §7's toggle works. */
    public record LessonRelease(String lessonId, boolean released, Long releasedAt, int children) {}

    // ---------------------------------------------------------------- results (§7, step 9)

    /** One stop of the lesson, in play order, so the grid's columns are the same for every child. */
    public record ResultStop(String stopId, String title, String type, int level, boolean open) {}

    /** One child's stop: `accuracy` is the stars as a percentage, `score` what §7's rules made of them. */
    public record ChildStopResult(String stopId, boolean attempted, Boolean firstTryCorrect, int stars, int attempts,
                                  Integer accuracy, Integer score, Integer markStars, String markComment,
                                  boolean needsMarking, String workUrl) {}

    /** One row of the Results page: §7's `HomeworkScore` with the marks and the saved work beside it. */
    public record ChildResult(String childId, String name, String classId, boolean attempted, int levelReached,
                              Integer autoScore, Integer teacherScore, Integer score, String band, int starsEarned,
                              int starsTotal, int completion, int needsMarking, String comment,
                              List<ChildStopResult> stops) {}

    /** `GET /teacher/lessons/{id}/results`. `classAverage` is over the children who have a score. */
    public record LessonResults(String lessonId, String title, String classId, String className, String subject,
                                String date, String type, boolean released, Long releasedAt, Integer classAverage,
                                int played, int needsMarking, List<ResultStop> stops, List<ChildResult> children) {}

    // ---------------------------------------------------------------- gradebook (§7)

    /** One column of the grid. `needsMarking` is how many of its children are still waiting for the teacher. */
    public record GradebookLesson(String lessonId, String title, String date, String subject, String type,
                                  boolean released, Integer classAverage, int needsMarking) {}

    /** One cell. `teacherScore` is kept beside `autoScore` rather than replacing it — §7's "keeps auto visible". */
    public record GradebookCell(String lessonId, boolean attempted, Integer autoScore, Integer teacherScore,
                                Integer score, String band, boolean needsMarking) {}

    /**
     * One row: a child, her cells in the same order as `lessons`, and §7's per-child average column.
     *
     * <p>`average` is the plain arithmetic mean of her scored cells <strong>in the requested window</strong> —
     * nothing weighted, nothing dropped — so a teacher who adds the row up by hand gets the same number. `band`
     * colours that average. The rolling, weighted measure is `ChildLevel.levelScore` on the child page.
     */
    public record GradebookChild(String childId, String name, Integer average, String band, String trend,
                                 List<GradebookCell> cells) {}

    /** `GET /teacher/classes/{id}/gradebook?from&to`. */
    public record Gradebook(String classId, String className, String subject, String from, String to,
                            List<GradebookLesson> lessons, List<GradebookChild> children, int needsMarking) {}

    // ---------------------------------------------------------------- the child page (§7)

    /**
     * §7's `ChildLevel(childId, subject, band, trend, computedAt)`, one per subject she has been scored in.
     *
     * <p>`levelScore` is deliberately not called an average: it is weighted toward her recent lessons, counts an
     * exam twice and looks at the newest ten only, because §7 asks where a child <em>is</em> rather than what her
     * marks add up to. The mean of a column is {@link GradebookChild#average}.
     */
    public record ChildLevel(String subject, String band, String trend, Integer levelScore, int lessons) {}

    /** One point of the score chart, oldest first, so the dashboard plots it without sorting. */
    public record ChildTrendPoint(String lessonId, String title, String date, String subject, Integer score,
                                  String band, boolean released) {}

    /** A comment the teacher left, on a stop or on the lesson, newest first. */
    public record ChildComment(String lessonId, String lessonTitle, String stopId, Integer stars, String comment,
                               long markedAt) {}

    /** Saved open-stop work: the same `/media/child/{id}` link `GET /teacher/students/{id}/timeline` hands out. */
    public record ChildWork(String id, String url, String kind, String stopId, long createdAt) {}

    /** `GET /teacher/children/{id}`: band and trend per subject, the chart, the comments and the saved work. */
    public record ChildReport(String childId, String name, String classId, String className, String avatarColor,
                              List<ChildLevel> levels, List<ChildTrendPoint> trend, List<ChildComment> comments,
                              List<ChildWork> work) {}
}
