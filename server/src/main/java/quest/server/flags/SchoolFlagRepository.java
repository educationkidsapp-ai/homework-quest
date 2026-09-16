package quest.server.flags;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** A school's flag overrides. Every method names its `school_id`; see {@link Entities.SchoolFeatureFlagEntity}. */
public interface SchoolFlagRepository extends JpaRepository<Entities.SchoolFeatureFlagEntity, Entities.SchoolFlagId> {
    List<Entities.SchoolFeatureFlagEntity> findBySchoolId(String schoolId);

    /** The one row a flag check is about; `findById` would need the composite key assembled first. */
    @Query("select f from SchoolFeatureFlagEntity f where f.schoolId = :schoolId and f.flagKey = :flagKey")
    Optional<Entities.SchoolFeatureFlagEntity> findOne(@Param("schoolId") String schoolId, @Param("flagKey") String flagKey);
}
