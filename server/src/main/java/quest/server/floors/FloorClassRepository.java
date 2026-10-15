package quest.server.floors;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/** `floor_classes` — which sections are on which floor. See {@link FloorRepository} for why every method is scoped. */
@Transactional(readOnly = true)
public interface FloorClassRepository extends JpaRepository<Entities.FloorClassEntity, String> {
    List<Entities.FloorClassEntity> findBySchoolIdOrderBySectionIdAsc(String schoolId);
    List<Entities.FloorClassEntity> findBySchoolIdAndFloorIdOrderBySectionIdAsc(String schoolId, String floorId);
    List<Entities.FloorClassEntity> findBySchoolIdAndFloorIdInOrderBySectionIdAsc(String schoolId, List<String> floorIds);

    /** Who already holds this section: the one row the unique index allows, and the 409 a second floor gets. */
    Optional<Entities.FloorClassEntity> findBySchoolIdAndSectionId(String schoolId, String sectionId);
}
