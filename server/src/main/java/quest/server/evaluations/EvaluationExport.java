package quest.server.evaluations;

import java.util.ArrayList;
import java.util.List;
import quest.server.grading.GradingExports;

/**
 * Feature 33: the leaders' board as the file a leader keeps, written by the gradebook's own writer
 * ({@link GradingExports#csv}, with the UTF-8 BOM that keeps Arabic names readable in Excel). Headers carry both
 * languages, because the file outlives the screen's language.
 *
 * <p><strong>Counts only.</strong> What a teacher wrote about a child is not tracking data: the file says who is on
 * time, late, missing, returned and approved, and it carries no rating and no comment at all — which is the spec's
 * "a CSV of the counts (no ratings, no comments)".
 */
final class EvaluationExport {
    private EvaluationExport() {}

    static final List<Object> HEADER = List.of(
            "Teacher / المعلمة", "On time / في الوقت", "Late / متأخر", "Missing / غير مكتمل",
            "Returned / أُعيد", "Approved / معتمد");

    static byte[] csv(EvaluationDto.EvaluationBoard board) {
        var rows = new ArrayList<List<Object>>();
        rows.add(HEADER);
        for (var teacher : board.rows()) {
            var c = teacher.counts();
            rows.add(List.of(teacher.fullName(), c.onTime(), c.late(), c.missing(), c.returned(), c.approved()));
        }
        var total = board.counts();
        rows.add(List.of("Total / الإجمالي", total.onTime(), total.late(), total.missing(), total.returned(), total.approved()));
        return GradingExports.csv(rows);
    }

    static String filename(EvaluationDto.EvaluationBoard board) {
        String round = board.round() == null ? "round" : board.round().id();
        return "child-evaluations-" + round + ".csv";
    }
}
