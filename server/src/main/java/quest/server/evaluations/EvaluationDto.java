package quest.server.evaluations;

import java.util.List;

/**
 * The Java mirror of `quest.api.dashboard.ChildEvaluations.kt` (feature 33), for the staff side. The parent's side is
 * the shared Kotlin {@link quest.api.dto.ParentEvaluation} itself, encoded with the kotlinx codec — it is a
 * different shape on purpose, carrying words where this one carries numbers.
 *
 * <p>`opensOn` is `yyyy-MM-dd` in the school's zone, every other time is an ISO-8601 instant, and `timeZone` says
 * which zone a client renders them in.
 */
public final class EvaluationDto {
    private EvaluationDto() {}

    /** The workflow: what the teacher and the leader move the row through. A column, unlike the status below. */
    public static final String DRAFT = "DRAFT", SUBMITTED = "SUBMITTED", RETURNED = "RETURNED", APPROVED = "APPROVED";

    /** The compliance status, computed from the round's deadline and the review — never stored. */
    public static final String ON_TIME = "ON_TIME", LATE = "LATE", MISSING = "MISSING";

    /** The four criteria, in the order every screen reads them. */
    public static final String PARTICIPATION = "participation", EFFORT = "effort", BEHAVIOUR = "behaviour",
            UNDERSTANDING = "understanding";
    public static final List<String> CRITERIA = List.of(PARTICIPATION, EFFORT, BEHAVIOUR, UNDERSTANDING);

    /** The four-point scale as the word key a client translates: 4 … 1. The parent is only ever given these. */
    public static final String EXCELLENT = "excellent", GOOD = "good", DEVELOPING = "developing",
            NEEDS_SUPPORT = "needsSupport";

    public static final String APPROVE_DECISION = "APPROVE", RETURN_DECISION = "RETURN";

    public record EvaluationRound(String id, String nameEn, String nameAr, String opensOn, String dueAt,
                                  int remindHoursBefore, String timeZone, boolean released, String releasedAt,
                                  String releasedBy, String releasedByName) {}

    public record CreateEvaluationRoundRequest(String nameEn, String nameAr, String opensOn, String dueAt,
                                               Integer remindHoursBefore) {}

    public record UpdateEvaluationRoundRequest(String nameEn, String nameAr, String opensOn, String dueAt,
                                               Integer remindHoursBefore) {}

    public record ChildEvaluationRow(String evaluationId, String roundId, String classId, String className,
                                     String subject, String childId, String childName, String status, String state,
                                     Integer participation, Integer effort, Integer behaviour, Integer understanding,
                                     String comment, String goingWell, String nextStep, String submittedAt,
                                     String reviewedBy, String reviewedByName, String reviewedAt, String reviewComment) {}

    /** The five cards; each is the number of rows its filter shows. */
    public record EvaluationCounts(int onTime, int late, int missing, int returned, int approved) {}

    /** `GET /teacher/evaluations?round=`. */
    public record TeacherEvaluations(EvaluationRound round, List<EvaluationRound> rounds, EvaluationCounts counts,
                                     List<ChildEvaluationRow> rows) {}

    /** `PUT /teacher/evaluations/{roundId}/{classId}/{subject}/{childId}`. */
    public record SaveChildEvaluationRequest(Integer participation, Integer effort, Integer behaviour,
                                             Integer understanding, String comment, String goingWell, String nextStep) {}

    /** One teacher on the leaders' board; `rows` is empty on the board itself and filled by one teacher's page. */
    public record EvaluationBoardRow(String teacherId, String fullName, EvaluationCounts counts,
                                     List<ChildEvaluationRow> rows) {}

    /** `GET /evaluations/board?round=` and `/evaluations/board/{teacherId}`. `scope` is feature 17's. */
    public record EvaluationBoard(EvaluationRound round, List<EvaluationRound> rounds, String scope,
                                  EvaluationCounts counts, List<EvaluationBoardRow> rows) {}

    /** `POST /evaluations/{id}/review`: `decision` is APPROVE or RETURN. */
    public record ReviewEvaluationRequest(String decision, String comment) {}
}
