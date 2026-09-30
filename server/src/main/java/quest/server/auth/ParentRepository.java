package quest.server.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParentRepository extends JpaRepository<Entities.ParentEntity, String> {
    Optional<Entities.ParentEntity> findByFirebaseUid(String uid);

    /**
     * MA1: the row for an address, if the server already has one. `parents` is not a tenant table and carries no
     * `school_id` — which school a parent belongs to is her children's — so this looks across every school on purpose
     * and `ChildAdmissionService` is what refuses to reuse a parent of a different one.
     */
    Optional<Entities.ParentEntity> findFirstByEmailIgnoreCase(String email);
}
