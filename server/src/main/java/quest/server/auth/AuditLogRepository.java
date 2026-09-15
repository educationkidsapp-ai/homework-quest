package quest.server.auth;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<Entities.AuditLogEntity, String> {
    List<Entities.AuditLogEntity> findBySchoolIdOrderByCreatedAtDesc(String schoolId);
}
