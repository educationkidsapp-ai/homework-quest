package quest.server.children;

import java.util.List;
import org.springframework.stereotype.Service;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.LessonRepository;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.ClassEntity;

/**
 * §2: "A child belongs to a school + curriculum + grade; the map merges every published lesson from every Class
 * matching those three." Any subject, any teacher — and nothing from another school, because the classes are looked
 * up by the child's `school_id` and the lessons by those class ids.
 *
 * <p>Two queries, whatever the number of classes or lessons: one for the classes, one for the lessons.
 */
@Service
public class SchoolLessons {
    private final ClassRepository classes; private final LessonRepository lessons;
    public SchoolLessons(ClassRepository classes, LessonRepository lessons) { this.classes = classes; this.lessons = lessons; }

    public List<LessonEntity> publishedFor(Entities.ChildEntity child) {
        var classIds = classes.findBySchoolIdAndCurriculumAndGrade(child.getSchoolId(), child.getCurriculum(), child.getGrade()).stream().map(ClassEntity::getId).toList();
        return classIds.isEmpty() ? List.of() : lessons.findByClassIdInAndStatusOrderByDateAsc(classIds, "published");
    }
}
