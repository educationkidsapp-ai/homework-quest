package quest.server.flags;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** The flip trail (§4 "an audit log of who flipped what and when"), newest first. */
public interface FlagAuditRepository extends JpaRepository<Entities.FlagAuditEntity, String> {
    List<Entities.FlagAuditEntity> findAllByOrderByCreatedAtDescIdDesc(Pageable page);
}
