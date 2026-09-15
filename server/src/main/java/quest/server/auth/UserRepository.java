package quest.server.auth;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * `JpaSpecificationExecutor` so the dashboard users list filters in the query instead of loading the table.
 *
 * <p>Every method is transactional so that the `school` Hibernate filter is on for all of them: Spring Data makes only
 * the `CrudRepository` methods transactional by default, while derived and `@Query` methods run with a session of
 * their own and would otherwise never reach {@link quest.server.tenancy.TenantTransactionManager}. Writes keep their
 * own read-write attribute from `SimpleJpaRepository`. `TenantArchitectureTest` fails if a repository of a tenant
 * entity loses this annotation.
 */
@Transactional(readOnly = true)
public interface UserRepository extends JpaRepository<Entities.UserEntity, String>, JpaSpecificationExecutor<Entities.UserEntity> {
    Optional<Entities.UserEntity> findByEmailIgnoreCase(String email);
    List<Entities.UserEntity> findBySchoolId(String schoolId);
    List<Entities.UserEntity> findBySchoolIdAndRole(String schoolId, String role);

    /**
     * The id behind an address whatever school it belongs to — `email` is unique across the platform, so an invite has
     * to see a row in another school to refuse it instead of colliding with the unique index. Native on purpose:
     * Hibernate filters do not touch native queries, and it returns an id rather than a row, so nothing about the
     * other school's user leaves the query.
     */
    @Query(value = "SELECT id FROM users WHERE lower(email) = lower(:email)", nativeQuery = true)
    Optional<String> findIdByEmailAcrossSchools(@Param("email") String email);
}
