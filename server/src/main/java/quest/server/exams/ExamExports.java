package quest.server.exams;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import quest.server.grading.GradingExports;

/**
 * §8's "export CSV/XLSX" for one exam, written from exactly the same rows in both formats — {@link GradingExports}
 * says at length why one row builder serves two writers, and this reuses its writers rather than its layout.
 *
 * <p>Two blocks, in the order a teacher reads them: the children, then the per-question difficulty. A workbook gets
 * them on two sheets and the CSV on one, separated by a blank line, because a CSV has nowhere else to put them.
 */
@Component
public class ExamExports {

    public byte[] csv(ExamDto.ExamResults results) {
        var rows = new ArrayList<List<Object>>(childRows(results));
        rows.add(List.of());
        rows.addAll(questionRows(results));
        return GradingExports.csv(rows);
    }

    public byte[] xlsx(ExamDto.ExamResults results) {
        // One sheet, two blocks: the workbook writer takes a single grid, and a teacher filtering the children's
        // block does not want the questions to travel with it — the blank row is what separates them in Excel too.
        var rows = new ArrayList<List<Object>>(childRows(results));
        rows.add(List.of());
        rows.addAll(questionRows(results));
        return GradingExports.xlsx("Exam results", rows);
    }

    /** Header, then one row per child — absent children included, because the absent list is half the point. */
    private static List<List<Object>> childRows(ExamDto.ExamResults results) {
        var rows = new ArrayList<List<Object>>();
        rows.add(List.of("Child", "State", "Stars", "Out of", "Percent", "Band", "Time taken", "Submitted",
                "Needs marking", "Re-opened", "Comment"));
        for (var child : results.children()) {
            var row = new ArrayList<Object>();
            row.add(child.name()); row.add(child.state());
            row.add(child.score()); row.add(child.maxScore()); row.add(child.percent()); row.add(child.band());
            row.add(child.secondsTaken() == null ? null : duration(child.secondsTaken()));
            row.add(child.submittedAt() == null ? null : java.time.Instant.ofEpochMilli(child.submittedAt()).toString());
            row.add(child.needsMarking()); row.add(child.reopened() ? "yes" : "no"); row.add(child.comment());
            rows.add(row);
        }
        var summary = new ArrayList<Object>();
        summary.add("Class average"); summary.add(null); summary.add(null); summary.add(null);
        summary.add(results.classAverage());
        summary.add(results.classAverage() == null ? null : quest.server.grading.Bands.band(results.classAverage()));
        summary.add(null); summary.add(null); summary.add(results.needsMarking()); summary.add(null); summary.add(null);
        rows.add(summary);
        return rows;
    }

    private static List<List<Object>> questionRows(ExamDto.ExamResults results) {
        var rows = new ArrayList<List<Object>>();
        rows.add(List.of("Question", "Type", "Open", "Answered", "Correct", "Missed %", "Average stars"));
        for (var q : results.questions())
            rows.add(java.util.Arrays.asList(q.title(), q.type(), q.open() ? "yes" : "no", q.answered(), q.correct(),
                    q.missedPercent(), q.averageStars()));
        return rows;
    }

    /** `12m 04s` — a teacher reads a sitting in minutes, and a spreadsheet must not turn it into a date. */
    static String duration(int seconds) {
        return (seconds / 60) + "m " + String.format(java.util.Locale.ROOT, "%02d", seconds % 60) + "s";
    }
}
