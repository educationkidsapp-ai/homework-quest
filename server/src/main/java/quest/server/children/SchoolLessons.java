package quest.server.children;

import java.util.List;
import org.springframework.stereotype.Service;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.LessonRepository;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.ClassEntity;

/**
 * Which published lessons a child's map is built from — every subject, every teacher, and nothing of another school.
 *
 * <p><strong>Since V7 a child sits in a section</strong> (D14): `children.class_id` names her class and the map is
 * that class's published lessons. The pre-V7 rule — §2's "every Class of her school with her curriculum and grade" —
 * is kept as the fallback for a child who has no section yet: a parent who joined with a school code rather than a
 * class join code, or a school whose sections have not been created. That fallback now looks at sections only, so a
 * pre-V7 leftover row (`name IS NULL`) never contributes a lesson twice.
 *
 * <p>One or two queries, whatever the number of classes or lessons; never one per class.
 */
@Service
public class SchoolLessons {
    private final ClassRepository classes; private final LessonRepository lessons;
    private final quest.server.exams.ExamSettingsRepository examSettings;
    private final quest.server.exams.ExamAttemptRepository examSittings;

    public SchoolLessons(ClassRepository classes, LessonRepository lessons,
                         quest.server.exams.ExamSettingsRepository examSettings,
                         quest.server.exams.ExamAttemptRepository examSittings) {
        this.classes = classes; this.lessons = lessons; this.examSettings = examSettings; this.examSittings = examSittings;
    }

    public List<LessonEntity> publishedFor(Entities.ChildEntity child) {
        var classIds = classIdsFor(child);
        return classIds.isEmpty() ? List.of() : lessons.findByClassIdInAndStatusOrderByDateAsc(classIds, "published");
    }

    /**
     * N4.3 (§8): what a child's <strong>map</strong> is built from — {@link #publishedFor} with every exam whose
     * window is not open right now left out, because §8 says the exam island "appears on the map … only between
     * open and close".
     *
     * <p>Separate from {@link #publishedFor} rather than replacing it: her parent's progress report is built from
     * the same method, and an exam she sat last week must keep its released score there long after the island has
     * gone. Hiding an island and withdrawing a result are different things.
     *
     * <p>A child the teacher has re-opened the exam for keeps her island past the close — that is what a re-opening
     * is for. Two extra statements, both only when the class has an exam in it at all.
     */
    public Windows visibleFor(Entities.ChildEntity child, java.time.Instant now) {
        var published = publishedFor(child);
        var examIds = published.stream().filter(quest.server.exams.ExamPlays::isExam).map(LessonEntity::getId).toList();
        if (examIds.isEmpty()) return new Windows(published, java.util.Map.of());
        var settings = new java.util.LinkedHashMap<String, quest.server.exams.Entities.ExamSettingsEntity>();
        for (var s : examSettings.findByLessonIdIn(examIds)) settings.put(s.getLessonId(), s);
        var sittings = new java.util.HashMap<String, quest.server.exams.Entities.ExamAttemptEntity>();
        for (var a : examSittings.findByChildIdAndLessonIdIn(child.getId(), examIds)) sittings.put(a.getLessonId(), a);
        var open = new java.util.LinkedHashMap<String, quest.server.exams.Entities.ExamSettingsEntity>();
        var visible = new java.util.ArrayList<LessonEntity>(published.size());
        for (var lesson : published) {
            var exam = settings.get(lesson.getId());
            if (exam == null) { if (!quest.server.exams.ExamPlays.isExam(lesson)) visible.add(lesson); continue; }
            if (!quest.server.exams.ExamAttemptService.isOpenFor(exam, sittings.get(lesson.getId()), now)) continue;
            open.put(lesson.getId(), exam);
            visible.add(lesson);
        }
        return new Windows(List.copyOf(visible), java.util.Map.copyOf(open));
    }

    /** The lessons a map may show, and the windows of the exams among them — one read, both answers. */
    public record Windows(List<LessonEntity> lessons, java.util.Map<String, quest.server.exams.Entities.ExamSettingsEntity> examWindows) {}

    private List<String> classIdsFor(Entities.ChildEntity child) {
        if (child.getClassId() != null) return List.of(child.getClassId());
        return classes.findBySchoolIdAndCurriculumAndGradeAndNameIsNotNullOrderByNameAsc(
                child.getSchoolId(), child.getCurriculum(), child.getGrade()).stream().map(ClassEntity::getId).toList();
    }
}
