package quest.server.flags;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** A school's flag overrides. Every method names its `school_id`; see {@link Entities.SchoolFeatureFlagEntity}. */
public interface SchoolFlagRepository extends JpaRepository<Entities.SchoolFeatureFlagEntity, Entities.SchoolFlagId> {
    List<Entities.SchoolFeatureFlagEntity> findBySchoolId(String schoolId);

    /** The one row a flag check is about; `findById` would need the composite key assembled first. */
    @Query("select f from SchoolFeatureFlagEntity f where f.schoolId = :schoolId and f.flagKey = :flagKey")
    Optional<Entities.SchoolFeatureFlagEntity> findOne(@Param("schoolId") String schoolId, @Param("flagKey") String flagKey);

    /**
     * §4's "enable for all / disable for all", half one: every school that already has a row for this flag, in one
     * statement instead of a read-modify-write per tenant. Bulk JPQL bypasses the persistence context, so
     * {@link FlagService#setForAll} clears it afterwards.
     */
    @Modifying
    @Query("update SchoolFeatureFlagEntity f set f.enabled = :enabled, f.updatedBy = :actor, f.updatedAt = :now where f.flagKey = :flagKey")
    int updateEverySchool(@Param("flagKey") String flagKey, @Param("enabled") boolean enabled,
                          @Param("actor") String actor, @Param("now") Instant now);

    /**
     * Half two: a row for every school that had none, in one statement. Native because JPQL has no INSERT … SELECT;
     * the `NOT EXISTS` shape and the casts are the ones `V5__flags_themes.sql` already uses, and are valid on
     * PostgreSQL 16 and on H2 in PostgreSQL mode. The casts type the parameters explicitly so a null `actor` does
     * not leave the driver guessing.
     */
    @Modifying
    @Query(value = """
            INSERT INTO school_feature_flags (school_id, flag_key, enabled, updated_by, updated_at)
            SELECT s.id, CAST(:flagKey AS VARCHAR), CAST(:enabled AS BOOLEAN), CAST(:actor AS VARCHAR), CAST(:now AS TIMESTAMP)
            FROM schools s
            WHERE NOT EXISTS (SELECT 1 FROM school_feature_flags f WHERE f.school_id = s.id AND f.flag_key = CAST(:flagKey AS VARCHAR))
            """, nativeQuery = true)
    int insertMissingSchools(@Param("flagKey") String flagKey, @Param("enabled") boolean enabled,
                             @Param("actor") String actor, @Param("now") Instant now);
}
