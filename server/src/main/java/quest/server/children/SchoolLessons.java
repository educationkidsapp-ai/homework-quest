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
    public SchoolLessons(ClassRepository classes, LessonRepository lessons) { this.classes = classes; this.lessons = lessons; }

    public List<LessonEntity> publishedFor(Entities.ChildEntity child) {
        var classIds = classIdsFor(child);
        return classIds.isEmpty() ? List.of() : lessons.findByClassIdInAndStatusOrderByDateAsc(classIds, "published");
    }

    private List<String> classIdsFor(Entities.ChildEntity child) {
        if (child.getClassId() != null) return List.of(child.getClassId());
        return classes.findBySchoolIdAndCurriculumAndGradeAndNameIsNotNullOrderByNameAsc(
                child.getSchoolId(), child.getCurriculum(), child.getGrade()).stream().map(ClassEntity::getId).toList();
    }
}
