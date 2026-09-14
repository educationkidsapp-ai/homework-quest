package quest.server.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminUserRepository extends JpaRepository<Entities.AdminUserEntity, String> {
    Optional<Entities.AdminUserEntity> findByEmailIgnoreCase(String email);
}
