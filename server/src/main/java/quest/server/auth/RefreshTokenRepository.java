package quest.server.auth;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenRepository extends JpaRepository<Entities.RefreshTokenEntity, String> {
    Optional<Entities.RefreshTokenEntity> findByTokenHash(String tokenHash);
    List<Entities.RefreshTokenEntity> findByUserIdAndRevokedAtIsNull(String userId);
}
