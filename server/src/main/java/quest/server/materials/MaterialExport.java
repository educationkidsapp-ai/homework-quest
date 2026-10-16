package quest.server.materials;

import java.util.ArrayList;
import java.util.List;
import quest.server.grading.GradingExports;

/**
 * Feature 31: the leaders' board as the file a leader keeps, written by the gradebook's own writer
 * ({@link GradingExports#csv}, with the UTF-8 BOM that keeps Arabic names readable in Excel). Headers carry both
 * languages, because the file outlives the screen's language.
 */
final class MaterialExport {
    private MaterialExport() {}

    static final List<Object> HEADER = List.of(
            "Teacher / المعلمة", "Class / الصف", "Subject / المادة", "Status / الحالة",
            "Handed in / وقت التسليم", "Note / ملاحظة", "Reviewed by / راجعها", "Review comment / تعليق المراجعة");

    static byte[] csv(MaterialDto.MaterialBoard board) {
        var rows = new ArrayList<List<Object>>();
        rows.add(HEADER);
        for (var teacher : board.rows())
            for (var row : teacher.rows())
                rows.add(List.of(teacher.fullName(), row.className(), row.subject(), row.status(),
                        or(row.submittedAt()), or(row.note()), or(row.reviewedByName()), or(row.reviewComment())));
        return GradingExports.csv(rows);
    }

    static String filename(MaterialDto.MaterialBoard board) { return "teaching-materials-" + board.week() + ".csv"; }

    private static String or(String value) { return value == null ? "" : value; }
}
