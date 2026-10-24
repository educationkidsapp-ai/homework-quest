package quest.server.structure;

import java.util.List;
import org.springframework.stereotype.Component;
import quest.server.grading.GradingService;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.ClassEntity;

/**
 * Feature 36's guardrail: <strong>"a change that affects released results … shows how many results will show a
 * different band and asks for confirmation."</strong>
 *
 * <p>A released result is a score on a lesson with `released_at` set — the one-way door §7 puts in front of a parent,
 * and the same toggle on an exam. {@link GradingService#releasedScores} computes them with §7's own arithmetic, so
 * the number named in the 409 is the number the gradebook will show afterwards; {@link #rebanded} then counts how
 * many of those scores fall in a different band under the new scheme than under the old one. Nothing is recomputed
 * and no score is ever changed — only the band it is shown in.
 */
@Component
public class ReleasedResults {

    /** `released` is every released score in scope; `rebanded` is how many of them would read differently. */
    public record Count(int released, int rebanded) {
        public boolean any() { return rebanded > 0; }
    }

    private final GradingService grading; private final ClassRepository classes;

    public ReleasedResults(GradingService grading, ClassRepository classes) {
        this.grading = grading; this.classes = classes;
    }

    /** One section's released scores, read under two schemes. */
    public Count of(ClassEntity section, GradingSchemes.Scheme from, GradingSchemes.Scheme to) {
        return count(grading.releasedScores(section.getId()), from, to);
    }

    /** Every section of one academy — what a scheme change touches, since the scheme is per academy. */
    public Count ofAcademy(String schoolId, String academyKey, GradingSchemes.Scheme from, GradingSchemes.Scheme to) {
        var scores = new java.util.ArrayList<Double>();
        for (var section : classes.findBySchoolIdAndNameIsNotNullOrderByCurriculumAscGradeAscNameAsc(schoolId))
            if (academyKey.equals(section.getCurriculum())) scores.addAll(grading.releasedScores(section.getId()));
        return count(scores, from, to);
    }

    private static Count count(List<Double> scores, GradingSchemes.Scheme from, GradingSchemes.Scheme to) {
        int moved = 0;
        for (double score : scores)
            if (!from.bandFor(score).nameEn().equals(to.bandFor(score).nameEn())) moved++;
        return new Count(scores.size(), moved);
    }

    /** The 409 the Admin confirms away with `confirm=true`, naming the count the screen shows her. */
    public static quest.server.config.ApiException confirmReleased(Count count) {
        return quest.server.config.ApiException.conflict("confirm_released",
                count.rebanded() + " of " + count.released() + " released results will be shown in a different band. "
                        + "Send confirm=true to go ahead.");
    }
}
