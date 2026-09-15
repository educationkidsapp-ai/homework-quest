package quest.server.auth;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import quest.server.config.Json;

/** Every administrative act that is not visible in the data itself lands in `audit_log` (§5). */
@Service
public class AuditService {
    private final AuditLogRepository log; private final Json json;
    public AuditService(AuditLogRepository log, Json json) { this.log = log; this.json = json; }

    public Entities.AuditLogEntity record(String actorUserId, String action, String targetType, String targetId, String schoolId, Object details) {
        var row = new Entities.AuditLogEntity();
        row.setId(UUID.randomUUID().toString());
        row.setActorUserId(actorUserId); row.setAction(action); row.setTargetType(targetType); row.setTargetId(targetId);
        row.setSchoolId(schoolId); row.setDetailsJson(details == null ? "{}" : json.write(details)); row.setCreatedAt(Instant.now());
        return log.save(row);
    }
}
