package quest.server.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParentRepository extends JpaRepository<Entities.ParentEntity, String> {
    Optional<Entities.ParentEntity> findByFirebaseUid(String uid);
}
