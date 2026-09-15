package quest.server.auth;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<Entities.UserEntity, String> {
    Optional<Entities.UserEntity> findByEmailIgnoreCase(String email);
    List<Entities.UserEntity> findBySchoolId(String schoolId);
    List<Entities.UserEntity> findBySchoolIdAndRole(String schoolId, String role);
}
