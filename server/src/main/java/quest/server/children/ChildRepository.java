package quest.server.children;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChildRepository extends JpaRepository<Entities.ChildEntity, String> {
    List<Entities.ChildEntity> findByParentIdAndDeletedAtIsNullOrderByCreatedAt(String parentId);
    long countByCurriculumAndGradeAndDeletedAtIsNull(String curriculum, int grade);
}
