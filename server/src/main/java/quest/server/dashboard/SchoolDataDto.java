package quest.server.dashboard;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * The Java mirror of the School page's Classes, Usage and Billing tabs (§6 screen 5), the Managerial School usage and
 * Teachers screens (§6 screens 19–20) and Admin's Platform usage & cost (§6 screen 10).
 */
public final class SchoolDataDto {
    private SchoolDataDto() {}

    /** Per school: (curriculum, grade, subject) with the teacher who owns it. */
    public record SchoolClass(String id, String schoolId, String curriculum, int grade, String subject,
                              String teacherId, String teacherName, long createdAt) {}

    public record CreateClassRequest(@NotBlank String curriculum, int grade, @NotBlank String subject, String teacherId) {}

    /**
     * Who teaches the class. A null `teacherId` alone means "leave it as it is"; `clearTeacher` is how the class is
     * handed back to nobody, because JSON cannot tell an absent field from a null one here.
     */
    public record UpdateClassRequest(String teacherId, boolean clearTeacher) {}

    /** A day of the plays series; `date` is ISO `yyyy-MM-dd`. */
    public record DayCount(String date, int count) {}

    /** A week of the publishing series; `week` is the ISO Monday of that week, `yyyy-MM-dd`. */
    public record WeekCount(String week, int count) {}

    /** How steadily one teacher publishes: `weeksWithALesson` out of `weeks` in the window (§6 screen 19). */
    public record TeacherConsistency(String teacherId, String displayName, int lessonsPublished, int weeks,
                                     int weeksWithALesson, Long lastPublishedAt) {}

    /** `GET /admin/schools/{id}/usage` and `GET /school/usage`. */
    public record SchoolUsage(String schoolId, String schoolName, String from, String to, int children,
                              int activeFamilies, List<DayCount> playsPerDay, List<WeekCount> lessonsPublishedPerWeek,
                              List<TeacherConsistency> teacherConsistency) {}

    /** One month of a school's model bill; `month` is `yyyy-MM`. */
    public record MonthCost(String month, long tokens, long tokensSaved, double costUsd) {}

    /**
     * `GET /admin/schools/{id}/billing`. `pricePer1kTokens` travels with the answer because it is an assumption
     * (`quest.llm.price-per-1k-tokens`, see `QuestProperties.Llm`), not a figure off an invoice, and the Billing tab
     * has to be able to show what it was computed with.
     */
    public record SchoolBilling(String schoolId, String schoolName, String currency, double pricePer1kTokens,
                                List<MonthCost> months, long totalTokens, double totalCostUsd) {}

    /** One school's share of the platform's model bill (§6 screen 10). */
    public record SchoolCost(String schoolId, String schoolName, long tokens, long tokensSaved, double costUsd, int lessons) {}

    /**
     * `GET /admin/usage/platform`. `aiCalls` is how many analyses and generations actually reached the model — every
     * cache row is one call that was paid for — and `cacheHits` how often a later lesson reused one, so
     * `cacheHitRate` is `hits / (hits + calls)`: the §6 "> 90 %" target.
     */
    public record PlatformUsage(String from, String to, int schools, int children, List<DayCount> playsPerDay,
                                long aiCalls, long cacheHits, double cacheHitRate, double pricePer1kTokens,
                                List<SchoolCost> costPerSchool, double totalCostUsd) {}

    /** §6 screen 20: a teacher of the school, read-only, with the classes she owns. */
    public record TeacherSummary(String userId, String email, String displayName, String photoUrl, String status,
                                 List<String> subjects, String curriculum, List<Integer> grades,
                                 List<SchoolClass> classes, int lessonsPublished, Long lastPublishedAt) {}
}
