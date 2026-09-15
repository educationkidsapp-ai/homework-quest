package quest.server.auth;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InviteRepository extends JpaRepository<Entities.InviteEntity, String> {
    Optional<Entities.InviteEntity> findByTokenHash(String tokenHash);
    List<Entities.InviteEntity> findBySchoolIdAndAcceptedAtIsNull(String schoolId);
}
