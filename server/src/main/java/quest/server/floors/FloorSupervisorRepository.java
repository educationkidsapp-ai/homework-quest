package quest.server.floors;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/** `floor_supervisors` — who holds which floor. See {@link FloorRepository} for why every method is scoped. */
@Transactional(readOnly = true)
public interface FloorSupervisorRepository extends JpaRepository<Entities.FloorSupervisorEntity, String> {
    List<Entities.FloorSupervisorEntity> findBySchoolIdOrderByFloorIdAsc(String schoolId);
    List<Entities.FloorSupervisorEntity> findBySchoolIdAndFloorIdOrderByUserIdAsc(String schoolId, String floorId);

    /** Her floors — the first statement of every `/floor/**` read, and of her academies in feature 14's resolver. */
    List<Entities.FloorSupervisorEntity> findBySchoolIdAndUserIdOrderByFloorIdAsc(String schoolId, String userId);

    List<Entities.FloorSupervisorEntity> findBySchoolIdAndUserIdInOrderByFloorIdAsc(String schoolId, List<String> userIds);
}
