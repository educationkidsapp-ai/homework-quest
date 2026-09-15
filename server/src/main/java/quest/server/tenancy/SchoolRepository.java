package quest.server.tenancy;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SchoolRepository extends JpaRepository<Entities.SchoolEntity, String> {
    Optional<Entities.SchoolEntity> findByCodeIgnoreCase(String code);
}
