package quest.server.evaluations;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional for the reason every repository of a tenant entity is: a derived query outside a transaction never
 * reaches {@link quest.server.tenancy.TenantTransactionManager}, where the `school` filter is switched on
 * (`TenantArchitectureTest`). Every query also names the school, because the reminder job has no request to take
 * one from — {@link #findByIdOnly} is the one exception and is the parent's, who carries no tenant scope at all and
 * has already proved the child whose round this is.
 */
@Transactional(readOnly = true)
public interface EvaluationRoundRepository extends JpaRepository<Entities.EvaluationRoundEntity, String> {
    List<Entities.EvaluationRoundEntity> findBySchoolIdOrderByDueAtDesc(String schoolId);

    Optional<Entities.EvaluationRoundEntity> findOneBySchoolIdAndId(String schoolId, String id);

    /** The rounds one school has released, newest deadline first — nothing a parent reads comes from another. */
    List<Entities.EvaluationRoundEntity> findBySchoolIdAndReleasedAtIsNotNullOrderByDueAtDesc(String schoolId);

    /** One round by id alone, for the parent's side: she has no tenant scope, and the id comes from her own rows. */
    Optional<Entities.EvaluationRoundEntity> findOneById(String id);
}
