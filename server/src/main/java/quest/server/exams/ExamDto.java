package quest.server.exams;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The Java mirror of `quest.api.dashboard.Exams.kt` (`docs/teacher-flow.md` step 10, teacher prompt §8).
 *
 * <p>Records rather than the Kotlin types, for the reason {@link quest.server.teacher.TeacherDto} gives: springdoc
 * derives a real schema from a record, and `server/openapi.json` is what the Angular client is generated from.
 */
public final class ExamDto {
    private ExamDto() {}

    // ---------------------------------------------------------------- the settings sheet (§8)

    /**
     * One exam's settings with the lesson's own identity beside them, so the settings sheet is one read.
     *
     * <p>`singleAttempt`, `hintsOff` and `numbersOff` are §8's rules rather than choices and are always true; they
     * are on the wire because the player has to be told. `open` is the window as the <strong>server's</strong>
     * clock reads it — a child's tablet is the one clock a school cannot set.
     */
    public record ExamSettings(String examId, String title, String classId, String className, String subject,
                               String date, long opensAt, long closesAt, String level, Integer durationMinutes,
                               boolean singleAttempt, boolean hintsOff, boolean numbersOff, String releaseMode,
                               String status, boolean released, Long releasedAt, boolean open) {}

    /**
     * One row of the class page's Exams tab, and the whole of `GET /teacher/exams/{id}` — the settings above with
     * the four numbers the tab draws beside them.
     *
     * <p><strong>Why the numbers are here.</strong> The dashboard was reading `/teacher/exams/{id}/results` once per
     * row to find them, which is a full scoring pass per exam for three integers; {@link ExamService#ofClass}
     * computes all of them for the whole tab in a fixed number of statements instead.
     *
     * <p>`state` is the one word the tab's State column says — `draft`, `scheduled`, `open`, `closed` or
     * `released` — decided by the same rule the dashboard's own `examStateOf` applies, so the two can never
     * disagree: released beats the window, and a published exam with no usable window is a draft rather than a
     * "closed" nobody ever sat. `roster` is the class register, `sat` the children with a sitting, and
     * `needsMarking` the open stops still waiting for the teacher — §7's count, over §8's paper.
     */
    public record ExamRow(String examId, String title, String classId, String className, String subject,
                          String date, long opensAt, long closesAt, String level, Integer durationMinutes,
                          boolean singleAttempt, boolean hintsOff, boolean numbersOff, String releaseMode,
                          String status, boolean released, Long releasedAt, boolean open,
                          String state, int roster, int sat, int needsMarking) {

        /** The row a settings read already holds, with the tab's four numbers laid beside it. */
        static ExamRow of(ExamSettings s, String state, int roster, int sat, int needsMarking) {
            return new ExamRow(s.examId(), s.title(), s.classId(), s.className(), s.subject(), s.date(), s.opensAt(),
                    s.closesAt(), s.level(), s.durationMinutes(), s.singleAttempt(), s.hintsOff(), s.numbersOff(),
                    s.releaseMode(), s.status(), s.released(), s.releasedAt(), s.open(), state, roster, sat, needsMarking);
        }
    }

    /** `POST /teacher/classes/{id}/exams`: the same editor as a lesson, plus the window and the level. */
    public record CreateExamRequest(@NotBlank @Size(max = 120) String title, @NotNull Long opensAt, @NotNull Long closesAt,
                                    String level, String source, @Min(1) @Max(600) Integer durationMinutes,
                                    String releaseMode, @Size(max = 2000) String notes,
                                    @Min(3) @Max(20) Integer practiceLength) {}

    /** `PATCH /teacher/exams/{id}`: only the fields that are present are written, and only while the window is shut. */
    public record UpdateExamRequest(@Size(max = 120) String title, Long opensAt, Long closesAt, String level,
                                    @Min(1) @Max(600) Integer durationMinutes, String releaseMode) {}

    // ---------------------------------------------------------------- the results page (§8)

    /**
     * One row of the results table — §8's `ExamResult` per child.
     *
     * <p>`score` out of `maxScore` is the stars she earned; `percent` is §7's 0–100 and is what the band, the
     * gradebook cell and her level are all taken from. `needsMarking` counts the open stops nobody has looked at
     * yet: until it is zero, `percent` describes only what could be scored automatically.
     *
     * <p>`lastSeenAt` is the last answer the sitting took. For a child still inside the paper it is the only thing
     * that separates "working on it" from "walked away from it ten minutes ago" — the column `exam_attempts` has
     * always written and nothing has ever read, which the #106 review asked to be either read or dropped.
     */
    public record ExamChildResult(String childId, String name, String state, int score, int maxScore, Integer percent,
                                  String band, Integer secondsTaken, Long startedAt, Long lastSeenAt, Long submittedAt,
                                  int needsMarking, boolean reopened, String comment) {}

    /** One column of the distribution chart: how many children landed in each of §7's four bands. */
    public record ExamBand(String band, int children) {}

    /**
     * Per-question difficulty — §8's "which questions most children missed".
     *
     * <p>`answered` is how many children reached the question at all, so `missedPercent` is out of those rather than
     * out of the roster: a question nobody got to is not a question everybody failed. An open stop has no right
     * answer and reports `averageStars` from the teacher's marks instead.
     */
    public record ExamQuestion(String stopId, String title, String type, boolean open, int answered, int correct,
                               Double averageStars, Integer missedPercent) {}

    /** `GET /teacher/exams/{id}/results`; the same rows back `results.csv`, `results.xlsx` and the per-child PDF. */
    public record ExamResults(String examId, String title, String classId, String className, String subject,
                              String date, ExamSettings settings, boolean released, Long releasedAt, int roster,
                              int sat, int submitted, int absent, Integer classAverage, int needsMarking,
                              List<ExamBand> distribution, List<ExamQuestion> questions,
                              List<ExamChildResult> children, List<ExamChildResult> absentees) {}

    /** `POST /teacher/exams/{id}/reopen/{childId}`: one more sitting for one child, until `closesAt`. */
    public record ExamReopen(String examId, String childId, long closesAt, long reopenedAt) {}
}
