package quest.server.chat;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * V49 (feature 27): the triages of a page of complaints, in one statement rather than one per row — what
 * {@link ComplaintService#rows} already does for children, sections, staff and last messages.
 *
 * <p>Transactional for the reason every repository of a tenant entity is (`TenantArchitectureTest`): the `school`
 * filter is enabled per transaction, and a derived query that opened its own would run with it off.
 */
@Transactional(readOnly = true)
public interface ComplaintTriageRepository extends JpaRepository<Entities.ComplaintTriageEntity, String> {
    List<Entities.ComplaintTriageEntity> findByComplaintIdIn(Collection<String> complaintIds);
}
