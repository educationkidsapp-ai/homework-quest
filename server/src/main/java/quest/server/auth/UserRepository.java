package quest.server.auth;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** `JpaSpecificationExecutor` so the dashboard users list filters in the query instead of loading the table. */
public interface UserRepository extends JpaRepository<Entities.UserEntity, String>, JpaSpecificationExecutor<Entities.UserEntity> {
    Optional<Entities.UserEntity> findByEmailIgnoreCase(String email);
    List<Entities.UserEntity> findBySchoolId(String schoolId);
    List<Entities.UserEntity> findBySchoolIdAndRole(String schoolId, String role);
}
